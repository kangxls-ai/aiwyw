<script setup lang="ts">
/** 配置表单基于服务器定义生成；敏感留空保持，明确勾选才清除。带revision整批保存，冲突时不能直接覆盖他人更改。 */
defineOptions({ name: "AdminSettings" });
definePageMeta({ layout: "admin" });
interface Definition {
  key: string;
  group: string;
  label: string;
  type: string;
  initial: string;
  secret: boolean;
  publicValue: boolean;
  help: string;
  min: number;
  max: number;
  options: string[];
}
interface Entry {
  definition: Definition;
  value: string;
  configured: boolean;
  revision: number;
}
const { request, message } = useApi();
const { site } = useSiteBrand();
const entries = ref<Entry[]>([]),
  values = reactive<Record<string, string>>({}),
  clears = reactive<Record<string, boolean>>({});
const route = useRoute();
const group = ref(String(route.query.group || "网站信息")),
  error = ref(""),
  notice = ref(""),
  loading = ref(true),
  saving = ref(false),
  publishing = ref(false),
  filter = ref("");
const groups = computed(() => [
  ...new Set(entries.value.map((e) => e.definition.group)),
]);
const visible = computed(() =>
  entries.value.filter((e) =>
    filter.value
      ? `${e.definition.label}${e.definition.key}${e.definition.help}`
          .toLowerCase()
          .includes(filter.value.toLowerCase())
      : e.definition.group === group.value,
  ),
);
const changes = computed(() =>
  entries.value.filter(
    (e) =>
      clears[e.definition.key] ||
      (e.definition.secret
        ? !!values[e.definition.key]
        : values[e.definition.key] !== e.value),
  ),
);
const managementDirty = useState<Record<string, boolean>>(
  "management-dirty",
  () => ({}),
);
watch(
  () => changes.value.length,
  (count) => {
    managementDirty.value.settings = count > 0;
  },
  { immediate: true },
);
const titles: Record<string, string> = {
  网站信息: "维护站点名称、备案和注册开关。保存公开配置后会发布首页与登录页。",
  首发商品: "维护 markdo 的展示资料，准备好后再发布。",
  捐赠收款: "当前仅使用模拟捐赠，真实付款码可预先上传保存。",
  邮件服务:
    "SMTP负责注册验证和密码找回邮件；POP3/IMAP只用于收信连接诊断，不能替代发信。",
  文件存储: "本地与对象存储配置在保存后用于新上传，历史资源仍保留原连接。",
};
async function load() {
  loading.value = true;
  error.value = "";
  try {
    entries.value = await request<Entry[]>("/api/admin/settings");
    for (const e of entries.value) {
      values[e.definition.key] = e.value;
      clears[e.definition.key] = false;
    }
  } catch (e: any) {
    if (e.status === 401) await navigateTo("/login");
    else if (e.status === 403) await navigateTo("/admin/security");
    else error.value = message(e);
  } finally {
    loading.value = false;
  }
}
onMounted(load);
async function save() {
  saving.value = true;
  error.value = "";
  notice.value = "";
  const publish = changes.value.some((e) => e.definition.publicValue);
  try {
    await request("/api/admin/settings", {
      method: "PUT",
      body: {
        changes: changes.value.map((e) => ({
          key: e.definition.key,
          value: values[e.definition.key],
          revision: e.revision,
          clear: !!clears[e.definition.key],
        })),
      },
    });
    await load();
    notice.value = "配置已保存";
    if (publish) await publishSite(true);
    return true;
  } catch (e) {
    error.value = message(e);
    return false;
  } finally {
    saving.value = false;
  }
}
async function publishSite(afterSave = false) {
  publishing.value = true;
  error.value = "";
  try {
    await request("/api/admin/site/publish", { method: "POST" });
    site.value = await request<Record<string, string>>("/api/public/site");
    notice.value = afterSave
      ? "配置已保存，首页与登录页已发布"
      : "首页与登录页已发布";
  } catch (e) {
    error.value = "页面发布失败，请重试。" + message(e);
  } finally {
    publishing.value = false;
  }
}
async function reload() {
  if (
    changes.value.length &&
    !window.confirm("重新加载将放弃尚未保存的修改，是否继续？")
  )
    return;
  notice.value = "";
  await load();
}
/** Element Plus只负责选文件，实际上传仍走已鉴权的统一HTTP边界。 */
async function uploadFile(file: File | undefined, key: string) {
  if (!file) return;
  try {
    const body = new FormData();
    body.append("file", file);
    const url =
      key === "site.security_filing_icon"
        ? "/api/admin/assets/filing"
        : "/api/admin/assets/payment";
    const result = await request<{ url: string }>(url, {
      method: "POST",
      body,
    });
    values[key] = result.url;
    notice.value = "图片已上传，点击保存修改后应用";
  } catch (e) {
    error.value = message(e);
  }
}
const preview = (url: string) =>
  /^\/api\/public\/(?:filing-)?assets\//.test(url)
    ? url.replace("/api/public/", "/api/admin/")
    : url;
