import type { H3Event } from "h3";
import { invalidatePublicPages, publicPagePaths } from "./site-pages";

/** API转发走显式网关，先覆盖实际连接方再转发；不能使用早于中间件执行的routeRules代理。
 * 固定内部目标、保留状态码/多Cookie、流式上传下载；禁止跟随重定向在服务器访问用户资源地址。
 */
export function proxySiteApi(event: H3Event, path: string, staffEntry = "") {
  const url = getRequestURL(event);
  const peer = event.node.req.socket?.remoteAddress || "127.0.0.1";
  const forwarded = event.node.req.headers["x-forwarded-for"];
  return proxyRequest(event, "http://127.0.0.1:18080" + path + url.search, {
    streamRequest: true,
    headers: {
      "x-aiwyw-peer-ip": peer,
      "x-aiwyw-staff-entry": staffEntry,
      "x-forwarded-for": typeof forwarded === "string" ? forwarded : peer,
    },
    fetchOptions: { redirect: "manual" },
    async onResponse(_event, response) {
      if (!response.ok || ["GET", "HEAD", "OPTIONS"].includes(event.method))
        return;
      if (
        /^\/api\/(admin\/(settings|catalog|content|apis|api-plans|seo)(\/|$)|install\/)/.test(
          path,
        )
      )
        invalidatePublicPages();

      if (path !== "/api/admin/site/publish") return;
      // 仅在上游管理员鉴权成功后生成，内部渲染请求不携带管理员会话。
      invalidatePublicPages();
      const { localFetch } = useNitroApp();
      try {
        const results = await Promise.all(
          publicPagePaths.flatMap((page) =>
            ["zh", "en", "*"].map(async (language) => {
              const result = await localFetch(page, {
                headers: { "accept-language": language },
              });
              await result.body?.cancel();
              return result.ok && result.headers.has("x-aiwyw-static");
            }),
          ),
        );
        if (results.every(Boolean)) return;
      } catch (error) {
        console.error("Public page publication failed", error);
      }
      removeResponseHeader(event, "content-length");
      throw createError({
        statusCode: 503,
        statusMessage: "Static page publication failed",
      });
    },
  });
}
