import { expect, test } from "@playwright/test";

const videoPath = process.env.NFB_E2E_SUBTITLE_VIDEO_PATH || "";
const coldVideoPath = process.env.NFB_E2E_COLD_VIDEO_PATH || "";
const username = process.env.NFB_E2E_USERNAME || "";
const password = process.env.NFB_E2E_PASSWORD || "";

test("ArtPlayer 字幕在原生设置菜单内完成选择和样式调整", async ({
  page,
}, testInfo) => {
  test.skip(
    process.env.NFB_E2E_MODE !== "real" || !videoPath || !username || !password,
    "需要真实 NAS 与同目录视频、VTT、SRT 验收样本"
  );

  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto("/login?redirect=%2Ffiles%2F");
  await page.getByPlaceholder("用户名").fill(username);
  await page.getByPlaceholder("密码").fill(password);
  await page.getByRole("button", { name: "登录" }).click();
  await expect(page).toHaveURL(/\/files\/?/);
  await page.goto(`/files${videoPath}`);
  const player = page.locator(".art-video-player");
  await expect(player).toBeVisible();
  const video = page.locator(".art-video");
  await expect
    .poll(
      async () =>
        video.evaluate((element: HTMLVideoElement) => element.readyState),
      { timeout: 90_000 }
    )
    .toBeGreaterThanOrEqual(2);
  await video.evaluate((element: HTMLVideoElement) => {
    element.pause();
    element.currentTime = 1;
  });
  await expect(page.locator(".player-tools")).toHaveCount(0);
  await expect(page.locator(".art-player-stage")).toBeVisible();
  await expect(page.locator(".art-control-playback-mode")).toBeVisible();
  await expect(page.locator(".art-control-playback-rate")).toBeVisible();
  await expect(page.locator(".art-control-playback-quality")).toBeVisible();

  async function openSubtitles() {
    // ArtPlayer finishes returning from the previous nested selection before
    // opening a new panel; reopening mid-transition gets reset to the root.
    await page.waitForTimeout(350);
    const activeSubtitleMenu = page.locator(
      '.art-setting-panel.art-current .art-setting-item[data-name="sub-offset"]'
    );
    if (!(await activeSubtitleMenu.isVisible())) {
      const subtitleRow = page.locator(
        '.art-setting-panel.art-current .art-setting-item[data-name="playback-subtitle"]'
      );
      if (!(await subtitleRow.isVisible()))
        await page.locator(".art-control-setting").click();
      if (!(await subtitleRow.isVisible()))
        await page.locator(".art-control-setting").click();
      await subtitleRow.click();
    }
    await expect(activeSubtitleMenu).toBeVisible();
  }

  await openSubtitles();
  const currentPanel = page.locator(".art-setting-panel.art-current");
  await expect(
    currentPanel.locator('.art-setting-item[data-name="sub-pick"]')
  ).toBeVisible();
  await expect(
    currentPanel.locator('.art-setting-item[data-name="sub-size"]')
  ).toBeVisible();
  await expect(
    currentPanel.locator('.art-setting-item[data-name="sub-pos"]')
  ).toBeVisible();
  await expect(
    currentPanel.locator('.art-setting-item[data-name="sub-offset"]')
  ).toBeVisible();
  await currentPanel
    .locator('.art-setting-item[data-name="sub-track-0"]')
    .click();
  await page.locator(".art-video").evaluate((video: HTMLVideoElement) => {
    video.currentTime = 1;
  });
  await expect(page.locator(".art-subtitle")).toContainText("菜单字幕验收");

  await openSubtitles();
  await currentPanel
    .locator('.art-setting-item[data-name="sub-track-1"]')
    .click();
  await page.locator(".art-video").evaluate((video: HTMLVideoElement) => {
    video.currentTime = 1;
  });
  await expect(page.locator(".art-subtitle")).toContainText("SRT 字幕验收");

  await openSubtitles();
  await currentPanel.locator('.art-setting-item[data-name="sub-size"]').click();
  await expect(
    currentPanel.locator('.art-setting-item[data-name="sub-size-lg"]')
  ).toBeVisible();
  await page.waitForTimeout(300);
  await currentPanel
    .locator('.art-setting-item[data-name="sub-size-lg"]')
    .click();
  await expect(player).toHaveCSS("--art-subtitle-font-size", "28px");

  await openSubtitles();
  await currentPanel.locator('.art-setting-item[data-name="sub-pos"]').click();
  await expect(
    currentPanel.locator('.art-setting-item[data-name="sub-pos-80"]')
  ).toBeVisible();
  await page.waitForTimeout(300);
  await currentPanel
    .locator('.art-setting-item[data-name="sub-pos-80"]')
    .click();
  await expect(player).toHaveCSS("--art-subtitle-bottom", "80px");

  await openSubtitles();
  const offset = currentPanel.locator(
    '.art-setting-item[data-name="sub-offset"] input'
  );
  await offset.focus();
  await offset.press("ArrowRight");
  await expect
    .poll(async () =>
      page.evaluate(() => {
        const key = Object.keys(localStorage).find((entry) =>
          entry.startsWith("nas-file-browser-subtitle-v1:")
        );
        return key
          ? JSON.parse(localStorage.getItem(key) || "{}").offset
          : null;
      })
    )
    .toBe(0.1);

  await openSubtitles();
  await currentPanel.locator('.art-setting-item[data-name="sub-pick"]').click();
  await expect(
    page.getByRole("dialog", { name: "选择外挂字幕文件" })
  ).toBeVisible();
  await page.getByRole("button", { name: "关闭路径选择器" }).click();
  await openSubtitles();
  await expect(
    currentPanel.locator('.art-setting-item[data-name="sub-track-1"]')
  ).toHaveClass(/art-current/);
  await expect(
    currentPanel.locator('.art-setting-item[data-name="sub-pick"]')
  ).not.toHaveClass(/art-current/);

  await testInfo.attach("desktop-subtitle-player", {
    body: await page.screenshot(),
    contentType: "image/png",
  });

  await page.setViewportSize({ width: 390, height: 844 });
  await expect(page.locator(".player-tools")).toHaveCount(0);
  await page.waitForTimeout(250);
  await openSubtitles();
  await expect(
    currentPanel.locator('.art-setting-item[data-name="sub-offset"]')
  ).toBeVisible();
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth
    )
  ).toBe(true);
  await testInfo.attach("mobile-subtitle-menu", {
    body: await page.screenshot(),
    contentType: "image/png",
  });
  await openSubtitles();
  await currentPanel.locator('.art-setting-item[data-name="sub-off"]').click();
  await page.reload();
  await expect(page.locator(".art-video-player")).toBeVisible();
  await openSubtitles();
  await expect(
    currentPanel.locator('.art-setting-item[data-name="sub-off"]')
  ).toHaveClass(/art-current/);
});

