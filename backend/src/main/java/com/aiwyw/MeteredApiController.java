package com.aiwyw;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

/** Key调用不读取Cookie身份；在线调试从会话取得用户并验证Key归属，使用完全相同的计费路径。 */
@RestController
public class MeteredApiController {
  final MeteredApi service;
  final Auth auth;
  final Accounts accounts;
  final JdbcTemplate db;

  MeteredApiController(MeteredApi service, Auth auth, Accounts accounts, JdbcTemplate db) {
    this.service = service;
    this.auth = auth;
    this.accounts = accounts;
    this.db = db;
  }

  private Auth.User memberIdentity(HttpServletRequest r) {
    auth.user(r, true);
    return auth.verified(r);
  }

  @GetMapping("/api/member/api/subscriptions/page")
  Map<String, Object> memberPeriods(@RequestParam Map<String, String> p, HttpServletRequest r) {
    String user = memberIdentity(r).id();
    return OperationalPage.query(
        db,
        "SELECT * FROM api_subscription",
        "user_id=?",
        List.of(user),
        Map.of(
            "id",
            "id",
            "created_at",
            "created_at",
            "plan_name",
            "plan_name",
            "start_at",
            "start_at",
            "end_at",
            "end_at",
            "used_count",
            "used_count",
            "refund_state",
            "refund_state"),
        "plan_name",
        p);
  }

  @GetMapping("/api/public/apis")
  Map<String, Object> publicApis() {
    return Map.of("apis", service.definitions(false), "plans", service.plans(false));
  }

  @GetMapping("/api/member/api")
  Map<String, Object> member(
      @RequestParam(defaultValue = "false") boolean summary, HttpServletRequest r) {
    String user = memberIdentity(r).id();
    if (summary)
      return Map.of(
          "accountMinuteLimit",
          service.settings.integer("api.account_minute_limit"),
          "mode",
          Commerce.MODE);
    return service.member(user);
  }

  @PostMapping("/api/member/api/keys")
  Map<String, Object> createKey(@RequestBody MeteredApi.KeyInput b, HttpServletRequest r) {
    return service.saveKey(memberIdentity(r).id(), null, b);
  }

  @PutMapping("/api/member/api/keys/{id}")
  Map<String, Object> updateKey(
      @PathVariable String id, @RequestBody MeteredApi.KeyInput b, HttpServletRequest r) {
    return service.saveKey(memberIdentity(r).id(), id, b);
  }

  @DeleteMapping("/api/member/api/keys/{id}")
  Map<String, Boolean> removeKey(
      @PathVariable String id, @RequestParam long revision, HttpServletRequest r) {
    service.deleteKey(memberIdentity(r).id(), id, revision);
    return Map.of("success", true);
  }

  record Preference(boolean payg, boolean overage) {}

  @PostMapping("/api/member/api/keys/{id}/rotate")
  Map<String, Object> rotateKey(
      @PathVariable String id, @RequestBody Revision b, HttpServletRequest r) {
    return service.rotateKey(memberIdentity(r).id(), id, b.revision());
  }

  @PutMapping("/api/member/api/preferences/{id}")
  Map<String, Boolean> preference(
      @PathVariable String id, @RequestBody Preference b, HttpServletRequest r) {
    service.preference(memberIdentity(r).id(), id, b.payg(), b.overage());
    return Map.of("success", true);
  }

  @PostMapping("/api/member/api/subscriptions")
  Map<String, String> buy(@RequestBody MeteredApi.Purchase b, HttpServletRequest r) {
    return Map.of("id", service.buy(memberIdentity(r).id(), b));
  }

  record Reason(String reason) {}

  @PostMapping("/api/member/api/subscriptions/{id}/refund")
  Map<String, Boolean> refund(
      @PathVariable String id, @RequestBody Reason b, HttpServletRequest r) {
    service.requestRefund(memberIdentity(r).id(), id, b.reason());
    return Map.of("success", true);
  }

  record Debug(String keyId, String requestKey, JsonNode input) {}

  @PostMapping("/api/member/api/debug/{slug}")
  Map<String, Object> debug(@PathVariable String slug, @RequestBody Debug b, HttpServletRequest r) {
    return service.invoke(memberIdentity(r).id(), b.keyId(), slug, b.requestKey(), b.input(), true);
  }

