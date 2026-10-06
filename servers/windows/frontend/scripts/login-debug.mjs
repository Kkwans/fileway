import { chromium } from "@playwright/test";

const b = await chromium.launch({ headless: true });
const p = await b.newPage();
await p.goto("http://127.0.0.1:8888/login", { waitUntil: "networkidle" });
const info = await p.evaluate(() => ({
  url: location.href,
  inputs: [...document.querySelectorAll("input")].map((i) => ({
    type: i.type,
    name: i.name,
    id: i.id,
    ph: i.placeholder,
    cls: i.className,
  })),
  buttons: [...document.querySelectorAll("button")].map((i) => ({
    type: i.type,
    text: (i.textContent || "").trim().slice(0, 40),
    cls: i.className,
  })),
  body: document.body.innerText.slice(0, 500),
}));
console.log(JSON.stringify(info, null, 2));

const api = await p.evaluate(async () => {
  const r = await fetch("/api/login", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username: "admin", password: process.env.WINFB_ADMIN_PASSWORD || "" }),
  });
  return { status: r.status, text: (await r.text()).slice(0, 300) };
});
console.log("api", JSON.stringify(api));
await b.close();

