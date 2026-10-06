import { chromium } from "@playwright/test";
import { mkdirSync } from "node:fs";

const BASE = process.env.QA_BASE || "http://127.0.0.1:8888";
const OUT =
  "D:/Kkwans/Desktop/Project/MyProject/WinFileBrowser/frontend/output/settings-visual";
mkdirSync(OUT, { recursive: true });

const browser = await chromium.launch({ headless: true });
const page = await browser.newPage({
  viewport: { width: 1400, height: 1000 },
  deviceScaleFactor: 2,
});
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

// Ensure one box is checked for visual tick QA
await page.evaluate(() => {
  const boxes = [...document.querySelectorAll(".check-card__input")];
  if (boxes[1] && !boxes[1].checked) {
    boxes[1].click();
  }
});
await page.waitForTimeout(400);

const info = await page.evaluate(() => {
  const inputs = [...document.querySelectorAll(".check-card__input")].map((el) => {
    const cs = getComputedStyle(el, "::after");
    return {
      checked: el.checked,
      afterContent: cs.content,
      afterDisplay: cs.display,
      bg: getComputedStyle(el).backgroundImage.slice(0, 80),
      bgc: getComputedStyle(el).backgroundColor,
    };
  });
  return inputs;
});

const card = page.locator(".check-card").nth(1);
await card.screenshot({ path: `${OUT}/checkbox-checked.png` });
await page.locator(".check-card-list").screenshot({
  path: `${OUT}/checkbox-list.png`,
});

console.log("CHECKBOX", JSON.stringify(info, null, 2));
console.log("SHOT", `${OUT}/checkbox-checked.png`);
await browser.close();

