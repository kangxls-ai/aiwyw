package com.aiwyw;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
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
import org.springframework.test.web.servlet.MockMvc;

/** 真实数据库/文件系统验证权限、内容校验、引用保护和短期交付，所有资料在临时目录。 */
@SpringBootTest
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(StaffMockMvc.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StorageTest {
  static final Path directory;

  static {
    try {
      directory = Files.createTempDirectory("aiwyw-storage-");
      String port = System.getProperty("aiwyw.test.mysql-port");
      if (port != null) {
        SecureStore store = new SecureStore(directory.toString());
        var spec =
            new Database.ConnectionSpec(
                "MYSQL",
                "127.0.0.1",
                Integer.parseInt(port),
                System.getProperty("aiwyw.test.mysql-database", "aiwyw_storage_assets_validation"),
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

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate db;
  @Autowired SecureStore crypto;
  @Autowired ObjectMapper json;
  @Autowired Storage storage;
  @Autowired Catalog catalog;
  @Autowired Settings settings;
  Cookie operator, administrator;
  String administratorId = UUID.randomUUID().toString();
  String actor, imageId, fileId, productId, publication;
  final byte[] fileBytes = new byte[] {'M', 'Z', 0, 1, 2, 3, 4, 5, 6};

  @BeforeAll
  void setup() {
    actor = UUID.randomUUID().toString();
    operator = session(actor, "OPERATOR", false);
    administrator = session(administratorId, "ADMIN", true);
    catalog.initialize();
  }

  Cookie session(String id, String role, boolean mfa) {
    String token = crypto.token();
    db.update(
        "INSERT INTO"
            + " app_user(id,email,password_hash,role_name,mfa_enabled,last_totp_step,created_at)"
            + " VALUES(?,?,?,?,TRUE,-1,?)",
        id,
        id + "@example.com",
        crypto.password("test-only-storage-password"),
        role,
        System.currentTimeMillis());
    for (boolean staff : new boolean[] {false, true})
      db.update(
          "INSERT INTO app_session(token_hash,user_id,expires_at,mfa_verified) VALUES(?,?,?,?)",
          crypto.digest((staff ? "staff:" : "") + token),
          id,
          System.currentTimeMillis() + 3600000,
          mfa);
    return new Cookie("AIWYW_SESSION", token);
  }

  byte[] png() throws Exception {
    var image = new java.awt.image.BufferedImage(12, 8, java.awt.image.BufferedImage.TYPE_INT_RGB);
    var out = new java.io.ByteArrayOutputStream();
    javax.imageio.ImageIO.write(image, "png", out);
    return out.toByteArray();
  }

  JsonNode upload(String name, byte[] bytes, String purpose) throws Exception {
    return json.readTree(
        mvc.perform(
                multipart("/api/admin/storage/assets")
                    .file(new MockMultipartFile("file", name, "application/octet-stream", bytes))
                    .param("purpose", purpose)
                    .cookie(operator)
                    .header("X-AIWYW-Request", "1"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  Catalog.ProductInput input(boolean includeAssets, long revision) {
    return new Catalog.ProductInput(
        "storage-app",
        "存储应用",
        "Storage app",
        "上传与交付验证",
        "Storage",
        "<p>介绍</p>" + (includeAssets ? "<img src=\"/api/public/media/" + imageId + "\">" : ""),
        "",
        List.of("文件"),
        includeAssets ? "/api/public/media/" + imageId : "",
        List.of(),
        "SOFTWARE",
        BigDecimal.ZERO,
        "1.0.0",
        "DOWNLOAD",
        includeAssets ? "asset:" + fileId : "https://example.com/resource.zip",
        revision);
  }

  void config(String key, String value) {
    var entry =
        settings.list().stream()
            .filter(e -> e.definition().key().equals(key))
            .findFirst()
            .orElseThrow();
    settings.save(
        List.of(new Settings.Change(key, value, entry.revision(), false)), administratorId);
  }

  @Test
  @Order(1)
  void anonymousMultipartIsDeniedBeforeStorageAndOperatorCannotReadCredentials() throws Exception {
    mvc.perform(
            multipart("/api/admin/storage/assets")
                .file(new MockMultipartFile("file", "x.png", "image/png", png()))
                .param("purpose", "IMAGE")
                .header("X-AIWYW-Request", "1"))
        .andExpect(status().isUnauthorized());
    assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM storage_asset", Integer.class));
    mvc.perform(get("/api/admin/storage/settings").cookie(operator))
        .andExpect(status().isForbidden());
    mvc.perform(
            get("/api/admin/storage/assets")
                .cookie(session(UUID.randomUUID().toString(), "USER", true)))
        .andExpect(status().isForbidden());
  }

  @Test
  @Order(2)
  void imagesAreReencodedAndPrivateUntilPublication() throws Exception {
    mvc.perform(
            multipart("/api/admin/storage/assets")
                .file(
                    new MockMultipartFile(
                        "file",
                        "evil.png",
                        "image/png",
                        "<svg onload='alert(1)'></svg>".getBytes()))
                .param("purpose", "IMAGE")
                .cookie(operator)
                .header("X-AIWYW-Request", "1"))
        .andExpect(status().isBadRequest());
    JsonNode image = upload("../../图标.png", png(), "IMAGE");
    imageId = image.get("id").asText();
    assertEquals("图标.png", image.get("originalName").asText());
    mvc.perform(get("/api/public/media/" + imageId)).andExpect(status().isNotFound());
    mvc.perform(get(image.get("previewUrl").asText()).cookie(operator))
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.IMAGE_PNG));
    var profile = db.queryForObject("SELECT config_enc FROM storage_profile LIMIT 1", String.class);
    assertFalse(profile.contains("localDirectory"));
    assertTrue(crypto.decrypt(profile).contains("localDirectory"));
  }

  @Test
  @Order(3)
  void executableDownloadIsNotPublicAndTicketStreamsOriginalBytesWithRange() throws Exception {
    var file = upload("工具安装包.exe", fileBytes, "DOWNLOAD");
    fileId = file.get("id").asText();
    assertEquals("asset:" + fileId, file.get("url").asText());
    mvc.perform(get("/api/public/media/" + fileId)).andExpect(status().isNotFound());
    mvc.perform(get("/api/admin/storage/assets/" + fileId + "/content"))
        .andExpect(status().isUnauthorized());
    productId = catalog.saveProduct(null, input(true, 0), actor);
    publication = catalog.publish(productId, 1, actor, settings);
    mvc.perform(get("/api/public/media/" + imageId)).andExpect(status().isOk());
    String url = catalog.freeDelivery("storage-app", UUID.randomUUID().toString());
    String token = url.substring(url.lastIndexOf('/') + 1);
    assertEquals(
        0,
        db.queryForObject(
            "SELECT COUNT(*) FROM storage_download_ticket WHERE token_hash=?",
            Integer.class,
            token));
    mvc.perform(get(url))
        .andExpect(status().isOk())
        .andExpect(content().bytes(fileBytes))
        .andExpect(
            header()
                .string("Content-Disposition", org.hamcrest.Matchers.containsString("attachment")));
    mvc.perform(get(url).header("Range", "bytes=0-2"))
        .andExpect(status().isPartialContent())
        .andExpect(content().bytes(Arrays.copyOfRange(fileBytes, 0, 3)));
    db.update("UPDATE storage_download_ticket SET expires_at=0");
    mvc.perform(get(url)).andExpect(status().isNotFound());
    mvc.perform(get("/api/public/downloads/" + fileId)).andExpect(status().isNotFound());
  }

  @Test
  @Order(4)
  void HistoricalReferencesPreventDeletionEvenAfterDraftStopsUsingFile() throws Exception {
    catalog.saveProduct(productId, input(false, catalog.draft(productId).revision()), actor);
    for (String id : List.of(imageId, fileId)) {
      assertEquals(1, storage.stored(id, true).asset().referenceCount());
      mvc.perform(
              delete("/api/admin/storage/assets/" + id)
                  .cookie(operator)
                  .header("X-AIWYW-Request", "1"))
          .andExpect(status().isConflict());
    }
    var unused = upload("unused.zip", fileBytes, "DOWNLOAD");
    String id = unused.get("id").asText();
    Path path = storage.localPath(storage.stored(id, true));
    storage.delete(id, actor);
    assertFalse(Files.exists(path));
    assertEquals("DELETED", storage.stored(id, false).asset().status());
    assertThrows(Api.Failure.class, () -> storage.stored(id, true));
  }

  @Test
  @Order(5)
  void configurationSwitchPreservesOldLocationAndIncompleteS3DoesNotPretendSuccess()
      throws Exception {
    String oldProfile = storage.stored(fileId, true).profileId();
    Path oldPath = storage.localPath(storage.stored(fileId, true));
    config("storage.local_directory", "other-assets");
    var fresh = upload("new.zip", fileBytes, "DOWNLOAD");
    var newFile = storage.stored(fresh.get("id").asText(), true);
    assertNotEquals(oldProfile, newFile.profileId());
    assertTrue(Files.exists(oldPath));
    assertTrue(storage.localPath(newFile).startsWith(directory.resolve("other-assets")));
    config("storage.provider", "S3");
    assertEquals(
        400,
        assertThrows(
                Api.Failure.class,
                () ->
                    storage.upload(
                        new MockMultipartFile(
                            "file", "bad.zip", "application/octet-stream", fileBytes),
                        "DOWNLOAD",
                        actor))
            .status);
    config("storage.provider", "LOCAL");
    mvc.perform(get("/api/admin/storage/assets/" + fileId + "/content").cookie(operator))
        .andExpect(status().isOk())
        .andExpect(content().bytes(fileBytes));
  }

  @Test
  @Order(6)
  void limitsPaginationAndPurposeConfusionAreEnforced() throws Exception {
    config("storage.file_limit_mb", "1");
    assertThrows(
        Api.Failure.class,
        () ->
            storage.upload(
                new MockMultipartFile(
                    "file", "large.zip", "application/octet-stream", new byte[1048577]),
                "DOWNLOAD",
                actor));
    var first = storage.list("", "DOWNLOAD", "READY", 1, 1);
    assertEquals(1, first.items().size());
    assertTrue(first.total() >= 2);
    assertEquals(
        400, assertThrows(Api.Failure.class, () -> storage.list("", "", "", 0, 20)).status);
    var bad =
        new Catalog.ProductInput(
            "bad-image-ref",
            "错误类型",
            "",
            "摘要",
            "",
            "<p>x</p>",
            "",
            List.of(),
            "/api/public/media/" + fileId,
            List.of(),
            "TOOL",
            BigDecimal.ZERO,
            "1",
            "DOWNLOAD",
            "asset:" + fileId,
            0);
    assertThrows(Api.Failure.class, () -> catalog.saveProduct(null, bad, actor));
    assertEquals(
        0,
        db.queryForObject(
            "SELECT COUNT(*) FROM catalog_product WHERE slug='bad-image-ref'", Integer.class));
  }

  @Test
  @Order(7)
  void sanitizedSummernoteFormattingSurvivesButActiveContentDoesNot() {
    String safe =
        ContentHtml.sanitize(
            "<p style='text-align:center;color:rgb(255,0,0);background-image:url(javascript:alert(1))'><u>正文</u></p><table><tbody><tr><td>A</td></tr></tbody></table><script>alert(1)</script>");
    assertTrue(safe.contains("text-align:center"));
    assertTrue(safe.contains("<u>"));
    assertTrue(safe.contains("<table>"));
    assertFalse(safe.contains("javascript"));
    assertFalse(safe.contains("<script"));
  }

  @Test
  @Order(8)
  void localProfilesNeverCopyUnusedObjectStoreSecrets() {
    config("storage.access_key", "sensitive-access-key");
    config("storage.secret_key", "sensitive-secret-key");
    assertTrue(storage.current().accessKey().isEmpty());
    assertTrue(storage.current().secretKey().isEmpty());
    var output =
        settings.list().stream()
            .filter(e -> e.definition().key().equals("storage.secret_key"))
            .findFirst()
            .orElseThrow();
    assertEquals("", output.value());
    assertTrue(output.configured());
  }

  @Test
  @Order(9)
  void actualWebpImageIsDecodedAndStoredAsPng() throws Exception {
    byte[] webp;
    try (var resource = getClass().getResourceAsStream("/tiny.webp")) {
      assertNotNull(resource);
      webp = resource.readAllBytes();
    }
    var asset = upload("webp-image.webp", webp, "IMAGE");
    assertEquals("image/png", asset.get("mimeType").asText());
    byte[] bytes =
        mvc.perform(get(asset.get("previewUrl").asText()).cookie(operator))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsByteArray();
    assertEquals(0x89, bytes[0] & 255);
    assertEquals('P', bytes[1]);
    assertEquals(4, javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(bytes)).getWidth());
  }
}
