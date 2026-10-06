import { expect, test, type Page } from "@playwright/test";

const mode = process.env.NFB_E2E_MODE?.trim() || "fixture";
const username = process.env.NFB_E2E_USERNAME?.trim() || "";
const password = process.env.NFB_E2E_PASSWORD || "";
const configuredScope = process.env.NFB_E2E_CLEANUP_SCOPE?.trim() || "";

function scopedPath(path: string) {
  const scope = configuredScope.startsWith("/")
    ? configuredScope
    : `/${configuredScope}`;
  return `${scope.replace(/\/+$/, "")}/${path}`;
}

async function requestJSON<T>(page: Page, path: string, init?: RequestInit) {
  return page.evaluate(
    async ({ path: requestPath, init: requestInit }) => {
      const headers = new Headers(requestInit?.headers);
      const token = localStorage.getItem("jwt");
      if (token) headers.set("X-Auth", token);
      const response = await fetch(requestPath, {
        ...requestInit,
        headers,
      });
      const body = await response.text();
      let data: unknown = null;
      try {
        data = body ? JSON.parse(body) : null;
      } catch {
        data = body;
      }
      if (!response.ok) {
        throw new Error(`${response.status} ${requestPath}: ${String(data)}`);
      }
      return { status: response.status, data: data as T };
    },
    { path, init }
  );
}

async function waitForTask(page: Page, id: string) {
  const deadline = Date.now() + 180_000;
  while (Date.now() < deadline) {
    const response = await requestJSON<{
      id: string;
      status: string;
      processedItems: number;
      totalItems: number;
      error?: string;
    }>(page, `/api/tasks/${encodeURIComponent(id)}`);
    if (!["queued", "running"].includes(response.data.status))
      return response.data;
    await page.waitForTimeout(500);
  }
  throw new Error(`任务 ${id} 在 180 秒内没有结束`);
}

async function login(page: Page) {
  if (!username || !password) {
    throw new Error(
      "NFB_E2E_MODE=real requires NFB_E2E_USERNAME and NFB_E2E_PASSWORD"
    );
  }
  await page.goto("/login?redirect=%2Fanalysis", {
    waitUntil: "domcontentloaded",
  });
  await page.getByPlaceholder("用户名").fill(username);
  await page.getByPlaceholder("密码").fill(password);
  await page.getByRole("button", { name: "登录" }).click();
  await expect(page).toHaveURL(/\/analysis(?:\?|$)/, { timeout: 20_000 });
}

async function removeTestDirectory(page: Page, path: string) {
  try {
    const response = await page.evaluate(async (target) => {
      const headers = new Headers();
      const token = localStorage.getItem("jwt");
      if (token) headers.set("X-Auth", token);
      const result = await fetch(
        `/api/resources${encodeURI(target)}?mode=permanent`,
        {
          method: "DELETE",
          headers,
        }
      );
      return result.status;
    }, path);
    if (response >= 200 && response < 300) return;
  } catch {
    // The directory may already have been removed by a failed test cleanup.
  }
  try {
    const trash = await requestJSON<
      Array<{ id: string; originalPath: string }>
    >(page, "/api/trash");
    for (const item of trash.data.filter((entry) =>
      entry.originalPath.startsWith(path)
    )) {
      await page.evaluate(async (id) => {
        const headers = new Headers();
        const token = localStorage.getItem("jwt");
        if (token) headers.set("X-Auth", token);
        await fetch(`/api/trash/${encodeURIComponent(id)}`, {
          method: "DELETE",
          headers,
        });
      }, item.id);
    }
  } catch {
    // Preserve the original failure; cleanup is best effort after the scoped run.
  }
}

