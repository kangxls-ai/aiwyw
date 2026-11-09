/** 单机生产进程：业务配置在后台维护；这里只确定回环监听、持久目录和进程生命周期。 */
import { spawn } from "node:child_process";
import { resolve, join } from "node:path";
import { fileURLToPath } from "node:url";
import { existsSync, mkdirSync } from "node:fs";

const root = resolve(fileURLToPath(new URL("../", import.meta.url)));
const data = resolve(process.argv[2] || "/var/lib/aiwyw");
if (Number(process.versions.node.split(".")[0]) < 24)
  throw new Error("生产运行需要Node.js 24或更高版本");
for (const file of ["backend/aiwyw-server.jar", "web/server/index.mjs"]) {
  if (!existsSync(join(root, file)))
    throw new Error("缺少生产发布文件：" + file);
}
mkdirSync(data, { recursive: true, mode: 0o700 });
const children = [];
let stopping = false,
  failed = false,
  killTimer;
function stop(code = 0) {
  if (stopping) return;
  stopping = true;
  failed = code !== 0;
  process.exitCode = code;
  for (const child of children)
    if (child.exitCode === null) child.kill("SIGTERM");
  killTimer = setTimeout(() => {
    for (const child of children)
      if (child.exitCode === null) child.kill("SIGKILL");
  }, 25000);
  killTimer.unref();
}
function start(command, args, options = {}) {
  const child = spawn(command, args, {
    cwd: root,
    stdio: "inherit",
    ...options,
  });
  children.push(child);
  child.on("error", () => {
    console.error("生产子进程启动失败，请检查运行环境与服务日志");
    stop(1);
  });
  child.on("exit", (code) => {
    if (!stopping) {
      console.error("生产子进程退出；停止另一进程，交由systemd整体重启");
      stop(code || 1);
    }
    if (children.every((c) => c.exitCode !== null || c.signalCode !== null)) {
      clearTimeout(killTimer);
      process.exitCode = failed ? 1 : 0;
    }
  });
  return child;
}
for (const signal of ["SIGTERM", "SIGINT"]) process.on(signal, () => stop());
// Web网关内部地址固定127.0.0.1:18080，不接受浏览器指定代理目标；公网只经过Nginx。
start("java", [
  "-Xms256m",
  "-Xmx1024m",
  "-Dfile.encoding=UTF-8",
  "-jar",
  join(root, "backend/aiwyw-server.jar"),
  "--server.address=127.0.0.1",
  "--server.port=18080",
  "--server.shutdown=graceful",
  "--spring.lifecycle.timeout-per-shutdown-phase=20s",
  "--aiwyw.mode=production",
  "--aiwyw.data-dir=" + data,
]);
try {
  let ready = false;
  for (let attempt = 0; attempt < 90 && !stopping; attempt++) {
    try {
      const response = await fetch(
        "http://127.0.0.1:18080/api/install/status",
        { signal: AbortSignal.timeout(1500) },
      );
      if (response.ok) {
        ready = true;
        break;
      }
    } catch {}
    await new Promise((r) => setTimeout(r, 1000));
  }
  if (!ready) throw new Error("后端未通过启动检查");
  if (!stopping)
    start(process.execPath, [join(root, "web/server/index.mjs")], {
      env: {
        ...process.env,
        NODE_ENV: "production",
        AIWYW_SERVER_DATA_DIR: data,
        NITRO_HOST: "127.0.0.1",
        NITRO_PORT: "3000",
        HOST: "127.0.0.1",
        PORT: "3000",
      },
    });
} catch {
  console.error("后端启动失败或超时，请检查数据库、数据目录与生产模式保护提示");
  stop(1);
}
