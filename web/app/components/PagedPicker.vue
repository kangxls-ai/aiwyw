<script setup lang="ts">
/** 仅打开时读取一页。已选值跨页保留，关闭弹窗不写回表单。 */
const props = withDefaults(
  defineProps<{
    modelValue: string | string[];
    title: string;
    endpoint: string;
    params?: Record<string, unknown>;
    valueKey?: string;
    labelKey?: string;
    detailKey?: string;
    labels?: Record<string, string>;
    clearable?: boolean;
    allowCreate?: boolean;
    max?: number;
    disabled?: boolean;
  }>(),
  { valueKey: "id", labelKey: "name", detailKey: "slug" },
);
const emit = defineEmits<{
  "update:modelValue": [value: any];
}>();
const { request, message } = useApi();
const { t: translate } = useLanguage();
const route = useRoute();
const t = (cn: string, en: string) =>
  staffRelative(route.path).startsWith("/admin") ? cn : translate(cn, en);
const visible = ref(false),
  q = ref(""),
  page = ref(1),
  size = ref(20);
const rows = ref<Record<string, any>[]>([]),
  total = ref(0),
  busy = ref(false),
  error = ref("");
const draft = ref<string[]>([]),
  added = ref(""),
  known = reactive(new Map<string, Record<string, any>>());
const multiple = computed(() => Array.isArray(props.modelValue));
const values = computed(() =>
  Array.isArray(props.modelValue)
    ? props.modelValue
    : props.modelValue
      ? [props.modelValue]
      : [],
);
const label = (value: string) =>
  known.get(value)?.[props.labelKey] || props.labels?.[value] || value;
const id = (row: Record<string, any>) => String(row[props.valueKey]);
let generation = 0;
async function load() {
  const current = ++generation;
  busy.value = true;
  error.value = "";
  try {
    const result = await request<{
      items: Record<string, any>[];
      total: number;
      page: number;
    }>(props.endpoint, {
      query: {
        ...props.params,
        q: q.value.trim(),
        page: page.value,
        pageSize: size.value,
      },
    });
    if (current !== generation || !visible.value) return;
    rows.value = result.items;
    total.value = result.total;
    page.value = result.page;
  } catch (e) {
    if (current === generation) error.value = message(e);
  } finally {
    if (current === generation) busy.value = false;
  }
}
function search() {
  page.value = 1;
  load();
}
function open() {
  draft.value = [...values.value];
  q.value = "";
  added.value = "";
  rows.value = [];
  total.value = 0;
  visible.value = true;
  search();
}
function choose(value: string, checked: boolean) {
  const row = rows.value.find((candidate) => id(candidate) === value);
  if (checked && row) known.set(value, row);
  if (
    checked &&
    multiple.value &&
    props.max &&
    draft.value.length >= props.max &&
    !draft.value.includes(value)
  )
    return;
  if (!multiple.value) draft.value = checked ? [value] : [];
  else if (checked && !draft.value.includes(value)) draft.value.push(value);
  else if (!checked) draft.value = draft.value.filter((item) => item !== value);
}
function add() {
  const name = added.value.trim();
  if (!name || name.length > 80) return;
  choose(name, true);
  added.value = "";
}
function confirm() {
  visible.value = false;
  emit(
    "update:modelValue",
    multiple.value ? [...draft.value] : draft.value[0] || "",
  );
}
function clear() {
  emit("update:modelValue", multiple.value ? [] : "");
}
</script>

<template>
  <div class="paged-picker">
    <el-button :aria-label="title" :disabled="disabled" @click="open">{{
      title
    }}</el-button>
    <span
      v-if="values.length"
      class="picker-current"
      :title="values.map(label).join('、')"
    >
      {{ values.slice(0, 3).map(label).join("、")
      }}<template v-if="values.length > 3"> +{{ values.length - 3 }}</template>
    </span>
    <el-button
      v-if="clearable && values.length"
      :disabled="disabled"
      text
      @click="clear"
      >{{ t("清空", "Clear") }}</el-button
    >
    <el-dialog
      v-model="visible"
      :title="title"
      append-to-body
      width="min(840px, 95vw)"
      :close-on-click-modal="false"
    >
      <form class="picker-search" @submit.prevent="search">
        <el-input
          v-model="q"
          :aria-label="t('筛选关键词', 'Filter keyword')"
          maxlength="80"
          clearable
        />
        <el-button type="primary" native-type="submit" :loading="busy">{{
          t("搜索", "Search")
        }}</el-button>
      </form>
      <el-alert v-if="error" :title="error" type="error" :closable="false" />
      <el-table :data="rows" v-loading="busy" :row-key="id" max-height="390">
        <el-table-column :label="t('选择', 'Select')" width="70">
          <template #default="{ row }">
            <el-checkbox
              v-if="multiple"
              :model-value="draft.includes(id(row))"
              :disabled="
                busy ||
                (!!max && draft.length >= max && !draft.includes(id(row)))
              "
              :aria-label="t('选择', 'Select') + ' ' + row[labelKey]"
              @change="(checked) => choose(id(row), !!checked)"
            />
            <el-radio
              v-else
              :model-value="draft[0]"
              :value="id(row)"
              :disabled="busy"
              :aria-label="t('选择', 'Select') + ' ' + row[labelKey]"
              @change="choose(id(row), true)"
            />
          </template>
        </el-table-column>
        <el-table-column
          :prop="labelKey"
          :label="t('名称', 'Name')"
          min-width="180"
          show-overflow-tooltip
        />
        <el-table-column
          v-if="detailKey"
          :prop="detailKey"
          :label="t('标识 / 说明', 'Identifier / note')"
          min-width="150"
          show-overflow-tooltip
        />
      </el-table>
      <el-pagination
        v-model:current-page="page"
        v-model:page-size="size"
        :total="total"
        :page-sizes="[10, 20, 50]"
        layout="total,sizes,prev,pager,next"
        @current-change="load"
        @size-change="search"
      />
      <div v-if="multiple && draft.length" class="picker-chosen">
        <span>{{ t("已选", "Selected") }} {{ draft.length }}</span>
        <el-tag
          v-for="value in draft"
          :key="value"
          closable
          @close="choose(value, false)"
          >{{ label(value) }}</el-tag
        >
      </div>
      <form v-if="allowCreate" class="picker-search" @submit.prevent="add">
        <el-input
          v-model="added"
          aria-label="新标签名称"
          placeholder="新标签名称"
          maxlength="80"
        />
        <el-button
          native-type="submit"
          :disabled="!added.trim() || (!!max && draft.length >= max)"
          >加入新标签</el-button
        >
      </form>
      <template #footer>
        <el-button @click="visible = false">{{
          t("取消", "Cancel")
        }}</el-button>
        <el-button
          type="primary"
          :disabled="busy || (!multiple && !draft.length)"
          @click="confirm"
          >{{ t("确认选择", "Confirm selection") }}</el-button
        >
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.paged-picker,
.picker-search,
.picker-chosen {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  min-width: 0;
}
.picker-current {
  max-width: 320px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.picker-search,
.picker-chosen {
  margin: 12px 0;
}
.picker-search .el-input {
  width: min(100%, 300px);
}
.el-pagination {
  flex-wrap: wrap;
  margin-block: 12px;
}
</style>
