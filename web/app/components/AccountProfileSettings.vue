<script setup lang="ts">
const { staffPath } = useStaffPaths();
/** 个人凭据只在组件内存中编辑；改密成功撤销全部会话，按原入口返回登录，不保存到浏览器存储。 */
const { request, message } = useApi(),
  { t } = useLanguage(),
  route = useRoute();
const emit = defineEmits<{ changed: [] }>();
const nickname = ref(""),
  ready = ref(false),
  saving = ref(false),
  changing = ref(false),
  error = ref(""),
  notice = ref("");
const password = reactive({
  oldPassword: "",
  newPassword: "",
  confirmation: "",
});
onMounted(async () => {
  try {
    const user = await request<any>("/api/auth/session");
    nickname.value = user.nickname;
    ready.value = true;
  } catch (e) {
    error.value = message(e);
  }
});
async function saveNickname() {
  saving.value = true;
  error.value = "";
  notice.value = "";
  try {
    await request("/api/member/nickname", {
      method: "PUT",
      body: { nickname: nickname.value },
    });
    useState<any>("admin-user").value = await request("/api/auth/session");
    notice.value = t("昵称已更新", "Nickname updated");
    emit("changed");
  } catch (e) {
    error.value = message(e);
  } finally {
    saving.value = false;
  }
}
async function changePassword() {
  error.value = "";
  notice.value = "";
  if (password.newPassword !== password.confirmation) {
    error.value = t("两次输入的新密码不一致", "The new passwords do not match");
    return;
  }
  changing.value = true;
  try {
    await request("/api/member/password", {
      method: "PUT",
      body: {
        oldPassword: password.oldPassword,
        newPassword: password.newPassword,
      },
    });
    password.oldPassword = "";
    password.newPassword = "";
    password.confirmation = "";
    await request("/api/auth/logout", { method: "POST" });
    useState<any>("admin-user").value = null;
    await navigateTo(
      staffRelative(route.path).startsWith("/admin")
        ? staffPath("/login?changed=1")
        : "/account-access?changed=1",
    );
  } catch (e) {
    error.value = message(e);
  } finally {
    changing.value = false;
  }
}
</script>
<template>
  <el-card shadow="never" class="profile-settings"
    ><template #header
      ><h2>{{ t("个人资料与密码", "Profile and password") }}</h2></template
    ><el-alert
      v-if="error"
      :title="error"
      type="error"
      :closable="false"
    /><el-alert
      v-if="notice"
      :title="notice"
      type="success"
      :closable="false"
    /><el-form
      label-position="right"
      label-width="112px"
      @submit.prevent="saveNickname"
      ><el-form-item :label="t('个人昵称', 'Nickname')"
        ><el-input
          v-model="nickname"
          :aria-label="t('个人昵称', 'Nickname')"
          maxlength="40"
          show-word-limit
          :disabled="!ready" /></el-form-item
      ><el-form-item
        ><el-button
          type="primary"
          :loading="saving"
          :disabled="!ready || !nickname.trim()"
          @click="saveNickname"
          >{{ t("保存昵称", "Save nickname") }}</el-button
        ></el-form-item
      ></el-form
    ><el-divider /><el-form
      label-position="right"
      label-width="112px"
      @submit.prevent="changePassword"
      ><el-form-item :label="t('当前密码', 'Current password')"
        ><el-input
          v-model="password.oldPassword"
          :aria-label="t('当前密码', 'Current password')"
          type="password"
          show-password
          autocomplete="current-password"
          maxlength="256" /></el-form-item
      ><el-form-item :label="t('新密码', 'New password')"
        ><el-input
          v-model="password.newPassword"
          :aria-label="t('新密码', 'New password')"
          type="password"
          show-password
          autocomplete="new-password"
          minlength="12"
          maxlength="256" /></el-form-item
      ><el-form-item :label="t('确认新密码', 'Confirm new password')"
        ><el-input
          v-model="password.confirmation"
          :aria-label="t('确认新密码', 'Confirm new password')"
          type="password"
          show-password
          autocomplete="new-password"
          maxlength="256" /></el-form-item
      ><el-form-item
        ><el-button
          type="primary"
          :loading="changing"
          :disabled="
            !ready ||
            !password.oldPassword ||
            password.newPassword.length < 12 ||
            !password.confirmation
          "
          @click="changePassword"
          >{{ t("修改密码", "Change password") }}</el-button
        ></el-form-item
      >
      <p class="admin-muted">
        {{
          t(
            "密码至少12个字符。修改成功后，所有设备需要重新登录。",
            "Use at least 12 characters. All devices must sign in again after this change.",
          )
        }}
      </p></el-form
    ></el-card
  >
</template>
