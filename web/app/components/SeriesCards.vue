<script setup lang="ts">
import type { SeriesCard } from "~/types/series";
defineProps<{ cards: SeriesCard[] }>();
const { t } = useLanguage();
</script>
<template>
  <ul class="series-cards">
    <li v-for="card in cards" :key="card.slug">
      <NuxtLink :to="'/series/' + card.slug">
        <strong>{{ t(card.titleZh, card.titleEn || card.titleZh) }}</strong>
        <span>{{ t(card.summaryZh, card.summaryEn || card.summaryZh) }}</span>
      </NuxtLink>
    </li>
  </ul>
</template>