test("Windows 来源播放器的倍速、原生与兼容、分辨率控制可用", async ({
  page,
}, testInfo) => {
  test.skip(
    process.env.NFB_E2E_MODE !== "real" || !videoPath || !username || !password,
    "需要真实 NAS 媒体验收样本"
  );
  test.setTimeout(180_000);
  const requestedQualities: string[] = [];
  page.on("request", (request) => {
    if (
      request.method() !== "POST" ||
      !request.url().endsWith("/api/media/hls")
    )
      return;
    const body = request.postDataJSON() as { quality?: string };
    requestedQualities.push(body.quality || "source");
  });
  await page.route("**/api/users/*", async (route) => {
    if (route.request().method() === "PUT")
      await route.fulfill({ status: 200, body: "" });
    else await route.continue();
  });
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto("/login?redirect=%2Ffiles%2F");
  await page.getByPlaceholder("用户名").fill(username);
  await page.getByPlaceholder("密码").fill(password);
  await page.getByRole("button", { name: "登录" }).click();
  await expect(page).toHaveURL(/\/files\/?/);
  await page.goto(`/files${videoPath}`);
  const video = page.locator(".art-video");
  await expect(video).toBeVisible();
  await video.evaluate((element: HTMLVideoElement) => element.pause());

  const rate = page.locator(".art-control-playback-rate");
  await rate.click();
  await rate.locator('.art-selector-item[data-value="1.25"]').click();
  await expect(video).toHaveJSProperty("playbackRate", 1.25);
  await rate.click();
  await rate.locator('.art-selector-item[data-value="custom"]').click();
  const customRate = page.getByRole("dialog", { name: "自定义倍速" });
  await expect(customRate).toBeVisible();
  await customRate.locator('input[type="number"]').fill("1.37");
  await customRate.getByRole("button", { name: "应用" }).click();
  await expect(video).toHaveJSProperty("playbackRate", 1.37);

  const mode = page.locator(".art-control-playback-mode");
  await mode.click();
  await mode.locator('.art-selector-item[data-value="compat"]').click();
  await expect(mode).toContainText("兼容");
  await expect
    .poll(
      async () =>
        video.evaluate((element: HTMLVideoElement) => element.currentSrc),
      {
        timeout: 120_000,
      }
    )
    .toMatch(/\/api\/media\/hls\//);
  await expect(video).toHaveJSProperty("playbackRate", 1.37);
  const compatSource = await video.evaluate(
    (element: HTMLVideoElement) => element.currentSrc
  );
  const quality = page.locator(".art-control-playback-quality");
  await quality.click();
  await quality.locator('.art-selector-item[data-value="source"]').click();
  await expect.poll(() => requestedQualities).toContain("source");
  await expect
    .poll(
      async () =>
        video.evaluate((element: HTMLVideoElement) => element.currentSrc),
      { timeout: 120_000 }
    )
    .not.toBe(compatSource);
  const sourceQualityUrl = await video.evaluate(
    (element: HTMLVideoElement) => element.currentSrc
  );
  await expect(video).toHaveJSProperty("playbackRate", 1.37);
  await quality.click();
  await expect(
    quality.locator('.art-selector-item[data-value="480p"]')
  ).toBeVisible();
  await quality.locator('.art-selector-item[data-value="480p"]').click();
  await expect(quality).toContainText("480p");
  await expect.poll(() => requestedQualities).toContain("480p");
  await expect
    .poll(
      async () =>
        video.evaluate((element: HTMLVideoElement) => element.currentSrc),
      {
        timeout: 120_000,
      }
    )
    .not.toBe(sourceQualityUrl);
  await expect
    .poll(async () =>
      video.evaluate((element: HTMLVideoElement) => element.currentSrc)
    )
    .toMatch(/\/api\/media\/hls\//);
  await expect(video).toHaveJSProperty("playbackRate", 1.37);

  await testInfo.attach("desktop-player-controls", {
    body: await page.screenshot(),
    contentType: "image/png",
  });

  await mode.click();
  await mode.locator('.art-selector-item[data-value="native"]').click();
  await expect(mode).toContainText("原生");
  await expect
    .poll(async () =>
      video.evaluate((element: HTMLVideoElement) => element.currentSrc)
    )
    .toMatch(/\/api\/raw\//);
  await expect(video).toHaveJSProperty("playbackRate", 1.37);
});

test("兼容冷启动不会在媒体就绪时关闭已打开的倍速菜单", async ({ page }) => {
  test.skip(
    process.env.NFB_E2E_MODE !== "real" ||
      !coldVideoPath ||
      !username ||
      !password,
    "需要未转码过的 NAS 媒体路径验证兼容冷启动"
  );
  test.setTimeout(120_000);
  await page.route("**/api/users/*", async (route) => {
    if (route.request().method() === "PUT")
      await route.fulfill({ status: 200, body: "" });
    else await route.continue();
  });
  await page.goto("/login?redirect=%2Ffiles%2F");
  await page.getByPlaceholder("用户名").fill(username);
  await page.getByPlaceholder("密码").fill(password);
  await page.getByRole("button", { name: "登录" }).click();
  await expect(page).toHaveURL(/\/files\/?/);
  const savedMode = await page.evaluate(() => {
    const token = localStorage.getItem("jwt") || "";
    const encoded = (token.split(".")[1] || "")
      .replace(/-/g, "+")
      .replace(/_/g, "/");
    const payload = JSON.parse(atob(encoded));
    return payload.user?.playerPreferences?.playbackMode;
  });
  test.skip(savedMode !== "compat", "冷启动用例需要账号默认使用兼容模式");
  await page.goto(`/files${coldVideoPath}`);

  const video = page.locator(".art-video");
  const rate = page.locator(".art-control-playback-rate");
  await expect(rate).toBeVisible();
  await expect(video).toHaveJSProperty("readyState", 0);
  await rate.click();
  await expect(rate).toHaveClass(/art-selector-open/);
  await rate.locator('.art-selector-item[data-value="1.5"]').click();
  await expect(video).toHaveJSProperty("playbackRate", 1.5);
  await expect
    .poll(
      async () =>
        video.evaluate((element: HTMLVideoElement) => element.readyState),
      { timeout: 90_000 }
    )
    .toBeGreaterThanOrEqual(2);
  await expect(video).toHaveJSProperty("playbackRate", 1.5);
});
