// 浏览器验收只使用临时数据目录，结束时关闭本测试启动的子进程；不能复用真实站点钱包。
import { spawn } from "node:child_process";
import { resolve } from "node:path";
import { javaOptions } from "./java-options.mjs";
import { startTestInbox } from "./e2e-mail.mjs";

if (!process.env.AIWYW_E2E_DIR)
  throw new Error("Missing isolated browser test directory");
const children = [];
const inbox = await startTestInbox(process.env.AIWYW_E2E_DIR);
function run(command, args, extraEnv = {}) {
  const child = spawn(command, args, {
    stdio: ["ignore", "pipe", "pipe"],
    env: { ...process.env, ...extraEnv },
    windowsHide: true,
  });
  children.push(child);
  child.stdout.on("data", (chunk) => {
    const text = chunk.toString();
    if (!text.includes("installation token")) process.stdout.write(text);
  });
  child.stderr.on("data", (chunk) => process.stderr.write(chunk));
  child.on("exit", (code) => {
    if (code) {
      stop();
      process.exit(code);
    }
  });
  child.on("error", (error) => {
    console.error(error.message);
    stop();
    process.exit(1);
  });
}
function stop() {
  inbox.server.close();
  for (const child of children) child.kill("SIGTERM");
}
process.on("SIGTERM", () => {
  stop();
  process.exit(0);
});
process.on("SIGINT", () => {
  stop();
  process.exit(0);
});
run("java", [
  ...javaOptions,
  ...inbox.javaOptions,
  "-jar",
  resolve("backend/target/aiwyw-server-0.1.0-SNAPSHOT.jar"),
  "--server.address=127.0.0.1",
  "--server.port=18080",
  `--aiwyw.data-dir=${process.env.AIWYW_E2E_DIR}`,
]);
run(process.execPath, [resolve("web/.output/server/index.mjs")], {
  AIWYW_SERVER_DATA_DIR: process.env.AIWYW_E2E_DIR,
  HOST: "127.0.0.1",
  PORT: "3000",
});
