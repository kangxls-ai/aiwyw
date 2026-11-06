package com.aiwyw;

import jakarta.servlet.http.*;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** 私有会话每个端点重新鉴权；游客凭据放HttpOnly Cookie，公开留言接口不返回邮箱与身份凭据。 */
@RestController
public class SupportController {
  final SupportService service;
  final Auth auth;
  final StorageController files;

  SupportController(SupportService service, Auth auth, StorageController files) {
    this.service = service;
    this.auth = auth;
    this.files = files;
  }

  void cookie(HttpServletRequest r, HttpServletResponse response, String token) {
    var c = new Cookie("AIWYW_SUPPORT", token);
    c.setHttpOnly(true);
    c.setSecure(AuthController.secure(r));
    c.setPath("/");
    c.setMaxAge(service.settings.integer("support.retention_days") * 86400);
    c.setAttribute("SameSite", "Lax");
    response.addCookie(c);
  }

  SupportService.Principal principal(HttpServletRequest r, HttpServletResponse response) {
    String token = null;
    if (r.getCookies() != null)
      for (var c : r.getCookies()) if (c.getName().equals("AIWYW_SUPPORT")) token = c.getValue();
    String visitor = service.visitor(token);
    if (visitor == null) {
      service.limit("visitor:" + r.getRemoteAddr());
      token = service.crypto.token();
      visitor = service.newVisitor(token);
    }
    cookie(r, response, token);
    Auth.User user = null;
    try {
      user = auth.user(r, false);
    } catch (Api.Failure denied) {
      if (denied.status != 401) throw denied;
    }
    if (user != null) service.bind(user.id(), visitor);
    return new SupportService.Principal(
        user == null ? null : user.id(), visitor, user == null ? "游客" : user.nickname());
  }

  @GetMapping("/api/support/session")
  Map<String, Object> session(HttpServletRequest r, HttpServletResponse response) {
    var p = principal(r, response);
    var visitor =
        service.one("SELECT email,email_optin FROM support_visitor WHERE id=?", p.visitor());
    return Map.of(
        "loggedIn",
        p.user() != null,
        "nickname",
        p.nickname(),
        "email",
        visitor.get("email"),
        "emailOptin",
        visitor.get("email_optin"),
        "online",
        service.online(),
        "pollSeconds",
        service.settings.integer("support.poll_seconds"));
  }

  @GetMapping("/api/support/conversations")
  Map<String, Object> list(
      @RequestParam(defaultValue = "") String q,
      @RequestParam(defaultValue = "") String state,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int pageSize,
      HttpServletRequest r,
      HttpServletResponse response) {
    return service.conversations(principal(r, response), null, q, state, page, pageSize);
  }

  @PostMapping("/api/support/conversations")
  Map<String, String> create(
      @RequestBody SupportService.NewConversation b,
      HttpServletRequest r,
      HttpServletResponse response) {
    var p = principal(r, response);
    service.limit(r.getRemoteAddr());
    return Map.of("id", service.create(p, b));
  }

  @GetMapping("/api/support/conversations/{id}/messages")
  Map<String, Object> messages(
      @PathVariable String id,
      @RequestParam(defaultValue = "0") long after,
      @RequestParam(defaultValue = "0") long before,
      HttpServletRequest r,
      HttpServletResponse response) {
    return service.messages(principal(r, response), id, null, after, before);
  }

  @PostMapping("/api/support/conversations/{id}/messages")
  Map<String, Object> send(
      @PathVariable String id,
      @RequestBody SupportService.Message b,
      HttpServletRequest r,
      HttpServletResponse response) {
    var p = principal(r, response);
    service.limit(r.getRemoteAddr());
    return service.send(p, id, null, b);
  }

  record Read(long seq) {}

  @PostMapping("/api/support/conversations/{id}/read")
  Map<String, Boolean> read(
      @PathVariable String id,
      @RequestBody Read b,
      HttpServletRequest r,
      HttpServletResponse response) {
    service.read(principal(r, response), id, null, b.seq());
    return Map.of("success", true);
  }

  record State(String state, long revision) {}

