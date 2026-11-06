package com.aiwyw;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.List;
import java.util.zip.*;
import javax.imageio.ImageIO;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.*;

/** 用户明确要求的本地合成验收数据。仅能作用于专用MySQL验证库，不注册任何网站入口，不发邮件。 */
public final class LocalValidationSeeder {
  static final ObjectMapper JSON = new ObjectMapper();

  static String id() {
    return UUID.randomUUID().toString();
  }

  static BigDecimal points(int value) {
    return BigDecimal.valueOf(value);
  }

  static MockHttpServletRequest login(
      Auth auth, SecureStore crypto, String email, String password, boolean enroll) {
    var request = new MockHttpServletRequest();
    request.setRemoteAddr("127.0.0.1");
    String token = auth.login(email, password, "", "127.0.0.1");
    request.setCookies(new Cookie("AIWYW_SESSION", token));
    if (enroll) {
      String secret = auth.enroll(auth.user(request, false));
      auth.confirm(
          auth.user(request, false),
          crypto.totp(secret, System.currentTimeMillis() / 30000),
          request);
    }
    return request;
  }

  static byte[] image(int index) throws Exception {
    var image = new BufferedImage(320, 220, BufferedImage.TYPE_INT_RGB);
    var g = image.createGraphics();
    g.setColor(new Color(242 - index * 2, 237, 255));
    g.fillRect(0, 0, 320, 220);
    g.setColor(new Color(112, 92, 191));
    g.setFont(new Font("SansSerif", Font.BOLD, 42));
    g.drawString("REVIEW " + index, 36, 112);
    g.dispose();
    var output = new ByteArrayOutputStream();
    ImageIO.write(image, "png", output);
    return output.toByteArray();
  }

  static byte[] archive(int index) throws Exception {
    var buffer = new ByteArrayOutputStream();
    try (var zip = new ZipOutputStream(buffer)) {
      zip.putNextEntry(new ZipEntry("README.txt"));
      zip.write(
          ("AI万有网 本地验收下载样本 " + index + "\n只用于验证上传、静态发布、兑换与下载，不是正式应用。\n")
              .getBytes(java.nio.charset.StandardCharsets.UTF_8));
      zip.closeEntry();
    }
    return buffer.toByteArray();
  }

