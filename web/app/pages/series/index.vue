<script setup lang="ts">
definePageMeta({ layout: "catalog" });
const { t } = useLanguage();
const { catalog, load } = useCatalog();
if (!catalog.value.sections.length) await load(true);
const section = computed(() =>
  catalog.value.sections.find((item) => item.kind === "SERIES"),
);
const title = computed(() =>
  section.value
    ? t(section.value.titleZh, section.value.titleEn || section.value.titleZh)
    : t("专栏", "Series"),
);
const subtitle = computed(() => {
  const current = section.value;
  return (
    (current &&
      t(current.subtitleZh, current.subtitleEn || current.subtitleZh)) ||
    t(
      "按主题查找系列文章和应用。",
      "Find related articles and applications by topic.",
    )
  );
});
useHead(() => ({ title: title.value }));
</script>

<template>
  <DirectoryListing
    endpoint="/api/public/directory/series"
    :title="title"
    :subtitle="subtitle"
    kind="SERIES"
  />
</template>
