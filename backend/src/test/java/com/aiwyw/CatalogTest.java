package com.aiwyw;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** 直接验证业务不变量和HTTP权限，不以“按钮存在”替代真实数据库事务验证。 */
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CatalogTest {
  static final Path directory;

  static {
    try {
      directory = Files.createTempDirectory("aiwyw-catalog-");
      // 可选生产方言联测：只连接显式指定的隔离MySQL测试端口，不读取用户现有库凭据。
      String mysqlPort = System.getProperty("aiwyw.test.mysql-port");
      if (mysqlPort != null) {
        SecureStore store = new SecureStore(directory.toString());
        var spec =
            new Database.ConnectionSpec(
                "MYSQL",
                "127.0.0.1",
                Integer.parseInt(mysqlPort),
                System.getProperty("aiwyw.test.mysql-database", "aiwyw_catalog_validation"),
                "root",
                "");
        Files.writeString(
            directory.resolve("bootstrap.enc"),
            store.encrypt(new ObjectMapper().writeValueAsString(spec)));
      }
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("aiwyw.data-dir", () -> directory.toString());
  }

  @Autowired CatalogAdminQuery query;
  @Autowired Catalog catalog;
  @Autowired Settings settings;
  @Autowired JdbcTemplate db;
  @Autowired MockMvc mvc;
  @Autowired SecureStore crypto;
  @Autowired ObjectMapper json;
  String productId, firstPublication, secondPublication;
  final String actor = "catalog-admin";

  Catalog.ProductInput product(String slug, String summary, long revision) {
    return new Catalog.ProductInput(
        slug,
        "可读工具",
        "Readable tool",
        summary,
        "Useful tool",
        "<h2>介绍</h2><p>安全正文</p><script>alert('evil')</script><img src='javascript:evil'"
            + " onerror='evil()'>",
        "",
        List.of("办公效率"),
        "",
        List.of(),
        "TOOL",
        new BigDecimal("0.00"),
        "1.0.0",
        "EXTERNAL",
        "https://example.com/private-package?token=not-public",
        revision);
  }

  Catalog.SectionInput section(long revision) {
    return new Catalog.SectionInput(
        "自定义栏目", "Custom", "", "", "star", true, 5, "AUTO", "VIEWS", 30, 20, List.of(), List.of(),
        revision);
  }

  Catalog.Product current() {
    return catalog.drafts().stream()
        .filter(p -> p.id().equals(productId))
        .findFirst()
        .orElseThrow();
  }

  Cookie session(String role, boolean mfa) {
    String id = UUID.randomUUID().toString(), token = crypto.token();
    db.update(
        "INSERT INTO"
            + " app_user(id,email,password_hash,role_name,mfa_enabled,last_totp_step,created_at)"
            + " VALUES(?,?,?,?,TRUE,-1,?)",
        id,
        id + "@example.com",
        crypto.password("test-account-long-password"),
        role,
        System.currentTimeMillis());
    db.update(
        "INSERT INTO app_session(token_hash,user_id,expires_at,mfa_verified) VALUES(?,?,?,?)",
        crypto.digest(token),
        id,
        System.currentTimeMillis() + 60000,
        mfa);
    return new Cookie("AIWYW_SESSION", token);
  }

  @Test
  @Order(1)
  void initializationDoesNotPublishInventedInventory() {
    assertEquals(4, catalog.sections().size());
    assertTrue(catalog.publicData().products().isEmpty());
    assertEquals(
        "DRAFT",
        catalog.drafts().stream()
            .filter(p -> p.slug().equals("markdo"))
            .findFirst()
            .orElseThrow()
            .status());
  }

  @Test
  @Order(2)
  void contentPermissionDoesNotGrantSystemSecrets() throws Exception {
    mvc.perform(get("/api/admin/catalog/products")).andExpect(status().isUnauthorized());
    Cookie operator = session("OPERATOR", false);
    mvc.perform(get("/api/admin/catalog/products").cookie(operator)).andExpect(status().isOk());
    mvc.perform(get("/api/admin/settings").cookie(operator)).andExpect(status().isForbidden());
    mvc.perform(get("/api/admin/catalog/products").cookie(session("FINANCE", true)))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/admin/catalog/products").cookie(session("ADMIN", false)))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/admin/catalog/sections")
                .cookie(operator)
                .contentType("application/json")
                .content(json.writeValueAsBytes(section(0))))
        .andExpect(status().isForbidden());
  }

  @Test
  @Order(3)
  void concurrentCreatesCannotExceedSixIncludingDisabledSections() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(5);
    try {
      List<Callable<Boolean>> jobs = new ArrayList<>();
      for (int i = 0; i < 5; i++)
        jobs.add(
            () -> {
              try {
                catalog.saveSection(null, section(0), actor);
                return true;
              } catch (Api.Failure e) {
                assertEquals(409, e.status);
                return false;
              }
            });
      int successes = 0;
      for (Future<Boolean> result : pool.invokeAll(jobs)) if (result.get()) successes++;
      assertEquals(2, successes);
      assertEquals(6, catalog.sections().size());
    } finally {
      pool.shutdownNow();
    }
    for (var s : catalog.sections())
      if (!Set.of("recommended", "latest", "downloads", "views").contains(s.id()))
        catalog.deleteSection(s.id(), s.revision(), actor);
    assertEquals(4, catalog.sections().size());
  }

  @Test
  @Order(4)
  void staleUpdatesRollbackAndPublishingRemovesUnsafeMarkupAndPrivateFields() throws Exception {
    productId = catalog.saveProduct(null, product("readable-tool", "第一版", 0), actor);
    assertThrows(
        Api.Failure.class,
        () -> catalog.saveProduct(productId, product("readable-tool", "过期修改", 0), actor));
    assertEquals("第一版", current().content().summaryZh());
    firstPublication = catalog.publish(productId, current().revision(), actor, settings);
    String html = catalog.page("readable-tool");
    assertTrue(html.contains("安全正文"));
    assertFalse(html.contains("javascript:evil"));
    assertFalse(html.contains("onerror"));
    assertFalse(html.contains("alert('evil')"));
    assertFalse(html.contains("not-public"));
    String body =
        mvc.perform(get("/api/public/catalog"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertFalse(body.contains("not-public"));
    assertFalse(body.contains("private-package"));
    assertTrue(
        catalog.history(productId).stream().anyMatch(p -> p.get("id").equals(firstPublication)));
  }

  @Test
  @Order(5)
  void draftIsIsolatedAndRollbackPreservesDraft() {
    catalog.saveProduct(productId, product("readable-tool", "第二版", current().revision()), actor);
    assertTrue(catalog.page("readable-tool").contains("第一版"));
    secondPublication = catalog.publish(productId, current().revision(), actor, settings);
    assertTrue(catalog.page("readable-tool").contains("第二版"));
    catalog.rollback(productId, firstPublication, current().revision(), actor);
    assertTrue(catalog.page("readable-tool").contains("第一版"));
    assertEquals("第二版", current().content().summaryZh());
    assertEquals(2, catalog.history(productId).size());
    assertThrows(
        Api.Failure.class,
        () ->
            catalog.saveProduct(
                productId, product("renamed-address", "更名", current().revision()), actor));
  }

  @Test
  @Order(6)
  void generationFailureNeverSwitchesDatabasePointer() throws Exception {
    String id = catalog.saveProduct(null, product("disk-failure", "文件失败", 0), actor);
    Path file = directory.resolve("publications").resolve(id);
    Files.createDirectories(file.getParent());
    Files.writeString(file, "block directory creation");
    Api.Failure failure =
        assertThrows(Api.Failure.class, () -> catalog.publish(id, 1, actor, settings));
    assertEquals(503, failure.status);
    assertNull(
        db.queryForObject(
            "SELECT active_publication FROM catalog_product WHERE id=?", String.class, id));
    assertTrue(catalog.history(id).isEmpty());
    Files.delete(file);
  }

  @Test
  @Order(7)
  void corruptHistoryCannotBeActivated() throws Exception {
    String file =
        db.queryForObject(
            "SELECT file_name FROM catalog_publication WHERE id=?",
            String.class,
            secondPublication);
    Path path = directory.resolve("publications").resolve(file);
    String original = Files.readString(path);
    Files.writeString(path, "corrupt");
    try {
      assertThrows(
          Api.Failure.class,
          () -> catalog.rollback(productId, secondPublication, current().revision(), actor));
      assertEquals(firstPublication, current().publicationId());
    } finally {
      Files.writeString(path, original);
    }
  }

  @Test
  @Order(8)
  void metricsDeduplicateAndDownloadsCountOnlyIssuedDelivery() {
    String visitor = UUID.randomUUID().toString();
    Catalog.Product p = catalog.findPublic("readable-tool");
    catalog.metric(p, visitor, "VIEW");
    catalog.metric(p, visitor, "VIEW");
    String url = catalog.freeDelivery("readable-tool", visitor);
    assertTrue(url.contains("private-package"));
    catalog.freeDelivery("readable-tool", visitor);
    assertEquals(
        1,
        db.queryForObject(
            "SELECT COUNT(*) FROM catalog_metric WHERE product_id=? AND metric_type='VIEW'",
            Integer.class,
            productId));
    assertEquals(
        1,
        db.queryForObject(
            "SELECT COUNT(*) FROM catalog_metric WHERE product_id=? AND metric_type='DOWNLOAD'",
            Integer.class,
            productId));
  }

  @Test
  @Order(9)
  void unlistingKeepsPageButBlocksNewDeliveryAndPublicDiscovery() throws Exception {
    catalog.unlist(productId, current().revision(), actor);
    assertTrue(catalog.page("readable-tool").contains("data-product-status=\"UNLISTED\""));
    assertThrows(
        Api.Failure.class,
        () -> catalog.freeDelivery("readable-tool", UUID.randomUUID().toString()));
    mvc.perform(get("/api/public/catalog")).andExpect(jsonPath("$.products.length()").value(0));
    catalog.rollback(productId, secondPublication, current().revision(), actor);
    assertEquals("UNLISTED", current().status());
  }

  @Test
  @Order(10)
  void staticResponseHasCspAndConditionalCaching() throws Exception {
    var response =
        mvc.perform(get("/api/public/pages/readable-tool"))
            .andExpect(status().isOk())
            .andExpect(
                header().string("Content-Type", org.hamcrest.Matchers.containsString("text/html")))
            .andReturn()
            .getResponse();
    assertTrue(response.getHeader("Content-Security-Policy").contains("sha256-"));
    mvc.perform(
            get("/api/public/pages/readable-tool")
                .header("If-None-Match", response.getHeader("ETag")))
        .andExpect(status().isNotModified());
    mvc.perform(get("/api/public/pages/missing")).andExpect(status().isNotFound());
  }

  @Test
  @Order(11)
  void sanitizerRejectsProtocolTricksAndEventAttributes() {
    for (String url :
        List.of(
            "javascript:alert(1)",
            "//evil.example/x",
            "/\\evil.example/x",
            "https://user:pass@example.com",
            "/../private")) assertFalse(ContentHtml.safeUrl(url));
    assertTrue(ContentHtml.safeUrl("https://example.com/a.png"));
    String output =
        ContentHtml.sanitize(
            "<p onclick='alert(1)'>正文</p><a"
                + " href='jav&#97;script:alert(1)'>链接</a><style>body{display:none}</style><iframe"
                + " src='https://evil.example'></iframe>");
    assertFalse(output.contains("onclick"));
    assertFalse(output.contains("javascript"));
    assertFalse(output.contains("<style"));
    assertFalse(output.contains("<iframe"));
    assertTrue(output.contains("正文"));
  }

  @Test
  @Order(12)
  void activeCorruptFileIsNotServedAndInvalidRechargeBoundsRollback() throws Exception {
    String file =
        db.queryForObject(
            "SELECT file_name FROM catalog_publication WHERE id=?",
            String.class,
            current().publicationId());
    Path path = directory.resolve("publications").resolve(file);
    String original = Files.readString(path);
    Files.writeString(path, "corrupt current");
    try {
      assertEquals(
          503, assertThrows(Api.Failure.class, () -> catalog.page("readable-tool")).status);
    } finally {
      Files.writeString(path, original);
    }
    Settings.Entry minimum =
        settings.list().stream()
            .filter(e -> e.definition().key().equals("wallet.min_recharge_yuan"))
            .findFirst()
            .orElseThrow();
    assertThrows(
        Api.Failure.class,
        () ->
            settings.save(
                List.of(
                    new Settings.Change(
                        "wallet.min_recharge_yuan", "20000", minimum.revision(), false)),
                actor));
    assertEquals("1", settings.value("wallet.min_recharge_yuan"));
  }

  @Test
  @Order(13)
  void accountMetricsDeduplicateAcrossBrowsersAndMalformedInputsAreRejected() throws Exception {
    Cookie account = session("OPERATOR", false);
    int before =
        db.queryForObject(
            "SELECT COUNT(*) FROM catalog_metric WHERE product_id=? AND metric_type='VIEW'",
            Integer.class,
            productId);
    for (int i = 0; i < 2; i++)
      mvc.perform(
              post("/api/public/catalog/readable-tool/view")
                  .header("X-AIWYW-Request", "1")
                  .cookie(account, new Cookie("AIWYW_VISITOR", UUID.randomUUID().toString())))
          .andExpect(status().isOk());
    assertEquals(
        before + 1,
        db.queryForObject(
            "SELECT COUNT(*) FROM catalog_metric WHERE product_id=? AND metric_type='VIEW'",
            Integer.class,
            productId));
    mvc.perform(
            post("/api/admin/catalog/products")
                .header("X-AIWYW-Request", "1")
                .cookie(account)
                .contentType("application/json")
                .content("{\"slug\":\"bad-input\",\"nameZh\":\"缺少类型\",\"pricePoints\":0}"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/admin/catalog/sections")
                .header("X-AIWYW-Request", "1")
                .cookie(account)
                .contentType("application/json")
                .content("{\"mode\":\"AUTO\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  @Order(14)
  void serverPaginationCombinesDatesStatusSectionAndTagsWithoutLeakingPrivateFields() {
    List<String> ids = new ArrayList<>();
    for (int i = 0; i < 14; i++) {
      var p = product("query-tool-" + i, "查询摘要", 0);
      var input =
          new Catalog.ProductInput(
              p.slug(),
              "分页应用" + i,
              "Paged app " + i,
              p.summaryZh(),
              p.summaryEn(),
              p.bodyZh(),
              p.bodyEn(),
              List.of("分页", i % 2 == 0 ? "A" : "B"),
              p.iconUrl(),
              p.screenshots(),
              p.type(),
              p.pricePoints(),
              p.version(),
              p.deliveryMode(),
              p.downloadUrl(),
              0);
      ids.add(catalog.saveProduct(null, input, actor));
    }
    var first = query.page("分页应用", "", "", "", "", List.of(), 1, 5);
    assertEquals(14, first.total());
    assertEquals(5, first.items().size());
    var last = query.page("分页应用", "", "", "", "", List.of(), 99, 5);
    assertEquals(3, last.page());
    assertEquals(4, last.items().size());
    assertEquals(7, query.page("分页应用", "", "", "", "", List.of("A", "分页"), 1, 20).total());
    catalog.publish(ids.get(0), 1, actor, settings);
    catalog.publish(ids.get(1), 1, actor, settings);
    long jan2 =
        java.time.LocalDate.of(2026, 1, 2)
            .atStartOfDay(java.time.ZoneId.of("Asia/Shanghai"))
            .toInstant()
            .toEpochMilli();
    db.update(
        "UPDATE catalog_product SET last_published_at=? WHERE id=?", jan2 + 3600000, ids.get(0));
    db.update(
        "UPDATE catalog_product SET last_published_at=? WHERE id=?", jan2 + 86400000, ids.get(1));
    assertEquals(
        1,
        query.page("分页应用", "PUBLISHED", "2026-01-02", "2026-01-02", "", List.of(), 1, 20).total());
    assertEquals(12, query.page("分页应用", "DRAFT", "", "", "", List.of(), 1, 20).total());
    var manual =
        new Catalog.SectionInput(
            "分页人工",
            "Paged",
            "",
            "",
            "star",
            true,
            5,
            "MANUAL",
            "LATEST",
            30,
            20,
            List.of(),
            List.of(ids.get(0), ids.get(2)),
            0);
    String section = catalog.saveSection(null, manual, actor);
    assertEquals(2, query.page("分页应用", "", "", "", section, List.of(), 1, 20).total());
    var tagged =
        new Catalog.SectionInput(
            "分页标签",
            "Tags",
            "",
            "",
            "star",
            true,
            5,
            "TAG",
            "LATEST",
            30,
            20,
            List.of("A"),
            List.of(),
            0);
    String taggedId = catalog.saveSection(null, tagged, actor);
    assertEquals(7, query.page("分页应用", "", "", "", taggedId, List.of(), 1, 20).total());
    assertEquals(0, query.page("' OR 1=1 --", "", "", "", "", List.of(), 1, 20).total());
    assertEquals(0, query.page("%", "", "", "", "", List.of(), 1, 20).total());
    assertThrows(
        Api.Failure.class,
        () -> query.page("", "", "2026-02-01", "2026-01-01", "", List.of(), 1, 20));
    assertFalse(catalog.encode(first).contains("not-public"));
    assertFalse(catalog.encode(first).contains("private-package"));
  }
}
