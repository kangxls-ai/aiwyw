<script setup lang="ts">
import {businessTime} from "~/utils/commerce";
/** 每个页签独立持有商品ID、草稿、上传与编辑器；保存后更新当前页签，不另开重复编辑页。 */
import { markdoIntroduction } from "~/data/markdoIntroduction";
import { ElMessageBox } from "element-plus";
import type { CatalogProduct, ProductContent } from "~/types/catalog";
const props = defineProps<{ tabId: string; initialProductId: string | null }>();
const { request, message } = useApi(),
  { dirty, registerSave, updateEditor, close } = useManagementTabs();
const id = ref(props.initialProductId),
  loading = ref(true),
  saving = ref(false),
  publishing = ref(false),
  error = ref(""),
  notice = ref(""),
  base = ref(""),
  knownTags = ref<string[]>([]);
const information = ref<CatalogProduct | null>(null),
  history = ref<
    Array<{
      id: string;
      created_at: number;
      sha256: string;
      created_by_name?: string;
    }>
  >([]),
  showHistory = ref(false);
const uploads = reactive<Record<string, boolean>>({}),
  uploading = computed(() => Object.values(uploads).some(Boolean));
const empty = (): ProductContent => ({
  slug: "",
  nameZh: "",
  nameEn: "",
  summaryZh: "",
  summaryEn: "",
  bodyZh: "",
  bodyEn: "",
  tags: [],
  iconUrl: "",
  screenshots: [],
  type: "SOFTWARE",
  pricePoints: 0,
  version: "",
  deliveryMode: "DOWNLOAD",
  downloadUrl: "",
  revision: 0,
});
const form = ref(empty()),
  changed = useState("catalog-changed", () => 0);
