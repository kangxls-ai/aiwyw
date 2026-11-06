/** 白名单打包：禁止将本地数据库、master.key、bootstrap、合成账号及验收数据复制到生产。 */
import {
  cpSync,
  mkdirSync,
  existsSync,
  readdirSync,
  statSync,
  readFileSync,
  writeFileSync,
} from "node:fs";
import { resolve, join, relative } from "node:path";
import { createHash } from "node:crypto";
import { execFileSync } from "node:child_process";
import { fileURLToPath } from "node:url";
const root = resolve(fileURLToPath(new URL("../", import.meta.url)));
const output = resolve(
  root,
  ".local",
  "production",
  "aiwyw-" + new Date().toISOString().replace(/[:.]/g, "-"),
);
const mappings = [
  [
    "backend/target/aiwyw-server-0.1.0-SNAPSHOT.jar",
    "backend/aiwyw-server.jar",
  ],
  ["web/.output", "web"],
  ["deploy", "deploy"],
  ["docs/PRODUCTION.md", "PRODUCTION.md"],
];
for (const [source] of mappings)
  if (!existsSync(join(root, source)))
    throw new Error("先构建并验证发布文件：" + source);
mkdirSync(output, { recursive: true });
// 独立包必须复制符号链接所指的真实文件，避免Linux产物仍引用CI工作目录。
for (const [source, target] of mappings)
  cpSync(join(root, source), join(output, target), {
    recursive: true,
    dereference: true,
  });
function files(dir) {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    return statSync(path).isDirectory() ? files(path) : [path];
  });
}
const paths = files(output);
if (
  paths.some((path) =>
    /\.(?:key|enc)$|(?:^|\/)validation-accounts\.txt$/i.test(
      relative(output, path).replaceAll("\\", "/"),
    ),
  )
)
  throw new Error("发布包包含禁止携带的运行凭据");
const commit = execFileSync("git", ["rev-parse", "HEAD"], {
  cwd: root,
  encoding: "utf8",
}).trim();
const dirty = !!execFileSync("git", ["status", "--porcelain"], {
  cwd: root,
  encoding: "utf8",
}).trim();
writeFileSync(
  join(output, "release.json"),
  JSON.stringify(
    {
      commit,
      dirty,
      builtAt: new Date().toISOString(),
      platform: process.platform,
      node: process.versions.node,
      mode: "production",
      containsData: false,
    },
    null,
    2,
  ),
);
writeFileSync(
  join(output, "SHA256SUMS"),
  files(output)
    .map(
      (path) =>
        createHash("sha256").update(readFileSync(path)).digest("hex") +
        "  " +
        relative(output, path).replaceAll("\\", "/"),
    )
    .join("\n") + "\n",
);
// tar在Windows与Linux均可用；只归档新建目录，不删除任何既有产物。
execFileSync("tar", ["-czf", output + ".tar.gz", "-C", output, "."]);
console.log(
  JSON.stringify({
    directory: output,
    archive: output + ".tar.gz",
    commit,
    dirty,
  }),
);
