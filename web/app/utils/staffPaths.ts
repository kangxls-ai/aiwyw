/** 后台组件保留逻辑路由，只有带独立入口的页面才会把前缀加到地址上。 */
export function staffRelative(path: string) {
  return path.replace(/^\/[0-9a-f]{32}(?=\/(?:admin|login)(?:[/?#]|$))/, "");
}
