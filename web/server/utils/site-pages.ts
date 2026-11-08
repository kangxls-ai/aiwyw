import { mkdtemp, readFile, unlink, writeFile } from "node:fs/promises";
import { randomUUID } from "node:crypto";
import { tmpdir } from "node:os";
import { join } from "node:path";
import type { H3Event } from "h3";

export const publicPagePaths = ["/", "/login", "/account-access"] as const;
type PageSlot = { key: string; epoch: number };
type PageFile = { path: string; expires: number };

const files = new Map<string, PageFile>();
let directory: Promise<string> | undefined;
let epoch = 0;

export function invalidatePublicPages() {
  // 失效前开始的渲染不能覆盖新配置；下一次访问或后台发布会重建对应语言。
  epoch++;
  for (const file of files.values()) void unlink(file.path).catch(() => {});
  files.clear();
}

export function publicPageSlot(event: H3Event): PageSlot | undefined {
  const url = getRequestURL(event);
  if (
    event.method !== "GET" ||
    url.search ||
    !publicPagePaths.some((p) => p === url.pathname)
  )
    return;

  // 普通首页及空白登录表单才允许共用。保留语言，其它身份信息不参与生成。
  const cookie = getCookie(event, "aiwyw_language");
  const language = cookie === "zh" || cookie === "en" ? cookie : undefined;
  const header = getHeader(event, "accept-language")?.toLowerCase();
  const variant =
    language ||
    (header?.startsWith("zh")
      ? "zh"
      : header?.startsWith("en")
        ? "en"
        : "default");
  if (language) event.node.req.headers.cookie = "aiwyw_language=" + language;
  else delete event.node.req.headers.cookie;
  delete event.node.req.headers.authorization;
  return { key: url.pathname + ":" + variant, epoch };
}

export async function readPublicPage(slot: PageSlot) {
  const file = files.get(slot.key);
  if (!file || file.expires < Date.now()) return;
  const body = await readFile(file.path, "utf8").catch(() => undefined);
  if (slot.epoch === epoch) return body;
}

export async function writePublicPage(slot: PageSlot, body: string) {
  if (slot.epoch !== epoch) return false;
  const base = await (directory ??= mkdtemp(join(tmpdir(), "aiwyw-pages-")));
  const path = join(base, randomUUID() + ".html");
  // 完整写入后才切换映射；并发请求可以继续读旧文件。
  await writeFile(path, body, { mode: 0o600, flag: "wx" });
  if (slot.epoch !== epoch) {
    await unlink(path).catch(() => {});
    return false;
  }
  const previous = files.get(slot.key);
  files.set(slot.key, {
    path,
    // 首页排名及统计会自然变动，短期复用后重新生成。登录页只在相关配置变更时重建。
    expires: slot.key.startsWith("/:") ? Date.now() + 60_000 : Infinity,
  });
  if (previous) void unlink(previous.path).catch(() => {});
  return true;
}
