<script setup lang="ts">
/** 报表请求和CSV共享日期/账本参数；金额展示实际批次充值成本分摊，明确不等于财务确认收入。 */
import { adminDateShortcuts, decimalUnits } from "~/utils/commerce";
const { request, message } = useApi(),
  data = ref<any>(null),
  range = ref<string[]>([]),
  mode = ref("REAL"),
  busy = ref(false),
  error = ref("");
const { openActiveUsers } = useManagementTabs();
async function load() {
  busy.value = true;
  error.value = "";
  try {
    data.value = await request("/api/admin/statistics", {
      query: {
        from: range.value?.[0] || "",
        until: range.value?.[1] || "",
        mode: mode.value,
      },
    });
  } catch (e) {
    error.value = message(e);
  } finally {
    busy.value = false;
  }
}
const exportUrl = computed(
  () =>
    "/api/admin/statistics/export?" +
    new URLSearchParams({
      from: range.value?.[0] || "",
      until: range.value?.[1] || "",
      mode: mode.value,
    }),
);
const net = computed(
    () =>
      data.value?.income.reduce(
        (n: bigint, r: any) => n + BigInt(String(r.net_cost_fen)),
        0n,
      ) || 0n,
  ),
  rate = (n: any, total: any) =>
    n === null
      ? "尚未到观察日"
      : Number(total)
        ? ((Number(n) / Number(total)) * 100).toFixed(1) + "%"
        : "—";
