<script setup lang="ts">
definePageMeta({ layout: "catalog" });
import { ElMessage, ElMessageBox } from "element-plus";
/** 个人中心只读取当前会话归属的数据；鼓励值展示来自服务器，前端没有本地钱包。 */
import "~/assets/element-admin.css";
import "~/assets/auth.css";
import {
  decimalUnits,
  businessTime,
  commerceStates,
  humanText,
} from "~/utils/commerce";
const { t } = useLanguage(),
  { request, message } = useApi(),
  route = useRoute();
const profile = ref<any>(null),
  wallet = ref<any>({ balance: "0.00", batches: [] }),
  packages = ref<any[]>([]),
  tab = ref(route.query.resource ? "resources" : "recharge"),
  data = ref<any>({ items: [], total: 0, page: 1 }),
  page = ref(1),
  size = ref(10),
  busy = ref(false),
  error = ref(""),
  notice = ref(""),
  amount = ref(10),
  packageId = ref("STANDARD"),
  payment = ref<any>({}),
  payDialog = ref(false),
  order = ref<any>(null),
  evidenceId = ref(""),
  declaration = ref(""),
  proofBusy = ref(false),
  requestKey = ref(""),
  profileDialog = ref(false),
  createDialog = ref(false),
  detailDialog = ref(false),
  detail = ref<any>(null);
const filters = reactive({ q: "", state: "", dates: null as string[] | null });
const states = computed(() =>
  tab.value === "points"
    ? [
        "DAILY_LOGIN",
        "RECHARGE",
        "PURCHASE",
        "REFUND",
        "API_HOLD",
        "API_SETTLE",
        "API_RELEASE",
        "API_REFUND",
        "EXPIRE",
        "ADJUST_CREDIT",
        "ADJUST_DEBIT",
      ].map((value) => ({ value, label: commerceStates[value] || value }))
    : tab.value === "notifications"
      ? [
          { value: "UNREAD", label: t("未读", "Unread") },
          { value: "READ", label: t("已读", "Read") },
        ]
      : tab.value === "batches"
        ? []
        : [
            "PENDING",
            "DECLARED",
            "CLOSED",
            "REJECTED",
            "POSTED",
            "ACTIVE",
            "REFUNDED",
            "APPROVED",
          ].map((value) => ({ value, label: commerceStates[value] || value })),
);
function query() {
  page.value = 1;
  list();
}
function showDetail(row: any) {
  detail.value = row;
  detailDialog.value = true;
}

