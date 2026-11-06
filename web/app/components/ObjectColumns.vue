<script setup lang="ts">
/** 对象主键不截断存储，窄列省略显示并提供完整悬浮文本；无历史依据不伪造时间或创建人。 */
import { businessTime } from "~/utils/commerce";
const { t: translate } = useLanguage();
const props = withDefaults(
  defineProps<{
    timeProp?: string;
    serverSort?: boolean;
    showCreator?: boolean;
    localized?: boolean;
  }>(),
  {
    timeProp: "created_at",
    serverSort: false,
    showCreator: true,
    localized: false,
  },
);
// 后台工作台沿用中文；用户账户/API页面随用户选择的中英文切换。
const t = (cn: string, en: string) =>
  props.localized ? translate(cn, en) : cn;
const creator = (row: any) => {
  if (row.created_by_id === "SYSTEM") return t("系统", "System");
  if (String(row.created_by_id || "").startsWith("GUEST:"))
    return t("游客", "Guest");
  if (!row.created_by_id) return t("历史未记录", "Not recorded");
  if (row.created_by_name === "已删除或未知账户")
    return t("已删除或未知账户", "Deleted or unknown account");
  return row.created_by_name;
};
const identity = (row: any) =>
  row.id ?? row.purchase_id ?? row.config_key ?? "—";
</script>
<template>
  <el-table-column
    :label="t('主键ID', 'ID')"
    prop="id"
    min-width="180"
    show-overflow-tooltip
    ><template #default="{ row }"
      ><code>{{ identity(row) }}</code></template
    ></el-table-column
  >
  <el-table-column
    :label="t('创建时间', 'Created at')"
    :prop="timeProp"
    min-width="175"
    :sortable="serverSort ? 'custom' : true"
    :sort-orders="['descending', 'ascending']"
    ><template #default="{ row }">{{
      Number(row[timeProp] ?? row.created_at ?? row.createdAt) > 0
        ? businessTime(row[timeProp] ?? row.created_at ?? row.createdAt)
        : t("历史未记录", "Not recorded")
    }}</template></el-table-column
  >
  <el-table-column
    v-if="showCreator"
    :label="t('创建人', 'Created by')"
    min-width="140"
    show-overflow-tooltip
    ><template #default="{ row }"
      ><span :title="row.created_by_id || undefined">{{
        creator(row)
      }}</span></template
    ></el-table-column
  >
</template>
