<script setup lang="ts">
/** API目录公开，Key、调试与订阅只读取当前登录账户；完整Key仅短暂展示在创建对话框。 */
import "~/assets/element-admin.css";
import { ElMessageBox } from "element-plus";
import { businessTime, decimalUnits } from "~/utils/commerce";
const { request, message } = useApi(),
  { t } = useLanguage();
const catalog = ref<any>({ apis: [], plans: [] }),
  member = ref<any>(null),
  error = ref(""),
  notice = ref(""),
  busy = ref(false),
  active = ref("services");
const route = useRoute(),
  search = ref(String(route.query.q || route.query.api || ""));
const rows = ref<any>({ items: [], total: 0, page: 1 }),
  recordPage = ref(1),
  recordSize = ref(10),
  filters = reactive({
    q: search.value,
    state: "",
    dates: null as string[] | null,
  });
const keyApiLabels = ref<Record<string, string>>({});
const plansDialog = ref(false),
  planApi = ref<any>(null),
  planData = ref<any>({ items: [], total: 0, page: 1 }),
  planPage = ref(1),
  planFilters = reactive({ q: "", state: "", dates: null as string[] | null }),
  billingDialog = ref(false),
  billingApi = ref<any>(null),
  debugDialog = ref(false),
  callDialog = ref(false),
  selectedCall = ref<any>(null);
const states = computed(() =>
  active.value === "keys"
    ? [
        { value: "ENABLED", label: t("启用", "Enabled") },
        { value: "DISABLED", label: t("停用", "Disabled") },
      ]
    : active.value === "calls"
      ? ["SUCCESS", "FAILED", "RUNNING", "UNKNOWN"].map((value) => ({
          value,
          label:
            (
              {
                SUCCESS: "成功",
                FAILED: "失败",
                RUNNING: "进行中",
                UNKNOWN: "结果未知",
              } as Record<string, string>
            )[value] || value,
        }))
      : active.value === "subscriptions"
        ? ["ACTIVE", "REFUNDED"].map((value) => ({
            value,
            label:
              value === "ACTIVE"
                ? t("有效", "Active")
                : t("已退款", "Refunded"),
          }))
        : [],
);
const recordOrder = useListOrder(listRecords, recordPage);
let listGeneration = 0;
async function listRecords() {
  const generation = ++listGeneration;
  try {
    busy.value = true;
    const kind = (
      {
        services: "apis",
        keys: "keys",
        subscriptions: "subscriptions",
        calls: "calls",
        debug: "debug",
      } as Record<string, string>
    )[active.value];
    const result = await request<any>(
      `${member.value ? "/api/member/api/records/" : "/api/public/api-records/"}${kind}`,
      {
        query: {
          page: recordPage.value,
          pageSize: recordSize.value,
          sort: recordOrder.sort.value,
          direction: recordOrder.direction.value,
          q: filters.q,
          state: filters.state,
          from: filters.dates?.[0] || "",
          until: filters.dates?.[1] || "",
          apiId: active.value === "services" ? route.query.apiId || "" : "",
        },
      },
    );
    if (generation !== listGeneration) return;
    rows.value = result;
    recordPage.value = rows.value.page;
    if (kind === "apis")
      for (const api of rows.value.items) {
        preferences[api.id] = {
          payg: !!api.preference?.payg,
          overage: !!api.preference?.overage,
        };
      }
  } catch (e) {
    if (generation === listGeneration) error.value = message(e);
  } finally {
    if (generation === listGeneration) busy.value = false;
  }
}
function queryRecords() {
  recordPage.value = 1;
  listRecords();
}
const planDebugApi = ref<any>(null);
function openDebug(api: any) {
  planDebugApi.value = api;
  debug.slug = api.slug;
  debug.keyId = "";
  debug.requestKey = crypto.randomUUID();
  output.value = "";
  debugDialog.value = true;
}
async function openPlans(api: any) {
  planApi.value = api;
  planPage.value = 1;
  planFilters.q = "";
  planFilters.dates = null;
  plansDialog.value = true;
  await listPlans();
}
async function listPlans() {
  try {
    planData.value = await request<any>("/api/public/api-records/plans", {
      query: {
        apiId: planApi.value.id,
        page: planPage.value,
        pageSize: 10,
        q: planFilters.q,
        from: planFilters.dates?.[0] || "",
        until: planFilters.dates?.[1] || "",
      },
    });
    planPage.value = planData.value.page;
  } catch (e) {
    error.value = message(e);
  }
}
function openBilling(api: any) {
  billingApi.value = api;
  billingDialog.value = true;
}
function newDebugRequest() {
  debug.requestKey = crypto.randomUUID();
}
const preferences = reactive<
  Record<string, { payg: boolean; overage: boolean }>
