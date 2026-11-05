package com.aiwyw;

import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** 财务与个人账户HTTP边界：用户ID来自会话；财务对账不能核销，运营不能读取付款证据。 */
@RestController
public class CommerceController {
  private final Commerce service;
  private final Auth auth;
  private final JdbcTemplate db;
  private final Storage storage;
  private final StorageController content;
  private final Settings settings;

  public CommerceController(
      Commerce service,
      Auth auth,
      JdbcTemplate db,
      Storage storage,
      StorageController content,
      Settings settings) {
    this.service = service;
    this.auth = auth;
    this.db = db;
    this.storage = storage;
    this.content = content;
    this.settings = settings;
  }

  @GetMapping("/api/member/wallet")
  Map<String, Object> wallet(HttpServletRequest r) {
    return service.summary(auth.user(r, false).id());
  }

  @GetMapping("/api/member/packages")
  List<Map<String, Object>> packages(HttpServletRequest r) {
    auth.user(r, false);
    return service.packages(false);
  }

  @GetMapping("/api/member/payment")
  Map<String, String> payment(HttpServletRequest r) {
    auth.user(r, false);
    return Map.of("mode", Commerce.MODE, "image", settings.value("payment.mock_image"));
  }

  @PostMapping("/api/member/recharge")
  Map<String, Object> create(@RequestBody Commerce.Recharge b, HttpServletRequest r) {
    return service.createRecharge(auth.verified(r).id(), b);
  }

  record Declaration(String evidenceId, String note) {}

  @PostMapping("/api/member/recharge/{id}/declare")
  Map<String, Boolean> declare(
      @PathVariable String id, @RequestBody Declaration b, HttpServletRequest r) {
    service.declare(auth.verified(r).id(), id, b.evidenceId(), b.note());
    return Map.of("success", true);
  }

  @GetMapping("/api/member/list/{kind}")
  Map<String, Object> memberList(
      @PathVariable String kind,
      @RequestParam(defaultValue = "") String state,
      @RequestParam(defaultValue = "") String from,
      @RequestParam(defaultValue = "") String until,
      @RequestParam(defaultValue = "created_at") String sort,
      @RequestParam(defaultValue = "descending") String direction,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int pageSize,
      HttpServletRequest r) {
    return service.page(
        kind, auth.user(r, false).id(), state, "", from, until, page, pageSize, sort, direction);
  }

  @GetMapping("/api/member/quote/{slug}")
  Map<String, Object> quote(@PathVariable String slug, HttpServletRequest r) {
    return service.quote(auth.verified(r).id(), slug);
  }

  @PostMapping("/api/member/purchase")
  Map<String, String> purchase(@RequestBody Commerce.Purchase b, HttpServletRequest r) {
    return Map.of("id", service.purchase(auth.verified(r).id(), b));
  }

  @PostMapping("/api/member/resources/{id}/download")
  Map<String, String> download(
      @PathVariable String id,
      @RequestParam(defaultValue = "false") boolean original,
      HttpServletRequest r) {
    return Map.of("url", service.delivery(auth.verified(r).id(), id, original));
  }

  record Reason(String reason) {}

  @PostMapping("/api/member/resources/{id}/refund")
  Map<String, Boolean> refund(
      @PathVariable String id, @RequestBody Reason b, HttpServletRequest r) {
    service.requestRefund(auth.verified(r).id(), id, b.reason());
    return Map.of("success", true);
  }

  @GetMapping("/api/member/notifications")
  List<Map<String, Object>> notices(HttpServletRequest r) {
    return Commerce.safeRows(
        db.queryForList(
            "SELECT id,title,body,read_at,created_at FROM member_notification WHERE user_id=? ORDER"
                + " BY created_at DESC,id LIMIT 100",
            auth.user(r, false).id()));
  }

  @PostMapping("/api/member/notifications/{id}/read")
  Map<String, Boolean> read(@PathVariable String id, HttpServletRequest r) {
    db.update(
        "UPDATE member_notification SET read_at=? WHERE id=? AND user_id=?",
        System.currentTimeMillis(),
        id,
        auth.user(r, false).id());
    return Map.of("success", true);
  }

  @PostMapping("/api/member/evidence")
  Storage.Asset receipt(@RequestParam("file") MultipartFile file, HttpServletRequest r) {
    return storage.upload(file, "RECEIPT", auth.verified(r).id());
  }

