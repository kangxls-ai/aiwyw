<script setup lang="ts">
/** 游客身份仅由HttpOnly Cookie保存；找回须一次性邮件核验，不按会话编号直接开放内容。 */
import "~/assets/element-admin.css";
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
async function history() {
  conversations.value = await request("/api/support/conversations", {
    query: { page: page.value, pageSize: 10 },
  });
  page.value = conversations.value.page;
}
async function notes() {
  publicNotes.value = await request("/api/public/messages", {
    query: { page: feedbackPage.value, pageSize: 10 },
  });
  mine.value = await request("/api/support/feedback", {
    query: { page: minePage.value, pageSize: 10 },
  });
}
function select(id: string) {
  if (
    chat.value?.draft &&
    !window.confirm(t("放弃尚未发送的消息？", "Discard the unsent message?"))
  )
    return;
  selected.value = id;
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
        ><div class="support-grid">
          <aside>
            <el-button type="primary" @click="createDialog = true">{{
              t("新建咨询", "New conversation")
            }}</el-button
            ><el-button @click="history">{{ t("刷新", "Refresh") }}</el-button
            ><el-empty
              v-if="!conversations.items.length"
              :description="t('暂无会话', 'No conversations')"
            /><button
              v-for="item in conversations.items"
              :key="item.id"
              class="conversation-pick"
              :class="{ chosen: selected === item.id }"
              @click="select(item.id)"
            >
              <strong>{{ item.subject }}</strong
              ><small
                >{{
                  item.state === "OPEN"
                    ? t("进行中", "Open")
                    : t("已结束", "Closed")
                }}
                · {{ item.assigned_name || t("待领取", "Waiting") }}</small
              ><el-badge
                v-if="Number(item.unread) > 0"
                :value="item.unread"
              /></button
            ><el-pagination
              v-model:current-page="page"
              :total="conversations.total"
              :page-size="10"
              layout="prev,pager,next"
              @current-change="history"
            />
          </aside>
          <el-card shadow="never"
            ><SupportChat
              v-if="selected"
              ref="chat"
              :id="selected"
              :poll-seconds="session?.pollSeconds"
              :active="active === 'chat'"
              @updated="history" /><el-empty
              v-else
              :description="
                t(
                  '选择或新建咨询，游客也可使用',
                  'Select or create a conversation. Guests are welcome.',
                )
              "
          /></el-card>
        </div>
        <p class="muted">
          {{
            t(
              "游客历史保存在当前浏览器凭据下；充值咨询需登录。清理Cookie后，可用会话编号与所留邮箱核验找回，无法核验请新建。",
              "Guest history uses a browser credential. Billing inquiries require sign-in. After clearing cookies, recover with the conversation ID and your email, or start a new chat.",
            )
          }}
        </p></el-tab-pane
      >
      <el-tab-pane
        :label="t('留言与意见', 'Messages and feedback')"
        name="feedback"
        ><el-card shadow="never"
          ><el-form
            class="feedback-form"
            label-position="right"
            label-width="120px"
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
                :aria-label="
                  t('意见反馈内容', 'Feedback message')
                " /></el-form-item
            ><el-form-item
              ><el-button
                type="primary"
                :loading="busy"
                @click="submitFeedback"
                >{{ t("提交反馈", "Submit") }}</el-button
              ></el-form-item
            ></el-form
          ></el-card
        >
        <h2>{{ t("公开留言板", "Public message board") }}</h2>
        <el-empty
          v-if="!publicNotes.items.length"
          :description="
            t('暂无已审核留言', 'No approved messages yet')
          " /><el-card
          v-for="item in publicNotes.items"
          :key="item.id"
          class="note-card"
          shadow="never"
          ><strong>{{ item.nickname }}</strong>
          <p class="note-body">{{ item.body }}</p>
          <p v-if="item.reply" class="note-body">
            {{ t("回复", "Reply") }}：{{ item.reply }}
          </p></el-card
        ><el-pagination
          v-model:current-page="feedbackPage"
          :total="publicNotes.total"
          :page-size="10"
          layout="prev,pager,next"
          @current-change="notes" />
        <h2>{{ t("我的反馈", "My feedback") }}</h2>
        <el-card
          v-for="item in mine.items"
          :key="item.id"
          class="note-card"
          shadow="never"
          ><el-tag
            >{{
              item.kind === "PRIVATE"
                ? t("私密", "Private")
                : t("公开", "Public")
            }}
            · {{ item.state }}</el-tag
          >
          <p class="note-body">{{ item.body }}</p>
          <p v-if="item.reply" class="note-body">
            {{ t("回复", "Reply") }}：{{ item.reply }}
          </p></el-card
        ><el-pagination
          v-model:current-page="minePage"
          :total="mine.total"
          :page-size="10"
          layout="prev,pager,next"
          @current-change="notes"
      /></el-tab-pane>
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
                t('充值咨询（需登录）', 'Billing (sign-in required)')
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
