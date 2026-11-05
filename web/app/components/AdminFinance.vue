<script setup lang="ts">
import { ElMessage, ElMessageBox } from "element-plus";
/** 审核表单不直接改余额，服务器在核销/退款事务里写批次、流水与审计。 */
import {
  decimalUnits,
  businessTime,
  commerceStates,
  adminDateShortcuts,
} from "~/utils/commerce";
const props = defineProps<{ view: string }>();
const { request, message } = useApi();
const user = useState<any>("admin-user", () => null),
  kind = computed(() => (props.view === "orders" ? "resources" : props.view)),
  title = computed(
    () =>
      ({
        recharge: "充值核销",
        points: "积分流水",
        refunds: "退款管理",
        orders: "兑换订单",
      })[props.view] || "交易管理",
  );
const q = ref(""),
  state = ref(""),
  range = ref<[string, string] | null>(null),
  page = ref(1),
  size = ref(20),
  busy = ref(false),
  error = ref(""),
  notice = ref(""),
  data = ref<any>({ items: [], total: 0 }),
  dialog = ref(false),
  selected = ref<any>(null),
  approve = ref(true),
  actual = ref(0),
  reason = ref("");
const canReview = computed(() =>
  user.value?.roles?.some((r: string) => ["ADMIN", "SUPPORT"].includes(r)),
);
const states = computed(() =>
  props.view === "recharge"
    ? ["PENDING", "DECLARED", "CLOSED", "REJECTED", "POSTED"]
    : props.view === "refunds"
      ? ["PENDING", "APPROVED", "REJECTED"]
      : props.view === "points"
        ? ["RECHARGE", "PURCHASE", "REFUND", "EXPIRE"]
        : ["ACTIVE", "REFUNDED"],
);
const listOrder = useListOrder(load, page, "created_at");
let loadSequence = 0;
async function load() {
  const sequence = ++loadSequence;
  busy.value = true;
  error.value = "";
  try {
    const nextData = await request("/api/admin/commerce/" + kind.value, {
      query: {
        sort: listOrder.sort.value,
        direction: listOrder.direction.value,
        q: q.value,
        state: state.value,
        from: range.value?.[0] || "",
        until: range.value?.[1] || "",
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
function search() {
  page.value = 1;
  load();
}
function review(row: any) {
  selected.value = row;
  actual.value = Number(row.price_fen || 0) / 100;
  reason.value = "";
  approve.value = true;
  dialog.value = true;
}
async function save() {
  if (!reason.value.trim()) {
    ElMessage.warning("请填写处理原因");
    return;
  }
  busy.value = true;
  try {
    const row = selected.value;
    await request(
      `/api/admin/commerce/${props.view}/${props.view === "refunds" ? row.purchase_id : row.id}/review`,
      {
        method: "POST",
        body:
          props.view === "refunds"
            ? {
                approve: approve.value,
                reason: reason.value,
                revision: row.revision,
              }
            : {
                approve: approve.value,
                reason: reason.value,
                actual: actual.value,
              },
      },
    );
    dialog.value = false;
    notice.value = "处理已完成，账本与审计同步保存";
    await load();
  } catch (e) {
    ElMessage.error(message(e));
  } finally {
    busy.value = false;
  }
}
function productName(row: any) {
  try {
    return JSON.parse(row.snapshot_json).content.nameZh;
  } catch {
    return row.package_name || row.id || row.purchase_id;
  }
}
onMounted(load);
</script>
<template>
  <section>
    <div class="management-panel-heading">
      <div>
        <span>TRANSACTION MANAGEMENT</span>
        <h1>{{ title }}</h1>
        <p>模拟交易账本，与真实收款隔离。时间统一为北京时间。</p>
      </div>
    </div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <p v-if="notice" class="alert success" role="status">{{ notice }}</p>
    <el-card shadow="never"
      ><el-form
        label-position="right"
        label-width="128px"
        class="admin-filter-grid"
        @submit.prevent="search"
        ><el-form-item label="账户昵称"
          ><el-input
            v-model="q"
            clearable
            aria-label="账户邮箱查询"
            @keyup.enter="search" /></el-form-item
        ><el-form-item label="状态 / 类型"
          ><el-select v-model="state" clearable aria-label="交易状态筛选"
            ><el-option
              v-for="v in states"
              :key="v"
              :value="v"
              :label="commerceStates[v] || v" /></el-select></el-form-item
        ><el-form-item label="时间范围"
          ><el-date-picker
            v-model="range"
            type="daterange"
            range-separator="至"
            start-placeholder="开始日期"
            end-placeholder="结束日期"
            value-format="YYYY-MM-DD"
            :shortcuts="adminDateShortcuts()"
            unlink-panels
            clearable
        /></el-form-item>
        <div>
          <el-button type="primary" :loading="busy" @click="search"
            >查询</el-button
          ><el-button
            @click="
              q = '';
              state = '';
              range = null;
              search();
            "
            >重置</el-button
          >
        </div></el-form
      ></el-card
    >
    <el-card shadow="never" style="margin-top: 20px"
      ><el-table
        :default-sort="listOrder.defaultSort"
        @sort-change="listOrder.change"
        :data="data.items"
        v-loading="busy"
        row-key="id"
        ><el-table-column
          prop="title"
          sortable="custom"
          :sort-orders="['descending', 'ascending']"
          label="订单 / 流水"
          min-width="250"
          ><template #default="{ row }"
            ><strong>{{ productName(row) }}</strong
            ><small style="display: block">{{
              row.nickname || row.user_id
            }}</small
            ><small>{{ row.id || row.purchase_id }}</small></template
          ></el-table-column
        ><el-table-column
          prop="nickname"
          label="账户昵称"
          sortable="custom"
          :sort-orders="['descending', 'ascending']"
          min-width="190"
        /><el-table-column
          prop="amount"
          sortable="custom"
          :sort-orders="['descending', 'ascending']"
          label="金额 / 积分"
          min-width="160"
          ><template #default="{ row }"
            ><template v-if="row.price_fen"
              >应付 ¥{{ decimalUnits(row.price_fen) }}<br />积分
              {{ decimalUnits(row.points_units) }}<br /><span
                v-if="row.actual_fen"
                >实到 ¥{{ decimalUnits(row.actual_fen) }}</span
              ></template
            ><template v-else-if="row.delta_units"
              >{{ commerceStates[row.kind] }} {{ decimalUnits(row.delta_units)
              }}<br />余额 {{ decimalUnits(row.balance_after) }}<br />成本分摊
              ¥{{ decimalUnits(row.cost_fen) }}</template
            ><template v-else-if="row.price_units"
              >{{ decimalUnits(row.price_units) }} 积分<br />成本 ¥{{
                decimalUnits(row.cost_fen)
              }}</template
            ><span v-else>{{ row.reason }}</span></template
          ></el-table-column
        ><el-table-column
          prop="state"
          sortable="custom"
          :sort-orders="['descending', 'ascending']"
          label="状态"
          min-width="95"
          ><template #default="{ row }"
            ><el-tag>{{
              commerceStates[row.state] || commerceStates[row.kind] || "—"
            }}</el-tag></template
          ></el-table-column
        ><el-table-column
          prop="created_at"
          sortable="custom"
          :sort-orders="['descending', 'ascending']"
          label="时间"
          min-width="160"
          ><template #default="{ row }">{{
            businessTime(row.created_at)
          }}</template></el-table-column
        ><el-table-column label="说明 / 操作" min-width="240"
          ><template #default="{ row }"
            ><p>{{ row.declaration || row.review_note || row.reason }}</p>
            <a
              v-if="row.evidence_id"
              :href="'/api/member/evidence/' + row.evidence_id"
              target="_blank"
              rel="noopener"
              >查看付款截图</a
            ><el-button
              v-if="
                canReview &&
                ((view === 'recharge' && !['POSTED'].includes(row.state)) ||
                  (view === 'refunds' && row.state === 'PENDING'))
              "
              link
              type="primary"
              @click="review(row)"
              >审核</el-button
            ></template
          ></el-table-column
        ></el-table
      ><el-pagination
        v-model:current-page="page"
        v-model:page-size="size"
        :total="data.total"
        :page-sizes="[10, 20, 50]"
        layout="total,sizes,prev,pager,next,jumper"
        @current-change="load"
        @size-change="search"
    /></el-card>
    <el-dialog
      v-model="dialog"
      :title="view === 'refunds' ? '审核整单退积分' : '核销模拟充值'"
      width="min(560px,94vw)"
      :close-on-click-modal="false"
      ><el-alert
        v-if="view === 'recharge'"
        title="不足门槛由客服核销，大额及关闭等异常订单须管理员。付款声明和截图不是自动到账依据。"
        type="info"
        :closable="false"
      /><el-descriptions
        v-if="selected"
        :column="1"
        border
        style="margin-bottom: 20px"
        ><el-descriptions-item label="账户">{{
          selected.nickname
        }}</el-descriptions-item
        ><el-descriptions-item label="订单">{{
          selected.id || selected.purchase_id
        }}</el-descriptions-item
        ><el-descriptions-item
          :label="view === 'refunds' ? '拟退积分' : '应付金额'"
          >{{
            view === "refunds"
              ? decimalUnits(selected.price_units) + " 积分"
              : "¥" + decimalUnits(selected.price_fen)
          }}</el-descriptions-item
        ></el-descriptions
      >
      <el-form label-position="right" label-width="128px"
        ><el-form-item label="审核结果"
          ><el-radio-group v-model="approve"
            ><el-radio :value="true">通过</el-radio
            ><el-radio :value="false">驳回</el-radio></el-radio-group
          ></el-form-item
        ><el-form-item
          v-if="view === 'recharge' && approve"
          label="核实实际金额（元）"
          required
          ><el-input-number
            v-model="actual"
            :min="0.01"
            :max="1000000"
            :precision="2"
            controls-position="right" /></el-form-item
        ><el-form-item label="逐单处理原因" required
          ><el-input
            v-model="reason"
            type="textarea"
            :rows="4"
            maxlength="1000"
            show-word-limit
            aria-label="逐单处理原因" /></el-form-item></el-form
      ><template #footer
        ><el-button @click="dialog = false">取消</el-button
        ><el-button type="primary" :loading="busy" @click="save"
          >确认处理</el-button
        ></template
      ></el-dialog
    >
  </section>
</template>
