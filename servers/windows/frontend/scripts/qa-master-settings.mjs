import { chromium } from "@playwright/test";

const BASE = "http://127.0.0.1:8888";
const MP4 =
  "/C/MyProgram/TODO/Telegram/鎴戠殑鏀惰棌_2026-01-20/video_files/-1001890432834_39829.mp4";
const MKV = "/C/Apps/WinFileBrowser/test/sample.mkv";

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

async function seedPrefs(resumeMode) {
  await page.evaluate(
    async ({ token, path, resumeMode }) => {
      await fetch("/api/media/playback", {
        method: "PUT",
        headers: { "Content-Type": "application/json", "X-Auth": token },
        body: JSON.stringify({ path, position: 42.5, duration: 8000 }),
      });
      const me = await fetch("/api/users/1", {
        headers: { "X-Auth": token },
      }).then(async (r) => {
        if (!r.ok) throw new Error("users/1 " + r.status);
        return r.json();
      });
      const id = me.id ?? 1;
      const next = {
        ...me,
        id,
        playerPreferences: {
          ...(me.playerPreferences || {}),
          resumeMode,
          playbackMode: "native",
        },
      };
      await fetch(`/api/users/${id}`, {
        method: "PUT",
        headers: { "Content-Type": "application/json", "X-Auth": token },
        body: JSON.stringify({
          what: "user",
          which: ["PlayerPreferences"],
          data: next,
        }),
      });
      // Keep SPA auth store in sync with server prefs.
      try {
        localStorage.setItem("user", JSON.stringify(next));
      } catch {
        /* ignore */
      }
    },
    { token, path: MP4, resumeMode }
  );
}

function playerProbe() {
  return page.evaluate(() => {
    const root = document.querySelector(".art-video-player");
    const stage = document.querySelector(".art-player-stage");
    const toast = root?.querySelector(".winfb-resume-toast");
    return {
      toast: toast
        ? {
            text: (toast.textContent || "").replace(/\s+/g, " ").trim(),
            visible: getComputedStyle(toast).display !== "none",
          }
        : null,
      loadingVisible: (() => {
        const el =
          stage?.querySelector(":scope > .art-loading") ||
          [...(stage?.querySelectorAll(".art-loading") || [])].find(
            (n) => getComputedStyle(n).display !== "none"
          );
        return !!el && getComputedStyle(el).display !== "none";
      })(),
      loadingText:
        stage?.querySelector(":scope > .art-loading .art-loading-text")?.textContent?.trim() ||
        [...(stage?.querySelectorAll(".art-loading-text") || [])]
          .map((n) => n.textContent?.trim())
          .filter(Boolean)
          .join(" | ") ||
        "",
      notice: root?.querySelector(".art-notice-inner")?.textContent?.trim() || "",
      modeChip:
        root?.querySelector(".art-control-playback-mode")?.textContent?.trim() ||
        "",
    };
  });
}

// Resume mode must show toast
await seedPrefs("resume");
await page.goto(`${BASE}/files${enc(MP4)}?preview=true`, {
  waitUntil: "domcontentloaded",
});
await page.waitForSelector(".art-video-player", { timeout: 20000 });
await page.waitForTimeout(3500);
console.log("RESUME_MODE", JSON.stringify(await playerProbe(), null, 2));

// From-start mode must show chooser toast
await seedPrefs("from-start");
await page.goto(`${BASE}/files${enc(MP4)}?preview=true`, {
  waitUntil: "domcontentloaded",
});
await page.waitForSelector(".art-video-player", { timeout: 20000 });
await page.waitForTimeout(3500);
console.log("FROM_START_MODE", JSON.stringify(await playerProbe(), null, 2));

