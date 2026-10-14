// Чиста логіка сервера (без мережі й бази) - її перевіряють тести.

export const SIREN_URL = 'https://siren.pp.ua/api/v3/alerts';
/** Тривоги, що тривають понад добу (окуповані території тощо), не враховуємо - як у застосунку. */
export const STALE_MS = 24 * 3600 * 1000;
export const MIN = 60 * 1000;

/** Розбирає відповідь siren.pp.ua: regionId → { level: 'red'|'yellow', since: ms|null }. */
export function parseSiren(arr) {
  const out = new Map();
  if (!Array.isArray(arr)) return out;
  for (const o of arr) {
    const alerts = Array.isArray(o?.activeAlerts) ? o.activeAlerts : [];
    for (const a of alerts) {
      if (String(a?.type || '').toUpperCase() !== 'AIR') continue;
      const rid = String(a.regionId || o.regionId || '');
      if (!rid) continue;
      const levels = Array.isArray(a.activeAlertLevels) ? a.activeAlertLevels : [];
      let red = levels.length === 0;
      let yellow = false;
      for (const l of levels) {
        if (String(l?.alertLevel || '').toLowerCase() === 'yellow') yellow = true;
        else red = true;
      }
      const level = red || !yellow ? 'red' : 'yellow';
      const t = Date.parse(a.lastUpdate || '');
      const since = Number.isFinite(t) ? t : null;
      const prev = out.get(rid);
      if (!prev) {
        out.set(rid, { level, since });
      } else {
        if (level === 'red') prev.level = 'red';
        if (since != null && (prev.since == null || since < prev.since)) prev.since = since;
      }
    }
  }
  return out;
}

/** Чи є тривога в будь-якому з регіонів, за якими стежить телефон. */
export function statusFor(alerts, watch, now) {
  let active = false;
  let red = false;
  for (const id of watch || []) {
    const a = alerts.get(String(id));
    if (!a) continue;
    if (a.since != null && now - a.since >= STALE_MS) continue;
    active = true;
    if (a.level === 'red') red = true;
  }
  return active ? { active: true, level: red ? 'red' : 'yellow' } : { active: false, level: null };
}

export function shouldWait(alarm, level) {
  return (level || 'red') === 'red' ? !!alarm.red : !!alarm.yellow;
}

// ---------- Час у часовому поясі телефона ----------

const WD = { Mon: 1, Tue: 2, Wed: 3, Thu: 4, Fri: 5, Sat: 6, Sun: 7 };
const fmtCache = new Map();

function formatter(tz) {
  let f = fmtCache.get(tz);
  if (!f) {
    f = new Intl.DateTimeFormat('en-US', {
      timeZone: tz, hourCycle: 'h23', year: 'numeric', month: '2-digit', day: '2-digit',
      hour: '2-digit', minute: '2-digit', second: '2-digit', weekday: 'short',
    });
    fmtCache.set(tz, f);
  }
  return f;
}

export function validTz(tz) {
  if (typeof tz !== 'string' || tz.length > 64) return false;
  try {
    formatter(tz);
    return true;
  } catch {
    return false;
  }
}

export function zonedParts(ms, tz) {
  const p = {};
  for (const x of formatter(tz).formatToParts(new Date(ms))) p[x.type] = x.value;
  return {
    y: +p.year, m: +p.month, d: +p.day, h: +p.hour % 24, mi: +p.minute, s: +p.second, wd: WD[p.weekday],
  };
}

function offsetMs(ms, tz) {
  const p = zonedParts(ms, tz);
  return Date.UTC(p.y, p.m - 1, p.d, p.h, p.mi, p.s) - Math.floor(ms / 1000) * 1000;
}

/** Місцеві дата й час → мілісекунди (з урахуванням переходу на літній/зимовий час). */
export function zonedToEpoch(y, m, d, h, mi, tz) {
  const guess = Date.UTC(y, m - 1, d, h, mi);
  const off = offsetMs(guess, tz);
  let t = guess - off;
  const off2 = offsetMs(t, tz);
  if (off2 !== off) t = guess - off2;
  return t;
}

/**
 * Найближчий момент після afterMs, коли має задзвонити будильник.
 * alarm = { hour, minute, days: [1..7] } (1 = понеділок; порожньо = один раз).
 */
export function nextOccurrence(alarm, afterMs, tz) {
  const p = zonedParts(afterMs, tz);
  for (let k = 0; k <= 8; k++) {
    const day = new Date(Date.UTC(p.y, p.m - 1, p.d + k));
    const wd = ((day.getUTCDay() + 6) % 7) + 1;
    const t = zonedToEpoch(day.getUTCFullYear(), day.getUTCMonth() + 1, day.getUTCDate(), alarm.hour, alarm.minute, tz);
    if (t <= afterMs) continue;
    if (!alarm.days || alarm.days.length === 0 || alarm.days.includes(wd)) return t;
  }
  return null;
}

// ---------- Перевірка даних від телефона ----------

export function cleanSync(body) {
  if (!body || typeof body !== 'object') return null;
  const token = String(body.token || '');
  if (!/^[0-9a-f]{32,200}$/i.test(token)) return null;
  const tz = validTz(body.tz) ? body.tz : 'Europe/Kyiv';
  const watch = (Array.isArray(body.watch) ? body.watch : [])
    .map(String)
    .filter((x) => /^\d{1,6}$/.test(x))
    .slice(0, 800);
  const alarms = [];
  for (const a of Array.isArray(body.alarms) ? body.alarms.slice(0, 30) : []) {
    const id = Number(a?.id);
    const hour = Number(a?.h);
    const minute = Number(a?.m);
    if (!Number.isInteger(id) || !Number.isInteger(hour) || !Number.isInteger(minute)) continue;
    if (hour < 0 || hour > 23 || minute < 0 || minute > 59) continue;
    const days = [...new Set((Array.isArray(a.days) ? a.days : []).map(Number).filter((d) => d >= 1 && d <= 7))].sort();
    const wait = Math.max(1, Math.min(24 * 60, Number(a.wait) || 120));
    const red = !!a.red;
    const yellow = !!a.yellow;
    if (!red && !yellow) continue;
    alarms.push({ id, hour, minute, days, red, yellow, wait });
  }
  return { token: token.toLowerCase(), sandbox: !!body.sandbox, tz, watch, alarms };
}
