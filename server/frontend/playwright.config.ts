import { defineConfig } from "@playwright/test";
import { existsSync } from "node:fs";

const externalBaseURL = process.env.NFB_E2E_BASE_URL?.trim();
const mode = process.env.NFB_E2E_MODE?.trim() || "fixture";

if (mode === "real" && !externalBaseURL) {
  throw new Error(
    "NFB_E2E_MODE=real requires NFB_E2E_BASE_URL pointing at the deployed NAS"
  );
}

const baseURL = externalBaseURL || "http://127.0.0.1:4173";
const executablePath =
  process.env.NFB_E2E_EXECUTABLE_PATH?.trim() ||
  "/home/Kkwans/.cache/ms-playwright/chromium-1234/chrome-linux/chrome";

export default defineConfig({
  testDir: "./e2e",
  fullyParallel: false,
  workers: 1,
  forbidOnly: Boolean(process.env.CI),
  retries: process.env.CI ? 1 : 0,
  timeout: 60_000,
  expect: {
    timeout: 8_000,
  },
  reporter: [
    ["list"],
    ["json", { outputFile: "test-results/e2e-report.json" }],
    ["html", { outputFolder: "test-results/e2e-report", open: "never" }],
  ],
  outputDir: "test-results/e2e",
  use: {
    baseURL,
    browserName: "chromium",
    headless: true,
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
    video: "off",
    launchOptions: existsSync(executablePath) ? { executablePath } : undefined,
  },
  webServer: externalBaseURL
    ? undefined
    : {
        command: "corepack pnpm exec vite dev --host 127.0.0.1 --port 4173",
        url: baseURL,
        reuseExistingServer: true,
        timeout: 120_000,
      },
});
