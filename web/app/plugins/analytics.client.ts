/** 运营事件只提交公开路径、搜索词与来源域；不收集管理页、密码、Key、邮箱或完整URL。上报失败不阻断页面。 */
export default defineNuxtPlugin((nuxtApp) => {
  const route = useRouter(),
    { request } = useApi();
  let last = "";
  async function track() {
    const p = route.currentRoute.value;
    if (
      !/^\/(?:apps|articles|api-services(?:\/[a-z0-9-]+)?|sections\/[a-z0-9-]+|support)?$/.test(
        p.path,
      )
    )
      return;
    const keyword = String(p.query.q || "").slice(0, 100),
      key = p.fullPath;
    if (last === key) return;
    last = key;
    let source = "";
    try {
      source = new URL(document.referrer).hostname;
    } catch {}
    try {
      await request("/api/public/events", {
        method: "POST",
        body: { kind: "PAGE", path: p.path, keyword: "", source },
      });
      if (keyword)
        await request("/api/public/events", {
          method: "POST",
          body: { kind: "SEARCH", path: p.path, keyword, source },
        });
    } catch {}
  }
  nuxtApp.hook("app:mounted", () => track());
  route.afterEach(() => track());
});
