/** 透明转发已发布HTML和CSP/ETag，不再SSR查询商品；路径参数编码防止路径穿越。 */
export default defineEventHandler((event) => {
  const slug = getRouterParam(event, "slug") || "";
  if (!/^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(slug))
    throw createError({
      statusCode: 404,
      statusMessage: "Application not found",
    });
  return proxyRequest(
    event,
    `http://127.0.0.1:18080/api/public/pages/${encodeURIComponent(slug)}`,
  );
});
