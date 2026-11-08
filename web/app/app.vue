<script setup lang="ts">
/** 根组件只读取公开站点配置；稳定页签键保证工作区切换保留草稿，关闭/退出用代数创建新实例。 */
const route = useRoute();
const { data: site, error: siteError } = await useFetch<Record<string, string>>(
  "/api/public/site",
  { key: "site-settings", default: () => ({}) },
);
useState<Record<string, string>>("site-settings", () => ({})).value =
  site.value || {};
if (import.meta.server && siteError.value) {
  const event = useRequestEvent();
  if (event) event.context.publicSiteUnavailable = true;
}
const sessionGeneration = useState("admin-session-generation", () => 0);
const revisions = useState<Record<string, number>>(
  "admin-page-revisions",
  () => ({}),
);
const pageKey = (target: any) =>
  target.path.startsWith("/admin")
    ? `${sessionGeneration.value}:${target.path === "/admin/workspace" ? target.path : target.fullPath}:${revisions.value[target.path === "/admin/workspace" ? target.path : target.fullPath] || 0}`
    : target.path;
</script>
<template>
  <NuxtLayout
    ><NuxtPage
      :page-key="pageKey"
      :keepalive="{
        max: 24,
        include: [
          'AdminDashboard',
          'AdminSettings',
          'AdminAudit',
          'AdminSecurity',
          'AdminWorkspace',
        ],
      }"
  /></NuxtLayout>
</template>
