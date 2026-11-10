package com.aiwyw;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

/** 写入完成后切换新静态版本；发生意外时停止提供旧公开页面，重启可从库重建。 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
@ConditionalOnProperty(name = "aiwyw.web-root")
final class StaticRefreshFilter extends OncePerRequestFilter {
  private final PublishedWebsite website;
  private final Database db;
  private final Auth auth;

  StaticRefreshFilter(PublishedWebsite website, Database db, Auth auth) {
    this.website = website;
    this.db = db;
    this.auth = auth;
  }

  private boolean publicationWrite(HttpServletRequest request) {
    if (!Set.of("PUT", "POST", "PATCH", "DELETE").contains(request.getMethod())) return false;
    String path = request.getServletPath();
    if (path.isEmpty()) path = request.getRequestURI();
    if (path.equals("/api/install")) return db.validToken(request.getHeader("X-Install-Token"));
    if (!path.matches("/api/admin/(?:catalog|content|series|settings|seo|apis|api-plans|site/publish|storage/assets)(?:/.*)?")
        || path.endsWith("/lookup")
        || (path.contains("/storage/assets") && !request.getMethod().equals("DELETE"))) return false;
    try {
      auth.staff(request);
      return true;
    } catch (Api.Failure denied) {
      return false;
    }
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
      FilterChain chain) throws ServletException, IOException {
    if (!publicationWrite(request)) {
      chain.doFilter(request, response);
      return;
    }
    synchronized (website) {
      Path previous = website.detach();
      var buffered = new ContentCachingResponseWrapper(response);
      try {
        chain.doFilter(request, buffered);
        if (buffered.getStatus() >= 400 && buffered.getStatus() < 500) website.restore(previous);
        else website.rebuild(previous);
      } catch (IOException | RuntimeException | ServletException failure) {
        LoggerFactory.getLogger(getClass()).error("Public snapshot unavailable after update ({})",
            failure.getClass().getSimpleName());
        if (response.isCommitted()) throw failure;
        buffered.resetBuffer();
        buffered.setStatus(503);
        buffered.setContentType("application/json;charset=UTF-8");
        buffered.getWriter().write("{\"message\":\"请在后台核对保存结果，静态发布待重建\"}");
      } finally {
        try {
          website.discard(previous);
        } catch (IOException cleanup) {
          LoggerFactory.getLogger(getClass()).warn("Public snapshot cleanup pending");
        }
      }
      buffered.copyBodyToResponse();
    }
  }
}
