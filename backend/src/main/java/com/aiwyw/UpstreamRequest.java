package com.aiwyw;

import com.fasterxml.jackson.databind.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 平台入口始终POST JSON；只把管理员配置的字段交给固定上游，不透传平台Key、Cookie或客户端请求头。 */
final class UpstreamRequest {
  static String method(String value) {
    String method = value == null || value.isBlank() ? "POST" : value;
    if (!Set.of("GET", "POST").contains(method)) throw new Api.Failure(400, "真实服务请求方式仅支持GET或POST");
    return method;
  }

  static Map<String, String> headers(ObjectMapper json, String raw) {
    if (raw == null || raw.isBlank()) return Map.of();
    try {
      if (raw.length() > 16000) throw new IllegalArgumentException();
      JsonNode node = json.readTree(raw);
      if (!node.isObject() || node.size() > 20) throw new IllegalArgumentException();
      Map<String, String> result = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
      var fields = node.fields();
      while (fields.hasNext()) {
        var e = fields.next();
        String key = e.getKey(), value = e.getValue().asText();
        if (!e.getValue().isTextual()
            || !key.matches("[A-Za-z0-9_-]{1,80}")
            || result.containsKey(key)
            || Set.of(
                    "host",
                    "content-length",
                    "transfer-encoding",
                    "connection",
                    "accept-encoding",
                    "content-type",
                    "accept",
                    "authorization",
                    "cookie",
                    "proxy-authorization",
                    "upgrade",
                    "expect",
                    "te",
                    "trailer")
                .contains(key.toLowerCase(Locale.ROOT))
            || value.length() > 4000
            || value.chars().anyMatch(c -> c < 32 || c > 126)) throw new IllegalArgumentException();
        result.put(key, value);
      }
      return result;
    } catch (Exception invalid) {
      throw new Api.Failure(400, "请求头须为最多20项字符串JSON对象，不能包含传输控制头、Authorization、Cookie或换行");
    }
  }

  /** GET把标量字段编码到查询串；对象/数组不猜测供应商编码格式，明确拒绝，避免静默丢参数。 */
  static URI target(URI endpoint, String method, JsonNode input) {
    if (!method.equals("GET")) return endpoint;
    if (!input.isObject()) throw new Api.Failure(400, "GET上游参数须为JSON对象，字段值只能为标量");
    StringBuilder suffix = new StringBuilder();
    var fields = input.fields();
    while (fields.hasNext()) {
      var e = fields.next();
      if (e.getValue().isContainerNode())
        throw new Api.Failure(400, "GET上游不支持嵌套对象或数组，请使用POST或调整接口参数");
      suffix
          .append(suffix.isEmpty() ? "" : "&")
          .append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8))
          .append("=")
          .append(
              URLEncoder.encode(
                  e.getValue().isNull() ? "" : e.getValue().asText(), StandardCharsets.UTF_8));
    }
    String result =
        endpoint.toASCIIString()
            + (suffix.isEmpty() ? "" : endpoint.getRawQuery() == null ? "?" : "&")
            + suffix;
    if (result.length() > 16000) throw new Api.Failure(400, "GET上游查询参数过长");
    return URI.create(result);
  }
}
