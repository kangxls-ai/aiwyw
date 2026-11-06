package com.aiwyw;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 独立安装验证奖励原子性、跨日、并发、历史快照及账户分页隐私，不连接用户真实库。 */
@SpringBootTest
@AutoConfigureMockMvc
class EncouragementTest {
  static final Path root;

  static {
    try {
      root = Files.createTempDirectory("aiwyw-encouragement-");
      var s = new SecureStore(root.toString());
      Files.writeString(
          root.resolve("bootstrap.enc"),
          s.encrypt(
              new ObjectMapper()
                  .writeValueAsString(new Database.ConnectionSpec("H2", "", 0, "", "", ""))));
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry p) {
    p.add("aiwyw.data-dir", () -> root.toString());
  }

  @Autowired Commerce wallet;
  @Autowired JdbcTemplate db;
  @Autowired SecureStore crypto;
  @Autowired Auth auth;
  @Autowired MockMvc mvc;
  @Autowired PlatformTransactionManager manager;
  @Autowired MemberRecords records;
  @Autowired Settings settings;
  @Autowired MeteredApi api;

  String user() {
    String id = UUID.randomUUID().toString();
    long now = System.currentTimeMillis();
    db.update(
        "INSERT INTO"
            + " app_user(id,email,password_hash,role_name,mfa_enabled,last_totp_step,created_at,email_verified_at,nickname,disabled)"
            + " VALUES(?,?,?,'USER',FALSE,-1,?,?,?,FALSE)",
        id,
        id + "@example.com",
        crypto.password("encouragement-fixture-123"),
        now,
        now,
        "测试用户");
    db.update("INSERT INTO access_user_role VALUES(?,'USER')", id);
    return id;
  }

  Cookie cookie(String id) {
    return new Cookie(
        "AIWYW_SESSION",
        auth.login(id + "@example.com", "encouragement-fixture-123", "", "127.0.0.1"));
  }

  long count(String table, String id) {
    return db.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE user_id=?", Long.class, id);
  }

  @Test
  void sameDayAcrossDevicesAwardsExactlyOnce() throws Exception {
    String id = user();
    var executor = Executors.newFixedThreadPool(6);
    try {
      var calls = new ArrayList<Callable<Boolean>>();
      for (int i = 0; i < 12; i++) calls.add(() -> wallet.dailyLogin(id, Instant.now()));
      int awarded = 0;
      for (var result : executor.invokeAll(calls)) if (result.get()) awarded++;
      assertEquals(1, awarded);
    } finally {
      executor.shutdownNow();
    }
    assertEquals("1.00", wallet.balanceSummary(id).get("balance"));
    assertEquals(1, count("daily_login_reward", id));
    assertEquals(1, count("wallet_batch", id));
    assertEquals(1, count("wallet_ledger", id));
    var batch = db.queryForMap("SELECT * FROM wallet_batch WHERE user_id=?", id);
    assertEquals(0L, ((Number) batch.get("cost_fen")).longValue());
    assertNull(batch.get("expires_at"));
  }

  @Test
  void beijingMidnightIsTheOnlyDailyBoundary() {
    String id = user();
    assertTrue(wallet.dailyLogin(id, Instant.parse("2026-11-05T15:59:59Z")));
    assertFalse(wallet.dailyLogin(id, Instant.parse("2026-11-05T15:59:59Z")));
    assertTrue(wallet.dailyLogin(id, Instant.parse("2026-11-05T16:00:00Z")));
    assertEquals("2.00", wallet.balanceSummary(id).get("balance"));
  }

  @Test
  void rollbackLeavesNoClaimBatchOrLedgerAndCanRetry() {
    String id = user();
    new TransactionTemplate(manager)
        .executeWithoutResult(
            s -> {
              assertTrue(wallet.dailyLogin(id, Instant.now()));
              s.setRollbackOnly();
            });
    assertEquals(0, count("daily_login_reward", id));
    assertEquals(0, count("wallet_batch", id));
    assertEquals(0, count("wallet_ledger", id));
    assertEquals("0.00", wallet.balanceSummary(id).get("balance"));
    assertTrue(wallet.dailyLogin(id, Instant.now()));
  }

  @Test
  void wrongPasswordCannotEarnButSuccessfulLoginAndSessionRestorationCan() throws Exception {
    String id = user();
    var json = new ObjectMapper();
    mvc.perform(
            post("/api/auth/login")
                .header("X-AIWYW-Request", "1")
                .contentType("application/json")
                .content(
                    json.writeValueAsBytes(
                        Map.of("email", id + "@example.com", "password", "wrong", "code", ""))))
        .andExpect(status().isUnauthorized());
    assertEquals(0, count("daily_login_reward", id));
    var result =
        mvc.perform(
                post("/api/auth/login")
                    .header("X-AIWYW-Request", "1")
                    .contentType("application/json")
                    .content(
                        json.writeValueAsBytes(
                            Map.of(
                                "email",
                                id + "@example.com",
                                "password",
                                "encouragement-fixture-123",
                                "code",
                                ""))))
            .andExpect(status().isOk())
            .andReturn();
    String value =
        result
            .getResponse()
            .getHeader("Set-Cookie")
            .split(";", 2)[0]
            .substring("AIWYW_SESSION=".length());
    mvc.perform(get("/api/auth/session").cookie(new Cookie("AIWYW_SESSION", value)))
        .andExpect(status().isOk());
    assertEquals("1.00", wallet.balanceSummary(id).get("balance"));
    String other = user();
    mvc.perform(get("/api/auth/session").cookie(cookie(other))).andExpect(status().isOk());
    assertEquals("1.00", wallet.balanceSummary(other).get("balance"));
  }

  @Test
  void futureDonationsUseTenAndOldOrderAmountsStayUntouched() {
    String id = user();
    var old =
        wallet.createRecharge(
            id, new Commerce.Recharge(new BigDecimal("5"), null, "historic-order-fixture"));
    db.update("UPDATE recharge_order SET points_units=10000,rate=20 WHERE id=?", old.get("id"));
    db.update(
        "UPDATE system_config SET config_value='20' WHERE config_key='wallet.points_per_yuan'");
    db.update("DELETE FROM schema_migration WHERE version='V013_ENCOURAGEMENT'");
    EncouragementSchema.migrate(db);
    assertEquals("10", settings.value("wallet.points_per_yuan"));
    assertEquals(
        10000L,
        db.queryForObject(
            "SELECT points_units FROM recharge_order WHERE id=?", Long.class, old.get("id")));
    var fresh =
        wallet.createRecharge(
            id, new Commerce.Recharge(new BigDecimal("5"), null, "new-donation-fixture"));
    assertEquals("5000", fresh.get("points_units"));
    String pack =
        wallet.savePackage(
            null,
            new Commerce.PackageInput(
                "捐赠档位", "Donation", new BigDecimal("2.50"), new BigDecimal("999"), 30, true, 0, 0),
            "fixture-admin");
    var bundled =
        wallet.createRecharge(id, new Commerce.Recharge(null, pack, "new-package-fixture"));
    assertEquals("2500", bundled.get("points_units"));
    assertThrows(
        Api.Failure.class,
        () ->
            settings.save(
                List.of(new Settings.Change("wallet.points_per_yuan", "20", 0, false)),
                "fixture-admin"));
  }

  @Test
  void notificationSearchAndPaginationCannotCrossAccounts() throws Exception {
    String id = user(), other = user();
    for (int i = 0; i < 23; i++)
      db.update(
          "INSERT INTO member_notification(id,user_id,title,body,created_at) VALUES(?,?,?,?,?)",
          UUID.randomUUID().toString(),
          id,
          "匹配通知" + i,
          "正文",
          1000L + i);
    db.update(
        "INSERT INTO member_notification(id,user_id,title,body,created_at) VALUES(?,?,?,?,?)",
        UUID.randomUUID().toString(),
        other,
        "其他用户秘密",
        "PRIVATE",
        3000L);
    var c = cookie(id);
    mvc.perform(
            get("/api/member/records/notifications")
                .cookie(c)
                .param("q", "匹配通知")
                .param("page", "2")
                .param("pageSize", "10"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(23))
        .andExpect(jsonPath("$.items.length()").value(10));
    mvc.perform(get("/api/member/records/notifications").cookie(c).param("q", "其他用户秘密"))
        .andExpect(jsonPath("$.total").value(0));
    mvc.perform(get("/api/member/api/records/keys")).andExpect(status().isUnauthorized());
  }

  @Test
  void apiAndKeyPagesSearchAllRowsButNeverAnotherUsersKeys() throws Exception {
    String id = user(), other = user(), scope = "";
    String prefix = UUID.randomUUID().toString().substring(0, 8);
    for (int i = 0; i < 13; i++)
      scope =
          api.saveDefinition(
              null,
              new MeteredApi.Definition(
                  "paged-" + prefix + "-" + i,
                  "分页验证接口" + i,
                  "Paged API",
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
              "fixture-admin");
    for (int i = 0; i < 12; i++)
      api.saveKey(
          id, null, new MeteredApi.KeyInput("我的分页Key" + i, List.of(scope), 60, 0, null, true, 0));
    String secret =
        (String)
            api.saveKey(
                    other,
                    null,
                    new MeteredApi.KeyInput("其他账户秘密Key", List.of(scope), 60, 0, null, true, 0))
                .get("token");
    mvc.perform(
            get("/api/public/api-records/apis")
                .param("q", "分页验证接口")
                .param("page", "2")
                .param("pageSize", "10"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(13))
        .andExpect(jsonPath("$.items.length()").value(3));
    var own = cookie(id);
    var response =
        mvc.perform(
                get("/api/member/api/records/keys")
                    .cookie(own)
                    .param("page", "2")
                    .param("pageSize", "10")
                    .param("user_id", other))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(12))
            .andExpect(jsonPath("$.items.length()").value(2))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertFalse(response.contains(secret));
    assertFalse(response.contains("token_hash"));
    mvc.perform(get("/api/member/api/records/keys").cookie(own).param("q", "其他账户秘密Key"))
        .andExpect(jsonPath("$.total").value(0));
  }
}
