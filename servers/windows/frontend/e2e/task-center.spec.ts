import {
  expect,
  test,
  type Page,
  type Route,
  type TestInfo,
} from "@playwright/test";
import { mkdirSync, writeFileSync } from "node:fs";

type Theme = "light" | "dark";
type Viewport = { name: string; width: number; height: number };

const themes: Theme[] = ["light", "dark"];
const viewports: Viewport[] = [
  { name: "desktop-wide", width: 1440, height: 900 },
  { name: "desktop", width: 1280, height: 800 },
  { name: "tablet-landscape", width: 1024, height: 768 },
  { name: "tablet-narrow", width: 900, height: 900 },
  { name: "tablet-boundary", width: 899, height: 900 },
  { name: "tablet-portrait", width: 820, height: 1180 },
  { name: "tablet-small", width: 768, height: 1024 },
  { name: "phone-boundary", width: 737, height: 900 },
  { name: "phone", width: 430, height: 932 },
  { name: "phone-small", width: 390, height: 844 },
  { name: "phone-narrow", width: 360, height: 800 },
];

const now = Date.now();
const activeTask = {
  id: "fixture-copy-1",
  userId: 1,
  ownerName: "fixture",
  type: "file.copy",
  title: "复制项目素材",
  status: "running",
  createdAt: now - 30_000,
  totalItems: 4,
  processedItems: 1,
  totalBytes: 16 * 1024 * 1024,
  processedBytes: 8 * 1024 * 1024,
};

const activeTransfers = [
  {
    id: "fixture-upload-1",
    kind: "upload",
    status: "running",
    name: "素材.zip",
    target: "/素材.zip",
    bytesTotal: 8 * 1024 * 1024,
    bytesTransferred: 2 * 1024 * 1024,
    createdAt: now - 20_000,
  },
  {
    id: "fixture-download-1",
    kind: "download",
    status: "queued",
    name: "备份.tar",
    target: "/备份.tar",
    bytesTotal: 32 * 1024 * 1024,
    bytesTransferred: 0,
    createdAt: now - 10_000,
  },
];

function base64Url(value: unknown) {
  return Buffer.from(JSON.stringify(value)).toString("base64url");
}

function fixtureToken() {
  return [
    base64Url({ alg: "none", typ: "JWT" }),
    base64Url({
      exp: Math.floor(Date.now() / 1000) + 3600,
      instance: { hostname: "DH4300Plus" },
      user: {
        id: 1,
        username: "fixture",
        password: "",
        scope: "/",
        locale: "zh-cn",
        perm: {
          admin: true,
          copy: true,
          create: true,
          delete: true,
          download: true,
          execute: true,
          modify: true,
          move: true,
          rename: true,
          share: true,
          shell: true,
          upload: true,
        },
        commands: [],
        rules: [],
        lockPassword: false,
        hideDotfiles: false,
        singleClick: false,
        redirectAfterCopyMove: false,
        dateFormat: false,
        viewMode: "details",
        aceEditorTheme: "",
      },
    }),
    "fixture",
  ].join(".");
}

async function fulfillJSON(route: Route, data: unknown) {
  await route.fulfill({
    status: 200,
    contentType: "application/json; charset=utf-8",
    body: JSON.stringify(data),
  });
}

async function installFixtureApi(page: Page) {
  const token = fixtureToken();
  const fixtureUser = JSON.parse(
    Buffer.from(token.split(".")[1], "base64url").toString("utf8")
  ).user;

  await page.route(/\/api\/login(?:\?|$)/, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "text/plain; charset=utf-8",
      body: token,
    });
  });

  await page.route(/\/api\/renew(?:\?|$)/, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "text/plain; charset=utf-8",
      body: token,
    });
  });

  await page.route(/\/api\/task-center\/events(?:\?|$)/, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "text/event-stream; charset=utf-8",
      body: ": fixture connected\n\n",
    });
  });

  await page.route(/\/api\/tasks(?:\?|$)/, async (route) => {
    const counts = {
      all: 1,
      active: 1,
      attention: 0,
      canceled: 0,
      completed: 0,
      archived: 0,
    };
    await fulfillJSON(route, {
      items: [activeTask],
      nextCursor: "",
      total: 1,
      counts,
      categoryCounts: {
        file: counts,
        background: { ...counts, all: 0, active: 0 },
      },
      owners: ["fixture"],
    });
  });

  await page.route(/\/api\/transfers(?:\?|$)/, async (route) => {
    const url = new URL(route.request().url());
    const kind = url.searchParams.get("kind");
    const items = kind
      ? activeTransfers.filter((item) => item.kind === kind)
      : activeTransfers;
    await fulfillJSON(route, { items, total: items.length });
  });

  await page.route(/\/api\/history(?:\?|$)/, async (route) => {
    await fulfillJSON(route, { items: [], total: 0, nextCursor: "" });
  });

  await page.route(/\/api\/resources\/(?:\?|$)/, async (route) => {
    await fulfillJSON(route, {
      path: "/",
      name: "",
      size: 0,
      extension: "",
      modified: new Date(now).toISOString(),
      mode: 0,
      isDir: true,
      isSymlink: false,
      type: "dir",
      riskLevel: "low",
      items: [],
      numDirs: 0,
      numFiles: 0,
      sorting: { by: "name", asc: true },
      index: 0,
    });
  });

  await page.route(
    /\/api\/(?:users\/1|favorites(?:\/groups)?|tags|categories|volumes|recent)(?:\?|$)/,
    async (route) => {
      const pathname = new URL(route.request().url()).pathname;
      await fulfillJSON(route, pathname === "/api/users/1" ? fixtureUser : []);
    }
  );
}

