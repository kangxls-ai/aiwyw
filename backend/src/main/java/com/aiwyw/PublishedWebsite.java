package com.aiwyw;

import static com.aiwyw.ContentHtml.escape;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 只导出公开内容。Nginx 仅读取 _published，临时和历史目录不对外提供。 */
@Component
@ConditionalOnProperty(name = "aiwyw.web-root")
final class PublishedWebsite {
  private final Path root, live;
  private final Settings settings;
  private final Database database;
  private final JdbcTemplate db;
  private final Catalog catalog;
  private final ContentService content;
  private final CatalogController directory;
  private final com.fasterxml.jackson.databind.ObjectMapper json;
  private final ContentController seo;
  private final SectionSelectionController sections;
  private final Series series;
  private final Storage storage;
  private final SecureStore secure;

  PublishedWebsite(
      @Value("${aiwyw.web-root}") String root,
      Settings settings, Database database, JdbcTemplate db, Catalog catalog,
      ContentService content, ContentController seo, SectionSelectionController sections,
      CatalogController directory, com.fasterxml.jackson.databind.ObjectMapper json,
      Series series, Storage storage, SecureStore secure) {
    this.root = Path.of(root).toAbsolutePath().normalize();
    this.live = this.root.resolve("_published");
    this.settings = settings;
    this.database = database;
    this.db = db;
    this.catalog = catalog;
    this.content = content;
    this.directory = directory;
    this.json = json;
    this.seo = seo;
    this.sections = sections;
    this.series = series;
    this.storage = storage;
    this.secure = secure;
  }

  @EventListener(ApplicationReadyEvent.class)
  synchronized void ready() throws IOException {
    Path previous = detach();
    try {
      rebuild(previous);
    } finally {
      discard(previous);
    }
  }

  /** 写操作前隔离旧目录，避免下架后仍由 Nginx 命中上一版本。 */
  Path detach() throws IOException {
    Files.createDirectories(root);
    if (!Files.exists(live)) return null;
    Path old = Files.createTempDirectory(root, ".previous-");
    return Files.move(live, old.resolve("snapshot"), StandardCopyOption.ATOMIC_MOVE);
  }

  void restore(Path previous) throws IOException {
    if (previous != null) Files.move(previous, live, StandardCopyOption.ATOMIC_MOVE);
  }

  void discard(Path previous) throws IOException {
    if (previous != null) remove(previous.getParent(), ".previous-");
  }

  private void remove(Path folder, String prefix) throws IOException {
    if (!root.equals(folder.getParent()) || !folder.getFileName().toString().startsWith(prefix))
      throw new IOException("Invalid publication staging directory");
    try (var paths = Files.walk(folder)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
    }
  }

  void rebuild(Path previous) throws IOException {
    Path stage = Files.createTempDirectory(root, ".publication-");
    try {
      // 运行账号和 Nginx 可以不同。仅开放这棵已发布文件树的读取权限。
      readable(stage);
      write(stage, "settings.json", json.writeValueAsString(database.installed() ? settings.publicValues() : Map.of()));
      write(stage, "navigation.json", json.writeValueAsString(database.installed() ? directory.publicCatalogBody(true) : Map.of("products", List.of(), "sections", List.of())));
      if (database.installed()) export(stage, previous);
      else {
        for (String lang : List.of("zh", "en", "default"))
          write(stage, "index." + lang + ".html",
              "<!doctype html><html lang='zh-CN'><meta charset='UTF-8'><title>微云湾</title>"
                  + "<main><p>网站尚未初始化</p><a href='/install'>打开安装向导</a></main></html>");
        write(stage, "robots.txt", "User-agent: *\nDisallow: /\n");
      }
      Files.move(stage, live, StandardCopyOption.ATOMIC_MOVE);
    } finally {
      if (Files.exists(stage)) remove(stage, ".publication-");
    }
  }

