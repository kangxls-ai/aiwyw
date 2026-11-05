package com.aiwyw;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

/** 游客Cookie与账户归属分开验证；会话行锁串行发送/抢单/转派，原子递增序号和未读游标。 */
@Service
public class SupportService {
  final JdbcTemplate db;
  final SecureStore crypto;
  final Settings settings;
  final Commerce commerce;
  final Accounts accounts;
  final Storage storage;
  final TransactionTemplate tx;

  public record Principal(String user, String visitor, String nickname) {
    String actor() {
      return user == null ? visitor : user;
    }
  }

  SupportService(
      JdbcTemplate db,
      SecureStore crypto,
      Settings settings,
      Commerce commerce,
      Accounts accounts,
      Storage storage,
      PlatformTransactionManager manager) {
    this.db = db;
    this.crypto = crypto;
    this.settings = settings;
    this.commerce = commerce;
    this.accounts = accounts;
    this.storage = storage;
    tx = new TransactionTemplate(manager);
  }

  static long n(Map<String, Object> r, String k) {
    return MeteredApi.n(r, k);
  }

  static String id() {
    return MeteredApi.id();
  }

  Map<String, Object> one(String sql, Object... args) {
    var rows = db.queryForList(sql, args);
    if (rows.isEmpty()) throw new Api.Failure(404, "会话或记录不存在");
    return rows.get(0);
  }

  void audit(String actor, String entity, String action) {
    db.update(
        "INSERT INTO catalog_audit(id,actor_id,entity_id,action_name,detail,created_at)"
            + " VALUES(?,?,?,?,?,?)",
        id(),
        actor,
        entity,
        action,
        "客服操作，不记录聊天正文、邮箱或找回令牌",
        System.currentTimeMillis());
  }

  void mail(String to, String title, String body) {
    if (to == null || to.isBlank()) return;
    long now = System.currentTimeMillis();
    db.update(
        "INSERT INTO mail_outbox(id,recipient,subject,body_enc,status,attempts,next_at,created_at)"
            + " VALUES(?,?,?,?,'PENDING',0,?,?)",
        id(),
        to,
        title,
        crypto.encrypt(body),
        now,
        now);
  }

  String visitor(String token) {
    if (token == null || token.length() > 200) return null;
    var rows =
        db.queryForList(
            "SELECT id FROM support_visitor WHERE token_hash=?",
            String.class,
            crypto.digest(token));
    return rows.isEmpty() ? null : rows.get(0);
  }

  String newVisitor(String token) {
    String key = id();
    db.update(
        "INSERT INTO support_visitor VALUES(?,?,?,FALSE,?)",
        key,
        crypto.digest(token),
        "",
        System.currentTimeMillis());
    return key;
  }

  void bind(String user, String visitor) {
    tx.executeWithoutResult(
        s -> {
          db.update(
              "UPDATE support_conversation SET user_id=? WHERE visitor_id=? AND user_id IS NULL",
              user,
              visitor);
          db.update(
              "UPDATE support_feedback SET user_id=? WHERE visitor_id=? AND user_id IS NULL",
              user,
              visitor);
        });
  }

  public void limit(String identity) {
    accounts.limit("support", identity, settings.integer("support.hour_limit"));
  }

  boolean owner(Principal p, Map<String, Object> c) {
    return c.get("user_id") != null
        ? c.get("user_id").equals(p.user())
        : c.get("visitor_id").equals(p.visitor());
  }

  Map<String, Object> access(Principal p, String key, Auth.User staff, boolean write) {
    var c = one("SELECT * FROM support_conversation WHERE id=?", key);
    if (staff != null) {
      if (write
          ? !staff.id().equals(c.get("assigned_to"))
          : c.get("assigned_to") != null
              && !staff.id().equals(c.get("assigned_to"))
              && !staff.hasRole("ADMIN")) throw new Api.Failure(403, "此会话由其他客服负责，请先由管理员转派");
    } else if (!owner(p, c)) throw new Api.Failure(404, "会话不存在");
    return c;
  }

  public record NewConversation(String subject, String topic, String email, boolean emailOptin) {}

