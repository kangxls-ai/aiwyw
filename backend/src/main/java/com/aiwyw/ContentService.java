package com.aiwyw;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 内容发布先写不可变文件，再事务切指针；失败保留旧版。与商品共用guard→资源的锁序。 */
@Service
public class ContentService {
  public record Input(
      String slug,
      String kind,
      String titleZh,
      String titleEn,
      String summaryZh,
      String summaryEn,
      String bodyZh,
      String bodyEn,
      long revision) {}

  public record Page(String html, String hash, long cachedAt, String kind) {}

  private final JdbcTemplate db;
  private final ObjectMapper json;
  private final SecureStore store;
  private final Settings settings;
  private final Storage storage;
  private final TransactionTemplate tx;
  private final Map<String, Page> cache = new ConcurrentHashMap<>();

  public ContentService(
      JdbcTemplate db,
      ObjectMapper json,
      SecureStore store,
      Settings settings,
      Storage storage,
      PlatformTransactionManager manager) {
    this.db = db;
    this.json = json;
    this.store = store;
    this.settings = settings;
    this.storage = storage;
    tx = new TransactionTemplate(manager);
  }

  private String text(String value, int max, boolean required) {
    String result = value == null ? "" : value.trim();
    if (result.length() > max || (required && result.isBlank()))
      throw new Api.Failure(400, "请填写标题、固定地址及正确长度的资料");
    return result;
  }

  public static String menu(String kind) {
    if (kind == null) throw new Api.Failure(400, "内容类型不正确");
    if (Set.of("FAQ", "CASE").contains(kind)) return "faq";
    if (Set.of("ARTICLE", "TUTORIAL").contains(kind)) return "articles";
    throw new Api.Failure(400, "内容类型不正确");
  }

  private Input clean(Input p) {
    if (p == null) throw new Api.Failure(400, "请填写内容");
    menu(p.kind());
    String slug = text(p.slug(), 80, true);
    if (!slug.matches("[a-z0-9]+(?:-[a-z0-9]+)*")) throw new Api.Failure(400, "公开地址仅支持小写英文、数字和短横线");
    return new Input(
        slug,
        p.kind(),
        text(p.titleZh(), 120, true),
        text(p.titleEn(), 120, false),
        text(p.summaryZh(), 500, false),
        text(p.summaryEn(), 500, false),
        ContentHtml.sanitize(p.bodyZh()),
        ContentHtml.sanitize(p.bodyEn()),
        p.revision());
  }

