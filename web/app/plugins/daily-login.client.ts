/** 访问网站或跨日恢复窗口时确认登录；奖励由服务端按业务日幂等发放，不在本地保存或增加余额。 */
export default defineNuxtPlugin((app) => {
  let lastDay = "",
    pending = false;
  const { request } = useApi();
  async function check() {
    const day = new Intl.DateTimeFormat("en-CA", {
      timeZone: "Asia/Shanghai",
    }).format(new Date());
    if (pending || lastDay === day || document.visibilityState === "hidden")
      return;
    pending = true;
    try {
      await request("/api/auth/session");
      lastDay = day;
    } catch (e: any) {
      if (e.status === 401) lastDay = day;
    } finally {
      pending = false;
    }
  }
  app.hook("app:mounted", check);
  document.addEventListener("visibilitychange", check);
  app.hook("page:finish", check);
});
