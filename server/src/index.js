// CalmAlarm: сервер тихих сигналів для iPhone.
//
// Щохвилини дивиться карту тривог. Якщо в регіоні телефона тривога, а будильник має задзвонити
// за кілька хвилин, надсилає тихий сигнал - телефон сам перевіряє тривогу і переносить будильник.
// Поки будильник чекає, сигналить при відбої, на межі очікування і раз на 10 хв.
// Якщо сервер зламається чи сигнал не дійде, будильник на телефоні просто задзвонить.

import { SIREN_URL, MIN, parseSiren, statusFor, shouldWait, nextOccurrence, cleanSync } from './logic.js';
import { makeJwt, sendPush } from './apns.js';
import { privacyPage, supportPage } from './pages.js';

const LOOKAHEAD = 4 * MIN;
const REPEAT_EVERY = 10 * MIN;
const MAX_PUSHES = 40; // безкоштовний тариф Cloudflare: до 50 зовнішніх запитів за запуск
const JWT_TTL = 45 * MIN;

const json = (obj, status = 200) =>
  new Response(JSON.stringify(obj), { status, headers: { 'content-type': 'application/json; charset=utf-8' } });

const html = (body) => new Response(body, { headers: { 'content-type': 'text/html; charset=utf-8' } });

export async function handleSync(env, body, now = Date.now()) {
  const s = cleanSync(body);
  if (!s) return json({ ok: false, error: 'bad request' }, 400);
  const db = env.DB;
  const stmts = [db.prepare('DELETE FROM alarms WHERE token = ?').bind(s.token)];
  if (s.alarms.length === 0 || s.watch.length === 0) {
    stmts.push(db.prepare('DELETE FROM waiting WHERE token = ?').bind(s.token));
    stmts.push(db.prepare('DELETE FROM devices WHERE token = ?').bind(s.token));
  } else {
    stmts.push(
      db
        .prepare(
          `INSERT INTO devices (token, sandbox, tz, watch, updated) VALUES (?, ?, ?, ?, ?)
           ON CONFLICT(token) DO UPDATE SET sandbox = excluded.sandbox, tz = excluded.tz,
             watch = excluded.watch, updated = excluded.updated`,
        )
        .bind(s.token, s.sandbox ? 1 : 0, s.tz, JSON.stringify(s.watch), now),
    );
    for (const a of s.alarms) {
      stmts.push(
        db
          .prepare(
            `INSERT INTO alarms (token, alarm_id, hour, minute, days, red, yellow, wait_min, next_at)
             VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`,
          )
          .bind(
            s.token, a.id, a.hour, a.minute, JSON.stringify(a.days), a.red ? 1 : 0, a.yellow ? 1 : 0, a.wait,
            nextOccurrence(a, now, s.tz),
          ),
      );
    }
  }
  await db.batch(stmts);
  return json({ ok: true, alarms: s.alarms.length });
}

function rowAlarm(r) {
  let days = [];
  try {
    days = JSON.parse(r.days || '[]');
  } catch {
    days = [];
  }
  return { hour: r.hour, minute: r.minute, days, red: r.red == null ? 1 : r.red, yellow: r.yellow == null ? 1 : r.yellow };
}

function parseWatch(s) {
  try {
    const v = JSON.parse(s || '[]');
    return Array.isArray(v) ? v : [];
  } catch {
    return [];
  }
}

