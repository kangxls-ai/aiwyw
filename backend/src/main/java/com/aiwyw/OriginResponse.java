package com.aiwyw;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.core.MethodParameter;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/** 仅在已完成原接口鉴权的对象列表上补充来源；不增加可任意查询跨用户对象的元数据接口。 */
@RestControllerAdvice
final class OriginResponse implements ResponseBodyAdvice<Object> {
  private final JdbcTemplate db;
  private final ObjectMapper json;

  OriginResponse(JdbcTemplate db, ObjectMapper json) {
    this.db = db;
    this.json = json;
  }

  public boolean supports(MethodParameter p, Class<? extends HttpMessageConverter<?>> c) {
    return true;
  }

  static String type(String path) {
    return switch (path) {
      case "/api/admin/catalog/products" -> "catalog_product";
      case "/api/admin/catalog/sections" -> "home_section";
      case "/api/admin/catalog/tags" -> "catalog_tag";
      case "/api/admin/accounts", "/api/admin/statistics/active-users" -> "app_user";
      case "/api/admin/roles" -> "access_role";
      case "/api/admin/content" -> "content_document";
      case "/api/admin/commerce-packages" -> "points_package";
      case "/api/admin/storage/assets" -> "storage_asset";
      case "/api/admin/apis", "/api/admin/api-list/apis" -> "api_definition";
      case "/api/admin/api-plans", "/api/admin/api-list/plans" -> "api_plan";
      case "/api/admin/api-keys", "/api/admin/api-list/keys" -> "api_key";
      case "/api/admin/api-subscriptions",
              "/api/admin/api-refunds",
              "/api/admin/api-list/subscriptions",
              "/api/admin/api-list/api-refunds",
              "/api/member/api/subscriptions/page" ->
          "api_subscription";
      case "/api/admin/api-calls", "/api/admin/api-list/usage", "/api/member/api/calls" ->
          "api_call";
      case "/api/admin/support/conversations" -> "support_conversation";
      case "/api/admin/support/feedback" -> "support_feedback";
      case "/api/admin/commerce/recharge", "/api/member/list/recharge" -> "recharge_order";
      case "/api/admin/commerce/orders", "/api/member/list/resources" -> "purchase_order";
      case "/api/admin/commerce/refunds", "/api/member/list/refunds" -> "refund_request";
      case "/api/admin/commerce/points", "/api/member/list/points" -> "wallet_ledger";
      case "/api/member/api" -> "api_key";
      case "/api/member/wallet" -> "wallet_batch";
      case "/api/member/notifications" -> "member_notification";
      case "/api/admin/licenses" -> "software_license";
      case "/api/admin/license-resets", "/api/admin/licenses/requests" -> "license_unbind_request";
      default ->
          path.matches("/api/admin/catalog/products/[^/]+/(history|publications)")
              ? "catalog_publication"
              : null;
    };
  }

  @Override
  public Object beforeBodyWrite(
      Object body,
      MethodParameter p,
      MediaType media,
      Class<? extends HttpMessageConverter<?>> converter,
      ServerHttpRequest request,
      ServerHttpResponse response) {
    if (!request.getMethod().equals(HttpMethod.GET)
        || !MediaType.APPLICATION_JSON.isCompatibleWith(media)) return body;
    String type = type(request.getURI().getPath());
    if (type == null || body == null) return body;
    Object result = json.convertValue(body, Object.class);
    String path = request.getURI().getPath();
    Object raw =
        result instanceof Map<?, ?> map
            ? map.get(
                path.equals("/api/member/api")
                    ? "keys"
                    : path.equals("/api/member/wallet") ? "batches" : "items")
            : result;
    if (!(raw instanceof List<?> list)) return body;
    var rows = new ArrayList<Map<String, Object>>();
    for (Object item : list) if (item instanceof Map<?, ?> map) rows.add((Map<String, Object>) map);
    if (rows.isEmpty()) return result;
    if (type.equals("refund_request") || type.equals("software_license"))
      for (var row : rows) if (!row.containsKey("id")) row.put("id", row.get("purchase_id"));
    List<String> ids =
        rows.stream()
            .map(r -> String.valueOf(r.get("id")))
            .filter(s -> !s.equals("null"))
            .distinct()
            .toList();
    if (ids.isEmpty()) return result;
    String marks = String.join(",", Collections.nCopies(ids.size(), "?"));
    var args = new ArrayList<Object>();
    args.add(type);
    args.addAll(ids);
    Map<String, Map<String, Object>> origins = new HashMap<>();
    for (var row :
        Commerce.safeRows(
            db.queryForList(
                "SELECT o.*,u.nickname FROM object_origin o LEFT JOIN app_user u ON"
                    + " u.id=o.created_by WHERE object_type=? AND object_id IN ("
                    + marks
                    + ")",
                args.toArray()))) origins.put((String) row.get("object_id"), row);
    if (type.equals("catalog_publication"))
      for (var row : rows) row.put("created_by", row.get("actor_id"));
    Set<String> creators = new HashSet<>();
    for (var row : rows) if (row.get("created_by") instanceof String actor) creators.add(actor);
    Map<String, String> names = new HashMap<>();
    if (!creators.isEmpty())
      for (var row :
          db.queryForList(
              "SELECT id,nickname FROM app_user WHERE id IN ("
                  + String.join(",", Collections.nCopies(creators.size(), "?"))
                  + ")",
              creators.toArray()))
        names.put(String.valueOf(row.get("id")), String.valueOf(row.get("nickname")));
    for (var row : rows) {
      var origin = origins.get(String.valueOf(row.get("id")));
      Object actor = origin == null ? row.get("created_by") : origin.get("created_by");
      row.put("created_by_id", actor);
      String label =
          actor == null
              ? "历史未记录"
              : actor.equals("SYSTEM")
                  ? "系统"
                  : String.valueOf(actor).startsWith("GUEST:")
                      ? "游客"
                      : origin != null && origin.get("nickname") != null
                          ? String.valueOf(origin.get("nickname"))
                          : names.getOrDefault(String.valueOf(actor), "已删除或未知账户");
      row.put("created_by_name", label);
      if (!row.containsKey("created_at"))
        row.put(
            "created_at",
            row.getOrDefault("createdAt", origin == null ? 0L : origin.get("created_at")));
    }
    // DTO列表的声明类型仍是List<Product/Asset>；返回同形JSON数组，避免Jackson把新增字段Map强转成旧DTO。
    return result instanceof List<?> values ? values.toArray() : result;
  }
}
