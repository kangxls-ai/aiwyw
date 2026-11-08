package com.aiwyw;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

/** 邮箱账户生命周期与事务发信箱。验证链接不能由后台或公开接口读取，重置后撤销全部会话。 */
@Service
public class Accounts {
  private final JdbcTemplate db;
  private final SecureStore crypto;
  private final Settings settings;
  private final TransactionTemplate tx;

  public Accounts(
      JdbcTemplate db, SecureStore crypto, Settings settings, PlatformTransactionManager manager) {
    this.db = db;
    this.crypto = crypto;
    this.settings = settings;
    tx = new TransactionTemplate(manager);
  }

  String email(String raw) {
    String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    if (value.length() > 254
        || !value.matches(
            "[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?\\.[A-Za-z]{2,63}"))
      throw new Api.Failure(400, "邮箱格式不正确");
    return value;
  }

  void password(String value) {
    if (value == null || value.length() < 12 || value.length() > 256)
      throw new Api.Failure(400, "密码需为12—256个字符");
  }

  /** 频控记录持久化；同一键行锁覆盖多个进程，窗口到期重新计数。 */
  void limit(String scope, String identity) {
    limit(scope, identity, settings.integer("account.mail_hour_limit"));
  }

  void limit(String scope, String identity, int maximum) {
    String key = crypto.digest(scope + ":" + identity);
    long seedTime = System.currentTimeMillis();
    try {
      db.update(
          "INSERT INTO account_rate(rate_key,attempts,until_at) VALUES(?,0,?)",
          key,
          seedTime + 3600000);
    } catch (org.springframework.dao.DuplicateKeyException exists) {
      /* 自动提交插入结束后才进入加锁事务，避免共享锁升级。 */
    }
    tx.executeWithoutResult(
        status -> {
          long now = System.currentTimeMillis();
          var row =
              db.queryForMap(
                  "SELECT attempts,until_at FROM account_rate WHERE rate_key=? FOR UPDATE", key);
          boolean expired = ((Number) row.get("until_at")).longValue() <= now;
          int n = expired ? 0 : ((Number) row.get("attempts")).intValue();
          if (n >= maximum) throw new Api.Failure(429, "请求过于频繁，请稍后再试");
          db.update(
              "UPDATE account_rate SET attempts=?,until_at=? WHERE rate_key=?",
              n + 1,
              expired ? now + 3600000 : row.get("until_at"),
              key);
        });
  }

  public void register(String raw, String password, String ip) {
    if (!Boolean.parseBoolean(settings.value("account.registration_open")))
      throw new Api.Failure(403, "网站暂未开放注册");
    String mail = email(raw);
    password(password);
    limit("register-ip", ip);
    limit("register-email", mail);
    tx.executeWithoutResult(
        status -> {
          var ids = db.queryForList("SELECT id FROM app_user WHERE email=?", String.class, mail);
          if (!ids.isEmpty()) return;
          String id = UUID.randomUUID().toString();
          try {
            db.update(
                "INSERT INTO"
                    + " app_user(id,email,password_hash,role_name,mfa_enabled,last_totp_step,created_at,disabled)"
                    + " VALUES(?,?,?,'USER',FALSE,-1,?,FALSE)",
                id,
                mail,
                crypto.password(password),
                System.currentTimeMillis());
          } catch (org.springframework.dao.DuplicateKeyException concurrent) {
            return;
          }
          db.update("UPDATE app_user SET nickname=? WHERE id=?", Identity.nickname(), id);
          db.update("INSERT INTO access_user_role VALUES(?,'USER')", id);
          Origins.created(db, "app_user", id, id);
          issue(id, mail, "VERIFY");
        });
  }

