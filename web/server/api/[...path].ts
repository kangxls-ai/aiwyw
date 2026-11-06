/** API转发走显式网关，先覆盖实际连接方再转发；不能使用早于中间件执行的routeRules代理。
 * 固定内部目标、保留状态码/多Cookie、流式上传下载；禁止跟随重定向在服务器访问用户资源地址。
 */
export default defineEventHandler((event) => {
  const url = getRequestURL(event);
  const peer = event.node.req.socket?.remoteAddress || "127.0.0.1";
  const forwarded = event.node.req.headers["x-forwarded-for"];
  return proxyRequest(
    event,
    "http://127.0.0.1:18080" + url.pathname + url.search,
    {
      streamRequest: true,
      headers: {
        "x-aiwyw-peer-ip": peer,
        "x-forwarded-for": typeof forwarded === "string" ? forwarded : peer,
      },
      fetchOptions: { redirect: "manual" },
    },
  );
});