  private String encode(Object o) {
    try {
      return json.writeValueAsString(o);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private Input decode(String s) {
    try {
      return json.readValue(s, Input.class);
    } catch (Exception e) {
      throw new IllegalStateException("Invalid content snapshot", e);
    }
  }

  Map<String, Object> row(String id) {
    var rows = db.queryForList("SELECT * FROM content_document WHERE id=?", id);
    if (rows.isEmpty()) throw new Api.Failure(404, "内容不存在");
    return rows.get(0);
  }

  public Map<String, Object> draft(String id) {
    var r = row(id);
    return Map.of(
        "id",
        id,
        "content",
        decode((String) r.get("draft_json")),
        "revision",
        r.get("revision"),
        "state",
        r.get("state"));
  }

  private void guard() {
    db.queryForObject("SELECT revision FROM catalog_guard WHERE id=1 FOR UPDATE", Long.class);
  }

  /** 复用统一资源引用保护，不另存图片；本地与对象存储通过原资源快照适配。 */
  private Catalog.ProductInput media(Input p) {
    return new Catalog.ProductInput(
        p.slug(),
        p.titleZh(),
        p.titleEn(),
        p.summaryZh(),
        p.summaryEn(),
        p.bodyZh(),
        p.bodyEn(),
        List.of(),
        "",
        List.of(),
        "DOCUMENT",
        BigDecimal.ZERO,
        "",
        "DOWNLOAD",
        "",
        p.revision());
  }

  public String save(String id, Input input, String actor) {
    Input p = clean(input);
    return tx.execute(
        s -> {
          guard();
          String key = id == null ? Ids.next() : id;
          long now = System.currentTimeMillis();
          if (db.queryForObject(
                  "SELECT COUNT(*) FROM content_document WHERE slug=? AND id<>?",
                  Integer.class,
                  p.slug(),
                  key)
              > 0) throw new Api.Failure(409, "公开地址已存在");
          if (id == null)
            db.update(
                "INSERT INTO"
                    + " content_document(id,slug,kind,title_zh,draft_json,state,revision,created_at,updated_at)"
                    + " VALUES(?,?,?,?,?,'DRAFT',1,?,?)",
                key,
                p.slug(),
                p.kind(),
                p.titleZh(),
                encode(p),
                now,
                now);
          else {
            var old = row(id);
            if (old.get("active_publication") != null && !old.get("slug").equals(p.slug()))
              throw new Api.Failure(409, "首次发布后公开地址不可更改");
            if (db.update(
                    "UPDATE content_document SET"
                        + " slug=?,kind=?,title_zh=?,draft_json=?,revision=revision+1,updated_at=?"
                        + " WHERE id=? AND revision=?",
                    p.slug(),
                    p.kind(),
                    p.titleZh(),
                    encode(p),
                    now,
                    key,
                    p.revision())
                != 1) throw new Api.Failure(409, "内容已变化，请重新读取");
          }
          storage.syncReferences("CONTENT_DRAFT", key, media(p), false);
          if (id == null) Origins.created(db, "content_document", key, actor);
          audit(actor, key, "SAVE_CONTENT");
          return key;
        });
  }

  public void publish(String id, long revision, String actor) {
    publish(id, revision, actor, null);
  }

  void publish(String id, long revision, String actor, List<String> seriesIds) {
    String slug =
        tx.execute(
            s -> {
              guard();
              var r = row(id);
              if (((Number) r.get("revision")).longValue() != revision)
                throw new Api.Failure(409, "内容已变化，请刷新");
              Input p = clean(decode((String) r.get("draft_json")));
              if (p.bodyZh().isBlank() || p.summaryZh().isBlank())
                throw new Api.Failure(400, "发布前请补齐中文摘要和正文");
              if (p.titleEn().isBlank() != p.bodyEn().isBlank())
                throw new Api.Failure(400, "英文标题与正文须同时提供，也可同时留空");
              SeriesLinks.replace(db, json, "content:" + id, seriesIds, actor);
              String publication = Ids.next(), prefix = id + "-" + publication;
              long now = System.currentTimeMillis();
              String zh = render(p, false, now), en = render(p, true, now);
              write(prefix, "zh", zh);
              write(prefix, "en", en);
              db.update(
                  "INSERT INTO"
                      + " content_publication(id,document_id,kind,title_zh,title_en,summary_zh,summary_en,snapshot_json,file_prefix,sha_zh,sha_en,created_at,web_directory)"
                      + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                  publication,
                  id,
                  p.kind(),
                  p.titleZh(),
                  p.titleEn(),
                  p.summaryZh(),
                  p.summaryEn(),
                  encode(p),
                  prefix,
                  store.digest(zh),
                  store.digest(en),
                  now,
                  WebPublicationPath.directory(now, publication));
              storage.syncReferences("CONTENT_PUB", publication, media(p), true);
              db.update(
                  "UPDATE content_document SET"
                      + " active_publication=?,state='PUBLISHED',revision=revision+1,updated_at=?"
                      + " WHERE id=?",
                  publication,
                  now,
                  id);
              audit(actor, id, "PUBLISH_CONTENT");
              return p.slug();
            });
    invalidate(slug);
  }

  public void unlist(String id, long revision, String actor) {
    String slug =
        tx.execute(
            s -> {
              guard();
              var r = row(id);
              if (db.update(
                      "UPDATE content_document SET"
                          + " state='UNLISTED',revision=revision+1,updated_at=? WHERE id=? AND"
                          + " revision=?",
                      System.currentTimeMillis(),
                      id,
                      revision)
                  != 1) throw new Api.Failure(409, "内容已变化，请刷新");
              audit(actor, id, "UNLIST_CONTENT");
              return (String) r.get("slug");
            });
    invalidate(slug);
  }

  public void remove(String id, long revision, String actor) {
    tx.executeWithoutResult(
        s -> {
          guard();
          var r = row(id);
          if (r.get("active_publication") != null) throw new Api.Failure(409, "已发布内容请下架，历史快照保留");
          if (((Number) r.get("revision")).longValue() != revision)
            throw new Api.Failure(409, "内容已变化");
          db.update(
              "DELETE FROM storage_reference WHERE owner_type='CONTENT_DRAFT' AND owner_id=?", id);
          db.update("DELETE FROM content_document WHERE id=?", id);
          audit(actor, id, "DELETE_CONTENT");
        });
  }

  private void invalidate(String slug) {
    cache.remove(slug + ":zh");
    cache.remove(slug + ":en");
  }

  private Path path(String prefix, String language) {
    if (!prefix.matches(Ids.REGEX + "-" + Ids.REGEX))
      throw new IllegalStateException("Invalid content file prefix");
    return store.root.resolve("content-pages").resolve(prefix + "." + language + ".html");
  }

  private void write(String prefix, String language, String html) {
    try {
      Path p = path(prefix, language);
      Files.createDirectories(p.getParent());
      Files.writeString(p, html, StandardOpenOption.CREATE_NEW);
    } catch (Exception e) {
      throw new Api.Failure(503, "静态内容生成失败，旧发布保持生效");
    }
  }

  public Page page(String slug, String language) {
    if (!Set.of("zh", "en").contains(language)) throw new Api.Failure(400, "语言不正确");
    String key = slug + ":" + language;
    Page cached = cache.get(key);
    if (cached != null && System.currentTimeMillis() - cached.cachedAt() < 30000) return cached;
    var rows =
        db.queryForList(
            "SELECT d.kind,p.file_prefix,p.sha_zh,p.sha_en FROM content_document d JOIN"
                + " content_publication p ON p.id=d.active_publication WHERE d.slug=? AND"
                + " d.state='PUBLISHED'",
            slug);
    if (rows.isEmpty()) throw new Api.Failure(404, "内容不存在或已下架");
    try {
      var r = rows.get(0);
      String html = Files.readString(path((String) r.get("file_prefix"), language)),
          hash = (String) r.get("sha_" + language);
      if (!store.digest(html).equals(hash))
        throw new IllegalStateException("Content hash mismatch");
      Page result = new Page(html, hash, System.currentTimeMillis(), (String) r.get("kind"));
      cache.put(key, result);
      return result;
    } catch (Exception e) {
      throw new Api.Failure(503, "静态内容校验失败，请联系管理员");
    }
  }

  public Map<String, Object> list(
      boolean admin,
      Set<String> allowed,
      String q,
      String kind,
      int page,
      int size,
      String sort,
      String direction) {
    if (q == null
        || q.length() > 100
        || page < 1
        || page > 1000000
        || size < 1
        || size > (admin ? 100 : 200)) throw new Api.Failure(400, "查询参数不正确");
    if (!kind.isBlank()) menu(kind);
    List<String> kinds =
        List.of("ARTICLE", "TUTORIAL", "FAQ", "CASE").stream()
            .filter(
                k -> (!admin || allowed.contains(menu(k))) && (kind.isBlank() || kind.equals(k)))
            .toList();
    if (kinds.isEmpty()) return Map.of("items", List.of(), "total", 0, "page", 1);
    String base =
        admin
            ? " FROM content_document d"
            : " FROM content_document d JOIN content_publication p ON p.id=d.active_publication";
    String alias = admin ? "d" : "p";
    String where =
        " WHERE "
            + alias
            + ".kind IN ("
            + String.join(",", Collections.nCopies(kinds.size(), "?"))
            + ")"
            + (admin ? "" : " AND d.state='PUBLISHED'");
    List<Object> args = new ArrayList<>(kinds);
    if (!q.isBlank()) {
      String term = "%" + q.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
      where +=
          admin
              ? " AND d.title_zh LIKE ? ESCAPE '!'"
              : " AND CONCAT(p.title_zh,' ',p.title_en,' ',p.summary_zh,' ',p.summary_en,'"
                  + " ',d.slug) LIKE ? ESCAPE '!'";
      args.add(term);
    }
    String ordering =
        ListOrder.sql(
            sort,
            direction,
            Map.of(
                "created_at",
                alias + ".created_at",
                "title_zh",
                alias + ".title_zh",
                "kind",
                alias + ".kind",
                "state",
                "d.state"),
            "created_at",
            "d.id");
    long total = db.queryForObject("SELECT COUNT(*)" + base + where, Long.class, args.toArray());
    int actual = (int) Math.min(page, Math.max(1, (total + size - 1) / size));
    args.add(size);
    args.add((actual - 1) * size);
    String cols =
        admin
            ? "d.id,d.slug,d.kind,d.title_zh,d.state,d.revision,d.created_at,d.updated_at"
            : "d.id,d.slug,p.kind,p.title_zh,p.title_en,p.summary_zh,p.summary_en,p.created_at";
    return Map.of(
        "items",
        Commerce.safeRows(
            db.queryForList(
                "SELECT " + cols + base + where + " ORDER BY " + ordering + " LIMIT ? OFFSET ?",
                args.toArray())),
        "total",
        total,
        "page",
        actual);
  }

  private void audit(String actor, String id, String action) {
    AuditLog.write(db, "catalog_audit", actor, id, action, "内容维护");
  }

  private String render(Input p, boolean requestedEnglish, long now) {
    boolean en = requestedEnglish && !p.titleEn().isBlank() && !p.bodyEn().isBlank();
    String lang = en ? "en" : "zh-CN",
        title = en ? p.titleEn() : p.titleZh(),
        summary = en ? p.summaryEn() : p.summaryZh(),
        body = en ? p.bodyEn() : p.bodyZh(),
        base = "https://" + settings.value("site.domain"),
        url = base + "/articles/" + p.slug(),
        canonical = url + (en ? "?lang=en" : "");
    Map<String, Object> structured =
        p.kind().equals("FAQ")
            ? Map.of(
                "@context",
                "https://schema.org",
                "@type",
                "FAQPage",
                "mainEntity",
                List.of(
                    Map.of(
                        "@type",
                        "Question",
                        "name",
                        title,
                        "acceptedAnswer",
                        Map.of("@type", "Answer", "text", body))))
            : Map.of(
                "@context",
                "https://schema.org",
                "@type",
                "Article",
                "headline",
                title,
                "description",
                summary,
                "datePublished",
                Instant.ofEpochMilli(now).toString(),
                "inLanguage",
                lang,
                "mainEntityOfPage",
                canonical);
    String ld = encode(structured).replace("<", "\\u003c").replace(">", "\\u003e");
    String links =
        "<link rel='alternate' hreflang='zh-CN' href='"
            + url
            + "'>"
            + (!p.titleEn().isBlank()
                ? "<link rel='alternate' hreflang='en' href='" + url + "?lang=en'>"
                : "");
    return "<!doctype html><html lang='"
        + lang
        + "'><head><meta charset='UTF-8'><meta name='viewport'"
        + " content='width=device-width,initial-scale=1'><title>"
        + ContentHtml.escape(title)
        + " · "
        + ContentHtml.escape(settings.value("site.name"))
        + "</title><meta name='description' content='"
        + ContentHtml.escape(summary)
        + "'><link rel='canonical' href='"
        + canonical
        + "'>"
        + links
        + "<script type='application/ld+json'>"
        + ld
        + "</script><style>body{font:16px/1.8"
        + " system-ui,sans-serif;background:#f5f7fb;color:#343c53;margin:0;padding:24px}article{max-width:920px;margin:24px"
        + " auto;padding:36px;background:#fff;border:1px solid"
        + " #e9e5f3;border-radius:16px}h1{font-size:30px;line-height:1.4}img{max-width:100%;height:auto}a{color:#8068cb}table{max-width:100%;border-collapse:collapse}td,th{border:1px"
        + " solid"
        + " #ddd;padding:8px}pre{overflow:auto}@media(max-width:600px){body{padding:12px}article{padding:20px}h1{font-size:24px}}</style></head><body><article><nav><a"
        + " href='/'>"
        + ContentHtml.escape(settings.value("site.name"))
        + "</a> · <a href='/articles'>"
        + (en ? "Articles" : "文章与教程")
        + "</a> · <a href='"
        + (en ? "/articles/" + p.slug() : "/articles/" + p.slug() + "?lang=en")
        + "'>"
        + (en ? "中文" : "English")
        + "</a></nav><h1>"
        + ContentHtml.escape(title)
        + "</h1><p>"
        + ContentHtml.escape(summary)
        + "</p><time datetime='"
        + Instant.ofEpochMilli(now)
        + "'>"
        + Instant.ofEpochMilli(now)
        + "</time><hr>"
        + body
        + "</article></body></html>";
  }
}
