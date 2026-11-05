<script setup lang="ts">
/** 审计页只读；展示操作者与字段键，不应加入敏感配置值。 */
defineOptions({ name: "AdminAudit" });
definePageMeta({ layout: "admin" });
const { request, message } = useApi();
const records = ref<any[]>([]),
  error = ref(""),
  loading = ref(true);
onMounted(async () => {
  try {
    records.value = await request("/api/admin/audit");
  } catch (e) {
    error.value = message(e);
  } finally {
    loading.value = false;
  }
});
const actions: Record<string, string> = {
  LICENSE_POLICY: "维护授权策略",
  LICENSE_ACTIVATE: "激活设备",
  REQUEST_UNBIND: "申请换机",
  APPROVE_UNBIND: "通过换机",
  REJECT_UNBIND: "驳回换机",
  UPLOAD: "上传文件",
  DELETE: "删除文件",
  DELETE_FAILED: "文件删除待重试",
  REFRESH_CREDENTIALS: "更新历史存储凭据",
  SAVE_TAG: "维护标签",
  DELETE_TAG: "删除标签",
  UPDATE: "更新配置",
  CLEAR: "清除配置",
  CREATE: "新建商品",
  SAVE: "保存草稿",
  PUBLISH: "发布",
  UNLIST: "下架",
  ROLLBACK: "恢复版本",
  CREATE_SECTION: "新增栏目",
  SAVE_SECTION: "保存栏目",
  DELETE_SECTION: "删除栏目",
};
const time = (value: number) =>
  new Intl.DateTimeFormat("zh-CN", {
    timeZone: "Asia/Shanghai",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  }).format(value);
</script>
<template>
  <div>
    <div class="page-heading">
      <div>
        <div class="eyebrow">CHANGE HISTORY</div>
        <h1>操作记录</h1>
        <p class="muted">
          最近100条配置、存储、栏目及商品发布记录，时间统一为北京时间。敏感值不写入记录。
        </p>
      </div>
    </div>
    <div v-if="error" class="alert error">{{ error }}</div>
    <div class="panel table-panel">
      <div v-if="loading" class="empty-state">正在加载…</div>
      <el-table
        v-else-if="records.length"
        :data="records"
        :default-sort="{ prop: 'created_at', order: 'descending' }"
        row-key="id"
        ><el-table-column
          label="时间"
          prop="created_at"
          sortable
          :sort-orders="['descending', 'ascending']"
          min-width="155"
          ><template #default="{ row }">{{
            time(row.created_at)
          }}</template></el-table-column
        ><el-table-column
          prop="nickname"
          sortable
          :sort-orders="['descending', 'ascending']"
          label="操作人"
          min-width="190"
        /><el-table-column
          prop="config_key"
          sortable
          :sort-orders="['descending', 'ascending']"
          label="配置项 / 资源ID"
          min-width="250"
        /><el-table-column
          label="操作"
          prop="action_name"
          sortable
          :sort-orders="['descending', 'ascending']"
          min-width="140"
          ><template #default="{ row }">{{
            actions[row.action_name] || row.action_name
          }}</template></el-table-column
        ><el-table-column
          label="版本 / 摘要"
          prop="detail"
          sortable
          :sort-orders="['descending', 'ascending']"
          min-width="180"
          ><template #default="{ row }">{{
            row.detail || row.revision
          }}</template></el-table-column
        ></el-table
      >
      <div v-else class="empty-state">
        尚无操作记录。保存配置、商品或栏目后会在这里显示。
      </div>
    </div>
  </div>
</template>
