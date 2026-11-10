package com.aiwyw;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** 后台入口由每个部署独立生成，仅保存在受保护的数据目录，不输出到日志或公开配置。 */
@Component
final class StaffEntry {
  static final String HEADER = "X-AIWYW-Staff-Entry";
  static final String COOKIE = "AIWYW_STAFF_SESSION";
  private static final String AUTHORIZED = StaffEntry.class.getName() + ".authorized";
  private final String value;

  StaffEntry(SecureStore store) throws Exception {
    Path file = store.security.resolve("staff-entry");
    if (!Files.exists(file)) {
      try {
        Files.writeString(
            file,
            UUID.randomUUID().toString().replace("-", "") + "\n",
            StandardOpenOption.CREATE_NEW);
      } catch (FileAlreadyExistsException concurrentStart) {
        // 另一启动进程已创建，读取该部署的同一入口。
      }
    }
    store.protect(file);
    value = Files.readString(file, StandardCharsets.US_ASCII).trim();
    if (!value.matches("[0-9a-f]{32}")) throw new IllegalStateException("Invalid staff entry file");
  }

  String path() {
    return "/" + value;
  }

  boolean authorize(HttpServletRequest request) {
    String supplied = request.getHeader(HEADER);
    String peer = RequestIp.numeric(request.getRemoteAddr());
    if (supplied == null
        || supplied.length() != 32
        || peer == null
        || !(peer.equals("127.0.0.1") || peer.equals("0:0:0:0:0:0:0:1"))
        || !MessageDigest.isEqual(
            value.getBytes(StandardCharsets.US_ASCII),
            supplied.getBytes(StandardCharsets.US_ASCII))) return false;
    request.setAttribute(AUTHORIZED, true);
    return true;
  }

  static boolean authorized(HttpServletRequest request) {
    return Boolean.TRUE.equals(request.getAttribute(AUTHORIZED));
  }

  static boolean mandatory(String path) {
    return path.equals("/api/admin")
        || path.startsWith("/api/admin/")
        || path.equals("/api/auth/mfa")
        || path.startsWith("/api/auth/mfa/");
  }

  static boolean scoped(String path) {
    return mandatory(path)
        || path.matches("/api/(?:support/assets|member/evidence)/[0-9a-f-]{36}")
        || switch (path) {
          case "/api/auth/login",
                  "/api/auth/logout",
                  "/api/auth/session",
                  "/api/member/profile",
                  "/api/member/nickname",
                  "/api/member/password" ->
              true;
          default -> false;
        };
  }
}
