<script setup lang="ts">
/** 组合条件发送到服务端后再分页；页签切换保留当前筛选，不在客户端截取全部应用。 */
import { adminDateShortcuts } from "~/utils/commerce";
import type { AdminSection } from "~/types/catalog";
interface Row {
  id: string;
  slug: string;
  nameZh: string;
  nameEn: string;
  iconUrl: string;
  version: string;
  pricePoints: number;
  tags: string[];
  status: string;
  revision: number;
  createdAt: number;
  lastPublishedAt: number | null;
}
const props = defineProps<{ view: string }>(),
  { request, message } = useApi(),
  { openEditor, tabs, dirty } = useManagementTabs();
const q = ref(""),
  status = ref(""),
  dateRange = ref<[string, string] | null>(null),
  section = ref(""),
  selectedTags = ref<string[]>([]),
  page = ref(1),
  size = ref(20);
const data = ref<{
    items: Row[];
    total: number;
    page: number;
    pageSize: number;
  }>({ items: [], total: 0, page: 1, pageSize: 20 }),
  facets = ref<{ tags: string[]; sections: AdminSection[] }>({
    tags: [],
    sections: [],
  });
const error = ref(""),
  notice = ref(""),
  busy = ref(false),
  changed = useState("catalog-changed", () => 0);
const dateShortcuts = adminDateShortcuts();
const title = computed(() =>
  props.view === "publishing"
    ? "发布与静态化"
    : props.view === "versions"
      ? "版本与文件"
      : props.view === "tags"
        ? "标签管理"
        : "应用管理",
);
const listOrder = useListOrder(load, page, "createdAt");
let loadSequence = 0;
async function load() {
  const sequence = ++loadSequence;
  busy.value = true;
  error.value = "";
  try {
    const nextData = await request("/api/admin/catalog/products/page", {
      query: {
        sort: listOrder.sort.value,
        direction: listOrder.direction.value,
        q: q.value,
        status: status.value,
        from: dateRange.value?.[0] || "",
        until: dateRange.value?.[1] || "",
        section: section.value,
        tag: selectedTags.value,
        page: page.value,
        pageSize: size.value,
      },
    });
    if (sequence !== loadSequence) return;
    data.value = nextData;
    page.value = data.value.page;
    facets.value = await request("/api/admin/catalog/products/facets");
  } catch (e) {
    if (sequence !== loadSequence) return;
    error.value = message(e);
  } finally {
    if (sequence === loadSequence) busy.value = false;
  }
}
function search() {
  page.value = 1;
  load();
}
function reset() {
  q.value = "";
  status.value = "";
  dateRange.value = null;
  section.value = "";
  selectedTags.value = [];
  search();
}
async function action(row: Row, verb: "publish" | "unlist") {
  if (
    verb === "publish" &&
    tabs.value.some((t) => t.productId === row.id && dirty.value[t.id])
  ) {
    await openEditor(row as Row);
    return;
  }
  if (
    verb === "unlist" &&
    !window.confirm(`下架“${row.nameZh}”？保留详情和历史交付资料。`)
  )
    return;
  busy.value = true;
  notice.value = "";
  error.value = "";
  try {
    await request(`/api/admin/catalog/products/${row.id}/${verb}`, {
      method: "POST",
      body: { revision: row.revision },
    });
    changed.value++;
    await load();
    notice.value =
      verb === "publish" ? "发布成功，静态详情已生成并生效" : "应用已下架";
  } catch (e) {
    error.value = message(e);
  } finally {
    busy.value = false;
  }
}
const time = (n: number | null) =>
  n
    ? new Intl.DateTimeFormat("zh-CN", {
        timeZone: "Asia/Shanghai",
        year: "numeric",
        month: "2-digit",
        day: "2-digit",
        hour: "2-digit",
        minute: "2-digit",
      }).format(n)
    : "未发布";
