package com.aiwyw;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

/** 用户列表在数据库先限定归属、再搜索分页；不返回Key摘要、上游密钥或全量私有响应正文。 */
@RestController
class MemberRecords {
  final JdbcTemplate db;
  final Auth auth;

  MemberRecords(JdbcTemplate db, Auth auth) {
    this.db = db;
    this.auth = auth;
  }

  @GetMapping("/api/member/records/notifications")
  Map<String, Object> notifications(@RequestParam Map<String, String> p, HttpServletRequest r) {
    String user = auth.user(r, false).id();
    String where = "user_id=?";
    if (p.getOrDefault("state", "").equals("UNREAD")) where += " AND read_at IS NULL";
    else if (p.getOrDefault("state", "").equals("READ")) where += " AND read_at IS NOT NULL";
    return OperationalPage.query(
        db,
        "SELECT id,user_id,REPLACE(REPLACE(title,'积分','鼓励值'),'充值','捐赠') AS"
            + " title,REPLACE(REPLACE(body,'积分','鼓励值'),'充值','捐赠') AS body,read_at,created_at FROM"
            + " member_notification",
        where,
        List.of(user),
        Map.of("id", "id", "created_at", "created_at", "title", "title", "read_at", "read_at"),
        "CONCAT(title,' ',body)",
        p);
  }

  @GetMapping("/api/public/api-records/{kind}")
  Map<String, Object> publicApi(
      @PathVariable String kind, @RequestParam Map<String, String> p, HttpServletRequest r) {
    if (!Set.of("apis", "plans").contains(kind)) throw new Api.Failure(404, "列表不存在");
    String user = null;
    try {
      user = auth.user(r, false).id();
    } catch (Api.Failure e) {
      if (e.status != 401) throw e;
    }
    return api(kind, user, p);
  }

  @GetMapping("/api/member/api/records/{kind}")
  Map<String, Object> memberApi(
      @PathVariable String kind, @RequestParam Map<String, String> p, HttpServletRequest r) {
    auth.user(r, true);
    return api(kind, auth.verified(r).id(), p);
  }

  Map<String, Object> api(String kind, String user, Map<String, String> p) {
    String source, where = "1=1", search = "name";
    List<Object> args = new ArrayList<>();
    switch (kind) {
      case "apis", "debug" -> {
        source =
            "SELECT id,slug,name,name_en,description,description_en,adapter,price_units,created_at"
                + " FROM api_definition WHERE enabled=TRUE";
        search = "CONCAT(name,' ',name_en,' ',slug,' ',description)";
        if (kind.equals("debug")) {
          where +=
              " AND id IN (SELECT s.api_id FROM api_key_scope s JOIN api_key k ON k.id=s.key_id"
                  + " WHERE k.user_id=? AND k.enabled=TRUE AND (k.expires_at IS NULL OR"
                  + " k.expires_at>?))";
          args.add(user);
          args.add(System.currentTimeMillis());
        }
      }
      case "plans" -> {
        source =
            "SELECT"
                + " p.id,p.api_id,p.name,p.price_units,p.quota,p.days,p.revision,p.created_at,d.name"
                + " AS api_name FROM api_plan p JOIN api_definition d ON d.id=p.api_id WHERE"
                + " p.enabled=TRUE AND d.enabled=TRUE";
        search = "CONCAT(name,' ',api_name)";
      }
      case "keys" -> {
        source =
            "SELECT"
                + " id,user_id,name,prefix,enabled,expires_at,minute_limit,day_limit,revision,created_at"
                + " FROM api_key";
        where = "user_id=?";
        args.add(user);
      }
      case "subscriptions" -> {
        source = "SELECT * FROM api_subscription";
        where = "user_id=?";
        args.add(user);
        search = "plan_name";
      }
      case "calls" -> {
        source =
            "SELECT"
                + " id,user_id,api_id,api_name,key_prefix,billing,price_units,state,error_code,debug_call,created_at,finished_at"
                + " FROM api_call";
        where = "user_id=?";
        args.add(user);
        search = "CONCAT(id,' ',api_name,' ',key_prefix)";
      }
      default -> throw new Api.Failure(404, "列表不存在");
    }
    if (user == null && !Set.of("apis", "plans").contains(kind)) throw new Api.Failure(401, "请先登录");
    String apiId = p.getOrDefault("apiId", "");
    if (!apiId.isBlank()) {
      if (kind.equals("keys")) {
        where += " AND id IN (SELECT key_id FROM api_key_scope WHERE api_id=?)";
      } else if (Set.of("plans", "subscriptions", "calls").contains(kind)) {
        where += " AND api_id=?";
      } else {
        where += " AND id=?";
      }
      args.add(apiId);
    }
    if (kind.equals("keys") && p.getOrDefault("validOnly", "").equals("true")) {
      where += " AND (expires_at IS NULL OR expires_at>?)";
      args.add(System.currentTimeMillis());
    }
    String state = p.getOrDefault("state", "");
    if (!state.isBlank()) {
      if (kind.equals("keys")) {
        if (!Set.of("ENABLED", "DISABLED").contains(state)) throw new Api.Failure(400, "状态不正确");
        where += " AND enabled=?";
        args.add(state.equals("ENABLED"));
      } else if (Set.of("subscriptions", "calls").contains(kind)) {
        where += " AND state=?";
        args.add(state);
      }
    }
    var columns = new HashMap<String, String>(Map.of("id", "id", "created_at", "created_at"));
    columns.put("name", search);
    if (Set.of("apis", "debug", "plans", "calls").contains(kind))
      columns.put("price_units", "price_units");
    var result = OperationalPage.query(db, source, where, args, columns, search, p);
    for (var row : (List<Map<String, Object>>) result.get("items")) {
      if (kind.equals("keys"))
        row.put(
            "apiIds",
            db.queryForList(
                "SELECT api_id FROM api_key_scope WHERE key_id=?", String.class, row.get("id")));
      if (kind.equals("apis") && user != null) {
        var preferences =
            db.queryForList(
                "SELECT payg,overage FROM api_preference WHERE user_id=? AND api_id=?",
                user,
                row.get("id"));
        row.put(
            "preference",
            preferences.isEmpty() ? Map.of("payg", false, "overage", false) : preferences.get(0));
      }
    }
    return result;
  }
}
