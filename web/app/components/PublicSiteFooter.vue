<script setup lang="ts">
const { site } = useSiteBrand();
const { t } = useLanguage();
const securityNumber = computed(
  () =>
    site.value["site.security_filing"]?.match(/(?:^|\D)(\d{14})(?!\d)/)?.[1],
);
const filings = computed(() => [
  {
    value: site.value["site.filing"]?.trim(),
    href: "https://beian.miit.gov.cn/",
    placeholder: t("工信部 ICP 备案号待填写", "MIIT ICP filing pending"),
  },
  {
    value: site.value["site.security_filing"]?.trim(),
    href: securityNumber.value
      ? "https://beian.mps.gov.cn/#/query/webSearch?code=" +
        securityNumber.value
      : "https://beian.mps.gov.cn/",
    icon: site.value["site.security_filing_icon"],
    placeholder: t("公安网安备案号待填写", "Public security filing pending"),
  },
]);
</script>

<template>
  <footer class="directory-footer public-site-width">
    <div class="site-filings">
      <template v-for="filing in filings" :key="filing.href">
        <a
          v-if="filing.value"
          :href="filing.href"
          target="_blank"
          rel="noopener noreferrer"
        >
          <img
            v-if="filing.icon"
            :src="filing.icon"
            width="16"
            height="16"
            alt=""
          />
          {{ filing.value }}
        </a>
        <span v-else>{{ filing.placeholder }}</span>
      </template>
    </div>
    <span>{{ site["site.domain"] || "www.aiwyw.com" }}</span>
  </footer>
</template>
