<script setup lang="ts">
/** 邮箱令牌只在点击确认时提交，避免邮件扫描器GET链接提前消费；不写localStorage。 */
definePageMeta({ layout: "catalog" });
import "~/assets/auth.css";
import "~/assets/element-admin.css";
const { t } = useLanguage(),
  { request, message } = useApi(),
  route = useRoute();
const action = ref(String(route.query.action || "login")),
  email = ref(""),
  password = ref(""),
  code = ref(""),
  error = ref(""),
  notice = ref(
    route.query.changed === "1"
      ? t("密码已更新，请重新登录", "Password updated. Please sign in again.")
      : "",
  ),
  busy = ref(false);
watch(
  () => route.query.action,
  (value) => {
    action.value = String(value || "login");
    error.value = "";
  },
);
const titles = computed(
  () =>
    ({
      login: t("登录账户", "Sign in"),
      register: t("创建账户", "Create account"),
      forgot: t("找回密码", "Recover password"),
      verify: t("验证邮箱", "Verify email"),
      reset: t("设置新密码", "Set a new password"),
    })[action.value] || t("登录账户", "Sign in"),
);
async function submit() {
  busy.value = true;
  error.value = "";
  notice.value = "";
  try {
    if (action.value === "login") {
      await request("/api/auth/login", {
        method: "POST",
        body: {
          email: email.value,
          password: password.value,
          code: code.value,
        },
      });
      password.value = "";
      const target =
        typeof route.query.return === "string" &&
        /^\/checkout\?slug=/.test(route.query.return)
          ? route.query.return
          : "/account";
      await navigateTo(target);
    } else {
      let endpoint = "",
        body: any = {};
      if (action.value === "register") {
        endpoint = "register";
        body = { email: email.value, password: password.value };
      } else if (action.value === "forgot") {
        endpoint = "forgot-password";
        body = { email: email.value };
      } else {
        endpoint =
          action.value === "verify" ? "verify-email" : "reset-password";
        body = {
          token: String(route.query.token || ""),
          password: password.value,
        };
      }
      const result = await request<{ message?: string }>(
        "/api/auth/" + endpoint,
        { method: "POST", body },
      );
      notice.value =
        result.message ||
        t("操作成功，请登录账户。", "Success. Please sign in.");
      password.value = "";
      if (action.value === "verify" || action.value === "reset")
        await navigateTo("/account-access?action=login");
    }
  } catch (e) {
    error.value = message(e);
  } finally {
    busy.value = false;
  }
}
</script>
<template>
  <el-card class="account-access auth-card" shadow="never"
    ><h1>{{ titles }}</h1>
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
    /><el-form
      label-position="right"
      label-width="112px"
      @submit.prevent="submit"
      ><el-form-item
        v-if="!['verify', 'reset'].includes(action)"
        :label="t('邮箱', 'Email')"
        required
        ><el-input
          v-model="email"
          type="email"
          autocomplete="username"
          aria-label="邮箱"
          maxlength="254" /></el-form-item
      ><el-form-item
        v-if="['login', 'register', 'reset'].includes(action)"
        :label="t('密码', 'Password')"
        required
        ><el-input
          v-model="password"
          type="password"
          show-password
          :autocomplete="
            action === 'login' ? 'current-password' : 'new-password'
          "
          aria-label="密码"
          :placeholder="
            action === 'login'
              ? ''
              : t('至少12个字符', 'At least 12 characters')
          " /></el-form-item
      ><el-form-item
        v-if="action === 'login'"
        :label="
          t('验证器动态码（如已启用）', 'Authenticator code (if enabled)')
        "
        ><el-input
          v-model="code"
          autocomplete="one-time-code"
          maxlength="6"
          aria-label="验证器动态码" /></el-form-item
      ><el-button
        type="primary"
        native-type="submit"
        :loading="busy"
        style="width: 100%"
        >{{
          action === "verify"
            ? t("确认验证邮箱", "Confirm email")
            : t("确认", "Continue")
        }}</el-button
      ></el-form
    ><el-divider />
    <div class="catalog-editor-actions">
      <el-button text @click="action = 'login'">{{
        t("登录", "Sign in")
      }}</el-button
      ><el-button text @click="action = 'register'">{{
        t("注册", "Register")
      }}</el-button
      ><el-button text @click="action = 'forgot'">{{
        t("忘记密码", "Forgot password")
      }}</el-button>
    </div></el-card
  >
</template>
