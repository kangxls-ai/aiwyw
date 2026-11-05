<script setup lang="ts">
import type { CatalogProduct } from "~/types/catalog";
defineProps<{ product: CatalogProduct }>();
</script>
<template>
  <span class="directory-app-logo catalog-logo"
    ><img
      v-if="product.content.iconUrl"
      :src="product.content.iconUrl"
      alt=""
      loading="lazy"
    /><span v-else>{{ product.content.nameZh.slice(0, 1) }}</span></span
  >
</template>
