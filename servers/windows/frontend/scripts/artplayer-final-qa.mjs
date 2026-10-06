import { chromium } from "@playwright/test";

const BASE = "http://127.0.0.1:8888";
const USER = "admin";
const PASS = process.env.WINFB_ADMIN_PASSWORD || "";
if (!PASS) throw new Error("Set WINFB_ADMIN_PASSWORD");
const MP4 =
  "/C/MyProgram/TODO/Telegram/鎴戠殑鏀惰棌_2026-01-20/video_files/-1001890432834_39829.mp4";

function enc(p) {
  return p
    .split("/")
    .map(encodeURIComponent)
    .join("/");
}

function report(label, value) {
  console.log(`\n=== ${label} ===`);
  console.log(typeof value === "string" ? value : JSON.stringify(value, null, 2));
}

const browser = await chromium.launch({ headless: true });
const page = await browser.newPage({ viewport: { width: 1400, height: 900 } });
page.on("pageerror", (e) => console.log("PAGEERROR", String(e).slice(0, 250)));

try {
  await page.goto(`${BASE}/login`, { waitUntil: "networkidle", timeout: 30000 });
  const token = await page.evaluate(async ({ u, p }) => {
    const r = await fetch("/api/login", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ username: u, password: p }),
    });
    return r.ok ? (await r.text()).trim() : "";
  }, { u: USER, p: PASS });
  await page.evaluate((t) => localStorage.setItem("jwt", t), token);
  report("login", { tokenLen: token.length });

  // --- PLAYER ---
  const playerUrl = `${BASE}/files${enc(MP4)}?preview=true`;
  await page.goto(playerUrl, { waitUntil: "domcontentloaded", timeout: 45000 });
  await page.waitForSelector(".art-video-player", { timeout: 20000 });
  await page.waitForTimeout(3500);

  const player = await page.evaluate(() => {
    const root = document.querySelector(".art-video-player");
    const stage = document.querySelector(".art-player-stage");
    const settings = [
      ...document.querySelectorAll(".art-setting-item"),
    ].map((el) => ({
      name: el.getAttribute("data-name"),
      text: (el.textContent || "").trim().slice(0, 48),
      tip:
        el
          .querySelector(".art-setting-item-right-tooltip")
          ?.textContent?.trim() || "",
    }));
    const controls = [...document.querySelectorAll(".art-control")].map(
      (el) => el.className.replace(/\s+/g, " ").trim()
    );
    return {
      controls,
      settings,
      subtitleTops: settings.filter(
        (s) =>
          s.name &&
          (s.name.startsWith("subtitle-") ||
            (s.name !== "playback-subtitle" && s.name.includes("subtitle")))
      ),
      rateChip: root
        ?.querySelector(".art-control-playback-rate .art-selector-value, .art-control-playback-rate .art-bar-label")
        ?.textContent?.trim() || "",
      rateItems: [
        ...document.querySelectorAll(
          ".art-control-playback-rate .art-selector-item"
        ),
      ].map((el) => (el.textContent || "").trim()),
      headerCenter: (() => {
        const c = document.querySelector(
          "#previewer header.media-preview-header > .header-center"
        );
        if (!c) return null;
        const s = getComputedStyle(c);
        return {
          bg: s.backgroundColor,
          border: s.borderTopWidth,
          radius: s.borderTopLeftRadius,
        };
      })(),
      loadingDisplay: stage?.querySelector(".art-loading")
        ? getComputedStyle(stage.querySelector(".art-loading")).display
        : "none-el",
      pathPickerDark: !!document.querySelector(".path-picker"),
    };
  });
  report("player", player);

  // Click rate chip 鈥?independent list
  await page.locator(".art-control-playback-rate").first().click({ force: true });
  await page.waitForTimeout(400);
  const rateOpen = await page.evaluate(() => {
    const ctrl = document.querySelector(".art-control-playback-rate");
    const list = ctrl?.querySelector(".art-selector-list");
    const settings = document.querySelector(".art-settings");
    return {
      open: !!ctrl?.classList.contains("art-selector-open"),
      items: [
        ...(list?.querySelectorAll(".art-selector-item") || []),
      ].map((el) => (el.textContent || "").trim()),
      settingsOpen:
        !!settings && getComputedStyle(settings).display !== "none",
      listVisible: !!list && getComputedStyle(list).opacity !== "0",
    };
  });
  report("rate popup", rateOpen);

  // Open settings via gear 鈥?subtitle nested?
  await page.locator(".art-control-setting").first().click({ force: true });
  await page.waitForTimeout(400);
  const gear = await page.evaluate(() => {
    const items = [...document.querySelectorAll(".art-setting-item")].map(
      (el) => ({
        name: el.getAttribute("data-name"),
        text: (el.textContent || "").trim(),
      })
    );
    return items;
  });
  report("settings via gear", gear);

  // --- SETTINGS PAGES ---
  await page.goto(`${BASE}/settings/profile`, {
    waitUntil: "networkidle",
    timeout: 30000,
  });
  await page.waitForTimeout(800);
  const profile = await page.evaluate(() => {
    const navSave = document.querySelector(".settings-nav-save");
    const passwordBtn = [
      ...document.querySelectorAll("button, input[type=submit]"),
    ]
      .map((el) => (el.textContent || el.value || "").trim())
      .filter(Boolean);
    const selects = [
      ...document.querySelectorAll(".app-select select, select"),
    ].slice(0, 6).map((el) => {
      const s = getComputedStyle(el);
      return {
        appearance: s.appearance,
        height: s.height,
        border: s.borderTopWidth,
      };
    });
    const rows = [
      ...document.querySelectorAll(".setting-control-row"),
    ].map((el) => {
      const controls = el.querySelector(".setting-controls");
      return controls ? getComputedStyle(controls).justifyContent : null;
    });
    return {
      url: location.pathname,
      navSave: navSave ? (navSave.textContent || "").trim() : null,
      buttons: passwordBtn.slice(0, 12),
      selectSample: selects,
      controlJustify: [...new Set(rows)],
      hasResumeSelect: !!document.querySelector(
        'select[name="resumeMode"], select option[value="from-start"]'
      ),
    };
  });
  report("profile settings", profile);

  await page.goto(`${BASE}/settings/global`, {
    waitUntil: "networkidle",
    timeout: 30000,
  });
  await page.waitForTimeout(800);
  const global = await page.evaluate(() => {
    const titles = [
      ...document.querySelectorAll(".card-title h2, .global-card-title h2"),
    ].map((el) => (el.textContent || "").trim());
    const saves = [
      ...document.querySelectorAll("button, input[type=submit]"),
    ]
      .map((el) => (el.textContent || el.value || "").trim())
      .filter((t) => /淇濆瓨|鏇存柊/.test(t));
    const help = [...document.querySelectorAll(".setting-help")].map((el) =>
      (el.textContent || "").trim()
    );
    return {
      titles,
      saveButtons: saves,
      navSave: document.querySelector(".settings-nav-save")?.textContent?.trim() || null,
      tokenHelp: help.find((h) => h.includes("鍒嗛挓") || h.includes("澶?)) || "",
    };
  });
  report("global settings", global);

  await page.goto(`${BASE}/settings/shares`, {
    waitUntil: "networkidle",
    timeout: 30000,
  });
  await page.waitForTimeout(600);
  const shares = await page.evaluate(() => ({
    empty: document.querySelector(".shares-empty")?.textContent?.replace(/\s+/g, " ").trim() || null,
    oldMessage: document.body.innerText.includes("杩欓噷娌℃湁浠讳綍鏂囦欢"),
  }));
  report("shares", shares);

  console.log("\nDONE");
} catch (e) {
  console.error("TEST FAIL", e);
} finally {
  await browser.close();
}

