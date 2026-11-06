package com.aiwyw;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 在线授权无离线宽限：每次校验都读取真实订单/账户状态。统一锁序用户→订单→授权→设备， 与钱包退款一致；密钥不进审计、不进URL、不在后台列表展示。客户端必须实际调用并处理失效，
 * 服务端无法阻止被修改的客户端或远程删除已下载文件。
 */
@Service
public class Licenses {
  private final JdbcTemplate db;
  private final Settings settings;
  private final SecureStore crypto;
  private final TransactionTemplate tx;

  public Licenses(
      JdbcTemplate db, SecureStore crypto, PlatformTransactionManager manager, Settings settings) {
    this.db = db;
    this.settings = settings;
    this.crypto = crypto;
    this.tx = new TransactionTemplate(manager);
  }

  public Map<String, Object> policy(String product) {
    var rows = db.queryForList("SELECT * FROM license_policy WHERE product_id=?", product);
    return rows.isEmpty()
        ? Map.of("revision", 0, "device_limit", settings.integer("license.default_devices"))
        : Commerce.safeRow(rows.get(0));
  }

  public void policy(String actor, String product, int devices, long revision) {
    if (devices < 1 || devices > 1000) throw new Api.Failure(400, "设备数量须为1至1000");
    tx.executeWithoutResult(
        s -> {
          db.queryForObject("SELECT revision FROM catalog_guard WHERE id=1 FOR UPDATE", Long.class);
          one("SELECT id FROM catalog_product WHERE id=?", product);
          if (db.queryForObject(
                  "SELECT COUNT(*) FROM license_policy WHERE product_id=?", Integer.class, product)
              == 0) {
            if (revision != 0) throw new Api.Failure(409, "策略已变化，请刷新");
            db.update(
                "INSERT INTO license_policy(product_id,device_limit,revision) VALUES(?,?,1)",
                product,
                devices);
          } else if (db.update(
                  "UPDATE license_policy SET device_limit=?,revision=revision+1 WHERE product_id=?"
                      + " AND revision=?",
                  devices,
                  product,
                  revision)
              != 1) throw new Api.Failure(409, "策略已变化，请刷新");
          audit(actor, product, "LICENSE_POLICY", "devices=" + devices);
        });
  }

  /** 调用者持有目录锁及发布事务；发布失败则条款也回滚。 */
  public static void snapshot(JdbcTemplate db, String product, String publication, int fallback) {
    var limits =
        db.queryForList(
            "SELECT device_limit FROM license_policy WHERE product_id=?", Integer.class, product);
    db.update(
        "INSERT INTO publication_license_terms(publication_id,device_limit,created_at)"
            + " VALUES(?,?,?)",
        publication,
        limits.isEmpty() ? fallback : limits.get(0),
        System.currentTimeMillis());
  }

  /** 必须在购买事务、钱包和订单锁内调用；没有发布条款的旧LICENSE快照拒绝购买，须重发。 */
  public void issue(String purchase, String publication) {
    var terms =
        db.queryForList(
            "SELECT device_limit FROM publication_license_terms WHERE publication_id=?",
            Integer.class,
            publication);
    if (terms.isEmpty()) throw new Api.Failure(409, "该软件授权条款尚未发布，请联系运营重新发布");
    String key = crypto.token();
    db.update(
        "INSERT INTO software_license(purchase_id,key_hash,key_enc,device_limit,state,created_at)"
            + " VALUES(?,?,?,?,'ACTIVE',?)",
        purchase,
        crypto.digest(key),
        crypto.encrypt(key),
        terms.get(0),
        System.currentTimeMillis());
    Origins.created(db, "software_license", purchase, "SYSTEM");
  }

  /** 与全额退款同事务调用，不另开事务。旧设备令牌即便已签发也立即失效。 */
  public void revoke(String purchase) {
    db.update("UPDATE software_license SET state='REVOKED' WHERE purchase_id=?", purchase);
    db.update(
        "UPDATE license_device SET state='REVOKED',revoked_at=?,token_enc='' WHERE purchase_id=?",
        System.currentTimeMillis(),
        purchase);
    db.update(
        "UPDATE license_unbind_request SET"
            + " state='CANCELLED',revision=revision+1,review_note='订单退款撤销授权',reviewed_at=? WHERE"
            + " purchase_id=? AND state='PENDING'",
        System.currentTimeMillis(),
        purchase);
  }

  private Map<String, Object> one(String sql, Object... args) {
    var rows = db.queryForList(sql, args);
    if (rows.isEmpty()) throw new Api.Failure(404, "授权或设备不存在");
    return rows.get(0);
  }

