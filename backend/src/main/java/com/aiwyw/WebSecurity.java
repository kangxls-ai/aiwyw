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
  private final SupportController support;
  private final Settings settings;

  public WebSecurity(
      Auth auth,
      com.fasterxml.jackson.databind.ObjectMapper json,
      SupportController support,
      Settings settings) {
    this.auth = auth;
    this.json = json;
    this.support = support;
    this.settings = settings;
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
    // 程序化API只接受Bearer Key，从不读取Cookie身份，因此不使用浏览器CSRF头约定。
    if (!req.getRequestURI().startsWith("/api/v1/invoke/")
        && !Set.of("GET", "HEAD", "OPTIONS").contains(req.getMethod())
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
            || req.getRequestURI().equals("/api/member/evidence")
            || req.getRequestURI().matches("/api/support/conversations/[^/]+/image"))) {
      try {
        if (req.getRequestURI().matches("/api/(admin/support|support)/conversations/[^/]+/image"))
          support.authorizeUpload(req, res);
        else if (req.getRequestURI().equals("/api/member/evidence")) auth.verified(req);
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
    if (req.getContentType() != null
        && req.getContentType().toLowerCase(java.util.Locale.ROOT).startsWith("application/json")) {
      long limit =
          req.getRequestURI().startsWith("/api/v1/invoke/")
                  || req.getRequestURI().startsWith("/api/member/api/debug/")
              ? settings.integer("api.body_limit_kb") * 1024L + 16384
              : 2 * 1024 * 1024L;
      if (req.getContentLengthLong() > limit) {
        res.setStatus(413);
        res.setContentType("application/json;charset=UTF-8");
        res.getWriter().write("{\"message\":\"请求内容超过大小限制\"}");
        return;
      }
      // 未声明长度或分块传输也必须受限，不能先把任意大JSON读入内存后再做业务校验。
      chain.doFilter(
          new HttpServletRequestWrapper(req) {
            @Override
            public ServletInputStream getInputStream() throws IOException {
              ServletInputStream input = super.getInputStream();
              return new ServletInputStream() {
                long consumed;

                private void count(int n) throws IOException {
                  if (n > 0) {
                    consumed += n;
                    if (consumed > limit) throw new IOException("Request body limit exceeded");
                  }
                }

                @Override
                public int read() throws IOException {
                  int n = input.read();
                  count(n < 0 ? 0 : 1);
                  return n;
                }

                @Override
                public int read(byte[] b, int offset, int length) throws IOException {
                  int n =
                      input.read(
                          b, offset, (int) Math.min(length, Math.max(1, limit - consumed + 1)));
                  count(n);
                  return n;
                }

                @Override
                public boolean isFinished() {
                  return input.isFinished();
                }

                @Override
                public boolean isReady() {
                  return input.isReady();
                }

                @Override
                public void setReadListener(ReadListener listener) {
                  input.setReadListener(listener);
                }
              };
            }
          },
          res);
    } else chain.doFilter(req, res);
  }
}
