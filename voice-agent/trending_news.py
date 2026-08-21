import urllib.request, xml.etree.ElementTree as ET, html

feeds = {
    "Google News": "https://news.google.com/rss?hl=en-US&gl=US&ceid=US:en",
    "BBC": "http://feeds.bbci.co.uk/news/rss.xml",
    "Reuters": "https://www.reutersagency.com/feed/?best-topics=top-news&post_type=best",
}
seen, out = set(), []
for src, url in feeds.items():
    try:
        req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
        root = ET.fromstring(urllib.request.urlopen(req, timeout=20).read())
        for item in root.iter("item"):
            t = html.unescape(item.findtext("title") or "").strip()
            if t and t.lower() not in seen:
                seen.add(t.lower())
                out.append((t, src))
    except Exception as e:
        print(f"[{src} failed: {e}]")
for i, (t, s) in enumerate(out[:15], 1):
    print(f"{i}. [{s}] {t}")
print("TOTAL:", len(out))
