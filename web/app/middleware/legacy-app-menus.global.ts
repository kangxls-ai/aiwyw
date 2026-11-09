/** 旧书签统一到应用管理，编辑器中的版本、文件、发布与回滚继续保留。 */
export default defineNuxtRouteMiddleware((to) => {
  if (
    staffRelative(to.path) === "/admin/workspace" &&
    ["versions", "publishing"].includes(String(to.query.view))
  )
    return navigateTo(
      { path: to.path, query: { ...to.query, view: "applications" } },
      { replace: true },
    );
});