// MKV: loader and/or auto compat
await page.evaluate(async ({ token }) => {
  const me = await fetch("/api/users/1", { headers: { "X-Auth": token } }).then((r) =>
    r.json()
  );
  const id = me.id ?? 1;
  await fetch(`/api/users/${id}`, {
    method: "PUT",
    headers: { "Content-Type": "application/json", "X-Auth": token },
    body: JSON.stringify({
      what: "user",
      which: ["PlayerPreferences"],
      data: {
        ...me,
        id,
        playerPreferences: {
          ...(me.playerPreferences || {}),
          resumeMode: "resume",
          playbackMode: "native",
        },
      },
    }),
  });
}, { token });
await page.goto(`${BASE}/files${enc(MKV)}?preview=true`, {
  waitUntil: "domcontentloaded",
});
await page.waitForSelector(".art-video-player", { timeout: 20000 });
await page.waitForTimeout(800);
const mkvEarly = await playerProbe();
await page.waitForTimeout(4000);
const mkvLate = await playerProbe();
console.log("MKV_EARLY", JSON.stringify(mkvEarly, null, 2));
console.log("MKV_LATE", JSON.stringify(mkvLate, null, 2));

await page.goto(`${BASE}/settings/profile`, { waitUntil: "networkidle" });
await page.waitForTimeout(1200);
const profile = await page.evaluate(() => {
  const grid = document.querySelector(".profile-settings-grid");
  const navSave = document.querySelector(".settings-nav-save");
  const badButtons = [...document.querySelectorAll("button, input[type=submit]")]
    .map((el) => (el.textContent || el.value || "").trim())
    .filter((t) => /^(鏇存柊|淇濆瓨)$/.test(t));
  const rows = [...document.querySelectorAll(".setting-control-row")].map((el) => {
    const controls = el.querySelector(".setting-controls");
    const field = controls?.querySelector("input, select, .app-select");
    return {
      label: el.querySelector("strong")?.textContent || "",
      controlW: field ? Math.round(field.getBoundingClientRect().width) : 0,
      controlsW: controls ? Math.round(controls.getBoundingClientRect().width) : 0,
      right: field ? Math.round(field.getBoundingClientRect().right) : 0,
    };
  });
  const rightCol = document.querySelector(".profile-settings-grid > .column:last-child");
  const sel = document.querySelector('select[name="selectAceEditorTheme"]');
  return {
    gridCols: grid ? getComputedStyle(grid).gridTemplateColumns : null,
    navSave: navSave?.textContent?.trim() || null,
    badButtons,
    rows,
    rightColH: rightCol ? Math.round(rightCol.getBoundingClientRect().height) : null,
    editorTheme: sel
      ? {
          options: sel.options.length,
          value: sel.value,
          w: Math.round(sel.getBoundingClientRect().width),
          h: Math.round(sel.getBoundingClientRect().height),
        }
      : null,
  };
});
console.log("PROFILE", JSON.stringify(profile, null, 2));

await page.goto(`${BASE}/settings/global`, { waitUntil: "networkidle" });
await page.waitForTimeout(600);
const global = await page.evaluate(() => ({
  navSave: document.querySelector(".settings-nav-save")?.textContent?.trim() || null,
  bottomSaves: [...document.querySelectorAll(".card-action button, .card-action input")]
    .map((el) => (el.textContent || el.value || "").trim())
    .filter(Boolean),
  titleSaves: [...document.querySelectorAll(".card-title button")]
    .map((el) => (el.textContent || "").trim())
    .filter(Boolean),
}));
console.log("GLOBAL", JSON.stringify(global, null, 2));

await page.goto(`${BASE}/settings/shares`, { waitUntil: "networkidle" });
await page.waitForTimeout(400);
const shares = await page.evaluate(() => ({
  empty: document.querySelector(".shares-empty")?.textContent?.replace(/\s+/g, " ").trim() || null,
  hasIcon: !!document.querySelector(".shares-empty svg"),
  steps: document.querySelectorAll(".shares-steps li").length,
}));
console.log("SHARES", JSON.stringify(shares, null, 2));

console.log("DONE");
await browser.close();

