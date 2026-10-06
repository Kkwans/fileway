import { expect, test } from "@playwright/test";

test("压缩包入口区分未选择路径与读取失败", async ({ page }, testInfo) => {
  const user = {
    id: 1,
    username: "fixture",
    password: "",
    scope: "/",
    locale: "zh-cn",
    perm: { admin: true, download: true },
    commands: [],
    rules: [],
    lockPassword: false,
    hideDotfiles: false,
    singleClick: false,
    redirectAfterCopyMove: false,
    dateFormat: false,
    viewMode: "mosaic",
    aceEditorTheme: "",
  };
  const encode = (value: unknown) =>
    Buffer.from(JSON.stringify(value)).toString("base64url");
  const token = [
    encode({ alg: "none", typ: "JWT" }),
    encode({ exp: Math.floor(Date.now() / 1000) + 3600, user }),
    "fixture",
  ].join(".");
  await page.addInitScript((jwt) => localStorage.setItem("jwt", jwt), token);
  await page.route(
    (url) => url.pathname.startsWith("/api/"),
    async (route) => {
      const path = new URL(route.request().url()).pathname;
      if (path === "/api/renew") {
        await route.fulfill({
          status: 200,
          contentType: "text/plain",
          body: token,
        });
      } else if (path === "/api/archives/entries") {
        await route.fulfill({ status: 404, body: "压缩包不存在" });
      } else if (path === "/api/users/1") {
        await route.fulfill({
          status: 200,
          contentType: "application/json",
          body: JSON.stringify(user),
        });
      } else if (path === "/api/tasks") {
        const counts = {
          all: 0,
          active: 0,
          attention: 0,
          canceled: 0,
          completed: 0,
          archived: 0,
        };
        await route.fulfill({
          status: 200,
          contentType: "application/json",
          body: JSON.stringify({
            items: [],
            nextCursor: "",
            total: 0,
            counts,
            categoryCounts: { file: counts, background: counts },
            owners: [],
          }),
        });
      } else if (path === "/api/transfers") {
        await route.fulfill({
          status: 200,
          contentType: "application/json",
          body: JSON.stringify({ items: [], total: 0 }),
        });
      } else if (path === "/api/task-center/events") {
        await route.fulfill({
          status: 200,
          contentType: "text/event-stream",
          body: ": fixture\n\n",
        });
      } else {
        await route.fulfill({
          status: 200,
          contentType: "application/json",
          body: "[]",
        });
      }
    }
  );

  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/archive");
  await expect(page.getByText("选择压缩包开始浏览")).toBeVisible();
  await expect(page.getByRole("link", { name: "去文件列表" })).toHaveAttribute(
    "href",
    "/files/"
  );
  await expect(page.getByText("无法打开压缩包")).toHaveCount(0);
  await page.screenshot({
    path: testInfo.outputPath("archive-idle-mobile.png"),
  });

  await page.goto("/archive?path=%2Fmissing.zip");
  await expect(page.getByText("无法打开压缩包")).toBeVisible();
  await expect(page.getByText("选择压缩包开始浏览")).toHaveCount(0);
});