  private void export(Path stage, Path previous) throws IOException {
    var site = settings.publicValues();
    var nav = catalog.publicData().sections();
    boolean indexing = Boolean.parseBoolean(settings.value("seo.allow_indexing"));
    var home = sections.home();
    var seriesCards = series.cards();
    var appPaths = publicationPaths("""
        SELECT d.slug,p.web_directory FROM catalog_product d JOIN catalog_publication p
          ON p.id=d.active_publication WHERE d.status IN ('PUBLISHED','UNLISTED')
        """);
    var articles = publicationPaths("""
        SELECT d.slug,p.web_directory FROM content_document d JOIN content_publication p
          ON p.id=d.active_publication WHERE d.state='PUBLISHED'
        """);
    var seriesPaths = new HashMap<String, String>();
    db.query("SELECT id,slug,created_at FROM editorial_series WHERE published=TRUE",
        row -> { seriesPaths.put(row.getString("slug"), WebPublicationPath.directory(row.getLong("created_at"), row.getString("id"))); });
    for (var p : catalog.publicData().products()) {
      if (!Set.of("PUBLISHED", "UNLISTED").contains(p.status())) continue;
      String slug = p.slug();
      String html = UiPresentation.decorate(catalog.page(slug, site), site, nav, p.content().type(), slug, "zh");
      writeDetail(stage, "apps", slug, appPaths.get(slug), ".html",
          decorate(html, indexing && p.status().equals("PUBLISHED"), true));
    }
    String fallback = site.getOrDefault("site.fallback_language", "en").equals("zh") ? "zh" : "en";
    for (String language : List.of("zh", "en", "default")) {
      String lang = language.equals("default") ? fallback : language;
      for (var article : articles.entrySet()) {
        String slug = article.getKey();
        var p = content.page(slug, lang);
        String html = UiPresentation.decorate(p.html(), site, nav, p.kind(), slug, lang);
        writeDetail(stage, "articles", slug, article.getValue(), "." + language + ".html",
            decorate(html, indexing, false));
      }
      boolean en = lang.equals("en");
      String suffix = "." + language + ".html";
      String seriesName = en ? "Series" : "专栏";
      String list = "<section class='directory-section'><h1>" + seriesName + "</h1>"
          + seriesList(seriesCards, en) + "</section>";
      write(stage, "series-list" + suffix, frame(site, nav, "/series", seriesName, "", list, en, indexing));
      for (var card : seriesCards) {
        var detail = series.detail(card.slug());
        String title = local(en, card.titleZh(), card.titleEn());
        String description = local(en, card.summaryZh(), card.summaryEn());
        StringBuilder items = new StringBuilder("<ol class='static-series-items'>");
        for (var item : detail.items()) items.append("<li>").append(resource(item, en)).append("</li>");
        String crumbs = "<nav class='public-breadcrumb' aria-label='当前位置'><a href='/'>"
            + (en ? "Home" : "首页") + "</a> / <a href='/series'>" + seriesName
            + "</a> / <span aria-current='page'>" + escape(title) + "</span></nav>";
        String body = "<section class='directory-section'>" + crumbs + "<h1>" + escape(title)
            + "</h1><p>" + escape(description) + "</p>" + items + "</ol></section>";
        String path = "series/" + card.slug();
        writeDetail(stage, "series", card.slug(), seriesPaths.get(card.slug()), suffix,
            frame(site, nav, "/" + path, title, description, body, en, indexing));
      }
      var body = new StringBuilder("<section class='directory-section static-search'>"
          + "<form action='/apps' method='get'><input type='search' name='q' aria-label='"
          + (en ? "Search resources" : "搜索资源") + "' placeholder='"
          + (en ? "Discover useful knowledge" : "发现好知识") + "'><button type='submit'>"
          + (en ? "Search" : "搜索") + "</button></form></section>");
      body.append("<p id='static-delivery-error' role='alert'></p>");
      for (var section : home) {
        String id = value(section, "id");
        var config = nav.stream().filter(x -> x.id().equals(id)).findFirst().orElseThrow().config();
        String more = switch (config.mode()) {
          case "API" -> "/api-services";
          case "ARTICLES" -> "/articles";
          case "SERIES" -> "/series";
          default -> "/sections/" + id;
        };
        body.append("<section class='directory-section'><h2>").append(escape(local(en, config.titleZh(), config.titleEn())))
            .append(" <a href='").append(escape(more)).append("'>")
            .append(en ? "More" : "更多").append("</a></h2><div class='static-resources'>");
        if (section.get("items") instanceof List<?> items)
          for (Object item : items)
            if (item instanceof Map<?, ?> row) body.append(resource(row, en));
        body.append("</div></section>");
      }
      write(stage, "index" + suffix, frame(site, nav, "/", "", "", body.toString(), en, indexing));
    }
    write(stage, "robots.txt", seo.robots());
    write(stage, "sitemap.xml", seo.sitemap());
    publishMedia(stage, previous);
    copyConfiguredImage(stage, "assets", "payment-images", settings.value("payment.mock_image"));
    if (!site.getOrDefault("site.security_filing", "").isBlank())
      copyConfiguredImage(stage, "filing-assets", "filing-images", site.getOrDefault("site.security_filing_icon", ""));
  }

