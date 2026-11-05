package com.aiwyw;

import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 为已存在的P1表补齐表/字段注释，生产MySQL也能直接在数据库客户端阅读含义。 只使用源码固定标识符和类型，不拼接用户输入。每步可重入，全部成功才记迁移完成。 MySQL
 * DDL会隐式提交，因此本迁移不与资金事务混用，也不假装能整体事务回滚。
 */
public final class SchemaComments {
  private SchemaComments() {}

  private record Column(String name, String definition, String description) {}

  private record Table(String name, String description, Column[] columns) {}

  private static Column c(String name, String definition, String description) {
    return new Column(name, definition, description);
  }

  private static final Table[] TABLES = {
    new Table(
        "app_user",
        "账户及后台角色；MFA密钥加密，密码仅保存不可逆摘要",
        new Column[] {
          c("id", "VARCHAR(36) NOT NULL", "账户UUID，不能用邮箱关联历史业务"),
          c("email", "VARCHAR(254) NOT NULL", "规范化小写邮箱，唯一索引防重复账户"),
          c("password_hash", "VARCHAR(512) NOT NULL", "PBKDF2迭代参数、随机盐和摘要，非密码明文"),
          c(
              "role_name",
              "VARCHAR(30) NOT NULL",
              "ADMIN管理员、OPERATOR商品运营、FINANCE财务、SUPPORT客服、USER用户"),
          c("totp_secret", "TEXT NULL", "AES-GCM加密的TOTP密钥，依赖master.key解密"),
          c("mfa_enabled", "BOOLEAN NOT NULL", "是否完成MFA绑定，不等于本次会话已经验证"),
          c("last_totp_step", "BIGINT NOT NULL DEFAULT -1", "最近已使用30秒步长；原子比较更新拒绝验证码重放"),
          c("created_at", "BIGINT NOT NULL", "创建时间UTC毫秒")
        }),
    new Table(
        "app_session",
        "服务端可撤销会话；过期和退出均不得继续访问",
        new Column[] {
          c("token_hash", "VARCHAR(64) NOT NULL", "Cookie随机令牌的SHA256摘要；禁止存令牌原文"),
          c("user_id", "VARCHAR(36) NOT NULL", "关联app_user.id"),
          c("expires_at", "BIGINT NOT NULL", "会话过期UTC毫秒"),
          c("mfa_verified", "BOOLEAN NOT NULL", "本次会话是否完成第二因素验证")
        }),
    new Table(
        "system_config",
        "运营配置；只允许注册键，敏感值加密，修改必须带revision",
        new Column[] {
          c("config_key", "VARCHAR(100) NOT NULL", "Settings.DEFINITIONS中的唯一配置键"),
          c("config_value", "TEXT NOT NULL", "按定义存文本或AES-GCM密文；敏感值不得手动填明文"),
          c("revision", "BIGINT NOT NULL", "每次成功保存递增，用于防止覆盖并发修改"),
          c("updated_at", "BIGINT NOT NULL", "最近修改UTC毫秒")
        }),
    new Table(
        "config_audit",
        "配置操作审计；仅记键和动作，不保存敏感旧值新值",
        new Column[] {
          c("id", "VARCHAR(36) NOT NULL", "审计UUID"),
              c("user_id", "VARCHAR(36) NOT NULL", "操作管理员账户ID"),
          c("config_key", "VARCHAR(100) NOT NULL", "被修改的配置键"),
              c("revision", "BIGINT NOT NULL", "修改后的配置版本"),
          c("action_name", "VARCHAR(40) NOT NULL", "UPDATE修改或CLEAR明确清除敏感值"),
              c("created_at", "BIGINT NOT NULL", "操作UTC毫秒")
        })
  };

  public static void migrate(DataSource source, JdbcTemplate db) {
    db.execute(
        "CREATE TABLE IF NOT EXISTS schema_migration (version VARCHAR(80) PRIMARY KEY COMMENT"
            + " '已完成迁移的唯一版本',description VARCHAR(200) NOT NULL COMMENT '迁移说明',applied_at BIGINT NOT"
            + " NULL COMMENT '成功UTC毫秒') COMMENT='启动迁移记录；禁止通过删记录强制重复破坏性迁移'");
    String version = "V002_BASE_COMMENTS";
    if (db.queryForObject(
            "SELECT COUNT(*) FROM schema_migration WHERE version=?", Integer.class, version)
        > 0) return;
    boolean mysql;
    try (var connection = source.getConnection()) {
      mysql = connection.getMetaData().getDatabaseProductName().equals("MySQL");
    } catch (SQLException failure) {
      throw new IllegalStateException("Cannot inspect schema dialect", failure);
    }
    for (Table table : TABLES) {
      db.execute(
          mysql
              ? "ALTER TABLE " + table.name() + " COMMENT='" + table.description() + "'"
              : "COMMENT ON TABLE " + table.name() + " IS '" + table.description() + "'");
      for (Column column : table.columns())
        db.execute(
            mysql
                ? "ALTER TABLE "
                    + table.name()
                    + " MODIFY COLUMN "
                    + column.name()
                    + " "
                    + column.definition()
                    + " COMMENT '"
                    + column.description()
                    + "'"
                : "COMMENT ON COLUMN "
                    + table.name()
                    + "."
                    + column.name()
                    + " IS '"
                    + column.description()
                    + "'");
    }
    try {
      db.update(
          "INSERT INTO schema_migration(version,description,applied_at) VALUES(?,?,?)",
          version,
          "P1基础表与字段中文注释",
          System.currentTimeMillis());
    } catch (org.springframework.dao.DuplicateKeyException anotherInstanceFinished) {
      /* 幂等注释写入后，允许另一个实例先完成登记。 */
    }
  }
}
