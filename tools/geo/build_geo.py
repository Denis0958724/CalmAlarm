"""Будує компактний файл меж областей і районів для карти на вкладці «Ймовірність».

Джерело меж: ua-geo (КАТОТТГ + OpenStreetMap), ліцензія меж ODbL 1.0, © учасники OpenStreetMap.
Берегова лінія: Natural Earth 10m land (суспільне надбання) - щоб межі не заходили в море.
Запуск: python3 build_geo.py boundaries.geojson.gz siren_regions.json ne_10m_land.geojson out.json
"""
import gzip, json, math, sys
from shapely.geometry import shape, mapping
from shapely.ops import unary_union

src, regions_path, land_path, out_path = sys.argv[1:5]
from shapely.geometry import box
from shapely.prepared import prep

_ua_box = box(21.5, 43.8, 41.0, 53.0)
_land = []
for f in json.load(open(land_path))["features"]:
    g = shape(f["geometry"])
    if g.intersects(_ua_box):
        _land.append(g.intersection(_ua_box))
LAND = unary_union(_land).buffer(0)

def on_land(g):
    """Обрізає морську частину (територіальні води) по береговій лінії."""
    return g.intersection(LAND).buffer(0)

LAT0 = 48.5
KX = math.cos(math.radians(LAT0))
Q = 0.004  # крок квантування, градуси широти (~450 м)

OBLAST_IDS = {
    "Вінницька область": "4", "Волинська область": "8", "Дніпропетровська область": "9", "Донецька область": "28",
    "Житомирська область": "10", "Закарпатська область": "11", "Запорізька область": "12",
    "Івано-Франківська область": "13", "Київська область": "14", "Київ": "31", "Кіровоградська область": "15",
    "Луганська область": "16", "Львівська область": "27", "Миколаївська область": "17", "Одеська область": "18",
    "Полтавська область": "19", "Рівненська область": "5", "Сумська область": "20", "Тернопільська область": "21",
    "Харківська область": "22", "Херсонська область": "23", "Хмельницька область": "3", "Черкаська область": "24",
    "Чернівецька область": "26", "Чернігівська область": "25", "Автономна Республіка Крим": "9999",
    "Севастополь": "9999",
}
CITY_PREFIXES = ["м. Харків", "м. Запоріжжя", "м. Нікополь", "м. Марганець", "м. Краматорськ"]
# Назва громади в КАТОТТГ для міст з окремими тривогами.
CITY_HROMADA = {"м. Харків": "Харківська", "м. Запоріжжя": "Запорізька", "м. Нікополь": "Нікопольська",
                "м. Марганець": "Марганецька", "м. Краматорськ": "Краматорська"}
# Райони, перейменовані після 2023 року, в API тривог ще під старими назвами.
ALIASES = {"володимирський район": "володимир-волинський район", "берестинський район": "красноградський район",
           "сіверськодонецький район": "сєвєродонецький район"}

def norm(s):
    return s.replace("’", "'").replace("ʼ", "'").strip().lower()

# Райони й громади з API тривог (siren.pp.ua) - щоб ключі карти збігалися з ідентифікаторами тривог.
reg = json.load(open(regions_path))
siren_districts = {}  # (oblastId, name) -> id
siren_cities = {}     # (oblastId, short) -> id
for st in reg["states"]:
    sid = st["regionId"]
    name = st.get("regionName", "")
    for pfx in CITY_PREFIXES:
        if name.startswith(pfx + " "):
            oid = {"м. Харків": "22", "м. Запоріжжя": "12"}.get(pfx)
            if oid: siren_cities[(oid, pfx)] = sid
    for d in st.get("regionChildIds", []):
        if d.get("regionType") == "District":
            siren_districts[(sid, norm(d["regionName"]))] = d["regionId"]
        for c in d.get("regionChildIds", []):
            for pfx in CITY_PREFIXES:
                if c.get("regionName", "").startswith(pfx + " "):
                    siren_cities[(sid, pfx)] = c["regionId"]

feats = {1: [], 2: [], 3: []}
code_to_oblast = {}
with gzip.open(src, "rt") as f:
    for line in f:
        line = line.strip().lstrip(",").rstrip(",")
        if not line.startswith('{"type": "Feature"'):
            continue
        o = json.loads(line)
        lv = o["properties"]["level"]
        if lv in (1, 2) or (lv == 3 and o["properties"]["name"].removesuffix(" громада") in CITY_HROMADA.values()):
            feats[lv].append(o)

def enc(geom, tol):
    g = geom.simplify(tol, preserve_topology=True)
    if g.geom_type == "Polygon":
        polys = [g]
    else:
        polys = [x for x in getattr(g, "geoms", []) if x.geom_type == "Polygon"]
    rings = []
    for p in polys:
        if p.area < (tol * 3) ** 2:
            continue
        for ring in [p.exterior]:  # без «дірок»: на такому масштабі вони не потрібні
            pts = []
            px = py = None
            out = []
            for lon, lat in ring.coords:
                x = round(lon * KX / Q); y = round(-lat / Q)
                if (x, y) == (px, py):
                    continue
                if px is None:
                    out += [x, y]
                else:
                    out += [x - px, y - py]
                px, py = x, y
            if len(out) >= 8:
                rings.append(out)
    return rings

def center(geom):
    c = geom.representative_point()
    return [round(c.x * KX / Q), round(-c.y / Q)]

out = {"q": Q, "kx": KX, "oblasts": {}, "districts": {}, "cities": {},
       "attribution": "Межі: © учасники OpenStreetMap (ODbL), ua-geo; узбережжя: Natural Earth"}

ob_geoms = {}
for o in feats[1]:
    name = o["properties"]["name"]; oid = OBLAST_IDS[name]
    g = on_land(shape(o["geometry"]).buffer(0))
    ob_geoms[oid] = unary_union([ob_geoms[oid], g]) if oid in ob_geoms else g
    code_to_oblast[o["properties"]["code"][:4]] = oid
for oid, g in ob_geoms.items():
    out["oblasts"][oid] = {"c": center(g), "r": enc(g, 0.012)}

missing = []
for o in feats[2]:
    p = o["properties"]; oid = code_to_oblast.get(p["code"][:4])
    n = norm(p["name"])
    did = siren_districts.get((oid, ALIASES.get(n, n)))
    if not did:
        # Немає в API тривог (Крим, частина Луганщини) - лише для малювання.
        missing.append((oid, p["name"])); did = "x" + p["code"][2:12]
    g = on_land(shape(o["geometry"]).buffer(0))
    out["districts"][did] = {"o": oid, "n": p["name"], "c": center(g), "r": enc(g, 0.006)}

for o in feats[3]:
    p = o["properties"]; oid = code_to_oblast.get(p["code"][:4])
    for pfx in CITY_PREFIXES:
        if p["name"].removesuffix(" громада") == CITY_HROMADA[pfx]:
            cid = siren_cities.get((oid, pfx))
            if cid and cid not in out["cities"]:
                g = on_land(shape(o["geometry"]).buffer(0))
                out["cities"][cid] = {"o": oid, "n": pfx, "c": center(g), "r": enc(g, 0.003)}

json.dump(out, open(out_path, "w"), ensure_ascii=False, separators=(",", ":"))
pts = sum(len(r) for d in list(out["oblasts"].values()) + list(out["districts"].values()) for r in d["r"]) // 2
print("oblasts", len(out["oblasts"]), "districts", len(out["districts"]), "cities", out["cities"].keys(), "points", pts)
print("unmatched raions:", missing)
