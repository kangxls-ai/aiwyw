<script setup lang="ts">
/** 增量游标、发送幂等键及旧请求代数，避免切换会话后的迟到响应串入当前会话。图片始终走私有授权地址。 */
import { ElMessageBox } from "element-plus";
import { businessTime } from "~/utils/commerce";
const props = defineProps<{
    id: string;
    staff?: boolean;
    self?: string;
    pollSeconds?: number;
    active?: boolean;
  }>(),
  emit = defineEmits<{ updated: [] }>(),
  { request, message } = useApi(),
  language = useLanguage();
const t = (cn: string, en: string) => (props.staff ? cn : language.t(cn, en));
const items = ref<any[]>([]),
  conversation = ref<any>(null),
  body = ref(""),
  asset = ref(""),
  error = ref(""),
  sending = ref(false),
  uploading = ref(false),
  fetching = ref(false),
  fileInput = ref<HTMLInputElement | null>(null),
  feed = ref<HTMLElement | null>(null),
  requestId = ref("");
const draft = computed(
    () =>
      !!body.value.trim() || !!asset.value || uploading.value || sending.value,
  ),
  writable = computed(
    () =>
      conversation.value?.state === "OPEN" &&
      (!props.staff || conversation.value?.assigned_to === props.self),
  );
const base = computed(
  () =>
    (props.staff
      ? "/api/admin/support/conversations/"
      : "/api/support/conversations/") + props.id,
);
watch([body, asset], () => {
  if (!sending.value) requestId.value = "";
});
async function load(initial = false, before = 0) {
  if (!props.id || fetching.value || props.active === false || document.hidden)
    return;
  fetching.value = true;
  const key = props.id;
  try {
    const after = initial ? 0 : Number(items.value.at(-1)?.seq || 0),
      r = await request<any>(base.value + "/messages", {
        query: { after: before ? 0 : after, before },
      });
    if (props.id !== key) return;
    conversation.value = r.conversation;
    const all = initial ? r.items : [...items.value, ...r.items];
    items.value = [
      ...new Map(all.map((m: any) => [Number(m.seq), m])).values(),
    ].sort((a: any, b: any) => Number(a.seq) - Number(b.seq));
    if (!before) {
      await nextTick();
      if (feed.value) feed.value.scrollTop = feed.value.scrollHeight;
    }
    if (!props.staff || conversation.value.assigned_to === props.self)
      await request(base.value + "/read", {
        method: "POST",
        body: { seq: Number(items.value.at(-1)?.seq || 0) },
      });
    if (r.items.length) emit("updated");
  } catch (e) {
    if (props.id === key) error.value = message(e);
  } finally {
    fetching.value = false;
  }
}
async function send() {
  if (!writable.value || !draft.value || uploading.value || sending.value)
    return;
  if (!requestId.value) requestId.value = crypto.randomUUID();
  sending.value = true;
  error.value = "";
  try {
    await request(base.value + "/messages", {
      method: "POST",
      body: {
        clientKey: requestId.value,
        body: body.value,
        assetId: asset.value,
      },
    });
    body.value = "";
    asset.value = "";
    requestId.value = "";
    await load();
    emit("updated");
  } catch (e) {
    error.value = message(e);
  } finally {
    sending.value = false;
  }
}
async function upload(file?: File) {
  if (!file || !writable.value || uploading.value || sending.value) return;
  uploading.value = true;
  error.value = "";
  try {
    const data = new FormData();
    data.append("file", file);
    const r = await request<any>(base.value + "/image", {
      method: "POST",
      body: data,
    });
    asset.value = r.id;
  } catch (e) {
    error.value = message(e);
  } finally {
    uploading.value = false;
    if (fileInput.value) fileInput.value.value = "";
  }
}
function paste(event: ClipboardEvent) {
  const file = [...(event.clipboardData?.files || [])].find((f) =>
    f.type.startsWith("image/"),
  );
  if (file) {
    event.preventDefault();
    upload(file);
  }
}
async function state() {
  try {
    const next = conversation.value.state === "OPEN" ? "CLOSED" : "OPEN";
    await ElMessageBox.confirm(
      next === "CLOSED"
        ? t(
            "结束当前会话？历史记录会保留。",
            "Close this conversation? History is retained.",
          )
        : t("重新打开当前会话？", "Reopen this conversation?"),
      t("确认", "Confirm"),
      {
        confirmButtonText: t("确定", "Confirm"),
        cancelButtonText: t("取消", "Cancel"),
      },
    );
    await request(base.value + "/state", {
      method: "POST",
      body: { state: next, revision: conversation.value.revision },
    });
    await load(true);
    emit("updated");
  } catch (e) {
    if (e !== "cancel" && e !== "close") error.value = message(e);
  }
}
watch(
  () => props.id,
  () => {
    items.value = [];
    conversation.value = null;
    body.value = "";
    asset.value = "";
    error.value = "";
    fetching.value = false;
    load(true);
  },
);
let timer: ReturnType<typeof setInterval> | undefined;
onMounted(() => {
  load(true);
  timer = setInterval(() => load(), Math.max(2, props.pollSeconds || 3) * 1000);
});
onBeforeUnmount(() => clearInterval(timer));
defineExpose({ draft, conversation, refresh: () => load(true) });
</script>
<template>
  <section class="support-chat">
    <div v-if="conversation" class="chat-heading">
      <div>
        <h2>{{ conversation.subject }}</h2>
        <small>{{ t("会话编号", "Conversation ID") }}：{{ id }}</small>
      </div>
      <el-button
        v-if="!staff || conversation.assigned_to === self"
        @click="state"
        >{{
          conversation.state === "OPEN"
            ? t("结束会话", "Close conversation")
            : t("重新打开", "Reopen")
        }}</el-button
      >
    </div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <div ref="feed" class="chat-feed" aria-live="polite">
      <el-button
        v-if="Number(items[0]?.seq) > 1"
        :loading="fetching"
        @click="load(false, Number(items[0].seq))"
        >{{ t("加载更早消息", "Load earlier messages") }}</el-button
      ><el-empty
        v-if="!items.length"
        :description="t('发送第一条消息', 'Send the first message')"
      />
      <article
        v-for="item in items"
        :key="item.id"
        :class="[
          'chat-message',
          item.actor_type === 'STAFF' ? 'from-staff' : 'from-member',
        ]"
      >
        <div>
          <strong>{{ item.nickname }}</strong
          ><time>{{ businessTime(item.created_at) }}</time>
        </div>
        <p v-if="item.body">{{ item.body }}</p>
        <a
          v-if="item.asset_id"
          :href="'/api/support/assets/' + item.asset_id"
          target="_blank"
          rel="noopener"
          ><img
            :src="'/api/support/assets/' + item.asset_id"
            :alt="t('会话图片', 'Conversation image')"
        /></a>
      </article>
    </div>
    <template v-if="writable"
      ><el-input
        v-model="body"
        type="textarea"
        :rows="3"
        maxlength="4000"
        show-word-limit
        :disabled="sending"
        :placeholder="
          t(
            '输入文字，可粘贴截图；Ctrl+Enter发送',
            'Type a message or paste an image; Ctrl+Enter sends',
          )
        "
        :aria-label="t('客服消息输入', 'Support message')"
        @paste="paste"
        @keydown.ctrl.enter.prevent="send"
      />
      <div v-if="asset" class="pending-image">
        <img
          :src="'/api/support/assets/' + asset"
          :alt="t('待发送图片', 'Pending image')"
        /><el-button :disabled="sending" @click="asset = ''">{{
          t("移除", "Remove")
        }}</el-button>
      </div>
      <div class="chat-compose-actions">
        <input
          ref="fileInput"
          type="file"
          accept="image/jpeg,image/png,image/webp"
          hidden
          @change="upload(($event.target as HTMLInputElement).files?.[0])"
        /><el-button
          :loading="uploading"
          :disabled="sending || uploading"
          @click="fileInput?.click()"
          >{{ t("上传图片 / 截图", "Upload image / screenshot") }}</el-button
        ><el-button
          type="primary"
          :loading="sending"
          :disabled="!draft || uploading"
          @click="send"
          >{{ t("发送", "Send") }}</el-button
        >
      </div></template
    >
    <el-alert
      v-else-if="conversation"
      type="info"
      :closable="false"
      :title="
        conversation.state === 'CLOSED'
          ? t(
              '会话已结束，可重新打开或新建咨询。',
              'This conversation is closed. Reopen it or create a new one.',
            )
          : t(
              '请先领取或由管理员转派后回复。',
              'Claim this conversation or have it assigned to you before replying.',
            )
      "
    />
  </section>
