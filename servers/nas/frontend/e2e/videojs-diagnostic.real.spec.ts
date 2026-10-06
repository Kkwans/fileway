import { expect, test } from "@playwright/test";

const videoPath =
  process.env.NFB_E2E_VIDEOJS_VIDEO_PATH ||
  process.env.NFB_E2E_SUBTITLE_VIDEO_PATH ||
  "";
const username = process.env.NFB_E2E_USERNAME || "";
const password = process.env.NFB_E2E_PASSWORD || "";

test("Video.js 诊断回退保留 Windows 版播放模式和自定义倍速", async ({
  page,
}, testInfo) => {
  test.skip(
    process.env.NFB_E2E_MODE !== "real" || !videoPath || !username || !password,
    "需要真实 NAS 媒体验收样本"
  );
  test.setTimeout(120_000);
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
  await page.goto(`/files${videoPath}?player=videojs`);
  await expect(page.locator(".header-title")).toHaveText(
    decodeURIComponent(videoPath.split("/").pop() || "")
  );

  const video = page.locator(".video-js video");
  await expect(video).toBeVisible();
  await expect
    .poll(
      async () =>
        video.evaluate((element: HTMLVideoElement) => element.readyState),
      { timeout: 90_000 }
    )
    .toBeGreaterThanOrEqual(2);
  await video.evaluate((element: HTMLVideoElement) =>
    element.play().catch(() => {})
  );
  await page.locator(".video-js").hover();
  const mode = page.locator(".vjs-playback-mode-button");
  const rate = page.locator(".vjs-custom-rate input");
  await expect(mode).toBeVisible();
  await expect(rate).toBeVisible();
  const configuredMode = await page.evaluate(() => {
    const encoded = (localStorage.getItem("jwt")?.split(".")[1] || "")
      .replace(/-/g, "+")
      .replace(/_/g, "/");
    const payload = JSON.parse(atob(encoded));
    return payload.user?.playerPreferences?.playbackMode || "native";
  });
  await expect(mode).toHaveText(
    configuredMode === "compat"
      ? "转码"
      : configuredMode === "ask"
        ? "选择"
        : "播放"
  );
  await rate.fill("1.37");
  await rate.press("Tab");
  await expect(video).toHaveJSProperty("playbackRate", 1.37);

  for (let attempt = 0; attempt < 3; attempt++) {
    if ((await mode.textContent())?.trim() === "转码") break;
    await page.locator(".video-js").hover();
    await mode.click();
  }
  await expect(mode).toHaveText("转码");
  await expect(
    page
      .locator(".media-compatibility-badge, .media-compatibility-card")
      .first()
  ).toBeVisible();
  await expect
    .poll(
      async () =>
        video.evaluate((element: HTMLVideoElement) => element.currentSrc),
      {
        timeout: 90_000,
      }
    )
    .toMatch(/\/api\/media\/hls\//);

  await testInfo.attach("videojs-diagnostic-controls", {
    body: await page.screenshot(),
    contentType: "image/png",
  });

  await page.locator(".video-js").hover();
  await mode.click();
  await expect(mode).toHaveText("选择");
  await page.locator(".video-js").hover();
  await mode.click();
  await expect(mode).toHaveText("播放");
  await expect
    .poll(async () =>
      video.evaluate((element: HTMLVideoElement) => element.currentSrc)
    )
    .toMatch(/\/api\/raw\//);
});
