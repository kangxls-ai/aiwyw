package com.aiwyw;

import com.fasterxml.jackson.databind.*;
import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 锁序：账户→调用/周期→钱包批次。外部调用绝不持有数据库事务；所有终态和计量只结算一次。 */
@Service
public class MeteredApi {
  final JdbcTemplate db;
  final SecureStore crypto;
  final Settings settings;
  final Commerce wallet;
  final ObjectMapper json;
  final ApiUpstream upstream;
  final TransactionTemplate tx;
  final OptionalRedis redis;

  MeteredApi(
      JdbcTemplate db,
      SecureStore crypto,
      Settings settings,
      Commerce wallet,
      ObjectMapper json,
      ApiUpstream upstream,
      OptionalRedis redis,
      PlatformTransactionManager manager) {
    this.db = db;
    this.crypto = crypto;
    this.settings = settings;
    this.wallet = wallet;
    this.json = json;
    this.upstream = upstream;
    this.redis = redis;
    tx = new TransactionTemplate(manager);
  }

  static String id() {
    return Ids.next();
  }

  static long n(Map<String, Object> row, String key) {
    return ((Number) row.get(key)).longValue();
  }

  static String text(String v, int max, boolean required) {
    String s = v == null ? "" : v.trim();
    if (s.length() > max || (required && s.isBlank())) throw new Api.Failure(400, "请填写完整且符合长度的资料");
    return s;
  }

  static String requestKey(String v) {
    String s = text(v, 80, true);
    if (!s.matches("[A-Za-z0-9_-]{8,80}")) throw new Api.Failure(400, "请求标识需为8—80位英文、数字、短横线或下划线");
    return s;
  }

