/** 始终追加实际连接方，客户端伪造的转发链不能覆盖右侧真实节点；后端仅信任后台配置的代理。 */
export default defineEventHandler((event) => {
  if (!event.path.startsWith("/api/")) return;
  const peer = event.node.req.socket?.remoteAddress;
  if (!peer) return;
  event.node.req.headers["x-aiwyw-peer-ip"] = peer;
  const previous = event.node.req.headers["x-forwarded-for"];
  const prefix =
    typeof previous === "string" && previous.length < 900
      ? previous + ", "
      : "";
  event.node.req.headers["x-forwarded-for"] = prefix + peer;
});
