import { chromium } from "@playwright/test";

const BASE = "http://127.0.0.1:8888";
const USER = "admin";
const PASS = process.env.WINFB_ADMIN_PASSWORD || "";
if (!PASS) throw new Error("Set WINFB_ADMIN_PASSWORD");
const MP4 =
  "/C/MyProgram/TODO/Telegram/鎴戠殑鏀惰棌_2026-01-20/video_files/-1001890432834_39829.mp4";
// Prefer HEVC MKV if present; fall back to mp4.
const MKV =
  process.env.TEST_MKV ||
  "/C/MyProgram/TODO/Telegram/鎴戠殑鏀惰棌_2026-01-20/video_files/闅愬叆灏樼儫.Return.to.Dust.2022.1080p.WEB-DL.H265.DDP5.1-NUMTV.mkv";

function enc(p) {
  return p.split("/").map(encodeURIComponent).join("/");
}

function report(label, value) {
  console.log(`\n=== ${label} ===`);
  console.log(typeof value === "string" ? value : JSON.stringify(value, null, 2));
}

async function openPlayer(page, path, tag) {
  const url = `${BASE}/files${enc(path)}?preview=true`;
  await page.goto(url, { waitUntil: "domcontentloaded", timeout: 45000 });
  await page.waitForSelector(".art-video-player, .art-player-stage", {
    timeout: 20000,
  });
  await page.waitForTimeout(1500);
  return page.evaluate((t) => {
    const root = document.querySelector(".art-video-player");
    const stage = document.querySelector(".art-player-stage");
    const loading = stage?.querySelector(".art-loading");
    const loadingText = loading?.textContent?.trim() || "";
    const video = document.querySelector("video");
    const auto = root?.querySelector(".art-layer-auto-playback");
    const rateList = [
      ...(root?.querySelectorAll(".art-control-playback-rate .art-selector-item") || []),
    ].map((el) => (el.textContent || "").trim());
    const bar = [
      ...(root?.querySelectorAll(".art-bar-label, .art-control-playback-rate .art-selector-value") || []),
    ]
      .map((n) => (n.textContent || "").trim())
      .filter(Boolean);
    return {
      tag: t,
      url: location.pathname,
      artCount: document.querySelectorAll(".art-video-player").length,
      loadingVisible: !!loading && getComputedStyle(loading).display !== "none",
      loadingText,
      notice: root?.querySelector(".art-notice-inner")?.textContent?.trim() || "",
      playbackRate: video?.playbackRate,
      duration: video?.duration,
      readyState: video?.readyState,
      autoPlaybackPresent: !!auto,
      autoPlaybackDisplay: auto ? getComputedStyle(auto).display : null,
      autoPlaybackText: auto?.textContent?.replace(/\s+/g, " ").trim() || "",
      rateList,
      bar,
      modeChip: root?.querySelector(".art-control-playback-mode")?.textContent?.trim() || "",
      settingShow: !!root?.classList.contains("art-setting-show"),
    };
  }, tag);
}

const browser = await chromium.launch({ headless: true });
const page = await browser.newPage({ viewport: { width: 1280, height: 800 } });

try {
  // API login + inject jwt (form login is Vue-bound; API is reliable for CI).
  await page.goto(`${BASE}/login`, { waitUntil: "networkidle", timeout: 30000 });
  const login = await page.evaluate(async ({ u, p }) => {
    const r = await fetch("/api/login", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ username: u, password: p }),
    });
    const text = await r.text();
    return { status: r.status, token: r.ok ? text.trim() : "" };
  }, { u: USER, p: PASS });
  report("login api", { status: login.status, tokenLen: login.token.length });
  if (!login.token) throw new Error("login api failed");
  await page.evaluate((t) => {
    localStorage.setItem("jwt", t);
  }, login.token);

  // Seed resume so autoPlayback has something to show
  await page.evaluate(async ({ path, token }) => {
    await fetch("/api/media/playback", {
      method: "PUT",
      headers: {
        "Content-Type": "application/json",
        "X-Auth": token,
      },
      body: JSON.stringify({ path, position: 42.5, duration: 120 }),
    });
  }, { path: MP4, token: login.token });

  await page.evaluate((path) => {
    const key = "artplayer_settings";
    const raw = localStorage.getItem(key);
    const data = raw ? JSON.parse(raw) : {};
    data.times = data.times || {};
    data.times[path] = 42.5;
    localStorage.setItem(key, JSON.stringify(data));
  }, MP4);

  const mp4 = await openPlayer(page, MP4, "mp4");
  report("MP4 player", mp4);

  // Click rate chip
  await page.locator(".art-control-playback-rate").first().click({ force: true });
  await page.waitForTimeout(400);
  const rateOpen = await page.evaluate(() => {
    const root = document.querySelector(".art-video-player");
    const ctrl = root?.querySelector(".art-control-playback-rate");
    const list = ctrl?.querySelector(".art-selector-list");
    return {
      chipOpen: !!ctrl?.classList.contains("art-selector-open"),
      listItems: [...(list?.querySelectorAll(".art-selector-item") || [])].map(
        (el) => ({
          text: (el.textContent || "").trim(),
          visible: !!(
            el.offsetWidth ||
            el.offsetHeight ||
            el.getClientRects().length
          ),
        })
      ),
      hasExtraPresets: !!(list && /0\.75|2\.50|3\.00/.test(list.textContent || "")),
    };
  });
  report("MP4 rate list", rateOpen);

  // Try MKV
  let mkvStatus = "skipped";
  try {
    const probe = await page.request.get(`${BASE}/api/raw${enc(MKV)}`);
    mkvStatus = `raw ${probe.status()}`;
  } catch (e) {
    mkvStatus = String(e);
  }
  const mkv = await openPlayer(page, MKV, "mkv").catch((e) => ({
    tag: "mkv",
    error: String(e),
  }));
  report("MKV player", mkv);
  report("MKV raw probe", mkvStatus);

  // Header filename chip CSS
  await page.goto(
    `${BASE}/files${enc(MP4)}?preview=true`,
    { waitUntil: "domcontentloaded" }
  );
  await page.waitForTimeout(2000);
  const header = await page.evaluate(() => {
    const center = document.querySelector(
      "#previewer header.media-preview-header > .header-center"
    );
    if (!center) return { found: false };
    const s = getComputedStyle(center);
    return {
      found: true,
      background: s.backgroundColor,
      border: s.border,
      borderRadius: s.borderRadius,
      backdropFilter: s.backdropFilter || s.webkitBackdropFilter,
      title: center.querySelector(".header-title")?.textContent || "",
    };
  });
  report("header-center style", header);

  await page.screenshot({ path: "artplayer-selftest.png" });
  console.log("\nDONE");
} catch (e) {
  console.error("TEST FAIL", e);
  await page.screenshot({ path: "artplayer-selftest-fail.png" }).catch(() => {});
} finally {
  await browser.close();
}

