<script setup lang="ts">
import { ElMessage, ElMessageBox } from "element-plus";
/** 后台权限由服务端角色与MFA决定，变更会撤销该用户的所有会话，不提供明文密码查询。 */
const props = defineProps<{
  view: string;
  editor?: boolean;
  tabId?: string;
  initialId?: string | null;
}>();
const { request, message } = useApi();
const q = ref(""),
  state = ref(""),
  page = ref(1),
  data = ref<any>({ items: [], total: 0 }),
  dialog = ref(false),
  busy = ref(false),
  error = ref("");
const form = reactive({
  id: "",
  email: "",
  password: "",
  roles: ["USER"],
  disabled: false,
});
const roleOptions = ref<any[]>([]);
const listOrder = useListOrder(load, page, "created_at");
let loadSequence = 0;
async function load() {
  const sequence = ++loadSequence;
  try {
    const nextData = await request("/api/admin/accounts", {
      query: {
        sort: listOrder.sort.value,
        direction: listOrder.direction.value,
        q: q.value,
        state: state.value,
        page: page.value,
        pageSize: 20,
      },
    });
    if (sequence !== loadSequence) return;
    data.value = nextData;
    page.value = data.value.page;
  } catch (e) {
    if (sequence !== loadSequence) return;
    error.value = message(e);
  }
}
function edit(row?: any) {
  if (!props.editor && !row) {
    tabState.openEntityEditor("users");
    return;
  }
  Object.assign(form, {
    id: row?.id || "",
    email: row?.email || "",
    password: "",
    roles: row?.roles || ["USER"],
    disabled: row?.disabled || false,
  });
  dialog.value = true;
}
async function removeUnverified(row: any) {
  try {
    await ElMessageBox.confirm(
      "仅删除没有业务历史的未激活普通账户，同时撤销会话和验证链接。该邮箱可重新注册。",
      "删除未激活账户",
      { confirmButtonText: "删除", cancelButtonText: "取消", type: "warning" },
    );
    await request("/api/admin/accounts/" + row.id, { method: "DELETE" });
    await load();
    ElMessage.success("未激活账户已删除");
  } catch (e) {
    if (e !== "cancel" && e !== "close") ElMessage.error(message(e));
  }
}
async function save() {
  busy.value = true;
  try {
    const { id, ...body } = form;
    const result = await request<any>(
      "/api/admin/accounts" + (id ? "/" + id : ""),
      {
        method: id ? "PUT" : "POST",
        body,
      },
    );
    dialog.value = false;
    await tabState.saved(id || result.id, form.email, true);
    form.password = "";
    tabState.dirty.value[tabState.key.value] = false;
    if (id === useState<any>("admin-user").value?.id) {
      // 修改本人角色后服务器已撤销旧会话，明确返回登录页，避免把成功保存显示成查询失败。
      await request("/api/auth/logout", { method: "POST" });
      useState<any>("admin-user").value = null;
      await navigateTo("/login");
      return;
    }
    await load();
  } catch (e) {
    ElMessage.error(message(e));
  } finally {
    busy.value = false;
  }
}
onMounted(async () => {
  roleOptions.value = await request("/api/admin/role-options");
  await load();
  if (props.editor)
    edit(
      props.initialId
        ? data.value.items.find((r: any) => r.id === props.initialId)
        : undefined,
    );
});

