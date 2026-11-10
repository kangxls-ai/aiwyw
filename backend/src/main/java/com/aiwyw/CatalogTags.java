package com.aiwyw;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

/** 标签是独立维护实体；改名只更新草稿，绝不重写历史发布或悄悄重新发布。 */
@RestController
@RequestMapping("/api/admin/catalog/tags")
public class CatalogTags {
  private final JdbcTemplate db;
  private final Catalog catalog;
  private final Auth auth;
  private final TransactionTemplate tx;

  public CatalogTags(
      JdbcTemplate db, Catalog catalog, Auth auth, PlatformTransactionManager manager) {
    this.db = db;
    this.catalog = catalog;
    this.auth = auth;
    tx = new TransactionTemplate(manager);
  }

  static void schema(JdbcTemplate db) {
    db.execute(
        """
        CREATE TABLE IF NOT EXISTS catalog_tag (
          id VARCHAR(36) PRIMARY KEY COMMENT '稳定标签UUID',
          name VARCHAR(80) NOT NULL UNIQUE COMMENT '标签名称，与当前草稿标签匹配',
          description VARCHAR(500) NOT NULL COMMENT '后台运营备注，不拼入公开HTML',
          revision BIGINT NOT NULL COMMENT '乐观锁版本',
          created_at BIGINT NOT NULL COMMENT '创建UTC毫秒'
        ) COMMENT='独立标签目录；删除目录项不改写历史发布快照'
        """);
    for (String tag : db.queryForList("SELECT DISTINCT tag FROM catalog_product_tag", String.class))
      ensure(db, List.of(tag));
  }

  /** 与保存商品共用目录锁/事务，新录入标签自动建目录，兼容升级前的自由标签。 */
  static void ensure(JdbcTemplate db, List<String> names) {
    ensure(db, names, null);
  }

  static void ensure(JdbcTemplate db, List<String> names, String actor) {
    for (String name : names)
      if (db.queryForObject("SELECT COUNT(*) FROM catalog_tag WHERE name=?", Integer.class, name)
          == 0) {
        String id = Ids.next();
        db.update(
            "INSERT INTO catalog_tag(id,name,description,revision,created_at) VALUES(?,?,'',1,?)",
            id,
            name,
            System.currentTimeMillis());
        if (actor != null) Origins.created(db, "catalog_tag", id, actor);
      }
  }

  public record Input(String name, String description, long revision) {}

  public Map<String, Object> list(String q, int page, int pageSize, HttpServletRequest r) {
    return list(q, new org.springframework.util.LinkedMultiValueMap<>(), page, pageSize, r);
  }

  @GetMapping
  public Map<String, Object> list(
      @RequestParam(defaultValue = "") String q,
      @RequestParam org.springframework.util.MultiValueMap<String, String> parameters,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int pageSize,
      HttpServletRequest r) {
    auth.editor(r);
    if (q.length() > 80 || page < 1 || pageSize < 1 || pageSize > 100)
      throw new Api.Failure(400, "查询参数不正确");
    String term = "%" + q.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    long total =
        db.queryForObject(
            "SELECT COUNT(*) FROM catalog_tag WHERE name LIKE ? ESCAPE '!'", Long.class, term);
    int actual = (int) Math.min(page, Math.max(1, (total + pageSize - 1) / pageSize));
    var items =
        db.queryForList(
            "SELECT t.*,(SELECT COUNT(*) FROM catalog_product_tag p WHERE p.tag=t.name) AS"
                + " applications FROM catalog_tag t WHERE t.name LIKE ? ESCAPE '!' ORDER BY "
                + ListOrder.sql(
                    parameters.getFirst("sort"),
                    parameters.getFirst("direction") == null
                        ? "descending"
                        : parameters.getFirst("direction"),
                    Map.of(
                        "created_at",
                        "t.created_at",
                        "name",
                        "t.name",
                        "description",
                        "t.description",
                        "applications",
                        "applications"),
                    "created_at",
                    "t.id")
                + " LIMIT ? OFFSET ?",
            term,
            pageSize,
            (actual - 1) * pageSize);
    return Map.of("items", Commerce.safeRows(items), "total", total, "page", actual);
  }

  /** 编辑应用和栏目需要标签名称，候选接口不授予标签维护权限。 */
  @GetMapping("/options/page")
  Map<String, Object> options(@RequestParam Map<String, String> filters, HttpServletRequest r) {
    var user = auth.staff(r);
    if (Collections.disjoint(user.menus(), Set.of("applications", "sections", "tags")))
      throw new Api.Failure(403, "没有标签选择权限");
    return OperationalPage.query(
        db,
        "SELECT id,name,created_at FROM catalog_tag",
        "1=1",
        List.of(),
        Map.of("id", "id", "created_at", "created_at"),
        "name",
        filters);
  }