>({});
async function loadPeriods() {
  await listRecords();
}
async function load() {
  try {
    member.value = await request<any>("/api/member/api?summary=true");
  } catch (e: any) {
    member.value = null;
    if (![401, 403].includes(e.status)) error.value = message(e);
  }
  await listRecords();
}
const initial = await useAsyncData("api-services-first-page", () =>
  request<any>("/api/public/api-records/apis", {
    query: { q: filters.q, pageSize: recordSize.value },
  }),
);
if (initial.data.value) rows.value = initial.data.value;
// 私有列表仅挂载后读取当前账户；未登录目录同样走服务端分页。
onMounted(async () => {
  try {
    await load();
    const slug = String(route.query.api || "");
    if (
      !member.value ||
      route.query.debug !== "1" ||
      !/^[a-z0-9-]{1,100}$/.test(slug)
    )
      return;
    active.value = "debug";
    const result = await request<any>("/api/member/api/records/debug", {
      query: { slug, pageSize: 1 },
    });
    if (result.items[0]) openDebug(result.items[0]);
    else
      notice.value = t(
        "请先创建一个有权调用此接口的 Key。",
        "Create a key with access to this API first.",
      );
  } catch (e) {
    error.value = message(e);
  }
});
const accessUrl = computed(() => {
  const slug = String(route.query.api || "");
  return route.query.debug === "1" && /^[a-z0-9-]{1,100}$/.test(slug)
    ? {
        path: "/account-access",
        query: { return: "/api-services?api=" + slug + "&debug=1" },
      }
    : "/account-access";
});
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
  keyApiLabels.value = row?.apiLabels || {};
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
    return true;
  } catch (e) {
    error.value = message(e);
    return false;
  }
}
async function saveBilling() {
  busy.value = true;
  try {
    if (await preference(billingApi.value)) billingDialog.value = false;
  } finally {
    busy.value = false;
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
        `消耗 ${decimalUnits(plan.price_units)} 鼓励值，${plan.quota} 次 / ${plan.days} 天。提前续费接当前到期，余量不结转。当前为模拟账本。`,
        `Costs ${decimalUnits(plan.price_units)} encouragement for ${plan.quota} calls / ${plan.days} days. Early renewal starts at the previous expiry. Unused quota does not carry over. This uses the simulated ledger.`,
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
    plansDialog.value = false;
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
        "仅未发生成功调用的套餐可整单退鼓励值，提交后该周期暂停调用，等待客服审核。",
        "Only unused plans qualify for a full encouragement refund. Calls on this period pause while support reviews the request.",
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
async function logs() {
  if (active.value === "calls") await listRecords();
}
watch(active, () => {
  recordPage.value = 1;
  recordOrder.sort.value = "created_at";
  recordOrder.direction.value = "descending";
  filters.q = "";
  filters.state = "";
  filters.dates = null;
  listRecords();
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
      <NuxtLink :to="accessUrl">{{
        t(
          "登录并验证邮箱后管理Key、订阅和调试",
          "Sign in and verify your email to manage keys, plans and debugging",
        )
      }}</NuxtLink>
    </p>
    <el-tabs v-model="active"
      ><el-tab-pane
        :label="t('接口与套餐', 'APIs and plans')"
        name="services" /><el-tab-pane
        v-if="member"
        :label="t('我的Key', 'My keys')"
        name="keys" /><el-tab-pane
        v-if="member"
        :label="t('我的订阅', 'My subscriptions')"
        name="subscriptions" /><el-tab-pane
        v-if="member"
        :label="t('在线调试', 'Online debugger')"
        name="debug" /><el-tab-pane
        v-if="member"
        :label="t('调用记录', 'Call history')"
        name="calls"
    /></el-tabs>
    <RecordQuery
      v-model="filters"
      :states="states"
      :loading="busy"
      @search="queryRecords"
      ><el-button v-if="active === 'keys'" @click="editKey()">{{
        t("新增Key", "Create key")
      }}</el-button></RecordQuery
    >
    <el-table
      :key="active"
      :default-sort="recordOrder.defaultSort"
      @sort-change="recordOrder.change"
      :data="rows.items"
      v-loading="busy"
      row-key="id"
      ><ObjectColumns localized :show-creator="false" server-sort />
      <template v-if="active === 'services' || active === 'debug'"
        ><el-table-column :label="t('接口名称', 'API name')" min-width="180"
          ><template #default="{ row }">{{
            t(row.name, row.name_en || row.name)
          }}</template></el-table-column
        ><el-table-column
          :label="t('描述', 'Description')"
          min-width="260"
          show-overflow-tooltip
          ><template #default="{ row }">{{
            t(row.description, row.description_en || row.description)
          }}</template></el-table-column
        ><el-table-column
          :label="t('成功调用鼓励值', 'Encouragement per success')"
          min-width="160"
          ><template #default="{ row }">{{
            decimalUnits(row.price_units)
          }}</template></el-table-column
        ></template
      >
      <template v-if="active === 'keys'"
        ><el-table-column
          prop="name"
          :label="t('名称', 'Name')"
          min-width="160"
        /><el-table-column
          prop="prefix"
          :label="t('密钥前缀', 'Prefix')"
          min-width="150"
        /><el-table-column
          prop="minute_limit"
          :label="t('每分钟上限', 'Per minute')"
        /><el-table-column
          prop="day_limit"
          :label="t('每日上限，0不限', 'Daily limit, 0 unlimited')"
          min-width="160"
        /><el-table-column :label="t('状态', 'Status')"
          ><template #default="{ row }">{{
            row.enabled ? t("启用", "Enabled") : t("停用", "Disabled")
          }}</template></el-table-column
        ><el-table-column :label="t('到期时间', 'Expires')" min-width="180"
          ><template #default="{ row }">{{
            row.expires_at
              ? businessTime(row.expires_at)
              : t("永久有效", "No expiry")
          }}</template></el-table-column
        ></template
      >
      <template v-if="active === 'subscriptions'"
        ><el-table-column
          prop="plan_name"
          :label="t('套餐', 'Plan')"
          min-width="170" /><el-table-column
          :label="t('生效时间', 'Starts')"
          min-width="180"
          ><template #default="{ row }">{{
            businessTime(row.start_at)
          }}</template></el-table-column
        ><el-table-column :label="t('到期时间', 'Expires')" min-width="180"
          ><template #default="{ row }">{{
            businessTime(row.end_at)
          }}</template></el-table-column
        ><el-table-column
          :label="t('已用 / 预留 / 总额', 'Used / held / quota')"
          min-width="190"
          ><template #default="{ row }"
            >{{ row.used_count }} / {{ row.held_count }} /
            {{ row.quota }}</template
          ></el-table-column
        ><el-table-column prop="refund_state" :label="t('退款状态', 'Refund')"
      /></template>
      <template v-if="active === 'calls'"
        ><el-table-column
          prop="api_name"
          label="API"
          min-width="180"
        /><el-table-column
          prop="key_prefix"
          :label="t('Key前缀', 'Key prefix')"
          min-width="160"
        /><el-table-column
          prop="state"
          :label="t('状态', 'Status')"
        /><el-table-column :label="t('计费方式', 'Billing')"
          ><template #default="{ row }">{{
            row.billing === "POINTS"
              ? t("鼓励值", "Encouragement")
              : row.billing === "QUOTA"
                ? t("套餐额度", "Plan quota")
                : t("免费", "Free")
          }}</template></el-table-column
        ><el-table-column :label="t('鼓励值', 'Encouragement')"
          ><template #default="{ row }">{{
            row.state === "SUCCESS" ? decimalUnits(row.price_units) : "0.00"
          }}</template></el-table-column
        ></template
      >
      <el-table-column
        :label="t('操作', 'Actions')"
        min-width="240"
        align="center"
        ><template #default="{ row }">
          <template v-if="active === 'services'"
            ><a
              :href="'/api-services/' + row.slug"
              target="_blank"
              rel="noopener"
              >{{ t("查看详情", "Details") }}</a
            ><el-button link type="primary" @click="openPlans(row)">{{
              t("套餐 / 续费", "Plans / renew")
            }}</el-button
            ><el-button v-if="member" link @click="openBilling(row)">{{
              t("计费设置", "Billing settings")
            }}</el-button></template
          >
          <template v-if="active === 'keys'"
            ><el-button link @click="editKey(row)">{{
              t("编辑", "Edit")
            }}</el-button
            ><el-button link @click="rotateKey(row)">{{
              t("轮换", "Rotate")
            }}</el-button
            ><el-button link type="danger" @click="removeKey(row)">{{
              t("删除", "Delete")
            }}</el-button></template
          >
          <template v-if="active === 'subscriptions'"
            ><el-button
              v-if="
                row.state === 'ACTIVE' &&
                Number(row.success_count) === 0 &&
                row.refund_state !== 'PENDING'
              "
              link
              @click="refund(row)"
              >{{ t("申请退鼓励值", "Request refund") }}</el-button
            ><el-button
              link
              @click="
                selectedCall = row;
                callDialog = true;
              "
              >{{ t("查看详情", "Details") }}</el-button
            ></template
          >
          <el-button
            v-if="active === 'debug'"
            link
            type="primary"
            @click="openDebug(row)"
            >{{ t("开始调试", "Debug") }}</el-button
          ><el-button
            v-if="active === 'calls'"
            link
            @click="
              selectedCall = row;
              callDialog = true;
            "
            >{{ t("查看详情", "Details") }}</el-button
          >
        </template></el-table-column
      > </el-table
    ><el-pagination
      v-model:current-page="recordPage"
      v-model:page-size="recordSize"
      :page-sizes="[10, 20, 50]"
      :total="rows.total"
      layout="total,sizes,prev,pager,next"
      @current-change="listRecords"
      @size-change="queryRecords"
    />
    <el-dialog
      v-model="plansDialog"
      :title="t('接口套餐', 'API plans')"
      width="min(1000px,96vw)"
      ><RecordQuery
        v-model="planFilters"
        @search="
          planPage = 1;
          listPlans();
        " /><el-table :data="planData.items"
        ><ObjectColumns localized :show-creator="false" /><el-table-column
          prop="name"
          :label="t('套餐', 'Plan')"
        /><el-table-column :label="t('鼓励值', 'Encouragement')"
          ><template #default="{ row }">{{
            decimalUnits(row.price_units)
          }}</template></el-table-column
        ><el-table-column
          prop="quota"
          :label="t('次数', 'Calls')"
        /><el-table-column
          prop="days"
          :label="t('天数', 'Days')"
        /><el-table-column :label="t('操作', 'Actions')"
          ><template #default="{ row }"
            ><el-button
              link
              type="primary"
              :disabled="busy"
              @click="buy(row)"
              >{{ t("购买 / 续费", "Buy / renew") }}</el-button
            ></template
          ></el-table-column
        ></el-table
      ><el-pagination
        v-model:current-page="planPage"
        :page-size="10"
        :total="planData.total"
        layout="total,prev,pager,next"
        @current-change="listPlans"
    /></el-dialog>
    <el-dialog
      v-model="billingDialog"
      :title="t('计费设置', 'Billing settings')"
      width="min(650px,95vw)"
      ><template v-if="billingApi && preferences[billingApi.id]"
        ><el-checkbox v-model="preferences[billingApi.id]!.payg">{{
          t(
            "无有效套餐时允许按次扣鼓励值",
            "Allow pay-as-you-go without an active plan",
          )
        }}</el-checkbox
        ><el-checkbox v-model="preferences[billingApi.id]!.overage">{{
          t(
            "周期额度用尽后允许扣鼓励值",
            "Allow encouragement after the quota is exhausted",
          )
        }}</el-checkbox>
        <p>
          <el-button @click="saveBilling">{{
            t("保存计费选择", "Save billing preferences")
          }}</el-button>
        </p></template
      ></el-dialog
    >
    <el-dialog
      v-model="debugDialog"
      :title="t('在线调试', 'Online debugger')"
      width="min(900px,96vw)"
      :close-on-click-modal="false"
      ><el-alert
        :title="
          t(
            '成功调用正常扣额度或鼓励值，相同请求标识重试不重复计费。',
            'Successful calls use normal billing; the same request ID is not billed twice.',
          )
        "
        type="info"
        :closable="false"
      />
      <el-form class="debug-form" label-position="right" label-width="130px"
        ><el-form-item :label="t('本人Key', 'Your key')"
          ><PagedPicker
            v-model="debug.keyId"
            :title="t('调试Key', 'Debug key')"
            endpoint="/api/member/api/records/keys"
            :params="{
              state: 'ENABLED',
              validOnly: true,
              apiId: planDebugApi?.id,
            }"
            detail-key="prefix"
        /></el-form-item>
        <el-form-item label="API"
          ><span>{{ planDebugApi?.name }}</span></el-form-item
        >
        <el-form-item :label="t('请求标识', 'Request ID')"
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
            :aria-label="t('调试JSON输入', 'Debug JSON input')" /></el-form-item
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
    </el-dialog>
    <el-dialog
      v-model="callDialog"
      :title="t('记录详情', 'Record details')"
      width="min(700px,95vw)"
      ><el-descriptions v-if="selectedCall" :column="1" border
        ><el-descriptions-item :label="t('编号', 'ID')">{{
          selectedCall.id
        }}</el-descriptions-item
        ><el-descriptions-item :label="t('接口 / 套餐', 'API / plan')">{{
          selectedCall.api_name || selectedCall.plan_name
        }}</el-descriptions-item
        ><el-descriptions-item :label="t('状态', 'Status')">{{
          selectedCall.state
        }}</el-descriptions-item
        ><el-descriptions-item :label="t('创建时间', 'Created at')">{{
          businessTime(selectedCall.created_at)
        }}</el-descriptions-item
        ><el-descriptions-item
          v-if="selectedCall.finished_at"
          :label="t('完成时间', 'Finished at')"
          >{{ businessTime(selectedCall.finished_at) }}</el-descriptions-item
        ><el-descriptions-item
          v-if="selectedCall.error_code"
          :label="t('错误编号', 'Error code')"
          >{{ selectedCall.error_code }}</el-descriptions-item
        ></el-descriptions
      ></el-dialog
    >
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
          ><PagedPicker
            v-model="keyForm.apiIds"
            :title="t('Key API权限', 'Key API permissions')"
            endpoint="/api/public/api-records/apis"
            :labels="keyApiLabels" /></el-form-item
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
            :model-value="
              keyForm.expiresAt ? businessTime(keyForm.expiresAt) : null
            "
            @update:model-value="
              (value) =>
                (keyForm.expiresAt = value
                  ? Date.parse(String(value).replace(' ', 'T') + '+08:00')
                  : null)
            "
            type="datetime"
            value-format="YYYY-MM-DD HH:mm:ss"
            clearable
            format="YYYY-MM-DD HH:mm:ss" /></el-form-item
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
