<script setup lang="ts">
/** 当前菜单独立保留筛选与编辑草稿，发布使用实际版本号；富文本和图片复用既有编辑/存储组件。 */
import { businessTime } from "~/utils/commerce";
const props = defineProps<{
    view: string;
    editor?: boolean;
    tabId?: string;
    initialId?: string | null;
  }>(),
  { request, message } = useApi(),
  editingState = useEntityEditing(props, props.view, load),
  { dirty, registerSave } = editingState;
const stateLabels: Record<string, string> = {
  DRAFT: "草稿",
  PUBLISHED: "已发布",
  UNLISTED: "已下架",
};
const kinds: Record<string, string> = {
  ARTICLE: "文章",
  TUTORIAL: "教程",
  FAQ: "FAQ",
  CASE: "案例",
};
const q = ref(""),
  kind = ref(""),
  page = ref(1),
  size = ref(20),
  data = ref<any>({ items: [], total: 0 }),
  editing = ref(false),
  busy = ref(false),
  error = ref(""),
  notice = ref(""),
  baseline = ref("");
const allowedKinds = computed(() =>
  props.view === "faq" ? ["FAQ", "CASE"] : ["ARTICLE", "TUTORIAL"],
);
const empty = () => ({
  id: "",
  slug: "",
  kind: props.view === "faq" ? "FAQ" : "ARTICLE",
  titleZh: "",
  titleEn: "",
  summaryZh: "",
  summaryEn: "",
  bodyZh: "",
  bodyEn: "",
  revision: 0,
});
const form = reactive(empty()),
  order = useListOrder(load, page);