  String encode(Object o) {
    try {
      return json.writeValueAsString(o);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  Map<String, Object> one(String sql, Object... args) {
    var rows = db.queryForList(sql, args);
    if (rows.isEmpty()) throw new Api.Failure(404, "记录不存在");
    return rows.get(0);
  }

  void audit(String actor, String entity, String action) {
    AuditLog.write(db, "catalog_audit", actor, entity, action, "API维护");
  }

  void verified(String user) {
    if (db.queryForObject(
            "SELECT COUNT(*) FROM app_user WHERE id=? AND disabled=FALSE AND email_verified_at IS"
                + " NOT NULL",
            Integer.class,
            user)
        != 1) throw new Api.Failure(403, "账户停用或邮箱尚未验证");
  }

  public record Definition(
      String slug,
      String name,
      String nameEn,
      String description,
      String descriptionEn,
      String adapter,
      String endpoint,
      String secret,
      boolean clearSecret,
      String successPointer,
      String successValue,
      BigDecimal pricePoints,
      boolean enabled,
      long revision,
      String requestMethod,
      String requestHeaders,
      boolean clearHeaders) {
    // 旧调用方/已保存配置仍按POST处理，不改变历史服务的请求行为。
    public Definition(
        String slug,
        String name,
        String nameEn,
        String description,
        String descriptionEn,
        String adapter,
        String endpoint,
        String secret,
        boolean clearSecret,
        String successPointer,
        String successValue,
        BigDecimal pricePoints,
        boolean enabled,
        long revision) {
      this(
          slug,
          name,
          nameEn,
          description,
          descriptionEn,
          adapter,
          endpoint,
          secret,
          clearSecret,
          successPointer,
          successValue,
          pricePoints,
          enabled,
          revision,
          "POST",
          "",
          false);
    }
  }

  public String saveDefinition(String existing, Definition b, String actor) {
    String slug = text(b.slug(), 80, true),
        name = text(b.name(), 120, true),
        adapter = text(b.adapter(), 20, true);
    if (!slug.matches("[a-z0-9]+(?:-[a-z0-9]+)*")
        || !Set.of("TEXT_STATS", "SHA256", "HTTPS_JSON").contains(adapter))
      throw new Api.Failure(400, "API路径或适配器不正确");
    String endpoint = text(b.endpoint(), 1000, false),
        pointer = text(b.successPointer(), 200, false),
        expected = text(b.successValue(), 200, false);
    if (adapter.equals("HTTPS_JSON")) {
      ApiUpstream.endpoint(endpoint);
      try {
        var value = json.readTree(expected);
        if (!pointer.startsWith("/") || value == null || value.isContainerNode())
          throw new IllegalArgumentException();
        com.fasterxml.jackson.core.JsonPointer.compile(pointer);
      } catch (Exception e) {
        throw new Api.Failure(400, "请提供成功字段JSON Pointer和标量JSON成功值");
      }
    }
    String requestMethod = UpstreamRequest.method(b.requestMethod());
    String requestHeaders = b.requestHeaders() == null ? "" : b.requestHeaders().trim();
    UpstreamRequest.headers(json, requestHeaders);
    String secret = text(b.secret(), 4000, false);
    if (secret.contains("\r") || secret.contains("\n")) throw new Api.Failure(400, "凭据不能换行");
    long price = Commerce.units(b.pricePoints());
    if (price < 0) throw new Api.Failure(400, "价格不能为负");
    return tx.execute(
        s -> {
          db.queryForObject("SELECT revision FROM catalog_guard WHERE id=1 FOR UPDATE", Long.class);
          String key = existing == null ? id() : existing;
          Object cipher = null, headersCipher = null;
          if (existing != null) {
            var old = one("SELECT * FROM api_definition WHERE id=?", key);
            if (!old.get("slug").equals(slug)) throw new Api.Failure(409, "API调用地址创建后固定");
            cipher = old.get("secret_enc");
            headersCipher = old.get("headers_enc");
          }
          if (b.clearHeaders()) headersCipher = null;
          if (!requestHeaders.isBlank()) headersCipher = crypto.encrypt(requestHeaders);
          if (b.clearSecret()) cipher = null;
          if (!secret.isBlank()) cipher = crypto.encrypt(secret);
          if (existing == null) {
            if (db.queryForObject(
                    "SELECT COUNT(*) FROM api_definition WHERE slug=?", Integer.class, slug)
                > 0) throw new Api.Failure(409, "API路径已存在");
            db.update(
                "INSERT INTO"
                    + " api_definition(id,slug,name,name_en,description,description_en,adapter,endpoint,secret_enc,success_pointer,success_value,price_units,enabled,revision,created_at)"
                    + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,1,?)",
                key,
                slug,
                name,
                text(b.nameEn(), 120, false),
                text(b.description(), 1000, true),
                text(b.descriptionEn(), 1000, false),
                adapter,
                endpoint,
                cipher,
                pointer,
                expected,
                price,
                b.enabled(),
                System.currentTimeMillis());
          } else if (db.update(
                  "UPDATE api_definition SET"
                      + " name=?,name_en=?,description=?,description_en=?,adapter=?,endpoint=?,secret_enc=?,success_pointer=?,success_value=?,price_units=?,enabled=?,revision=revision+1"
                      + " WHERE id=? AND revision=?",
                  name,
                  text(b.nameEn(), 120, false),
                  text(b.description(), 1000, true),
                  text(b.descriptionEn(), 1000, false),
                  adapter,
                  endpoint,
                  cipher,
                  pointer,
                  expected,
                  price,
                  b.enabled(),
                  key,
                  b.revision())
              != 1) throw new Api.Failure(409, "API已变化，请重新读取");
          db.update(
              "UPDATE api_definition SET request_method=?,headers_enc=? WHERE id=?",
              requestMethod,
              headersCipher,
              key);
          if (existing == null) Origins.created(db, "api_definition", key, actor);
          audit(actor, key, "SAVE_API");
          org.springframework.transaction.support.TransactionSynchronizationManager
              .registerSynchronization(
                  new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                      redis.remove("public-apis");
                    }
                  });
          return key;
        });
  }

  public List<Map<String, Object>> definitions(boolean admin) {
    if (!admin) {
      String cached = redis.get("public-apis");
      if (cached != null)
        try {
          return json.readValue(cached, List.class);
        } catch (Exception ignored) {
          redis.remove("public-apis");
        }
    }
    String cols =
        "id,slug,name,name_en,description,description_en,adapter,price_units,enabled,revision,created_at"
            + (admin
                ? ",endpoint,request_method,CASE WHEN headers_enc IS NULL THEN FALSE ELSE TRUE END"
                    + " AS headers_set,success_pointer,success_value,CASE WHEN secret_enc IS NULL"
                    + " THEN FALSE ELSE TRUE END AS secret_set"
                : "");
    var result =
        Commerce.safeRows(
            db.queryForList(
                "SELECT "
                    + cols
                    + " FROM api_definition"
                    + (admin ? "" : " WHERE enabled=TRUE")
                    + " ORDER BY created_at DESC,id"));
    if (!admin) redis.put("public-apis", encode(result));
    return result;
  }

  public record Plan(
      String apiId,
      String name,
      BigDecimal pricePoints,
      long quota,
      int days,
      boolean enabled,
      long revision) {}

  public String savePlan(String existing, Plan b, String actor) {
    long price = Commerce.units(b.pricePoints());
    String name = text(b.name(), 120, true);
    if (price < 0 || b.quota() < 1 || b.quota() > 1_000_000_000L || b.days() < 1 || b.days() > 3650)
      throw new Api.Failure(400, "套餐价格、额度或天数不正确");
    return tx.execute(
        s -> {
          one("SELECT id FROM api_definition WHERE id=? FOR UPDATE", b.apiId());
          String key = existing == null ? id() : existing;
          if (existing == null)
            db.update(
                "INSERT INTO api_plan VALUES(?,?,?,?,?,?,?,1,?)",
                key,
                b.apiId(),
                name,
                price,
                b.quota(),
                b.days(),
                b.enabled(),
                System.currentTimeMillis());
          else if (db.update(
                  "UPDATE api_plan SET"
                      + " name=?,price_units=?,quota=?,days=?,enabled=?,revision=revision+1 WHERE"
                      + " id=? AND api_id=? AND revision=?",
                  name,
                  price,
                  b.quota(),
                  b.days(),
                  b.enabled(),
                  key,
                  b.apiId(),
                  b.revision())
              != 1) throw new Api.Failure(409, "套餐已变化或API不一致");
          if (existing == null) Origins.created(db, "api_plan", key, actor);
          audit(actor, key, "SAVE_API_PLAN");
          return key;
        });
  }

  public List<Map<String, Object>> plans(boolean admin) {
    return Commerce.safeRows(
        db.queryForList(
            "SELECT p.*,d.name AS api_name FROM api_plan p JOIN api_definition d ON d.id=p.api_id"
                + (admin ? "" : " WHERE p.enabled=TRUE AND d.enabled=TRUE")
                + " ORDER BY p.created_at DESC,p.id"));
  }

  public record KeyInput(
      String name,
      List<String> apiIds,
      int minuteLimit,
      long dayLimit,
      Long expiresAt,
      boolean enabled,
      long revision) {}

  public Map<String, Object> saveKey(String user, String existing, KeyInput b) {
    String name = text(b.name(), 80, true);
    if (b.apiIds() == null
        || b.apiIds().isEmpty()
        || b.apiIds().size() > 100
        || b.minuteLimit() < 1
        || b.minuteLimit() > settings.integer("api.account_minute_limit")
        || b.dayLimit() < 0
        || b.dayLimit() > 1_000_000_000L
        || (b.enabled() && b.expiresAt() != null && b.expiresAt() <= System.currentTimeMillis()))
      throw new Api.Failure(400, "Key权限、有效期或限额不正确");
    return tx.execute(
        s -> {
          wallet.lock(user);
          verified(user);
          String key = existing == null ? id() : existing, token = "";
          for (String api : new HashSet<>(b.apiIds()))
            one("SELECT id FROM api_definition WHERE id=?", api);
          if (existing == null) {
            if (db.queryForObject(
                    "SELECT COUNT(*) FROM api_key WHERE user_id=?", Integer.class, user)
                >= settings.integer("api.max_keys")) throw new Api.Failure(409, "Key数量达到配置上限");
            token = "aiwyw_" + crypto.token();
            db.update(
                "INSERT INTO api_key VALUES(?,?,?,?,?,?,?,?,?,1,?)",
                key,
                user,
                name,
                token.substring(0, 14),
                crypto.digest(token),
                b.enabled(),
                b.expiresAt(),
                b.minuteLimit(),
                b.dayLimit(),
                System.currentTimeMillis());
          } else if (db.update(
                  "UPDATE api_key SET"
                      + " name=?,enabled=?,expires_at=?,minute_limit=?,day_limit=?,revision=revision+1"
                      + " WHERE id=? AND user_id=? AND revision=?",
                  name,
                  b.enabled(),
                  b.expiresAt(),
                  b.minuteLimit(),
                  b.dayLimit(),
                  key,
                  user,
                  b.revision())
              != 1) throw new Api.Failure(409, "Key已变化");
          db.update("DELETE FROM api_key_scope WHERE key_id=?", key);
          for (String api : new HashSet<>(b.apiIds()))
            db.update("INSERT INTO api_key_scope VALUES(?,?)", key, api);
          if (existing == null) Origins.created(db, "api_key", key, user);
          audit(user, key, "SAVE_API_KEY");
          return Map.of("id", key, "token", token);
        });
  }

  public void deleteKey(String user, String key, long revision) {
    tx.executeWithoutResult(
        s -> {
          wallet.lock(user);
          one(
              "SELECT id FROM api_key WHERE id=? AND user_id=? AND revision=?",
              key,
              user,
              revision);
          if (db.queryForObject("SELECT COUNT(*) FROM api_call WHERE key_id=?", Integer.class, key)
              > 0) throw new Api.Failure(409, "已使用Key请停用，保留调用审计");
          db.update("DELETE FROM api_key_scope WHERE key_id=?", key);
          db.update("DELETE FROM api_key WHERE id=?", key);
          audit(user, key, "DELETE_API_KEY");
        });
  }

  /** 轮换沿用Key配置和审计ID，旧凭据立即失效；完整新凭据同创建一样仅返回一次。 */
  public Map<String, Object> rotateKey(String user, String key, long revision) {
    return tx.execute(
        s -> {
          wallet.lock(user);
          verified(user);
          String token = "aiwyw_" + crypto.token();
          if (db.update(
                  "UPDATE api_key SET token_hash=?,prefix=?,revision=revision+1 WHERE id=? AND"
                      + " user_id=? AND revision=?",
                  crypto.digest(token),
                  token.substring(0, 14),
                  key,
                  user,
                  revision)
              != 1) throw new Api.Failure(409, "Key已变化");
          audit(user, key, "ROTATE_API_KEY");
          return Map.of("id", key, "token", token);
        });
  }

  public List<Map<String, Object>> keys(String user) {
    var rows =
        Commerce.safeRows(
            db.queryForList(
                "SELECT"
                    + " id,user_id,name,prefix,enabled,expires_at,minute_limit,day_limit,revision,created_at"
                    + " FROM api_key"
                    + (user == null ? "" : " WHERE user_id=?")
                    + " ORDER BY created_at DESC,id",
                user == null ? new Object[] {} : new Object[] {user}));
    for (var r : rows)
      r.put(
          "apiIds",
          db.queryForList(
              "SELECT api_id FROM api_key_scope WHERE key_id=?", String.class, r.get("id")));
    return rows;
  }

  public void preference(String user, String api, boolean payg, boolean overage) {
    tx.executeWithoutResult(
        s -> {
          wallet.lock(user);
          one("SELECT id FROM api_definition WHERE id=?", api);
          if (db.update(
                  "UPDATE api_preference SET payg=?,overage=? WHERE user_id=? AND api_id=?",
                  payg,
                  overage,
                  user,
                  api)
              == 0)
            db.update("INSERT INTO api_preference VALUES(?,?,?,?)", user, api, payg, overage);
          audit(user, api, "API_BILLING_PREFERENCE");
        });
  }

  public Map<String, Object> member(String user) {
    return Map.of(
        "apis",
        definitions(false),
        "plans",
        plans(false),
        "keys",
        keys(user),
        "preferences",
        Commerce.safeRows(db.queryForList("SELECT * FROM api_preference WHERE user_id=?", user)),
        "subscriptions",
        Commerce.safeRows(
            db.queryForList(
                "SELECT * FROM api_subscription WHERE user_id=? ORDER BY created_at DESC,id LIMIT"
                    + " 200",
                user)),
        "accountMinuteLimit",
        settings.integer("api.account_minute_limit"),
        "mode",
        Commerce.MODE);
  }

  public record Purchase(String planId, long revision, String requestKey) {}

  public String buy(String user, Purchase b) {
    String request = requestKey(b.requestKey()),
        hash = crypto.digest(b.planId() + ":" + b.revision());
    return tx.execute(
        s -> {
          wallet.lock(user);
          verified(user);
          var old =
              db.queryForList(
                  "SELECT id,request_hash FROM api_subscription WHERE user_id=? AND request_key=?",
                  user,
                  request);
          if (!old.isEmpty()) {
            if (!old.get(0).get("request_hash").equals(hash))
              throw new Api.Failure(409, "请求标识不可用于另一套餐");
            return (String) old.get(0).get("id");
          }
          // 与套餐编辑一致：先API定义再套餐，避免MySQL连接查询选择不同锁序导致死锁。
          var brief = one("SELECT api_id FROM api_plan WHERE id=?", b.planId());
          var definition =
              one("SELECT enabled FROM api_definition WHERE id=? FOR UPDATE", brief.get("api_id"));
          var p = one("SELECT * FROM api_plan WHERE id=? FOR UPDATE", b.planId());
          if (!Boolean.TRUE.equals(p.get("enabled"))
              || !Boolean.TRUE.equals(definition.get("enabled"))
              || n(p, "revision") != b.revision()) throw new Api.Failure(409, "套餐已变化，请重新确认");
          Long last =
              db.queryForObject(
                  "SELECT MAX(end_at) FROM api_subscription WHERE user_id=? AND api_id=? AND"
                      + " state='ACTIVE'",
                  Long.class,
                  user,
                  p.get("api_id"));
          long start = Math.max(System.currentTimeMillis(), last == null ? 0 : last),
              end = Math.addExact(start, n(p, "days") * 86400000L);
          String key = id();
          wallet.hold(user, key, n(p, "price_units"));
          wallet.settleHold(user, key);
          db.update(
              "INSERT INTO"
                  + " api_subscription(id,user_id,api_id,plan_id,request_key,request_hash,plan_name,price_units,quota,days,used_count,held_count,success_count,start_at,end_at,state,refund_state,refund_reason,review_note,reminded,revision,created_at)"
                  + " VALUES(?,?,?,?,?,?,?,?,?,?,0,0,0,?,?,'ACTIVE','NONE','','',FALSE,1,?)",
              key,
              user,
              p.get("api_id"),
              b.planId(),
              request,
              hash,
              p.get("name"),
              p.get("price_units"),
              p.get("quota"),
              p.get("days"),
              start,
              end,
              System.currentTimeMillis());
          wallet.notify(user, "API套餐已开通", p.get("name") + "，生效时间和到期时间请在API中心查看（北京时间）。");
          Origins.created(db, "api_subscription", key, user);
          audit(user, key, "BUY_API_PLAN");
          return key;
        });
  }

  public void requestRefund(String user, String subscription, String reason) {
    String note = text(reason, 1000, true);
    tx.executeWithoutResult(
        s -> {
          wallet.lock(user);
          var p =
              one(
                  "SELECT * FROM api_subscription WHERE id=? AND user_id=? FOR UPDATE",
                  subscription,
                  user);
          if (!p.get("state").equals("ACTIVE")
              || n(p, "success_count") > 0
              || n(p, "held_count") > 0
              || db.queryForObject(
                      "SELECT COUNT(*) FROM api_call WHERE subscription_id=? AND state='RUNNING'",
                      Integer.class,
                      subscription)
                  > 0) throw new Api.Failure(409, "仅未发生成功调用且无进行中调用的套餐可整单退款");
          if (p.get("refund_state").equals("PENDING")) return;
          db.update(
              "UPDATE api_subscription SET"
                  + " refund_state='PENDING',refund_reason=?,revision=revision+1 WHERE id=?",
              note,
              subscription);
          audit(user, subscription, "REQUEST_API_REFUND");
        });
  }

  public void reviewRefund(
      Auth.User actor, String subscription, boolean approve, String reason, long revision) {
    String note = text(reason, 1000, true);
    if (!actor.hasRole("ADMIN") && !actor.hasRole("SUPPORT"))
      throw new Api.Failure(403, "仅客服或管理员审核退款");
    tx.executeWithoutResult(
        s -> {
          var brief = one("SELECT user_id FROM api_subscription WHERE id=?", subscription);
          String user = (String) brief.get("user_id");
          if (user.equals(actor.id())) throw new Api.Failure(403, "不能审核本人申请");
          wallet.lock(user);
          var p = one("SELECT * FROM api_subscription WHERE id=? FOR UPDATE", subscription);
          if (p.get("refund_state").equals("APPROVED") && approve) return;
          if (n(p, "revision") != revision
              || !p.get("refund_state").equals("PENDING")
              || n(p, "success_count") > 0
              || n(p, "held_count") > 0) throw new Api.Failure(409, "退款状态已变化");
          if (approve) wallet.releaseHold(user, subscription, true);
          db.update(
              "UPDATE api_subscription SET"
                  + " state=?,refund_state=?,review_note=?,reviewer_id=?,reviewed_at=?,revision=revision+1"
                  + " WHERE id=?",
              approve ? "REFUNDED" : "ACTIVE",
              approve ? "APPROVED" : "REJECTED",
              note,
              actor.id(),
              System.currentTimeMillis(),
              subscription);
          wallet.notify(user, approve ? "API套餐退鼓励值完成" : "API套餐退款未通过", note);
          audit(actor.id(), subscription, "REVIEW_API_REFUND");
        });
  }

  /** 每分钟尝试计数包含失败，日额度只计成功和进行中预留，所有Key受同一账户锁保护。 */
  private void rate(String key, int maximum) {
    redis.limit(key, maximum);
    long minute = System.currentTimeMillis() / 60000;
    var rows = db.queryForList("SELECT * FROM api_rate WHERE rate_key=?", key);
    int attempts =
        rows.isEmpty() || n(rows.get(0), "window_id") != minute
            ? 0
            : ((Number) rows.get(0).get("attempts")).intValue();
    if (attempts >= maximum) throw new Api.Failure(429, "API每分钟调用次数达到上限");
    if (rows.isEmpty()) db.update("INSERT INTO api_rate VALUES(?,?,?)", key, 1, minute);
    else
      db.update(
          "UPDATE api_rate SET attempts=?,window_id=? WHERE rate_key=?", attempts + 1, minute, key);
  }

  public String keyUser(String token) {
    if (token == null || token.length() > 200) throw new Api.Failure(401, "API Key无效");
    return (String)
        one("SELECT user_id FROM api_key WHERE token_hash=? AND enabled=TRUE", crypto.digest(token))
            .get("user_id");
  }

  public String keyId(String token) {
    var rows = db.queryForList("SELECT id FROM api_key WHERE token_hash=?", crypto.digest(token));
    if (rows.isEmpty()) throw new Api.Failure(401, "API Key无效");
    return (String) rows.get(0).get("id");
  }

  private JsonNode canonical(JsonNode node) {
    if (node.isObject()) {
      var result = json.createObjectNode();
      var names = new ArrayList<String>();
      node.fieldNames().forEachRemaining(names::add);
      Collections.sort(names);
      for (String name : names) result.set(name, canonical(node.get(name)));
      return result;
    }
    if (node.isArray()) {
      var result = json.createArrayNode();
      node.forEach(x -> result.add(canonical(x)));
      return result;
    }
    return node;
  }

  record Admission(
      String id, String user, Map<String, Object> definition, Map<String, Object> cached) {}

  public Map<String, Object> invoke(
      String user, String key, String slug, String rawRequest, JsonNode input, boolean debug) {
    if (input == null) throw new Api.Failure(400, "请提供JSON输入");
    String request = requestKey(rawRequest), encoded = encode(canonical(input));
    int limit = settings.integer("api.body_limit_kb") * 1024;
    if (encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > limit)
      throw new Api.Failure(413, "API输入超过限制");
    Admission admission =
        tx.execute(
            s -> {
              wallet.lock(user);
              verified(user);
              var k = one("SELECT * FROM api_key WHERE id=? AND user_id=? FOR UPDATE", key, user);
              long now = System.currentTimeMillis();
              if (!Boolean.TRUE.equals(k.get("enabled"))
                  || (k.get("expires_at") != null && n(k, "expires_at") <= now))
                throw new Api.Failure(401, "Key已停用或过期");
              var api = one("SELECT * FROM api_definition WHERE slug=?", slug);
              String apiId = (String) api.get("id"), hash = crypto.digest(apiId + ":" + encoded);
              if (db.queryForObject(
                      "SELECT COUNT(*) FROM api_key_scope WHERE key_id=? AND api_id=?",
                      Integer.class,
                      key,
                      apiId)
                  == 0) throw new Api.Failure(403, "Key没有此API权限");
              var previous =
                  db.queryForList(
                      "SELECT * FROM api_call WHERE user_id=? AND api_id=? AND request_key=?",
                      user,
                      apiId,
                      request);
              if (!previous.isEmpty()) {
                var call = previous.get(0);
                if (!call.get("request_hash").equals(hash))
                  throw new Api.Failure(409, "同一调用标识不可更改输入");
                return new Admission((String) call.get("id"), user, api, call);
              }
              if (!Boolean.TRUE.equals(api.get("enabled"))) throw new Api.Failure(409, "API已停用");
              rate("user:" + user, settings.integer("api.account_minute_limit"));
              rate(
                  "key:" + key,
                  Math.min(
                      settings.integer("api.account_minute_limit"),
                      ((Number) k.get("minute_limit")).intValue()));
              String day =
                  Instant.ofEpochMilli(now)
                      .atZone(ZoneId.of("Asia/Shanghai"))
                      .toLocalDate()
                      .toString();
              if (n(k, "day_limit") > 0
                  && db.queryForObject(
                          "SELECT COUNT(*) FROM api_call WHERE key_id=? AND day_key=? AND state IN"
                              + " ('RUNNING','SUCCESS')",
                          Long.class,
                          key,
                          day)
                      >= n(k, "day_limit")) throw new Api.Failure(429, "Key每日额度已用完");
              var subscriptions =
                  db.queryForList(
                      "SELECT * FROM api_subscription WHERE user_id=? AND api_id=? AND"
                          + " state='ACTIVE' AND start_at<=? AND end_at>? ORDER BY start_at,id FOR"
                          + " UPDATE",
                      user,
                      apiId,
                      now,
                      now);
              Map<String, Object> subscription =
                  subscriptions.isEmpty() ? null : subscriptions.get(0);
              String sub = subscription == null ? null : (String) subscription.get("id");
              var prefs =
                  db.queryForList(
                      "SELECT * FROM api_preference WHERE user_id=? AND api_id=?", user, apiId);
              boolean payg = !prefs.isEmpty() && Boolean.TRUE.equals(prefs.get(0).get("payg")),
                  overage = !prefs.isEmpty() && Boolean.TRUE.equals(prefs.get(0).get("overage"));
              String billing;
              long price = 0;
              if (subscription != null) {
                if (subscription.get("refund_state").equals("PENDING"))
                  throw new Api.Failure(409, "套餐退款审核中，调用暂停");
                if (n(subscription, "used_count") + n(subscription, "held_count")
                    < n(subscription, "quota")) {
                  billing = "QUOTA";
                  db.update("UPDATE api_subscription SET held_count=held_count+1 WHERE id=?", sub);
                } else {
                  if (!overage) throw new Api.Failure(402, "套餐额度耗尽；默认停止，可主动开启超额扣分");
                  billing = "POINTS";
                  price = n(api, "price_units");
                }
              } else if (n(api, "price_units") == 0) billing = "FREE";
              else {
                if (!payg) throw new Api.Failure(402, "没有有效套餐，请购买或主动启用按次扣分");
                billing = "POINTS";
                price = n(api, "price_units");
              }
              String call = id();
              if (billing.equals("POINTS")) wallet.hold(user, call, price);
              db.update(
                  "INSERT INTO"
                      + " api_call(id,user_id,key_id,key_prefix,api_id,api_name,request_key,request_hash,subscription_id,billing,price_units,state,error_code,day_key,debug_call,deadline_at,created_at)"
                      + " VALUES(?,?,?,?,?,?,?,?,?,?,?,'RUNNING','',?,?,?,?)",
                  call,
                  user,
                  key,
                  k.get("prefix"),
                  apiId,
                  api.get("name"),
                  request,
                  hash,
                  sub,
                  billing,
                  price,
                  day,
                  debug,
                  now + (settings.integer("api.timeout_seconds") + 60) * 1000L,
                  now);
              Origins.created(db, "api_call", call, user);
              return new Admission(call, user, api, null);
            });
    if (admission.cached() != null) return replay(admission.cached());
    JsonNode result;
    try {
      result =
          upstream.bounded(
              admission.definition(),
              input,
              settings.integer("api.timeout_seconds"),
              limit,
              crypto,
              settings.integer("api.concurrent_limit"));
      if (encode(result).getBytes(java.nio.charset.StandardCharsets.UTF_8).length > limit)
        throw new Api.Failure(502, "API响应超过限制");
    } catch (Exception failure) {
      int status =
          failure instanceof Api.Failure f
              ? f.status
              : failure instanceof SocketTimeoutException ? 504 : 502;
      finishFailure(user, admission.id(), status == 504 ? "TIMEOUT" : "UPSTREAM_FAILED", false);
      throw new Api.Failure(status, "API调用未成功，未计费；重试请使用新的请求标识");
    }
    return tx.execute(
        s -> {
          wallet.lock(user);
          var call = one("SELECT * FROM api_call WHERE id=? FOR UPDATE", admission.id());
          if (!call.get("state").equals("RUNNING")) throw new Api.Failure(409, "调用已进入恢复终态，未重复计费");
          if (call.get("billing").equals("POINTS")) wallet.settleHold(user, admission.id());
          if (call.get("subscription_id") != null) {
            db.update(
                "UPDATE api_subscription SET success_count=success_count+1"
                    + (call.get("billing").equals("QUOTA")
                        ? ",held_count=held_count-1,used_count=used_count+1"
                        : "")
                    + " WHERE id=?",
                call.get("subscription_id"));
          }
          Map<String, Object> response =
              Map.of(
                  "requestId",
                  admission.id(),
                  "status",
                  "SUCCESS",
                  "data",
                  result,
                  "chargedPoints",
                  Commerce.decimal(n(call, "price_units")),
                  "billing",
                  call.get("billing"),
                  "mode",
                  Commerce.MODE);
          db.update(
              "UPDATE api_call SET state='SUCCESS',response_enc=?,finished_at=? WHERE id=?",
              crypto.encrypt(encode(response)),
              System.currentTimeMillis(),
              admission.id());
          return response;
        });
  }

  Map<String, Object> replay(Map<String, Object> call) {
    String state = (String) call.get("state");
    if (state.equals("SUCCESS")) {
      if (call.get("response_enc") == null) throw new Api.Failure(410, "幂等响应正文已过保留期，旧标识不会重新调用或扣费");
      try {
        return json.readValue(crypto.decrypt((String) call.get("response_enc")), Map.class);
      } catch (Exception e) {
        throw new Api.Failure(503, "调用记录暂不可读");
      }
    }
    throw new Api.Failure(
        state.equals("RUNNING") ? 409 : 502,
        state.equals("RUNNING") ? "请求进行中，请保留同一标识稍后查询" : "原调用未成功且不重复计费；如需新调用请使用新标识");
  }

  void finishFailure(String user, String callId, String error, boolean unknown) {
    tx.executeWithoutResult(
        s -> {
          wallet.lock(user);
          var c = one("SELECT * FROM api_call WHERE id=? FOR UPDATE", callId);
          if (!c.get("state").equals("RUNNING")) return;
          if (c.get("billing").equals("QUOTA"))
            db.update(
                "UPDATE api_subscription SET held_count=held_count-1 WHERE id=?",
                c.get("subscription_id"));
          if (c.get("billing").equals("POINTS")) wallet.releaseHold(user, callId, false);
          db.update(
              "UPDATE api_call SET state=?,error_code=?,finished_at=? WHERE id=?",
              unknown ? "UNKNOWN" : "FAILED",
              error,
              System.currentTimeMillis(),
              callId);
        });
  }

  public Map<String, Object> calls(String user, int page, int size, String state, String apiId) {
    if (page < 1
        || page > 1_000_000
        || size < 1
        || size > 100
        || (!state.isBlank() && !Set.of("RUNNING", "SUCCESS", "FAILED", "UNKNOWN").contains(state)))
      throw new Api.Failure(400, "查询不正确");
    String where = " WHERE 1=1";
    List<Object> args = new ArrayList<>();
    if (user != null) {
      where += " AND c.user_id=?";
      args.add(user);
    }
    if (!state.isBlank()) {
      where += " AND c.state=?";
      args.add(state);
    }
    if (!apiId.isBlank()) {
      where += " AND c.api_id=?";
      args.add(apiId);
    }
    long total =
        db.queryForObject("SELECT COUNT(*) FROM api_call c" + where, Long.class, args.toArray());
    int actual = (int) Math.min(page, Math.max(1, (total + size - 1) / size));
    args.add(size);
    args.add((actual - 1) * size);
    var items =
        Commerce.safeRows(
            db.queryForList(
                "SELECT c.id,c.user_id,c.key_id,c.key_prefix AS"
                    + " prefix,c.api_id,c.api_name,c.billing,c.price_units,c.state,c.error_code,c.day_key,c.debug_call,c.created_at,c.finished_at"
                    + " FROM api_call c LEFT JOIN api_key k ON k.id=c.key_id"
                    + where
                    + " ORDER BY c.created_at DESC,c.id LIMIT ? OFFSET ?",
                args.toArray()));
    return Map.of("items", items, "total", total, "page", actual);
  }

  @Scheduled(fixedDelay = 60000)
  public void sweep() {
    for (var c :
        db.queryForList(
            "SELECT id,user_id FROM api_call WHERE state='RUNNING' AND deadline_at<? ORDER BY"
                + " deadline_at LIMIT 100",
            System.currentTimeMillis()))
      try {
        finishFailure((String) c.get("user_id"), (String) c.get("id"), "PROCESS_INTERRUPTED", true);
      } catch (Exception ignored) {
        /* 下一轮恢复；不盲目重放不确定上游。 */
      }
    db.update(
        "UPDATE api_call SET response_enc=NULL WHERE response_enc IS NOT NULL AND finished_at<?",
        System.currentTimeMillis() - settings.integer("api.response_days") * 86400000L);
    for (var p :
        db.queryForList(
            "SELECT id,user_id FROM api_subscription WHERE state='ACTIVE' AND reminded=FALSE AND"
                + " end_at>? AND end_at<=? ORDER BY end_at LIMIT 100",
            System.currentTimeMillis(),
            System.currentTimeMillis() + settings.integer("api.expiry_reminder_days") * 86400000L))
      tx.executeWithoutResult(
          s -> {
            String user = (String) p.get("user_id");
            wallet.lock(user);
            if (db.update(
                    "UPDATE api_subscription SET reminded=TRUE WHERE id=? AND reminded=FALSE",
                    p.get("id"))
                == 1) wallet.notify(user, "API套餐即将到期", "请到API中心查看并手动续费；周期余量不结转。");
          });
  }
}
