package com.aiwyw;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

/** 上线检查报告只陈述可验证事实；证书、备份恢复及容量必须在目标服务器另行验收。 */
@RestController
class ProductionReadiness {
  final Database database;
  final Settings settings;
  final JdbcTemplate db;
  final Auth auth;

  ProductionReadiness(Database database, Settings settings, JdbcTemplate db, Auth auth) {
    this.database = database;
    this.settings = settings;
    this.db = db;
    this.auth = auth;
  }

  record Check(String name, String state, String detail) {}

  static boolean https(String value) {
    try {
      var u = URI.create(value);
      return "https".equals(u.getScheme())
          && u.getHost() != null
          && !Set.of("localhost", "127.0.0.1", "::1").contains(u.getHost());
    } catch (Exception e) {
      return false;
    }
  }

  @GetMapping("/api/admin/production/readiness")
  Map<String, Object> report(HttpServletRequest req) {
    auth.admin(req);
    var checks = new ArrayList<Check>();
    checks.add(
        new Check(
            "运行模式",
            database.production() ? "PASS" : "BLOCK",
            database.production() ? "已按生产模式启动" : "当前是本地验证模式；正式部署包以生产模式启动"));
    checks.add(
        new Check(
            "数据库",
            database.engine().equals("MYSQL") ? "PASS" : "BLOCK",
            database.engine().equals("MYSQL") ? "已连接MySQL" : "正式运行必须使用MySQL"));
    boolean fixtures =
        db.queryForObject(
                "SELECT COUNT(*) FROM schema_migration WHERE version LIKE 'LOCAL!_REVIEW!_%' ESCAPE"
                    + " '!'",
                Long.class)
            > 0;
    checks.add(
        new Check(
            "验收数据隔离",
            fixtures ? "BLOCK" : "PASS",
            fixtures ? "当前库含本地合成验收标记；生产请安装到新库，不迁移该库" : "未发现本地验收数据标记"));
    boolean mail =
        Boolean.parseBoolean(settings.value("mail.enabled"))
            && !settings.value("mail.host").isBlank()
            && !settings.value("mail.username").isBlank()
            && !settings.value("mail.password").isBlank()
            && !settings.value("mail.from").isBlank();
    checks.add(
        new Check(
            "邮件配置",
            mail ? "REVIEW" : "BLOCK",
            mail ? "配置已填写；还需从生产服务器连接测试并实际完成注册验证和找回密码" : "请在配置管理开启邮件并保存发信资料"));
    checks.add(
        new Check(
            "验证邮件链接",
            https(settings.value("account.link_origin")) ? "PASS" : "BLOCK",
            "请配置本站HTTPS地址；当前值：" + settings.value("account.link_origin")));
    // 现行策略是管理员/财务统一开关，不读取已废弃的两个旧版键。
    boolean mfa = Boolean.parseBoolean(settings.value("security.mfa_required"));
    checks.add(
        new Check(
            "管理及财务双因素策略", mfa ? "PASS" : "BLOCK", mfa ? "策略已开启；上线账户需完成绑定" : "当前使用紧急关闭策略，上线前请恢复"));
    String filing = settings.value("site.filing");
    checks.add(
        new Check(
            "备案信息",
            filing.isBlank() || filing.contains("模拟") || filing.contains("占位") ? "REVIEW" : "PASS",
            "请按部署地区及实际备案结果维护，不以占位号作为正式备案"));
    checks.add(new Check("真实收款", "REVIEW", "真实收款仍未开放。当前仅模拟积分交易，不可对外提供真实转账充值"));
    checks.add(
        new Check(
            "反向代理来源IP",
            "REVIEW",
            "单机Nginx部署需在配置管理将前台可信反向代理IP设为127.0.0.1,::1；公网只能访问Nginx，禁止暴露3000/18080/3306"));
    checks.add(
        new Check(
            "备份、恢复与容量", "REVIEW", "在目标服务器完成数据库+data/master.key配套备份恢复演练、HTTPS与峰值容量测试；此页面不自动判定通过"));
    return Map.of(
        "mode",
        database.production() ? "production" : "development",
        "blocked",
        checks.stream().anyMatch(c -> c.state().equals("BLOCK")),
        "checks",
        checks);
  }
}
