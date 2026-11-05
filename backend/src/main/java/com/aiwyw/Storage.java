package com.aiwyw;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

/**
 * 图片与下载文件的统一资源服务。数据库保存资源身份与原连接，文件正文不进入数据库。 上传失败不变成可引用资源；草稿/发布引用与商品事务一起提交；删除先锁资源并核对引用。
 * 切换配置只影响新上传，不把旧对象键解释为新Bucket地址。
 */
@Service
public class Storage {
  public record Connection(
      String provider,
      String localDirectory,
      String endpoint,
      String region,
      String bucket,
      String accessKey,
      String secretKey,
      String sessionToken,
      boolean pathStyle) {}

  public record Asset(
      String id,
      String originalName,
      String mimeType,
      long sizeBytes,
      String purpose,
      String status,
      String provider,
      long createdAt,
      boolean publicVisible,
      long referenceCount,
      String url,
      String previewUrl) {}

  public record AssetPage(List<Asset> items, long total, int page, int pageSize) {}

  record Stored(
      Asset asset, String profileId, String objectKey, String sha256, Connection connection) {}

  private static final Pattern MEDIA =
      Pattern.compile("(?:src|href)=\"/api/public/media/([a-f0-9-]{36})\"");
  private final JdbcTemplate db;
  private final Settings settings;
  private final SecureStore store;
  private final ObjectMapper json;
  private final TransactionTemplate tx;

  public Storage(
      JdbcTemplate db,
      Settings settings,
      SecureStore store,
      ObjectMapper json,
      PlatformTransactionManager manager) {
    this.db = db;
    this.settings = settings;
    this.store = store;
    this.json = json;
    this.tx = new TransactionTemplate(manager);
  }

