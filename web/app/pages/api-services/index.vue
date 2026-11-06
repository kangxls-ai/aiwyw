<script setup lang="ts">
/** API目录公开，Key、调试与订阅只读取当前登录账户；完整Key仅短暂展示在创建对话框。 */
import "~/assets/element-admin.css";
import { ElMessageBox } from "element-plus";
import { businessTime, decimalUnits } from "~/utils/commerce";
definePageMeta({ layout: "catalog" });
const { request, message } = useApi(),
  { t } = useLanguage();
const catalog = ref<any>({ apis: [], plans: [] }),
  member = ref<any>(null),
  error = ref(""),
  notice = ref(""),
  busy = ref(false),
  active = ref("services");
const route = useRoute(),
  search = ref(String(route.query.q || ""));
const visibleApis = computed(() =>
  catalog.value.apis.filter(
    (a: any) =>
      (!route.query.api || a.slug === route.query.api) &&
      `${a.name} ${a.name_en} ${a.description} ${a.slug}`
        .toLowerCase()
        .includes(String(route.query.q || "").toLowerCase()),
  ),
);
function searchApis() {
  navigateTo({
    path: "/api-services",
    query: search.value ? { q: search.value } : {},
  });
}
function newDebugRequest() {
  debug.requestKey = crypto.randomUUID();
}
const preferences = reactive<
  Record<string, { payg: boolean; overage: boolean }>
>({});
const subscriptionPage = ref(1),
  periods = ref<any>({ items: [], total: 0 });
async function loadPeriods() {
  try {
    periods.value = await request("/api/member/api/subscriptions/page", {
      query: { page: subscriptionPage.value, pageSize: 20 },
    });
    subscriptionPage.value = periods.value.page;
  } catch (e) {
    error.value = message(e);
  }
}
async function load() {
  catalog.value = await request("/api/public/apis");
  try {
    member.value = await request("/api/member/api");
    for (const api of catalog.value.apis) {
      const p = member.value.preferences.find((x: any) => x.api_id === api.id);
      preferences[api.id] = { payg: !!p?.payg, overage: !!p?.overage };
    }
  } catch (e: any) {
    if (![401, 403].includes(e.status)) error.value = message(e);
  }
}
// SSR只加载公开目录，私人数据挂载后再加载，避免服务端不带Cookie时与浏览器产生不一致。
const initialCatalog = await useAsyncData("api-service-directory", () =>
  request<any>("/api/public/apis"),
);
if (initialCatalog.data.value) catalog.value = initialCatalog.data.value;
onMounted(() => load().catch((e) => (error.value = message(e))));
const dialogButtons = () => ({
  confirmButtonText: t("确定", "Confirm"),
  cancelButtonText: t("取消", "Cancel"),
});
const keyDialog = ref(false),
  token = ref(""),
  keyId = ref("");
