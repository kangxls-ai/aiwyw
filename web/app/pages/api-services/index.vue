<script setup lang="ts">
definePageMeta({ layout: "catalog" });
const route = useRoute(),
  { t } = useLanguage();
const manage = computed(() => route.query.manage === "1" || !!route.query.api);
const title = computed(() => t("API 服务", "API services"));
const { siteName } = useSiteBrand();
useHead(() => ({ title: title.value + " · " + siteName.value }));
</script>
<template>
  <ApiWorkspace v-if="manage" />
  <div v-else>
    <div class="directory-width api-workspace-entry">
      <NuxtLink to="/api-services?manage=1">{{
        t("管理Key、订阅与在线调试", "Manage keys, subscriptions and debugging")
      }}</NuxtLink>
    </div>
    <DirectoryListing
      endpoint="/api/public/directory/apis"
      :title="title"
      kind="API"
    />
  </div>
</template>
<style scoped>
.api-workspace-entry {
  padding-top: 16px;
  text-align: right;
}
</style>
