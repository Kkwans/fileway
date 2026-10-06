import { expect, test, type Page, type Route } from "@playwright/test";

const permissions = {
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
};
const user = {
  id: 1,
  username: "fixture",
  password: "",
  scope: "/",
  locale: "zh-cn",
  perm: permissions,
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
const settings = {
  signup: false,
  createUserDir: false,
  hideLoginButton: false,
  minimumPasswordLength: 6,
  userHomeBasePath: "/users",
  defaults: {
    ...user,
    sorting: { by: "name", asc: true },
  },
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
};

function fixtureToken() {
  const encode = (value: unknown) =>
    Buffer.from(JSON.stringify(value)).toString("base64url");
  return [
    encode({ alg: "none", typ: "JWT" }),
    encode({ exp: Math.floor(Date.now() / 1000) + 3600, user }),
    "fixture",
  ].join(".");
}

async function fulfillJSON(route: Route, body: unknown, status = 200) {
  await route.fulfill({
    status,
    contentType: "application/json",
    body: JSON.stringify(body),
  });
}

async function installFixture(
  page: Page,
  onSettingsSave: () => number,
  onUserSave: () => number = () => 200
) {
  const token = fixtureToken();
  await page.route(
    (url) => url.pathname.startsWith("/api/"),
    async (route) => {
      const request = route.request();
      const path = new URL(request.url()).pathname;
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
          contentType: "text/event-stream",
          body: ": fixture\n\n",
        });
        return;
      }
      if (path === "/api/settings") {
        await fulfillJSON(
          route,
          request.method() === "PUT" ? {} : settings,
          request.method() === "PUT" ? onSettingsSave() : 200
        );
        return;
      }
      if (path === "/api/users/1") {
        await fulfillJSON(
          route,
          request.method() === "PUT" ? {} : user,
          request.method() === "PUT" ? onUserSave() : 200
        );
        return;
      }
      if (path === "/api/tasks") {
        const counts = {
          all: 0,
          active: 0,
          attention: 0,
          canceled: 0,
          completed: 0,
          archived: 0,
        };
        await fulfillJSON(route, {
          items: [],
          total: 0,
          nextCursor: "",
          counts,
          categoryCounts: { file: counts, background: counts },
          owners: [],
        });
        return;
      }
      if (path === "/api/transfers") {
        await fulfillJSON(route, { items: [], total: 0 });
        return;
      }
      await fulfillJSON(route, []);
    }
  );
}

async function login(page: Page, redirect: string) {
  await page.goto(`/login?redirect=${encodeURIComponent(redirect)}`);
  await page.getByLabel("用户名").fill("fixture");
  await page.getByLabel("密码").fill("fixture");
  await page.getByRole("button", { name: "登录" }).click();
  await expect(page).toHaveURL(new RegExp(redirect.replaceAll("/", "\\/")));
}

test("全局设置取消、放弃与保存失败均保护编辑内容", async ({ page }) => {
  let saveStatus = 500;
  let saveCount = 0;
  await installFixture(page, () => {
    saveCount++;
    return saveStatus;
  });
  await login(page, "/settings/global");

  const homePath = page.locator(".card-content input[type=text]").first();
  await homePath.fill("/changed-locally");
  await page.getByRole("link", { name: "用户管理" }).click();
  await expect(page.getByText("你确定要放弃所做的更改吗？")).toBeVisible();
  await page.getByRole("button", { name: "取消" }).click();
  await expect(homePath).toHaveValue("/changed-locally");

  await page.getByRole("link", { name: "用户管理" }).click();
  await page.getByRole("button", { name: "保存更改" }).click();
  await expect(page).toHaveURL(/\/settings\/global$/);
  await expect(homePath).toHaveValue("/changed-locally");
  expect(saveCount).toBe(1);

  saveStatus = 200;
  await page.getByRole("link", { name: "用户管理" }).click();
  await page.getByRole("button", { name: "保存更改" }).click();
  await expect(page).toHaveURL(/\/settings\/users$/);
  expect(saveCount).toBe(2);
});

test("用户编辑页放弃修改后才能切换设置页", async ({ page }) => {
  await installFixture(page, () => 200);
  await login(page, "/settings/users/1");
  await page.getByLabel("用户名").fill("changed-locally");
  await page.getByRole("link", { name: "账户设置" }).click();
  await expect(page.getByText("你确定要放弃所做的更改吗？")).toBeVisible();
  await page.getByRole("button", { name: "放弃更改" }).click();
  await expect(page).toHaveURL(/\/settings\/profile$/);
});

test("账户页只保护待提交的密码", async ({ page }) => {
  await installFixture(page, () => 200);
  await login(page, "/settings/profile");
  await page.getByPlaceholder("新密码", { exact: true }).fill("local-only");
  await page.getByRole("link", { name: "全局设置" }).click();
  await expect(page.getByText("你确定要放弃所做的更改吗？")).toBeVisible();
  await page.getByRole("button", { name: "放弃更改" }).click();
  await expect(page).toHaveURL(/\/settings\/global$/);
});

test("用户编辑页保存成功后继续原导航", async ({ page }) => {
  let updates = 0;
  await installFixture(
    page,
    () => 200,
    () => {
      updates++;
      return 200;
    }
  );
  await login(page, "/settings/users/1");
  await page.getByLabel("用户名").fill("changed-locally");
  await page.getByRole("link", { name: "账户设置" }).click();
  await page.getByRole("button", { name: "保存更改" }).click();
  await expect(page.getByRole("heading", { name: "当前密码" })).toBeVisible();
  await page.locator(".card.floating input[type=password]").fill("fixture");
  await page.getByRole("button", { name: "确定" }).click();
  await expect(page).toHaveURL(/\/settings\/profile$/);
  expect(updates).toBe(1);
});
