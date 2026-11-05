/**
 * 页签稳定ID独立于菜单ID：不同应用各有编辑实例，关闭后真实卸载而不是遗留缓存。
 * 保存函数只保存在客户端内存，不放SSR可序列化状态；批量关闭先统一决策再移除页签。
 */
import { adminMenuItem, type AdminMenuItem } from "~/data/adminNavigation";
export interface ManagementTab extends AdminMenuItem {
  kind?: "product-editor";
  editorId?: string;
  productId?: string | null;
}
const saves = new Map<string, () => Promise<boolean>>();
let resolveClosing: ((value: boolean) => void) | null = null;
export function useManagementTabs() {
  const tabs = useState<ManagementTab[]>("management-tabs", () => [
    adminMenuItem("dashboard"),
  ]);
  const dirty = useState<Record<string, boolean>>(
    "management-dirty",
    () => ({}),
  );
  const revisions = useState<Record<string, number>>(
    "admin-page-revisions",
    () => ({}),
  );
  const closing = useState<string[]>("management-closing", () => []),
    closeBusy = useState("management-close-busy", () => false),
    closeError = useState("management-close-error", () => "");
  const route = useRoute();
  function descriptor(): ManagementTab {
    if (
      route.path === "/admin/workspace" &&
      route.query.view === "application-edit"
    ) {
      const editor = String(route.query.editor || route.query.product || "");
      if (!/^[a-f0-9-]{36}$/.test(editor)) return adminMenuItem("applications");
      const product = route.query.product ? String(route.query.product) : null;
      return {
        id: `editor-${editor}`,
        editorId: editor,
        productId: product,
        kind: "product-editor",
        label: product ? "编辑应用" : "新增应用",
        icon: "note",
        description: "维护应用资料与上传内容",
        route: route.fullPath,
      };
    }
    return adminMenuItem(
      route.path === "/admin"
        ? "dashboard"
        : route.path === "/admin/settings"
          ? "settings"
          : route.path === "/admin/audit"
            ? "audit"
            : route.path === "/admin/security"
              ? "security"
              : String(route.query.view || "dashboard"),
    );
  }
  const activeId = computed(() => descriptor().id);
  watch(
    () => route.fullPath,
    () => {
      const item = descriptor();
      if (!tabs.value.some((t) => t.id === item.id)) tabs.value.push(item);
    },
    { immediate: true },
  );
  async function open(id: string) {
    const item = tabs.value.find((t) => t.id === id) || adminMenuItem(id);
    await navigateTo(item.route);
  }
  async function openEditor(product?: {
    id: string;
    nameZh?: string;
    content?: { nameZh: string };
  }) {
    const existing =
      product &&
      tabs.value.find(
        (t) => t.kind === "product-editor" && t.productId === product.id,
      );
    if (existing) {
      await navigateTo(existing.route);
      return;
    }
    const editor = crypto.randomUUID(),
      id = `editor-${editor}`;
    const target = {
      path: "/admin/workspace",
      query: {
        view: "application-edit",
        editor,
        ...(product ? { product: product.id } : {}),
      },
    };
    const href = useRouter().resolve(target).fullPath;
    tabs.value.push({
      id,
      kind: "product-editor",
      editorId: editor,
      productId: product?.id || null,
      label: product
        ? `编辑：${product.nameZh || product.content?.nameZh || "应用"}`
        : "新增应用",
      icon: "note",
      route: href,
      description: "维护应用资料与上传内容",
    });
    await navigateTo(target);
  }
  async function updateEditor(id: string, productId: string, name: string) {
    const item = tabs.value.find((t) => t.id === id);
    if (!item) return;
    item.productId = productId;
    item.label = `编辑：${name}`;
    item.route = useRouter().resolve({
      path: "/admin/workspace",
      query: {
        view: "application-edit",
        editor: item.editorId,
        product: productId,
      },
    }).fullPath;
    if (activeId.value === id && route.fullPath !== item.route)
      await navigateTo(item.route, { replace: true });
  }
  function registerSave(id: string, handler: () => Promise<boolean>) {
    saves.set(id, handler);
    return () => {
      if (saves.get(id) === handler) saves.delete(id);
    };
  }
  async function finish(ids: string[]) {
    const selected = new Set(ids.filter((id) => id !== "dashboard"));
    if (selected.has(activeId.value)) {
      const current = tabs.value.findIndex((t) => t.id === activeId.value);
      const neighbor =
        [
          ...tabs.value.slice(0, current).reverse(),
          ...tabs.value.slice(current + 1),
        ].find((t) => !selected.has(t.id)) || adminMenuItem("dashboard");
      if ((await navigateTo(neighbor.route)) === false) return false;
    }
    for (const item of tabs.value.filter((t) => selected.has(t.id))) {
      delete dirty.value[item.id];
      saves.delete(item.id);
      if (!item.route.startsWith("/admin/workspace"))
        revisions.value[item.route] = (revisions.value[item.route] || 0) + 1;
    }
    tabs.value = tabs.value.filter((t) => !selected.has(t.id));
    return true;
  }
  async function requestClose(ids: string[]): Promise<boolean> {
    if (closing.value.length) return false;
    ids = ids.filter(
      (id) => id !== "dashboard" && tabs.value.some((t) => t.id === id),
    );
    if (!ids.length) return true;
    if (!ids.some((id) => dirty.value[id])) return finish(ids);
    closing.value = ids;
    closeError.value = "";
    return new Promise((resolve) => {
      resolveClosing = resolve;
    });
  }
  async function confirmClose(choice: "save" | "discard" | "cancel") {
    if (closeBusy.value) return;
    if (choice === "cancel") {
      closing.value = [];
      resolveClosing?.(false);
      resolveClosing = null;
      return;
    }
    closeBusy.value = true;
    closeError.value = "";
    try {
      if (choice === "save")
        for (const id of closing.value.filter((id) => dirty.value[id])) {
          const handler = saves.get(id);
          if (!handler || !(await handler())) {
            closeError.value = `${tabs.value.find((t) => t.id === id)?.label || "编辑页签"}尚未保存，请检查表单或上传状态。`;
            return;
          }
        }
      const done = await finish([...closing.value]);
      if (!done) return;
      closing.value = [];
      resolveClosing?.(true);
      resolveClosing = null;
    } catch {
      closeError.value = "保存未完成，页签保持打开，请检查后重试。";
    } finally {
      closeBusy.value = false;
    }
  }
  function close(id: string) {
    return requestClose([id]);
  }
  async function closeGroup(
    id: string,
    mode: "left" | "right" | "others" | "all",
  ) {
    const index = tabs.value.findIndex((t) => t.id === id);
    const selected =
      mode === "all"
        ? tabs.value
        : mode === "left"
          ? tabs.value.slice(0, index)
          : mode === "right"
            ? tabs.value.slice(index + 1)
            : tabs.value.filter((t) => t.id !== id);
    const result = await requestClose(selected.map((t) => t.id));
    if (result && mode === "others" && tabs.value.some((tab) => tab.id === id))
      await open(id);
    return result;
  }
  function clear() {
    saves.clear();
    resolveClosing?.(false);
    resolveClosing = null;
    closing.value = [];
    tabs.value = [adminMenuItem("dashboard")];
    dirty.value = {};
  }
  return {
    tabs,
    dirty,
    activeId,
    open,
    openEditor,
    updateEditor,
    close,
    closeGroup,
    requestClose,
    registerSave,
    closing,
    closeBusy,
    closeError,
    confirmClose,
    clear,
  };
}
