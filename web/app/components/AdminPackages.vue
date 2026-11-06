<script setup lang="ts">
import { compareIntegers } from "~/utils/listOrder";
import { ElMessage, ElMessageBox } from "element-plus";
import { decimalUnits } from "~/utils/commerce";
const props = defineProps<{
  editor?: boolean;
  tabId?: string;
  initialId?: string | null;
}>();
const { request, message } = useApi();
const isAdmin = computed(() =>
  useState<any>("admin-user").value?.menus?.includes("packages"),
);
const data = ref<any[]>([]),
  dialog = ref(false),
  busy = ref(false),
  error = ref("");
const form = reactive({
  id: "",
  name: "",
  nameEn: "",
  price: 1,
  points: 10,
  validDays: 365,
  enabled: false,
  position: 0,
  revision: 0,
});
async function load() {
  try {
    data.value = await request("/api/admin/commerce-packages");
  } catch (e) {
    error.value = message(e);
  }
}
function edit(row?: any) {
  if (!props.editor && !row) {
    tabState.openEntityEditor("packages");
    return;
  }
  Object.assign(form, {
    id: row?.id || "",
    name: row?.name || "",
    nameEn: row?.name_en || "",
    price: Number(row?.price_fen || 100) / 100,
    points: Number(row?.points_units || 1000) / 100,
    validDays: row?.valid_days || 365,
    enabled: row?.enabled || false,
    position: row?.position || 0,
    revision: row?.revision || 0,
  });
  dialog.value = true;
}
async function save() {
  busy.value = true;
  try {
    const { id, ...body } = form;
    const result = await request<any>(
      "/api/admin/commerce-packages" + (id ? "/" + id : ""),
      {
        method: id ? "PUT" : "POST",
        body,
      },
    );
    dialog.value = false;
    await tabState.saved(result.id, form.name, true);
    if (!props.editor) await load();
  } catch (e) {
    ElMessage.error(message(e));
  } finally {
    busy.value = false;
  }
}
onMounted(async () => {
  await load();
  if (props.editor)
    edit(
      props.initialId
        ? data.value.find((r) => r.id === props.initialId)
        : undefined,
    );
});

const baseline = ref("");
const tabState = useEntityEditing(props, "packages", load);
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
        <span>POINT PACKAGES</span>
        <h1>优惠积分包</h1>
        <p>
          订单保留下单时价格与有效期，后续修改不覆盖历史订单。前台最多展示5档。
        </p>
      </div>
      <el-button v-if="isAdmin" type="primary" @click="edit()"
        >新增优惠包</el-button
      >
    </div>
    <el-alert
      v-if="error"
      :title="error"
      type="error"
      :closable="false"
    /><el-card v-if="!editor" shadow="never"
      ><el-table
        :default-sort="{ prop: 'created_at', order: 'descending' }"
        :data="data"
        ><ObjectColumns /><el-table-column
          prop="name"
          sortable
          :sort-orders="['descending', 'ascending']"
          label="名称"
          min-width="180"
        /><el-table-column
          prop="price_fen"
          :sort-method="
            (a: any, b: any) => compareIntegers(a.price_fen, b.price_fen)
          "
          sortable
          :sort-orders="['descending', 'ascending']"
          label="售价 / 积分"
          ><template #default="{ row }"
            >¥{{ decimalUnits(row.price_fen) }} /
            {{ decimalUnits(row.points_units) }} 积分</template
          ></el-table-column
        ><el-table-column
          prop="valid_days"
          sortable
          :sort-orders="['descending', 'ascending']"
          label="到账起有效天数"
        /><el-table-column
          prop="enabled"
          sortable
          :sort-orders="['descending', 'ascending']"
          label="展示"
          ><template #default="{ row }">{{
            row.enabled ? "显示" : "隐藏"
          }}</template></el-table-column
        ><el-table-column
          align="center"
          header-align="center"
          v-if="isAdmin"
          label="操作"
          ><template #default="{ row }"
            ><el-button link type="primary" @click="edit(row)"
              >编辑</el-button
            ></template
          ></el-table-column
        ></el-table
      ></el-card
    ><ManagementForm
      :editor="editor"
      v-if="dialog"
      v-model="dialog"
      :title="form.id ? '编辑优惠包' : '新增优惠包'"
      width="min(600px,94vw)"
      :close-on-click-modal="false"
      ><el-form
        label-position="right"
        label-width="128px"
        class="admin-form-grid"
        ><el-form-item label="名称" required
          ><el-input
            v-model="form.name"
            aria-label="积分包名称"
            maxlength="100" /></el-form-item
        ><el-form-item label="英文名称"
          ><el-input v-model="form.nameEn" maxlength="100" /></el-form-item
        ><el-form-item label="售价（元）"
          ><el-input-number
            v-model="form.price"
            :min="0.01"
            :precision="2"
            controls-position="right" /></el-form-item
        ><el-form-item label="积分"
          ><el-input-number
            v-model="form.points"
            :min="0.01"
            :precision="2"
            controls-position="right" /></el-form-item
        ><el-form-item label="有效天数"
          ><el-input-number
            v-model="form.validDays"
            :min="1"
            :max="36500"
            :precision="0"
            controls-position="right" /></el-form-item
        ><el-form-item label="顺序"
          ><el-input-number
            v-model="form.position"
            :min="0"
            :max="10000"
            controls-position="right" /></el-form-item
        ><el-form-item label="前台显示"
          ><el-switch v-model="form.enabled" /></el-form-item></el-form
      ><template #footer
        ><el-button
          @click="
            editor ? tabState.close(tabState.key.value) : (dialog = false)
          "
          >取消</el-button
        ><el-button type="primary" :loading="busy" @click="save"
          >保存优惠包</el-button
        ></template
      ></ManagementForm
    >
  </section>
</template>
