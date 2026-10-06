import { expect, test, type Page, type TestInfo } from "@playwright/test";
import { mkdirSync, writeFileSync } from "node:fs";

type Theme = "light" | "dark";
type Viewport = { name: string; width: number; height: number };

const mode = process.env.NFB_E2E_MODE?.trim() || "fixture";
const username = process.env.NFB_E2E_USERNAME?.trim() || "";
const password = process.env.NFB_E2E_PASSWORD || "";
const themes: Theme[] = ["light", "dark"];
const viewports: Viewport[] = [
  { name: "desktop-wide", width: 1440, height: 900 },
  { name: "desktop", width: 1280, height: 800 },
  { name: "tablet-landscape", width: 1024, height: 768 },
  { name: "tablet-narrow", width: 900, height: 900 },
  { name: "tablet-boundary", width: 899, height: 900 },
  { name: "tablet-portrait", width: 820, height: 1180 },
  { name: "tablet-small", width: 768, height: 1024 },
  { name: "phone-boundary-wide", width: 737, height: 900 },
  { name: "phone-boundary", width: 736, height: 900 },
  { name: "phone", width: 430, height: 932 },
  { name: "phone-small", width: 390, height: 844 },
  { name: "phone-narrow", width: 360, height: 800 },
];
const settingsViewports: Viewport[] = [
  { name: "settings-wide-boundary", width: 960, height: 900 },
  { name: "settings-narrow-boundary", width: 959, height: 900 },
];

async function login(page: Page) {
  if (!username || !password) {
    throw new Error(
      "NFB_E2E_MODE=real requires NFB_E2E_USERNAME and NFB_E2E_PASSWORD"
    );
  }
  await page.goto("/login?redirect=%2Ffiles%2F", {
    waitUntil: "domcontentloaded",
  });
  await page.getByPlaceholder("用户名").fill(username);
  await page.getByPlaceholder("密码").fill(password);
  await page.getByRole("button", { name: "登录" }).click();
  await expect(page).toHaveURL(/\/files(?:\/|$)/, { timeout: 20_000 });
  await expect(page.getByLabel("当前实例")).toContainText("DH4300Plus", {
    timeout: 20_000,
  });
}

async function setTheme(page: Page, theme: Theme) {
  await page.evaluate((nextTheme) => {
    document.documentElement.className = nextTheme;
  }, theme);
}

async function geometry(page: Page) {
  return page.evaluate(() => {
    const header = document.querySelector("header");
    const rect = header?.getBoundingClientRect();
    return {
      innerWidth: window.innerWidth,
      innerHeight: window.innerHeight,
      scrollWidth: document.documentElement.scrollWidth,
      scrollHeight: document.documentElement.scrollHeight,
      headerTop: rect?.top ?? null,
      headerBottom: rect?.bottom ?? null,
      headerHeight: rect?.height ?? null,
    };
  });
}

async function findVideoPath(page: Page) {
  const configured = process.env.NFB_E2E_VIDEO_PATH?.trim();
  if (configured)
    return configured.startsWith("/") ? configured : `/${configured}`;
  return page.evaluate(async () => {
    type Entry = {
      path?: string;
      type?: string;
      isDir?: boolean;
      items?: Entry[];
    };
    const queue: Array<{ path: string; depth: number }> = [
      { path: "/", depth: 0 },
    ];
    const seen = new Set<string>();
    let requests = 0;
    while (queue.length && requests < 80) {
      const current = queue.shift()!;
      if (seen.has(current.path) || current.depth > 4) continue;
      seen.add(current.path);
      requests++;
      const response = await fetch(`/api/resources${current.path}`);
      if (!response.ok) continue;
      const resource = (await response.json()) as Entry;
      for (const item of resource.items ?? []) {
        if (item.type === "video" && !item.isDir && item.path) return item.path;
        if (item.isDir && item.path)
          queue.push({ path: `${item.path}/`, depth: current.depth + 1 });
      }
    }
    return "";
  });
}

