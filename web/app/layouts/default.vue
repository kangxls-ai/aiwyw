<script setup lang="ts">
import en from "element-plus/es/locale/lang/en.mjs";
import zhCn from "element-plus/es/locale/lang/zh-cn.mjs";
/** 身份页公共布局；站点配置取公开白名单，不持有后台密钥。 */
const { t, toggle, zh } = useLanguage();
const { data: site } = await useFetch<Record<string, string>>(
  "/api/public/site",
  { default: () => ({}) },
);
useHead(() => ({ htmlAttrs: { lang: zh.value ? "zh-CN" : "en" } }));
</script>
<template>
  <el-config-provider :locale="zh ? zhCn : en"
    ><div class="site-shell">
      <header class="site-header wrap">
        <NuxtLink to="/" class="brand"
          ><span class="brand-mark">万</span
          ><span
            >{{ site?.["site.name"] || "AI万有网"
            }}<small>TOOLS FOR YOUR NEXT IDEA</small></span
          ></NuxtLink
        >
        <nav aria-label="主导航">
          <NuxtLink to="/apps">{{ t("探索工具", "Explore") }}</NuxtLink
          ><NuxtLink to="/products/markdo"
            >markdo <span class="tiny-dot" /></NuxtLink
          ><button class="text-button" @click="toggle">
            {{ zh ? "EN" : "中文" }}</button
          ><NuxtLink to="/account">{{ t("我的账户", "My account") }}</NuxtLink
          ><NuxtLink class="nav-admin" to="/admin"
            >{{ t("管理后台", "Admin") }} ↗</NuxtLink
          >
        </nav>
      </header>
      <main><slot /></main>
      <footer class="site-footer wrap">
        <div>
          <strong>{{ site?.["site.name"] || "AI万有网" }}</strong>
          <p>
            {{
              t(
                "独立开发，小而美，持续创造。",
                "Independently made. Thoughtfully built.",
              )
            }}
          </p>
        </div>
        <div class="footer-right">
          <span>{{ site?.["site.domain"] || "www.aiwyw.com" }}</span
          ><a
            v-if="site?.['site.filing']"
            href="https://beian.miit.gov.cn"
            target="_blank"
            rel="noopener"
            >{{ site["site.filing"] }}</a
          ><span>{{ t("简体中文 / English", "English / 简体中文") }}</span>
        </div>
      </footer>
    </div></el-config-provider
  >
</template>
