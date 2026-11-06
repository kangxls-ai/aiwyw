package com.aiwyw;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.*;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

/** 独立目录/可选独立MySQL库。内置API做真实计算；失败/阻塞上游以受控测试替身验证事务，不冒充云商联调。 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OperationsTest {
  static final Path directory;

  static {
    try {
      directory = Files.createTempDirectory("aiwyw-operations-");
      String port = System.getProperty("aiwyw.test.mysql-port");
      if (port != null) {
        var c = new SecureStore(directory.toString());
        var spec =
            new Database.ConnectionSpec(
                "MYSQL",
                "127.0.0.1",
                Integer.parseInt(port),
                System.getProperty("aiwyw.test.mysql-database", "aiwyw_operations_validation"),
                "root",
                "");
        Files.writeString(
            directory.resolve("bootstrap.enc"),
            c.encrypt(new ObjectMapper().writeValueAsString(spec)));
      }
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry p) {
    p.add("aiwyw.data-dir", () -> directory.toString());
  }

  @Autowired MeteredApi api;
  @Autowired SupportService support;
  @Autowired OperationsStats stats;
  @Autowired Commerce wallet;
  @Autowired JdbcTemplate db;
  @Autowired SecureStore crypto;
  @Autowired ObjectMapper json;
  @Autowired Settings settings;
  @Autowired Storage storage;
  @Autowired MockMvc mvc;
  @MockitoSpyBean ApiUpstream upstream;
  String admin;

  @BeforeAll
  void initialize() {
    admin = user("ADMIN", true);
  }

  String id() {
    return UUID.randomUUID().toString();
  }

  String user(String role, boolean verified) {
    String u = id();
    long now = System.currentTimeMillis();
    db.update(
        "INSERT INTO"
            + " app_user(id,email,password_hash,role_name,mfa_enabled,last_totp_step,created_at,email_verified_at,nickname,disabled)"
            + " VALUES(?,?,?,?,FALSE,-1,?,?,?,FALSE)",
        u,
        u + "@example.com",
        crypto.password("operations-test-password-123"),
        role,
        now,
        verified ? now : null,
        "读者" + u.substring(0, 5));
    db.update("INSERT INTO access_user_role VALUES(?,?)", u, role);
    return u;
  }

  Auth.User actor(String u, String role) {
    return new Auth.User(u, u + "@example.com", role, true);
  }

  Cookie cookie(String u) {
    String token = crypto.token();
    db.update(
        "INSERT INTO app_session VALUES(?,?,?,TRUE)",
        crypto.digest(token),
        u,
        System.currentTimeMillis() + 3600000L);
    return new Cookie("AIWYW_SESSION", token);
  }

  void credit(String user, long units, long fen) {
    db.update("INSERT INTO wallet_account VALUES(?,?,?)", user, Commerce.MODE, units);
    db.update(
        "INSERT INTO"
            + " wallet_batch(id,user_id,mode,source_type,source_id,total_units,remaining_units,cost_fen,remaining_cost_fen,expires_at,created_at)"
            + " VALUES(?,?,?,'RECHARGE',?,?,?,?,?,NULL,?)",
        id(),
        user,
        Commerce.MODE,
        id(),
        units,
        units,
        fen,
        fen,
        System.currentTimeMillis());
  }

  long balance(String user) {
    return new BigDecimal((String) wallet.summary(user).get("balance"))
        .movePointRight(2)
        .longValueExact();
  }

  String definition(String price) {
    return api.saveDefinition(
        null,
        new MeteredApi.Definition(
            "api-" + id(),
            "文本统计",
            "Text statistics",
            "输入text字符串",
            "Input text",
            "TEXT_STATS",
            "",
            "",
            false,
            "",
            "",
            new BigDecimal(price),
            true,
            0),
        admin);
  }

  String slug(String apiId) {
    return db.queryForObject("SELECT slug FROM api_definition WHERE id=?", String.class, apiId);
  }

  Map<String, Object> key(String user, String apiId, long daily) {
    return api.saveKey(
        user, null, new MeteredApi.KeyInput("调用Key", List.of(apiId), 60, daily, null, true, 0));
  }

  JsonNode input(String text) {
    return json.valueToTree(Map.of("text", text));
  }

  String plan(String apiId, int quota) {
    return api.savePlan(
        null,
        new MeteredApi.Plan(apiId, "月度额度", new BigDecimal("2.00"), quota, 30, true, 0),
        admin);
  }

  String buy(String user, String plan) {
    return api.buy(
        user,
        new MeteredApi.Purchase(
            plan,
            db.queryForObject("SELECT revision FROM api_plan WHERE id=?", Long.class, plan),
            id()));
  }

  SupportService.Principal guest() {
    String token = crypto.token();
    return new SupportService.Principal(null, support.newVisitor(token), "游客");
  }

  String conversation(SupportService.Principal p) {
    return support.create(p, new SupportService.NewConversation("工具使用问题", "GENERAL", "", false));
  }

  long revision(String conversation) {
    return db.queryForObject(
        "SELECT revision FROM support_conversation WHERE id=?", Long.class, conversation);
  }

  void set(String name, String value) {
    var e =
        settings.list().stream()
            .filter(x -> x.definition().key().equals(name))
            .findFirst()
            .orElseThrow();
    settings.save(List.of(new Settings.Change(name, value, e.revision(), false)), admin);
  }

  void stub(String apiId, org.mockito.stubbing.Answer<?> answer) throws Exception {
    doAnswer(answer)
        .when(upstream)
        .run(
            argThat(a -> a != null && a.get("id").equals(apiId)),
            any(JsonNode.class),
            anyInt(),
            anyInt(),
            any(SecureStore.class));
  }

  @Test
  void builtinCalculationKeyIsOneTimeAndPublicCallerNeedsVerifiedOwner() throws Exception {
    String user = user("USER", true), a = definition("0.00");
    var k = key(user, a, 0);
    String token = (String) k.get("token"), kid = (String) k.get("id");
    var r = api.invoke(user, kid, slug(a), id(), input("Hello world"), true);
    assertEquals(11, ((JsonNode) r.get("data")).get("characters").asInt());
    assertEquals("0.00", r.get("chargedPoints"));
    assertFalse(api.encode(api.keys(user)).contains(token));
    assertFalse(
        db.queryForObject("SELECT token_hash FROM api_key WHERE id=?", String.class, kid)
            .equals(token));
    mvc.perform(
            post("/api/v1/invoke/" + slug(a))
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", id())
                .contentType("application/json")
                .content("{\"text\":\"real call\"}"))
        .andExpect(status().isOk());
    mvc.perform(
            post("/api/v1/invoke/" + slug(a))
                .contentType("application/json")
                .content("{\"text\":\"x\"}"))
        .andExpect(status().isUnauthorized());
    String unverified = user("USER", false);
    assertThrows(Api.Failure.class, () -> key(unverified, a, 0));
    assertThrows(
        Api.Failure.class,
        () -> api.invoke(user("USER", true), kid, slug(a), id(), input("x"), false));
  }

  @Test
  void pointBillingIdempotencyAndCostAreAtomic() {
    String user = user("USER", true), a = definition("1.25");
    credit(user, 10000, 1000);
    api.preference(user, a, true, false);
    String k = (String) key(user, a, 0).get("id"), request = id();
    var r = api.invoke(user, k, slug(a), request, input("same"), false);
    assertEquals(
        json.valueToTree(r),
        json.valueToTree(api.invoke(user, k, slug(a), request, input("same"), false)));
    assertEquals(9875, balance(user));
    assertEquals(
        1, db.queryForObject("SELECT COUNT(*) FROM api_call WHERE user_id=?", Integer.class, user));
    assertEquals(
        12,
        db.queryForObject(
            "SELECT cost_fen FROM wallet_hold WHERE id=?", Long.class, r.get("requestId")));
    assertEquals(
        409,
        assertThrows(
                Api.Failure.class,
                () -> api.invoke(user, k, slug(a), request, input("changed"), false))
            .status);
  }

  @Test
  void parallelSameRequestAndSharedQuotaAcrossKeysCannotDoubleSpend() throws Exception {
    String user = user("USER", true), a = definition("1.00");
    credit(user, 10000, 1000);
    String sub = buy(user, plan(a, 1)),
        k1 = (String) key(user, a, 0).get("id"),
        k2 = (String) key(user, a, 0).get("id");
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    stub(
        a,
        x -> {
          entered.countDown();
          assertTrue(release.await(10, TimeUnit.SECONDS));
          return input("done");
        });
    ExecutorService executor = Executors.newSingleThreadExecutor();
    String request = id();
    try {
      var first = executor.submit(() -> api.invoke(user, k1, slug(a), request, input("x"), false));
      assertTrue(entered.await(10, TimeUnit.SECONDS));
      assertEquals(
          1,
          db.queryForObject("SELECT held_count FROM api_subscription WHERE id=?", Long.class, sub));
      assertEquals(
          402,
          assertThrows(
                  Api.Failure.class, () -> api.invoke(user, k2, slug(a), id(), input("x"), false))
              .status);
      assertEquals(
          409,
          assertThrows(
                  Api.Failure.class,
                  () -> api.invoke(user, k2, slug(a), request, input("x"), false))
              .status);
      release.countDown();
      var result = first.get(10, TimeUnit.SECONDS);
      assertEquals(
          json.valueToTree(result),
          json.valueToTree(api.invoke(user, k2, slug(a), request, input("x"), false)));
      assertEquals(
          1,
          db.queryForObject("SELECT used_count FROM api_subscription WHERE id=?", Long.class, sub));
      assertEquals(
          0,
          db.queryForObject("SELECT held_count FROM api_subscription WHERE id=?", Long.class, sub));
      assertEquals(9800, balance(user));
    } finally {
      release.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void failedAndTimedOutCallsReleasePointsAndQuota() throws Exception {
    String user = user("USER", true), a = definition("3.00");
    credit(user, 10000, 1000);
    api.preference(user, a, true, false);
    String k = (String) key(user, a, 0).get("id");
    stub(
        a,
        x -> {
          throw new java.net.SocketTimeoutException();
        });
    assertEquals(
        504,
        assertThrows(Api.Failure.class, () -> api.invoke(user, k, slug(a), id(), input("x"), false))
            .status);
    assertEquals(10000, balance(user));
    String sub = buy(user, plan(a, 2));
    assertThrows(Api.Failure.class, () -> api.invoke(user, k, slug(a), id(), input("x"), true));
    assertEquals(
        0,
        db.queryForObject(
            "SELECT used_count+held_count FROM api_subscription WHERE id=?", Long.class, sub));
    assertEquals(
        0,
        db.queryForObject(
            "SELECT COUNT(*) FROM api_call WHERE user_id=? AND response_enc IS NOT NULL",
            Integer.class,
            user));
  }

  @Test
  void interruptedWorkerBecomesUnknownReleasesHoldAndNeverReplays() throws Exception {
    String user = user("USER", true), a = definition("2.00");
    credit(user, 10000, 1000);
    api.preference(user, a, true, false);
    String k = (String) key(user, a, 0).get("id"), request = id();
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    AtomicInteger invoked = new AtomicInteger();
    stub(
        a,
        x -> {
          invoked.incrementAndGet();
          entered.countDown();
          release.await(10, TimeUnit.SECONDS);
          return input("late");
        });
    var executor = Executors.newSingleThreadExecutor();
    try {
      var running = executor.submit(() -> api.invoke(user, k, slug(a), request, input("x"), false));
      assertTrue(entered.await(10, TimeUnit.SECONDS));
      db.update("UPDATE api_call SET deadline_at=0 WHERE user_id=?", user);
      api.sweep();
      assertEquals(10000, balance(user));
      release.countDown();
      assertThrows(ExecutionException.class, () -> running.get(10, TimeUnit.SECONDS));
      assertEquals(
          "UNKNOWN",
          db.queryForObject("SELECT state FROM api_call WHERE user_id=?", String.class, user));
      assertThrows(
          Api.Failure.class, () -> api.invoke(user, k, slug(a), request, input("x"), false));
      assertEquals(1, invoked.get());
    } finally {
      release.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void renewalKeepsOldTermsAndStartsAtPreviousExpiry() {
    String user = user("USER", true), a = definition("1.00");
    credit(user, 10000, 1000);
    String p = plan(a, 2), first = buy(user, p);
    api.savePlan(p, new MeteredApi.Plan(a, "新套餐", new BigDecimal("4.00"), 5, 7, true, 1), admin);
    String next = buy(user, p);
    assertEquals(
        db.queryForObject("SELECT end_at FROM api_subscription WHERE id=?", Long.class, first),
        db.queryForObject("SELECT start_at FROM api_subscription WHERE id=?", Long.class, next));
    assertEquals(
        200,
        db.queryForObject(
            "SELECT price_units FROM api_subscription WHERE id=?", Long.class, first));
    assertEquals(
        400,
        db.queryForObject("SELECT price_units FROM api_subscription WHERE id=?", Long.class, next));
    assertEquals(
        2, db.queryForObject("SELECT quota FROM api_subscription WHERE id=?", Long.class, first));
  }

  @Test
  void unusedPlanRefundPausesCallsRejectsSelfReviewAndRevokesOnce() {
    String user = user("USER", true), a = definition("1.00");
    credit(user, 10000, 1000);
    String sub = buy(user, plan(a, 3)), k = (String) key(user, a, 0).get("id");
    api.requestRefund(user, sub, "不再需要");
    assertThrows(Api.Failure.class, () -> api.invoke(user, k, slug(a), id(), input("x"), false));
    long rev =
        db.queryForObject("SELECT revision FROM api_subscription WHERE id=?", Long.class, sub);
    assertThrows(
        Api.Failure.class, () -> api.reviewRefund(actor(user, "ADMIN"), sub, true, "自审", rev));
    api.reviewRefund(actor(admin, "ADMIN"), sub, true, "未使用，整单退积分", rev);
    api.reviewRefund(actor(admin, "ADMIN"), sub, true, "重复同意不重复退款", rev);
    assertEquals(10000, balance(user));
    assertEquals(
        "REFUNDED",
        db.queryForObject("SELECT state FROM api_subscription WHERE id=?", String.class, sub));
  }

  @Test
  void usedPlanCannotRefundAndOverageRequiresExplicitOptIn() {
    String user = user("USER", true), a = definition("0.50");
    credit(user, 10000, 1000);
    String sub = buy(user, plan(a, 1)), k = (String) key(user, a, 0).get("id");
    api.invoke(user, k, slug(a), id(), input("first"), false);
    assertThrows(Api.Failure.class, () -> api.requestRefund(user, sub, "已经使用"));
    assertEquals(
        402,
        assertThrows(
                Api.Failure.class, () -> api.invoke(user, k, slug(a), id(), input("next"), false))
            .status);
    api.preference(user, a, false, true);
    api.invoke(user, k, slug(a), id(), input("overage"), false);
    assertEquals(9750, balance(user));
    db.update(
        "UPDATE api_subscription SET end_at=? WHERE id=?", System.currentTimeMillis() - 1, sub);
    assertEquals(
        402,
        assertThrows(
                Api.Failure.class,
                () -> api.invoke(user, k, slug(a), id(), input("expired"), false))
            .status);
  }

  @Test
  void keyDailyScopeAndRevocationAreEnforced() {
    String user = user("USER", true), a = definition("0.00"), other = definition("0.00");
    var key = key(user, a, 1);
    String k = (String) key.get("id");
    api.invoke(user, k, slug(a), id(), input("1"), false);
    assertEquals(
        429,
        assertThrows(Api.Failure.class, () -> api.invoke(user, k, slug(a), id(), input("2"), false))
            .status);
    assertEquals(
        403,
        assertThrows(
                Api.Failure.class, () -> api.invoke(user, k, slug(other), id(), input("x"), false))
            .status);
    api.saveKey(user, k, new MeteredApi.KeyInput("禁用", List.of(a), 60, 1, null, false, 1));
    assertEquals(
        401,
        assertThrows(Api.Failure.class, () -> api.invoke(user, k, slug(a), id(), input("x"), false))
            .status);
    assertThrows(Api.Failure.class, () -> api.deleteKey(user, k, 2));
  }

  @Test
  void upstreamPolicyRejectsLocalNetworksAndCredentialEcho() throws Exception {
    for (String ip :
        List.of(
            "127.0.0.1",
            "10.0.0.1",
            "169.254.169.254",
            "100.64.0.1",
            "::1",
            "fc00::1",
            "64:ff9b::7f00:1"))
      assertFalse(ApiUpstream.publicAddress(java.net.InetAddress.getByName(ip)));
    assertThrows(Api.Failure.class, () -> ApiUpstream.endpoint("http://example.com"));
    assertThrows(Api.Failure.class, () -> ApiUpstream.endpoint("https://user:pass@example.com"));
    assertTrue(ApiUpstream.publicAddress(java.net.InetAddress.getByName("8.8.8.8")));
    String a =
        api.saveDefinition(
            null,
            new MeteredApi.Definition(
                "remote-" + id(),
                "上游",
                "",
                "JSON成功字段",
                "",
                "HTTPS_JSON",
                "https://example.com/api",
                "upstream-test-secret",
                false,
                "/success",
                "true",
                BigDecimal.ZERO,
                true,
                0),
            admin);
    String encoded = api.encode(api.definitions(true));
    assertFalse(encoded.contains("upstream-test-secret"));
    assertTrue(encoded.contains("secret_set"));
    assertEquals(
        502,
        assertThrows(
                Api.Failure.class,
                () ->
                    upstream.run(
                        Map.of("adapter", "HTTPS_JSON", "endpoint", "https://127.0.0.1/x"),
                        input("x"),
                        1,
                        1024,
                        crypto))
            .status);
  }

  @Test
  void guestHistoryIsolationRechargeLoginAndLaterAccountBinding() {
    var g = guest();
    String c = conversation(g);
    support.send(g, c, null, new SupportService.Message(id(), "私有咨询", ""));
    assertThrows(Api.Failure.class, () -> support.messages(guest(), c, null, 0, 0));
    assertEquals(
        401,
        assertThrows(
                Api.Failure.class,
                () ->
                    support.create(
                        g, new SupportService.NewConversation("充值", "RECHARGE", "", false)))
            .status);
    String user = user("USER", true);
    support.bind(user, g.visitor());
    assertThrows(Api.Failure.class, () -> support.messages(g, c, null, 0, 0));
    assertEquals(
        1,
        ((List<?>)
                support
                    .messages(new SupportService.Principal(user, g.visitor(), "读者"), c, null, 0, 0)
                    .get("items"))
            .size());
  }

  @Test
  void claimRaceTransferAndOfflineResponsibility() throws Exception {
    var g = guest();
    String c = conversation(g), first = user("SUPPORT", true), second = user("SUPPORT", true);
    var a = actor(first, "SUPPORT");
    var b = actor(second, "SUPPORT");
    CountDownLatch go = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(2);
    try {
      var f1 =
          pool.submit(
              () -> {
                go.await();
                try {
                  support.assign(a, c, "", 1);
                  return true;
                } catch (Api.Failure e) {
                  return false;
                }
              });
      var f2 =
          pool.submit(
              () -> {
                go.await();
                try {
                  support.assign(b, c, "", 1);
                  return true;
                } catch (Api.Failure e) {
                  return false;
                }
              });
      go.countDown();
      assertNotEquals(f1.get(), f2.get());
      String owner =
          db.queryForObject(
              "SELECT assigned_to FROM support_conversation WHERE id=?", String.class, c);
      support.db.update("DELETE FROM support_presence WHERE user_id=?", owner);
      assertEquals(
          owner,
          db.queryForObject(
              "SELECT assigned_to FROM support_conversation WHERE id=?", String.class, c));
      String target = owner.equals(first) ? second : first;
      support.assign(actor(admin, "ADMIN"), c, target, revision(c));
      assertThrows(
          Api.Failure.class, () -> support.messages(null, c, owner.equals(first) ? a : b, 0, 0));
      support.send(
          null, c, actor(target, "SUPPORT"), new SupportService.Message(id(), "新负责人回复", ""));
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void messageRetriesUnreadCursorAndClosedState() {
    var g = guest();
    String c = conversation(g), agent = user("SUPPORT", true);
    var staff = actor(agent, "SUPPORT");
    support.assign(staff, c, "", 1);
    String request = id();
    var sent = support.send(g, c, null, new SupportService.Message(request, "相同消息", ""));
    assertEquals(sent, support.send(g, c, null, new SupportService.Message(request, "相同消息", "")));
    assertThrows(
        Api.Failure.class,
        () -> support.send(g, c, null, new SupportService.Message(request, "换文", "")));
    support.read(null, c, staff, 1);
    support.read(null, c, staff, 0);
    assertEquals(
        1,
        db.queryForObject("SELECT staff_read FROM support_conversation WHERE id=?", Long.class, c));
    support.send(null, c, staff, new SupportService.Message(id(), "回复", ""));
    assertEquals(
        1L,
        ((Number)
                ((List<Map<String, Object>>)
                        support.conversations(g, null, "", "", 1, 10).get("items"))
                    .get(0)
                    .get("unread"))
            .longValue());
    support.state(g, c, null, "CLOSED", revision(c));
    assertThrows(
        Api.Failure.class,
        () -> support.send(g, c, null, new SupportService.Message(id(), "结束后不能发送", "")));
    support.state(g, c, null, "OPEN", revision(c));
  }

  @Test
  void privateChatImageNeverEntersPublicMediaOrStorageList() throws Exception {
    var g = guest();
    String c = conversation(g);
    byte[] image = getClass().getResourceAsStream("/tiny.webp").readAllBytes();
    var asset =
        support.upload(g, c, null, new MockMultipartFile("file", "shot.webp", "image/webp", image));
    support.send(g, c, null, new SupportService.Message(id(), "截图", asset.id()));
    assertThrows(Api.Failure.class, () -> support.image(guest(), null, asset.id()));
    assertEquals("CHAT", support.image(g, null, asset.id()).asset().purpose());
    mvc.perform(get("/api/public/media/" + asset.id())).andExpect(status().isNotFound());
    assertFalse(
        storage.list("", "", "", 1, 100).items().stream().anyMatch(a -> a.id().equals(asset.id())));
    assertThrows(Api.Failure.class, () -> storage.delete(asset.id(), admin));
    db.update("UPDATE support_conversation SET updated_at=0 WHERE id=?", c);
    support.purge();
    assertThrows(Api.Failure.class, () -> support.messages(g, c, null, 0, 0));
    assertEquals(
        "DELETED",
        db.queryForObject("SELECT status FROM storage_asset WHERE id=?", String.class, asset.id()));
    // 模拟物理删除曾失败后保留的持久状态，下轮清理应真正重试，而不是遗留不可见文件。
    var pending =
        storage.upload(
            new MockMultipartFile("file", "orphan.webp", "image/webp", image), "CHAT", g.actor());
    db.update(
        "UPDATE storage_asset SET status='DELETE_PENDING',created_at=0 WHERE id=?", pending.id());
    support.purge();
    assertEquals(
        "DELETED",
        db.queryForObject(
            "SELECT status FROM storage_asset WHERE id=?", String.class, pending.id()));
  }

  @Test
  void visitorRecoveryRequiresOneTimeMailboxProofAndReplacesCredential() {
    String raw = crypto.token(), v = support.newVisitor(raw);
    var g = new SupportService.Principal(null, v, "游客");
    String c =
        support.create(
            g, new SupportService.NewConversation("找回", "GENERAL", "recover@example.com", false));
    int before =
        db.queryForObject(
            "SELECT COUNT(*) FROM mail_outbox WHERE recipient='recover@example.com'",
            Integer.class);
    support.recover(c, "different@example.com");
    assertEquals(
        before,
        db.queryForObject(
            "SELECT COUNT(*) FROM mail_outbox WHERE recipient='recover@example.com'",
            Integer.class));
    support.recover(c, "recover@example.com");
    String mail =
        crypto.decrypt(
            db.queryForObject(
                "SELECT body_enc FROM mail_outbox WHERE recipient='recover@example.com' ORDER BY"
                    + " created_at DESC LIMIT 1",
                String.class));
    String token = mail.split("recover=")[1].split("\\s")[0],
        fresh = support.consumeRecovery(token);
    assertNull(support.visitor(raw));
    assertEquals(v, support.visitor(fresh));
    assertThrows(Api.Failure.class, () -> support.consumeRecovery(token));
    support.recover(c, "recover@example.com");
    String secondMail =
        crypto.decrypt(
            db.queryForObject(
                "SELECT body_enc FROM mail_outbox WHERE recipient='recover@example.com' ORDER BY"
                    + " created_at DESC LIMIT 1",
                String.class));
    String oldEmailToken = secondMail.split("recover=")[1].split("\\s")[0];
    support.create(
        g,
        new SupportService.NewConversation("更改联系邮箱", "GENERAL", "new-contact@example.com", false));
    assertThrows(Api.Failure.class, () -> support.consumeRecovery(oldEmailToken));
  }

  @Test
  void publicModerationNeverLeaksPrivateFeedback() {
    var g = guest();
    String publicId = support.feedback(g, new SupportService.Feedback("PUBLIC", "可公开的问题")),
        privateId = support.feedback(g, new SupportService.Feedback("PRIVATE", "不能公开的意见"));
    assertEquals(0L, support.feedbackList(null, "public", null, 1, 20).get("total"));
    support.reviewFeedback(actor(admin, "ADMIN"), publicId, "APPROVED", "已回复", 1);
    support.reviewFeedback(actor(admin, "ADMIN"), privateId, "RESOLVED", "私密回复", 1);
    String result = api.encode(support.feedbackList(null, "public", null, 1, 20));
    assertTrue(result.contains("可公开的问题"));
    assertFalse(result.contains("不能公开的意见"));
    assertEquals(0L, support.feedbackList(guest(), "mine", null, 1, 20).get("total"));
  }

  @Test
  void offlineMailIsThrottledAndRepliesAlwaysHaveMemberNotice() {
    set("support.inbox", "support-test@example.com");
    try {
      var g = guest();
      String c = conversation(g);
      support.send(g, c, null, new SupportService.Message(id(), "离线1", ""));
      support.send(g, c, null, new SupportService.Message(id(), "离线2", ""));
      assertEquals(
          1,
          db.queryForObject(
              "SELECT COUNT(*) FROM mail_outbox WHERE recipient='support-test@example.com'",
              Integer.class));
      String user = user("USER", true);
      support.bind(user, g.visitor());
      String agent = user("SUPPORT", true);
      var staff = actor(agent, "SUPPORT");
      support.assign(staff, c, "", 1);
      support.send(null, c, staff, new SupportService.Message(id(), "回复提醒", ""));
      assertEquals(
          1,
          db.queryForObject(
              "SELECT COUNT(*) FROM member_notification WHERE user_id=?", Integer.class, user));
      assertEquals(
          0,
          db.queryForObject(
              "SELECT COUNT(*) FROM mail_outbox WHERE recipient=?",
              Integer.class,
              user + "@example.com"));
    } finally {
      set("support.inbox", "");
    }
  }

  @Test
  void analyticsDeduplicatesUsesServerIdentityAndKeepsFirstSource() {
    String user = user("USER", true);
    Cookie authCookie = cookie(user);
    MockHttpServletRequest r = new MockHttpServletRequest();
    r.setRemoteAddr("192.0.2.42");
    r.setCookies(authCookie);
    MockHttpServletResponse response = new MockHttpServletResponse();
    var event = new OperationsStats.Event("PAGE", "/apps", "", "search.example.com");
    stats.event(event, r, response);
    Cookie visitor = response.getCookie("AIWYW_ANALYTICS");
    assertNotNull(visitor);
    r.setCookies(authCookie, visitor);
    stats.event(
        new OperationsStats.Event("PAGE", "/apps", "", "other.example.com"),
        r,
        new MockHttpServletResponse());
    assertEquals(
        1,
        db.queryForObject(
            "SELECT COUNT(*) FROM analytics_event WHERE user_id=?", Integer.class, user));
    stats.event(
        new OperationsStats.Event("SEARCH", "/apps", "markdo", "other.example.com"),
        r,
        new MockHttpServletResponse());
    assertEquals(
        "search.example.com",
        db.queryForObject(
            "SELECT source_host FROM analytics_event WHERE user_id=? AND kind='SEARCH'",
            String.class,
            user));
    assertThrows(
        Api.Failure.class,
        () ->
            stats.event(
                new OperationsStats.Event("PAGE", "/admin/settings", "", ""),
                r,
                new MockHttpServletResponse()));
  }

  @Test
  void financialReportExcludesSimulationAndRefundsReverseOriginalCost() {
    String user = user("USER", true), a = definition("1.25");
    credit(user, 10000, 1000);
    api.preference(user, a, true, false);
    String k = (String) key(user, a, 0).get("id");
    api.invoke(user, k, slug(a), id(), input("billed"), false);
    String sub = buy(user, plan(a, 3));
    api.requestRefund(user, sub, "未使用退款");
    api.reviewRefund(actor(admin, "ADMIN"), sub, true, "核实未使用", 2);
    var report = stats.report("", "", "SIMULATED");
    List<Map<String, Object>> income = (List<Map<String, Object>>) report.get("income");
    long sum =
        income.stream()
            .filter(x -> x.get("entity_id").equals(a))
            .mapToLong(x -> Long.parseLong(String.valueOf(x.get("net_cost_fen"))))
            .sum();
    assertEquals(12, sum);
    assertEquals(0, ((List<?>) stats.report("", "", "REAL").get("income")).size());
    assertEquals("\"'=SUM(A1)\"", OperationsStats.cell("=SUM(A1)"));
    assertTrue(OperationsStats.cell(" =SUM(A1)\ncontinued").startsWith("\"'"));
    assertThrows(Api.Failure.class, () -> OperationsStats.window("2026-12-31", "2026-01-01"));
  }

  @Test
  void administrativeMenuBoundariesAndPrivateApiNoCsrfBypass() throws Exception {
    String user = user("USER", true);
    mvc.perform(get("/api/admin/statistics").cookie(cookie(user)))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/admin/api-list/apis").cookie(cookie(user)))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/support/feedback")
                .contentType("application/json")
                .content("{\"kind\":\"PUBLIC\",\"body\":\"x\"}"))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/admin/statistics").cookie(cookie(admin))).andExpect(status().isOk());
    mvc.perform(
            get("/api/admin/api-list/apis")
                .cookie(cookie(admin))
                .param("sort", "DROP TABLE app_user"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void boundedDeadlineCoversBlockedAdapterAndReleasesReservation() throws Exception {
    String user = user("USER", true), a = definition("1.00");
    credit(user, 1000, 100);
    api.preference(user, a, true, false);
    String k = (String) key(user, a, 0).get("id");
    stub(
        a,
        x -> {
          Thread.sleep(5000);
          return input("late");
        });
    set("api.timeout_seconds", "1");
    long start = System.nanoTime();
    try {
      assertEquals(
          504,
          assertThrows(
                  Api.Failure.class, () -> api.invoke(user, k, slug(a), id(), input("x"), false))
              .status);
      assertTrue(System.nanoTime() - start < 4_000_000_000L);
      assertEquals(1000, balance(user));
    } finally {
      set("api.timeout_seconds", "30");
    }
  }

  @Test
  void rotationInvalidatesOldTokenAndResponsePurgeKeepsIdempotencyFact() {
    String user = user("USER", true), a = definition("0.00");
    var k = key(user, a, 0);
    String key = (String) k.get("id"), request = id();
    var result = api.invoke(user, key, slug(a), request, input("x"), false);
    String before =
        db.queryForObject(
            "SELECT key_prefix FROM api_call WHERE id=?", String.class, result.get("requestId"));
    var rotated = api.rotateKey(user, key, 1);
    assertNotEquals(k.get("token"), rotated.get("token"));
    assertThrows(Api.Failure.class, () -> api.keyUser((String) k.get("token")));
    assertEquals(
        before,
        db.queryForObject(
            "SELECT key_prefix FROM api_call WHERE id=?", String.class, result.get("requestId")));
    db.update("UPDATE api_call SET finished_at=0 WHERE id=?", result.get("requestId"));
    api.sweep();
    assertEquals(
        410,
        assertThrows(
                Api.Failure.class, () -> api.invoke(user, key, slug(a), request, input("x"), false))
            .status);
    assertEquals(
        1, db.queryForObject("SELECT COUNT(*) FROM api_call WHERE user_id=?", Integer.class, user));
  }

  @Test
  void expiredRefundReceivesConfiguredGrace() {
    String user = user("USER", true), a = definition("1.00");
    credit(user, 10000, 1000);
    String subscription = buy(user, plan(a, 3));
    db.update("UPDATE wallet_batch SET expires_at=0 WHERE user_id=?", user);
    db.update("UPDATE wallet_hold_part SET expires_at=0 WHERE hold_id=?", subscription);
    assertEquals(0, balance(user));
    api.requestRefund(user, subscription, "从未使用");
    long before = System.currentTimeMillis();
    api.reviewRefund(actor(admin, "ADMIN"), subscription, true, "原批次已过期，按已确认规则恢复30天", 2);
    assertEquals(200, balance(user));
    long expiry =
        db.queryForObject(
            "SELECT expires_at FROM wallet_batch WHERE user_id=? AND source_type='API_REFUND'",
            Long.class,
            user);
    assertTrue(expiry >= before + 29 * 86400000L);
    assertTrue(expiry <= System.currentTimeMillis() + 31 * 86400000L);
  }

  @Test
  void failedInFlightCallReleasesAlreadyExpiredBatchWithoutDuplicateExpiryLedger()
      throws Exception {
    String user = user("USER", true), a = definition("1.00");
    credit(user, 10000, 1000);
    api.preference(user, a, true, false);
    String k = (String) key(user, a, 0).get("id");
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    stub(
        a,
        x -> {
          entered.countDown();
          assertTrue(release.await(10, TimeUnit.SECONDS));
          throw new java.net.SocketTimeoutException();
        });
    var executor = Executors.newSingleThreadExecutor();
    try {
      var running = executor.submit(() -> api.invoke(user, k, slug(a), id(), input("x"), false));
      assertTrue(entered.await(10, TimeUnit.SECONDS));
      // 仅在隔离测试库模拟调用期间原批次到期；先读取余额，触发未预留部分的到期流水。
      db.update("UPDATE wallet_batch SET expires_at=0 WHERE user_id=?", user);
      db.update(
          "UPDATE wallet_hold_part SET expires_at=0 WHERE hold_id IN (SELECT id FROM wallet_hold"
              + " WHERE user_id=?)",
          user);
      assertEquals(0, balance(user));
      release.countDown();
      var error = assertThrows(ExecutionException.class, () -> running.get(10, TimeUnit.SECONDS));
      assertInstanceOf(Api.Failure.class, error.getCause());
      assertEquals(504, ((Api.Failure) error.getCause()).status);
      assertEquals(0, balance(user));
      assertEquals(
          "RELEASED",
          db.queryForObject("SELECT state FROM wallet_hold WHERE user_id=?", String.class, user));
      assertEquals(
          "FAILED",
          db.queryForObject("SELECT state FROM api_call WHERE user_id=?", String.class, user));
      assertEquals(
          0L,
          db.queryForObject(
              "SELECT expires_at FROM wallet_batch WHERE user_id=? AND source_type='API_RELEASE'",
              Long.class,
              user));
      assertEquals(
          "SYSTEM",
          db.queryForObject(
              "SELECT o.created_by FROM object_origin o JOIN wallet_batch b ON b.id=o.object_id"
                  + " WHERE o.object_type='wallet_batch' AND b.user_id=? AND"
                  + " b.source_type='API_RELEASE'",
              String.class,
              user));
      assertEquals(
          0L,
          db.queryForObject(
              "SELECT SUM(remaining_cost_fen) FROM wallet_batch WHERE user_id=?",
              Long.class,
              user));
    } finally {
      release.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void redisFailureFallsBackToPersistentAccountRate() throws Exception {
    String user = user("USER", true), a = definition("0.00");
    String k = (String) key(user, a, 0).get("id");
    try (var silent = new java.net.ServerSocket(0)) {
      set("redis.port", Integer.toString(silent.getLocalPort()));
      set("redis.timeout_ms", "100");
      set("redis.enabled", "true");
      set("api.account_minute_limit", "2");
      try {
        api.invoke(user, k, slug(a), id(), input("1"), false);
        api.invoke(user, k, slug(a), id(), input("2"), false);
        assertEquals(
            429,
            assertThrows(
                    Api.Failure.class, () -> api.invoke(user, k, slug(a), id(), input("3"), false))
                .status);
      } finally {
        set("redis.enabled", "false");
        api.redis.close();
        set("redis.port", "6379");
        set("redis.timeout_ms", "200");
        set("api.account_minute_limit", "120");
      }
    }
  }

  @Test
  void statisticsCsvAndRetentionHaveConcreteKnownSamples() throws Exception {
    String user = user("USER", true);
    var yesterday = java.time.LocalDate.now(OperationsStats.ZONE).minusDays(1);
    db.update(
        "UPDATE app_user SET created_at=? WHERE id=?",
        yesterday.atStartOfDay(OperationsStats.ZONE).toInstant().toEpochMilli(),
        user);
    var r = new MockHttpServletRequest();
    r.setRemoteAddr("192.0.2.55");
    r.setCookies(cookie(user));
    stats.event(new OperationsStats.Event("PAGE", "/", "", ""), r, new MockHttpServletResponse());
    var report =
        stats.report(
            yesterday.toString(),
            java.time.LocalDate.now(OperationsStats.ZONE).toString(),
            "SIMULATED");
    var cohort =
        ((List<Map<String, Object>>) report.get("retention"))
            .stream()
                .filter(x -> x.get("day").equals(yesterday.toString()))
                .findFirst()
                .orElseThrow();
    assertTrue(((Number) cohort.get("d1")).longValue() >= 1);
    assertNull(cohort.get("d7"));
    mvc.perform(
            get("/api/admin/statistics/export").cookie(cookie(admin)).param("mode", "SIMULATED"))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Disposition", "attachment; filename=aiwyw-income.csv"));
  }

  @Test
  void manualAdjustmentIsAdminOnlyAuditedIdempotentAndUsesRealBatchOrder() {
    String u = user("USER", true);
    credit(u, 10000, 1000);
    String request = id();
    var positive = new Commerce.Adjustment(u, new BigDecimal("1.23"), 1, "API异常补偿，赠分成本0", request);
    String first = wallet.adjust(actor(admin, "ADMIN"), positive);
    assertEquals(first, wallet.adjust(actor(admin, "ADMIN"), positive));
    assertEquals(10123, balance(u));
    assertThrows(
        Api.Failure.class,
        () ->
            wallet.adjust(
                actor(admin, "ADMIN"),
                new Commerce.Adjustment(u, new BigDecimal("2.00"), 1, "换参数", request)));
    String debit =
        wallet.adjust(
            actor(admin, "ADMIN"),
            new Commerce.Adjustment(u, new BigDecimal("-2.00"), 0, "纠正异常积分", id()));
    assertEquals(9923, balance(u));
    assertEquals(
        7, db.queryForObject("SELECT cost_fen FROM wallet_hold WHERE id=?", Long.class, debit));
    assertEquals(
        2,
        db.queryForObject(
            "SELECT COUNT(*) FROM wallet_adjustment WHERE user_id=?", Integer.class, u));
    assertThrows(
        Api.Failure.class,
        () ->
            wallet.adjust(
                actor(u, "ADMIN"), new Commerce.Adjustment(u, BigDecimal.ONE, 0, "本人不能调整", id())));
    assertThrows(
        Api.Failure.class,
        () ->
            wallet.adjust(
                actor(id(), "FINANCE"),
                new Commerce.Adjustment(u, BigDecimal.ONE, 0, "财务不能直接调整", id())));
    assertThrows(
        Api.Failure.class,
        () ->
            wallet.adjust(
                actor(admin, "ADMIN"),
                new Commerce.Adjustment(u, new BigDecimal("-1000.00"), 0, "余额不足必须回滚", id())));
    assertEquals(9923, balance(u));
  }
}
