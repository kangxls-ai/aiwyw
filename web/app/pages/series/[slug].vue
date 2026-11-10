<script setup lang="ts">
import type { SeriesDetail } from "~/types/series";
definePageMeta({ layout: "catalog" });
const { t } = useLanguage();
const route = useRoute();
const { request } = useApi();
const { data, error, status } = await useAsyncData(
  "series-" + route.params.slug,
  () =>
    request<SeriesDetail>(
      "/api/public/series/" + encodeURIComponent(String(route.params.slug)),
    ),
);
if (error.value)
  throw createError({
    statusCode: error.value.statusCode || 503,
    statusMessage: "Series unavailable",
  });
const title = computed(() =>
  data.value
    ? t(
        data.value.series.titleZh,
        data.value.series.titleEn || data.value.series.titleZh,
      )
    : "",
);
useHead(() => ({ title: title.value }));
</script>
<template>
  <main class="directory-width series-page">
    <template v-if="data">
      <nav class="public-breadcrumb" :aria-label="t('当前位置', 'Breadcrumb')">
        <NuxtLink to="/">{{ t("首页", "Home") }}</NuxtLink
        ><span>/</span>
        <NuxtLink to="/series">{{ t("专栏", "Series") }}</NuxtLink
        ><span>/</span>
        <span aria-current="page">{{ title }}</span>
      </nav>
      <h1>{{ title }}</h1>
      <p>
        {{
          t(
            data.series.summaryZh,
            data.series.summaryEn || data.series.summaryZh,
          )
        }}
      </p>
      <h2>{{ t("系列目录", "Contents") }}</h2>
      <ol v-if="data.items.length" class="series-cards series-contents">
        <li v-for="item in data.items" :key="item.ref">
          <NuxtLink :to="item.href">
            <strong>{{ t(item.name, item.nameEn || item.name) }}</strong>
            <span>{{
              t(
                item.summary || item.product?.content.summaryZh || "",
                item.summaryEn ||
                  item.product?.content.summaryEn ||
                  item.summary ||
                  item.product?.content.summaryZh ||
                  "",
              )
            }}</span>
          </NuxtLink>
        </li>
      </ol>
      <p v-else class="catalog-empty">
        {{ t("暂无已发布内容。", "No published items yet.") }}
      </p>
    </template>
    <p v-else class="catalog-empty">
      {{
        status === "pending"
          ? t("正在读取专栏。", "Loading series.")
          : t("专栏暂不可用。", "Series unavailable.")
      }}
    </p>
  </main>
</template>
