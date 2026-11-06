<script setup lang="ts">
/** 游客身份仅由HttpOnly Cookie保存；找回须一次性邮件核验，不按会话编号直接开放内容。 */
import "~/assets/element-admin.css";
import { businessTime } from "~/utils/commerce";
definePageMeta({ layout: "catalog" });
const { request, message } = useApi(),
  { t } = useLanguage(),
  route = useRoute();
const session = ref<any>(null),
  conversations = ref<any>({ items: [], total: 0 }),
  page = ref(1),
  active = ref("chat"),
  selected = ref(""),
  error = ref(""),
  notice = ref(""),
  createDialog = ref(false),
  busy = ref(false),
  chat = ref<any>(null);
const chatDialog = ref(false),
  feedbackDialog = ref(false),
  noteDialog = ref(false),
  selectedNote = ref<any>(null);
const chatFilters = reactive({ q: "", state: "" }),
  feedbackFilters = reactive({ q: "", state: "" });
const chatStates = computed(() => [
  { value: "OPEN", label: t("进行中", "Open") },
  { value: "CLOSED", label: t("已结束", "Closed") },
]);
const feedbackStates = computed(() =>
  ["PENDING", "APPROVED", "REJECTED", "RESOLVED"].map((value) => ({
    value,
    label:
      (
        {
          PENDING: "待处理",
          APPROVED: "已公开",
          REJECTED: "未通过",
          RESOLVED: "已回复",
        } as Record<string, string>
      )[value] || value,
  })),
);
function searchHistory() {
  page.value = 1;
  history().catch((e) => (error.value = message(e)));
}
function searchNotes() {
  feedbackPage.value = 1;
  minePage.value = 1;
  notes().catch((e) => (error.value = message(e)));
}
function showNote(row: any) {
  selectedNote.value = row;
  noteDialog.value = true;
}
function closeChat(done: () => void) {
  if (
    !chat.value?.draft ||
    window.confirm(t("放弃尚未发送的消息？", "Discard the unsent message?"))
  ) {
    selected.value = "";
    done();
  }
}
const form = reactive({
    subject: "",
    topic: "GENERAL",
    email: "",
    emailOptin: false,
  }),
  recovery = reactive({ conversationId: "", email: "" }),
  feedback = reactive({ kind: "PUBLIC", body: "" }),
  feedbackPage = ref(1),
  minePage = ref(1),
  publicNotes = ref<any>({ items: [], total: 0 }),
  mine = ref<any>({ items: [], total: 0 });
const historyOrder = useListOrder(history, page),
  noteOrder = useListOrder(notes, feedbackPage),
  mineOrder = useListOrder(notes, minePage);
