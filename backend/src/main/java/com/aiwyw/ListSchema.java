package com.aiwyw;

import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** 只增字段；无法还原历史创建时间时保留0并显示历史记录，不伪造精确日期。 */
final class ListSchema {
  static void migrate(DataSource source, JdbcTemplate db) {
    for (String table : new String[] {"home_section", "points_package"}) {
      if (!CatalogQuerySchema.column(source, table, "created_at"))
        db.execute(
            "ALTER TABLE "
                + table
                + " ADD COLUMN created_at BIGINT NOT NULL DEFAULT 0 COMMENT '创建UTC毫秒；无历史依据保留0'");
    }
    if (!CatalogQuerySchema.column(source, "purchase_order", "sort_title"))
      db.execute(
          "ALTER TABLE purchase_order ADD COLUMN sort_title VARCHAR(100) NOT NULL DEFAULT ''"
              + " COMMENT '购买时中文名称排序快照，不读取当前商品名'");
    for (var row :
        db.queryForList("SELECT id,snapshot_json FROM purchase_order WHERE sort_title=''")) {
      try {
        var product =
            new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue((String) row.get("snapshot_json"), Catalog.Product.class);
        db.update(
            "UPDATE purchase_order SET sort_title=? WHERE id=?",
            product.content().nameZh(),
            row.get("id"));
      } catch (Exception bad) {
        throw new IllegalStateException("Cannot restore purchase name index", bad);
      }
    }
    db.update(
        "UPDATE home_section SET created_at=COALESCE((SELECT MIN(a.created_at) FROM catalog_audit a"
            + " WHERE a.entity_id=home_section.id),0) WHERE created_at=0");
    db.update(
        "UPDATE points_package SET created_at=COALESCE((SELECT MIN(a.created_at) FROM finance_audit"
            + " a WHERE a.entity_id=points_package.id AND a.action_name='SAVE_PACKAGE'),0) WHERE"
            + " created_at=0");
  }
}
