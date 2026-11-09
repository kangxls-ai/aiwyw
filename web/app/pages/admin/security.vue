<script setup lang="ts">
const { staffPath } = useStaffPaths();
/** MFA密钥仅在未绑定时展示；绑定结果由服务器核验，不能仅设置客户端状态绕过权限。 */
defineOptions({ name: "AdminSecurity" });
import "~/assets/auth.css";
import QRCode from "qrcode";
definePageMeta({ layout: "admin" });
const { request, message } = useApi();
const user = useState<any>("admin-user", () => null);
const secret = ref(""),
  qr = ref(""),
  code = ref(""),
  error = ref(""),
  busy = ref(false),
  done = ref(false);
async function enroll() {
  busy.value = true;
  error.value = "";
  try {
    const r = await request("/api/auth/mfa/enroll", { method: "POST" });
    secret.value = r.secret;
    qr.value = await QRCode.toDataURL(r.uri, { width: 220, margin: 1 });
  } catch (e) {
    error.value = message(e);
  } finally {
    busy.value = false;
  }
}
async function confirm() {
  busy.value = true;
  error.value = "";
  try {
    await request("/api/auth/mfa/confirm", {
      method: "POST",
      body: { code: code.value },
    });
    user.value = await request("/api/auth/session");
    secret.value = "";
    qr.value = "";
    done.value = true;
  } catch (e) {
    error.value = message(e);
  } finally {
    busy.value = false;
  }
}
</script>
<template>
  <div>
    <div class="page-heading">
      <div>
        <div class="eyebrow">ACCOUNT SECURITY</div>
        <h1>账户安全</h1>
        <p class="muted">管理员需完成双重验证，才能管理系统配置。</p>
      </div>
    </div>
    <div class="panel security-panel">
      <div v-if="error" class="alert error" role="alert">{{ error }}</div>
      <template v-if="user?.mfaPolicyEnabled === false"
        ><h2>双因素认证策略已关闭</h2>
        <p>
          当前按管理员配置使用密码登录。恢复策略后，管理员和财务角色会重新要求验证，已绑定的验证器资料仍保留。
        </p>
        <NuxtLink
          v-if="user?.menus?.includes('settings')"
          :to="staffPath('/admin/settings')"
          >进入配置管理</NuxtLink
        ></template
      ><template v-else-if="user?.mfaVerified || done"
        ><span class="check-dot">✓</span>
        <h2>双重验证已启用</h2>
        <p class="muted">
          后续登录需填写验证器的6位动态码。请妥善保存验证器备份。
        </p>
        <NuxtLink :to="staffPath('/admin/settings')" type="primary"
          >进入配置管理 →</NuxtLink
        ></template
      ><template v-else
        ><h2>绑定身份验证器</h2>
        <p class="muted">
          使用支持 TOTP
          的验证器扫码，或手动添加密钥，再输入动态码完成绑定。二维码和密钥仅在本页生成。
        </p>
        <el-button
          v-if="!secret"
          type="primary"
          :disabled="busy"
          @click="enroll"
        >
          开始绑定
        </el-button>
        <el-form
          label-position="right"
          label-width="128px"
          v-else
          @submit.prevent="confirm"
        >
          <div class="mfa-setup">
            <img :src="qr" alt="验证器绑定二维码" width="220" height="220" />
            <div>
              <label
                >手动添加密钥<code class="secret-code">{{
                  secret
                }}</code></label
              >
              <p class="field-help">请勿把此密钥发送给其他人。</p>
            </div>
          </div>
          <el-form-item label="6位动态码"
            ><el-input
              aria-label="6位动态码"
              v-model="code"
              inputmode="numeric"
              pattern="[0-9]{6}"
              maxlength="6"
              required
              autocomplete="one-time-code" /></el-form-item
          ><el-button type="primary" native-type="submit" :disabled="busy"
            >确认绑定</el-button
          >
        </el-form></template
      >
    </div>
    <AccountProfileSettings />
  </div>
</template>