  @PostMapping("/api/support/conversations/{id}/state")
  Map<String, Boolean> state(
      @PathVariable String id,
      @RequestBody State b,
      HttpServletRequest r,
      HttpServletResponse response) {
    service.state(principal(r, response), id, null, b.state(), b.revision());
    return Map.of("success", true);
  }

  @PostMapping("/api/support/conversations/{id}/image")
  Storage.Asset upload(
      @PathVariable String id,
      @RequestParam("file") MultipartFile file,
      HttpServletRequest r,
      HttpServletResponse response) {
    return service.upload(principal(r, response), id, null, file);
  }

  /** 供安全过滤器在MultipartResolver读取正文前调用，验证会话归属与上传频控。 */
  void authorizeUpload(HttpServletRequest r, HttpServletResponse response) {
    String[] parts = r.getRequestURI().split("/");
    String id = parts[parts.length - 2];
    service.limit(r.getRemoteAddr());
    if (r.getRequestURI().startsWith("/api/admin/"))
      service.access(null, id, auth.permission(r, "conversations"), true);
    else service.access(principal(r, response), id, null, false);
  }

  @GetMapping("/api/support/assets/{id}")
  ResponseEntity<?> image(
      @PathVariable String id, HttpServletRequest r, HttpServletResponse response) {
    Auth.User staff = null;
    try {
      var u = auth.user(r, false);
      if (u.menus().contains("conversations")) staff = auth.permission(r, "conversations");
    } catch (Api.Failure denied) {
      if (denied.status != 401) throw denied;
    }
    return files.content(
        service.image(staff == null ? principal(r, response) : null, staff, id), false);
  }

  record Recovery(String conversationId, String email) {}

  @PostMapping("/api/support/recovery")
  Map<String, String> recover(@RequestBody Recovery b, HttpServletRequest r) {
    service.limit("recovery:" + r.getRemoteAddr());
    service.recover(b.conversationId(), b.email());
    return Map.of("message", "资料匹配时会发送核验邮件；无法核验请新建咨询。");
  }

  record Token(String token) {}

  @PostMapping("/api/support/recovery/consume")
  Map<String, Boolean> consume(
      @RequestBody Token b, HttpServletRequest r, HttpServletResponse response) {
    service.limit("recovery:" + r.getRemoteAddr());
    cookie(r, response, service.consumeRecovery(b.token()));
    return Map.of("success", true);
  }

  @GetMapping("/api/public/messages")
  Map<String, Object> publicMessages(
      @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize) {
    return service.feedbackList(null, "public", null, page, pageSize);
  }

  @PostMapping("/api/support/feedback")
  Map<String, String> feedback(
      @RequestBody SupportService.Feedback b, HttpServletRequest r, HttpServletResponse response) {
    var p = principal(r, response);
    service.limit(r.getRemoteAddr());
    return Map.of("id", service.feedback(p, b));
  }

  @GetMapping("/api/support/feedback")
  Map<String, Object> mine(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int pageSize,
      HttpServletRequest r,
      HttpServletResponse response) {
    return service.feedbackList(principal(r, response), "mine", null, page, pageSize);
  }

  @GetMapping("/api/admin/support/conversations")
  Map<String, Object> adminList(
      @RequestParam(defaultValue = "") String q,
      @RequestParam(defaultValue = "") String state,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int pageSize,
      @RequestParam Map<String, String> filters,
      HttpServletRequest r) {
    var staff = auth.permission(r, "conversations");
    service.heartbeat(staff);
    var result =
        new HashMap<>(service.conversations(null, staff, q, state, page, pageSize, filters));
    result.put("agents", service.agents());
    result.put("pollSeconds", service.settings.integer("support.poll_seconds"));
    result.put("self", staff.id());
    result.put("canTransfer", staff.hasRole("ADMIN"));
    return result;
  }

  @GetMapping("/api/admin/support/unread")
  Map<String, Object> unread(HttpServletRequest r) {
    var u = auth.permission(r, "conversations");
    return Map.of(
        "unread",
        service.db.queryForObject(
            "SELECT COUNT(*) FROM support_message m JOIN support_conversation c ON"
                + " c.id=m.conversation_id WHERE c.assigned_to=? AND m.actor_type<>'STAFF' AND"
                + " m.seq>c.staff_read",
            Long.class,
            u.id()),
        "waiting",
        service.db.queryForObject(
            "SELECT COUNT(*) FROM support_conversation WHERE assigned_to IS NULL AND state='OPEN'",
            Long.class));
  }

