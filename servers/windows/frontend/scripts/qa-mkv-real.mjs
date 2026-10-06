import { chromium } from "@playwright/test";

const BASE = process.env.QA_BASE || "http://127.0.0.1:8888";
const PASS = process.env.WINFB_ADMIN_PASSWORD || "WinFB-2026!";
const MKV =
  "/D/Kkwans/Videos/电影/隐入尘烟.Return.to.Dust.2022.1080p.WEB-DL.H265.DDP5.1-NUMTV.mkv";

function enc(p) {
  return p.split("/").map(encodeURIComponent).join("/");
}

const browser = await chromium.launch({
  headless: true,
  args: ["--disable-application-cache"],
});
const context = await browser.newContext({
  viewport: { width: 1400, height: 900 },
  bypassCSP: true,
});
const page = await context.newPage();
page.on("console", (m) => {
  if (m.type() === "error") console.log("ERR", m.text().slice(0, 160));
});
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

// native policy on account — MKV should still go compat for progress
await page.evaluate(async ({ token }) => {
  const me = await fetch("/api/users/1", { headers: { "X-Auth": token } }).then((r) =>
    r.json()
  );
  await fetch(`/api/users/${me.id}`, {
    method: "PUT",
    headers: { "Content-Type": "application/json", "X-Auth": token },
    body: JSON.stringify({
      what: "user",
      which: ["PlayerPreferences"],
      data: {
        ...me,
        id: me.id,
        playerPreferences: {
          ...(me.playerPreferences || {}),
          playbackMode: "native",
        },
      },
    }),
  });
}, { token });

const url = `${BASE}/files${enc(MKV)}?preview=true&t=${Date.now()}`;
await page.goto(url, { waitUntil: "domcontentloaded" });
await page.waitForSelector(".art-video-player", { timeout: 30000 });

function snap() {
  return page.evaluate(() => {
    const stage = document.querySelector(".art-player-stage");
    const loader = stage?.querySelector(":scope > .art-loading");
    const video = document.querySelector(".art-video-player video");
    const text = loader?.textContent?.replace(/\s+/g, " ").trim() || "";
    return {
      loaderVisible: !!loader && getComputedStyle(loader).display !== "none",
      loaderText: text,
      hasStatusChange: /排队|转码|兼容|进度|%|\d+s/.test(text),
      currentTime: video ? Math.round(video.currentTime * 10) / 10 : null,
      readyState: video?.readyState ?? null,
      paused: video?.paused ?? null,
    };
  });
}

const samples = [];
for (let i = 0; i < 20; i++) {
  await page.waitForTimeout(2500);
  const s = { t: (i + 1) * 2.5, ...(await snap()) };
  samples.push(s);
  if (s.currentTime > 2 && !s.loaderVisible) break;
}
const statusTexts = [...new Set(samples.map((s) => s.loaderText).filter(Boolean))];
console.log("STATUS_TEXTS", JSON.stringify(statusTexts));
console.log("SAMPLES", JSON.stringify(samples, null, 2));
await page.screenshot({
  path: "D:/Kkwans/Desktop/Project/MyProject/WinFileBrowser/frontend/output/settings-visual/mkv-real.png",
  fullPage: false,
});
console.log("DONE");
await browser.close();
