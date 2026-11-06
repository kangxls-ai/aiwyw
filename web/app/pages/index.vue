<script setup lang="ts">
// 首页完全由公开目录接口驱动；空库保持真实空状态，不填充虚构商品和统计。
definePageMeta({ layout: "catalog" });
const { catalog, error, load } = useCatalog(),
  { t } = useLanguage();
await load(true);
const route = useRoute();
const selected = computed(
  () =>
    catalog.value.sections.find((s) => s.id === route.query.section) ||
    catalog.value.sections[0],
);
const { request } = useApi();
const {
  data: selection,
  status: sectionStatus,
  error: sectionError,
  refresh,
} = await useAsyncData(
  "home-section",
  () =>
    selected.value
      ? request<any>(`/api/public/sections/${selected.value.id}/items`, {
          query: { pageSize: Math.min(selected.value.limit, 60) },
        })
      : Promise.resolve({ items: [], total: 0 }),
  { watch: [selected] },
);
const query = ref("");
const site = useState<Record<string, string>>("site-settings", () => ({}));
useHead(() => ({
  title: `${site.value["site.name"] || "AI万有网"} · ${t("工具与应用", "Tools and applications")}`,
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
      <div class="directory-intro">
        <span class="directory-eyebrow">{{
          t("为每一个灵感，找到趁手的工具", "Useful tools for every idea")
        }}</span>
        <h1>
          {{ t("好用的工具，", "Useful tools,")
          }}<span>{{ t("都在这里。", "all in one place.") }}</span>
        </h1>
      </div>
      <form class="directory-search" role="search" @submit.prevent="search">
        <SiteIcon name="search" /><label
          class="directory-sr"
          for="catalog-query"
          >{{
            t("搜索工具、软件或 API", "Search tools, software or APIs")
          }}</label
        ><input
          id="catalog-query"
          v-model="query"
          type="search"
          :placeholder="
            t('搜索工具、软件或 API…', 'Search tools, software or APIs…')
          "
        /><button class="directory-search-submit" type="submit">
          {{ t("搜索", "Search") }}<SiteIcon name="arrow" />
        </button>
      </form>
    </section>
    <div class="directory-content-heading">
      <span
        ><span class="directory-online-dot" />{{
          t("发现你的下一款好工具", "Discover your next useful tool")
        }}</span
      ><span>{{
        t("精选应用 · 简单好用", "Selected apps · Simply useful")
      }}</span>
    </div>
    <div v-if="error" class="catalog-empty catalog-error" role="alert">
      <p>{{ error }}</p>
      <button @click="load(true)">{{ t("重新加载", "Try again") }}</button>
    </div>
    <template v-else
      ><section
        v-for="section in selected ? [selected] : []"
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
        <p
          v-if="sectionStatus === 'pending'"
          role="status"
          class="catalog-empty"
        >
          {{ t("正在加载栏目内容…", "Loading…") }}
        </p>
        <div v-else-if="sectionError" class="catalog-empty" role="alert">
          <p>
            {{ t("栏目加载失败，请重试。", "Unable to load this section.") }}
          </p>
          <button @click="refresh()">{{ t("重试", "Retry") }}</button>
        </div>
        <div v-else-if="selection?.items?.length" class="directory-app-grid">
          <template v-for="item in selection.items" :key="item.ref">
            <CatalogTile v-if="item.product" :product="item.product" />
            <a
              v-else
              class="section-content-card"
              :href="item.href"
              target="_blank"
              rel="noopener"
              ><SiteIcon
                :name="item.kind === 'API' ? 'convert' : 'document'"
              /><span>{{ t(item.name, item.nameEn || item.name) }}</span
              ><small>{{ t("查看详情", "View details") }}</small></a
            >
          </template>
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

<style scoped>
.section-content-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 14px;
  min-height: 174px;
  padding: 18px 8px;
  text-align: center;
  border: 1px solid #ecebf5;
  border-radius: 12px;
  background: white;
  color: #454064;
  text-decoration: none;
}
.section-content-card:hover {
  border-color: #bcb2e7;
  background: #faf9ff;
}
.section-content-card > svg {
  width: 38px;
  height: 38px;
  color: #8a76c8;
}
.section-content-card small {
  font-size: 12px;
  color: #8b90a3;
}
</style>
