package com.aiwyw;

import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.net.ssl.*;
import org.springframework.stereotype.Service;

/** 真实本地适配器及有明确成功字段的HTTPS JSON代理。禁止重定向，DNS检查后固定连接IP，保留TLS主机校验。 */
@Service
public class ApiUpstream {
  private final ObjectMapper json;
  private final AtomicInteger active = new AtomicInteger();
  private final ThreadPoolExecutor executor =
      new ThreadPoolExecutor(
          0,
          64,
          60,
          TimeUnit.SECONDS,
          new SynchronousQueue<>(),
          r -> {
            Thread t = new Thread(r, "api-upstream");
            t.setDaemon(true);
            return t;
          },
          new ThreadPoolExecutor.AbortPolicy());

  private static class Exchange {
    final AtomicBoolean cancelled = new AtomicBoolean();
    final AtomicReference<Socket> socket = new AtomicReference<>();
  }

  private final ThreadLocal<Exchange> current = new ThreadLocal<>();

  /** 包括DNS和写请求的整体超时；超时主动关闭套接字，DNS不可中断时工作线程仍受池上限保护。 */
  public JsonNode bounded(
      Map<String, Object> definition,
      JsonNode input,
      int seconds,
      int limit,
      SecureStore crypto,
      int maximum)
      throws Exception {
    if (active.incrementAndGet() > maximum) {
      active.decrementAndGet();
      throw new Api.Failure(503, "API上游并发已满，未计费");
    }
    Exchange exchange = new Exchange();
    Future<JsonNode> future = null;
    try {
      future =
          executor.submit(
              () -> {
                current.set(exchange);
                try {
                  return run(definition, input, seconds, limit, crypto);
                } finally {
                  current.remove();
                }
              });
      return future.get(seconds, TimeUnit.SECONDS);
    } catch (TimeoutException timeout) {
      throw new SocketTimeoutException();
    } catch (ExecutionException failure) {
      if (failure.getCause() instanceof Exception e) throw e;
      throw new IOException("Upstream failed");
    } catch (RejectedExecutionException full) {
      throw new Api.Failure(503, "API工作池繁忙，未计费");
    } finally {
      exchange.cancelled.set(true);
      Socket socket = exchange.socket.get();
      if (socket != null)
        try {
          socket.close();
        } catch (IOException ignored) {
        }
      if (future != null && !future.isDone()) future.cancel(true);
      active.decrementAndGet();
    }
  }

  @jakarta.annotation.PreDestroy
  void close() {
    executor.shutdownNow();
  }

  ApiUpstream(ObjectMapper json) {
    this.json = json;
  }

  static URI endpoint(String raw) {
    try {
      URI u = URI.create(raw);
      if (!"https".equals(u.getScheme())
          || u.getHost() == null
          || u.getUserInfo() != null
          || u.getFragment() != null
          || (u.getPort() != -1 && u.getPort() != 443)) throw new IllegalArgumentException();
      return u;
    } catch (Exception invalid) {
      throw new Api.Failure(400, "上游必须为不含账号片段的HTTPS地址，端口443");
    }
  }

  static boolean publicAddress(InetAddress a) {
    if (a.isAnyLocalAddress()
        || a.isLoopbackAddress()
        || a.isLinkLocalAddress()
        || a.isSiteLocalAddress()
        || a.isMulticastAddress()) return false;
    byte[] b = a.getAddress();
    int x = b[0] & 255;
    if (b.length == 16)
      return (x & 224) == 32
          && !((b[0] & 255) == 32 && (b[1] & 255) == 1 && (b[2] & 255) == 13 && (b[3] & 255) == 184)
          && !((b[0] & 255) == 32 && (b[1] & 255) == 2);
    int y = b[1] & 255;
    return x != 0
        && x != 10
        && x != 127
        && x < 224
        && !(x == 100 && y >= 64 && y <= 127)
        && !(x == 169 && y == 254)
        && !(x == 172 && y >= 16 && y <= 31)
        && !(x == 192 && (y == 168 || y == 0))
        && !(x == 198 && (y == 18 || y == 19));
  }