  /** 已持有用户锁，校验账户停用和订单状态；所有业务判断使用锁定读取，不能改成快照读。 */
  private Map<String, Object> lock(String user, String purchase) {
    var account = one("SELECT disabled FROM app_user WHERE id=? FOR UPDATE", user);
    if (Boolean.TRUE.equals(account.get("disabled"))) throw new Api.Failure(403, "账户已停用");
    var order =
        one(
            "SELECT * FROM purchase_order WHERE id=? AND user_id=? AND mode='SIMULATED' FOR UPDATE",
            purchase,
            user);
    var license = one("SELECT * FROM software_license WHERE purchase_id=? FOR UPDATE", purchase);
    if (!"ACTIVE".equals(order.get("state")) || !"ACTIVE".equals(license.get("state")))
      throw new Api.Failure(403, "授权已失效");
    return order;
  }

  public Map<String, Object> detail(String user, String purchase) {
    return tx.execute(
        s -> {
          var order = lock(user, purchase);
          var license =
              one(
                  "SELECT device_limit,key_enc FROM software_license WHERE purchase_id=?",
                  purchase);
          return Map.of(
              "key",
              crypto.decrypt((String) license.get("key_enc")),
              "deviceLimit",
              license.get("device_limit"),
              "majorVersion",
              order.get("major_version"),
              "productId",
              order.get("product_id"),
              "devices",
              Commerce.safeRows(
                  db.queryForList(
                      "SELECT id,device_name,state,created_at,revoked_at FROM license_device WHERE"
                          + " purchase_id=? ORDER BY created_at DESC,id",
                      purchase)),
              "requests",
              Commerce.safeRows(
                  db.queryForList(
                      "SELECT id,device_id,state,reason,review_note,created_at FROM"
                          + " license_unbind_request WHERE purchase_id=? ORDER BY created_at"
                          + " DESC,id",
                      purchase)));
        });
  }

  public record Activation(
      String key, String deviceId, String deviceName, String productId, String version) {}

  private void credential(String secret) {
    if (secret == null || !secret.matches("[A-Za-z0-9_-]{43}"))
      throw new Api.Failure(403, "授权凭据不正确");
  }

  private void version(Map<String, Object> order, String product, String version) {
    if (version == null
        || version.length() > 80
        || !Objects.equals(product, order.get("product_id"))
        || !version.split("\\.", 2)[0].equals(order.get("major_version")))
      throw new Api.Failure(403, "该授权不适用于此应用或大版本");
  }

  public Map<String, Object> activate(Activation input) {
    credential(input.key());
    if (input.deviceId() == null
        || !input.deviceId().matches("[A-Za-z0-9_-]{32,200}")
        || input.deviceName() == null
        || input.deviceName().isBlank()
        || input.deviceName().length() > 80) throw new Api.Failure(400, "请提供稳定安装标识及1至80字设备名称");
    var brief =
        one(
            "SELECT p.user_id,p.id FROM software_license l JOIN purchase_order p ON"
                + " p.id=l.purchase_id WHERE l.key_hash=?",
            crypto.digest(input.key()));
    String user = (String) brief.get("user_id"), purchase = (String) brief.get("id");
    return tx.execute(
        s -> {
          var order = lock(user, purchase);
          version(order, input.productId(), input.version());
          var license =
              one("SELECT device_limit FROM software_license WHERE purchase_id=?", purchase);
          String hash = crypto.digest(input.deviceId());
          var previous =
              db.queryForList(
                  "SELECT * FROM license_device WHERE purchase_id=? AND device_hash=? FOR UPDATE",
                  purchase,
                  hash);
          if (!previous.isEmpty() && previous.get(0).get("state").equals("ACTIVE"))
            return Map.of(
                "deviceToken",
                crypto.decrypt((String) previous.get(0).get("token_enc")),
                "bindingId",
                previous.get(0).get("id"),
                "majorVersion",
                order.get("major_version"),
                "mode",
                "SIMULATED");
          if (db.queryForObject(
                  "SELECT COUNT(*) FROM license_device WHERE purchase_id=? AND state='ACTIVE'",
                  Integer.class,
                  purchase)
              >= ((Number) license.get("device_limit")).intValue())
            throw new Api.Failure(409, "已达授权设备数，请在个人中心申请换机解绑");
          String token = crypto.token(),
              id =
                  previous.isEmpty()
                      ? UUID.randomUUID().toString()
                      : (String) previous.get(0).get("id");
          if (previous.isEmpty())
            db.update(
                "INSERT INTO"
                    + " license_device(id,purchase_id,device_hash,device_name,token_hash,token_enc,state,created_at)"
                    + " VALUES(?,?,?,?,?,?,'ACTIVE',?)",
                id,
                purchase,
                hash,
                input.deviceName().trim(),
                crypto.digest(token),
                crypto.encrypt(token),
                System.currentTimeMillis());
          else
            db.update(
                "UPDATE license_device SET"
                    + " device_name=?,token_hash=?,token_enc=?,state='ACTIVE',revoked_at=NULL WHERE"
                    + " id=?",
                input.deviceName().trim(),
                crypto.digest(token),
                crypto.encrypt(token),
                id);
          audit(user, id, "LICENSE_ACTIVATE", "purchase=" + purchase);
          return Map.of(
              "deviceToken",
              token,
              "bindingId",
              id,
              "majorVersion",
              order.get("major_version"),
              "mode",
              "SIMULATED");
        });
  }

