package com.aiwyw;

import jakarta.servlet.http.*;
import java.time.Duration;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** HTTP边界只做身份和参数映射；事务、六栏目约束、静态发布和统计口径统一在Catalog。 */
@RestController
public class CatalogController {
  private final Catalog catalog;
  private final Auth auth;
  private final Settings settings;
  private final CatalogAdminQuery query;
  private final OperationsStats statistics;

  public CatalogController(
      Catalog catalog,
      Auth auth,
      Settings settings,
      CatalogAdminQuery query,
      OperationsStats statistics) {
    this.catalog = catalog;
    this.auth = auth;
    this.settings = settings;
    this.query = query;
    this.statistics = statistics;
  }

  public record Revision(long revision) {}

  public record Rollback(String publicationId, long revision) {}

  @GetMapping("/api/admin/catalog/products")
  public List<Catalog.Product> products(HttpServletRequest req) {
    auth.editor(req);
    return catalog.drafts();
  }

  @GetMapping("/api/admin/catalog/products/page")
  public CatalogAdminQuery.Page page(
      @RequestParam(defaultValue = "") String q,
      @RequestParam(defaultValue = "") String status,
      @RequestParam(defaultValue = "") String from,
      @RequestParam(defaultValue = "") String until,
      @RequestParam(defaultValue = "") String section,
      @RequestParam org.springframework.util.MultiValueMap<String, String> parameters,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int pageSize,
      HttpServletRequest req) {
    auth.editor(req);
    return query.page(
        q,
        status,
        from,
        until,
        section,
        parameters.getOrDefault("tag", List.of()),
        page,
        pageSize,
        parameters.getFirst("sort"),
        parameters.getFirst("direction") == null ? "descending" : parameters.getFirst("direction"));
  }

  @GetMapping("/api/admin/catalog/products/facets")
  public Map<String, Object> facets(
      @RequestParam(defaultValue = "true") boolean includeTags, HttpServletRequest req) {
    auth.editor(req);
    return includeTags ? query.facets() : Map.of("sections", catalog.sections());
  }

  @GetMapping("/api/admin/catalog/products/options/page")
  public Map<String, Object> productOptionsPage(
      @RequestParam Map<String, String> filters, HttpServletRequest req) {
    auth.editor(req);
    return catalog.productOptions(filters);
  }

  @GetMapping("/api/admin/catalog/products/options")
  public List<Map<String, Object>> options(HttpServletRequest req) {
    auth.editor(req);
    return catalog.drafts().stream()
        .map(
            p ->
                Map.<String, Object>of(
                    "id", p.id(), "nameZh", p.content().nameZh(), "status", p.status()))
        .toList();
  }

  @GetMapping("/api/admin/catalog/products/{id}")
  public Catalog.Product draft(@PathVariable String id, HttpServletRequest req) {
    auth.editor(req);
    return catalog.draft(id);
  }

  @PostMapping("/api/admin/catalog/products")
  public Map<String, String> create(
      @RequestBody Catalog.ProductInput input, HttpServletRequest req) {
    return Map.of("id", catalog.saveProduct(null, input, auth.editor(req).id()));
  }

  @PutMapping("/api/admin/catalog/products/{id}")
  public Map<String, String> save(
      @PathVariable String id, @RequestBody Catalog.ProductInput input, HttpServletRequest req) {
    return Map.of("id", catalog.saveProduct(id, input, auth.editor(req).id()));
  }

  @PostMapping("/api/admin/catalog/products/{id}/publish")
  public Map<String, String> publish(
      @PathVariable String id, @RequestBody Revision input, HttpServletRequest req) {
    return Map.of(
        "publicationId", catalog.publish(id, input.revision(), auth.editor(req).id(), settings));
  }

  @PostMapping("/api/admin/catalog/products/{id}/unlist")
  public Map<String, Boolean> unlist(
      @PathVariable String id, @RequestBody Revision input, HttpServletRequest req) {
    catalog.unlist(id, input.revision(), auth.editor(req).id());
    return Map.of("success", true);
  }

