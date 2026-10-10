"""Print the id of an oblast without any active air alert right now (prefers the west)."""
import json
import sys

PREFERRED = ["11", "26", "13", "21", "27", "8", "5", "3", "4", "10"]
NAMES = {
    "11": "Закарпатська область", "26": "Чернівецька область", "13": "Івано-Франківська область",
    "21": "Тернопільська область", "27": "Львівська область", "8": "Волинська область",
    "5": "Рівненська область", "3": "Хмельницька область", "4": "Вінницька область", "10": "Житомирська область",
}
active = set()
try:
    data = json.load(open(sys.argv[1], encoding="utf-8"))
    parent = {}
    if len(sys.argv) > 2:
        try:
            for s in json.load(open(sys.argv[2], encoding="utf-8"))["states"]:
                for d in s.get("regionChildIds", []):
                    parent[d["regionId"]] = s["regionId"]
                    for c in d.get("regionChildIds", []):
                        parent[c["regionId"]] = s["regionId"]
        except Exception:
            pass
    for e in data:
        for a in e.get("activeAlerts", []):
            rid = a.get("regionId") or e.get("regionId")
            active.add(parent.get(rid, rid))
except Exception:
    pass
for oid in PREFERRED:
    if oid not in active:
        print(f"{oid}|{NAMES[oid]}")
        break
else:
    print("11|Закарпатська область")
