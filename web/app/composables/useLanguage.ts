/** 语言优先级：已保存Cookie > 浏览器首选 > 后台回退语言；Cookie保留一年，SSR与浏览器读取一致。 */
export function useLanguage() {
  const language = useCookie<"zh" | "en">("aiwyw_language", {
    maxAge: 365 * 86400,
    sameSite: "lax",
  });
  if (!language.value) {
    const browser = import.meta.server
      ? useRequestHeaders(["accept-language"])["accept-language"]
      : navigator.language;
    const fallback =
      useState<Record<string, string>>("site-settings", () => ({})).value[
        "site.fallback_language"
      ] === "zh"
        ? "zh"
        : "en";
    language.value = browser?.toLowerCase().startsWith("zh")
      ? "zh"
      : browser?.toLowerCase().startsWith("en")
        ? "en"
        : fallback;
  }
  const zh = computed(() => language.value === "zh");
  const t = (cn: string, en: string) => (zh.value ? cn : en);
  const toggle = () => {
    language.value = zh.value ? "en" : "zh";
  };
  return { language, zh, t, toggle };
}
