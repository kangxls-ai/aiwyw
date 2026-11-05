package com.aiwyw;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

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

  private record Attempts(int count, long until) {}

  private final Map<String, Attempts> failures = new ConcurrentHashMap<>();
  private final SecureStore crypto;
  private final Settings settings;
  private final JdbcTemplate jdbc;

  public Auth(SecureStore crypto, Settings settings, JdbcTemplate jdbc) {
    this.crypto = crypto;
    this.settings = settings;
    this.jdbc = jdbc;
  }

  /** 按来源与账户限速；密码与已启用TOTP都通过后创建新会话。TOTP步长使用CAS更新，拒绝并发重放。 */
  public String login(String email, String password, String code, String ip) {
    if (email == null || password == null || email.length() > 254 || password.length() > 256)
      throw new Api.Failure(401, "邮箱、密码或验证码不正确");
    String key = crypto.digest(ip + ":" + email.toLowerCase(Locale.ROOT));
    long now = System.currentTimeMillis();
    failures.entrySet().removeIf(e -> e.getValue().until() < now);
    Attempts a = failures.get(key);
    if (a != null && a.count() >= settings.integer("security.login_attempts"))
      throw new Api.Failure(429, "尝试次数过多，请15分钟后再试");
    var rows =
        jdbc.queryForList(
            "SELECT * FROM app_user WHERE email=?", email.trim().toLowerCase(Locale.ROOT));
    boolean accepted = false, mfa = false;
    String id = "";
    if (!rows.isEmpty()) {
      var row = rows.get(0);
      id = (String) row.get("id");
      accepted =
          !Boolean.TRUE.equals(row.get("disabled"))
              && crypto.verifyPassword(password, (String) row.get("password_hash"));
      if (accepted
          && Boolean.parseBoolean(settings.value("security.mfa_required"))
          && Boolean.TRUE.equals(row.get("mfa_enabled"))) {
        long step =
            crypto.validTotpStep(
                crypto.decrypt((String) row.get("totp_secret")),
                code,
                ((Number) row.get("last_totp_step")).longValue());
        accepted =
            step >= 0
                && jdbc.update(
                        "UPDATE app_user SET last_totp_step=? WHERE id=? AND last_totp_step<?",
                        step,
                        id,
                        step)
                    == 1;
        mfa = accepted;
      }
    } else crypto.verifyPassword(password, crypto.password("invalid-account-placeholder"));
    if (!accepted) {
      failures.compute(key, (k, v) -> new Attempts(v == null ? 1 : v.count() + 1, now + 900000));
      throw new Api.Failure(401, "邮箱、密码或验证码不正确");
    }
    failures.remove(key);
    jdbc.update("DELETE FROM app_session WHERE expires_at<?", now);
    String token = crypto.token();
    jdbc.update(
        "INSERT INTO app_session(token_hash,user_id,expires_at,mfa_verified) VALUES(?,?,?,?)",
        crypto.digest(token),
        id,
        now + settings.integer("security.session_hours") * 3600000L,
        mfa);
    return token;
  }

  /** 从服务端会话联查账户，每次验证有效期与可选MFA要求；不信任客户端角色。 */
  public User user(HttpServletRequest request, boolean requireMfa) {
    String token = cookie(request);
    if (token == null) throw new Api.Failure(401, "请先登录");
    var rows =
        jdbc.queryForList(
            "SELECT u.id,u.email,u.role_name,u.nickname,s.mfa_verified FROM app_session s JOIN"
                + " app_user u ON u.id=s.user_id WHERE u.disabled=FALSE AND s.token_hash=? AND"
                + " s.expires_at>?",
            crypto.digest(token),
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
              ? "publishing"
              : "applications";
    else if (p.contains("commerce-packages")) key = "packages";
    else if (p.contains("/commerce/")) {
      String k = p.split("/commerce/")[1].split("/")[0];
      key = k.equals("resources") ? "orders" : k;
    }
    if (!u.menus().contains(key)
        && !(key.equals("applications")
            && (u.menus().contains("versions")
                || u.menus().contains("publishing")
                || (r.getMethod().equals("GET")
                    && p.endsWith("/options")
                    && u.menus().contains("licenses"))))) throw new Api.Failure(403, "没有该菜单权限");
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

  /** 用户充值、兑换及API要求实际邮箱验证；管理员身份不绕过交易邮箱要求。 */
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
      jdbc.update("DELETE FROM app_session WHERE token_hash=?", crypto.digest(token));
  }

  /** 只从固定HttpOnly会话Cookie读取，不接受URL查询中的身份令牌。 */
  public String cookie(HttpServletRequest req) {
    if (req.getCookies() != null)
      for (var c : req.getCookies()) if (c.getName().equals("AIWYW_SESSION")) return c.getValue();
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
        crypto.digest(cookie(req)));
    jdbc.update(
        "UPDATE app_session SET mfa_verified=TRUE WHERE token_hash=?", crypto.digest(cookie(req)));
    failures.remove(key);
  }
}
