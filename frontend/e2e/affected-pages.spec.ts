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
  { name: "phone-boundary-wide", width: 737, height: 900 },
  { name: "phone-boundary", width: 736, height: 900 },
  { name: "phone", width: 430, height: 932 },
  { name: "phone-small", width: 390, height: 844 },
  { name: "phone-narrow", width: 360, height: 800 },
];

const now = Date.now();

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

async function json(route: Route, data: unknown, status = 200) {
  await route.fulfill({
    status,
    contentType: "application/json; charset=utf-8",
    body: JSON.stringify(data),
  });
}

function directoryResource(pathname: string) {
  const path =
    decodeURIComponent(pathname.replace(/^\/api\/resources/, "")) || "/";
  return {
    path,
    name: path === "/" ? "" : path.split("/").filter(Boolean).at(-1) || "",
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
  };
}

function videoResource() {
  return {
    path: "/fixture-video.mkv",
    name: "fixture-video.mkv",
    size: 64 * 1024 * 1024,
    extension: ".mkv",
    modified: new Date(now - 86_400_000).toISOString(),
    created: new Date(now - 172_800_000).toISOString(),
    mode: 0,
    isDir: false,
    isSymlink: false,
    type: "video",
    riskLevel: "low",
    subtitles: [],
    index: 0,
  };
}

