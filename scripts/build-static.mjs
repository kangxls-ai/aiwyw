import { spawnSync } from "node:child_process";

// npm_execpath points to npm's JavaScript CLI on Windows, macOS and Linux.
const result = spawnSync(
  process.execPath,
  [process.env.npm_execpath, "run", "generate", "--workspace", "web"],
  {
    env: { ...process.env, AIWYW_STATIC_BUILD: "1" },
    stdio: "inherit",
  },
);
if (result.error) throw result.error;
process.exit(result.status ?? 1);
