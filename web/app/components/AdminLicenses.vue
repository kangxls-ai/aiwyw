<script setup lang="ts">
/** 商品策略影响下次发布；客服只看到换机审核，无法读取任何用户激活密钥。 */
import { ElMessageBox, ElMessage } from "element-plus";
import { businessTime } from "~/utils/commerce";
const { request, message } = useApi();
const stateText: Record<string, string> = {
  PENDING: "待审核",
  APPROVED: "已通过",
  REJECTED: "已驳回",
  CANCELLED: "已取消",
};
const user = useState<any>("admin-user", () => null);
const editor = computed(() => user.value?.menus?.includes("licenses"));
const reviewer = computed(() =>
  user.value?.roles?.some((r: string) => ["ADMIN", "SUPPORT"].includes(r)),
);
const products = ref<any[]>([]),
  product = ref(""),
  devices = ref(1),
  revision = ref(0),
  q = ref(""),
  state = ref(""),
  page = ref(1),
  size = ref(20),
  data = ref<any>({ items: [], total: 0 }),
  error = ref(""),
  busy = ref(false),
  saving = ref(false),
  baseline = ref(1);
const { dirty, registerSave } = useManagementTabs();
const listOrder = useListOrder(load, page);
watch(devices, () => (dirty.value.licenses = devices.value !== baseline.value));
async function policy() {
  try {
    const result = await request<any>(
      "/api/admin/licenses/policy/" + product.value,
    );
    devices.value = Number(result.device_limit) || 1;
    revision.value = Number(result.revision);
    baseline.value = devices.value;
    dirty.value.licenses = false;
  } catch (e) {
    error.value = message(e);
  }
}
async function choose(value: string) {
  if (dirty.value.licenses) {
    try {
      await ElMessageBox.confirm(
        "切换应用将放弃未保存的设备数修改。",
        "切换应用",
        { confirmButtonText: "放弃修改", cancelButtonText: "继续编辑" },
      );
    } catch {
      return;
    }
  }
  product.value = value;
  await policy();
}
async function save() {
  if (!product.value) return false;
  saving.value = true;
  try {
    await request("/api/admin/licenses/policy/" + product.value, {
      method: "PUT",
      body: { deviceLimit: devices.value, revision: revision.value },
    });
    await policy();
    ElMessage.success("策略已保存；请重新发布应用后生效");
    return true;
  } catch (e) {
    error.value = message(e);
    return false;
  } finally {
    saving.value = false;
  }
}
let loadSequence = 0;
async function load() {
  const sequence = ++loadSequence;
  if (!reviewer.value) return;
  busy.value = true;
  try {
    const nextData = await request("/api/admin/licenses/requests", {
      query: {
        q: q.value,
        state: state.value,
        page: page.value,
        pageSize: size.value,
        sort: listOrder.sort.value,
        direction: listOrder.direction.value,
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
async function review(row: any, approve: boolean) {
  try {
    const result = await ElMessageBox.prompt(
      approve
        ? "通过后旧设备在线凭据立即失效，释放一个设备名额。"
        : "填写驳回原因，用户可补充后再次申请。",
      "审核换机",
      {
        inputType: "textarea",
        inputValidator: (v) =>
          (!!v?.trim() && v.length <= 500) || "请填写1至500字原因",
        confirmButtonText: approve ? "通过解绑" : "驳回",
        cancelButtonText: "取消",
      },
    );
    await request("/api/admin/licenses/requests/" + row.id + "/review", {
      method: "POST",
      body: { approve, reason: result.value, revision: Number(row.revision) },
    });
    await load();
  } catch (e) {
    if (e !== "cancel" && e !== "close") ElMessage.error(message(e));
  }
}
let unregister: (() => void) | undefined;
// 会话由外层布局异步读取；等待角色确定后加载对应数据，直接刷新授权页面也能得到应用选项。
onMounted(() => {
  unregister = registerSave("licenses", save);
  watch(
    () => user.value?.role,
    async () => {
      try {
        if (editor.value)
          products.value = await request("/api/admin/catalog/products/options");
        await load();
      } catch (e) {
        error.value = message(e);
      }
    },
    { immediate: true },
  );
});
onBeforeUnmount(() => unregister?.());
</script>
<template>
  <section>
    <div class="management-panel-heading">
      <div>
        <span>SOFTWARE LICENSES</span>
        <h1>软件授权与换机</h1>
        <p>按购买时条款限制设备数，同一大版本保留授权；退款撤销授权。</p>
      </div>
    </div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-card v-if="editor" shadow="never"
      ><template #header>应用授权策略</template
      ><el-form label-position="right" label-width="128px"
        ><el-form-item label="应用"
          ><el-select
            :model-value="product"
            filterable
            aria-label="授权策略应用"
            @update:model-value="choose"
            ><el-option
              v-for="p in products"
              :key="p.id"
              :label="p.nameZh"
              :value="p.id" /></el-select></el-form-item
        ><el-form-item label="设备数量"
          ><el-input-number
            v-model="devices"
            :min="1"
            :max="1000"
            :disabled="!product"
            controls-position="right" /></el-form-item
        ><el-form-item
          ><el-button
            type="primary"
            :loading="saving"
            :disabled="!product"
            @click="save"
            >保存授权策略</el-button
          ></el-form-item
        ></el-form
      >
      <p class="admin-muted">
        未覆盖时采用系统管理 →
        配置管理中的默认激活设备数。策略保存后须重新发布；不改变旧发布快照及已购权益。仅交付方式为“软件激活”的应用使用该策略。
      </p></el-card
    >
    <el-card v-if="reviewer" shadow="never" style="margin-top: 20px"
      ><template #header>换机解绑申请</template
      ><el-form
        label-position="right"
        label-width="128px"
        class="admin-filter-grid"
        @submit.prevent="
          page = 1;
          load();
        "
        ><el-form-item label="账户邮箱"
          ><el-input v-model="q" clearable maxlength="100" /></el-form-item
        ><el-form-item label="申请状态"
          ><el-select v-model="state" clearable placeholder="全部"
            ><el-option
              v-for="(label, value) in {
                PENDING: '待审核',
                APPROVED: '已通过',
                REJECTED: '已驳回',
                CANCELLED: '已取消',
              }"
              :key="value"
              :label="label"
              :value="value" /></el-select
        ></el-form-item>
        <div class="full">
          <el-button
            type="primary"
            @click="
              page = 1;
              load();
            "
            >查询</el-button
          ><el-button
            @click="
              q = '';
              state = '';
              page = 1;
              load();
            "
            >重置</el-button
          >
        </div></el-form
      >
      <el-table
        :data="data.items"
        v-loading="busy"
        :default-sort="listOrder.defaultSort"
        @sort-change="listOrder.change"
        ><el-table-column
          prop="created_at"
          label="创建时间"
          sortable="custom"
          :sort-orders="['descending', 'ascending']"
          min-width="165"
          ><template #default="{ row }">{{
            businessTime(row.created_at)
          }}</template></el-table-column
        ><el-table-column
          v-for="(label, key) in {
            email: '账户',
            device_name: '设备',
            reason: '申请原因',
            state: '状态',
          }"
          :key="key"
          :prop="key"
          :label="label"
          sortable="custom"
          :sort-orders="['descending', 'ascending']"
          min-width="140"
          ><template #default="{ row }">{{
            key === "state" ? stateText[row.state] || row.state : row[key]
          }}</template></el-table-column
        ><el-table-column label="审核" min-width="180"
          ><template #default="{ row }"
            ><template v-if="row.state === 'PENDING'"
              ><el-button link type="primary" @click="review(row, true)"
                >通过解绑</el-button
              ><el-button link type="danger" @click="review(row, false)"
                >驳回</el-button
              ></template
            ><span v-else>{{ row.review_note }}</span></template
          ></el-table-column
        ></el-table
      ><el-pagination
        v-model:current-page="page"
        v-model:page-size="size"
        :total="data.total"
        :page-sizes="[10, 20, 50]"
        layout="total,sizes,prev,pager,next"
        @current-change="load"
        @size-change="
          page = 1;
          load();
        "
      />
    </el-card>
  </section>
</template>
