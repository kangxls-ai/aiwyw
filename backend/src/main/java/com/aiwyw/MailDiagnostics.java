package com.aiwyw;

import jakarta.mail.*;
import jakarta.servlet.http.HttpServletRequest;
import java.net.*;
import java.util.*;
import java.util.concurrent.Semaphore;
import javax.net.ssl.SSLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.web.bind.annotation.*;

/** 共用发信连接与脱敏诊断；POP3/IMAP只认证并关闭，不读取、移动或删除用户邮件。 */
@RestController
class MailDiagnostics {
  private final Settings settings;
  private final Auth auth;
  private final JdbcTemplate db;
  private final Semaphore testing = new Semaphore(1);

  MailDiagnostics(Settings settings, Auth auth, JdbcTemplate db) {
    this.settings = settings;
    this.auth = auth;
    this.db = db;
  }

  static Properties properties(String protocol) {
    var p = new Properties();
    p.setProperty("mail." + protocol + ".ssl.checkserveridentity", "true");
    p.setProperty("mail." + protocol + ".connectiontimeout", "5000");
    p.setProperty("mail." + protocol + ".timeout", "5000");
    p.setProperty("mail." + protocol + ".writetimeout", "5000");
    // Java 17默认协议集合由JDK安全策略维护，不降低证书校验或信任任意主机。
    return p;
  }

  static JavaMailSenderImpl sender(Settings s) {
    var sender = new JavaMailSenderImpl();
    sender.setHost(s.value("mail.host"));
    sender.setPort(s.integer("mail.port"));
    sender.setUsername(s.value("mail.username"));
    sender.setPassword(s.value("mail.password"));
    var p = properties("smtp");
    p.setProperty("mail.smtp.auth", "true");
    if (s.value("mail.security").equals("TLS")) p.setProperty("mail.smtp.ssl.enable", "true");
    else {
      p.setProperty("mail.smtp.starttls.enable", "true");
      p.setProperty("mail.smtp.starttls.required", "true");
    }
    sender.setJavaMailProperties(p);
    return sender;
  }

  static String code(Throwable error) {
    Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    Deque<Throwable> pending = new ArrayDeque<>();
    pending.add(error);
    String result = "DELIVERY";
    while (!pending.isEmpty()) {
      var e = pending.removeFirst();
      if (!seen.add(e)) continue;
      if (e instanceof AuthenticationFailedException || e instanceof MailAuthenticationException)
        return "AUTH";
      if (e instanceof SSLException) return "TLS";
      if (e instanceof UnknownHostException) result = "DNS";
      else if (e instanceof SocketTimeoutException && !result.equals("DNS")) result = "TIMEOUT";
      else if (e instanceof ConnectException && result.equals("DELIVERY")) result = "CONNECT";
      if (e.getCause() != null) pending.add(e.getCause());
      if (e instanceof MessagingException m && m.getNextException() != null)
        pending.add(m.getNextException());
      if (e instanceof org.springframework.mail.MailSendException m)
        pending.addAll(m.getFailedMessages().values());
    }
    return result;
  }

  static String hint(String code) {
    return switch (code) {
      case "AUTH" -> "邮箱认证失败。QQ请在邮箱网页开启相应协议，并使用完整邮箱地址和授权码（不是QQ登录密码）；移除授权码中的空格，必要时重新生成。";
      case "TLS" ->
          "TLS握手或证书验证失败。检查端口与加密方式是否匹配、服务器时间及JDK可信证书；不要关闭证书验证。QQ SMTP 465使用SSL/TLS，587使用STARTTLS。";
      case "DNS" -> "主机名无法解析，请核对服务商地址及部署服务器DNS。";
      case "TIMEOUT" -> "连接或响应超时，请检查服务器出站防火墙、服务商访问限制与端口。";
      case "CONNECT" -> "无法建立连接，请核对主机、端口和服务器出站网络。";
      default -> "服务器拒绝或未完成邮件操作。检查发信邮箱是否与授权账号一致、服务商限额与风控；连接检查成功也不等于最终入箱。";
    };
  }

