package com.aiwyw;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.*;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

/** 内容编辑按类型对应菜单鉴权，公开列表只查发布快照；静态页面关闭JavaScript仍完整可读。 */
@RestController
public class ContentController {
  private final ContentService service;
  private final Auth auth;
  private final Settings settings;
  private final JdbcTemplate db;
  private final OperationsStats statistics;
  private final Catalog catalog;

  ContentController(
      ContentService service,
      Auth auth,
      Settings settings,
      JdbcTemplate db,
      OperationsStats statistics,
      Catalog catalog) {
    this.service = service;
    this.auth = auth;
    this.settings = settings;
    this.db = db;
    this.statistics = statistics;
    this.catalog = catalog;
  }

  @GetMapping("/api/admin/content")
  Map<String, Object> list(
      @RequestParam(defaultValue = "articles") String scope,
      @RequestParam(defaultValue = "") String q,
      @RequestParam(defaultValue = "") String kind,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int pageSize,
      @RequestParam(defaultValue = "created_at") String sort,
      @RequestParam(defaultValue = "descending") String direction,
      HttpServletRequest r) {
    if (!Set.of("articles", "faq").contains(scope)) throw new Api.Failure(400, "内容范围不正确");
    var u = auth.permission(r, scope);
    return service.list(true, Set.of(scope), q, kind, page, pageSize, sort, direction);
  }

  private Auth.User editor(String id, HttpServletRequest r) {
    auth.staff(r);
    var row = service.row(id);
    return auth.permission(r, ContentService.menu((String) row.get("kind")));
  }

  @GetMapping("/api/admin/content/{id}")
  Map<String, Object> draft(@PathVariable String id, HttpServletRequest r) {
    editor(id, r);
    return service.draft(id);
  }

  @PostMapping("/api/admin/content")
  Map<String, String> create(@RequestBody ContentService.Input b, HttpServletRequest r) {
    var u = auth.permission(r, ContentService.menu(b.kind()));
    return Map.of("id", service.save(null, b, u.id()));
  }

  @PutMapping("/api/admin/content/{id}")
  Map<String, String> save(
      @PathVariable String id, @RequestBody ContentService.Input b, HttpServletRequest r) {
    var u = editor(id, r);
    auth.permission(r, ContentService.menu(b.kind()));
    return Map.of("id", service.save(id, b, u.id()));
  }

  record Revision(long revision) {}

  @PostMapping("/api/admin/content/{id}/publish")
  Map<String, Boolean> publish(
      @PathVariable String id, @RequestBody Revision b, HttpServletRequest r) {
    var u = editor(id, r);
    service.publish(id, b.revision(), u.id());
    return Map.of("success", true);
  }

  @PostMapping("/api/admin/content/{id}/unlist")
  Map<String, Boolean> unlist(
      @PathVariable String id, @RequestBody Revision b, HttpServletRequest r) {
    var u = editor(id, r);
    service.unlist(id, b.revision(), u.id());
    return Map.of("success", true);
  }

  @DeleteMapping("/api/admin/content/{id}")
  Map<String, Boolean> remove(
      @PathVariable String id, @RequestParam long revision, HttpServletRequest r) {
    var u = editor(id, r);
    service.remove(id, revision, u.id());
    return Map.of("success", true);
  }

