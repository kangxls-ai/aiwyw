// 公开详情交由后端已发布的静态文件响应；Nuxt不在每次访问时重新查询商品。
export default defineNuxtConfig({
  compatibilityDate: "2026-11-03",
  devtools: { enabled: false },
  modules: ["@element-plus/nuxt"],
  elementPlus: { defaultLocale: "zh-cn" },
  css: ["~/assets/main.css"],
  routeRules: {
    "/api/**": { proxy: "http://127.0.0.1:18080/api/**" },
    "/apps": { ssr: true },
    "/preview/**": { redirect: { to: "/", statusCode: 301 } },
    "/preview/admin": { redirect: { to: "/admin", statusCode: 301 } },
    "/products/markdo": { redirect: { to: "/apps/markdo", statusCode: 301 } },
  },
  app: {
    head: {
      link: [{ rel: "stylesheet", href: "/ui-theme.css?v=2" }],
      title: "AI万有网",
      htmlAttrs: { lang: "zh-CN" },
      meta: [
        { name: "viewport", content: "width=device-width,initial-scale=1" },
      ],
    },
  },
  typescript: { strict: true },
});