  public static void main(String[] args) throws Exception {
    if (args.length != 2 || !args[1].equals("--confirm-local-validation"))
      throw new IllegalArgumentException("仅允许显式本地验收数据生成");
    Path root = Path.of(args[0]).toAbsolutePath().normalize();
    if (!Files.isRegularFile(root.resolve("bootstrap.enc"))
        || !Files.isRegularFile(root.resolve("master.key")))
      throw new IllegalStateException("需先完成MySQL迁移，不能创建空密钥");
    var store = new SecureStore(root.toString());
    var spec =
        JSON.readValue(
            store.decrypt(Files.readString(root.resolve("bootstrap.enc"))),
            Database.ConnectionSpec.class);
    if (!spec.engine().equals("MYSQL") || !spec.database().startsWith("aiwyw_local_review_"))
      throw new IllegalStateException("拒绝向普通或生产数据库生成合成数据");
    Path manifest = root.resolve("validation-seed.enc");
    Map<String, String> secrets;
    if (Files.exists(manifest))
      secrets = JSON.readValue(store.decrypt(Files.readString(manifest)), Map.class);
    else {
      secrets = new LinkedHashMap<>();
      secrets.put("batch", UUID.randomUUID().toString().substring(0, 8));
      secrets.put("password", store.token() + "a9!");
      Files.writeString(manifest, store.encrypt(JSON.writeValueAsString(secrets)));
      store.protect(manifest);
    }
    String batch = secrets.get("batch"),
        prefix = "review-" + batch,
        marker = "LOCAL_REVIEW_" + batch;
    var application = new SpringApplication(Application.class);
    application.setWebApplicationType(WebApplicationType.NONE);
    application.setDefaultProperties(
        Map.of("aiwyw.data-dir", root.toString(), "spring.main.banner-mode", "off"));
    // 离线生成器停用调度器，防止导入旧队列或合成场景时对外发信/执行清理；网站运行策略不变。
    application.addInitializers(
        context ->
            context.addBeanFactoryPostProcessor(
                factory -> {
                  var registry = (BeanDefinitionRegistry) factory;
                  String name =
                      "org.springframework.context.annotation.internalScheduledAnnotationProcessor";
                  if (registry.containsBeanDefinition(name)) registry.removeBeanDefinition(name);
                }));
    try (var context =
        application.run("--aiwyw.data-dir=" + root, "--spring.main.web-application-type=none")) {
      var db = context.getBean(JdbcTemplate.class);
      try (var connection = db.getDataSource().getConnection()) {
        if (!spec.database().equals(connection.getCatalog())
            || !connection.getMetaData().getDatabaseProductName().contains("MySQL"))
          throw new IllegalStateException("实际数据库与专用验证库不一致，拒绝写入");
      }
      var crypto = context.getBean(SecureStore.class);
      var settings = context.getBean(Settings.class);
      if (!secrets.containsKey("originalOutbox")) {
        secrets.put(
            "originalOutbox",
            JSON.writeValueAsString(db.queryForList("SELECT id FROM mail_outbox", String.class)));
        Files.writeString(manifest, store.encrypt(JSON.writeValueAsString(secrets)));
        store.protect(manifest);
      }
      if (db.queryForObject(
              "SELECT COUNT(*) FROM schema_migration WHERE version=?", Integer.class, marker)
          > 0) {
        System.out.println("本批验收数据已生成，未重复写入。");
        return;
      }
      var catalog = context.getBean(Catalog.class);
      catalog.initialize();
      var content = context.getBean(ContentService.class);
      var commerce = context.getBean(Commerce.class);
      var storage = context.getBean(Storage.class);
      var api = context.getBean(MeteredApi.class);
      var support = context.getBean(SupportService.class);
      var auth = context.getBean(Auth.class);
      var licenses = context.getBean(Licenses.class);
      long now = System.currentTimeMillis();
      List<String> users = new ArrayList<>();
      List<String> roles =
          List.of(
              "ADMIN",
              "USER",
              "USER",
              "USER",
              "OPERATOR",
              "FINANCE",
              "SUPPORT",
              "USER",
              "USER",
              "USER",
              "USER",
              "USER",
              "USER",
              "USER",
              "USER");
      for (int i = 0; i < roles.size(); i++) {
        String email = prefix + "-u" + i + "@example.com";
        var old = db.queryForList("SELECT id FROM app_user WHERE email=?", String.class, email);
        String user = old.isEmpty() ? id() : old.get(0);
        users.add(user);
        if (old.isEmpty()) {
          long at = now - i * 86400000L;
          db.update(
              "INSERT INTO"
                  + " app_user(id,email,password_hash,role_name,mfa_enabled,last_totp_step,created_at,email_verified_at,nickname,disabled)"
                  + " VALUES(?,?,?,?,FALSE,-1,?,?,?,?)",
              user,
              email,
              crypto.password(secrets.get("password")),
              roles.get(i),
              at,
              (i == 2 || i == 8) ? null : at,
              "验收" + batch + "用户" + String.format("%02d", i + 1),
              i == 3);
          db.update("INSERT INTO access_user_role VALUES(?,?)", user, roles.get(i));
          Origins.created(db, "app_user", user, "SYSTEM", at);
        }
      }
      // 本批独立管理员先真实登录、绑定TOTP并验证；后续财务/审核使用该已验证会话，不关闭MFA策略。
      MockHttpServletRequest adminRequest;
      var bound =
          db.queryForObject(
              "SELECT totp_secret FROM app_user WHERE id=?", String.class, users.get(0));
      if (secrets.containsKey("adminSession")) {
        adminRequest = new MockHttpServletRequest();
        adminRequest.setCookies(new Cookie("AIWYW_SESSION", secrets.get("adminSession")));
      } else if (bound == null)
        adminRequest =
            login(auth, crypto, prefix + "-u0@example.com", secrets.get("password"), true);
      else {
        adminRequest = new MockHttpServletRequest();
        adminRequest.setRemoteAddr("127.0.0.1");
        String token =
            auth.login(
                prefix + "-u0@example.com",
                secrets.get("password"),
                crypto.totp(crypto.decrypt(bound), System.currentTimeMillis() / 30000),
                "127.0.0.1");
        adminRequest.setCookies(new Cookie("AIWYW_SESSION", token));
      }
      var admin = auth.admin(adminRequest);
      String actor = admin.id();
      secrets.put("adminSession", adminRequest.getCookies()[0].getValue());
      Files.writeString(manifest, store.encrypt(JSON.writeValueAsString(secrets)));
      store.protect(manifest);
      String provider = settings.value("storage.provider");
      if (!provider.equals("LOCAL")) {
        var entry =
            settings.list().stream()
                .filter(e -> e.definition().key().equals("storage.provider"))
                .findFirst()
                .orElseThrow();
        settings.save(
            List.of(new Settings.Change("storage.provider", "LOCAL", entry.revision(), false)),
            actor);
      }
      List<String> owner = List.of(users.get(1), users.get(7), users.get(9));
      for (String user : owner) {
        if (db.queryForObject(
                "SELECT COUNT(*) FROM wallet_adjustment WHERE actor_id=? AND request_key=?",
                Integer.class,
                actor,
                prefix + "-gift-" + user)
            == 0)
          commerce.adjust(
              admin,
              new Commerce.Adjustment(
                  user, points(50), 365, "本地验收赠分，成本0，无真实资金", prefix + "-gift-" + user));
      }
      List<String> imageUrls = new ArrayList<>(), downloads = new ArrayList<>();
      for (int i = 1; i <= 10; i++) {
        for (String purpose : List.of("IMAGE", "DOWNLOAD")) {
          String filename =
              prefix
                  + "-"
                  + purpose.toLowerCase(Locale.ROOT)
                  + "-"
                  + i
                  + (purpose.equals("IMAGE") ? ".png" : ".zip");
          var existing =
              db.queryForList(
                  "SELECT id FROM storage_asset WHERE original_name=? AND created_by=? AND"
                      + " status='READY'",
                  String.class,
                  filename,
                  actor);
          String asset;
          if (existing.isEmpty())
            asset =
                storage
                    .upload(
                        new MockMultipartFile(
                            "file",
                            filename,
                            purpose.equals("IMAGE") ? "image/png" : "application/zip",
                            purpose.equals("IMAGE") ? image(i) : archive(i)),
                        purpose,
                        actor)
                    .id();
          else asset = existing.get(0);
          if (purpose.equals("IMAGE")) imageUrls.add("/api/public/media/" + asset);
          else downloads.add("asset:" + asset);
        }
      }
      // 样本落本地盘，随后恢复原默认模式；资产持有独立存储快照，不影响旧文件或后续真实上传。
      if (!provider.equals("LOCAL")) {
        var entry =
            settings.list().stream()
                .filter(e -> e.definition().key().equals("storage.provider"))
                .findFirst()
                .orElseThrow();
        settings.save(
            List.of(new Settings.Change("storage.provider", provider, entry.revision(), false)),
            actor);
      }
      var roleController = context.getBean(RoleController.class);
      List<List<String>> menuCases =
          List.of(
              List.of("applications"),
              List.of("articles", "faq"),
              List.of("statistics"),
              List.of("conversations", "messages"),
              List.of("orders", "recharge"),
              List.of("apis", "plans"),
              List.of("keys", "usage"),
              List.of("tags", "sections"),
              List.of("storage"),
              List.of("dashboard", "security"));
      for (int i = 0; i < 10; i++) {
        String name = "验收" + batch + "角色" + (i + 1);
        if (db.queryForObject("SELECT COUNT(*) FROM access_role WHERE name=?", Integer.class, name)
            == 0)
          roleController.save(
              id(), new RoleController.Input(name, menuCases.get(i), 0), adminRequest);
      }
      for (int i = 1; i <= 10; i++) {
        String name = "验收" + batch + "积分包" + i + " · " + (i % 2 == 0 ? "短期促销" : "常规优惠");
        if (db.queryForObject(
                "SELECT COUNT(*) FROM points_package WHERE name=?", Integer.class, name)
            == 0)
          commerce.savePackage(
              null,
              new Commerce.PackageInput(
                  name,
                  "Review package " + i,
                  points(i * 5),
                  points(i * 60),
                  i % 2 == 0 ? 7 : 365,
                  i <= 3
                      && db.queryForObject(
                              "SELECT COUNT(*) FROM points_package WHERE enabled=TRUE",
                              Integer.class)
                          < settings.integer("wallet.visible_packages"),
                  i,
                  0),
              actor);
      }
      List<String> definitions = new ArrayList<>(), plans = new ArrayList<>();
      for (int i = 0; i < 10; i++) {
        String slug = prefix + "-api-" + (i + 1);
        var existing =
            db.queryForList("SELECT id FROM api_definition WHERE slug=?", String.class, slug);
        String definition =
            existing.isEmpty()
                ? api.saveDefinition(
                    null,
                    new MeteredApi.Definition(
                        slug,
                        "验收" + batch + "API" + (i + 1),
                        "Review API " + (i + 1),
                        "本地合成验收：" + (i % 2 == 0 ? "文本统计" : "SHA256") + "，无外部上游。",
                        "Local validation fixture",
                        i % 2 == 0 ? "TEXT_STATS" : "SHA256",
                        "",
                        "",
                        false,
                        "/success",
                        "true",
                        i < 2 ? BigDecimal.ZERO : points(i),
                        i < 8,
                        0),
                    actor)
                : existing.get(0);
        definitions.add(definition);
        String planName = "验收" + batch + "套餐" + (i + 1) + " · " + (i % 2 == 0 ? "月度" : "短周期");
        var prior = db.queryForList("SELECT id FROM api_plan WHERE name=?", String.class, planName);
        plans.add(
            prior.isEmpty()
                ? api.savePlan(
                    null,
                    new MeteredApi.Plan(
                        definitions.get(i == 2 ? 0 : i < 8 ? i : 0),
                        planName,
                        points(5 + i),
                        100 + i * 100,
                        i % 2 == 0 ? 30 : 7,
                        true,
                        0),
                    actor)
                : prior.get(0));
      }
      List<String> toolIds = new ArrayList<>(),
          software = new ArrayList<>(),
          resources = new ArrayList<>();
      for (String type : List.of("TOOL", "SOFTWARE", "RESOURCE", "API"))
        for (int i = 0; i < 10; i++) {
          String slug =
              type.equals("API")
                  ? prefix + "-api-" + (i + 1)
                  : prefix + "-" + type.toLowerCase(Locale.ROOT) + "-" + (i + 1);
          var old =
              db.queryForList("SELECT id FROM catalog_product WHERE slug=?", String.class, slug);
          String product;
          if (old.isEmpty()) {
            String title =
                "验收"
                    + batch
                    + " · "
                    + Map.of("TOOL", "免费工具", "SOFTWARE", "授权软件", "RESOURCE", "数字资源", "API", "API服务")
                        .get(type)
                    + String.format("%02d", i + 1);
            String body =
                "<h2>本地验收样本</h2><p>用于验证分类、标签、版本、存储、发布及交易。由用户要求生成的合成数据，不是正式商品。</p><p>场景编号："
                    + (i + 1)
                    + "</p>";
            product =
                catalog.saveProduct(
                    null,
                    new Catalog.ProductInput(
                        slug,
                        title,
                        "Review " + type + " " + (i + 1),
                        "用于" + type + "场景验证的合成样本，序号" + (i + 1),
                        "Synthetic local validation sample",
                        body,
                        body,
                        List.of("验收" + batch + "标签" + (i + 1), "验收类型-" + type),
                        imageUrls.get(i),
                        List.of(imageUrls.get(i)),
                        type,
                        type.equals("TOOL") ? BigDecimal.ZERO : points(i + 1),
                        "1." + (i / 5) + "." + i,
                        type.equals("SOFTWARE")
                            ? "LICENSE"
                            : type.equals("API") ? "API" : "DOWNLOAD",
                        type.equals("API") ? "" : downloads.get(i),
                        0),
                    actor);
            if (type.equals("SOFTWARE")) licenses.policy(actor, product, 1 + i % 3, 0);
            if (!type.equals("API") || i < 8) catalog.publish(product, 1, actor, settings);
            db.update(
                "UPDATE catalog_product SET created_at=created_at-?,first_published_at=CASE WHEN"
                    + " first_published_at>0 THEN first_published_at-? ELSE first_published_at END"
                    + " WHERE id=?",
                i * 86400000L,
                i * 86400000L,
                product);
          } else product = old.get(0);
          if (type.equals("TOOL")) toolIds.add(product);
          if (type.equals("SOFTWARE")) software.add(product);
          if (type.equals("RESOURCE")) resources.add(product);
        }
      for (String kind : List.of("ARTICLE", "TUTORIAL", "FAQ", "CASE"))
        for (int i = 1; i <= 10; i++) {
          String slug = prefix + "-" + kind.toLowerCase(Locale.ROOT) + "-" + i;
          if (db.queryForObject(
                  "SELECT COUNT(*) FROM content_document WHERE slug=?", Integer.class, slug)
              > 0) continue;
          String title = "验收" + batch + " · " + kind + " 内容" + i;
          String key =
              content.save(
                  null,
                  new ContentService.Input(
                      slug,
                      kind,
                      title,
                      "Review " + kind + " " + i,
                      "本地合成内容，验证图文、双语和发布状态。",
                      "Synthetic local content",
                      "<h2>场景 "
                          + i
                          + "</h2><p>本内容用于本地验收。</p><img src=\""
                          + imageUrls.get(i - 1)
                          + "\" alt=\"验收配图\">",
                      "<h2>Review</h2><p>Local validation sample.</p>",
                      0),
                  actor);
          if (i <= 8) content.publish(key, 1, actor);
          if (i == 8) content.unlist(key, 2, actor);
          db.update(
              "UPDATE content_document SET created_at=created_at-? WHERE id=?", i * 86400000L, key);
        }
      for (int i = 0; i < 10; i++) {
        String user = owner.get(i % owner.size());
        var recharge =
            commerce.createRecharge(
                user, new Commerce.Recharge(points(50 + i), null, prefix + "-topup-" + i));
        String key = (String) recharge.get("id");
        if (recharge.get("state").equals("PENDING") && i < 8)
          commerce.declare(user, key, null, "本地模拟付款声明，无实际转账");
        String current =
            db.queryForObject("SELECT state FROM recharge_order WHERE id=?", String.class, key);
        if (i < 5 && !current.equals("POSTED"))
          commerce.reviewRecharge(
              admin,
              key,
              new BigDecimal(String.valueOf(recharge.get("price_fen"))).movePointLeft(2),
              true,
              "本地验收模拟核销");
        if (i == 7 && !current.equals("REJECTED"))
          commerce.reviewRecharge(admin, key, BigDecimal.ZERO, false, "本地验收：声明不符，驳回");
      }
      List<String> purchases = new ArrayList<>();
      for (var group : List.of(software, resources))
        for (int i = 0; i < group.size(); i++) {
          String product = group.get(i), user = owner.get(i % 3);
          var draft = catalog.draft(product);
          purchases.add(
              commerce.purchase(
                  user,
                  new Commerce.Purchase(
                      draft.slug(), draft.publicationId(), prefix + "-purchase-" + product)));
        }
      for (int i = 0; i < 10; i++) {
        String purchase = purchases.get(i), user = owner.get(i % 3);
        if (db.queryForObject(
                "SELECT COUNT(*) FROM refund_request WHERE purchase_id=?", Integer.class, purchase)
            == 0) commerce.requestRefund(user, purchase, "本地验收：整单退积分样例" + (i + 1));
        var row =
            db.queryForMap(
                "SELECT state,revision FROM refund_request WHERE purchase_id=?", purchase);
        if (i < 3 && row.get("state").equals("PENDING"))
          commerce.reviewRefund(
              admin, purchase, true, "本地验收批准退款", ((Number) row.get("revision")).longValue());
        else if (i == 3 && row.get("state").equals("PENDING"))
          commerce.reviewRefund(
              admin, purchase, false, "本地验收驳回退款", ((Number) row.get("revision")).longValue());
      }
      int resets = 0;
      for (int i = 3; i < 10; i++) {
        String purchase = purchases.get(i), user = owner.get(i % 3);
        var license = licenses.detail(user, purchase);
        int devices = List.of(4, 5, 7).contains(i) ? 2 : 1;
        for (int n = 0; n < devices; n++) {
          // 与真实客户端一致，使用足够长且可重复的安装标识，不放宽授权入口校验。
          String device = crypto.digest(prefix + "-device-" + i + "-" + n);
          var activation =
              licenses.activate(
                  new Licenses.Activation(
                      (String) license.get("key"),
                      device,
                      "验收设备" + (resets + 1),
                      software.get(i),
                      "1.0.0"));
          String binding = (String) activation.get("bindingId");
          var existing =
              db.queryForList(
                  "SELECT id FROM license_unbind_request WHERE purchase_id=? AND device_id=?",
                  String.class,
                  purchase,
                  binding);
          if (existing.isEmpty()) {
            String reset = licenses.request(user, purchase, binding, "本地验收换机申请");
            if (resets < 2) licenses.review(admin, reset, true, "本地验收批准换机", 1);
            else if (resets == 2) licenses.review(admin, reset, false, "本地验收驳回换机", 1);
          }
          resets++;
        }
      }
      List<String> keyIds = new ArrayList<>();
      for (int i = 0; i < 10; i++) {
        String user = owner.get(i % 3), name = "验收" + batch + "Key" + (i + 1);
        var previous =
            db.queryForList(
                "SELECT id FROM api_key WHERE user_id=? AND name=?", String.class, user, name);
        String key =
            previous.isEmpty()
                ? (String)
                    api.saveKey(
                            user,
                            null,
                            new MeteredApi.KeyInput(
                                name,
                                definitions.subList(0, 8),
                                30,
                                1000L,
                                now + 86400000L * (i + 1),
                                true,
                                0))
                        .get("id")
                : previous.get(0);
        keyIds.add(key);
        String plan = plans.get(i);
        long revision =
            db.queryForObject("SELECT revision FROM api_plan WHERE id=?", Long.class, plan);
        String subscription =
            api.buy(user, new MeteredApi.Purchase(plan, revision, prefix + "-subscription-" + i));
        if (db.queryForObject(
                "SELECT refund_state FROM api_subscription WHERE id=?", String.class, subscription)
            .equals("NONE")) api.requestRefund(user, subscription, "本地验收：未使用套餐退款样例");
        if (i < 4) {
          long refundRevision =
              db.queryForObject(
                  "SELECT revision FROM api_subscription WHERE id=?", Long.class, subscription);
          String refundState =
              db.queryForObject(
                  "SELECT refund_state FROM api_subscription WHERE id=?",
                  String.class,
                  subscription);
          if (refundState.equals("PENDING"))
            api.reviewRefund(admin, subscription, i < 3, "本地验收套餐退款审核", refundRevision);
        }
        api.preference(user, definitions.get(2), true, false);
        try {
          api.invoke(
              user,
              key,
              prefix + "-api-3",
              prefix + "-call-" + i,
              JSON.readTree(i < 8 ? "{\"text\":\"Local validation\"}" : "{\"invalid\":true}"),
              true);
        } catch (Api.Failure expected) {
          if (i < 8) throw expected;
        }
      }
      db.update("UPDATE api_key SET enabled=FALSE,revision=revision+1 WHERE id=?", keyIds.get(9));
      db.update("UPDATE api_key SET expires_at=? WHERE id=?", now - 60000, keyIds.get(8));
      // 每类反馈10条，部分审核通过/驳回，其余待审；客人不填邮箱，因此没有外部邮件。
      for (int i = 0; i < 10; i++) {
        String subject = "验收" + batch + "咨询" + (i + 1);
        var existing =
            db.queryForList("SELECT * FROM support_conversation WHERE subject=?", subject);
        SupportService.Principal principal;
        String conversation;
        if (existing.isEmpty()) {
          var request = new MockHttpServletRequest();
          var response = new MockHttpServletResponse();
          principal = context.getBean(SupportController.class).principal(request, response);
          if (i >= 5) {
            String member = owner.get(i % 3);
            principal =
                new SupportService.Principal(
                    member,
                    principal.visitor(),
                    db.queryForObject(
                        "SELECT nickname FROM app_user WHERE id=?", String.class, member));
          }
          conversation =
              support.create(
                  principal, new SupportService.NewConversation(subject, "GENERAL", "", false));
        } else {
          var row = existing.get(0);
          conversation = (String) row.get("id");
          principal =
              new SupportService.Principal(
                  (String) row.get("user_id"), (String) row.get("visitor_id"), "验收用户");
        }
        if (db.queryForObject(
                "SELECT state FROM support_conversation WHERE id=?", String.class, conversation)
            .equals("OPEN")) {
          support.send(
              principal,
              conversation,
              null,
              new SupportService.Message(prefix + "-message-" + i, "本地验收咨询内容：场景" + (i + 1), null));
          if (i < 5) {
            if (db.queryForObject(
                    "SELECT assigned_to FROM support_conversation WHERE id=?",
                    String.class,
                    conversation)
                == null)
              support.assign(
                  admin,
                  conversation,
                  actor,
                  db.queryForObject(
                      "SELECT revision FROM support_conversation WHERE id=?",
                      Long.class,
                      conversation));
            support.send(
                null,
                conversation,
                admin,
                new SupportService.Message(prefix + "-reply-" + i, "本地验收客服答复", null));
          }
          if (i < 2)
            support.state(
                principal,
                conversation,
                null,
                "CLOSED",
                db.queryForObject(
                    "SELECT revision FROM support_conversation WHERE id=?",
                    Long.class,
                    conversation));
        }
        for (String kind : List.of("PUBLIC", "PRIVATE")) {
          String body = "验收" + batch + " · " + kind + " 意见样例" + (i + 1);
          var prior =
              db.queryForList(
                  "SELECT id FROM support_feedback WHERE kind=? AND body=?",
                  String.class,
                  kind,
                  body);
          String feedback =
              prior.isEmpty()
                  ? support.feedback(principal, new SupportService.Feedback(kind, body))
                  : prior.get(0);
          if (i < 4
              && db.queryForObject(
                      "SELECT state FROM support_feedback WHERE id=?", String.class, feedback)
                  .equals("PENDING"))
            support.reviewFeedback(
                admin,
                feedback,
                i < 3 ? kind.equals("PUBLIC") ? "APPROVED" : "RESOLVED" : "REJECTED",
                "本地验收处理意见",
                1);
        }
      }
      // 来源、关键词及活跃列表分别有十组事实；明确使用本地合成来源，不冒充真实访客。
      for (int i = 0; i < 10; i++) {
        String visitor = crypto.digest(prefix + "-visitor-" + i),
            user = users.get(new int[] {1, 2, 7, 8, 9, 10, 11, 12, 13, 14}[i]);
        long at = now - i * 3600000L;
        String day = Instant.ofEpochMilli(at).atZone(OperationsStats.ZONE).toLocalDate().toString();
        for (String kind : List.of("PAGE", "SEARCH")) {
          String dedupe = crypto.digest(prefix + "-analytics-" + kind + "-" + i);
          if (db.queryForObject(
                  "SELECT COUNT(*) FROM analytics_event WHERE dedupe_hash=?", Integer.class, dedupe)
              == 0)
            db.update(
                "INSERT INTO analytics_event VALUES(?,?,?,?,?,?,?,?,?,?)",
                id(),
                visitor,
                user,
                kind,
                "/apps",
                kind.equals("SEARCH") ? "验收" + batch + "关键词" + (i + 1) : "",
                "scenario" + (i + 1) + ".example.test",
                day,
                dedupe,
                at);
        }
      }
      // 首页人工选品仅追加本批样本，不覆盖用户选品；自动栏目自然覆盖不同商品类型。
      for (var section : catalog.sections())
        if (section.config().mode().equals("MANUAL")) {
          var c = section.config();
          var selected = new LinkedHashSet<>(c.productIds());
          selected.addAll(toolIds);
          catalog.saveSection(
              section.id(),
              new Catalog.SectionInput(
                  c.titleZh(),
                  c.titleEn(),
                  c.subtitleZh(),
                  c.subtitleEn(),
                  c.icon(),
                  c.enabled(),
                  c.position(),
                  c.mode(),
                  c.sort(),
                  c.days(),
                  c.limit(),
                  c.tags(),
                  new ArrayList<>(selected),
                  section.revision()),
              actor);
        }
      // 清除本批及失败重试期间新增的发信队列，绝不把合成客服内容寄给现有真实收件人。
      Set<String> originalOutbox =
          new HashSet<>(JSON.readValue(secrets.get("originalOutbox"), List.class));
      for (String mail : db.queryForList("SELECT id FROM mail_outbox", String.class))
        if (!originalOutbox.contains(mail)) db.update("DELETE FROM mail_outbox WHERE id=?", mail);
      db.update(
          "INSERT INTO schema_migration VALUES(?,?,?)",
          marker,
          "用户授权的本地合成验收数据，每种主要对象10条",
          System.currentTimeMillis());
      Map<String, Object> counts = new LinkedHashMap<>();
      for (String table :
          List.of(
              "catalog_product",
              "content_document",
              "points_package",
              "api_definition",
              "api_plan",
              "api_key",
              "api_subscription",
              "api_call",
              "support_conversation",
              "support_feedback",
              "storage_asset",
              "app_user",
              "access_role",
              "recharge_order",
              "purchase_order",
              "refund_request",
              "software_license"))
        counts.put(table, db.queryForObject("SELECT COUNT(*) FROM " + table, Long.class));
      Path report = root.resolve("validation-data-report.json");
      Files.writeString(
          report,
          JSON.writerWithDefaultPrettyPrinter()
              .writeValueAsString(
                  Map.of(
                      "batch",
                      batch,
                      "database",
                      spec.database(),
                      "counts",
                      counts,
                      "generatedAt",
                      System.currentTimeMillis(),
                      "synthetic",
                      true)));
      store.protect(report);
      StringBuilder access =
          new StringBuilder(
              "AI万有网 本地MySQL合成验收账号\n仅用于本地验证，不用于生产。原有管理员密码与MFA不变。\n数据库："
                  + spec.database()
                  + "\n批次："
                  + batch
                  + "\n本批账号共用临时密码："
                  + secrets.get("password")
                  + "\n\n");
      for (int i = 0; i < roles.size(); i++)
        access
            .append(prefix)
            .append("-u")
            .append(i)
            .append("@example.com · ")
            .append(roles.get(i))
            .append(i == 2 || i == 8 ? " · 未激活" : i == 3 ? " · 停用" : " · 已激活")
            .append('\n');
      access.append(
          "\n"
              + "普通用户验证充值、购买、API请使用u1、u7或u9。u2和u8用于未激活筛选及删除。u0已绑定生成器验证器，仅供离线生成；继续使用原管理员管理站点。\n"
              + "所有样本名称含‘验收’及本批次，可按名称筛选。下载ZIP是说明样本，不是正式软件。\n");
      Path accessFile = root.resolve("validation-accounts.txt");
      Files.writeString(accessFile, access);
      store.protect(accessFile);
      System.out.println("本地合成验收数据已生成；账号说明保存在受限数据目录，密码未输出。批次：" + batch);
    }
  }
}
