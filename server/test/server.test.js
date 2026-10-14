import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { DatabaseSync } from 'node:sqlite';
import { parseSiren, statusFor, nextOccurrence, zonedToEpoch, cleanSync, MIN } from '../src/logic.js';
import { makeJwt } from '../src/apns.js';
import worker, { runCron, handleSync } from '../src/index.js';

const here = new URL('.', import.meta.url);
const fixture = JSON.parse(readFileSync(new URL('fixtures/alerts.json', here), 'utf8'));
const schema = readFileSync(new URL('../schema.sql', here), 'utf8');

// ---------- Імітація Cloudflare D1 поверх SQLite ----------

class FakeD1 {
  constructor() {
    this.db = new DatabaseSync(':memory:');
    this.db.exec(schema);
  }
  prepare(sql) {
    const stmt = { sql, args: [], bind: (...a) => ({ ...stmt, args: a }) };
    return stmt;
  }
  async batch(stmts) {
    return stmts.map((s) => {
      const st = this.db.prepare(s.sql);
      if (/^\s*select/i.test(s.sql)) return { results: st.all(...s.args) };
      st.run(...s.args);
      return { results: [] };
    });
  }
  all(sql, ...a) {
    return this.db.prepare(sql).all(...a);
  }
}

async function makeKeyPem() {
  const kp = await crypto.subtle.generateKey({ name: 'ECDSA', namedCurve: 'P-256' }, true, ['sign', 'verify']);
  const der = new Uint8Array(await crypto.subtle.exportKey('pkcs8', kp.privateKey));
  const b64 = Buffer.from(der).toString('base64').replace(/(.{64})/g, '$1\n');
  return { pem: `-----BEGIN PRIVATE KEY-----\n${b64}\n-----END PRIVATE KEY-----\n`, publicKey: kp.publicKey };
}

const kyiv = (y, m, d, h, mi) => zonedToEpoch(y, m, d, h, mi, 'Europe/Kyiv');
const TOKEN = 'ab'.repeat(32);

function sirenWith(entries) {
  // entries: [{ id, level, since }]
  return entries.map((e) => ({
    regionId: e.id,
    regionType: 'State',
    regionName: 'x',
    activeAlerts: [
      {
        regionId: e.id,
        regionType: 'State',
        type: 'AIR',
        lastUpdate: new Date(e.since).toISOString(),
        activeAlertLevels: [{ alertLevel: e.level === 'yellow' ? 'Yellow' : 'Red' }],
      },
    ],
  }));
}

function fakeFetch(state) {
  return async (url, init) => {
    if (String(url).includes('siren.pp.ua')) {
      if (state.sirenDown) return new Response('down', { status: 503 });
      return new Response(JSON.stringify(state.siren), { status: 200 });
    }
    state.pushes.push({ url: String(url), headers: init.headers, body: JSON.parse(init.body) });
    const status = state.apnsStatus || 200;
    return new Response(status === 200 ? '' : JSON.stringify({ reason: 'Unregistered' }), { status });
  };
}

async function setup({ days = [1, 2, 3, 4, 5], red = true, yellow = true, wait = 120, now }) {
  const { pem } = await makeKeyPem();
  const env = { DB: new FakeD1(), APNS_KEY: pem, APNS_KEY_ID: 'KEY123', APNS_TEAM_ID: 'TEAM123', APNS_TOPIC: 'ua.calmalarm.app' };
  const res = await handleSync(
    env,
    { token: TOKEN, sandbox: false, tz: 'Europe/Kyiv', watch: ['14', '81'], alarms: [{ id: 1, h: 7, m: 0, days, red, yellow, wait }] },
    now,
  );
  assert.equal(res.status, 200);
  return env;
}

// ---------- Логіка ----------

test('parseSiren: рівні, вкладені тривоги і застарілі записи', () => {
  const m = parseSiren(fixture);
  assert.equal(m.get('16').level, 'red');
  assert.equal(m.get('124').level, 'yellow'); // тривога району всередині запису громади
  assert.ok(Number.isFinite(m.get('124').since)); // мікросекунди розбираються
  const now = Date.parse('2026-10-10T16:53:15Z');
  assert.equal(statusFor(m, ['16'], now).active, false); // Луганщина з 2022 - застаріла
  assert.deepEqual(statusFor(m, ['124'], now), { active: true, level: 'yellow' });
  assert.equal(statusFor(m, ['99999'], now).active, false);
});

