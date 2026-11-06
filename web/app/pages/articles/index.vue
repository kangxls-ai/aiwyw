<script setup lang="ts">
definePageMeta({ layout: "catalog" });
import "~/assets/element-admin.css";
const { t, zh } = useLanguage(),
  { request, message } = useApi(),
  q = ref(""),
  kind = ref(""),
  page = ref(1),
  data = ref<any>({ items: [], total: 0 }),
  error = ref("");
const { catalog } = useCatalog();
const articleColumn = computed(() =>
  catalog.value.sections.find((s) => s.kind === "ARTICLES"),
);
const columnTitle = computed(() =>
  articleColumn.value
    ? t(
        articleColumn.value.titleZh,
        articleColumn.value.titleEn || articleColumn.value.titleZh,
      )
    : t("文章与教程", "Articles and tutorials"),
);
async function load() {
  try {
    data.value = await request("/api/public/content", {
      query: { q: q.value, kind: kind.value, page: page.value, pageSize: 12 },
    });
    page.value = data.value.page;
    if (import.meta.client && q.value.trim()) {
      let source = "";
      try {
        source = new URL(document.referrer).hostname;
      } catch {}
      request("/api/public/events", {
        method: "POST",
        body: {
          kind: "SEARCH",
          path: "/articles",
          keyword: q.value.trim(),
          source,
        },
      }).catch(() => {});
    }
  } catch (e) {
    error.value = message(e);
  }
}
await load();
useHead(() => ({
  title: `${columnTitle.value} · AI万有网`,
  meta: [
    {
      name: "description",
      content: t(
        "应用教程、常见问题和使用案例。",
        "Tutorials, FAQs and application use cases.",
      ),
    },
  ],
}));
</script>
<template>
  <main class="directory-width content-browse">
    <h1>{{ columnTitle }}</h1>
    <p
      v-if="articleColumn?.subtitleZh || articleColumn?.subtitleEn"
      class="admin-muted"
    >
      {{
        t(
          articleColumn.subtitleZh,
          articleColumn.subtitleEn || articleColumn.subtitleZh,
        )
      }}
    </p>
    <el-alert
      v-if="error"
      :title="error"
      type="error"
      :closable="false"
    /><el-form
      inline
      @submit.prevent="
        page = 1;
        load();
      "
      ><el-form-item :label="t('标题', 'Title')"
        ><el-input v-model="q" maxlength="100" clearable /></el-form-item
      ><el-form-item :label="t('分类', 'Category')"
        ><el-select
          v-model="kind"
          clearable
          style="width: 170px"
          :placeholder="t('全部', 'All')"
          ><el-option
            v-for="(label, key) in {
              ARTICLE: t('文章', 'Articles'),
              TUTORIAL: t('教程', 'Tutorials'),
              FAQ: 'FAQ',
              CASE: t('案例', 'Cases'),
            }"
            :key="key"
            :label="label"
            :value="key" /></el-select></el-form-item
      ><el-button
        type="primary"
        @click="
          page = 1;
          load();
        "
        >{{ t("搜索", "Search") }}</el-button
      ></el-form
    ><el-empty
      v-if="!data.items.length"
      :description="t('暂无已发布内容', 'No published content yet')"
    />
    <div class="content-grid">
      <!-- 静态详情使用完整导航，由服务器直接返回已发布HTML。 -->
      <a
        v-for="item in data.items"
        :key="item.id"
        :href="'/articles/' + item.slug + (zh ? '' : '?lang=en')"
        target="_blank"
        rel="noopener noreferrer"
        ><el-card shadow="hover"
          ><h2>{{ zh ? item.title_zh : item.title_en || item.title_zh }}</h2>
          <p>{{ zh ? item.summary_zh : item.summary_en || item.summary_zh }}</p>
          <time>{{
            new Date(Number(item.created_at)).toLocaleDateString(
              zh ? "zh-CN" : "en",
              { timeZone: "Asia/Shanghai" },
            )
          }}</time></el-card
        ></a
      >
    </div>
    <el-pagination
      v-model:current-page="page"
      :page-size="12"
      :total="data.total"
      layout="total,prev,pager,next"
      @current-change="load"
    />
  </main>
</template>
<style scoped>
.content-browse {
  padding-top: 32px;
  padding-bottom: 40px;
}
.content-browse h1 {
  margin-bottom: 24px;
}
.content-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 20px;
  margin: 24px 0;
}
.content-grid h2 {
  font-size: 19px;
}
.content-grid p {
  margin: 14px 0;
  color: #7e859a;
}
.content-grid time {
  font-size: 12px;
  color: #989fb0;
}
@media (max-width: 850px) {
  .content-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
@media (max-width: 600px) {
  .content-grid {
    grid-template-columns: 1fr;
  }
}
</style>