async function login(page: Page) {
  const mode = process.env.NFB_E2E_MODE?.trim() || "fixture";
  await page.goto("/login?redirect=%2Ftasks", { waitUntil: "networkidle" });
  await expect(page.getByPlaceholder("用户名")).toBeVisible();
  await page
    .getByPlaceholder("用户名")
    .fill(
      mode === "fixture" ? "fixture" : (process.env.NFB_E2E_USERNAME ?? "")
    );
  await page
    .getByPlaceholder("密码")
    .fill(
      mode === "fixture" ? "fixture" : (process.env.NFB_E2E_PASSWORD ?? "")
    );
  await page.getByRole("button", { name: "登录" }).click();
  await expect(page).toHaveURL(/\/tasks/);
  await expect(page.getByRole("heading", { name: "下载记录" })).toBeVisible();
}

async function captureGeometry(page: Page) {
  return page.evaluate(() => {
    const header = document.querySelector(".task-center-page > header");
    const workspace = document.querySelector(".task-center-workspace");
    const headerRect = header?.getBoundingClientRect();
    const workspaceRect = workspace?.getBoundingClientRect();
    return {
      innerWidth: window.innerWidth,
      innerHeight: window.innerHeight,
      scrollWidth: document.documentElement.scrollWidth,
      scrollHeight: document.documentElement.scrollHeight,
      headerBottom: headerRect?.bottom ?? null,
      workspaceTop: workspaceRect?.top ?? null,
      workspaceWidth: workspaceRect?.width ?? null,
    };
  });
}

test.describe("NAS File Browser browser gate", () => {
  test("captures the task-center viewport and theme matrix", async ({
    page,
  }, testInfo: TestInfo) => {
    const mode = process.env.NFB_E2E_MODE?.trim() || "fixture";
    if (
      mode === "real" &&
      (!process.env.NFB_E2E_USERNAME || !process.env.NFB_E2E_PASSWORD)
    ) {
      throw new Error(
        "NFB_E2E_MODE=real requires NFB_E2E_USERNAME and NFB_E2E_PASSWORD"
      );
    }

    const consoleErrors: string[] = [];
    const pageErrors: string[] = [];
    const requestCounts: Record<string, number> = {};
    const geometry: Array<Record<string, unknown>> = [];

    page.on("console", (message) => {
      if (message.type() === "error") consoleErrors.push(message.text());
    });
    page.on("pageerror", (error) => pageErrors.push(error.message));
    page.on("request", (request) => {
      if (!request.url().includes("/api/")) return;
      const pathname = new URL(request.url()).pathname;
      requestCounts[pathname] = (requestCounts[pathname] ?? 0) + 1;
    });

    if (mode === "fixture") await installFixtureApi(page);
    await login(page);

    await expect(page.getByLabel("当前实例")).toContainText("DH4300Plus");

    const taskEntry = page.locator(".header-task-center");
    await expect(taskEntry).toHaveCount(0);

    await page.goto("/files/");
    await expect(taskEntry).toBeVisible();
    if (mode === "fixture") {
      const badge = taskEntry.locator(".header-task-center__badge");
      await expect(badge).toBeVisible();
      await expect(badge).toHaveAttribute("aria-label", /3/);
    }
    await page.goto("/tasks?tab=file");
    await expect(taskEntry).toHaveCount(0);

    await expect(page.getByRole("heading", { name: "文件任务" })).toBeVisible();
    await expect(page.getByText("8.0 MB / 16 MB")).toBeVisible();

    for (const theme of themes) {
      await page.evaluate((nextTheme) => {
        document.documentElement.className = nextTheme;
      }, theme);
      for (const viewport of viewports) {
        await page.setViewportSize({
          width: viewport.width,
          height: viewport.height,
        });
        await page.waitForTimeout(60);
        const metrics = await captureGeometry(page);
        expect(metrics.scrollWidth).toBeLessThanOrEqual(metrics.innerWidth + 1);
        expect(metrics.headerBottom).not.toBeNull();
        expect(metrics.workspaceTop).not.toBeNull();
        expect(metrics.workspaceTop as number).toBeGreaterThanOrEqual(
          (metrics.headerBottom as number) - 1
        );
        geometry.push({ theme, viewport: viewport.name, ...metrics });
        await page.screenshot({
          path: testInfo.outputPath(
            `screenshots/${theme}-${viewport.name}.png`
          ),
          fullPage: true,
        });
      }
    }

    const report = {
      dataSource: mode === "real" ? "real-deployment" : "fixture",
      route: "/tasks",
      themes,
      viewports,
      geometry,
      consoleErrors,
      pageErrors,
      requestCounts,
      generatedAt: new Date().toISOString(),
    };
    const reportPath = testInfo.outputPath("audit.json");
    mkdirSync(reportPath.slice(0, reportPath.lastIndexOf("/")), {
      recursive: true,
    });
    writeFileSync(reportPath, JSON.stringify(report, null, 2));
    await testInfo.attach("audit", {
      path: reportPath,
      contentType: "application/json",
    });

    expect(consoleErrors).toEqual([]);
    expect(pageErrors).toEqual([]);
  });
});
