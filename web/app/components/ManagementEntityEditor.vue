<script setup lang="ts">
/** 容器统一页签入口，业务保存仍由原模块及服务端权限负责。 */
const props = defineProps<{
  tab: import("~/composables/useManagementTabs").ManagementTab;
}>();
const { open, dirty } = useManagementTabs();
const completed = useState<Record<string, boolean>>(
  "management-editor-completed",
  () => ({}),
);
watch(
  () => dirty.value[props.tab.id],
  (value) => {
    if (value) completed.value[props.tab.id] = false;
  },
);
</script>
<template>
  <div>
    <div class="management-panel-heading">
      <h1>{{ tab.label }}</h1>
      <el-button @click="open(tab.entityType!)">返回列表</el-button>
    </div>
    <el-alert
      v-if="completed[tab.id]"
      title="保存成功，列表已更新。可继续处理其他页签或返回列表。"
      type="success"
      :closable="false"
    />
    <AdminSupport
      v-if="tab.entityType === 'conversations'"
      view="conversations"
      editor
      :tab-id="tab.id"
      :initial-id="tab.entityId"
    />
    <AdminSeries
      v-else-if="tab.entityType === 'series'"
      editor
      :tab-id="tab.id"
      :initial-id="tab.entityId"
    />
    <AdminContent
      v-else-if="['articles', 'faq'].includes(tab.entityType!)"
      :view="tab.entityType!"
      editor
      :tab-id="tab.id"
      :initial-id="tab.entityId"
    />
    <AdminApi
      v-else-if="tab.entityType === 'plans'"
      view="plans"
      editor
      :tab-id="tab.id"
      :api-id="tab.entityId"
    />
    <AdminPackages
      v-else-if="tab.entityType === 'packages'"
      editor
      :tab-id="tab.id"
      :initial-id="tab.entityId"
    />
    <AdminRoles
      v-else-if="tab.entityType === 'roles'"
      editor
      :tab-id="tab.id"
      :initial-id="tab.entityId"
    />
    <AdminUsers
      v-else-if="tab.entityType === 'users'"
      view="users"
      editor
      :tab-id="tab.id"
      :initial-id="tab.entityId"
    />
    <AdminTags
      v-else-if="tab.entityType === 'tags'"
      editor
      :tab-id="tab.id"
      :initial-id="tab.entityId"
    />
    <AdminCatalog
      v-else-if="tab.entityType === 'sections'"
      editor
      :tab-id="tab.id"
      :initial-id="tab.entityId"
      :initial-mode="tab.preset"
    />
  </div>
</template>
