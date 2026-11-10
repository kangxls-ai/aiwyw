<script setup lang="ts">
import type { SeriesCard } from "~/types/series";
interface Row extends SeriesCard {
  id: string;
  published: boolean;
  revision: number;
  createdAt: number;
  refs: string[];
}
const props = defineProps<{
  editor?: boolean;
  tabId?: string;
  initialId?: string | null;
}>();
const { request, message } = useApi();
const error = ref(""),
  busy = ref(false),
  rows = ref<Row[]>([]),
  ready = ref(!props.initialId);
const form = reactive<Omit<Row, "createdAt">>({
  id: "",
  slug: "",
  titleZh: "",
  titleEn: "",
  summaryZh: "",
  summaryEn: "",
  published: false,
  revision: 0,
  refs: [],
});
const baseline = ref(JSON.stringify(form));
const changed = useState("catalog-changed", () => 0);
const pinDialog = ref<{ endpoint: string; title: string } | null>(null);
function showPins(id: string, title: string) {
  pinDialog.value = {
    endpoint: "/api/admin/series/" + id + "/pins",
    title: title + " · 置顶内容",
  };
}
async function load() {
  try {
    rows.value = await request<Row[]>("/api/admin/series");
  } catch (e) {
    error.value = message(e);
  }
}
const tabs = useEntityEditing(props, "series", load);
async function save() {
  if (!ready.value || busy.value) return false;
  busy.value = true;
  error.value = "";
  try {
    const result = await request<{ id: string }>(
      "/api/admin/series" + (form.id ? "/" + form.id : ""),
      { method: form.id ? "PUT" : "POST", body: form },
    );
    Object.assign(form, await request<Row>("/api/admin/series/" + result.id));
    baseline.value = JSON.stringify(form);
    changed.value++;
    await tabs.saved(result.id, form.titleZh);
    return true;
  } catch (e) {
    error.value = message(e);
    return false;
  } finally {
    busy.value = false;
  }
}
watch(
  () => JSON.stringify(form),
  (value) => {
    if (props.editor && ready.value)
      tabs.dirty.value[tabs.key.value] = value !== baseline.value;
  },
);
let unregister: (() => void) | undefined;
onMounted(async () => {
  if (!props.editor) {
    await load();
    return;
  }
  unregister = tabs.registerSave(tabs.key.value, save);
  if (props.initialId)
    try {
      Object.assign(
        form,
        await request<Row>("/api/admin/series/" + props.initialId),
      );
      baseline.value = JSON.stringify(form);
      ready.value = true;
    } catch (e) {
      error.value = message(e);
    }
});
onBeforeUnmount(() => unregister?.());
async function remove(row: Row) {
  if (
    !window.confirm("删除专栏「" + row.titleZh + "」？关联的文章与应用会保留。")
  )
    return;
  error.value = "";
  try {
    await request("/api/admin/series/" + row.id, {
      method: "DELETE",
      query: { revision: row.revision },
    });
    changed.value++;
    await load();
  } catch (e) {
    error.value = message(e);
  }
}
</script>
<template>
  <el-alert v-if="error" :title="error" type="error" :closable="false" />
  <template v-if="!editor">
    <div class="management-panel-heading">
      <div>
        <h1>专栏管理</h1>
        <p>将文章与应用组织成可按顺序阅读的目录。</p>
      </div>
      <el-button type="primary" @click="tabs.openEntityEditor('series')"
        >新增专栏</el-button
      >
    </div>
    <el-table
      :data="rows"
      :default-sort="{ prop: 'createdAt', order: 'descending' }"
    >
      <ObjectColumns time-prop="createdAt" :show-creator="false" />
      <el-table-column
        prop="titleZh"
        label="专栏名称"
        sortable
        :sort-orders="['descending', 'ascending']"
      />
      <el-table-column
        prop="slug"
        label="公开地址"
        sortable
        :sort-orders="['descending', 'ascending']"
      />
      <el-table-column
        prop="published"
        label="状态"
        sortable
        :sort-orders="['descending', 'ascending']"
        ><template #default="{ row }">{{
          row.published ? "已发布" : "草稿"
        }}</template></el-table-column
      >
      <el-table-column
        label="条目"
        sortable
        :sort-by="(row) => row.refs.length"
        :sort-orders="['descending', 'ascending']"
        ><template #default="{ row }">{{
          row.refs.length
        }}</template></el-table-column
      >
      <el-table-column label="操作" width="265"
        ><template #default="{ row }">
          <a
            v-if="row.published"
            :href="'/series/' + row.slug"
            target="_blank"
            rel="noopener noreferrer"
            >查看</a
          >
          <el-button
            link
            @click="
              tabs.openEntityEditor('series', { id: row.id, name: row.titleZh })
            "
            >编辑</el-button
          >
          <el-button link @click="showPins(row.id, row.titleZh)"
            >置顶内容</el-button
          >
          <el-button link type="danger" @click="remove(row as Row)"
            >删除</el-button
          >
        </template></el-table-column
      >
    </el-table>
  </template>
  <el-form
    v-else-if="ready"
    label-width="105px"
    class="catalog-form"
    @submit.prevent="save"
  >
    <el-form-item label="公开地址"
      ><el-input
        v-model="form.slug"
        maxlength="80"
        placeholder="例如 emba-prep"
    /></el-form-item>
    <el-form-item label="中文标题"
      ><el-input v-model="form.titleZh" maxlength="120"
    /></el-form-item>
    <el-form-item label="英文标题"
      ><el-input v-model="form.titleEn" maxlength="120"
    /></el-form-item>
    <el-form-item label="中文简介"
      ><el-input v-model="form.summaryZh" maxlength="500" type="textarea"
    /></el-form-item>
    <el-form-item label="英文简介"
      ><el-input v-model="form.summaryEn" maxlength="500" type="textarea"
    /></el-form-item>
    <el-form-item label="发布状态"
      ><el-switch v-model="form.published" active-text="公开展示"
    /></el-form-item>
    <SelectionPicker v-model="form.refs" series />
    <el-button type="primary" native-type="submit" :loading="busy"
      >保存专栏</el-button
    >
    <el-button v-if="form.id" @click="showPins(form.id, form.titleZh)"
      >置顶目录内容</el-button
    >
  </el-form>
  <PinItems v-model="pinDialog" />
</template>
