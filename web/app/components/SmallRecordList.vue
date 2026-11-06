<script setup lang="ts">
/** 仅用于个人资料单行及固定服务入口；业务记录必须使用数据库分页端点。 */
const props = defineProps<{
    rows: any[];
    columns: { key: string; label: string }[];
  }>(),
  { t } = useLanguage();
const filters = reactive({ q: "" }),
  term = ref(""),
  page = ref(1),
  size = ref(10);
const filtered = computed(() =>
  props.rows.filter((row) =>
    Object.values(row)
      .join(" ")
      .toLowerCase()
      .includes(term.value.toLowerCase()),
  ),
);
const items = computed(() =>
  filtered.value.slice((page.value - 1) * size.value, page.value * size.value),
);
</script>
<template>
  <RecordQuery
    v-model="filters"
    :dates="false"
    @search="
      term = filters.q;
      page = 1;
    "
  /><el-table :data="items"
    ><el-table-column
      v-for="column in columns"
      :key="column.key"
      :prop="column.key"
      :label="column.label"
      min-width="170"
      show-overflow-tooltip /><el-table-column
      :label="t('操作', 'Actions')"
      min-width="230"
      align="center"
      ><template #default="{ row }"
        ><slot
          name="actions"
          :row="row" /></template></el-table-column></el-table
  ><el-pagination
    v-model:current-page="page"
    :page-size="size"
    :total="filtered.length"
    layout="total,prev,pager,next"
  />
</template>
