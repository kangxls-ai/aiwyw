<script setup lang="ts">
import type { StorageAsset } from "~/types/storage";
const props = defineProps<{
  modelValue: string;
  purpose: "IMAGE" | "DOWNLOAD";
  label: string;
}>();
const emit = defineEmits<{
  "update:modelValue": [value: string];
  busy: [value: boolean];
}>();
const { draftImage } = useStaffPaths();
const { request } = useApi(),
  { busy, progress, error, upload, cancel } = useAssetUpload();
const library = ref(false),
  external = ref(false),
  asset = ref<StorageAsset | null>(null);
watch(busy, (value) => emit("busy", value));
watch(
  () => props.modelValue,
  async (value) => {
    external.value =
      !!value &&
      !value.startsWith("asset:") &&
      !value.startsWith("/api/public/media/");
    const id = value.startsWith("asset:")
      ? value.slice(6)
      : value.startsWith("/api/public/media/")
        ? value.split("/").pop()
        : null;
    if (!id) {
      asset.value = null;
      return;
    }
    if (asset.value?.id === id) return;
    try {
      asset.value = await request(`/api/admin/storage/assets/${id}`);
    } catch {
      asset.value = null;
    }
  },
  { immediate: true },
);
function select(value: StorageAsset) {
  asset.value = value;
  emit("update:modelValue", value.url);
  library.value = false;
  external.value = false;
}
async function change(file: File | undefined) {
  if (!file) return;
  try {
    select(await upload(file, props.purpose));
  } catch {}
}
</script>
<template>
  <div class="asset-control">
    <span class="asset-control-label">{{ label }}</span>
    <div class="asset-control-preview">
      <el-image
        v-if="purpose === 'IMAGE' && modelValue"
        :src="draftImage(modelValue)"
        :alt="label"
        :preview-src-list="[draftImage(modelValue)]"
        fit="contain"
        style="width: 72px; height: 72px"
      />
      <div v-if="asset">
        <strong>{{ asset.originalName }}</strong
        ><small
          >{{ (asset.sizeBytes / 1024).toFixed(1) }} KB ·
          {{ asset.provider === "LOCAL" ? "本地存储" : "对象存储" }}</small
        >
      </div>
      <span v-else-if="!modelValue" class="muted">尚未选择</span>
    </div>
    <div class="catalog-editor-actions">
      <el-upload
        :auto-upload="false"
        :show-file-list="false"
        :accept="
          purpose === 'IMAGE' ? 'image/png,image/jpeg,image/webp' : undefined
        "
        :disabled="busy"
        :on-change="(file) => change(file.raw)"
        ><el-button :loading="busy">{{
          purpose === "IMAGE" ? "上传" + label : "上传下载内容"
        }}</el-button></el-upload
      ><el-button :disabled="busy" @click="library = true"
        >从文件库选择</el-button
      ><el-button
        v-if="modelValue"
        :disabled="busy"
        @click="
          asset = null;
          emit('update:modelValue', '');
        "
        >移除</el-button
      ><el-button :disabled="busy" @click="external = !external"
        >使用外部链接</el-button
      >
    </div>
    <div v-if="busy" class="asset-upload-progress">
      <el-progress :percentage="progress" /><el-button @click="cancel"
        >取消上传</el-button
      >
    </div>
    <p v-if="error" class="catalog-error" role="alert">{{ error }}</p>
    <el-input
      v-if="external"
      :model-value="modelValue.startsWith('asset:') ? '' : modelValue"
      placeholder="https://"
      aria-label="外部链接"
      @update:model-value="(value: string) => emit('update:modelValue', value)"
    /><AssetLibraryDialog
      v-if="library"
      :purpose="purpose"
      @selected="select"
      @close="library = false"
    />
  </div>
</template>
