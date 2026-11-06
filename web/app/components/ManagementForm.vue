<script setup lang="ts">
/** 新增页签使用卡片，原有对象编辑弹窗保持交互；不把弹窗专属属性传到普通DOM。 */
defineProps<{
  editor?: boolean;
  modelValue: boolean;
  title?: string;
  width?: string;
  beforeClose?: (done: () => void) => void;
  closeOnClickModal?: boolean;
}>();
const emit = defineEmits<{
  "update:modelValue": [value: boolean];
  opened: [];
}>();
</script>
<template>
  <el-card
    v-if="editor && modelValue"
    class="management-editor-form"
    shadow="never"
  >
    <template v-if="title" #header
      ><h2>{{ title }}</h2></template
    >
    <slot /><template #footer><slot name="footer" /></template>
  </el-card>
  <el-dialog
    v-else-if="!editor"
    :model-value="modelValue"
    :title="title"
    :width="width"
    :before-close="beforeClose"
    :close-on-click-modal="closeOnClickModal ?? false"
    @update:model-value="(value) => emit('update:modelValue', value)"
    @opened="emit('opened')"
  >
    <slot /><template #footer><slot name="footer" /></template>
  </el-dialog>
</template>
<style scoped>
.management-editor-form :deep(.el-card__footer) {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
}
.management-editor-form h2 {
  margin: 0;
}
</style>