const baseline = ref("");
const tabState = useEntityEditing(props, "users", load);
watch(dialog, (visible) => {
  if (visible) baseline.value = JSON.stringify(form);
  else tabState.dirty.value[tabState.key.value] = false;
});
watch(
  () => JSON.stringify(form),
  (value) => {
    tabState.dirty.value[tabState.key.value] =
      dialog.value && value !== baseline.value;
  },
);
let unregister: (() => void) | undefined;
onMounted(() => {
  unregister = tabState.registerSave(tabState.key.value, async () => {
    await save();
    return !dialog.value;
  });
});
onBeforeUnmount(() => unregister?.());
</script>
<template>
  <section>
    <div v-if="!editor" class="management-panel-heading">
      <div>
        <span>ACCOUNT MANAGEMENT</span>
        <h1>用户与权限</h1>
        <p>
          启用双因素策略时，任一管理员或财务角色须验证。客服核销普通捐赠；财务只对账，不能核销。角色变更后须重新登录。
        </p>
      </div>
      <el-button type="primary" @click="edit()">新增账户</el-button>
    </div>
    <el-alert
      v-if="error"
      :title="error"
      type="error"
      :closable="false"
    /><el-card v-if="!editor" shadow="never"
      ><el-form
        inline
        @submit.prevent="
          page = 1;
          load();
        "
        ><el-form-item label="邮箱"
          ><el-input
            v-model="q"
            clearable
            @keyup.enter="
              page = 1;
              load();
            " /></el-form-item
        ><el-form-item label="账户状态"
          ><el-select
            v-model="state"
            clearable
            aria-label="用户状态筛选"
            style="width: 180px"
            ><el-option label="未激活" value="UNVERIFIED" /><el-option
              label="已激活"
              value="ACTIVE" /><el-option
              label="已停用"
              value="DISABLED" /></el-select></el-form-item
        ><el-button
          type="primary"
          @click="
            page = 1;
            load();
          "
          >查询</el-button
        ></el-form
      ><el-table
        :default-sort="listOrder.defaultSort"
        @sort-change="listOrder.change"
        :data="data.items"
        ><ObjectColumns server-sort /><el-table-column
          prop="nickname"
          sortable="custom"
          :sort-orders="['descending', 'ascending']"
          label="昵称"
          min-width="160"
        /><el-table-column
          prop="email"
          sortable="custom"
          :sort-orders="['descending', 'ascending']"
          label="邮箱"
          min-width="220"
        /><el-table-column
          prop="role_name"
          sortable="custom"
          :sort-orders="['descending', 'ascending']"
          label="角色"
          ><template #default="{ row }">{{
            (row.roles || [])
              .map(
                (id: string) =>
                  roleOptions.find((r) => r.id === id)?.name || id,
              )
              .join("、")
          }}</template></el-table-column
        ><el-table-column
          prop="mfa_enabled"
          sortable="custom"
          :sort-orders="['descending', 'ascending']"
          label="邮箱 / MFA"
          ><template #default="{ row }"
            >{{ row.email_verified_at ? "已验证" : "未验证" }} /
            {{ row.mfa_enabled ? "已绑定" : "未绑定" }}</template
          ></el-table-column
        ><el-table-column
          prop="disabled"
          sortable="custom"
          :sort-orders="['descending', 'ascending']"
          label="状态"
          ><template #default="{ row }">{{
            row.disabled ? "停用" : "正常"
          }}</template></el-table-column
        ><el-table-column align="center" header-align="center" label="操作"
          ><template #default="{ row }"
            ><el-button link type="primary" @click="edit(row)"
              >维护权限</el-button
            ><el-button
              v-if="
                !row.email_verified_at &&
                (row.roles || ['USER']).every((r: string) => r === 'USER')
              "
              link
              type="danger"
              @click="removeUnverified(row)"
              >删除未激活</el-button
            ></template
          ></el-table-column
        ></el-table
      ><el-pagination
        v-model:current-page="page"
        :page-size="20"
        :total="data.total"
        layout="total,prev,pager,next"
        @current-change="load" /></el-card
    ><ManagementForm
      :editor="editor"
      v-if="dialog"
      v-model="dialog"
      :title="form.id ? '维护账户权限' : '新增账户'"
      width="min(540px,94vw)"
      :close-on-click-modal="false"
      ><el-form label-position="right" label-width="128px"
        ><el-form-item label="邮箱"
          ><el-input
            v-model="form.email"
            :disabled="!!form.id"
            type="email" /></el-form-item
        ><el-form-item v-if="!form.id" label="初始密码（至少12字符）"
          ><el-input
            v-model="form.password"
            type="password"
            show-password
            autocomplete="new-password" /></el-form-item
        ><el-form-item label="角色"
          ><el-select v-model="form.roles" multiple
            ><el-option
              v-for="role in roleOptions"
              :key="role.id"
              :label="role.name"
              :value="role.id" /></el-select></el-form-item
        ><el-form-item label="停用"
          ><el-switch v-model="form.disabled" /></el-form-item></el-form
      ><template #footer
        ><el-button
          @click="
            editor ? tabState.close(tabState.key.value) : (dialog = false)
          "
          >取消</el-button
        ><el-button type="primary" :loading="busy" @click="save"
          >保存账户</el-button
        ></template
      ></ManagementForm
    >
  </section>
</template>