const readiness = ref<any>(null),
  readinessBusy = ref(false);
async function checkProduction() {
  readinessBusy.value = true;
  try {
    readiness.value = await request("/api/admin/production/readiness");
  } catch (e) {
    error.value = message(e);
  } finally {
    readinessBusy.value = false;
  }
}
const mailResult = ref<any>(null),
  mailStatus = ref<any>(null);
watch(
  () => JSON.stringify(values),
  () => {
    mailResult.value = null;
  },
);
async function refreshMailStatus() {
  try {
    mailStatus.value = await request("/api/admin/mail/status");
  } catch {
    /* 主表错误独立展示，不覆盖用户输入。 */
  }
}
watch(
  group,
  (g) => {
    if (g === "邮件服务") refreshMailStatus();
  },
  { immediate: true },
);
function mailPreset(alternate = false) {
  Object.assign(values, {
    "mail.host": "smtp.qq.com",
    "mail.port": alternate ? "587" : "465",
    "mail.security": alternate ? "STARTTLS" : "TLS",
    "mail.pop3.host": "pop.qq.com",
    "mail.pop3.port": "995",
    "mail.imap.host": "imap.qq.com",
    "mail.imap.port": "993",
  });
  if (values["mail.username"] && !values["mail.from"])
    values["mail.from"] = values["mail.username"];
  notice.value =
    "已填入QQ方案；请填写完整邮箱账号和授权码，保存后测试。原授权码和启用状态保持不变。";
  mailResult.value = null;
}
async function testMail(protocol = "SMTP", send = false) {
  saving.value = true;
  error.value = "";
  notice.value = "";
  mailResult.value = null;
  try {
    mailResult.value = await request(
      send ? "/api/admin/mail/test" : "/api/admin/mail/check",
      { method: "POST", ...(send ? {} : { body: { protocol } }) },
    );
    await refreshMailStatus();
  } catch (e) {
    error.value = message(e);
  } finally {
    saving.value = false;
  }
}
async function testRedis() {
  saving.value = true;
  error.value = "";
  notice.value = "";
  try {
    await request("/api/admin/redis/check", { method: "POST" });
    notice.value = "Redis连接检查通过";
  } catch (e) {
    error.value = message(e);
  } finally {
    saving.value = false;
  }
}
onBeforeRouteLeave(
  (to) =>
    to.path.startsWith("/admin") ||
    !changes.value.length ||
    window.confirm("还有未保存的配置，确定离开吗？"),
);
const protect = (e: Entry) =>
  [
    "security.admin_mfa",
    "security.finance_mfa",
    "release.approval_required",
    "api.debug_billable",
    "api.overage_default",
  ].includes(e.definition.key);
