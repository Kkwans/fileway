import { chromium } from "@playwright/test";

const BASE = process.env.QA_BASE || "http://127.0.0.1:8888";
const PASS = process.env.WINFB_ADMIN_PASSWORD || "";
if (!PASS) throw new Error("Set WINFB_ADMIN_PASSWORD");
const MP4 =
  "/C/MyProgram/TODO/Telegram/我的收藏_2026-01-20/video_files/-1001890432834_39829.mp4";
const MKV = "/C/Apps/WinFileBrowser/test/sample.mkv";

function enc(p) {
  return p.split("/").map(encodeURIComponent).join("/");
}

const browser = await chromium.launch({ headless: true });
const page = await browser.newPage({ viewport: { width: 1400, height: 900 } });
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

async function seed(resumeMode) {
  await page.evaluate(
    async ({ token, path, resumeMode }) => {
      await fetch("/api/media/playback", {
        method: "PUT",
        headers: { "Content-Type": "application/json", "X-Auth": token },
        body: JSON.stringify({ path, position: 53, duration: 8000 }),
      });
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
              resumeMode,
              playbackMode: "native",
              resumeMinSec: 10,
            },
          },
        }),
      });
    },
    { token, path: MP4, resumeMode }
  );
}

function probePlayer() {
  return page.evaluate(() => {
    const layer = document.querySelector(".art-layer-auto-playback");
    const stage = document.querySelector(".art-player-stage");
    return {
      toast: layer
        ? {
            text: (layer.textContent || "").replace(/\s+/g, " ").trim(),
            display: getComputedStyle(layer).display,
          }
        : null,
      loading: (() => {
        const el = stage?.querySelector(":scope > .art-loading");
        return el
          ? {
              visible: getComputedStyle(el).display !== "none",
              text: el.textContent?.replace(/\s+/g, " ").trim() || "",
            }
          : null;
      })(),
      mode:
        document
          .querySelector(".art-control-playback-mode")
          ?.textContent?.replace(/\s+/g, " ")
          .trim() || "",
    };
  });
}

await seed("resume");
await page.goto(`${BASE}/files${enc(MP4)}?preview=true`, { waitUntil: "domcontentloaded" });
await page.waitForSelector(".art-video-player", { timeout: 20000 });
await page.waitForTimeout(2500);
const toastEarly = await probePlayer();
await page.waitForTimeout(5000);
const toastLate = await probePlayer();
console.log("TOAST_EARLY", JSON.stringify(toastEarly, null, 2));
console.log("TOAST_LATE", JSON.stringify(toastLate, null, 2));

await page.goto(`${BASE}/files${enc(MKV)}?preview=true`, { waitUntil: "domcontentloaded" });
await page.waitForSelector(".art-video-player", { timeout: 20000 });
await page.waitForTimeout(900);
const mkvEarly = await probePlayer();
await page.waitForTimeout(3500);
const mkvLate = await probePlayer();
console.log("MKV_EARLY", JSON.stringify(mkvEarly, null, 2));
console.log("MKV_LATE", JSON.stringify(mkvLate, null, 2));
console.log("DONE");
await browser.close();
