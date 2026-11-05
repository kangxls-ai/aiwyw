package com.aiwyw;

/** 文件快照先校验再加公共视觉资源，不修改已发布文件、业务条款或脚本CSP摘要，也不增加详情查询。 */
final class UiPresentation {
  private UiPresentation() {}

  static String decorate(String html) {
    return html.replace("</head>", "<link rel=\"stylesheet\" href=\"/ui-theme.css?v=2\"></head>")
        .replace("<body>", "<body class=\"published-page\">");
  }
}
