<script setup lang="ts">
/** 配置与文件元数据分开授权；保存配置不移动历史文件，秘密留空保留且不回显。 */
import type { StorageAsset, StoragePage } from "~/types/storage";
interface Entry {
  definition: {
    key: string;
    label: string;
    type: string;
    secret: boolean;
    help: string;
    min: number;
    max: number;
    options: string[];
  };
  value: string;
  configured: boolean;
  revision: number;
}
const { request, message } = useApi(),
  { registerSave, dirty } = useManagementTabs(),
  user = useState<any>("admin-user", () => null);
const isAdmin = computed(() => user.value?.menus?.includes("settings")),
  panel = ref("files"),
  entries = ref<Entry[]>([]),
  values = reactive<Record<string, string>>({}),
  clears = reactive<Record<string, boolean>>({});
const {
  upload,
  busy: uploading,
  progress,
  error: uploadError,
  cancel,
} = useAssetUpload();
const data = ref<StoragePage>({ items: [], total: 0, page: 1, pageSize: 20 }),
  q = ref(""),
  purpose = ref(""),
  status = ref(""),
  page = ref(1),
  busy = ref(false),
  saving = ref(false),
  error = ref(""),
  notice = ref("");
const capabilities = ref({
    provider: "LOCAL",
    imageLimitMb: 10,
    fileLimitMb: 256,
  }),
  changed = useState("storage-changed", () => 0);
const changes = computed(() =>
  entries.value.filter((e) =>
    e.definition.secret
      ? !!values[e.definition.key] || clears[e.definition.key]
      : values[e.definition.key] !== e.value,
  ),
);
watch(
  () => changes.value.length,
  (value) => {
    dirty.value.storage = value > 0;
  },
);
const listOrder = useListOrder(load, page, "createdAt");
let loadSequence = 0;
async function load() {
  const sequence = ++loadSequence;
  busy.value = true;
  error.value = "";
  try {
    const [nextData, nextCapabilities] = await Promise.all([
      request("/api/admin/storage/assets", {
        query: {
          sort: listOrder.sort.value,
          direction: listOrder.direction.value,
          q: q.value,
          purpose: purpose.value,
          status: status.value,
          page: page.value,
          pageSize: 20,
        },
      }),
      request("/api/admin/storage/capabilities"),
    ]);
    if (sequence !== loadSequence) return;
    data.value = nextData;
    capabilities.value = nextCapabilities;
    page.value = data.value.page;
  } catch (e) {
    if (sequence !== loadSequence) return;
    error.value = message(e);
  } finally {
    if (sequence === loadSequence) busy.value = false;
  }
}
async function configuration() {
  try {
    entries.value = await request("/api/admin/storage/settings");
    for (const e of entries.value) {
      values[e.definition.key] = e.value;
      clears[e.definition.key] = false;
    }
  } catch (e) {
    error.value = message(e);
  }
}
watch(
  isAdmin,
  (value) => {
    if (value) configuration();
  },
  { immediate: true },
);
async function save() {
  if (!changes.value.length) return true;
  saving.value = true;
  error.value = "";
  notice.value = "";
  try {
    await request("/api/admin/storage/settings", {
      method: "PUT",
      body: {
        changes: changes.value.map((e) => ({
          key: e.definition.key,
          value: values[e.definition.key],
          clear: !!clears[e.definition.key],
          revision: e.revision,
        })),
      },
    });
    await configuration();
    await load();
    dirty.value.storage = false;
    changed.value++;
    notice.value = "存储配置已保存，新上传将使用当前配置";
    return true;
  } catch (e) {
    error.value = message(e);
    return false;
  } finally {
    saving.value = false;
  }
}
async function check() {
  error.value = "";
  notice.value = "";
  saving.value = true;
  try {
    await request("/api/admin/storage/check", { method: "POST" });
    notice.value = "存储连接检查通过";
  } catch (e) {
    error.value = message(e);
  } finally {
    saving.value = false;
  }
}
async function refreshCredentials() {
  if (
    !window.confirm(
      "将当前凭据同步到相同地址、Bucket和区域的历史对象存储连接？已有文件位置不会改变。",
    )
  )
    return;
  saving.value = true;
  try {
    const result = await request<{ profiles: number }>(
      "/api/admin/storage/refresh-credentials",
      { method: "POST" },
    );
    notice.value = `已更新 ${result.profiles} 个同位置历史连接的凭据`;
  } catch (e) {
    error.value = message(e);
  } finally {
    saving.value = false;
  }
}
async function reloadConfig() {
  if (
    changes.value.length &&
    !window.confirm("重新读取将放弃未保存的存储配置，是否继续？")
  )
    return;
  await configuration();
}
async function fileUpload(file: File | undefined, kind: "IMAGE" | "DOWNLOAD") {
  if (!file) return;
  try {
    await upload(file, kind);
    notice.value = "文件已保存，可在应用编辑中选择";
    await load();
  } catch {}
}
async function remove(asset: StorageAsset) {
  if (
    !window.confirm(
      `删除“${asset.originalName}”？正在使用或被历史版本引用的文件不能删除。`,
    )
  )
    return;
  error.value = "";
  try {
    await request(`/api/admin/storage/assets/${asset.id}`, {
      method: "DELETE",
    });
    changed.value++;
    notice.value = "文件已删除";
    await load();
  } catch (e) {
    await load();
    error.value = message(e);
  }
}
const bytes = (n: number) =>
  n >= 1048576
    ? `${(n / 1048576).toFixed(2)} MB`
    : `${(n / 1024).toFixed(1)} KB`;
