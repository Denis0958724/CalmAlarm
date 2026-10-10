"""Pick a district with an air alert right now: prints oblastId|oblastName|districtId|districtName."""
import json
import sys
from datetime import datetime, timezone

alerts = json.load(open(sys.argv[1], encoding="utf-8"))
regions = json.load(open(sys.argv[2], encoding="utf-8"))
parent, dname, oname = {}, {}, {}
for s in regions["states"]:
    oname[s["regionId"]] = s["regionName"]
    for d in s.get("regionChildIds", []):
        if d.get("regionType") == "District":
            parent[d["regionId"]] = s["regionId"]
            dname[d["regionId"]] = d["regionName"]
now = datetime.now(timezone.utc)
best = None
for e in alerts:
    for a in e.get("activeAlerts", []):
        rid = a.get("regionId") or e.get("regionId")
        if a.get("type") != "AIR" or rid not in parent or parent[rid] in ("16", "9999"):
            continue
        t = datetime.fromisoformat(a["lastUpdate"].replace("Z", "+00:00"))
        age = (now - t).total_seconds() / 60
        if 5 <= age <= 360 and (best is None or age < best[0]):
            best = (age, rid)
if best:
    rid = best[1]
    print(f"{parent[rid]}|{oname[parent[rid]]}|{rid}|{dname[rid]}")
else:
    print("16|Луганська область||")
