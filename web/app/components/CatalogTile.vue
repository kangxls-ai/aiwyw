<script setup lang="ts">
import {businessTime} from "~/utils/commerce";
import type { CatalogProduct } from "~/types/catalog";
defineProps<{ product: CatalogProduct; list?: boolean }>();
const { t } = useLanguage(),
  { download, busy } = useCatalog();
const count = (n: number) =>
  n >= 10000 ? `${(n / 10000).toFixed(1)}万` : n.toLocaleString("en-US");
const date = businessTime;
</script>
<template>
  <article v-if="!list" class="home-application-tile">
    <div class="home-tile-main">
      <a
        class="home-tile-information"
        :href="`/apps/${product.slug}`"
        target="_blank"
        rel="noopener noreferrer"
        ><CatalogLogo :product="product" /><span class="directory-app-name">{{
          t(
            product.content.nameZh,
            product.content.nameEn || product.content.nameZh,
          )
        }}</span></a
      >
      <button
        class="home-tile-download"
        :aria-label="`${t('下载', 'Download')} ${product.content.nameZh}`"
        :disabled="busy"
        @click="download(product)"
      >
        <SiteIcon name="download" /><span>{{ t("下载", "Download") }}</span>
      </button>
    </div>
    <div class="home-tile-footer">
      <span :title="t('浏览数', 'Views')"
        ><SiteIcon name="eye" />{{ count(product.views) }}</span
      ><span :title="t('下载数', 'Downloads')"
        ><SiteIcon name="download" />{{ count(product.downloads) }}</span
      ><a
        :href="`/apps/${product.slug}`"
        target="_blank"
        rel="noopener noreferrer"
        >{{ t("查看详情", "Details") }}<SiteIcon name="chevron"
      /></a>
    </div>
  </article>
  <article v-else class="catalog-app-card">
    <button
      class="catalog-card-hit"
      :aria-label="`${t('下载', 'Download')} ${product.content.nameZh}`"
      :disabled="busy"
      @click="download(product)"
    />
    <div class="catalog-card-identity">
      <CatalogLogo :product="product" />
      <h2>
        {{
          t(
            product.content.nameZh,
            product.content.nameEn || product.content.nameZh,
          )
        }}
      </h2>
    </div>
    <div class="catalog-card-information">
      <div class="catalog-card-tags">
        <span v-for="tag in product.content.tags" :key="tag">{{ tag }}</span>
      </div>
      <p class="catalog-card-description">
        {{
          t(
            product.content.summaryZh,
            product.content.summaryEn || product.content.summaryZh,
          )
        }}
      </p>
      <div class="catalog-card-metadata">
        <span
          ><SiteIcon name="calendar" />{{
            date(product.firstPublishedAt)
          }}</span
        ><span><SiteIcon name="download" />{{ count(product.downloads) }}</span
        ><span><SiteIcon name="eye" />{{ count(product.views) }}</span>
      </div>
      <div class="catalog-card-bottom">
        <span
          class="catalog-price"
          :class="{ free: Number(product.content.pricePoints) === 0 }"
          >{{
            Number(product.content.pricePoints) === 0
              ? t("免费", "Free")
              : `${product.content.pricePoints} ${t("鼓励值", "encouragement")}`
          }}</span
        ><a
          class="catalog-details-link"
          :href="`/apps/${product.slug}`"
          target="_blank"
          rel="noopener noreferrer"
          @click.stop
          >{{ t("查看详情", "Details") }}<SiteIcon name="chevron"
        /></a>
      </div>
    </div>
  </article>
</template>