test.describe("NAS File Browser real duplicate cleanup acceptance", () => {
  test.skip(
    mode !== "real" || !configuredScope,
    "只在 real 模式并显式提供 NFB_E2E_CLEANUP_SCOPE 时运行；测试目录必须是隔离目录"
  );

  test("scans, keeps the unique earliest file, trashes the copy and restores it", async ({
    page,
  }) => {
    test.setTimeout(360_000);
    await login(page);

    const testDirectory = `${configuredScope.replace(/\/+$/, "")}/.nfb-e2e-duplicate-${Date.now()}`;
    const earliest = scopedPath(
      `.nfb-e2e-duplicate-${testDirectory.split("-").at(-1)}/earliest.txt`
    );
    const later = scopedPath(
      `.nfb-e2e-duplicate-${testDirectory.split("-").at(-1)}/later.txt`
    );
    try {
      const mkdir = await page.evaluate(async (path) => {
        const headers = new Headers();
        const token = localStorage.getItem("jwt");
        if (token) headers.set("X-Auth", token);
        const response = await fetch(`/api/resources${encodeURI(path)}/`, {
          method: "POST",
          headers,
        });
        return response.status;
      }, testDirectory);
      expect(mkdir).toBeLessThan(300);

      for (const [index, path] of [earliest, later].entries()) {
        if (index === 1) await page.waitForTimeout(1_100);
        const response = await page.evaluate(
          async ({ path: target, content }) => {
            const headers = new Headers({ "Content-Type": "text/plain" });
            const token = localStorage.getItem("jwt");
            if (token) headers.set("X-Auth", token);
            const result = await fetch(`/api/resources${encodeURI(target)}`, {
              method: "POST",
              headers,
              body: content,
            });
            return result.status;
          },
          { path, content: "nas-file-browser duplicate cleanup acceptance\n" }
        );
        expect(response).toBeLessThan(300);
      }

      const started = await requestJSON<{
        id: string;
        type: string;
        status: string;
      }>(page, "/api/analysis/duplicates", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ paths: [testDirectory] }),
      });
      expect(started.status).toBe(202);
      expect(started.data.type).toBe("analysis.duplicates");
      const analysisTask = await waitForTask(page, started.data.id);
      expect(analysisTask.status).toBe("completed");

      const reportResponse = await requestJSON<{
        schemaVersion?: number;
        groups: Array<{
          sha256: string;
          suggestedKeepPath?: string;
          keepReason?: string;
          files: Array<{ path: string; created?: string }>;
        }>;
      }>(page, `/api/analysis/${encodeURIComponent(started.data.id)}`);
      const group = reportResponse.data.groups.find((entry) =>
        entry.files.some((file) => file.path === earliest)
      );
      expect(reportResponse.data.schemaVersion).toBe(3);
      expect(group).toBeDefined();
      expect(group?.suggestedKeepPath).toBe(earliest);
      expect(group?.keepReason).toBe("oldest-created");

      await page.goto(
        `/analysis?tool=duplicates&task=${encodeURIComponent(started.data.id)}&paths=${encodeURIComponent(testDirectory)}`,
        { waitUntil: "domcontentloaded" }
      );
      await expect(page.getByRole("heading", { name: "确认结果" })).toBeVisible(
        {
          timeout: 30_000,
        }
      );
      await expect(
        page.getByText("已建议最早创建项", { exact: true })
      ).toBeVisible();
      await page.getByRole("button", { name: /清理所选 1 组/ }).click();
      await expect(
        page.getByRole("heading", { name: "确认移入回收站" })
      ).toBeVisible();
      await page.getByRole("button", { name: "确认移入回收站" }).click();
      await expect(page.getByText("清理结果", { exact: true })).toBeVisible({
        timeout: 30_000,
      });

      const cleanupTaskLink = page.getByRole("link", {
        name: "前往回收站恢复",
      });
      await expect(cleanupTaskLink).toBeVisible({ timeout: 30_000 });
      await expect(page.getByText(/成功 1/)).toBeVisible();

      const keeper = await requestJSON<unknown>(
        page,
        `/api/resources${encodeURI(earliest)}?metadata=1`
      );
      expect(keeper.status).toBe(200);
      const removed = await page.evaluate(async (path) => {
        const headers = new Headers();
        const token = localStorage.getItem("jwt");
        if (token) headers.set("X-Auth", token);
        const response = await fetch(
          `/api/resources${encodeURI(path)}?metadata=1`,
          { headers }
        );
        return response.status;
      }, later);
      expect(removed).toBe(404);

      const trash = await requestJSON<
        Array<{ originalPath: string; status: string }>
      >(page, "/api/trash");
      expect(trash.data.some((item) => item.originalPath === later)).toBe(true);

      await cleanupTaskLink.click();
      await expect(page).toHaveURL(/\/trash/);
      await expect(page.getByText(later, { exact: true })).toBeVisible({
        timeout: 20_000,
      });
      await page
        .locator(".trash-item")
        .filter({ hasText: later })
        .getByRole("button", { name: "恢复" })
        .click();
      await expect(page.getByText(later, { exact: true })).toHaveCount(0, {
        timeout: 20_000,
      });
      const restored = await requestJSON<unknown>(
        page,
        `/api/resources${encodeURI(later)}?metadata=1`
      );
      expect(restored.status).toBe(200);
    } finally {
      await removeTestDirectory(page, testDirectory);
    }
  });
});
