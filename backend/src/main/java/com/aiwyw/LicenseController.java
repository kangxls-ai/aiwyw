package com.aiwyw;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

/** 所有敏感响应禁止缓存；程序凭据只允许POST正文，用户查询与运营策略分别鉴权。 */
@RestController
public class LicenseController {
  private final Licenses service;
  private final Auth auth;
  private final Accounts accounts;
  private final JdbcTemplate db;
  private final Settings settings;

  public LicenseController(
      Licenses service, Auth auth, Accounts accounts, JdbcTemplate db, Settings settings) {
    this.service = service;
    this.auth = auth;
    this.accounts = accounts;
    this.db = db;
    this.settings = settings;
  }

  @GetMapping("/api/admin/licenses/policy/{product}")
  Map<String, Object> policy(@PathVariable String product, HttpServletRequest r) {
    auth.editor(r);
    return service.policy(product);
  }

  record Policy(int deviceLimit, long revision) {}

  @PutMapping("/api/admin/licenses/policy/{product}")
  Map<String, Boolean> policy(
      @PathVariable String product, @RequestBody Policy b, HttpServletRequest r) {
    service.policy(auth.editor(r).id(), product, b.deviceLimit(), b.revision());
    return Map.of("success", true);
  }

  @PostMapping("/api/member/licenses/{purchase}/detail")
  Map<String, Object> detail(@PathVariable String purchase, HttpServletRequest r) {
    return service.detail(auth.verified(r).id(), purchase);
  }

  record Unbind(String deviceId, String reason) {}

  @PostMapping("/api/member/licenses/{purchase}/unbind")
  Map<String, String> request(
      @PathVariable String purchase, @RequestBody Unbind b, HttpServletRequest r) {
    return Map.of("id", service.request(auth.verified(r).id(), purchase, b.deviceId(), b.reason()));
  }

  @PostMapping("/api/license/activate")
  Map<String, Object> activate(@RequestBody Licenses.Activation b, HttpServletRequest r) {
    accounts.limit(
        "license-activate", r.getRemoteAddr(), settings.integer("license.activate_hour_limit"));
    return service.activate(b);
  }

  @PostMapping("/api/license/verify")
  Map<String, Object> verify(@RequestBody Licenses.Verification b, HttpServletRequest r) {
    // 与激活分开计数；上限在后台配置，正常客户端应按文档控制校验频率。
    accounts.limit(
        "license-verify",
        r.getRemoteAddr() + ":" + b.bindingId(),
        settings.integer("license.verify_hour_limit"));
    return service.verify(b);
  }

  record Review(boolean approve, String reason, long revision) {}

  @PostMapping("/api/admin/licenses/requests/{id}/review")
  Map<String, Boolean> review(
      @PathVariable String id, @RequestBody Review b, HttpServletRequest r) {
    service.review(auth.finance(r, true), id, b.approve(), b.reason(), b.revision());
    return Map.of("success", true);
  }

  @GetMapping("/api/admin/licenses/requests")
  Map<String, Object> requests(
      @RequestParam(defaultValue = "") String q,
      @RequestParam(defaultValue = "") String state,
      @RequestParam(defaultValue = "created_at") String sort,
      @RequestParam(defaultValue = "descending") String direction,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int pageSize,
      HttpServletRequest r) {
    var staff = auth.staff(r);
    if (!staff.hasRole("ADMIN") && !staff.hasRole("SUPPORT"))
      throw new Api.Failure(403, "需要客服或管理员权限");
    if (q.length() > 100
        || page < 1
        || page > 1000000
        || pageSize < 1
        || pageSize > 100
        || !Set.of("", "PENDING", "APPROVED", "REJECTED", "CANCELLED").contains(state))
      throw new Api.Failure(400, "查询参数不正确");
    String ordering =
        ListOrder.sql(
            sort,
            direction,
            Map.of(
                "created_at",
                "r.created_at",
                "email",
                "u.email",
                "device_name",
                "d.device_name",
                "state",
                "r.state",
                "reason",
                "r.reason"),
            "created_at",
            "r.id");
    String where =
        " FROM license_unbind_request r JOIN app_user u ON u.id=r.user_id JOIN license_device d ON"
            + " d.id=r.device_id WHERE u.email LIKE ? ESCAPE '!'"
            + (state.isBlank() ? "" : " AND r.state=?");
    List<Object> args = new ArrayList<>();
    args.add("%" + q.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%");
    if (!state.isBlank()) args.add(state);
    long total = db.queryForObject("SELECT COUNT(*)" + where, Long.class, args.toArray());
    int actual = (int) Math.min(page, Math.max(1, (total + pageSize - 1) / pageSize));
    args.add(pageSize);
    args.add((actual - 1) * pageSize);
    return Map.of(
        "items",
        Commerce.safeRows(
            db.queryForList(
                "SELECT r.*,u.nickname AS email,d.device_name"
                    + where
                    + " ORDER BY "
                    + ordering
                    + " LIMIT ? OFFSET ?",
                args.toArray())),
        "total",
        total,
        "page",
        actual);
  }
}