  @PostMapping("/api/v1/invoke/{slug}")
  Map<String, Object> invoke(
      @PathVariable String slug,
      @RequestBody JsonNode input,
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      HttpServletRequest r) {
    String token =
        authorization != null && authorization.startsWith("Bearer ")
            ? authorization.substring(7)
            : null;
    String user, keyId;
    try {
      user = service.keyUser(token);
      keyId = service.keyId(token);
    } catch (Api.Failure invalid) {
      accounts.limit(
          "api-invalid", r.getRemoteAddr(), service.settings.integer("api.invalid_hour_limit"));
      throw new Api.Failure(401, "API Key无效");
    }
    return service.invoke(user, keyId, slug, key, input, false);
  }

  @GetMapping("/api/member/api/calls")
  Map<String, Object> memberCalls(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int pageSize,
      @RequestParam(defaultValue = "") String state,
      @RequestParam(defaultValue = "") String apiId,
      HttpServletRequest r) {
    return service.calls(memberIdentity(r).id(), page, pageSize, state, apiId);
  }

  @GetMapping("/api/admin/apis")
  List<Map<String, Object>> apis(HttpServletRequest r) {
    auth.permission(r, "apis");
    return service.definitions(true);
  }

  @PostMapping("/api/admin/apis")
  Map<String, String> create(@RequestBody MeteredApi.Definition b, HttpServletRequest r) {
    return Map.of("id", service.saveDefinition(null, b, auth.permission(r, "apis").id()));
  }

  @PutMapping("/api/admin/apis/{id}")
  Map<String, String> update(
      @PathVariable String id, @RequestBody MeteredApi.Definition b, HttpServletRequest r) {
    return Map.of("id", service.saveDefinition(id, b, auth.permission(r, "apis").id()));
  }

  @GetMapping("/api/admin/api-plans")
  List<Map<String, Object>> plans(HttpServletRequest r) {
    auth.permission(r, "plans");
    return service.plans(true);
  }

  @PostMapping("/api/admin/api-plans")
  Map<String, String> createPlan(@RequestBody MeteredApi.Plan b, HttpServletRequest r) {
    return Map.of("id", service.savePlan(null, b, auth.permission(r, "plans").id()));
  }

  @PutMapping("/api/admin/api-plans/{id}")
  Map<String, String> updatePlan(
      @PathVariable String id, @RequestBody MeteredApi.Plan b, HttpServletRequest r) {
    return Map.of("id", service.savePlan(id, b, auth.permission(r, "plans").id()));
  }

  @GetMapping("/api/admin/api-keys")
  List<Map<String, Object>> keys(HttpServletRequest r) {
    auth.permission(r, "keys");
    return service.keys(null);
  }

  record Revision(long revision) {}

  @PostMapping("/api/admin/api-keys/{id}/revoke")
  Map<String, Boolean> revoke(
      @PathVariable String id, @RequestBody Revision b, HttpServletRequest r) {
    var actor = auth.permission(r, "keys");
    service.tx.executeWithoutResult(
        s -> {
          String user =
              (String) service.one("SELECT user_id FROM api_key WHERE id=?", id).get("user_id");
          service.wallet.lock(user);
          if (db.update(
                  "UPDATE api_key SET enabled=FALSE,revision=revision+1 WHERE id=? AND revision=?",
                  id,
                  b.revision())
              != 1) throw new Api.Failure(409, "Key已变化");
          service.audit(actor.id(), id, "ADMIN_REVOKE_KEY");
        });
    return Map.of("success", true);
  }

  @GetMapping("/api/admin/api-calls")
  Map<String, Object> calls(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int pageSize,
      @RequestParam(defaultValue = "") String state,
      @RequestParam(defaultValue = "") String apiId,
      HttpServletRequest r) {
    auth.permission(r, "usage");
    return service.calls(null, page, pageSize, state, apiId);
  }

  @GetMapping("/api/admin/api-subscriptions")
  List<Map<String, Object>> subscriptions(HttpServletRequest r) {
    auth.permission(r, "plans");
    return Commerce.safeRows(
        db.queryForList(
            "SELECT s.*,u.nickname FROM api_subscription s JOIN app_user u ON u.id=s.user_id ORDER"
                + " BY s.created_at DESC,s.id LIMIT 500"));
  }

  @GetMapping("/api/admin/api-refunds")
  List<Map<String, Object>> refunds(HttpServletRequest r) {
    auth.permission(r, "refunds");
    return Commerce.safeRows(
        db.queryForList(
            "SELECT s.*,u.nickname FROM api_subscription s JOIN app_user u ON u.id=s.user_id WHERE"
                + " refund_state<>'NONE' ORDER BY s.created_at DESC,s.id LIMIT 500"));
  }

