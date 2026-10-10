"""Build a calibrated model: P(alert starts in the unit within 30 min | no alert now, features).

Data: official air-raid dataset (raion level since Dec 2025).
Units: every raion, Kyiv city, a few city hromadas with separate alerts, and "whole oblast" aggregates.
"""
import json
import sys
import numpy as np
import pandas as pd
from scipy import sparse
from sklearn.linear_model import LogisticRegression

CSV = sys.argv[1]
OUT = sys.argv[2]
TG_FILES = sys.argv[3:]
REGIONS_KT = "/home/claude/calmalarm/app/src/main/java/ua/calmalarm/app/Regions.kt"

START = pd.Timestamp("2026-02-16" if TG_FILES else "2025-12-15", tz="UTC")
END = pd.Timestamp("2026-09-06 23:55", tz="UTC")
SPLIT = pd.Timestamp("2026-07-20", tz="UTC")  # validation from this day
BIN = pd.Timedelta(minutes=5)
H = 6  # 30 min horizon in bins
RECENT = 12  # 60 min

NEIGH = {
    "Вінницька область": ["Житомирська область", "Київська область", "Черкаська область", "Кіровоградська область", "Одеська область", "Хмельницька область", "Чернівецька область"],
    "Волинська область": ["Рівненська область", "Львівська область"],
    "Дніпропетровська область": ["Харківська область", "Полтавська область", "Кіровоградська область", "Миколаївська область", "Херсонська область", "Запорізька область", "Донецька область"],
    "Донецька область": ["Луганська область", "Харківська область", "Дніпропетровська область", "Запорізька область"],
    "Житомирська область": ["Рівненська область", "Хмельницька область", "Вінницька область", "Київська область"],
    "Закарпатська область": ["Львівська область", "Івано-Франківська область"],
    "Запорізька область": ["Дніпропетровська область", "Донецька область", "Херсонська область"],
    "Івано-Франківська область": ["Львівська область", "Закарпатська область", "Чернівецька область", "Тернопільська область"],
    "Київська область": ["Житомирська область", "Вінницька область", "Черкаська область", "Полтавська область", "Чернігівська область", "м. Київ"],
    "Кіровоградська область": ["Вінницька область", "Черкаська область", "Полтавська область", "Дніпропетровська область", "Миколаївська область", "Одеська область"],
    "Львівська область": ["Волинська область", "Рівненська область", "Тернопільська область", "Івано-Франківська область", "Закарпатська область"],
    "Миколаївська область": ["Одеська область", "Кіровоградська область", "Дніпропетровська область", "Херсонська область"],
    "Одеська область": ["Миколаївська область", "Кіровоградська область", "Вінницька область"],
    "Полтавська область": ["Київська область", "Чернігівська область", "Сумська область", "Харківська область", "Дніпропетровська область", "Кіровоградська область", "Черкаська область"],
    "Рівненська область": ["Волинська область", "Львівська область", "Тернопільська область", "Хмельницька область", "Житомирська область"],
    "Сумська область": ["Чернігівська область", "Полтавська область", "Харківська область"],
    "Тернопільська область": ["Рівненська область", "Хмельницька область", "Чернівецька область", "Івано-Франківська область", "Львівська область"],
    "Харківська область": ["Сумська область", "Полтавська область", "Дніпропетровська область", "Донецька область", "Луганська область"],
    "Херсонська область": ["Миколаївська область", "Дніпропетровська область", "Запорізька область"],
    "Хмельницька область": ["Рівненська область", "Житомирська область", "Вінницька область", "Чернівецька область", "Тернопільська область"],
    "Черкаська область": ["Київська область", "Полтавська область", "Кіровоградська область", "Вінницька область"],
    "Чернівецька область": ["Івано-Франківська область", "Тернопільська область", "Хмельницька область", "Вінницька область"],
    "Чернігівська область": ["Київська область", "Полтавська область", "Сумська область"],
    "м. Київ": ["Київська область"],
}
CITIES = {  # hromada name -> short label
    "м. Харків та Харківська територіальна громада": "м. Харків",
    "м. Запоріжжя та Запорізька територіальна громада": "м. Запоріжжя",
    "м. Нікополь та Нікопольська територіальна громада": "м. Нікополь",
    "м. Марганець та Марганецька територіальна громада": "м. Марганець",
    "м. Краматорськ та Краматорська територіальна громада": "м. Краматорськ",
}

