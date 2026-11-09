package com.aiwyw;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 服务端身份与MFA策略。会话Cookie只携带随机令牌，数据库保存摘要；按已保存的双因素策略核验所有高权限角色，不以菜单可见性代替权限。 */
@Service
public class Auth {
  public record User(
      String id,
      String email,
      String role,
      boolean mfaVerified,
      String nickname,
      List<String> roles,
      Set<String> menus,
      boolean mfaRequired,
      boolean mfaPolicyEnabled) {
    public User(String id, String email, String role, boolean verified) {
      this(
          id,
          email,
          role,
          verified,
          "",
          List.of(role),
          Identity.defaults(role),
          Set.of("ADMIN", "FINANCE").contains(role),
          true);
    }

    public boolean hasRole(String value) {
      return roles.contains(value);
    }
  }

  private static final long ATTEMPT_WINDOW_MS = 15 * 60_000L;

  private record Attempts(int count, long until) {}

  private record LoginResult(String id, boolean mfa, boolean locked) {
    static LoginResult rejected() {
      return new LoginResult(null, false, false);
    }

    static LoginResult blocked() {
      return new LoginResult(null, false, true);
    }
  }

  private final Map<String, Attempts> failures = new ConcurrentHashMap<>();
  private final SecureStore crypto;
  private final Settings settings;
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transaction;
  private final String dummyPassword;

  public Auth(
      SecureStore crypto,
      Settings settings,
      JdbcTemplate jdbc,
      PlatformTransactionManager manager) {
    this.crypto = crypto;
    this.settings = settings;
    this.jdbc = jdbc;
    this.transaction = new TransactionTemplate(manager);
    this.dummyPassword = crypto.password(crypto.token());
  }

  /** Java内部任务没有HTTP入口；公开及后台登录由显式入口标记区分。 */
  public String login(String email, String password, String code, String ip) {
    return login(email, password, code, ip, null);
  }

  String login(String email, String password, String code, String ip, Boolean staff) {
    if (email == null || password == null || email.length() > 254 || password.length() > 256)
      throw new Api.Failure(401, "邮箱、密码或验证码不正确");
    String normalized = email.trim().toLowerCase(Locale.ROOT);
    // 普通入口的失败不能消耗后台入口的来源额度，即使两者共享反向代理IP。
    String channel = Boolean.TRUE.equals(staff) ? "staff:" : "public:";
    String key = crypto.digest(channel + ip + ":" + normalized);
    long now = System.currentTimeMillis();
    int maximum = settings.integer("security.login_attempts");
    long lockDuration = settings.integer("security.login_lock_minutes") * 60_000L;
    boolean mfaEnabled = Boolean.parseBoolean(settings.value("security.mfa_required"));
    failures.entrySet().removeIf(e -> e.getValue().until() <= now);
    Attempts previous = failures.get(key);
    if (previous != null && previous.count() >= maximum) throw new Api.Failure(429, "尝试次数过多，请稍后再试");

    String token = crypto.token();
    long expires = now + settings.integer("security.session_hours") * 3_600_000L;
    // 失败先提交计数，再返回HTTP错误。成功则持有同一账户锁直至会话写入，避免与改密撤销竞态。
    LoginResult result =
        transaction.execute(
            status -> {
              var checked =
                  checkPassword(
                      normalized, password, code, staff, maximum, lockDuration, mfaEnabled);
              if (checked.id() != null)
                jdbc.update(
                    "INSERT INTO app_session(token_hash,user_id,expires_at,mfa_verified)"
                        + " VALUES(?,?,?,?)",
                    sessionDigest(token, Boolean.TRUE.equals(staff)),
                    checked.id(),
                    expires,
                    checked.mfa());
              return checked;
            });
    if (result.locked()) throw new Api.Failure(429, "登录暂时锁定，请稍后再试");
    if (result.id() == null) {
      failures.compute(
          key,
          (k, old) ->
              old == null || old.until() <= now
                  ? new Attempts(1, now + ATTEMPT_WINDOW_MS)
                  : new Attempts(old.count() + 1, old.until()));
      throw new Api.Failure(401, "邮箱、密码或验证码不正确");
    }
    failures.remove(key);
    jdbc.update("DELETE FROM app_session WHERE expires_at<?", now);
    return token;
  }

