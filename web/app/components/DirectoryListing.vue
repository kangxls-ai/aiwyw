<script setup lang="ts">
import type { DirectoryPage } from "~/types/catalog";
const props = defineProps<{
  endpoint: string;
  title: string;
  kind?: string;
  subtitle?: string;
}>();
const route = useRoute(),
  { request } = useApi(),
  { t } = useLanguage();
const sizes = [40, 60, 100, 150, 200];
const size = computed(() =>
  sizes.includes(Number(route.query.pageSize))
    ? Number(route.query.pageSize)
    : 100,
);
const page = computed(() => {
  const value = Number(route.query.page);
  return Number.isInteger(value) && value > 0 && value <= 1000000 ? value : 1;
});
const tags = computed(() => {
  const value = route.query.tag;
  if (Array.isArray(value)) return value.filter((tag): tag is string => !!tag);
  return value
    ? [value]
    : String(route.query.tags || "")
        .split(",")
        .filter(Boolean);
});
const query = computed(() => ({
  q: String(route.query.q || "").slice(0, 100),
  type: String(route.query.type || route.query.kind || ""),
  price: String(route.query.price || ""),
  tag: tags.value,
  includeTags: false,
  page: page.value,
  pageSize: size.value,
}));
const draft = reactive({ q: "", type: "", price: "" });
watch(
  query,
  (value) =>
    Object.assign(draft, { q: value.q, type: value.type, price: value.price }),
  { immediate: true },
);
const key = computed(
  () => "directory:" + props.endpoint + ":" + JSON.stringify(query.value),
);
const { data, status, error, refresh } = await useAsyncData(key, () =>
  request<DirectoryPage>(props.endpoint, { query: query.value }),
);
const types = computed(() => {
  const apps = {
    TOOL: t("工具", "Tool"),
    SOFTWARE: t("软件", "Software"),
    RESOURCE: t("资源", "Resource"),
    DOCUMENT: t("文档", "Document"),
  };
  const articles = {
    ARTICLE: t("文章", "Article"),
    TUTORIAL: t("教程", "Tutorial"),
    FAQ: "FAQ",
    CASE: t("案例", "Case study"),
  };
  if (props.kind === "API" || props.kind === "SERIES") return {};
  if (props.kind === "ARTICLES") return articles;
  if (props.kind === "APPLICATIONS")
    return { ...apps, API: t("API 服务", "API service") };
  return { ...apps, ...articles, API: t("API 服务", "API service") };
});
function change(patch: Record<string, string | string[] | number | undefined>) {
  return navigateTo({ path: route.path, query: { ...route.query, ...patch } });
}
function search() {
  return change({
    q: draft.q.trim() || undefined,
    type: draft.type || undefined,
    price: draft.price || undefined,
    kind: undefined,
    page: undefined,
  });
}
function changePage(current: number, pageSize: number) {
  if (pageSize !== size.value)
    return change({
      pageSize: pageSize === 100 ? undefined : pageSize,
      page: undefined,
    });
  if (current !== data.value?.page)
    return change({ page: current === 1 ? undefined : current });
}
function selectTags(values: string[]) {
  return change({
    tag: values.length ? values : undefined,
    tags: undefined,
    page: undefined,
  });
}
</script>

<template>
  <main class="directory-width catalog-list-page">
    <nav class="catalog-breadcrumb" :aria-label="t('当前位置', 'Breadcrumb')">
      <NuxtLink to="/">{{ t("首页", "Home") }}</NuxtLink
      ><SiteIcon name="chevron" /><span>{{ title }}</span>
    </nav>
    <div class="catalog-page-heading">
      <div>
        <h1>{{ title }}</h1>
        <p v-if="subtitle">{{ subtitle }}</p>
      </div>
    </div>
    <el-form class="directory-filters" inline @submit.prevent="search">
      <el-form-item :label="t('关键词', 'Keyword')">
        <el-input
          v-model="draft.q"
          :aria-label="t('目录关键词', 'Directory keyword')"
          :placeholder="
            kind === 'SERIES'
              ? t('专栏名称或简介', 'Series title or description')
              : undefined
          "
          maxlength="100"
          clearable
        />
      </el-form-item>
      <el-form-item v-if="Object.keys(types).length" :label="t('类型', 'Type')">
        <el-select
          v-model="draft.type"
          :aria-label="t('内容类型', 'Content type')"
          clearable
          :placeholder="t('全部', 'All')"
        >
          <el-option
            v-for="(label, value) in types"
            :key="value"
            :value="value"
            :label="label"
          />
        </el-select>
      </el-form-item>
      <el-form-item
        v-if="kind !== 'ARTICLES' && kind !== 'SERIES'"
        :label="t('费用', 'Price')"
      >
        <el-select
          v-model="draft.price"
          :aria-label="t('费用筛选', 'Price filter')"
          clearable
          :placeholder="t('全部', 'All')"
        >
          <el-option value="FREE" :label="t('免费', 'Free')" />
          <el-option
            value="PAID"
            :label="t('需鼓励值', 'Encouragement required')"
          />
        </el-select>
      </el-form-item>
      <el-form-item
        v-if="kind !== 'ARTICLES' && kind !== 'API' && kind !== 'SERIES'"
        :label="t('标签', 'Tags')"
      >
        <PagedPicker
          :model-value="tags"
          :title="t('标签筛选', 'Filter tags')"
          :endpoint="endpoint + '/tags'"
          value-key="name"
          detail-key=""
          :max="20"
          clearable
          @update:model-value="selectTags"
        />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" native-type="submit">{{
          t("搜索", "Search")
        }}</el-button>
        <el-button
          @click="
            change({
              q: undefined,
              type: undefined,
              kind: undefined,
              price: undefined,
              tag: undefined,
              tags: undefined,
              page: undefined,
            })
          "
          >{{ t("重置", "Reset") }}</el-button
        >
      </el-form-item>
    </el-form>
    <p v-if="status === 'pending'" class="catalog-empty" role="status">
      {{ t("加载中…", "Loading…") }}
    </p>
    <div v-else-if="error" class="catalog-empty" role="alert">
      <p>
        {{ t("目录无法加载或栏目已停用。", "This directory is unavailable.") }}
      </p>
      <el-button @click="refresh()">{{ t("重试", "Retry") }}</el-button>
    </div>
    <template v-else>
      <div v-if="data?.items.length" class="directory-card-grid content-grid">
        <DirectoryCard
          v-for="item in data.items"
          :key="item.ref"
          :item="item"
        />
      </div>
      <p v-else class="catalog-empty">
        {{ t("没有匹配的已发布内容。", "No matching published content.") }}
      </p>
      <el-pagination
        class="directory-pager"
        :current-page="data?.page || 1"
        :page-size="size"
        :total="data?.total || 0"
        :page-sizes="sizes"
        layout="total,sizes,prev,pager,next"
        @update:current-page="(value) => changePage(value, size)"
        @update:page-size="(value) => changePage(data?.page || 1, value)"
      />
    </template>
  </main>
</template>

<style scoped>
.directory-filters {
  margin: 0 0 14px;
}
.directory-filters .el-input {
  width: min(250px, 64vw);
}
.directory-filters .el-select {
  width: min(160px, 56vw);
}
.directory-pager {
  flex-wrap: wrap;
  height: auto;
  margin: 22px 0;
}
</style>