const route = useRoute();
watch(
  () => route.query.tag,
  (tag) => {
    if (route.query.view === "applications" && typeof tag === "string") {
      selectedTags.value = [tag];
      search();
    }
  },
  { immediate: true },
);
onMounted(load);
watch(changed, () => load());
</script>
<template>
  <section>
    <div class="management-panel-heading">
      <div>
        <span>APPLICATION MANAGEMENT</span>
        <h1>{{ title }}</h1>
        <p>管理应用、版本及公开发布。</p>
      </div>
      <el-button type="primary" @click="openEditor()">新增应用</el-button>
    </div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <p v-if="notice" class="alert success" role="status">{{ notice }}</p>
    <el-card shadow="never"
      ><el-form
        label-position="right"
        label-width="128px"
        class="admin-filter-grid"
        @submit.prevent="search"
      >
        <el-form-item label="名称或关键字"
          ><el-input
            v-model="q"
            aria-label="名称或关键字"
            maxlength="100"
            clearable
            placeholder="应用名称、地址或摘要"
            @keyup.enter="search"
        /></el-form-item>
        <el-form-item label="上下架状态"
          ><el-select
            v-model="status"
            placeholder="全部状态"
            aria-label="上下架状态"
            clearable
            ><el-option label="上架" value="PUBLISHED" /><el-option
              label="下架"
              value="UNLISTED" /><el-option
              label="草稿"
              value="DRAFT" /></el-select
        ></el-form-item>
        <el-form-item label="所在栏目"
          ><el-select
            v-model="section"
            placeholder="全部栏目"
            aria-label="所在栏目"
            filterable
            clearable
            ><el-option
              v-for="item in facets.sections.filter(
                (s) => s.config.mode !== 'ARTICLES',
              )"
              :key="item.id"
              :label="
                item.config.titleZh + (item.config.enabled ? '' : '（停用）')
              "
              :value="item.id" /></el-select
        ></el-form-item>
        <el-form-item label="发布时间范围" class="wide"
          ><el-date-picker
            v-model="dateRange"
            type="daterange"
            range-separator="至"
            start-placeholder="开始日期"
            end-placeholder="结束日期"
            value-format="YYYY-MM-DD"
            format="YYYY-MM-DD"
            :shortcuts="dateShortcuts"
            clearable
            unlink-panels
        /></el-form-item>
        <el-form-item label="标签（全部匹配）"
          ><el-select
            v-model="selectedTags"
            placeholder="全部标签"
            aria-label="标签筛选"
            multiple
            filterable
            clearable
            collapse-tags
            collapse-tags-tooltip
            ><el-option
              v-for="tag in facets.tags"
              :key="tag"
              :label="tag"
              :value="tag" /></el-select
        ></el-form-item>
        <div class="full">
          <el-button type="primary" :loading="busy" @click="search"
            >查询</el-button
          >
          <el-button @click="reset">重置</el-button>
          <p class="admin-muted">北京时间最近一次发布；多个标签全部匹配。</p>
        </div>
      </el-form></el-card
    >
    <el-card shadow="never" style="margin-top: 20px"
      ><el-table
        :default-sort="listOrder.defaultSort"
        @sort-change="listOrder.change"
        :data="data.items"
        v-loading="busy"
        row-key="id"
        empty-text="没有符合条件的应用"
      >
        <ObjectColumns time-prop="createdAt" server-sort /><el-table-column
          prop="nameZh"
          sortable="custom"
          :sort-orders="['descending', 'ascending']"
          label="应用"
          min-width="200"
          ><template #default="{ row }"
            ><div class="application-table-name">
              <el-avatar
                v-if="row.iconUrl"
                shape="square"
                :src="row.iconUrl"
                :size="38"
              /><span
                ><strong>{{ row.nameZh }}</strong
                ><small>{{ row.slug }}</small></span
              >
            </div></template
          ></el-table-column
        >
        <el-table-column
          prop="version"
          label="版本"
          sortable="custom"
          :sort-orders="['descending', 'ascending']"
          min-width="90"
        />
        <el-table-column
          prop="pricePoints"
          label="积分"
          sortable="custom"
          :sort-orders="['descending', 'ascending']"
          min-width="90"
        />

        <el-table-column
          prop="tags"
          sortable="custom"
          :sort-orders="['descending', 'ascending']"
          label="标签"
          min-width="150"
          ><template #default="{ row }"
            ><el-tag
              v-for="tag in row.tags"
              :key="tag"
              size="small"
              style="margin: 2px"
              >{{ tag }}</el-tag
            ></template
          ></el-table-column
        >
        <el-table-column
          prop="status"
          sortable="custom"
          :sort-orders="['descending', 'ascending']"
          label="状态"
          width="90"
          ><template #default="{ row }"
            ><el-tag :type="row.status === 'PUBLISHED' ? 'success' : 'info'">{{
              { DRAFT: "草稿", PUBLISHED: "上架", UNLISTED: "下架" }[
                row.status as "DRAFT" | "PUBLISHED" | "UNLISTED"
              ]
            }}</el-tag></template
          ></el-table-column
        >
        <el-table-column
          prop="lastPublishedAt"
          sortable="custom"
          :sort-orders="['descending', 'ascending']"
          label="最近发布时间"
          min-width="155"
          ><template #default="{ row }">{{
            time(row.lastPublishedAt)
          }}</template></el-table-column
        >
        <el-table-column
          align="center"
          header-align="center"
          label="操作"
          min-width="210"
          fixed="right"
          ><template #default="{ row }"
            ><div class="admin-table-actions">
              <el-button link type="primary" @click="openEditor(row as Row)"
                >编辑</el-button
              ><el-button
                link
                type="primary"
                :disabled="busy"
                @click="action(row as Row, 'publish')"
                >发布</el-button
              ><el-button
                v-if="row.status === 'PUBLISHED'"
                link
                type="warning"
                :disabled="busy"
                @click="action(row as Row, 'unlist')"
                >下架</el-button
              ><a
                v-if="row.status !== 'DRAFT'"
                :href="`/apps/${row.slug}`"
                target="_blank"
                rel="noopener"
                >详情</a
              >
            </div></template
          ></el-table-column
        > </el-table
      ><el-pagination
        v-model:current-page="page"
        v-model:page-size="size"
        :page-sizes="[10, 20, 50]"
        :total="data.total"
        layout="total, sizes, prev, pager, next, jumper"
        @size-change="search"
        @current-change="load"
    /></el-card>
  </section>
</template>
