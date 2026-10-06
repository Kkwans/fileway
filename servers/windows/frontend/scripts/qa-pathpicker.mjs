import { chromium } from "@playwright/test";

const BASE = "http://127.0.0.1:8888";
const MP4 =
  "/C/MyProgram/TODO/Telegram/鎴戠殑鏀惰棌_2026-01-20/video_files/-1001890432834_39829.mp4";

function enc(p) {
  return p.split("/").map(encodeURIComponent).join("/");
}

const browser = await chromium.launch({ headless: true });
const page = await browser.newPage({ viewport: { width: 1400, height: 900 } });
await page.goto(`${BASE}/login`, { waitUntil: "networkidle" });
const token = await page.evaluate(async () => {
  const r = await fetch("/api/login", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username: "admin", password: process.env.WINFB_ADMIN_PASSWORD || "" }),
  });
  return r.ok ? (await r.text()).trim() : "";
});
await page.evaluate((t) => localStorage.setItem("jwt", t), token);
await page.goto(`${BASE}/files${enc(MP4)}?preview=true`, {
  waitUntil: "domcontentloaded",
});
await page.waitForSelector(".art-video-player", { timeout: 20000 });
await page.waitForTimeout(2000);

// Open settings panel via DOM (icon may be hover-only)
await page.evaluate(() => {
  const btn =
    document.querySelector(".art-setting") ||
    document.querySelector(".art-control-setting") ||
    [...document.querySelectorAll(".art-control")].find((el) =>
      (el.className || "").includes("setting")
    );
  if (btn) btn.click();
});
await page.waitForTimeout(500);
const panel = await page.evaluate(() => {
  const items = [...document.querySelectorAll(".art-setting-item, .art-setting-item-left-text")];
  return items.map((el) => (el.textContent || "").replace(/\s+/g, " ").trim()).filter(Boolean).slice(0, 30);
});
console.log("SETTINGS_ITEMS", JSON.stringify(panel));

// Click 瀛楀箷 parent then 閫夋嫨鏂囦欢 if present
const clicked = await page.evaluate(() => {
  const texts = [...document.querySelectorAll(".art-setting-item, .art-settings-panel .art-setting-item")];
  const find = (s) =>
    texts.find((el) => (el.textContent || "").includes(s));
  const sub = find("瀛楀箷");
  if (sub) {
    sub.click();
    return "clicked-subtitle";
  }
  return "no-subtitle-item";
});
console.log("CLICK", clicked);
await page.waitForTimeout(500);
const nested = await page.evaluate(() => {
  return [...document.querySelectorAll(".art-setting-item, [class*=setting] *")]
    .map((el) => (el.textContent || "").replace(/\s+/g, " ").trim())
    .filter((t) => t && t.length < 40)
    .filter((t) => /瀛楀箷|閫夋嫨|鏂囦欢|澶栨寕/.test(t))
    .slice(0, 20);
});
console.log("NESTED", JSON.stringify(nested));
await page.evaluate(() => {
  const byValue = document.querySelector('[data-value="__pick__"]');
  if (byValue) {
    byValue.click();
    return;
  }
  const nodes = [...document.querySelectorAll(".art-setting-item, .art-selector-item, .art-settings *")];
  const target = nodes.find((el) =>
    (el.textContent || "").replace(/\s+/g, " ").includes("浠庡叾浠栫洰褰曟坊鍔犲瓧骞?)
  );
  if (target) (target.closest(".art-setting-item, .art-selector-item") || target).click();
});
await page.waitForTimeout(800);
const picker = await page.evaluate(() => {
  const root = document.querySelector(".path-picker");
  if (!root) return { present: false };
  const loc = root.querySelector(".path-picker__location");
  const list = [...root.querySelectorAll(".path-picker__entry-main span")].map((s) =>
    (s.textContent || "").trim()
  );
  const locBg = loc ? getComputedStyle(loc).backgroundColor : null;
  const rootStyle = getComputedStyle(root);
  return {
    present: true,
    title: root.querySelector("h2")?.textContent?.trim() || "",
    locBg,
    locText: loc?.textContent?.replace(/\s+/g, " ").trim() || "",
    bg: rootStyle.backgroundColor,
    color: rootStyle.color,
    height: Math.round(root.getBoundingClientRect().height),
    maxH: rootStyle.maxHeight,
    entries: list.slice(0, 25),
  };
});
console.log("PICKER", JSON.stringify(picker, null, 2));

// Click 涓婁竴绾?to verify directories are listed for navigation
await page.evaluate(() => {
  const btn = [...document.querySelectorAll(".path-picker__entry-main")].find((el) =>
    (el.textContent || "").includes("涓婁竴绾?)
  );
  btn?.click();
});
await page.waitForTimeout(700);
const parent = await page.evaluate(() => {
  const root = document.querySelector(".path-picker");
  const list = [...root.querySelectorAll(".path-picker__entry-main span")].map((s) =>
    (s.textContent || "").trim()
  );
  return {
    loc: root.querySelector(".path-picker__location")?.textContent?.replace(/\s+/g, " ").trim(),
    entries: list.slice(0, 30),
  };
});
console.log("PARENT", JSON.stringify(parent, null, 2));

// Go up again if still sparse
await page.evaluate(() => {
  const btn = [...document.querySelectorAll(".path-picker__entry-main")].find((el) =>
    (el.textContent || "").includes("涓婁竴绾?)
  );
  btn?.click();
});
await page.waitForTimeout(700);
const grand = await page.evaluate(() => {
  const root = document.querySelector(".path-picker");
  const list = [...root.querySelectorAll(".path-picker__entry-main span")].map((s) =>
    (s.textContent || "").trim()
  );
  return {
    loc: root.querySelector(".path-picker__location")?.textContent?.replace(/\s+/g, " ").trim(),
    entries: list.slice(0, 40),
  };
});
console.log("GRAND", JSON.stringify(grand, null, 2));
await browser.close();