df = pd.read_csv(CSV)
df["s"] = pd.to_datetime(df.started_at, utc=True, format="mixed")
df["f"] = pd.to_datetime(df.finished_at, utc=True, format="mixed", errors="coerce")
df = df[(df.f > START - pd.Timedelta(days=2)) & (df.s <= END)].copy()
df["raion"] = df.raion.fillna("")
df["hromada"] = df.hromada.fillna("")
# Hromada rows that actually name a raion behave like raion alerts
mask = (df.level == "hromada") & (df.hromada == df.raion) & (df.raion != "")
df.loc[mask, "level"] = "raion"

n_bins = int((END - START) / BIN) + 1
times = START + BIN * np.arange(n_bins)


def to_bins(s, f):
    a = np.ceil((s - START) / BIN).astype(int)
    b = np.ceil((f - START) / BIN).astype(int)
    return np.clip(a, 0, n_bins), np.clip(b, 0, n_bins)


raions = sorted({(o, r) for o, r in df[df.level == "raion"][["oblast", "raion"]].itertuples(index=False)})
raions = [x for x in raions if x[0] in NEIGH]
oblasts = sorted({o for o, _ in raions} | {"м. Київ"})
r_index = {x: i for i, x in enumerate(raions)}
by_obl = {o: [r_index[x] for x in raions if x[0] == o] for o in oblasts}

# raion activity (incl. oblast-level alerts)
RA = np.zeros((len(raions), n_bins), dtype=bool)
for row in df[df.level == "raion"].itertuples():
    k = (row.oblast, row.raion)
    if k in r_index:
        a, b = to_bins(row.s, row.f)
        RA[r_index[k], a:b] = True
OBL = {o: np.zeros(n_bins, dtype=bool) for o in oblasts}
for row in df[df.level == "oblast"].itertuples():
    if row.oblast in OBL:
        a, b = to_bins(row.s, row.f)
        OBL[row.oblast][a:b] = True
for o, idx in by_obl.items():
    for i in idx:
        RA[i] |= OBL[o]
HROM = {}
for name in CITIES:
    v = np.zeros(n_bins, dtype=bool)
    for row in df[(df.level == "hromada") & (df.hromada == name)].itertuples():
        a, b = to_bins(row.s, row.f)
        v[a:b] = True
    HROM[name] = v
city_raion = {}
for name in CITIES:
    rr = df[df.hromada == name][["oblast", "raion"]].drop_duplicates()
    if len(rr):
        city_raion[name] = (rr.iloc[0].oblast, rr.iloc[0].raion)

# oblast "any raion active"
OANY = {o: (RA[idx].any(axis=0) if idx else OBL[o]) for o, idx in by_obl.items()}
OANY["м. Київ"] = OBL["м. Київ"]
total = RA.sum(axis=0) + OBL["м. Київ"].astype(int)
KY = OBL["м. Київ"]
ST = RA & ~np.concatenate([RA[:, :1], RA[:, :-1]], axis=1)
KST = KY & ~np.concatenate([KY[:1], KY[:-1]])


def recent_sum(M, w):
    c = np.cumsum(M, axis=-1)
    sh = np.concatenate([np.zeros(M.shape[:-1] + (w,)), c[..., :-w]], axis=-1)
    return c - sh


ST20 = recent_sum(ST.astype(np.int32), 4)  # raion started within last 20 min
KST20 = recent_sum(KST.astype(np.int32), 4)
wave = recent_sum(ST.sum(axis=0).astype(np.int32) + KST.astype(np.int32), 6)  # starts in last 30 min nationwide
obl_new = {o: (ST20[idx].sum(axis=0) if idx else KST20) for o, idx in by_obl.items()}
obl_new["м. Київ"] = KST20
nb_new = {o: np.sum([obl_new[n] for n in NEIGH[o] if n in obl_new], axis=0) for o in oblasts}
nb_count = {o: np.sum([OANY[n] for n in NEIGH[o] if n in OANY], axis=0) for o in oblasts}

