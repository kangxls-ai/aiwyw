package com.aiwyw;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;

/** 独立安装验证公开外壳、旧快照兼容、备注码幂等和代理配置的私密边界。 */
@SpringBootTest
@AutoConfigureMockMvc
class ExperienceTest {
  static final Path root;

  static {
    try {
      root = Files.createTempDirectory("aiwyw-experience-");
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry p) {
    p.add("aiwyw.data-dir", () -> root.toString());
  }

  @Autowired JdbcTemplate db;
  @Autowired Catalog catalog;
  @Autowired ContentService content;
  @Autowired Commerce commerce;
  @Autowired MeteredApi api;
  @Autowired Settings settings;
  @Autowired SecureStore crypto;
  @Autowired ObjectMapper json;
  @Autowired MockMvc mvc;

  String user() {
    String id = UUID.randomUUID().toString();
    long now = System.currentTimeMillis();
    db.update(
        "INSERT INTO"
            + " app_user(id,email,password_hash,role_name,mfa_enabled,last_totp_step,created_at,email_verified_at,nickname)"
            + " VALUES(?,?,?,'USER',FALSE,-1,?,?,?)",
        id,
        id + "@example.com",
        crypto.password("isolated-review-password-123"),
        now,
        now,
        "昵称" + id.substring(0, 6));
    return id;
  }

  MeteredApi.Definition definition(String slug, String method, String headers) {
    return new MeteredApi.Definition(
        slug,
        "代理服务",
        "Proxy",
        "服务说明",
        "Description",
        "HTTPS_JSON",
        "https://example.com/api?fixed=1",
        "vendor-secret",
        false,
        "/ok",
        "true",
        BigDecimal.ONE,
        true,
        0,
        method,
        headers,
        false);
  }

  @Test
  void memoIsFourDigitsStableOnRetryAndNotFabricatedForOldOrders() {
    String owner = user(), key = UUID.randomUUID().toString();
    var body = new Commerce.Recharge(BigDecimal.TEN, null, key);
    var first = commerce.createRecharge(owner, body);
    assertTrue(((String) first.get("payment_memo")).matches("[0-9]{4}"));
    var retry = commerce.createRecharge(owner, body);
    assertEquals(first.get("id"), retry.get("id"));
    assertEquals(first.get("payment_memo"), retry.get("payment_memo"));
    db.update("UPDATE recharge_order SET payment_memo=NULL WHERE id=?", first.get("id"));
    ExperienceSchema.migrate(db.getDataSource(), db);
    assertNull(
        db.queryForMap("SELECT payment_memo FROM recharge_order WHERE id=?", first.get("id"))
            .get("payment_memo"));
    assertEquals(
        1,
        db.queryForObject(
            "SELECT COUNT(*) FROM recharge_order WHERE user_id=?", Integer.class, owner));
  }

  @Test
  void proxyConfigurationIsEncryptedAndPublicMetadataExcludesUpstream() throws Exception {
    String slug = "proxy-" + UUID.randomUUID();
    String id =
        api.saveDefinition(
            null, definition(slug, "GET", "{\"X-API-Key\":\"vendor-header-secret\"}"), user());
    var row = db.queryForMap("SELECT * FROM api_definition WHERE id=?", id);
    assertEquals("GET", row.get("request_method"));
    assertFalse(row.get("headers_enc").toString().contains("vendor-header-secret"));
    assertTrue(crypto.decrypt((String) row.get("headers_enc")).contains("vendor-header-secret"));
    String admin = json.writeValueAsString(api.definitions(true));
    assertTrue(admin.contains("headers_set"));
    assertFalse(admin.contains("vendor-header-secret"));
    String publicData = json.writeValueAsString(api.definitions(false));
    assertFalse(publicData.contains("example.com"));
    assertFalse(publicData.contains("secret_enc"));
    assertFalse(publicData.contains("headers_enc"));
    var b = definition(slug, "POST", "");
    api.saveDefinition(
        id,
        new MeteredApi.Definition(
            b.slug(),
            b.name(),
            b.nameEn(),
            b.description(),
            b.descriptionEn(),
            b.adapter(),
            b.endpoint(),
            "",
            false,
            b.successPointer(),
            b.successValue(),
            b.pricePoints(),
            true,
            1,
            "POST",
            "",
            false),
        user());
    assertEquals(
        row.get("headers_enc"),
        db.queryForObject("SELECT headers_enc FROM api_definition WHERE id=?", String.class, id));
  }

  @Test
  void getEncodingAndHeaderValidationDoNotPermitTransportInjection() throws Exception {
    URI endpoint = URI.create("https://example.com/api?fixed=1");
    URI target =
        UpstreamRequest.target(
            endpoint, "GET", json.readTree("{\"q\":\"中文 & value\",\"count\":2}"));
    assertTrue(target.toASCIIString().contains("fixed=1&q=%E4%B8%AD%E6%96%87+%26+value&count=2"));
    assertEquals(endpoint, UpstreamRequest.target(endpoint, "POST", json.readTree("{}")));
    assertThrows(
        Api.Failure.class,
        () -> UpstreamRequest.target(endpoint, "GET", json.readTree("{\"x\":[]}")));
    for (String key :
        List.of("Host", "Cookie", "Authorization", "Content-Length", "Transfer-Encoding"))
      assertThrows(
          Api.Failure.class, () -> UpstreamRequest.headers(json, "{\"" + key + "\":\"secret\"}"));
    assertThrows(
        Api.Failure.class,
        () -> UpstreamRequest.headers(json, "{\"X-Key\":\"a\\r\\nInjected:b\"}"));
    assertThrows(Api.Failure.class, () -> UpstreamRequest.method("DELETE"));
    assertEquals("POST", UpstreamRequest.method(null));
  }

  @Test
  void legacyProductSnapshotGetsCurrentNavigationFooterAndBreadcrumb() throws Exception {
    String actor = user(), slug = "detail-" + UUID.randomUUID();
    String id =
        catalog.saveProduct(
            null,
            new Catalog.ProductInput(
                slug,
                "介绍应用",
                "Detail app",
                "应用摘要",
                "Summary",
                "<p>正文</p>",
                "<p>Body</p>",
                List.of(),
                "",
                List.of(),
                "TOOL",
                BigDecimal.ZERO,
                "1.0.0",
                "DOWNLOAD",
                "https://example.com/file.zip",
                0),
            actor);
    catalog.publish(id, 1, actor, settings);
    String original = catalog.page(slug), hash = catalog.hash(original);
    var response =
        mvc.perform(get("/api/public/pages/" + slug))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();
    String html = response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    assertTrue(html.contains("public-site-header"));
    assertTrue(html.contains("public-breadcrumb"));
    assertTrue(html.contains("工信部 ICP 备案号待填写"));
    assertTrue(html.contains("aria-current=\"page\""));
    assertFalse(html.contains("实用工具，触手可及"));
    assertEquals(hash, catalog.hash(catalog.page(slug)));
    assertTrue(
        response.getHeader("Content-Security-Policy").contains(StaticProductPage.scriptHash()));
  }

  @Test
  void faqAndCaseStaticPagesKeepContentSecurityAndCorrectPath() throws Exception {
    for (String kind : List.of("FAQ", "CASE")) {
      String slug = kind.toLowerCase() + "-" + UUID.randomUUID();
      String id =
          content.save(
              null,
              new ContentService.Input(
                  slug,
                  kind,
                  "内容标题",
                  "Content title",
                  "摘要",
                  "Summary",
                  "<p>正文中的HTML属性示例：data-product-status=PUBLISHED</p>",
                  "<p>Body</p>",
                  0),
              user());
      content.publish(id, 1, user());
      var response =
          mvc.perform(get("/api/public/content-pages/" + slug))
              .andExpect(status().isOk())
              .andReturn()
              .getResponse();
      String html = response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
      assertTrue(html.contains("public-site-header"));
      assertTrue(html.contains("/articles?kind=" + kind));
      assertTrue(html.contains("site-filing"));
      assertTrue(response.getHeader("Content-Security-Policy").contains("script-src 'none'"));
    }
  }

  @Test
  void apiNavigationCanBeHiddenAndDoesNotConsumeApplicationSlots() {
    var nav =
        catalog.sections().stream()
            .filter(s -> s.config().mode().equals("API"))
            .findFirst()
            .orElseThrow();
    var c = nav.config();
    assertThrows(Api.Failure.class, () -> catalog.saveSection(null, c, user()));
    catalog.saveSection(
        nav.id(),
        new Catalog.SectionInput(
            c.titleZh(),
            c.titleEn(),
            c.subtitleZh(),
            c.subtitleEn(),
            c.icon(),
            false,
            0,
            "API",
            c.sort(),
            c.days(),
            c.limit(),
            c.tags(),
            c.productIds(),
            nav.revision()),
        user());
    assertTrue(
        catalog.publicData().sections().stream().noneMatch(s -> s.config().mode().equals("API")));
    var current =
        catalog.sections().stream().filter(s -> s.id().equals(nav.id())).findFirst().orElseThrow();
    catalog.saveSection(
        nav.id(),
        new Catalog.SectionInput(
            c.titleZh(),
            c.titleEn(),
            c.subtitleZh(),
            c.subtitleEn(),
            c.icon(),
            true,
            0,
            "API",
            c.sort(),
            c.days(),
            c.limit(),
            c.tags(),
            c.productIds(),
            current.revision()),
        user());
    assertTrue(
        catalog.publicData().sections().stream().anyMatch(s -> s.config().mode().equals("API")));
  }
}
