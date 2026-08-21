// Playwright (core, from npx cache) + cached Chromium: top 10 BBC headlines.
const { chromium } = require("/home/kali/.npm/_npx/31e32ef8478fbf80/node_modules/playwright-core");

(async () => {
  const browser = await chromium.launch({
    headless: true,
    args: ["--no-sandbox", "--disable-dev-shm-usage"],
  });
  const page = await browser.newPage({ viewport: { width: 1280, height: 900 } });
  await page.goto("https://www.bbc.com/news", { waitUntil: "domcontentloaded", timeout: 45000 });
  await page.waitForTimeout(3500);

  const headlines = await page.evaluate(() => {
    const els = document.querySelectorAll("h1, h2, h3, [data-testid='card-headline']");
    const seen = new Set();
    const out = [];
    for (const e of els) {
      const t = (e.innerText || "").replace(/\s+/g, " ").trim();
      if (t.length < 15 || seen.has(t.toLowerCase())) continue;
      seen.add(t.toLowerCase());
      out.push(t);
      if (out.length >= 12) break;
    }
    return out;
  });

  console.log("PAGE_TITLE:", await page.title());
  console.log("URL:", page.url());
  console.log("HEADLINES_FOUND:", headlines.length);
  headlines.slice(0, 10).forEach((t, i) => console.log(`${i + 1}. ${t}`));
  await browser.close();
})().catch((e) => { console.error("ERROR:", e.message); process.exit(1); });
