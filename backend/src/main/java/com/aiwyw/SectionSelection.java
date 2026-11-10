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
    return """
        SELECT id AS ref,id AS raw_id,'PRODUCT' AS kind,sort_type AS category,sort_name AS name,
          slug,status,created_at,last_published_at AS published_at FROM catalog_product
        UNION ALL SELECT CONCAT('api:',id),id,'API','API',name,slug,
          CASE WHEN enabled=TRUE THEN 'PUBLISHED' ELSE 'DISABLED' END,created_at,NULL FROM api_definition
        UNION ALL SELECT CONCAT('content:',d.id),d.id,'CONTENT',d.kind,d.title_zh,d.slug,d.state,
          d.created_at,p.created_at FROM content_document d LEFT JOIN content_publication p
            ON p.id=d.active_publication
        """;
  }

  static String sort(String key, String direction, Map<String, String> allowed) {
    String column = allowed.get(key);
    if (column == null || !Set.of("asc", "desc").contains(direction))
      throw new Api.Failure(400, "排序参数不正确");
    return column + (direction.equals("asc") ? " ASC" : " DESC");
  }

  static String term(String q) {
    if (q == null || q.length() > 100) throw new Api.Failure(400, "关键词最多100字");
    return "%" + q.trim().replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
  }

  static int pageNumber(int page, int size, long total) {
    if (page < 1 || page > 1000000 || size < 1 || size > 100)
      throw new Api.Failure(400, "分页参数不正确");
    return (int) Math.min(page, Math.max(1, (total + size - 1) / size));
  }

  static void valid(List<String> refs) {
    if (refs == null || refs.size() > 300 || new HashSet<>(refs).size() != refs.size())
      throw new Api.Failure(400, "选品最多300项且不能重复");
    for (String id : refs)
      if (id == null || !id.matches("(?:(?:api|content):)?" + Ids.REGEX))
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
    return page(q, kind, page, size, true, "created_at", "desc");
  }

  Map<String, Object> page(
      String q, String kind, int page, int size, boolean allowApi, String sortBy, String direction) {
    var groups = Set.of("ALL", "PRODUCT", "API", "CONTENT");
    if (!(groups.contains(kind) || Set.of("TOOL", "SOFTWARE", "RESOURCE", "DOCUMENT",
        "ARTICLE", "TUTORIAL", "FAQ", "CASE").contains(kind)) || !allowApi && kind.equals("API"))
      throw new Api.Failure(400, "选品分类不正确");
    String order = sort(sortBy, direction, Map.of("name", "name", "slug", "slug",
        "category", "category", "status", "status", "created_at", "created_at", "published_at", "published_at"));
    String term = term(q);
    String where =
        " WHERE (name LIKE ? ESCAPE '!' OR slug LIKE ? ESCAPE '!' OR raw_id LIKE ? ESCAPE '!')"
            + (kind.equals("ALL") ? "" : groups.contains(kind) ? " AND kind=?" : " AND category=?")
            + (allowApi ? "" : " AND kind<>'API'");
    List<Object> args = new ArrayList<>(List.of(term, term, term));
    if (!kind.equals("ALL")) args.add(kind);
    long total =
        db.queryForObject(
            "SELECT COUNT(*) FROM (" + source() + ") s" + where, Long.class, args.toArray());
    int actual = pageNumber(page, size, total);
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
                    + " ORDER BY " + order + ",ref LIMIT ? OFFSET ?",
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
        var item = apiItem(row);
        items.put((String) item.get("ref"), item);
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
        var item = articleItem(row);
        items.put((String) item.get("ref"), item);
      }
    return refs.stream().filter(items::containsKey).map(items::get).toList();
  }

  static Map<String, Object> apiItem(Map<String, Object> row) {
    return Map.of(
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
        "/api-services/" + row.get("slug"),
        "slug",
        row.get("slug"),
        "priceUnits",
        row.get("price_units"));
  }

  static Map<String, Object> articleItem(Map<String, Object> row) {
    return Map.of(
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
        "/articles/" + row.get("slug"));
  }

  /** 只统计当前返回页中已有的内容浏览记录，保留运营统计原有的去重与保留期。 */
  void addViews(List<Map<String, Object>> items) {
    var paths =
        items.stream()
            .filter(row -> !row.containsKey("product"))
            .map(row -> (String) row.get("href"))
            .distinct()
            .toList();
    if (paths.isEmpty()) return;
    var counts = new HashMap<String, Long>();
    db.query(
        "SELECT path,COUNT(*) AS views FROM analytics_event WHERE kind='PAGE' AND path IN ("
            + marks(paths.size())
            + ") GROUP BY path",
        r -> {
          counts.put(r.getString("path"), r.getLong("views"));
        },
        paths.toArray());
    for (var item : items)
      if (counts.containsKey(item.get("href"))) item.put("views", counts.get(item.get("href")));
  }
}

