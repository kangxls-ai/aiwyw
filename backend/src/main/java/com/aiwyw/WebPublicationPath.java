package com.aiwyw;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** 北京时间归档路径写入发布记录。旧发布从原始发布时间回填，同一版本在重建时定位一致。 */
final class WebPublicationPath {
  private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("uuuu/MM/dd")
      .withZone(ZoneId.of("Asia/Shanghai"));
  private static final String DIRECTORY = "[0-9]{4}/[0-9]{2}/[0-9]{2}/" + Ids.REGEX;

  private WebPublicationPath() {}

  static String directory(long time, String id) {
    if (id == null || !id.matches(Ids.REGEX)) throw new IllegalStateException("Invalid publication ID");
    return DATE.format(Instant.ofEpochMilli(time)) + "/" + id;
  }

  static String archive(String category, String directory, String filename) {
    if (directory == null || !directory.matches(DIRECTORY))
      throw new IllegalStateException("Invalid public archive directory");
    return "pages/" + category + "/" + directory + "/" + filename;
  }

  static String route(String category, String slug, String suffix) {
    if (!slug.matches("[a-z0-9]+(?:-[a-z0-9]+)*")) throw new IllegalStateException("Invalid public slug");
    return "routes/" + category + "/" + slug.substring(0, Math.min(2, slug.length())) + "/" + slug + suffix;
  }

  static void migrate(DataSource source, JdbcTemplate db) {
    for (String table : List.of("catalog_publication", "content_publication")) {
      if (!CatalogQuerySchema.column(source, table, "web_directory"))
        try {
          db.execute("ALTER TABLE " + table
              + " ADD COLUMN web_directory VARCHAR(64) COMMENT 'Nginx公开静态文件目录：北京时间年月日与发布ID'");
        } catch (org.springframework.dao.DataAccessException race) {
          if (!CatalogQuerySchema.column(source, table, "web_directory")) throw race;
        }
      // 分批回填旧记录；中断后可从剩余空值继续，保留文件哈希和原有地址。
      for (;;) {
        var rows = db.queryForList("SELECT id,created_at FROM " + table + " WHERE web_directory IS NULL LIMIT 200");
        if (rows.isEmpty()) break;
        for (var row : rows) {
          String id = (String) row.get("id");
          db.update("UPDATE " + table + " SET web_directory=? WHERE id=? AND web_directory IS NULL",
              directory(((Number) row.get("created_at")).longValue(), id), id);
        }
      }
    }
  }
}
