import { chromium } from "@playwright/test";

const BASE = "http://127.0.0.1:8888";
const browser = await chromium.launch({ headless: true });
const page = await browser.newPage({ viewport: { width: 1400, height: 900 } });
page.on("pageerror", (e) => console.log("PAGEERROR", String(e).slice(0, 160)));

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

await page.goto(`${BASE}/settings/profile`, { waitUntil: "networkidle" });
await page.waitForTimeout(800);

const before = await page.evaluate(() => ({
  nativeSelects: document.querySelectorAll(".setting-controls select, .app-select select")
    .length,
  appSelects: document.querySelectorAll(".app-select__trigger").length,
  appChecks: document.querySelectorAll(".app-checkbox__box").length,
  nativeChecks: document.querySelectorAll(
    '.setting-toggle-row input[type="checkbox"]'
  ).length,
  prefixBtns: document.querySelectorAll(".prefix-state-button, .prefix-icon-button").length,
}));

await page.click(".settings-nav-save");
await page.waitForTimeout(700);
const toast = await page.evaluate(() => {
  const nodes = [...document.querySelectorAll("*")];
  const texts = nodes
    .map((el) => (el.childElementCount === 0 ? (el.textContent || "").trim() : ""))
    .filter((t) => t && /宸蹭繚瀛榺宸叉洿鏂皘璁剧疆/.test(t) && t.length < 30);
  return [...new Set(texts)].slice(0, 10);
});

// open AppSelect
await page.click(".setting-controls .app-select__trigger");
await page.waitForTimeout(300);
const menu = await page.evaluate(() => {
  const m = document.querySelector(".app-select__menu");
  return m
    ? {
        open: true,
        items: [...m.querySelectorAll(".app-select__option")].map((o) =>
          (o.textContent || "").trim()
        ),
      }
    : { open: false };
});

// click 鍏煎浼樺厛
await page.evaluate(() => {
  const opt = [...document.querySelectorAll(".app-select__option")].find((o) =>
    (o.textContent || "").includes("鍏煎浼樺厛")
  );
  opt?.click();
});
await page.waitForTimeout(400);
const afterPick = await page.evaluate(() => {
  const triggers = [...document.querySelectorAll(".app-select__value")].map((el) =>
    (el.textContent || "").trim()
  );
  return triggers;
});

await page.goto(`${BASE}/settings/shares`, { waitUntil: "networkidle" });
await page.waitForTimeout(400);
const shares = await page.evaluate(() => {
  const cta = document.querySelector(".shares-cta");
  return cta
    ? {
        text: (cta.textContent || "").trim(),
        bg: getComputedStyle(cta).backgroundColor,
        h: Math.round(cta.getBoundingClientRect().height),
      }
    : null;
});

console.log("BEFORE", JSON.stringify(before, null, 2));
console.log("TOAST", JSON.stringify(toast, null, 2));
console.log("MENU", JSON.stringify(menu, null, 2));
console.log("AFTER_PICK", JSON.stringify(afterPick, null, 2));
console.log("SHARES", JSON.stringify(shares, null, 2));
console.log("DONE");
await browser.close();

