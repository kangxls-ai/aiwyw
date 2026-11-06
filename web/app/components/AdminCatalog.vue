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
  products = ref<Array<{ id: string; nameZh: string }>>([]),
  id = ref<string | null>(null),
  editing = ref(false),
  busy = ref(false),
  error = ref(""),
  notice = ref(""),
  knownTags = ref<string[]>([]),
  base = ref("");
const changed = useState("catalog-changed", () => 0);
const applicationCount = computed(
  () => sections.value.filter((s) => s.config.mode !== "ARTICLES").length,
);
const empty = (): SectionConfig => ({
  titleZh: "",
  titleEn: "",
  subtitleZh: "",
  subtitleEn: "",
  icon: "star",
  enabled: true,
  position: sections.value.length,
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
  knownTags.value = (
    await request<{ tags: string[] }>("/api/admin/catalog/products/facets")
  ).tags;
  [sections.value, products.value] = await Promise.all([
    request("/api/admin/catalog/sections"),
    request("/api/admin/catalog/products/options"),
  ]);
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
    ? { ...structuredClone(toRaw(value.config)), revision: value.revision }
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
function select(id: string, checked: boolean) {
  form.value.productIds = checked
    ? [...form.value.productIds, id]
    : form.value.productIds.filter((v) => v !== id);
}
function move(index: number, direction: number) {
  const values = [...form.value.productIds],
    next = index + direction;
  if (next < 0 || next >= values.length) return;
  [values[index], values[next]] = [values[next]!, values[index]!];
  form.value.productIds = values;
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
          应用展示栏目最多6个（含停用）；文章栏目独立维护，API固定在顶部导航末尾。
        </p>
      </div>
      <div class="admin-table-actions">
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
      </div>
    </div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <p v-if="notice" class="alert success" role="status">{{ notice }}</p>
    <el-card v-if="!editor" shadow="never"
      ><el-table
        :default-sort="{ prop: 'createdAt', order: 'descending' }"
        :data="sections"
        row-key="id"
        ><ObjectColumns time-prop="createdAt" /><el-table-column
          prop="config.position"
          sortable
          :sort-orders="['descending', 'ascending']"
          label="顺序"
          width="80"
          ><template #default="{ row }">{{
            row.config.position
          }}</template></el-table-column
        ><el-table-column
          prop="config.titleZh"
          sortable
          :sort-orders="['descending', 'ascending']"
          label="栏目"
          min-width="180"
          ><template #default="{ row }">{{
            row.config.titleZh
          }}</template></el-table-column
        ><el-table-column
          prop="config.mode"
          sortable
          :sort-orders="['descending', 'ascending']"
          label="选品"
          min-width="130"
          ><template #default="{ row }">{{
            {
              MANUAL: "人工选品",
              TAG: "按标签",
              AUTO: "自动排序",
              ARTICLES: "文章与教程导航",
            }[row.config.mode as "MANUAL" | "TAG" | "AUTO" | "ARTICLES"]
          }}</template></el-table-column
        ><el-table-column
          prop="config.enabled"
          sortable
          :sort-orders="['descending', 'ascending']"
          label="状态"
          width="90"
          ><template #default="{ row }"
            ><el-tag :type="row.config.enabled ? 'success' : 'info'">{{
              row.config.enabled ? "显示" : "停用"
            }}</el-tag></template
          ></el-table-column
        ><el-table-column
          align="center"
          header-align="center"
          label="操作"
          width="160"
          ><template #default="{ row }"
            ><el-button link type="primary" @click="open(row as AdminSection)"
              >编辑</el-button
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
        <el-form-item label="顺序"
          ><el-input-number
            v-model="form.position"
            aria-label="栏目顺序"
            :min="0"
            :max="1000"
            controls-position="right" /></el-form-item
        ><el-form-item v-if="form.mode !== 'ARTICLES'" label="首页显示数量"
          ><el-input-number
            v-model="form.limit"
            :min="1"
            :max="60"
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
            ><el-option value="MANUAL" label="人工选品" /><el-option
              value="TAG"
              label="按标签筛选" /><el-option
              value="AUTO"
              label="自动选品" /><el-option
              value="ARTICLES"
              label="文章与教程（顶部导航）"
              :disabled="
                sections.some(
                  (s) => s.config.mode === 'ARTICLES' && s.id !== id,
                )
              " /></el-select></el-form-item
        ><el-form-item v-if="form.mode !== 'ARTICLES'" label="排序依据"
          ><el-select v-model="form.sort"
            ><el-option value="LATEST" label="首次上架时间" /><el-option
              value="VIEWS"
              label="浏览量" /><el-option
              value="DOWNLOADS"
              label="下载量" /></el-select
        ></el-form-item>
        <el-form-item
          v-if="
            form.mode !== 'ARTICLES' &&
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
          ><el-select
            v-model="form.tags"
            multiple
            filterable
            :reserve-keyword="false"
            allow-create
            default-first-option
            ><el-option
              v-for="tag in knownTags"
              :key="tag"
              :label="tag"
              :value="tag" /></el-select
        ></el-form-item>
        <div v-if="form.mode === 'MANUAL'" class="full">
          <h3>选择应用</h3>
          <el-checkbox-group v-model="form.productIds"
            ><el-checkbox
              v-for="product in products"
              :key="product.id"
              :value="product.id"
              >{{ product.nameZh }}</el-checkbox
            ></el-checkbox-group
          >
          <div
            v-for="(productId, index) in form.productIds"
            :key="productId"
            class="catalog-editor-actions"
          >
            <span
              >{{ index + 1 }}.
              {{ products.find((p) => p.id === productId)?.nameZh }}</span
            ><el-button size="small" @click="move(index, -1)">上移</el-button
            ><el-button size="small" @click="move(index, 1)">下移</el-button>
          </div>
          <p class="admin-muted">未发布的选择会保留，首页只展示已上架应用。</p>
        </div>
      </el-form></el-card
    >
  </section>
</template>