const assignment = useSeriesAssignment(
  "content",
  computed(() => form.kind),
);
const { seriesIds } = assignment;
// 上传完成前不允许保存/关闭，避免将尚未插入正文的图片遗漏在发布快照之外。
const uploads = reactive({ zh: false, en: false });
const uploading = computed(() => uploads.zh || uploads.en);
watch(
  () => JSON.stringify(form) + uploading.value,
  (value) =>
    (dirty.value[editingState.key.value] =
      editing.value &&
      (uploading.value || JSON.stringify(form) !== baseline.value)),
);
function closeEditor() {
  if (props.editor) {
    editingState.close(editingState.key.value);
    return;
  }
  if (uploading.value) {
    error.value = "请等待图片上传完成";
    return;
  }
  if (
    !dirty.value[editingState.key.value] ||
    window.confirm("放弃未保存内容？")
  ) {
    editing.value = false;
    dirty.value[editingState.key.value] = false;
  }
}
async function load() {
  try {
    data.value = await request("/api/admin/content", {
      query: {
        scope: props.view,
        q: q.value,
        kind: kind.value,
        page: page.value,
        pageSize: size.value,
        sort: order.sort.value,
        direction: order.direction.value,
      },
    });
    page.value = data.value.page;
  } catch (e) {
    error.value = message(e);
  }
}
async function edit(id?: string, force = false, refreshSeries = true) {
  if (!props.editor && !force) {
    await editingState.openEntityEditor(props.view, id ? { id } : undefined);
    return;
  }
  if (uploading.value) {
    error.value = "请等待图片上传完成";
    return;
  }
  if (
    !force &&
    dirty.value[editingState.key.value] &&
    !window.confirm("放弃当前未保存内容？")
  )
    return;
  try {
    if (id) {
      const result = await request<any>("/api/admin/content/" + id);
      Object.assign(form, result.content, {
        id,
        revision: Number(result.revision),
      });
    } else Object.assign(form, empty());
    if (refreshSeries) await assignment.refresh(id);
    editing.value = true;
    baseline.value = JSON.stringify(form);
    dirty.value[editingState.key.value] = false;
  } catch (e) {
    error.value = message(e);
  }
}
async function save() {
  if (!editing.value) return true;
  if (uploading.value) {
    error.value = "请等待图片上传完成";
    return false;
  }
  busy.value = true;
  error.value = "";
  try {
    const result = await request<any>(
      "/api/admin/content" + (form.id ? "/" + form.id : ""),
      { method: form.id ? "PUT" : "POST", body: form },
    );
    await edit(result.id, true, false);
    await editingState.saved(result.id, form.titleZh);
    if (!props.editor) await load();
    notice.value = "草稿已保存";
    return true;
  } catch (e) {
    error.value = message(e);
    return false;
  } finally {
    busy.value = false;
  }
}
async function publish() {
  if (!(await save())) return;
  await action({ id: form.id, revision: form.revision }, "publish");
}
async function action(row: any, verb: string) {
  if (verb !== "publish" && dirty.value[editingState.key.value]) {
    error.value = "请先保存或放弃当前编辑";
    return;
  }
  busy.value = true;
  try {
    await request(
      "/api/admin/content/" + row.id + (verb === "delete" ? "" : "/" + verb),
      verb === "delete"
        ? { method: "DELETE", query: { revision: row.revision } }
        : {
            method: "POST",
            body: {
              revision: Number(row.revision),
              seriesIds:
                verb === "publish" && editing.value && form.id === row.id
                  ? assignment.changes()
                  : undefined,
            },
          },
    );
    editingState.changed();
    await load();
    if (editing.value && form.id === row.id && verb !== "delete")
      await edit(row.id, true);
    notice.value =
      verb === "publish"
        ? "发布成功，双语静态页面已生成"
        : verb === "unlist"
          ? "已下架"
          : "草稿已删除";
  } catch (e) {
    error.value = message(e);
  } finally {
    busy.value = false;
  }
}
let unregister: (() => void) | undefined;
onMounted(() => {
  unregister = registerSave(editingState.key.value, save);
  if (props.editor) edit(props.initialId || undefined, true);
  else load();
});
onBeforeUnmount(() => unregister?.());
</script>
<template>
  <section>
    <div v-if="!editor" class="management-panel-heading">
      <div>
        <h1>{{ view === "faq" ? "FAQ 与案例" : "文章与教程" }}</h1>
        <p>草稿独立保存，发布后生成双语静态正文及SEO资料。</p>
      </div>
      <el-button type="primary" @click="edit()">新增内容</el-button>
    </div>
    <el-alert
      v-if="error"
      :title="error"
      type="error"
      :closable="false"
    /><el-alert
      v-if="notice"
      :title="notice"
      type="success"
      :closable="false"
    />
    <el-card v-if="editing" shadow="never" style="margin-bottom: 20px"
      ><template #header
        ><div class="admin-table-actions">
          <strong>{{ form.id ? "编辑内容" : "新增内容" }}</strong
          ><el-button
            type="primary"
            :loading="busy"
            :disabled="uploading"
            @click="save"
            >保存草稿</el-button
          ><el-button :disabled="busy || uploading" @click="publish"
            >保存并发布</el-button
          ><el-button @click="closeEditor">{{
            editor ? "关闭页签" : "收起编辑"
          }}</el-button>
        </div></template
      ><el-form
        class="admin-form-grid"
        label-position="right"
        label-width="112px"
        ><el-form-item label="发布专栏" class="wide">
          <SelectionPicker
            v-model="seriesIds"
            assignment="content"
            :content-kind="form.kind"
          /> </el-form-item
        ><el-form-item label="中文标题"
          ><el-input
            v-model="form.titleZh"
            aria-label="内容中文标题"
            maxlength="120" /></el-form-item
        ><el-form-item label="英文标题"
          ><el-input
            v-model="form.titleEn"
            aria-label="内容英文标题"
            maxlength="120" /></el-form-item
        ><el-form-item label="公开地址"
          ><el-input
            v-model="form.slug"
            aria-label="内容公开地址"
            maxlength="80"
            placeholder="小写英文、数字和短横线" /></el-form-item
        ><el-form-item label="内容类型"
          ><el-select v-model="form.kind" aria-label="内容类型"
            ><el-option
              v-for="key in allowedKinds"
              :key="key"
              :value="key"
              :label="kinds[key]" /></el-select></el-form-item
        ><el-form-item label="中文摘要"
          ><el-input
            v-model="form.summaryZh"
            aria-label="内容中文摘要"
            type="textarea"
            maxlength="500"
            :rows="3" /></el-form-item
        ><el-form-item label="英文摘要"
          ><el-input
            v-model="form.summaryEn"
            type="textarea"
            maxlength="500"
            :rows="3"
        /></el-form-item>
        <div class="full">
          <SummernoteEditor
            v-model="form.bodyZh"
            label="内容中文正文"
            @busy="uploads.zh = $event"
          />
        </div>
        <div class="full">
          <SummernoteEditor
            v-model="form.bodyEn"
            label="内容英文正文"
            @busy="uploads.en = $event"
          /></div></el-form
    ></el-card>
    <el-card v-if="!editor" shadow="never"
      ><el-form
        label-position="right"
        label-width="112px"
        class="admin-filter-grid"
        @submit.prevent="
          page = 1;
          load();
        "
        ><el-form-item label="标题查询"
          ><el-input
            v-model="q"
            aria-label="内容标题查询"
            clearable
            maxlength="100" /></el-form-item
        ><el-form-item label="内容分类"
          ><el-select v-model="kind" clearable placeholder="全部"
            ><el-option
              v-for="key in allowedKinds"
              :key="key"
              :value="key"
              :label="kinds[key]" /></el-select
        ></el-form-item>
        <div class="full">
          <el-button
            type="primary"
            @click="
              page = 1;
              load();
            "
            >查询</el-button
          >
        </div></el-form
      ><el-table
        :data="data.items"
        :default-sort="order.defaultSort"
        @sort-change="order.change"
        ><ObjectColumns server-sort /><el-table-column
          prop="title_zh"
          label="标题"
          sortable="custom"
          :sort-orders="['descending', 'ascending']"
          min-width="200"
        /><el-table-column
          prop="kind"
          label="类型"
          sortable="custom"
          :sort-orders="['descending', 'ascending']"
          ><template #default="{ row }">{{
            kinds[row.kind]
          }}</template></el-table-column
        ><el-table-column
          prop="state"
          label="状态"
          sortable="custom"
          :sort-orders="['descending', 'ascending']"
          ><template #default="{ row }">{{
            stateLabels[row.state]
          }}</template></el-table-column
        ><el-table-column
          align="center"
          header-align="center"
          label="操作"
          min-width="200"
          ><template #default="{ row }"
            ><el-button link type="primary" @click="edit(row.id)"
              >编辑</el-button
            ><a
              v-if="row.state === 'PUBLISHED'"
              :href="'/articles/' + row.slug"
              target="_blank"
              rel="noopener"
              >查看</a
            ><el-button
              v-if="row.state === 'PUBLISHED'"
              link
              type="warning"
              @click="action(row, 'unlist')"
              >下架</el-button
            ><el-button
              v-if="row.state === 'DRAFT'"
              link
              type="danger"
              @click="action(row, 'delete')"
              >删除</el-button
            ></template
          ></el-table-column
        ></el-table
      ><el-pagination
        v-model:current-page="page"
        v-model:page-size="size"
        :total="data.total"
        :page-sizes="[10, 20, 50]"
        layout="total,sizes,prev,pager,next"
        @current-change="load"
        @size-change="
          page = 1;
          load();
        "
    /></el-card>
  </section>
</template>