async function installFixtureApi(page: Page, unknownRequests: string[]) {
  const token = fixtureToken();
  const user = JSON.parse(
    Buffer.from(token.split(".")[1], "base64url").toString("utf8")
  ).user;
  let favorite = false;

  await page.route(
    (url) => url.pathname.startsWith("/api/"),
    async (route) => {
      const request = route.request();
      const url = new URL(request.url());
      const path = url.pathname;
      const method = request.method();

      if (path === "/api/login" || path === "/api/renew") {
        await route.fulfill({
          status: 200,
          contentType: "text/plain",
          body: token,
        });
        return;
      }
      if (path === "/api/task-center/events") {
        await route.fulfill({
          status: 200,
          contentType: "text/event-stream; charset=utf-8",
          body: ": fixture connected\n\n",
        });
        return;
      }
      if (path === "/api/users/1") return json(route, user);
      if (path === "/api/settings") {
        return json(route, {
          signup: false,
          createUserDir: false,
          hideLoginButton: false,
          minimumPasswordLength: 6,
          userHomeBasePath: "/users",
          defaults: { ...user, sorting: { by: "name", asc: true } },
          authMethod: "json",
          rules: [],
          branding: {
            name: "NAS 文件浏览器",
            disableExternal: false,
            disableUsedPercentage: false,
            files: "",
            theme: "light",
            color: "",
          },
          tus: { chunkSize: 10 * 1024 * 1024, retryCount: 5 },
          shell: [],
          commands: {},
          tokenExpirationTime: "2h",
        });
      }
      if (path === "/api/tasks" || path === "/api/tasks/summary") {
        const counts = {
          all: 0,
          active: 0,
          attention: 0,
          canceled: 0,
          completed: 0,
          archived: 0,
        };
        return json(route, {
          items: [],
          nextCursor: "",
          total: 0,
          counts,
          categoryCounts: { file: counts, background: counts },
          owners: [],
        });
      }
      if (path === "/api/transfers")
        return json(route, { items: [], total: 0 });
      if (path === "/api/history")
        return json(route, { items: [], total: 0, nextCursor: "" });
      if (path === "/api/trash" && method === "GET") {
        return json(route, [
          {
            id: "trash-file",
            userId: 1,
            ownerName: "fixture",
            originalPath: "/项目/说明.md",
            name: "/项目/说明.md",
            isDir: false,
            size: 4096,
            sizeState: "accurate",
            deletedAt: now - 3_600_000,
            status: "available",
          },
          {
            id: "trash-dir-calculating",
            userId: 1,
            ownerName: "fixture",
            originalPath: "/项目/素材",
            name: "/项目/素材",
            isDir: true,
            size: 0,
            sizeState: "calculating",
            sizeTaskId: "fixture-size-task",
            deletedAt: now - 7_200_000,
            status: "available",
          },
          {
            id: "trash-dir-unknown",
            userId: 1,
            ownerName: "fixture",
            originalPath: "/旧记录",
            name: "/旧记录",
            isDir: true,
            size: 0,
            sizeState: "unknown",
            deletedAt: now - 86_400_000,
            status: "available",
          },
        ]);
      }
      if (path === "/api/analysis/recent") {
        return json(route, {
          items: [
            {
              id: "scan-fixture",
              tool: url.searchParams.get("tool") || "duplicates",
              status: "completed",
              createdAt: now - 120_000,
              finishedAt: now - 60_000,
              scopes: ["/项目/照片", "/项目/视频"],
              processedItems: 24,
              totalItems: 24,
              resultReady: true,
              metrics: {
                scannedFiles: 24,
                scannedDirectories: 4,
                scannedBytes: 128 * 1024 * 1024,
                duplicateGroups: 3,
                reclaimableBytes: 32 * 1024 * 1024,
              },
            },
          ],
          nextCursor: "",
        });
      }
      if (path.startsWith("/api/resources")) {
        return json(
          route,
          path.includes("fixture-video.mkv")
            ? videoResource()
            : directoryResource(path)
        );
      }
      if (path === "/api/media/playback") {
        return json(route, {
          exists: false,
          path: "/fixture-video.mkv",
          position: 0,
          duration: 0,
          updatedAt: now,
        });
      }
      if (path === "/api/media/info") {
        return json(route, {
          videoCodec: "hevc",
          audioCodec: "aac",
          duration: 120,
          resolution: { width: 3840, height: 1600 },
          audioTracks: [
            { index: 1, codec: "truehd", language: "eng", title: "英语 Atmos" },
            { index: 2, codec: "ac3", language: "eng", title: "英语 AC-3" },
            { index: 3, codec: "dts", language: "chi", title: "长影国配 DTS" },
            { index: 4, codec: "ac3", language: "chi", title: "长影国配 AC-3" },
          ],
          subtitleTracks: [
            {
              index: 5,
              codec: "hdmv_pgs_subtitle",
              language: "eng",
              title: "英文",
            },
            {
              index: 6,
              codec: "hdmv_pgs_subtitle",
              language: "chi",
              title: "简中特效",
            },
            {
              index: 7,
              codec: "hdmv_pgs_subtitle",
              language: "chi",
              title: "中英特效",
            },
            {
              index: 8,
              codec: "hdmv_pgs_subtitle",
              language: "chi",
              title: "简体中文",
            },
            {
              index: 9,
              codec: "hdmv_pgs_subtitle",
              language: "chi",
              title: "繁体中文",
            },
            {
              index: 10,
              codec: "hdmv_pgs_subtitle",
              language: "chi",
              title: "繁体中文",
            },
          ],
        });
      }
      if (path === "/api/media/sprite") return json(route, {});
      if (path === "/api/media/hls") return json(route, {});
      if (path === "/api/favorites/groups") return json(route, []);
      if (path === "/api/favorites") {
        if (method === "POST") {
          favorite = true;
          return json(route, {
            id: "favorite-video",
            path: "/fixture-video.mkv",
            name: "fixture-video.mkv",
            addedAt: now,
            order: 0,
          });
        }
        return json(
          route,
          favorite
            ? [
                {
                  id: "favorite-video",
                  path: "/fixture-video.mkv",
                  name: "fixture-video.mkv",
                  addedAt: now,
                  order: 0,
                },
              ]
            : []
        );
      }
      if (
        [
          "/api/tags",
          "/api/categories",
          "/api/volumes",
          "/api/recent",
        ].includes(path)
      ) {
        return json(route, []);
      }

      unknownRequests.push(`${method} ${path}`);
      await json(route, {});
    }
  );
}

async function login(page: Page) {
  await page.goto(
    "/login?redirect=%2Ffiles%2F%E9%A1%B9%E7%9B%AE%2F%E7%B4%A0%E6%9D%90%3Fsort%3Dname%26order%3Ddesc%26view%3Ddetails",
    {
      waitUntil: "networkidle",
    }
  );
  await page.getByPlaceholder("用户名").fill("fixture");
  await page.getByPlaceholder("密码").fill("fixture");
  await page.getByRole("button", { name: "登录" }).click();
  await expect(page).toHaveURL(/\/files\/.*%E9%A1%B9%E7%9B%AE|\/files\/项目/);
  await expect(page.getByLabel("当前实例")).toContainText("DH4300Plus");
}

