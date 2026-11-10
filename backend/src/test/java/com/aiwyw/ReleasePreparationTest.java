package com.aiwyw;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;

/** 大目录分页、混合发布快照与生产约束；所有文件和数据均在独立临时目录。 */
@SpringBootTest
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(StaffMockMvc.class)
class ReleasePreparationTest {
  static final Path root;

  static {
    try {
      root = Files.createTempDirectory("aiwyw-release-");
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("aiwyw.data-dir", () -> root.toString());
  }

  @Autowired SectionSelection selection;
  @Autowired Catalog catalog;
  @Autowired Settings settings;
  @Autowired ContentService content;
  @Autowired MeteredApi api;
  @Autowired Database database;
  @Autowired JdbcTemplate db;
  @Autowired MockMvc mvc;
  @Autowired SecureStore crypto;
  @Autowired SupportController support;

  @Test
  void publishedSeriesHomeAndDirectoryUseIndependentPageLimits() throws Exception {
    String prefix = "series-page-test-";
    try {
      for (int i = 1; i <= 103; i++) {
        db.update("INSERT INTO editorial_series(id,slug,title_zh,title_en,summary_zh,summary_en,"
            + "published,refs_json,revision,created_at) VALUES(?,?,?,?,?,?,?,'[]',1,?)",
            Ids.next(), prefix + i, "分页验收专栏 " + i, "Series Page Fixture",
            "测试摘要", "Series Page Description", i < 103, 1700000000000L + i);
      }
      mvc.perform(get("/api/public/directory/series").param("q", "SERIES PAGE DESCRIPTION"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.total").value(102))
          .andExpect(jsonPath("$.items.length()").value(100))
          .andExpect(jsonPath("$.items[0].href").value("/series/" + prefix + 102));
      mvc.perform(get("/api/public/directory/series").param("q", prefix).param("page", "2"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.page").value(2))
          .andExpect(jsonPath("$.items.length()").value(2))
          .andExpect(jsonPath("$.items[1].href").value("/series/" + prefix + 1));
      var home = mvc.perform(get("/api/public/home-sections")).andExpect(status().isOk())
          .andReturn().getResponse().getContentAsByteArray();
      var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(home);
      for (var section : json) {
        if (!section.path("kind").asText().equals("SERIES")) continue;
        assertEquals(20, section.path("items").size());
        assertEquals("/series/" + prefix + 102, section.path("items").get(0).path("href").asText());
        assertEquals("/series/" + prefix + 83, section.path("items").get(19).path("href").asText());
        return;
      }
      fail("Default series section missing");
    } finally {
      db.update("DELETE FROM editorial_series WHERE slug LIKE ?", prefix + "%");
    }
  }

  @Test
  void reverseProxyHttpsKeepsGuestConversationCookieSecure() {
    var request = new org.springframework.mock.web.MockHttpServletRequest();
    request.addHeader("X-Forwarded-Proto", "https");
    var response = new org.springframework.mock.web.MockHttpServletResponse();
    support.cookie(request, response, "non-secret-cookie-fixture");
    assertTrue(response.getCookie("AIWYW_SUPPORT").getSecure());
    assertTrue(response.getCookie("AIWYW_SUPPORT").isHttpOnly());
    assertTrue(AuthController.secure(request));
    assertFalse(AuthController.secure(new org.springframework.mock.web.MockHttpServletRequest()));
    var https = new org.springframework.mock.web.MockHttpServletRequest();
    https.setSecure(true);
    assertTrue(AuthController.secure(https));
  }

  @Test
  void authenticatedReadinessUsesCurrentMfaPolicy() throws Exception {
    String id = UUID.randomUUID().toString(), token = crypto.token();
    db.update(
        "INSERT INTO"
            + " app_user(id,email,password_hash,role_name,mfa_enabled,last_totp_step,created_at,email_verified_at,nickname,disabled)"
            + " VALUES(?,?,?,'ADMIN',TRUE,-1,?,?,?,FALSE)",
        id,
        id + "@example.com",
        crypto.password("readiness-local-fixture-123"),
        System.currentTimeMillis(),
        System.currentTimeMillis(),
        "上线检查账户");
    db.update("INSERT INTO access_user_role VALUES(?,'ADMIN')", id);
    for (boolean staff : new boolean[] {false, true})
      db.update(
          "INSERT INTO app_session VALUES(?,?,?,TRUE)",
          crypto.digest((staff ? "staff:" : "") + token),
          id,
          System.currentTimeMillis() + 60000);
    var cookie = new jakarta.servlet.http.Cookie("AIWYW_SESSION", token);
    mvc.perform(get("/api/admin/production/readiness").cookie(cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.checks").isArray())
        .andExpect(jsonPath("$.blocked").value(true));
    var entry =
        settings.list().stream()
            .filter(e -> e.definition().key().equals("security.mfa_required"))
            .findFirst()
            .orElseThrow();
    settings.save(
        List.of(new Settings.Change("security.mfa_required", "false", entry.revision(), false)),
        id);
    try {
      var result =
          mvc.perform(get("/api/admin/production/readiness").cookie(cookie))
              .andExpect(status().isOk())
              .andReturn()
              .getResponse()
              .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
      var body = new com.fasterxml.jackson.databind.ObjectMapper().readTree(result);
      assertTrue(
          java.util.stream.StreamSupport.stream(body.get("checks").spliterator(), false)
              .anyMatch(
                  row ->
                      row.get("name").asText().equals("管理及财务双因素策略")
                          && row.get("state").asText().equals("BLOCK")));
    } finally {
      settings.save(
          List.of(
              new Settings.Change(
                  "security.mfa_required", entry.value(), entry.revision() + 1, false)),
          id);
    }
  }

  @Test
  void largeSelectionUsesDatabasePagesAndLiteralSearch() {
    for (int i = 0; i < 105; i++)
      db.update(
          "INSERT INTO"
              + " catalog_product(id,slug,draft_json,revision,status,updated_at,created_at,sort_name)"
              + " VALUES(?,?,?,1,'DRAFT',?,?,?)",
          UUID.randomUUID().toString(),
          "page-fixture-" + i,
          "{}",
          1000L + i,
          1000L + i,
          "分页商品" + i);
    var first = selection.page("分页商品", "PRODUCT", 1, 20);
    var second = selection.page("分页商品", "PRODUCT", 2, 20);
    assertEquals(105L, first.get("total"));
    var a = (List<Map<String, Object>>) first.get("items");
    var b = (List<Map<String, Object>>) second.get("items");
    assertEquals(20, a.size());
    assertEquals(20, b.size());
    assertTrue(
        Collections.disjoint(
            a.stream().map(row -> row.get("ref")).toList(),
            b.stream().map(row -> row.get("ref")).toList()));
    assertFalse(a.get(0).containsKey("draft_json"));
    assertEquals(0L, selection.page("%", "ALL", 1, 20).get("total"));
    assertThrows(Api.Failure.class, () -> selection.page("", "ALL", 1, 1000));
    var refs = List.of((String) b.get(3).get("ref"), (String) a.get(0).get("ref"));
    assertEquals(refs, selection.lookup(refs).stream().map(row -> row.get("ref")).toList());
    assertEquals(
        "MISSING", selection.lookup(List.of(UUID.randomUUID().toString())).get(0).get("kind"));
  }

  @Test
  void homepageCapsEnabledSectionsAndDirectoryFiltersBeforePaging() throws Exception {
    for (int i = 0; i < 205; i++)
      api.saveDefinition(
          null,
          new MeteredApi.Definition(
              "directory-fixture-" + i,
              "分页接口 " + i,
              "Directory fixture " + i,
              "分页上限验收",
              "Directory limit",
              "TEXT_STATS",
              "",
              "",
              false,
              "",
              "",
              BigDecimal.ONE,
              true,
              0),
          "release-fixture");
    var current =
        catalog.sections().stream().filter(s -> s.config().mode().equals("API")).findFirst();
    String id =
        catalog.saveSection(
            current.map(Catalog.Section::id).orElse(null),
            new Catalog.SectionInput(
                "接口目录",
                "APIs",
                "",
                "",
                "browser",
                true,
                0,
                "API",
                "LATEST",
                0,
                40,
                List.of(),
                List.of(),
                current.map(Catalog.Section::revision).orElse(0L)),
            "release-fixture");
    var json = new com.fasterxml.jackson.databind.ObjectMapper();
    var response =
        mvc.perform(get("/api/public/home-sections"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsByteArray();
    var sections = json.readTree(response);
    var expected =
        catalog.sections().stream()
            .filter(s -> s.config().enabled())
            .map(Catalog.Section::id)
            .toList();
    assertEquals(expected.size(), sections.size());
    for (int i = 0; i < expected.size(); i++) {
      assertEquals(expected.get(i), sections.get(i).get("id").asText());
      if (expected.get(i).equals(id)) assertEquals(40, sections.get(i).get("items").size());
    }
    mvc.perform(get("/api/public/sections/" + id + "/items").param("q", "分页上限验收"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(100));
    mvc.perform(
            get("/api/public/sections/" + id + "/items")
                .param("q", "Directory fixture")
                .param("price", "PAID")
                .param("pageSize", "200")
                .param("page", "2"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(205))
        .andExpect(jsonPath("$.items.length()").value(5))
        .andExpect(jsonPath("$.page").value(2))
        .andExpect(jsonPath("$.items[0].endpoint").doesNotExist());
    mvc.perform(get("/api/public/sections/" + id + "/items").param("pageSize", "201"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void manualMixedSelectionHonorsPublishStateAndOrder() {
    var input =
        new Catalog.ProductInput(
            "mixed-product",
            "混合应用",
            "Mixed app",
            "摘要",
            "Summary",
            "<p>不可加载到短目录的长正文</p>",
            "",
            List.of("工具"),
            "",
            List.of(),
            "TOOL",
            BigDecimal.ZERO,
            "1",
            "EXTERNAL",
            "https://example.com/private.zip",
            0);
    String product = catalog.saveProduct(null, input, "release-fixture");
    catalog.publish(product, 1, "release-fixture", settings);
    String document =
        content.save(
            null,
            new ContentService.Input(
                "mixed-case",
                "CASE",
                "混合案例",
                "Mixed case",
                "摘要",
                "Summary",
                "<p>内容</p>",
                "<p>Content</p>",
                0),
            "release-fixture");
    content.publish(document, 1, "release-fixture");
    String definition =
        api.saveDefinition(
            null,
            new MeteredApi.Definition(
                "mixed-api",
                "混合API",
                "Mixed API",
                "说明",
                "Description",
                "TEXT_STATS",
                "",
                "",
                false,
                "",
                "",
                BigDecimal.ZERO,
                true,
                0),
            "release-fixture");
    var refs = List.of("content:" + document, "api:" + definition, product);
    SectionSelection.validate(db, refs);
    var result = selection.publicItems(refs);
    assertEquals(refs, result.stream().map(row -> row.get("ref")).toList());
    var safe = (Catalog.Product) result.get(2).get("product");
    assertEquals("", safe.content().bodyZh());
    assertEquals("", safe.content().downloadUrl());
    db.update("UPDATE api_definition SET enabled=FALSE WHERE id=?", definition);
    assertEquals(2, selection.publicItems(refs).size());
    assertThrows(Api.Failure.class, () -> SectionSelection.valid(List.of("api:invalid")));
    assertThrows(
        Api.Failure.class,
        () -> SectionSelection.validate(db, List.of(UUID.randomUUID().toString())));
  }

  @Test
  void publicSummaryAndAdminBoundaries() throws Exception {
    mvc.perform(get("/api/public/catalog").param("summary", "true"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.products.length()").value(0));
    mvc.perform(get("/api/admin/catalog/selection")).andExpect(status().isUnauthorized());
    mvc.perform(
            post("/api/admin/mail/check")
                .header("X-AIWYW-Request", "1")
                .contentType("application/json")
                .content("{\"protocol\":\"SMTP\"}"))
        .andExpect(status().isUnauthorized());
    mvc.perform(get("/api/admin/production/readiness")).andExpect(status().isUnauthorized());
  }

  @Test
  void productionInstallationForbidsH2ButKeepsDevelopmentAvailable() {
    assertFalse(database.production());
    org.springframework.test.util.ReflectionTestUtils.setField(database, "mode", "production");
    try {
      assertThrows(
          Api.Failure.class,
          () ->
              database.requireProductionEngine(
                  new Database.ConnectionSpec("H2", "", 0, "", "", "")));
      assertDoesNotThrow(
          () ->
              database.requireProductionEngine(
                  new Database.ConnectionSpec("MYSQL", "localhost", 3306, "empty_db", "user", "")));
    } finally {
      org.springframework.test.util.ReflectionTestUtils.setField(database, "mode", "development");
    }
    assertFalse(ProductionReadiness.https("http://127.0.0.1:3000"));
    assertTrue(ProductionReadiness.https("https://www.aiwyw.com"));
  }

  @Test
  void diagnosticNeverReturnsRawProviderExceptions() {
    assertEquals(
        "AUTH",
        MailDiagnostics.code(
            new org.springframework.mail.MailAuthenticationException("secret-fixture")));
    assertEquals(
        "TLS",
        MailDiagnostics.code(
            new jakarta.mail.MessagingException(
                "hidden", new javax.net.ssl.SSLHandshakeException("hidden-host"))));
    assertEquals("DNS", MailDiagnostics.code(new java.net.UnknownHostException("hidden-host")));
    assertEquals("TIMEOUT", MailDiagnostics.code(new java.net.SocketTimeoutException("hidden")));
    assertFalse(MailDiagnostics.hint("AUTH").contains("secret-fixture"));
    assertEquals(
        "true",
        MailDiagnostics.properties("imaps").getProperty("mail.imaps.ssl.checkserveridentity"));
  }
}
