<script setup lang="ts">
/** 统一查询区：筛选与页码在父列表维护，提交才发请求；不在浏览器截断大型私有集合。 */
const props = withDefaults(
  defineProps<{
    modelValue: { q: string; state?: string; dates?: string[] | null };
    states?: { value: string; label: string }[];
    dates?: boolean;
    loading?: boolean;
  }>(),
  { dates: true },
);
const emit = defineEmits<{ search: [] }>(),
  { t } = useLanguage();
function reset() {
  props.modelValue.q = "";
  props.modelValue.state = "";
  props.modelValue.dates = null;
  emit("search");
}
</script>
<template>
  <el-form
    inline
    class="record-query"
    label-position="right"
    @submit.prevent="emit('search')"
    ><el-form-item :label="t('关键词', 'Keyword')"
      ><el-input
        v-model="modelValue.q"
        maxlength="100"
        clearable
        :aria-label="t('列表关键词', 'List keyword')"
        style="width: 220px" /></el-form-item
    ><el-form-item v-if="states?.length" :label="t('状态', 'Status')"
      ><el-select
        v-model="modelValue.state"
        clearable
        :aria-label="t('列表状态', 'List status')"
        style="width: 160px"
        ><el-option
          v-for="s in states"
          :key="s.value"
          :value="s.value"
          :label="s.label" /></el-select></el-form-item
    ><el-form-item v-if="dates" :label="t('创建时间', 'Created at')"
      ><el-date-picker
        v-model="modelValue.dates"
        type="daterange"
        value-format="YYYY-MM-DD"
        format="YYYY-MM-DD HH:mm:ss"
        :start-placeholder="t('开始日期', 'Start date')"
        :end-placeholder="t('结束日期', 'End date')"
        style="width: 400px; max-width: 100%" /></el-form-item
    ><el-form-item
      ><el-button type="primary" native-type="submit" :loading="loading">{{
        t("查询", "Search")
      }}</el-button
      ><el-button @click="reset">{{ t("重置", "Reset") }}</el-button
      ><slot /></el-form-item
  ></el-form>
</template>
<style scoped>
.record-query {
  margin: 18px 0;
}
.record-query :deep(.el-form-item) {
  margin-right: 18px;
}
@media (max-width: 650px) {
  .record-query :deep(.el-form-item) {
    display: flex;
    margin-right: 0;
  }
  .record-query :deep(.el-form-item__content) {
    min-width: 0;
  }
  .record-query :deep(.el-date-editor) {
    width: 100% !important;
  }
}
</style>
