<script setup lang="ts">
/** SEO运营只允许写入公开描述与索引开关，不借此开放系统密钥。 */
const { request, message } = useApi(),
  { dirty, registerSave } = useManagementTabs();
const entries = ref<any[]>([]),
  values = reactive<Record<string, string>>({}),
  published = ref(0),
  error = ref(""),
  notice = ref(""),
  base = ref(""),
  busy = ref(false);
async function load() {
  try {
    const data = await request<any>("/api/admin/seo");
    entries.value = data.settings;
    published.value = data.published;
    for (const e of entries.value) values[e.definition.key] = e.value;
    base.value = JSON.stringify(values);
    dirty.value.seo = false;
  } catch (e) {
    error.value = message(e);
  }
}
watch(
  () => JSON.stringify(values),
  (v) => (dirty.value.seo = v !== base.value),
);
async function save() {
  const changes = entries.value
    .filter((e) => e.value !== values[e.definition.key])
    .map((e) => ({
      key: e.definition.key,
      value: values[e.definition.key],
      revision: Number(e.revision),
      clear: false,
    }));
  if (!changes.length) return true;
  busy.value = true;
  try {
    await request("/api/admin/seo", { method: "PUT", body: { changes } });
    await load();
    notice.value =
      "SEO设置已保存，站点地图与robots即时使用新设置；既有静态正文资料须重新发布后更新。";
    return true;
  } catch (e) {
    error.value = message(e);
    return false;
  } finally {
    busy.value = false;
  }
}
let unregister: (() => void) | undefined;
onMounted(() => {
  unregister = registerSave("seo", save);
  load();
});
onBeforeUnmount(() => unregister?.());
</script>
<template>
  <section>
    <div class="management-panel-heading">
      <div>
        <h1>SEO 与多语言</h1>
        <p>
          已发布内容
          {{
            published
          }}
          篇。页面包含canonical、语言标记和结构化正文，收录结果由搜索引擎决定。
        </p>
      </div>
    </div>
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
    /><el-card shadow="never"
      ><el-form label-position="right" label-width="180px"
        ><el-form-item
          v-for="e in entries"
          :key="e.definition.key"
          :label="e.definition.label"
          ><el-switch
            v-if="e.definition.type === 'boolean'"
            v-model="values[e.definition.key]"
            active-value="true"
            inactive-value="false" /><el-input
            v-else
            v-model="values[e.definition.key]"
            type="textarea"
            maxlength="500" /></el-form-item
        ><el-form-item
          ><el-button type="primary" :loading="busy" @click="save"
            >保存SEO设置</el-button
          ></el-form-item
        ></el-form
      >
      <div class="admin-table-actions">
        <a href="/sitemap.xml" target="_blank">查看站点地图</a
        ><a href="/robots.txt" target="_blank">查看robots</a
        ><NuxtLink to="/articles">浏览已发布内容</NuxtLink>
      </div></el-card
    >
  </section>
</template>