  @GetMapping("/api/member/evidence/{id}")
  org.springframework.http.ResponseEntity<?> evidence(
      @PathVariable String id, HttpServletRequest r) {
    var u = auth.user(r, false);
    var rows = db.queryForList("SELECT created_by,purpose FROM storage_asset WHERE id=?", id);
    if (rows.isEmpty() || !rows.get(0).get("purpose").equals("RECEIPT"))
      throw new Api.Failure(404, "付款截图不存在");
    if (!rows.get(0).get("created_by").equals(u.id())) auth.finance(r, false);
    return content.content(storage.stored(id, true), false);
  }

  @GetMapping("/api/admin/commerce/{kind}")
  Map<String, Object> list(
      @PathVariable String kind,
      @RequestParam(defaultValue = "") String state,
      @RequestParam(defaultValue = "") String q,
      @RequestParam(defaultValue = "") String from,
      @RequestParam(defaultValue = "") String until,
      @RequestParam org.springframework.util.MultiValueMap<String, String> parameters,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int pageSize,
      HttpServletRequest r) {
    auth.finance(r, false);
    var result =
        service.page(
            kind,
            null,
            state,
            q,
            from,
            until,
            page,
            pageSize,
            parameters.getFirst("sort"),
            parameters.getFirst("direction") == null
                ? "descending"
                : parameters.getFirst("direction"));
    for (Object item : (List<?>) result.get("items")) {
      @SuppressWarnings("unchecked")
      var row = (Map<String, Object>) item;
      row.put(
          "nickname",
          db.queryForObject(
              "SELECT nickname FROM app_user WHERE id=?", String.class, row.get("user_id")));
    }
    return result;
  }

  record RechargeReview(BigDecimal actual, boolean approve, String reason) {}

  @PostMapping("/api/admin/commerce/adjust")
  Map<String, String> adjust(@RequestBody Commerce.Adjustment b, HttpServletRequest r) {
    return Map.of("id", service.adjust(auth.permission(r, "points"), b));
  }

  @GetMapping("/api/admin/commerce/adjust-options")
  List<Map<String, Object>> adjustmentUsers(
      @RequestParam(defaultValue = "") String q, HttpServletRequest r) {
    var actor = auth.permission(r, "points");
    if (!actor.hasRole("ADMIN")) throw new Api.Failure(403, "仅管理员可选择调整账户");
    String value = MeteredApi.text(q, 100, false),
        term = "%" + value.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    return Commerce.safeRows(
        db.queryForList(
            "SELECT id,nickname FROM app_user WHERE disabled=FALSE AND id<>? AND (nickname LIKE ?"
                + " ESCAPE '!' OR id=?) ORDER BY created_at DESC,id LIMIT 20",
            actor.id(),
            term,
            value));
  }

  @PostMapping("/api/admin/commerce/recharge/{id}/review")
  Map<String, Boolean> review(
      @PathVariable String id, @RequestBody RechargeReview b, HttpServletRequest r) {
    service.reviewRecharge(auth.finance(r, true), id, b.actual(), b.approve(), b.reason());
    return Map.of("success", true);
  }

  record RefundReview(boolean approve, String reason, long revision) {}

  @PostMapping("/api/admin/commerce/refunds/{id}/review")
  Map<String, Boolean> reviewRefund(
      @PathVariable String id, @RequestBody RefundReview b, HttpServletRequest r) {
    service.reviewRefund(auth.finance(r, true), id, b.approve(), b.reason(), b.revision());
    return Map.of("success", true);
  }

  @GetMapping("/api/admin/commerce-packages")
  List<Map<String, Object>> allPackages(HttpServletRequest r) {
    auth.finance(r, false);
    return service.packages(true);
  }

  @PostMapping("/api/admin/commerce-packages")
  Map<String, String> addPackage(@RequestBody Commerce.PackageInput b, HttpServletRequest r) {
    return Map.of("id", service.savePackage(null, b, auth.admin(r).id()));
  }

  @PutMapping("/api/admin/commerce-packages/{id}")
  Map<String, String> updatePackage(
      @PathVariable String id, @RequestBody Commerce.PackageInput b, HttpServletRequest r) {
    return Map.of("id", service.savePackage(id, b, auth.admin(r).id()));
  }
}