  private LoginResult checkPassword(
      String email,
      String password,
      String code,
      Boolean staff,
      int maximum,
      long lockDuration,
      boolean mfaPolicy) {
    var users = jdbc.queryForList("SELECT * FROM app_user WHERE email=? FOR UPDATE", email);
    if (users.isEmpty()) {
      crypto.verifyPassword(password, dummyPassword);
      return LoginResult.rejected();
    }
    var user = users.get(0);
    String id = (String) user.get("id");
    if (Boolean.TRUE.equals(user.get("disabled"))
        || (staff != null && staff != hasStaffRole(id, (String) user.get("role_name")))) {
      crypto.verifyPassword(password, dummyPassword);
      return LoginResult.rejected();
    }
    long now = System.currentTimeMillis();
    var guards = jdbc.queryForList("SELECT * FROM account_login_guard WHERE user_id=?", id);
    var guard = guards.isEmpty() ? Map.<String, Object>of() : guards.get(0);
    long lockedUntil = ((Number) guard.getOrDefault("locked_until", 0L)).longValue();
    if (lockedUntil > now) return LoginResult.blocked();
    if (!crypto.verifyPassword(password, (String) user.get("password_hash")))
      return recordFailure(id, guard, now, lockedUntil, maximum, lockDuration);
    boolean mfa = mfaPolicy && Boolean.TRUE.equals(user.get("mfa_enabled"));
    if (mfa) {
      long step =
          crypto.validTotpStep(
              crypto.decrypt((String) user.get("totp_secret")),
              code,
              ((Number) user.get("last_totp_step")).longValue());
      if (step < 0
          || jdbc.update(
                  "UPDATE app_user SET last_totp_step=? WHERE id=? AND last_totp_step<?",
                  step,
                  id,
                  step)
              != 1) return recordFailure(id, guard, now, lockedUntil, maximum, lockDuration);
    }
    if (!guard.isEmpty()) jdbc.update("DELETE FROM account_login_guard WHERE user_id=?", id);
    return new LoginResult(id, mfa, false);
  }

  private LoginResult recordFailure(
      String id,
      Map<String, Object> guard,
      long now,
      long lockedUntil,
      int maximum,
      long lockDuration) {
    long window = ((Number) guard.getOrDefault("window_until", 0L)).longValue();
    int count =
        lockedUntil == 0 && window > now ? ((Number) guard.get("failures")).intValue() + 1 : 1;
    if (count == 1) window = now + ATTEMPT_WINDOW_MS;
    long until = count >= maximum ? now + lockDuration : 0;
    if (guard.isEmpty())
      jdbc.update(
          "INSERT INTO account_login_guard(user_id,failures,window_until,locked_until)"
              + " VALUES(?,?,?,?)",
          id,
          count,
          window,
          until);
    else
      jdbc.update(
          "UPDATE account_login_guard SET failures=?,window_until=?,locked_until=? WHERE"
              + " user_id=?",
          count,
          window,
          until,
          id);
    return until > 0 ? LoginResult.blocked() : LoginResult.rejected();
  }

  private boolean hasStaffRole(String id, String fallback) {
    var roles =
        jdbc.queryForList("SELECT role_id FROM access_user_role WHERE user_id=?", String.class, id);
    if (roles.isEmpty()) roles = List.of(fallback);
    for (String role : roles) {
      if (Set.of("ADMIN", "FINANCE", "OPERATOR", "SUPPORT").contains(role)
          || jdbc.queryForObject(
                  "SELECT COUNT(*) FROM access_role_menu WHERE role_id=? AND menu_id NOT LIKE"
                      + " 'group:%'",
                  Integer.class, role)
              > 0) return true;
    }
    return false;
  }

  /** 两类会话使用不同摘要域，复制或改名普通Cookie也无法换成后台会话。 */
  String sessionDigest(String token, boolean staff) {
    return crypto.digest(staff ? "staff:" + token : token);
  }

  /** 从服务端会话联查账户，每次验证有效期与可选MFA要求；不信任客户端角色。 */
  public User user(HttpServletRequest request, boolean requireMfa) {
    return userToken(cookie(request), requireMfa, StaffEntry.authorized(request));
  }