@RestController
class SectionSelectionController {
  final SectionSelection service;
  final Catalog catalog;
  final Auth auth;
  final ContentService content;
  final Series series;

  SectionSelectionController(
      SectionSelection service, Catalog catalog, Auth auth, ContentService content, Series series) {
    this.service = service;
    this.catalog = catalog;
    this.auth = auth;
    this.content = content;
    this.series = series;
  }

  @GetMapping("/api/admin/catalog/selection")
  Map<String, Object> page(
      @RequestParam(defaultValue = "") String q,
      @RequestParam(defaultValue = "ALL") String kind,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int pageSize,
      @RequestParam(defaultValue = "created_at") String sort,
      @RequestParam(defaultValue = "desc") String direction,
      HttpServletRequest r) {
    auth.permission(r, "sections");
    return service.page(q, kind, page, pageSize, true, sort, direction);
  }

  record Lookup(List<String> refs) {}

  @PostMapping("/api/admin/catalog/selection/lookup")
  List<Map<String, Object>> lookup(@RequestBody Lookup b, HttpServletRequest r) {
    auth.permission(r, "sections");
    return service.lookup(b.refs());
  }

  private static final Set<String> ARTICLE_TYPES = Set.of("ARTICLE", "TUTORIAL", "FAQ", "CASE");
  private static final Set<String> PRODUCT_TYPES =
      Set.of("TOOL", "SOFTWARE", "RESOURCE", "DOCUMENT", "API");

  @GetMapping("/api/public/home-sections")
  List<Map<String, Object>> home() {
    return catalog.sections().stream()
        .filter(s -> s.config().enabled())
        .map(
            s -> {
              var c = s.config();
              var items = c.mode().equals("SERIES")
                  ? series.homeItems()
                  : browse(s, "", "", List.of(), "", "", 1, Math.min(40, c.limit()), false).get("items");
              return Map.<String, Object>of(
                  "id",
                  s.id(),
                  "titleZh",
                  c.titleZh(),
                  "titleEn",
                  c.titleEn(),
                  "subtitleZh",
                  c.subtitleZh(),
                  "subtitleEn",
                  c.subtitleEn(),
                  "icon",
                  c.icon(),
                  "kind",
                  c.mode(),
                  "items",
                  items);
            })
        .toList();
  }

  @GetMapping("/api/public/sections/{id}/items")
  Map<String, Object> items(
      @PathVariable String id,
      @RequestParam org.springframework.util.MultiValueMap<String, String> filters) {
    return browseRequest(section(id), "", filters);
  }

  @GetMapping("/api/public/directory/{kind}")
  Map<String, Object> directory(
      @PathVariable String kind,
      @RequestParam org.springframework.util.MultiValueMap<String, String> filters) {
    if (!Set.of("applications", "articles", "apis").contains(kind))
      throw new Api.Failure(404, "目录不存在");
    return browseRequest(null, kind, filters);
  }

  private Catalog.Section section(String id) {
    return catalog.sections().stream()
        .filter(s -> s.id().equals(id) && s.config().enabled())
        .findFirst()
        .orElseThrow(() -> new Api.Failure(404, "栏目不存在或已停用"));
  }

  @GetMapping("/api/public/sections/{id}/items/tags")
  Map<String, Object> sectionTags(
      @PathVariable String id, @RequestParam Map<String, String> params) {
    return tagPage(section(id), params);
  }

