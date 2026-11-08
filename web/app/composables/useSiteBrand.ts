export function useSiteBrand() {
  const site = useState<Record<string, string>>("site-settings", () => ({}));
  const siteName = computed(() => site.value["site.name"] || "微云湾");
  const siteSlogan = computed(
    () => site.value["site.slogan"] ?? "以技术为舟，向未来远航",
  );
  const registrationOpen = computed(
    () => site.value["account.registration_open"] === "true",
  );
  return { site, siteName, siteSlogan, registrationOpen };
}
