<script setup lang="ts">
const { staffPath } = useStaffPaths();
import "~/assets/catalog.css";
import "~/assets/storage-editor.css";
import "~/assets/element-admin.css";
/** 管理框架只缓存页签内容，不缓存服务端授权结果；退出时增加实例代数，防止下次登录复用旧表单。 */
const { request } = useApi();
const user = useState<any>("admin-user", () => null);
const route = useRoute(),
  error = ref("");
const { tabs, activeId, open, close, closeGroup, requestClose, dirty, clear } =
  useManagementTabs();
onMounted(async () => {
  try {
    user.value = await request("/api/auth/session");
    if (!user.value.menus?.length) {
      await navigateTo("/account");
      return;
    }
    if (
      user.value.mfaRequired &&
      !user.value.mfaVerified &&
      staffRelative(route.path) !== "/admin/security"
    )
      await navigateTo(staffPath("/admin/security"));
  } catch {
    await navigateTo(staffPath("/login"));
  }
});
async function logout() {
  if (!(await requestClose(tabs.value.map((t) => t.id)))) return;
  try {
    await request("/api/auth/logout", { method: "POST" });
    user.value = null;
    useState("admin-session-generation", () => 0).value++;
    clear();
    await navigateTo(staffPath("/login"));
  } catch {
    error.value = "退出未完成，请重试";
  }
}
let removeGuard: (() => void) | undefined;
function unload(event: BeforeUnloadEvent) {
  if (Object.values(dirty.value).some(Boolean)) {
    event.preventDefault();
    event.returnValue = "";
  }
}
onMounted(() => {
  window.addEventListener("beforeunload", unload);
  removeGuard = useRouter().beforeEach((to, from) => {
    if (
      staffRelative(from.path).startsWith("/admin") &&
      !staffRelative(to.path).startsWith("/admin") &&
      Object.values(dirty.value).some(Boolean)
    )
      return window.confirm("还有未保存的编辑内容，确定离开后台？");
    return true;
  });
});
onBeforeUnmount(() => {
  window.removeEventListener("beforeunload", unload);
  removeGuard?.();
});
</script>
<template>
  <AdminWorkspaceFrame
    :tabs="tabs"
    :active-id="activeId"
    :user="user"
    :dirty="dirty"
    @open="open"
    @close="close"
    @logout="logout"
    @group-close="closeGroup"
    ><div v-if="error" class="alert error">{{ error }}</div>
    <ManagementCloseDialog /><slot
  /></AdminWorkspaceFrame>
</template>
