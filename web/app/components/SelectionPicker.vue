<script setup lang="ts">
/** 草稿与已确认选择分离；翻页只取20项，跨页勾选保存在ID集合，不取全库做假分页。 */
import { ElMessage } from "element-plus";
import { businessTime } from "~/utils/commerce";
const props = defineProps<{
    modelValue: string[];
    series?: boolean;
    assignment?: "catalog" | "content";
    contentKind?: string;
  }>(),
  emit = defineEmits<{ "update:modelValue": [string[]] }>();
const { request, message } = useApi();
const endpoint = computed(() =>
  props.assignment
    ? "/api/admin/" + props.assignment + "/series-picker"
    : props.series
      ? "/api/admin/series/selection"
      : "/api/admin/catalog/selection",
);
const visible = ref(false),
  draft = ref<string[]>([]),
  q = ref(""),
  kind = ref("ALL"),
  page = ref(1),
  size = ref(20),
  data = ref<any>({ items: [], total: 0 }),
  busy = ref(false),
  error = ref("");
const names = ref<Record<string, any>>({});
const order = useListOrder(load, page);
const categories: Record<string, string> = {
  TOOL: "免费工具",
  SOFTWARE: "软件",
  RESOURCE: "资源",
  DOCUMENT: "文档",
  ARTICLE: "文章",
  TUTORIAL: "教程",
  FAQ: "FAQ",
  CASE: "案例",
  API: "API",
};
let generation = 0;
async function lookup() {
  if (!props.modelValue.length) return;
  try {
    const rows = await request<any[]>(endpoint.value + "/lookup", {
      method: "POST",
      body: { refs: props.modelValue },
      query: { contentKind: props.contentKind },
    });
    for (const row of rows) names.value[row.ref] = row;
  } catch (e) {
    error.value = message(e);
  }
}
watch(() => props.modelValue, lookup, { deep: true, immediate: true });
async function load() {
  const token = ++generation;
  busy.value = true;
  error.value = "";
  try {
    const value = await request<any>(endpoint.value, {
      query: {
        q: q.value,
        kind: kind.value,
        page: page.value,
        pageSize: size.value,
        contentKind: props.contentKind,
        sort: order.sort.value,
        direction: order.direction.value === "ascending" ? "asc" : "desc",
      },
    });
    if (token !== generation) return;
    data.value = value;
    page.value = value.page;
    for (const row of value.items) names.value[row.ref] = row;
  } catch (e) {
    if (token === generation) error.value = message(e);
  } finally {
    if (token === generation) busy.value = false;
  }
}
function open() {
  draft.value = [...props.modelValue];
  visible.value = true;
  load();
}
function choose(id: string, checked: boolean) {
  if (checked && !draft.value.includes(id)) {
    if (draft.value.length >= 300) {
      ElMessage.warning("最多选择300项");
      return;
    }
    draft.value.push(id);
  } else if (!checked) draft.value = draft.value.filter((v) => v !== id);
}
function confirm() {
  emit("update:modelValue", [...draft.value]);
  visible.value = false;
}
function remove(id: string) {
  emit(
    "update:modelValue",
    props.modelValue.filter((v) => v !== id),
  );
}
function move(index: number, next: number) {
  if (next < 0 || next >= props.modelValue.length || index === next) return;
  const ids = [...props.modelValue],
    entry = ids.splice(index, 1)[0]!;
  ids.splice(next, 0, entry);
  emit("update:modelValue", ids);
}
const dragging = ref<number | null>(null);
function drop(index: number) {
  if (dragging.value !== null) move(dragging.value, index);
  dragging.value = null;
}
const typeLabel = (value: string) =>
  ({
    PRODUCT: "应用 / 工具",
    API: "API服务",
    CONTENT: "文章 / FAQ / 案例",
    MISSING: "已删除",
  })[value] || value;
