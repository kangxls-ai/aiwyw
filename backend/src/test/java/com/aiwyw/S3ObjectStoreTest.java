package com.aiwyw;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

/** 独立S3协议端点验证SDK实际PUT/HEAD/DELETE和预签名GET，不需要云账户，不放宽生产HTTPS校验。 */
class S3ObjectStoreTest {
  @Test
  void realSdkWritesSignsAndReadsWithoutExposingSecret() throws Exception {
    Map<String, byte[]> objects = new ConcurrentHashMap<>();
    List<String> authorization = Collections.synchronizedList(new ArrayList<>());
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          try {
            String path = exchange.getRequestURI().getPath(), method = exchange.getRequestMethod();
            String header = exchange.getRequestHeaders().getFirst("Authorization");
            if (header != null) authorization.add(header);
            if (method.equals("HEAD")) {
              exchange.sendResponseHeaders(200, -1);
            } else if (method.equals("PUT")) {
              objects.put(path, exchange.getRequestBody().readAllBytes());
              exchange.getResponseHeaders().set("ETag", "\"object-etag\"");
              exchange.sendResponseHeaders(200, -1);
            } else if (method.equals("GET")) {
              byte[] bytes = objects.get(path);
              if (bytes == null) exchange.sendResponseHeaders(404, -1);
              else {
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
              }
            } else if (method.equals("DELETE")) {
              objects.remove(path);
              exchange.sendResponseHeaders(204, -1);
            } else exchange.sendResponseHeaders(405, -1);
          } finally {
            exchange.close();
          }
        });
    server.start();
    Path file = Files.createTempFile("s3-protocol-", ".bin");
    byte[] bytes = "verified-object-content".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    Files.write(file, bytes);
    try {
      var config =
          new Storage.Connection(
              "S3",
              "",
              "http://127.0.0.1:" + server.getAddress().getPort(),
              "us-east-1",
              "private-bucket",
              "test-access",
              "secret-never-in-url",
              "",
              true);
      var adapter = new S3ObjectStore(config);
      adapter.check();
      adapter.put("folder/object.bin", file, "application/octet-stream", "test-sha");
      assertArrayEquals(bytes, objects.get("/private-bucket/folder/object.bin"));
      assertFalse(authorization.isEmpty());
      assertTrue(authorization.stream().allMatch(v -> v.startsWith("AWS4-HMAC-SHA256")));
      String url =
          adapter.download(
              "folder/object.bin",
              "attachment; filename=package.zip",
              "application/octet-stream",
              5,
              false);
      assertTrue(url.contains("X-Amz-Signature="));
      assertTrue(url.contains("X-Amz-Expires=300"));
      assertFalse(url.contains("secret-never-in-url"));
      var response =
          HttpClient.newHttpClient()
              .send(
                  HttpRequest.newBuilder(URI.create(url)).GET().build(),
                  HttpResponse.BodyHandlers.ofByteArray());
      assertArrayEquals(bytes, response.body());
      adapter.delete("folder/object.bin");
      assertTrue(objects.isEmpty());
    } finally {
      server.stop(0);
      Files.deleteIfExists(file);
    }
  }
}