  public void send(String raw, String purpose, String ip) {
    String mail = email(raw);
    limit("mail-ip", ip);
    limit("mail-email", mail);
    tx.executeWithoutResult(
        status -> {
          var rows =
              db.queryForList(
                  "SELECT id,email_verified_at,disabled FROM app_user WHERE email=? FOR UPDATE",
                  mail);
          if (rows.isEmpty() || Boolean.TRUE.equals(rows.get(0).get("disabled"))) return;
          var user = rows.get(0);
          if (purpose.equals("VERIFY") && user.get("email_verified_at") != null) return;
          issue((String) user.get("id"), mail, purpose);
        });
  }

  private void issue(String id, String mail, String purpose) {
    long now = System.currentTimeMillis();
    String token = crypto.token();
    db.update(
        "UPDATE account_action_token SET used_at=? WHERE user_id=? AND purpose=? AND used_at IS"
            + " NULL",
        now,
        id,
        purpose);
    db.update(
        "INSERT INTO account_action_token(token_hash,user_id,purpose,expires_at,created_at)"
            + " VALUES(?,?,?,?,?)",
        crypto.digest(token),
        id,
        purpose,
        now
            + settings.integer(
                    purpose.equals("VERIFY") ? "account.verify_minutes" : "account.reset_minutes")
                * 60000L,
        now);
    String origin =
        settings.value("account.link_origin").isBlank()
            ? "https://" + settings.value("site.domain")
            : settings.value("account.link_origin").replaceAll("/+$", "");
    String url =
        origin
            + "/account-access?action="
            + (purpose.equals("VERIFY") ? "verify" : "reset")
            + "&token="
            + token;
    String text = purpose.equals("VERIFY") ? "请打开以下链接确认邮箱：" : "请打开以下链接设置新密码。如非本人操作，请忽略：";
    db.update(
        "INSERT INTO mail_outbox(id,recipient,subject,body_enc,status,attempts,next_at,created_at)"
            + " VALUES(?,?,?,?,'PENDING',0,?,?)",
        UUID.randomUUID().toString(),
        mail,
        settings.value("site.name") + (purpose.equals("VERIFY") ? " 邮箱验证" : " 重置密码"),
        crypto.encrypt(text + "\n" + url + "\n链接只可使用一次。"),
        now,
        now);
  }

  public void consume(String token, String purpose, String newPassword) {
    if (token == null || !token.matches("[A-Za-z0-9_-]{43}"))
      throw new Api.Failure(400, "链接无效或已过期");
    if (purpose.equals("RESET")) password(newPassword);
    tx.executeWithoutResult(
        status -> {
          long now = System.currentTimeMillis();
          String hash = crypto.digest(token);
          var ids =
              db.queryForList(
                  "SELECT user_id FROM account_action_token WHERE token_hash=? AND purpose=? AND"
                      + " used_at IS NULL AND expires_at>?",
                  String.class,
                  hash,
                  purpose,
                  now);
          if (ids.isEmpty()) throw new Api.Failure(400, "链接无效或已过期");
          String id = ids.get(0);
          if (db.queryForList("SELECT id FROM app_user WHERE id=? FOR UPDATE", id).isEmpty())
            throw new Api.Failure(400, "链接对应的账户不存在或已删除");
          if (db.update(
                  "UPDATE account_action_token SET used_at=? WHERE token_hash=? AND purpose=? AND"
                      + " used_at IS NULL AND expires_at>?",
                  now,
                  hash,
                  purpose,
                  now)
              != 1) throw new Api.Failure(409, "链接已使用或已过期");
          if (purpose.equals("VERIFY"))
            db.update("UPDATE app_user SET email_verified_at=? WHERE id=?", now, id);
          else {
            db.update(
                "UPDATE app_user SET password_hash=? WHERE id=?", crypto.password(newPassword), id);
            db.update("DELETE FROM app_session WHERE user_id=?", id);
          }
        });
  }