const labels: Record<string, string> = {
  READY: "可用",
  UPLOADING: "上传中",
  DELETE_PENDING: "删除待重试",
  DELETED: "已删除",
  LOCAL: "本地存储",
  S3: "对象存储",
  EXTERNAL: "外部链接",
};
let unregister: (() => void) | undefined;
onMounted(() => {
  unregister = registerSave("storage", save);
  load();
});
onBeforeUnmount(() => unregister?.());
watch(changed, load);
</script>
<template>
  <section>
    <div class="management-panel-heading">
      <div>
        <span>STORAGE MANAGEMENT</span>
        <h1>存储管理</h1>
        <p>
          当前上传模式：{{
            labels[capabilities.provider]
          }}。历史资源保留原存储连接。
        </p>
      </div>
      <el-radio-group v-model="panel"
        ><el-radio-button value="files">文件管理</el-radio-button
        ><el-radio-button v-if="isAdmin" value="configuration"
          >存储配置</el-radio-button
        ></el-radio-group
      >
    </div>
    <el-alert
      v-if="error || uploadError"
      :title="error || uploadError"
      type="error"
      :closable="false"
    />
    <p v-if="notice" class="alert success" role="status">{{ notice }}</p>
    <template v-if="panel === 'files'"
      ><el-card shadow="never"
        ><div class="catalog-editor-actions">
          <el-upload
            :auto-upload="false"
            :show-file-list="false"
            accept="image/png,image/jpeg,image/webp"
            :disabled="uploading || capabilities.provider === 'EXTERNAL'"
            :on-change="(file) => fileUpload(file.raw, 'IMAGE')"
            ><el-button type="primary" plain>上传图片</el-button></el-upload
          ><el-upload
            :auto-upload="false"
            :show-file-list="false"
            :disabled="uploading || capabilities.provider === 'EXTERNAL'"
            :on-change="(file) => fileUpload(file.raw, 'DOWNLOAD')"
            ><el-button>上传下载内容</el-button></el-upload
          ><span class="admin-muted"
            >图片最多{{ capabilities.imageLimitMb }}MB，下载内容最多{{
              capabilities.fileLimitMb
            }}MB。</span
          >
        </div>
        <el-progress v-if="uploading" :percentage="progress" /><el-form
          label-position="right"
          label-width="128px"
          class="admin-filter-grid"
          style="margin-top: 20px"
          @submit.prevent="
            page = 1;
            load();
          "
          ><el-form-item label="文件名称"
            ><el-input
              v-model="q"
              aria-label="文件名称查询"
              clearable
              @keyup.enter="
                page = 1;
                load();
              " /></el-form-item
          ><el-form-item label="文件类型"
            ><el-select v-model="purpose" clearable aria-label="文件类型筛选"
              ><el-option value="IMAGE" label="图片" /><el-option
                value="DOWNLOAD"
                label="下载内容" /></el-select></el-form-item
          ><el-form-item label="文件状态"
            ><el-select v-model="status" clearable aria-label="文件状态筛选"
              ><el-option value="READY" label="可用" /><el-option
                value="DELETE_PENDING"
                label="删除待重试" /><el-option
                value="DELETED"
                label="已删除" /></el-select
          ></el-form-item>
          <div>
            <el-button
              type="primary"
              :loading="busy"
              @click="
                page = 1;
                load();
              "
              >查询</el-button
            >
          </div></el-form
        ></el-card
      >
      <el-card shadow="never" style="margin-top: 20px"
        ><el-table
          :default-sort="listOrder.defaultSort"
          @sort-change="listOrder.change"
          :data="data.items"
          row-key="id"
          v-loading="busy"
          ><el-table-column
            prop="createdAt"
            label="创建时间"
            sortable="custom"
            :sort-orders="['descending', 'ascending']"
            min-width="165"
            ><template #default="{ row }">{{
              new Date(Number(row.createdAt)).toLocaleString("zh-CN", {
                hour12: false,
                timeZone: "Asia/Shanghai",
              })
            }}</template></el-table-column
          ><el-table-column
            prop="originalName"
            sortable="custom"
            :sort-orders="['descending', 'ascending']"
            label="文件"
            min-width="230"
            ><template #default="{ row }"
              ><div class="application-table-name">
                <el-image
                  v-if="row.purpose === 'IMAGE' && row.status === 'READY'"
                  :src="row.previewUrl"
                  :preview-src-list="[row.previewUrl]"
                  fit="contain"
                  style="width: 40px; height: 40px"
                /><span>{{ row.originalName }}</span>
              </div></template
            ></el-table-column
          ><el-table-column
            prop="purpose"
            sortable="custom"
            :sort-orders="['descending', 'ascending']"
            label="类型 / 存储"
            width="140"
            ><template #default="{ row }"
              >{{ row.purpose === "IMAGE" ? "图片" : "下载内容" }} /
              {{ labels[row.provider] }}</template
            ></el-table-column
          ><el-table-column
            prop="sizeBytes"
            sortable="custom"
            :sort-orders="['descending', 'ascending']"
            label="大小"
            width="110"
            ><template #default="{ row }">{{
              bytes(row.sizeBytes)
            }}</template></el-table-column
          ><el-table-column
            prop="referenceCount"
            sortable="custom"
            :sort-orders="['descending', 'ascending']"
            label="引用"
            width="70"
          /><el-table-column
            prop="status"
            sortable="custom"
            :sort-orders="['descending', 'ascending']"
            label="状态"
            width="120"
            ><template #default="{ row }"
              ><el-tag>{{ labels[row.status] }}</el-tag></template
            ></el-table-column
          ><el-table-column label="操作" width="160"
            ><template #default="{ row }"
              ><a
                v-if="row.status === 'READY'"
                :href="row.previewUrl"
                target="_blank"
                rel="noopener"
                >{{ row.purpose === "IMAGE" ? "预览" : "下载" }}</a
              ><el-button
                v-if="row.status !== 'DELETED'"
                link
                type="danger"
                :disabled="
                  row.referenceCount > 0 ||
                  uploading ||
                  (row.status === 'UPLOADING' &&
                    Date.now() - row.createdAt < 600000)
                "
                @click="remove(row as StorageAsset)"
                >{{
                  row.status === "DELETE_PENDING" ? "重试删除" : "删除"
                }}</el-button
              ></template
            ></el-table-column
          ></el-table
        ><el-pagination
          v-model:current-page="page"
          :page-size="data.pageSize"
          :total="data.total"
          layout="total,prev,pager,next"
          @current-change="load" /></el-card
    ></template>
    <el-card v-else-if="isAdmin" shadow="never"
      ><p class="admin-muted">
        切换模式只影响新上传；密钥留空保留原值，明确勾选清除才删除。
      </p>
      <div class="catalog-editor-actions">
        <el-button
          type="primary"
          :disabled="!changes.length"
          :loading="saving"
          @click="save"
          >保存存储配置</el-button
        ><el-button :disabled="saving" @click="reloadConfig">重新读取</el-button
        ><el-button :disabled="saving || !!changes.length" @click="check"
          >检查已保存连接</el-button
        ><el-button
          v-if="values['storage.provider'] === 'S3'"
          :disabled="saving || !!changes.length"
          @click="refreshCredentials"
          >同步历史连接凭据</el-button
        >
      </div>
      <el-form
        label-position="right"
        label-width="128px"
        class="admin-form-grid"
        style="margin-top: 24px"
        @submit.prevent="save"
        ><el-form-item
          v-for="entry in entries"
          :key="entry.definition.key"
          :label="entry.definition.label"
          ><div style="width: 100%">
            <el-select
              v-if="entry.definition.type === 'select'"
              v-model="values[entry.definition.key]"
              ><el-option
                v-for="option in entry.definition.options"
                :key="option"
                :value="option"
                :label="labels[option] || option" /></el-select
            ><el-switch
              v-else-if="entry.definition.type === 'boolean'"
              v-model="values[entry.definition.key]"
              active-value="true"
              inactive-value="false"
            /><el-input-number
              v-else-if="entry.definition.type === 'number'"
              :model-value="Number(values[entry.definition.key])"
              @update:model-value="
                (value) => (values[entry.definition.key] = String(value ?? 0))
              "
              :min="entry.definition.min"
              :max="entry.definition.max"
              :precision="0"
              controls-position="right"
            /><el-input
              v-else
              v-model="values[entry.definition.key]"
              :type="entry.definition.secret ? 'password' : 'text'"
              :show-password="entry.definition.secret"
              :disabled="!!clears[entry.definition.key]"
              :placeholder="
                entry.definition.secret
                  ? entry.configured
                    ? '已设置，留空保持原值'
                    : '尚未设置'
                  : ''
              "
              :autocomplete="entry.definition.secret ? 'new-password' : 'off'"
            /><el-checkbox
              v-if="entry.definition.secret && entry.configured"
              v-model="clears[entry.definition.key]"
              >清除已保存的值</el-checkbox
            >
            <p class="admin-muted">{{ entry.definition.help }}</p>
          </div></el-form-item
        ></el-form
      ></el-card
    >
  </section>
</template>
