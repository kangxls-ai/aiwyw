<script setup lang="ts">
const { staffPath } = useStaffPaths();
const legacyRoute = useRoute();
if (legacyRoute.query.view === "email")
  await navigateTo(
    staffPath("/admin/settings?group=" + encodeURIComponent("邮件服务")),
    {
      replace: true,
    },
  );
/** 工作区按已打开页签显式保留组件；关闭即卸载，避免多应用编辑被单一表单或缓存上限覆盖。 */
defineOptions({ name: "AdminWorkspace" });
definePageMeta({ layout: "admin" });
import { adminMenuItem } from "~/data/adminNavigation";
const { tabs, activeId } = useManagementTabs();
const workspaces = computed(() =>
  tabs.value.filter((t) =>
    staffRelative(t.route).startsWith("/admin/workspace"),
  ),
);
const view = (tab: { route: string }) =>
  new URL(tab.route, "http://localhost").searchParams.get("view") || "";
</script>
<template>
  <section
    v-for="tab in workspaces"
    v-show="tab.id === activeId"
    :key="tab.id"
    :data-workspace="tab.id"
  >
    <ManagementEntityEditor v-if="tab.kind === 'entity-editor'" :tab="tab" />
    <AdminActiveUsers
      v-else-if="tab.kind === 'active-users'"
      :from="tab.from!"
      :until="tab.until!"
    />
    <AdminApi
      v-else-if="tab.kind === 'api-editor'"
      view="apis"
      editor
      :tab-id="tab.id"
      :api-id="tab.apiId || null"
    />
    <AdminProductEditor
      v-else-if="tab.kind === 'product-editor'"
      :tab-id="tab.id"
      :initial-product-id="tab.productId || null"
    />
    <AdminProductList
      v-else-if="['applications', 'versions', 'publishing'].includes(view(tab))"
      :view="view(tab)"
    />
    <AdminFinance
      v-else-if="
        ['recharge', 'points', 'refunds', 'orders'].includes(view(tab))
      "
      :view="view(tab)"
    />
    <AdminPackages v-else-if="view(tab) === 'packages'" />
    <AdminApi
      v-else-if="['apis', 'plans', 'keys', 'usage'].includes(view(tab))"
      :view="view(tab)"
    />
    <AdminSupport
      v-else-if="['conversations', 'messages', 'feedback'].includes(view(tab))"
      :view="view(tab)"
    />
    <AdminStatistics v-else-if="view(tab) === 'statistics'" />
    <AdminUsers v-else-if="view(tab) === 'users'" :view="view(tab)" />
    <AdminLicenses v-else-if="view(tab) === 'licenses'" />
    <AdminSeries v-else-if="view(tab) === 'series'" />
    <AdminContent
      v-else-if="['articles', 'faq'].includes(view(tab))"
      :view="view(tab)"
    /><AdminSeo v-else-if="view(tab) === 'seo'" /><AdminRoles
      v-else-if="view(tab) === 'roles'"
    /><AdminTags v-else-if="view(tab) === 'tags'" />
    <AdminCatalog v-else-if="view(tab) === 'sections'" view="sections" />
    <AdminStorage v-else-if="view(tab) === 'storage'" />
    <div v-else class="panel">
      <h1>{{ adminMenuItem(view(tab)).label }}</h1>
      <p class="muted">该功能尚未接入，当前不接受业务操作。</p>
    </div>
  </section>
</template>