  /** 旧密码校验、写新摘要、撤销会话及重置令牌同事务，且与邮件重置共用账户行锁。 */
  public void changePassword(String id, String oldPassword, String newPassword) {
    password(newPassword);
    if (oldPassword == null || oldPassword.length() > 256) throw new Api.Failure(400, "请填写当前密码");
    limit("change-password", id, settings.integer("security.password_change_hour_limit"));
    tx.executeWithoutResult(
        status -> {
          var row =
              db.queryForMap(
                  "SELECT password_hash,disabled FROM app_user WHERE id=? FOR UPDATE", id);
          if (Boolean.TRUE.equals(row.get("disabled"))) throw new Api.Failure(403, "账户已停用");
          if (!crypto.verifyPassword(oldPassword, (String) row.get("password_hash")))
            throw new Api.Failure(400, "当前密码不正确");
          if (oldPassword.equals(newPassword)) throw new Api.Failure(400, "新密码不能与当前密码相同");
          db.update(
              "UPDATE app_user SET password_hash=? WHERE id=?", crypto.password(newPassword), id);
          db.update("DELETE FROM app_session WHERE user_id=?", id);
          db.update(
              "UPDATE account_action_token SET used_at=? WHERE user_id=? AND purpose='RESET' AND"
                  + " used_at IS NULL",
              System.currentTimeMillis(),
              id);
          AuditLog.write(db, "finance_audit", id, id, "PASSWORD_CHANGED", "本人修改密码，已撤销全部会话与重置令牌");
        });
  }

  /** 发信在事务外执行；先CAS抢占，崩溃五分钟后可重试。SMTP异常不写含令牌的日志。 */
  @Scheduled(fixedDelay = 15000)
  public void deliver() {
    if (!Boolean.parseBoolean(settings.value("mail.enabled"))) return;
    long now = System.currentTimeMillis();
    for (var row :
        db.queryForList(
            "SELECT * FROM mail_outbox WHERE status<>'SENT' AND next_at<=? ORDER BY created_at"
                + " LIMIT 10",
            now)) {
      String id = (String) row.get("id");
      if (db.update(
              "UPDATE mail_outbox SET status='SENDING',next_at=?,attempts=attempts+1 WHERE id=? AND"
                  + " status<>'SENT' AND next_at<=?",
              now + 300000,
              id,
              now)
          != 1) continue;
      try {
        var sender = sender(settings);
        var message = new SimpleMailMessage();
        message.setFrom(settings.value("mail.from"));
        message.setTo((String) row.get("recipient"));
        message.setSubject((String) row.get("subject"));
        message.setText(crypto.decrypt((String) row.get("body_enc")));
        sender.send(message);
        db.update(
            "UPDATE mail_outbox SET status='SENT',body_enc=?,last_error_code=NULL WHERE id=?",
            crypto.encrypt("sent"),
            id);
      } catch (Exception failure) {
        db.update(
            "UPDATE mail_outbox SET status='PENDING',last_error_code=?,next_at=? WHERE id=?",
            MailDiagnostics.code(failure),
            System.currentTimeMillis()
                + Math.min(3600000, 60000L * (1 + ((Number) row.get("attempts")).intValue())),
            id);
      }
    }
  }

  static JavaMailSenderImpl sender(Settings settings) {
    return MailDiagnostics.sender(settings);
  }
}

@RestController
class AccountController {
  private final Accounts accounts;
  private final Auth auth;
  private final JdbcTemplate db;
  private final SecureStore crypto;
  private final Database installation;

  AccountController(
      Accounts accounts, Auth auth, JdbcTemplate db, SecureStore crypto, Database installation) {
    this.accounts = accounts;
    this.auth = auth;
    this.db = db;
    this.crypto = crypto;
    this.installation = installation;
  }

  record Registration(String email, String password) {}

  record Mail(String email) {}

  record Token(String token, String password) {}

  @PostMapping("/api/auth/register")
  Map<String, String> register(@RequestBody Registration b, HttpServletRequest r) {
    if (!installation.installed()) throw new Api.Failure(409, "请先完成站点安装");
    accounts.register(b.email(), b.password(), r.getRemoteAddr());
    return Map.of("message", "如邮箱可用于注册，验证邮件将发送至该邮箱，请检查收件箱。");
  }

