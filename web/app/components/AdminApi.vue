<script setup lang="ts">
/** API配置、密钥和计量各按菜单权限加载；分页及排序在服务端执行，不将上游密钥放入编辑回显。 */
import { ElMessageBox } from "element-plus";
import { businessTime, decimalUnits } from "~/utils/commerce";
const props = defineProps<{
    view: string;
    editor?: boolean;
    tabId?: string;
    apiId?: string | null;
  }>(),
  { request, message } = useApi(),
  {
    dirty,
    registerSave,
    openApiEditor,
    updateApiEditor,
    close: closeTab,
    openEntityEditor,
    updateEntityEditor,
  } = useManagementTabs();
const dirtyId = computed(() => props.tabId || props.view);
const site = useState<Record<string, string>>("site-settings", () => ({}));
const apiChanged = useState("api-management-changed", () => 0);
watch(apiChanged, () => {
  if (!props.editor) load();
});
const labels: Record<string, string> = {
  apis: "API 管理",
  plans: "套餐与订阅",
  subscriptions: "订阅周期",
  keys: "Key 管理",
  usage: "调用统计",
  "api-refunds": "API套餐退款",
};
const q = ref(""),
  state = ref(""),
  page = ref(1),
  size = ref(20),
  range = ref<string[]>([]),
  data = ref<any>({ items: [], total: 0 }),
  error = ref(""),
  notice = ref(""),
  busy = ref(false),
  editing = ref(false),
  baseline = ref(""),
  apiOptions = ref<any[]>([]),
  section = ref("plans");