/** Один прохід (запускається щохвилини). Повертає короткий звіт для журналу і тестів. */
export async function runCron(env, { now = Date.now(), fetchImpl = fetch } = {}) {
  const report = { alerts: 0, due: 0, waiting: 0, pushed: 0, gone: 0, failed: 0 };
  let alerts;
  try {
    const res = await fetchImpl(SIREN_URL, { headers: { 'user-agent': 'CalmAlarm-server/1.0' } });
    if (!res.ok) throw new Error(`siren ${res.status}`);
    alerts = parseSiren(await res.json());
  } catch (e) {
    // Немає даних про тривоги - нічого не надсилаємо: будильники задзвонять як звичайні.
    report.error = String(e);
    return report;
  }
  report.alerts = alerts.size;

  const db = env.DB;
  const [dueRes, waitRes, jwtRes] = await db.batch([
    db
      .prepare(
        `SELECT a.token, a.alarm_id, a.hour, a.minute, a.days, a.red, a.yellow, a.wait_min, a.next_at,
                d.watch, d.sandbox, d.tz
         FROM alarms a JOIN devices d ON d.token = a.token
         WHERE a.next_at IS NOT NULL AND a.next_at <= ?`,
      )
      .bind(now + LOOKAHEAD),
    db.prepare(
      `SELECT w.token, w.alarm_id, w.occ, w.deadline, w.last_push, d.watch, d.sandbox, a.red, a.yellow
       FROM waiting w JOIN devices d ON d.token = w.token
       LEFT JOIN alarms a ON a.token = w.token AND a.alarm_id = w.alarm_id`,
    ),
    db.prepare("SELECT v, t FROM kv WHERE k = 'jwt'"),
  ]);
  const due = dueRes.results || [];
  const waits = waitRes.results || [];
  report.due = due.length;
  report.waiting = waits.length;

  const writes = [];
  // Кандидати на сигнал: { prio, token, sandbox, kind, onSent: [stmts] }
  const candidates = [];

  for (const r of due) {
    const alarm = rowAlarm(r);
    const tz = r.tz || 'Europe/Kyiv';
    const advance = (after) =>
      db
        .prepare('UPDATE alarms SET next_at = ? WHERE token = ? AND alarm_id = ?')
        .bind(alarm.days.length ? nextOccurrence(alarm, after, tz) : null, r.token, r.alarm_id);
    if (r.next_at < now - MIN) {
      // Пропущений запуск - просто рахуємо наступний раз.
      writes.push(advance(now));
      continue;
    }
    const st = statusFor(alerts, parseWatch(r.watch), now);
    if (st.active && shouldWait(alarm, st.level)) {
      candidates.push({
        prio: 2,
        token: r.token,
        sandbox: !!r.sandbox,
        kind: 'pre',
        onSent: [
          db
            .prepare(
              `INSERT INTO waiting (token, alarm_id, occ, deadline, last_push) VALUES (?, ?, ?, ?, ?)
               ON CONFLICT(token, alarm_id) DO UPDATE SET occ = excluded.occ, deadline = excluded.deadline,
                 last_push = excluded.last_push`,
            )
            .bind(r.token, r.alarm_id, r.next_at, r.next_at + r.wait_min * MIN, now),
          advance(r.next_at + MIN),
        ],
      });
    } else if (r.next_at <= now) {
      writes.push(advance(r.next_at + MIN));
    }
  }

  for (const w of waits) {
    const del = db.prepare('DELETE FROM waiting WHERE token = ? AND alarm_id = ?').bind(w.token, w.alarm_id);
    const st = statusFor(alerts, parseWatch(w.watch), now);
    const relevant = st.active && shouldWait(rowAlarm(w), st.level);
    if (!relevant) {
      candidates.push({ prio: 0, token: w.token, sandbox: !!w.sandbox, kind: 'clear', onSent: [del] });
    } else if (now >= w.deadline) {
      candidates.push({ prio: 1, token: w.token, sandbox: !!w.sandbox, kind: 'deadline', onSent: [del] });
    } else if (now >= w.occ && now - w.last_push >= REPEAT_EVERY) {
      candidates.push({
        prio: 3,
        token: w.token,
        sandbox: !!w.sandbox,
        kind: 'still',
        onSent: [
          db.prepare('UPDATE waiting SET last_push = ? WHERE token = ? AND alarm_id = ?').bind(now, w.token, w.alarm_id),
        ],
      });
    }
  }

  if (candidates.length > 0) {
    if (!env.APNS_KEY || !env.APNS_KEY_ID || !env.APNS_TEAM_ID) {
      report.error = 'APNs key is not configured';
    } else {
      // Токен постачальника APNs зберігаємо в базі: Apple сердиться, якщо міняти його надто часто.
      const row = (jwtRes.results || [])[0];
      let jwt = row && now - row.t < JWT_TTL ? row.v : null;
      if (!jwt) {
        jwt = await makeJwt(env.APNS_KEY, env.APNS_KEY_ID, env.APNS_TEAM_ID, Math.floor(now / 1000));
        writes.push(
          db
            .prepare("INSERT INTO kv (k, v, t) VALUES ('jwt', ?, ?) ON CONFLICT(k) DO UPDATE SET v = excluded.v, t = excluded.t")
            .bind(jwt, now),
        );
      }
      candidates.sort((a, b) => a.prio - b.prio);
      // Один сигнал на телефон за прохід, навіть якщо будильників кілька.
      const byToken = new Map();
      for (const c of candidates) {
        const g = byToken.get(c.token);
        if (g) g.onSent.push(...c.onSent);
        else byToken.set(c.token, { ...c, onSent: [...c.onSent] });
      }
      let sent = 0;
      const topic = env.APNS_TOPIC || 'ua.calmalarm.app';
      const groups = [...byToken.values()].slice(0, MAX_PUSHES);
      const results = await Promise.all(
        groups.map((g) =>
          sendPush({ fetchImpl, jwt, topic, token: g.token, sandbox: g.sandbox, kind: g.kind, nowSec: Math.floor(now / 1000) }),
        ),
      );
      groups.forEach((g, i) => {
        const r = results[i];
        if (r === 'ok') {
          sent++;
          writes.push(...g.onSent);
        } else if (r === 'gone') {
          report.gone++;
          writes.push(db.prepare('DELETE FROM alarms WHERE token = ?').bind(g.token));
          writes.push(db.prepare('DELETE FROM waiting WHERE token = ?').bind(g.token));
          writes.push(db.prepare('DELETE FROM devices WHERE token = ?').bind(g.token));
        } else {
          report.failed++;
        }
      });
      report.pushed = sent;
    }
  }

  if (writes.length > 0) await db.batch(writes);
  return report;
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (request.method === 'POST' && url.pathname === '/v1/sync') {
      let body;
      try {
        body = await request.json();
      } catch {
        return json({ ok: false, error: 'bad json' }, 400);
      }
      return handleSync(env, body);
    }
    if (request.method === 'GET') {
      if (url.pathname === '/privacy') return html(privacyPage(env));
      if (url.pathname === '/support') return html(supportPage(env));
      if (url.pathname === '/' || url.pathname === '/health') {
        return json({ ok: true, service: 'CalmAlarm', time: new Date().toISOString() });
      }
    }
    return json({ ok: false, error: 'not found' }, 404);
  },

  async scheduled(event, env, ctx) {
    ctx.waitUntil(
      runCron(env, { now: event.scheduledTime || Date.now() }).then((r) => {
        if (r.pushed || r.error || r.failed) console.log(JSON.stringify(r));
      }),
    );
  },
};
