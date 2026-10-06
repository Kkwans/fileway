import { chromium } from "@playwright/test";

const BASE = "http://127.0.0.1:8888";
const USER = "admin";
const PASS = process.env.WINFB_ADMIN_PASSWORD || "";
if (!PASS) throw new Error("Set WINFB_ADMIN_PASSWORD");
const MP4 =
  "/C/MyProgram/TODO/Telegram/鎴戠殑鏀惰棌_2026-01-20/video_files/-1001890432834_39829.mp4";

const browser = await chromium.launch({ headless: true });
const page = await browser.newPage({ viewport: { width: 1280, height: 800 } });
page.on("console", (msg) => {
  const t = msg.type();
  if (t === "error" || t === "warning") console.log("BROWSER", t, msg.text().slice(0, 300));
});
page.on("pageerror", (err) => console.log("PAGEERROR", String(err).slice(0, 400)));

await page.goto(`${BASE}/login`, { waitUntil: "networkidle" });
const token = await page.evaluate(async ({ u, p }) => {
  const r = await fetch("/api/login", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username: u, password: p }),
  });
  return r.ok ? (await r.text()).trim() : "";
}, { u: USER, p: PASS });
await page.evaluate((t) => localStorage.setItem("jwt", t), token);
await page.evaluate(async ({ path, token }) => {
  await fetch("/api/media/playback", {
    method: "PUT",
    headers: { "Content-Type": "application/json", "X-Auth": token },
    body: JSON.stringify({ path, position: 42.5, duration: 8365 }),
  });
  const key = "artplayer_settings";
  const raw = localStorage.getItem(key);
  const data = raw ? JSON.parse(raw) : {};
  data.times = data.times || {};
  data.times[path] = 42.5;
  localStorage.setItem(key, JSON.stringify(data));
}, { path: MP4, token });

const url = `${BASE}/files${MP4.split("/").map(encodeURIComponent).join("/")}?preview=true`;
await page.goto(url, { waitUntil: "domcontentloaded", timeout: 45000 });
await page.waitForSelector(".art-video-player", { timeout: 20000 });
await page.waitForTimeout(4000);

const dump = await page.evaluate(() => {
  const root = document.querySelector(".art-video-player");
  const stage = document.querySelector(".art-player-stage");
  return {
    classes: root?.className || "",
    controlsHTML: root?.querySelector(".art-controls-right")?.innerHTML?.slice(0, 800) || "",
    controlClasses: [...(root?.querySelectorAll(".art-control") || [])].map((el) => el.className),
    settingsItems: [...(root?.querySelectorAll(".art-setting-item") || [])].map((el) => ({
      name: el.getAttribute("data-name"),
      text: (el.textContent || "").trim().slice(0, 40),
      tip: el.querySelector(".art-setting-item-right-tooltip")?.textContent?.trim() || "",
    })),
    autoHTML: root?.querySelector(".art-layer-auto-playback")?.outerHTML?.slice(0, 300) || "",
    storage: localStorage.getItem("artplayer_settings"),
    loading: stage?.querySelector(".art-loading")?.textContent || null,
    loadingDisplay: stage?.querySelector(".art-loading")
      ? getComputedStyle(stage.querySelector(".art-loading")).display
      : null,
    notice: root?.querySelector(".art-notice-inner")?.textContent || "",
  };
});
console.log(JSON.stringify(dump, null, 2));
await page.screenshot({ path: "artplayer-dump.png" });
await browser.close();

