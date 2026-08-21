"""Playwright: navigate to BBC News and pull the top 5 headlines."""
import re
from playwright.sync_api import sync_playwright

URL = "https://www.bbc.com/news"

with sync_playwright() as p:
    browser = p.chromium.launch(
        executable_path="/usr/bin/chromium",
        headless=True,
        args=["--no-sandbox", "--disable-dev-shm-usage"],
    )
    page = browser.new_page(viewport={"width": 1280, "height": 900})
    page.goto(URL, wait_until="domcontentloaded", timeout=45000)
    page.wait_for_timeout(3500)

    # Pull likely headlines from headings and links in the main content.
    texts = page.eval_on_selector_all(
        "h1, h2, h3, [data-testid='card-headline']",
        "els => els.map(e => e.innerText.trim()).filter(t => t.length > 15)",
    )

    seen, out = set(), []
    for t in texts:
        t = re.sub(r"\s+", " ", t).strip()
        key = t.lower()
        if key in seen or len(t) < 15:
            continue
        seen.add(key)
        out.append(t)
        if len(out) >= 8:
            break

    print("PAGE_TITLE:", page.title())
    print("URL:", page.url)
    print("HEADLINES_FOUND:", len(out))
    for i, t in enumerate(out[:5], 1):
        print(f"{i}. {t}")
    browser.close()