  @GetMapping("/api/admin/support/conversations/{id}/messages")
  Map<String, Object> adminMessages(
      @PathVariable String id,
      @RequestParam(defaultValue = "0") long after,
      @RequestParam(defaultValue = "0") long before,
      HttpServletRequest r) {
    var staff = auth.permission(r, "conversations");
    service.heartbeat(staff);
    return service.messages(null, id, staff, after, before);
  }

  @PostMapping("/api/admin/support/conversations/{id}/messages")
  Map<String, Object> adminSend(
      @PathVariable String id, @RequestBody SupportService.Message b, HttpServletRequest r) {
    var staff = auth.permission(r, "conversations");
    service.limit(staff.id());
    return service.send(null, id, staff, b);
  }

  @PostMapping("/api/admin/support/conversations/{id}/read")
  Map<String, Boolean> adminRead(
      @PathVariable String id, @RequestBody Read b, HttpServletRequest r) {
    service.read(null, id, auth.permission(r, "conversations"), b.seq());
    return Map.of("success", true);
  }

  record Assignment(String userId, long revision) {}

  @PostMapping("/api/admin/support/conversations/{id}/assign")
  Map<String, Boolean> assign(
      @PathVariable String id, @RequestBody Assignment b, HttpServletRequest r) {
    service.assign(auth.permission(r, "conversations"), id, b.userId(), b.revision());
    return Map.of("success", true);
  }

  @PostMapping("/api/admin/support/conversations/{id}/state")
  Map<String, Boolean> adminState(
      @PathVariable String id, @RequestBody State b, HttpServletRequest r) {
    service.state(null, id, auth.permission(r, "conversations"), b.state(), b.revision());
    return Map.of("success", true);
  }

  @PostMapping("/api/admin/support/conversations/{id}/image")
  Storage.Asset adminUpload(
      @PathVariable String id, @RequestParam("file") MultipartFile file, HttpServletRequest r) {
    return service.upload(null, id, auth.permission(r, "conversations"), file);
  }

  @GetMapping("/api/admin/support/feedback")
  Map<String, Object> feedbackList(
      @RequestParam String scope,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int pageSize,
      HttpServletRequest r) {
    if (!Set.of("messages", "feedback").contains(scope)) throw new Api.Failure(400, "范围不正确");
    return service.feedbackList(null, scope, auth.permission(r, scope), page, pageSize);
  }

  record FeedbackReview(String state, String reply, long revision) {}

  @GetMapping("/api/admin/support/feedback/page")
  Map<String, Object> feedbackPage(
      @RequestParam String scope, @RequestParam Map<String, String> p, HttpServletRequest r) {
    if (!Set.of("messages", "feedback").contains(scope)) throw new Api.Failure(400, "范围不正确");
    auth.permission(r, scope);
    String where = "kind=?";
    List<Object> args = new ArrayList<>();
    args.add(scope.equals("messages") ? "PUBLIC" : "PRIVATE");
    if (!p.getOrDefault("state", "").isBlank()) {
      where += " AND state=?";
      args.add(MeteredApi.text(p.get("state"), 12, true));
    }
    return OperationalPage.query(
        service.db,
        "SELECT id,nickname,kind,body,state,reply,revision,created_at,updated_at FROM"
            + " support_feedback",
        where,
        args,
        Map.of(
            "id",
            "id",
            "created_at",
            "created_at",
            "nickname",
            "nickname",
            "state",
            "state",
            "updated_at",
            "updated_at"),
        "body",
        p);
  }

  @PostMapping("/api/admin/support/feedback/{id}/review")
  Map<String, Boolean> review(
      @PathVariable String id, @RequestBody FeedbackReview b, HttpServletRequest r) {
    service.reviewFeedback(auth.staff(r), id, b.state(), b.reply(), b.revision());
    return Map.of("success", true);
  }
}
