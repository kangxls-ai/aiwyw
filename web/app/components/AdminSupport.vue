<script setup lang="ts">
/** 只在当前在线会话页签可见时发送心跳；切换模块或离开浏览器后不会假装客服在线。 */
import { ElMessageBox } from "element-plus";
import { businessTime, adminDateShortcuts } from "~/utils/commerce";
const props = defineProps<{
    view: string;
    editor?: boolean;
    tabId?: string;
    initialId?: string | null;
  }>(),
  { request, message } = useApi(),
  { activeId, dirty, openEntityEditor } = useManagementTabs();
const active = computed(() => activeId.value === (props.tabId || props.view)),
  data = ref<any>({ items: [], total: 0, agents: [] }),
  q = ref(""),
  state = ref(""),
  page = ref(1),
  size = ref(20),
  range = ref<string[]>([]),
  error = ref(""),
  notice = ref(""),
  selected = ref(props.initialId || ""),
  target = ref(""),
  chat = ref<any>(null),
  loading = ref(false),
  order = useListOrder(load, page);
const title = computed(
  () =>
    ({ conversations: "在线会话", messages: "公开留言", feedback: "私密反馈" })[
      props.view
    ],
);
async function load(quiet = false) {
  if (loading.value) return;
  loading.value = true;
  try {
    data.value = await request(
      props.view === "conversations"
        ? "/api/admin/support/conversations"
        : "/api/admin/support/feedback/page",
      {
        query: {
          scope: props.view,
          q: q.value,
          state: state.value,
          page: page.value,
          pageSize: size.value,
          sort: order.sort.value,
          direction: order.direction.value,
          from: range.value?.[0] || "",
          until: range.value?.[1] || "",
        },
      },
    );
    page.value = data.value.page;
  } catch (e) {
    if (!quiet) error.value = message(e);
  } finally {
    loading.value = false;
  }
}
async function select(row: any) {
  await openEntityEditor("conversations", {
    id: row.id,
    name: row.subject || "会话回复",
  });
}
async function claim(row: any) {
  try {
    await request("/api/admin/support/conversations/" + row.id + "/assign", {
      method: "POST",
      body: { userId: "", revision: row.revision },
    });
    await select(row);
    await load();
    await chat.value?.refresh();
    notice.value = "会话已领取";
  } catch (e) {
    error.value = message(e);
    await load();
  }
}
async function transfer() {
  try {
    if (!target.value) throw new Error("target");
    const c = chat.value?.conversation;
    if (!c) return;
    await request(
      "/api/admin/support/conversations/" + selected.value + "/assign",
      { method: "POST", body: { userId: target.value, revision: c.revision } },
    );
    await load();
    await chat.value?.refresh();
    notice.value = "转派已完成，历史记录保留";
  } catch (e) {
    error.value =
      (e as Error).message === "target" ? "请选择接收客服" : message(e);
  }
}
async function review(row: any, approve: boolean) {
  try {
    const r = await ElMessageBox.prompt(
      "填写回复或处理说明，私密反馈不会公开。",
      approve ? "审核并回复" : "驳回留言",
      {
        inputType: "textarea",
        inputValidator: (v: string) => !!v?.trim() || "请填写说明",
      },
    );
    await request("/api/admin/support/feedback/" + row.id + "/review", {
      method: "POST",
      body: {
        state: approve
          ? props.view === "messages"
            ? "APPROVED"
            : "RESOLVED"
          : "REJECTED",
        reply: r.value,
        revision: row.revision,
      },
    });
    notice.value = "处理已保存";
    await load();
  } catch (e) {
    if (e !== "cancel" && e !== "close") error.value = message(e);
  }
}
watch(
  () => chat.value?.draft,
  (v) => {
    dirty.value[props.tabId || props.view] = !!v;
  },
);
let timer: ReturnType<typeof setInterval> | undefined;
let nextRefresh = 0;
onMounted(() => {
  load();
  timer = setInterval(() => {
    if (
      props.view === "conversations" &&
      active.value &&
      !document.hidden &&
      Date.now() >= nextRefresh
    ) {
      nextRefresh =
        Date.now() + Math.max(2, Number(data.value.pollSeconds || 3)) * 1000;
      load(true);
    }
  }, 1000);
});
onBeforeUnmount(() => {
  clearInterval(timer);
  dirty.value[props.tabId || props.view] = false;
});
</script>
<template>
  <section>
    <div class="management-panel-heading">
      <div>
        <h1>{{ editor ? "会话回复" : title }}</h1>
        <p>
          {{
            view === "conversations"
              ? "抢单领取；离线保留负责人，管理员可手动转派。消息与图片只向授权参与方提供。"
              : view === "messages"
                ? "公开留言先审核后展示，回复与内容均为纯文本。"
                : "私密反馈仅本人和授权处理人员可见，不进入公开留言板。"
          }}
        </p>
      </div>
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
    <el-card
      v-if="editor && view === 'conversations' && selected"
      shadow="never"
      style="margin-bottom: 22px"
      ><div
        v-if="data.canTransfer"
        class="admin-table-actions"
        style="margin-bottom: 18px"
      >
        <el-select
          v-model="target"
          placeholder="选择接收客服"
          style="width: 220px"
          ><el-option
            v-for="a in data.agents"
            :key="a.id"
            :value="a.id"
            :label="a.nickname" /></el-select
        ><el-button @click="transfer">管理员转派</el-button>
      </div>
      <SupportChat
        ref="chat"
        :id="selected"
        staff
        :self="data.self"
        :active="active"
        :poll-seconds="data.pollSeconds"
        @updated="load(true)"
    /></el-card>
    <el-card v-if="!editor" shadow="never"
      ><el-form
        label-position="right"
        label-width="110px"
        class="admin-filter-grid"
        @submit.prevent="
          page = 1;
          load();
        "
        ><el-form-item
          :label="view === 'conversations' ? '主题查询' : '内容查询'"
          ><el-input v-model="q" maxlength="100" clearable /></el-form-item
        ><el-form-item label="创建日期"
          ><el-date-picker
            v-model="range"
            type="daterange"
            value-format="YYYY-MM-DD"
            start-placeholder="开始日期"
            end-placeholder="结束日期"
            :shortcuts="adminDateShortcuts()" /></el-form-item
        ><el-form-item label="状态"
          ><el-select v-model="state" clearable placeholder="全部"
            ><el-option
              v-for="s in view === 'conversations'
                ? ['OPEN', 'CLOSED']
                : view === 'messages'
                  ? ['PENDING', 'APPROVED', 'REJECTED']
                  : ['PENDING', 'RESOLVED', 'REJECTED']"
              :key="s"
              :value="s"
              :label="s" /></el-select
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
        :default-sort="order.defaultSort"
        @sort-change="order.change"
        ><ObjectColumns server-sort /><template v-if="view === 'conversations'"
          ><el-table-column
            prop="subject"
            label="主题"
            min-width="200"
            sortable="custom"
            :sort-orders="['descending', 'ascending']"
          /><el-table-column
            prop="assigned_name"
            label="负责人"
            sortable="custom"
            :sort-orders="['descending', 'ascending']"
            ><template #default="{ row }">{{
              row.assigned_name || "待领取"
            }}</template></el-table-column
          ><el-table-column
            prop="unread"
            label="未读"
            sortable="custom"
            :sort-orders="['descending', 'ascending']"
            ><template #default="{ row }"
              ><el-tag :type="Number(row.unread) ? 'danger' : 'info'">{{
                row.unread
              }}</el-tag></template
            ></el-table-column
          ></template
        ><template v-else
          ><el-table-column
            prop="nickname"
            label="提交者"
            sortable="custom"
            :sort-orders="['descending', 'ascending']"
          /><el-table-column prop="body" label="内容" min-width="240"
            ><template #default="{ row }"
              ><p class="feedback-text">{{ row.body }}</p>
              <p v-if="row.reply" class="feedback-text">
                回复：{{ row.reply }}
              </p></template
            ></el-table-column
          ></template
        ><el-table-column
          prop="state"
          label="状态"
          sortable="custom"
          :sort-orders="['descending', 'ascending']"
        /><el-table-column
          align="center"
          header-align="center"
          label="操作"
          min-width="150"
          ><template #default="{ row }"
            ><template v-if="view === 'conversations'"
              ><el-button link type="primary" @click="select(row)"
                >查看</el-button
              ><el-button
                v-if="!row.assigned_to && row.state === 'OPEN'"
                link
                type="success"
                @click="claim(row)"
                >抢单领取</el-button
              ></template
            ><template v-else
              ><el-button link type="primary" @click="review(row, true)">{{
                view === "messages" ? "批准 / 回复" : "处理 / 回复"
              }}</el-button
              ><el-button
                v-if="view === 'messages'"
                link
                type="danger"
                @click="review(row, false)"
                >驳回 / 隐藏</el-button
              ></template
            ></template
          ></el-table-column
        ></el-table
      ><el-pagination
        v-model:current-page="page"
        v-model:page-size="size"
        :total="data.total"
        :page-sizes="[10, 20, 50]"
        layout="total,sizes,prev,pager,next"
        @current-change="load()"
        @size-change="
          page = 1;
          load();
        "
      />
    </el-card>
  </section>
</template>
<style scoped>
.feedback-text {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  margin: 6px 0;
}
</style>
