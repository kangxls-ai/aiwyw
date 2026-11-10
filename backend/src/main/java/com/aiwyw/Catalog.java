package com.aiwyw;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 正式商品目录、首页栏目与不可变发布快照。 写操作先锁catalog_guard，再校验客户端revision；跨进程同样能守住最多6个应用栏目（文章导航独立）。 发布顺序：净化资料 →
 * 写完整HTML文件 → 事务保存快照并切指针 → 提交后失效内存缓存。 任何一步失败都不切换公开内容；孤立文件可以按运维手册清理，禁止反向猜测并自动发布。
 */
@Service
public class Catalog {
  public record ProductInput(
      String slug,
      String nameZh,
      String nameEn,
      String summaryZh,
      String summaryEn,
      String bodyZh,
      String bodyEn,
      List<String> tags,
      String iconUrl,
      List<String> screenshots,
      String type,
      BigDecimal pricePoints,
      String version,
      String deliveryMode,
      String downloadUrl,
      long revision) {
    /** 旧截图字段仍能读取。只把未出现在正文中的图片追加到介绍，空英文沿用中文。 */
    ProductInput withInlineImages() {
      if (screenshots == null || screenshots.isEmpty()) return this;
      return new ProductInput(
          slug,
          nameZh,
          nameEn,
          summaryZh,
          summaryEn,
          ContentHtml.appendImages(bodyZh, screenshots, "应用图片"),
          bodyEn == null || bodyEn.isBlank()
              ? ""
              : ContentHtml.appendImages(bodyEn, screenshots, "Application image"),
          tags,
          iconUrl,
          List.of(),
          type,
          pricePoints,
          version,
          deliveryMode,
          downloadUrl,
          revision);
    }
  }

  /** 选品规则与排序参数属于栏目配置，产品标签不依赖栏目存在。 */
  public record SectionInput(
      String titleZh,
      String titleEn,
      String subtitleZh,
      String subtitleEn,
      String icon,
      boolean enabled,
      int position,
      String mode,
      String sort,
      int days,
      int limit,
      List<String> tags,
      List<String> productIds,
      long revision) {}

  public record Section(String id, SectionInput config, long revision, long createdAt) {}

  public record Product(
      String id,
      String slug,
      ProductInput content,
      long revision,
      String status,
      String publicationId,
      long firstPublishedAt,
      long views,
      long downloads) {}

  public record SectionView(String id, SectionInput config, List<Product> products) {}

  /** 只有公开字段进入此缓存；绝不能把后台草稿或private_json装入。 */
  public record PublicData(
      List<Product> products,
      List<SectionView> sections,
      Map<String, String> staticFiles,
      Map<String, String> staticHashes) {}

  private final JdbcTemplate db;
  private final TransactionTemplate tx;
  private final ObjectMapper json;
  private final SecureStore store;
  private final Database database;
  private final Storage storage;
  private volatile PublicData cached;
  private volatile long cacheAt, initializedGeneration = -1;
  private static final Set<String> NAVIGATION_MODES = Set.of("ARTICLES", "API", "SERIES");
  private static final ZoneId REPORT_ZONE = ZoneId.of("Asia/Shanghai");

  public Catalog(
      JdbcTemplate db,
      PlatformTransactionManager manager,
      ObjectMapper json,
      SecureStore store,
      Database database,
      Storage storage) {
    this.db = db;
    this.tx = new TransactionTemplate(manager);
    this.json = json;
    this.store = store;
    this.database = database;
    this.storage = storage;
  }

