package com.aiwyw;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;

/** 选品只查询短元数据，数据库先分页；引用使用固定命名空间，兼容原应用UUID，不拼接用户表名。 */
@Service
class SectionSelection {
  final JdbcTemplate db;
  final ObjectMapper json;

  SectionSelection(JdbcTemplate db, ObjectMapper json) {
    this.db = db;
    this.json = json;
  }

  static String source() {
    return "SELECT id AS ref,id AS raw_id,'PRODUCT' AS kind,sort_name AS"
        + " name,slug,status,created_at FROM catalog_product UNION ALL SELECT"
        + " CONCAT('api:',id),id,'API',name,slug,CASE WHEN enabled=TRUE THEN 'PUBLISHED'"
        + " ELSE 'DISABLED' END,created_at FROM api_definition UNION ALL SELECT"
        + " CONCAT('content:',id),id,'CONTENT',title_zh,slug,state,created_at FROM"
        + " content_document";
  }

  static void valid(List<String> refs) {
    if (refs == null || refs.size() > 300 || new HashSet<>(refs).size() != refs.size())
      throw new Api.Failure(400, "选品最多300项且不能重复");
    for (String id : refs)
      if (id == null || !id.matches("(?:(?:api|content):)?[a-f0-9-]{36}"))
        throw new Api.Failure(400, "选品标识格式不正确");
  }

  static void validate(JdbcTemplate db, List<String> refs) {
    valid(refs);
    if (refs.isEmpty()) return;
    long found =
        db.queryForObject(
            "SELECT COUNT(*) FROM (" + source() + ") s WHERE ref IN (" + marks(refs.size()) + ")",
            Long.class,
            refs.toArray());
    if (found != refs.size()) throw new Api.Failure(400, "部分已选内容已删除，请在选品列表移除后保存");
  }

  static String marks(int n) {
    return String.join(",", Collections.nCopies(n, "?"));
  }

  Map<String, Object> page(String q, String kind, int page, int size) {
    if (q == null
        || q.length() > 100
        || page < 1
        || page > 1000000
        || size < 1
        || size > 100
        || !Set.of("ALL", "PRODUCT", "API", "CONTENT").contains(kind))
      throw new Api.Failure(400, "选品查询参数不正确");
    String term = "%" + q.trim().replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    String where =
        " WHERE (name LIKE ? ESCAPE '!' OR slug LIKE ? ESCAPE '!')"
            + (kind.equals("ALL") ? "" : " AND kind=?");
    List<Object> args = new ArrayList<>(List.of(term, term));
    if (!kind.equals("ALL")) args.add(kind);
    long total =
        db.queryForObject(
            "SELECT COUNT(*) FROM (" + source() + ") s" + where, Long.class, args.toArray());
    int actual = (int) Math.min(page, Math.max(1, (total + size - 1) / size));
    args.add(size);
    args.add((actual - 1) * size);
    return Map.of(
        "items",
        Commerce.safeRows(
            db.queryForList(
                "SELECT * FROM ("
                    + source()
                    + ") s"
                    + where
                    + " ORDER BY created_at DESC,ref LIMIT ? OFFSET ?",
                args.toArray())),
        "total",
        total,
        "page",
        actual,
        "pageSize",
        size);
  }

  List<Map<String, Object>> lookup(List<String> refs) {
    valid(refs);
    if (refs.isEmpty()) return List.of();
    Map<String, Map<String, Object>> found = new HashMap<>();
    for (var row :
        Commerce.safeRows(
            db.queryForList(
                "SELECT * FROM (" + source() + ") s WHERE ref IN (" + marks(refs.size()) + ")",
                refs.toArray()))) found.put((String) row.get("ref"), row);
    return refs.stream()
        .map(
            ref ->
                found.getOrDefault(
                    ref,
                    Map.of("ref", ref, "name", "已删除内容", "kind", "MISSING", "status", "MISSING")))
        .toList();
  }

