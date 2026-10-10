"""Build or modify the app's SharedPreferences XML for emulator tests.

seed  <emulator-local "YYYY-MM-DD HH:MM:SS"> <times-out>  -> prints prefs XML to stdout
region <prefs-in> <oblast-id> <oblast-name>               -> prints modified prefs XML
"""
import datetime
import html
import json
import re
import sys


def s(name, val):
    return f'    <string name="{name}">{html.escape(val)}</string>'


def seed(now_str, times_out):
    now = datetime.datetime.strptime(now_str.strip(), "%Y-%m-%d %H:%M:%S")
    a = now.replace(second=0) + datetime.timedelta(minutes=2)
    b = a + datetime.timedelta(minutes=5)
    alarms = [
        # A: waits during red/yellow alerts, gives up after 30 min
        {"id": 1, "h": a.hour, "m": a.minute, "days": [], "on": True, "tone": 1, "red": True, "yellow": True, "wait": 30},
        # B: rings even during an alert
        {"id": 2, "h": b.hour, "m": b.minute, "days": [], "on": True, "tone": 0, "red": False, "yellow": False, "wait": 120},
    ]
    region = {"oid": "16", "on": "Луганська область", "did": "", "dn": ""}
    with open(times_out, "w") as f:
        f.write(f"{int((a - now).total_seconds())} {int((b - now).total_seconds())} {a:%H:%M} {b:%H:%M}\n")
    print("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>")
    print("<map>")
    print(s("alarms", json.dumps(alarms, ensure_ascii=False)))
    print(s("region", json.dumps(region, ensure_ascii=False)))
    print('    <int name="next_id" value="10" />')
    print('    <boolean name="asked_notif" value="true" />')
    print('    <boolean name="asked_notif_2" value="true" />')
    print("</map>")


def set_region(path, oid, name):
    xml = open(path, encoding="utf-8").read()
    region = {"oid": oid, "on": name, "did": "", "dn": ""}
    new = s("region", json.dumps(region, ensure_ascii=False)).strip()
    xml, n = re.subn(r'<string name="region">.*?</string>', new, xml, flags=re.S)
    if n == 0:
        xml = xml.replace("</map>", "    " + new + "\n</map>")
    print(xml)


if __name__ == "__main__":
    if sys.argv[1] == "seed":
        seed(sys.argv[2], sys.argv[3])
    else:
        set_region(sys.argv[2], sys.argv[3], sys.argv[4])
