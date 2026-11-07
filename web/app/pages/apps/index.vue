<script setup lang="ts">
definePageMeta({ layout: "catalog" });
const route = useRoute(),
  { t } = useLanguage(),
  { catalog, load } = useCatalog();
await load(true);
const section = computed(() =>
  catalog.value.sections.find(
    (item) => item.id === route.query.section && item.kind === "APPLICATIONS",
  ),
);
const title = computed(() =>
  section.value
    ? t(section.value.titleZh, section.value.titleEn || section.value.titleZh)
    : t("全部应用", "All applications"),
);
const endpoint = computed(() =>
  section.value
    ? "/api/public/sections/" + section.value.id + "/items"
    : "/api/public/directory/applications",
);
useHead(() => ({ title: title.value + " · AI万有网" }));
</script>
<template>
  <DirectoryListing
    :endpoint="endpoint"
    :title="title"
    :kind="section ? undefined : 'APPLICATIONS'"
  />
</template>
