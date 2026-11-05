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
const entries = ref<Entry[]>([]),
  values = reactive<Record<string, string>>({}),
  clears = reactive<Record<string, boolean>>({});
const group = ref("网站信息"),
  error = ref(""),
  notice = ref(""),
  loading = ref(true),
  saving = ref(false),
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
  网站信息: "让用户更好地认识你的商店。",
  首发商品: "维护 markdo 的展示资料，准备好后再发布。",
  充值收款: "当前仅使用模拟充值，真实付款码可预先上传保存。",
  邮件服务: "保存服务商资料后，可发送一封测试邮件。",
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
    return true;
  } catch (e) {
    error.value = message(e);
    return false;
  } finally {
    saving.value = false;
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
    const result = await request<{ url: string }>("/api/admin/assets/payment", {
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
  url.startsWith("/api/public/assets/")
    ? url.replace("/api/public/assets/", "/api/admin/assets/")
    : url;
async function testMail() {
  saving.value = true;
  error.value = "";
  notice.value = "";
  try {
    await request("/api/admin/mail/test", { method: "POST" });
    notice.value = "测试邮件已发送至当前管理员邮箱";
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
    <div v-loading="loading" class="settings-layout">
      <aside class="settings-groups">
        <NuxtLink to="/admin/workspace?view=sections">首页栏目 →</NuxtLink
        ><NuxtLink to="/admin/workspace?view=storage">存储管理 →</NuxtLink
        ><NuxtLink to="/admin/workspace?view=packages">优惠积分包 →</NuxtLink
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
                  :label="v"
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
        <el-button
          v-if="!filter && group === '邮件服务'"
          :disabled="saving || !!changes.length"
          @click="testMail"
          >发送测试邮件</el-button
        >
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