  private String encode(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private Connection decode(String encrypted) {
    try {
      return json.readValue(store.decrypt(encrypted), Connection.class);
    } catch (Exception e) {
      throw new IllegalStateException("Invalid storage profile; restore matching master key", e);
    }
  }

  Connection current() {
    // 一次SQL读取整个连接，避免管理员切换模式时拼出新旧配置的混合快照。
    Map<String, String> snapshot = new HashMap<>();
    for (var row :
        db.queryForList(
            "SELECT config_key,config_value FROM system_config WHERE config_key LIKE 'storage.%'"))
      snapshot.put((String) row.get("config_key"), (String) row.get("config_value"));
    java.util.function.Function<String, String> value =
        key -> {
          var definition = settings.definition(key);
          String raw = snapshot.get(key);
          return raw == null
              ? definition.initial()
              : definition.secret() ? store.decrypt(raw) : raw;
        };
    String provider = value.apply("storage.provider");
    if (!Set.of("LOCAL", "S3").contains(provider))
      throw new Api.Failure(400, "外部链接模式不接收上传，请在存储管理选择本地或对象存储");
    if (provider.equals("LOCAL"))
      return new Connection(
          "LOCAL", value.apply("storage.local_directory"), "", "", "", "", "", "", true);
    var c =
        new Connection(
            provider,
            value.apply("storage.local_directory"),
            value.apply("storage.endpoint"),
            value.apply("storage.region").isBlank() ? "us-east-1" : value.apply("storage.region"),
            value.apply("storage.bucket"),
            value.apply("storage.access_key"),
            value.apply("storage.secret_key"),
            value.apply("storage.session_token"),
            Boolean.parseBoolean(value.apply("storage.path_style")));
    if (provider.equals("S3")
        && (c.endpoint().isBlank()
            || c.bucket().isBlank()
            || c.accessKey().isBlank()
            || c.secretKey().isBlank())) throw new Api.Failure(400, "请先保存完整的对象存储地址、Bucket与访问凭据");
    return c;
  }

  private String profile(Connection c) {
    String encoded = encode(c), fingerprint = store.digest(encoded);
    try {
      return tx.execute(
          status -> {
            var ids =
                db.queryForList(
                    "SELECT id FROM storage_profile WHERE fingerprint=?",
                    String.class,
                    fingerprint);
            if (!ids.isEmpty()) return ids.get(0);
            String id = UUID.randomUUID().toString();
            db.update(
                "INSERT INTO storage_profile(id,fingerprint,provider,config_enc,created_at)"
                    + " VALUES(?,?,?,?,?)",
                id,
                fingerprint,
                c.provider(),
                store.encrypt(encoded),
                System.currentTimeMillis());
            return id;
          });
    } catch (org.springframework.dao.DuplicateKeyException concurrent) {
      return db.queryForObject(
          "SELECT id FROM storage_profile WHERE fingerprint=?", String.class, fingerprint);
    }
  }

  private void audit(String actor, String entity, String action, String detail) {
    db.update(
        "INSERT INTO storage_audit(id,actor_id,entity_id,action_name,detail,created_at)"
            + " VALUES(?,?,?,?,?,?)",
        UUID.randomUUID().toString(),
        actor,
        entity,
        action,
        detail,
        System.currentTimeMillis());
  }

  public Map<String, Object> capabilities() {
    return Map.of(
        "provider",
        settings.value("storage.provider"),
        "imageLimitMb",
        settings.integer("storage.image_limit_mb"),
        "fileLimitMb",
        settings.integer("storage.file_limit_mb"));
  }

  public void check() {
    Connection c = current();
    try {
      if (c.provider().equals("S3")) new S3ObjectStore(c).check();
      else {
        Path root = localRoot(c);
        Files.createDirectories(root);
        Path probe = Files.createTempFile(root, "write-check-", ".tmp");
        Files.delete(probe);
      }
    } catch (Exception failure) {
      throw new Api.Failure(400, "存储连接检查失败，请检查地址、权限与TLS配置");
    }
  }

  /** 相同位置的历史连接可显式更新凭据；先验证新配置，不移动对象、不更改Bucket或对象键。 */
  public int refreshCredentials(String actor) {
    Connection c = current();
    if (!c.provider().equals("S3")) throw new Api.Failure(400, "当前不是对象存储模式");
    check();
    return tx.execute(
        status -> {
          int changed = 0;
          for (var row :
              db.queryForList("SELECT id,config_enc FROM storage_profile WHERE provider='S3'")) {
            Connection old = decode((String) row.get("config_enc"));
            if (old.endpoint().equals(c.endpoint())
                && old.bucket().equals(c.bucket())
                && old.region().equals(c.region())
                && old.pathStyle() == c.pathStyle()) {
              db.update(
                  "UPDATE storage_profile SET config_enc=? WHERE id=?",
                  store.encrypt(encode(c)),
                  row.get("id"));
              changed++;
            }
          }
          audit(actor, "storage", "REFRESH_CREDENTIALS", "profiles=" + changed);
          return changed;
        });
  }

  private Path localRoot(Connection c) {
    if (!c.localDirectory().matches("[a-zA-Z0-9_-]+(?:/[a-zA-Z0-9_-]+)*"))
      throw new IllegalStateException("Invalid local profile path");
    Path path = store.root.resolve(c.localDirectory()).normalize();
    if (!path.startsWith(store.root))
      throw new IllegalStateException("Storage path escaped data directory");
    return path;
  }

  Path localPath(Stored file) {
    if (!file.objectKey().matches("[0-9]{4}/[0-9]{2}/[a-f0-9-]{36}\\.(?:png|bin)"))
      throw new IllegalStateException("Invalid object key");
    Path root = localRoot(file.connection()), path = root.resolve(file.objectKey()).normalize();
    if (!path.startsWith(root)) throw new IllegalStateException("Invalid storage path");
    for (Path parent = path;
        parent != null && parent.startsWith(store.root);
        parent = parent.getParent())
      if (Files.isSymbolicLink(parent)) throw new Api.Failure(503, "存储路径存在符号链接，请联系管理员检查");
    return path;
  }

  /** 按流校验实际长度，图片先读取尺寸再解码；安装包不进入内存，也不按原始文件名落盘。 */
  public Asset upload(MultipartFile input, String purpose, String actor) {
    if (purpose == null || !Set.of("IMAGE", "DOWNLOAD", "RECEIPT", "CHAT").contains(purpose))
      throw new Api.Failure(400, "请选择图片或下载文件");
    long maximum =
        settings.integer(
                purpose.equals("CHAT")
                    ? "support.image_limit_mb"
                    : !purpose.equals("DOWNLOAD")
                        ? "storage.image_limit_mb"
                        : "storage.file_limit_mb")
            * 1024L
            * 1024;
    if (input.isEmpty() || input.getSize() > maximum) throw new Api.Failure(400, "上传内容为空或超出当前大小限制");
    String name =
        input.getOriginalFilename() == null
            ? "file"
            : input.getOriginalFilename().replace('\\', '/');
    name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "").trim();
    if (name.isBlank() || name.equals(".") || name.equals("..") || name.length() > 200)
      throw new Api.Failure(400, "文件名不正确或超过200字符");
    Connection c = current();
    String profile = profile(c), id = UUID.randomUUID().toString();
    Path temporary = null, normalized = null;
    try {
      Path spool = store.root.resolve("upload-tmp");
      Files.createDirectories(spool);
      temporary = Files.createTempFile(spool, "receive-", ".tmp");
      try (var stream = input.getInputStream();
          var target = Files.newOutputStream(temporary)) {
        byte[] buffer = new byte[65536];
        long total = 0;
        int length;
        while ((length = stream.read(buffer)) != -1) {
          total += length;
          if (total > maximum) throw new Api.Failure(400, "上传文件超过当前大小限制");
          target.write(buffer, 0, length);
        }
      }
      Path body = temporary;
      String mime = "application/octet-stream";
      if (!purpose.equals("DOWNLOAD")) {
        normalized = Files.createTempFile(spool, "image-", ".png");
        try (var imageStream = ImageIO.createImageInputStream(temporary.toFile())) {
          var readers = ImageIO.getImageReaders(imageStream);
          if (!readers.hasNext()) throw new Api.Failure(400, "图片格式不正确，仅支持PNG、JPG、WebP");
          var reader = readers.next();
          try {
            reader.setInput(imageStream);
            String format = reader.getFormatName().toLowerCase(Locale.ROOT);
            if (!Set.of("png", "jpeg", "jpg", "webp").contains(format)
                || reader.getWidth(0) > 8192
                || reader.getHeight(0) > 8192
                || (long) reader.getWidth(0) * reader.getHeight(0) > 25000000)
              throw new Api.Failure(400, "图片格式或尺寸超出限制");
            BufferedImage image = reader.read(0);
            if (image == null || !ImageIO.write(image, "png", normalized.toFile()))
              throw new Api.Failure(400, "无法读取图片");
          } finally {
            reader.dispose();
          }
        }
        body = normalized;
        mime = "image/png";
        if (Files.size(body) > maximum) throw new Api.Failure(400, "图片转换后超过大小限制，请压缩后上传");
      }
      String sha = sha256(body),
          key =
              LocalDate.now(ZoneOffset.UTC)
                      .format(java.time.format.DateTimeFormatter.ofPattern("yyyy/MM/"))
                  + id
                  + (!purpose.equals("DOWNLOAD") ? ".png" : ".bin");
      long now = System.currentTimeMillis();
      db.update(
          "INSERT INTO"
              + " storage_asset(id,profile_id,object_key,original_name,mime_type,size_bytes,sha256,purpose,status,public_visible,created_by,created_at,updated_at)"
              + " VALUES(?,?,?,?,?,?,?,?,'UPLOADING',FALSE,?,?,?)",
          id,
          profile,
          key,
          name,
          mime,
          Files.size(body),
          sha,
          purpose,
          actor,
          now,
          now);
      try {
        if (c.provider().equals("S3")) new S3ObjectStore(c).put(key, body, mime, sha);
        else {
          Path target = localPath(stored(id, false));
          Files.createDirectories(target.getParent());
          try {
            Files.move(body, target, StandardCopyOption.ATOMIC_MOVE);
          } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(body, target);
          }
        }
        tx.executeWithoutResult(
            status -> {
              db.update(
                  "UPDATE storage_asset SET status='READY',updated_at=? WHERE id=? AND"
                      + " status='UPLOADING'",
                  System.currentTimeMillis(),
                  id);
              audit(actor, id, "UPLOAD", "stored");
            });
      } catch (Exception failure) {
        db.update(
            "UPDATE storage_asset SET status='DELETE_PENDING',updated_at=? WHERE id=?",
            System.currentTimeMillis(),
            id);
        try {
          delete(id, actor);
        } catch (Exception cleanup) {
          /* 元数据保留待重试，不能误报上传完成。 */
        }
        throw new Api.Failure(503, "文件保存失败，请检查存储连接、磁盘空间及权限");
      }
      return stored(id, true).asset();
    } catch (Api.Failure business) {
      throw business;
    } catch (Exception failure) {
      throw new Api.Failure(400, "文件处理失败，请检查文件内容和存储权限");
    } finally {
      for (Path path : new Path[] {temporary, normalized})
        if (path != null)
          try {
            Files.deleteIfExists(path);
          } catch (IOException ignored) {
            /* 私有暂存文件由维护流程清理，不公开。 */
          }
    }
  }

  private String sha256(Path file) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    try (var stream = new DigestInputStream(Files.newInputStream(file), digest)) {
      stream.transferTo(OutputStream.nullOutputStream());
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  Stored stored(String id, boolean ready) {
    if (id == null || !id.matches("[a-f0-9-]{36}")) throw new Api.Failure(404, "文件不存在");
    var rows =
        db.queryForList(
            "SELECT a.*,p.provider,p.config_enc,(SELECT COUNT(*) FROM storage_reference r WHERE"
                + " r.asset_id=a.id) AS refs FROM storage_asset a JOIN storage_profile p ON"
                + " a.profile_id=p.id WHERE a.id=?",
            id);
    if (rows.isEmpty()) throw new Api.Failure(404, "文件不存在");
    var r = rows.get(0);
    String state = (String) r.get("status");
    if (ready && !state.equals("READY")) throw new Api.Failure(404, "文件不可用");
    String purpose = (String) r.get("purpose");
    var asset =
        new Asset(
            id,
            (String) r.get("original_name"),
            (String) r.get("mime_type"),
            ((Number) r.get("size_bytes")).longValue(),
            purpose,
            state,
            (String) r.get("provider"),
            ((Number) r.get("created_at")).longValue(),
            Boolean.TRUE.equals(r.get("public_visible")),
            ((Number) r.get("refs")).longValue(),
            purpose.equals("CHAT")
                ? "/api/support/assets/" + id
                : purpose.equals("RECEIPT")
                    ? "/api/member/evidence/" + id
                    : purpose.equals("IMAGE") ? "/api/public/media/" + id : "asset:" + id,
            "/api/admin/storage/assets/" + id + "/content");
    return new Stored(
        asset,
        (String) r.get("profile_id"),
        (String) r.get("object_key"),
        (String) r.get("sha256"),
        decode((String) r.get("config_enc")));
  }

  public AssetPage list(String query, String purpose, String state, int page, int size) {
    return list(query, purpose, state, page, size, "createdAt", "descending");
  }

  public AssetPage list(
      String query,
      String purpose,
      String state,
      int page,
      int size,
      String sort,
      String direction) {
    String ordering =
        ListOrder.sql(
            sort,
            direction,
            Map.of(
                "createdAt",
                "a.created_at",
                "originalName",
                "a.original_name",
                "sizeBytes",
                "a.size_bytes",
                "purpose",
                "a.purpose",
                "status",
                "a.status",
                "referenceCount",
                "(SELECT COUNT(*) FROM storage_reference sr WHERE sr.asset_id=a.id)"),
            "createdAt",
            "a.id");
    if (page < 1
        || page > 1000000
        || size < 1
        || size > 100
        || query == null
        || query.length() > 200) throw new Api.Failure(400, "分页或查询参数不正确");
    List<Object> values = new ArrayList<>();
    String where = " WHERE a.purpose NOT IN ('RECEIPT','CHAT')";
    if (!query.isBlank()) {
      where += " AND LOWER(a.original_name) LIKE ? ESCAPE '!'";
      values.add(
          "%"
              + query
                  .toLowerCase(Locale.ROOT)
                  .replace("!", "!!")
                  .replace("%", "!%")
                  .replace("_", "!_")
              + "%");
    }
    if (purpose != null && !purpose.isBlank()) {
      if (purpose == null || !Set.of("IMAGE", "DOWNLOAD").contains(purpose))
        throw new Api.Failure(400, "文件类型不正确");
      where += " AND a.purpose=?";
      values.add(purpose);
    }
    if (state != null && !state.isBlank()) {
      if (!Set.of("UPLOADING", "READY", "DELETE_PENDING", "DELETED").contains(state))
        throw new Api.Failure(400, "文件状态不正确");
      where += " AND a.status=?";
      values.add(state);
    } else where += " AND a.status<>'DELETED'";
    long total =
        db.queryForObject(
            "SELECT COUNT(*) FROM storage_asset a" + where, Long.class, values.toArray());
    int actual = (int) Math.min(page, Math.max(1, (total + size - 1) / size));
    values.add(size);
    values.add((actual - 1) * size);
    var ids =
        db.queryForList(
            "SELECT a.id FROM storage_asset a"
                + where
                + " ORDER BY "
                + ordering
                + " LIMIT ? OFFSET ?",
            String.class,
            values.toArray());
    return new AssetPage(
        ids.stream().map(id -> stored(id, false).asset()).toList(), total, actual, size);
  }

  /** 固定锁顺序：商品guard → 资源UUID排序；删除操作只锁资源，不反向获取商品guard。 */
  public void syncReferences(
      String ownerType, String ownerId, Catalog.ProductInput p, boolean publish) {
    Map<String, String> needed = new TreeMap<>();
    java.util.function.Consumer<String> image =
        url -> {
          if (url != null && url.matches("/api/public/media/[a-f0-9-]{36}"))
            reference(needed, url.substring(url.lastIndexOf('/') + 1), "IMAGE");
        };
    image.accept(p.iconUrl());
    for (String screenshot : p.screenshots()) image.accept(screenshot);
    for (String body : List.of(p.bodyZh(), p.bodyEn())) {
      var matcher = MEDIA.matcher(body);
      while (matcher.find()) reference(needed, matcher.group(1), "IMAGE");
    }
    if (p.downloadUrl().startsWith("asset:"))
      reference(needed, p.downloadUrl().substring(6), "DOWNLOAD");
    Set<String> locks = new TreeSet<>(needed.keySet());
    locks.addAll(
        db.queryForList(
            "SELECT asset_id FROM storage_reference WHERE owner_type=? AND owner_id=?",
            String.class,
            ownerType,
            ownerId));
    for (String id : locks) {
      var rows =
          db.queryForList("SELECT purpose,status FROM storage_asset WHERE id=? FOR UPDATE", id);
      if (needed.containsKey(id)
          && (rows.isEmpty()
              || !rows.get(0).get("status").equals("READY")
              || !rows.get(0).get("purpose").equals(needed.get(id))))
        throw new Api.Failure(400, "引用的图片或下载文件不存在、类型不匹配或已删除");
    }
    db.update(
        "DELETE FROM storage_reference WHERE owner_type=? AND owner_id=?", ownerType, ownerId);
    for (var entry : needed.entrySet()) {
      db.update(
          "INSERT INTO storage_reference(asset_id,owner_type,owner_id) VALUES(?,?,?)",
          entry.getKey(),
          ownerType,
          ownerId);
      if (publish && entry.getValue().equals("IMAGE"))
        db.update("UPDATE storage_asset SET public_visible=TRUE WHERE id=?", entry.getKey());
    }
  }

  private void reference(Map<String, String> needed, String id, String kind) {
    String previous = needed.putIfAbsent(id, kind);
    if (previous != null && !previous.equals(kind)) throw new Api.Failure(400, "同一资源不能同时作为图片和下载内容");
  }

  public void delete(String id, String actor) {
    tx.executeWithoutResult(
        status -> {
          var rows =
              db.queryForList(
                  "SELECT status,updated_at FROM storage_asset WHERE id=? FOR UPDATE", id);
          if (rows.isEmpty()) throw new Api.Failure(404, "文件不存在");
          var row = rows.get(0);
          if (row.get("status").equals("UPLOADING")
              && System.currentTimeMillis() - ((Number) row.get("updated_at")).longValue() < 600000)
            throw new Api.Failure(409, "文件正在上传，暂不能删除");
          if (db.queryForObject(
                  "SELECT COUNT(*) FROM storage_reference WHERE asset_id=?", Long.class, id)
              > 0) throw new Api.Failure(409, "文件已被草稿或历史发布引用，不能删除");
          db.update(
              "UPDATE storage_asset SET status='DELETE_PENDING',updated_at=? WHERE id=?",
              System.currentTimeMillis(),
              id);
        });
    try {
      Stored file = stored(id, false);
      if (file.connection().provider().equals("S3"))
        new S3ObjectStore(file.connection()).delete(file.objectKey());
      else Files.deleteIfExists(localPath(file));
      tx.executeWithoutResult(
          status -> {
            db.update(
                "UPDATE storage_asset SET status='DELETED',updated_at=? WHERE id=? AND"
                    + " status='DELETE_PENDING'",
                System.currentTimeMillis(),
                id);
            audit(actor, id, "DELETE", "deleted");
          });
    } catch (Exception failure) {
      audit(actor, id, "DELETE_FAILED", "retry required");
      throw new Api.Failure(503, "物理文件删除未完成，已保留待重试记录");
    }
  }

  /** 仅商品/权益服务调用；客户端不能指定资源ID直接换取私有文件链接。 */
  public String ticket(String id, String publication) {
    Stored file = stored(id, true);
    if (!file.asset().purpose().equals("DOWNLOAD")) throw new Api.Failure(400, "不是下载文件");
    if (db.queryForObject(
            "SELECT COUNT(*) FROM storage_reference WHERE asset_id=? AND owner_type='PUBLICATION'"
                + " AND owner_id=?",
            Long.class,
            id,
            publication)
        == 0) throw new Api.Failure(403, "资源与发布版本不匹配");
    String token = store.token();
    long now = System.currentTimeMillis();
    db.update("DELETE FROM storage_download_ticket WHERE expires_at<?", now);
    db.update(
        "INSERT INTO"
            + " storage_download_ticket(token_hash,asset_id,publication_id,expires_at,created_at)"
            + " VALUES(?,?,?,?,?)",
        store.digest(token),
        id,
        publication,
        now + settings.integer("storage.download_minutes") * 60000L,
        now);
    return "/api/public/downloads/" + token;
  }

  Stored byTicket(String token) {
    if (token == null || !token.matches("[A-Za-z0-9_-]{43}"))
      throw new Api.Failure(404, "下载链接无效或已过期");
    var ids =
        db.queryForList(
            "SELECT asset_id FROM storage_download_ticket WHERE token_hash=? AND expires_at>?",
            String.class,
            store.digest(token),
            System.currentTimeMillis());
    if (ids.isEmpty()) throw new Api.Failure(404, "下载链接无效或已过期，请重新申请");
    var entitlements =
        db.queryForList(
            "SELECT entitlement_id FROM storage_download_ticket WHERE token_hash=?",
            String.class,
            store.digest(token));
    if (!entitlements.isEmpty()
        && entitlements.get(0) != null
        && db.queryForObject(
                "SELECT COUNT(*) FROM purchase_order WHERE id=? AND state='ACTIVE'",
                Integer.class,
                entitlements.get(0))
            == 0) throw new Api.Failure(403, "该下载权益已撤销");
    return stored(ids.get(0), true);
  }
}
