<script setup lang="ts">
// 复用用户认可的样式，不加载原型钱包、示例数据或说明文件下载逻辑。
import "~/assets/catalog-base.css";
import "~/assets/catalog.css";
useHead({ link: [{ rel: "stylesheet", href: "/site-shell.css?v=1" }] });
import zhCn from "element-plus/es/locale/lang/zh-cn";
import en from "element-plus/es/locale/lang/en";
const { catalog, deliveryError, load } = useCatalog(),
  { t, toggle, zh } = useLanguage();
const site = useState<Record<string, string>>("site-settings", () => ({}));
const route = useRoute();
const currentSection = computed(() =>
  catalog.value.sections.some((s) => s.id === route.query.section)
    ? String(route.query.section)
    : catalog.value.sections[0]?.id,
);
// 所有前台页面复用可维护导航；API显隐来自栏目配置，服务端保证排在末尾。
if (!catalog.value.sections.length) await load(true);
const changed = useState("catalog-changed", () => 0);
watch(changed, () => load(true));
onMounted(() => {
  if (!catalog.value.sections.length) load(true);
});
</script>
<template>
  <el-config-provider :locale="zh ? zhCn : en">
    <div class="directory-preview">
      <header class="directory-header">
        <div class="directory-width directory-header-inner">
          <NuxtLink class="directory-brand" to="/"
            ><span class="directory-brand-logo">万<span /></span
            ><span
              >{{ site["site.name"] || "AI万有网"
              }}<small>{{
                t("发现工具的更多可能", "Discover useful tools")
              }}</small></span
            ></NuxtLink
          >
          <nav :aria-label="t('首页导航', 'Main navigation')">
            <NuxtLink
              v-for="section in catalog.sections"
              :key="section.id"
              :aria-current="
                route.path === '/' && currentSection === section.id
                  ? 'page'
                  : undefined
              "
              :to="
                section.kind === 'APPLICATIONS'
                  ? {
                      path: '/',
                      query: { section: section.id },
                    }
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
      <footer class="directory-footer directory-width">
        <NuxtLink to="/support">{{
          t("联系客服与反馈", "Support and feedback")
        }}</NuxtLink>
        <p class="site-filing">
          {{ site["site.filing"]?.trim() || "模拟ICP备00000000号（占位）" }}
        </p>
        <span>{{ site["site.domain"] || "www.aiwyw.com" }}</span>
      </footer>
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
.directory-footer {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  text-align: center;
}
.site-filing {
  margin: 0;
  color: #59627c;
  font-size: 14px;
}
</style>
