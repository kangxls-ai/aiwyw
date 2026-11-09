package com.aiwyw;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;

/** 每次独立数据目录；验证来源、删除边界、统计窗口和旧授权迁移，不更换真实安装主密钥。 */
@SpringBootTest
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(StaffMockMvc.class)
class ManagementTest {
  static final Path root;

  static {
    try {
      root = Files.createTempDirectory("aiwyw-management-");
      String port = System.getProperty("aiwyw.test.mysql-port"),
          credentials = System.getProperty("aiwyw.test.mysql-credentials");
      if (port != null || credentials != null) {
        var store = new SecureStore(root.toString());
        Files.writeString(
            root.resolve("bootstrap.enc"),
            store.encrypt(
                new ObjectMapper()
                    .writeValueAsString(
                        credentials != null
                            ? LocalMysqlMigration.mysql(
                                Path.of(credentials),
                                System.getProperty("aiwyw.test.mysql-database"))
                            : new Database.ConnectionSpec(
                                "MYSQL",
                                "127.0.0.1",
                                Integer.parseInt(port),
                                System.getProperty("aiwyw.test.mysql-database"),
                                "root",
                                ""))));
      }
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry p) {
    p.add("aiwyw.data-dir", () -> root.toString());
  }

  @Autowired JdbcTemplate db;
  @Autowired SecureStore crypto;
  @Autowired Settings settings;
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired OperationsStats stats;
  @Autowired javax.sql.DataSource source;

  String user(String role, boolean verified) {
    String id = UUID.randomUUID().toString();
    long now = System.currentTimeMillis();
    db.update(
        "INSERT INTO"
            + " app_user(id,email,password_hash,role_name,mfa_enabled,last_totp_step,created_at,email_verified_at,nickname,disabled)"
            + " VALUES(?,?,?,?,FALSE,-1,?,?,?,FALSE)",
        id,
        id + "@example.com",
        crypto.password("management-fixture-password-123"),
        role,
        now,
        verified ? now : null,
        "验收" + id.substring(0, 6));
    db.update("INSERT INTO access_user_role VALUES(?,?)", id, role);
    return id;
  }

  Cookie session(String id) {
    String token = crypto.token();
    for (boolean staff : new boolean[] {false, true})
      db.update(
          "INSERT INTO app_session VALUES(?,?,?,TRUE)",
          crypto.digest((staff ? "staff:" : "") + token),
          id,
          System.currentTimeMillis() + 3600000);
    return new Cookie("AIWYW_SESSION", token);
  }

  @Test
  void sourceIpRejectsSpoofingAndSelectsRightmostUntrustedHop() {
    var request = new MockHttpServletRequest();
    request.setRemoteAddr("198.51.100.20");
    request.addHeader("X-Forwarded-For", "8.8.8.8");
    assertEquals("198.51.100.20", RequestIp.resolve(request, settings));
    request.setRemoteAddr("127.0.0.1");
    request.removeHeader("X-Forwarded-For");
    request.addHeader("X-Forwarded-For", "8.8.8.8, 198.51.100.20, 127.0.0.1");
    assertEquals("198.51.100.20", RequestIp.resolve(request, settings));
    request.removeHeader("X-Forwarded-For");
    request.addHeader("X-Forwarded-For", "example.com");
    assertEquals("127.0.0.1", RequestIp.resolve(request, settings));
    assertNull(RequestIp.numeric("deadbeef"));
    assertNull(RequestIp.numeric("localhost"));
    request.addHeader("X-AIWYW-Peer-IP", "127.0.0.1");
    request.removeHeader("X-Forwarded-For");
    request.addHeader("X-Forwarded-For", "203.0.113.45");
    assertEquals("127.0.0.1", RequestIp.resolve(request, settings));
  }

  @Test
  void realAdminMutationRecordsIpAndCreationAndLaterEditDoesNotReplaceCreator() throws Exception {
    String admin = user("ADMIN", true),
        other = user("ADMIN", true),
        id = UUID.randomUUID().toString();
    mvc.perform(
            put("/api/admin/roles/" + id)
                .cookie(session(admin))
                .header("X-AIWYW-Request", "1")
                .with(
                    r -> {
                      r.addHeader("X-Forwarded-For", "198.51.100.21");
                      return r;
                    })
                .contentType("application/json")
                .content("{\"name\":\"来源验收角色\",\"menus\":[\"applications\"],\"revision\":0}"))
        .andExpect(status().isOk());
    mvc.perform(
            put("/api/admin/roles/" + id)
                .cookie(session(other))
                .header("X-AIWYW-Request", "1")
                .contentType("application/json")
                .content("{\"name\":\"来源验收角色二\",\"menus\":[\"applications\"],\"revision\":1}"))
        .andExpect(status().isOk());
    assertEquals(
        admin,
        db.queryForObject(
            "SELECT created_by FROM object_origin WHERE object_type='access_role' AND object_id=?",
            String.class,
            id));
    assertEquals(
        "198.51.100.21",
        db.queryForObject(
            "SELECT source_ip FROM finance_audit WHERE actor_id=? AND entity_id=?",
            String.class,
            admin,
            id));
    var response =
        mvc.perform(get("/api/admin/roles").cookie(session(admin)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();
    var rows = json.readTree(response.getContentAsString()).get("items");
    var row =
        java.util.stream.StreamSupport.stream(rows.spliterator(), false)
            .filter(n -> n.get("id").asText().equals(id))
            .findFirst()
            .orElseThrow();
    assertEquals(admin, row.get("created_by_id").asText());
    assertTrue(row.get("created_at").asLong() > 0);
    mvc.perform(get("/api/admin/audit").cookie(session(admin))).andExpect(status().isOk());
  }

  @Test
  void unverifiedFilterIncludesRegistrationAndDeletionRevokesCredentials() throws Exception {
    String admin = user("ADMIN", true),
        pending = user("USER", false),
        verified = user("USER", true);
    var oldSession = session(pending);
    db.update(
        "INSERT INTO account_action_token(token_hash,user_id,purpose,expires_at,created_at)"
            + " VALUES(?,?,'VERIFY',?,?)",
        crypto.digest("deletion-token"),
        pending,
        System.currentTimeMillis() + 60000,
        System.currentTimeMillis());
    var result =
        mvc.perform(get("/api/admin/accounts").param("state", "UNVERIFIED").cookie(session(admin)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();
    String body = result.getContentAsString();
    assertTrue(body.contains(pending));
    assertFalse(body.contains(verified));
    mvc.perform(
            delete("/api/admin/accounts/" + pending)
                .cookie(session(admin))
                .header("X-AIWYW-Request", "1"))
        .andExpect(status().isOk());
    assertEquals(
        0, db.queryForObject("SELECT COUNT(*) FROM app_user WHERE id=?", Integer.class, pending));
    assertEquals(
        0,
        db.queryForObject(
            "SELECT COUNT(*) FROM account_action_token WHERE user_id=?", Integer.class, pending));
    mvc.perform(get("/api/member/profile").cookie(oldSession)).andExpect(status().isUnauthorized());
    mvc.perform(
            delete("/api/admin/accounts/" + verified)
                .cookie(session(admin))
                .header("X-AIWYW-Request", "1"))
        .andExpect(status().isConflict());
  }

  @Test
  void deletionProtectsStaffAndFinancialHistoryAndNeedsAdmin() throws Exception {
    String admin = user("ADMIN", true),
        staff = user("FINANCE", false),
        pending = user("USER", false);
    mvc.perform(
            delete("/api/admin/accounts/" + staff)
                .cookie(session(admin))
                .header("X-AIWYW-Request", "1"))
        .andExpect(status().isConflict());
    db.update("INSERT INTO wallet_account VALUES(?,'SIMULATED',0)", pending);
    db.update(
        "INSERT INTO wallet_ledger VALUES(?,?,'SIMULATED','EXPIRE',?,-100,0,0,?)",
        UUID.randomUUID().toString(),
        pending,
        UUID.randomUUID().toString(),
        System.currentTimeMillis());
    mvc.perform(
            delete("/api/admin/accounts/" + pending)
                .cookie(session(admin))
                .header("X-AIWYW-Request", "1"))
        .andExpect(status().isConflict());
    mvc.perform(
            delete("/api/admin/accounts/" + pending)
                .cookie(session(user("USER", true)))
                .header("X-AIWYW-Request", "1"))
        .andExpect(status().isForbidden());
    assertEquals(
        1, db.queryForObject("SELECT COUNT(*) FROM app_user WHERE id=?", Integer.class, pending));
  }

  @Test
  void activeUsersDrillUsesSameWindowDistinctUsersAndIncludesDeletedHistory() throws Exception {
    String first = user("USER", true),
        second = user("USER", true),
        deleted = UUID.randomUUID().toString();
    var window = OperationsStats.window("2026-02-02", "2026-02-02");
    for (String id : List.of(first, first, second, deleted)) {
      String random = UUID.randomUUID().toString();
      db.update(
          "INSERT INTO analytics_event VALUES(?,?,?,'PAGE','/apps','',?,'2026-02-02',?,?)",
          random,
          crypto.digest(random),
          id,
          "direct",
          crypto.digest("dedupe" + random),
          window.start() + 1000);
    }
    String random = UUID.randomUUID().toString();
    db.update(
        "INSERT INTO analytics_event VALUES(?,?,?,'PAGE','/apps','','direct','2026-02-03',?,?)",
        random,
        crypto.digest(random),
        user("USER", true),
        crypto.digest(random),
        window.end());
    var report = stats.report("2026-02-02", "2026-02-02", "SIMULATED");
    var traffic = (Map<?, ?>) report.get("traffic");
    var drill = stats.activeUsers("2026-02-02", "2026-02-02", 1, 2, "pages", "descending");
    assertEquals(3L, ((Number) traffic.get("active_users")).longValue());
    assertEquals(3L, drill.get("total"));
    assertEquals(2, ((List<?>) drill.get("items")).size());
    assertEquals(
        400,
        assertThrows(
                Api.Failure.class,
                () ->
                    stats.activeUsers(
                        "2026-02-02", "2026-02-02", 1, 20, "DROP TABLE app_user", "descending"))
            .status);
    mvc.perform(get("/api/admin/statistics/active-users").cookie(session(user("USER", true))))
        .andExpect(status().isForbidden());
  }

  @Test
  void legacyApplicationPermissionsMergeOnlyOnce() throws Exception {
    String role = UUID.randomUUID().toString();
    db.update(
        "INSERT INTO access_role VALUES(?,? ,FALSE,1,?)",
        role,
        "旧版本管理角色",
        System.currentTimeMillis());
    db.update("INSERT INTO access_role_menu VALUES(?,'versions')", role);
    db.update("DELETE FROM schema_migration WHERE version='V009_MERGED_APPLICATIONS'");
    Identity.schema(source, db);
    assertEquals(
        1,
        db.queryForObject(
            "SELECT COUNT(*) FROM access_role_menu WHERE role_id=? AND menu_id='applications'",
            Integer.class,
            role));
    assertEquals(
        0,
        db.queryForObject(
            "SELECT COUNT(*) FROM access_role_menu WHERE menu_id IN ('versions','publishing')",
            Integer.class));
    db.update("DELETE FROM access_role_menu WHERE role_id=?", role);
    Identity.schema(source, db);
    assertEquals(
        0,
        db.queryForObject(
            "SELECT COUNT(*) FROM access_role_menu WHERE role_id=?", Integer.class, role));
    db.update("INSERT INTO access_role_menu VALUES(?,'sections')", role);
    String editor = user("USER", true);
    db.update("DELETE FROM access_user_role WHERE user_id=?", editor);
    db.update("INSERT INTO access_user_role VALUES(?,?)", editor, role);
    var cookie = session(editor);
    mvc.perform(get("/api/admin/catalog/products/options").cookie(cookie))
        .andExpect(status().isOk());
    mvc.perform(get("/api/admin/catalog/products/facets").cookie(cookie))
        .andExpect(status().isOk());
    mvc.perform(get("/api/admin/catalog/products").cookie(cookie))
        .andExpect(status().isForbidden());
  }
}
