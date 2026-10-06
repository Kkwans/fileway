import { chromium } from "@playwright/test";
const BASE = process.env.QA_BASE || "http://127.0.0.1:8888";
const PASS = process.env.WINFB_ADMIN_PASSWORD || "WinFB-2026!";
const MP4 =
  "/C/MyProgram/TODO/Telegram/我的收藏_2026-01-20/video_files/-1001890432834_39829.mp4";
function enc(p) {
  return p.split("/").map(encodeURIComponent).join("/");
}
const browser = await chromium.launch({ headless: true });
const page = await browser.newPage({ viewport: { width: 1400, height: 900 } });
page.on("console", (m) => console.log("CON", m.type(), m.text().slice(0, 160)));
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
await page.goto(`${BASE}/files${enc(MP4)}?preview=true`, {
  waitUntil: "domcontentloaded",
});
await page.waitForSelector(".art-video-player", { timeout: 20000 });
for (const wait of [1000, 2000, 4000]) {
  await page.waitForTimeout(wait === 1000 ? 1000 : wait - (wait === 2000 ? 1000 : 2000));
  const snap = await page.evaluate(() => ({
    layer: !!document.querySelector(".art-layer-auto-playback"),
    winfb: !!document.querySelector(".winfb-resume-toast"),
    html:
      document
        .querySelector(".art-player-stage")
        ?.innerHTML?.includes("art-auto-playback") || false,
    notice:
      document.querySelector(".art-notice-inner")?.textContent?.trim() || "",
    playerKids:
      document.querySelector(".art-video-player")?.children?.length ?? 0,
  }));
  console.log("SNAP", wait, JSON.stringify(snap));
}
await browser.close();
