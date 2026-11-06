<script setup lang="ts">
definePageMeta({ layout: "catalog" });
/** 用户先核对价格与发布版本，再显式确认兑换；服务端重新校验，拒绝静默采用更新价格。 */
import "~/assets/element-admin.css";
const { t } = useLanguage(),
  { request, message } = useApi(),
  route = useRoute();
const quote = ref<any>(null),
  busy = ref(false),
  error = ref(""),
  key = ref("");
async function load() {
  try {
    quote.value = await request(
      "/api/member/quote/" + encodeURIComponent(String(route.query.slug || "")),
    );
  } catch (e: any) {
    if (e.status === 401)
      await navigateTo(
        "/account-access?return=" + encodeURIComponent(route.fullPath),
      );
    else error.value = message(e);
  }
}
async function redeem() {
  if (!quote.value) return;
  busy.value = true;
  error.value = "";
  try {
    key.value ||= crypto.randomUUID();
    const result = quote.value.owned
      ? { id: quote.value.purchaseId }
      : await request<{ id: string }>("/api/member/purchase", {
          method: "POST",
          body: {
            slug: quote.value.product.slug,
            publicationId: quote.value.product.publicationId,
            requestKey: key.value,
          },
        });
    await navigateTo("/account?resource=" + result.id);
  } catch (e) {
    error.value = message(e);
    await load();
  } finally {
    busy.value = false;
  }
}
onMounted(load);
</script>
<template>
  <el-card class="account-access" shadow="never"
    ><h1>{{ t("确认鼓励值兑换", "Confirm redemption") }}</h1>
    <el-alert
      v-if="error"
      :title="error"
      type="error"
      :closable="false"
    /><template v-if="quote"
      ><h2>{{ quote.product.content.nameZh }}</h2>
      <p>{{ quote.product.content.summaryZh }}</p>
      <el-descriptions :column="1" border
        ><el-descriptions-item :label="t('版本', 'Version')">{{
          quote.product.content.version
        }}</el-descriptions-item
        ><el-descriptions-item :label="t('价格', 'Price')"
          >{{ quote.product.content.pricePoints }}
          {{ t("鼓励值", "encouragement") }}</el-descriptions-item
        ><el-descriptions-item :label="t('可用鼓励值', 'Available encouragement')">{{
          quote.balance
        }}</el-descriptions-item></el-descriptions
      >
      <p>
        {{
          t(
            "使用模拟账本鼓励值兑换。已拥有同一大版本不会重复扣分。",
            "Uses simulated ledger encouragement. Owned major versions are not charged twice.",
          )
        }}
      </p>
      <el-button type="primary" :loading="busy" @click="redeem">{{
        quote.owned
          ? t("进入我的资源", "Open my resources")
          : t("确认兑换", "Confirm redemption")
      }}</el-button></template
    ><NuxtLink to="/account">{{
      t("我的账户 / 捐赠", "My account / Donation")
    }}</NuxtLink></el-card
  >
</template>
