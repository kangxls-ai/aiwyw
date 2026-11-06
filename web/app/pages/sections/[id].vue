<script setup lang="ts">
/** 栏目更多页使用服务端分页；人工混排内容最多300项，未公开内容不会出现在结果里。 */
definePageMeta({ layout: "catalog" });
const route = useRoute(),
  { request } = useApi(),
  { t } = useLanguage(),
  { catalog, load } = useCatalog();
await load(true);
const section = computed(() =>
  catalog.value.sections.find((s) => s.id === route.params.id),
);
const page = computed(() => {
  const value = Number(route.query.page);
  return Number.isSafeInteger(value) && value > 0 ? value : 1;
});
const q = ref(String(route.query.q || ""));
watch(
  () => route.query.q,
  (value) => {
    q.value = String(value || "");
  },
);
const { data, error, status, refresh } = await useAsyncData(
  "section-list",
  () =>
    request<any>(`/api/public/sections/${route.params.id}/items`, {
      query: {
        page: page.value,
        pageSize: 9,
        q: route.query.q || "",
        tag: route.query.tag || "",
      },
    }),
  { watch: [() => route.fullPath] },
);
useHead(() => ({
  title: `${section.value ? t(section.value.titleZh, section.value.titleEn || section.value.titleZh) : t("栏目", "Section")} · AI万有网`,
}));
function change(query: Record<string, any>) {
  return navigateTo({ path: route.path, query: { ...route.query, ...query } });
}
</script>
<template>
  <main class="directory-width catalog-list-page">
    <div class="catalog-breadcrumb">
      <NuxtLink to="/">{{ t("首页", "Home") }}</NuxtLink
      ><SiteIcon name="chevron" /><span>{{
        section
          ? t(section.titleZh, section.titleEn || section.titleZh)
          : t("栏目", "Section")
      }}</span>
    </div>
    <div class="catalog-page-heading">
      <h1>
        {{
          section
            ? t(section.titleZh, section.titleEn || section.titleZh)
            : t("栏目", "Section")
        }}
      </h1>
      <el-form
        inline
        @submit.prevent="change({ q: q.trim() || undefined, page: undefined })"
        ><el-form-item :label="t('名称或描述', 'Name or description')"
          ><el-input v-model="q" clearable maxlength="100" /></el-form-item
        ><el-button native-type="submit">{{
          t("搜索", "Search")
        }}</el-button></el-form
      >
    </div>
    <div v-if="data?.tags?.length" class="catalog-tag-filter">
      <span>{{ t("标签筛选", "Tags") }}</span>
      <div>
        <button
          :class="{ active: !route.query.tag }"
          @click="change({ tag: undefined, page: undefined })"
        >
          {{ t("全部", "All") }}</button
        ><button
          v-for="tag in data.tags"
          :key="tag"
          :class="{ active: route.query.tag === tag }"
          @click="change({ tag, page: undefined })"
        >
          {{ tag }}
        </button>
      </div>
    </div>
    <p v-if="status === 'pending'" class="catalog-empty" role="status">
      {{ t("加载中…", "Loading…") }}
    </p>
    <div v-else-if="error" class="catalog-empty" role="alert">
      <p>{{ t("栏目无法加载或已停用。", "This section is unavailable.") }}</p>
      <el-button @click="refresh()">{{ t("重试", "Retry") }}</el-button>
    </div>
    <template v-else>
      <p class="admin-muted">
        {{ t("共", "Total") }} {{ data?.total || 0 }} {{ t("项", "items") }}
      </p>
      <div class="catalog-application-list">
        <template v-for="item in data?.items || []" :key="item.ref"
          ><CatalogTile v-if="item.product" :product="item.product" list /><a
            v-else
            class="section-result"
            :href="item.href"
            target="_blank"
            rel="noopener"
            ><SiteIcon :name="item.kind === 'API' ? 'convert' : 'document'" />
            <div>
              <h2>{{ t(item.name, item.nameEn || item.name) }}</h2>
              <p>
                {{
                  t(item.summary || "", item.summaryEn || item.summary || "")
                }}
              </p>
              <small>{{ t("查看详情 ↗", "View details ↗") }}</small>
            </div></a
          ></template
        >
      </div>
      <p v-if="!data?.items?.length" class="catalog-empty">
        {{
          t(
            "没有匹配的已发布内容，请调整筛选条件。",
            "No matching published content.",
          )
        }}
      </p>
      <el-pagination
        v-if="data?.total"
        :current-page="data.page"
        :page-size="9"
        :total="data.total"
        layout="total,prev,pager,next"
        @current-change="
          (value) => change({ page: value === 1 ? undefined : value })
        "
      />
    </template>
  </main>
</template>
<style scoped>
.section-result {
  display: flex;
  gap: 22px;
  min-height: 180px;
  padding: 24px;
  border: 1px solid #eae8f2;
  border-radius: 12px;
  background: white;
  color: #48435e;
  text-decoration: none;
}
.section-result > svg {
  width: 48px;
  height: 48px;
  flex-shrink: 0;
  color: #8b78c5;
}
.section-result h2 {
  font-size: 16px;
  font-weight: 500;
}
.section-result p {
  font-size: 14px;
  color: #72798e;
}
.section-result small {
  color: #7a6ab0;
}
.section-result:hover {
  border-color: #b9aadc;
}
</style>