  @PostMapping
  public Map<String, String> create(@RequestBody Input input, HttpServletRequest r) {
    return save(null, input, r);
  }

  @PutMapping("/{id}")
  public Map<String, String> save(
      @PathVariable String id, @RequestBody Input input, HttpServletRequest r) {
    String actor = auth.editor(r).id();
    String name = input.name() == null ? "" : input.name().trim(),
        description = input.description() == null ? "" : input.description().trim();
    if (name.isEmpty()
        || name.length() > 80
        || name.chars().anyMatch(c -> c < 32)
        || description.length() > 500) throw new Api.Failure(400, "请填写1—80字标签名，备注最多500字");
    return tx.execute(
        status -> {
          db.queryForObject("SELECT revision FROM catalog_guard WHERE id=1 FOR UPDATE", Long.class);
          String key = id == null ? Ids.next() : id;
          var collision =
              db.queryForList(
                  "SELECT id FROM catalog_tag WHERE name=? AND id<>?", String.class, name, key);
          if (!collision.isEmpty()) throw new Api.Failure(409, "标签名称已存在");
          if (id == null)
            db.update(
                "INSERT INTO catalog_tag(id,name,description,revision,created_at)"
                    + " VALUES(?,?,?,1,?)",
                key,
                name,
                description,
                System.currentTimeMillis());
          else {
            var rows = db.queryForList("SELECT * FROM catalog_tag WHERE id=?", id);
            if (rows.isEmpty()) throw new Api.Failure(404, "标签不存在");
            var old = rows.get(0);
            if (((Number) old.get("revision")).longValue() != input.revision())
              throw new Api.Failure(409, "标签已变化，请重新读取");
            String previous = (String) old.get("name");
            if (!previous.equals(name)) {
              if (catalog.sections().stream().anyMatch(s -> s.config().tags().contains(previous)))
                throw new Api.Failure(409, "栏目规则正在引用此标签，请先调整栏目规则再改名");
              db.update("UPDATE catalog_tag SET name=? WHERE id=?", name, id);
              for (var product : catalog.drafts())
                if (product.content().tags().contains(previous)) {
                  var p = product.content();
                  catalog.saveProduct(
                      product.id(),
                      new Catalog.ProductInput(
                          p.slug(),
                          p.nameZh(),
                          p.nameEn(),
                          p.summaryZh(),
                          p.summaryEn(),
                          p.bodyZh(),
                          p.bodyEn(),
                          p.tags().stream()
                              .map(t -> t.equals(previous) ? name : t)
                              .distinct()
                              .toList(),
                          p.iconUrl(),
                          p.screenshots(),
                          p.type(),
                          p.pricePoints(),
                          p.version(),
                          p.deliveryMode(),
                          p.downloadUrl(),
                          product.revision()),
                      actor);
                }
            }
            db.update(
                "UPDATE catalog_tag SET name=?,description=?,revision=revision+1 WHERE id=?",
                name,
                description,
                id);
          }
          if (id == null) Origins.created(db, "catalog_tag", key, actor);
          AuditLog.write(db, "catalog_audit", actor, key, "SAVE_TAG", "tag metadata");
          return Map.of("id", key);
        });
  }

  @DeleteMapping("/{id}")
  public Map<String, Boolean> delete(
      @PathVariable String id, @RequestParam long revision, HttpServletRequest r) {
    String actor = auth.editor(r).id();
    tx.executeWithoutResult(
        status -> {
          db.queryForObject("SELECT revision FROM catalog_guard WHERE id=1 FOR UPDATE", Long.class);
          var rows = db.queryForList("SELECT * FROM catalog_tag WHERE id=?", id);
          if (rows.isEmpty()) throw new Api.Failure(404, "标签不存在");
          var tag = rows.get(0);
          String name = (String) tag.get("name");
          if (db.queryForObject(
                      "SELECT COUNT(*) FROM catalog_product_tag WHERE tag=?", Integer.class, name)
                  > 0
              || catalog.sections().stream().anyMatch(s -> s.config().tags().contains(name)))
            throw new Api.Failure(409, "标签仍被应用草稿或栏目引用，不能删除");
          if (db.update("DELETE FROM catalog_tag WHERE id=? AND revision=?", id, revision) != 1)
            throw new Api.Failure(409, "标签已变化，请重新读取");
          AuditLog.write(db, "catalog_audit", actor, id, "DELETE_TAG", "tag metadata");
        });
    return Map.of("success", true);
  }
}