  private Map<String, String> publicationPaths(String sql) {
    var paths = new LinkedHashMap<String, String>();
    db.query(sql, row -> { paths.put(row.getString("slug"), row.getString("web_directory")); });
    return paths;
  }

  /** Nginx按公开地址查分片入口，真实文件按发布日期归档；同卷优先用硬链接，避免双份正文。 */
  private static void writeDetail(Path stage, String category, String slug, String directory,
      String suffix, String html) throws IOException {
    String archive = WebPublicationPath.archive(category, directory, slug + suffix);
    Path entry = target(stage, WebPublicationPath.route(category, slug, suffix));
    write(stage, archive, html);
    Path saved = stage.resolve(archive);
    try {
      Files.createLink(entry, saved);
    } catch (IOException | UnsupportedOperationException unsupported) {
      Files.copy(saved, entry);
      readable(entry);
    }
  }

  private void publishMedia(Path stage, Path previous) throws IOException {
    for (String id : db.queryForList(
        "SELECT id FROM storage_asset WHERE status='READY' AND purpose='IMAGE' AND public_visible=TRUE", String.class)) {
      if (!id.matches(Ids.REGEX)) continue;
      Path target = target(stage, "media/" + id + ".png");
      Path saved = previous == null ? null : previous.resolve("media/" + id + ".png");
      if (saved != null && Files.isRegularFile(saved)) {
        try {
          Files.createLink(target, saved);
        } catch (IOException | UnsupportedOperationException e) {
          Files.copy(saved, target);
        }
      } else {
        var file = storage.stored(id, true);
        if (file.connection().provider().equals("S3"))
          new S3ObjectStore(file.connection()).copyTo(file.objectKey(), target);
        else Files.copy(storage.localPath(file), target);
      }
      readable(target);
    }
  }

  private void copyConfiguredImage(Path stage, String publicDir, String sourceDir, String url) throws IOException {
    String prefix = "/api/public/" + publicDir + "/";
    if (!url.startsWith(prefix)) return;
    String name = url.substring(prefix.length());
    if (!name.matches("" + Ids.REGEX + "\\.png")) return;
    Path source = secure.root.resolve(sourceDir).resolve(name);
    if (!Files.isRegularFile(source)) return;
    Path target = target(stage, publicDir + "/" + name);
    Files.copy(source, target);
    readable(target);
  }

  private String frame(Map<String, String> site, List<Catalog.SectionView> nav, String path,
      String title, String description, String body, boolean en, boolean indexing) {
    String brand = site.getOrDefault("site.name", "微云湾");
    String lang = en ? "en" : "zh";
    String meta = description.isBlank()
        ? site.getOrDefault(en ? "site.description_en" : "site.description", "") : description;
    String toggle = "<a class='catalog-language' href='" + escape(path) + "?lang="
        + (en ? "zh" : "en") + "'>" + (en ? "中文" : "English") + "</a>";
    return decorate("<!doctype html><html data-language='" + lang + "' lang='" + (en ? "en" : "zh-CN")
        + "'><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>"
        + "<title>" + escape(title.isBlank() ? brand : title + " · " + brand) + "</title>"
        + "<meta name='description' content='" + escape(meta) + "'>"
        + "<link rel='canonical' href='https://" + escape(site.getOrDefault("site.domain", "www.aiwyw.com"))
        + escape(path) + (en ? "?lang=en" : "") + "'>"
        + "<link rel='icon' href='/images/weiyunwan.svg'><link rel='stylesheet' href='/ui-theme.css?v=2'>"
        + "<link rel='stylesheet' href='/site-shell.css?v=2'><link rel='stylesheet' href='/static-website.css'>"
        + "</head><body class='published-page static-website'>"
        + UiPresentation.header(site, nav, toggle)
        + "<main class='public-site-width'>" + body + "</main>" + UiPresentation.footer(site)
        + "</body></html>", indexing, false);
  }

  private static String decorate(String html, boolean indexing, boolean product) {
    String scripts = product ? " 'sha256-" + StaticProductPage.scriptHash() + "'" : "";
    String head = "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none';"
        + " script-src 'self'" + scripts + "; img-src 'self' https: data:; style-src 'self' 'unsafe-inline';"
        + " connect-src 'self'; base-uri 'none'; form-action 'self'\">"
        + (indexing ? "" : "<meta name='robots' content='noindex,nofollow'>")
        + "<script src='/static-page.js' defer></script>";
    return html.replaceFirst("(?i)<head>", java.util.regex.Matcher.quoteReplacement("<head>" + head));
  }