  record Review(boolean approve, String reason, long revision) {}

  @PostMapping("/api/admin/redis/check")
  Map<String, Boolean> checkRedis(HttpServletRequest r) {
    auth.permission(r, "settings");
    service.redis.check();
    return Map.of("success", true);
  }

  @GetMapping("/api/admin/api-list/{kind}")
  Map<String, Object> page(
      @PathVariable String kind, @RequestParam Map<String, String> p, HttpServletRequest r) {
    Map<String, String> permissions =
        Map.of(
            "apis",
            "apis",
            "plans",
            "plans",
            "subscriptions",
            "plans",
            "keys",
            "keys",
            "usage",
            "usage",
            "api-refunds",
            "refunds");
    if (!permissions.containsKey(kind)) throw new Api.Failure(404, "列表不存在");
    auth.permission(r, permissions.get(kind));
    String source =
        switch (kind) {
          case "apis" ->
              "SELECT"
                  + " id,slug,name,name_en,description,description_en,adapter,endpoint,request_method,CASE"
                  + " WHEN headers_enc IS NULL THEN FALSE ELSE TRUE END AS"
                  + " headers_set,success_pointer,success_value,price_units,enabled,revision,created_at,CASE"
                  + " WHEN secret_enc IS NULL THEN FALSE ELSE TRUE END AS secret_set FROM"
                  + " api_definition";
          case "plans" ->
              "SELECT p.*,d.name AS api_name FROM api_plan p JOIN api_definition d ON"
                  + " d.id=p.api_id";
          case "keys" ->
              "SELECT"
                  + " k.id,k.user_id,k.name,k.prefix,k.enabled,k.expires_at,k.minute_limit,k.day_limit,k.revision,k.created_at,u.nickname"
                  + " FROM api_key k JOIN app_user u ON u.id=k.user_id";
          case "usage" ->
              "SELECT c.id,c.api_name AS"
                  + " name,c.state,c.billing,c.price_units,c.debug_call,c.created_at,c.finished_at,c.error_code,c.key_prefix"
                  + " AS prefix,u.nickname FROM api_call c JOIN api_key k ON k.id=c.key_id JOIN"
                  + " app_user u ON u.id=c.user_id";
          default ->
              "SELECT s.*,s.plan_name AS name,u.nickname FROM api_subscription s JOIN app_user u ON"
                  + " u.id=s.user_id";
        };
    var columns = new HashMap<String, String>();
    for (String c : List.of("id", "created_at", "name")) columns.put(c, c);
    for (String c :
        switch (kind) {
          case "apis" -> List.of("slug", "adapter", "price_units", "enabled");
          case "plans" -> List.of("quota", "days", "price_units", "enabled", "api_name");
          case "keys" -> List.of("prefix", "nickname", "minute_limit", "day_limit", "enabled");
          case "usage" -> List.of("state", "billing", "price_units", "nickname", "finished_at");
          default ->
              List.of(
                  "price_units",
                  "quota",
                  "used_count",
                  "start_at",
                  "end_at",
                  "state",
                  "refund_state",
                  "nickname");
        }) columns.put(c, c);
    String where = kind.equals("api-refunds") ? "refund_state<>'NONE'" : "1=1";
    var args = new ArrayList<Object>();
    String state = p.getOrDefault("state", "");
    if (!state.isBlank()) {
      if (List.of("apis", "plans", "keys").contains(kind)) {
        if (!Set.of("true", "false").contains(state)) throw new Api.Failure(400, "状态不正确");
        where += " AND enabled=?";
        args.add(Boolean.parseBoolean(state));
      } else {
        where += " AND " + (kind.equals("api-refunds") ? "refund_state" : "state") + "=?";
        args.add(MeteredApi.text(state, 16, true));
      }
    }
    return OperationalPage.query(db, source, where, args, columns, "name", p);
  }

  @PostMapping("/api/admin/api-subscriptions/{id}/refund")
  Map<String, Boolean> review(
      @PathVariable String id, @RequestBody Review b, HttpServletRequest r) {
    service.reviewRefund(auth.permission(r, "refunds"), id, b.approve(), b.reason(), b.revision());
    return Map.of("success", true);
  }
}
