<script setup lang="ts">
import type { DirectoryItem } from "~/types/catalog";
import { decimalUnits } from "~/utils/commerce";
const props = defineProps<{ item: DirectoryItem }>();
const { t, zh } = useLanguage();
const { download, busy } = useCatalog();
const product = computed(() => props.item.product);
const isApi = computed(
  () =>
    props.item.kind === "API" ||
    product.value?.content.deliveryMode === "API" ||
    product.value?.content.type === "API",
);
const title = computed(() =>
  t(props.item.name, props.item.nameEn || props.item.name),
);
const summary = computed(() => {
  const c = product.value?.content;
  return c
    ? t(c.summaryZh, c.summaryEn || c.summaryZh)
    : t(
        props.item.summary || "",
        props.item.summaryEn || props.item.summary || "",
      );
});
const category = computed(() => {
  if (isApi.value) return t("API 服务", "API service");
  if (product.value) return t("应用工具", "Application");
  return (
    (
      {
        ARTICLE: t("文章", "Article"),
        TUTORIAL: t("教程", "Tutorial"),
        FAQ: "FAQ",
        CASE: t("案例", "Case study"),
      } as Record<string, string>
    )[props.item.kind] || t("内容", "Content")
  );
});
// 现有发布资料只有简介字段，卡片头部取其首个短句，完整简介在中部显示。
const slogan = computed(
  () =>
    summary.value.split(/[。！？!?；;\n]|\.(?:\s|$)/)[0]?.trim() ||
    category.value,
);
const href = computed(() => {
  const path = props.item.href || "/apps/" + product.value?.slug;
  return zh.value || path.startsWith("/api-services/")
    ? path
    : path + "?lang=en";
});
const debugUrl = computed(() => ({
  path: "/api-services",
  query: { api: props.item.slug || product.value?.slug, debug: "1" },
}));
const views = computed(() => product.value?.views ?? props.item.views);
const count = (n: number) =>
  new Intl.NumberFormat(zh.value ? "zh-CN" : "en-US", {
    notation: "compact",
  }).format(n);
const price = computed(() => {
  const amount = product.value?.content.pricePoints;
  if (amount !== undefined)
    return amount > 0
      ? amount + " " + t("鼓励值", "encouragement")
      : t("免费", "Free");
  const units = Number(props.item.priceUnits);
  return units > 0
    ? decimalUnits(props.item.priceUnits) +
        " " +
        t("鼓励值/次", "encouragement / call")
    : "";
});
</script>

<template>
  <article class="directory-entry-card">
    <header class="entry-head">
      <div class="entry-icon" aria-hidden="true">
        <CatalogLogo v-if="product" :product="product" />
        <span v-else class="entry-kind-icon"
          ><SiteIcon :name="isApi ? 'convert' : 'document'"
        /></span>
      </div>
      <div class="entry-heading">
        <h3>
          <a
            :href="href"
            target="_blank"
            rel="noopener noreferrer"
            :title="title"
            >{{ title }}</a
          >
        </h3>
        <p :title="slogan">{{ slogan }}</p>
      </div>
    </header>
    <div class="entry-body">
      <p :title="summary">{{ summary || category }}</p>
      <div class="entry-labels">
        <span>{{ category }}</span>
        <span v-if="price">{{ price }}</span>
        <span v-for="tag in product?.content.tags.slice(0, 2)" :key="tag">{{
          tag
        }}</span>
      </div>
    </div>
    <footer class="entry-foot">
      <div class="entry-stats">
        <span v-if="views !== undefined" :title="t('浏览数', 'Views')"
          ><SiteIcon name="eye" />{{ count(views) }}</span
        >
        <span v-if="product && !isApi" :title="t('下载数', 'Downloads')"
          ><SiteIcon name="download" />{{ count(product.downloads) }}</span
        >
      </div>
      <div class="entry-actions">
        <button
          v-if="product && !isApi"
          type="button"
          :disabled="busy"
          :aria-label="t('下载', 'Download') + ' ' + title"
          @click="download(product)"
        >
          {{ t("下载", "Download") }}
        </button>
        <NuxtLink v-if="isApi" class="entry-primary" :to="debugUrl">{{
          t("调试", "Debug")
        }}</NuxtLink>
        <a :href="href" target="_blank" rel="noopener noreferrer">{{
          product || isApi ? t("查看详情", "Details") : t("阅读", "Read")
        }}</a>
      </div>
    </footer>
  </article>
