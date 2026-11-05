<script setup lang="ts">
/** 密钥仅在用户主动打开时从私有POST读取；关闭即清内存，不持久化到浏览器或地址栏。 */
import { businessTime } from "~/utils/commerce";
const props = defineProps<{ purchase: string }>();
const { t } = useLanguage(),
  { request, message } = useApi();
const stateText: Record<string, string> = {
  ACTIVE: "有效",
  REVOKED: "已撤销",
  PENDING: "待审核",
  APPROVED: "已通过",
  REJECTED: "已驳回",
  CANCELLED: "已取消",
};
const visible = ref(false),
  data = ref<any>(null),
  error = ref(""),
  reveal = ref(false),
  busy = ref(false);
// 弹窗可快速关闭/重开；关闭立即清除密钥，迟到响应不能重新写回已关闭的组件。
const selectedDevice = ref<any>(null),
  reason = ref(""),
  submitting = ref(false),
  notice = ref("");
let loadSequence = 0;
watch(visible, (value) => {
  if (!value) {
    loadSequence++;
    data.value = null;
    selectedDevice.value = null;
    reason.value = "";
    notice.value = "";
    reveal.value = false;
    busy.value = false;
  }
});
onBeforeUnmount(() => {
  loadSequence++;
  data.value = null;
});
async function open() {
  visible.value = true;
  reveal.value = false;
  busy.value = true;
  error.value = "";
  const sequence = ++loadSequence;
  try {
    const result = await request(
      "/api/member/licenses/" + props.purchase + "/detail",
      { method: "POST" },
    );
    if (sequence === loadSequence && visible.value) data.value = result;
  } catch (e) {
    if (sequence === loadSequence && visible.value) error.value = message(e);
  } finally {
    if (sequence === loadSequence) busy.value = false;
  }
}
function chooseDevice(device: any) {
  selectedDevice.value = device;
  reason.value = "";
  notice.value = "";
}
/** 原因直接在设备弹窗内填写，避免多层弹窗遮挡或误提交。服务器仍逐次检查归属和设备状态。 */
async function unbind() {
  if (!selectedDevice.value || !reason.value.trim() || submitting.value) return;
  submitting.value = true;
  error.value = "";
  try {
    await request("/api/member/licenses/" + props.purchase + "/unbind", {
      method: "POST",
      body: { deviceId: selectedDevice.value.id, reason: reason.value },
    });
    selectedDevice.value = null;
    reason.value = "";
    notice.value = t("换机申请已提交", "Replacement request submitted");
    await open();
  } catch (e) {
    error.value = message(e);
  } finally {
    submitting.value = false;
  }
}
</script>
<template>
  <el-button link type="primary" @click="open">{{
    t("激活授权", "License")
  }}</el-button
  ><el-dialog
    append-to-body
    v-model="visible"
    :title="t('软件授权与设备', 'Software license and devices')"
    width="min(850px,94vw)"
    ><el-alert v-if="error" :title="error" type="error" :closable="false" />
    <div v-loading="busy" v-if="data">
      <p>
        {{ t("授权大版本", "Licensed major version") }}：{{
          data.majorVersion
        }}
        · {{ t("设备上限", "Device limit") }}：{{ data.deviceLimit }}
      </p>
      <el-input
        readonly
        :model-value="data.key"
        :type="reveal ? 'text' : 'password'"
        :aria-label="t('激活密钥', 'Activation key')"
        style="max-width: 520px"
      /><el-button link @click="reveal = !reveal">{{
        reveal ? t("隐藏", "Hide") : t("显示密钥", "Reveal key")
      }}</el-button>
      <p>
        {{
          t(
            "请保管激活密钥，仅在支持本站授权的应用中输入。换机须审核，退款会撤销授权。",
            "Keep this key private. Enter it only in an app integrated with this service. Device replacement requires approval; refunds revoke the license.",
          )
        }}
      </p>
      <el-table
        :data="data.devices"
        :default-sort="{ prop: 'created_at', order: 'descending' }"
        ><el-table-column
          prop="device_name"
          :label="t('设备', 'Device')"
          sortable
          :sort-orders="['descending', 'ascending']"
        /><el-table-column
          prop="created_at"
          :label="t('绑定时间', 'Bound at')"
          sortable
          :sort-orders="['descending', 'ascending']"
          ><template #default="{ row }">{{
            businessTime(row.created_at)
          }}</template></el-table-column
        ><el-table-column
          prop="state"
          :label="t('状态', 'State')"
          sortable
          :sort-orders="['descending', 'ascending']"
          ><template #default="{ row }">{{
            t(stateText[row.state] || row.state, row.state)
          }}</template></el-table-column
        ><el-table-column :label="t('操作', 'Actions')"
          ><template #default="{ row }"
            ><el-button
              v-if="row.state === 'ACTIVE'"
              link
              type="warning"
              @click="chooseDevice(row)"
              >{{ t("申请解绑", "Request unbind") }}</el-button
            ></template
          ></el-table-column
        ></el-table
      >
      <el-alert
        v-if="notice"
        :title="notice"
        type="success"
        :closable="false"
      />
      <el-form
        v-if="selectedDevice"
        label-position="right"
        label-width="100px"
        @submit.prevent="unbind"
        style="margin-top: 16px"
        ><el-form-item :label="t('换机原因', 'Reason')"
          ><el-input
            v-model="reason"
            :aria-label="t('换机原因', 'Reason')"
            type="textarea"
            :rows="3"
            maxlength="500"
            show-word-limit /></el-form-item
        ><el-form-item
          ><el-button
            type="primary"
            :loading="submitting"
            :disabled="!reason.trim()"
            @click="unbind"
            >{{ t("提交申请", "Submit request") }}</el-button
          ><el-button
            @click="
              selectedDevice = null;
              reason = '';
            "
            >{{ t("取消", "Cancel") }}</el-button
          ></el-form-item
        ></el-form
      >
      <h3>{{ t("申请记录", "Requests") }}</h3>
      <p v-for="item in data.requests" :key="item.id">
        {{ businessTime(item.created_at) }} ·
        {{ t(stateText[item.state] || item.state, item.state) }} ·
        {{ item.review_note || item.reason }}
      </p>
    </div></el-dialog
  >
</template>
