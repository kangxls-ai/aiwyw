package com.aiwyw;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Nginx 内部认证子请求校验同一后台入口。返回值不包含入口，也不会创建会话。 */
@RestController
final class StaffGate {
  private final StaffEntry entry;

  StaffGate(StaffEntry entry) {
    this.entry = entry;
  }

  @GetMapping("/__staff_gate")
  ResponseEntity<Void> check(HttpServletRequest request) {
    return ResponseEntity.status(entry.authorize(request) ? 204 : 403).build();
  }
}
