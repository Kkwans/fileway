import { expect, test } from "@playwright/test";

test("文件视图由有效 URL 参数控制，并随前进后退恢复", async ({ page }) => {
  let resourceRequests = 0;
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
  await page.addInitScript(() => {
    if (!localStorage.getItem("nas-file-browser-view-mode")) {
      localStorage.setItem("nas-file-browser-view-mode", "mosaic");
    }
  });
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
      } else if (path === "/api/users/1") {
        await route.fulfill({
          status: 200,
          contentType: "application/json",
          body: JSON.stringify(user),
        });
      } else if (path === "/api/tasks") {
        await route.fulfill({
          status: 200,
          contentType: "application/json",
          body: JSON.stringify({
            items: [],
            nextCursor: "",
            total: 0,
            counts: {
              all: 0,
              active: 0,
              attention: 0,
              canceled: 0,
              completed: 0,
              archived: 0,
            },
          }),
        });
      } else if (path === "/api/transfers") {
        await route.fulfill({
          status: 200,
          contentType: "application/json",
          body: JSON.stringify({ items: [], total: 0 }),
        });
      } else if (path.startsWith("/api/resources/")) {
        resourceRequests += 1;
        await route.fulfill({
          status: 200,
          contentType: "application/json",
          body: JSON.stringify({
            path: "/",
            name: "",
            size: 0,
            extension: "",
            modified: new Date().toISOString(),
            mode: 0,
            isDir: true,
            isSymlink: false,
            type: "dir",
            riskLevel: "low",
            items: [
              {
                path: "/readme.txt",
                name: "readme.txt",
                size: 12,
                extension: ".txt",
                modified: new Date().toISOString(),
                mode: 0,
                isDir: false,
                isSymlink: false,
                type: "text",
                riskLevel: "low",
                index: 0,
              },
            ],
            numDirs: 0,
            numFiles: 1,
            sorting: { by: "name", asc: true },
            index: 0,
          }),
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

  await page.goto("/files/?sort=name&order=desc&view=details");
  const listing = page.locator("#listing");
  await expect(listing).toHaveClass(/details/);
  await expect(page.getByRole("table", { name: "文件列表" })).toBeVisible();
  const initialResourceRequests = resourceRequests;

  await page.getByRole("button", { name: "切换视图" }).click();
  await page.getByRole("button", { name: "紧凑网格" }).click();
  await expect(listing).toHaveClass(/compact-grid/);
  await expect(page).toHaveURL(/sort=name&order=desc&view=compact-grid/);
  expect(resourceRequests).toBe(initialResourceRequests);
  await expect
    .poll(() =>
      page.evaluate(() => localStorage.getItem("nas-file-browser-view-mode"))
    )
    .toBe("compact-grid");

  await page.goBack();
  await expect(page).toHaveURL(/view=details/);
  await expect(page.getByRole("table", { name: "文件列表" })).toBeVisible();
  await page.goForward();
  await expect(listing).toHaveClass(/compact-grid/);
  expect(resourceRequests).toBe(initialResourceRequests);

  await page.goto("/files/?view=invalid");
  await expect(listing).toHaveClass(/compact-grid/);
  await page.goto("/files/");
  await expect(listing).toHaveClass(/compact-grid/);
});