const snapshot = () => JSON.stringify(form.value);
watch([() => snapshot(), uploading], () => {
  if (!loading.value)
    dirty.value[props.tabId] = snapshot() !== base.value || uploading.value;
});
async function fillMarkdo() {
  try {
    await ElMessageBox.confirm(
      "填入已核对仓库的双语介绍和建议标签？会替换当前名称、摘要和图文，价格、版本、文件及截图保留。保存前可继续编辑。",
      "填入MarkDo介绍",
      { confirmButtonText: "填入介绍", cancelButtonText: "取消" },
    );
    Object.assign(form.value, structuredClone(markdoIntroduction));
  } catch {
    /* 取消保持原稿 */
  }
}
async function load() {
  loading.value = true;
  try {
    if (id.value) {
      information.value = await request<CatalogProduct>(
        `/api/admin/catalog/products/${id.value}`,
      );
      form.value = {
        ...information.value.content,
        revision: information.value.revision,
      };
      await updateEditor(props.tabId, id.value, form.value.nameZh);
    }
    knownTags.value = (
      await request<{ tags: string[] }>("/api/admin/catalog/products/facets")
    ).tags;
    base.value = snapshot();
    dirty.value[props.tabId] = false;
    if (id.value && information.value?.publicationId)
      history.value = await request(
        `/api/admin/catalog/products/${id.value}/publications`,
      );
  } catch (e) {
    error.value = message(e);
  } finally {
    loading.value = false;
  }
}
let savePromise: Promise<boolean> | null = null;
/** 批量关闭与保存按钮共享同一个Promise，避免并发重复创建商品或覆盖revision。 */
function save(): Promise<boolean> {
  if (savePromise) return savePromise;
  savePromise = saveOnce().finally(() => {
    savePromise = null;
  });
  return savePromise;
}
async function saveOnce() {
  if (uploading.value) {
    error.value = "请等待上传完成再保存";
    return false;
  }
  saving.value = true;
  error.value = "";
  notice.value = "";
  try {
    const body = { ...form.value };
    const result = await request<{ id: string }>(
      id.value
        ? `/api/admin/catalog/products/${id.value}`
        : "/api/admin/catalog/products",
      { method: id.value ? "PUT" : "POST", body },
    );
    id.value = result.id;
    await load();
    changed.value++;
    notice.value = "草稿已保存，发布后更新公开页面";
    return true;
  } catch (e) {
    error.value = message(e);
    return false;
  } finally {
    saving.value = false;
  }
}
async function publish() {
  if (publishing.value) return;
  publishing.value = true;
  try {
    if ((!id.value || dirty.value[props.tabId]) && !(await save())) return;
    if (!id.value) return;
    await request(`/api/admin/catalog/products/${id.value}/publish`, {
      method: "POST",
      body: { revision: form.value.revision },
    });
    await load();
    changed.value++;
    notice.value = "发布成功，静态详情已生成并生效";
  } catch (e) {
    error.value = message(e);
  } finally {
    publishing.value = false;
  }
}
async function rollback(publication: string) {
  if (dirty.value[props.tabId]) {
    error.value = "请先保存当前编辑，再恢复公开版本";
    return;
  }
  if (!window.confirm("恢复此历史公开版本？草稿和下架状态保持不变。")) return;
  try {
    await request(`/api/admin/catalog/products/${id.value}/rollback`, {
      method: "POST",
      body: { publicationId: publication, revision: form.value.revision },
    });
    await load();
    changed.value++;
    notice.value = "公开版本已恢复";
  } catch (e) {
    error.value = message(e);
  }
}
function screenshot(value: string) {
  if (!value) return;
  if (form.value.screenshots.length >= 12) {
    error.value = "最多12张截图";
    return;
  }
  if (!form.value.screenshots.includes(value))
    form.value.screenshots.push(value);
}
function move(index: number, direction: number) {
  const values = [...form.value.screenshots],
    next = index + direction;
  if (next < 0 || next >= values.length) return;
  [values[index], values[next]] = [values[next]!, values[index]!];
  form.value.screenshots = values;
}
let unregister: (() => void) | undefined;
onMounted(() => {
  unregister = registerSave(props.tabId, save);
  load();
});
onBeforeUnmount(() => unregister?.());
watch(changed, async () => {
  try {
    knownTags.value = (
      await request<{ tags: string[] }>("/api/admin/catalog/products/facets")
    ).tags;
  } catch {
    /* 保留当前可用选项，保存错误由主流程展示。 */
  }
});
</script>
<template>
  <section class="application-editor">
    <div class="management-panel-heading">
      <div>
        <span>APPLICATION EDITOR</span>
        <h1>{{ id ? `编辑：${form.nameZh || "应用"}` : "新增应用" }}</h1>
        <p>资料保存为草稿，发布后更新公开页面。</p>
      </div>
      <div class="catalog-editor-actions">
        <el-button v-if="form.slug === 'markdo'" @click="fillMarkdo"
          >填入MarkDo介绍</el-button
        >
        <el-button
          type="primary"
          :disabled="loading || saving || uploading"
          @click="save"
        >
          {{ saving ? "正在保存…" : "保存草稿" }}</el-button
        ><el-button
          :disabled="loading || saving || uploading || publishing"
          @click="publish"
        >
          保存并发布</el-button
        ><a
          v-if="information?.publicationId"
          class="button secondary"
          :href="`/apps/${form.slug}`"
          target="_blank"
          rel="noopener"
          >查看详情</a
        ><el-button @click="close(tabId)">关闭页签</el-button>
      </div>
    </div>
    <p v-if="error" class="alert error" role="alert">{{ error }}</p>
    <p v-if="notice" class="alert success" role="status">{{ notice }}</p>
    <p v-if="loading" class="muted">正在读取应用…</p>
    <el-form
      v-if="!loading"
      :model="form"
      label-position="right"
      label-width="128px"
      class="catalog-editor-card admin-form-grid"
      @submit.prevent="save"
    >
      <el-form-item label="应用名称" required
        ><el-input
          v-model="form.nameZh"
          aria-label="应用名称"
          maxlength="100"
          show-word-limit
          clearable
      /></el-form-item>
      <el-form-item label="英文名称"
        ><el-input
          v-model="form.nameEn"
          aria-label="英文名称"
          maxlength="100"
          clearable
      /></el-form-item>
      <el-form-item label="公开地址" required
        ><el-input
          class="field-address"
          v-model="form.slug"
          aria-label="公开地址"
          :disabled="!!information?.publicationId"
          placeholder="例如 markdo"
        /><small>首次发布后固定，改名不会改变详情地址。</small></el-form-item
      >
      <el-form-item label="版本"
        ><el-input
          class="field-short"
          v-model="form.version"
          aria-label="版本"
          maxlength="60"
          placeholder="例如 1.0.0"
      /></el-form-item>
      <el-form-item label="商品类型"
        ><el-select v-model="form.type" aria-label="商品类型"
          ><el-option
            v-for="(label, value) in {
              SOFTWARE: '软件',
              TOOL: '工具',
              API: 'API',
              DOCUMENT: '文档',
              RESOURCE: '资源',
            }"
            :key="value"
            :label="label"
            :value="value" /></el-select
      ></el-form-item>
      <el-form-item label="鼓励值价格"
        ><el-input-number
          v-model="form.pricePoints"
          aria-label="鼓励值价格"
          :min="0"
          :precision="2"
          :step="1"
          controls-position="right"
        /><small>0鼓励值免费。</small></el-form-item
      >
      <el-form-item label="交付方式"
        ><el-select v-model="form.deliveryMode" aria-label="交付方式"
          ><el-option
            v-for="(label, value) in {
              DOWNLOAD: '文件下载',
              EXTERNAL: '外部资源链接',
              LICENSE: '软件激活',
              API: 'API服务',
            }"
            :key="value"
            :label="label"
            :value="value" /></el-select
      ></el-form-item>
      <el-form-item label="标签"
        ><el-select
          v-model="form.tags"
          aria-label="应用标签"
          multiple
          filterable
          :reserve-keyword="false"
          allow-create
          default-first-option
          clearable
          collapse-tags-tooltip
          ><el-option
            v-for="tag in knownTags"
            :key="tag"
            :label="tag"
            :value="tag" /></el-select
      ></el-form-item>
      <el-form-item label="简介"
        ><el-input
          v-model="form.summaryZh"
          aria-label="简介"
          type="textarea"
          :rows="3"
          maxlength="500"
          show-word-limit
      /></el-form-item>
      <el-form-item label="英文简介"
        ><el-input
          v-model="form.summaryEn"
          aria-label="英文简介"
          type="textarea"
          :rows="3"
          maxlength="500"
          show-word-limit
      /></el-form-item>
      <div class="full">
        <AssetControl
          v-model="form.iconUrl"
          purpose="IMAGE"
          label="应用图标"
          @busy="uploads.icon = $event"
        />
      </div>
      <div class="full">
        <AssetControl
          model-value=""
          purpose="IMAGE"
          label="添加截图"
          @update:model-value="screenshot"
          @busy="uploads.screenshots = $event"
        />
        <div class="editor-screenshots">
          <article v-for="(url, index) in form.screenshots" :key="url">
            <el-image
              :src="url"
              :preview-src-list="form.screenshots"
              :initial-index="index"
              :alt="`截图${index + 1}`"
              fit="contain"
            />
            <div>
              <el-button size="small" @click="move(index, -1)">上移</el-button
              ><el-button size="small" @click="move(index, 1)">下移</el-button
              ><el-button
                size="small"
                type="danger"
                plain
                @click="form.screenshots.splice(index, 1)"
                >移除</el-button
              >
            </div>
          </article>
        </div>
      </div>
      <div class="full">
        <SummernoteEditor
          v-model="form.bodyZh"
          label="图文介绍"
          @busy="uploads.bodyZh = $event"
        />
      </div>
      <div class="full">
        <SummernoteEditor
          v-model="form.bodyEn"
          label="英文图文介绍"
          @busy="uploads.bodyEn = $event"
        />
      </div>
      <div class="full">
        <AssetControl
          v-model="form.downloadUrl"
          purpose="DOWNLOAD"
          label="下载内容"
          @busy="uploads.download = $event"
        /><small>文件按后台存储配置保存，发布后通过授权入口交付。</small>
      </div>
    </el-form>
    <section v-if="history.length" class="catalog-editor-card">
      <el-button class="button secondary" @click="showHistory = !showHistory">
        {{ showHistory ? "收起发布记录" : "查看发布记录" }}
      </el-button>
      <div v-if="showHistory">
        <article v-for="item in history" :key="item.id" class="catalog-history">
          <p class="admin-muted">
            ID：{{ item.id }} · 创建人：{{
              item.created_by_name || "历史未记录"
            }}
          </p>
          <time>{{
            businessTime(item.created_at)
          }}</time>
          <div>{{ item.id }}</div>
          <small>SHA-256：{{ item.sha256 }}</small>
          <div class="catalog-editor-actions">
            <el-button
              :disabled="saving || publishing"
              @click="rollback(item.id)"
            >
              恢复此版本
            </el-button>
          </div>
        </article>
      </div>
    </section>
  </section>
</template>