  @GetMapping("/api/public/content")
  Map<String, Object> browse(
      @RequestParam(defaultValue = "") String q,
      @RequestParam(defaultValue = "") String kind,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "12") int pageSize) {
    return service.list(false, Set.of(), q, kind, page, pageSize, "created_at", "descending");
  }

  @GetMapping(value = "/api/public/content-pages/{slug}", produces = MediaType.TEXT_HTML_VALUE)
  ResponseEntity<String> page(
      @PathVariable String slug,
      @RequestParam(defaultValue = "zh") String lang,
      HttpServletRequest r,
      jakarta.servlet.http.HttpServletResponse response) {
    var p = service.page(slug, lang);
    statistics.pageEvent("/articles/" + slug, r, response);
    String html =
        UiPresentation.decorate(
            p.html(),
            settings.publicValues(),
            catalog.publicData().sections(),
            p.kind(),
            slug,
            lang);
    String etag = "\"" + catalog.hash(html) + "\"";
    var headers = new HttpHeaders();
    headers.setETag(etag);
    headers.setCacheControl("no-cache");
    if (!Boolean.parseBoolean(settings.value("seo.allow_indexing")))
      headers.set("X-Robots-Tag", "noindex, nofollow");
    headers.set(
        "Content-Security-Policy",
        "default-src 'none'; img-src 'self' https: data:; style-src 'self' 'unsafe-inline';"
            + " script-src 'none'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'");
    if (etag.equals(r.getHeader("If-None-Match")))
      return new ResponseEntity<>(null, headers, HttpStatus.NOT_MODIFIED);
    return new ResponseEntity<>(html, headers, HttpStatus.OK);
  }

  @GetMapping("/api/admin/seo")
  Map<String, Object> seo(HttpServletRequest r) {
    auth.permission(r, "seo");
    return Map.of(
        "settings",
        settings.list().stream().filter(e -> SEO_KEYS.contains(e.definition().key())).toList(),
        "published",
        db.queryForObject(
            "SELECT COUNT(*) FROM content_document WHERE state='PUBLISHED'", Integer.class));
  }

  static final Set<String> SEO_KEYS =
      Set.of("site.description", "site.description_en", "seo.allow_indexing");

  record SeoSave(List<Settings.Change> changes) {}

  @PutMapping("/api/admin/seo")
  Map<String, Boolean> seo(@RequestBody SeoSave b, HttpServletRequest r) {
    var u = auth.permission(r, "seo");
    if (b.changes() == null
        || b.changes().stream().anyMatch(c -> c == null || !SEO_KEYS.contains(c.key())))
      throw new Api.Failure(400, "仅支持SEO描述与索引开关");
    settings.save(b.changes(), u.id());
    return Map.of("success", true);
  }

  @GetMapping(value = "/api/public/seo/robots", produces = MediaType.TEXT_PLAIN_VALUE)
  String robots() {
    return "User-agent: *\n"
        + (Boolean.parseBoolean(settings.value("seo.allow_indexing"))
            ? "Allow: /\nDisallow: /admin\nDisallow: /account\nDisallow: /api/\nSitemap: https://"
                + settings.value("site.domain")
                + "/sitemap.xml\n"
            : "Disallow: /\n");
  }

  @GetMapping(value = "/api/public/seo/sitemap", produces = MediaType.APPLICATION_XML_VALUE)
  String sitemap() {
    StringBuilder xml =
        new StringBuilder(
            "<?xml version='1.0' encoding='UTF-8'?><urlset"
                + " xmlns='http://www.sitemaps.org/schemas/sitemap/0.9'>");
    if (Boolean.parseBoolean(settings.value("seo.allow_indexing"))) {
      String base = "https://" + settings.value("site.domain");
      entry(xml, base + "/", null);
      entry(xml, base + "/articles", null);
      for (var row :
          db.queryForList(
              "SELECT d.slug,p.created_at,p.title_en FROM content_document d JOIN"
                  + " content_publication p ON p.id=d.active_publication WHERE"
                  + " d.state='PUBLISHED'")) {
        entry(
            xml,
            base + "/articles/" + row.get("slug"),
            ((Number) row.get("created_at")).longValue());
        if (!((String) row.get("title_en")).isBlank())
          entry(
              xml,
              base + "/articles/" + row.get("slug") + "?lang=en",
              ((Number) row.get("created_at")).longValue());
      }
      for (var row :
          db.queryForList(
              "SELECT d.slug,p.created_at FROM catalog_product d JOIN catalog_publication p ON"
                  + " p.id=d.active_publication WHERE d.status='PUBLISHED'"))
        entry(xml, base + "/apps/" + row.get("slug"), ((Number) row.get("created_at")).longValue());
    }
    return xml.append("</urlset>").toString();
  }

  private void entry(StringBuilder xml, String url, Long time) {
    xml.append("<url><loc>").append(ContentHtml.escape(url)).append("</loc>");
    if (time != null)
      xml.append("<lastmod>").append(Instant.ofEpochMilli(time)).append("</lastmod>");
    xml.append("</url>");
  }
}