test('nextOccurrence: будні, один раз, перехід на зимовий час', () => {
  // П'ятниця 16.10.2026 08:00 Київ → наступний будній день - понеділок 19.10 07:00
  const fri = kyiv(2026, 10, 16, 8, 0);
  assert.equal(nextOccurrence({ hour: 7, minute: 0, days: [1, 2, 3, 4, 5] }, fri, 'Europe/Kyiv'), kyiv(2026, 10, 19, 7, 0));
  // Один раз: до 07:00 - сьогодні, після - завтра
  assert.equal(nextOccurrence({ hour: 7, minute: 0, days: [] }, kyiv(2026, 10, 16, 6, 0), 'Europe/Kyiv'), kyiv(2026, 10, 16, 7, 0));
  assert.equal(nextOccurrence({ hour: 7, minute: 0, days: [] }, fri, 'Europe/Kyiv'), kyiv(2026, 10, 17, 7, 0));
  // Берлін: 25.10.2026 закінчується літній час
  const sat = Date.parse('2026-10-24T05:00:00Z'); // 07:00 у Берліні (UTC+2)
  assert.equal(nextOccurrence({ hour: 7, minute: 0, days: [] }, sat, 'Europe/Berlin'), Date.parse('2026-10-25T06:00:00Z'));
});

test('cleanSync відкидає погані дані', () => {
  assert.equal(cleanSync({ token: 'zz' }), null);
  const s = cleanSync({ token: TOKEN, tz: 'Mars/Base', watch: ['14', 'x', 5], alarms: [{ id: 1, h: 25, m: 0 }, { id: 2, h: 7, m: 30, red: true, days: [1, 9, 1] }] });
  assert.equal(s.tz, 'Europe/Kyiv');
  assert.deepEqual(s.watch, ['14', '5']);
  assert.equal(s.alarms.length, 1);
  assert.deepEqual(s.alarms[0].days, [1]);
});

test('JWT для APNs підписується правильно', async () => {
  const { pem, publicKey } = await makeKeyPem();
  const jwt = await makeJwt(pem, 'KEY123', 'TEAM123', 1760000000);
  const [h, c, sig] = jwt.split('.');
  const dec = (s) => JSON.parse(Buffer.from(s.replace(/-/g, '+').replace(/_/g, '/'), 'base64').toString());
  assert.deepEqual(dec(h), { alg: 'ES256', kid: 'KEY123' });
  assert.deepEqual(dec(c), { iss: 'TEAM123', iat: 1760000000 });
  const raw = Buffer.from(sig.replace(/-/g, '+').replace(/_/g, '/'), 'base64');
  assert.equal(raw.length, 64);
  const ok = await crypto.subtle.verify({ name: 'ECDSA', hash: 'SHA-256' }, publicKey, raw, new TextEncoder().encode(`${h}.${c}`));
  assert.ok(ok);
});

// ---------- Повний сценарій ----------

test('тривога перед будильником: сигнал, повтори, відбій', async () => {
  const t0 = kyiv(2026, 10, 19, 6, 57); // понеділок
  const env = await setup({ now: t0 - 3600 * 1000 });
  const state = { siren: sirenWith([{ id: '14', level: 'red', since: kyiv(2026, 10, 19, 6, 30) }]), pushes: [] };
  const f = fakeFetch(state);

  let r = await runCron(env, { now: t0, fetchImpl: f });
  assert.equal(r.pushed, 1);
  const p = state.pushes[0];
  assert.equal(p.url, `https://api.push.apple.com/3/device/${TOKEN}`);
  assert.equal(p.headers['apns-push-type'], 'background');
  assert.equal(p.headers['apns-priority'], '5');
  assert.equal(p.headers['apns-topic'], 'ua.calmalarm.app');
  assert.match(p.headers.authorization, /^bearer [\w-]+\.[\w-]+\.[\w-]+$/);
  assert.equal(p.body.aps['content-available'], 1);
  assert.equal(p.body.t, 'pre');
  const w = env.DB.all('SELECT * FROM waiting');
  assert.equal(w.length, 1);
  assert.equal(w[0].occ, kyiv(2026, 10, 19, 7, 0));
  assert.equal(w[0].deadline, kyiv(2026, 10, 19, 9, 0));
  assert.equal(env.DB.all('SELECT next_at FROM alarms')[0].next_at, kyiv(2026, 10, 20, 7, 0));
  const jwt1 = env.DB.all("SELECT v FROM kv WHERE k = 'jwt'")[0].v;

  // Хвилину потому - ще до часу будильника: тиша
  r = await runCron(env, { now: t0 + MIN, fetchImpl: f });
  assert.equal(r.pushed, 0);
  // 07:10 - тривога триває, минуло 13 хв від сигналу: повтор
  r = await runCron(env, { now: kyiv(2026, 10, 19, 7, 10), fetchImpl: f });
  assert.equal(r.pushed, 1);
  assert.equal(state.pushes.at(-1).body.t, 'still');
  assert.equal(state.pushes.at(-1).headers.authorization, `bearer ${jwt1}`); // токен не міняється щохвилини
  // 07:15 - рано для повтору
  r = await runCron(env, { now: kyiv(2026, 10, 19, 7, 15), fetchImpl: f });
  assert.equal(r.pushed, 0);
  // 07:20 - відбій
  state.siren = [];
  r = await runCron(env, { now: kyiv(2026, 10, 19, 7, 20), fetchImpl: f });
  assert.equal(r.pushed, 1);
  assert.equal(state.pushes.at(-1).body.t, 'clear');
  assert.equal(env.DB.all('SELECT * FROM waiting').length, 0);
});

