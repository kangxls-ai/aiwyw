// 公开详情交由后端已发布的静态文件响应；Nuxt不在每次访问时重新查询商品。
const staticDelivery = process.env.AIWYW_STATIC_BUILD === "1";
export default defineNuxtConfig({
  ssr: !staticDelivery,
  runtimeConfig: { public: { staticDelivery } },
  nitro: { prerender: { crawlLinks: false, routes: ["/"] } },
  compatibilityDate: "2026-11-03",
  devtools: { enabled: false },
  modules: ["@element-plus/nuxt"],
  // 服务式确认框使用同一应用上下文，和对话框共享层级计数及语言；避免被套餐/客服窗口遮挡。
  elementPlus: {
    defaultLocale: "zh-cn",
    installMethods: ["ElMessageBox", "ElMessage"],
  },
  css: ["~/assets/main.css"],
  routeRules: {
    "/apps": { ssr: !staticDelivery },
    "/account-access": { headers: { "cache-control": "private, no-store" } },
    "/preview/**": { redirect: { to: "/", statusCode: 301 } },
    "/products/markdo": { redirect: { to: "/apps/markdo", statusCode: 301 } },
  },
  hooks: {
    "pages:extend"(pages) {
      for (const page of pages) {
        if (/^\/(?:admin(?:\/|$)|login$)/.test(page.path))
          page.path = "/:staffEntry([0-9a-f]{32})" + page.path;
      }
    },
  },
  app: {
    head: {
      link: [
        { rel: "stylesheet", href: "/ui-theme.css?v=2" },
        { rel: "icon", type: "image/svg+xml", href: "/images/weiyunwan.svg" },
      ],
      title: "微云湾",
      htmlAttrs: { lang: "zh-CN" },
      meta: [
        { name: "viewport", content: "width=device-width,initial-scale=1" },
      ],
    },
  },
  typescript: { strict: true },
});
