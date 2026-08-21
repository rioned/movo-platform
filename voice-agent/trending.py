import urllib.request
import xml.etree.ElementTree as ET

def fetch(url):
    req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
    return urllib.request.urlopen(req, timeout=15).read()

# Google Trends: currently trending searches (US, past 24h)
try:
    data = fetch('https://trends.google.com/trending/rss?geo=US')
    root = ET.fromstring(data)
    items = root.findall('.//item')
    print('=== GOOGLE TRENDS (US) ===')
    for i, it in enumerate(items[:10], 1):
        print(f"{i}. {it.findtext('title')}")
except Exception as e:
    print('trends failed:', e)

# Google News: top headlines
try:
    data = fetch('https://news.google.com/rss?hl=en-US&gl=US&ceid=US:en')
    root = ET.fromstring(data)
    items = root.findall('.//item')
    print('=== GOOGLE NEWS TOP ===')
    for i, it in enumerate(items[:10], 1):
        print(f"{i}. {it.findtext('title')}")
except Exception as e:
    print('news failed:', e)