  @PostMapping("/api/auth/verification-email")
  Map<String, String> resend(@RequestBody Mail b, HttpServletRequest r) {
    accounts.send(b.email(), "VERIFY", r.getRemoteAddr());
    return Map.of("message", "如账户需要验证，验证邮件将发送至该邮箱。");
  }

  @PostMapping("/api/auth/forgot-password")
  Map<String, String> forgot(@RequestBody Mail b, HttpServletRequest r) {
    accounts.send(b.email(), "RESET", r.getRemoteAddr());
    return Map.of("message", "如账户存在，重置邮件将发送至该邮箱。");
  }

  @PostMapping("/api/auth/verify-email")
  Map<String, Boolean> verify(@RequestBody Token b) {
    accounts.consume(b.token(), "VERIFY", null);
    return Map.of("success", true);
  }

  @PostMapping("/api/auth/reset-password")
  Map<String, Boolean> reset(@RequestBody Token b) {
    accounts.consume(b.token(), "RESET", b.password());
    return Map.of("success", true);
  }

  @GetMapping("/api/member/profile")
  Map<String, Object> profile(HttpServletRequest r) {
    var u = auth.user(r, false);
    return Map.of(
        "user",
        u,
        "verified",
        db.queryForObject(
            "SELECT email_verified_at IS NOT NULL FROM app_user WHERE id=?", Boolean.class, u.id()),
        "businessEmail",
        db.queryForObject("SELECT business_email FROM app_user WHERE id=?", Boolean.class, u.id()));
  }

  record PasswordChange(String oldPassword, String newPassword) {}

  @PutMapping("/api/member/password")
  Map<String, Boolean> password(@RequestBody PasswordChange b, HttpServletRequest r) {
    accounts.changePassword(auth.user(r, true).id(), b.oldPassword(), b.newPassword());
    return Map.of("success", true);
  }

  record Preference(boolean businessEmail) {}

  @PutMapping("/api/member/preferences")
  Map<String, Boolean> preferences(@RequestBody Preference b, HttpServletRequest r) {
    db.update(
        "UPDATE app_user SET business_email=? WHERE id=?",
        b.businessEmail(),
        auth.user(r, false).id());
    return Map.of("success", true);
  }

  record Staff(String email, String password, String role, boolean disabled, List<String> roles) {}

  @GetMapping("/api/admin/accounts")
  Map<String, Object> users(
      @RequestParam(defaultValue = "") String q,
      @RequestParam org.springframework.util.MultiValueMap<String, String> parameters,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int pageSize,
      HttpServletRequest r) {
    auth.admin(r);
    if (q.length() > 100 || page < 1 || pageSize < 1 || pageSize > 100)
      throw new Api.Failure(400, "分页参数不正确");
    String state = parameters.getFirst("state");
    String statusClause =
        switch (state == null ? "" : state) {
          case "", "ALL" -> "";
          case "UNVERIFIED" -> " AND email_verified_at IS NULL";
          case "ACTIVE" -> " AND email_verified_at IS NOT NULL AND disabled=FALSE";
          case "DISABLED" -> " AND disabled=TRUE";
          default -> throw new Api.Failure(400, "账户状态不正确");
        };
    String term = "%" + q.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    long total =
        db.queryForObject(
            "SELECT COUNT(*) FROM app_user WHERE email LIKE ? ESCAPE '!'" + statusClause,
            Long.class,
            term);
    int actual = (int) Math.min(page, Math.max(1, (total + pageSize - 1) / pageSize));
    return Map.of(
        "items",
        withRoles(
            Commerce.safeRows(
                db.queryForList(
                    "SELECT"
                        + " id,email,nickname,role_name,mfa_enabled,email_verified_at,disabled,created_at"
                        + " FROM app_user WHERE email LIKE ? ESCAPE '!'"
                        + statusClause
                        + " ORDER BY "
                        + ListOrder.sql(
                            parameters.getFirst("sort"),
                            parameters.getFirst("direction") == null
                                ? "descending"
                                : parameters.getFirst("direction"),
                            Map.of(
                                "nickname",
                                "nickname",
                                "created_at",
                                "created_at",
                                "email",
                                "email",
                                "role_name",
                                "role_name",
                                "mfa_enabled",
                                "mfa_enabled",
                                "disabled",
                                "disabled"),
                            "created_at",
                            "id")
                        + " LIMIT ?"
                        + " OFFSET ?",
                    term,
                    pageSize,
                    (actual - 1) * pageSize))),
        "total",
        total,
        "page",
        actual);
  }

