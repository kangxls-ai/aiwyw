import type { PublicCatalog, CatalogProduct } from "~/types/catalog";

/** SSR读取公开目录；正式页面没有演示商品回退，接口失败必须展示可重试状态。 */
export function useCatalog() {
  const catalog = useState<PublicCatalog>("public-catalog", () => ({
    products: [],
    sections: [],
  }));
  const error = useState("catalog-error", () => ""),
    deliveryError = useState("delivery-error", () => ""),
    busy = useState("delivery-busy", () => false);
  const { request, message } = useApi();
  async function load(summary = false) {
    try {
      catalog.value = await request<PublicCatalog>("/api/public/catalog", {
        query: { summary },
      });
      error.value = "";
    } catch (e) {
      error.value = message(e);
    }
  }
  /** 只在服务端确认交付成功后跳转；按钮点击本身不累加下载次数，也不本机扣鼓励值。 */
  async function download(product: CatalogProduct) {
    if (
      product.content.type === "API" ||
      product.content.deliveryMode === "API"
    ) {
      await navigateTo({ path: "/api-services", query: { api: product.slug } });
      return;
    }
    if (product.content.pricePoints > 0) {
      await navigateTo({ path: "/checkout", query: { slug: product.slug } });
      return;
    }
    if (busy.value) return;
    busy.value = true;
    deliveryError.value = "";
    try {
      const result = await request<{ url: string }>(
        `/api/public/catalog/${product.slug}/download`,
        { method: "POST" },
      );
      window.location.assign(result.url);
    } catch (e) {
      deliveryError.value = message(e);
    } finally {
      busy.value = false;
    }
  }
  return { catalog, error, deliveryError, busy, load, download };
}