# ---- units ----
units = []  # (key, oblast, active_vec, partial_vec)
for (o, r), i in r_index.items():
    others = [j for j in by_obl[o] if j != i]
    partial = RA[others].any(axis=0) if others else np.zeros(n_bins, bool)
    partial = partial | np.any([HROM[c] for c in CITIES if city_raion.get(c, (None,))[0] == o and city_raion[c][1] != r] or [np.zeros(n_bins, bool)], axis=0)
    pc = RA[others].sum(axis=0) if others else np.zeros(n_bins, int)
    on = ST20[others].sum(axis=0) if others else np.zeros(n_bins, int)
    units.append((f"{o}|{r}", o, RA[i], partial, pc, on))
for c, (o, r) in city_raion.items():
    act = HROM[c] | RA[r_index[(o, r)]]
    others = [j for j in by_obl[o] if j != r_index[(o, r)]]
    partial = RA[others].any(axis=0)
    units.append((f"{o}|{CITIES[c]}", o, act, partial, RA[others].sum(axis=0), ST20[others].sum(axis=0)))
for o in oblasts:
    if o == "м. Київ":
        units.append(("м. Київ|", o, OBL[o], np.zeros(n_bins, bool), np.zeros(n_bins, int), np.zeros(n_bins, int)))
    elif by_obl[o]:
        units.append((f"{o}|*", o, OANY[o], np.zeros(n_bins, bool), np.zeros(n_bins, int), np.zeros(n_bins, int)))

kyiv_hour = np.array([t.tz_convert("Europe/Kyiv").hour for t in times])

ZERO = np.zeros(n_bins, np.int32)
FALSE = np.zeros(n_bins, bool)
TG = {}
TG_GLOB = FALSE
if TG_FILES:
    sys.path.insert(0, __file__.rsplit("/", 1)[0])
    import tg_feats
    pats = tg_feats.parse_regions_kt(REGIONS_KT)
    posts = tg_feats.load_posts(TG_FILES)
    print("tg posts", len(posts), posts[0][0], posts[-1][0])
    uspec = []
    for key, o, *_ in units:
        place = key.split("|", 1)[1]
        uspec.append((key, o, None if place in ("", "*") else place))
    TG, TG_GLOB = tg_feats.compute(posts, pats, uspec, times, BIN)
    print("tg computed")
train_bins = np.where(times < SPLIT)[0]
valid_bins = np.where(times >= SPLIT)[0]
STEP = 3  # sample every 15 min


def total_bucket(t):
    return np.digitize(t, [1, 6, 16, 31, 61])  # 0..5


def rows_for(bins):
    feats, ys, uids = [], [], []
    bins = bins[(bins >= RECENT) & (bins < n_bins - H)][::STEP]
    for ui, (key, o, act, partial, pc, on) in enumerate(units):
        tcnt, tdir, tdist, tclr = TG.get(key, (ZERO, FALSE, FALSE, FALSE))
        a = act
        cur = a[bins]
        keep = ~cur
        b = bins[keep]
        if len(b) == 0:
            continue
        fut = np.zeros(len(b), bool)
        for k in range(1, H + 1):
            fut |= a[b + k]
        rec = np.zeros(len(b), bool)
        for k in range(1, RECENT + 1):
            rec |= a[b - k]
        X = np.column_stack([
            np.full(len(b), ui),
            kyiv_hour[b],
            partial[b],
            np.minimum(nb_count[o][b], 3),
            total_bucket(total[b]),
            rec,
            np.minimum(pc[b], 3),
            on[b] > 0,
            np.digitize(nb_new[o][b], [1, 3]),
            np.digitize(wave[b], [1, 4, 11]),
            b,
            np.minimum(tcnt[b] + tclr[b], 2),
            tdir[b],
            tdist[b],
            TG_GLOB[b],
            tclr[b],
        ])
        feats.append(X)
        ys.append(fut)
    return np.vstack(feats), np.concatenate(ys)


