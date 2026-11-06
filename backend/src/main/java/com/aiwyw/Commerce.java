package com.aiwyw;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 鼓励值事实以百分之一整数保存；金额以人民币分保存。所有余额写入先锁(user,mode)，再锁业务行。 核销、批次、余额、流水、通知与审计同事务提交。模拟账本命名空间不能直接切成真实经营余额。
 */
@Service
public class Commerce {
  public static final String MODE = "SIMULATED";
  private final JdbcTemplate db;
  private final Settings settings;
  private final SecureStore crypto;
  private final Catalog catalog;
  private final Storage storage;
  private final Licenses licenses;
  private final ObjectMapper json;
  private final TransactionTemplate tx;

  public Commerce(
      JdbcTemplate db,
      Settings settings,
      SecureStore crypto,
      Catalog catalog,
      Storage storage,
      Licenses licenses,
      ObjectMapper json,
      PlatformTransactionManager manager) {
    this.db = db;
    this.settings = settings;
    this.crypto = crypto;
    this.catalog = catalog;
    this.storage = storage;
    this.licenses = licenses;
    this.json = json;
    this.tx = new TransactionTemplate(manager);
  }

  public static long units(BigDecimal value) {
    try {
      if (value == null || value.signum() < 0 || value.scale() > 2) throw new ArithmeticException();
      return value.movePointRight(2).longValueExact();
    } catch (ArithmeticException invalid) {
      throw new Api.Failure(400, "金额或鼓励值必须为非负数，最多两位小数且不超出范围");
    }
  }

  public static String decimal(long value) {
    return BigDecimal.valueOf(value, 2).toPlainString();
  }

  static long number(Map<String, Object> row, String key) {
    return ((Number) row.get(key)).longValue();
  }

  private String requestKey(String key) {
    if (key == null || !key.matches("[A-Za-z0-9_-]{16,80}"))
      throw new Api.Failure(400, "请求标识不正确，请刷新后重试");
    return key;
  }

  private String note(String value) {
    String n = value == null ? "" : value.trim();
    if (n.isBlank() || n.length() > 1000) throw new Api.Failure(400, "请填写1—1000字处理原因");
    return n;
  }