const keyForm = reactive({
  name: "",
  apiIds: [] as string[],
  minuteLimit: 60,
  dayLimit: 0,
  expiresAt: null as number | null,
  enabled: true,
  revision: 0,
});
function editKey(row?: any) {
  Object.assign(
    keyForm,
    {
      name: "",
      apiIds: [],
      minuteLimit: Math.min(60, member.value.accountMinuteLimit),
      dayLimit: 0,
      expiresAt: null,
      enabled: true,
      revision: 0,
    },
    row
      ? {
          ...row,
          apiIds: [...row.apiIds],
          minuteLimit: Number(row.minute_limit),
          dayLimit: Number(row.day_limit),
          expiresAt: row.expires_at === null ? null : Number(row.expires_at),
        }
      : {},
  );
  keyId.value = row?.id || "";
  token.value = "";
  keyDialog.value = true;
}
async function saveKey() {
  busy.value = true;
  error.value = "";
  try {
    const r = await request<any>(
      "/api/member/api/keys" + (keyId.value ? "/" + keyId.value : ""),
      { method: keyId.value ? "PUT" : "POST", body: keyForm },
    );
    token.value = r.token;
    await load();
    if (!token.value) keyDialog.value = false;
  } catch (e) {
    error.value = message(e);
  } finally {
    busy.value = false;
  }
}
async function removeKey(row: any) {
  try {
    await ElMessageBox.confirm(
      t(
        "未使用Key可删除；已有调用记录的Key请编辑停用。",
        "Unused keys may be deleted. Disable keys with call history instead.",
      ),
      t("删除Key", "Delete key"),
      dialogButtons(),
    );
    await request("/api/member/api/keys/" + row.id, {
      method: "DELETE",
      query: { revision: row.revision },
    });
    await load();
  } catch (e) {
    if (e !== "cancel" && e !== "close") error.value = message(e);
  }
}
async function rotateKey(row: any) {
  try {
    await ElMessageBox.confirm(
      t(
        "轮换后旧Key立即失效，请保存新Key并更新调用程序。",
        "Rotating immediately invalidates the old key. Save the new key and update your clients.",
      ),
      t("轮换Key", "Rotate key"),
      dialogButtons(),
    );
    const r = await request<any>("/api/member/api/keys/" + row.id + "/rotate", {
      method: "POST",
      body: { revision: row.revision },
    });
    token.value = r.token;
    keyDialog.value = true;
    await load();
  } catch (e) {
    if (e !== "cancel" && e !== "close") error.value = message(e);
  }
}
async function preference(api: any) {
  try {
    await request("/api/member/api/preferences/" + api.id, {
      method: "PUT",
      body: preferences[api.id],
    });
    notice.value = t("计费选择已保存", "Billing preferences saved");
  } catch (e) {
    error.value = message(e);
  }
}
const purchases = new Map<string, string>();
async function buy(plan: any) {
  if (!member.value) {
    await navigateTo("/account-access");
    return;
  }
  try {
    await ElMessageBox.confirm(
      t(
        `消耗 ${decimalUnits(plan.price_units)} 积分，${plan.quota} 次 / ${plan.days} 天。提前续费接当前到期，余量不结转。当前为模拟账本。`,
        `Costs ${decimalUnits(plan.price_units)} points for ${plan.quota} calls / ${plan.days} days. Early renewal starts at the previous expiry. Unused quota does not carry over. This uses the simulated ledger.`,
      ),
      t("确认购买或续费", "Confirm purchase or renewal"),
      dialogButtons(),
    );
    busy.value = true;
    const k = plan.id + ":" + plan.revision;
    if (!purchases.has(k)) purchases.set(k, crypto.randomUUID());
    await request("/api/member/api/subscriptions", {
      method: "POST",
      body: {
        planId: plan.id,
        revision: plan.revision,
        requestKey: purchases.get(k),
      },
    });
    purchases.delete(k);
    await load();
    notice.value = t(
      "套餐已购买，请查看周期时间",
      "Plan purchased. Check the start and expiry dates.",
    );
    active.value = "subscriptions";
  } catch (e) {
    if (e !== "cancel" && e !== "close") error.value = message(e);
  } finally {
    busy.value = false;
  }
}
async function refund(row: any) {
  try {
    const r = await ElMessageBox.prompt(
      t(
        "仅未发生成功调用的套餐可整单退积分，提交后该周期暂停调用，等待客服审核。",
        "Only unused plans qualify for a full points refund. Calls on this period pause while support reviews the request.",
      ),
      t("申请套餐退款", "Request plan refund"),
      {
        ...dialogButtons(),
        inputValidator: (v: string) =>
          !!v?.trim() || t("请填写理由", "Enter a reason"),
      },
    );
    await request("/api/member/api/subscriptions/" + row.id + "/refund", {
      method: "POST",
      body: { reason: r.value },
    });
    await load();
    notice.value = t("退款申请已提交", "Refund request submitted");
    await loadPeriods();
  } catch (e) {
    if (e !== "cancel" && e !== "close") error.value = message(e);
  }
}
const debug = reactive({
    keyId: "",
    slug: "",
    input: '{"text":"Hello AI万有网"}',
    requestKey: "",
  }),
  output = ref(""),
  debugBusy = ref(false);
onMounted(() => (debug.requestKey = crypto.randomUUID()));
const scopedApis = computed(() =>
  catalog.value.apis.filter((a: any) =>
    member.value?.keys
      .find((k: any) => k.id === debug.keyId)
      ?.apiIds.includes(a.id),
  ),
);
async function run() {
  error.value = "";
  try {
    const input = JSON.parse(debug.input);
    debugBusy.value = true;
    const r = await request("/api/member/api/debug/" + debug.slug, {
      method: "POST",
      body: { keyId: debug.keyId, requestKey: debug.requestKey, input },
    });
    output.value = JSON.stringify(r, null, 2);
    await logs();
  } catch (e) {
    error.value =
      e instanceof SyntaxError
        ? t("请输入有效JSON", "Enter valid JSON")
        : message(e);
  } finally {
    debugBusy.value = false;
  }
}
const calls = ref<any>({ items: [], total: 0 }),
  page = ref(1),
  state = ref("");