async function history() {
  conversations.value = await request("/api/support/conversations", {
    query: {
      page: page.value,
      pageSize: 10,
      sort: historyOrder.sort.value,
      direction: historyOrder.direction.value,
      q: chatFilters.q,
      state: chatFilters.state,
    },
  });
  page.value = conversations.value.page;
}
async function notes() {
  publicNotes.value = await request("/api/public/messages", {
    query: {
      page: feedbackPage.value,
      pageSize: 10,
      q: feedbackFilters.q,
      sort: noteOrder.sort.value,
      direction: noteOrder.direction.value,
    },
  });
  mine.value = await request("/api/support/feedback", {
    query: {
      page: minePage.value,
      pageSize: 10,
      sort: mineOrder.sort.value,
      direction: mineOrder.direction.value,
      q: feedbackFilters.q,
      state: feedbackFilters.state,
    },
  });
}
function select(id: string) {
  if (
    chat.value?.draft &&
    !window.confirm(t("放弃尚未发送的消息？", "Discard the unsent message?"))
  )
    return;
  selected.value = id;
  chatDialog.value = true;
}
async function create() {
  busy.value = true;
  try {
    const r = await request<any>("/api/support/conversations", {
      method: "POST",
      body: form,
    });
    createDialog.value = false;
    await history();
    select(r.id);
  } catch (e) {
    error.value = message(e);
  } finally {
    busy.value = false;
  }
}
async function submitFeedback() {
  busy.value = true;
  try {
    await request("/api/support/feedback", { method: "POST", body: feedback });
    feedback.body = "";
    feedbackDialog.value = false;
    notice.value = t(
      "已提交，公开留言审核后展示；私密反馈仅本人和客服可见。",
      "Submitted. Public messages need approval; private feedback stays private.",
    );
    await notes();
  } catch (e) {
    error.value = message(e);
  } finally {
    busy.value = false;
  }
}
async function recover() {
  try {
    const r = await request<any>("/api/support/recovery", {
      method: "POST",
      body: recovery,
    });
    notice.value = t(
      "资料匹配时会发送核验邮件；无法核验请新建咨询。",
      "If the details match, a verification email will be sent. If you cannot verify, start a new conversation.",
    );
  } catch (e) {
    error.value = message(e);
  }
}
onMounted(async () => {
  try {
    if (route.query.recover) {
      const token = String(route.query.recover);
      await navigateTo("/support", { replace: true });
      await request("/api/support/recovery/consume", {
        method: "POST",
        body: { token },
      });
      notice.value = t(
        "邮箱核验成功，历史会话已找回。",
        "Email verified. Your conversations have been recovered.",
      );
    }
    session.value = await request("/api/support/session");
    form.email = session.value.email;
    form.emailOptin = session.value.emailOptin;
    await history();
    await notes();
  } catch (e) {
    error.value = message(e);
  }
});
onBeforeRouteLeave(
  () =>
    !chat.value?.draft ||
    window.confirm(
      t("还有未发送的消息，确认离开？", "Leave with an unsent message?"),
    ),
);
useHead(() => ({
  title: t("客服与反馈 · AI万有网", "Support and feedback · AI万有网"),
  meta: [{ name: "referrer", content: "no-referrer" }],
}));
</script>
<template>
  <main class="directory-width support-page">
    <h1>{{ t("客服与反馈", "Support and feedback") }}</h1>
    <p>
      {{
        session?.online
          ? t(
              "客服在线，可发起咨询。",
              "Support is online. Start a conversation.",
            )
          : t(
              "当前客服离线，可留言，回复后在这里查看。",
              "Support is offline. Leave a message and check the reply here.",
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
    <el-tabs v-model="active"
      ><el-tab-pane :label="t('在线咨询 / 历史', 'Chat / history')" name="chat"
        ><RecordQuery
          v-model="chatFilters"
          :states="chatStates"
          :dates="false"
          @search="searchHistory"
          ><el-button type="primary" @click="createDialog = true">{{
            t("新建咨询", "New conversation")
          }}</el-button></RecordQuery
        >
        <el-table
          :default-sort="historyOrder.defaultSort"
          @sort-change="historyOrder.change"
          :data="conversations.items"
          row-key="id"
          ><ObjectColumns
            localized
            :show-creator="false"
            server-sort
          /><el-table-column
            prop="subject"
            :label="t('主题', 'Subject')"
            min-width="200"
          /><el-table-column :label="t('状态', 'Status')"
            ><template #default="{ row }">{{
              row.state === "OPEN" ? t("进行中", "Open") : t("已结束", "Closed")
            }}</template></el-table-column
          ><el-table-column
            prop="assigned_name"
            :label="t('客服', 'Agent')"
            min-width="130"
          /><el-table-column
            prop="unread"
            :label="t('未读', 'Unread')"
          /><el-table-column
            :label="t('最后更新', 'Updated at')"
            min-width="180"
            ><template #default="{ row }">{{
              businessTime(row.updated_at)
            }}</template></el-table-column
          ><el-table-column :label="t('操作', 'Actions')"
            ><template #default="{ row }"
              ><el-button link type="primary" @click="select(row.id)">{{
                t("查看 / 回复", "View / reply")
              }}</el-button></template
            ></el-table-column
          ></el-table
        ><el-pagination
          v-model:current-page="page"
          :total="conversations.total"
          :page-size="10"
          layout="total,prev,pager,next"
          @current-change="history"
        />
        <p class="muted">
          {{
            t(
              "游客历史保存在当前浏览器凭据下；捐赠咨询需登录。清理Cookie后，可用会话编号与所留邮箱核验找回，无法核验请新建。",
              "Guest history uses a browser credential. Billing inquiries require sign-in. After clearing cookies, recover with the conversation ID and your email, or start a new chat.",
            )
          }}
        </p></el-tab-pane
      >
      <el-tab-pane
        :label="t('留言与意见', 'Messages and feedback')"
        name="feedback"
        ><RecordQuery
          v-model="feedbackFilters"
          :states="feedbackStates"
          :dates="false"
          @search="searchNotes"
          ><el-button type="primary" @click="feedbackDialog = true">{{
            t("新增留言与意见", "New message or feedback")
          }}</el-button></RecordQuery
        >
        <h2>{{ t("公开留言板", "Public message board") }}</h2>
        <el-table
          :default-sort="noteOrder.defaultSort"
          @sort-change="noteOrder.change"
          :data="publicNotes.items"
          ><ObjectColumns
            localized
            :show-creator="false"
            server-sort
          /><el-table-column
            prop="nickname"
            :label="t('昵称', 'Nickname')"
            min-width="150"
          /><el-table-column
            prop="body"
            :label="t('内容', 'Message')"
            min-width="260"
            show-overflow-tooltip
          /><el-table-column
            prop="reply"
            :label="t('客服回复', 'Reply')"
            min-width="220"
            show-overflow-tooltip
          /><el-table-column :label="t('操作', 'Actions')"
            ><template #default="{ row }"
              ><el-button link @click="showNote(row)">{{
                t("查看详情", "Details")
              }}</el-button></template
            ></el-table-column
          ></el-table
        ><el-pagination
          v-model:current-page="feedbackPage"
          :total="publicNotes.total"
          :page-size="10"
          layout="total,prev,pager,next"
          @current-change="notes"
        />
        <h2>{{ t("我提交的留言与意见", "My messages and feedback") }}</h2>
        <el-table
          :default-sort="mineOrder.defaultSort"
          @sort-change="mineOrder.change"
          :data="mine.items"
          ><ObjectColumns
            localized
            :show-creator="false"
            server-sort
          /><el-table-column
            prop="kind"
            :label="t('类型', 'Visibility')"
          /><el-table-column
            prop="body"
            :label="t('内容', 'Message')"
            min-width="260"
            show-overflow-tooltip
          /><el-table-column
            prop="state"
            :label="t('状态', 'Status')"
          /><el-table-column
            prop="reply"
            :label="t('处理回复', 'Response')"
            min-width="220"
            show-overflow-tooltip
          /><el-table-column :label="t('操作', 'Actions')"
            ><template #default="{ row }"
              ><el-button link @click="showNote(row)">{{
                t("查看详情", "Details")
              }}</el-button></template
            ></el-table-column
          ></el-table
        ><el-pagination
          v-model:current-page="minePage"
          :total="mine.total"
          :page-size="10"
          layout="total,prev,pager,next"
          @current-change="notes"
        />
      </el-tab-pane>
      <el-tab-pane
        :label="t('找回游客会话', 'Recover guest history')"
        name="recover"
        ><el-form
          class="feedback-form"
          label-position="right"
          label-width="140px"
          ><el-form-item :label="t('会话编号', 'Conversation ID')"
            ><el-input
              v-model="recovery.conversationId"
              maxlength="36" /></el-form-item
          ><el-form-item :label="t('所留邮箱', 'Email you provided')"
            ><el-input
              v-model="recovery.email"
              type="email"
              maxlength="254" /></el-form-item
          ><el-form-item
            ><el-button type="primary" @click="recover">{{
              t("发送核验链接", "Send verification link")
            }}</el-button></el-form-item
          ></el-form
        ></el-tab-pane
      ></el-tabs
    >
    <el-dialog
      v-model="chatDialog"
      :title="t('在线咨询', 'Online support')"
      width="min(1000px,96vw)"
      :close-on-click-modal="false"
      :before-close="closeChat"
      destroy-on-close
      ><SupportChat
        v-if="selected && chatDialog"
        ref="chat"
        :id="selected"
        :poll-seconds="session?.pollSeconds"
        :active="active === 'chat' && chatDialog"
        @updated="history"
    /></el-dialog>
    <el-dialog
      v-model="feedbackDialog"
      :title="t('新增留言与意见', 'New message or feedback')"
      width="min(800px,96vw)"
    >
      <el-form class="feedback-form" label-position="right" label-width="120px"
        ><el-form-item :label="t('提交方式', 'Visibility')"
          ><el-select v-model="feedback.kind"
            ><el-option
              value="PUBLIC"
              :label="
                t('公开留言（先审后发）', 'Public (moderated)')
              " /><el-option
              value="PRIVATE"
              :label="
                t('私密意见反馈', 'Private feedback')
              " /></el-select></el-form-item
        ><el-form-item :label="t('内容', 'Message')"
          ><el-input
            v-model="feedback.body"
            type="textarea"
            :rows="4"
            maxlength="4000"
            :aria-label="t('意见反馈内容', 'Feedback message')" /></el-form-item
        ><el-form-item
          ><el-button type="primary" :loading="busy" @click="submitFeedback">{{
            t("提交反馈", "Submit")
          }}</el-button></el-form-item
        ></el-form
      >
    </el-dialog>
    <el-dialog
      v-model="noteDialog"
      :title="t('留言与意见详情', 'Feedback details')"
      width="min(700px,95vw)"
      ><el-descriptions v-if="selectedNote" :column="1" border
        ><el-descriptions-item :label="t('内容', 'Message')"
          ><p class="note-body">
            {{ selectedNote.body }}
          </p></el-descriptions-item
        ><el-descriptions-item :label="t('状态', 'Status')">{{
          selectedNote.state
        }}</el-descriptions-item
        ><el-descriptions-item :label="t('处理回复', 'Response')"
          ><p class="note-body">
            {{ selectedNote.reply || "—" }}
          </p></el-descriptions-item
        ><el-descriptions-item :label="t('创建时间', 'Created at')">{{
          businessTime(selectedNote.created_at)
        }}</el-descriptions-item
        ><el-descriptions-item :label="t('更新时间', 'Updated at')">{{
          businessTime(selectedNote.updated_at)
        }}</el-descriptions-item></el-descriptions
      ></el-dialog
    >
    <el-dialog
      v-model="createDialog"
      :title="t('新建咨询', 'New conversation')"
      width="min(600px,94vw)"
      ><el-form label-position="right" label-width="120px"
        ><el-form-item :label="t('主题', 'Subject')"
          ><el-input
            v-model="form.subject"
            maxlength="120"
            :aria-label="t('咨询主题', 'Conversation subject')" /></el-form-item
        ><el-form-item :label="t('咨询类型', 'Topic')"
          ><el-select v-model="form.topic"
            ><el-option
              value="GENERAL"
              :label="t('普通咨询', 'General')" /><el-option
              value="RECHARGE"
              :label="
                t('捐赠咨询（需登录）', 'Billing (sign-in required)')
              " /></el-select></el-form-item
        ><el-form-item :label="t('联系邮箱', 'Contact email')"
          ><el-input
            v-model="form.email"
            type="email"
            :placeholder="
              t('可选，用于核验找回', 'Optional, used for recovery')
            " /></el-form-item
        ><el-form-item :label="t('回复邮件', 'Email replies')"
          ><el-checkbox v-model="form.emailOptin">{{
            t("自愿接收游客回复提醒", "Opt in to guest reply notifications")
          }}</el-checkbox></el-form-item
        ><el-form-item
          ><el-button type="primary" :loading="busy" @click="create">{{
            t("开始咨询", "Start conversation")
          }}</el-button></el-form-item
        ></el-form
      ></el-dialog
    >
  </main>
</template>
<style scoped>
.support-page {
  padding-top: 32px;
  padding-bottom: 48px;
}
.support-grid {
  display: grid;
  grid-template-columns: 260px minmax(0, 1fr);
  gap: 22px;
  margin: 20px 0;
}
.conversation-pick {
  position: relative;
  display: block;
  width: 100%;
  text-align: left;
  border: 1px solid #e8e4f4;
  background: white;
  border-radius: 10px;
  margin: 14px 0;
  padding: 15px;
  cursor: pointer;
  color: #424b63;
}
.conversation-pick.chosen {
  background: #f2edff;
  border-color: #b9a9ee;
}
.conversation-pick strong,
.conversation-pick small {
  display: block;
}
.conversation-pick small {
  margin-top: 7px;
  color: #9098aa;
}
.feedback-form {
  max-width: 760px;
}
.note-card {
  margin: 16px 0;
}
.note-body {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.support-page h2 {
  font-size: 22px;
  margin: 24px 0 18px;
}
@media (max-width: 760px) {
  .support-grid {
    grid-template-columns: 1fr;
  }
  .support-grid aside {
    max-height: 280px;
    overflow: auto;
  }
}
</style>