async function isAdmin(page: Page) {
  return page.evaluate(() => {
    const token = localStorage.getItem("jwt");
    if (!token) return false;
    try {
      const payload = JSON.parse(
        atob(token.split(".")[1].replace(/-/g, "+").replace(/_/g, "/"))
      );
      return payload.user?.perm?.admin === true;
    } catch {
      return false;
    }
  });
}

test.describe("NAS File Browser real deployment acceptance", () => {
  test.skip(
    mode !== "real",
    "只在 NFB_E2E_MODE=real 且提供真实验收账号时运行；默认 fixture 门禁不修改真实数据"
  );

  test("covers real pages, navigation, responsive geometry and media", async ({
    page,
  }, testInfo: TestInfo) => {
    test.setTimeout(600_000);
    const consoleErrors: string[] = [];
    const mediaWarnings: string[] = [];
    const pageErrors: string[] = [];
    const failedResponses: string[] = [];
    const measurements: Array<Record<string, unknown>> = [];
    page.on("console", (message) => {
      if (message.type() !== "error") return;
      const text = message.text();
      // The NAS fixture currently exposes an H.264/AAC MP4. The bundled
      // ARM64 Chromium may lack those proprietary decoders and Video.js then
      // emits CODE:4; the compatibility panel is the expected fallback.
      if (/VIDEOJS: ERROR: \(CODE:4 MEDIA_ERR_SRC_NOT_SUPPORTED\)/.test(text)) {
        mediaWarnings.push(text);
      } else {
        consoleErrors.push(text);
      }
    });
    page.on("pageerror", (error) => pageErrors.push(error.message));
    page.on("response", (response) => {
      if (response.url().includes("/api/") && response.status() >= 400) {
        failedResponses.push(
          `${response.status()} ${new URL(response.url()).pathname}`
        );
      }
    });

    await login(page);
    const admin = await isAdmin(page);

    await page.goto("/files/?sort=name&order=desc&view=details", {
      waitUntil: "domcontentloaded",
    });
    await expect(page.locator(".header-task-center")).toBeVisible({
      timeout: 20_000,
    });
    await page.goto("/trash", { waitUntil: "domcontentloaded" });
    await expect(page.getByRole("heading", { name: "回收站" })).toBeVisible({
      timeout: 20_000,
    });
    await page.reload({ waitUntil: "domcontentloaded" });
    await page.getByRole("button", { name: "返回上一页" }).click();
    await expect(page).toHaveURL(/sort=name/);
    await expect(page).toHaveURL(/order=desc/);
    await expect(page).toHaveURL(/view=details/);

    await page.goto("/trash", { waitUntil: "domcontentloaded" });
    const more = page.getByRole("button", { name: "更多" });
    await more.click();
    await expect(page.locator("#dropdown")).toHaveClass(/active/);
    await page.mouse.click(20, 200);
    await expect(page.locator("#dropdown")).not.toHaveClass(/active/);

    await page.setViewportSize({ width: 1280, height: 800 });
    const handle = page.getByRole("separator", { name: "调整侧栏宽度" });
    const sidebar = page.locator(".sidebar-frame");
    const handleBox = await handle.boundingBox();
    const sidebarBox = await sidebar.boundingBox();
    expect(handleBox).not.toBeNull();
    expect(sidebarBox).not.toBeNull();
    expect(handleBox?.width).toBeGreaterThanOrEqual(19);
    expect(
      Math.abs(
        (handleBox?.x ?? 0) +
          (handleBox?.width ?? 0) / 2 -
          ((sidebarBox?.x ?? 0) + (sidebarBox?.width ?? 0))
      )
    ).toBeLessThanOrEqual(1);
    if (admin) {
      await page.getByRole("button", { name: "目录分类", exact: true }).click();
      await expect(
        page.getByRole("button", { name: "NAS 根目录", exact: true })
      ).toBeVisible();
    }

    const routes = [
      { name: "files", path: "/files/", title: "" },
      { name: "search", path: "/search", title: "搜索" },
      { name: "recent", path: "/recent", title: "最近访问" },
      { name: "trash", path: "/trash", title: "回收站" },
      { name: "analysis", path: "/analysis", title: "存储工具" },
      { name: "tasks", path: "/tasks", title: "任务中心" },
      { name: "settings", path: "/settings/profile", title: "设置" },
    ];
    for (const theme of themes) {
      for (const target of routes) {
        await page.goto(target.path, { waitUntil: "domcontentloaded" });
        await setTheme(page, theme);
        if (target.title) {
          await expect(page.locator(".page-title h1")).toHaveText(
            target.title,
            {
              timeout: 20_000,
            }
          );
          await expect(page.locator(".header-task-center")).toHaveCount(0);
        } else {
          await expect(page.locator(".header-task-center")).toBeVisible({
            timeout: 20_000,
          });
        }
        const targetViewports =
          target.name === "settings"
            ? [...viewports, ...settingsViewports]
            : viewports;
        for (const viewport of targetViewports) {
          await page.setViewportSize({
            width: viewport.width,
            height: viewport.height,
          });
          await page.waitForTimeout(220);
          const metrics = await geometry(page);
          expect(metrics.scrollWidth).toBeLessThanOrEqual(
            metrics.innerWidth + 1
          );
          measurements.push({
            theme,
            route: target.name,
            viewport: viewport.name,
            ...metrics,
          });
          await page.screenshot({
            path: testInfo.outputPath(
              `screenshots/${theme}-${viewport.name}-${target.name}.png`
            ),
            // Real NAS data can produce long lists and repeated full-page
            // captures eventually exhaust Chromium's screenshot surface. A
            // viewport capture keeps every responsive state inspectable while
            // the full document geometry is recorded above.
            fullPage: false,
          });
        }
      }
    }

    const videoPath = await findVideoPath(page);
    if (!videoPath)
      throw new Error(
        "未找到可访问视频；设置 NFB_E2E_VIDEO_PATH 后重跑真实视频验收"
      );
    await page.goto(`/files${encodeURI(videoPath)}`, {
      waitUntil: "domcontentloaded",
    });
    await expect(page.locator("#previewer")).toBeVisible({ timeout: 30_000 });
    for (const theme of themes) {
      await setTheme(page, theme);
      for (const viewport of viewports) {
        await page.setViewportSize({
          width: viewport.width,
          height: viewport.height,
        });
        await page.waitForTimeout(220);
        const metrics = await geometry(page);
        expect(metrics.scrollWidth).toBeLessThanOrEqual(metrics.innerWidth + 1);
        measurements.push({
          theme,
          route: "video",
          viewport: viewport.name,
          ...metrics,
        });
        await page.screenshot({
          path: testInfo.outputPath(
            `screenshots/${theme}-${viewport.name}-video.png`
          ),
          fullPage: false,
        });
      }
    }

    const report = {
      dataSource: "real-deployment",
      themes,
      viewports,
      settingsViewports,
      videoPath,
      measurements,
      consoleErrors,
      pageErrors,
      failedResponses,
      mediaWarnings,
      generatedAt: new Date().toISOString(),
    };
    const reportPath = testInfo.outputPath("real-acceptance.json");
    mkdirSync(reportPath.slice(0, reportPath.lastIndexOf("/")), {
      recursive: true,
    });
    writeFileSync(reportPath, JSON.stringify(report, null, 2));
    await testInfo.attach("real-acceptance", {
      path: reportPath,
      contentType: "application/json",
    });
    expect(consoleErrors).toEqual([]);
    expect(pageErrors).toEqual([]);
    expect(failedResponses).toEqual([]);
  });
});