def design(F):
    n = len(F)
    nu = len(units)
    cols = []
    cols.append(sparse.csr_matrix((np.ones(n), (np.arange(n), F[:, 0])), shape=(n, nu)))
    cols.append(sparse.csr_matrix((np.ones(n), (np.arange(n), F[:, 1])), shape=(n, 24)))
    cols.append(sparse.csr_matrix(F[:, 2:3].astype(float)))
    nb = F[:, 3]
    cols.append(sparse.csr_matrix(np.column_stack([nb == 1, nb == 2, nb >= 3]).astype(float)))
    tb = F[:, 4]
    cols.append(sparse.csr_matrix(np.column_stack([tb == k for k in range(1, 6)]).astype(float)))
    cols.append(sparse.csr_matrix(F[:, 5:6].astype(float)))
    pc = F[:, 6]
    cols.append(sparse.csr_matrix(np.column_stack([pc == 2, pc >= 3]).astype(float)))
    cols.append(sparse.csr_matrix(F[:, 7:8].astype(float)))
    nn = F[:, 8]
    cols.append(sparse.csr_matrix(np.column_stack([nn == 1, nn == 2]).astype(float)))
    wv = F[:, 9]
    cols.append(sparse.csr_matrix(np.column_stack([wv == 1, wv == 2, wv == 3]).astype(float)))
    tc = F[:, 11]
    cols.append(sparse.csr_matrix(np.column_stack([tc == 1, tc == 2, F[:, 12]]).astype(float)))
    return sparse.hstack(cols).tocsr()


Ftr, ytr = rows_for(train_bins)
Fva, yva = rows_for(valid_bins)
print("train rows", len(ytr), "pos rate", ytr.mean(), "| valid rows", len(yva), "pos", yva.mean())
Xtr, Xva = design(Ftr), design(Fva)
clf = LogisticRegression(C=1.0, max_iter=400, fit_intercept=True, solver="lbfgs")
age_days = (SPLIT - times[Ftr[:, 10]]) / pd.Timedelta(days=1)
wtr = 0.5 ** (np.asarray(age_days, dtype=float) / 90.0)
clf.fit(Xtr, ytr, sample_weight=wtr)
p_va = clf.predict_proba(Xva)[:, 1]

# baseline: per-unit historical rate only
base_rate = {}
for ui in range(len(units)):
    m = Ftr[:, 0] == ui
    base_rate[ui] = ytr[m].mean() if m.any() else ytr.mean()
p_base = np.array([base_rate[u] for u in Fva[:, 0]])


def brier(p, y):
    return float(np.mean((p - y) ** 2))


def logloss(p, y):
    p = np.clip(p, 1e-6, 1 - 1e-6)
    return float(-np.mean(y * np.log(p) + (1 - y) * np.log(1 - p)))


print("valid brier model", brier(p_va, yva), "base", brier(p_base, yva), "climatology", brier(np.full(len(yva), ytr.mean()), yva))
print("valid logloss model", logloss(p_va, yva), "base", logloss(p_base, yva))
from sklearn.metrics import roc_auc_score
print("AUC model", roc_auc_score(yva, p_va), "base", roc_auc_score(yva, p_base))
# calibration table
edges = [0, .02, .05, .1, .2, .3, .4, .5, .6, .8, 1.01]
idx = np.digitize(p_va, edges) - 1
print("calibration (predicted vs observed, n):")
for k in range(len(edges) - 1):
    m = idx == k
    if m.sum():
        print(f"  {edges[k]:.2f}-{edges[k+1]:.2f}: pred {p_va[m].mean():.3f} obs {yva[m].mean():.3f} n={m.sum()}")

