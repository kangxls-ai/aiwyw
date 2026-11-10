<script setup lang="ts">
/** 栏目维护仅编辑当前栏目；应用编辑已独立为多个页签。 */
import type { AdminSection, SectionConfig } from "~/types/catalog";
const props = defineProps<{
  editor?: boolean;
  tabId?: string;
  initialId?: string | null;
  initialMode?: string;
}>();
const { request, message } = useApi(),
  tabState = useEntityEditing(props, "sections", load),
  { dirty, registerSave } = tabState;
const sections = ref<AdminSection[]>([]),
  id = ref<string | null>(null),
  editing = ref(false),
  busy = ref(false),
  error = ref(""),
  notice = ref(""),
  base = ref("");
const changed = useState("catalog-changed", () => 0);
const pinDialog = ref<{ endpoint: string; title: string } | null>(null);
const dragging = ref("");
const dropTarget = ref<{ id: string; before: boolean } | null>(null);
function showPins(id: string, title: string) {
  pinDialog.value = {
    endpoint: "/api/admin/catalog/sections/" + id + "/pins",
    title: title + " · 置顶内容",
  };
}
function dragStart(event: DragEvent, row: AdminSection) {
  if (busy.value) {
    event.preventDefault();
    return;
  }
  dragging.value = row.id;
  event.dataTransfer?.setData("text/plain", row.id);
  if (event.dataTransfer) event.dataTransfer.effectAllowed = "move";
}
function clearDrag() {
  dragging.value = "";
  dropTarget.value = null;
}
function targetRow(event: DragEvent) {
  const row = (event.target as Element | null)?.closest("tr");
  const id = row?.querySelector<HTMLElement>("[data-section-order-id]")?.dataset
    .sectionOrderId;
  if (!dragging.value || !row || !id || id === dragging.value) return null;
  const box = row.getBoundingClientRect();
  return { id, before: event.clientY < box.top + box.height / 2 };
}
function dragOver(event: DragEvent) {
  dropTarget.value = targetRow(event);
  if (dropTarget.value) event.preventDefault();
}
async function drop(event: DragEvent) {
  const to = targetRow(event),
    from = dragging.value;
  clearDrag();
  if (!to) return;
  event.preventDefault();
  const order = sections.value.filter((row) => row.id !== from);
  const moving = sections.value.find((row) => row.id === from);
  if (!moving) return;
  const index = order.findIndex((row) => row.id === to.id);
  order.splice(index + (to.before ? 0 : 1), 0, moving);
  await persistOrder(order);
}
async function move(row: AdminSection, direction: number) {
  const index = sections.value.findIndex((item) => item.id === row.id);
  const next = index + direction;
  if (index < 0 || next < 0 || next >= sections.value.length) return;
  const order = [...sections.value];
  order.splice(index, 1);
  order.splice(next, 0, row);
  await persistOrder(order);
}
async function persistOrder(order: AdminSection[]) {
  if (
    busy.value ||
    order.every((row, index) => row.id === sections.value[index]?.id)
  )
    return;
  busy.value = true;
  error.value = "";
  notice.value = "";
  try {
    sections.value = await request<AdminSection[]>(
      "/api/admin/catalog/sections/order",
      {
        method: "PUT",
        body: order.map(({ id, revision }) => ({ id, revision })),
      },
    );
    changed.value++;
    notice.value = "栏目顺序已保存";
  } catch (e) {
    error.value = message(e);
    await load().catch(() => {});
  } finally {
    busy.value = false;
  }
}
const applicationCount = computed(
  () =>
    sections.value.filter(
      (s) => !["ARTICLES", "API", "SERIES"].includes(s.config.mode),
    ).length,
);
const empty = (): SectionConfig => ({
  titleZh: "",
  titleEn: "",
  subtitleZh: "",
  subtitleEn: "",
  icon: "star",
  enabled: true,
  position: Math.min(
    1000,
    1 + Math.max(-1, ...sections.value.map((row) => row.config.position)),
  ),
  mode: "MANUAL",
  sort: "LATEST",
  days: 30,
  limit: 20,
  tags: [],
  productIds: [],
  revision: 0,
});
const form = ref(empty()),
  snapshot = () => JSON.stringify(form.value);
