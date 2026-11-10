<script setup lang="ts">
/** 安装页面只向受安装码保护的接口发送数据库资料；成功后立即清空密码与安装码，不存localStorage。 */
definePageMeta({ layout: false });
const { request, message } = useApi();
const busy = ref(false),
  error = ref(""),
  notice = ref(""),
  installed = ref(false),
  externalDatabase = ref(false),
  staffLoginPath = ref("");
const production = ref(false);
const token = ref(""),
  email = ref(""),
  password = ref("");
const connection = reactive({
  engine: "H2",
  host: "localhost",
  port: 3306,
  database: "aiwyw",
  username: "",
  password: "",
});
onMounted(async () => {
  try {
    const s = await request("/api/install/status");
    installed.value = s.installed;
    externalDatabase.value = !!s.externalDatabase;
    production.value = s.production;
    if (s.production) connection.engine = "MYSQL";
  } catch (e) {
    error.value = message(e);
  }
});
async function testConnection() {
  busy.value = true;
  error.value = "";
  notice.value = "";
  try {
    await request("/api/install/test-database", {
      method: "POST",
      headers: { "X-Install-Token": token.value },
      body: connection,
    });
    notice.value = "连接测试成功";
  } catch (e) {
    error.value = message(e);
  } finally {
    busy.value = false;
  }
}
async function install() {
  busy.value = true;
  error.value = "";
  try {
    const result = await request<{ staffLoginPath: string }>("/api/install", {
      method: "POST",
      headers: { "X-Install-Token": token.value },
      body: {
        connection: externalDatabase.value ? null : connection,
        email: email.value,
        password: password.value,
      },
    });
    password.value = "";
    token.value = "";
    staffLoginPath.value = result.staffLoginPath;
    installed.value = true;
  } catch (e) {
    error.value = message(e);
  } finally {
    busy.value = false;
  }
}
</script>
<template>
  <div class="install-page">
    <NuxtLink to="/" class="brand"
      ><img
        src="/images/weiyunwan.svg"
        width="38"
        height="38"
        alt=""
      />微云湾</NuxtLink
    >
    <div class="install-card">
      <div class="eyebrow">WELCOME ABOARD</div>
      <h1>开始搭建你的工具商店</h1>
      <p class="muted">通过页面完成初始化，无需手工编辑配置文件。</p>
      <div v-if="installed" class="success-panel">
        <h2>安装已完成</h2>
        <p>请登录并绑定验证器，进入管理后台。</p>
        <a v-if="staffLoginPath" :href="staffLoginPath" class="button primary"
          >前往登录 →</a
        >
        <p v-else>请使用安装完成时保存的专属后台地址。</p>
      </div>
      <el-form
        label-position="right"
        label-width="128px"
        v-else
        @submit.prevent="install"
      >
        <div v-if="error" class="alert error" role="alert">{{ error }}</div>
        <div v-if="notice" class="alert success">{{ notice }}</div>
        <el-form-item label="一次性安装码"
          ><el-input
            v-model="token"
            required
            type="password"
            autocomplete="off"
            placeholder="从服务首次启动提示中复制"
        /></el-form-item>
        <div class="form-section-title">01 数据库</div>
        <p v-if="externalDatabase" class="field-help">
          正在使用 config/application.properties 中的 MySQL 连接。安装码位于
          security/install.token。
        </p>
        <template v-else>
          <el-form-item label="数据库类型"
            ><el-select v-model="connection.engine" aria-label="数据库类型">
              <el-option
                v-if="!production"
                value="H2"
                label="本地数据库 · 开发与体验"
              />
              <el-option value="MYSQL" label="MySQL · 生产环境" /> </el-select
          ></el-form-item>
          <p v-if="connection.engine === 'H2'" class="field-help">
            本地模式仅用于开发和体验，数据保存在服务数据目录。正式部署请在首次安装时选择空的
            MySQL 数据库。
          </p>
          <template v-else
            ><div class="form-grid">
              <el-form-item label="主机"
                ><el-input v-model="connection.host" required /></el-form-item
              ><el-form-item label="端口"
                ><el-input-number
                  v-model="connection.port"
                  :min="1"
                  :max="65535"
                  required /></el-form-item
              ><el-form-item label="数据库名"
                ><el-input
                  v-model="connection.database"
                  required
                  pattern="[a-zA-Z0-9_]+" /></el-form-item
              ><el-form-item label="账号"
                ><el-input
                  v-model="connection.username"
                  required
                  autocomplete="off"
              /></el-form-item>
            </div>
            <el-form-item label="数据库密码"
              ><el-input
                v-model="connection.password"
                type="password"
                autocomplete="new-password"
            /></el-form-item>
            <p class="field-help">
              MySQL 连接要求 TLS。数据库需提前创建并授予当前账号建表权限。
            </p></template
          ><el-button
            class="button secondary"
            native-type="button"
            :disabled="busy || !token"
            @click="testConnection"
          >
            测试数据库连接
          </el-button>
        </template>
        <div class="form-section-title">02 管理员账户</div>
        <el-form-item label="邮箱"
          ><el-input
            v-model="email"
            type="email"
            autocomplete="username"
            required /></el-form-item
        ><el-form-item label="密码"
          ><el-input
            v-model="password"
            type="password"
            minlength="12"
            maxlength="128"
            autocomplete="new-password"
            required
            placeholder="至少12位" /></el-form-item
        ><el-button
          type="primary"
          native-type="submit"
          class="full-width"
          :disabled="busy"
        >
          {{ busy ? "正在处理…" : "完成安装" }} →
        </el-button>
        <p class="field-help">
          安装成功后入口自动锁定。数据库密码由系统加密保存；请备份整个服务数据目录。
        </p>
      </el-form>
    </div>
  </div>
</template>
