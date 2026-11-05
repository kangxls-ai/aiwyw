package com.aiwyw;

import jakarta.servlet.http.*;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** 统计独立菜单授权；导出与屏幕使用相同范围和账本，避免模拟数字混入真实报告。 */
@RestController
public class OperationsStatsController {
  final OperationsStats stats;
  final Auth auth;

  OperationsStatsController(OperationsStats stats, Auth auth) {
    this.stats = stats;
    this.auth = auth;
  }

  @PostMapping("/api/public/events")
  Map<String, Boolean> event(
      @RequestBody OperationsStats.Event b, HttpServletRequest r, HttpServletResponse response) {
    stats.event(b, r, response);
    return Map.of("accepted", true);
  }

  @GetMapping("/api/admin/statistics")
  Map<String, Object> report(
      @RequestParam(defaultValue = "") String from,
      @RequestParam(defaultValue = "") String until,
      @RequestParam(defaultValue = "REAL") String mode,
      HttpServletRequest r) {
    auth.permission(r, "statistics");
    return stats.report(from, until, mode);
  }

  @GetMapping("/api/admin/statistics/export")
  ResponseEntity<byte[]> csv(
      @RequestParam(defaultValue = "") String from,
      @RequestParam(defaultValue = "") String until,
      @RequestParam(defaultValue = "REAL") String mode,
      HttpServletRequest r) {
    auth.permission(r, "statistics");
    return ResponseEntity.ok()
        .contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=aiwyw-income.csv")
        .cacheControl(CacheControl.noStore())
        .body(stats.csv(from, until, mode));
  }
}
