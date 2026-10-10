"""Telegram features, mirroring Chance.kt exactly (window 30 min)."""
import gzip
import json
import re
import regex
import numpy as np
import pandas as pd

THREAT = ["бпла", "шахед", "дрон", "мопед", "ракет", "баліст", "каб", "реактив", "курс", "напрям", "загроз",
          "ціл", "груп", "кинджал", "калібр", "іскандер", "авіабомб", "герань", "гербер", "х-", "пролітає",
          "прямує", "рухаєт", "швидкісн", "крилат"]
CLEAR = ["відбій", "чисто", "мінус", "збит", "знищен", "дорозвідк", "без загроз", "покинув", "покинули"]
GLOBAL = ["міг-31", "миг-31", "міг31", "кинджал", "ту-95", "ту-160", "ту-22", "стратег", "баліст", "масован",
          "х-101", "х-555", "калібр", "пуски", "пуск ракет", "всій території", "по всій україні", "ракетна небезпека"]
DIR = r"(?:курс(?:ом)?\s+на|в\s+напрямку|у\s+напрямку|напрям(?:ок|ку)?|на|над|через|біля|повз|в|у|до)\s+(?:\p{L}+\s+)?(?:"


def parse_regions_kt(path):
    src = open(path, encoding="utf-8").read()
    src = src[src.index("val oblasts: List<Oblast> = listOf("):src.index("val byId")]
    out = {}
    for block in src.split("Oblast(")[1:]:
        strs = re.findall(r'"((?:[^"\\]|\\.)*)"', block)
        if len(strs) < 3:
            continue
        name, pats = strs[1], strs[2:]
        pats = [p.replace("$E", r"(?!\p{L})").replace("\\\\", "\\") for p in pats]
        out[name] = pats
    return out


def norm(s):
    return s.lower().replace("’", "'").replace("ʼ", "'").replace("`", "'")


def place_stem(name):
    first = name.lower().replace("’", "'")
    if first.startswith("м. "):
        first = first[3:]
    first = first.split(" ")[0] if first else ""
    stem = re.sub(r"(ськ|цьк|зьк)ий$", "", first)
    return regex.escape(stem[:8]) if len(stem) >= 4 else None


def load_posts(paths):
    rows = []
    for p in paths:
        with gzip.open(p, "rt", encoding="utf-8") as f:
            for line in f:
                o = json.loads(line)
                rows.append((pd.Timestamp(o["t"]).tz_convert("UTC"), norm(o["text"])))
    rows.sort(key=lambda x: x[0])
    return rows


def compute(posts, oblast_pats, units, times, BIN, window_bins=6):
    """units: list of (key, oblast_name, place_name_or_None). Returns dict of arrays per unit + global array."""
    n = len(times)
    start = times[0]
    obl_re = {o: regex.compile(r"(?<!\p{L})(?:" + "|".join(p) + ")") for o, p in oblast_pats.items()}
    any_re = regex.compile(r"(?<!\p{L})(?:" + "|".join(x for p in oblast_pats.values() for x in p) + ")")
    stems = {}
    for key, o, place in units:
        st = place_stem(place) if place else None
        stems[key] = st
    dist_re = {k: regex.compile(r"(?<!\p{L})" + st) for k, st in stems.items() if st}
    dir_re = {}
    for key, o, place in units:
        mine = list(oblast_pats.get(o, [])) + ([stems[key]] if stems[key] else [])
        if mine:
            dir_re[key] = regex.compile(DIR + "|".join(mine) + ")")
    # events per post -> add to bins [b0, b0+window)
    cnt = {k: np.zeros(n + window_bins + 1, np.int32) for k, _, _ in units}
    dir_a = {k: np.zeros(n + window_bins + 1, np.int32) for k, _, _ in units}
    dist_a = {k: np.zeros(n + window_bins + 1, np.int32) for k, _, _ in units}
    clr_a = {k: np.zeros(n + window_bins + 1, np.int32) for k, _, _ in units}
    glob = np.zeros(n + window_bins + 1, np.int32)
    for t, text in posts:
        b0 = int(np.ceil((t - start) / BIN))  # first bin whose time >= post time
        if b0 >= n or b0 + window_bins <= 0:
            continue
        b0 = max(b0, 0)
        if "повітряна тривога в" in text or "відбій тривоги" in text:
            continue
        is_clear = any(w in text for w in CLEAR)
        is_threat = any(w in text for w in THREAT)
        obl_hit = {o for o, r in obl_re.items() if r.search(text)}
        any_hit = bool(obl_hit) or bool(any_re.search(text))
        mentioned_any_unit = False
        for key, o, place in units:
            d_hit = key in dist_re and dist_re[key].search(text) is not None
            mine = (o in obl_hit) or d_hit
            if not mine:
                continue
            mentioned_any_unit = True
            sl = slice(b0, b0 + window_bins)
            if is_clear:
                clr_a[key][sl] += 1
            elif is_threat:
                cnt[key][sl] += 1
                if key in dir_re and dir_re[key].search(text):
                    dir_a[key][sl] += 1
                if d_hit:
                    dist_a[key][sl] += 1
        # global flag (per-unit "not mine" is approximated as global for everyone; unit-specific exclusion below)
        if not is_clear and any(w in text for w in GLOBAL) and ((not any_hit) or "україн" in text or "всій території" in text):
            glob[b0:b0 + window_bins] += 1
    return ({k: (cnt[k][:n], dir_a[k][:n] > 0, dist_a[k][:n] > 0, clr_a[k][:n] > 0) for k in cnt}, glob[:n] > 0)
