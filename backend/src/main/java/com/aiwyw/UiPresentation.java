package com.aiwyw;

import static com.aiwyw.ContentHtml.escape;

import java.util.*;
import java.util.regex.Matcher;
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
            .replace("</head>", "<link rel=\"stylesheet\" href=\"/site-shell.css?v=2\"></head>");
    String brand = escape(site.getOrDefault("site.name", "微云湾"));
    String slogan = site.getOrDefault("site.slogan", "以技术为舟，向未来远航");
    // 原快照的标题保留内容名称，站点后缀使用当前公开品牌。
    html =
        Pattern.compile("(<title>[^<]* · )[^<]*</title>")
            .matcher(html)
            .replaceFirst("$1" + Matcher.quoteReplacement(brand) + "</title>");
    html =
        html.replace(
            "</head>",
            "<link rel=\"icon\" type=\"image/svg+xml\" href=\"/images/weiyunwan.svg\"></head>");
    // 类型来自已发布对象，不能让教程正文中的HTML示例决定页面外壳类型。
    boolean product = Set.of("TOOL", "SOFTWARE", "RESOURCE", "API", "DOCUMENT").contains(kind);
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
                      : c.mode().equals("API") ? "/api-services" : "/sections/" + s.id();
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
            + " public-site-header-inner\"><a class=\"public-site-brand\" href=\"/\"><img"
            + " class=\"public-site-logo\" src=\"/images/weiyunwan.svg\" width=\"48\" height=\"48\""
            + " alt=\"\"><span>"
            + brand
            + (slogan.isBlank() ? "" : "<small>" + escape(slogan) + "</small>")
            + "</span></a><nav class=\"public-site-nav\" aria-label=\"首页导航\">"
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
              java.util.regex.Matcher.quoteReplacement(
                  "<article class=\"published-article\">" + crumbs));
    String securityFiling = site.getOrDefault("site.security_filing", "");
    String securityIcon = site.getOrDefault("site.security_filing_icon", "");
    var securityNumber = Pattern.compile("(?<![0-9])[0-9]{14}(?![0-9])").matcher(securityFiling);
    String securityLink =
        "https://beian.mps.gov.cn/"
            + (securityNumber.find() ? "#/query/webSearch?code=" + securityNumber.group() : "");
    String footer =
        "<footer class=\"directory-footer public-site-width\"><div class=\"site-filings\">"
            + filing(
                site.get("site.filing"),
                "https://beian.miit.gov.cn/",
                "工信部 ICP 备案号待填写",
                "MIIT ICP filing pending",
                "")
            + filing(
                securityFiling,
                securityLink,
                "公安网安备案号待填写",
                "Public security filing pending",
                securityIcon)
            + "</div><span>"
            + escape(site.getOrDefault("site.domain", "www.aiwyw.com"))
            + "</span></footer>";
    if (product)
      html =
          html.replaceFirst(
              "(?s)<footer>.*?</footer>", java.util.regex.Matcher.quoteReplacement(footer));
    else html = html.replace("</body>", footer + "</body>");
    // 旧静态文件保持不可变；只更新产品交易区域文案及文章发布时间，不改作者正文或CSP脚本。
    if (product) {
      int cut = html.indexOf("<section class=\"body\">");
      if (cut < 0) cut = html.indexOf("<script>");
      if (cut < 0) cut = html.length();
      html =
          html.substring(0, cut)
                  .replace("积分", "鼓励值")
                  .replace("充值", "捐赠")
                  .replace(">points</span>", ">encouragement</span>")
              + html.substring(cut);
    } else {
      var time = Pattern.compile("<time datetime='([^']+)'>[^<]+</time>").matcher(html);
      if (time.find())
        try {
          String text =
              java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                  .withZone(java.time.ZoneId.of("Asia/Shanghai"))
                  .format(java.time.Instant.parse(time.group(1)));
          html =
              html.substring(0, time.start())
                  + "<time datetime='"
                  + time.group(1)
                  + "'>"
                  + text
                  + "</time>"
                  + html.substring(time.end());
        } catch (java.time.DateTimeException ignored) {
          /* 旧内容异常不覆盖正文。 */
        }
    }
    return html;
  }

  private static String filing(String value, String url, String zh, String en, String icon) {
    if (value == null || value.isBlank()) return "<span>" + dual(zh, en) + "</span>";
    return "<a href=\""
        + url
        + "\" target=\"_blank\" rel=\"noopener noreferrer\">"
        + (icon.isBlank()
            ? ""
            : "<img src=\"" + escape(icon) + "\" width=\"16\" height=\"16\" alt=\"\">")
        + escape(value.trim())
        + "</a>";
  }

  private static String dual(String zh, String en) {
    return "<span class=\"zh\">"
        + escape(zh)
        + "</span><span class=\"en\">"
        + escape(en)
        + "</span>";
  }
}
