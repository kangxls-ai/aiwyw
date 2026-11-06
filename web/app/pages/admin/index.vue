<script setup lang="ts">
import type { CatalogProduct, AdminSection } from "~/types/catalog";
import "~/assets/catalog.css";
defineOptions({ name: "AdminDashboard" });
definePageMeta({ layout: "admin" });
const { request, message } = useApi();
const products = ref<CatalogProduct[]>([]),
  sections = ref<AdminSection[]>([]),
  overview = ref<any>(null),
  error = ref("");
const user = useState<any>("admin-user", () => null);
onMounted(() =>
  watch(
    () => user.value?.menus,
    async () => {
      try {
        [products.value, sections.value, overview.value] = await Promise.all([
          user.value?.menus?.some((m: string) =>
            ["applications", "versions", "publishing"].includes(m),
          )
            ? request("/api/admin/catalog/products")
            : Promise.resolve([]),
          user.value?.menus?.includes("sections")
            ? request("/api/admin/catalog/sections")
            : Promise.resolve([]),
          request("/api/admin/overview"),
        ]);
      } catch (e) {
        error.value = message(e);
      }
    },
    { immediate: true },
  ),
);
</script>
<template>
  <div>
    <div class="page-heading">
      <div>
        <div class="eyebrow">WORKSPACE</div>
        <h1>工作台</h1>
        <p class="muted">管理应用、内容与站点运营。</p>
      </div>
      <NuxtLink class="button primary" to="/admin/workspace?view=applications"
        >应用管理 →</NuxtLink
      >
    </div>
    <p v-if="overview" class="admin-muted">
      当前数据库：{{ overview.databaseEngine }} · {{ overview.databaseName }}
    </p>
    <div v-if="error" class="alert error">{{ error }}</div>
    <div class="catalog-stat-grid">
      <article>
        <span>应用数量</span><strong>{{ products.length }}</strong
        ><span>含草稿及下架应用</span>
      </article>
      <article>
        <span>已发布应用</span
        ><strong>{{
          products.filter((p) => p.status === "PUBLISHED").length
        }}</strong
        ><span>公开目录当前可见</span>
      </article>
      <article>
        <span>首页栏目</span
        ><strong
          >{{ sections.filter((s) => s.config.mode !== "ARTICLES").length }} /
          6</strong
        ><span>应用展示区，文章导航独立维护</span>
      </article>
    </div>
    <div class="catalog-editor-card">
      <h2>常用入口</h2>
      <div class="catalog-editor-actions">
        <NuxtLink class="button secondary" to="/admin/workspace?view=sections"
          >首页栏目</NuxtLink
        ><NuxtLink class="button secondary" to="/admin/settings"
          >配置管理</NuxtLink
        ><NuxtLink class="button secondary" to="/admin/audit"
          >操作记录</NuxtLink
        >
      </div>
    </div>
  </div>
</template>
