package com.aiwyw;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** 独立数据库的财务不变量测试；不连接用户实际钱包或邮件服务，测试邮件仅在私有发信箱检查。 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CommerceTest {
  static final Path directory;

  static {
    try {
      directory = Files.createTempDirectory("aiwyw-commerce-");
      String port = System.getProperty("aiwyw.test.mysql-port");
      if (port != null) {
        var crypto = new SecureStore(directory.toString());
        var spec =
            new Database.ConnectionSpec(
                "MYSQL",
                "127.0.0.1",
                Integer.parseInt(port),
                System.getProperty("aiwyw.test.mysql-database", "aiwyw_commerce_validation"),
                "root",
                "");
        Files.writeString(
            directory.resolve("bootstrap.enc"),
            crypto.encrypt(new ObjectMapper().writeValueAsString(spec)));
      }
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("aiwyw.data-dir", () -> directory.toString());
  }

  @Autowired Commerce service;
  @Autowired Accounts accounts;
  @Autowired Catalog catalog;
  @Autowired CatalogTags tags;
  @Autowired JdbcTemplate db;
  @Autowired SecureStore crypto;
  @Autowired ObjectMapper json;
  @Autowired MockMvc mvc;
  final Auth.User
      admin = new Auth.User(UUID.randomUUID().toString(), "admin@example.com", "ADMIN", true),
      support =
          new Auth.User(UUID.randomUUID().toString(), "support@example.com", "SUPPORT", false),
      finance = new Auth.User(UUID.randomUUID().toString(), "finance@example.com", "FINANCE", true);

  String user() {
    String id = UUID.randomUUID().toString();
    db.update(
        "INSERT INTO"
            + " app_user(id,email,password_hash,role_name,mfa_enabled,last_totp_step,created_at,email_verified_at)"
            + " VALUES(?,?,?,'USER',FALSE,-1,?,?)",
        id,
        id + "@example.com",
        crypto.password("isolated-test-password-123"),
        System.currentTimeMillis(),
        System.currentTimeMillis());
    return id;
  }

  Cookie cookie(String id, String role, boolean mfa) {
    if (db.queryForObject("SELECT COUNT(*) FROM app_user WHERE id=?", Integer.class, id) == 0)
      db.update(
          "INSERT INTO"
              + " app_user(id,email,password_hash,role_name,mfa_enabled,last_totp_step,created_at,email_verified_at)"
              + " VALUES(?,?,?,?,FALSE,-1,?,?)",
          id,
          id + "@example.com",
          crypto.password("isolated-test-password-123"),
          role,
          System.currentTimeMillis(),
          System.currentTimeMillis());
    String token = crypto.token();
    db.update(
        "INSERT INTO app_session(token_hash,user_id,expires_at,mfa_verified) VALUES(?,?,?,?)",
        crypto.digest(token),
        id,
        System.currentTimeMillis() + 3600000,
        mfa);
    return new Cookie("AIWYW_SESSION", token);
  }

  String key() {
    return UUID.randomUUID().toString();
  }

  String topup(String user, int yuan) {
    String id =
        (String)
            service
                .createRecharge(user, new Commerce.Recharge(BigDecimal.valueOf(yuan), null, key()))
                .get("id");
    service.declare(user, id, null, "isolated payment statement");
    service.reviewRecharge(
        yuan >= 200 ? admin : support,
        id,
        BigDecimal.valueOf(yuan),
        true,
        "isolated receipt verified");
    return id;
  }

  Catalog.ProductInput input(
      String slug, String version, String price, List<String> labels, long revision) {
    return new Catalog.ProductInput(
        slug,
        slug,
        "",
        "测试完整商品介绍",
        "",
        "<h2>安全说明</h2>",
        "",
        labels,
        "",
        List.of(),
        "SOFTWARE",
        new BigDecimal(price),
        version,
        "DOWNLOAD",
        "https://example.com/real-resource.zip",
        revision);
  }

  Catalog.Product product(String price) {
    String slug = "commerce-" + key();
    String id =
        catalog.saveProduct(null, input(slug, "1.0.0", price, List.of("交易"), 0), admin.id());
    catalog.publish(id, catalog.draft(id).revision(), admin.id(), nullSettings());
    return catalog.findPublic(slug);
  }

  @Autowired Settings settings;

  Settings nullSettings() {
    return settings;
  }

  String purchase(String user, Catalog.Product product, String key) {
    return service.purchase(
        user, new Commerce.Purchase(product.slug(), product.publicationId(), key));
  }

  void consistent(String user) {
    long sum =
        db.queryForObject(
            "SELECT COALESCE(SUM(remaining_units),0) FROM wallet_batch WHERE user_id=? AND"
                + " mode='SIMULATED'",
            Long.class,
            user);
    long balance =
        db.queryForObject(
            "SELECT balance_units FROM wallet_account WHERE user_id=? AND mode='SIMULATED'",
            Long.class,
            user);
    assertEquals(sum, balance);
    assertTrue(balance >= 0);
    assertEquals(
        0,
        db.queryForObject(
            "SELECT COUNT(*) FROM wallet_batch WHERE user_id=? AND (remaining_units<0 OR"
                + " remaining_cost_fen<0)",
            Integer.class,
            user));
  }

  private void registerForTest(String email, String password, String ip) {
    var original =
        settings.list().stream()
            .filter(entry -> entry.definition().key().equals("account.registration_open"))
            .findFirst()
            .orElseThrow();
    settings.save(
        List.of(
            new Settings.Change("account.registration_open", "true", original.revision(), false)),
        "test-operator");
    try {
      accounts.register(email, password, ip);
    } finally {
      settings.save(
          List.of(
              new Settings.Change(
                  "account.registration_open", original.value(), original.revision() + 1, false)),
          "test-operator");
    }
  }

  @Test
  void registrationVerificationResetReplayAndSessionRevocation() throws Exception {
    String email = key() + "@example.com";
    registerForTest(email, "original-test-password-9283", "register-" + key());
    String id = db.queryForObject("SELECT id FROM app_user WHERE email=?", String.class, email);
    assertNull(
        db.queryForObject("SELECT email_verified_at FROM app_user WHERE id=?", Long.class, id));
    var row =
        db.queryForMap(
            "SELECT * FROM mail_outbox WHERE recipient=? ORDER BY created_at DESC", email);
    String body = crypto.decrypt((String) row.get("body_enc")),
        token = body.split("token=")[1].split("\\s")[0];
    assertFalse(row.get("body_enc").toString().contains(token));
    assertEquals(
        0,
        db.queryForObject(
            "SELECT COUNT(*) FROM account_action_token WHERE token_hash=?", Integer.class, token));
    accounts.consume(token, "VERIFY", null);
    assertNotNull(
        db.queryForObject("SELECT email_verified_at FROM app_user WHERE id=?", Long.class, id));
    assertThrows(Api.Failure.class, () -> accounts.consume(token, "VERIFY", null));
    Cookie session = cookie(id, "USER", false);
    accounts.send(email, "RESET", "reset-" + key());
    String reset =
        crypto.decrypt(
                db.queryForObject(
                    "SELECT body_enc FROM mail_outbox WHERE recipient=? AND subject=?",
                    String.class,
                    email,
                    "微云湾 重置密码"))
            .split("token=")[1]
            .split("\\s")[0];
    assertThrows(Api.Failure.class, () -> accounts.consume(reset, "VERIFY", null));
    accounts.consume(reset, "RESET", "new-isolated-password-9374");
    mvc.perform(get("/api/member/profile").cookie(session)).andExpect(status().isUnauthorized());
    assertTrue(
        crypto.verifyPassword(
            "new-isolated-password-9374",
            db.queryForObject("SELECT password_hash FROM app_user WHERE id=?", String.class, id)));
    assertThrows(
        Api.Failure.class, () -> accounts.consume(reset, "RESET", "new-isolated-password-9374"));
  }

  @Test
  void emailVerificationIsRequiredAndFinanceCannotPost() throws Exception {
    String user = user();
    db.update("UPDATE app_user SET email_verified_at=NULL WHERE id=?", user);
    mvc.perform(
            post("/api/member/recharge")
                .cookie(cookie(user, "USER", false))
                .header("X-AIWYW-Request", "1")
                .contentType("application/json")
                .content(
                    json.writeValueAsBytes(new Commerce.Recharge(BigDecimal.TEN, null, key()))))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/admin/commerce/recharge/anything/review")
                .cookie(cookie(finance.id(), "FINANCE", true))
                .header("X-AIWYW-Request", "1")
                .contentType("application/json")
                .content("{\"actual\":10,\"approve\":true,\"reason\":\"核实\"}"))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/admin/commerce/recharge").cookie(cookie(finance.id(), "FINANCE", false)))
        .andExpect(status().isForbidden());
  }

  @Test
  void rechargeSnapshotsIdempotencyAndActualAmountConversion() {
    String user = user(), key = key();
    var request = new Commerce.Recharge(BigDecimal.TEN, null, key);
    String id = (String) service.createRecharge(user, request).get("id");
    assertEquals(id, service.createRecharge(user, request).get("id"));
    assertThrows(
        Api.Failure.class,
        () -> service.createRecharge(user, new Commerce.Recharge(new BigDecimal("11"), null, key)));
    service.declare(user, id, null, "");
    service.reviewRecharge(support, id, new BigDecimal("8.25"), true, "少付，按实到普通鼓励值");
    service.reviewRecharge(support, id, new BigDecimal("8.25"), true, "重复请求");
    assertEquals("82.50", service.summary(user).get("balance"));
    assertEquals(
        1,
        db.queryForObject(
            "SELECT COUNT(*) FROM wallet_ledger WHERE user_id=? AND kind='RECHARGE'",
            Integer.class,
            user));
    assertNull(
        db.queryForObject("SELECT expires_at FROM wallet_batch WHERE user_id=?", Long.class, user));
    consistent(user);
  }

  @Test
  void largeAndClosedOrdersRequireAdministrator() {
    String user = user();
    String id =
        (String)
            service
                .createRecharge(user, new Commerce.Recharge(new BigDecimal("200"), null, key()))
                .get("id");
    service.declare(user, id, null, "");
    assertThrows(
        Api.Failure.class,
        () -> service.reviewRecharge(support, id, new BigDecimal("200"), true, "attempt"));
    service.reviewRecharge(admin, id, new BigDecimal("200"), true, "大额批准");
    String closed =
        (String)
            service
                .createRecharge(user, new Commerce.Recharge(BigDecimal.ONE, null, key()))
                .get("id");
    db.update(
        "UPDATE recharge_order SET expires_at=? WHERE id=?",
        System.currentTimeMillis() - 1,
        closed);
    service.declare(user, closed, null, "延迟声明");
    assertEquals(
        "CLOSED",
        db.queryForObject("SELECT state FROM recharge_order WHERE id=?", String.class, closed));
    assertThrows(
        Api.Failure.class,
        () -> service.reviewRecharge(support, closed, BigDecimal.ONE, true, "attempt"));
    service.reviewRecharge(admin, closed, BigDecimal.ONE, true, "关闭后补记");
    consistent(user);
  }

  @Test
  void concurrentPostingCreditsOnce() throws Exception {
    String user = user(),
        id =
            (String)
                service
                    .createRecharge(user, new Commerce.Recharge(BigDecimal.TEN, null, key()))
                    .get("id");
    service.declare(user, id, null, "");
    ExecutorService pool = Executors.newFixedThreadPool(5);
    try {
      List<Callable<Boolean>> jobs = new ArrayList<>();
      for (int i = 0; i < 10; i++)
        jobs.add(
            () -> {
              service.reviewRecharge(support, id, BigDecimal.TEN, true, "verified concurrent");
              return true;
            });
      for (var result : pool.invokeAll(jobs)) assertTrue(result.get());
    } finally {
      pool.shutdownNow();
    }
    assertEquals("100.00", service.summary(user).get("balance"));
    assertEquals(
        1,
        db.queryForObject(
            "SELECT COUNT(*) FROM wallet_batch WHERE user_id=?", Integer.class, user));
    consistent(user);
  }

  @Test
  void concurrentRedemptionAndDuplicateOwnershipNeverDoubleCharge() throws Exception {
    String user = user();
    topup(user, 10);
    var product = product("10.00");
    ExecutorService pool = Executors.newFixedThreadPool(5);
    Set<String> results = new HashSet<>();
    try {
      List<Callable<String>> jobs = new ArrayList<>();
      for (int i = 0; i < 10; i++) {
        String request = key();
        jobs.add(() -> purchase(user, product, request));
      }
      for (var result : pool.invokeAll(jobs)) results.add(result.get());
    } finally {
      pool.shutdownNow();
    }
    assertEquals(1, results.size());
    assertEquals("90.00", service.summary(user).get("balance"));
    assertEquals(
        1,
        db.queryForObject(
            "SELECT COUNT(*) FROM purchase_order WHERE user_id=?", Integer.class, user));
    assertEquals(
        10,
        db.queryForObject(
            "SELECT COUNT(*) FROM purchase_request WHERE user_id=?", Integer.class, user));
    var alias =
        db.queryForMap("SELECT request_key FROM purchase_request WHERE user_id=? LIMIT 1", user);
    var other = product("1.00");
    assertThrows(Api.Failure.class, () -> purchase(user, other, (String) alias.get("request_key")));
    consistent(user);
  }

  @Test
  void earliestExpiryAllocationFullRefundAndExpiredExtension() {
    String user = user();
    topup(user, 10);
    String pack =
        service.savePackage(
            null,
            new Commerce.PackageInput(
                "折扣-" + key(),
                "Discount",
                new BigDecimal("5"),
                new BigDecimal("100"),
                1,
                false,
                0,
                0),
            admin.id());
    db.update("UPDATE points_package SET enabled=TRUE WHERE id=?", pack);
    String id =
        (String) service.createRecharge(user, new Commerce.Recharge(null, pack, key())).get("id");
    // 升级前已创建的额外奖励订单，核销必须保留原100鼓励值快照。
    db.update("UPDATE recharge_order SET points_units=10000 WHERE id=?", id);
    service.declare(user, id, null, "");
    service.reviewRecharge(support, id, new BigDecimal("5"), true, "promo checked");
    var product = product("150.00");
    String purchase = purchase(user, product, key());
    assertEquals("50.00", service.summary(user).get("balance"));
    assertEquals(
        2,
        db.queryForObject(
            "SELECT COUNT(*) FROM wallet_allocation WHERE purchase_id=?", Integer.class, purchase));
    assertEquals(
        1000,
        db.queryForObject("SELECT cost_fen FROM purchase_order WHERE id=?", Long.class, purchase));
    long now = System.currentTimeMillis();
    db.update(
        "UPDATE wallet_allocation SET expires_at=? WHERE purchase_id=? AND expires_at IS NOT NULL",
        now - 1,
        purchase);
    db.update("UPDATE wallet_batch SET expires_at=? WHERE source_id=?", now - 1, id);
    service.requestRefund(user, purchase, "无法使用，整单退款");
    long revision =
        db.queryForObject(
            "SELECT revision FROM refund_request WHERE purchase_id=?", Long.class, purchase);
    service.reviewRefund(support, purchase, true, "已核实交付问题", revision);
    service.reviewRefund(support, purchase, true, "幂等重试", revision);
    assertEquals("200.00", service.summary(user).get("balance"));
    assertEquals(
        "REFUNDED",
        db.queryForObject("SELECT state FROM purchase_order WHERE id=?", String.class, purchase));
    long expiry =
        db.queryForObject(
            "SELECT expires_at FROM wallet_batch WHERE user_id=? AND source_type='REFUND'",
            Long.class,
            user);
    assertTrue(
        expiry >= now + 30 * 86400000L && expiry <= System.currentTimeMillis() + 30 * 86400000L);
    assertEquals(
        support.id(),
        db.queryForObject(
            "SELECT o.created_by FROM object_origin o JOIN wallet_batch b ON b.id=o.object_id"
                + " WHERE o.object_type='wallet_batch' AND b.user_id=? AND b.source_type='REFUND'",
            String.class,
            user));
    assertThrows(Api.Failure.class, () -> service.delivery(user, purchase, false));
    assertEquals(
        1,
        db.queryForObject(
            "SELECT COUNT(*) FROM wallet_ledger WHERE user_id=? AND kind='REFUND'",
            Integer.class,
            user));
    consistent(user);
  }

  @Test
  void expiryOnlyRunsOnceAndInsufficientPurchaseRollsBack() {
    String user = user();
    topup(user, 1);
    db.update(
        "UPDATE wallet_batch SET expires_at=? WHERE user_id=?",
        System.currentTimeMillis() - 1,
        user);
    assertEquals("0.00", service.summary(user).get("balance"));
    assertEquals("0.00", service.summary(user).get("balance"));
    assertEquals(
        1,
        db.queryForObject(
            "SELECT COUNT(*) FROM wallet_ledger WHERE user_id=? AND kind='EXPIRE'",
            Integer.class,
            user));
    var product = product("1.00");
    assertThrows(Api.Failure.class, () -> purchase(user, product, key()));
    assertEquals(
        0,
        db.queryForObject(
            "SELECT COUNT(*) FROM purchase_order WHERE user_id=?", Integer.class, user));
    consistent(user);
  }

  @Test
  void paidDeliveryKeepsOriginalMajorVersionAndUnlistedEntitlements() {
    String user = user();
    topup(user, 10);
    var product = product("10.00");
    String purchase = purchase(user, product, key());
    String pid = product.id();
    catalog.saveProduct(
        pid,
        input(product.slug(), "2.0.0", "20.00", List.of("交易"), catalog.draft(pid).revision()),
        admin.id());
    catalog.publish(pid, catalog.draft(pid).revision(), admin.id(), settings);
    assertEquals("https://example.com/real-resource.zip", service.delivery(user, purchase, false));
    assertThrows(Api.Failure.class, () -> purchase(user, product, key()));
    catalog.unlist(pid, catalog.draft(pid).revision(), admin.id());
    assertEquals("https://example.com/real-resource.zip", service.delivery(user, purchase, true));
    assertThrows(Api.Failure.class, () -> service.quote(user, product.slug()));
  }

  @Test
  void receiptOwnerAndRolePrivacy() throws Exception {
    String owner = user(), other = user();
    var image = new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB);
    var bytes = new java.io.ByteArrayOutputStream();
    javax.imageio.ImageIO.write(image, "png", bytes);
    var response =
        mvc.perform(
                multipart("/api/member/evidence")
                    .file(
                        new MockMultipartFile(
                            "file", "receipt.png", "image/png", bytes.toByteArray()))
                    .cookie(cookie(owner, "USER", false))
                    .header("X-AIWYW-Request", "1"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();
    String id = json.readTree(response.getContentAsString()).get("id").asText();
    mvc.perform(get("/api/member/evidence/" + id).cookie(cookie(owner, "USER", false)))
        .andExpect(status().isOk());
    mvc.perform(get("/api/member/evidence/" + id).cookie(cookie(other, "USER", false)))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/public/media/" + id)).andExpect(status().isNotFound());
    String operator = UUID.randomUUID().toString();
    mvc.perform(
            get("/api/admin/storage/assets/" + id + "/content")
                .cookie(cookie(operator, "OPERATOR", false)))
        .andExpect(status().isNotFound());
    String order =
        (String)
            service
                .createRecharge(owner, new Commerce.Recharge(BigDecimal.ONE, null, key()))
                .get("id");
    assertThrows(Api.Failure.class, () -> service.declare(other, order, id, ""));
    service.declare(owner, order, id, "");
    assertEquals(
        1,
        db.queryForObject(
            "SELECT COUNT(*) FROM storage_reference WHERE asset_id=?", Integer.class, id));
  }

  @Test
  void tagsAreSeparateVersionedEntitiesAndRenameOnlyDraft() throws Exception {
    var operator = cookie(admin.id(), "ADMIN", true);
    var product = product("1.00");
    var rows =
        tags.list(
            "交易",
            1,
            20,
            new org.springframework.mock.web.MockHttpServletRequest() {
              @Override
              public Cookie[] getCookies() {
                return new Cookie[] {operator};
              }
            });
    @SuppressWarnings("unchecked")
    var tag = ((List<Map<String, Object>>) rows.get("items")).get(0);
    var req = new org.springframework.mock.web.MockHttpServletRequest();
    req.setCookies(operator);
    String name = "已改名-" + key();
    tags.save(
        (String) tag.get("id"),
        new CatalogTags.Input(name, "备注", ((Number) tag.get("revision")).longValue()),
        req);
    assertTrue(catalog.draft(product.id()).content().tags().contains(name));
    assertTrue(catalog.findPublic(product.slug()).content().tags().contains("交易"));
    assertThrows(
        Api.Failure.class,
        () ->
            tags.delete(
                (String) tag.get("id"), ((Number) tag.get("revision")).longValue() + 1, req));
  }

  @Test
  void amountPrecisionAndNamespaceAreEnforced() {
    assertThrows(Api.Failure.class, () -> Commerce.units(new BigDecimal("1.001")));
    assertThrows(Api.Failure.class, () -> Commerce.units(new BigDecimal("-1")));
    String user = user();
    topup(user, 1);
    assertEquals(
        0,
        db.queryForObject(
            "SELECT COUNT(*) FROM wallet_batch WHERE mode<>'SIMULATED'", Integer.class));
    assertEquals("10.00", service.summary(user).get("balance"));
    consistent(user);
  }

  @Autowired Storage storage;

  @Test
  void protectedTicketIsRevokedOnRefundAndReviewRejectCanBeResubmitted() throws Exception {
    String user = user();
    topup(user, 10);
    String slug = "private-" + key();
    var asset =
        storage.upload(
            new MockMultipartFile(
                "file", "private.zip", "application/zip", "actual-private-package".getBytes()),
            "DOWNLOAD",
            admin.id());
    var source = input(slug, "1.0.0", "25.00", List.of(), 0);
    var product =
        new Catalog.ProductInput(
            source.slug(),
            source.nameZh(),
            source.nameEn(),
            source.summaryZh(),
            source.summaryEn(),
            source.bodyZh(),
            source.bodyEn(),
            source.tags(),
            source.iconUrl(),
            source.screenshots(),
            source.type(),
            source.pricePoints(),
            source.version(),
            source.deliveryMode(),
            asset.url(),
            0);
    String pid = catalog.saveProduct(null, product, admin.id());
    catalog.publish(pid, catalog.draft(pid).revision(), admin.id(), settings);
    String order = purchase(user, catalog.findPublic(slug), key());
    String url = service.delivery(user, order, false);
    mvc.perform(get(url))
        .andExpect(status().isOk())
        .andExpect(content().bytes("actual-private-package".getBytes()));
    service.requestRefund(user, order, "首次申请");
    service.reviewRefund(support, order, false, "请提供使用信息", 1);
    assertEquals(
        "ACTIVE",
        db.queryForObject("SELECT state FROM purchase_order WHERE id=?", String.class, order));
    service.requestRefund(user, order, "补充信息后再次申请");
    long revision =
        db.queryForObject(
            "SELECT revision FROM refund_request WHERE purchase_id=?", Long.class, order);
    assertThrows(Api.Failure.class, () -> service.reviewRefund(support, order, true, "过期审核", 1));
    service.reviewRefund(support, order, true, "核实后整单退鼓励值", revision);
    mvc.perform(get(url)).andExpect(status().isForbidden());
    assertThrows(Api.Failure.class, () -> service.delivery(user, order, true));
    consistent(user);
  }

  @Test
  void packageSnapshotsSurviveEditsAndActiveLimitIsSerialized() throws Exception {
    db.update("UPDATE points_package SET enabled=FALSE");
    String user = user();
    String pack =
        service.savePackage(
            null,
            new Commerce.PackageInput(
                "快照捐赠档位", "Snapshot", new BigDecimal("5"), new BigDecimal("100"), 1, true, 1, 0),
            admin.id());
    String order =
        (String) service.createRecharge(user, new Commerce.Recharge(null, pack, key())).get("id");
    service.savePackage(
        pack,
        new Commerce.PackageInput(
            "已改价", "Changed", new BigDecimal("10"), new BigDecimal("200"), 7, true, 1, 1),
        admin.id());
    service.declare(user, order, null, "");
    service.reviewRecharge(support, order, new BigDecimal("5"), true, "按下单快照核销");
    assertEquals("50.00", service.summary(user).get("balance"));
    ExecutorService pool = Executors.newFixedThreadPool(5);
    int success = 0;
    try {
      List<Callable<Boolean>> jobs = new ArrayList<>();
      for (int i = 0; i < 10; i++)
        jobs.add(
            () -> {
              try {
                service.savePackage(
                    null,
                    new Commerce.PackageInput(
                        "并发包-" + key(), "", BigDecimal.ONE, BigDecimal.TEN, 30, true, 0, 0),
                    admin.id());
                return true;
              } catch (Api.Failure limit) {
                assertEquals(409, limit.status);
                return false;
              }
            });
      for (var result : pool.invokeAll(jobs)) if (result.get()) success++;
    } finally {
      pool.shutdownNow();
    }
    assertEquals(4, success);
    assertEquals(5, service.packages(false).size());
    db.update("UPDATE points_package SET enabled=FALSE");
    consistent(user);
  }

  @Autowired Licenses licenses;
  @Autowired CatalogAdminQuery adminQuery;

  Catalog.Product licensed(int devices) {
    return licensed(devices, "10.00");
  }

  Catalog.Product licensed(int devices, String price) {
    String slug = "licensed-" + key();
    var p = input(slug, "1.0.0", price, List.of("授权"), 0);
    String id =
        catalog.saveProduct(
            null,
            new Catalog.ProductInput(
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
                "LICENSE",
                p.downloadUrl(),
                0),
            admin.id());
    licenses.policy(admin.id(), id, devices, 0);
    catalog.publish(id, catalog.draft(id).revision(), admin.id(), settings);
    return catalog.findPublic(slug);
  }

  Licenses.Activation activation(String key, String device, Catalog.Product p) {
    return new Licenses.Activation(key, device, "测试电脑", p.id(), p.content().version());
  }

  Licenses.Verification verification(Map<String, Object> active, String device, Catalog.Product p) {
    return new Licenses.Verification(
        (String) active.get("bindingId"),
        (String) active.get("deviceToken"),
        device,
        p.id(),
        p.content().version());
  }

  @Test
  void licenseTermsFrozenDeviceActivationAndVersionBoundary() {
    String user = user();
    topup(user, 10);
    var p = licensed(1);
    licenses.policy(admin.id(), p.id(), 5, 1);
    String order = purchase(user, p, key());
    String secret = (String) licenses.detail(user, order).get("key"), device = crypto.token();
    assertEquals(1, ((Number) licenses.detail(user, order).get("deviceLimit")).intValue());
    var active = licenses.activate(activation(secret, device, p));
    assertEquals(active, licenses.activate(activation(secret, device, p)));
    assertEquals(true, licenses.verify(verification(active, device, p)).get("valid"));
    assertThrows(Api.Failure.class, () -> licenses.activate(activation(secret, crypto.token(), p)));
    assertThrows(Api.Failure.class, () -> licenses.detail(user(), order));
    assertThrows(
        Api.Failure.class,
        () ->
            licenses.verify(
                new Licenses.Verification(
                    (String) active.get("bindingId"),
                    (String) active.get("deviceToken"),
                    device,
                    p.id(),
                    "2.0.0")));
    assertThrows(Api.Failure.class, () -> licenses.verify(verification(active, crypto.token(), p)));
    assertTrue(service.delivery(user, order, true).startsWith("https://example.com/"));
    catalog.unlist(p.id(), catalog.draft(p.id()).revision(), admin.id());
    assertEquals(true, licenses.verify(verification(active, device, p)).get("valid"));
  }

  @Test
  void concurrentActivationCannotExceedPurchasedLimit() throws Exception {
    String user = user();
    topup(user, 10);
    var p = licensed(1);
    String order = purchase(user, p, key());
    String secret = (String) licenses.detail(user, order).get("key");
    ExecutorService pool = Executors.newFixedThreadPool(5);
    int successes = 0;
    try {
      List<Callable<Boolean>> jobs = new ArrayList<>();
      for (int i = 0; i < 15; i++)
        jobs.add(
            () -> {
              try {
                licenses.activate(activation(secret, crypto.token(), p));
                return true;
              } catch (Api.Failure denied) {
                assertEquals(409, denied.status);
                return false;
              }
            });
      for (var result : pool.invokeAll(jobs)) if (result.get()) successes++;
    } finally {
      pool.shutdownNow();
    }
    assertEquals(1, successes);
    assertEquals(
        1,
        db.queryForObject(
            "SELECT COUNT(*) FROM license_device WHERE purchase_id=? AND state='ACTIVE'",
            Integer.class,
            order));
    consistent(user);
  }

  @Test
  void unbindRequiresReviewThenOldTokenRevokedAndNewDeviceAllowed() {
    String user = user();
    topup(user, 10);
    var p = licensed(1);
    String order = purchase(user, p, key());
    String secret = (String) licenses.detail(user, order).get("key"), device = crypto.token();
    var old = licenses.activate(activation(secret, device, p));
    String request = licenses.request(user, order, (String) old.get("bindingId"), "更换损坏的电脑");
    assertEquals(request, licenses.request(user, order, (String) old.get("bindingId"), "重复提交"));
    assertThrows(
        Api.Failure.class,
        () ->
            licenses.review(
                new Auth.User(user, "x@example.com", "SUPPORT", false), request, true, "self", 1));
    assertThrows(Api.Failure.class, () -> licenses.review(finance, request, true, "finance", 1));
    licenses.review(support, request, false, "请补充设备情况", 1);
    assertEquals(true, licenses.verify(verification(old, device, p)).get("valid"));
    String next = licenses.request(user, order, (String) old.get("bindingId"), "设备故障无法启动");
    licenses.review(support, next, true, "已核实换机", 1);
    assertThrows(Api.Failure.class, () -> licenses.review(support, next, true, "重复审核", 1));
    assertThrows(Api.Failure.class, () -> licenses.verify(verification(old, device, p)));
    assertNotNull(licenses.activate(activation(secret, crypto.token(), p)).get("deviceToken"));
  }

  @Test
  void refundAndDisabledAccountInvalidateOnlineLicenseImmediately() {
    String user = user();
    topup(user, 10);
    var p = licensed(1);
    String order = purchase(user, p, key());
    String secret = (String) licenses.detail(user, order).get("key"), device = crypto.token();
    var active = licenses.activate(activation(secret, device, p));
    String req = licenses.request(user, order, (String) active.get("bindingId"), "更换电脑");
    db.update("UPDATE app_user SET disabled=TRUE WHERE id=?", user);
    assertThrows(Api.Failure.class, () -> licenses.verify(verification(active, device, p)));
    db.update("UPDATE app_user SET disabled=FALSE WHERE id=?", user);
    service.requestRefund(user, order, "软件无法满足需求");
    service.reviewRefund(support, order, true, "核实整单退鼓励值", 1);
    assertThrows(Api.Failure.class, () -> licenses.verify(verification(active, device, p)));
    assertThrows(Api.Failure.class, () -> licenses.activate(activation(secret, crypto.token(), p)));
    assertEquals(
        "CANCELLED",
        db.queryForObject(
            "SELECT state FROM license_unbind_request WHERE id=?", String.class, req));
    assertEquals(
        "REVOKED",
        db.queryForObject(
            "SELECT state FROM software_license WHERE purchase_id=?", String.class, order));
    assertEquals("100.00", service.summary(user).get("balance"));
    consistent(user);
  }

  @Test
  void licenseHttpPermissionsCacheAndNoSecretList() throws Exception {
    String user = user();
    topup(user, 10);
    var p = licensed(1);
    String order = purchase(user, p, key());
    Cookie member = cookie(user, "USER", false);
    String secret = (String) licenses.detail(user, order).get("key");
    mvc.perform(post("/api/member/licenses/" + order + "/detail").cookie(member))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/member/licenses/" + order + "/detail")
                .cookie(member)
                .header("X-AIWYW-Request", "1"))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"));
    mvc.perform(get("/api/admin/licenses/requests").cookie(member))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/admin/licenses/requests").cookie(cookie(admin.id(), "ADMIN", false)))
        .andExpect(status().isForbidden());
    String result =
        mvc.perform(
                get("/api/admin/licenses/requests").cookie(cookie(support.id(), "SUPPORT", false)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertFalse(result.contains(secret));
    mvc.perform(
            post("/api/license/activate")
                .header("X-AIWYW-Request", "1")
                .contentType("application/json")
                .content(json.writeValueAsString(activation(secret, crypto.token(), p))))
        .andExpect(status().isOk());
  }

  @Test
  void listSortsBeforePaginationAndRejectsUntrustedSql() {
    var a = product("1.00");
    var b = product("2.00");
    db.update("UPDATE catalog_product SET created_at=? WHERE id=?", 100L, a.id());
    db.update("UPDATE catalog_product SET created_at=? WHERE id=?", 200L, b.id());
    assertEquals(
        a.id(),
        adminQuery
            .page("commerce-", "", "", "", "", List.of(), 1, 1, "createdAt", "ascending")
            .items()
            .get(0)
            .id());
    var desc =
        adminQuery
            .page("commerce-", "", "", "", "", List.of(), 1, 100, "pricePoints", "descending")
            .items();
    for (int i = 1; i < desc.size(); i++)
      assertTrue(desc.get(i - 1).pricePoints().compareTo(desc.get(i).pricePoints()) >= 0);
    assertThrows(
        Api.Failure.class,
        () ->
            adminQuery.page(
                "", "", "", "", "", List.of(), 1, 20, "id;DROP TABLE app_user", "descending"));
    assertThrows(
        Api.Failure.class,
        () -> service.page("recharge", null, "", "", "", "", 1, 20, "created_at", "desc;--"));
  }

  @Test
  void freeLicenseRequiresClaimButDoesNotDeductPoints() throws Exception {
    String user = user();
    var p = licensed(1, "0.00");
    mvc.perform(
            post("/api/public/catalog/" + p.slug() + "/download").header("X-AIWYW-Request", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.url").value("/checkout?slug=" + p.slug()));
    String order = purchase(user, p, key());
    assertNotNull(licenses.detail(user, order).get("key"));
    assertEquals("0.00", service.summary(user).get("balance"));
    assertEquals(order, purchase(user, p, key()));
    consistent(user);
  }

  @Test
  void publishedMajorCannotChangeActivationContract() {
    var p = licensed(1);
    catalog.saveProduct(
        p.id(),
        input(p.slug(), "1.1.0", "10.00", List.of(), catalog.draft(p.id()).revision()),
        admin.id());
    assertThrows(
        Api.Failure.class,
        () -> catalog.publish(p.id(), catalog.draft(p.id()).revision(), admin.id(), settings));
    assertEquals(p.publicationId(), catalog.findPublic(p.slug()).publicationId());
    catalog.saveProduct(
        p.id(),
        input(p.slug(), "2.0.0", "10.00", List.of(), catalog.draft(p.id()).revision()),
        admin.id());
    assertDoesNotThrow(
        () -> catalog.publish(p.id(), catalog.draft(p.id()).revision(), admin.id(), settings));
  }

  @Autowired Auth authentication;

  @Test
  void administratorCanAddOwnRoleAndOldSessionIsRevoked() throws Exception {
    Cookie original = cookie(admin.id(), "ADMIN", true);
    mvc.perform(
            put("/api/admin/accounts/" + admin.id())
                .cookie(original)
                .header("X-AIWYW-Request", "1")
                .contentType("application/json")
                .content("{\"roles\":[\"ADMIN\",\"FINANCE\"],\"disabled\":false}"))
        .andExpect(status().isOk());
    mvc.perform(get("/api/auth/session").cookie(original)).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/auth/session").cookie(cookie(admin.id(), "ADMIN", true)))
        .andExpect(jsonPath("$.roles").value(org.hamcrest.Matchers.hasItem("FINANCE")));
  }

  @Test
  void nicknameRegistrationAndOwnedModification() throws Exception {
    mvc.perform(
            get("/api/admin/accounts")
                .param("sort", "nickname")
                .param("direction", "descending")
                .cookie(cookie(admin.id(), "ADMIN", true)))
        .andExpect(status().isOk());
    String mail = key() + "@example.com";
    registerForTest(mail, "nickname-test-password-123", "nick-" + key());
    String id = db.queryForObject("SELECT id FROM app_user WHERE email=?", String.class, mail),
        name = db.queryForObject("SELECT nickname FROM app_user WHERE id=?", String.class, id);
    assertTrue(name.matches("(?=.*[A-Za-z])(?=.*[0-9])[A-Za-z0-9]+"));
    mvc.perform(
            put("/api/member/nickname")
                .cookie(cookie(id, "USER", false))
                .header("X-AIWYW-Request", "1")
                .contentType("application/json")
                .content("{\"nickname\":\"我的工具箱\"}"))
        .andExpect(status().isOk());
    assertEquals(
        "我的工具箱", db.queryForObject("SELECT nickname FROM app_user WHERE id=?", String.class, id));
  }

  @Test
  void multiRoleUnionAndMfaCannotBeBypassedByNonPrivilegedPrimaryRole() throws Exception {
    String id = user();
    db.update("INSERT INTO access_user_role VALUES(?, 'OPERATOR')", id);
    db.update("INSERT INTO access_user_role VALUES(?, 'FINANCE')", id);
    Cookie plain = cookie(id, "USER", false);
    var result =
        mvc.perform(get("/api/auth/session").cookie(plain)).andExpect(status().isOk()).andReturn();
    var data = json.readTree(result.getResponse().getContentAsString());
    assertTrue(data.get("mfaRequired").asBoolean());
    assertTrue(data.get("menus").toString().contains("applications"));
    mvc.perform(get("/api/admin/catalog/products").cookie(plain)).andExpect(status().isForbidden());
    var old =
        settings.list().stream()
            .filter(e -> e.definition().key().equals("security.mfa_required"))
            .findFirst()
            .orElseThrow();
    try {
      settings.save(
          List.of(new Settings.Change("security.mfa_required", "false", old.revision(), false)),
          admin.id());
      mvc.perform(get("/api/admin/catalog/products").cookie(plain)).andExpect(status().isOk());
    } finally {
      var current =
          settings.list().stream()
              .filter(e -> e.definition().key().equals("security.mfa_required"))
              .findFirst()
              .orElseThrow();
      settings.save(
          List.of(new Settings.Change("security.mfa_required", "true", current.revision(), false)),
          admin.id());
    }
    mvc.perform(get("/api/admin/catalog/products").cookie(plain)).andExpect(status().isForbidden());
  }

  @Test
  void customRoleOnlyOpensGrantedMenuAndCannotReadSettings() throws Exception {
    String role = key(), id = user();
    Cookie administrator = cookie(admin.id(), "ADMIN", true);
    mvc.perform(
            put("/api/admin/roles/" + role)
                .cookie(administrator)
                .header("X-AIWYW-Request", "1")
                .contentType("application/json")
                .content("{\"name\":\"目录编辑\",\"menus\":[\"applications\"],\"revision\":0}"))
        .andExpect(status().isOk());
    db.update("INSERT INTO access_user_role VALUES(?,?)", id, role);
    Cookie custom = cookie(id, "USER", false);
    mvc.perform(get("/api/admin/catalog/products").cookie(custom)).andExpect(status().isOk());
    mvc.perform(get("/api/admin/settings").cookie(custom)).andExpect(status().isForbidden());
    mvc.perform(
            delete("/api/admin/roles/" + role)
                .queryParam("revision", "1")
                .cookie(administrator)
                .header("X-AIWYW-Request", "1"))
        .andExpect(status().isConflict());
  }

  @Test
  void passwordChangeVerifiesOldPasswordAndRevokesSessionsAndResetLinks() throws Exception {
    String id = user(), token = crypto.token();
    Cookie first = cookie(id, "USER", false), second = cookie(id, "USER", false);
    db.update(
        "INSERT INTO account_action_token(token_hash,user_id,purpose,expires_at,created_at)"
            + " VALUES(?,?,'RESET',?,?)",
        crypto.digest(token),
        id,
        System.currentTimeMillis() + 600000,
        System.currentTimeMillis());
    mvc.perform(
            put("/api/member/password")
                .cookie(first)
                .header("X-AIWYW-Request", "1")
                .contentType("application/json")
                .content(
                    "{\"oldPassword\":\"wrong-password\",\"newPassword\":\"new-password-secure-456\"}"))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/api/member/profile").cookie(second)).andExpect(status().isOk());
    mvc.perform(
            put("/api/member/password")
                .cookie(first)
                .header("X-AIWYW-Request", "1")
                .contentType("application/json")
                .content(
                    "{\"oldPassword\":\"isolated-test-password-123\",\"newPassword\":\"new-password-secure-456\"}"))
        .andExpect(status().isOk());
    mvc.perform(get("/api/member/profile").cookie(first)).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/member/profile").cookie(second)).andExpect(status().isUnauthorized());
    assertTrue(
        crypto.verifyPassword(
            "new-password-secure-456",
            db.queryForObject("SELECT password_hash FROM app_user WHERE id=?", String.class, id)));
    assertThrows(
        Api.Failure.class, () -> accounts.consume(token, "RESET", "another-reset-password-789"));
    assertThrows(
        Api.Failure.class,
        () ->
            authentication.login(
                id + "@example.com", "isolated-test-password-123", "", "old-password-test"));
    assertNotNull(
        authentication.login(
            id + "@example.com", "new-password-secure-456", "", "new-password-test"));
    assertFalse(
        db.queryForObject(
                "SELECT detail FROM finance_audit WHERE actor_id=? AND"
                    + " action_name='PASSWORD_CHANGED'",
                String.class,
                id)
            .contains("new-password-secure-456"));
  }

  @Test
  void parentMenuDoesNotGrantChildAndChildPersistsParent() throws Exception {
    String role = key(), id = user();
    Cookie administrator = cookie(admin.id(), "ADMIN", true);
    String url = "/api/admin/roles/" + role;
    mvc.perform(
            put(url)
                .cookie(administrator)
                .header("X-AIWYW-Request", "1")
                .contentType("application/json")
                .content("{\"name\":\"父级独立选择\",\"menus\":[\"group:products\"],\"revision\":0}"))
        .andExpect(status().isOk());
    assertEquals(
        List.of("group:products"),
        db.queryForList(
            "SELECT menu_id FROM access_role_menu WHERE role_id=?", String.class, role));
    db.update("INSERT INTO access_user_role VALUES(?,?)", id, role);
    Cookie member = cookie(id, "USER", false);
    mvc.perform(get("/api/admin/catalog/products").cookie(member))
        .andExpect(status().isForbidden());
    mvc.perform(
            put(url)
                .cookie(administrator)
                .header("X-AIWYW-Request", "1")
                .contentType("application/json")
                .content("{\"name\":\"子级自动补父级\",\"menus\":[\"applications\"],\"revision\":1}"))
        .andExpect(status().isOk());
    assertTrue(
        db.queryForList("SELECT menu_id FROM access_role_menu WHERE role_id=?", String.class, role)
            .containsAll(List.of("applications", "group:products")));
    mvc.perform(get("/api/admin/catalog/products").cookie(member)).andExpect(status().isOk());
    mvc.perform(get("/api/admin/catalog/tags").cookie(member)).andExpect(status().isForbidden());
  }

  @Autowired ContentService content;

  ContentService.Input article(String slug, String title, String body, long revision) {
    return new ContentService.Input(
        slug,
        "ARTICLE",
        title,
        "English " + title,
        "发布摘要",
        "Published summary",
        body,
        "<p>English published body</p>",
        revision);
  }

  long contentRevision(String id) {
    return ((Number) content.draft(id).get("revision")).longValue();
  }

  @Test
  void contentDraftIsPrivateAndPublishedSnapshotDoesNotFollowDraft() {
    String slug = "article-" + key(),
        id =
            content.save(
                null,
                article(
                    slug,
                    "发布标题" + slug,
                    "<h2>旧发布正文</h2><script>alert(1)</script><img src='https://example.com/x'"
                        + " onerror='alert(1)'>",
                    0),
                admin.id());
    assertThrows(Api.Failure.class, () -> content.page(slug, "zh"));
    content.publish(id, contentRevision(id), admin.id());
    String first = content.page(slug, "zh").html();
    assertTrue(first.contains("旧发布正文"));
    assertFalse(first.contains("onerror="));
    assertFalse(first.contains("<script>alert"));
    assertTrue(first.contains("application/ld+json"));
    assertTrue(content.page(slug, "en").html().contains("English published body"));
    content.save(id, article(slug, "尚未发布" + slug, "<p>未公开草稿</p>", contentRevision(id)), admin.id());
    assertEquals(first, content.page(slug, "zh").html());
    assertEquals(
        0L,
        content
            .list(false, Set.of(), "尚未发布" + slug, "", 1, 20, "created_at", "descending")
            .get("total"));
    content.publish(id, contentRevision(id), admin.id());
    assertTrue(content.page(slug, "zh").html().contains("未公开草稿"));
    assertThrows(
        Api.Failure.class,
        () ->
            content.save(
                id, article("new-" + key(), "改地址", "<p>x</p>", contentRevision(id)), admin.id()));
  }

  @Test
  void failedContentPublicationRetainsPreviousPointerAndFiles() throws Exception {
    String slug = "failure-" + key(),
        id = content.save(null, article(slug, "发布失败保护", "<p>原正文</p>", 0), admin.id());
    content.publish(id, contentRevision(id), admin.id());
    String before =
        db.queryForObject(
            "SELECT active_publication FROM content_document WHERE id=?", String.class, id);
    Path folder = crypto.root.resolve("content-pages"),
        backup = crypto.root.resolve("content-pages-" + key());
    Files.move(folder, backup);
    Files.writeString(folder, "isolated test blocker");
    try {
      assertThrows(Api.Failure.class, () -> content.publish(id, contentRevision(id), admin.id()));
      assertEquals(
          before,
          db.queryForObject(
              "SELECT active_publication FROM content_document WHERE id=?", String.class, id));
    } finally {
      Files.delete(folder);
      Files.move(backup, folder);
    }
    assertTrue(content.page(slug, "zh").html().contains("原正文"));
  }

  @Test
  void contentMenuBoundariesSeoWhitelistAndFaqStructuredData() throws Exception {
    String slug = "faq-" + key(),
        faq =
            content.save(
                null,
                new ContentService.Input(
                    slug, "FAQ", "如何使用？", "", "使用说明", "", "<p>操作说明</p>", "", 0),
                admin.id());
    content.publish(faq, contentRevision(faq), admin.id());
    assertTrue(content.page(slug, "zh").html().contains("\"@type\":\"FAQPage\""));
    String role = key(), id = user();
    db.update(
        "INSERT INTO access_role VALUES(?, '内容角色',FALSE,1,?)", role, System.currentTimeMillis());
    db.update("INSERT INTO access_role_menu VALUES(?,'articles')", role);
    db.update("INSERT INTO access_role_menu VALUES(?,'seo')", role);
    db.update("INSERT INTO access_user_role VALUES(?,?)", id, role);
    Cookie staff = cookie(id, "USER", false);
    mvc.perform(get("/api/admin/content/" + faq).cookie(staff)).andExpect(status().isForbidden());
    mvc.perform(get("/api/admin/content").param("scope", "faq").cookie(staff))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/admin/seo").cookie(staff)).andExpect(status().isOk());
    mvc.perform(get("/api/admin/settings").cookie(staff)).andExpect(status().isForbidden());
    mvc.perform(
            put("/api/admin/seo")
                .cookie(staff)
                .header("X-AIWYW-Request", "1")
                .contentType("application/json")
                .content(
                    "{\"changes\":[{\"key\":\"mail.password\",\"value\":\"not-allowed\",\"revision\":0,\"clear\":false}]}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void contentReferencesProtectPublishedMediaAndUnlistRemovesDiscovery() throws Exception {
    byte[] image = getClass().getResourceAsStream("/tiny.webp").readAllBytes();
    var asset =
        storage.upload(
            new MockMultipartFile("file", "cover.webp", "image/webp", image), "IMAGE", admin.id());
    String slug = "media-" + key(),
        id =
            content.save(
                null,
                article(slug, "图片引用保护", "<p>正文</p><img src='" + asset.url() + "'>", 0),
                admin.id());
    assertThrows(Api.Failure.class, () -> storage.delete(asset.id(), admin.id()));
    content.publish(id, contentRevision(id), admin.id());
    mvc.perform(get("/api/public/content-pages/" + slug))
        .andExpect(status().isOk())
        .andExpect(header().exists("ETag"));
    String sitemap =
        mvc.perform(get("/api/public/seo/sitemap")).andReturn().getResponse().getContentAsString();
    assertTrue(sitemap.contains("/articles/" + slug));
    content.unlist(id, contentRevision(id), admin.id());
    assertThrows(Api.Failure.class, () -> content.page(slug, "zh"));
    assertThrows(Api.Failure.class, () -> content.remove(id, contentRevision(id), admin.id()));
    assertThrows(Api.Failure.class, () -> storage.delete(asset.id(), admin.id()));
    assertFalse(
        mvc.perform(get("/api/public/seo/sitemap"))
            .andReturn()
            .getResponse()
            .getContentAsString()
            .contains("/articles/" + slug));
    String draft =
        content.save(null, article("draft-" + key(), "可删除草稿", "<p>x</p>", 0), admin.id());
    content.remove(draft, contentRevision(draft), admin.id());
    assertThrows(Api.Failure.class, () -> content.draft(draft));
  }

  @Test
  void seoIndexSwitchIsDynamicForAlreadyPublishedHtml() throws Exception {
    String slug = "index-" + key(),
        id = content.save(null, article(slug, "索引控制", "<p>公开正文</p>", 0), admin.id());
    content.publish(id, contentRevision(id), admin.id());
    var old =
        settings.list().stream()
            .filter(e -> e.definition().key().equals("seo.allow_indexing"))
            .findFirst()
            .orElseThrow();
    try {
      settings.save(
          List.of(new Settings.Change("seo.allow_indexing", "false", old.revision(), false)),
          admin.id());
      mvc.perform(get("/api/public/content-pages/" + slug))
          .andExpect(header().string("X-Robots-Tag", "noindex, nofollow"));
      assertTrue(
          mvc.perform(get("/api/public/seo/robots"))
              .andReturn()
              .getResponse()
              .getContentAsString()
              .contains("Disallow: /"));
      assertFalse(
          mvc.perform(get("/api/public/seo/sitemap"))
              .andReturn()
              .getResponse()
              .getContentAsString()
              .contains("/articles/" + slug));
    } finally {
      var current =
          settings.list().stream()
              .filter(e -> e.definition().key().equals("seo.allow_indexing"))
              .findFirst()
              .orElseThrow();
      settings.save(
          List.of(
              new Settings.Change("seo.allow_indexing", old.value(), current.revision(), false)),
          admin.id());
    }
  }
}