  @org.springframework.transaction.annotation.Transactional
  @PostMapping("/api/admin/accounts")
  public Map<String, String> staff(@RequestBody Staff b, HttpServletRequest r) {
    auth.admin(r);
    String mail = accounts.email(b.email());
    accounts.password(b.password());
    List<String> selected = selectedRoles(b);
    String id = UUID.randomUUID().toString();
    try {
      db.update(
          "INSERT INTO"
              + " app_user(id,email,password_hash,role_name,mfa_enabled,last_totp_step,created_at,disabled)"
              + " VALUES(?,?,?,?,FALSE,-1,?,?)",
          id,
          mail,
          crypto.password(b.password()),
          legacyRole(selected),
          System.currentTimeMillis(),
          b.disabled());
    } catch (org.springframework.dao.DuplicateKeyException exists) {
      throw new Api.Failure(409, "邮箱已存在，请在现有账户维护角色");
    }
    db.update("UPDATE app_user SET nickname=? WHERE id=?", Identity.nickname(), id);
    assign(id, selected);
    Origins.created(db, "app_user", id, auth.admin(r).id());
    audit(auth.admin(r).id(), id, "CREATE_ACCOUNT");
    return Map.of("id", id);
  }

  @org.springframework.transaction.annotation.Transactional
  @PutMapping("/api/admin/accounts/{id}")
  public Map<String, Boolean> change(
      @PathVariable String id, @RequestBody Staff b, HttpServletRequest r) {
    var admin = auth.admin(r);
    List<String> selected = selectedRoles(b);
    if (admin.id().equals(id)
        && admin.hasRole("ADMIN")
        && (b.disabled() || !selected.contains("ADMIN")))
      throw new Api.Failure(400, "当前管理员可追加角色，但不能停用自己或移除管理员身份");
    if ((b.disabled() || !selected.contains("ADMIN"))
        && db.queryForObject(
                "SELECT COUNT(*) FROM access_user_role WHERE user_id=? AND role_id='ADMIN'",
                Integer.class,
                id)
            > 0
        && db.queryForObject(
                "SELECT COUNT(DISTINCT u.id) FROM app_user u LEFT JOIN access_user_role ar ON"
                    + " ar.user_id=u.id WHERE u.id<>? AND u.disabled=FALSE AND (ar.role_id='ADMIN'"
                    + " OR (ar.user_id IS NULL AND u.role_name='ADMIN'))",
                Integer.class,
                id)
            == 0) throw new Api.Failure(409, "须保留至少一个可用管理员");
    if (db.update(
            "UPDATE app_user SET role_name=?,disabled=? WHERE id=?",
            legacyRole(selected),
            b.disabled(),
            id)
        != 1) throw new Api.Failure(404, "账户不存在");
    assign(id, selected);
    db.update("DELETE FROM app_session WHERE user_id=?", id);
    audit(admin.id(), id, "CHANGE_ACCOUNT");
    return Map.of("success", true);
  }