const { registerSave } = useManagementTabs();
let unregister: (() => void) | undefined;
onMounted(() => {
  unregister = registerSave("settings", save);
});
onBeforeUnmount(() => unregister?.());
</script>
<template>
  <div>
    <div class="page-heading">
      <div>
        <div class="eyebrow">SYSTEM SETTINGS</div>
        <h1>配置管理</h1>
        <p class="muted">
          所有运营设置，一个入口。修改后保存，无需编辑配置文件。
        </p>
      </div>
      <div class="heading-actions">
        <el-button :loading="readinessBusy" @click="checkProduction"
          >生产上线检查</el-button
        >
        <el-button
          v-if="group === '网站信息'"
          :disabled="!!changes.length || saving || loading"
          :loading="publishing"
          @click="publishSite()"
          >发布首页与登录页</el-button
        >
        <el-button :disabled="saving || loading" @click="reload"
          >重新加载</el-button
        ><el-button
          type="primary"
          :disabled="!changes.length"
          :loading="saving"
          @click="save"
          >保存修改 <el-badge v-if="changes.length" :value="changes.length"
        /></el-button>
      </div>
    </div>
    <el-alert
      title="敏感信息加密保存，密码和密钥不显示原文。留空保留原值，明确勾选清除才删除。"
      type="info"
      :closable="false"
    />
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <div v-if="notice" class="alert success" role="status">{{ notice }}</div>
    <el-dialog
      :model-value="!!readiness"
      title="生产上线检查"
      width="min(960px,96vw)"
      @close="readiness = null"
      ><el-alert
        :title="
          readiness?.blocked
            ? '仍有上线阻断项，请按清单处理'
            : '自动检查无阻断项；仍须完成下列人工核验'
        "
        :type="readiness?.blocked ? 'warning' : 'info'"
        :closable="false"
      /><el-table :data="readiness?.checks || []"
        ><el-table-column
          prop="name"
          label="检查项目"
          width="180" /><el-table-column label="结果" width="100"
          ><template #default="{ row }"
            ><el-tag
              :type="
                row.state === 'PASS'
                  ? 'success'
                  : row.state === 'BLOCK'
                    ? 'danger'
                    : 'warning'
              "
              >{{
                row.state === "PASS"
                  ? "通过"
                  : row.state === "BLOCK"
                    ? "阻断"
                    : "待核验"
              }}</el-tag
            ></template
          ></el-table-column
        ><el-table-column
          prop="detail"
          label="处理说明"
          min-width="300" /></el-table
      ><template #footer
        ><el-button @click="readiness = null">关闭</el-button></template
      ></el-dialog
    >
    <div v-loading="loading" class="settings-layout">
      <aside class="settings-groups">
        <NuxtLink to="/admin/workspace?view=sections">首页栏目 →</NuxtLink
        ><NuxtLink to="/admin/workspace?view=storage">存储管理 →</NuxtLink
        ><NuxtLink to="/admin/workspace?view=packages">捐赠档位 →</NuxtLink
        ><NuxtLink to="/admin/workspace?view=licenses"
          >软件授权与换机 →</NuxtLink
        ><el-input
          v-model="filter"
          aria-label="搜索配置项"
          placeholder="搜索配置项…"
          clearable
        /><el-button
          v-for="g in groups"
          :key="g"
          :type="group === g && !filter ? 'primary' : 'default'"
          text
          @click="
            group = g;
            filter = '';
          "
          >{{ g }}</el-button
        >
      </aside>
      <el-card shadow="never"
        ><template #header
          ><h2>{{ filter ? "搜索结果" : group }}</h2>
          <p class="muted">
            {{ titles[group] || "按已确认业务规则维护配置。" }}
          </p></template
        >
        <el-form
          label-position="right"
          label-width="210px"
          @submit.prevent="save"
          ><el-form-item
            v-for="e in visible"
            :key="e.definition.key"
            :label="e.definition.label"
            :for="e.definition.key"
            ><div style="width: 100%">
              <el-switch
                v-if="e.definition.type === 'boolean'"
                :id="e.definition.key"
                v-model="values[e.definition.key]"
                active-value="true"
                inactive-value="false"
                :disabled="protect(e)"
                active-text="开启"
                inactive-text="关闭"
              />
              <el-select
                v-else-if="e.definition.type === 'select'"
                :id="e.definition.key"
                v-model="values[e.definition.key]"
                :aria-label="e.definition.label"
                ><el-option
                  v-for="v in e.definition.options"
                  :key="v"
                  :value="v"
                  :label="
                    e.definition.key === 'mail.security'
                      ? v === 'TLS'
                        ? 'SSL/TLS（通常465）'
                        : 'STARTTLS（通常587）'
                      : v
                  "
              /></el-select>
              <template v-else-if="e.definition.type === 'image'"
                ><el-image
                  v-if="values[e.definition.key]"
                  :src="preview(values[e.definition.key] || '')"
                  :alt="e.definition.label"
                  style="width: 150px; display: block"
                  fit="contain"
                /><el-upload
                  :auto-upload="false"
                  :show-file-list="false"
                  accept="image/png,image/jpeg"
                  :on-change="(file) => uploadFile(file.raw, e.definition.key)"
                  ><el-button
                    >选择{{ e.definition.label }}</el-button
                  ></el-upload
                ><el-button
                  v-if="
                    values[e.definition.key] &&
                    e.definition.key !== 'payment.mock_image'
                  "
                  link
                  type="danger"
                  @click="values[e.definition.key] = ''"
                  >移除</el-button
                ></template
              >
              <template v-else-if="e.definition.secret"
                ><el-input
                  :id="e.definition.key"
                  v-model="values[e.definition.key]"
                  type="password"
                  show-password
                  autocomplete="new-password"
                  :disabled="clears[e.definition.key]"
                  :placeholder="
                    e.configured ? '已设置，留空保持原值' : '尚未设置'
                  "
                /><el-checkbox
                  v-if="e.configured"
                  v-model="clears[e.definition.key]"
                  >明确清除已保存的值</el-checkbox
                ></template
              >
              <el-input-number
                v-else-if="e.definition.type === 'number'"
                :id="e.definition.key"
                :model-value="Number(values[e.definition.key])"
                @update:model-value="
                  (value) => (values[e.definition.key] = String(value ?? 0))
                "
                :min="e.definition.min"
                :max="e.definition.max"
                :precision="0"
                controls-position="right"
              />
              <el-input
                v-else
                :id="e.definition.key"
                v-model="values[e.definition.key]"
                :maxlength="e.definition.max"
                clearable
              />
              <p class="admin-muted">{{ e.definition.help }}</p>
            </div></el-form-item
          ></el-form
        ><el-empty
          v-if="!visible.length && !loading"
          description="没有匹配的配置项"
        />
        <div v-if="!filter && group === '邮件服务'" class="mail-testing">
          <el-alert
            title="QQ邮箱：先在邮箱网页开启协议并生成授权码；账号使用完整邮箱地址，发信邮箱与账号一致。网站发送验证邮件必须通过SMTP。"
            type="info"
            :closable="false"
          />
          <div class="heading-actions">
            <el-button :disabled="saving" @click="mailPreset()"
              >填入QQ方案（465 SSL/TLS）</el-button
            ><el-button :disabled="saving" @click="mailPreset(true)"
              >填入QQ备用方案（587 STARTTLS）</el-button
            >
          </div>
          <p class="admin-muted">
            POP3：pop.qq.com / 995 SSL/TLS；IMAP：imap.qq.com / 993
            SSL/TLS。三个协议共用上方邮箱账号和授权码；连接测试不读取或删除邮件。先保存修改再测试。
          </p>
          <div class="heading-actions">
            <el-button
              v-for="protocol in ['SMTP', 'POP3', 'IMAP']"
              :key="protocol"
              :disabled="saving || !!changes.length"
              @click="testMail(protocol)"
              >测试{{ protocol }}连接</el-button
            ><el-button
              type="primary"
              :disabled="saving || !!changes.length"
              @click="testMail('SMTP', true)"
              >发送测试邮件到我的邮箱</el-button
            >
          </div>
          <p v-if="saving" role="status">正在测试，请稍候…</p>
          <el-alert
            v-if="mailResult"
            :title="`${mailResult.protocol} · ${mailResult.code} · ${mailResult.elapsedMs}ms：${mailResult.message}`"
            :type="mailResult.success ? 'success' : 'error'"
            :closable="false"
          />
          <p v-if="mailStatus" class="admin-muted">
            自动投递：{{ mailStatus.enabled ? "已启用" : "未启用" }}；等待投递
            {{ mailStatus.pending }} 封。<span
              v-for="item in mailStatus.recentFailures"
              :key="item.last_error_code"
            >
              {{ item.last_error_code }}：{{ item.total }} 封。</span
            >配置修复并启用服务后队列按退避策略自动重试，最多每小时一次。
          </p>
          <a
            href="https://help.mail.qq.com/detail/106/985"
            target="_blank"
            rel="noopener"
            >QQ邮箱授权码说明 ↗</a
          >
        </div>
        <el-button
          v-if="!filter && group === '缓存与限流'"
          :disabled="saving || !!changes.length"
          @click="testRedis"
          >测试Redis连接</el-button
        >
      </el-card>
    </div>
  </div>
</template>