  User userToken(String token, boolean requireMfa, boolean staff) {
    if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) throw new Api.Failure(401, "请先登录");
    var rows =
        jdbc.queryForList(
            "SELECT u.id,u.email,u.role_name,u.nickname,s.mfa_verified FROM app_session s JOIN"
                + " app_user u ON u.id=s.user_id WHERE u.disabled=FALSE AND s.token_hash=? AND"
                + " s.expires_at>?",
            sessionDigest(token, staff),
            System.currentTimeMillis());
    if (rows.isEmpty()) throw new Api.Failure(401, "登录已过期，请重新登录");
    var r = rows.get(0);
    String id = (String) r.get("id");
    if (((String) r.get("nickname")).isBlank()) {
      String nickname = Identity.nickname();
      jdbc.update("UPDATE app_user SET nickname=? WHERE id=? AND nickname=''", nickname, id);
      r.put(
          "nickname",
          jdbc.queryForObject("SELECT nickname FROM app_user WHERE id=?", String.class, id));
    }
    List<String> roles =
        jdbc.queryForList(
            "SELECT role_id FROM access_user_role WHERE user_id=? ORDER BY role_id",
            String.class,
            id);
    // 兼容安装流程和旧集成测试直接创建的账户；正式账户创建同事务写入角色关联。
    if (roles.isEmpty()) roles = List.of((String) r.get("role_name"));
    Set<String> menus = new HashSet<>();
    for (String role : roles)
      menus.addAll(
          jdbc.queryForList(
              "SELECT menu_id FROM access_role_menu WHERE role_id=? AND menu_id NOT LIKE 'group:%'",
              String.class, role));
    boolean enabled = Boolean.parseBoolean(settings.value("security.mfa_required"));
    boolean required = enabled && (roles.contains("ADMIN") || roles.contains("FINANCE"));
    String primary =
        roles.contains("ADMIN") ? "ADMIN" : roles.contains("SUPPORT") ? "SUPPORT" : roles.get(0);
    var u =
        new User(
            id,
            (String) r.get("email"),
            primary,
            !enabled || Boolean.TRUE.equals(r.get("mfa_verified")),
            (String) r.get("nickname"),
            roles,
            Set.copyOf(menus),
            required,
            enabled);
    if (requireMfa && required && !u.mfaVerified()) throw new Api.Failure(403, "请先完成双重验证设置");
    return u;
  }

  /** 工作台仅返回非敏感摘要；后台四角色可进入，管理员/财务仍强制MFA。 */
  public User staff(HttpServletRequest r) {
    var u = user(r, false);
    if (u.menus().isEmpty()) throw new Api.Failure(403, "需要后台角色");
    checkMfa(u);
    return u;
  }

  private void checkMfa(User u) {
    if (u.mfaRequired() && !u.mfaVerified()) throw new Api.Failure(403, "请先完成双重验证");
  }

  /** 菜单不是仅隐藏导航；每个后台请求先解析所属模块并验证并集权限。 */
  private User menu(HttpServletRequest r, String fallback) {
    var u = staff(r);
    String p = r.getRequestURI(), key = fallback;
    if (p.contains("/accounts") || p.contains("/role-options")) key = "users";
    else if (p.contains("/roles")) key = "roles";
    else if (p.contains("/audit")) key = "audit";
    else if (p.contains("/storage")) key = "storage";
    else if (p.contains("/licenses")) key = "licenses";
    else if (p.contains("/catalog/tags")) key = "tags";
    else if (p.contains("/catalog/sections")) key = "sections";
    else if (p.contains("/catalog/products"))
      key =
          (p.endsWith("/publish") || p.endsWith("/unlist") || p.endsWith("/rollback"))
              ? "applications"
              : "applications";
    else if (p.contains("commerce-packages")) key = "packages";
    else if (p.contains("/commerce/")) {
      String k = p.split("/commerce/")[1].split("/")[0];
      key = k.equals("resources") ? "orders" : k;
    }
    // 栏目选品/授权策略需要只读候选项；此依赖权限不开放应用明细或应用写操作。
    boolean metadata =
        key.equals("applications")
            && r.getMethod().equals("GET")
            && ((p.endsWith("/options") || p.endsWith("/options/page"))
                    && (u.menus().contains("licenses") || u.menus().contains("sections"))
                || (p.endsWith("/facets")
                    && (u.menus().contains("sections") || u.menus().contains("tags"))));
    boolean legacy =
        key.equals("applications")
            && (u.menus().contains("versions") || u.menus().contains("publishing"));
    if (!u.menus().contains(key) && !metadata && !legacy) throw new Api.Failure(403, "没有该菜单权限");
    return u;
  }

  public User admin(HttpServletRequest r) {
    if (r.getRequestURI().startsWith("/api/admin/storage/")
        && !r.getRequestURI().contains("/assets")
        && !r.getRequestURI().endsWith("capabilities")) return permission(r, "settings");
    return menu(r, "settings");
  }

  public User permission(HttpServletRequest r, String key) {
    var u = staff(r);
    if (!u.menus().contains(key)) throw new Api.Failure(403, "没有该菜单权限");
    return u;
  }

  /** 用户捐赠、兑换及API要求实际邮箱验证；管理员身份不绕过交易邮箱要求。 */
  public User verified(HttpServletRequest r) {
    User u = user(r, false);
    if (!jdbc.queryForObject(
        "SELECT email_verified_at IS NOT NULL FROM app_user WHERE id=?", Boolean.class, u.id()))
      throw new Api.Failure(403, "请先验证邮箱");
    return u;
  }

  public User finance(HttpServletRequest r, boolean review) {
    var u = menu(r, "orders");
    if (review && !u.hasRole("ADMIN") && !u.hasRole("SUPPORT"))
      throw new Api.Failure(403, "财务审核须具备客服或管理员角色");
    return u;
  }

  public User editor(HttpServletRequest r) {
    return menu(r, "applications");
  }

  /** 按令牌摘要删除会话；只清Cookie而保留服务端记录是不完整退出。 */
  public void logout(HttpServletRequest req) {
    String token = cookie(req);
    if (token != null)
      jdbc.update(
          "DELETE FROM app_session WHERE token_hash=?",
          sessionDigest(token, StaffEntry.authorized(req)));
  }

  /** 只从固定HttpOnly会话Cookie读取，不接受URL查询中的身份令牌。 */
  public String cookie(HttpServletRequest req) {
    String name = StaffEntry.authorized(req) ? StaffEntry.COOKIE : "AIWYW_SESSION";
    if (req.getCookies() != null)
      for (var c : req.getCookies())
        if (c.getName().equals(name) && c.getValue().matches("[A-Za-z0-9_-]{43}"))
          return c.getValue();
    return null;
  }

  /** 仅未完成绑定的账户可读取待绑定密钥；密钥加密保存，不能重新绑定覆盖已启用MFA。 */
  public String enroll(User user) {
    var r = jdbc.queryForMap("SELECT mfa_enabled,totp_secret FROM app_user WHERE id=?", user.id());
    if (Boolean.TRUE.equals(r.get("mfa_enabled"))) throw new Api.Failure(409, "双重验证已启用");
    String secret = (String) r.get("totp_secret");
    if (secret == null) {
      String value = crypto.newTotpSecret();
      jdbc.update(
          "UPDATE app_user SET totp_secret=? WHERE id=? AND mfa_enabled=FALSE AND totp_secret IS"
              + " NULL",
          crypto.encrypt(value),
          user.id());
      secret =
          jdbc.queryForObject(
              "SELECT totp_secret FROM app_user WHERE id=?", String.class, user.id());
    }
    return crypto.decrypt(secret);
  }

  /** 限制验证码尝试次数；首次确认后撤销其他会话并提升当前会话验证级别。 */
  public void confirm(User user, String code, HttpServletRequest req) {
    String key = "mfa:" + user.id();
    long now = System.currentTimeMillis();
    var a = failures.get(key);
    if (a != null && a.until() > now && a.count() >= 5)
      throw new Api.Failure(429, "验证码尝试过多，请15分钟后再试");
    var r =
        jdbc.queryForMap(
            "SELECT mfa_enabled,totp_secret,last_totp_step FROM app_user WHERE id=?", user.id());
    if (Boolean.TRUE.equals(r.get("mfa_enabled"))) throw new Api.Failure(409, "双重验证已启用，请重新登录");
    long step =
        r.get("totp_secret") == null
            ? -1
            : crypto.validTotpStep(crypto.decrypt((String) r.get("totp_secret")), code, -1);
    if (step < 0) {
      failures.compute(
          key,
          (k, v) -> new Attempts(v == null || v.until() < now ? 1 : v.count() + 1, now + 900000));
      throw new Api.Failure(400, "验证码不正确或已过期");
    }
    if (jdbc.update(
            "UPDATE app_user SET mfa_enabled=TRUE,last_totp_step=? WHERE id=? AND"
                + " mfa_enabled=FALSE",
            step,
            user.id())
        != 1) throw new Api.Failure(409, "验证状态已变化，请重新登录");
    jdbc.update(
        "DELETE FROM app_session WHERE user_id=? AND token_hash<>?",
        user.id(),
        sessionDigest(cookie(req), StaffEntry.authorized(req)));
    jdbc.update(
        "UPDATE app_session SET mfa_verified=TRUE WHERE token_hash=?",
        sessionDigest(cookie(req), StaffEntry.authorized(req)));
    failures.remove(key);
  }
}
