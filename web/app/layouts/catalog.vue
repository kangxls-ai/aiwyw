<script setup lang="ts">
// 复用用户认可的样式，不加载原型钱包、示例数据或说明文件下载逻辑。
import "~/assets/catalog-base.css";
import "~/assets/catalog.css";
const { catalog, deliveryError } = useCatalog(),
  { t, toggle, zh } = useLanguage();
const site = useState<Record<string, string>>("site-settings", () => ({}));
</script>
<template>
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
            :to="{ path: '/apps', query: { section: section.id } }"
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
      <div>
        <strong>{{ site["site.name"] || "AI万有网" }}</strong
        ><span>{{
          t("实用工具，触手可及。", "Useful tools, within reach.")
        }}</span>
      </div>
      <span
        >{{ site["site.domain"] || "www.aiwyw.com" }}<br />{{
          site["site.filing"]
        }}</span
      >
    </footer>
    <div v-if="deliveryError" class="catalog-feedback" role="alert">
      <span>{{ deliveryError }}</span
      ><button :aria-label="t('关闭', 'Close')" @click="deliveryError = ''">
        ×
      </button>
    </div>
  </div>
</template>
