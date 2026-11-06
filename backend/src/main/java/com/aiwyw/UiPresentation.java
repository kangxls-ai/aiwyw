package com.aiwyw;

import static com.aiwyw.ContentHtml.escape;

import java.util.*;
import java.util.regex.Pattern;

/** 文件快照先校验再加公共视觉资源，不修改已发布文件、业务条款或脚本CSP摘要，也不增加详情查询。 */
final class UiPresentation {
  private UiPresentation() {}

  static String decorate(String html) {
    return html.replace("</head>", "<link rel=\"stylesheet\" href=\"/ui-theme.css?v=2\"></head>")
        .replace("<body>", "<body class=\"published-page\">");
  }

  /** 先校验原静态文件，再以缓存公开导航装配公共外壳；不修改快照或重新查询商品正文。 */
  static String decorate(
      String html,
      Map<String, String> site,
      List<Catalog.SectionView> sections,
      String kind,
      String slug,
      String language) {
    String original = html;
    html =
        decorate(html)
            .replace("</head>", "<link rel=\"stylesheet\" href=\"/site-shell.css?v=1\"></head>");
    boolean product = original.contains("data-product-status=");
    boolean en = "en".equals(language);
    if (!original.contains("data-language="))
      html = html.replaceFirst("<html ", "<html data-language=\"" + (en ? "en" : "zh") + "\" ");
    StringBuilder nav = new StringBuilder();
    sections.stream()
        .sorted(Comparator.comparing(s -> s.config().mode().equals("API")))
        .forEach(
            s -> {
              var c = s.config();
              String href =
                  c.mode().equals("ARTICLES")
                      ? "/articles"
                      : c.mode().equals("API") ? "/api-services" : "/apps?section=" + s.id();
              nav.append("<a href=\"")
                  .append(escape(href))
                  .append("\">")
                  .append(dual(c.titleZh(), c.titleEn().isBlank() ? c.titleZh() : c.titleEn()))
                  .append("</a>");
            });
    String toggle =
        product
            ? "<button id=\"language\" class=\"catalog-language\" aria-label=\"切换语言\">中文 /"
                  + " English</button>"
            : "<a class=\"catalog-language\" href=\"/articles/"
                + escape(slug)
                + (en ? "" : "?lang=en")
                + "\">"
                + (en ? "中文" : "English")
                + "</a>";
    String header =
        "<header class=\"public-site-header\"><div class=\"public-site-width"
            + " public-site-header-inner\"><a class=\"public-site-brand\" href=\"/\"><span"
            + " class=\"public-site-logo\">万</span><span>"
            + escape(site.getOrDefault("site.name", "AI万有网"))
            + "<small>"
            + dual("发现工具的更多可能", "Discover useful tools")
            + "</small></span></a><nav class=\"public-site-nav\" aria-label=\"首页导航\">"
            + nav
            + "</nav>"
            + toggle
            + "</div></header>";
    if (product)
      html =
          html.replaceFirst(
              "(?s)<header>.*?</header>", java.util.regex.Matcher.quoteReplacement(header));
    else
      html =
          html.replace(
              "<body class=\"published-page\">", "<body class=\"published-page\">" + header);
    var title = Pattern.compile("(?s)<h1[^>]*>(.*?)</h1>").matcher(original);
    String current = title.find() ? title.group(1) : escape(slug);
    String zh =
        kind.equals("API")
            ? "API 服务"
            : product
                ? "所有应用"
                : switch (kind) {
                  case "FAQ" -> "常见问题";
                  case "CASE" -> "使用案例";
                  case "TUTORIAL" -> "教程";
                  default -> "文章";
                };
    String english =
        kind.equals("API")
            ? "API services"
            : product
                ? "All applications"
                : switch (kind) {
                  case "FAQ" -> "FAQ";
                  case "CASE" -> "Cases";
                  case "TUTORIAL" -> "Tutorials";
                  default -> "Articles";
                };
    String href =
        kind.equals("API") ? "/api-services" : product ? "/apps" : "/articles?kind=" + kind;
    String crumbs =
        "<nav class=\"public-breadcrumb\" aria-label=\"当前位置\"><a href=\"/\">"
            + dual("首页", "Home")
            + "</a><span> / </span><a href=\""
            + escape(href)
            + "\">"
            + dual(zh, english)
            + "</a><span> / </span><span aria-current=\"page\">"
            + current
            + "</span></nav>";
    if (product)
      html =
          html.replaceFirst(
              "(?s)<a class=\"back\".*?</a>", java.util.regex.Matcher.quoteReplacement(crumbs));
    else
      html =
          html.replaceFirst(
              "(?s)<article><nav>.*?</nav>",
              java.util.regex.Matcher.quoteReplacement("<article>" + crumbs));
    String filing = site.getOrDefault("site.filing", "").trim();
    if (filing.isBlank()) filing = "模拟ICP备00000000号（占位）";
    String footer =
        "<footer class=\"directory-footer public-site-width\"><a href=\"/support\">"
            + dual("联系客服与反馈", "Support and feedback")
            + "</a><p class=\"site-filing\">"
            + escape(filing)
            + "</p><span>"
            + escape(site.getOrDefault("site.domain", "www.aiwyw.com"))
            + "</span></footer>";
    if (product)
      html =
          html.replaceFirst(
              "(?s)<footer>.*?</footer>", java.util.regex.Matcher.quoteReplacement(footer));
    else html = html.replace("</body>", footer + "</body>");
    return html;
  }

  private static String dual(String zh, String en) {
    return "<span class=\"zh\">"
        + escape(zh)
        + "</span><span class=\"en\">"
        + escape(en)
        + "</span>";
  }
}
