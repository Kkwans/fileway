import { expect, test } from "@playwright/test";

test("登录断网时给出行内反馈并允许重试", async ({ page }) => {
  await page.route("**/api/login", (route) => route.abort("failed"));
  await page.goto("/login");

  await expect(page.getByRole("textbox", { name: "用户名" })).toBeVisible();
  await expect(page.getByLabel("密码")).toHaveAttribute(
    "autocomplete",
    "current-password"
  );
  await expect(page.locator('meta[name="viewport"]')).toHaveAttribute(
    "content",
    "width=device-width, initial-scale=1"
  );

  await page.getByRole("textbox", { name: "用户名" }).fill("audit-fixture");
  await page.getByLabel("密码").fill("audit-fixture");
  await page.getByRole("button", { name: "登录" }).click();

  await expect(page.getByRole("alert")).toHaveText(
    "无法连接服务器，请检查网络后重试"
  );
  await expect(page.getByRole("button", { name: "登录" })).toBeEnabled();
});

test("登录请求超时后提示重试", async ({ page }) => {
  await page.clock.install();
  await page.route("**/api/login", async () => {
    await new Promise(() => {});
  });
  await page.goto("/login");

  await page.getByLabel("用户名").fill("audit-fixture");
  await page.getByLabel("密码").fill("audit-fixture");
  await page.getByRole("button", { name: "登录" }).click();
  await page.clock.fastForward(15_001);

  await expect(page.getByRole("alert")).toHaveText("连接超时，请稍后重试");
  await expect(page.getByRole("button", { name: "登录" })).toBeEnabled();
});