  public record Verification(
      String bindingId, String deviceToken, String deviceId, String productId, String version) {}

  public Map<String, Object> verify(Verification input) {
    credential(input.deviceToken());
    if (input.deviceId() == null || input.deviceId().length() > 200)
      throw new Api.Failure(403, "设备凭据不正确");
    var brief =
        one(
            "SELECT p.user_id,p.id FROM license_device d JOIN purchase_order p ON"
                + " p.id=d.purchase_id WHERE d.id=?",
            input.bindingId());
    return tx.execute(
        s -> {
          var order = lock((String) brief.get("user_id"), (String) brief.get("id"));
          version(order, input.productId(), input.version());
          var device = one("SELECT * FROM license_device WHERE id=? FOR UPDATE", input.bindingId());
          if (!"ACTIVE".equals(device.get("state"))
              || !crypto.digest(input.deviceToken()).equals(device.get("token_hash"))
              || !crypto.digest(input.deviceId()).equals(device.get("device_hash")))
            throw new Api.Failure(403, "设备授权已失效");
          return Map.of(
              "valid",
              true,
              "majorVersion",
              order.get("major_version"),
              "checkedAt",
              System.currentTimeMillis(),
              "mode",
              "SIMULATED");
        });
  }

  public String request(String user, String purchase, String device, String reason) {
    String note = note(reason);
    return tx.execute(
        s -> {
          lock(user, purchase);
          var binding =
              one(
                  "SELECT * FROM license_device WHERE id=? AND purchase_id=? FOR UPDATE",
                  device,
                  purchase);
          if (!binding.get("state").equals("ACTIVE")) throw new Api.Failure(409, "设备已解绑");
          var pending =
              db.queryForList(
                  "SELECT id FROM license_unbind_request WHERE device_id=? AND state='PENDING'",
                  String.class,
                  device);
          if (!pending.isEmpty()) return pending.get(0);
          String id = UUID.randomUUID().toString();
          db.update(
              "INSERT INTO"
                  + " license_unbind_request(id,purchase_id,device_id,user_id,reason,state,revision,review_note,created_at)"
                  + " VALUES(?,?,?,?,?,'PENDING',1,'',?)",
              id,
              purchase,
              device,
              user,
              note,
              System.currentTimeMillis());
          Origins.created(db, "license_unbind_request", id, user);
          audit(user, id, "REQUEST_UNBIND", "purchase=" + purchase);
          return id;
        });
  }

  public void review(Auth.User actor, String id, boolean approve, String reason, long revision) {
    if (!actor.hasRole("SUPPORT") && !(actor.hasRole("ADMIN") && actor.mfaVerified()))
      throw new Api.Failure(403, "需要客服或管理员权限");
    String note = note(reason);
    var brief = one("SELECT user_id,purchase_id FROM license_unbind_request WHERE id=?", id);
    if (actor.id().equals(brief.get("user_id"))) throw new Api.Failure(403, "不能审核自己的换机申请");
    tx.executeWithoutResult(
        s -> {
          lock((String) brief.get("user_id"), (String) brief.get("purchase_id"));
          var request = one("SELECT * FROM license_unbind_request WHERE id=? FOR UPDATE", id);
          if (!"PENDING".equals(request.get("state"))
              || ((Number) request.get("revision")).longValue() != revision)
            throw new Api.Failure(409, "申请已处理，请刷新");
          if (approve)
            db.update(
                "UPDATE license_device SET state='REVOKED',token_enc='',revoked_at=? WHERE id=? AND"
                    + " state='ACTIVE'",
                System.currentTimeMillis(),
                request.get("device_id"));
          db.update(
              "UPDATE license_unbind_request SET"
                  + " state=?,revision=revision+1,reviewer_id=?,review_note=?,reviewed_at=? WHERE"
                  + " id=?",
              approve ? "APPROVED" : "REJECTED",
              actor.id(),
              note,
              System.currentTimeMillis(),
              id);
          audit(actor.id(), id, approve ? "APPROVE_UNBIND" : "REJECT_UNBIND", note);
          db.update(
              "INSERT INTO member_notification(id,user_id,title,body,created_at) VALUES(?,?,?,?,?)",
              UUID.randomUUID().toString(),
              brief.get("user_id"),
              approve ? "换机解绑已通过" : "换机申请未通过",
              note,
              System.currentTimeMillis());
        });
  }

  private String note(String value) {
    if (value == null || value.isBlank() || value.length() > 500)
      throw new Api.Failure(400, "请填写1至500字原因");
    return value.trim();
  }

  private void audit(String actor, String entity, String action, String detail) {
    AuditLog.write(db, "finance_audit", actor, entity, action, detail);
  }
}
