/** 透明转发已发布内容HTML和CSP/ETag，无需客户端再次查询正文；参数编码防止路径穿越。 */
export default defineEventHandler((event) => {
  const slug = getRouterParam(event, "slug") || "";
  if (!/^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(slug))
    throw createError({
      statusCode: 404,
      statusMessage: "Article not found",
    });
  return proxyRequest(
    event,
    `http://127.0.0.1:18080/api/public/content-pages/${encodeURIComponent(slug)}?lang=${getQuery(event).lang === "en" ? "en" : "zh"}`,
  );
});