async function setTheme(page: Page, theme: Theme) {
  await page.evaluate((nextTheme) => {
    document.documentElement.className = nextTheme;
  }, theme);
}

async function geometry(page: Page) {
  return page.evaluate(() => {
    const header = document.querySelector("main header");
    const rect = header?.getBoundingClientRect();
    return {
      innerWidth: window.innerWidth,
      innerHeight: window.innerHeight,
      scrollWidth: document.documentElement.scrollWidth,
      headerTop: rect?.top ?? null,
      headerBottom: rect?.bottom ?? null,
      headerHeight: rect?.height ?? null,
    };
  });
}

test.describe("affected page browser gate", () => {
  test("covers file navigation, trash status and sidebar controls", async ({
    page,
  }) => {
    test.setTimeout(60_000);
    const unknownRequests: string[] = [];
    await installFixtureApi(page, unknownRequests);
    await login(page);

    await page.goto(
      "/files/%E9%A1%B9%E7%9B%AE/%E7%B4%A0%E6%9D%90?sort=name&order=desc&view=details",
      { waitUntil: "networkidle" }
    );
    await expect(page).toHaveURL(/sort=name/);

    // The More menu belongs to the file view, not the trash view.
    await page.getByRole("button", { name: "更多" }).click();
    await expect(page.locator("#dropdown")).toHaveClass(/active/);
    await page.mouse.click(300, 300);
    await expect(page.locator("#dropdown")).not.toHaveClass(/active/);

    const moreButton = page.getByRole("button", { name: "更多" });
    await moreButton.click();
    await page
      .locator("#dropdown")
      .getByRole("button", { name: "下载" })
      .focus();
    await expect(page.locator("#dropdown")).toHaveClass(/active/);
    await page.keyboard.press("Escape");
    await expect(page.locator("#dropdown")).not.toHaveClass(/active/);
    await expect(moreButton).toBeFocused();

    await moreButton.click();
    await page
      .locator("#dropdown")
      .getByRole("button", { name: "下载" })
      .focus();
    await page.getByRole("searchbox", { name: "在当前目录搜索" }).focus();
    await expect(page.locator("#dropdown")).not.toHaveClass(/active/);

    await expect(page.locator(".shell")).toHaveCSS("visibility", "hidden");
    await moreButton.click();
    await page
      .locator("#dropdown")
      .getByRole("button", { name: "终端" })
      .click();
    await expect(page.locator(".shell")).toHaveCSS("visibility", "visible");

    await page.goto("/trash");
    await expect(page.locator(".shell")).toHaveCSS("visibility", "hidden");
    await expect(page.getByRole("heading", { name: "回收站" })).toBeVisible();
    await expect(page.getByText("统计中", { exact: true })).toBeVisible();
    await expect(page.getByText("未统计", { exact: true })).toBeVisible();
    await expect(page.getByRole("button", { name: "补算" })).toBeVisible();

    await page.reload({ waitUntil: "networkidle" });
    await page.goBack();
    await expect(page).toHaveURL(/\/files\/.*%E9%A1%B9%E7%9B%AE|\/files\/项目/);
    await expect(page).toHaveURL(/sort=name/);
    await expect(page).toHaveURL(/order=desc/);
    await expect(page).toHaveURL(/view=details/);

    await page.setViewportSize({ width: 1280, height: 800 });
    const sidebar = page.locator(".sidebar-frame");
    const resizeHandle = page.getByRole("separator", { name: "调整侧栏宽度" });
    const sidebarBox = await sidebar.boundingBox();
    const handleBox = await resizeHandle.boundingBox();
    expect(sidebarBox).not.toBeNull();
    expect(handleBox).not.toBeNull();
    expect(handleBox?.width).toBeGreaterThanOrEqual(19);
    expect(
      Math.abs(
        (handleBox?.x ?? 0) +
          (handleBox?.width ?? 0) / 2 -
          ((sidebarBox?.x ?? 0) + (sidebarBox?.width ?? 0))
      )
    ).toBeLessThanOrEqual(1);
    const initialWidth = Number(
      await resizeHandle.getAttribute("aria-valuenow")
    );
    await resizeHandle.focus();
    await page.keyboard.press("ArrowRight");
    await expect(resizeHandle).toHaveAttribute(
      "aria-valuenow",
      String(initialWidth + 10)
    );
    const dragBox = await resizeHandle.boundingBox();
    expect(dragBox).not.toBeNull();
    await page.mouse.move(
      (dragBox?.x ?? 0) + (dragBox?.width ?? 0) / 2,
      (dragBox?.y ?? 0) + 100
    );
    await page.mouse.down();
    await page.mouse.move((dragBox?.x ?? 0) + 90, (dragBox?.y ?? 0) + 100);
    await page.mouse.up();
    expect(
      Number(await resizeHandle.getAttribute("aria-valuenow"))
    ).toBeGreaterThan(initialWidth + 40);
    await resizeHandle.dblclick();
    await expect(resizeHandle).toHaveAttribute("aria-valuenow", "288");

    await page.getByRole("button", { name: "目录分类", exact: true }).click();
    await expect(
      page.getByRole("button", { name: "根目录", exact: true })
    ).toBeVisible();

    expect(unknownRequests).toEqual([]);
  });

  test("covers storage analysis and media state", async ({ page }) => {
    test.setTimeout(60_000);
    const unknownRequests: string[] = [];
    await installFixtureApi(page, unknownRequests);
    await login(page);

    await page.goto(
      "/analysis?tool=duplicates&paths=%2F%E9%A1%B9%E7%9B%AE%2F%E7%85%A7%E7%89%87"
    );
    await expect(page.getByRole("heading", { name: "存储工具" })).toBeVisible();
    await expect(page.getByRole("heading", { name: "扫描范围" })).toBeVisible();
    await expect(page.getByText("最近扫描", { exact: true })).toBeVisible();
    await page.getByRole("button", { name: "添加范围" }).click();
    await expect(
      page.getByRole("dialog", { name: "选择分析范围" })
    ).toBeVisible();
    await page.getByRole("button", { name: "关闭路径选择器" }).click();

    await page.goto("/files/fixture-video.mkv");
    await expect(
      page.locator(".header-title", { hasText: "fixture-video.mkv" })
    ).toBeVisible();
    await expect(page.locator(".art-player-stage")).toBeVisible();
    await expect(page.getByText("4K", { exact: true }).first()).toBeVisible();
    const favoriteAction = page.getByRole("button", {
      name: "收藏",
      exact: true,
    });
    await expect(favoriteAction).toHaveAttribute("aria-pressed", "false");
    await favoriteAction.click();
    await expect(
      page.locator("button.icon-button[aria-label='取消收藏']")
    ).toHaveAttribute("aria-pressed", "true");
    await expect(page.locator(".header-task-center")).toHaveCount(0);

    expect(unknownRequests).toEqual([]);
  });

  test("lists six embedded PGS tracks and sends the chosen track to compatibility playback", async ({
    page,
  }) => {
    test.setTimeout(75_000);
    const unknownRequests: string[] = [];
    const requests: Array<Record<string, unknown>> = [];
    await installFixtureApi(page, unknownRequests);
    await page.route(/\/api\/media\/hls(?:\?|$)/, async (route) => {
      if (route.request().method() === "POST") {
        requests.push(JSON.parse(route.request().postData() || "{}"));
      }
      await json(route, { state: "failed", error: "fixture 不启动转码" });
    });
    await login(page);
    await page.goto("/files/fixture-video.mkv");
    await expect(page.locator(".art-player-stage")).toBeVisible();
    const panel = page.locator(".art-setting-panel.art-current");
    async function openSubtitles() {
      await page.waitForTimeout(350);
      if (await panel.locator('[data-name="sub-off"]').isVisible()) return;
      const row = panel.locator('[data-name="playback-subtitle"]');
      if (!(await row.isVisible()))
        await page.locator(".art-control-setting").click();
      await row.click();
    }
    await openSubtitles();
    await expect(panel.locator('[data-name^="sub-track-"]')).toHaveCount(6);
    await expect(panel.locator('[data-name="sub-track-1"]')).toContainText(
      "简中特效"
    );
    await panel.locator('[data-name="sub-track-1"]').click();
    await expect.poll(() => requests.length).toBeGreaterThanOrEqual(1);
    expect(requests.at(-1)).toMatchObject({ subtitleStreamIndex: 6 });

    await openSubtitles();
    await expect(panel.locator('[data-name="sub-pgs-info"]')).toBeVisible();
    await expect(panel.locator('[data-name="sub-size"]')).toHaveCount(0);
    await panel.locator('[data-name="sub-track-2"]').click();
    await expect.poll(() => requests.length).toBeGreaterThanOrEqual(2);
    expect(requests.at(-1)).toMatchObject({ subtitleStreamIndex: 7 });

    await openSubtitles();
    await panel.locator('[data-name="sub-off"]').click();
    await expect.poll(() => requests.length).toBeGreaterThanOrEqual(3);
    expect(requests.at(-1)).not.toHaveProperty("subtitleStreamIndex");
    await openSubtitles();
    await expect(panel.locator('[data-name="sub-size"]')).toBeVisible();
  });

  test("lists four embedded audio tracks and selects the Chinese dub", async ({
    page,
  }) => {
    test.setTimeout(75_000);
    const unknownRequests: string[] = [];
    const requests: Array<Record<string, unknown>> = [];
    await installFixtureApi(page, unknownRequests);
    await page.route(/\/api\/media\/hls(?:\?|$)/, async (route) => {
      if (route.request().method() === "POST") {
        requests.push(JSON.parse(route.request().postData() || "{}"));
      }
      await json(route, { state: "failed", error: "fixture 不启动转码" });
    });
    await login(page);
    await page.goto("/files/fixture-video.mkv");
    await expect(page.locator(".art-player-stage")).toBeVisible();
    await page.locator(".art-control-setting").click();
    const panel = page.locator(".art-setting-panel.art-current");
    await panel.locator('[data-name="playback-audio"]').click();
    await expect(panel.locator('[data-name^="audio-track-"]')).toHaveCount(4);
    await expect(panel.locator('[data-name="audio-track-3"]')).toContainText(
      "长影国配"
    );
    await panel.locator('[data-name="audio-track-3"]').click();
    await expect.poll(() => requests.length).toBeGreaterThanOrEqual(1);
    expect(requests.at(-1)).toMatchObject({ audioStreamIndex: 3 });

    await page.waitForTimeout(350);
    const subtitleRow = panel.locator('[data-name="playback-subtitle"]');
    if (!(await subtitleRow.isVisible()))
      await page.locator(".art-control-setting").click();
    if (!(await subtitleRow.isVisible()))
      await page.locator(".art-control-setting").click();
    await subtitleRow.click();
    await panel.locator('[data-name="sub-track-1"]').click();
    await expect.poll(() => requests.length).toBeGreaterThanOrEqual(2);
    expect(requests.at(-1)).toMatchObject({
      audioStreamIndex: 3,
      subtitleStreamIndex: 6,
    });
  });

  test("captures affected pages across themes and viewport sizes", async ({
    page,
  }, testInfo: TestInfo) => {
    test.setTimeout(180_000);
    const unknownRequests: string[] = [];
    const consoleErrors: string[] = [];
    const pageErrors: string[] = [];
    const failedResponses: string[] = [];
    const measurements: Array<Record<string, unknown>> = [];

    page.on("console", (message) => {
      if (message.type() === "error") consoleErrors.push(message.text());
    });
    page.on("pageerror", (error) => pageErrors.push(error.message));
    page.on("response", (response) => {
      if (response.url().includes("/api/") && response.status() >= 400) {
        failedResponses.push(
          `${response.status()} ${new URL(response.url()).pathname}`
        );
      }
    });
    await installFixtureApi(page, unknownRequests);
    await login(page);

    const routes = [
      { name: "trash", path: "/trash", title: "回收站" },
      {
        name: "analysis",
        path: "/analysis?tool=duplicates&paths=%2F%E9%A1%B9%E7%9B%AE%2F%E7%85%A7%E7%89%87",
        title: "存储工具",
      },
      {
        name: "video",
        path: "/files/fixture-video.mkv",
        title: "fixture-video.mkv",
      },
    ];
    for (const theme of themes) {
      for (const target of routes) {
        await page.goto(target.path, { waitUntil: "networkidle" });
        await setTheme(page, theme);
        await expect(
          page.getByText(target.title, { exact: true }).first()
        ).toBeVisible();
        for (const viewport of viewports) {
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
            viewport: viewport.name,
            route: target.path,
            ...metrics,
          });
          await page.screenshot({
            path: testInfo.outputPath(
              `screenshots/${theme}-${viewport.name}-${target.name}.png`
            ),
            fullPage: true,
          });
        }
      }
    }

    const report = {
      dataSource: "fixture",
      themes,
      viewports,
      measurements,
      unknownRequests: [...new Set(unknownRequests)],
      consoleErrors,
      pageErrors,
      failedResponses,
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

    expect(unknownRequests).toEqual([]);
    expect(consoleErrors).toEqual([]);
    expect(pageErrors).toEqual([]);
    expect(failedResponses).toEqual([]);
  });

  test("captures optimized pages across themes and viewport sizes", async ({
    page,
  }, testInfo: TestInfo) => {
    test.setTimeout(300_000);
    const unknownRequests: string[] = [];
    const consoleErrors: string[] = [];
    const pageErrors: string[] = [];
    const measurements: Array<Record<string, unknown>> = [];
    page.on("console", (message) => {
      if (message.type() === "error") consoleErrors.push(message.text());
    });
    page.on("pageerror", (error) => pageErrors.push(error.message));
    await installFixtureApi(page, unknownRequests);

    for (const theme of themes) {
      await page.goto("/login", { waitUntil: "networkidle" });
      await setTheme(page, theme);
      await expect(page.getByLabel("用户名")).toBeVisible();
      for (const viewport of viewports) {
        await page.setViewportSize(viewport);
        const metrics = await geometry(page);
        expect(metrics.scrollWidth).toBeLessThanOrEqual(metrics.innerWidth + 1);
        measurements.push({
          theme,
          viewport: viewport.name,
          route: "/login",
          ...metrics,
        });
        await page.screenshot({
          path: testInfo.outputPath(
            `optimized/${theme}-${viewport.name}-login.png`
          ),
          fullPage: true,
        });
      }
    }

    await login(page);
    const routes = [
      {
        name: "search",
        path: "/search",
        marker: "输入关键词或选择文件类型，然后开始搜索",
      },
      { name: "archive", path: "/archive", marker: "选择压缩包开始浏览" },
      { name: "tasks", path: "/tasks", marker: "下载记录" },
      { name: "global", path: "/settings/global", marker: "全局设置" },
      { name: "profile", path: "/settings/profile", marker: "账户设置" },
      { name: "user-new", path: "/settings/users/new", marker: "新建用户" },
      {
        name: "files",
        path: "/files/?view=details",
        marker: "这个文件夹是空的",
      },
    ];
    for (const theme of themes) {
      for (const target of routes) {
        await page.goto(target.path, { waitUntil: "networkidle" });
        await setTheme(page, theme);
        await expect(
          page.getByText(target.marker, { exact: true }).first()
        ).toBeVisible();
        for (const viewport of viewports) {
          await page.setViewportSize(viewport);
          await page.waitForTimeout(120);
          const metrics = await geometry(page);
          expect(metrics.scrollWidth).toBeLessThanOrEqual(
            metrics.innerWidth + 1
          );
          measurements.push({
            theme,
            viewport: viewport.name,
            route: target.path,
            ...metrics,
          });
          await page.screenshot({
            path: testInfo.outputPath(
              `optimized/${theme}-${viewport.name}-${target.name}.png`
            ),
            fullPage: true,
          });
        }
      }
    }

    const reportPath = testInfo.outputPath("optimized-audit.json");
    writeFileSync(
      reportPath,
      JSON.stringify(
        {
          dataSource: "fixture",
          themes,
          viewports,
          measurements,
          unknownRequests: [...new Set(unknownRequests)],
          consoleErrors,
          pageErrors,
        },
        null,
        2
      )
    );
    await testInfo.attach("optimized-audit", {
      path: reportPath,
      contentType: "application/json",
    });
    expect(unknownRequests).toEqual([]);
    expect(consoleErrors).toEqual([]);
    expect(pageErrors).toEqual([]);
  });
});
