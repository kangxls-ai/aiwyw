<script setup lang="ts">
definePageMeta({ layout: "catalog" });
import { ElMessage, ElMessageBox } from "element-plus";
/** 个人中心只读取当前会话归属的数据；积分展示来自服务器，前端没有本地钱包。 */
import "~/assets/element-admin.css";
import "~/assets/auth.css";
import { compareIntegers } from "~/utils/listOrder";
import { decimalUnits, businessTime, commerceStates } from "~/utils/commerce";
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
  notifications = ref<any[]>([]);
const kinds: Record<string, string> = {
  recharge: "recharge",
  points: "points",
  resources: "resources",
  refunds: "refunds",
};
const names = computed(() => ({
  recharge: t("充值订单", "Top-ups"),
  points: t("积分流水", "Ledger"),
  resources: t("我的资源", "My resources"),
  refunds: t("退款申请", "Refunds"),
  notifications: t("站内通知", "Notifications"),
  profile: t("个人资料与安全", "Profile and security"),
  services: t("API与客服", "APIs and support"),
}));
async function load() {
  try {
    profile.value = await request("/api/member/profile");
    wallet.value = await request("/api/member/wallet");
    packages.value = await request("/api/member/packages");
    payment.value = await request("/api/member/payment");
    notifications.value = await request("/api/member/notifications");
    await list();
  } catch (e: any) {
    if (e.status === 401) await navigateTo("/account-access");
    else error.value = message(e);
  }
}
const listOrder = useListOrder(list, page);
async function list() {
  if (!kinds[tab.value]) return;
  busy.value = true;
  try {
    data.value = await request("/api/member/list/" + kinds[tab.value], {
      query: {
        page: page.value,
        pageSize: size.value,
        sort: listOrder.sort.value,
        direction: listOrder.direction.value,
      },
    });
    page.value = data.value.page;
  } catch (e) {
    error.value = message(e);
  } finally {
    busy.value = false;
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
      "付款声明已提交，核销后积分到账。",
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
      t("请说明申请整单退积分的原因", "Reason for a full points refund"),
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
function productName(row: any) {
  try {
    const product = JSON.parse(row.snapshot_json);
    return product.content.nameZh;
  } catch {
    return row.id;
  }
}
async function read(row: any) {
  await request(`/api/member/notifications/${row.id}/read`, { method: "POST" });
  row.read_at = Date.now();
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
    <AccountProfileSettings
      v-if="profile"
      v-show="tab === 'profile'"
      @changed="load"
    />
    <el-card v-if="tab === 'services'" shadow="never">
      <h2>{{ t("服务与支持", "Services and support") }}</h2>
      <div class="member-service-links">
        <NuxtLink class="button secondary" to="/api-services">{{
          t("API、Key与订阅", "APIs, keys and subscriptions")
        }}</NuxtLink>
        <NuxtLink class="button secondary" to="/support">{{
          t("咨询客服与查看历史", "Support and history")
        }}</NuxtLink>
      </div>
    </el-card>
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
          "验证邮箱后可充值和兑换。",
          "Verify your email before topping up or redeeming.",
        )
      }}</template
      ><el-button link type="primary" @click="resend">{{
        t("发送验证邮件", "Send verification email")
      }}</el-button
      ><el-button link @click="load">{{
        t("刷新状态", "Refresh")
      }}</el-button></el-alert
    >
    <div v-if="tab === 'recharge'" class="member-grid">
      <el-card shadow="never"
        ><h3>{{ t("可用积分", "Available points") }}</h3>
        <div class="money">{{ wallet.balance }}</div>
        <p>
          {{
            t(
              "模拟账本 · 与真实收款隔离",
              "Simulated ledger · isolated from real payments",
            )
          }}
        </p></el-card
      ><el-card shadow="never" style="grid-column: span 2"
        ><h3>{{ t("充值积分", "Top up points") }}</h3>
        <el-form inline @submit.prevent="create"
          ><el-form-item :label="t('充值类型', 'Type')"
            ><el-select v-model="packageId" style="width: 200px"
              ><el-option
                :label="t('普通充值（永久）', 'Standard (no expiry)')"
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
              >{{ t("创建充值订单", "Create top-up") }}</el-button
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
        <p class="admin-muted">
          {{
            t(
              "当前仅模拟充值，请勿实际转账。点击我已付款不会直接增加积分。",
              "Simulation only. Do not transfer money. A payment statement does not credit points.",
            )
          }}
        </p></el-card
      >
    </div>
    <el-card v-if="kinds[tab] || tab === 'notifications'" shadow="never"
      ><template v-if="tab === 'notifications'">
        <el-switch
          v-if="profile"
          v-model="profile.businessEmail"
          :active-text="t('接收业务邮件', 'Receive transaction emails')"
          @change="(value) => preference(Boolean(value))" />
        <el-empty
          v-if="!notifications.length"
          :description="t('暂无通知', 'No notifications')" />
        <div v-for="item in notifications" :key="item.id">
          <h3>
            {{ item.title }}
            <el-tag v-if="!item.read_at">{{ t("未读", "Unread") }}</el-tag>
          </h3>
          <p>{{ item.body }}</p>
          <small>{{ businessTime(item.created_at) }} UTC+8</small
          ><el-button v-if="!item.read_at" link @click="read(item)">{{
            t("标记已读", "Mark read")
          }}</el-button
          ><el-divider /></div
      ></template>
      <template v-else
        ><el-table
          :default-sort="listOrder.defaultSort"
          @sort-change="listOrder.change"
          :data="data.items"
          v-loading="busy"
          row-key="id"
          ><ObjectColumns server-sort /><el-table-column
            :label="t('内容', 'Details')"
            prop="details"
            sortable="custom"
            :sort-orders="['descending', 'ascending']"
            min-width="230"
            ><template #default="{ row }"
              ><strong>{{
                tab === "resources"
                  ? productName(row)
                  : row.package_name ||
                    row.reason ||
                    commerceStates[row.kind] ||
                    row.id
              }}</strong>
              <p v-if="row.payment_memo">
                {{ t("付款备注", "Payment remark") }}：<strong>{{
                  row.payment_memo
                }}</strong>
              </p>
              <p v-if="row.price_fen">
                ¥{{ decimalUnits(row.price_fen) }} /
                {{ decimalUnits(row.points_units) }} {{ t("积分", "points") }}
              </p>
              <p v-if="row.delta_units">
                {{ decimalUnits(row.delta_units) }} /
                {{ t("余额", "Balance") }} {{ decimalUnits(row.balance_after) }}
              </p>
              <p v-if="row.price_units">
                {{ decimalUnits(row.price_units) }} {{ t("积分", "points") }}
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
              ><template
                v-if="
                  tab === 'recharge' &&
                  ['PENDING', 'CLOSED'].includes(row.state)
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
          @current-change="list" /></template
    ></el-card>
    <el-card v-if="tab === 'points'" shadow="never"
      ><template #header>{{ t("积分批次", "Point batches") }}</template
      ><el-table
        :default-sort="{ prop: 'created_at', order: 'descending' }"
        :data="wallet.batches"
        ><ObjectColumns localized /><el-table-column
          :label="t('剩余积分', 'Remaining points')"
          prop="remaining_units"
          sortable
          :sort-method="
            (a: any, b: any) =>
              compareIntegers(a.remaining_units, b.remaining_units)
          "
          :sort-orders="['descending', 'ascending']"
          ><template #default="{ row }">{{
            decimalUnits(row.remaining_units)
          }}</template></el-table-column
        ><el-table-column
          :label="t('到期时间（北京时间）', 'Expiry (UTC+8)')"
          prop="expires_at"
          sortable
          :sort-orders="['descending', 'ascending']"
          ><template #default="{ row }">{{
            row.expires_at
              ? businessTime(row.expires_at)
              : t("永久有效", "No expiry")
          }}</template></el-table-column
        ></el-table
      ></el-card
    >
    <el-dialog
      v-model="payDialog"
      :title="t('模拟充值订单', 'Simulated top-up')"
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
