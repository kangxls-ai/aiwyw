<script setup lang="ts">
definePageMeta({ layout: "catalog" });
const { t } = useLanguage(),
  { catalog, load } = useCatalog();
await load(true);
const section = computed(() =>
  catalog.value.sections.find((item) => item.kind === "ARTICLES"),
);
const title = computed(() =>
  section.value
    ? t(section.value.titleZh, section.value.titleEn || section.value.titleZh)
    : t("文章与教程", "Articles and tutorials"),
);
useHead(() => ({
  title: title.value + " · AI万有网",
  meta: [
    {
      name: "description",
      content: t(
        "应用教程、常见问题和使用案例。",
        "Tutorials, FAQs and application use cases.",
      ),
    },
  ],
}));
</script>
<template>
  <DirectoryListing
    endpoint="/api/public/directory/articles"
    :title="title"
    kind="ARTICLES"
    :subtitle="
      section && t(section.subtitleZh, section.subtitleEn || section.subtitleZh)
    "
  />
</template>