onMounted(load);
</script>
<template>
  <section>
    <div class="management-panel-heading">
      <div>
        <h1>运营统计</h1>
        <p>
          日期采用北京时间。行为按访客/页面/关键词/自然日去重；默认排除后台账户。收入来自服务端订单与结算。
        </p>
      </div>
    </div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-card shadow="never"
      ><el-form
        label-position="right"
        label-width="110px"
        class="admin-filter-grid"
        ><el-form-item label="统计日期"
          ><el-date-picker
            v-model="range"
            type="daterange"
            value-format="YYYY-MM-DD"
            :shortcuts="adminDateShortcuts()"
            start-placeholder="开始日期"
            end-placeholder="结束日期" /></el-form-item
        ><el-form-item label="账务模式"
          ><el-select v-model="mode" aria-label="统计账本模式"
            ><el-option value="REAL" label="真实账本（排除模拟）" /><el-option
              value="SIMULATED"
              label="模拟账本（独立分析）" /></el-select
        ></el-form-item>
        <div class="full">
          <el-button type="primary" :loading="busy" @click="load"
            >查询统计</el-button
          ><a class="button secondary" :href="exportUrl">导出收入CSV</a>
        </div></el-form
      ></el-card
    >
    <template v-if="data"
      ><p class="report-scope">
        {{ data.from }} — {{ data.until }} · 北京时间 ·
        {{
          data.mode === "SIMULATED"
            ? "当前显示模拟账本，不代表真实收款"
            : "当前显示真实账本，排除全部模拟交易"
        }}。充值与商品消费分开展示，成本分摊不作为税务财务确认口径。
      </p>
      <div class="report-metrics">
        <article>
          <span>去重浏览数</span><strong>{{ data.traffic.events }}</strong>
        </article>
        <article>
          <span>独立访客</span><strong>{{ data.traffic.visitors }}</strong>
        </article>
        <article>
          <span>活跃账户</span><strong>{{ data.traffic.active_users }}</strong>
          <el-button
            link
            type="primary"
            @click="openActiveUsers(data.from, data.until)"
            >查看活跃用户</el-button
          >
        </article>
        <article>
          <span>充值核销金额（元）</span
          ><strong>{{ decimalUnits(data.cash.recharge_fen) }}</strong>
        </article>
        <article>
          <span>消费净成本分摊（元）</span
          ><strong>{{ decimalUnits(net) }}</strong>
        </article>
        <article>
          <span>咨询 / 待领取</span
          ><strong
            >{{ data.support.conversations }} /
            {{ data.support.waiting }}</strong
          >
        </article>
      </div>
      <el-tabs>
        <el-tab-pane label="访问与来源"
          ><div class="report-grid">
            <el-card shadow="never"
              ><h2>每日活跃</h2>
              <el-table
                :data="data.days"
                :default-sort="{ prop: 'day_key', order: 'descending' }"
                ><el-table-column
                  prop="day_key"
                  label="日期"
                  sortable
                  :sort-orders="['descending', 'ascending']" /><el-table-column
                  prop="views"
                  label="去重浏览数"
                  sortable
                  :sort-orders="['descending', 'ascending']" /><el-table-column
                  prop="visitors"
                  label="访客"
                  sortable
                  :sort-orders="['descending', 'ascending']" /><el-table-column
                  prop="active_users"
                  label="账户"
                  sortable
                  :sort-orders="[
                    'descending',
                    'ascending',
                  ]" /></el-table></el-card
            ><el-card shadow="never"
              ><h2>首次来源（最多50项）</h2>
              <el-table :data="data.sources"
                ><el-table-column
                  prop="source_host"
                  label="来源域名" /><el-table-column
                  prop="visitors"
                  label="访客"
                  sortable
                  :sort-orders="['descending', 'ascending']" /></el-table
            ></el-card></div
        ></el-tab-pane>
        <el-tab-pane label="搜索关键词"
          ><el-table :data="data.keywords"
            ><el-table-column prop="keyword" label="关键词" /><el-table-column
              prop="searches"
              label="去重搜索次数"
              sortable
              :sort-orders="['descending', 'ascending']" /><el-table-column
              prop="visitors"
              label="访客"
              sortable
              :sort-orders="['descending', 'ascending']" /></el-table
        ></el-tab-pane>
        <el-tab-pane label="账户留存"
          ><p>
            以注册日为队列，统计在第1、7、30个北京时间自然日登录访问的账户。最近31个注册日；未到观察日留空，不记为0。
          </p>
          <el-table
            :data="data.retention"
            :default-sort="{ prop: 'day', order: 'descending' }"
            ><el-table-column
              prop="day"
              label="注册日"
              sortable
              :sort-orders="['descending', 'ascending']"
            /><el-table-column
              prop="registered"
              label="注册账户"
            /><el-table-column v-for="d in [1, 7, 30]" :key="d" :label="'D' + d"
              ><template #default="{ row }"
                >{{ row["d" + d] === null ? "尚未到观察日" : row["d" + d] }} ·
                {{ rate(row["d" + d], row.registered) }}</template
              ></el-table-column
            ></el-table
          ></el-tab-pane
        >
        <el-tab-pane label="商品与API收入"
          ><el-table :data="data.income"
            ><el-table-column prop="category" label="类别" /><el-table-column
              prop="title"
              label="商品 / 套餐"
              min-width="220" /><el-table-column
              prop="net_units"
              label="净消费积分"
              sortable
              :sort-orders="['descending', 'ascending']"
              ><template #default="{ row }">{{
                decimalUnits(row.net_units)
              }}</template></el-table-column
            ><el-table-column
              prop="net_cost_fen"
              label="实际成本分摊（元）"
              sortable
              :sort-orders="['descending', 'ascending']"
              ><template #default="{ row }">{{
                decimalUnits(row.net_cost_fen)
              }}</template></el-table-column
            ><el-table-column
              prop="charges"
              label="购买 / 成功调用" /><el-table-column
              prop="refunds"
              label="退款" /></el-table
        ></el-tab-pane>
        <el-tab-pane label="API调用"
          ><p>
            调用量为实际处理记录，计费账本当前为模拟；耗时含上游处理，不等于平台自身耗时。
          </p>
          <el-table :data="data.api"
            ><el-table-column prop="api_name" label="API" /><el-table-column
              prop="state"
              label="状态"
            /><el-table-column
              prop="calls"
              label="次数"
              sortable
              :sort-orders="['descending', 'ascending']"
            /><el-table-column prop="mean_ms" label="平均耗时毫秒"
              ><template #default="{ row }">{{
                row.mean_ms === null ? "—" : Number(row.mean_ms).toFixed(0)
              }}</template></el-table-column
            ></el-table
          ></el-tab-pane
        >
        <el-tab-pane label="客服与反馈"
          ><p>
            新会话 {{ data.support.conversations }}，进行中
            {{ data.support.open_count }}，待领取
            {{ data.support.waiting }}；已回复会话平均首次响应
            {{
              data.support.mean_first_reply_ms === null
                ? "—"
                : Math.round(Number(data.support.mean_first_reply_ms) / 1000) +
                  " 秒"
            }}。
          </p>
          <el-table :data="data.feedback"
            ><el-table-column prop="kind" label="公开 / 私密" /><el-table-column
              prop="state"
              label="状态" /><el-table-column
              prop="records"
              label="数量" /></el-table
        ></el-tab-pane> </el-tabs
    ></template>
  </section>
</template>
<style scoped>
.report-scope {
  font-size: 13px;
  color: #7f879c;
  line-height: 1.9;
  margin: 20px 0;
}
.report-metrics {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 16px;
  margin: 20px 0;
}
.report-metrics article {
  background: #fff;
  border: 1px solid #e9e5f3;
  border-radius: 12px;
  padding: 22px;
}
.report-metrics span {
  display: block;
  color: #858da3;
  font-size: 13px;
}
.report-metrics strong {
  display: block;
  font-size: 26px;
  margin-top: 12px;
  color: #555073;
}
.report-grid {
  display: grid;
  grid-template-columns: 1.3fr 1fr;
  gap: 20px;
}
.report-grid h2 {
  font-size: 18px;
  margin-bottom: 15px;
}
@media (max-width: 1000px) {
  .report-grid {
    grid-template-columns: 1fr;
  }
  .report-metrics {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