  /** 公开选品只读取当前发布快照；草稿修改不提前泄露，上下架/服务停用立即从展示中排除。 */
  List<Map<String, Object>> publicItems(List<String> refs) {
    valid(refs);
    Map<String, Map<String, Object>> items = new HashMap<>();
    List<String> products = refs.stream().filter(s -> !s.contains(":")).toList(),
        apis = refs.stream().filter(s -> s.startsWith("api:")).map(s -> s.substring(4)).toList(),
        contents =
            refs.stream().filter(s -> s.startsWith("content:")).map(s -> s.substring(8)).toList();
    Map<String, long[]> counts = new HashMap<>();
    if (!products.isEmpty())
      for (var m :
          db.queryForList(
              "SELECT product_id,metric_type,COUNT(*) AS n FROM catalog_metric WHERE product_id IN"
                  + " ("
                  + marks(products.size())
                  + ") GROUP BY product_id,metric_type",
              products.toArray())) {
        var value = counts.computeIfAbsent((String) m.get("product_id"), key -> new long[2]);
        value[m.get("metric_type").equals("VIEW") ? 0 : 1] = ((Number) m.get("n")).longValue();
      }
    if (!products.isEmpty())
      for (var row :
          db.queryForList(
              "SELECT p.snapshot_json FROM catalog_product d JOIN catalog_publication p ON"
                  + " p.id=d.active_publication WHERE d.status='PUBLISHED' AND d.id IN ("
                  + marks(products.size())
                  + ")",
              products.toArray())) {
        try {
          var p = json.readValue((String) row.get("snapshot_json"), Catalog.Product.class);
          var c = p.content();
          var safe =
              new Catalog.ProductInput(
                  c.slug(),
                  c.nameZh(),
                  c.nameEn(),
                  c.summaryZh(),
                  c.summaryEn(),
                  "",
                  "",
                  c.tags(),
                  c.iconUrl(),
                  List.of(),
                  c.type(),
                  c.pricePoints(),
                  c.version(),
                  c.deliveryMode(),
                  "",
                  c.revision());
          var metrics = counts.getOrDefault(p.id(), new long[2]);
          long views = metrics[0], downloads = metrics[1];
          items.put(
              p.id(),
              Map.of(
                  "ref",
                  p.id(),
                  "kind",
                  "PRODUCT",
                  "name",
                  c.nameZh(),
                  "nameEn",
                  c.nameEn(),
                  "href",
                  "/apps/" + p.slug(),
                  "product",
                  new Catalog.Product(
                      p.id(),
                      p.slug(),
                      safe,
                      p.revision(),
                      "PUBLISHED",
                      p.publicationId(),
                      p.firstPublishedAt(),
                      views,
                      downloads)));
        } catch (Exception invalid) {
          throw new Api.Failure(503, "已发布选品快照暂不可用");
        }
      }
    if (!apis.isEmpty())
      for (var row :
          Commerce.safeRows(
              db.queryForList(
                  "SELECT id,slug,name,name_en,description,description_en,price_units FROM"
                      + " api_definition WHERE enabled=TRUE AND id IN ("
                      + marks(apis.size())
                      + ")",
                  apis.toArray()))) {
        String ref = "api:" + row.get("id");
        items.put(
            ref,
            Map.of(
                "ref",
                ref,
                "kind",
                "API",
                "name",
                row.get("name"),
                "nameEn",
                row.get("name_en"),
                "summary",
                row.get("description"),
                "summaryEn",
                row.get("description_en"),
                "href",
                "/api-services/" + row.get("slug")));
      }
    if (!contents.isEmpty())
      for (var row :
          Commerce.safeRows(
              db.queryForList(
                  "SELECT d.id,d.slug,p.kind,p.title_zh,p.title_en,p.summary_zh,p.summary_en FROM"
                      + " content_document d JOIN content_publication p ON"
                      + " p.id=d.active_publication WHERE d.state='PUBLISHED' AND d.id IN ("
                      + marks(contents.size())
                      + ")",
                  contents.toArray()))) {
        String ref = "content:" + row.get("id");
        items.put(
            ref,
            Map.of(
                "ref",
                ref,
                "kind",
                row.get("kind"),
                "name",
                row.get("title_zh"),
                "nameEn",
                row.get("title_en"),
                "summary",
                row.get("summary_zh"),
                "summaryEn",
                row.get("summary_en"),
                "href",
                "/articles/" + row.get("slug")));
      }
    return refs.stream().filter(items::containsKey).map(items::get).toList();
  }
}

@RestController
class SectionSelectionController {
  final SectionSelection service;
  final Catalog catalog;
  final Auth auth;
  final ContentService content;
  final MeteredApi api;

  SectionSelectionController(
      SectionSelection service,
      Catalog catalog,
      Auth auth,
      ContentService content,
      MeteredApi api) {
    this.service = service;
    this.catalog = catalog;
    this.auth = auth;
    this.content = content;
    this.api = api;
  }

  @GetMapping("/api/admin/catalog/selection")
  Map<String, Object> page(
      @RequestParam(defaultValue = "") String q,
      @RequestParam(defaultValue = "ALL") String kind,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int pageSize,
      HttpServletRequest r) {
    auth.permission(r, "sections");
    return service.page(q, kind, page, pageSize);
  }

  record Lookup(List<String> refs) {}

  @PostMapping("/api/admin/catalog/selection/lookup")
  List<Map<String, Object>> lookup(@RequestBody Lookup b, HttpServletRequest r) {
    auth.permission(r, "sections");
    return service.lookup(b.refs());
  }

