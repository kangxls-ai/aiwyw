<script setup lang="ts">
/** API公开介绍只读取已启用服务的公开字段，上游地址、请求头与凭据不进入浏览器。 */
definePageMeta({ layout: "catalog" });
import "~/assets/element-admin.css";
import { decimalUnits } from "~/utils/commerce";
const { t } = useLanguage(),
  { request } = useApi(),
  route = useRoute();
const site = useState<Record<string, string>>("site-settings", () => ({}));
const data = await request<any>("/api/public/apis");
const api = data.apis.find(
  (item: any) => item.slug === String(route.params.slug),
);
if (!api)
  throw createError({ statusCode: 404, statusMessage: "API not found" });
const plans = data.plans.filter((p: any) => p.api_id === api.id);
const title = computed(() => t(api.name, api.name_en || api.name));
useHead(() => ({
  title: title.value + " · " + (site.value["site.name"] || "AI万有网"),
  meta: [
    {
      name: "description",
      content: t(api.description, api.description_en || api.description),
    },
  ],
}));
</script>
<template>
  <main class="directory-width public-detail">
    <nav class="public-breadcrumb" :aria-label="t('当前位置', 'Breadcrumb')">
      <NuxtLink to="/">{{ t("首页", "Home") }}</NuxtLink
      ><span>/</span
      ><NuxtLink to="/api-services">{{
        t("API 服务", "API services")
      }}</NuxtLink
      ><span>/</span><span aria-current="page">{{ title }}</span>
    </nav>
    <el-card shadow="never"
      ><h1>{{ title }}</h1>
      <p class="api-description">
        {{ t(api.description, api.description_en || api.description) }}
      </p>
      <p>
        {{ t("成功调用单价", "Price per successful call") }}：{{
          decimalUnits(api.price_units)
        }}
        {{ t("鼓励值", "encouragement") }}
      </p>
      <h2>{{ t("平台调用地址", "Platform endpoint") }}</h2>
      <pre>
POST https://{{ site["site.domain"] || "www.aiwyw.com" }}/api/v1/invoke/{{
          api.slug
        }}
Authorization: Bearer &lt;YOUR_KEY&gt;
Idempotency-Key: &lt;UNIQUE_REQUEST_ID&gt;
Content-Type: application/json</pre
      >
      <p>
        {{
          t(
            "使用平台Key提交接口要求的JSON参数。仅成功调用计费；具体权限、限速及套餐以账户配置为准。",
            "Send the documented JSON input with your platform key. Only successful calls are billed; key permissions, rate limits and plans apply.",
          )
        }}
      </p>
      <NuxtLink
        class="api-manage-link"
        :to="{ path: '/api-services', query: { api: api.slug } }"
        >{{
          t(
            "管理Key、订阅与在线调试",
            "Manage keys, subscriptions and debugging",
          )
        }}</NuxtLink
      ></el-card
    >
    <el-card v-if="plans.length" shadow="never" class="detail-plans"
      ><h2>{{ t("可选套餐", "Available plans") }}</h2>
      <div v-for="plan in plans" :key="plan.id">
        <h3>{{ plan.name }}</h3>
        <p>
          {{ decimalUnits(plan.price_units) }} {{ t("鼓励值", "encouragement") }} ·
          {{ plan.quota }} {{ t("次", "calls") }} · {{ plan.days }}
          {{ t("天", "days") }}
        </p>
      </div></el-card
    >
  </main>
</template>
<style scoped>
.api-manage-link {
  display: inline-flex;
  align-items: center;
  background: #8068cb;
  color: #fff;
  border: 1px solid #8068cb;
  border-radius: 8px;
  padding: 10px 18px;
  margin-top: 14px;
  font-size: 14px;
}
.api-description {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.public-detail pre {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  background: #f5f6fb;
  padding: 20px;
  border-radius: 8px;
}
.detail-plans {
  margin-top: 24px;
}
.public-detail h1 {
  margin: 0 0 18px;
}
.public-detail h2 {
  margin: 24px 0 12px;
}
</style>
