<script setup lang="ts">
/** 栏目稳定ID、标签与页码写入URL；筛选始终基于公开已发布目录，不读取后台草稿。 */
const { catalog, error, load } = useCatalog(),
  { t } = useLanguage();
await load();
const catalogSections = computed(() =>
  catalog.value.sections
    .filter((s) => s.kind === "APPLICATIONS")
    .map((s) => ({
      ...s,
      title: t(s.titleZh, s.titleEn || s.titleZh),
    })),
);
const catalogTags = computed(() => [
  ...new Set(catalog.value.products.flatMap((p) => p.content.tags)),
]);
function appsForSection(id: string) {
  const source =
    id === "all"
      ? catalog.value.products
      : catalog.value.sections.find((s) => s.id === id)?.products || [];
  return source.map((p) => ({
    ...p,
    name: t(p.content.nameZh, p.content.nameEn || p.content.nameZh),
    description: t(
      p.content.summaryZh,
      p.content.summaryEn || p.content.summaryZh,
    ),
    keywords: p.content.slug,
    tags: p.content.tags,
  }));
}
definePageMeta({ layout: "catalog" });
const route = useRoute();
const sectionId = computed(() =>
  catalogSections.value.some((s) => s.id === route.query.section)
    ? String(route.query.section)
    : "all",
);
const title = computed(
  () =>
    catalogSections.value.find((s) => s.id === sectionId.value)?.title ||
    t("全部应用", "All applications"),
);
useHead(() => ({ title: `${title.value} · AI万有网` }));
const selectedTags = computed(() =>
  String(route.query.tags || "")
    .split(",")
    .filter((t) => catalogTags.value.includes(t)),
);
const term = computed(() => String(route.query.q || ""));
const draft = ref(term.value);
watch(term, (q) => (draft.value = q));
const pageSize = 9;
const filtered = computed(() =>
  appsForSection(sectionId.value).filter(
    (app) =>
      selectedTags.value.every((tag) => app.tags.includes(tag)) &&
      (!term.value.trim() ||
        `${app.name} ${app.description} ${app.keywords} ${app.tags.join(" ")}`
          .toLowerCase()
          .includes(term.value.trim().toLowerCase())),
  ),
);
const pageCount = computed(() =>
  Math.max(1, Math.ceil(filtered.value.length / pageSize)),
);
const currentPage = computed(() =>
  Math.max(
    1,
    Math.min(
      pageCount.value,
      Number.isSafeInteger(Number(route.query.page))
        ? Number(route.query.page)
        : 1,
    ),
  ),
);
const paged = computed(() =>
  filtered.value.slice(
    (currentPage.value - 1) * pageSize,
    currentPage.value * pageSize,
  ),
);
function pageTo(page: number) {
  return {
    path: "/apps",
    query: { ...route.query, page: page === 1 ? undefined : String(page) },
  };
}
async function filterTag(tag?: string) {
  const tags = tag
    ? selectedTags.value.includes(tag)
      ? selectedTags.value.filter((t) => t !== tag)
      : [...selectedTags.value, tag]
    : [];
  await navigateTo({
    path: "/apps",
    query: {
      ...route.query,
      tags: tags.length ? tags.join(",") : undefined,
      page: undefined,
    },
  });
}
async function search() {
  await navigateTo({
    path: "/apps",
    query: {
      ...route.query,
      q: draft.value.trim() || undefined,
      page: undefined,
    },
  });
}
</script>
<template>
  <main class="directory-width catalog-list-page">
    <div v-if="error" class="catalog-empty catalog-error" role="alert">
      {{ error }}
    </div>
    <div class="catalog-breadcrumb">
      <NuxtLink to="/">{{ t("首页", "Home") }}</NuxtLink
      ><SiteIcon name="chevron" /><span>{{ title }}</span>
    </div>
    <div class="catalog-page-heading">
      <div>
        <span class="directory-eyebrow">{{
          t("发现适合你的下一款工具", "Find your next useful tool")
        }}</span>
        <h1>
          {{ title
          }}<span>{{ filtered.length }} {{ t("款应用", "applications") }}</span>
        </h1>
      </div>
      <form
        class="directory-search catalog-list-search"
        role="search"
        @submit.prevent="search"
      >
        <SiteIcon name="search" /><label
          class="directory-sr"
          for="catalog-list-query"
          >{{ t("搜索应用", "Search applications") }}</label
        ><input
          id="catalog-list-query"
          v-model="draft"
          type="search"
          :placeholder="t('搜索名称或功能…', 'Search by name or feature…')"
        /><button class="directory-search-submit" type="submit">
          {{ t("搜索", "Search") }}
        </button>
      </form>
    </div>
    <div class="catalog-filter-panel">
      <div class="catalog-section-tabs" aria-label="应用区域">
        <NuxtLink
          :to="{
            path: '/apps',
            query: { section: 'all', ...(term ? { q: term } : {}) },
          }"
          :class="{ active: sectionId === 'all' }"
          >{{ t("全部应用", "All applications") }}</NuxtLink
        ><NuxtLink
          v-for="section in catalogSections"
          :key="section.id"
          :to="{
            path: '/apps',
            query: {
              section: section.id,
              ...(term ? { q: term } : {}),
              ...(selectedTags.length ? { tags: selectedTags.join(',') } : {}),
            },
          }"
          :class="{ active: sectionId === section.id }"
          >{{ section.title }}</NuxtLink
        >
      </div>
      <div class="catalog-tag-filter">
        <span>{{ t("标签筛选", "Tags") }}</span>
        <div>
          <button
            :class="{ active: !selectedTags.length }"
            :aria-pressed="!selectedTags.length"
            @click="filterTag()"
          >
            {{ t("全部", "All") }}</button
          ><button
            v-for="tag in catalogTags"
            :key="tag"
            :class="{ active: selectedTags.includes(tag) }"
            :aria-pressed="selectedTags.includes(tag)"
            @click="filterTag(tag)"
          >
            {{ tag }}
          </button>
        </div>
      </div>
    </div>
    <div class="catalog-result-bar">
      <span aria-live="polite"
        >{{
          selectedTags.length
            ? t("同时匹配：", "Matching: ") + selectedTags.join("、")
            : t("为你整理的实用应用", "Useful applications for you")
        }}<span v-if="term">
          · {{ t("搜索", "Search") }} “{{ term }}”</span
        ></span
      ><span>{{
        filtered.length
          ? t(
              "点击卡片下载 · 查看详情了解更多",
              "Click a card to download · Open details to learn more",
            )
          : t("换一个关键词或标签试试", "Try another keyword or tag")
      }}</span>
    </div>
    <div v-if="paged.length" class="catalog-application-list">
      <CatalogTile v-for="app in paged" :key="app.id" :product="app" list />
    </div>
    <div v-else class="directory-no-results" role="status">
      <SiteIcon name="search" />
      <h2>{{ t("没有符合条件的应用", "No applications match") }}</h2>
      <p>
        {{
          t(
            "可以取消部分标签，或调整搜索关键词。",
            "Try fewer tags or another search term.",
          )
        }}
      </p>
      <NuxtLink
        class="catalog-primary-button"
        :to="{ path: '/apps', query: { section: sectionId } }"
        >{{ t("清除筛选条件", "Clear filters") }}</NuxtLink
      >
    </div>
    <nav v-if="paged.length" class="catalog-pagination" aria-label="应用分页">
      <span
        >{{ t("共", "Total") }} {{ filtered.length }} · {{ pageSize }}
        {{ t("款/页", "per page") }}</span
      >
      <div>
        <NuxtLink
          v-if="currentPage > 1"
          :to="pageTo(currentPage - 1)"
          aria-label="上一页"
          ><SiteIcon name="back" /></NuxtLink
        ><button v-else aria-label="上一页" disabled>
          <SiteIcon name="back" /></button
        ><NuxtLink
          v-for="page in pageCount"
          :key="page"
          :to="pageTo(page)"
          :aria-current="page === currentPage ? 'page' : undefined"
          :class="{ active: page === currentPage }"
          :aria-label="`第${page}页`"
          >{{ page }}</NuxtLink
        ><NuxtLink
          v-if="currentPage < pageCount"
          :to="pageTo(currentPage + 1)"
          aria-label="下一页"
          ><SiteIcon name="arrow" /></NuxtLink
        ><button v-else aria-label="下一页" disabled>
          <SiteIcon name="arrow" />
        </button>
      </div>
      <span>{{ currentPage }} / {{ pageCount }}</span>
    </nav>
  </main>
</template>
