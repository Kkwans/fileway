import { chromium } from "@playwright/test";

const BASE = "http://127.0.0.1:8888";
const USER = "admin";
const PASS = process.env.WINFB_ADMIN_PASSWORD || "";
if (!PASS) throw new Error("Set WINFB_ADMIN_PASSWORD");
const VIDEO_PATH =
  "/C/MyProgram/TODO/Telegram/鎴戠殑鏀惰棌_2026-01-20/video_files/-1001890432834_39829.mp4";

const browser = await chromium.launch({ headless: true });
const page = await browser.newPage({ viewport: { width: 1280, height: 800 } });

function report(label, value) {
  console.log(`\n=== ${label} ===`);
  console.log(typeof value === "string" ? value : JSON.stringify(value, null, 2));
}

try {
  await page.goto(`${BASE}/login`, { waitUntil: "networkidle", timeout: 30000 });
  await page.fill('input[name="username"], input[placeholder*="鐢ㄦ埛"], #username', USER).catch(async () => {
    const inputs = page.locator('form input');
    await inputs.nth(0).fill(USER);
  });
  await page.fill('input[type="password"]', PASS);
  await page.click('button[type="submit"], form button');
  await page.waitForURL(/files|settings|\/$/, { timeout: 20000 }).catch(() => {});
  report("after login url", page.url());

  const encoded = VIDEO_PATH.split("/").map(encodeURIComponent).join("/");
  // Try common preview routes
  const candidates = [
    `${BASE}/files${encoded}?preview=true`,
    `${BASE}/files${encoded}`,
  ];

  let opened = false;
  for (const url of candidates) {
    await page.goto(url, { waitUntil: "domcontentloaded", timeout: 30000 });
    await page.waitForTimeout(2500);
    const hasArt = await page.locator(".art-video-player, .art-player-stage").count();
    report(`candidate ${url} art-nodes`, hasArt);
    if (hasArt > 0) {
      opened = true;
      break;
    }
  }

  if (!opened) {
    // Click file in listing if needed
    await page.goto(`${BASE}/files/C/MyProgram/TODO/Telegram/鎴戠殑鏀惰棌_2026-01-20/video_files/`, {
      waitUntil: "networkidle",
      timeout: 30000,
    });
    await page.waitForTimeout(1500);
    report("listing body snippet", (await page.locator("body").innerText()).slice(0, 500));
  }

  // Force player engine + reload preview-ish URL from current SPA if player not mounted
  const artCount = await page.locator(".art-video-player").count();
  if (artCount === 0) {
    // SPA may need click on file
    const item = page.locator(`a, [role="button"], .item, .listing-item`).filter({
      hasText: /39829|mp4/i,
    });
    if ((await item.count()) > 0) {
      await item.first().click();
      await page.waitForTimeout(3000);
    }
  }

  report("url final", page.url());
  report("art player count", await page.locator(".art-video-player").count());

  // Wait for ready / controls
  await page.waitForTimeout(2000);
  const stage = page.locator(".art-player-stage, .art-video-player").first();

  // Open settings via gear if present
  const gear = page.locator(".art-setting, .art-control-setting, [data-control='setting']").first();
  if ((await gear.count()) > 0) {
    await gear.click({ force: true }).catch(() => {});
    await page.waitForTimeout(800);
  }

  // Dump settings panel structure
  const settingsDump = await page.evaluate(() => {
    const root =
      document.querySelector(".art-video-player") ||
      document.querySelector(".art-player-stage");
    if (!root) return { error: "no player root" };
    const items = [...root.querySelectorAll(".art-setting-item")].map((el) => {
      const name = el.getAttribute("data-name") || "";
      const left = el.querySelector(".art-setting-item-left-text")?.textContent?.trim() || "";
      const tip = el.querySelector(".art-setting-item-right-tooltip")?.textContent?.trim() || "";
      const icon = el.querySelector(".art-setting-item-left-icon");
      const iconHtml = icon ? icon.innerHTML.slice(0, 120) : "";
      const iconText = icon ? (icon.textContent || "").trim() : "";
      return { name, left, tip, iconText, iconHtml };
    });
    const bars = [...root.querySelectorAll(".art-control")].map((el) => ({
      cls: el.className,
      text: (el.textContent || "").trim().slice(0, 40),
    }));
    return {
      settingShow: root.classList.contains("art-setting-show"),
      items,
      bars,
      selectorLists: root.querySelectorAll(".art-selector-list").length,
      barLabels: [...root.querySelectorAll(".art-bar-label")].map((n) => n.textContent),
    };
  });
  report("settings dump", settingsDump);

  // Ensure settings panel is closed 鈥?chip popups are independent of the gear.
  await page.evaluate(() => {
    document
      .querySelector(".art-video-player")
      ?.classList.remove("art-setting-show");
  });
  await page.waitForTimeout(200);

  // Click bottom bar rate control 鈥?expect independent selector popup on that chip
  const rateControl = page
    .locator(".art-control-playback-rate, [name='playback-rate']")
    .first();
  if ((await rateControl.count()) > 0) {
    await rateControl.click({ force: true });
    await page.waitForTimeout(600);
    const afterClick = await page.evaluate(() => {
      const root = document.querySelector(".art-video-player");
      if (!root) return null;
      const ctrl = root.querySelector(".art-control-playback-rate");
      const list = ctrl && ctrl.querySelector(".art-selector-list");
      const settings = root.querySelector(".art-settings");
      const cRect = ctrl && ctrl.getBoundingClientRect();
      const lRect = list && list.getBoundingClientRect();
      const sRect = settings && settings.getBoundingClientRect();
      return {
        settingShow: root.classList.contains("art-setting-show"),
        chipOpen: !!(ctrl && ctrl.classList.contains("art-selector-open")),
        listVisible:
          !!list &&
          getComputedStyle(list).opacity !== "0" &&
          !!(lRect && lRect.height),
        listItems: list
          ? [...list.querySelectorAll(".art-selector-item")].map((el) => ({
              text: (el.textContent || "").trim(),
              visible: !!(
                el.offsetWidth ||
                el.offsetHeight ||
                el.getClientRects().length
              ),
            }))
          : [],
        listAboveChip: !!(
          lRect &&
          cRect &&
          lRect.bottom <= cRect.top + 8
        ),
        listNearChip: !!(
          lRect &&
          cRect &&
          Math.abs(lRect.left - cRect.left) < 220
        ),
        settingsPanelOpen: settings
          ? getComputedStyle(settings).display !== "none"
          : false,
        listNotAtGear: !!(
          lRect &&
          sRect &&
          Math.abs(lRect.left - sRect.left) > 40
        ),
      };
    });
    report("after rate bar click", afterClick);
  } else {
    report("rate control", "NOT FOUND");
  }

  // Try select 1.25x from the chip popup via direct DOM click
  const rate125 = page
    .locator(".art-control-playback-rate .art-selector-item")
    .filter({ hasText: "1.25x" })
    .first();
  if ((await rate125.count()) > 0) {
    await page.evaluate(() => {
      const items = [
        ...document.querySelectorAll(
          ".art-control-playback-rate .art-selector-item"
        ),
      ];
      const target =
        items.find((el) => (el.textContent || "").includes("1.25x")) ||
        items[0];
      target?.dispatchEvent(
        new MouseEvent("click", { bubbles: true, cancelable: true, view: window })
      );
    });
    await page.waitForTimeout(700);
    const afterRate = await page.evaluate(() => {
      const root = document.querySelector(".art-video-player");
      const video = document.querySelector("video");
      return {
        playbackRate: video?.playbackRate,
        bar: [...(root?.querySelectorAll(".art-bar-label, .art-control-playback-rate .art-selector-value") || [])].map(
          (n) => (n.textContent || "").trim()
        ).filter(Boolean),
        tips: [...(root?.querySelectorAll(".art-setting-item[data-name='playback-rate'] .art-setting-item-right-tooltip") || [])].map((n) => n.textContent),
        rateNameText: root?.querySelector(".art-setting-item[data-name='playback-rate'] .art-setting-item-left-text")?.textContent,
        chipStillOpen: !!root?.querySelector(".art-control-playback-rate.art-selector-open"),
        settingsStillClosed: !root?.classList.contains("art-setting-show"),
      };
    });
    report("after select 1.25x", afterRate);
  } else {
    report("rate-1.25 chip item", "NOT FOUND in DOM");
  }

  await page.screenshot({ path: "output/playwright/artplayer-settings.png", fullPage: true }).catch(async () => {
    await page.screenshot({ path: "artplayer-settings.png" });
  });
  console.log("\nDONE");
} catch (e) {
  console.error("TEST FAIL", e);
  await page.screenshot({ path: "artplayer-fail.png" }).catch(() => {});
} finally {
  await browser.close();
}

