package com.aiwyw;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import jakarta.servlet.http.Cookie;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(StaffMockMvc.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FoundationTest {
  static Path directory;

  static {
    try {
      directory = Files.createTempDirectory("aiwyw-tests-");
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("aiwyw.data-dir", () -> directory.toString());
  }

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired SecureStore crypto;
  @Autowired JdbcTemplate jdbc;
  static Cookie session;
  static String secret;
  static long verifiedStep;
  final String pass = "test-admin-password-9381";

  MockHttpServletRequestBuilder write(String method, String path, Object body) throws Exception {
    var r =
        switch (method) {
          case "PUT" -> put(path);
          default -> post(path);
        };
    return r.header("X-AIWYW-Request", "1")
        .contentType(MediaType.APPLICATION_JSON)
        .content(json.writeValueAsBytes(body));
  }

  JsonNode content(MvcResult result) throws Exception {
    return json.readTree(result.getResponse().getContentAsString());
  }

  Map<String, Object> installBody() {
    return Map.of(
        "connection",
        Map.of(
            "engine", "H2", "host", "", "port", 0, "database", "", "username", "", "password", ""),
        "email",
        "admin@example.com",
        "password",
        pass);
  }

  @Autowired StaffEntry staffEntry;

  @Test
  @Order(1)
  void installationRequiresTokenAndCsrf() throws Exception {
    mvc.perform(get("/api/install/status")).andExpect(jsonPath("$.installed").value(false));
    mvc.perform(
            post("/api/install")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(installBody())))
        .andExpect(status().isForbidden());
    mvc.perform(write("POST", "/api/install", installBody())).andExpect(status().isForbidden());
  }

  @Test
  @Order(2)
  void installsEncryptedDatabaseAndLocksInstaller() throws Exception {
    String token = Files.readString(directory.resolve("install.token")).trim();
    mvc.perform(write("POST", "/api/install", installBody()).header("X-Install-Token", token))
        .andExpect(status().isOk());
    assertTrue(Files.exists(directory.resolve("bootstrap.enc")));
    assertFalse(Files.exists(directory.resolve("install.token")));
    assertFalse(Files.readString(directory.resolve("bootstrap.enc")).contains("password"));
    mvc.perform(write("POST", "/api/install", installBody()).header("X-Install-Token", token))
        .andExpect(status().isConflict());
  }

  @Test
  @Order(3)
  void passwordOnlySessionCannotChangeSettings() throws Exception {
    mvc.perform(get("/api/admin/settings")).andExpect(status().isUnauthorized());
    var r =
        mvc.perform(
                write(
                        "POST",
                        "/api/auth/login",
                        Map.of("email", "admin@example.com", "password", pass, "code", ""))
                    .header(StaffEntry.HEADER, staffEntry.path().substring(1)))
            .andExpect(status().isOk())
            .andReturn();
    session = r.getResponse().getCookie(StaffEntry.COOKIE);
    assertNotNull(session);
    assertTrue(session.isHttpOnly());
    assertTrue(r.getResponse().getHeader("Set-Cookie").contains("SameSite=Strict"));
    mvc.perform(get("/api/admin/settings").cookie(session)).andExpect(status().isForbidden());
  }

  @Test
  @Order(4)
  void enrollmentValidatesOtpAndUnlocksSettings() throws Exception {
    var r =
        mvc.perform(write("POST", "/api/auth/mfa/enroll", Map.of()).cookie(session))
            .andExpect(status().isOk())
            .andReturn();
    secret = content(r).get("secret").asText();
    verifiedStep = System.currentTimeMillis() / 30000;
    mvc.perform(
            write(
                    "POST",
                    "/api/auth/mfa/confirm",
                    Map.of("code", crypto.totp(secret, verifiedStep)))
                .cookie(session))
        .andExpect(status().isOk());
    mvc.perform(get("/api/admin/settings").cookie(session)).andExpect(status().isOk());
    mvc.perform(write("POST", "/api/auth/mfa/enroll", Map.of()).cookie(session))
        .andExpect(status().isConflict());
  }

  Map<String, Object> change(String key, String value, long revision) {
    return Map.of("key", key, "value", value, "revision", revision, "clear", false);
  }

  @Test
  @Order(5)
  void configSavesAndPublicAllowlistDoesNotLeakSecrets() throws Exception {
    var changes =
        List.of(
            change("site.name", "万有测试站", 0), change("mail.password", "sensitive-test-secret", 0));
    mvc.perform(write("PUT", "/api/admin/settings", Map.of("changes", changes)).cookie(session))
        .andExpect(status().isOk());
    String stored =
        jdbc.queryForObject(
            "SELECT config_value FROM system_config WHERE config_key='mail.password'",
            String.class);
    assertNotEquals("sensitive-test-secret", stored);
    assertEquals("sensitive-test-secret", crypto.decrypt(stored));
    var r =
        mvc.perform(get("/api/admin/settings").cookie(session))
            .andExpect(status().isOk())
            .andReturn();
    assertFalse(r.getResponse().getContentAsString().contains("sensitive-test-secret"));
    r =
        mvc.perform(get("/api/public/site"))
            .andExpect(jsonPath("$['site.name']").value("万有测试站"))
            .andReturn();
    assertFalse(r.getResponse().getContentAsString().contains("mail.password"));
    assertEquals("sensitive-test-secret", new SecureStore(directory.toString()).decrypt(stored));
  }

  @Test
  @Order(6)
  void staleBatchRollsBackAllChanges() throws Exception {
    mvc.perform(
            write(
                    "PUT",
                    "/api/admin/settings",
                    Map.of(
                        "changes",
                        List.of(
                            change("site.description", "must-rollback", 0),
                            change("site.name", "stale", 0))))
                .cookie(session))
        .andExpect(status().isConflict());
    assertNotEquals(
        "must-rollback",
        jdbc.queryForObject(
            "SELECT config_value FROM system_config WHERE config_key='site.description'",
            String.class));
  }

  @Test
  @Order(7)
  void refusesUnsafeAndUnknownConfiguration() throws Exception {
    for (var c :
        List.of(
            change("security.admin_mfa", "false", 0),
            change("wallet.points_per_yuan", "0", 0),
            change("payment.mode", "REAL", 0),
            change("site.domain", "https://evil.test/path", 0),
            change("unknown.key", "x", 0),
            change("payment.mock_image", "javascript:alert(1)", 0))) {
      mvc.perform(
              write("PUT", "/api/admin/settings", Map.of("changes", List.of(c))).cookie(session))
          .andExpect(status().isBadRequest());
    }
  }

  @Test
  @Order(8)
  void auditContainsActorAndKeysButNoValues() throws Exception {
    var r =
        mvc.perform(get("/api/admin/audit").cookie(session)).andExpect(status().isOk()).andReturn();
    String text = r.getResponse().getContentAsString();
    assertTrue(text.contains("mail.password"));
    assertTrue(
        text.contains(
            jdbc.queryForObject(
                "SELECT nickname FROM app_user WHERE email='admin@example.com'", String.class)));
    assertFalse(text.contains("sensitive-test-secret"));
  }

  @Test
  @Order(9)
  void operatorCannotAccessAdminConfig() throws Exception {
    String id = UUID.randomUUID().toString(), token = crypto.token();
    jdbc.update(
        "INSERT INTO"
            + " app_user(id,email,password_hash,role_name,mfa_enabled,last_totp_step,created_at)"
            + " VALUES(?,?,?,'OPERATOR',TRUE,-1,?)",
        id,
        "operator@example.com",
        crypto.password(pass),
        System.currentTimeMillis());
    for (boolean staff : new boolean[] {false, true})
      jdbc.update(
          "INSERT INTO app_session(token_hash,user_id,expires_at,mfa_verified) VALUES(?,?,?,TRUE)",
          crypto.digest((staff ? "staff:" : "") + token),
          id,
          System.currentTimeMillis() + 60000);
    mvc.perform(get("/api/admin/settings").cookie(new Cookie("AIWYW_SESSION", token)))
        .andExpect(status().isForbidden());
  }

  @Test
  @Order(10)
  void uploadsRejectExecutableAndKeepRealCodesPrivate() throws Exception {
    var fake =
        new MockMultipartFile(
            "file", "evil.png", "image/png", "<script>alert(1)</script>".getBytes());
    mvc.perform(
            multipart("/api/admin/assets/payment")
                .file(fake)
                .header("X-AIWYW-Request", "1")
                .cookie(session))
        .andExpect(status().isBadRequest());
    var image = new java.awt.image.BufferedImage(8, 8, java.awt.image.BufferedImage.TYPE_INT_RGB);
    var output = new java.io.ByteArrayOutputStream();
    javax.imageio.ImageIO.write(image, "png", output);
    var file = new MockMultipartFile("file", "code.png", "image/png", output.toByteArray());
    var r =
        mvc.perform(
                multipart("/api/admin/assets/payment")
                    .file(file)
                    .header("X-AIWYW-Request", "1")
                    .cookie(session))
            .andExpect(status().isOk())
            .andReturn();
    String url = content(r).get("url").asText();
    mvc.perform(get(url)).andExpect(status().isNotFound());
    mvc.perform(get(url.replace("/public/", "/admin/")).cookie(session)).andExpect(status().isOk());
    mvc.perform(
            write(
                    "PUT",
                    "/api/admin/settings",
                    Map.of("changes", List.of(change("payment.mock_image", url, 0))))
                .cookie(session))
        .andExpect(status().isOk());
    mvc.perform(get(url))
        .andExpect(status().isOk())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                .contentType(MediaType.IMAGE_PNG));
  }

  @Test
  @Order(11)
  void totpCannotBeReplayed() throws Exception {
    mvc.perform(
            write(
                    "POST",
                    "/api/auth/login",
                    Map.of(
                        "email",
                        "admin@example.com",
                        "password",
                        pass,
                        "code",
                        crypto.totp(secret, verifiedStep)))
                .header(StaffEntry.HEADER, staffEntry.path().substring(1)))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @Order(12)
  void secretClearIsExplicitAndLogoutRevokesSession() throws Exception {
    mvc.perform(
            write(
                    "PUT",
                    "/api/admin/settings",
                    Map.of("changes", List.of(change("mail.password", "", 1))))
                .cookie(session))
        .andExpect(status().isOk());
    assertEquals(
        "sensitive-test-secret",
        crypto.decrypt(
            jdbc.queryForObject(
                "SELECT config_value FROM system_config WHERE config_key='mail.password'",
                String.class)));
    mvc.perform(
            write(
                    "PUT",
                    "/api/admin/settings",
                    Map.of(
                        "changes",
                        List.of(
                            Map.of(
                                "key",
                                "mail.password",
                                "value",
                                "",
                                "revision",
                                1,
                                "clear",
                                true))))
                .cookie(session))
        .andExpect(status().isOk());
    assertEquals(
        "",
        crypto.decrypt(
            jdbc.queryForObject(
                "SELECT config_value FROM system_config WHERE config_key='mail.password'",
                String.class)));
    mvc.perform(
            write("POST", "/api/auth/logout", Map.of())
                .cookie(session)
                .header(StaffEntry.HEADER, staffEntry.path().substring(1)))
        .andExpect(status().isOk());
    mvc.perform(get("/api/admin/settings").cookie(session)).andExpect(status().isUnauthorized());
  }

  @Test
  @Order(13)
  void accountLockSurvivesSourceChangesAndDoesNotExtendOnRetry() throws Exception {
    String id = UUID.randomUUID().toString(), email = "account-lock@example.com";
    String password = "isolated-lock-test-password";
    jdbc.update(
        "INSERT INTO"
            + " app_user(id,email,password_hash,role_name,mfa_enabled,last_totp_step,created_at)"
            + " VALUES(?,?,?,'OPERATOR',FALSE,-1,?)",
        id,
        email,
        crypto.password(password),
        System.currentTimeMillis());
    for (int attempt = 1; attempt <= 5; attempt++) {
      mvc.perform(
              write(
                      "POST",
                      "/api/auth/login",
                      Map.of("email", email, "password", "wrong", "code", ""))
                  .header("X-Forwarded-For", "198.51.100.1"))
          .andExpect(status().isUnauthorized());
    }
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM account_login_guard WHERE user_id=?", Integer.class, id));
    for (int attempt = 1; attempt <= 5; attempt++) {
      mvc.perform(
              write(
                      "POST",
                      "/api/auth/login",
                      Map.of(
                          "email",
                          " " + email.toUpperCase(Locale.ROOT) + " ",
                          "password",
                          "wrong",
                          "code",
                          ""))
                  .header(StaffEntry.HEADER, staffEntry.path().substring(1))
                  .header("X-Forwarded-For", "198.51.100." + attempt))
          .andExpect(status().is(attempt == 5 ? 429 : 401));
    }
    long until =
        jdbc.queryForObject(
            "SELECT locked_until FROM account_login_guard WHERE user_id=?", Long.class, id);
    assertTrue(until > System.currentTimeMillis());
    var attempt =
        write("POST", "/api/auth/login", Map.of("email", email, "password", password, "code", ""))
            .header(StaffEntry.HEADER, staffEntry.path().substring(1))
            .header("X-Forwarded-For", "198.51.100.10");
    mvc.perform(attempt).andExpect(status().isTooManyRequests());
    assertEquals(
        until,
        jdbc.queryForObject(
            "SELECT locked_until FROM account_login_guard WHERE user_id=?", Long.class, id));
    jdbc.update(
        "UPDATE account_login_guard SET locked_until=? WHERE user_id=?",
        System.currentTimeMillis() - 1,
        id);
    mvc.perform(attempt).andExpect(status().isOk());
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM account_login_guard WHERE user_id=?", Integer.class, id));
  }
}
