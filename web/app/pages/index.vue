<script setup lang="ts">
// 首页完全由公开目录接口驱动；空库保持真实空状态，不填充虚构商品和统计。
definePageMeta({ layout: "catalog" });
const { catalog, error, load } = useCatalog(),
  { t } = useLanguage();
await load();
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
      <button @click="load">{{ t("重新加载", "Try again") }}</button>
    </div>
    <template v-else
      ><section
        v-for="section in catalog.sections"
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
          <NuxtLink
            class="catalog-more-link"
            :to="{ path: '/apps', query: { section: section.id } }"
            >{{ t("更多", "More") }}<SiteIcon name="arrow"
          /></NuxtLink>
        </div>
        <div v-if="section.products.length" class="directory-app-grid">
          <CatalogTile
            v-for="product in section.products.slice(0, section.limit)"
            :key="product.id"
            :product="product"
          />
        </div>
        <p v-else class="catalog-empty">
          {{
            t(
              "应用正在整理，敬请期待。",
              "Applications are being prepared. Check back soon.",
            )
          }}
        </p>
      </section></template
    >
  </main>
</template>