</template>

<style scoped>
.directory-entry-card {
  display: grid;
  grid-template-rows: 100px 100px 50px;
  width: min(460px, 100%);
  height: 250px;
  min-width: 0;
  padding: 0;
  overflow: hidden;
  outline: 1px solid #e9e5f2;
  outline-offset: -1px;
  border-radius: 14px;
  background: #fff;
  box-shadow: 0 4px 16px #29204308;
}
.directory-entry-card:hover {
  outline-color: #bdafe5;
}
.entry-head {
  display: grid;
  grid-template-columns: 1fr 2fr;
  align-items: center;
  min-width: 0;
  padding: 0 18px 0 0;
}
.entry-icon {
  display: grid;
  place-items: center;
}
.entry-icon :deep(.directory-app-logo),
.entry-kind-icon {
  width: 64px;
  height: 64px;
  margin: 0;
  border-radius: 17px;
  display: grid;
  place-items: center;
}
.entry-kind-icon {
  background: #f0ebff;
  color: #765cbc;
}
.entry-kind-icon svg {
  width: 31px;
  height: 31px;
}
.entry-heading {
  min-width: 0;
}
.entry-heading h3,
.entry-heading p {
  margin: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.entry-heading h3 {
  font-size: 18px;
  line-height: 1.45;
}
.entry-heading h3 a {
  color: #242139;
}
.entry-heading p {
  margin-top: 7px;
  font-size: 13px;
  color: #6f6287;
}
.entry-body {
  padding: 10px 16px;
  min-width: 0;
  border-top: 1px solid #f0edf5;
}
.entry-body p {
  margin: 0;
  color: #49516a;
  font-size: 14px;
  line-height: 20px;
  overflow-wrap: anywhere;
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  overflow: hidden;
}
.entry-labels {
  display: flex;
  gap: 6px;
  margin-top: 8px;
  overflow: hidden;
  white-space: nowrap;
}
.entry-labels span {
  flex: 0 0 auto;
  max-width: 130px;
  overflow: hidden;
  text-overflow: ellipsis;
  padding: 2px 7px;
  border-radius: 5px;
  background: #f4f0fc;
  color: #665295;
  font-size: 12px;
}
.entry-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 0 13px;
  border-top: 1px solid #f0edf5;
  font-size: 13px;
}
.entry-stats,
.entry-actions,
.entry-stats span {
  display: flex;
  align-items: center;
  gap: 7px;
  white-space: nowrap;
}
.entry-stats {
  color: #697189;
}
.entry-stats svg {
  width: 15px;
  height: 15px;
}
.entry-actions button,
.entry-actions a {
  display: inline-flex;
  align-items: center;
  min-height: 30px;
  padding: 0 8px;
  border-radius: 6px;
  color: #60449c;
  background: #f3eefc;
  font-size: 13px;
  text-decoration: none;
}
.entry-actions button,
.entry-actions .entry-primary {
  background: #7456bb;
  color: #fff;
  border: 0;
}
.entry-actions button:disabled {
  opacity: 0.55;
}
@media (max-width: 420px) {
  .entry-foot {
    gap: 4px;
    padding-inline: 8px;
  }
  .entry-stats,
  .entry-actions,
  .entry-stats span {
    gap: 4px;
  }
  .entry-actions button,
  .entry-actions a {
    font-size: 12px;
    padding-inline: 6px;
  }
}
</style>