  @GetMapping("/api/public/directory/applications/tags")
  Map<String, Object> applicationTags(@RequestParam Map<String, String> params) {
    return tagPage(null, params);
  }

  private Map<String, Object> tagPage(Catalog.Section section, Map<String, String> params) {
    int page, size;
    try {
      page = Integer.parseInt(params.getOrDefault("page", "1"));
      size = Integer.parseInt(params.getOrDefault("pageSize", "20"));
    } catch (NumberFormatException invalid) {
      throw new Api.Failure(400, "标签分页参数不正确");
    }
    String q = params.getOrDefault("q", "").trim().toLowerCase(Locale.ROOT);
    if (page < 1 || page > 1000000 || size < 1 || size > 100 || q.length() > 80)
      throw new Api.Failure(400, "标签分页参数不正确");
    var tags =
        tags(productItems(section)).stream()
            .filter(tag -> tag.toLowerCase(Locale.ROOT).contains(q))
            .toList();
    int actual = Math.min(page, Math.max(1, (tags.size() + size - 1) / size));
    int offset = (actual - 1) * size;
    return Map.of(
        "items",
        tags.subList(offset, Math.min(offset + size, tags.size())).stream()
            .map(tag -> Map.of("name", tag))
            .toList(),
        "total",
        tags.size(),
        "page",
        actual);
  }

  private Map<String, Object> browseRequest(
      Catalog.Section section,
      String directory,
      org.springframework.util.MultiValueMap<String, String> filters) {
    var params = filters.toSingleValueMap();
    String q = params.getOrDefault("q", "").trim(),
        type = params.getOrDefault("type", ""),
        price = params.getOrDefault("price", "");
    List<String> tags =
        filters.getOrDefault("tag", List.of()).stream()
            .filter(tag -> !tag.isBlank())
            .distinct()
            .toList();
    int page, size;
    try {
      page = Integer.parseInt(params.getOrDefault("page", "1"));
      size = Integer.parseInt(params.getOrDefault("pageSize", "100"));
    } catch (NumberFormatException invalid) {
      throw new Api.Failure(400, "栏目分页范围不正确");
    }
    if (page < 1
        || page > 1000000
        || size < 1
        || size > 200
        || q.length() > 100
        || tags.size() > 20
        || tags.stream().anyMatch(tag -> tag.length() > 80)
        || (!price.isBlank() && !Set.of("FREE", "PAID").contains(price))
        || (!type.isBlank() && !PRODUCT_TYPES.contains(type) && !ARTICLE_TYPES.contains(type)))
      throw new Api.Failure(400, "栏目筛选参数不正确");
    return browse(
        section,
        directory,
        q,
        tags,
        type,
        price,
        page,
        size,
        !"false".equals(params.get("includeTags")));
  }

  private Map<String, Object> browse(
      Catalog.Section section,
      String directory,
      String q,
      List<String> selectedTags,
      String type,
      String price,
      int page,
      int size,
      boolean includeTags) {
    String mode = section == null ? directory : section.config().mode();
    if (Set.of("ARTICLES", "articles").contains(mode)) {
      if (!type.isBlank() && !ARTICLE_TYPES.contains(type))
        return result(List.of(), 0, 1, List.of());
      var data = content.list(false, Set.of(), q, type, page, size, "created_at", "descending");
      var rows = (List<Map<String, Object>>) data.get("items");
      return result(
          rows.stream().map(SectionSelection::articleItem).toList(),
          ((Number) data.get("total")).longValue(),
          ((Number) data.get("page")).intValue(),
          List.of());
    }
    if (Set.of("API", "apis").contains(mode)) return apiPage(q, price, page, size);
    var all = productItems(section);
    var tags = includeTags ? tags(all) : List.<String>of();
    String term = q.toLowerCase(Locale.ROOT);
    var matches =
        all.stream().filter(row -> matches(row, term, selectedTags, type, price)).toList();
    int actual = Math.min(page, Math.max(1, (matches.size() + size - 1) / size));
    int offset = (actual - 1) * size;
    return result(
        matches.subList(offset, Math.min(offset + size, matches.size())).stream()
            .map(SectionSelectionController::safeItem)
            .toList(),
        matches.size(),
        actual,
        tags);
  }