</script>
<template>
  <div class="selection-picker">
    <div class="admin-table-actions">
      <el-button type="primary" @click="open">{{
        assignment ? "选择专栏" : "选择内容"
      }}</el-button
      ><span>已选择 {{ modelValue.length }} / 300 项</span>
    </div>
    <p v-if="assignment" class="admin-muted">
      可跨页选择多个专栏，发布成功后生效。
    </p>
    <p v-else class="admin-muted">
      {{
        series
          ? "支持应用、文章、FAQ 和案例跨页多选。"
          : "支持应用、API、文章、FAQ 和案例跨页多选。"
      }}拖动已选项调整顺序，也可用上下移按钮；未发布内容不会公开展示。
    </p>
    <el-alert
      v-if="error && !visible"
      :title="error"
      type="error"
      :closable="false"
    />
    <el-empty
      v-if="!assignment && !modelValue.length"
      :description="assignment ? '尚未选择专栏' : '尚未选择内容'"
      :image-size="48"
    />
    <ol class="selected-content" aria-label="已选内容排序">
      <li
        v-for="(id, index) in modelValue"
        :key="id"
        :draggable="!assignment"
        @dragstart="dragging = index"
        @dragend="dragging = null"
        @dragover.prevent
        @drop.prevent="drop(index)"
      >
        <span v-if="!assignment" class="drag-handle" aria-hidden="true">⠿</span
        ><span class="selection-number">{{ index + 1 }}</span>
        <div class="selected-name">
          <strong>{{ names[id]?.name || id }}</strong
          ><small
            >{{
              assignment ? "专栏" : typeLabel(names[id]?.kind || "PRODUCT")
            }}
            · {{ names[id]?.status || "读取中" }}</small
          >
        </div>
        <el-button
          size="small"
          v-if="!assignment"
          :disabled="index === 0"
          :aria-label="'上移 ' + (names[id]?.name || id)"
          @click="move(index, index - 1)"
          >上移</el-button
        >
        <el-button
          size="small"
          v-if="!assignment"
          :disabled="index === modelValue.length - 1"
          :aria-label="'下移 ' + (names[id]?.name || id)"
          @click="move(index, index + 1)"
          >下移</el-button
        >
        <el-button size="small" type="danger" plain @click="remove(id)"
          >移除</el-button
        >
      </li>
    </ol>
    <el-dialog
      v-model="visible"
      :title="assignment ? '选择专栏' : '选择栏目内容'"
      width="min(1000px,96vw)"
      :close-on-click-modal="false"
      append-to-body
    >
      <el-form
        inline
        label-position="right"
        @submit.prevent="
          page = 1;
          load();
        "
        ><el-form-item label="名称或地址"
          ><el-input
            v-model="q"
            aria-label="选品搜索"
            maxlength="100"
            clearable /></el-form-item
        ><el-form-item :label="assignment ? '专栏状态' : '内容类型'"
          ><el-select
            v-model="kind"
            aria-label="选品内容类型"
            style="width: 190px"
            @change="
              page = 1;
              load();
            "
            ><el-option value="ALL" label="全部" />
            <template v-if="assignment">
              <el-option value="PUBLISHED" label="已发布" />
              <el-option value="DRAFT" label="草稿" />
            </template>
            <template v-else>
              <el-option value="PRODUCT" label="应用 / 工具" />
              <el-option v-if="!series" value="API" label="API服务" />
              <el-option value="CONTENT" label="文章 / FAQ / 案例" />
              <template v-if="series">
                <template v-for="(label, key) in categories" :key="key">
                  <el-option v-if="key !== 'API'" :value="key" :label="label" />
                </template>
              </template> </template></el-select></el-form-item
        ><el-button
          type="primary"
          :loading="busy"
          @click="
            page = 1;
            load();
          "
          >搜索</el-button
        ></el-form
      >
      <el-alert v-if="error" :title="error" type="error" :closable="false" />
      <div class="admin-table-actions">
        <el-button
          size="small"
          :disabled="busy"
          @click="data.items.forEach((row: any) => choose(row.ref, true))"
          >选择本页</el-button
        ><el-button
          size="small"
          :disabled="busy"
          @click="data.items.forEach((row: any) => choose(row.ref, false))"
          >取消本页</el-button
        ><span>跨页已选 {{ draft.length }} 项</span>
      </div>
      <el-table
        v-loading="busy"
        :data="data.items"
        row-key="ref"
        :default-sort="order.defaultSort"
        @sort-change="order.change"
        max-height="440"
        ><el-table-column label="选择" width="70"
          ><template #default="{ row }"
            ><el-checkbox
              :disabled="busy"
              :model-value="draft.includes(row.ref)"
              :aria-label="'选择 ' + row.name"
              @change="
                (value) => choose(row.ref, !!value)
              " /></template></el-table-column
        ><el-table-column
          prop="name"
          label="名称"
          min-width="155"
          show-overflow-tooltip
          sortable="custom"
          :sort-orders="['descending', 'ascending']"
        />
        <el-table-column
          v-if="!assignment"
          prop="category"
          label="分类"
          min-width="100"
          sortable="custom"
          :sort-orders="['descending', 'ascending']"
        >
          <template #default="{ row }">{{
            categories[row.category] || typeLabel(row.kind)
          }}</template>
        </el-table-column>
        <el-table-column
          prop="slug"
          label="地址标识"
          min-width="140"
          show-overflow-tooltip
          sortable="custom"
          :sort-orders="['descending', 'ascending']"
        />
        <el-table-column
          prop="status"
          label="发布状态"
          width="110"
          sortable="custom"
          :sort-orders="['descending', 'ascending']"
        />
        <el-table-column
          prop="created_at"
          label="创建时间"
          min-width="165"
          sortable="custom"
          :sort-orders="['descending', 'ascending']"
        >
          <template #default="{ row }">{{
            businessTime(row.created_at)
          }}</template>
        </el-table-column>
        <el-table-column
          v-if="!assignment"
          prop="published_at"
          label="发布时间"
          min-width="165"
          sortable="custom"
          :sort-orders="['descending', 'ascending']"
        >
          <template #default="{ row }">{{
            businessTime(row.published_at)
          }}</template>
        </el-table-column>
      </el-table>
      <el-pagination
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
      />
      <template #footer
        ><el-button @click="visible = false">取消</el-button
        ><el-button type="primary" :disabled="busy" @click="confirm"
          >确认选择（{{ draft.length }}）</el-button
        ></template
      >
    </el-dialog>
  </div>
</template>
<style scoped>
.selected-content {
  padding: 0;
  margin: 18px 0;
  list-style: none;
}
.selected-content li {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px;
  border: 1px solid #e6e7f1;
  border-radius: 8px;
  margin-bottom: 8px;
  background: #fff;
}
.drag-handle {
  cursor: grab;
  color: #9894b5;
  font-size: 22px;
}
.selection-number {
  color: #858ca1;
}
.selected-name {
  flex: 1;
  min-width: 0;
}
.selected-name strong {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-weight: 500;
}
.selected-name small {
  color: #9299aa;
}
@media (max-width: 680px) {
  .selected-content li {
    flex-wrap: wrap;
  }
  .selected-name {
    min-width: 180px;
  }
}
</style>
