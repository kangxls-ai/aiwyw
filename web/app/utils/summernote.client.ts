/** Summernote Lite只在浏览器初始化，共享同一个jQuery实例；不从CDN载入脚本或字体。 */
let loading: Promise<any> | null = null;
export function loadSummernote() {
  if (!loading)
    loading = (async () => {
      const module = await import("jquery");
      const jquery = module.default;
      (window as any).jQuery = jquery;
      (window as any).$ = jquery;
      await import("summernote/dist/summernote-lite.js");
      await import("summernote/dist/lang/summernote-zh-CN.js");
      return jquery;
    })();
  return loading;
}
