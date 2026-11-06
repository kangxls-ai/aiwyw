package com.aiwyw;

import java.util.Locale;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** 来源信息独立于业务表，避免改变历史快照或依赖无依据的创建人回填；审计IP为空表示历史未知。 */
final class ManagementSchema {
  static void migrate(DataSource source, JdbcTemplate db) {
    db.execute(
        """
        CREATE TABLE IF NOT EXISTS object_origin (
          object_type VARCHAR(60) NOT NULL COMMENT '源码白名单业务表名，不接受客户端标识符',
          object_id VARCHAR(100) NOT NULL COMMENT '原始业务主键，不重新编号',
          created_at BIGINT NOT NULL COMMENT '有依据的创建UTC毫秒，历史未知为0',
          created_by VARCHAR(100) COMMENT '实际创建账户ID；SYSTEM为系统，GUEST前缀为游客，历史未知为空',
          PRIMARY KEY(object_type,object_id)
        ) COMMENT='对象创建来源；与创建事务一起写入，后续修改不覆盖'
        """);
    try (var c = source.getConnection()) {
      var meta = c.getMetaData();
      for (String table :
          new String[] {"config_audit", "catalog_audit", "storage_audit", "finance_audit"}) {
        String physical =
            meta.storesUpperCaseIdentifiers() ? table.toUpperCase(Locale.ROOT) : table;
        boolean present;
        try (var cols = meta.getColumns(c.getCatalog(), null, physical, null)) {
          present = false;
          while (cols.next())
            if (cols.getString("COLUMN_NAME").equalsIgnoreCase("source_ip")) present = true;
        }
        if (!present)
          db.execute(
              "ALTER TABLE "
                  + table
                  + " ADD COLUMN source_ip VARCHAR(45) NULL COMMENT '操作来源IP；仅信任已配置代理，历史未知为空'");
      }
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException("来源信息迁移失败，保留原数据", e);
    }
    if (db.queryForObject(
            "SELECT COUNT(*) FROM schema_migration WHERE version='V010_OBJECT_ORIGIN'",
            Integer.class)
        == 0) {
      // 只回填数据库本就保存了发起人/上传者的对象；没有依据的历史创建人继续留空。
      for (String table :
          new String[] {
            "storage_asset",
            "api_key",
            "api_subscription",
            "api_call",
            "recharge_order",
            "purchase_order",
            "support_conversation",
            "support_feedback"
          }) {
        String creator =
            table.equals("storage_asset")
                ? "s.created_by"
                : table.startsWith("support_")
                    ? "COALESCE(s.user_id,CONCAT('GUEST:',s.visitor_id))"
                    : "s.user_id";
        db.update(
            "INSERT INTO object_origin(object_type,object_id,created_at,created_by) SELECT"
                + " ?,s.id,s.created_at,"
                + creator
                + " FROM "
                + table
                + " s WHERE NOT EXISTS (SELECT 1 FROM object_origin o WHERE o.object_type=? AND"
                + " o.object_id=s.id)",
            table,
            table);
      }
      db.update(
          "INSERT INTO object_origin(object_type,object_id,created_at,created_by) SELECT"
              + " 'catalog_product',p.id,p.created_at,a.actor_id FROM catalog_product p JOIN"
              + " catalog_audit a ON a.entity_id=p.id AND a.action_name='CREATE' WHERE NOT EXISTS"
              + " (SELECT 1 FROM object_origin o WHERE o.object_type='catalog_product' AND"
              + " o.object_id=p.id)");
      db.update(
          "INSERT INTO schema_migration VALUES('V010_OBJECT_ORIGIN','审计来源IP与有依据的对象创建来源',?)",
          System.currentTimeMillis());
    }
  }
}
