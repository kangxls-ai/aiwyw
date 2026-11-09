<script setup lang="ts">
const { staffPath } = useStaffPaths();
/** 登录表单不自行判断角色或MFA成功，依据服务器会话跳转；密码和动态码不得缓存。 */
definePageMeta({ layout: "catalog" });
import "~/assets/element-admin.css";
import "~/assets/auth.css";
const { siteName, siteSlogan, registrationOpen } = useSiteBrand();
useHead(() => ({ title: "登录 · " + siteName.value }));
const changedPassword = useRoute().query.changed === "1";
const { request, message } = useApi();
const email = ref(""),
  password = ref(""),
  code = ref(""),
  error = ref(""),
  busy = ref(false);
onMounted(async () => {
  try {
    const s = await request("/api/install/status");
    if (!s.installed) await navigateTo("/install");
  } catch (e) {
    error.value = message(e);
  }
});
async function login() {
  busy.value = true;
  error.value = "";
  try {
    await request("/api/auth/login", {
      method: "POST",
      body: { email: email.value, password: password.value, code: code.value },
    });
    password.value = "";
    const s = await request("/api/auth/session");
    await navigateTo(
      !s.menus?.length
        ? "/account"
        : s.mfaRequired && !s.mfaVerified
          ? staffPath("/admin/security")
          : staffPath("/admin"),
    );
  } catch (e) {
    error.value = message(e);
  } finally {
    busy.value = false;
  }
}
</script>
<template>
  <div class="login-page auth-page">
    <div class="login-story">
      <div>
        <div class="eyebrow">{{ siteName }}</div>
        <h1>{{ siteSlogan || siteName }}</h1>
        <p>管理应用、内容与服务。</p>
      </div>
      <span class="login-decoration">✳</span>
    </div>
    <div class="login-form">
      <NuxtLink to="/" class="quiet-link">← 返回网站</NuxtLink>
      <h2>欢迎回来</h2>
      <p class="muted">登录管理后台，继续你的创造。</p>
      <el-alert
        v-if="changedPassword"
        title="密码已更新，请重新登录"
        type="success"
        :closable="false"
      />
      <el-form
        label-position="right"
        label-width="128px"
        @submit.prevent="login"
      >
        <div v-if="error" class="alert error" role="alert">{{ error }}</div>
        <el-form-item label="邮箱"
          ><el-input
            v-model="email"
            type="email"
            autocomplete="username"
            required
            placeholder="your@email.com" /></el-form-item
        ><el-form-item label="密码"
          ><el-input
            v-model="password"
            type="password"
            autocomplete="current-password"
            required /></el-form-item
        ><el-form-item label="验证器动态码"
          ><el-input
            v-model="code"
            inputmode="numeric"
            pattern="[0-9]{6}"
            maxlength="6"
            autocomplete="one-time-code"
            placeholder="首次登录无需填写，随后绑定验证器" /></el-form-item
        ><el-button
          native-type="submit"
          type="primary"
          class="full-width"
          :disabled="busy"
        >
          {{ busy ? "正在登录…" : "登录后台" }} →
        </el-button>
      </el-form>
      <nav class="auth-actions" aria-label="账户入口">
        <NuxtLink to="/account-access">用户登录</NuxtLink>
        <NuxtLink v-if="registrationOpen" to="/account-access?action=register"
          >注册</NuxtLink
        >
        <NuxtLink to="/account-access?action=forgot">找回密码</NuxtLink>
      </nav>
      <p class="field-help">账户入口支持邮箱验证与找回密码。</p>
    </div>
  </div>
</template>