test('тривога довша за ліміт: сигнал на межі очікування', async () => {
  const env = await setup({ wait: 60, now: kyiv(2026, 10, 19, 5, 0) });
  const state = { siren: sirenWith([{ id: '81', level: 'red', since: kyiv(2026, 10, 19, 6, 0) }]), pushes: [] };
  const f = fakeFetch(state);
  await runCron(env, { now: kyiv(2026, 10, 19, 6, 58), fetchImpl: f });
  await runCron(env, { now: kyiv(2026, 10, 19, 8, 0), fetchImpl: f });
  assert.equal(state.pushes.at(-1).body.t, 'deadline');
  assert.equal(env.DB.all('SELECT * FROM waiting').length, 0);
});

test('без тривоги або не той рівень - сигналу немає, розклад іде далі', async () => {
  const env = await setup({ yellow: false, now: kyiv(2026, 10, 19, 5, 0) });
  const state = { siren: sirenWith([{ id: '14', level: 'yellow', since: kyiv(2026, 10, 19, 6, 30) }]), pushes: [] };
  const f = fakeFetch(state);
  let r = await runCron(env, { now: kyiv(2026, 10, 19, 6, 57), fetchImpl: f });
  assert.equal(r.pushed, 0);
  r = await runCron(env, { now: kyiv(2026, 10, 19, 7, 0), fetchImpl: f });
  assert.equal(r.pushed, 0);
  assert.equal(env.DB.all('SELECT next_at FROM alarms')[0].next_at, kyiv(2026, 10, 20, 7, 0));
});

test('карта тривог недоступна - нічого не надсилаємо', async () => {
  const env = await setup({ now: kyiv(2026, 10, 19, 5, 0) });
  const state = { sirenDown: true, pushes: [] };
  const r = await runCron(env, { now: kyiv(2026, 10, 19, 6, 57), fetchImpl: fakeFetch(state) });
  assert.equal(r.pushed, 0);
  assert.ok(r.error);
});

test('видалений застосунок: токен прибирається', async () => {
  const env = await setup({ now: kyiv(2026, 10, 19, 5, 0) });
  const state = { siren: sirenWith([{ id: '14', level: 'red', since: kyiv(2026, 10, 19, 6, 30) }]), pushes: [], apnsStatus: 410 };
  const r = await runCron(env, { now: kyiv(2026, 10, 19, 6, 57), fetchImpl: fakeFetch(state) });
  assert.equal(r.gone, 1);
  assert.equal(env.DB.all('SELECT * FROM devices').length, 0);
  assert.equal(env.DB.all('SELECT * FROM alarms').length, 0);
});

test('пропущені запуски: наступний час перераховується', async () => {
  const env = await setup({ now: kyiv(2026, 10, 12, 5, 0) });
  const state = { siren: [], pushes: [] };
  await runCron(env, { now: kyiv(2026, 10, 19, 12, 0), fetchImpl: fakeFetch(state) });
  assert.equal(env.DB.all('SELECT next_at FROM alarms')[0].next_at, kyiv(2026, 10, 20, 7, 0));
});

test('HTTP: синхронізація, сторінки, помилки', async () => {
  const env = { DB: new FakeD1(), SUPPORT_EMAIL: 'help@example.com' };
  const req = (path, body) =>
    new Request(`https://calmalarm.example.workers.dev${path}`, body ? { method: 'POST', body } : undefined);
  let res = await worker.fetch(req('/v1/sync', JSON.stringify({ token: TOKEN, watch: ['14'], alarms: [{ id: 3, h: 6, m: 30, red: true }] })), env);
  assert.equal(res.status, 200);
  assert.equal(env.DB.all('SELECT * FROM alarms').length, 1);
  // Порожній список будильників - телефон забуваємо
  res = await worker.fetch(req('/v1/sync', JSON.stringify({ token: TOKEN, watch: ['14'], alarms: [] })), env);
  assert.equal(env.DB.all('SELECT * FROM devices').length, 0);
  res = await worker.fetch(req('/v1/sync', '{oops'), env);
  assert.equal(res.status, 400);
  res = await worker.fetch(req('/privacy'), env);
  const text = await res.text();
  assert.match(text, /Політика конфіденційності/);
  assert.match(text, /help@example\.com/);
  assert.doesNotMatch(text, /\u2014|\u2013/); // без довгих тире
  res = await worker.fetch(req('/support'), env);
  assert.equal(res.status, 200);
  res = await worker.fetch(req('/nope'), env);
  assert.equal(res.status, 404);
});