watch(
  () => snapshot(),
  () => {
    dirty.value[tabState.key.value] =
      editing.value && snapshot() !== base.value;
  },
);
async function load() {
  sections.value = await request("/api/admin/catalog/sections");
}
function open(value?: AdminSection, mode?: string) {
  if (!props.editor) {
    tabState.openEntityEditor(
      "sections",
      value ? { id: value.id, name: value.config.titleZh } : undefined,
      mode,
    );
    return;
  }
  if (
    dirty.value[tabState.key.value] &&
    !window.confirm("放弃当前栏目未保存修改？")
  )
    return;
  id.value = value?.id || null;
  form.value = value
    ? {
        ...structuredClone(toRaw(value.config)),
        limit: Math.min(40, value.config.limit),
        revision: value.revision,
      }
    : { ...empty(), ...(mode ? { mode } : {}) };

  editing.value = true;
  base.value = snapshot();
  dirty.value[tabState.key.value] = false;
}
async function save() {
  if (!editing.value) return true;
  busy.value = true;
  error.value = "";
  notice.value = "";
  try {
    const value = { ...form.value };
    const result = await request<{ id: string }>(
      id.value
        ? `/api/admin/catalog/sections/${id.value}`
        : "/api/admin/catalog/sections",
      { method: id.value ? "PUT" : "POST", body: value },
    );
    dirty.value[tabState.key.value] = false;
    editing.value = false;
    await load();
    open(sections.value.find((s) => s.id === result.id));
    changed.value++;
    await tabState.saved(result.id, form.value.titleZh);
    notice.value = "栏目已保存并生效";
    return true;
  } catch (e) {
    error.value = message(e);
    return false;
  } finally {
    busy.value = false;
  }
}
async function remove(value: AdminSection) {
  if (
    !window.confirm(`删除栏目“${value.config.titleZh}”？不会删除其中的应用。`)
  )
    return;
  try {
    await request(`/api/admin/catalog/sections/${value.id}`, {
      method: "DELETE",
      query: { revision: value.revision },
    });
    if (id.value === value.id) {
      editing.value = false;
      dirty.value[tabState.key.value] = false;
    }
    await load();
    changed.value++;
    notice.value = "栏目已删除";
  } catch (e) {
    error.value = message(e);
  }
}
let unregister: (() => void) | undefined;
onMounted(async () => {
  unregister = registerSave(tabState.key.value, save);
  try {
    await load();
    if (props.editor)
      open(
        props.initialId
          ? sections.value.find((s) => s.id === props.initialId)
          : undefined,
        props.initialMode,
      );
  } catch (e) {
    error.value = message(e);
  }
});
onBeforeUnmount(() => unregister?.());
</script>
<template>
  <section>
    <div v-if="!editor" class="management-panel-heading">
      <div>
        <span>HOME SECTIONS</span>
        <h1>首页栏目</h1>
        <p>
          拖动手柄调整首页顺序，也可聚焦手柄后按方向键。顶部 API
          导航固定在末尾。
        </p>
      </div>
      <div class="admin-table-actions">
        <el-button @click="showPins('all-applications', '全部应用')"
          >全部应用置顶</el-button
        >
        <el-button
          type="primary"
          :disabled="applicationCount >= 6"
          @click="open()"
          >新增栏目</el-button
        ><el-button
          v-if="!sections.some((s) => s.config.mode === 'ARTICLES')"
          @click="open(undefined, 'ARTICLES')"
          >添加文章栏目</el-button
        >
        <el-button
          v-if="!sections.some((s) => s.config.mode === 'SERIES')"
          @click="open(undefined, 'SERIES')"
          >添加专栏栏目</el-button
        >
        <el-button
          v-if="!sections.some((s) => s.config.mode === 'API')"
          @click="open(undefined, 'API')"
          >添加API栏目</el-button
        >
      </div>
    </div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <p v-if="notice" class="alert success" role="status">{{ notice }}</p>
    <el-card v-if="!editor" shadow="never"
      ><el-table
        :data="sections"
        row-key="id"
        v-loading="busy"
        :row-class-name="
          ({ row }) =>
            dropTarget && dropTarget.id === row.id
              ? dropTarget.before
                ? 'section-drop-before'
                : 'section-drop-after'
              : ''
        "
        @dragover="dragOver"
        @drop="drop"
        ><el-table-column label="排序" width="72">
          <template #default="{ row }">
            <button
              class="section-drag"
              type="button"
              :draggable="!busy"
              :disabled="busy"
              :data-section-order-id="row.id"
              :aria-label="'拖动排序 ' + row.config.titleZh"
              title="拖动排序，也可按上下方向键"
              @dragstart="dragStart($event, row as AdminSection)"
              @dragend="clearDrag"
              @keydown.up.prevent="move(row as AdminSection, -1)"
              @keydown.down.prevent="move(row as AdminSection, 1)"
            >
              ⠿
            </button>
          </template> </el-table-column
        ><ObjectColumns
          time-prop="createdAt"
          :sortable-time="false"
        /><el-table-column prop="config.titleZh" label="栏目" min-width="180"
          ><template #default="{ row }">{{
            row.config.titleZh
          }}</template></el-table-column
        ><el-table-column prop="config.mode" label="选品" min-width="130"
          ><template #default="{ row }">{{
            {
              MANUAL: "人工选品",
              TAG: "按标签",
              AUTO: "自动排序",
              ARTICLES: "文章与教程导航",
              API: "API服务导航",
              SERIES: "专栏",
            }[
              row.config.mode as
                | "MANUAL"
                | "TAG"
                | "AUTO"
                | "ARTICLES"
                | "API"
                | "SERIES"
            ]
          }}</template></el-table-column
        ><el-table-column prop="config.enabled" label="状态" width="90"
          ><template #default="{ row }"
            ><el-tag :type="row.config.enabled ? 'success' : 'info'">{{
              row.config.enabled ? "显示" : "停用"
            }}</el-tag></template
          ></el-table-column
        ><el-table-column
          align="center"
          header-align="center"
          label="操作"
          width="215"
          ><template #default="{ row }"
            ><el-button link type="primary" @click="open(row as AdminSection)"
              >编辑</el-button
            ><el-button link @click="showPins(row.id, row.config.titleZh)"
              >置顶内容</el-button
            ><el-button link type="danger" @click="remove(row as AdminSection)"
              >删除</el-button
            ></template
          ></el-table-column
        ></el-table
      ></el-card
    >
    <el-card v-if="editing" shadow="never" style="margin-top: 20px"
      ><template #header
        ><div class="catalog-editor-actions">
          <h2>编辑栏目</h2>
          <el-button type="primary" :loading="busy" @click="save"
            >保存修改</el-button
          >
        </div></template
      ><el-form
        :model="form"
        label-position="right"
        label-width="128px"
        class="admin-form-grid"
        @submit.prevent="save"
      >
        <el-form-item label="栏目名称" required
          ><el-input
            v-model="form.titleZh"
            aria-label="栏目名称"
            maxlength="40" /></el-form-item
        ><el-form-item label="英文栏目名"
          ><el-input v-model="form.titleEn" maxlength="80" /></el-form-item
        ><el-form-item label="栏目说明"
          ><el-input v-model="form.subtitleZh" maxlength="200" /></el-form-item
        ><el-form-item label="英文说明"
          ><el-input v-model="form.subtitleEn" maxlength="200"
        /></el-form-item>
        <el-form-item v-if="form.mode === 'SERIES'" label="首页显示数量">
          <span>最新 20 个已发布专栏</span> </el-form-item
        ><el-form-item v-else label="首页显示数量"
          ><el-input-number
            v-model="form.limit"
            :min="1"
            :max="40"
            controls-position="right"
        /></el-form-item>
        <el-form-item label="图标"
          ><el-select v-model="form.icon"
            ><el-option
              v-for="icon in [
                'star',
                'spark',
                'download',
                'flame',
                'shapes',
                'folder',
                'note',
                'chart',
                'browser',
              ]"
              :key="icon"
              :value="icon"
              :label="icon" /></el-select></el-form-item
        ><el-form-item label="显示状态"
          ><el-switch
            v-model="form.enabled"
            aria-label="栏目显示状态"
            active-text="显示"
            inactive-text="停用"
        /></el-form-item>
        <el-form-item label="栏目内容"
          ><el-select v-model="form.mode" aria-label="栏目内容类型"
            ><el-option
              value="API"
              label="API服务（顶部导航末尾）"
              :disabled="
                sections.some((s) => s.config.mode === 'API' && s.id !== id)
              " /><el-option value="MANUAL" label="人工选品" /><el-option
              value="TAG"
              label="按标签筛选" /><el-option
              value="AUTO"
              label="自动选品" /><el-option
              value="SERIES"
              label="专栏"
              :disabled="
                sections.some((s) => s.config.mode === 'SERIES' && s.id !== id)
              " /><el-option
              value="ARTICLES"
              label="文章与教程（顶部导航）"
              :disabled="
                sections.some(
                  (s) => s.config.mode === 'ARTICLES' && s.id !== id,
                )
              " /></el-select></el-form-item
        ><el-form-item
          v-if="!['ARTICLES', 'API', 'SERIES'].includes(form.mode)"
          label="排序依据"
          ><el-select v-model="form.sort"
            ><el-option value="LATEST" label="首次上架时间" /><el-option
              value="VIEWS"
              label="浏览量" /><el-option
              value="DOWNLOADS"
              label="下载量" /></el-select
        ></el-form-item>
        <el-form-item
          v-if="
            !['ARTICLES', 'API', 'SERIES'].includes(form.mode) &&
            form.mode !== 'MANUAL' &&
            form.sort !== 'LATEST'
          "
          label="统计周期"
          ><el-select v-model="form.days"
            ><el-option :value="7" label="近7天" /><el-option
              :value="30"
              label="近30天" /><el-option :value="0" label="累计" /></el-select
        ></el-form-item>
        <el-form-item v-if="form.mode === 'TAG'" label="选品标签" class="full"
          ><PagedPicker
            v-model="form.tags"
            title="选择选品标签"
            endpoint="/api/admin/catalog/tags/options/page"
            value-key="name"
            detail-key=""
            :max="20"
            clearable
            allow-create
        /></el-form-item>
        <div v-if="form.mode === 'MANUAL'" class="full">
          <h3>人工选品与排序</h3>
          <SelectionPicker v-model="form.productIds" />
        </div> </el-form
    ></el-card>
    <PinItems v-model="pinDialog" />
  </section>
</template>
<style scoped>
.section-drag {
  width: 36px;
  height: 32px;
  border: 1px solid #ded9ea;
  border-radius: 6px;
  color: #574382;
  background: #fff;
  font-size: 20px;
  cursor: grab;
}
.section-drag:active {
  cursor: grabbing;
}
:deep(.section-drop-before > td) {
  box-shadow: inset 0 2px #8569bb;
}
:deep(.section-drop-after > td) {
  box-shadow: inset 0 -2px #8569bb;
}
</style>
