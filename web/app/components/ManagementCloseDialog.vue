<script setup lang="ts">
/** 一个批次只作一次关闭决策；取消不移除任何页签，保存失败保持全部目标页签打开。 */
const { tabs, dirty, closing, closeBusy, closeError, confirmClose } =
  useManagementTabs();
const unsaved = computed(() =>
  tabs.value.filter((t) => closing.value.includes(t.id) && dirty.value[t.id]),
);
</script>
<template>
  <el-dialog
    :model-value="closing.length > 0"
    title="关闭前保存修改？"
    width="min(550px,94vw)"
    :close-on-click-modal="false"
    :close-on-press-escape="!closeBusy"
    :show-close="!closeBusy"
    @close="confirmClose('cancel')"
    ><p>以下页签有未保存内容：</p>
    <ul>
      <li v-for="tab in unsaved" :key="tab.id">{{ tab.label }}</li>
    </ul>
    <p v-if="closeError" class="catalog-error" role="alert">{{ closeError }}</p>
    <template #footer
      ><el-button
        type="primary"
        :loading="closeBusy"
        @click="confirmClose('save')"
        >保存并关闭</el-button
      ><el-button :disabled="closeBusy" @click="confirmClose('discard')"
        >放弃修改并关闭</el-button
      ><el-button :disabled="closeBusy" @click="confirmClose('cancel')"
        >取消</el-button
      ></template
    ></el-dialog
  >
</template>