  public JsonNode run(
      Map<String, Object> definition,
      JsonNode input,
      int timeoutSeconds,
      int limit,
      SecureStore crypto)
      throws Exception {
    String adapter = (String) definition.get("adapter");
    if (!adapter.equals("HTTPS_JSON")) {
      if (!input.isObject() || !input.path("text").isTextual())
        throw new Api.Failure(400, "输入须为包含text字符串的JSON对象");
      String text = input.get("text").asText();
      if (adapter.equals("TEXT_STATS"))
        return json.valueToTree(
            Map.of(
                "characters",
                text.codePointCount(0, text.length()),
                "words",
                text.isBlank() ? 0 : text.trim().split("\\s+").length,
                "lines",
                text.isEmpty() ? 0 : text.split("\\R", -1).length));
      if (adapter.equals("SHA256"))
        return json.valueToTree(
            Map.of(
                "sha256",
                HexFormat.of()
                    .formatHex(
                        MessageDigest.getInstance("SHA-256")
                            .digest(text.getBytes(StandardCharsets.UTF_8)))));
      throw new Api.Failure(400, "未实现的API适配器");
    }
    String method =
        UpstreamRequest.method((String) definition.getOrDefault("request_method", "POST"));
    URI u = UpstreamRequest.target(endpoint((String) definition.get("endpoint")), method, input);
    var customHeaders =
        UpstreamRequest.headers(
            json,
            definition.get("headers_enc") == null
                ? ""
                : crypto.decrypt((String) definition.get("headers_enc")));
    InetAddress[] addresses = InetAddress.getAllByName(u.getHost());
    if (addresses.length == 0 || Arrays.stream(addresses).anyMatch(a -> !publicAddress(a)))
      throw new Api.Failure(502, "上游地址不符合公共网络访问策略");
    String secret =
        definition.get("secret_enc") == null
            ? ""
            : crypto.decrypt((String) definition.get("secret_enc"));
    if (secret.contains("\r") || secret.contains("\n")) throw new Api.Failure(502, "上游凭据格式不正确");
    byte[] response =
        exchange(
            u,
            addresses[0],
            method.equals("GET") ? new byte[0] : json.writeValueAsBytes(input),
            secret,
            timeoutSeconds,
            limit,
            method,
            customHeaders);
    JsonNode result = json.readTree(response);
    if (result == null
        || !result
            .at((String) definition.get("success_pointer"))
            .equals(json.readTree((String) definition.get("success_value"))))
      throw new Api.Failure(502, "上游未满足已配置成功条件，未计费");
    return result;
  }

  /** 整个交换设置绝对截止时间，每次读前缩短SO_TIMEOUT，避免慢速字节绕过超时。禁止压缩和无限响应。 */
  byte[] exchange(URI u, InetAddress pinned, byte[] body, String secret, int seconds, int limit)
      throws Exception {
    return exchange(u, pinned, body, secret, seconds, limit, "POST", Map.of());
  }

