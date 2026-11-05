<script setup lang="ts">
import { ElMessage, ElMessageBox } from "element-plus";
/** 独立标签目录，筛选分页由后端执行；改名有版本冲突保护，引用中的标签不能直接删除。 */
const { request, message } = useApi();
const { open } = useManagementTabs();
const q = ref(""),
  page = ref(1),
  size = ref(20),
  busy = ref(false),
  error = ref(""),
  dialog = ref(false),
  saving = ref(false);
const data = ref<{ items: any[]; total: number; page: number }>({
  items: [],
  total: 0,
  page: 1,
});
const form = reactive({ id: "", name: "", description: "", revision: 0 });
const changed = useState("catalog-changed", () => 0);
const listOrder = useListOrder(load, page, "created_at");
let loadSequence = 0;
async function load() {
  const sequence = ++loadSequence;
  busy.value = true;
  try {
    const nextData = await request("/api/admin/catalog/tags", {
      query: {
        sort: listOrder.sort.value,
        direction: listOrder.direction.value,
        q: q.value,
        page: page.value,
        pageSize: size.value,
      },
    });
    if (sequence !== loadSequence) return;
    data.value = nextData;
    page.value = data.value.page;
  } catch (e) {
    if (sequence !== loadSequence) return;
    error.value = message(e);
  } finally {
    if (sequence === loadSequence) busy.value = false;
  }
}
function edit(row?: any) {
  Object.assign(form, {
    id: row?.id || "",
    name: row?.name || "",
    description: row?.description || "",
    revision: row?.revision || 0,
  });
  dialog.value = true;
}
async function save() {
  saving.value = true;
  try {
    await request("/api/admin/catalog/tags" + (form.id ? "/" + form.id : ""), {
      method: form.id ? "PUT" : "POST",
      body: {
        name: form.name,
        description: form.description,
        revision: form.revision,
      },
    });
    dialog.value = false;
    changed.value++;
    await load();
  } catch (e) {
    ElMessage.error(message(e));
  } finally {
    saving.value = false;
  }
}
async function remove(row: any) {
  try {
    await ElMessageBox.confirm(
      `删除标签“${row.name}”？已发布历史页面保持原样。`,
      "删除标签",
      { type: "warning", confirmButtonText: "删除", cancelButtonText: "取消" },
    );
    await request("/api/admin/catalog/tags/" + row.id, {
      method: "DELETE",
      query: { revision: row.revision },
    });
    await load();
    changed.value++;
  } catch (e) {
    if (e !== "cancel" && e !== "close") ElMessage.error(message(e));
  }
}
async function applications(row: any) {
  await navigateTo({
    path: "/admin/workspace",
    query: { view: "applications", tag: row.name },
  });
}
onMounted(load);
watch(changed, load);

const baseline = ref("");
const tabState = useManagementTabs();
watch(dialog, (visible) => {
  if (visible) baseline.value = JSON.stringify(form);
  else tabState.dirty.value["tags"] = false;
});
watch(
  () => JSON.stringify(form),
  (value) => {
    tabState.dirty.value["tags"] = dialog.value && value !== baseline.value;
  },
);
let unregister: (() => void) | undefined;
onMounted(() => {
  unregister = tabState.registerSave("tags", async () => {
    await save();
    return !dialog.value;
  });
});
onBeforeUnmount(() => unregister?.());
</script>
<template>
  <section>
    <div class="management-panel-heading">
      <div>
        <span>TAG MANAGEMENT</span>
        <h1>标签管理</h1>
        <p>
          维护标签目录，查看关联应用。改名更新应用草稿，发布页面在重新发布后更新。
        </p>
      </div>
      <el-button type="primary" @click="edit()">新增标签</el-button>
    </div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-card shadow="never"
      ><el-form
        inline
        @submit.prevent="
          page = 1;
          load();
        "
        ><el-form-item label="标签名称"
          ><el-input
            v-model="q"
            clearable
            aria-label="标签名称查询"
            @keyup.enter="
              page = 1;
              load();
            " /></el-form-item
        ><el-form-item
          ><el-button
            type="primary"
            @click="
              page = 1;
              load();
            "
            >查询</el-button
          ></el-form-item
        ></el-form
      >
      <el-table
        :default-sort="listOrder.defaultSort"
        @sort-change="listOrder.change"
        :data="data.items"
        v-loading="busy"
        row-key="id"
        ><el-table-column
          prop="created_at"
          label="创建时间"
          sortable="custom"
          :sort-orders="['descending', 'ascending']"
          min-width="165"
          ><template #default="{ row }">{{
            new Date(Number(row.created_at)).toLocaleString("zh-CN", {
              hour12: false,
              timeZone: "Asia/Shanghai",
            })
          }}</template></el-table-column
        ><el-table-column
          prop="name"
          sortable="custom"
          :sort-orders="['descending', 'ascending']"
          label="标签名称"
          min-width="160"
        /><el-table-column
          prop="description"
          sortable="custom"
          :sort-orders="['descending', 'ascending']"
          label="运营备注"
          min-width="220"
        /><el-table-column
          prop="applications"
          sortable="custom"
          :sort-orders="['descending', 'ascending']"
          label="关联应用"
          width="130"
          ><template #default="{ row }"
            ><el-button link type="primary" @click="applications(row)"
              >{{ row.applications }} 个应用</el-button
            ></template
          ></el-table-column
        ><el-table-column
          align="center"
          header-align="center"
          label="操作"
          width="160"
          ><template #default="{ row }"
            ><el-button link type="primary" @click="edit(row)">编辑</el-button
            ><el-button link type="danger" @click="remove(row)"
              >删除</el-button
            ></template
          ></el-table-column
        ></el-table
      >
      <el-pagination
        v-model:current-page="page"
        v-model:page-size="size"
        :page-sizes="[10, 20, 50]"
        :total="data.total"
        layout="total,sizes,prev,pager,next"
        @current-change="load"
        @size-change="
          page = 1;
          load();
        "
    /></el-card>
    <el-dialog
      v-model="dialog"
      :title="form.id ? '编辑标签' : '新增标签'"
      width="min(540px,94vw)"
      :close-on-click-modal="false"
      :before-close="
        (done: any) => {
          if (form.name)
            ElMessageBox.confirm('关闭标签编辑？未保存内容会丢失。', '提示')
              .then(() => done())
              .catch(() => {});
          else done();
        }
      "
      ><el-form label-position="right" label-width="128px"
        ><el-form-item label="标签名称" required
          ><el-input
            v-model="form.name"
            aria-label="标签名称"
            maxlength="80"
            show-word-limit /></el-form-item
        ><el-form-item label="运营备注"
          ><el-input
            v-model="form.description"
            aria-label="运营备注"
            type="textarea"
            maxlength="500"
            show-word-limit /></el-form-item></el-form
      ><template #footer
        ><el-button @click="dialog = false">取消</el-button
        ><el-button type="primary" :loading="saving" @click="save"
          >保存标签</el-button
        ></template
      ></el-dialog
    >
  </section>
</template>