const kind = computed(() =>
  props.view === "plans" ? section.value : props.view,
);
const listOrdering = useListOrder(load, page);
const tableDefaultSort = listOrdering.defaultSort;
const tableSortChanged = listOrdering.change;
const initial = () => ({
  id: "",
  slug: "",
  name: "",
  nameEn: "",
  description: "",
  descriptionEn: "",
  adapter: "HTTPS_JSON",
  requestMethod: "POST",
  requestHeaders: "",
  headersSet: false,
  clearHeaders: false,
  endpoint: "",
  secret: "",
  secretSet: false,
  clearSecret: false,
  successPointer: "/success",
  successValue: "true",
  pricePoints: 0,
  apiId: "",
  quota: 1000,
  days: 30,
  enabled: true,
  revision: 0,
});
const form = reactive(initial());
watch(
  () => JSON.stringify(form),
  (v) => (dirty.value[dirtyId.value] = editing.value && v !== baseline.value),
);
async function load() {
  try {
    data.value = await request("/api/admin/api-list/" + kind.value, {
      query: {
        q: q.value,
        state: state.value,
        page: page.value,
        pageSize: size.value,
        sort: listOrdering.sort.value,
        direction: listOrdering.direction.value,
        from: range.value?.[0] || "",
        until: range.value?.[1] || "",
      },
    });
    page.value = data.value.page;
  } catch (e) {
    error.value = message(e);
  }
}
watch(kind, () => {
  page.value = 1;
  state.value = "";
  q.value = "";
  listOrdering.sort.value = "created_at";
  listOrdering.direction.value = "descending";
  load();
});
function edit(row?: any) {
  if (props.view === "plans" && !props.editor) {
    openEntityEditor("plans", row ? { id: row.id, name: row.name } : undefined);
    return;
  }
  if (props.view === "apis" && !props.editor) {
    openApiEditor(row);
    return;
  }
  if (dirty.value[dirtyId.value] && !window.confirm("放弃未保存的编辑？"))
    return;
  Object.assign(
    form,
    initial(),
    row
      ? {
          ...row,
          nameEn: row.name_en || "",
          descriptionEn: row.description_en || "",
          secretSet: row.secret_set,
          requestMethod: row.request_method || "POST",
          headersSet: row.headers_set,
          successPointer: row.success_pointer || "/success",
          successValue: row.success_value || "true",
          pricePoints: Number(row.price_units) / 100,
          apiId: row.api_id || "",
        }
      : {},
  );
  form.requestHeaders = "";
  form.clearHeaders = false;
  form.secret = "";
  form.clearSecret = false;
  editing.value = true;
  baseline.value = JSON.stringify(form);
  dirty.value[dirtyId.value] = false;
}
async function save() {
  if (!editing.value) return true;
  busy.value = true;
  error.value = "";
  try {
    const endpoint =
      props.view === "apis" ? "/api/admin/apis" : "/api/admin/api-plans";
    const saved = await request<{ id: string }>(
      endpoint + (form.id ? "/" + form.id : ""),
      {
        method: form.id ? "PUT" : "POST",
        body: form,
      },
    );
    editing.value = false;
    form.requestHeaders = "";
    form.clearHeaders = false;
    form.secret = "";
    dirty.value[dirtyId.value] = false;
    notice.value = "已保存，已有购买和调用条款不被覆盖";
    if (props.editor) {
      const rows = await request<any[]>(
        props.view === "apis" ? "/api/admin/apis" : "/api/admin/api-plans",
      );
      edit(rows.find((r) => r.id === saved.id));
      if (props.view === "apis")
        await updateApiEditor(dirtyId.value, saved.id, form.name);
      else await updateEntityEditor(dirtyId.value, saved.id, form.name);
      apiChanged.value++;
    } else await load();
    apiOptions.value = (await request<any>("/api/public/apis")).apis;
    return true;
  } catch (e) {
    error.value = message(e);
    return false;
  } finally {
    busy.value = false;
  }
}
function close() {
  if (props.editor) {
    closeTab(dirtyId.value);
    return;
  }
  if (!dirty.value[dirtyId.value] || window.confirm("放弃未保存的编辑？")) {
    editing.value = false;
    form.requestHeaders = "";
    form.clearHeaders = false;
    form.secret = "";
    dirty.value[dirtyId.value] = false;
  }
}
async function revoke(row: any) {
  try {
    await ElMessageBox.confirm(
      "停用后阻止新调用，已有调用记录和在途结算保留。",
      "停用Key",
    );
    await request("/api/admin/api-keys/" + row.id + "/revoke", {
      method: "POST",
      body: { revision: row.revision },
    });
    await load();
  } catch (e) {
    if (e !== "cancel" && e !== "close") error.value = message(e);
  }
}
async function review(row: any, approve: boolean) {
  try {
    const r = await ElMessageBox.prompt(
      approve
        ? "整单退鼓励值并撤销该周期，已发生成功调用不能批准。"
        : "填写驳回理由，驳回后该周期恢复调用。",
      "API套餐退款审核",
      { inputValidator: (v: string) => !!v?.trim() || "请填写审核理由" },
    );
    await request("/api/admin/api-subscriptions/" + row.id + "/refund", {
      method: "POST",
      body: { approve, reason: r.value, revision: row.revision },
    });
    await load();
    notice.value = "退款审核完成";
  } catch (e) {
    if (e !== "cancel" && e !== "close") error.value = message(e);
  }
}
let unregister: (() => void) | undefined;
onMounted(async () => {
  unregister = registerSave(dirtyId.value, save);
  if (props.editor) {
    try {
      if (props.apiId) {
        const rows = await request<any[]>(
          props.view === "apis" ? "/api/admin/apis" : "/api/admin/api-plans",
        );
        const row = rows.find((r) => r.id === props.apiId);
        if (!row) throw new Error("API不存在或已删除，请返回列表刷新");
        edit(row);
      } else edit();
    } catch (e) {
      error.value = message(e);
    }
  } else await load();
  apiOptions.value = (await request<any>("/api/public/apis")).apis;
});
onBeforeUnmount(() => unregister?.());
</script>
<template>
  <section>
    <div v-if="!editor || view === 'apis'" class="management-panel-heading">
      <div>
        <h1>
          {{
            editor
              ? (form.id ? "编辑" : "新增") + (view === "apis" ? "API" : "套餐")
              : labels[view]
          }}
        </h1>
        <p>
          {{
            view === "usage"
              ? "成功调用才结算，失败及超时不扣费；调试走相同计费路径。"
              : "固定周期与价格保存购买快照，密钥不回显，所有变更服务端校验。"
          }}
        </p>
      </div>
      <el-button
        v-if="
          !editor &&
          ['apis', 'plans'].includes(view) &&
          kind !== 'subscriptions'
        "
        type="primary"
        @click="edit()"
        >{{ view === "apis" ? "新增API" : "新增套餐" }}</el-button
      >
    </div>
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
    <el-radio-group
      v-if="view === 'plans' && !editor"
      v-model="section"
      style="margin-bottom: 18px"
      ><el-radio-button value="plans">套餐配置</el-radio-button
      ><el-radio-button value="subscriptions"
        >订阅周期</el-radio-button
      ></el-radio-group
    >
    <el-card v-if="editing" shadow="never" style="margin-bottom: 20px"
      ><template #header
        ><div class="admin-table-actions">
          <strong
            >{{ form.id ? "编辑" : "新增"
            }}{{ view === "apis" ? "API" : "套餐" }}</strong
          ><el-button type="primary" :loading="busy" @click="save"
            >保存</el-button
          ><el-button @click="close">{{
            editor ? "关闭页签" : "收起编辑"
          }}</el-button>
        </div></template
      >
      <el-form
        label-position="right"
        label-width="130px"
        class="admin-form-grid"
      >
        <el-form-item label="名称"
          ><el-input
            v-model="form.name"
            aria-label="API配置名称"
            maxlength="120" /></el-form-item
        ><el-form-item label="成功扣分"
          ><el-input-number
            v-model="form.pricePoints"
            :min="0"
            :max="1000000"
            :precision="2"
            aria-label="API鼓励值价格"
        /></el-form-item>
        <template v-if="view === 'apis'"
          ><el-form-item label="接口标识（固定）"
            ><el-input
              v-model="form.slug"
              aria-label="API调用地址"
              :disabled="!!form.id"
              placeholder="小写英文、数字、短横线" /></el-form-item
          ><el-form-item label="英文名称"
            ><el-input v-model="form.nameEn" /></el-form-item
          ><el-form-item label="中文说明"
            ><el-input
              v-model="form.description"
              aria-label="API中文说明"
              type="textarea"
              maxlength="1000"
              :rows="3" /></el-form-item
          ><el-form-item label="英文说明"
            ><el-input
              v-model="form.descriptionEn"
              type="textarea"
              maxlength="1000"
              :rows="3" /></el-form-item
          ><el-form-item label="平台调用地址"
            ><el-input
              :model-value="
                'https://' +
                (site['site.domain'] || 'www.aiwyw.com') +
                '/api/v1/invoke/' +
                (form.slug || '{接口标识}')
              "
              readonly
              aria-label="平台调用地址"
            />
            <p class="admin-muted">
              用户使用平台Key向此地址POST
              JSON。平台完成鉴权和计费后，按下方配置转发；上游凭据不提供给用户。
            </p></el-form-item
          >
          <el-form-item label="服务类型"
            ><el-select v-model="form.adapter" aria-label="API适配器"
              ><el-option
                label="文本统计（真实本地计算）"
                value="TEXT_STATS" /><el-option
                label="SHA256（真实本地计算）"
                value="SHA256" /><el-option
                label="代理真实HTTPS JSON服务"
                value="HTTPS_JSON" /></el-select
          ></el-form-item>
          <template v-if="form.adapter === 'HTTPS_JSON'"
            ><el-form-item label="真实服务地址" required
              ><el-input
                v-model="form.endpoint"
                aria-label="真实服务地址"
                maxlength="1000"
                placeholder="https://api.example.com/path" /></el-form-item
            ><el-form-item label="上游请求方式" required
              ><el-select
                v-model="form.requestMethod"
                aria-label="真实服务请求方式"
                ><el-option value="POST" label="POST · JSON请求体" /><el-option
                  value="GET"
                  label="GET · 标量查询参数" /></el-select
            ></el-form-item>
            <el-form-item label="真实服务请求头"
              ><el-input
                v-model="form.requestHeaders"
                type="textarea"
                :rows="3"
                maxlength="16000"
                aria-label="真实服务请求头"
                :placeholder="
                  form.headersSet
                    ? '已加密设置；留空保留，填写JSON替换'
                    : '{&quot;X-API-Key&quot;:&quot;供应商密钥&quot;}'
                "
              />
              <p class="admin-muted">
                字符串JSON对象，加密保存且不回显。固定JSON传输头由平台生成，Bearer凭据在下方填写。
              </p>
              <el-checkbox v-model="form.clearHeaders"
                >清除已有请求头</el-checkbox
              ></el-form-item
            >
            <el-form-item label="成功字段Pointer" required
              ><el-input
                v-model="form.successPointer"
                placeholder="/success 或 /code" /></el-form-item
            ><el-form-item label="成功值（JSON）" required
              ><el-input
                v-model="form.successValue"
                placeholder='true 或 0 或 "ok"' /></el-form-item
            ><el-form-item label="上游Bearer凭据"
              ><el-input
                v-model="form.secret"
                type="password"
                show-password
                autocomplete="new-password"
                :placeholder="
                  form.secretSet ? '已设置，留空保留' : '可选，仅加密保存'
                " /></el-form-item
            ><el-form-item label="清除已有凭据"
              ><el-checkbox v-model="form.clearSecret"
                >明确清除</el-checkbox
              ></el-form-item
            ></template
          ></template
        >
        <template v-else
          ><el-form-item label="所属API"
            ><el-select
              v-model="form.apiId"
              aria-label="套餐所属API"
              :disabled="!!form.id"
              ><el-option
                v-for="a in apiOptions"
                :key="a.id"
                :value="a.id"
                :label="a.name" /></el-select></el-form-item
          ><el-form-item label="成功调用额度"
            ><el-input-number
              v-model="form.quota"
              :min="1"
              :max="1000000000" /></el-form-item
          ><el-form-item label="固定天数"
            ><el-input-number
              v-model="form.days"
              :min="1"
              :max="3650" /></el-form-item
        ></template>
        <el-form-item label="启用"
          ><el-switch v-model="form.enabled" aria-label="API配置启用"
        /></el-form-item>
      </el-form>
    </el-card>
    <el-card v-if="!editor" shadow="never"
      ><el-form
        label-position="right"
        label-width="110px"
        class="admin-filter-grid"
        @submit.prevent="
          page = 1;
          load();
        "
        ><el-form-item label="名称查询"
          ><el-input
            v-model="q"
            clearable
            maxlength="100"
            aria-label="API列表名称查询" /></el-form-item
        ><el-form-item label="创建日期"
          ><el-date-picker
            v-model="range"
            type="daterange"
            value-format="YYYY-MM-DD"
            start-placeholder="开始日期"
            end-placeholder="结束日期"
            format="YYYY-MM-DD HH:mm:ss" /></el-form-item
        ><el-form-item label="状态"
          ><el-select v-model="state" clearable placeholder="全部"
            ><el-option
              v-for="s in ['apis', 'plans', 'keys'].includes(kind)
                ? ['true', 'false']
                : kind === 'usage'
                  ? ['RUNNING', 'SUCCESS', 'FAILED', 'UNKNOWN']
                  : kind === 'api-refunds'
                    ? ['PENDING', 'APPROVED', 'REJECTED']
                    : ['ACTIVE', 'REFUNDED']"
              :key="s"
              :value="s"
              :label="
                s === 'true' ? '启用' : s === 'false' ? '停用' : s
              " /></el-select
        ></el-form-item>
        <div class="full">
          <el-button
            type="primary"
            @click="
              page = 1;
              load();
            "
            >查询</el-button
          >
        </div></el-form
      >
      <el-table
        :data="data.items"
        :default-sort="tableDefaultSort"
        @sort-change="tableSortChanged"
      >
        <ObjectColumns server-sort /><el-table-column
          prop="name"
          label="名称"
          min-width="170"
          sortable="custom"
          :sort-orders="['descending', 'ascending']"
        />
        <el-table-column
          v-if="kind === 'apis'"
          prop="slug"
          label="调用路径"
          sortable="custom"
          :sort-orders="['descending', 'ascending']"
        /><el-table-column
          v-if="kind === 'apis'"
          prop="adapter"
          label="适配器"
          sortable="custom"
          :sort-orders="['descending', 'ascending']"
        />
        <el-table-column
          v-if="kind !== 'keys'"
          prop="price_units"
          label="鼓励值"
          sortable="custom"
          :sort-orders="['descending', 'ascending']"
          ><template #default="{ row }">{{
            decimalUnits(row.price_units)
          }}</template></el-table-column
        >
        <el-table-column
          v-if="
            ['subscriptions', 'keys', 'usage', 'api-refunds'].includes(kind)
          "
          prop="nickname"
          label="用户"
          sortable="custom"
          :sort-orders="['descending', 'ascending']"
        />
        <el-table-column
          v-if="['keys', 'usage'].includes(kind)"
          prop="prefix"
          label="Key前缀"
        />
        <template v-if="kind === 'plans'"
          ><el-table-column
            prop="api_name"
            label="API"
            sortable="custom"
            :sort-orders="['descending', 'ascending']" /><el-table-column
            prop="quota"
            label="额度"
            sortable="custom"
            :sort-orders="['descending', 'ascending']" /><el-table-column
            prop="days"
            label="天数"
            sortable="custom"
            :sort-orders="['descending', 'ascending']"
        /></template>
        <template v-if="['subscriptions', 'api-refunds'].includes(kind)"
          ><el-table-column
            prop="used_count"
            label="已用额度"
            sortable="custom"
            :sort-orders="['descending', 'ascending']" /><el-table-column
            prop="end_at"
            label="到期时间"
            min-width="170"
            sortable="custom"
            :sort-orders="['descending', 'ascending']"
            ><template #default="{ row }">{{
              businessTime(row.end_at)
            }}</template></el-table-column
          ><el-table-column
            prop="refund_state"
            label="退款状态"
            sortable="custom"
            :sort-orders="['descending', 'ascending']" /><el-table-column
            v-if="kind === 'api-refunds'"
            prop="refund_reason"
            label="申请理由"
            min-width="180"
        /></template>
        <el-table-column
          v-if="kind === 'keys'"
          prop="minute_limit"
          label="每分钟上限"
          sortable="custom"
          :sort-orders="['descending', 'ascending']"
        /><el-table-column
          v-if="kind === 'usage'"
          prop="billing"
          label="计费方式"
          sortable="custom"
          :sort-orders="['descending', 'ascending']"
        /><el-table-column
          v-if="kind === 'usage'"
          prop="state"
          label="状态"
          sortable="custom"
          :sort-orders="['descending', 'ascending']"
        />
        <el-table-column
          v-if="['apis', 'plans', 'keys'].includes(kind)"
          prop="enabled"
          label="状态"
          sortable="custom"
          :sort-orders="['descending', 'ascending']"
          ><template #default="{ row }">{{
            row.enabled ? "启用" : "停用"
          }}</template></el-table-column
        >
        <el-table-column
          align="center"
          header-align="center"
          v-if="['apis', 'plans', 'keys', 'api-refunds'].includes(kind)"
          label="操作"
          min-width="140"
          ><template #default="{ row }"
            ><el-button
              v-if="['apis', 'plans'].includes(kind)"
              link
              type="primary"
              @click="edit(row)"
              >编辑</el-button
            ><el-button
              v-if="kind === 'keys' && row.enabled"
              link
              type="danger"
              @click="revoke(row)"
              >停用Key</el-button
            ><template
              v-if="kind === 'api-refunds' && row.refund_state === 'PENDING'"
              ><el-button link type="primary" @click="review(row, true)"
                >批准</el-button
              ><el-button link type="danger" @click="review(row, false)"
                >驳回</el-button
              ></template
            ></template
          ></el-table-column
        > </el-table
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