  public String create(Principal p, NewConversation b) {
    String subject = MeteredApi.text(b.subject(), 120, true),
        topic = MeteredApi.text(b.topic(), 16, true),
        email = MeteredApi.text(b.email(), 254, false);
    if (!Set.of("GENERAL", "RECHARGE").contains(topic)) throw new Api.Failure(400, "咨询类型不正确");
    if (topic.equals("RECHARGE") && p.user() == null) throw new Api.Failure(401, "充值咨询请先登录");
    if (!email.isBlank()) email = accounts.email(email);
    final String mail = email;
    return tx.execute(
        s -> {
          var oldVisitor =
              one("SELECT id,email FROM support_visitor WHERE id=? FOR UPDATE", p.visitor());
          if (!oldVisitor.get("email").equals(mail))
            db.update(
                "UPDATE support_recovery SET used_at=? WHERE visitor_id=? AND used_at IS NULL",
                System.currentTimeMillis(),
                p.visitor());
          db.update(
              "UPDATE support_visitor SET email=?,email_optin=? WHERE id=?",
              mail,
              b.emailOptin(),
              p.visitor());
          String key = id();
          long now = System.currentTimeMillis();
          db.update(
              "INSERT INTO"
                  + " support_conversation(id,user_id,visitor_id,subject,topic,state,last_seq,member_read,staff_read,last_notice_at,revision,created_at,updated_at)"
                  + " VALUES(?,?,?,?,?,'OPEN',0,0,0,0,1,?,?)",
              key,
              p.user(),
              p.visitor(),
              subject,
              topic,
              now,
              now);
          audit(p.actor(), key, "CREATE_CONVERSATION");
          return key;
        });
  }

  /** 公共列表不暴露邮箱；游客必须携带原Cookie，登录用户只能读归属账户。 */
  public Map<String, Object> conversations(
      Principal p, Auth.User staff, String q, String state, int page, int size) {
    return conversations(p, staff, q, state, page, size, Map.of());
  }

