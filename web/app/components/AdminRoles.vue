<script setup lang="ts">
/** 只维护菜单集合；角色保存有并发版本校验，未保存内容接入统一页签关闭提示。 */
import { adminMenuGroups } from "~/data/adminNavigation";
import type { TreeInstance } from "element-plus";
import { ElMessageBox, ElMessage } from "element-plus";
const props = defineProps<{
  editor?: boolean;
  tabId?: string;
  initialId?: string | null;
}>();
const { request, message } = useApi(),
  tabState = useEntityEditing(props, "roles", load),
  { dirty, registerSave } = tabState;
const rows = ref<any[]>([]),
  menus = ref<Record<string, string>>({}),
  dialog = ref(false),
  error = ref(""),
  busy = ref(false),
  baseline = ref("");
const form = reactive({ id: "", name: "", menus: [] as string[], revision: 0 });
const grantTree = ref<TreeInstance>();
const menuTree = computed(() =>
  adminMenuGroups.map((group) => ({
    id: "group:" + group.id,
    label: group.label,
    children: group.items
      .filter((item) => menus.value[item.id])
      .map((item) => ({ id: item.id, label: item.label })),
  })),
);
function normalize(keys: string[]) {
  const result = new Set(keys);
  for (const group of menuTree.value)
    if (group.children.some((child) => result.has(child.id)))
      result.add(group.id);
  return [...result];
}
function syncTree() {
  grantTree.value?.setCheckedKeys(form.menus);
}
function checkMenus(
  _node: unknown,
  state: { checkedKeys: (string | number)[] },
) {
  form.menus = normalize(state.checkedKeys.map(String));
  syncTree();
}
async function load() {
  try {
    const result = await request<any>("/api/admin/roles");
    rows.value = result.items;
    menus.value = result.menus;
  } catch (e) {
    error.value = message(e);
  }
}
function beforeClose(done: () => void) {
  if (
    JSON.stringify(form) !== baseline.value &&
    !window.confirm("角色修改尚未保存，确定放弃？")
  )
    return;
  done();
}
function edit(row?: any) {
  if (!props.editor && !row) {
    tabState.openEntityEditor("roles");
    return;
  }
  Object.assign(form, {
    // 与服务器相同的26位小写编码，保留浏览器生成UUID的随机性。
    id:
      row?.id ||
      BigInt("0x" + crypto.randomUUID().replaceAll("-", ""))
        .toString(32)
        .padStart(26, "0"),
    name: row?.name || "",
    menus: row?.menus ? normalize([...row.menus]) : [],
    revision: Number(row?.revision) || 0,
  });
  baseline.value = JSON.stringify(form);
  dialog.value = true;
}
async function save() {
  busy.value = true;
  try {
    await request("/api/admin/roles/" + form.id, { method: "PUT", body: form });
    dialog.value = false;
    await tabState.saved(form.id, form.name, true);
    dirty.value[tabState.key.value] = false;
    await load();
    return true;
  } catch (e) {
    ElMessage.error(message(e));
    return false;
  } finally {
    busy.value = false;
  }
}
async function remove(row: any) {
  try {
    await ElMessageBox.confirm(
      "删除角色前须解除所有用户授权，内置角色不能删除。",
      "删除角色",
      { confirmButtonText: "删除", cancelButtonText: "取消" },
    );
    await request("/api/admin/roles/" + row.id, {
      method: "DELETE",
      query: { revision: row.revision },
    });
    await load();
  } catch (e) {
    if (e !== "cancel" && e !== "close") ElMessage.error(message(e));
  }
}
watch(
  () => JSON.stringify(form),
  (value) =>
    (dirty.value[tabState.key.value] =
      dialog.value && value !== baseline.value),
);
watch(dialog, (value) => {
  if (!value) dirty.value[tabState.key.value] = false;
});
let unregister: (() => void) | undefined;
onMounted(() => {
  unregister = registerSave(tabState.key.value, save);
  load().then(() => {
    if (props.editor)
      edit(
        props.initialId
          ? rows.value.find((r) => r.id === props.initialId)
          : undefined,
      );
  });
});
onBeforeUnmount(() => unregister?.());
</script>
<template>
  <section>
    <div v-if="!editor" class="management-panel-heading">
      <div>
        <h1>角色管理</h1>
        <p>用户可拥有多个角色，菜单权限取并集，服务端实时校验。</p>
      </div>
      <el-button type="primary" @click="edit()">新增角色</el-button>
    </div>
    <el-alert
      v-if="error"
      :title="error"
      type="error"
      :closable="false"
    /><el-card v-if="!editor" shadow="never"
      ><el-table
        :data="rows"
        :default-sort="{ prop: 'created_at', order: 'descending' }"
        ><ObjectColumns /><el-table-column
          prop="name"
          label="角色名称"
          sortable
          :sort-orders="['descending', 'ascending']"
        /><el-table-column align="center" header-align="center" label="操作"
          ><template #default="{ row }"
            ><el-button link type="primary" @click="edit(row)">编辑</el-button
            ><el-button
              v-if="!row.builtin"
              link
              type="danger"
              @click="remove(row)"
              >删除</el-button
            ></template
          ></el-table-column
        ></el-table
      ></el-card
    ><ManagementForm
      :editor="editor"
      v-if="dialog"
      v-model="dialog"
      title="维护角色"
      @opened="syncTree"
      :before-close="beforeClose"
      width="min(760px,94vw)"
      :close-on-click-modal="false"
      ><el-form label-position="right" label-width="100px"
        ><el-form-item label="角色名称"
          ><el-input v-model="form.name" maxlength="80" /></el-form-item
        ><el-form-item label="绑定菜单"
          ><el-tree
            ref="grantTree"
            :data="menuTree"
            node-key="id"
            show-checkbox
            check-strictly
            default-expand-all
            :expand-on-click-node="false"
            :default-checked-keys="form.menus"
            @check="checkMenus"
          />
          <p class="admin-muted">
            勾选父级不勾选子级；选中子级时自动选中并保留父级。
          </p></el-form-item
        ></el-form
      ><template #footer
        ><el-button
          @click="
            editor ? tabState.close(tabState.key.value) : (dialog = false)
          "
          >取消</el-button
        ><el-button type="primary" :loading="busy" @click="save"
          >保存角色</el-button
        ></template
      ></ManagementForm
    >
  </section>
</template>