  record Check(String protocol) {}

  @PostMapping("/api/admin/mail/check")
  Map<String, Object> check(@RequestBody Check body, HttpServletRequest req) {
    auth.admin(req);
    if (body.protocol() == null || !Set.of("SMTP", "POP3", "IMAP").contains(body.protocol()))
      throw new Api.Failure(400, "请选择SMTP、POP3或IMAP");
    return execute(body.protocol(), false, null);
  }

  @PostMapping("/api/admin/mail/test")
  Map<String, Object> sendTest(HttpServletRequest req) {
    var user = auth.admin(req);
    // 测试收件人固定为当前管理员，不接受任意地址，避免成为群发接口。
    return execute("SMTP", true, user.email());
  }

  private Map<String, Object> execute(String protocol, boolean send, String recipient) {
    if (settings.value("mail.username").isBlank()
        || settings.value("mail.password").isBlank()
        || settings.value("mail.host").isBlank())
      throw new Api.Failure(400, "请先保存SMTP主机、完整邮箱账号和密码或授权码");
    if (send && settings.value("mail.from").isBlank()) throw new Api.Failure(400, "请先保存发信邮箱");
    if (!testing.tryAcquire()) throw new Api.Failure(429, "另一项邮件测试正在进行，请稍后再试");
    long start = System.nanoTime();
    try {
      if (protocol.equals("SMTP")) {
        var sender = sender(settings);
        if (!send) sender.testConnection();
        else {
          var message = new SimpleMailMessage();
          message.setFrom(settings.value("mail.from"));
          message.setTo(recipient);
          message.setSubject(settings.value("site.name") + " 邮件配置测试");
          message.setText("这是管理员手动触发的测试邮件。网站使用SMTP投递注册验证、密码找回等邮件。");
          sender.send(message);
        }
      } else {
        String prefix = protocol.equals("POP3") ? "pop3" : "imap", transport = prefix + "s";
        var p = properties(transport);
        p.setProperty("mail." + transport + ".ssl.enable", "true");
        try (var store = Session.getInstance(p).getStore(transport)) {
          store.connect(
              settings.value("mail." + prefix + ".host"),
              settings.integer("mail." + prefix + ".port"),
              settings.value("mail.username"),
              settings.value("mail.password"));
        }
      }
      return Map.of(
          "success",
          true,
          "protocol",
          protocol,
          "code",
          "OK",
          "elapsedMs",
          (System.nanoTime() - start) / 1000000,
          "message",
          send ? "测试邮件已交给SMTP服务器，收件人为当前管理员；请检查收件箱及垃圾箱。" : protocol + "连接、加密及账号认证通过；本次未发送或读取邮件。");
    } catch (Exception e) {
      String code = code(e);
      return Map.of(
          "success",
          false,
          "protocol",
          protocol,
          "code",
          code,
          "elapsedMs",
          (System.nanoTime() - start) / 1000000,
          "message",
          hint(code));
    } finally {
      testing.release();
    }
  }

  @GetMapping("/api/admin/mail/status")
  Map<String, Object> status(HttpServletRequest req) {
    auth.admin(req);
    // 不返回收件地址、正文或验证链接；队列只显示可操作的失败分类。
    return Map.of(
        "enabled",
        Boolean.parseBoolean(settings.value("mail.enabled")),
        "pending",
        db.queryForObject("SELECT COUNT(*) FROM mail_outbox WHERE status<>'SENT'", Long.class),
        "recentFailures",
        Commerce.safeRows(
            db.queryForList(
                "SELECT last_error_code,COUNT(*) AS total FROM mail_outbox WHERE status<>'SENT' AND"
                    + " last_error_code IS NOT NULL GROUP BY last_error_code")));
  }
}
