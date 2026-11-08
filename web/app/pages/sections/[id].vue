<script setup lang="ts">
definePageMeta({ layout: "catalog" });
const route = useRoute(),
  { t } = useLanguage(),
  { catalog, load } = useCatalog();
await load(true);
const section = computed(() =>
  catalog.value.sections.find((item) => item.id === route.params.id),
);
const title = computed(() =>
  section.value
    ? t(section.value.titleZh, section.value.titleEn || section.value.titleZh)
    : t("栏目", "Section"),
);
const { siteName } = useSiteBrand();
useHead(() => ({ title: title.value + " · " + siteName.value }));
</script>
<template>
  <DirectoryListing
    :endpoint="'/api/public/sections/' + route.params.id + '/items'"
    :title="title"
    :kind="section?.kind === 'APPLICATIONS' ? undefined : section?.kind"
    :subtitle="
      section && t(section.subtitleZh, section.subtitleEn || section.subtitleZh)
    "
  />
</template>
