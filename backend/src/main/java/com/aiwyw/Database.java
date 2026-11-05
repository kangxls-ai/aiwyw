package com.aiwyw;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;

/** 连接引导与无损建表。安装前只能使用本地临时引导库；安装后读取加密bootstrap.enc。连接切换只在安装成功后执行，禁止以重建库处理业务异常。 */
@Configuration
public class Database {
  public record ConnectionSpec(
      String engine, String host, int port, String database, String username, String password) {}

  private final SecureStore store;
  private final ObjectMapper json;
  private volatile boolean installed;
  private final String installToken;
  private SwitchingSource source;
  // 安装切库后递增，业务缓存必须检测代数，防止返回安装前开发库的数据。
  private volatile long generation;

  public long generation() {
    return generation;
  }

  public Database(SecureStore store, ObjectMapper json) throws Exception {
    this.store = store;
    this.json = json;
    installed = Files.exists(store.root.resolve("bootstrap.enc"));
    Path token = store.root.resolve("install.token");
    if (!installed && !Files.exists(token)) {
      Files.writeString(token, store.token(), StandardOpenOption.CREATE_NEW);
      store.protect(token);
    }
    installToken = installed ? "" : Files.readString(token).trim();
    if (!installed) System.out.println("AIWYW first installation token: " + installToken);
  }

  @Bean
  /** 构造连接池并执行幂等建表/注释迁移；引导资料损坏应停止启动而不是换空库。 */
  public DataSource dataSource() throws Exception {
    HikariDataSource delegate;
    if (installed)
      delegate =
          connect(
              json.readValue(
                  store.decrypt(Files.readString(store.root.resolve("bootstrap.enc"))),
                  ConnectionSpec.class));
    else delegate = connect(new ConnectionSpec("H2", "", 0, "", "", ""));
    source = new SwitchingSource(delegate);
    schema(source);
    return source;
  }

  public boolean installed() {
    return installed;
  }

  public boolean validToken(String token) {
    return !installed
        && token != null
        && java.security.MessageDigest.isEqual(
            token.getBytes(java.nio.charset.StandardCharsets.UTF_8),
            installToken.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  /** 只接收受限主机和库名；生产MySQL强制TLS，连接超时有上限。 */
  public HikariDataSource connect(ConnectionSpec spec) {
    HikariDataSource d = new HikariDataSource();
    if ("MYSQL".equals(spec.engine())) {
      if (spec.host() == null
          || !spec.host().matches("[a-zA-Z0-9._:-]{1,253}")
          || spec.database() == null
          || !spec.database().matches("[a-zA-Z0-9_]{1,64}")
          || spec.port() < 1
          || spec.port() > 65535) throw new Api.Failure(400, "数据库地址、端口或库名格式不正确");
      d.setJdbcUrl(
          "jdbc:mysql://"
              + spec.host()
              + ":"
              + spec.port()
              + "/"
              + spec.database()
              + "?sslMode=REQUIRED&connectionTimeZone=UTC&connectTimeout=5000&socketTimeout=10000");
      d.setUsername(spec.username());
      d.setPassword(spec.password());
    } else if ("H2".equals(spec.engine())) {
      d.setJdbcUrl(
          "jdbc:h2:file:"
              + store.root.resolve("local").toString().replace('\\', '/')
              + ";MODE=MySQL;DB_CLOSE_ON_EXIT=FALSE");
      d.setUsername("sa");
      d.setPassword(store.localPassword());
    } else throw new Api.Failure(400, "请选择MySQL或本地开发数据库");
    d.setMaximumPoolSize(5);
    d.setConnectionTimeout(7000);
    d.setInitializationFailTimeout(5000);
    return d;
  }

  /** 只增加缺失表与说明，不删除现有记录；生产DDL隐式提交，先备份再升级。 */
  public static void schema(DataSource d) {
    JdbcTemplate j = new JdbcTemplate(d);
    j.execute(
        "CREATE TABLE IF NOT EXISTS app_user (id VARCHAR(36) PRIMARY KEY,email VARCHAR(254) NOT"
            + " NULL UNIQUE,password_hash VARCHAR(512) NOT NULL,role_name VARCHAR(30) NOT"
            + " NULL,totp_secret TEXT,mfa_enabled BOOLEAN NOT NULL,last_totp_step BIGINT NOT NULL"
            + " DEFAULT -1,created_at BIGINT NOT NULL)");
    j.execute(
        "CREATE TABLE IF NOT EXISTS app_session (token_hash VARCHAR(64) PRIMARY KEY,user_id"
            + " VARCHAR(36) NOT NULL,expires_at BIGINT NOT NULL,mfa_verified BOOLEAN NOT NULL)");
    j.execute(
        "CREATE TABLE IF NOT EXISTS system_config (config_key VARCHAR(100) PRIMARY KEY,config_value"
            + " TEXT NOT NULL,revision BIGINT NOT NULL,updated_at BIGINT NOT NULL)");
    j.execute(
        "CREATE TABLE IF NOT EXISTS config_audit (id VARCHAR(36) PRIMARY KEY,user_id VARCHAR(36)"
            + " NOT NULL,config_key VARCHAR(100) NOT NULL,revision BIGINT NOT NULL,action_name"
            + " VARCHAR(40) NOT NULL,created_at BIGINT NOT NULL)");
    CatalogSchema.migrate(j);
    SchemaComments.migrate(d, j);
    StorageSchema.migrate(j);
    CatalogQuerySchema.migrate(d, j);
    CatalogTags.schema(j);
    CommerceSchema.migrate(d, j);
    ListSchema.migrate(d, j);
    LicenseSchema.migrate(j);
    Identity.schema(d, j);
    ContentSchema.migrate(j);
    OperationsSchema.migrate(j);
  }

  /** 先写加密待定文件再原子更名；成功后锁安装入口并删除一次性安装码。 */
  public synchronized void finish(ConnectionSpec spec) throws Exception {
    String encrypted = store.encrypt(json.writeValueAsString(spec));
    Path temporary = store.root.resolve("bootstrap.pending");
    Files.writeString(temporary, encrypted);
    store.protect(temporary);
    try {
      Files.move(temporary, store.root.resolve("bootstrap.enc"), StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(temporary, store.root.resolve("bootstrap.enc"));
    }
    installed = true;
    Files.deleteIfExists(store.root.resolve("install.token"));
  }

  /** 安装向导持有新连接池，成功后才替换；缓存代数随之改变。 */
  public void activate(HikariDataSource next) {
    source.switchTo(next);
    generation++;
  }

  public static class SwitchingSource extends AbstractDataSource implements AutoCloseable {
    private volatile HikariDataSource delegate;

    SwitchingSource(HikariDataSource d) {
      delegate = d;
    }

    void switchTo(HikariDataSource d) {
      HikariDataSource old = delegate;
      delegate = d;
      old.close();
    }

    @Override
    public Connection getConnection() throws SQLException {
      return delegate.getConnection();
    }

    @Override
    public Connection getConnection(String user, String pass) throws SQLException {
      return delegate.getConnection(user, pass);
    }

    @Override
    public void close() {
      delegate.close();
    }
  }
}
