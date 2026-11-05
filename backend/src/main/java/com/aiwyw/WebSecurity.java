package com.aiwyw;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** 所有HTTP请求的安全响应头与写请求来源约束。不是身份授权层，具体API仍必须验证会话与角色。 */
@Component
public class WebSecurity extends OncePerRequestFilter {
  private final Auth auth;
  private final com.fasterxml.jackson.databind.ObjectMapper json;

  public WebSecurity(Auth auth, com.fasterxml.jackson.databind.ObjectMapper json) {
    this.auth = auth;
    this.json = json;
  }

  @Override
  /** 写请求必须带非简单自定义请求头且不得为cross-site；私有API默认no-store，不允许公共CDN缓存。 */
  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    res.setHeader("X-Content-Type-Options", "nosniff");
    res.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
    res.setHeader("X-Frame-Options", "DENY");
    if (req.getRequestURI().startsWith("/api/")) res.setHeader("Cache-Control", "no-store");
    if (!Set.of("GET", "HEAD", "OPTIONS").contains(req.getMethod())
        && (!"1".equals(req.getHeader("X-AIWYW-Request"))
            || "cross-site".equals(req.getHeader("Sec-Fetch-Site")))) {
      res.setStatus(403);
      res.setContentType("application/json;charset=UTF-8");
      res.getWriter().write("{\"message\":\"请求来源校验失败，请刷新页面后再试\"}");
      return;
    }
    // 在MultipartResolver读取大文件前验证身份，避免匿名请求占满临时磁盘。
    if (req.getContentType() != null
        && req.getContentType().toLowerCase(java.util.Locale.ROOT).startsWith("multipart/")
        && (req.getRequestURI().startsWith("/api/admin/")
            || req.getRequestURI().equals("/api/member/evidence"))) {
      try {
        if (req.getRequestURI().equals("/api/member/evidence")) auth.verified(req);
        else if (req.getRequestURI().equals("/api/admin/storage/assets")) auth.editor(req);
        else auth.admin(req);
      } catch (Api.Failure denied) {
        res.setStatus(denied.status);
        res.setContentType("application/json;charset=UTF-8");
        json.writeValue(res.getWriter(), java.util.Map.of("message", denied.getMessage()));
        return;
      }
    }
    // No cross-origin credential allowance; unsafe requests require a non-simple custom header.
    chain.doFilter(req, res);
  }
}
