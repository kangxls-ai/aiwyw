<!-- 正式管理框架只负责导航、页签与布局；权限必须由各服务端接口验证。 -->
<script setup lang="ts">
const { site, siteName } = useSiteBrand();
import {
  adminMenuGroups,
  adminMenuItem,
  type AdminMenuItem,
} from "~/data/adminNavigation";
import "~/assets/admin-workspace.css";
const props = defineProps<{
  activeId: string;
  tabs: import("~/composables/useManagementTabs").ManagementTab[];
  user?: {
    email: string;
    nickname?: string;
    roles?: string[];
    menus?: string[];
    role?: string;
    mfaRequired?: boolean;
    mfaVerified?: boolean;
  } | null;
  dirty?: Record<string, boolean>;
}>();
const emit = defineEmits<{
  open: [id: string];
  close: [id: string];
  logout: [];
  groupClose: [id: string, mode: "left" | "right" | "others" | "all"];
}>();
const expanded = ref<string[]>(["workspace", "products", "system"]);
const collapsed = ref(false),
  userMenu = ref(false);
const unread = ref(0),
  waiting = ref(0),
  { request } = useApi();
async function supportBadge() {
  if (
    !props.user?.menus?.includes("conversations") ||
    (props.user.mfaRequired && !props.user.mfaVerified) ||
    document.hidden
  )
    return;
  try {
    const r = await request<any>("/api/admin/support/unread");
    unread.value = Number(r.unread);
    waiting.value = Number(r.waiting);
  } catch {
    /* 权限撤销/会话过期时由页面入口处理，不用轮询错误打断编辑。 */
  }
}
let supportTimer: ReturnType<typeof setInterval> | undefined;
onMounted(() => {
  supportBadge();
  supportTimer = setInterval(supportBadge, 15000);
});
onBeforeUnmount(() => clearInterval(supportTimer));
watch(
  () => props.activeId,
  (id) => {
    const tab = props.tabs.find((t) => t.id === id);
    const menuId =
      tab?.entityType || (tab?.kind === "api-editor" ? "apis" : id);
    const group = adminMenuGroups.find((g) =>
      g.items.some((i) => i.id === menuId),
    );
    if (group && !expanded.value.includes(group.id))
      expanded.value.push(group.id);
  },
  { immediate: true },
);
function toggle(id: string) {
  if (collapsed.value) {
    collapsed.value = false;
    if (!expanded.value.includes(id)) expanded.value.push(id);
    return;
  }
  expanded.value = expanded.value.includes(id)
    ? expanded.value.filter((g) => g !== id)
    : [...expanded.value, id];
}
/** 菜单按角色收敛，接口仍独立鉴权；隐藏菜单不充当安全边界。 */
const visibleGroups = computed(() => {
  const allowed = props.user?.menus || [];
  return adminMenuGroups
    .map((group) => ({
      ...group,
      items: group.items.filter((item) => allowed.includes(item.id)),
    }))
    .filter((group) => group.items.length);
});
const current = computed(
  () =>
    props.tabs.find((t) => t.id === props.activeId) ||
    adminMenuItem(props.activeId),
);
const groupName = computed(
  () =>
    adminMenuGroups.find((g) =>
      g.items.some(
        (i) =>
          i.id ===
          (props.tabs.find((t) => t.id === props.activeId)?.entityType ||
            props.activeId),
      ),
    )?.label ||
    (current.value.id.startsWith("api-editor-")
      ? "API 服务"
      : current.value.id.startsWith("editor-")
        ? "商品管理"
        : "工作空间"),
);
// 右键菜单固定定位并限制在视口内，Escape/外部点击可收起，Shift+F10支持键盘。
const context = ref<{ id: string; x: number; y: number } | null>(null);
function contextMenu(event: MouseEvent, id: string) {
  event.preventDefault();
  context.value = {
    id,
    x: Math.max(8, Math.min(event.clientX, window.innerWidth - 210)),
    y: Math.max(8, Math.min(event.clientY, window.innerHeight - 190)),
  };
}
function contextKey(event: KeyboardEvent, id: string) {
  if (event.shiftKey && event.key === "F10") {
    event.preventDefault();
    const box = (event.currentTarget as HTMLElement).getBoundingClientRect();
    context.value = {
      id,
      x: Math.min(box.left, window.innerWidth - 210),
      y: Math.min(box.bottom, window.innerHeight - 190),
    };
  }
}
function hideContext(event: Event) {
  if (event instanceof KeyboardEvent) {
    if (event.key === "Escape") context.value = null;
  } else if (!(event.target as HTMLElement).closest(".management-tab-context"))
    context.value = null;
}
function group(mode: "left" | "right" | "others" | "all") {
  if (context.value) emit("groupClose", context.value.id, mode);
  context.value = null;
}
onMounted(() => {
  document.addEventListener("pointerdown", hideContext);
  document.addEventListener("keydown", hideContext);
});
onBeforeUnmount(() => {
  document.removeEventListener("pointerdown", hideContext);
  document.removeEventListener("keydown", hideContext);
});
</script>
<template>
  <div class="management-workspace" :class="{ compact: collapsed }">
    <header class="management-banner">
      <div class="management-brand">
        <NuxtLink to="/" class="management-logo" :aria-label="siteName"
          ><img src="/images/weiyunwan.svg" width="44" height="44" alt=""
        /></NuxtLink>
        <div>
          <strong>{{ siteName }}<span>管理工作台</span></strong>
          <p>把每一件好工具，认真管理好。</p>
        </div>
      </div>
      <div class="management-banner-actions">
        <button
          v-if="user?.menus?.includes('conversations')"
          class="management-visit"
          @click="emit('open', 'conversations')"
        >
          客服未读 {{ unread }} · 待领取 {{ waiting }}
        </button>
        <NuxtLink to="/" class="management-visit"
          ><SiteIcon name="browser" />访问网站<SiteIcon
            name="arrow" /></NuxtLink
        ><span class="management-mode">管理后台</span>
        <div class="management-account">
          <button
            class="management-user"
            :aria-expanded="userMenu"
            @click="userMenu = !userMenu"
          >
            <span class="management-avatar">A<span /></span
            ><span
              ><strong>{{ user?.nickname || "后台账户" }}</strong
              ><small>{{
                user?.role === "ADMIN" ? "管理员" : "后台账户"
              }}</small></span
            ><SiteIcon name="chevron" />
          </button>
          <div v-if="userMenu" class="management-user-menu">
            <button
              @click="
                emit('open', 'security');
                userMenu = false;
              "
            >
              <SiteIcon name="lock" />账户安全</button
            ><button
              @click="
                emit('logout');
                userMenu = false;
              "
            >
              <SiteIcon name="back" />退出登录
            </button>
          </div>
        </div>
      </div>
    </header>
    <div class="management-body">
      <aside class="management-sidebar" aria-label="后台管理菜单">
        <div class="management-sidebar-top">
          <span>管理导航</span
          ><button
            :aria-label="collapsed ? '展开菜单栏' : '收起菜单栏'"
            @click="collapsed = !collapsed"
          >
            <SiteIcon name="clipboard" />
          </button>
        </div>
        <nav>
          <section
            v-for="group in visibleGroups"
            :key="group.id"
            class="management-menu-group"
          >
            <button
              :aria-label="group.label"
              :title="group.label"
              class="management-group-toggle"
              :class="{ selected: group.items.some((i) => i.id === activeId) }"
              :aria-expanded="expanded.includes(group.id)"
              @click="toggle(group.id)"
            >
              <SiteIcon :name="group.icon" /><span>{{ group.label }}</span
              ><SiteIcon
                name="chevron"
                :class="{ expanded: expanded.includes(group.id) }"
              />
            </button>
            <div
              v-show="expanded.includes(group.id)"
              class="management-submenu"
            >
              <button
                v-for="item in group.items"
                :key="item.id"
                :aria-label="item.label"
                :title="item.label"
                :class="{ active: item.id === activeId }"
                :aria-current="item.id === activeId ? 'page' : undefined"
                @click="emit('open', item.id)"
              >
                <span class="management-submenu-dot" /><span>{{
                  item.label
                }}</span
                ><span
                  v-if="dirty?.[item.id]"
                  class="management-menu-dirty"
                  aria-label="有未保存修改"
                />
              </button>
            </div>
          </section>
        </nav>
        <div class="management-sidebar-foot">
          <span /><span>{{ siteName }} <small>管理工作空间</small></span>
        </div>
      </aside>
      <div class="management-right">
        <div class="management-tabs-row">
          <nav
            class="management-tabs"
            aria-label="已打开的工作区"
            role="tablist"
          >
            <div
              v-for="tab in tabs"
              :key="tab.id"
              class="management-tab"
              :class="{ active: tab.id === activeId }"
              @contextmenu="contextMenu($event, tab.id)"
              @keydown="contextKey($event, tab.id)"
            >
              <button
                :id="`management-tab-${tab.id}`"
                role="tab"
                :aria-label="tab.label"
                :aria-selected="tab.id === activeId"
                :aria-controls="`management-panel-${tab.id}`"
                @click="emit('open', tab.id)"
              >
                <SiteIcon :name="tab.icon" /><span>{{ tab.label }}</span
                ><span
                  v-if="dirty?.[tab.id]"
                  class="management-tab-dirty"
                  aria-label="未保存"
                /></button
              ><button
                v-if="tab.id !== 'dashboard'"
                class="management-tab-close"
                :aria-label="`关闭${tab.label}页签`"
                @click.stop="emit('close', tab.id)"
              >
                <SiteIcon name="close" />
              </button>
            </div>
          </nav>
          <button
            class="management-tabs-home"
            aria-label="切换到工作台"
            @click="emit('open', 'dashboard')"
          >
            <SiteIcon name="chart" />
          </button>
        </div>
        <div class="management-workspace-bar">
          <span
            >{{ groupName }}<SiteIcon name="chevron" /><strong>{{
              current.label
            }}</strong></span
          ><span><i />工作区</span>
        </div>
        <main
          :id="`management-panel-${activeId}`"
          class="management-content"
          role="tabpanel"
          :aria-labelledby="`management-tab-${activeId}`"
        >
          <slot />
        </main>
        <footer class="management-footer">
          <span
            >{{ siteName }} · {{ site["site.domain"] || "www.aiwyw.com" }}</span
          ><span>配置、操作记录与账户安全</span>
        </footer>
      </div>
    </div>
  </div>
  <Teleport to="body"
    ><div
      v-if="context"
      class="management-tab-context"
      role="menu"
      aria-label="页签操作"
      :style="{ left: context.x + 'px', top: context.y + 'px' }"
    >
      <button role="menuitem" @click="group('right')">关闭右侧页签</button
      ><button role="menuitem" @click="group('others')">关闭其他页签</button
      ><button role="menuitem" @click="group('left')">关闭左侧页签</button
      ><button role="menuitem" title="保留工作台" @click="group('all')">
        关闭全部页签
      </button>
    </div></Teleport
  >
</template>
<style scoped>
.management-banner-actions button.management-visit {
  background: transparent;
  border: 0;
  padding: 0;
  cursor: pointer;
  font: inherit;
}
</style>
