<script setup lang="ts">
import type { SeriesCard } from "~/types/series";
definePageMeta({ layout: "catalog" });
const { t } = useLanguage();
const { request } = useApi();
const { data, error } = await useAsyncData("series-list", () =>
  request<SeriesCard[]>("/api/public/series"),
);
useHead(() => ({ title: t("专栏", "Series") }));
</script>
<template>
  <main class="directory-width series-page">
    <h1>{{ t("专栏", "Series") }}</h1>
    <p>
      {{
        t(
          "按主题探索系列文章和应用。",
          "Explore related articles and applications by topic.",
        )
      }}
    </p>
    <SeriesCards v-if="data?.length" :cards="data" />
    <p v-else class="catalog-empty">
      {{
        error
          ? t("暂时无法读取专栏。", "Unable to load series.")
          : t("暂无已发布专栏。", "No published series yet.")
      }}
    </p>
  </main>
</template>
