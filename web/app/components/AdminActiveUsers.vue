<script setup lang="ts">
/** URL固定本次统计窗口；分页/排序仍在服务器执行，不加载全量行为或公开邮箱。 */
import { businessTime } from "~/utils/commerce";
const props = defineProps<{ from: string; until: string }>();
const { request, message } = useApi();
const data = ref<any>({ items: [], total: 0 }),
  page = ref(1),
  size = ref(20),
  error = ref("");
const order = useListOrder(load, page);
async function load() {
  try {
    data.value = await request("/api/admin/statistics/active-users", {
      query: {
        from: props.from,
        until: props.until,
        page: page.value,
        pageSize: size.value,
        sort: order.sort.value,
        direction: order.direction.value,
      },
    });
    page.value = data.value.page;
  } catch (e) {
    error.value = message(e);
  }
}
onMounted(load);
</script>
<template>
  <section>
    <div class="management-panel-heading">
      <div>
        <h1>活跃用户</h1>
        <p>
          {{ from }} — {{ until }} · 北京时间 ·
          与报表活跃账户采用同一统计窗口和去重口径
        </p>
      </div>
    </div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-card shadow="never"
      ><el-table
        :data="data.items"
        :default-sort="order.defaultSort"
        @sort-change="order.change"
      >
        <ObjectColumns server-sort />
        <el-table-column
          prop="nickname"
          label="昵称"
          min-width="160"
          sortable="custom"
          :sort-orders="['descending', 'ascending']"
        />
        <el-table-column
          prop="pages"
          label="浏览数"
          sortable="custom"
          :sort-orders="['descending', 'ascending']"
        />
        <el-table-column
          prop="active_days"
          label="活跃天数"
          sortable="custom"
          :sort-orders="['descending', 'ascending']"
        />
        <el-table-column
          prop="first_seen_at"
          label="首次活跃"
          min-width="175"
          sortable="custom"
          :sort-orders="['descending', 'ascending']"
          ><template #default="{ row }">{{
            businessTime(row.first_seen_at)
          }}</template></el-table-column
        >
        <el-table-column
          prop="last_seen_at"
          label="最近活跃"
          min-width="175"
          sortable="custom"
          :sort-orders="['descending', 'ascending']"
          ><template #default="{ row }">{{
            businessTime(row.last_seen_at)
          }}</template></el-table-column
        > </el-table
      ><el-pagination
        v-model:current-page="page"
        v-model:page-size="size"
        :total="data.total"
        layout="total,sizes,prev,pager,next"
        @current-change="load"
        @size-change="
          page = 1;
          load();
        "
    /></el-card>
  </section>
</template>