  @org.springframework.transaction.annotation.Transactional
  @DeleteMapping("/api/admin/accounts/{id}")
  Map<String, Boolean> deleteUnverified(@PathVariable String id, HttpServletRequest r) {
    var actor = auth.admin(r);
    if (actor.id().equals(id)) throw new Api.Failure(403, "不能删除本人账户");
    db.queryForObject("SELECT revision FROM catalog_guard WHERE id=1 FOR UPDATE", Long.class);
    var rows = db.queryForList("SELECT * FROM app_user WHERE id=? FOR UPDATE", id);
    if (rows.isEmpty()) throw new Api.Failure(404, "账户不存在");
    var target = rows.get(0);
    if (target.get("email_verified_at") != null
        || !target.get("role_name").equals("USER")
        || db.queryForObject(
                "SELECT COUNT(*) FROM access_user_role WHERE user_id=? AND role_id<>'USER'",
                Integer.class,
                id)
            > 0) throw new Api.Failure(409, "仅可删除未激活的普通账户，后台账户不能通过此入口删除");
    for (String table :
        List.of(
            "recharge_order",
            "purchase_order",
            "wallet_ledger",
            "wallet_batch",
            "api_subscription",
            "api_key",
            "support_conversation",
            "support_feedback"))
      if (db.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE user_id=?", Integer.class, id)
          > 0) throw new Api.Failure(409, "该账户已有业务记录，请停用并保留历史，不能删除");
    db.update("DELETE FROM app_session WHERE user_id=?", id);
    db.update("DELETE FROM account_action_token WHERE user_id=?", id);
    db.update("DELETE FROM mail_outbox WHERE recipient=? AND status<>'SENT'", target.get("email"));
    db.update("DELETE FROM access_user_role WHERE user_id=?", id);
    db.update("DELETE FROM wallet_account WHERE user_id=? AND balance_units=0", id);
    db.update("DELETE FROM app_user WHERE id=?", id);
    audit(actor.id(), id, "DELETE_UNVERIFIED_ACCOUNT");
    return Map.of("success", true);
  }

  private List<Map<String, Object>> withRoles(List<Map<String, Object>> rows) {
    for (var row : rows) {
      var assigned =
          db.queryForList(
              "SELECT r.id,r.name FROM access_user_role ur JOIN access_role r ON r.id=ur.role_id"
                  + " WHERE ur.user_id=? ORDER BY r.id",
              row.get("id"));
      Map<String, String> labels = new LinkedHashMap<>();
      for (var role : assigned) labels.put((String) role.get("id"), (String) role.get("name"));
      row.put(
          "roles", labels.isEmpty() ? List.of(row.get("role_name")) : List.copyOf(labels.keySet()));
      row.put("roleLabels", labels);
    }
    return rows;
  }

  private List<String> selectedRoles(Staff b) {
    db.queryForObject("SELECT revision FROM catalog_guard WHERE id=1 FOR UPDATE", Long.class);
    var roles =
        b.roles() == null
            ? List.of(b.role() == null ? "USER" : b.role())
            : List.copyOf(new LinkedHashSet<>(b.roles()));
    if (roles.isEmpty() || roles.size() > 30) throw new Api.Failure(400, "请选择1至30个角色");
    for (String role : roles)
      if (db.queryForObject("SELECT COUNT(*) FROM access_role WHERE id=?", Integer.class, role)
          != 1) throw new Api.Failure(400, "角色不存在");
    return roles;
  }

  private String legacyRole(List<String> roles) {
    return roles.stream()
        .filter(r -> Set.of("ADMIN", "FINANCE", "SUPPORT", "OPERATOR", "USER").contains(r))
        .findFirst()
        .orElse("USER");
  }

  private void assign(String id, List<String> roles) {
    db.update("DELETE FROM access_user_role WHERE user_id=?", id);
    for (String role : roles) db.update("INSERT INTO access_user_role VALUES(?,?)", id, role);
  }

  private void role(String role) {
    if (role == null || !Set.of("ADMIN", "OPERATOR", "FINANCE", "SUPPORT", "USER").contains(role))
      throw new Api.Failure(400, "角色不正确");
  }

  private void audit(String actor, String id, String action) {
    AuditLog.write(db, "finance_audit", actor, id, action, "账户维护，不含凭据");
  }
}
