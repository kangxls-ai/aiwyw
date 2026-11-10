export function useStaffPaths() {
  const route = useRoute();
  function staffPath(path: string) {
    const entry = route.params.staffEntry;
    return typeof entry === "string" && /^[0-9a-f]{32}$/.test(entry)
      ? "/" + entry + path
      : path;
  }
  function staffApi(path: string) {
    if (
      /^\/api\/(?:admin(?:\/|$)|auth\/(?:login|logout|session|mfa\/[^/?]+)(?:[?]|$)|member\/(?:profile|nickname|password)(?:[?]|$)|support\/assets\/|member\/evidence\/)/.test(
        path,
      )
    )
      return staffPath(path);
    return path;
  }
  function draftImage(path: string) {
    const match =
      /^\/api\/public\/media\/((?:[0-9a-v]{26}|[0-9a-f-]{36}))$/.exec(path);
    return match
      ? staffApi("/api/admin/storage/assets/" + match[1] + "/content")
      : path;
  }
  return { staffPath, staffApi, draftImage };
}