  private List<Map<String, Object>> productItems(Catalog.Section section) {
    if (section != null && section.config().mode().equals("MANUAL"))
      return service.publicItems(section.config().productIds());
    // 自动选品沿用发布快照和既有排序，只发送当前页的短字段。
    var data = catalog.publicData();
    List<Catalog.Product> products =
        section == null
            ? data.products().stream()
                .filter(p -> p.status().equals("PUBLISHED"))
                .sorted(
                    Comparator.comparingLong(Catalog.Product::firstPublishedAt)
                        .reversed()
                        .thenComparing(Catalog.Product::id))
                .toList()
            : data.sections().stream()
                .filter(s -> s.id().equals(section.id()))
                .findFirst()
                .map(Catalog.SectionView::products)
                .orElse(List.of());
    return products.stream()
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
        .toList();
  }

  private static List<String> tags(List<Map<String, Object>> rows) {
    return rows.stream()
        .map(row -> (Catalog.Product) row.get("product"))
        .filter(Objects::nonNull)
        .flatMap(p -> p.content().tags().stream())
        .distinct()
        .sorted()
        .toList();
  }

  private static boolean matches(
      Map<String, Object> row, String term, List<String> tags, String type, String price) {
    var product = (Catalog.Product) row.get("product");
    var c = product == null ? null : product.content();
    String kind = c == null ? (String) row.get("kind") : c.type();
    if (!type.isBlank() && !type.equals(kind)) return false;
    if (!tags.isEmpty() && (c == null || !c.tags().containsAll(tags))) return false;
    var amount =
        c == null
            ? new java.math.BigDecimal(String.valueOf(row.getOrDefault("priceUnits", "0")))
            : c.pricePoints();
    if (price.equals("FREE") && amount.signum() > 0 || price.equals("PAID") && amount.signum() == 0)
      return false;
    String summary =
        c == null
            ? row.getOrDefault("summary", "") + " " + row.getOrDefault("summaryEn", "")
            : String.join(" ", c.summaryZh(), c.summaryEn(), c.slug(), String.join(" ", c.tags()));
    return (row.get("name")
            + " "
            + row.get("nameEn")
            + " "
            + summary
            + " "
            + row.getOrDefault("href", ""))
        .toLowerCase(Locale.ROOT)
        .contains(term);
  }

  private Map<String, Object> apiPage(String q, String price, int page, int size) {
    String term =
        "%"
            + q.toLowerCase(Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_")
            + "%";
    String where =
        " WHERE enabled=TRUE AND LOWER(CONCAT(name,' ',name_en,' ',slug,' ',"
            + "description,' ',description_en)) LIKE ? ESCAPE '!'";
    if (price.equals("FREE")) where += " AND price_units=0";
    if (price.equals("PAID")) where += " AND price_units>0";
    long total =
        service.db.queryForObject("SELECT COUNT(*) FROM api_definition" + where, Long.class, term);
    int actual = (int) Math.min(page, Math.max(1, (total + size - 1) / size));
    var rows =
        Commerce.safeRows(
            service.db.queryForList(
                "SELECT id,slug,name,name_en,description,description_en,price_units FROM"
                    + " api_definition"
                    + where
                    + " ORDER BY created_at DESC,id LIMIT ? OFFSET ?",
                term,
                size,
                (actual - 1) * size));
    return result(rows.stream().map(SectionSelection::apiItem).toList(), total, actual, List.of());
  }

  private Map<String, Object> result(
      List<Map<String, Object>> rows, long total, int page, List<String> tags) {
    var items = rows.stream().map(HashMap::new).map(row -> (Map<String, Object>) row).toList();
    service.addViews(items);
    return Map.of("items", items, "total", total, "page", page, "tags", tags);
  }

  private static Map<String, Object> safeItem(Map<String, Object> row) {
    if (!(row.get("product") instanceof Catalog.Product p)) return row;
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
    var result = new HashMap<String, Object>(row);
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
  }
}