  public Map<String, Object> conversations(
      Principal p,
      Auth.User staff,
      String q,
      String state,
      int page,
      int size,
      Map<String, String> filters) {
    if (page < 1
        || page > 1_000_000
        || size < 1
        || size > 100
        || q.length() > 100
        || (!state.isBlank() && !Set.of("OPEN", "CLOSED").contains(state)))
      throw new Api.Failure(400, "查询不正确");
    List<Object> args = new ArrayList<>();
    String where = " WHERE 1=1";
    if (staff == null) {
      if (p.user() != null) {
        where += " AND c.user_id=?";
        args.add(p.user());
      } else {
        where += " AND c.user_id IS NULL AND c.visitor_id=?";
        args.add(p.visitor());
      }
    } else if (!staff.hasRole("ADMIN")) {
      where += " AND (c.assigned_to IS NULL OR c.assigned_to=?)";
      args.add(staff.id());
    }
    if (!state.isBlank()) {
      where += " AND c.state=?";
      args.add(state);
    }
    if (!q.isBlank()) {
      where += " AND c.subject LIKE ? ESCAPE '!'";
      args.add("%" + q.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%");
    }
    if (!filters.getOrDefault("from", "").isBlank()
        || !filters.getOrDefault("until", "").isBlank()) {
      var w = OperationsStats.window(filters.get("from"), filters.get("until"));
      where += " AND c.created_at>=? AND c.created_at<?";
      args.add(w.start());
      args.add(w.end());
    }
    String ordering =
        ListOrder.sql(
            filters.getOrDefault("sort", "created_at"),
            filters.getOrDefault("direction", "descending"),
            Map.of(
                "created_at",
                "c.created_at",
                "subject",
                "c.subject",
                "state",
                "c.state",
                "updated_at",
                "c.updated_at",
                "assigned_name",
                "u.nickname",
                "unread",
                "unread"),
            "created_at",
            "c.id");
    long total =
        db.queryForObject(
            "SELECT COUNT(*) FROM support_conversation c" + where, Long.class, args.toArray());
    int actual = (int) Math.min(page, Math.max(1, (total + size - 1) / size));
    args.add(size);
    args.add((actual - 1) * size);
    String unread =
        staff == null
            ? "m.actor_type='STAFF' AND m.seq>c.member_read"
            : "m.actor_type<>'STAFF' AND m.seq>c.staff_read";
    var rows =
        Commerce.safeRows(
            db.queryForList(
                "SELECT"
                    + " c.id,c.subject,c.topic,c.state,c.assigned_to,c.revision,c.last_seq,c.created_at,c.updated_at,u.nickname"
                    + " AS assigned_name,(SELECT COUNT(*) FROM support_message m WHERE"
                    + " m.conversation_id=c.id AND "
                    + unread
                    + ") AS unread FROM support_conversation c LEFT JOIN app_user u ON"
                    + " u.id=c.assigned_to"
                    + where
                    + " ORDER BY "
                    + ordering
                    + " LIMIT ? OFFSET ?",
                args.toArray()));
    return Map.of("items", rows, "total", total, "page", actual);
  }

  public Map<String, Object> messages(
      Principal p, String key, Auth.User staff, long after, long before) {
    if (after < 0 || before < 0) throw new Api.Failure(400, "消息游标不正确");
    var c = access(p, key, staff, false);
    List<Map<String, Object>> rows;
    if (before > 0)
      rows =
          db.queryForList(
              "SELECT * FROM support_message WHERE conversation_id=? AND seq<? ORDER BY seq DESC"
                  + " LIMIT 100",
              key,
              before);
    else if (after == 0)
      rows =
          db.queryForList(
              "SELECT * FROM support_message WHERE conversation_id=? ORDER BY seq DESC LIMIT 100",
              key);
    else
      rows =
          db.queryForList(
              "SELECT * FROM support_message WHERE conversation_id=? AND seq>? ORDER BY seq LIMIT"
                  + " 100",
              key,
              after);
    if (before > 0 || after == 0) Collections.reverse(rows);
    for (var m : rows) {
      m.remove("request_hash");
      m.remove("actor_id");
      m.remove("client_key");
    }
    c.remove("visitor_id");
    c.remove("user_id");
    c.remove("last_notice_at");
    return Map.of(
        "conversation", Commerce.safeRows(List.of(c)).get(0), "items", Commerce.safeRows(rows));
  }

  public record Message(String clientKey, String body, String assetId) {}

  public Map<String, Object> send(Principal p, String key, Auth.User staff, Message b) {
    String client = MeteredApi.requestKey(b.clientKey()),
        body = MeteredApi.text(b.body(), 4000, false),
        asset = MeteredApi.text(b.assetId(), 36, false);
    if (body.isBlank() && asset.isBlank()) throw new Api.Failure(400, "请输入消息或上传图片");
    String actor = staff == null ? p.actor() : staff.id(),
        hash = crypto.digest(actor + ":" + body + ":" + asset);
    return tx.execute(
        s -> {
          one("SELECT id FROM support_conversation WHERE id=? FOR UPDATE", key);
          var c = access(p, key, staff, staff != null);
          var repeated =
              db.queryForList(
                  "SELECT id,seq,request_hash FROM support_message WHERE conversation_id=? AND"
                      + " client_key=?",
                  key,
                  client);
          if (!repeated.isEmpty()) {
            if (!repeated.get(0).get("request_hash").equals(hash))
              throw new Api.Failure(409, "消息标识不能更换内容");
            return Map.of("id", repeated.get(0).get("id"), "seq", repeated.get(0).get("seq"));
          }
          if (!c.get("state").equals("OPEN")) throw new Api.Failure(409, "会话已结束，请重新打开或新建咨询");
          String msg = id();
          long seq = n(c, "last_seq") + 1, now = System.currentTimeMillis();
          if (!asset.isBlank()) {
            one(
                "SELECT a.asset_id FROM support_attachment a JOIN storage_asset f ON"
                    + " f.id=a.asset_id WHERE a.asset_id=? AND a.conversation_id=? AND"
                    + " a.uploader_id=? AND a.message_id IS NULL AND f.status='READY' AND"
                    + " f.purpose='CHAT' FOR UPDATE",
                asset,
                key,
                actor);
            db.update("UPDATE support_attachment SET message_id=? WHERE asset_id=?", msg, asset);
            db.update("INSERT INTO storage_reference VALUES(?,'CHAT',?)", asset, msg);
          }
          String nickname = staff == null ? p.nickname() : staff.nickname();
          if (nickname == null || nickname.isBlank()) nickname = staff == null ? "游客" : "客服";
          db.update(
              "INSERT INTO"
                  + " support_message(id,conversation_id,seq,client_key,request_hash,actor_type,actor_id,nickname,kind,body,asset_id,created_at)"
                  + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
              msg,
              key,
              seq,
              client,
              hash,
              staff != null ? "STAFF" : p.user() != null ? "MEMBER" : "GUEST",
              actor,
              nickname,
              asset.isBlank() ? "TEXT" : "IMAGE",
              body,
              asset.isBlank() ? null : asset,
              now);
          db.update(
              "UPDATE support_conversation SET last_seq=?,updated_at=?"
                  + (staff != null ? ",first_reply_at=COALESCE(first_reply_at,?)" : "")
                  + " WHERE id=?",
              staff != null ? new Object[] {seq, now, now, key} : new Object[] {seq, now, key});
          if (staff != null) {
            if (c.get("user_id") != null)
              commerce.notify((String) c.get("user_id"), "客服回复了你的咨询", (String) c.get("subject"));
            else {
              var guest =
                  one(
                      "SELECT email,email_optin FROM support_visitor WHERE id=?",
                      c.get("visitor_id"));
              if (Boolean.TRUE.equals(guest.get("email_optin")))
                mail(
                    (String) guest.get("email"),
                    "AI万有网客服回复",
                    "你的咨询已有回复，请使用原浏览器访问 https://"
                        + settings.value("site.domain")
                        + "/support 查看。邮件不包含私有聊天正文。");
            }
          } else offline(c, key, now);
          return Map.of("id", msg, "seq", seq);
        });
  }

  void offline(Map<String, Object> c, String key, long now) {
    long cutoff = now - settings.integer("support.presence_seconds") * 1000L;
    String agent = (String) c.get("assigned_to");
    long online =
        agent == null
            ? db.queryForObject(
                "SELECT COUNT(*) FROM support_presence p JOIN app_user u ON u.id=p.user_id WHERE"
                    + " p.seen_at>? AND u.disabled=FALSE",
                Long.class,
                cutoff)
            : db.queryForObject(
                "SELECT COUNT(*) FROM support_presence p JOIN app_user u ON u.id=p.user_id WHERE"
                    + " p.user_id=? AND p.seen_at>? AND u.disabled=FALSE",
                Long.class,
                agent,
                cutoff);
    if (online == 0
        && now - n(c, "last_notice_at")
            >= settings.integer("support.offline_mail_minutes") * 60000L) {
      mail(
          settings.value("support.inbox"),
          "AI万有网有新的离线咨询",
          "请登录客服工作台查看会话 " + key + "。邮件不包含用户正文和私有图片。");
      db.update("UPDATE support_conversation SET last_notice_at=? WHERE id=?", now, key);
    }
  }

  public void read(Principal p, String key, Auth.User staff, long seq) {
    tx.executeWithoutResult(
        s -> {
          one("SELECT id FROM support_conversation WHERE id=? FOR UPDATE", key);
          var c = access(p, key, staff, staff != null);
          long bounded = Math.min(Math.max(0, seq), n(c, "last_seq"));
          String field = staff == null ? "member_read" : "staff_read";
          db.update(
              "UPDATE support_conversation SET "
                  + field
                  + "=CASE WHEN "
                  + field
                  + "<? THEN ? ELSE "
                  + field
                  + " END WHERE id=?",
              bounded,
              bounded,
              key);
        });
  }

  public void heartbeat(Auth.User u) {
    long now = System.currentTimeMillis();
    if (db.update("UPDATE support_presence SET seen_at=? WHERE user_id=?", now, u.id()) == 0)
      try {
        db.update("INSERT INTO support_presence VALUES(?,?)", u.id(), now);
      } catch (org.springframework.dao.DuplicateKeyException ignored) {
        /* 并发心跳下轮覆盖即可。 */
      }
  }

  public long online() {
    return db.queryForObject(
        "SELECT COUNT(*) FROM support_presence p JOIN app_user u ON u.id=p.user_id WHERE"
            + " p.seen_at>? AND u.disabled=FALSE",
        Long.class,
        System.currentTimeMillis() - settings.integer("support.presence_seconds") * 1000L);
  }

  public List<Map<String, Object>> agents() {
    var rows =
        Commerce.safeRows(
            db.queryForList(
                "SELECT id,nickname FROM app_user WHERE disabled=FALSE ORDER BY nickname,id"));
    return rows.stream().filter(r -> canReceive((String) r.get("id"))).toList();
  }

  boolean canReceive(String user) {
    return db.queryForObject(
            "SELECT COUNT(*) FROM access_user_role ur JOIN access_role_menu rm ON"
                + " rm.role_id=ur.role_id JOIN app_user u ON u.id=ur.user_id WHERE ur.user_id=? AND"
                + " u.disabled=FALSE AND rm.menu_id='conversations'",
            Integer.class,
            user)
        > 0;
  }

  public void assign(Auth.User staff, String key, String target, long revision) {
    boolean transfer = target != null && !target.isBlank();
    if (transfer && !staff.hasRole("ADMIN")) throw new Api.Failure(403, "仅管理员转派会话");
    String agent = transfer ? target : staff.id();
    if (!canReceive(agent)) throw new Api.Failure(400, "接收账号未授权在线会话菜单");
    tx.executeWithoutResult(
        s -> {
          var c = one("SELECT * FROM support_conversation WHERE id=? FOR UPDATE", key);
          if (n(c, "revision") != revision
              || !c.get("state").equals("OPEN")
              || (!transfer
                  && c.get("assigned_to") != null
                  && !c.get("assigned_to").equals(staff.id())))
            throw new Api.Failure(409, "会话已被领取或状态已变化");
          db.update(
              "UPDATE support_conversation SET assigned_to=?,staff_read=0,revision=revision+1 WHERE"
                  + " id=?",
              agent,
              key);
          audit(staff.id(), key, transfer ? "TRANSFER_CONVERSATION" : "CLAIM_CONVERSATION");
        });
  }

  public void state(Principal p, String key, Auth.User staff, String state, long revision) {
    if (!Set.of("OPEN", "CLOSED").contains(state)) throw new Api.Failure(400, "状态不正确");
    tx.executeWithoutResult(
        s -> {
          one("SELECT id FROM support_conversation WHERE id=? FOR UPDATE", key);
          access(p, key, staff, staff != null);
          if (db.update(
                  "UPDATE support_conversation SET state=?,revision=revision+1,updated_at=? WHERE"
                      + " id=? AND revision=?",
                  state,
                  System.currentTimeMillis(),
                  key,
                  revision)
              != 1) throw new Api.Failure(409, "会话状态已变化");
          audit(staff == null ? p.actor() : staff.id(), key, "CONVERSATION_" + state);
        });
  }

  public Storage.Asset upload(Principal p, String key, Auth.User staff, MultipartFile file) {
    access(p, key, staff, staff != null);
    String actor = staff == null ? p.actor() : staff.id();
    var asset = storage.upload(file, "CHAT", actor);
    try {
      tx.executeWithoutResult(
          s -> {
            one("SELECT id FROM support_conversation WHERE id=? FOR UPDATE", key);
            access(p, key, staff, staff != null);
            db.update(
                "INSERT INTO support_attachment VALUES(?,?,?,NULL,?)",
                asset.id(),
                key,
                actor,
                System.currentTimeMillis());
          });
      return asset;
    } catch (RuntimeException e) {
      try {
        storage.delete(asset.id(), actor);
      } catch (Exception ignored) {
      }
      throw e;
    }
  }

  Storage.Stored image(Principal p, Auth.User staff, String asset) {
    var a = one("SELECT * FROM support_attachment WHERE asset_id=?", asset);
    access(p, (String) a.get("conversation_id"), staff, false);
    if (a.get("message_id") == null
        && !a.get("uploader_id").equals(staff == null ? p.actor() : staff.id()))
      throw new Api.Failure(404, "图片不存在");
    return storage.stored(asset, true);
  }

  public void recover(String conversation, String rawEmail) {
    String email = accounts.email(rawEmail);
    var rows =
        db.queryForList(
            "SELECT v.id FROM support_visitor v JOIN support_conversation c ON c.visitor_id=v.id"
                + " WHERE c.id=? AND c.user_id IS NULL AND v.email=?",
            conversation,
            email);
    if (rows.isEmpty()) return;
    tx.executeWithoutResult(
        s -> {
          var current =
              one("SELECT email FROM support_visitor WHERE id=? FOR UPDATE", rows.get(0).get("id"));
          if (!current.get("email").equals(email)) return;
          String token = crypto.token();
          db.update(
              "INSERT INTO support_recovery VALUES(?,?,?,NULL)",
              crypto.digest(token),
              rows.get(0).get("id"),
              System.currentTimeMillis() + settings.integer("support.recovery_minutes") * 60000L);
          mail(
              email,
              "找回AI万有网游客会话",
              "请使用一次性链接核验邮箱并找回原浏览器的游客会话：https://"
                  + settings.value("site.domain")
                  + "/support?recover="
                  + token
                  + "\n此链接将换发游客凭据，不能转发给他人。");
        });
  }

  public String consumeRecovery(String token) {
    if (token == null || token.length() > 200) throw new Api.Failure(400, "找回链接无效");
    return tx.execute(
        s -> {
          var brief =
              one(
                  "SELECT visitor_id FROM support_recovery WHERE token_hash=?",
                  crypto.digest(token));
          // 与修改邮箱、签发链接一致，先锁游客再锁令牌，防止旧邮箱链接继续找回历史。
          one("SELECT id FROM support_visitor WHERE id=? FOR UPDATE", brief.get("visitor_id"));
          var r =
              one(
                  "SELECT * FROM support_recovery WHERE token_hash=? FOR UPDATE",
                  crypto.digest(token));
          if (r.get("used_at") != null || n(r, "expires_at") <= System.currentTimeMillis())
            throw new Api.Failure(400, "找回链接已失效");
          String fresh = crypto.token();
          db.update(
              "UPDATE support_visitor SET token_hash=? WHERE id=?",
              crypto.digest(fresh),
              r.get("visitor_id"));
          db.update(
              "UPDATE support_recovery SET used_at=? WHERE token_hash=?",
              System.currentTimeMillis(),
              crypto.digest(token));
          return fresh;
        });
  }

  public record Feedback(String kind, String body) {}

  public String feedback(Principal p, Feedback b) {
    String kind = MeteredApi.text(b.kind(), 12, true), body = MeteredApi.text(b.body(), 4000, true);
    if (!Set.of("PUBLIC", "PRIVATE").contains(kind)) throw new Api.Failure(400, "请选择公开留言或私密反馈");
    String key = id();
    long now = System.currentTimeMillis();
    db.update(
        "INSERT INTO support_feedback VALUES(?,?,?,?,?,?,'PENDING','',NULL,1,?,?)",
        key,
        p.user(),
        p.visitor(),
        p.nickname(),
        kind,
        body,
        now,
        now);
    return key;
  }

  public Map<String, Object> feedbackList(
      Principal p, String scope, Auth.User staff, int page, int size) {
    if (page < 1 || page > 1_000_000 || size < 1 || size > 100) throw new Api.Failure(400, "查询不正确");
    String where;
    List<Object> args = new ArrayList<>();
    boolean publicList = scope.equals("public");
    if (publicList) where = " WHERE kind='PUBLIC' AND state='APPROVED'";
    else if (staff != null) {
      where = " WHERE kind=?";
      args.add(scope.equals("messages") ? "PUBLIC" : "PRIVATE");
    } else if (p.user() != null) {
      where = " WHERE user_id=?";
      args.add(p.user());
    } else {
      where = " WHERE user_id IS NULL AND visitor_id=?";
      args.add(p.visitor());
    }
    long total =
        db.queryForObject(
            "SELECT COUNT(*) FROM support_feedback" + where, Long.class, args.toArray());
    int actual = (int) Math.min(page, Math.max(1, (total + size - 1) / size));
    args.add(size);
    args.add((actual - 1) * size);
    return Map.of(
        "items",
        Commerce.safeRows(
            db.queryForList(
                "SELECT id,nickname,kind,body,state,reply,revision,created_at,updated_at FROM"
                    + " support_feedback"
                    + where
                    + " ORDER BY created_at DESC,id LIMIT ? OFFSET ?",
                args.toArray())),
        "total",
        total,
        "page",
        actual);
  }

  public void reviewFeedback(
      Auth.User staff, String key, String state, String reply, long revision) {
    String note = MeteredApi.text(reply, 4000, true);
    tx.executeWithoutResult(
        s -> {
          var f = one("SELECT * FROM support_feedback WHERE id=? FOR UPDATE", key);
          String menu = f.get("kind").equals("PUBLIC") ? "messages" : "feedback";
          if (!staff.menus().contains(menu)) throw new Api.Failure(403, "没有对应反馈菜单权限");
          if (!(f.get("kind").equals("PUBLIC")
                  ? Set.of("APPROVED", "REJECTED")
                  : Set.of("RESOLVED", "REJECTED"))
              .contains(state)) throw new Api.Failure(400, "处理状态不正确");
          if (db.update(
                  "UPDATE support_feedback SET"
                      + " state=?,reply=?,reviewer_id=?,revision=revision+1,updated_at=? WHERE id=?"
                      + " AND revision=?",
                  state,
                  note,
                  staff.id(),
                  System.currentTimeMillis(),
                  key,
                  revision)
              != 1) throw new Api.Failure(409, "反馈已变化");
          if (f.get("user_id") != null)
            commerce.notify((String) f.get("user_id"), "意见反馈已有回复", "请到联系客服页面查看你的反馈记录。");
          audit(staff.id(), key, "REVIEW_FEEDBACK");
        });
  }

  @Scheduled(fixedDelay = 3600000)
  public void purge() {
    long cutoff =
        System.currentTimeMillis() - settings.integer("support.retention_days") * 86400000L;
    for (String key :
        db.queryForList(
            "SELECT id FROM support_conversation WHERE updated_at<? ORDER BY updated_at LIMIT 100",
            String.class,
            cutoff)) {
      List<String> assets =
          tx.execute(
              s -> {
                var c =
                    one("SELECT updated_at FROM support_conversation WHERE id=? FOR UPDATE", key);
                if (n(c, "updated_at") >= cutoff) return List.<String>of();
                var ids =
                    db.queryForList(
                        "SELECT asset_id FROM support_attachment WHERE conversation_id=?",
                        String.class,
                        key);
                db.update(
                    "DELETE FROM storage_reference WHERE owner_type='CHAT' AND owner_id IN (SELECT"
                        + " id FROM support_message WHERE conversation_id=?)",
                    key);
                db.update("DELETE FROM support_attachment WHERE conversation_id=?", key);
                db.update("DELETE FROM support_message WHERE conversation_id=?", key);
                db.update("DELETE FROM support_conversation WHERE id=?", key);
                return ids;
              });
      for (String asset : assets)
        try {
          storage.delete(asset, "SYSTEM");
        } catch (Exception ignored) {
          /* 私有孤立文件保留待后台凭据恢复后重试清理。 */
        }
    }
    for (var a :
        db.queryForList(
            "SELECT asset_id,conversation_id FROM support_attachment WHERE message_id IS NULL AND"
                + " created_at<? LIMIT 100",
            System.currentTimeMillis() - 86400000L))
      try {
        String asset = (String) a.get("asset_id");
        tx.executeWithoutResult(
            s -> {
              one(
                  "SELECT id FROM support_conversation WHERE id=? FOR UPDATE",
                  a.get("conversation_id"));
              db.queryForList("SELECT id FROM storage_asset WHERE id=? FOR UPDATE", asset);
              db.update(
                  "DELETE FROM support_attachment WHERE asset_id=? AND message_id IS NULL", asset);
            });
        storage.delete(asset, "SYSTEM");
      } catch (Exception ignored) {
      }
    db.update("DELETE FROM support_recovery WHERE expires_at<?", System.currentTimeMillis());
    // 对象存储故障时删除可能失败；保留私有状态，下轮在凭据恢复后重试孤立CHAT文件。
    for (String asset :
        db.queryForList(
            "SELECT id FROM storage_asset a WHERE a.purpose='CHAT' AND a.status IN"
                + " ('READY','DELETE_PENDING') AND a.created_at<? AND NOT EXISTS (SELECT 1 FROM"
                + " support_attachment x WHERE x.asset_id=a.id) LIMIT 100",
            String.class,
            System.currentTimeMillis() - 86400000L))
      try {
        storage.delete(asset, "SYSTEM");
      } catch (Exception ignored) {
      }
    db.update(
        "DELETE FROM support_visitor WHERE created_at<? AND id NOT IN (SELECT visitor_id FROM"
            + " support_conversation) AND id NOT IN (SELECT visitor_id FROM support_feedback) AND"
            + " id NOT IN (SELECT visitor_id FROM support_recovery)",
        cutoff);
  }
}
