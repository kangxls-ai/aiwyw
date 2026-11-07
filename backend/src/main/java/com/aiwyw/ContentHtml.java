package com.aiwyw;

import java.net.URI;
import java.util.Set;

/** 运营富文本的服务端白名单。禁止脚本、事件属性、iframe与危险CSS和URL协议。 浏览器编辑器不是安全边界，导入HTML也必须经过这里；静态页只输出净化结果。 */
public final class ContentHtml {
  private static final org.owasp.html.PolicyFactory POLICY =
      new org.owasp.html.HtmlPolicyBuilder()
          .allowElements(
              "p",
              "div",
              "span",
              "br",
              "h1",
              "h2",
              "h3",
              "h4",
              "h5",
              "h6",
              "strong",
              "b",
              "em",
              "i",
              "u",
              "s",
              "strike",
              "sup",
              "sub",
              "ul",
              "ol",
              "li",
              "blockquote",
              "pre",
              "code",
              "hr",
              "table",
              "thead",
              "tbody",
              "tfoot",
              "tr",
              "td",
              "th",
              "img")
          .allowElements(
              (name, attributes) -> {
                attributes.add("rel");
                attributes.add("noopener noreferrer");
                return name;
              },
              "a")
          .allowWithoutAttributes("span", "a")
          .allowUrlProtocols("https")
          .allowAttributes("src")
          .matching((element, attribute, value) -> safeUrl(value) ? value : null)
          .onElements("img")
          .allowAttributes("href")
          .matching((element, attribute, value) -> safeUrl(value) ? value : null)
          .onElements("a")
          .allowAttributes("alt", "title")
          .onElements("img", "a")
          .allowAttributes("colspan", "rowspan")
          .matching(java.util.regex.Pattern.compile("[1-9][0-9]?"))
          .onElements("td", "th")
          .allowAttributes("width", "height")
          .matching(java.util.regex.Pattern.compile("[0-9]{1,4}%?"))
          .onElements("img", "table", "td", "th")
          .allowStyling(
              org.owasp.html.CssSchema.withProperties(
                  Set.of(
                      "text-align",
                      "color",
                      "background-color",
                      "font-weight",
                      "font-style",
                      "text-decoration",
                      "font-size",
                      "line-height",
                      "width",
                      "height",
                      "max-width",
                      "margin-left")))
          .toFactory();

  private ContentHtml() {}

  /** 对文本/HTML属性均转义，禁止把尚未净化的用户字段拼进HTML。 */
  public static String escape(String value) {
    return value == null
        ? ""
        : value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;");
  }

  /** 媒体和交付只接收站内绝对路径或HTTPS；不由服务端抓取外链，避免SSRF。 */
  public static boolean safeUrl(String value) {
    if (value == null || value.isBlank()) return true;
    if (value.length() > 2000
        || value.chars().anyMatch(c -> c <= 32 || c == '\\' || c == '"' || c == '<' || c == '>'))
      return false;
    if (value.startsWith("/") && !value.startsWith("//")) return !value.contains("..");
    try {
      URI uri = URI.create(value);
      return "https".equalsIgnoreCase(uri.getScheme())
          && uri.getHost() != null
          && uri.getUserInfo() == null;
    } catch (IllegalArgumentException badUrl) {
      return false;
    }
  }

  /** Summernote支持的段落、颜色、对齐、表格均经成熟HTML/CSS白名单过滤，不接受脚本和嵌入页面。 */
  public static String sanitize(String input) {
    if (input == null || input.isBlank()) return "";
    if (input.length() > 100000) throw new Api.Failure(400, "图文介绍不能超过10万字符");
    return POLICY.sanitize(input);
  }

  /** 仅向已净化的正文追加通过同一URL规则的图片。重复资源不再插入。 */
  static String appendImages(String body, java.util.List<String> images, String alt) {
    StringBuilder result = new StringBuilder(body == null ? "" : body);
    for (String image : images) {
      if (image == null || image.isBlank() || !safeUrl(image)) continue;
      String source = "src=\"" + escape(image) + "\"";
      if (result.indexOf(source) >= 0) continue;
      result
          .append("<p><img ")
          .append(source)
          .append(" alt=\"")
          .append(escape(alt))
          .append("\" /></p>");
    }
    return result.toString();
  }
}