  /** 安装向导切换数据库后重新种子化；不把旧开发库缓存带进新生产库。 */
  public synchronized void initialize() {
    if (initializedGeneration == database.generation()) return;
    tx.executeWithoutResult(
        status -> {
          long revision =
              db.queryForObject(
                  "SELECT revision FROM catalog_guard WHERE id=1 FOR UPDATE", Long.class);
          if (revision == 0) {
            String[][] defaults = {
              {"recommended", "站长推荐", "Editor's picks", "star", "MANUAL", "LATEST"},
              {"latest", "最新上架", "New arrivals", "spark", "AUTO", "LATEST"},
              {"downloads", "热门下载", "Popular downloads", "download", "AUTO", "DOWNLOADS"},
              {"views", "热门点击", "Most viewed", "flame", "AUTO", "VIEWS"}
            };
            for (int i = 0; i < defaults.length; i++) {
              var a = defaults[i];
              SectionInput c =
                  new SectionInput(
                      a[1], a[2], "", "", a[3], true, i, a[4], a[5], 30, 20, List.of(), List.of(),
                      0);
              db.update(
                  "INSERT INTO home_section(id,config_json,revision,created_at) VALUES(?,?,0,?)",
                  a[0],
                  encode(c),
                  System.currentTimeMillis());
            }
            // markdo只有名称得到确认，保存草稿，不编造价格、版本、下载文件或公开商品事实。
            var p =
                new ProductInput(
                    "markdo",
                    "markdo",
                    "markdo",
                    "",
                    "",
                    "",
                    "",
                    List.of(),
                    "",
                    List.of(),
                    "SOFTWARE",
                    BigDecimal.ZERO,
                    "",
                    "DOWNLOAD",
                    "",
                    0);
            String productId = Ids.next();
            db.update(
                "INSERT INTO catalog_product(id,slug,draft_json,revision,status,updated_at)"
                    + " VALUES(?,?,?,0,'DRAFT',?)",
                productId,
                p.slug(),
                encode(p),
                System.currentTimeMillis());
            CatalogQuerySchema.index(db, productId, p);
            db.update("UPDATE catalog_product SET created_at=updated_at WHERE created_at=0");
            db.update("UPDATE catalog_guard SET revision=1 WHERE id=1");
          }
          // 文章是导航栏目，不占六个应用展示区；只迁移一次，管理员删除或停用后不会重启复活。
          if (db.queryForObject(
                  "SELECT COUNT(*) FROM schema_migration WHERE version='V008_ARTICLE_NAV'",
                  Integer.class)
              == 0) {
            var article =
                new SectionInput(
                    "文章与教程",
                    "Articles and tutorials",
                    "",
                    "",
                    "note",
                    true,
                    4,
                    "ARTICLES",
                    "LATEST",
                    30,
                    20,
                    List.of(),
                    List.of(),
                    0);
            db.update(
                "INSERT INTO home_section(id,config_json,revision,created_at)"
                    + " VALUES('articles-navigation',?,0,?)",
                encode(article),
                System.currentTimeMillis());
            db.update(
                "INSERT INTO schema_migration VALUES('V008_ARTICLE_NAV','可维护的文章导航栏目',?)",
                System.currentTimeMillis());
          }
        });
    // API导航从固定链接迁入栏目，仅一次；以后停用/删除不因重启恢复，始终排在导航末尾。
    tx.executeWithoutResult(
        status -> {
          lock();
          if (db.queryForObject(
                  "SELECT COUNT(*) FROM schema_migration WHERE version='V011_API_NAV'",
                  Integer.class)
              == 0) {
            if (db.queryForList("SELECT config_json FROM home_section", String.class).stream()
                .map(value -> decode(value, SectionInput.class))
                .noneMatch(c -> c.mode().equals("API"))) {
              var c =
                  new SectionInput(
                      "API 服务",
                      "API services",
                      "",
                      "",
                      "browser",
                      true,
                      1000,
                      "API",
                      "LATEST",
                      30,
                      20,
                      List.of(),
                      List.of(),
                      0);
              db.update(
                  "INSERT INTO home_section(id,config_json,revision,created_at)"
                      + " VALUES('api-navigation',?,0,?)",
                  encode(c),
                  System.currentTimeMillis());
              Origins.created(db, "home_section", "api-navigation", "SYSTEM");
            }
            db.update(
                "INSERT INTO schema_migration VALUES('V011_API_NAV','统一管理可显隐API导航',?)",
                System.currentTimeMillis());
          }
        });
    // 专栏与文章、API一样使用独立栏目。迁移一次，管理员停用或删除后不会恢复。
    tx.executeWithoutResult(status -> {
      db.queryForObject("SELECT revision FROM catalog_guard WHERE id=1 FOR UPDATE", Long.class);
      if (db.queryForObject("SELECT COUNT(*) FROM schema_migration WHERE version='V016_SERIES_NAV'",
          Integer.class) != 0) return;
      long now = System.currentTimeMillis();
      var section = new SectionInput("专栏", "Series", "", "", "folder", true, 5,
          "SERIES", "LATEST", 0, Series.HOME_LIMIT, List.of(), List.of(), 0);
      db.update("INSERT INTO home_section(id,config_json,revision,created_at) VALUES('series-navigation',?,0,?)",
          encode(section), now);
      Origins.created(db, "home_section", "series-navigation", "SYSTEM");
      db.update("INSERT INTO schema_migration VALUES('V016_SERIES_NAV','统一管理专栏导航与首页展示',?)", now);
    });
    initializedGeneration = database.generation();
    cached = null;
  }

