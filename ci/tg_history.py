"""Download public Telegram channel history (t.me/s preview) back to a date. Output: JSONL (id, t, text)."""
import gzip
import html
import json
import re
import sys
import time
import urllib.request
from datetime import datetime, timezone

channel, since, out = sys.argv[1], sys.argv[2], sys.argv[3]
since_dt = datetime.fromisoformat(since).replace(tzinfo=timezone.utc)
UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"
post_re = re.compile(r'data-post="[^/]+/(\d+)"(.*?)(?=data-post="|\Z)', re.S)
text_re = re.compile(r'<div class="[^"]*js-message_text[^"]*"[^>]*>(.*?)</div>', re.S)
time_re = re.compile(r'<time[^>]*datetime="([^"]+)"')


def get(url):
    for attempt in range(8):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": UA})
            with urllib.request.urlopen(req, timeout=30) as r:
                return r.read().decode("utf-8", "replace")
        except Exception as e:
            wait = 5 * (attempt + 1)
            print("retry", url, e, "sleep", wait, flush=True)
            time.sleep(wait)
    raise RuntimeError("failed " + url)


def clean(s):
    s = re.sub(r"<br\s*/?>", "\n", s)
    s = re.sub(r"<[^>]+>", "", s)
    return html.unescape(s).strip()


seen = {}
url = f"https://t.me/s/{channel}"
before = None
pages = 0
while True:
    page = get(url if before is None else f"{url}?before={before}")
    pages += 1
    ids = []
    for m in post_re.finditer(page):
        pid = int(m.group(1))
        body = m.group(2)
        tm = time_re.search(body)
        tx = text_re.search(body)
        if not tm:
            continue
        ids.append(pid)
        if tx and pid not in seen:
            seen[pid] = (tm.group(1), clean(tx.group(1)))
    if not ids:
        print("no posts on page, stop", flush=True)
        break
    oldest = min(ids)
    oldest_t = datetime.fromisoformat(seen.get(oldest, (None,))[0] or min(v[0] for v in seen.values()))
    if pages % 25 == 0:
        print(pages, "pages, oldest", oldest, oldest_t, "posts", len(seen), flush=True)
    if oldest_t < since_dt or oldest <= 1 or before == oldest:
        break
    before = oldest
    time.sleep(0.6)

with gzip.open(out, "wt", encoding="utf-8") as f:
    for pid in sorted(seen):
        t, tx = seen[pid]
        f.write(json.dumps({"id": pid, "t": t, "text": tx}, ensure_ascii=False) + "\n")
print("done", channel, len(seen), "posts,", pages, "pages")
