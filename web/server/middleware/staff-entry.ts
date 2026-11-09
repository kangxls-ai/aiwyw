import {
  mandatoryStaffApi,
  matchesStaffEntry,
  scopedStaffApi,
} from "../utils/staff-entry";
import { proxySiteApi } from "../utils/api-proxy";

/** 在Nuxt渲染、代理和静态缓存之前校验入口，不向错误路径提示真实地址。 */
export default defineEventHandler(async (event) => {
  const rawPath = event.path.split("?", 1)[0] || "/";
  const path = getRequestURL(event).pathname;
  if (/^\/(?:login|admin)(?:\/|$)/.test(path) || mandatoryStaffApi(path)) {
    setHeader(event, "Cache-Control", "no-store");
    throw createError({ statusCode: 404, statusMessage: "Not Found" });
  }
  const match = /^\/([0-9a-fA-F]{32})(\/.*)$/.exec(path);
  if (!match) return;
  const [, candidate, inner] = match as [string, string, string];
  setHeader(event, "Cache-Control", "private, no-store");
  setHeader(event, "Referrer-Policy", "no-referrer");
  setHeader(event, "X-Robots-Tag", "noindex, nofollow");
  if (
    path !== rawPath ||
    /[%\\;]|\/\//.test(path) ||
    !(scopedStaffApi(inner) || /^\/(?:login$|admin(?:\/|$))/.test(inner)) ||
    !(await matchesStaffEntry(candidate))
  ) {
    throw createError({ statusCode: 404, statusMessage: "Not Found" });
  }
  if (scopedStaffApi(inner)) return proxySiteApi(event, inner, candidate);
  setHeader(event, "X-Frame-Options", "DENY");
  setHeader(event, "Content-Security-Policy", "frame-ancestors 'none'");
});