  private String encode(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private <T> T decode(String value, Class<T> type) {
    try {
      return json.readValue(value, type);
    } catch (Exception e) {
      throw new IllegalStateException("Invalid commerce snapshot", e);
    }
  }

  private Map<String, Object> one(String sql, Object... args) {
    var rows = db.queryForList(sql, args);
    if (rows.isEmpty()) throw new Api.Failure(404, "记录不存在");
    return rows.get(0);
  }

  /** 唯一锁顺序是账户→捐赠/购买→批次；同一账户并发消费不能读到相同可扣余额。 */
  /** 先锁真实账户行再取钱包行，避免MySQL重复INSERT的共享锁升级死锁。锁定读始终读取最新提交值。 */
  void lock(String user) {
    db.queryForMap("SELECT id FROM app_user WHERE id=? FOR UPDATE", user);
    var rows =
        db.queryForList(
            "SELECT balance_units FROM wallet_account WHERE user_id=? AND mode=? FOR UPDATE",
            user,
            MODE);
    if (rows.isEmpty())
      db.update("INSERT INTO wallet_account(user_id,mode,balance_units) VALUES(?,?,0)", user, MODE);
    expire(user);
  }

  private long balance(String user) {
    return db.queryForObject(
        "SELECT balance_units FROM wallet_account WHERE user_id=? AND mode=? FOR UPDATE",
        Long.class,
        user,
        MODE);
  }

  private void ledger(String user, String kind, String source, long delta, long cost) {
    long next = Math.addExact(balance(user), delta);
    if (next < 0) throw new Api.Failure(409, "鼓励值不足");
    db.update(
        "UPDATE wallet_account SET balance_units=? WHERE user_id=? AND mode=?", next, user, MODE);
    String ledgerId = UUID.randomUUID().toString();
    db.update(
        "INSERT INTO"
            + " wallet_ledger(id,user_id,mode,kind,source_id,delta_units,balance_after,cost_fen,created_at)"
            + " VALUES(?,?,?,?,?,?,?,?,?)",
        ledgerId,
        user,
        MODE,
        kind,
        source,
        delta,
        next,
        cost,
        System.currentTimeMillis());
    Origins.created(db, "wallet_ledger", ledgerId, "SYSTEM");
  }

  /** 已认证账户按北京时间首次登录领取；统一账户锁串行化跨设备并发，主键为最终幂等边界。 */
  public boolean dailyLogin(String user, java.time.Instant instant) {
    String day = instant.atZone(java.time.ZoneId.of("Asia/Shanghai")).toLocalDate().toString();
    return tx.execute(
        status -> {
          lock(user);
          if (db.queryForObject(
                  "SELECT COUNT(*) FROM daily_login_reward WHERE user_id=? AND mode=? AND"
                      + " day_key=?",
                  Integer.class,
                  user,
                  MODE,
                  day)
              > 0) return false;
          String batch = UUID.randomUUID().toString();
          long now = instant.toEpochMilli();
          db.update(
              "INSERT INTO daily_login_reward VALUES(?,?,?,?,100,?)", user, MODE, day, batch, now);
          db.update(
              "INSERT INTO"
                  + " wallet_batch(id,user_id,mode,source_type,source_id,total_units,remaining_units,cost_fen,remaining_cost_fen,expires_at,created_at)"
                  + " VALUES(?,?,?,'DAILY_LOGIN',?,100,100,0,0,NULL,?)",
              batch,
              user,
              MODE,
              user + ":" + day,
              now);
          ledger(user, "DAILY_LOGIN", batch, 100, 0);
          Origins.created(db, "wallet_batch", batch, "SYSTEM");
          return true;
        });
  }

  private void audit(String actor, String entity, String action, String detail) {
    AuditLog.write(db, "finance_audit", actor, entity, action, detail);
  }

  void notify(String user, String title, String text) {
    String notification = UUID.randomUUID().toString();
    db.update(
        "INSERT INTO member_notification(id,user_id,title,body,created_at) VALUES(?,?,?,?,?)",
        notification,
        user,
        title,
        text,
        System.currentTimeMillis());
    Origins.created(db, "member_notification", notification, "SYSTEM");
    var email =
        db.queryForList(
            "SELECT email FROM app_user WHERE id=? AND business_email=TRUE AND email_verified_at IS"
                + " NOT NULL",
            String.class,
            user);
    if (!email.isEmpty()) {
      long now = System.currentTimeMillis();
      db.update(
          "INSERT INTO"
              + " mail_outbox(id,recipient,subject,body_enc,status,attempts,next_at,created_at)"
              + " VALUES(?,?,?, ?,'PENDING',0,?,?)",
          UUID.randomUUID().toString(),
          email.get(0),
          title,
          crypto.encrypt(text),
          now,
          now);
    }
  }

  /** 到期边界为expires_at<=now；所有读写余额之前处理，定时任务只是辅助及时展示。 */
  private void expire(String user) {
    long now = System.currentTimeMillis();
    for (var batch :
        db.queryForList(
            "SELECT * FROM wallet_batch WHERE user_id=? AND mode=? AND expires_at<=? AND"
                + " remaining_units>0 ORDER BY expires_at,created_at,id FOR UPDATE",
            user,
            MODE,
            now)) {
      ledger(
          user,
          "EXPIRE",
          (String) batch.get("id"),
          -number(batch, "remaining_units"),
          -number(batch, "remaining_cost_fen"));
      db.update(
          "UPDATE wallet_batch SET remaining_units=0,remaining_cost_fen=0 WHERE id=?",
          batch.get("id"));
    }
  }

  @Scheduled(fixedDelay = 60000)
  public void sweep() {
    db.update(
        "UPDATE recharge_order SET state='CLOSED' WHERE mode=? AND state='PENDING' AND"
            + " expires_at<=?",
        MODE,
        System.currentTimeMillis());
    for (String user :
        db.queryForList(
            "SELECT DISTINCT user_id FROM wallet_batch WHERE mode=? AND expires_at<=? AND"
                + " remaining_units>0 LIMIT 100",
            String.class,
            MODE,
            System.currentTimeMillis()))
      try {
        tx.executeWithoutResult(s -> lock(user));
      } catch (Exception failure) {
        /* 单户失败不阻断其他户；下次交易仍会核实到期状态。 */
      }
  }

  public Map<String, Object> balanceSummary(String user) {
    return tx.execute(
        status -> {
          lock(user);
          return Map.of("mode", MODE, "balance", decimal(balance(user)));
        });
  }

  public Map<String, Object> summary(String user) {
    return tx.execute(
        s -> {
          lock(user);
          return Map.of(
              "mode",
              MODE,
              "balance",
              decimal(balance(user)),
              "batches",
              safeRows(
                  db.queryForList(
                      "SELECT id,source_type,total_units,remaining_units,expires_at,created_at FROM"
                          + " wallet_batch WHERE user_id=? AND mode=? ORDER BY CASE WHEN expires_at"
                          + " IS NULL THEN 1 ELSE 0 END,expires_at,created_at,id",
                      user,
                      MODE)));
        });
  }

  /** API与套餐共用原钱包批次顺序。调用方必须已在事务中锁账户；预留不等于收入。 */
  void hold(String user, String id, long units) {
    hold(user, id, units, "API_HOLD");
  }

  private void hold(String user, String id, long units, String kind) {
    if (!org.springframework.transaction.support.TransactionSynchronizationManager
        .isActualTransactionActive())
      throw new IllegalStateException("Wallet hold requires transaction");
    if (units < 0 || balance(user) < units) throw new Api.Failure(402, "可用鼓励值不足");
    long rest = units, cost = 0;
    for (var b :
        db.queryForList(
            "SELECT * FROM wallet_batch WHERE user_id=? AND mode=? AND remaining_units>0 ORDER BY"
                + " CASE WHEN expires_at IS NULL THEN 1 ELSE 0 END,expires_at,created_at,id FOR"
                + " UPDATE",
            user,
            MODE)) {
      long take = Math.min(rest, number(b, "remaining_units"));
      if (take == 0) break;
      long fen =
          BigInteger.valueOf(number(b, "remaining_cost_fen"))
              .multiply(BigInteger.valueOf(take))
              .divide(BigInteger.valueOf(number(b, "remaining_units")))
              .longValueExact();
      db.update(
          "UPDATE wallet_batch SET"
              + " remaining_units=remaining_units-?,remaining_cost_fen=remaining_cost_fen-? WHERE"
              + " id=?",
          take,
          fen,
          b.get("id"));
      db.update(
          "INSERT INTO wallet_hold_part(id,hold_id,batch_id,units,cost_fen,expires_at)"
              + " VALUES(?,?,?,?,?,?)",
          UUID.randomUUID().toString(),
          id,
          b.get("id"),
          take,
          fen,
          b.get("expires_at"));
      rest -= take;
      cost = Math.addExact(cost, fen);
    }
    if (rest != 0) throw new IllegalStateException("Wallet and batches diverged");
    db.update(
        "INSERT INTO wallet_hold(id,user_id,mode,units,cost_fen,state,created_at)"
            + " VALUES(?,?,?,?,?,'HELD',?)",
        id,
        user,
        MODE,
        units,
        cost,
        System.currentTimeMillis());
    ledger(user, kind, id, -units, -cost);
  }

  void settleHold(String user, String id) {
    settleHold(user, id, "API_SETTLE");
  }

  private void settleHold(String user, String id, String kind) {
    if (db.update(
            "UPDATE wallet_hold SET state='SETTLED' WHERE id=? AND user_id=? AND state='HELD'",
            id,
            user)
        != 1) throw new Api.Failure(409, "预留已变化");
    ledger(user, kind, id, 0, 0);
  }

  /** 失败释放不延长鼓励值原期限；正式审核退款才对已过期部分延长既定30天。 */
  void releaseHold(String user, String id, boolean refund) {
    var h = one("SELECT * FROM wallet_hold WHERE id=? AND user_id=? FOR UPDATE", id, user);
    String target = refund ? "REFUNDED" : "RELEASED", expected = refund ? "SETTLED" : "HELD";
    if (h.get("state").equals(target)) return;
    if (!h.get("state").equals(expected)) throw new Api.Failure(409, "预留状态不允许退回");
    long now = System.currentTimeMillis();
    for (var p :
        db.queryForList(
            "SELECT * FROM wallet_hold_part WHERE hold_id=? ORDER BY batch_id,id", id)) {
      Long expiry = p.get("expires_at") == null ? null : ((Number) p.get("expires_at")).longValue();
      if (expiry != null && expiry <= now) {
        // 原批次未预留余额可能已经到期并写入唯一EXPIRE流水。过期预留另建批次，
        // 失败释放保留原到期日并立即到期；审核退款才延长有效期，避免重复流水导致释放回滚。
        String batchId = UUID.randomUUID().toString();
        db.update(
            "INSERT INTO"
                + " wallet_batch(id,user_id,mode,source_type,source_id,total_units,remaining_units,cost_fen,remaining_cost_fen,expires_at,created_at)"
                + " VALUES(?,?,?,?,?,?,?,?,?,?,?)",
            batchId,
            user,
            MODE,
            refund ? "API_REFUND" : "API_RELEASE",
            p.get("id"),
            p.get("units"),
            p.get("units"),
            p.get("cost_fen"),
            p.get("cost_fen"),
            refund ? now + settings.integer("wallet.refund_expired_days") * 86400000L : expiry,
            now);
        Origins.created(db, "wallet_batch", batchId, "SYSTEM");
      } else
        db.update(
            "UPDATE wallet_batch SET"
                + " remaining_units=remaining_units+?,remaining_cost_fen=remaining_cost_fen+? WHERE"
                + " id=?",
            p.get("units"),
            p.get("cost_fen"),
            p.get("batch_id"));
    }
    ledger(
        user, refund ? "API_REFUND" : "API_RELEASE", id, number(h, "units"), number(h, "cost_fen"));
    db.update("UPDATE wallet_hold SET state=? WHERE id=?", target, id);
    expire(user);
  }

  public record Adjustment(
      String userId, BigDecimal points, int validDays, String reason, String requestKey) {}

  /** 管理员例外处理必须独立留痕；赠分成本0，扣分按原批次顺序，不能凭此伪造捐赠或已用套餐退款。 */
  public String adjust(Auth.User actor, Adjustment b) {
    if (!actor.hasRole("ADMIN") || !actor.mfaVerified())
      throw new Api.Failure(403, "仅完成当前安全策略的管理员可调整鼓励值");
    if (actor.id().equals(b.userId())) throw new Api.Failure(403, "不能调整本人账户");
    String reason = MeteredApi.text(b.reason(), 1000, true),
        request = MeteredApi.requestKey(b.requestKey());
    if (b.points() == null || b.points().signum() == 0 || b.validDays() < 0 || b.validDays() > 3650)
      throw new Api.Failure(400, "请输入非零鼓励值及正确有效期");
    long amount = units(b.points().abs()), delta = b.points().signum() < 0 ? -amount : amount;
    String hash = crypto.digest(b.userId() + ":" + delta + ":" + b.validDays() + ":" + reason);
    try {
      return tx.execute(
          s -> {
            lock(b.userId());
            var old =
                db.queryForList(
                    "SELECT id,request_hash FROM wallet_adjustment WHERE actor_id=? AND"
                        + " request_key=?",
                    actor.id(),
                    request);
            if (!old.isEmpty()) {
              if (!old.get(0).get("request_hash").equals(hash))
                throw new Api.Failure(409, "调整标识不能更换参数");
              return (String) old.get(0).get("id");
            }
            String key = UUID.randomUUID().toString();
            long now = System.currentTimeMillis();
            if (delta > 0) {
              String batchId = UUID.randomUUID().toString();
              db.update(
                  "INSERT INTO"
                      + " wallet_batch(id,user_id,mode,source_type,source_id,total_units,remaining_units,cost_fen,remaining_cost_fen,expires_at,created_at)"
                      + " VALUES(?,?,?,'ADJUSTMENT',?,?,?,0,0,?,?)",
                  batchId,
                  b.userId(),
                  MODE,
                  key,
                  amount,
                  amount,
                  b.validDays() == 0 ? null : now + b.validDays() * 86400000L,
                  now);
              Origins.created(db, "wallet_batch", batchId, actor.id());
              ledger(b.userId(), "ADJUST_CREDIT", key, delta, 0);
            } else {
              hold(b.userId(), key, amount, "ADJUST_DEBIT");
              settleHold(b.userId(), key, "ADJUST_DONE");
            }
            db.update(
                "INSERT INTO wallet_adjustment VALUES(?,?,?,?,?,?,?,?,?,?)",
                key,
                b.userId(),
                actor.id(),
                MODE,
                delta,
                b.validDays(),
                reason,
                request,
                hash,
                now);
            Origins.created(db, "wallet_adjustment", key, actor.id());
            audit(actor.id(), key, "ADJUST_POINTS", reason);
            notify(b.userId(), "鼓励值人工调整", decimal(delta) + " 鼓励值。原因：" + reason);
            return key;
          });
    } catch (org.springframework.dao.DuplicateKeyException conflict) {
      throw new Api.Failure(409, "调整请求已处理，请重新读取");
    }
  }

  public record Recharge(BigDecimal amount, String packageId, String requestKey) {}

  public Map<String, Object> createRecharge(String user, Recharge b) {
    String key = requestKey(b.requestKey()),
        hash =
            crypto.digest(
                encode(
                    List.of(
                        b.amount() == null ? "" : b.amount().stripTrailingZeros().toPlainString(),
                        b.packageId() == null ? "" : b.packageId())));
    return tx.execute(
        s -> {
          lock(user);
          var prior =
              db.queryForList(
                  "SELECT * FROM recharge_order WHERE user_id=? AND mode=? AND request_key=?",
                  user,
                  MODE,
                  key);
          if (!prior.isEmpty()) {
            if (!prior.get(0).get("request_hash").equals(hash))
              throw new Api.Failure(409, "同一请求标识不能用于不同捐赠");
            return safeRow(prior.get(0));
          }
          long price, points;
          int days = 0;
          String packageName = "普通捐赠", packageId = b.packageId();
          int rate = settings.integer("wallet.points_per_yuan");
          if (packageId != null && !packageId.isBlank()) {
            var pack = one("SELECT * FROM points_package WHERE id=? AND enabled=TRUE", packageId);
            price = number(pack, "price_fen");
            points = Math.multiplyExact(price, rate);
            days = ((Number) pack.get("valid_days")).intValue();
            packageName = (String) pack.get("name");
          } else {
            if (b.amount() == null || b.amount().stripTrailingZeros().scale() > 0)
              throw new Api.Failure(400, "普通捐赠请输入整数元");
            price = units(b.amount());
            if (price < settings.integer("wallet.min_recharge_yuan") * 100L
                || price > settings.integer("wallet.max_recharge_yuan") * 100L)
              throw new Api.Failure(400, "捐赠金额超出后台配置范围");
            points = Math.multiplyExact(price, rate);
          }
          String id = UUID.randomUUID().toString();
          long now = System.currentTimeMillis();
          db.update(
              "INSERT INTO"
                  + " recharge_order(id,user_id,mode,request_key,request_hash,package_id,package_name,channel,price_fen,points_units,valid_days,rate,threshold_fen,state,declaration,review_note,created_at,expires_at)"
                  + " VALUES(?,?,?,?,?,?,?,'MOCK',?,?,?,?,?,'PENDING','','',?,?)",
              id,
              user,
              MODE,
              key,
              hash,
              packageId,
              packageName,
              price,
              points,
              days,
              rate,
              settings.integer("wallet.large_payment_yuan") * 100L,
              now,
              now + settings.integer("wallet.order_timeout_minutes") * 60000L);
          // 备注码只用于人工匹配，同一幂等请求返回原码；不唯一、不代替到账核验。
          db.update(
              "UPDATE recharge_order SET payment_memo=? WHERE id=?",
              String.format(
                  java.util.Locale.ROOT, "%04d", new java.security.SecureRandom().nextInt(10000)),
              id);
          Origins.created(db, "recharge_order", id, user);
          return safeRow(one("SELECT * FROM recharge_order WHERE id=?", id));
        });
  }

  public void declare(String user, String id, String evidence, String text) {
    if (text != null && text.length() > 500) throw new Api.Failure(400, "付款说明最多500字");
    tx.executeWithoutResult(
        s -> {
          lock(user);
          var order =
              one(
                  "SELECT * FROM recharge_order WHERE id=? AND user_id=? AND mode=? FOR UPDATE",
                  id,
                  user,
                  MODE);
          String state = (String) order.get("state");
          if (state.equals("POSTED") || state.equals("DECLARED")) return;
          long now = System.currentTimeMillis();
          if (!Set.of("PENDING", "CLOSED").contains(state))
            throw new Api.Failure(409, "当前订单不能声明付款");
          if (evidence != null && !evidence.isBlank()) {
            var asset =
                one(
                    "SELECT purpose,status,created_by FROM storage_asset WHERE id=? FOR UPDATE",
                    evidence);
            if (!asset.get("purpose").equals("RECEIPT")
                || !asset.get("status").equals("READY")
                || !asset.get("created_by").equals(user)) throw new Api.Failure(403, "付款截图不属于当前账户");
            db.update(
                "DELETE FROM storage_reference WHERE owner_type='RECHARGE' AND owner_id=?", id);
            db.update(
                "INSERT INTO storage_reference(asset_id,owner_type,owner_id)"
                    + " VALUES(?,'RECHARGE',?)",
                evidence,
                id);
          }
          boolean closed = state.equals("CLOSED") || number(order, "expires_at") <= now;
          db.update(
              "UPDATE recharge_order SET state=?,declared_at=?,evidence_id=?,declaration=? WHERE"
                  + " id=?",
              closed ? "CLOSED" : "DECLARED",
              now,
              evidence,
              text == null ? "" : text,
              id);
          audit(
              user,
              id,
              closed ? "LATE_DECLARATION" : "DECLARE_PAYMENT",
              "user statement, not receipt confirmation");
        });
  }

  /** 实到不同于应付时转普通永久鼓励值；门槛取应付、实到的较大值，禁止拆低审核金额绕过管理员。 */
  public void reviewRecharge(
      Auth.User actor, String id, BigDecimal actual, boolean approve, String reason) {
    String explanation = note(reason);
    long amount = approve ? units(actual) : 0;
    tx.executeWithoutResult(
        s -> {
          var brief = one("SELECT user_id FROM recharge_order WHERE id=? AND mode=?", id, MODE);
          String user = (String) brief.get("user_id");
          if (actor.id().equals(user)) throw new Api.Failure(403, "不能审核本人的交易申请");
          lock(user);
          var order = one("SELECT * FROM recharge_order WHERE id=? FOR UPDATE", id);
          if (order.get("state").equals("POSTED")) {
            if (approve && number(order, "actual_fen") == amount) return;
            throw new Api.Failure(409, "订单已入账，不能覆盖核销结果");
          }
          boolean admin = actor.hasRole("ADMIN") && actor.mfaVerified();
          if (!admin && !actor.hasRole("SUPPORT")) throw new Api.Failure(403, "仅客服或管理员可核销");
          if ((!order.get("state").equals("DECLARED")
                  || Math.max(number(order, "price_fen"), amount) >= number(order, "threshold_fen"))
              && !admin) throw new Api.Failure(403, "大额、关闭或异常订单需管理员审核");
          if (approve
              && (amount < 1 || amount > settings.integer("wallet.max_recharge_yuan") * 100L))
            throw new Api.Failure(400, "实际金额超出配置范围");
          if (!approve) {
            db.update(
                "UPDATE recharge_order SET state='REJECTED',reviewer_id=?,review_note=? WHERE id=?",
                actor.id(),
                explanation,
                id);
            audit(actor.id(), id, "REJECT_RECHARGE", explanation);
            notify(user, "捐赠审核未通过", explanation);
            return;
          }
          boolean equal = amount == number(order, "price_fen");
          long points =
              equal
                  ? number(order, "points_units")
                  : Math.multiplyExact(amount, number(order, "rate"));
          int days = equal ? ((Number) order.get("valid_days")).intValue() : 0;
          long now = System.currentTimeMillis();
          Long expiry = days == 0 ? null : now + days * 86400000L;
          db.update(
              "INSERT INTO"
                  + " wallet_batch(id,user_id,mode,source_type,source_id,total_units,remaining_units,cost_fen,remaining_cost_fen,expires_at,created_at)"
                  + " VALUES(?,?,?,'RECHARGE',?,?,?,?,?,?,?)",
              UUID.randomUUID().toString(),
              user,
              MODE,
              id,
              points,
              points,
              amount,
              amount,
              expiry,
              now);
          ledger(user, "RECHARGE", id, points, amount);
          db.update(
              "UPDATE recharge_order SET"
                  + " state='POSTED',actual_fen=?,reviewer_id=?,review_note=?,posted_at=? WHERE"
                  + " id=?",
              amount,
              actor.id(),
              explanation,
              now,
              id);
          String batchId =
              db.queryForObject(
                  "SELECT id FROM wallet_batch WHERE mode=? AND source_type='RECHARGE' AND"
                      + " source_id=?",
                  String.class,
                  MODE,
                  id);
          Origins.created(db, "wallet_batch", batchId, actor.id());
          audit(actor.id(), id, "POST_RECHARGE", explanation);
          notify(user, "模拟捐赠鼓励值已到账", decimal(points) + " 鼓励值已到账。此记录属于模拟账本，不代表真实收款。");
        });
  }

  public record PackageInput(
      String name,
      String nameEn,
      BigDecimal price,
      BigDecimal points,
      int validDays,
      boolean enabled,
      int position,
      long revision) {}

  public String savePackage(String id, PackageInput p, String actor) {
    if (p.name() == null
        || p.name().isBlank()
        || p.name().length() > 100
        || p.nameEn() == null
        || p.nameEn().length() > 100
        || p.validDays() < 1
        || p.validDays() > 36500
        || p.position() < 0
        || p.position() > 10000) throw new Api.Failure(400, "捐赠档位名称、有效期或顺序不正确");
    long price = units(p.price()), points = Math.multiplyExact(price, 10L);
    if (price < 1 || price > 100000000 || points < 1 || points > 100000000000L)
      throw new Api.Failure(400, "捐赠档位价格或鼓励值超出范围");
    return tx.execute(
        s -> {
          db.queryForObject("SELECT revision FROM finance_guard WHERE id=1 FOR UPDATE", Long.class);
          String key = id == null ? UUID.randomUUID().toString() : id;
          int active =
              db.queryForObject(
                  "SELECT COUNT(*) FROM points_package WHERE enabled=TRUE AND id<>?",
                  Integer.class,
                  key);
          if (p.enabled() && active >= settings.integer("wallet.visible_packages"))
            throw new Api.Failure(409, "前台展示捐赠档位已达配置上限");
          if (id == null)
            db.update(
                "INSERT INTO"
                    + " points_package(id,name,name_en,price_fen,points_units,valid_days,enabled,position,revision,created_at)"
                    + " VALUES(?,?,?,?,?,?,?,?,1,?)",
                key,
                p.name().trim(),
                p.nameEn().trim(),
                price,
                points,
                p.validDays(),
                p.enabled(),
                p.position(),
                System.currentTimeMillis());
          else if (db.update(
                  "UPDATE points_package SET"
                      + " name=?,name_en=?,price_fen=?,points_units=?,valid_days=?,enabled=?,position=?,revision=revision+1"
                      + " WHERE id=? AND revision=?",
                  p.name().trim(),
                  p.nameEn().trim(),
                  price,
                  points,
                  p.validDays(),
                  p.enabled(),
                  p.position(),
                  id,
                  p.revision())
              != 1) throw new Api.Failure(409, "捐赠档位已变化，请重新读取");
          if (id == null) Origins.created(db, "points_package", key, actor);
          audit(actor, key, "SAVE_PACKAGE", "package configuration");
          return key;
        });
  }

  public List<Map<String, Object>> packages(boolean admin) {
    return safeRows(
        db.queryForList(
            "SELECT id,REPLACE(REPLACE(name,'积分','鼓励值'),'充值','捐赠') AS"
                + " name,name_en,price_fen,(price_fen*10) AS"
                + " points_units,valid_days,enabled,position,revision,created_at FROM"
                + " points_package"
                + (admin ? "" : " WHERE enabled=TRUE")
                + " ORDER BY position,id"
                + (admin ? "" : " LIMIT " + settings.integer("wallet.visible_packages"))));
  }

  /** 财务判断读取数据库当前发布指针，不能使用30秒展示缓存判定上下架与价格。 */
  private Catalog.Product activeProduct(String slug, boolean locked) {
    var row =
        one(
            "SELECT v.snapshot_json FROM catalog_product p JOIN catalog_publication v ON"
                + " p.active_publication=v.id WHERE p.slug=? AND p.status='PUBLISHED'"
                + (locked ? " FOR UPDATE" : ""),
            slug);
    return decode((String) row.get("snapshot_json"), Catalog.Product.class);
  }

  private String major(String version) {
    String first = version.split("\\.", 2)[0];
    return first.isBlank() ? version : first;
  }

  public Map<String, Object> quote(String user, String slug) {
    var p = activeProduct(slug, false);
    var owned =
        db.queryForList(
            "SELECT id FROM purchase_order WHERE user_id=? AND mode=? AND product_id=? AND"
                + " major_version=? AND state='ACTIVE'",
            String.class,
            user,
            MODE,
            p.id(),
            major(p.content().version()));
    return Map.of(
        "product",
        p,
        "balance",
        summary(user).get("balance"),
        "owned",
        !owned.isEmpty(),
        "purchaseId",
        owned.isEmpty() ? "" : owned.get(0),
        "mode",
        MODE);
  }

  public record Purchase(String slug, String publicationId, String requestKey) {}

  public String purchase(String user, Purchase input) {
    String key = requestKey(input.requestKey()),
        hash = crypto.digest(input.slug() + ":" + input.publicationId());
    return tx.execute(
        s -> {
          lock(user);
          var repeat =
              db.queryForList(
                  "SELECT purchase_id AS id,request_hash FROM purchase_request WHERE user_id=? AND"
                      + " mode=? AND request_key=?",
                  user,
                  MODE,
                  key);
          if (!repeat.isEmpty()) {
            if (!repeat.get(0).get("request_hash").equals(hash))
              throw new Api.Failure(409, "同一标识不能用于不同兑换");
            return (String) repeat.get(0).get("id");
          }
          var product = activeProduct(input.slug(), true);
          if (!product.status().equals("PUBLISHED")
              || !Objects.equals(product.publicationId(), input.publicationId()))
            throw new Api.Failure(409, "应用已下架或发布信息已更新，请重新确认价格");
          var existing =
              db.queryForList(
                  "SELECT id FROM purchase_order WHERE user_id=? AND mode=? AND product_id=? AND"
                      + " major_version=? AND state='ACTIVE'",
                  String.class,
                  user,
                  MODE,
                  product.id(),
                  major(product.content().version()));
          if (!existing.isEmpty()) {
            db.update(
                "INSERT INTO purchase_request(user_id,mode,request_key,request_hash,purchase_id)"
                    + " VALUES(?,?,?,?,?)",
                user,
                MODE,
                key,
                hash,
                existing.get(0));
            return existing.get(0);
          }
          var privateData =
              decode(
                  (String)
                      one(
                              "SELECT private_json FROM catalog_publication WHERE id=?",
                              product.publicationId())
                          .get("private_json"),
                  Catalog.ProductInput.class);
          if (!Set.of("DOWNLOAD", "EXTERNAL", "LICENSE").contains(privateData.deliveryMode())
              || privateData.downloadUrl().isBlank()) throw new Api.Failure(409, "此商品交付能力尚未开放");
          long price = units(product.content().pricePoints());
          if (price <= 0 && !privateData.deliveryMode().equals("LICENSE"))
            throw new Api.Failure(400, "免费应用无需鼓励值兑换");
          if (balance(user) < price) throw new Api.Failure(409, "可用鼓励值不足，请先捐赠");
          String id = UUID.randomUUID().toString();
          long rest = price, cost = 0;
          for (var batch :
              db.queryForList(
                  "SELECT * FROM wallet_batch WHERE user_id=? AND mode=? AND remaining_units>0"
                      + " ORDER BY CASE WHEN expires_at IS NULL THEN 1 ELSE 0"
                      + " END,expires_at,created_at,id FOR UPDATE",
                  user,
                  MODE)) {
            long take = Math.min(rest, number(batch, "remaining_units"));
            if (take == 0) break;
            long allocatedCost =
                BigInteger.valueOf(number(batch, "remaining_cost_fen"))
                    .multiply(BigInteger.valueOf(take))
                    .divide(BigInteger.valueOf(number(batch, "remaining_units")))
                    .longValueExact();
            if (allocatedCost < 0 || allocatedCost > number(batch, "remaining_cost_fen"))
              throw new IllegalStateException("Invalid batch cost allocation");
            db.update(
                "UPDATE wallet_batch SET"
                    + " remaining_units=remaining_units-?,remaining_cost_fen=remaining_cost_fen-?"
                    + " WHERE id=?",
                take,
                allocatedCost,
                batch.get("id"));
            db.update(
                "INSERT INTO wallet_allocation(id,purchase_id,batch_id,units,cost_fen,expires_at)"
                    + " VALUES(?,?,?,?,?,?)",
                UUID.randomUUID().toString(),
                id,
                batch.get("id"),
                take,
                allocatedCost,
                batch.get("expires_at"));
            rest -= take;
            cost += allocatedCost;
          }
          if (rest != 0) throw new IllegalStateException("Wallet and batches diverged");
          db.update(
              "INSERT INTO"
                  + " purchase_order(id,user_id,mode,request_key,request_hash,product_id,major_version,publication_id,snapshot_json,price_units,cost_fen,state,created_at)"
                  + " VALUES(?,?,?,?,?,?,?,?,?,?,?,'ACTIVE',?)",
              id,
              user,
              MODE,
              key,
              hash,
              product.id(),
              major(product.content().version()),
              product.publicationId(),
              encode(product),
              price,
              cost,
              System.currentTimeMillis());
          db.update(
              "INSERT INTO purchase_request(user_id,mode,request_key,request_hash,purchase_id)"
                  + " VALUES(?,?,?,?,?)",
              user,
              MODE,
              key,
              hash,
              id);
          db.update(
              "UPDATE purchase_order SET sort_title=? WHERE id=?", product.content().nameZh(), id);
          if (privateData.deliveryMode().equals("LICENSE"))
            licenses.issue(id, product.publicationId());
          ledger(user, "PURCHASE", id, -price, -cost);
          Origins.created(db, "purchase_order", id, user);
          audit(user, id, "PURCHASE", "points=" + decimal(price));
          notify(user, "兑换成功", product.content().nameZh() + "已加入我的资源。");
          return id;
        });
  }

  /** 不信任客户指定发布ID；只能交付已购快照，或同一大版本的当前已发布更新，包含已下架老权益。 */
  public String delivery(String user, String id, boolean original) {
    return tx.execute(
        s -> {
          lock(user);
          var order =
              one(
                  "SELECT * FROM purchase_order WHERE id=? AND user_id=? AND mode=? FOR UPDATE",
                  id,
                  user,
                  MODE);
          if (!order.get("state").equals("ACTIVE")) throw new Api.Failure(403, "该订单权益已撤销");
          String publication = (String) order.get("publication_id");
          if (!original) {
            var current =
                db.queryForList(
                    "SELECT v.id,v.private_json FROM catalog_product p JOIN catalog_publication v"
                        + " ON p.active_publication=v.id WHERE p.id=?",
                    order.get("product_id"));
            if (!current.isEmpty()) {
              var p =
                  decode((String) current.get(0).get("private_json"), Catalog.ProductInput.class);
              if (major(p.version()).equals(order.get("major_version"))
                  && Set.of("DOWNLOAD", "EXTERNAL", "LICENSE").contains(p.deliveryMode())
                  && !p.downloadUrl().isBlank()) publication = (String) current.get(0).get("id");
            }
          }
          var content =
              decode(
                  (String)
                      one("SELECT private_json FROM catalog_publication WHERE id=?", publication)
                          .get("private_json"),
                  Catalog.ProductInput.class);
          String url = content.downloadUrl();
          if (url.isBlank()) throw new Api.Failure(409, "资源暂不可用，请联系客服补交");
          if (url.startsWith("asset:")) {
            url = storage.ticket(url.substring(6), publication);
            db.update(
                "UPDATE storage_download_ticket SET entitlement_id=? WHERE token_hash=?",
                id,
                crypto.digest(url.substring(url.lastIndexOf('/') + 1)));
          }
          var snapshot = decode((String) order.get("snapshot_json"), Catalog.Product.class);
          catalog.metric(snapshot, "user:" + user, "DOWNLOAD");
          return url;
        });
  }

  public void requestRefund(String user, String id, String reason) {
    String explanation = note(reason);
    tx.executeWithoutResult(
        s -> {
          lock(user);
          var order =
              one(
                  "SELECT state FROM purchase_order WHERE id=? AND user_id=? AND mode=? FOR UPDATE",
                  id,
                  user,
                  MODE);
          if (!order.get("state").equals("ACTIVE")) throw new Api.Failure(409, "订单已退款");
          var existing =
              db.queryForList("SELECT state FROM refund_request WHERE purchase_id=?", id);
          if (!existing.isEmpty()) {
            if (!existing.get(0).get("state").equals("REJECTED")) return;
            db.update(
                "UPDATE refund_request SET"
                    + " reason=?,state='PENDING',revision=revision+1,created_at=? WHERE"
                    + " purchase_id=?",
                explanation,
                System.currentTimeMillis(),
                id);
          } else
            db.update(
                "INSERT INTO"
                    + " refund_request(purchase_id,user_id,reason,state,review_note,revision,created_at)"
                    + " VALUES(?,?,?,'PENDING','',1,?)",
                id,
                user,
                explanation,
                System.currentTimeMillis());
          if (existing.isEmpty()) Origins.created(db, "refund_request", id, user);
          audit(user, id, "REQUEST_REFUND", explanation);
        });
  }

  public void reviewRefund(
      Auth.User actor, String id, boolean approve, String reason, long revision) {
    if (!actor.hasRole("SUPPORT") && !(actor.hasRole("ADMIN") && actor.mfaVerified()))
      throw new Api.Failure(403, "仅客服或管理员可审核退款");
    String explanation = note(reason);
    tx.executeWithoutResult(
        s -> {
          var brief = one("SELECT user_id FROM purchase_order WHERE id=? AND mode=?", id, MODE);
          String user = (String) brief.get("user_id");
          if (actor.id().equals(user)) throw new Api.Failure(403, "不能审核本人的交易申请");
          lock(user);
          var refund = one("SELECT * FROM refund_request WHERE purchase_id=? FOR UPDATE", id);
          if (refund.get("state").equals("APPROVED") && approve) return;
          if (!refund.get("state").equals("PENDING") || number(refund, "revision") != revision)
            throw new Api.Failure(409, "退款申请已变化，请重新读取");
          var order = one("SELECT * FROM purchase_order WHERE id=? FOR UPDATE", id);
          if (!order.get("state").equals("ACTIVE")) throw new Api.Failure(409, "订单权益已撤销");
          if (approve) {
            long now = System.currentTimeMillis(), total = 0, cost = 0;
            for (var allocation :
                db.queryForList(
                    "SELECT * FROM wallet_allocation WHERE purchase_id=? ORDER BY batch_id,id",
                    id)) {
              long amount = number(allocation, "units"), partCost = number(allocation, "cost_fen");
              Long expiry = (Long) allocation.get("expires_at");
              if (expiry != null && expiry <= now) {
                String batchId = UUID.randomUUID().toString();
                db.update(
                    "INSERT INTO"
                        + " wallet_batch(id,user_id,mode,source_type,source_id,total_units,remaining_units,cost_fen,remaining_cost_fen,expires_at,created_at)"
                        + " VALUES(?,?,?,'REFUND',?,?,?,?,?,?,?)",
                    batchId,
                    user,
                    MODE,
                    allocation.get("id"),
                    amount,
                    amount,
                    partCost,
                    partCost,
                    now + settings.integer("wallet.refund_expired_days") * 86400000L,
                    now);
                Origins.created(db, "wallet_batch", batchId, actor.id());
              } else
                db.update(
                    "UPDATE wallet_batch SET"
                        + " remaining_units=remaining_units+?,remaining_cost_fen=remaining_cost_fen+?"
                        + " WHERE id=?",
                    amount,
                    partCost,
                    allocation.get("batch_id"));
              total += amount;
              cost += partCost;
            }
            if (total != number(order, "price_units"))
              throw new IllegalStateException("Refund allocations diverged");
            ledger(user, "REFUND", id, total, cost);
            db.update("UPDATE purchase_order SET state='REFUNDED' WHERE id=?", id);
            licenses.revoke(id);
          }
          db.update(
              "UPDATE refund_request SET"
                  + " state=?,reviewer_id=?,review_note=?,revision=revision+1,reviewed_at=? WHERE"
                  + " purchase_id=?",
              approve ? "APPROVED" : "REJECTED",
              actor.id(),
              explanation,
              System.currentTimeMillis(),
              id);
          audit(actor.id(), id, approve ? "APPROVE_REFUND" : "REJECT_REFUND", explanation);
          notify(user, approve ? "退鼓励值已完成" : "退款申请未通过", explanation);
        });
  }

  /** 统一分页输出仅接受固定查询，不将客户端字段名拼入SQL；大整数使用字符串避免浏览器损失精度。 */
  public Map<String, Object> page(
      String kind,
      String user,
      String state,
      String q,
      String from,
      String until,
      int page,
      int size) {
    return page(kind, user, state, q, from, until, page, size, "created_at", "descending");
  }

  public Map<String, Object> page(
      String kind,
      String user,
      String state,
      String q,
      String from,
      String until,
      int page,
      int size,
      String sort,
      String direction) {
    Map<String, String> fields =
        new HashMap<>(
            Map.of(
                "created_at",
                "created_at",
                "email",
                "(SELECT email FROM app_user au WHERE au.id="
                    + (kind.equals("refunds")
                        ? "refund_request"
                        : kind.equals("resources")
                            ? "purchase_order"
                            : kind.equals("points")
                                ? "wallet_ledger"
                                : kind.equals("batches") ? "wallet_batch" : "recharge_order")
                    + ".user_id)"));
    fields.put(
        "title",
        switch (kind) {
          case "resources" -> "sort_title";
          case "refunds" ->
              "(SELECT po.sort_title FROM purchase_order po WHERE"
                  + " po.id=refund_request.purchase_id)";
          case "recharge" -> "COALESCE(NULLIF(package_name,''),id)";
          default -> "id";
        });
    fields.put(
        "details",
        switch (kind) {
          case "resources" -> "sort_title";
          case "refunds" -> "reason";
          case "recharge" -> "COALESCE(NULLIF(package_name,''),id)";
          case "points" -> "kind";
          default -> "id";
        });
    fields.put(
        "nickname", fields.get("email").replace("SELECT email FROM", "SELECT nickname FROM"));
    fields.put("id", kind.equals("refunds") ? "purchase_id" : "id");
    fields.put("state", kind.equals("points") ? "kind" : "state");
    fields.put(
        "amount",
        switch (kind) {
          case "recharge" -> "price_fen";
          case "points" -> "delta_units";
          case "batches" -> "remaining_units";
          default -> "price_units";
        });
    String ordering =
        ListOrder.sql(
            sort, direction, fields, "created_at", kind.equals("refunds") ? "purchase_id" : "id");
    if (page < 1
        || page > 1000000
        || size < 1
        || size > 100
        || q == null
        || q.length() > 100
        || state.length() > 20) throw new Api.Failure(400, "分页或筛选参数不正确");
    String table, suffix = "", owner = "user_id", time = "created_at";
    switch (kind) {
      case "recharge" -> table = "recharge_order";
      case "points" -> table = "wallet_ledger";
      case "batches" -> table = "wallet_batch";
      case "resources" -> table = "purchase_order";
      case "refunds" -> {
        table = "refund_request";
        suffix = " AND purchase_id IN (SELECT id FROM purchase_order WHERE mode='SIMULATED')";
      }
      default -> throw new Api.Failure(400, "列表类型不正确");
    }
    List<Object> args = new ArrayList<>();
    String where = " WHERE " + (kind.equals("refunds") ? "1=1" : "mode='SIMULATED'") + suffix;
    if (user != null) {
      where += " AND " + owner + "=?";
      args.add(user);
    }
    if (!state.isBlank()) {
      where += " AND " + (kind.equals("points") ? "kind" : "state") + "=?";
      if (kind.equals("batches")) throw new Api.Failure(400, "批次不支持此状态筛选");
      args.add(state);
    }
    if (!q.isBlank()) {
      if (user != null) {
        String search =
            kind.equals("resources")
                ? "sort_title"
                : kind.equals("refunds")
                    ? "reason"
                    : kind.equals("recharge")
                        ? "CONCAT(id,' ',package_name,' ',COALESCE(payment_memo,''))"
                        : kind.equals("points") ? "CONCAT(id,' ',kind)" : "id";
        where += " AND " + search + " LIKE ? ESCAPE '!'";
        args.add("%" + q.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%");
      } else {
        String term = "%" + q.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        where +=
            " AND (user_id IN (SELECT id FROM app_user WHERE email LIKE ? ESCAPE '!' OR nickname"
                + " LIKE ? ESCAPE '!')"
                + (kind.equals("recharge") ? " OR payment_memo LIKE ? ESCAPE '!'" : "")
                + ")";
        args.add(term);
        args.add(term);
        if (kind.equals("recharge")) args.add(term);
      }
    }
    try {
      if (!from.isBlank()) {
        where += " AND " + time + ">=?";
        args.add(
            java.time.LocalDate.parse(from)
                .atStartOfDay(java.time.ZoneId.of("Asia/Shanghai"))
                .toInstant()
                .toEpochMilli());
      }
      if (!until.isBlank()) {
        where += " AND " + time + "<?";
        args.add(
            java.time.LocalDate.parse(until)
                .plusDays(1)
                .atStartOfDay(java.time.ZoneId.of("Asia/Shanghai"))
                .toInstant()
                .toEpochMilli());
      }
    } catch (java.time.DateTimeException bad) {
      throw new Api.Failure(400, "日期范围不正确");
    }
    long total =
        db.queryForObject("SELECT COUNT(*) FROM " + table + where, Long.class, args.toArray());
    int actual = (int) Math.min(page, Math.max(1, (total + size - 1) / size));
    args.add(size);
    args.add((actual - 1) * size);
    return Map.of(
        "items",
        safeRows(
            db.queryForList(
                (kind.equals("refunds")
                        ? "SELECT *, (SELECT price_units FROM purchase_order po WHERE"
                            + " po.id=refund_request.purchase_id) AS price_units,(SELECT cost_fen"
                            + " FROM purchase_order po WHERE po.id=refund_request.purchase_id) AS"
                            + " cost_fen,(SELECT snapshot_json FROM purchase_order po WHERE"
                            + " po.id=refund_request.purchase_id) AS snapshot_json FROM "
                        : "SELECT * FROM ")
                    + table
                    + where
                    + " ORDER BY "
                    + ordering
                    + " LIMIT ? OFFSET ?",
                args.toArray())),
        "total",
        total,
        "page",
        actual,
        "pageSize",
        size);
  }

  static Map<String, Object> safeRow(Map<String, Object> row) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (var entry : row.entrySet()) {
      String key = entry.getKey().toLowerCase(Locale.ROOT);
      Object value = entry.getValue();
      if (value != null
          && (key.endsWith("_units") || key.endsWith("_fen") || key.equals("balance_after")))
        value = value.toString();
      result.put(key, value);
    }
    result.remove("request_hash");
    result.remove("request_key");
    return result;
  }

  static List<Map<String, Object>> safeRows(List<Map<String, Object>> rows) {
    return rows.stream().map(Commerce::safeRow).toList();
  }
}
