<script setup lang="ts">
const { staffApi } = useStaffPaths();
import type { StorageAsset, StoragePage } from "~/types/storage";
const props = defineProps<{ purpose: "IMAGE" | "DOWNLOAD" }>();
const emit = defineEmits<{ selected: [asset: StorageAsset]; close: [] }>();
const { request, message } = useApi(),
  q = ref(""),
  page = ref(1),
  data = ref<StoragePage>({ items: [], total: 0, page: 1, pageSize: 12 }),
  error = ref(""),
  busy = ref(false);
async function load() {
  busy.value = true;
  error.value = "";
  try {
    data.value = await request("/api/admin/storage/assets", {
      query: {
        q: q.value,
        purpose: props.purpose,
        status: "READY",
        page: page.value,
        pageSize: 12,
      },
    });
    page.value = data.value.page;
  } catch (e) {
    error.value = message(e);
  } finally {
    busy.value = false;
  }
}
onMounted(() => {
  load();
});
</script>
<template>
  <el-dialog
    :model-value="true"
    :title="purpose === 'IMAGE' ? '选择图片' : '选择下载文件'"
    width="min(850px,94vw)"
    :close-on-click-modal="false"
    @close="emit('close')"
    ><el-form
      inline
      @submit.prevent="
        page = 1;
        load();
      "
      ><el-form-item label="文件名称"
        ><el-input
          v-model="q"
          aria-label="搜索文件"
          placeholder="搜索文件名"
          clearable
          @keyup.enter="
            page = 1;
            load();
          " /></el-form-item
      ><el-button
        type="primary"
        :loading="busy"
        @click="
          page = 1;
          load();
        "
        >查询</el-button
      ></el-form
    ><el-alert v-if="error" :title="error" type="error" :closable="false" />
    <div class="asset-library-grid">
      <el-button
        v-for="asset in data.items"
        :key="asset.id"
        class="asset-library-item"
        :aria-label="`选择 ${asset.originalName}`"
        @click="emit('selected', asset)"
        ><el-image
          v-if="purpose === 'IMAGE'"
          :src="staffApi(asset.previewUrl)"
          fit="contain"
        /><SiteIcon v-else name="folder" /><strong>{{
          asset.originalName
        }}</strong
        ><small>{{ (asset.sizeBytes / 1024).toFixed(1) }} KB</small></el-button
      >
    </div>
    <el-empty
      v-if="!data.items.length && !busy"
      description="暂无文件，请先上传" /><el-pagination
      v-model:current-page="page"
      :page-size="data.pageSize"
      :total="data.total"
      layout="total,prev,pager,next"
      @current-change="load"
  /></el-dialog>
</template>
