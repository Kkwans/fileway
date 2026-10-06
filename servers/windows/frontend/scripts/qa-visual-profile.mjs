import { chromium } from "@playwright/test";
import { mkdirSync } from "node:fs";

const BASE = process.env.QA_BASE || "http://127.0.0.1:8888";
const PASS = process.env.WINFB_ADMIN_PASSWORD || "";
if (!PASS) throw new Error("Set WINFB_ADMIN_PASSWORD");
const OUT =
  "D:/Kkwans/Desktop/Project/MyProject/WinFileBrowser/frontend/output/settings-visual";
mkdirSync(OUT, { recursive: true });

const browser = await chromium.launch({ headless: true });
const page = await browser.newPage({ viewport: { width: 1400, height: 1000 } });
await page.goto(`${BASE}/login`, { waitUntil: "networkidle" });
const token = await page.evaluate(async (password) => {
  const r = await fetch("/api/login", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username: "admin", password }),
  });
  return r.ok ? (await r.text()).trim() : "";
}, PASS);
await page.evaluate((t) => localStorage.setItem("jwt", t), token);
await page.goto(`${BASE}/settings/profile`, { waitUntil: "networkidle" });
await page.waitForTimeout(900);

const metrics = await page.evaluate(() => {
  const cards = [...document.querySelectorAll(".check-card")].map((el) => {
    const r = el.getBoundingClientRect();
    return {
      w: Math.round(r.width),
      h: Math.round(r.height),
      text: (el.textContent || "").replace(/\s+/g, " ").trim().slice(0, 40),
    };
  });
  const navSave = document.querySelector(".settings-nav-save");
  return {
    cards,
    navSave: navSave?.textContent?.trim() || null,
    hasResumeMin: !!document.querySelector('input[name="resumeMinSec"]'),
    appSelects: document.querySelectorAll(".app-select__trigger").length,
  };
});

await page.locator(".check-card-list").screenshot({
  path: `${OUT}/profile-interaction.png`,
});
console.log("METRICS", JSON.stringify(metrics, null, 2));
await browser.close();
