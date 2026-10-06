import { expect, test } from "@playwright/test";

test("搜索首次态与提交后的零结果各自表达真实状态", async ({
  page,
}, testInfo) => {
  await page.setViewportSize({ width: 390, height: 844 });
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

  await page.route(
    (url) => url.pathname.startsWith("/api/"),
    async (route) => {
      const path = new URL(route.request().url()).pathname;
      if (path === "/api/login" || path === "/api/renew") {
        await route.fulfill({
          status: 200,
          contentType: "text/plain",
          body: token,
        });
      } else if (path.startsWith("/api/search")) {
        await route.fulfill({
          status: 200,
          contentType: "application/x-ndjson",
          body: '{"type":"summary","reason":"completed","count":0}\n',
        });
      } else if (path === "/api/task-center/events") {
        await route.fulfill({
          status: 200,
          contentType: "text/event-stream",
          body: ": fixture\n\n",
        });
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
            total: 0,
            nextCursor: "",
            counts,
            categoryCounts: { file: counts, background: counts },
            owners: [],
          }),
        });
      } else if (path === "/api/transfers") {
        await route.fulfill({
          status: 200,
          contentType: "application/json",
          body: '{"items":[],"total":0}',
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

  await page.goto("/login?redirect=%2Fsearch");
  await page.getByLabel("用户名").fill("fixture");
  await page.getByLabel("密码").fill("fixture");
  await page.getByRole("button", { name: "登录" }).click();
  await expect(page).toHaveURL(/\/search$/);
  await expect(
    page.getByText("输入关键词或选择文件类型，然后开始搜索")
  ).toBeVisible();
  await expect(page.getByText("没有找到匹配的文件或文件夹")).toHaveCount(0);
  await page.screenshot({
    path: testInfo.outputPath("search-idle-mobile.png"),
  });

  await page.getByRole("searchbox", { name: "搜索文件" }).fill("no-match");
  await page.getByRole("button", { name: "开始搜索" }).click();
  await expect(page.getByText("没有找到匹配的文件或文件夹")).toBeVisible();
  await page.screenshot({
    path: testInfo.outputPath("search-empty-mobile.png"),
  });
  await page.getByRole("button", { name: "清空搜索内容" }).click();
  await expect(
    page.getByText("输入关键词或选择文件类型，然后开始搜索")
  ).toBeVisible();
  await expect
    .poll(() => new URL(page.url()).searchParams.has("q"))
    .toBe(false);
  await page.reload();
  await expect(
    page.getByText("输入关键词或选择文件类型，然后开始搜索")
  ).toBeVisible();
});