  byte[] exchange(
      URI u,
      InetAddress pinned,
      byte[] body,
      String secret,
      int seconds,
      int limit,
      String method,
      Map<String, String> customHeaders)
      throws Exception {
    long deadline = System.nanoTime() + seconds * 1_000_000_000L;
    try (Socket tcp = new Socket()) {
      Exchange exchange = current.get();
      if (exchange != null) {
        exchange.socket.set(tcp);
        if (exchange.cancelled.get()) throw new SocketTimeoutException();
      }
      tcp.connect(new InetSocketAddress(pinned, 443), seconds * 1000);
      tcp.setSoTimeout(remaining(deadline));
      try (SSLSocket ssl =
          (SSLSocket)
              ((SSLSocketFactory) SSLSocketFactory.getDefault())
                  .createSocket(tcp, u.getHost(), 443, true)) {
        SSLParameters p = ssl.getSSLParameters();
        p.setEndpointIdentificationAlgorithm("HTTPS");
        ssl.setSSLParameters(p);
        ssl.setSoTimeout(remaining(deadline));
        ssl.startHandshake();
        String target = u.getRawPath() == null || u.getRawPath().isEmpty() ? "/" : u.getRawPath();
        if (u.getRawQuery() != null) target += "?" + u.getRawQuery();
        String headers =
            method
                + " "
                + target
                + " HTTP/1.1\r\nHost: "
                + u.getHost()
                + "\r\n"
                + "Content-Type: application/json\r\n"
                + "Accept: application/json\r\n"
                + "Accept-Encoding: identity\r\n"
                + "Connection: close\r\n"
                + "Content-Length: "
                + body.length
                + "\r\n"
                + (secret.isEmpty() ? "" : "Authorization: Bearer " + secret + "\r\n")
                + customHeaders.entrySet().stream()
                    .map(e -> e.getKey() + ": " + e.getValue() + "\r\n")
                    .collect(java.util.stream.Collectors.joining())
                + "\r\n";
        ssl.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
        ssl.getOutputStream().write(body);
        ssl.getOutputStream().flush();
        InputStream in = ssl.getInputStream();
        String status = line(in, ssl, deadline, 4096);
        if (!status.matches("HTTP/1\\.[01] 2[0-9]{2} .*"))
          throw new Api.Failure(502, "上游HTTP失败或重定向，未计费");
        Map<String, String> fields = new HashMap<>();
        int total = 0;
        for (String l; (l = line(in, ssl, deadline, 8192)).length() > 0; ) {
          total += l.length();
          if (total > 32768) throw new IOException("Headers too large");
          int colon = l.indexOf(':');
          if (colon <= 0) throw new IOException("Invalid header");
          fields.put(l.substring(0, colon).toLowerCase(Locale.ROOT), l.substring(colon + 1).trim());
        }
        if (!fields.getOrDefault("content-encoding", "identity").equalsIgnoreCase("identity"))
          throw new IOException("Compressed response unsupported");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (fields.getOrDefault("transfer-encoding", "").equalsIgnoreCase("chunked")) {
          while (true) {
            String n = line(in, ssl, deadline, 100);
            long length = Long.parseLong(n.split(";", 2)[0], 16);
            if (length == 0) break;
            if (length < 0 || length > limit - out.size())
              throw new IOException("Response too large");
            copy(in, out, ssl, deadline, (int) length, limit);
            if (!line(in, ssl, deadline, 2).isEmpty()) throw new IOException("Invalid chunk");
          }
        } else if (fields.containsKey("content-length")) {
          long length = Long.parseLong(fields.get("content-length"));
          if (length < 0 || length > limit) throw new IOException("Response too large");
          copy(in, out, ssl, deadline, (int) length, limit);
        } else {
          byte[] buffer = new byte[8192];
          int n;
          while (true) {
            ssl.setSoTimeout(remaining(deadline));
            n = in.read(buffer);
            if (n < 0) break;
            if (out.size() + n > limit) throw new IOException("Response too large");
            out.write(buffer, 0, n);
          }
        }
        return out.toByteArray();
      }
    }
  }

  private static int remaining(long deadline) throws SocketTimeoutException {
    long ms = (deadline - System.nanoTime()) / 1_000_000L;
    if (ms <= 0) throw new SocketTimeoutException();
    return (int) Math.min(ms, Integer.MAX_VALUE);
  }

  private String line(InputStream in, SSLSocket socket, long deadline, int max) throws IOException {
    ByteArrayOutputStream b = new ByteArrayOutputStream();
    int c;
    while (true) {
      socket.setSoTimeout(remaining(deadline));
      c = in.read();
      if (c < 0) throw new EOFException();
      if (c == '\n') break;
      if (b.size() >= max) throw new IOException("Line too long");
      b.write(c);
    }
    return b.toString(StandardCharsets.US_ASCII).replaceAll("\r$", "");
  }

  private void copy(
      InputStream in,
      ByteArrayOutputStream out,
      SSLSocket socket,
      long deadline,
      int length,
      int limit)
      throws IOException {
    byte[] b = new byte[Math.min(8192, Math.max(1, length))];
    int rest = length;
    while (rest > 0) {
      socket.setSoTimeout(remaining(deadline));
      int n = in.read(b, 0, Math.min(rest, b.length));
      if (n < 0) throw new EOFException();
      if (out.size() + n > limit) throw new IOException("Response too large");
      out.write(b, 0, n);
      rest -= n;
    }
  }
}
