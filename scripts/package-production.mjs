/** 白名单打包：禁止将本地数据库、master.key、bootstrap、合成账号及验收数据复制到生产。 */
import {
  cpSync,
  mkdirSync,
  existsSync,
  readdirSync,
  statSync,
  readFileSync,
  writeFileSync,
  chmodSync,
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
  ["backend/target/aiwyw-server-0.1.0-SNAPSHOT-deploy.jar", "aiwyw-server.jar"],
  ["backend/target/lib", "lib"],
  ["web/.output/public", "www"],
  ["web/.output/public/200.html", "www/app-shell.html"],
  ["deploy/standalone", "."],
  ["deploy/nginx.conf", "nginx/nginx.conf"],
  ["deploy/aiwyw-proxy.conf", "nginx/aiwyw-proxy.conf"],
  ["deploy/aiwyw-routes.conf", "nginx/aiwyw-routes.conf"],
  ["deploy/aiwyw.service", "aiwyw.service"],
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
    filter: (file) =>
      source !== "web/.output/public" ||
      ![
        "index.html",
        "200.html",
        "404.html",
        "_published",
        "_payload.json",
      ].includes(relative(join(root, source), file).replaceAll("\\", "/")),
  });
function files(dir) {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    return statSync(path).isDirectory() ? files(path) : [path];
  });
}
for (const name of ["start.sh", "stop.sh"]) {
  const script = join(output, name);
  writeFileSync(script, readFileSync(script, "utf8").replaceAll("\r\n", "\n"));
  chmodSync(script, 0o755);
}
const paths = files(output);
const libraryDigest = createHash("sha256");
for (const path of files(join(output, "lib")).sort()) {
  libraryDigest.update(
    relative(join(output, "lib"), path).replaceAll("\\", "/") + "\0",
  );
  libraryDigest.update(
    createHash("sha256").update(readFileSync(path)).digest(),
  );
}
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
      librariesSha256: libraryDigest.digest("hex"),
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