const kinds: Record<string, string> = {
  recharge: "recharge",
  points: "points",
  resources: "resources",
  refunds: "refunds",
  batches: "batches",
  notifications: "notifications",
};
const names = computed(() => ({
  recharge: t("捐赠订单", "Donation"),
  points: t("鼓励值流水", "Ledger"),
  batches: t("鼓励值有效期", "Encouragement expiry"),
  resources: t("我的资源", "My resources"),
  refunds: t("退款申请", "Refunds"),
  notifications: t("站内通知", "Notifications"),
  profile: t("个人资料与安全", "Profile and security"),
  services: t("API与客服", "APIs and support"),
}));
async function load() {
  try {
    await request("/api/auth/session");
    profile.value = await request("/api/member/profile");
    wallet.value = await request("/api/member/wallet?summary=true");
    packages.value = await request("/api/member/packages");
    payment.value = await request("/api/member/payment");

    await list();
  } catch (e: any) {
    if (e.status === 401) await navigateTo("/account-access");
    else error.value = message(e);
  }
}
const listOrder = useListOrder(list, page);
let listGeneration = 0;
const recordKey = (row: any) => row.id || row.purchase_id;
async function list() {
  const generation = ++listGeneration;
  if (!kinds[tab.value]) {
    busy.value = false;
    return;
  }
  busy.value = true;
  try {
    const result = await request(
      tab.value === "notifications"
        ? "/api/member/records/notifications"
        : "/api/member/list/" + kinds[tab.value],
      {
        query: {
          page: page.value,
          pageSize: size.value,
          q: filters.q,
          state: filters.state,
          from: filters.dates?.[0] || "",
          until: filters.dates?.[1] || "",
          sort: listOrder.sort.value,
          direction: listOrder.direction.value,
        },
      },
    );
    if (generation !== listGeneration) return;
    data.value = result;
    page.value = data.value.page;
  } catch (e) {
    if (generation === listGeneration) error.value = message(e);
  } finally {
    if (generation === listGeneration) busy.value = false;
  }
}
async function create() {
  busy.value = true;
  try {
    requestKey.value ||= crypto.randomUUID();
    order.value = await request("/api/member/recharge", {
      method: "POST",
      body: {
        amount: packageId.value === "STANDARD" ? amount.value : null,
        packageId: packageId.value === "STANDARD" ? null : packageId.value,
        requestKey: requestKey.value,
      },
    });
    requestKey.value = "";
    evidenceId.value = "";
    declaration.value = "";
    createDialog.value = false;
    payDialog.value = true;
    await list();
  } catch (e) {
    error.value = message(e);
  } finally {
    busy.value = false;
  }
}
async function declare() {
  if (!order.value || proofBusy.value) return;
  busy.value = true;
  try {
    await request(`/api/member/recharge/${order.value.id}/declare`, {
      method: "POST",
      body: { evidenceId: evidenceId.value || null, note: declaration.value },
    });
    payDialog.value = false;
    notice.value = t(
      "付款声明已提交，核销后鼓励值到账。",
      "Payment statement submitted for review.",
    );
    await load();
  } catch (e) {
    error.value = message(e);
  } finally {
    busy.value = false;
  }
}
async function proof(file: File | undefined) {
  if (!file) return;
  proofBusy.value = true;
  try {
    const form = new FormData();
    form.append("file", file);
    const result = await request<{ id: string }>("/api/member/evidence", {
      method: "POST",
      body: form,
    });
    evidenceId.value = result.id;
  } catch (e) {
    ElMessage.error(message(e));
  } finally {
    proofBusy.value = false;
  }
}
async function resend() {
  try {
    const result = await request<{ message: string }>(
      "/api/auth/verification-email",
      { method: "POST", body: { email: profile.value.user.email } },
    );
    notice.value = result.message;
  } catch (e) {
    error.value = message(e);
  }
}
async function download(row: any, original = false) {
  try {
    const result = await request<{ url: string }>(
      `/api/member/resources/${row.id}/download`,
      { method: "POST", query: { original } },
    );
    window.location.assign(result.url);
  } catch (e) {
    error.value = message(e);
  }
}
async function refund(row: any) {
  try {
    const result = await ElMessageBox.prompt(
      t(
        "请说明申请整单退鼓励值的原因",
        "Reason for a full encouragement refund",
      ),
      t("申请退款", "Request refund"),
      {
        inputType: "textarea",
        inputValidator: (v: string) =>
          !!v?.trim() || t("请填写原因", "A reason is required"),
        confirmButtonText: t("提交", "Submit"),
        cancelButtonText: t("取消", "Cancel"),
      },
    );
    await request(`/api/member/resources/${row.id}/refund`, {
      method: "POST",
      body: { reason: result.value },
    });
    notice.value = t("退款申请已提交", "Refund request submitted");
    await list();
  } catch (e) {
    if (e !== "cancel" && e !== "close") error.value = message(e);
  }
}
function recordTitle(row: any) {
  return humanText(
    tab.value === "resources"
      ? productName(row)
      : row.package_name ||
          row.reason ||
          commerceStates[row.kind] ||
          commerceStates[row.source_type] ||
          row.source_type ||
          row.id,
  );
}
function productName(row: any) {
  try {
    const product = JSON.parse(row.snapshot_json);
    return humanText(product.content.nameZh);
  } catch {
    return row.id;
  }
}
async function read(row: any) {
  await request(`/api/member/notifications/${row.id}/read`, { method: "POST" });
  await list();
}
async function preference(value: boolean) {
  try {
    await request("/api/member/preferences", {
      method: "PUT",
      body: { businessEmail: value },
    });
  } catch (e) {
    error.value = message(e);
  }
}
async function logout() {
  await request("/api/auth/logout", { method: "POST" });
  await navigateTo("/account-access");
}
watch(tab, () => {
  page.value = 1;
  listOrder.sort.value = "created_at";
  listOrder.direction.value = "descending";
  filters.q = "";
  filters.state = "";
  filters.dates = null;
  list();
});
onMounted(load);
</script>
<template>
  <div class="member-area">
    <div class="management-panel-heading">
      <div>
        <h1>{{ t("我的账户", "My account") }}</h1>
        <p>{{ profile?.user?.nickname }}</p>
      </div>
      <el-button @click="logout">{{ t("退出登录", "Sign out") }}</el-button>
    </div>
    <!-- 单层功能页签拆开资料、交易与服务入口，余额仍由服务器统一读取。 -->
    <el-tabs v-model="tab" class="member-tabs">
      <el-tab-pane
        v-for="(name, key) in names"
        :key="key"
        :name="key"
        :label="name"
      />
    </el-tabs>
    <SmallRecordList
      v-if="tab === 'profile' && profile"
      :rows="[
        {
          nickname: profile.user.nickname,
          email: profile.user.email,
          verified: profile.verified
            ? t('已验证', 'Verified')
            : t('未验证', 'Unverified'),
          policy: profile.user.mfaRequired
            ? t('需要双因素验证', 'MFA required')
            : t('当前策略无需双因素', 'MFA optional'),
        },
      ]"
      :columns="[
        { key: 'nickname', label: t('昵称', 'Nickname') },
        { key: 'email', label: t('邮箱', 'Email') },
        { key: 'verified', label: t('邮箱状态', 'Email status') },
        { key: 'policy', label: t('安全策略', 'Security policy') },
      ]"
    >
      <template #actions
        ><el-button link type="primary" @click="profileDialog = true">{{
          t("修改资料与密码", "Edit profile and password")
        }}</el-button
        ><el-button v-if="!profile.verified" link @click="resend">{{
          t("发送验证邮件", "Send verification email")
        }}</el-button></template
      >
    </SmallRecordList>
    <SmallRecordList
      v-if="tab === 'services'"
      :rows="[
        {
          name: t('API、Key与订阅', 'APIs, keys and subscriptions'),
          description: t(
            '查询接口、套餐、Key、订阅与调用记录',
            'Browse APIs, plans, keys, subscriptions and calls',
          ),
          href: '/api-services',
        },
        {
          name: t('在线咨询与反馈', 'Support and feedback'),
          description: t(
            '查询会话、历史留言和处理结果',
            'Browse conversations, messages and responses',
          ),
          href: '/support',
        },
      ]"
      :columns="[
        { key: 'name', label: t('服务', 'Service') },
        { key: 'description', label: t('说明', 'Description') },
      ]"
    >
      <template #actions="{ row }"
        ><NuxtLink :to="row.href">{{ t("进入", "Open") }}</NuxtLink></template
      >
    </SmallRecordList>
    <el-dialog
      v-model="profileDialog"
      :title="t('个人资料与安全', 'Profile and security')"
      width="min(850px,95vw)"
      destroy-on-close
      ><AccountProfileSettings v-if="profileDialog" @changed="load" /><el-form
        label-position="right"
        label-width="130px"
        ><el-form-item :label="t('业务邮件', 'Business emails')"
          ><el-switch
            v-if="profile"
            v-model="profile.businessEmail"
            @change="
              (value) => preference(Boolean(value))
            " /></el-form-item></el-form
    ></el-dialog>
    <el-alert
      v-if="error"
      :title="error"
      type="error"
      :closable="false"
    /><el-alert
      v-if="notice"
      :title="notice"
      type="success"
      :closable="false"
    /><el-alert
      v-if="profile && !profile.verified"
      type="warning"
      :closable="false"
      ><template #title>{{
        t(
          "验证邮箱后可捐赠和兑换。",
          "Verify your email before donating or redeeming.",
        )
      }}</template
      ><el-button link type="primary" @click="resend">{{
        t("发送验证邮件", "Send verification email")
      }}</el-button
      ><el-button link @click="load">{{
        t("刷新状态", "Refresh")
      }}</el-button></el-alert
    >
    <div v-if="tab === 'recharge'" class="admin-table-actions">
      <span
        >{{ t("可用鼓励值", "Available encouragement") }}：{{ wallet.balance }}
        ·
        {{
          t(
            "每日首次登录奖励1鼓励值；捐赠1元获得10鼓励值",
            "Daily login awards 1 encouragement; each CNY donated grants 10",
          )
        }}</span
      ><el-button type="primary" @click="createDialog = true">{{
        t("新增捐赠", "New donation")
      }}</el-button>
    </div>
    <el-dialog
      v-model="createDialog"
      :title="t('新增捐赠', 'New donation')"
      width="min(800px,95vw)"
      ><div class="donation-create">
        <el-card shadow="never"
          ><h3>{{ t("可用鼓励值", "Available encouragement") }}</h3>
          <div class="money">{{ wallet.balance }}</div>
          <p>
            {{
              t(
                "模拟账本 · 与真实收款隔离",
                "Simulated ledger · isolated from real payments",
              )
            }}
          </p></el-card
        ><el-card shadow="never"
          ><h3>{{ t("捐赠鼓励值", "Donation encouragement") }}</h3>
          <el-form inline @submit.prevent="create"
            ><el-form-item :label="t('捐赠类型', 'Type')"
              ><el-select v-model="packageId" style="width: 200px"
                ><el-option
                  :label="t('普通捐赠（永久）', 'Standard (no expiry)')"
                  value="STANDARD" /><el-option
                  v-for="pack in packages"
                  :key="pack.id"
                  :label="pack.name + ' · ¥' + decimalUnits(pack.price_fen)"
                  :value="pack.id" /></el-select></el-form-item
            ><el-form-item
              v-if="packageId === 'STANDARD'"
              :label="t('金额（元）', 'Amount (CNY)')"
              ><el-input-number
                v-model="amount"
                :aria-label="t('捐赠金额', 'Donation amount')"
                :precision="0"
                :min="1"
                :max="1000000"
                controls-position="right" /></el-form-item
            ><el-form-item
              ><el-button
                type="primary"
                :loading="busy"
                :disabled="!profile?.verified"
                @click="create"
                >{{ t("创建捐赠订单", "Create donation") }}</el-button
              ></el-form-item
            ></el-form
          >
          <div v-if="packageId === 'STANDARD'" class="catalog-editor-actions">
            <el-button
              v-for="v in [1, 10, 50, 100]"
              :key="v"
              size="small"
              @click="amount = v"
              >{{ v }}元</el-button
            >
          </div>
          <p>
            {{ t("预计获得鼓励值", "Expected encouragement") }}：{{
              packageId === "STANDARD"
                ? amount * 10
                : decimalUnits(
                    packages.find((p) => p.id === packageId)?.points_units,
                  )
            }}
          </p>
          <p class="admin-muted">
            {{
              t(
                "当前仅模拟捐赠，请勿实际转账。点击我已付款不会直接增加鼓励值。",
                "Simulation only. Do not transfer money. A payment statement does not credit encouragement.",
              )
            }}
          </p></el-card
        >
      </div></el-dialog
    >
    <el-card v-if="kinds[tab] || tab === 'notifications'" shadow="never"
      ><RecordQuery
        v-model="filters"
        :states="states"
        :loading="busy"
        @search="query"
      />
      <el-table
        v-if="tab === 'notifications'"
        :key="tab"
        :default-sort="listOrder.defaultSort"
        @sort-change="listOrder.change"
        :data="data.items"
        v-loading="busy"
        ><ObjectColumns
          localized
          :show-creator="false"
          server-sort
        /><el-table-column
          prop="title"
          :label="t('标题', 'Title')"
          min-width="180"
        /><el-table-column
          prop="body"
          :label="t('内容', 'Message')"
          min-width="300"
          show-overflow-tooltip
        /><el-table-column :label="t('状态', 'Status')"
          ><template #default="{ row }">{{
            row.read_at ? t("已读", "Read") : t("未读", "Unread")
          }}</template></el-table-column
        ><el-table-column :label="t('操作', 'Actions')" min-width="180"
          ><template #default="{ row }"
            ><el-button link @click="showDetail(row)">{{
              t("查看详情", "Details")
            }}</el-button
            ><el-button
              v-if="!row.read_at"
              link
              type="primary"
              @click="read(row)"
              >{{ t("标记已读", "Mark read") }}</el-button
            ></template
          ></el-table-column
        ></el-table
      >
      <el-table
        v-else
        :key="tab"
        :default-sort="listOrder.defaultSort"
        @sort-change="listOrder.change"
        :data="data.items"
        v-loading="busy"
        :row-key="recordKey"
        ><ObjectColumns server-sort /><el-table-column
          :label="t('内容', 'Details')"
          prop="details"
          sortable="custom"
          :sort-orders="['descending', 'ascending']"
          min-width="230"
          ><template #default="{ row }"
            ><strong>{{ recordTitle(row) }}</strong>
            <p v-if="row.payment_memo">
              {{ t("付款备注", "Payment remark") }}：<strong>{{
                row.payment_memo
              }}</strong>
            </p>
            <p v-if="row.price_fen">
              ¥{{ decimalUnits(row.price_fen) }} /
              {{ decimalUnits(row.points_units) }}
              {{ t("鼓励值", "encouragement") }}
            </p>
            <p v-if="row.remaining_units !== undefined">
              {{ t("剩余", "Remaining") }}
              {{ decimalUnits(row.remaining_units) }} ·
              {{
                row.expires_at
                  ? businessTime(row.expires_at)
                  : t("永久有效", "No expiry")
              }}
            </p>
            <p v-if="row.delta_units">
              {{ decimalUnits(row.delta_units) }} / {{ t("余额", "Balance") }}
              {{ decimalUnits(row.balance_after) }}
            </p>
            <p v-if="row.price_units">
              {{ decimalUnits(row.price_units) }}
              {{ t("鼓励值", "encouragement") }}
            </p></template
          ></el-table-column
        ><el-table-column
          :label="t('状态', 'Status')"
          prop="state"
          sortable="custom"
          :sort-orders="['descending', 'ascending']"
          min-width="100"
          ><template #default="{ row }">{{
            commerceStates[row.state] || "—"
          }}</template></el-table-column
        ><el-table-column
          align="center"
          header-align="center"
          :label="t('操作', 'Actions')"
          min-width="220"
          ><template #default="{ row }"
            ><el-button link @click="showDetail(row)">{{
              t("查看详情", "Details")
            }}</el-button
            ><template
              v-if="
                tab === 'recharge' && ['PENDING', 'CLOSED'].includes(row.state)
              "
              ><el-button
                link
                type="primary"
                @click="
                  order = row;
                  payDialog = true;
                  evidenceId = '';
                  declaration = '';
                "
                >{{ t("查看/声明付款", "View payment") }}</el-button
              ></template
            ><template v-if="tab === 'resources' && row.state === 'ACTIVE'"
              ><el-button link type="primary" @click="download(row)">{{
                t("下载", "Download")
              }}</el-button
              ><el-button link @click="download(row, true)">{{
                t("购买时版本", "Original version")
              }}</el-button
              ><MemberLicense
                v-if="
                  JSON.parse(row.snapshot_json).content.deliveryMode ===
                  'LICENSE'
                "
                :purchase="row.id"
              /><el-button link type="warning" @click="refund(row)">{{
                t("申请退款", "Request refund")
              }}</el-button></template
            ></template
          ></el-table-column
        ></el-table
      ><el-pagination
        v-model:current-page="page"
        :page-size="size"
        :total="data.total"
        layout="total,prev,pager,next"
        @current-change="list"
      />
    </el-card>
    <el-dialog
      v-model="detailDialog"
      :title="t('记录详情', 'Record details')"
      width="min(700px,95vw)"
      ><el-descriptions v-if="detail" :column="1" border
        ><el-descriptions-item :label="t('编号', 'ID')">{{
          detail.id || detail.purchase_id
        }}</el-descriptions-item
        ><el-descriptions-item :label="t('内容', 'Content')">{{
          humanText(
            detail.title ||
              detail.reason ||
              detail.package_name ||
              commerceStates[detail.kind] ||
              productName(detail),
          )
        }}</el-descriptions-item
        ><el-descriptions-item
          v-if="detail.body"
          :label="t('正文', 'Message')"
          >{{ humanText(detail.body) }}</el-descriptions-item
        ><el-descriptions-item :label="t('创建时间', 'Created at')">{{
          businessTime(detail.created_at)
        }}</el-descriptions-item
        ><el-descriptions-item
          v-if="detail.review_note"
          :label="t('审核说明', 'Review note')"
          >{{ humanText(detail.review_note) }}</el-descriptions-item
        ></el-descriptions
      ></el-dialog
    >
    <el-dialog
      v-model="payDialog"
      :title="t('模拟捐赠订单', 'Simulated donation')"
      width="min(560px,94vw)"
      :close-on-click-modal="false"
      ><el-alert
        :title="
          t(
            '模拟二维码，请勿真实转账。',
            'Simulated QR code. Do not transfer money.',
          )
        "
        type="warning"
        :closable="false"
      />
      <p>{{ order?.id }}</p>
      <h2>¥{{ decimalUnits(order?.price_fen) }}</h2>
      <div v-if="order?.payment_memo" class="payment-memo" role="note">
        <p>
          {{
            t(
              "请在支付备注中填写以下四位数字",
              "Enter these four digits in the payment remark",
            )
          }}
        </p>
        <strong>{{ order.payment_memo }}</strong>
        <p>
          {{
            t(
              "请一并提供账户昵称供核销人员匹配；备注码不是到账凭证。",
              "Include your account nickname for matching. This code is not proof of payment.",
            )
          }}
        </p>
      </div>
      <el-image
        :src="payment.image"
        style="width: 220px; height: 220px"
        fit="contain"
        :alt="t('模拟收款二维码', 'Simulated payment QR code')"
      /><el-form label-position="right" label-width="128px"
        ><el-form-item :label="t('付款说明', 'Payment statement')"
          ><el-input
            v-model="declaration"
            type="textarea"
            maxlength="500" /></el-form-item
        ><el-upload
          :auto-upload="false"
          :show-file-list="false"
          accept="image/png,image/jpeg,image/webp"
          :disabled="proofBusy"
          :on-change="(file) => proof(file.raw)"
          ><el-button :loading="proofBusy">{{
            t("上传付款截图（可选）", "Upload screenshot (optional)")
          }}</el-button></el-upload
        ><el-image
          v-if="evidenceId"
          :src="'/api/member/evidence/' + evidenceId"
          style="width: 120px" /></el-form
      ><template #footer
        ><el-button @click="payDialog = false">{{
          t("关闭", "Close")
        }}</el-button
        ><el-button
          type="primary"
          :loading="busy"
          :disabled="proofBusy"
          @click="declare"
          >{{ t("我已付款", "I have paid") }}</el-button
        ></template
      ></el-dialog
    >
  </div>
</template>