</template>
<style scoped>
.support-chat {
  min-width: 0;
}
.chat-heading {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 15px;
  margin-bottom: 14px;
}
.chat-heading h2 {
  font-size: 20px;
  margin: 0 0 8px;
}
.chat-heading small {
  color: #8c92a5;
  overflow-wrap: anywhere;
}
.chat-feed {
  height: 390px;
  overflow: auto;
  background: #f7f8fc;
  border: 1px solid #eceaf4;
  border-radius: 12px;
  padding: 16px;
  margin: 16px 0;
}
.chat-message {
  max-width: 88%;
  padding: 12px 15px;
  margin: 12px 0;
  background: white;
  border: 1px solid #eceaf4;
  border-radius: 12px;
  overflow-wrap: anywhere;
}
.from-staff {
  margin-left: auto;
  background: #f0ebff;
}
.chat-message time {
  font-size: 11px;
  color: #8991a6;
  margin-left: 12px;
}
.chat-message p {
  white-space: pre-wrap;
  margin: 9px 0 0;
}
.chat-message img {
  max-width: 100%;
  max-height: 240px;
  border-radius: 8px;
  margin-top: 10px;
}
.chat-compose-actions {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  margin-top: 14px;
}
.pending-image {
  margin-top: 12px;
  display: flex;
  align-items: center;
  gap: 12px;
}
.pending-image img {
  max-height: 90px;
  max-width: 160px;
}
@media (max-width: 600px) {
  .chat-feed {
    height: 340px;
    padding: 10px;
  }
  .chat-message {
    max-width: 96%;
  }
  .chat-heading {
    align-items: flex-start;
  }
  .chat-heading small {
    font-size: 10px;
  }
}
</style>
