package com.aiwyw;

import jakarta.servlet.http.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** 身份HTTP边界与会话Cookie生命周期；登录返回不包含令牌明文的JSON，退出同时撤销服务端会话。 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {
  private final Auth auth;
  private final Database database;
  private final Commerce wallet;
  private final Settings settings;

  public AuthController(Auth auth, Database database, Commerce wallet, Settings settings) {
    this.auth = auth;
    this.wallet = wallet;
    this.settings = settings;
    this.database = database;
  }

  public record Login(String email, String password, String code) {}

  public record Code(String code) {}

  /** 生产入口清洗转发头且内部服务只监听回环；所有身份及访客Cookie统一识别代理终止的HTTPS。 */
  static boolean secure(HttpServletRequest request) {
    return request.isSecure() || "https".equalsIgnoreCase(request.getHeader("X-Forwarded-Proto"));
  }

  /** HttpOnly/SameSite保护身份令牌；生产HTTPS设置Secure。反向代理必须清洗不可信转发头。 */
  private String cookie(String value, HttpServletRequest r, boolean clear) {
    return ResponseCookie.from("AIWYW_SESSION", value)
        .httpOnly(true)
        .secure(secure(r))
        .sameSite("Strict")
        .path("/")
        .maxAge(clear ? 0 : 86400)
        .build()
        .toString();
  }

  @PostMapping("/login")
  /** 系统安装锁定后才允许登录，错误信息不透露账户是否存在。 */
  public ResponseEntity<?> login(@RequestBody Login input, HttpServletRequest r) {
    if (!database.installed()) throw new Api.Failure(409, "请先完成安装");
    String token = auth.login(input.email(), input.password(), input.code(), r.getRemoteAddr());
    reward(auth.userToken(token, false));
    return ResponseEntity.ok()
        .header(HttpHeaders.SET_COOKIE, cookie(token, r, false))
        .body(Map.of("success", true));
  }

  @GetMapping("/session")
  /** 返回已验证的服务端用户摘要，角色由数据库决定。 */
  public Auth.User session(HttpServletRequest r) {
    var user = auth.user(r, false);
    reward(user);
    return user;
  }

  /** 有效持久会话跨日恢复同样算当天登录；待完成强制MFA的会话不发奖。 */
  private void reward(Auth.User user) {
    if (!user.mfaRequired() || user.mfaVerified())
      wallet.dailyLogin(user.id(), java.time.Instant.now());
  }

  @PostMapping("/logout")
  /** 删除服务端会话并使浏览器Cookie过期。 */
  public ResponseEntity<?> logout(HttpServletRequest r) {
    auth.logout(r);
    return ResponseEntity.ok()
        .header(HttpHeaders.SET_COOKIE, cookie("", r, true))
        .body(Map.of("success", true));
  }

  @PostMapping("/mfa/enroll")
  /** 返回当前用户的待绑定验证器资料，不接受指定其他用户ID。 */
  public Map<String, String> enroll(HttpServletRequest r) {
    var u = auth.user(r, false);
    String secret = auth.enroll(u);
    String issuer =
        URLEncoder.encode(settings.value("site.name"), StandardCharsets.UTF_8).replace("+", "%20");
    return Map.of(
        "secret",
        secret,
        "uri",
        "otpauth://totp/"
            + issuer
            + ":"
            + URLEncoder.encode(u.email(), StandardCharsets.UTF_8)
            + "?secret="
            + secret
            + "&issuer="
            + issuer
            + "&algorithm=SHA1&digits=6&period=30");
  }

  @PostMapping("/mfa/confirm")
  /** 确认动态验证码后更新当前会话MFA状态，不能仅更新浏览器状态。 */
  public Map<String, Boolean> confirm(@RequestBody Code code, HttpServletRequest r) {
    auth.confirm(auth.user(r, false), code.code(), r);
    reward(auth.user(r, false));
    return Map.of("success", true);
  }
}