async function logs() {
  try {
    calls.value = await request("/api/member/api/calls", {
      query: { page: page.value, pageSize: 20, state: state.value },
    });
    page.value = calls.value.page;
  } catch (e) {
    error.value = message(e);
  }
}
watch(active, (v) => {
  if (v === "calls") logs();
  if (v === "subscriptions") loadPeriods();
});
useHead(() => ({ title: t("API 服务 · AI万有网", "API services · AI万有网") }));
</script>
<template>
  <main class="directory-width api-center">
    <h1>{{ t("API 服务", "API services") }}</h1>
    <p class="muted">
      {{
        t(
          "仅成功调用计费；多Key共享账户与套餐额度。时间采用北京时间。",
          "Only successful calls are billed. Keys share account and plan quotas. Times are shown in Beijing time.",
        )
      }}
    </p>
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
    />
    <p v-if="!member">
      <NuxtLink to="/account-access">{{
        t(
          "登录并验证邮箱后管理Key、订阅和调试",
          "Sign in and verify your email to manage keys, plans and debugging",
        )
      }}</NuxtLink>
    </p>
    <el-tabs v-model="active">
      <el-tab-pane :label="t('接口与套餐', 'APIs and plans')" name="services">
        <el-form inline @submit.prevent="searchApis"
          ><el-form-item :label="t('检索API', 'Search APIs')"
            ><el-input
              v-model="search"
              clearable
              maxlength="100"
              style="width: 280px" /></el-form-item
          ><el-button type="primary" @click="searchApis">{{
            t("搜索", "Search")
          }}</el-button></el-form
        >
        <el-empty
          v-if="!visibleApis.length"
          :description="t('暂无匹配API', 'No matching APIs')"
        />
        <el-card
          v-for="api in visibleApis"
          :key="api.id"
          shadow="never"
          class="api-card"
        >
          <h2>
            <a
              :href="'/api-services/' + api.slug"
              target="_blank"
              rel="noopener noreferrer"
              >{{ t(api.name, api.name_en || api.name) }}</a
            >
          </h2>
          <a
            :href="'/api-services/' + api.slug"
            target="_blank"
            rel="noopener noreferrer"
            >{{ t("查看详情", "View details") }} →</a
          >
          <p>{{ t(api.description, api.description_en || api.description) }}</p>
          <p>
            {{ t("成功按次单价", "Price per successful call") }}：{{
              decimalUnits(api.price_units)
            }}
            {{ t("积分", "points") }}
          </p>
          <pre>
POST /api/v1/invoke/{{ api.slug }}
Authorization: Bearer &lt;YOUR_KEY&gt;
Idempotency-Key: &lt;UNIQUE_REQUEST_ID&gt;
Content-Type: application/json

