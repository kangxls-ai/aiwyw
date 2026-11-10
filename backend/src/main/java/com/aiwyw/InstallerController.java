package com.aiwyw;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

/** 一次性安装向导。安装码保护写入数据库凭据及创建首个管理员，已安装后拒绝再次初始化，绝不覆盖已有用户库。 */
@RestController
@RequestMapping("/api/install")
public class InstallerController {
  private final Database db;
  private final Settings settings;
  private final SecureStore crypto;
  private final StaffEntry staffEntry;

  public InstallerController(
      Database db, Settings settings, SecureStore crypto, StaffEntry staffEntry) {
    this.db = db;
    this.settings = settings;
    this.crypto = crypto;
    this.staffEntry = staffEntry;
  }

  public record InstallRequest(
      Database.ConnectionSpec connection,
      @Email @NotBlank String email,
      @Size(min = 12, max = 128) String password) {}

  @GetMapping("/status")
  /** 只返回是否安装和版本，不返回安装码、数据库密码或主密钥。 */
  public Map<String, Object> status() {
    return Map.of(
        "installed",
        db.installed(),
        "version",
        "0.1.0",
        "requiresToken",
        !db.installed(),
        "production", db.production(), "externalDatabase", db.configuredConnection()!=null);
  }

  /** 同时检查安装锁和常量时间安装码校验，避免远程抢先初始化。 */
  private void verify(String token) {
    if (db.installed()) throw new Api.Failure(409, "系统已安装，不能重复初始化");
    if (!db.validToken(token)) throw new Api.Failure(403, "安装凭据不正确，请使用服务首次启动时提供的安装码");
  }

  @PostMapping("/test-database")
  /** 临时连接验证数据库权限与TLS，连接池在请求结束立即关闭，不改变当前数据库。 */
  public Map<String, Boolean> test(
      @RequestHeader(value = "X-Install-Token", required = false) String token,
      @RequestBody(required = false) Database.ConnectionSpec requested) {
    verify(token);
    Database.ConnectionSpec spec = connection(requested);
    db.requireProductionEngine(spec);
    try (var pool = db.connect(spec);
        var connection = pool.getConnection()) {
      connection.isValid(3);
      return Map.of("success", true);
    } catch (Api.Failure e) {
      throw e;
    } catch (Exception e) {
      throw new Api.Failure(400, "数据库连接失败，请检查地址、账号、权限和TLS设置");
    }
  }

  @PostMapping
  /** 对空目标库建表，事务内种子配置与管理员；引导资料成功持久化后才激活连接池，失败关闭新池。 */
  public synchronized Map<String, Object> install(
      @RequestHeader(value = "X-Install-Token", required = false) String token,
      @Valid @RequestBody InstallRequest req)
      throws Exception {
    verify(token);
    if (req.password() == null || req.password().isBlank())
      throw new Api.Failure(400, "管理员密码至少12位");
    var spec=connection(req.connection());
    db.requireProductionEngine(spec);
    var pool = db.connect(spec);
    boolean activated = false;
    try {
      Database.schema(pool);
      var j = new JdbcTemplate(pool);
      if (j.queryForObject("SELECT COUNT(*) FROM app_user", Integer.class) != 0)
        throw new Api.Failure(409, "目标数据库已有用户，请勿覆盖；请选择空库或恢复原启动资料");
      var transaction = new TransactionTemplate(new DataSourceTransactionManager(pool));
      transaction.executeWithoutResult(
          status -> {
            settings.seed(j);
            j.update(
                "INSERT INTO"
                    + " app_user(id,email,password_hash,role_name,mfa_enabled,last_totp_step,created_at,nickname)"
                    + " VALUES(?,?,?,'ADMIN',FALSE,-1,?,?)",
                UUID.randomUUID().toString(),
                req.email().trim().toLowerCase(Locale.ROOT),
                crypto.password(req.password()),
                System.currentTimeMillis(),
                Identity.nickname());
          });
      String installedAdmin =
          j.queryForObject(
              "SELECT id FROM app_user WHERE email=?",
              String.class,
              req.email().trim().toLowerCase(Locale.ROOT));
      j.update("INSERT INTO access_user_role VALUES(?,'ADMIN')", installedAdmin);
      db.finish(spec);
      db.activate(pool);
      activated = true;
      return Map.of("installed", true, "staffLoginPath", staffEntry.path() + "/login");
    } finally {
      if (!activated) pool.close();
    }
  }
  private Database.ConnectionSpec connection(Database.ConnectionSpec requested) {
    var configured=db.configuredConnection();
    if(configured!=null) return configured;
    if(requested==null) throw new Api.Failure(400,"请填写数据库连接");
    return requested;
  }

}