  private static String seriesList(List<Series.Card> cards, boolean en) {
    var html = new StringBuilder("<div class='static-resources'>");
    for (var card : cards)
      html.append(resource(Map.of("href", "/series/" + card.slug(), "name", card.titleZh(),
          "nameEn", card.titleEn(), "summary", card.summaryZh(), "summaryEn", card.summaryEn()), en));
    if (cards.isEmpty()) html.append("<p>").append(en ? "No published series" : "暂无已发布专栏").append("</p>");
    return html.append("</div>").toString();
  }

  private static String resource(Map<?, ?> row, boolean en) {
    String href = value(row, "href");
    String name = local(en, value(row, "name"), value(row, "nameEn"));
    String summary = local(en, value(row, "summary"), value(row, "summaryEn"));
    String icon = "";
    String slogan = "";
    String actions = "";
    String stats = row.containsKey("views")
        ? (en ? "Views " : "浏览 ") + escape(value(row, "views")) + " · " : "";
    if (row.get("product") instanceof Catalog.Product p) {
      href = "/apps/" + p.slug();
      name = local(en, p.content().nameZh(), p.content().nameEn());
      summary = local(en, p.content().summaryZh(), p.content().summaryEn());
      slogan = Objects.toString(p.content().version(), "");
      icon = p.content().iconUrl();
      stats = (en ? "Views " : "浏览 ") + p.views() + " · "
          + (en ? "Downloads " : "下载 ") + p.downloads() + " · ";
      if (p.content().type().equals("API") || p.content().deliveryMode().equals("API"))
        actions = "<a href='/api-services?api=" + escape(p.slug()) + "'>" + (en ? "Try" : "调试") + "</a>";
      else if (p.content().pricePoints().signum() > 0)
        actions = "<a href='/checkout?slug=" + escape(p.slug()) + "'>" + (en ? "Download" : "下载") + "</a>";
      else
        actions = "<button type='button' data-static-download='" + escape(p.slug()) + "' data-error='"
            + (en ? "Download unavailable" : "下载暂不可用") + "'>" + (en ? "Download" : "下载") + "</button>";
    } else if (href.startsWith("/api-services/")) {
      actions = "<a href='" + escape(href) + "'>" + (en ? "Try" : "调试") + "</a>";
    }
    String image = icon == null || icon.isBlank() ? ""
        : "<img src='" + escape(icon) + "' alt='' width='48' height='48' loading='lazy'>";
    String link = "<a href='" + escape(href) + "'>";
    String detail = href.startsWith("/articles/") ? (en ? "Read" : "阅读") : (en ? "Details" : "查看详情");
    return "<article class='static-resource'><div class='static-resource-head'>" + image + "<div><strong>"
        + link + escape(name) + "</a></strong><div>" + escape(slogan) + "</div></div></div><span>"
        + escape(summary) + "</span><div class='static-resource-actions'>" + stats + actions + link
        + detail + "</a></div></article>";
  }

  private static String value(Map<?, ?> row, String key) {
    return Objects.toString(row.get(key), "");
  }

  private static String local(boolean en, String zh, String english) {
    return en && english != null && !english.isBlank() ? english : Objects.toString(zh, "");
  }

  private static Path target(Path stage, String relative) throws IOException {
    Path path = stage.resolve(relative).normalize();
    if (!path.startsWith(stage)) throw new IOException("Invalid publication path");
    publicDirectories(path.getParent());
    return path;
  }

  private static void publicDirectories(Path dir) throws IOException {
    if (Files.isDirectory(dir)) return;
    publicDirectories(dir.getParent());
    Files.createDirectory(dir);
    readable(dir);
  }

  private static void write(Path stage, String relative, String content) throws IOException {
    Path path = target(stage, relative);
    Files.writeString(path, content, StandardOpenOption.CREATE_NEW);
    readable(path);
  }

  private static void readable(Path path) throws IOException {
    if (Files.getFileStore(path).supportsFileAttributeView("posix"))
      Files.setPosixFilePermissions(path, java.nio.file.attribute.PosixFilePermissions.fromString(
          Files.isDirectory(path) ? "rwxr-xr-x" : "rw-r--r--"));
  }
}