# ---- final model on all data (recency-weighted to the end of data) ----
Fall = np.vstack([Ftr, Fva]); yall = np.concatenate([ytr, yva])
age_all = (END - times[Fall[:, 10]]) / pd.Timedelta(days=1)
wall = 0.5 ** (np.asarray(age_all, dtype=float) / 90.0)
final = LogisticRegression(C=1.0, max_iter=500, fit_intercept=True, solver="lbfgs")
final.fit(design(Fall), yall, sample_weight=wall)
VALID_CLF = clf
clf = final
coef = clf.coef_[0]
nu = len(units)
o = 0
unit_c = coef[o:o + nu]; o += nu
hour_c = coef[o:o + 24]; o += 24
partial_c = coef[o]; o += 1
nb_c = coef[o:o + 3]; o += 3
tot_c = coef[o:o + 5]; o += 5
rec_c = coef[o]; o += 1
pc_c = coef[o:o + 2]; o += 2
on_c = coef[o]; o += 1
nn_c = coef[o:o + 2]; o += 2
wv_c = coef[o:o + 3]; o += 3
tg_c = coef[o:o + 3]; o += 3
model = {
    "version": 1,
    "trained": f"{START.date()}..{END.date()}",
    "validated_on": f"{SPLIT.date()}..{END.date()} (model trained before {SPLIT.date()})",
    "intercept": float(clf.intercept_[0]),
    "units": {units[i][0]: round(float(unit_c[i]), 4) for i in range(nu)},
    "hour": [round(float(x), 4) for x in hour_c],
    "partial": round(float(partial_c), 4),
    "neighbors": [0.0] + [round(float(x), 4) for x in nb_c],
    "total_edges": [1, 6, 16, 31, 61],
    "total": [0.0] + [round(float(x), 4) for x in tot_c],
    "recent_end": round(float(rec_c), 4),
    "partial_count": [0.0, 0.0] + [round(float(x), 4) for x in pc_c],
    "own_new": round(float(on_c), 4),
    "nb_new_edges": [1, 3],
    "nb_new": [0.0] + [round(float(x), 4) for x in nn_c],
    "wave_edges": [1, 4, 11],
    "wave": [0.0] + [round(float(x), 4) for x in wv_c],
    "tg": {
        "obl": [0.0, round(float(tg_c[0]), 4), round(float(tg_c[1]), 4)],
        "dir": round(float(tg_c[2]), 4),
        "dist": 0.0,
        "global": 0.0,
        "clear": 0.0,
    },
}
recent_mask = np.asarray((END - times[Fall[:, 10]]) / pd.Timedelta(days=1), dtype=float) <= 90
usual = {}
for ui in range(nu):
    mm = recent_mask & (Fall[:, 0] == ui)
    if mm.sum() > 100:
        usual[units[ui][0]] = round(float(yall[mm].mean()), 4)
model["usual"] = usual
json.dump(model, open(OUT, "w"), ensure_ascii=False, indent=1)
print("usual Kyiv", usual.get("м. Київ|"), "Brovary", usual.get("Київська область|Броварський район"), "Lviv", usual.get("Львівська область|Львівський район"))
print("tg", tg_c)
print("pc", pc_c, "own_new", on_c, "nb_new", nn_c, "wave", wv_c)
print("partial", partial_c, "nb", nb_c, "total", tot_c, "recent", rec_c)
print("hour", np.round(hour_c, 2))
# a few unit base probabilities at 3am and 3pm with nothing going on
from math import exp
sig = lambda z: 1 / (1 + exp(-z))
for key in ["м. Київ|", "Київська область|Броварський район", "Львівська область|Львівський район", "Харківська область|м. Харків", "Харківська область|Харківський район", "Закарпатська область|Ужгородський район", "Одеська область|Одеський район", "Дніпропетровська область|Дніпровський район"]:
    if key in model["units"]:
        z = model["intercept"] + model["units"][key]
        print(key, "quiet 03h", round(sig(z + model["hour"][3]) * 100, 1), "% quiet 15h", round(sig(z + model["hour"][15]) * 100, 1), "%")
print("units", nu)
