package com.aiwyw;

import java.util.*;
import java.util.function.BooleanSupplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** 每个列表独立置顶。沿用栏目写锁分配严格递增的次序，跨请求同一毫秒也不会打乱。 */
@Service
class EditorialPins {
  static final String ALL_APPLICATIONS = "all-applications";
  static final String ORDER = "CASE WHEN ep.pin_order IS NULL THEN 1 ELSE 0 END,ep.pin_order DESC,";
  private final JdbcTemplate db;
  private final TransactionTemplate tx;

  EditorialPins(JdbcTemplate db, PlatformTransactionManager manager) {
    this.db = db;
    this.tx = new TransactionTemplate(manager);
  }

  static void schema(JdbcTemplate db) {
    db.execute("""
        CREATE TABLE IF NOT EXISTS editorial_pin (
          scope_key VARCHAR(96) NOT NULL COMMENT '栏目、独立目录或专栏的置顶范围',
          target_ref VARCHAR(96) NOT NULL COMMENT '使用稳定对象编号的选品引用',
          pin_order BIGINT NOT NULL COMMENT '置顶操作次序，越新越靠前',
          PRIMARY KEY (scope_key,target_ref)
        ) COMMENT='各公开目录独立置顶，未发布条目不参与展示'
        """);
  }

  record Change(String ref, boolean pinned) {}

  static String scope(Catalog.Section section, String directory) {
    return section == null ? "directory:" + directory : "section:" + section.id();
  }

  static String join(String referenceExpression) {
    return " LEFT JOIN editorial_pin ep ON ep.scope_key=? AND ep.target_ref=" + referenceExpression;
  }

  void change(String scope, Change input, String actor, BooleanSupplier eligible) {
    if (input == null || input.ref() == null || !input.ref().matches("(?:(?:api|content|series):)?" + Ids.REGEX))
      throw new Api.Failure(400, "置顶条目标识不正确");
    tx.executeWithoutResult(status -> {
      long sequence = db.queryForObject("SELECT revision FROM catalog_guard WHERE id=1 FOR UPDATE", Long.class);
      if (input.pinned() && !eligible.getAsBoolean())
        throw new Api.Failure(409, "该条目已移出栏目或下架，请刷新列表");
      db.update("DELETE FROM editorial_pin WHERE scope_key=? AND target_ref=?", scope, input.ref());
      if (input.pinned()) {
        db.update("UPDATE catalog_guard SET revision=revision+1 WHERE id=1");
        db.update("INSERT INTO editorial_pin(scope_key,target_ref,pin_order) VALUES(?,?,?)",
            scope, input.ref(), sequence + 1);
      }
      AuditLog.write(db, "catalog_audit", actor, input.ref(),
          input.pinned() ? "PIN_CONTENT" : "UNPIN_CONTENT", scope);
    });
  }

  private Map<String, Long> ranks(String scope) {
    var ranks = new HashMap<String, Long>();
    db.query("SELECT target_ref,pin_order FROM editorial_pin WHERE scope_key=?",
        row -> { ranks.put(row.getString("target_ref"), row.getLong("pin_order")); }, scope);
    return ranks;
  }

  /** 保持未置顶条目的原有顺序；在分页、首页截取前执行。 */
  List<Map<String, Object>> order(String scope, List<Map<String, Object>> items) {
    var ranks = ranks(scope);
    return ranks.isEmpty() ? items : items.stream().sorted(Comparator.comparingLong(
        (Map<String, Object> row) -> ranks.getOrDefault(row.get("ref"), 0L)).reversed()).toList();
  }

  Map<String, Object> management(String scope, List<Map<String, Object>> items, long total, int page) {
    var ranks = ranks(scope);
    return Map.of("items", items.stream().map(row -> Map.of(
        "ref", row.get("ref"), "name", row.get("name"), "kind", row.get("kind"),
        "pinned", ranks.containsKey(row.get("ref")))).toList(), "total", total, "page", page);
  }

  static void clear(JdbcTemplate db, String scope) {
    db.update("DELETE FROM editorial_pin WHERE scope_key=?", scope);
  }
}
