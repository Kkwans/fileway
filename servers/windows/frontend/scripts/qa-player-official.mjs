import { chromium } from "@playwright/test";

const BASE = "http://127.0.0.1:8888";
const MP4 =
  "/C/MyProgram/TODO/Telegram/鎴戠殑鏀惰棌_2026-01-20/video_files/-1001890432834_39829.mp4";

function enc(p) {
  return p.split("/").map(encodeURIComponent).join("/");
}

const browser = await chromium.launch({ headless: true });
const page = await browser.newPage({ viewport: { width: 1400, height: 900 } });
page.on("pageerror", (e) => console.log("PAGEERROR", String(e).slice(0, 200)));

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

async function seed(resumeMode) {
  await page.evaluate(
    async ({ token, path, resumeMode }) => {
      await fetch("/api/media/playback", {
        method: "PUT",
        headers: { "Content-Type": "application/json", "X-Auth": token },
        body: JSON.stringify({ path, position: 53, duration: 8000 }),
      });
      const me = await fetch("/api/users/1", {
        headers: { "X-Auth": token },
      }).then((r) => r.json());
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
    const root = document.querySelector(".art-video-player") || document.querySelector(".art-player-stage");
    const layer = document.querySelector(".art-layer-auto-playback");
    return {
      hasOfficialLayer: !!layer,
      display: layer ? getComputedStyle(layer).display : null,
      close: !!layer?.querySelector(".art-auto-playback-close svg, .art-auto-playback-close"),
      last: layer?.querySelector(".art-auto-playback-last")?.textContent?.trim() || "",
      jump: layer?.querySelector(".art-auto-playback-jump")?.textContent?.trim() || "",
      bg: layer ? getComputedStyle(layer).backgroundColor : null,
    };
  });
}

await seed("from-start");
await page.goto(`${BASE}/files${enc(MP4)}?preview=true`, { waitUntil: "domcontentloaded" });
await page.waitForSelector(".art-player-stage, .art-video-player", { timeout: 20000 });
await page.waitForTimeout(3000);
console.log("FROM_START", JSON.stringify(await probePlayer(), null, 2));

await seed("resume");
await page.goto(`${BASE}/files${enc(MP4)}?preview=true`, { waitUntil: "domcontentloaded" });
await page.waitForSelector(".art-player-stage, .art-video-player", { timeout: 20000 });
await page.waitForTimeout(3000);
console.log("RESUME", JSON.stringify(await probePlayer(), null, 2));

// Open subtitle picker via settings if possible 鈥?at least mount PathPicker contract via evaluate on a crafted open
await page.evaluate(() => {
  // try click subtitle path through art settings is heavy; check component contract on files page
});
await page.goto(`${BASE}/files${enc(MP4)}?preview=true`, { waitUntil: "domcontentloaded" });
await page.waitForSelector(".art-video-player", { timeout: 20000 });
await page.waitForTimeout(1500);
// ArtPlayer settings 鈫?瀛楀箷 鈫?閫夋嫨鏂囦欢 (best effort)
const picker = await page.evaluate(async () => {
  const stage = document.querySelector(".art-player-stage");
  // Open art setting
  const settingBtn = document.querySelector(".art-setting");
  return { settingBtn: !!settingBtn, stage: !!stage };
});
console.log("PLAYER_CHROME", JSON.stringify(picker));

console.log("DONE");
await browser.close();

