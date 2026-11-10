<script setup lang="ts">
// 复用用户认可的样式，不加载原型钱包、示例数据或说明文件下载逻辑。
import "~/assets/catalog-base.css";
import "~/assets/catalog.css";
useHead({ link: [{ rel: "stylesheet", href: "/site-shell.css?v=2" }] });
import zhCn from "element-plus/es/locale/lang/zh-cn";
import en from "element-plus/es/locale/lang/en";
const { catalog, deliveryError, error: navigationError, load } = useCatalog(),
  { t, toggle, zh } = useLanguage();
const { siteName, siteSlogan } = useSiteBrand();
const route = useRoute();
const staticDelivery = useRuntimeConfig().public.staticDelivery;
// 所有前台页面复用可维护导航；API显隐来自栏目配置，服务端保证排在末尾。
if (!catalog.value.sections.length) await load(true);
if (import.meta.server && navigationError.value) {
  const event = useRequestEvent();
  if (event) event.context.publicSiteUnavailable = true;
}
const changed = useState("catalog-changed", () => 0);
watch(changed, () => load(true));
onMounted(() => {
  if (!catalog.value.sections.length) load(true);
});
</script>
<template>
  <el-config-provider :locale="zh ? zhCn : en">
    <div class="directory-preview">
      <header class="directory-header public-site-header">
        <div class="public-site-width public-site-header-inner">
          <NuxtLink class="public-site-brand" to="/" :external="staticDelivery">
            <img
              class="public-site-logo directory-brand-logo"
              src="/images/weiyunwan.svg"
              width="48"
              height="48"
              alt=""
            />
            <span
              >{{ siteName
              }}<small v-if="siteSlogan">{{ siteSlogan }}</small></span
            >
          </NuxtLink>
          <nav
            class="public-site-nav"
            :aria-label="t('首页导航', 'Main navigation')"
          >
            <NuxtLink
              to="/series"
              :external="staticDelivery"
              :aria-current="
                route.path.startsWith('/series') ? 'page' : undefined
              "
              >{{ t("专栏", "Series") }}</NuxtLink
            >
            <NuxtLink
              v-for="section in catalog.sections"
              :key="section.id"
              :aria-current="
                route.path === '/sections/' + section.id ||
                route.path === section.href
                  ? 'page'
                  : undefined
              "
              :to="
                section.kind === 'APPLICATIONS'
                  ? '/sections/' + section.id
                  : section.href
              "
              >{{
                t(section.titleZh, section.titleEn || section.titleZh)
              }}</NuxtLink
            >
          </nav>
          <button class="catalog-language" @click="toggle">
            {{ zh ? "English" : "中文" }}
          </button>
        </div>
      </header>
      <slot />
      <PublicSiteFooter />
      <div v-if="deliveryError" class="catalog-feedback" role="alert">
        <span>{{ deliveryError }}</span
        ><button :aria-label="t('关闭', 'Close')" @click="deliveryError = ''">
          ×
        </button>
      </div>
    </div>
  </el-config-provider>
</template>
<style scoped>
.directory-header nav a[aria-current="page"] {
  color: #755db2;
  box-shadow: 0 2px 0 #ad9cd6;
}
</style>
