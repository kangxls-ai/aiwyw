<script setup lang="ts">
/** 置顶独立保存，不受栏目基本资料或选品草稿的保存影响。 */
interface Row {
  ref: string;
  name: string;
  kind: string;
  pinned: boolean;
}
interface Result {
  items: Row[];
  page: number;
  total: number;
}
const target = defineModel<{ endpoint: string; title: string } | null>({
  required: true,
});
const { request, message } = useApi();
const changed = useState("catalog-changed", () => 0);
const q = ref(""),
  page = ref(1),
  busy = ref(false),
  saving = ref(false),
  error = ref("");
const result = ref<Result>({ items: [], page: 1, total: 0 });
const size = 40;
let generation = 0;

async function load() {
  const endpoint = target.value?.endpoint;
  if (!endpoint) return;
  const token = ++generation;
  busy.value = true;
  error.value = "";
  try {
    const value = await request<Result>(endpoint, {
      query: { q: q.value, page: page.value, pageSize: size },
    });
    if (token !== generation) return;
    result.value = value;
    page.value = value.page;
  } catch (e) {
    if (token === generation) error.value = message(e);
  } finally {
    if (token === generation) busy.value = false;
  }
}
watch(target, (value) => {
  generation++;
  if (!value) return;
  q.value = "";
  page.value = 1;
  result.value = { items: [], page: 1, total: 0 };
  load();
});
function search() {
  page.value = 1;
  load();
}
async function change(row: Row, pinned: boolean) {
  const endpoint = target.value?.endpoint;
  if (!endpoint || saving.value) return;
  saving.value = true;
  error.value = "";
  try {
    await request(endpoint, { method: "PUT", body: { ref: row.ref, pinned } });
    changed.value++;
    if (target.value?.endpoint === endpoint) {
      if (pinned) page.value = 1;
      await load();
    }
  } catch (e) {
    if (target.value?.endpoint === endpoint) error.value = message(e);
  } finally {
    saving.value = false;
  }
}
const kinds: Record<string, string> = {
  PRODUCT: "应用",
  ARTICLE: "文章",
  TUTORIAL: "教程",
  FAQ: "常见问题",
  CASE: "案例",
  API: "API",
  SERIES: "专栏",
};
</script>
<template>
  <el-dialog
    :model-value="!!target"
    :title="target?.title"
    width="min(760px, 96vw)"
    append-to-body
    @update:model-value="
      (open) => {
        if (!open) target = null;
      }
    "
  >
    <p class="admin-muted">
      置顶只影响当前栏目，最新置顶排在最前。可随时取消置顶。
    </p>
    <el-form inline @submit.prevent="search">
      <el-form-item label="关键词">
        <el-input
          v-model="q"
          aria-label="置顶内容搜索"
          clearable
          maxlength="100"
        />
      </el-form-item>
      <el-button native-type="submit" type="primary" :disabled="saving"
        >搜索</el-button
      >
    </el-form>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-table
      :data="result.items"
      v-loading="busy"
      row-key="ref"
      max-height="440"
      empty-text="暂无符合条件的已发布内容"
    >
      <el-table-column
        prop="name"
        label="名称"
        min-width="190"
        show-overflow-tooltip
      />
      <el-table-column label="类型" width="90">
        <template #default="{ row }">{{
          kinds[row.kind] || row.kind
        }}</template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }"
          ><el-tag v-if="row.pinned">已置顶</el-tag></template
        >
      </el-table-column>
      <el-table-column label="操作" width="165">
        <template #default="{ row }">
          <el-button
            link
            type="primary"
            :disabled="busy || saving"
            @click="change(row as Row, true)"
          >
            {{ row.pinned ? "移到最前" : "置顶" }}
          </el-button>
          <el-button
            v-if="row.pinned"
            link
            :disabled="busy || saving"
            @click="change(row as Row, false)"
          >
            取消置顶
          </el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination
      :current-page="page"
      :page-size="size"
      :total="result.total"
      layout="total,prev,pager,next"
      @update:current-page="
        (value) => {
          page = value;
          load();
        }
      "
    />
  </el-dialog>
</template>