  @GetMapping("/api/admin/catalog/products/{id}/publications")
  public List<Map<String, Object>> history(@PathVariable String id, HttpServletRequest req) {
    auth.editor(req);
    return catalog.history(id);
  }

  @PostMapping("/api/admin/catalog/products/{id}/rollback")
  public Map<String, Boolean> rollback(
      @PathVariable String id, @RequestBody Rollback input, HttpServletRequest req) {
    catalog.rollback(id, input.publicationId(), input.revision(), auth.editor(req).id());
    return Map.of("success", true);
  }

  @GetMapping("/api/admin/catalog/sections")
  public List<Catalog.Section> sections(HttpServletRequest req) {
    auth.editor(req);
    return catalog.sections();
  }

  @PostMapping("/api/admin/catalog/sections")
  public Map<String, String> createSection(
      @RequestBody Catalog.SectionInput input, HttpServletRequest req) {
    return Map.of("id", catalog.saveSection(null, input, auth.editor(req).id()));
  }

  @PutMapping("/api/admin/catalog/sections/{id}")
  public Map<String, String> saveSection(
      @PathVariable String id, @RequestBody Catalog.SectionInput input, HttpServletRequest req) {
    return Map.of("id", catalog.saveSection(id, input, auth.editor(req).id()));
  }

  @DeleteMapping("/api/admin/catalog/sections/{id}")
  public Map<String, Boolean> deleteSection(
      @PathVariable String id, @RequestParam long revision, HttpServletRequest req) {
    catalog.deleteSection(id, revision, auth.editor(req).id());
    return Map.of("success", true);
  }

  /** 不返回文件路径表、草稿、后台人工选品ID列表与交付地址。 */
  @GetMapping("/api/public/catalog")
  public Map<String, Object> publicCatalog(
      @RequestParam(defaultValue = "false") boolean summary,
      HttpServletRequest req,
      HttpServletResponse res) {
    visitor(req, res);
    Catalog.PublicData data =
        summary
            ? new Catalog.PublicData(
                List.of(),
                catalog.sections().stream()
                    .filter(s -> s.config().enabled())
                    .map(s -> new Catalog.SectionView(s.id(), s.config(), List.of()))
                    .toList(),
                Map.of(),
                Map.of())
            : catalog.publicData();
    return Map.of(
        "products",
        data.products().stream()
            .filter(p -> p.status().equals("PUBLISHED"))
            .map(this::directoryProduct)
            .toList(),
        "sections",
        data.sections().stream()
            .sorted(java.util.Comparator.comparing(s -> s.config().mode().equals("API")))
            .map(
                s ->
                    Map.of(
                        "id",
                        s.id(),
                        "kind",
                        Set.of("ARTICLES", "API").contains(s.config().mode())
                            ? s.config().mode()
                            : "APPLICATIONS",
                        "href",
                        s.config().mode().equals("ARTICLES")
                            ? "/articles"
                            : s.config().mode().equals("API")
                                ? "/api-services"
                                : "/sections/" + s.id(),
                        "titleZh",
                        s.config().titleZh(),
                        "titleEn",
                        s.config().titleEn(),
                        "subtitleZh",
                        s.config().subtitleZh(),
                        "subtitleEn",
                        s.config().subtitleEn(),
                        "icon",
                        s.config().icon(),
                        "limit",
                        s.config().limit(),
                        "products",
                        s.products().stream().map(this::directoryProduct).toList()))
            .toList());
  }

  @PostMapping("/api/public/catalog/{slug}/view")
  public Map<String, Boolean> view(
      @PathVariable String slug, HttpServletRequest req, HttpServletResponse res) {
    catalog.metric(catalog.findPublic(slug), identity(req, res), "VIEW");
    return Map.of("success", true);
  }

