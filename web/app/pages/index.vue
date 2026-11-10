<script setup lang="ts">
// 首页完全由公开目录接口驱动；空库保持真实空状态，不填充虚构商品和统计。
definePageMeta({ layout: "catalog" });
import type { SeriesCard } from "~/types/series";
import type { HomeSection } from "~/types/catalog";
const { t } = useLanguage();
const { request } = useApi();
const {
  data: sections,
  status,
  error,
  refresh,
} = await useAsyncData("homepage-sections", () =>
  request<HomeSection[]>("/api/public/home-sections"),
);
if (import.meta.server && error.value) {
  const event = useRequestEvent();
  if (event) event.context.publicSiteUnavailable = true;
}
const changed = useState("catalog-changed", () => 0);
const { data: seriesCards, refresh: refreshSeries } = await useAsyncData(
  "home-series",
  () => request<SeriesCard[]>("/api/public/series"),
);
watch(changed, () => {
  refresh();
  refreshSeries();
});
const query = ref("");
const { site, siteName } = useSiteBrand();
useHead(() => ({
  title: `${siteName.value} · ${t("资源", "Resources")}`,
  meta: [
    {
      name: "description",
      content: t(
        site.value["site.description"] || "",
        site.value["site.description_en"] || "",
      ),
    },
  ],
}));
async function search() {
  await navigateTo({
    path: "/apps",
    query: query.value.trim() ? { q: query.value.trim() } : {},
  });
}
</script>
<template>
  <main class="directory-width directory-main">
    <section class="directory-search-section">
      <h1 class="directory-sr">{{ t("资源", "Resources") }}</h1>
      <form class="directory-search" role="search" @submit.prevent="search">
        <SiteIcon name="search" /><label
          class="directory-sr"
          for="catalog-query"
          >{{ t("搜索资源", "Search resources") }}</label
        ><input
          id="catalog-query"
          v-model="query"
          type="search"
          :placeholder="t('发现好知识', 'Discover new ideas')"
        /><button class="directory-search-submit" type="submit">
          {{ t("搜索", "Search") }}<SiteIcon name="arrow" />
        </button>
      </form>
    </section>
    <div class="directory-content-heading">
      <span
        ><span class="directory-online-dot" />{{
          t("发现优质资源", "Discover useful resources")
        }}</span
      ><span>{{
        t("精选资源 · 便捷查阅", "Selected resources · Easy to browse")
      }}</span>
    </div>
    <section v-if="seriesCards?.length" class="directory-section">
      <div class="directory-section-heading">
        <div class="directory-section-title">
          <h2>{{ t("专栏", "Series") }}</h2>
        </div>
        <NuxtLink to="/series" class="catalog-more-link">{{
          t("全部专栏", "All series")
        }}</NuxtLink>
      </div>
      <SeriesCards :cards="seriesCards.slice(0, 6)" />
    </section>
    <p v-if="status === 'pending'" role="status" class="catalog-empty">
      {{ t("正在加载栏目内容…", "Loading…") }}
    </p>
    <div v-else-if="error" class="catalog-empty catalog-error" role="alert">
      <p>{{ t("栏目加载失败，请重试。", "Unable to load sections.") }}</p>
      <button @click="refresh()">{{ t("重新加载", "Try again") }}</button>
    </div>
    <template v-else>
      <section
        v-for="section in sections || []"
        :id="section.id"
        :key="section.id"
        class="directory-section"
      >
        <div class="directory-section-heading">
          <div class="directory-section-title">
            <span class="directory-section-symbol purple"
              ><SiteIcon :name="section.icon"
            /></span>
            <h2>
              {{ t(section.titleZh, section.titleEn || section.titleZh) }}
            </h2>
            <span class="directory-section-subtitle">{{
              t(section.subtitleZh, section.subtitleEn || section.subtitleZh)
            }}</span>
          </div>
          <NuxtLink class="catalog-more-link" :to="`/sections/${section.id}`"
            >{{ t("更多", "More") }}<SiteIcon name="arrow"
          /></NuxtLink>
        </div>
        <div v-if="section.items.length" class="directory-card-grid">
          <DirectoryCard
            v-for="item in section.items"
            :key="item.ref"
            :item="item"
          />
        </div>
        <p v-else class="catalog-empty">
          {{
            t(
              "本栏目暂时没有已发布内容。",
              "No published content in this section yet.",
            )
          }}
        </p>
      </section></template
    >
  </main>
</template>
