package com.aiwyw;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** 资源配置只授予管理员MFA；商品运营可上传和管理资源，但不能读取存储凭据。 */
@RestController
public class StorageController {
  private final Storage storage;
  private final Settings settings;
  private final Auth auth;

  public StorageController(Storage storage, Settings settings, Auth auth) {
    this.storage = storage;
    this.settings = settings;
    this.auth = auth;
  }

  @GetMapping("/api/admin/storage/capabilities")
  public Map<String, Object> capabilities(HttpServletRequest req) {
    auth.editor(req);
    return storage.capabilities();
  }

  @GetMapping("/api/admin/storage/settings")
  public List<Settings.Entry> configuration(HttpServletRequest req) {
    auth.admin(req);
    return settings.list().stream()
        .filter(e -> e.definition().key().startsWith("storage."))
        .toList();
  }

  @PutMapping("/api/admin/storage/settings")
  public Map<String, Boolean> save(@RequestBody AdminController.Save body, HttpServletRequest req) {
    var actor = auth.admin(req);
    if (body.changes() == null
        || body.changes().stream()
            .anyMatch(c -> c == null || c.key() == null || !c.key().startsWith("storage.")))
      throw new Api.Failure(400, "仅支持存储配置");
    settings.save(body.changes(), actor.id());
    return Map.of("success", true);
  }

  @PostMapping("/api/admin/storage/check")
  public Map<String, Boolean> check(HttpServletRequest req) {
    auth.admin(req);
    storage.check();
    return Map.of("success", true);
  }

  @PostMapping("/api/admin/storage/refresh-credentials")
  public Map<String, Integer> refresh(HttpServletRequest req) {
    return Map.of("profiles", storage.refreshCredentials(auth.admin(req).id()));
  }

  @GetMapping("/api/admin/storage/assets")
  public Storage.AssetPage list(
      @RequestParam(defaultValue = "") String q,
      @RequestParam(defaultValue = "") String purpose,
      @RequestParam(defaultValue = "") String status,
      @RequestParam(defaultValue = "createdAt") String sort,
      @RequestParam(defaultValue = "descending") String direction,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int pageSize,
      HttpServletRequest req) {
    auth.editor(req);
    return storage.list(q, purpose, status, page, pageSize, sort, direction);
  }

  @PostMapping("/api/admin/storage/assets")
  public Storage.Asset upload(
      @RequestParam("file") MultipartFile file,
      @RequestParam(defaultValue = "DOWNLOAD") String purpose,
      HttpServletRequest req) {
    if (!Set.of("IMAGE", "DOWNLOAD").contains(purpose))
      throw new Api.Failure(400, "请选择公开图片或下载文件；私有附件应从对应业务上传");
    return storage.upload(file, purpose, auth.editor(req).id());
  }

  @DeleteMapping("/api/admin/storage/assets/{id}")
  public Map<String, Boolean> delete(@PathVariable String id, HttpServletRequest req) {
    var operator = auth.editor(req);
    if (Set.of("RECEIPT", "CHAT").contains(storage.stored(id, false).asset().purpose()))
      throw new Api.Failure(404, "文件不存在");
    storage.delete(id, operator.id());
    return Map.of("success", true);
  }

  @GetMapping("/api/admin/storage/assets/{id}")
  public Storage.Asset get(@PathVariable String id, HttpServletRequest req) {
    auth.editor(req);
    var file = storage.stored(id, true);
    if (Set.of("RECEIPT", "CHAT").contains(file.asset().purpose()))
      throw new Api.Failure(404, "文件不存在");
    return file.asset();
  }

  @GetMapping("/api/admin/storage/assets/{id}/content")
  public ResponseEntity<?> preview(@PathVariable String id, HttpServletRequest req) {
    auth.editor(req);
    var file = storage.stored(id, true);
    if (Set.of("RECEIPT", "CHAT").contains(file.asset().purpose()))
      throw new Api.Failure(404, "文件不存在");
    return content(file, false);
  }

  @GetMapping("/api/public/media/{id}")
  public ResponseEntity<?> image(@PathVariable String id) {
    var file = storage.stored(id, true);
    if (!file.asset().purpose().equals("IMAGE")) throw new Api.Failure(404, "图片不存在");
    if (!file.asset().publicVisible()) throw new Api.Failure(404, "图片尚未发布");
    return content(file, true);
  }

  @GetMapping("/api/public/downloads/{token}")
  public ResponseEntity<?> download(@PathVariable String token) {
    return content(storage.byTicket(token), false);
  }

  /** FileSystemResource由Spring支持流式读取及Range续传；对象存储只发短时签名，不经应用内存转存。 */
  ResponseEntity<?> content(Storage.Stored file, boolean publiclyCacheable) {
    var asset = file.asset();
    boolean image = asset.mimeType().equals("image/png");
    String disposition =
        (image ? ContentDisposition.inline() : ContentDisposition.attachment())
            .filename(
                image
                    ? asset.originalName().replaceFirst("\\.[^.]*$", "") + ".png"
                    : asset.originalName(),
                StandardCharsets.UTF_8)
            .build()
            .toString();
    if (file.connection().provider().equals("S3")) {
      String url =
          new S3ObjectStore(file.connection())
              .download(
                  file.objectKey(),
                  disposition,
                  asset.mimeType(),
                  settings.integer("storage.download_minutes"),
                  publiclyCacheable);
      return ResponseEntity.status(302)
          .header(HttpHeaders.LOCATION, url)
          .cacheControl(CacheControl.noStore())
          .header("Referrer-Policy", "no-referrer")
          .build();
    }
    var path = storage.localPath(file);
    if (!Files.isRegularFile(path)) throw new Api.Failure(404, "文件暂不可用，请联系管理员恢复");
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(asset.mimeType()))
        .contentLength(asset.sizeBytes())
        .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
        .header("Referrer-Policy", "no-referrer")
        .header(
            "Content-Security-Policy", image ? "default-src 'none'" : "sandbox; default-src 'none'")
        .cacheControl(
            publiclyCacheable
                ? CacheControl.maxAge(Duration.ofDays(1)).cachePublic().immutable()
                : CacheControl.noStore())
        .eTag("\"" + file.sha256() + "\"")
        .body(new FileSystemResource(path));
  }
}
