package com.aiwyw;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 为真实分页增加可查询索引；保留原草稿JSON与不可变快照，不重写历史版本。 */
public final class CatalogQuerySchema {
  private CatalogQuerySchema() {}

  static boolean column(DataSource source, String table, String name) {
    try (var c = source.getConnection();
        var rows = c.getMetaData().getColumns(c.getCatalog(), null, null, null)) {
      while (rows.next())
        if (table.equalsIgnoreCase(rows.getString("TABLE_NAME"))
            && name.equalsIgnoreCase(rows.getString("COLUMN_NAME"))) return true;
      return false;
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  public static void migrate(DataSource source, JdbcTemplate db) {
    for (String[] field :
        new String[][] {
          {"created_at", "BIGINT NOT NULL DEFAULT 0 COMMENT '创建UTC毫秒；旧资料无审计时取升级前最后已知时间'"},
          {"sort_type", "VARCHAR(20) COMMENT '草稿应用类型，供跨类型选品筛选'"},
          {"sort_name", "VARCHAR(200) NOT NULL DEFAULT '' COMMENT '草稿中文名称排序索引'"},
          {"sort_version", "VARCHAR(80) NOT NULL DEFAULT '' COMMENT '草稿版本文本排序索引'"},
          {"sort_price", "DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '草稿鼓励值价格排序索引'"},
          {"sort_tags", "VARCHAR(1800) NOT NULL DEFAULT '' COMMENT '草稿标签显示文本排序索引'"},
          {"last_published_at", "BIGINT COMMENT '最近一次成功发布UTC毫秒；首次上架排序仍用first_published_at'"},
          {"search_text", "VARCHAR(1600) NOT NULL DEFAULT '' COMMENT '草稿名称和摘要的小写查询索引，不用于公开正文'"}
        }) {
      if (!column(source, "catalog_product", field[0]))
        try {
          db.execute("ALTER TABLE catalog_product ADD COLUMN " + field[0] + " " + field[1]);
        } catch (org.springframework.dao.DataAccessException race) {
          if (!column(source, "catalog_product", field[0])) throw race;
        }
    }
    // 旧库按批回填新增类型索引，避免读出全部商品草稿或重写历史快照。
    var drafts = new ObjectMapper();
    for (;;) {
      var batch = db.queryForList("SELECT id,draft_json FROM catalog_product WHERE sort_type IS NULL LIMIT 200");
      if (batch.isEmpty()) break;
      for (var row : batch)
        try {
          var p = drafts.readValue((String) row.get("draft_json"), Catalog.ProductInput.class);
          db.update("UPDATE catalog_product SET sort_type=? WHERE id=? AND sort_type IS NULL",
              Objects.toString(p.type(), ""), row.get("id"));
        } catch (com.fasterxml.jackson.core.JsonProcessingException badData) {
          throw new IllegalStateException("Cannot read catalog draft type", badData);
        }
    }
    db.execute(
        """
        CREATE TABLE IF NOT EXISTS catalog_product_tag (
          product_id VARCHAR(36) NOT NULL COMMENT '所属商品UUID',
          tag VARCHAR(80) NOT NULL COMMENT '当前草稿标签，用于后台筛选',
          PRIMARY KEY(product_id,tag),
          FOREIGN KEY(product_id) REFERENCES catalog_product(id)
        ) COMMENT='草稿标签查询索引；前台仍以发布快照标签为准'
        """);
    new TransactionTemplate(new DataSourceTransactionManager(source))
        .executeWithoutResult(
            status -> {
              db.queryForObject(
                  "SELECT revision FROM catalog_guard WHERE id=1 FOR UPDATE", Long.class);
              if (db.queryForObject(
                      "SELECT COUNT(*) FROM schema_migration WHERE version='V005_LIST_ORDER'",
                      Integer.class)
                  > 0) return;
              db.update(
                  "UPDATE catalog_product SET created_at=COALESCE((SELECT MIN(a.created_at) FROM"
                      + " catalog_audit a WHERE a.entity_id=catalog_product.id AND"
                      + " a.action_name='CREATE'),updated_at) WHERE created_at=0");
              ObjectMapper json = new ObjectMapper();
              for (var row :
                  db.queryForList("SELECT id,draft_json,active_publication FROM catalog_product"))
                try {
                  var p =
                      json.readValue((String) row.get("draft_json"), Catalog.ProductInput.class);
                  index(db, (String) row.get("id"), p);
                  if (row.get("active_publication") != null)
                    db.update(
                        "UPDATE catalog_product SET last_published_at=(SELECT created_at FROM"
                            + " catalog_publication WHERE id=?) WHERE id=?",
                        row.get("active_publication"),
                        row.get("id"));
                } catch (Exception badData) {
                  throw new IllegalStateException(
                      "Cannot migrate catalog query index; restore valid draft", badData);
                }
              db.update(
                  "INSERT INTO schema_migration(version,description,applied_at)"
                      + " VALUES('V005_LIST_ORDER',?,?)",
                  "商品分页与发布时间索引",
                  System.currentTimeMillis());
            });
  }

  static void index(JdbcTemplate db, String id, Catalog.ProductInput p) {
    String search =
        String.join(" ", p.slug(), p.nameZh(), p.nameEn(), p.summaryZh(), p.summaryEn())
            .toLowerCase(Locale.ROOT);
    db.update(
        "UPDATE catalog_product SET"
            + " search_text=?,sort_name=?,sort_type=?,sort_version=?,sort_price=?,sort_tags=?,created_at=CASE"
            + " WHEN created_at=0 THEN updated_at ELSE created_at END WHERE id=?",
        search,
        p.nameZh(),
        p.type(),
        p.version(),
        p.pricePoints(),
        String.join(",", p.tags()),
        id);
    db.update("DELETE FROM catalog_product_tag WHERE product_id=?", id);
    for (String tag : p.tags())
      db.update("INSERT INTO catalog_product_tag(product_id,tag) VALUES(?,?)", id, tag);
  }
}
