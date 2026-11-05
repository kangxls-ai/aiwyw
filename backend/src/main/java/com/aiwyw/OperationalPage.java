package com.aiwyw;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** 新模块统一服务端分页/日期/排序；SQL列名只来自调用方白名单，所有用户值绑定参数。 */
final class OperationalPage {
  static Map<String, Object> query(
      JdbcTemplate db,
      String source,
      String baseWhere,
      List<Object> baseArgs,
      Map<String, String> columns,
      String searchColumn,
      Map<String, String> p) {
    int page, size;
    try {
      page = Integer.parseInt(p.getOrDefault("page", "1"));
      size = Integer.parseInt(p.getOrDefault("pageSize", "20"));
    } catch (Exception e) {
      throw new Api.Failure(400, "分页格式不正确");
    }
    if (page < 1 || page > 1_000_000 || size < 1 || size > 100)
      throw new Api.Failure(400, "分页范围不正确");
    String where = " WHERE " + baseWhere;
    List<Object> args = new ArrayList<>(baseArgs);
    String q = p.getOrDefault("q", "").trim();
    if (q.length() > 100) throw new Api.Failure(400, "查询过长");
    if (!q.isBlank()) {
      where += " AND " + searchColumn + " LIKE ? ESCAPE '!'";
      args.add("%" + q.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%");
    }
    if (!p.getOrDefault("from", "").isBlank() || !p.getOrDefault("until", "").isBlank()) {
      var w = OperationsStats.window(p.get("from"), p.get("until"));
      where += " AND " + columns.get("created_at") + ">=? AND " + columns.get("created_at") + "<?";
      args.add(w.start());
      args.add(w.end());
    }
    String order =
        ListOrder.sql(
            p.getOrDefault("sort", "created_at"),
            p.getOrDefault("direction", "descending"),
            columns,
            "created_at",
            columns.get("id"));
    long total =
        db.queryForObject(
            "SELECT COUNT(*) FROM (" + source + ") records" + where, Long.class, args.toArray());
    int actual = (int) Math.min(page, Math.max(1, (total + size - 1) / size));
    args.add(size);
    args.add((actual - 1) * size);
    return Map.of(
        "items",
        Commerce.safeRows(
            db.queryForList(
                "SELECT * FROM ("
                    + source
                    + ") records"
                    + where
                    + " ORDER BY "
                    + order
                    + " LIMIT ? OFFSET ?",
                args.toArray())),
        "total",
        total,
        "page",
        actual);
  }
}
