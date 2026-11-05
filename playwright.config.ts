import { defineConfig, devices } from "@playwright/test";
import { mkdtempSync, existsSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

process.env.AIWYW_E2E_DIR ||= mkdtempSync(join(tmpdir(), "aiwyw-browser-"));
export default defineConfig({
  testDir: "./tests",
  timeout: 60000,
  fullyParallel: false,
  workers: 1,
  retries: 0,
  use: {
    actionTimeout: 15000,
    baseURL: "http://127.0.0.1:3000",
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },
  projects: [
    {
      name: "chromium",
      use: {
        ...devices["Desktop Chrome"],
        ...(process.platform === "win32" &&
        existsSync("C:/Program Files/Google/Chrome/Application/chrome.exe")
          ? { channel: "chrome" }
          : {}),
      },
    },
  ],
  webServer: {
    command: "node scripts/e2e-services.mjs",
    url: "http://127.0.0.1:3000/api/install/status",
    timeout: 90000,
    reuseExistingServer: false,
  },
});