{{
              api.adapter === "HTTPS_JSON"
                ? "按接口说明提供JSON / JSON as documented"
                : '{"text":"需要处理的文本 / Your text"}'
            }}</pre
          >
          <div v-if="member && preferences[api.id]" class="api-actions">
            <el-checkbox v-model="preferences[api.id]!.payg">{{
              t(
                "无有效套餐时允许按次扣分",
                "Allow pay-as-you-go without an active plan",
              )
            }}</el-checkbox
            ><el-checkbox v-model="preferences[api.id]!.overage">{{
              t(
                "周期额度用尽后允许扣分",
                "Allow points after the quota is exhausted",
              )
            }}</el-checkbox
            ><el-button @click="preference(api)">{{
              t("保存计费选择", "Save billing preferences")
            }}</el-button>
          </div>
          <div class="api-plans">
            <el-card
              v-for="plan in catalog.plans.filter(
                (p: any) => p.api_id === api.id,
              )"
              :key="plan.id"
              shadow="never"
              ><strong>{{ plan.name }}</strong>
              <p>
                {{ decimalUnits(plan.price_units) }} {{ t("积分", "points") }} ·
                {{ plan.quota }} {{ t("次", "calls") }} / {{ plan.days }}
                {{ t("天", "days") }}
              </p>
              <el-button type="primary" :disabled="busy" @click="buy(plan)">{{
                t("购买 / 续费", "Buy / renew")
              }}</el-button></el-card
            >
          </div>
        </el-card>
      </el-tab-pane>
      <el-tab-pane v-if="member" :label="t('我的Key', 'My keys')" name="keys"
        ><el-button type="primary" @click="editKey()">{{
          t("新增Key", "Create key")
        }}</el-button
        ><el-table :data="member.keys"
          ><ObjectColumns localized /><el-table-column
            prop="name"
            :label="t('名称', 'Name')"
          /><el-table-column
            prop="prefix"
            :label="t('密钥前缀', 'Prefix')"
          /><el-table-column
            prop="minute_limit"
            :label="t('每分钟上限', 'Per minute')"
          /><el-table-column
            prop="day_limit"
            :label="t('每日上限，0不限', 'Daily limit, 0 unlimited')"
          /><el-table-column :label="t('状态', 'Status')"
            ><template #default="{ row }">{{
              row.enabled ? t("启用", "Enabled") : t("停用", "Disabled")
            }}</template></el-table-column
          ><el-table-column :label="t('操作', 'Actions')"
            ><template #default="{ row }"
              ><el-button link @click="editKey(row)">{{
                t("编辑", "Edit")
              }}</el-button
              ><el-button link type="primary" @click="rotateKey(row)">{{
                t("轮换", "Rotate")
              }}</el-button
              ><el-button link type="danger" @click="removeKey(row)">{{
                t("删除", "Delete")
              }}</el-button></template
            ></el-table-column
          ></el-table
        ></el-tab-pane
      >
      <el-tab-pane
        v-if="member"
        :label="t('我的订阅', 'My subscriptions')"
        name="subscriptions"
        ><el-table :data="periods.items"
          ><ObjectColumns localized /><el-table-column
            prop="plan_name"
            :label="t('套餐', 'Plan')"
          /><el-table-column :label="t('生效时间', 'Starts')" min-width="180"
            ><template #default="{ row }">{{
              businessTime(row.start_at)
            }}</template></el-table-column
          ><el-table-column :label="t('到期时间', 'Expires')" min-width="180"
            ><template #default="{ row }">{{
              businessTime(row.end_at)
            }}</template></el-table-column
          ><el-table-column
            :label="t('已用 / 预留 / 总额', 'Used / held / quota')"
            ><template #default="{ row }"
              >{{ row.used_count }} / {{ row.held_count }} /
              {{ row.quota }}</template
            ></el-table-column
          ><el-table-column
            prop="refund_state"
            :label="t('退款状态', 'Refund')"
          /><el-table-column :label="t('操作', 'Actions')"
            ><template #default="{ row }"
              ><el-button
                v-if="
                  row.state === 'ACTIVE' &&
                  Number(row.success_count) === 0 &&
                  row.refund_state !== 'PENDING'
                "
                link
                @click="refund(row)"
                >{{ t("申请退积分", "Request refund") }}</el-button
              ></template
            ></el-table-column
          ></el-table
        ><el-pagination
          v-model:current-page="subscriptionPage"
          :total="periods.total"
          :page-size="20"
          layout="total,prev,pager,next"
          @current-change="loadPeriods"
        />
        ></el-tab-pane
      >
      <el-tab-pane
        v-if="member"
        :label="t('在线调试', 'Online debugger')"
        name="debug"
        ><el-alert
          type="info"
          :closable="false"
          :title="
            t(
              '使用本人Key，成功调用正常扣额度或积分。相同请求标识重试不重复计费。',
              'Uses your own key and normal billing. Retrying the same request ID is not billed twice.',
            )
          "
        /><el-form class="debug-form" label-position="right" label-width="130px"
          ><el-form-item :label="t('本人Key', 'Your key')"
            ><el-select
              v-model="debug.keyId"
              :aria-label="t('调试Key', 'Debug key')"
              ><el-option
                v-for="key in member.keys.filter((k: any) => k.enabled)"
                :key="key.id"
                :value="key.id"
                :label="key.name" /></el-select></el-form-item
          ><el-form-item label="API"
            ><el-select
              v-model="debug.slug"
              :aria-label="t('调试API', 'Debug API')"
              ><el-option
                v-for="api in scopedApis"
                :key="api.id"
                :value="api.slug"
                :label="api.name" /></el-select></el-form-item
          ><el-form-item :label="t('请求标识', 'Request ID')"
            ><el-input
              v-model="debug.requestKey"
              :aria-label="t('调试请求标识', 'Debug request ID')"
            /><el-button @click="newDebugRequest">{{
              t("新请求", "New request")
            }}</el-button></el-form-item
          ><el-form-item :label="t('JSON输入', 'JSON input')"
            ><el-input
              v-model="debug.input"
              type="textarea"
              :rows="6"
              :aria-label="
                t('调试JSON输入', 'Debug JSON input')
              " /></el-form-item
          ><el-form-item
            ><el-button
              type="primary"
              :loading="debugBusy"
              :disabled="!debug.keyId || !debug.slug"
              @click="run"
              >{{ t("执行（正常计费）", "Run (normal billing)") }}</el-button
            ></el-form-item
          ></el-form
        >
        <pre v-if="output" class="debug-output">{{ output }}</pre>
      </el-tab-pane>
      <el-tab-pane
        v-if="member"
        :label="t('调用记录', 'Call history')"
        name="calls"
        ><el-form inline
          ><el-form-item :label="t('状态', 'Status')"
            ><el-select v-model="state" clearable style="width: 160px"
              ><el-option
                v-for="s in ['SUCCESS', 'FAILED', 'RUNNING', 'UNKNOWN']"
                :key="s"
                :value="s"
                :label="s" /></el-select></el-form-item
          ><el-button
            @click="
              page = 1;
              logs();
            "
            >{{ t("查询", "Search") }}</el-button
          ></el-form
        ><el-table :data="calls.items"
          ><ObjectColumns localized /><el-table-column
            :label="t('时间', 'Time')"
            min-width="180"
            ><template #default="{ row }">{{
              businessTime(row.created_at)
            }}</template></el-table-column
          ><el-table-column prop="api_name" label="API" /><el-table-column
            prop="state"
            :label="t('状态', 'Status')" /><el-table-column
            prop="billing"
            :label="t('计费方式', 'Billing')" /><el-table-column
            :label="t('积分', 'Points')"
            ><template #default="{ row }">{{
              row.state === "SUCCESS" ? decimalUnits(row.price_units) : "0.00"
            }}</template></el-table-column
          ><el-table-column
            prop="id"
            :label="t('调用编号', 'Request ID')"
            min-width="270" /></el-table
        ><el-pagination
          v-model:current-page="page"
          :total="calls.total"
          :page-size="20"
          layout="total,prev,pager,next"
          @current-change="logs"
      /></el-tab-pane>
    </el-tabs>
    <el-dialog
      v-model="keyDialog"
      :title="t('Key管理', 'Manage key')"
      width="min(600px,94vw)"
      @closed="token = ''"
    >
      <div v-if="token">
        <el-alert
          type="warning"
          :closable="false"
          :title="
            t(
              '完整Key仅显示这一次，请立即复制保存。关闭后无法找回。',
              'This key is shown only once. Copy it now; it cannot be recovered.',
            )
          "
        /><el-input
          :model-value="token"
          readonly
          type="textarea"
          :aria-label="t('新建API密钥', 'New API secret')"
          :rows="3"
        /><el-button @click="keyDialog = false">{{
          t("已保存，关闭", "Saved, close")
        }}</el-button>
      </div>
      <el-form v-else label-position="right" label-width="120px"
        ><el-form-item :label="t('名称', 'Name')"
          ><el-input
            v-model="keyForm.name"
            :aria-label="t('Key名称', 'Key name')"
            maxlength="80" /></el-form-item
        ><el-form-item :label="t('API权限', 'API permissions')"
          ><el-select
            v-model="keyForm.apiIds"
            multiple
            :aria-label="t('Key API权限', 'Key API permissions')"
            ><el-option
              v-for="api in catalog.apis"
              :key="api.id"
              :value="api.id"
              :label="api.name" /></el-select></el-form-item
        ><el-form-item :label="t('每分钟上限', 'Per minute')"
          ><el-input-number
            v-model="keyForm.minuteLimit"
            :min="1"
            :max="member?.accountMinuteLimit" /></el-form-item
        ><el-form-item :label="t('每日上限', 'Daily limit')"
          ><el-input-number
            v-model="keyForm.dayLimit"
            :min="0"
            :max="1000000000"
          /><span
            >0 {{ t("不另限制", "means no additional limit") }}</span
          ></el-form-item
        ><el-form-item :label="t('到期时间', 'Expires')"
          ><el-date-picker
            v-model="keyForm.expiresAt"
            type="datetime"
            value-format="x"
            clearable /></el-form-item
        ><el-form-item :label="t('启用', 'Enabled')"
          ><el-switch v-model="keyForm.enabled" /></el-form-item
        ><el-form-item
          ><el-button type="primary" :loading="busy" @click="saveKey">{{
            t("保存Key", "Save key")
          }}</el-button></el-form-item
        ></el-form
      >
    </el-dialog>
  </main>
</template>
<style scoped>
.api-center {
  padding-top: 32px;
  padding-bottom: 48px;
}
.api-card {
  margin: 20px 0;
}
.api-card h2 {
  font-size: 22px;
}
.api-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: center;
  margin: 18px 0;
}
.api-plans {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 14px;
  margin-top: 20px;
}
pre {
  background: #f6f5fb;
  padding: 18px;
  border-radius: 10px;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  font: 13px/1.8 monospace;
}
.debug-form {
  max-width: 820px;
  margin: 24px 0;
}
.el-table {
  margin-top: 16px;
}
.el-pagination {
  margin-top: 20px;
}
@media (max-width: 700px) {
  .api-plans {
    grid-template-columns: 1fr;
  }
}
</style>