  @PostMapping("/api/public/catalog/{slug}/download")
  public Map<String, String> download(
      @PathVariable String slug, HttpServletRequest req, HttpServletResponse res) {
    var product = catalog.findPublic(slug);
    if (product.status().equals("PUBLISHED")
        && (product.content().type().equals("API")
            || product.content().deliveryMode().equals("API")))
      return Map.of("url", "/api-services?api=" + product.slug());
    if (product.status().equals("PUBLISHED")
        && (product.content().pricePoints().signum() > 0
            || product.content().deliveryMode().equals("LICENSE")))
      return Map.of("url", "/checkout?slug=" + product.slug());
    return Map.of("url", catalog.freeDelivery(slug, identity(req, res)));
  }

  @GetMapping(value = "/api/public/pages/{slug}", produces = MediaType.TEXT_HTML_VALUE)
  public ResponseEntity<String> page(
      @PathVariable String slug, HttpServletRequest req, HttpServletResponse res) {
    // 不要求Nuxt渲染，不查商品详情接口；内存指针命中文件。no-cache允许浏览器验证更新，CDN可配置短TTL。
    visitor(req, res);
    String html =
        UiPresentation.decorate(
            catalog.page(slug),
            settings.publicValues(),
            catalog.publicData().sections(),
            catalog.findPublic(slug).content().type(),
            slug,
            "zh");
    statistics.pageEvent("/apps/" + slug, req, res);
    String etag = "\"" + catalog.hash(html) + "\"";
    String csp =
        "default-src 'self'; script-src 'sha256-"
            + StaticProductPage.scriptHash()
            + "'; style-src 'self' 'unsafe-inline'; img-src 'self' https:; connect-src 'self';"
            + " base-uri 'none'; frame-ancestors 'none'; form-action 'none'";
    if (etag.equals(req.getHeader("If-None-Match")))
      return ResponseEntity.status(304)
          .eTag(etag)
          .cacheControl(CacheControl.noCache())
          .header("Content-Security-Policy", csp)
          .build();
    return ResponseEntity.ok()
        .contentType(new MediaType("text", "html", java.nio.charset.StandardCharsets.UTF_8))
        .eTag(etag)
        .cacheControl(CacheControl.noCache())
        .header("Content-Security-Policy", csp)
        .body(html);
  }

  /** 登录者按账户去重，游客按匿名Cookie去重；访客标识不是购买或授权凭据。 */
  private String identity(HttpServletRequest req, HttpServletResponse res) {
    String anonymous = visitor(req, res);
    try {
      return "account:" + auth.user(req, false).id();
    } catch (Api.Failure failure) {
      if (failure.status != 401) throw failure;
      return "visitor:" + anonymous;
    }
  }

  /** 列表不传全文和截图数组，避免首页随着富文本增长而传输全部详情内容。 */
  private Catalog.Product directoryProduct(Catalog.Product p) {
    var c = p.content();
    var summary =
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
            0);
    return new Catalog.Product(
        p.id(),
        p.slug(),
        summary,
        p.revision(),
        p.status(),
        p.publicationId(),
        p.firstPublishedAt(),
        p.views(),
        p.downloads());
  }

  /** HttpOnly随机访客ID只用于日去重，不信任其作为身份或权益凭据。 */
  private String visitor(HttpServletRequest req, HttpServletResponse res) {
    if (req.getCookies() != null)
      for (Cookie cookie : req.getCookies())
        if (cookie.getName().equals("AIWYW_VISITOR") && cookie.getValue().matches("[a-f0-9-]{36}"))
          return cookie.getValue();
    String id = UUID.randomUUID().toString();
    res.addHeader(
        "Set-Cookie",
        ResponseCookie.from("AIWYW_VISITOR", id)
            .httpOnly(true)
            .secure(AuthController.secure(req))
            .sameSite("Lax")
            .path("/")
            .maxAge(Duration.ofDays(365))
            .build()
            .toString());
    return id;
  }
}
