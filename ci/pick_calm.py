"""Print the id of an oblast without an active air alert right now (prefers the west)."""
import json
import sys

PREFERRED = ["11", "26", "13", "21", "27", "8", "5", "3", "4", "10"]
NAMES = {
    "11": "Закарпатська область", "26": "Чернівецька область", "13": "Івано-Франківська область",
    "21": "Тернопільська область", "27": "Львівська область", "8": "Волинська область",
    "5": "Рівненська область", "3": "Хмельницька область", "4": "Вінницька область", "10": "Житомирська область",
}
try:
    data = json.load(open(sys.argv[1], encoding="utf-8"))
    active = {e.get("regionId") for e in data if e.get("activeAlerts")}
except Exception:
    active = set()
for oid in PREFERRED:
    if oid not in active:
        print(f"{oid}|{NAMES[oid]}")
        break
else:
    print("11|Закарпатська область")