  @GetMapping("/api/public/sections/{id}/items")
  Map<String, Object> items(
      @PathVariable String id,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int pageSize,
      @RequestParam(defaultValue = "") String q,
      @RequestParam(defaultValue = "") String tag) {
    if (page < 1
        || page > 1000000
        || pageSize < 1
        || pageSize > 60
        || q.length() > 100
        || tag.length() > 100) throw new Api.Failure(400, "栏目分页范围不正确");
    var section =
        catalog.sections().stream()
            .filter(s -> s.id().equals(id) && s.config().enabled())
            .findFirst()
            .orElseThrow(() -> new Api.Failure(404, "栏目不存在或已停用"));
    var c = section.config();
    List<Map<String, Object>> all;
    if (c.mode().equals("MANUAL")) all = service.publicItems(c.productIds());
    else if (c.mode().equals("ARTICLES")) {
      var data = content.list(false, Set.of(), q, "", page, pageSize, "created_at", "descending");
      var list = (List<Map<String, Object>>) data.get("items");
      return Map.of(
          "items",
          list.stream()
              .map(
                  row ->
                      Map.of(
                          "ref",
                          "content:" + row.get("id"),
                          "kind",
                          row.get("kind"),
                          "name",
                          row.get("title_zh"),
                          "nameEn",
                          row.get("title_en"),
                          "summary",
                          row.get("summary_zh"),
                          "summaryEn",
                          row.get("summary_en"),
                          "href",
                          "/articles/" + row.get("slug")))
              .toList(),
          "total",
          data.get("total"),
          "page",
          data.get("page"));
    } else if (c.mode().equals("API")) {
      String term = "%" + q.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
      String where =
          " WHERE enabled=TRUE AND (name LIKE ? ESCAPE '!' OR name_en LIKE ? ESCAPE '!')";
      long total =
          service.db.queryForObject(
              "SELECT COUNT(*) FROM api_definition" + where, Long.class, term, term);
      int actual = (int) Math.min(page, Math.max(1, (total + pageSize - 1) / pageSize));
      var rows =
          Commerce.safeRows(
              service.db.queryForList(
                  "SELECT id,slug,name,name_en,description,description_en FROM api_definition"
                      + where
                      + " ORDER BY created_at DESC,id LIMIT ? OFFSET ?",
                  term,
                  term,
                  pageSize,
                  (actual - 1) * pageSize));
      return Map.of(
          "items",
          rows.stream()
              .map(
                  row ->
                      Map.<String, Object>of(
                          "ref",
                          "api:" + row.get("id"),
                          "kind",
                          "API",
                          "name",
                          row.get("name"),
                          "nameEn",
                          row.get("name_en"),
                          "summary",
                          row.get("description"),
                          "summaryEn",
                          row.get("description_en"),
                          "href",
                          "/api-services/" + row.get("slug")))
              .toList(),
          "total",
          total,
          "page",
          actual,
          "tags",
          List.of());
    } else {
      // 自动栏目可超过人工上限：仅查询当前页元数据；先在已缓存的短目录上筛选，不把富文本返回首页。
      all =
          catalog.publicData().sections().stream()
              .filter(s -> s.id().equals(id))
              .findFirst()
              .map(
                  s ->
                      s.products().stream()
                          .map(
                              p ->
                                  Map.<String, Object>of(
                                      "ref",
                                      p.id(),
                                      "kind",
                                      "PRODUCT",
                                      "name",
                                      p.content().nameZh(),
                                      "nameEn",
                                      p.content().nameEn(),
                                      "product",
                                      p))
                          .toList())
              .orElse(List.of());
    }
    var tags =
        all.stream()
            .filter(row -> row.get("product") instanceof Catalog.Product)
            .flatMap(row -> ((Catalog.Product) row.get("product")).content().tags().stream())
            .distinct()
            .sorted()
            .toList();
    String term = q.trim().toLowerCase(Locale.ROOT);
    all =
        all.stream()
            .filter(
                row -> {
                  var p = (Catalog.Product) row.get("product");
                  return (tag.isBlank() || (p != null && p.content().tags().contains(tag)))
                      && (term.isBlank()
                          || (row.get("name")
                                  + " "
                                  + row.getOrDefault("nameEn", "")
                                  + " "
                                  + row.getOrDefault("summary", "")
                                  + (p == null
                                      ? ""
                                      : p.content().summaryZh() + " " + p.content().summaryEn()))
                              .toLowerCase(Locale.ROOT)
                              .contains(term));
                })
            .toList();
    int actual = (int) Math.min(page, Math.max(1, (all.size() + pageSize - 1) / pageSize)),
        offset = (actual - 1) * pageSize;
    return Map.of(
        "items",
        all.subList(offset, Math.min(offset + pageSize, all.size())).stream()
            .map(
                row -> {
                  if (!(row.get("product") instanceof Catalog.Product p)) return row;
                  var result = new HashMap<String, Object>(row);
                  var c1 = p.content();
                  var safe =
                      new Catalog.ProductInput(
                          c1.slug(),
                          c1.nameZh(),
                          c1.nameEn(),
                          c1.summaryZh(),
                          c1.summaryEn(),
                          "",
                          "",
                          c1.tags(),
                          c1.iconUrl(),
                          List.of(),
                          c1.type(),
                          c1.pricePoints(),
                          c1.version(),
                          c1.deliveryMode(),
                          "",
                          c1.revision());
                  result.put(
                      "product",
                      new Catalog.Product(
                          p.id(),
                          p.slug(),
                          safe,
                          p.revision(),
                          p.status(),
                          p.publicationId(),
                          p.firstPublishedAt(),
                          p.views(),
                          p.downloads()));
                  return result;
                })
            .toList(),
        "total",
        all.size(),
        "page",
        actual,
        "tags",
        tags);
  }
}
