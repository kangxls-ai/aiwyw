package com.aiwyw;

import jakarta.servlet.http.HttpServletRequest;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** 系统管理HTTP接口。每个私有入口独立调用auth.admin；付款码上传与启用真实捐赠分离，上传不代表真实收款已开放。 */
@RestController
public class AdminController {
  private final Auth auth;
  private final Settings settings;
  private final JdbcTemplate jdbc;
  private final SecureStore crypto;
  private final Database database;

  public AdminController(
      Auth auth, Settings settings, JdbcTemplate jdbc, SecureStore crypto, Database database) {
    this.auth = auth;
    this.settings = settings;
    this.jdbc = jdbc;
    this.crypto = crypto;
    this.database = database;
  }

  @GetMapping("/api/public/site")
  /** 公开白名单配置，仅展示信息和当前模拟收款图，不含SMTP、对象存储密钥。 */
  public Map<String, String> site() {
    return settings.publicValues();
  }

  @GetMapping("/api/admin/settings")
  /** 要求管理员MFA，返回不含敏感明文的配置元数据。 */
  public List<Settings.Entry> settings(HttpServletRequest req) {
    auth.admin(req);
    return settings.list();
  }

  public record Save(List<Settings.Change> changes) {}

  @PutMapping("/api/admin/settings")
  /** 操作者ID来自会话，不接受表单伪造审计用户。 */
  public Map<String, Boolean> save(@RequestBody Save body, HttpServletRequest req) {
    var u = auth.admin(req);
    settings.save(body.changes(), u.id());
    return Map.of("success", true);
  }

  @PostMapping("/api/admin/site/publish")
  public Map<String, Boolean> publishSite(HttpServletRequest req) {
    auth.admin(req);
    return Map.of("success", true);
  }

  @GetMapping("/api/admin/audit")
  /** 按时间返回最近100条配置与目录审计，不含旧值新值或密钥。 */
  public List<Map<String, Object>> audit(HttpServletRequest req) {
    auth.admin(req);
    return Commerce.safeRows(
        jdbc.queryForList(
            """
SELECT a.id,a.config_key,a.revision,a.action_name,a.created_at,
  COALESCE(u.nickname,a.user_id) AS nickname,a.user_id AS created_by_id,
  COALESCE(u.nickname,a.user_id) AS created_by_name,a.detail,a.source_ip
FROM (
  SELECT id,user_id,config_key,revision,action_name,created_at,'' AS detail,source_ip FROM config_audit
  UNION ALL SELECT id,actor_id,CONCAT('catalog:',entity_id),0,action_name,created_at,detail,source_ip FROM catalog_audit
  UNION ALL SELECT id,actor_id,CONCAT('storage:',entity_id),0,action_name,created_at,detail,source_ip FROM storage_audit
  UNION ALL SELECT id,actor_id,CONCAT('finance:',entity_id),0,action_name,created_at,detail,source_ip FROM finance_audit
) a LEFT JOIN app_user u ON a.user_id=u.id ORDER BY a.created_at DESC,a.id DESC LIMIT 100
"""));
  }

  @GetMapping("/api/admin/overview")
  /** 返回基础运行摘要，不将开发阶段状态冒充完整V1验收。 */
  public Map<String, Object> overview(HttpServletRequest req) {
    auth.staff(req);
    return Map.of(
        "installed",
        database.installed(),
        "databaseEngine",
        database.engine(),
        "databaseName",
        database.databaseName(),
        "settingsCount",
        Settings.DEFINITIONS.size(),
        "paymentMode",
        settings.value("payment.mode"),
        "domain",
        settings.value("site.domain"),
        "version",
        "0.1.0",
        "stage",
        "基础系统与配置中心",
        "databaseMigration",
        "数据库连接由首次安装页面设置；当前版本不提供在线迁移，请勿直接更换数据库");
  }

  @PostMapping({"/api/admin/assets/payment", "/api/admin/assets/filing"})
  /** 验证真实图片格式与像素尺寸，重编码PNG去除隐藏负载，文件名由服务器随机生成。 */
  public Map<String, String> upload(
      @RequestParam("file") MultipartFile file, HttpServletRequest req) throws Exception {
    auth.admin(req);
    boolean filing = req.getRequestURI().endsWith("/filing");
    if (file.isEmpty() || file.getSize() > 2 * 1024 * 1024) throw new Api.Failure(400, "图片需在2MB以内");
    BufferedImage image;
    try (var stream = ImageIO.createImageInputStream(file.getInputStream())) {
      var readers = ImageIO.getImageReaders(stream);
      if (!readers.hasNext()) throw new Api.Failure(400, "请上传PNG或JPG图片");
      var reader = readers.next();
      try {
        reader.setInput(stream);
        String format = reader.getFormatName();
        if (!format.equalsIgnoreCase("PNG") && !format.equalsIgnoreCase("JPEG"))
          throw new Api.Failure(400, "请上传PNG或JPG图片");
        if (reader.getWidth(0) > 4096 || reader.getHeight(0) > 4096)
          throw new Api.Failure(400, "图片边长不能超过4096像素");
        image = reader.read(0);
      } finally {
        reader.dispose();
      }
    }
    String id = UUID.randomUUID() + ".png";
    Path folder = crypto.root.resolve(filing ? "filing-images" : "payment-images");
    Files.createDirectories(folder);
    ImageIO.write(image, "png", folder.resolve(id).toFile());
    return Map.of("url", (filing ? "/api/public/filing-assets/" : "/api/public/assets/") + id);
  }

  @GetMapping("/api/public/assets/{name}")
  /** 仅当前选中的模拟收款图可公开；预上传的真实付款码保持鉴权。 */
  public ResponseEntity<byte[]> asset(@PathVariable String name) throws Exception {
    if (!name.matches("[a-f0-9-]{36}\\.png")) throw new Api.Failure(404, "图片不存在");
    // Only the currently selected mock image is public. Preloaded real payment codes remain
    // private.
    if (!settings.value("payment.mock_image").equals("/api/public/assets/" + name))
      throw new Api.Failure(404, "图片尚未公开");
    return readImage("payment-images", name);
  }

  @GetMapping({"/api/public/filing-assets/{name}", "/api/admin/filing-assets/{name}"})
  public ResponseEntity<byte[]> filingAsset(@PathVariable String name, HttpServletRequest req)
      throws Exception {
    if (req.getRequestURI().startsWith("/api/admin/")) {
      auth.admin(req);
    } else {
      var site = settings.publicValues();
      if (site.getOrDefault("site.security_filing", "").isBlank()
          || !("/api/public/filing-assets/" + name).equals(site.get("site.security_filing_icon")))
        throw new Api.Failure(404, "图片尚未公开");
    }
    return readImage("filing-images", name);
  }

  @GetMapping("/api/admin/assets/{name}")
  /** 已上传但未公开的收款图只能由管理员读取；文件名白名单拒绝路径穿越。 */
  public ResponseEntity<byte[]> privateAsset(@PathVariable String name, HttpServletRequest req)
      throws Exception {
    auth.admin(req);
    if (!name.matches("[a-f0-9-]{36}\\.png")) throw new Api.Failure(404, "图片不存在");
    return readImage("payment-images", name);
  }

  private ResponseEntity<byte[]> readImage(String directory, String name) throws Exception {
    if (!name.matches("[a-f0-9-]{36}\\.png")) throw new Api.Failure(404, "图片不存在");
    Path path = crypto.root.resolve(directory).resolve(name);
    if (!Files.isRegularFile(path)) throw new Api.Failure(404, "图片不存在");
    return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(Files.readAllBytes(path));
  }
}