  private void lock() {
    db.queryForObject("SELECT revision FROM catalog_guard WHERE id=1 FOR UPDATE", Long.class);
    db.update("UPDATE catalog_guard SET revision=revision+1 WHERE id=1");
  }

  private void audit(String actor, String entity, String action, String detail) {
    AuditLog.write(db, "catalog_audit", actor, entity, action, detail);
  }

  private void invalidateAfterCommit() {
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            cached = null;
          }
        });
  }

  public String encode(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception failure) {
      throw new IllegalStateException("Cannot encode catalog data", failure);
    }
  }

  private <T> T decode(String value, Class<T> type) {
    try {
      return json.readValue(value, type);
    } catch (Exception failure) {
      throw new IllegalStateException(
          "Invalid stored catalog data; restore validated backup", failure);
    }
  }

  private String text(String value, int maximum, boolean required) {
    String result = value == null ? "" : value.trim();
    if ((required && result.isBlank()) || result.length() > maximum)
      throw new Api.Failure(400, "请检查必填项与字段长度");
    return result;
  }

  private List<String> labels(List<String> values, int maximum) {
    if (values == null) return List.of();
    if (values.size() > maximum) throw new Api.Failure(400, "标签或选品数量过多");
    return values.stream().map(s -> text(s, 80, true)).distinct().toList();
  }

  private String url(String value) {
    String result = text(value, 2000, false);
    if (!ContentHtml.safeUrl(result)) throw new Api.Failure(400, "资源地址必须是HTTPS链接或站内路径");
    return result;
  }

  /** 保存草稿即可净化，发布时仍再次校验，避免后续导入通道绕过。 */
  private ProductInput validate(ProductInput p) {
    if (p == null
        || p.pricePoints() == null
        || p.pricePoints().signum() < 0
        || p.pricePoints().scale() > 2
        || p.pricePoints().compareTo(new BigDecimal("9999999999.99")) > 0)
      throw new Api.Failure(400, "鼓励值价格必须是非负数，最多两位小数");
    String slug = text(p.slug(), 80, true);
    if (!slug.matches("[a-z0-9]+(?:-[a-z0-9]+)*")) throw new Api.Failure(400, "应用地址仅支持小写字母、数字和连字符");
    if (p.type() == null
        || p.deliveryMode() == null
        || !Set.of("SOFTWARE", "TOOL", "API", "DOCUMENT", "RESOURCE").contains(p.type())
        || !Set.of("DOWNLOAD", "EXTERNAL", "LICENSE", "API").contains(p.deliveryMode()))
      throw new Api.Failure(400, "商品类型或交付方式不正确");
    List<String> screenshots = p.screenshots() == null ? List.of() : p.screenshots();
    if (screenshots.size() > 12) throw new Api.Failure(400, "最多12张截图");
    return new ProductInput(
            slug,
            text(p.nameZh(), 100, true),
            text(p.nameEn(), 100, false),
            text(p.summaryZh(), 500, false),
            text(p.summaryEn(), 500, false),
            ContentHtml.sanitize(p.bodyZh()),
            ContentHtml.sanitize(p.bodyEn()),
            labels(p.tags(), 20),
            url(p.iconUrl()),
            screenshots.stream().map(this::url).toList(),
            p.type(),
            p.pricePoints().setScale(2),
            text(p.version(), 60, false),
            p.deliveryMode(),
            p.downloadUrl() != null && p.downloadUrl().matches("asset:" + Ids.REGEX)
                ? p.downloadUrl()
                : url(p.downloadUrl()),
            p.revision())
        .withInlineImages();
  }

  Product mapDraft(java.sql.ResultSet r, int n) throws java.sql.SQLException {
    return new Product(
        r.getString("id"),
        r.getString("slug"),
        decode(r.getString("draft_json"), ProductInput.class).withInlineImages(),
        r.getLong("revision"),
        r.getString("status"),
        r.getString("active_publication"),
        r.getLong("first_published_at"),
        0,
        0);
  }

  public List<Product> drafts() {
    initialize();
    return db.query("SELECT * FROM catalog_product ORDER BY updated_at DESC,id", this::mapDraft);
  }

  public Product draft(String id) {
    initialize();
    return db.query("SELECT * FROM catalog_product WHERE id=?", this::mapDraft, id).stream()
        .findFirst()
        .orElseThrow(() -> new Api.Failure(404, "商品不存在"));
  }

  public String saveProduct(String id, ProductInput input, String actor) {
    initialize();
    ProductInput p = validate(input);
    return tx.execute(
        status -> {
          lock();
          String key = id == null ? Ids.next() : id;
          try {
            if (id == null)
              db.update(
                  "INSERT INTO catalog_product(id,slug,draft_json,revision,status,updated_at)"
                      + " VALUES(?,?,?,1,'DRAFT',?)",
                  key,
                  p.slug(),
                  encode(p),
                  System.currentTimeMillis());
            else {
              var rows =
                  db.queryForList(
                      "SELECT slug,active_publication FROM catalog_product WHERE id=?", id);
              if (rows.isEmpty()) throw new Api.Failure(404, "商品不存在");
              if (rows.get(0).get("active_publication") != null
                  && !p.slug().equals(rows.get(0).get("slug")))
                throw new Api.Failure(409, "首次发布后不能更改公开地址");
              if (db.update(
                      "UPDATE catalog_product SET"
                          + " slug=?,draft_json=?,revision=revision+1,updated_at=? WHERE id=? AND"
                          + " revision=?",
                      p.slug(),
                      encode(p),
                      System.currentTimeMillis(),
                      id,
                      p.revision())
                  != 1) throw new Api.Failure(409, "商品已被其他人修改，请刷新后重试");
            }
          } catch (org.springframework.dao.DuplicateKeyException duplicate) {
            throw new Api.Failure(409, "应用地址已存在");
          }
          CatalogQuerySchema.index(db, key, p);
          CatalogTags.ensure(db, p.tags(), actor);
          storage.syncReferences("DRAFT", key, p, false);
          if (id == null) Origins.created(db, "catalog_product", key, actor);
          audit(actor, key, id == null ? "CREATE" : "SAVE", "draft");
          invalidateAfterCommit();
          return key;
        });
  }

  Map<String, Object> productOptions(Map<String, String> filters) {
    initialize();
    return OperationalPage.query(
        db,
        "SELECT id,sort_name AS name,slug,status,search_text,created_at FROM catalog_product",
        "1=1",
        List.of(),
        Map.of("id", "id", "created_at", "created_at"),
        "search_text",
        filters);
  }

  public List<Section> sections() {
    initialize();
    return db
        .query(
            "SELECT id,config_json,revision,created_at FROM home_section",
            (r, n) ->
                new Section(
                    r.getString(1),
                    decode(r.getString(2), SectionInput.class),
                    r.getLong(3),
                    r.getLong(4)))
        .stream()
        .sorted(
            Comparator.comparingInt((Section s) -> s.config().position())
                .thenComparing(Section::id))
        .toList();
  }

  Section sectionForMode(String mode) {
    return sections().stream().filter(s -> s.config().mode().equals(mode)).findFirst().orElse(null);
  }

  record SectionOrder(String id, long revision) {}

  /** 整体顺序一次提交；并发编辑或增删栏目时要求刷新，避免覆盖另一位运营者的更新。 */
  void reorderSections(List<SectionOrder> order, String actor) {
    initialize();
    if (order == null || order.size() > 9) throw new Api.Failure(400, "栏目顺序不正确");
    tx.executeWithoutResult(status -> {
      lock();
      var current = new HashMap<String, Section>();
      for (var section : sections()) current.put(section.id(), section);
      if (current.size() != order.size()) throw new Api.Failure(409, "栏目列表已变化，请刷新后重试");
      int position = 0;
      for (var item : order) {
        var row = item == null ? null : current.remove(item.id());
        if (row == null || row.revision() != item.revision())
          throw new Api.Failure(409, "栏目列表已变化，请刷新后重试");
        var c = row.config();
        if (c.position() != position) {
          var updated = new SectionInput(c.titleZh(), c.titleEn(), c.subtitleZh(), c.subtitleEn(),
              c.icon(), c.enabled(), position, c.mode(), c.sort(), c.days(), c.limit(), c.tags(),
              c.productIds(), row.revision() + 1);
          db.update("UPDATE home_section SET config_json=?,revision=revision+1 WHERE id=?",
              encode(updated), row.id());
        }
        position++;
      }
      audit(actor, "home-sections", "REORDER_SECTIONS", "调整首页栏目顺序");
      invalidateAfterCommit();
    });
  }

  public String saveSection(String id, SectionInput c, String actor) {
    initialize();
    if (c == null
        || c.mode() == null
        || c.sort() == null
        || c.icon() == null
        || !Set.of("MANUAL", "TAG", "AUTO", "ARTICLES", "API", "SERIES").contains(c.mode())
        || !Set.of("LATEST", "VIEWS", "DOWNLOADS").contains(c.sort())
        || !Set.of(0, 7, 30).contains(c.days())
        || c.limit() < 1
        || c.limit() > 40
        || c.position() < 0
        || c.position() > 1000) throw new Api.Failure(400, "请检查栏目选品规则、周期、位置和展示数量");
    if (!Set.of(
            "star", "spark", "download", "flame", "shapes", "folder", "note", "chart", "browser")
        .contains(c.icon())) throw new Api.Failure(400, "请选择栏目图标");
    SectionInput safe =
        new SectionInput(
            text(c.titleZh(), 40, true),
            text(c.titleEn(), 80, false),
            text(c.subtitleZh(), 100, false),
            text(c.subtitleEn(), 160, false),
            c.icon(),
            c.enabled(),
            c.position(),
            c.mode(),
            c.sort(),
            c.days(),
            c.mode().equals("SERIES") ? Series.HOME_LIMIT : c.limit(),
            labels(c.tags(), 20),
            labels(c.productIds(), 300),
            c.revision());
    return tx.execute(
        status -> {
          lock();
          var others =
              db
                  .query(
                      "SELECT id,config_json FROM home_section",
                      (r, n) ->
                          new Section(
                              r.getString(1), decode(r.getString(2), SectionInput.class), 0, 0))
                  .stream()
                  .filter(s -> !s.id().equals(id))
                  .toList();
          if (NAVIGATION_MODES.contains(safe.mode())
              && others.stream().anyMatch(s -> s.config().mode().equals(safe.mode())))
            throw new Api.Failure(409, "该导航栏目已存在，请编辑已有栏目");
          if (!NAVIGATION_MODES.contains(safe.mode())
              && others.stream()
                      .filter(s -> !NAVIGATION_MODES.contains(s.config().mode()))
                      .count()
                  >= 6) throw new Api.Failure(409, "首页最多6个栏目，请编辑或删除已有栏目");
          SectionSelection.validate(db, safe.productIds());
          String key = id == null ? Ids.next() : id;
          if (id == null)
            db.update(
                "INSERT INTO home_section(id,config_json,revision,created_at) VALUES(?,?,1,?)",
                key,
                encode(safe),
                System.currentTimeMillis());
          else if (db.update(
                  "UPDATE home_section SET config_json=?,revision=revision+1 WHERE id=? AND"
                      + " revision=?",
                  encode(safe),
                  id,
                  c.revision())
              != 1) throw new Api.Failure(409, "栏目已变化，请刷新后重试");
          CatalogTags.ensure(db, safe.tags(), actor);
          if (id == null) Origins.created(db, "home_section", key, actor);
          audit(
              actor,
              key,
              id == null ? "CREATE_SECTION" : "SAVE_SECTION",
              "position=" + safe.position());
          invalidateAfterCommit();
          return key;
        });
  }

  public void deleteSection(String id, long revision, String actor) {
    initialize();
    tx.executeWithoutResult(
        status -> {
          lock();
          if (db.update("DELETE FROM home_section WHERE id=? AND revision=?", id, revision) != 1)
            throw new Api.Failure(409, "栏目已变化，请刷新后重试");
          EditorialPins.clear(db, "section:" + id);
          audit(actor, id, "DELETE_SECTION", "products retained");
          invalidateAfterCommit();
        });
  }

  /** 每次发布创建新文件与新记录；前次HTML和快照不覆盖。 产品已购条款未来必须引用publicationId，不可追随active_publication改变。 */
  public String publish(String id, long revision, String actor, Settings settings) {
    return publish(id, revision, actor, settings, null);
  }

  String publish(String id, long revision, String actor, Settings settings, List<String> seriesIds) {
    initialize();
    return tx.execute(
        status -> {
          lock();
          Product draft = draft(id);
          if (draft.revision() != revision) throw new Api.Failure(409, "商品已变化，请重新打开后发布");
          ProductInput privateContent = validate(draft.content());
          if (privateContent.summaryZh().isBlank()
              || privateContent.bodyZh().isBlank()
              || privateContent.version().isBlank()) throw new Api.Failure(400, "发布前请补齐摘要、版本和图文介绍");
          // 同一大版本的交付承诺不可从下载切换成强制激活，或反向移除激活；避免已有权益无密钥或政策漂移。
          String major = privateContent.version().split("\\.", 2)[0];
          for (String previous :
              db.queryForList(
                  "SELECT private_json FROM catalog_publication WHERE product_id=?",
                  String.class,
                  id)) {
            ProductInput published = decode(previous, ProductInput.class);
            if (published.version().split("\\.", 2)[0].equals(major)
                && published.deliveryMode().equals("LICENSE")
                    != privateContent.deliveryMode().equals("LICENSE"))
              throw new Api.Failure(409, "同一已发布大版本不可切换下载与软件激活，请使用新的大版本");
          }
          String publication = Ids.next();
          long now = System.currentTimeMillis();
          long first = draft.firstPublishedAt() == 0 ? now : draft.firstPublishedAt();
          Product product =
              new Product(
                  id,
                  privateContent.slug(),
                  publicContent(privateContent),
                  revision + 1,
                  "PUBLISHED",
                  publication,
                  first,
                  0,
                  0);
          SeriesLinks.replace(db, json, id, seriesIds, actor);
          String html = StaticProductPage.render(product, settings.publicValues());
          String file = id + "/" + publication + ".html";
          writeStatic(file, html);
          db.update(
              "INSERT INTO"
                  + " catalog_publication(id,product_id,snapshot_json,private_json,file_name,sha256,actor_id,created_at,web_directory)"
                  + " VALUES(?,?,?,?,?,?,?,?,?)",
              publication,
              id,
              encode(product),
              encode(privateContent),
              file,
              hash(html),
              actor,
              now,
              WebPublicationPath.directory(now, publication));
          storage.syncReferences("PUBLICATION", publication, privateContent, true);
          if (privateContent.deliveryMode().equals("LICENSE"))
            Licenses.snapshot(db, id, publication, settings.integer("license.default_devices"));
          db.update(
              "UPDATE catalog_product SET"
                  + " active_publication=?,last_published_at=?,status='PUBLISHED',first_published_at=?,revision=revision+1,updated_at=?"
                  + " WHERE id=?",
              publication,
              System.currentTimeMillis(),
              first,
              System.currentTimeMillis(),
              id);
          audit(actor, id, "PUBLISH", publication);
          invalidateAfterCommit();
          return publication;
        });
  }

  private ProductInput publicContent(ProductInput p) {
    return new ProductInput(
        p.slug(),
        p.nameZh(),
        p.nameEn(),
        p.summaryZh(),
        p.summaryEn(),
        p.bodyZh(),
        p.bodyEn(),
        p.tags(),
        p.iconUrl(),
        p.screenshots(),
        p.type(),
        p.pricePoints(),
        p.version(),
        p.deliveryMode(),
        "",
        0);
  }

  public void unlist(String id, long revision, String actor) {
    initialize();
    tx.executeWithoutResult(
        status -> {
          lock();
          if (db.update(
                  "UPDATE catalog_product SET status='UNLISTED',revision=revision+1,updated_at=?"
                      + " WHERE id=? AND revision=? AND active_publication IS NOT NULL",
                  System.currentTimeMillis(),
                  id,
                  revision)
              != 1) throw new Api.Failure(409, "商品未发布或已发生变化");
          audit(actor, id, "UNLIST", "existing entitlements retained");
          invalidateAfterCommit();
        });
  }

  public List<Map<String, Object>> history(String id) {
    initialize();
    return db.queryForList(
        "SELECT id,product_id,sha256,actor_id,created_at FROM catalog_publication WHERE"
            + " product_id=? ORDER BY created_at DESC,id",
        id);
  }

  public void rollback(String id, String publication, long revision, String actor) {
    initialize();
    tx.executeWithoutResult(
        status -> {
          lock();
          var rows =
              db.queryForList(
                  "SELECT file_name,sha256 FROM catalog_publication WHERE id=? AND product_id=?",
                  publication,
                  id);
          if (rows.isEmpty()) throw new Api.Failure(404, "发布版本不存在");
          try {
            String html = Files.readString(staticPath((String) rows.get(0).get("file_name")));
            if (!hash(html).equals(rows.get(0).get("sha256")))
              throw new Api.Failure(409, "静态文件校验失败，请从备份恢复，不能切换此版本");
          } catch (java.io.IOException missing) {
            throw new Api.Failure(409, "静态文件缺失，请先从备份恢复");
          }
          if (db.update(
                  "UPDATE catalog_product SET active_publication=?,revision=revision+1,updated_at=?"
                      + " WHERE id=? AND revision=?",
                  publication,
                  System.currentTimeMillis(),
                  id,
                  revision)
              != 1) throw new Api.Failure(409, "商品已变化，请刷新后重试");
          // 下架状态不被回滚悄悄重新上架；重新上架需显式发布。
          audit(actor, id, "ROLLBACK", publication);
          invalidateAfterCommit();
        });
  }

  private Path staticPath(String file) {
    Path root = store.root.resolve("publications").toAbsolutePath().normalize();
    Path path = root.resolve(file).normalize();
    if (!path.startsWith(root) || !file.matches(Ids.REGEX + "/" + Ids.REGEX + "\\.html"))
      throw new IllegalStateException("Invalid publication path");
    return path;
  }

  private void writeStatic(String file, String html) {
    Path target = staticPath(file);
    try {
      Files.createDirectories(target.getParent());
      Path pending = Files.createTempFile(target.getParent(), "render-", ".pending");
      Files.writeString(pending, html, StandardCharsets.UTF_8);
      // 优先原子移动；目标文件是新UUID，兼容不支持ATOMIC_MOVE的卷而不覆盖旧文件。
      try {
        Files.move(pending, target, StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException unsupported) {
        Files.move(pending, target);
      }
    } catch (java.io.IOException failure) {
      throw new Api.Failure(503, "静态文件写入失败，原发布版本未改变，请检查磁盘空间与权限");
    }
  }

  public String hash(String html) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(html.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  /** 公开目录最多30秒刷新一次统计；修改发布/栏目立即失效。静态正文不查询商品详情接口。 */
  public synchronized PublicData publicData() {
    initialize();
    long now = System.currentTimeMillis();
    if (cached != null && now - cacheAt < 30000) return cached;
    List<Map<String, Object>> rows =
        db.queryForList(
            "SELECT p.status,p.first_published_at,v.snapshot_json,v.file_name,v.sha256 FROM"
                + " catalog_product p JOIN catalog_publication v ON p.active_publication=v.id");
    List<Product> products = new ArrayList<>();
    Map<String, String> files = new HashMap<>();
    Map<String, String> hashes = new HashMap<>();
    var allCounts = counts(0);
    for (var row : rows) {
      Product p = decode((String) row.get("snapshot_json"), Product.class);
      long[] total = allCounts.getOrDefault(p.id(), new long[2]);
      products.add(
          new Product(
              p.id(),
              p.slug(),
              p.content(),
              p.revision(),
              (String) row.get("status"),
              p.publicationId(),
              ((Number) row.get("first_published_at")).longValue(),
              total[0],
              total[1]));
      files.put(p.slug(), (String) row.get("file_name"));
      hashes.put(p.slug(), (String) row.get("sha256"));
    }
    Map<Integer, Map<String, long[]>> countsByDays = new HashMap<>();
    countsByDays.put(0, allCounts);
    List<SectionView> views = new ArrayList<>();
    for (Section s : sections())
      if (s.config().enabled()) {
        SectionInput c = s.config();
        if (NAVIGATION_MODES.contains(c.mode())) {
          views.add(new SectionView(s.id(), c, List.of()));
          continue;
        }
        List<Product> selected =
            products.stream()
                .filter(p -> p.status().equals("PUBLISHED"))
                .filter(p -> !c.mode().equals("MANUAL") || c.productIds().contains(p.id()))
                .filter(
                    p ->
                        !c.mode().equals("TAG")
                            || c.tags().stream().anyMatch(p.content().tags()::contains))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        var scores =
            c.sort().equals("LATEST")
                ? Map.<String, long[]>of()
                : countsByDays.computeIfAbsent(c.days(), this::counts);
        long[] noScore = new long[2];
        Comparator<Product> comparator =
            c.mode().equals("MANUAL")
                ? Comparator.comparingInt(p -> c.productIds().indexOf(p.id()))
                : c.sort().equals("LATEST")
                    ? Comparator.comparingLong(Product::firstPublishedAt).reversed()
                    : Comparator.comparingLong(
                            (Product p) ->
                                scores
                                    .getOrDefault(p.id(), noScore)[
                                    c.sort().equals("VIEWS") ? 0 : 1])
                        .reversed();
        selected.sort(comparator.thenComparing(Product::id));
        // 返回完整选品结果供“更多”分页；首页只截取limit，不把后台选择条件暴露成私有数据。
        views.add(new SectionView(s.id(), c, List.copyOf(selected)));
      }
    cached =
        new PublicData(
            List.copyOf(products), List.copyOf(views), Map.copyOf(files), Map.copyOf(hashes));
    cacheAt = now;
    return cached;
  }

  /** 一个时间范围只查一次聚合，栏目或应用数量增加时不会逐项查询统计。 */
  private Map<String, long[]> counts(int days) {
    String since =
        days == 0 ? "0000-00-00" : LocalDate.now(REPORT_ZONE).minusDays(days - 1).toString();
    Map<String, long[]> values = new HashMap<>();
    db.query(
        "SELECT product_id,metric_type,COUNT(*) AS total FROM catalog_metric"
            + " WHERE metric_day>=? GROUP BY product_id,metric_type",
        r -> {
          var count = values.computeIfAbsent(r.getString("product_id"), id -> new long[2]);
          count[r.getString("metric_type").equals("VIEW") ? 0 : 1] = r.getLong(3);
        },
        since);
    return values;
  }

  public Product findPublic(String slug) {
    return publicData().products().stream()
        .filter(p -> p.slug().equals(slug))
        .findFirst()
        .orElseThrow(() -> new Api.Failure(404, "应用不存在或尚未发布"));
  }

  public String page(String slug) {
    return page(slug, null);
  }

  String page(String slug, Map<String, String> site) {
    var data = publicData();
    Product p =
        data.products().stream()
            .filter(item -> item.slug().equals(slug))
            .findFirst()
            .orElseThrow(() -> new Api.Failure(404, "应用不存在或尚未发布"));
    try {
      String html = Files.readString(staticPath(data.staticFiles().get(slug)));
      if (!hash(html).equals(data.staticHashes().get(slug)))
        throw new Api.Failure(503, "页面完整性校验失败，请联系站点管理员恢复发布文件");
      // 使用刚校验的同一份发布快照适配旧图库，不改写文件、订单或历史数据。
      if (site != null && p.content().screenshots() != null && !p.content().screenshots().isEmpty())
        return StaticProductPage.render(p, site);
      return p.status().equals("UNLISTED")
          ? html.replace("data-product-status=\"PUBLISHED\"", "data-product-status=\"UNLISTED\"")
          : html;
    } catch (java.io.IOException missing) {
      throw new Api.Failure(503, "页面暂不可用，请联系站点管理员");
    }
  }

  /** 已发放地址才计DOWNLOAD，二维码展示/余额不足/权限拒绝均不能调用本方法。 */
  public void metric(Product product, String visitor, String kind) {
    if (visitor == null) return;
    if (visitor.matches(Ids.REGEX)) visitor = "visitor:" + visitor;
    if (!visitor.matches("(?:account|visitor):" + Ids.REGEX)) return;
    if (!Set.of("VIEW", "DOWNLOAD").contains(kind))
      throw new IllegalArgumentException("Unsupported metric");
    String digest = store.digest(store.localPassword() + ":" + visitor);
    try {
      db.update(
          "INSERT INTO catalog_metric(product_id,metric_day,visitor_hash,metric_type,created_at)"
              + " VALUES(?,?,?,?,?)",
          product.id(),
          LocalDate.now(REPORT_ZONE).toString(),
          digest,
          kind,
          System.currentTimeMillis());
    } catch (org.springframework.dao.DuplicateKeyException repeatedSameDay) {
      /* 按确认规则去重，非失败。 */
    }
  }

  public String freeDelivery(String slug, String visitor) {
    Product p = findPublic(slug);
    if (db.queryForObject(
            "SELECT COUNT(*) FROM catalog_product WHERE id=? AND status='PUBLISHED' AND"
                + " active_publication=?",
            Integer.class,
            p.id(),
            p.publicationId())
        != 1) throw new Api.Failure(409, "发布信息已更新，请刷新页面后重试");
    if (!p.status().equals("PUBLISHED")) throw new Api.Failure(409, "此应用已下架，不接受新的下载申请");
    if (p.content().pricePoints().signum() > 0 || p.content().deliveryMode().equals("LICENSE"))
      throw new Api.Failure(409, "请登录后从账户完成鼓励值兑换");
    String privateJson =
        db.queryForObject(
            "SELECT private_json FROM catalog_publication WHERE id=?",
            String.class,
            p.publicationId());
    ProductInput privateContent = decode(privateJson, ProductInput.class);
    if (privateContent.downloadUrl().isBlank()) throw new Api.Failure(409, "下载资源暂未开放，请联系站点客服");
    String result =
        privateContent.downloadUrl().startsWith("asset:")
            ? storage.ticket(privateContent.downloadUrl().substring(6), p.publicationId())
            : privateContent.downloadUrl();
    metric(p, visitor, "DOWNLOAD");
    return result;
  }
}
