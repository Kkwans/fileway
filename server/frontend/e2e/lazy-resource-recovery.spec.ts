import { expect, test } from "@playwright/test";

test("资源失效提示不自动刷新，取消刷新后仍可恢复", async ({ page }) => {
  await page.goto("/login");
  await page.getByLabel("用户名").fill("unsaved-local-input");
  // Vite uses this event for both chunk imports and CSS preloads. Preserve the
  // rejected import for Router/Vue; notification must never consume the error.
  const prevented = await page.evaluate(() => {
    const event = new Event("vite:preloadError", { cancelable: true });
    Object.assign(event, {
      payload: new TypeError("Failed to fetch dynamically imported module"),
    });
    window.dispatchEvent(event);
    window.dispatchEvent(event);
    return event.defaultPrevented;
  });
  expect(prevented).toBe(false);
  const reload = page.getByRole("button", { name: "刷新页面", exact: true });
  await expect(reload).toHaveCount(1);
  await expect(reload).toBeVisible();
  await expect(page.getByLabel("用户名")).toHaveValue("unsaved-local-input");
  page.once("dialog", (dialog) => dialog.dismiss());
  await reload.click();
  await expect(reload).toBeVisible();
  await expect(reload).toBeEnabled();
  await expect(page.getByLabel("用户名")).toHaveValue("unsaved-local-input");
});
