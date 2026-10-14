-- База даних сервера CalmAlarm (Cloudflare D1). Можна запускати повторно.

CREATE TABLE IF NOT EXISTS devices (
  token   TEXT PRIMARY KEY,
  sandbox INTEGER NOT NULL DEFAULT 0,
  tz      TEXT NOT NULL DEFAULT 'Europe/Kyiv',
  watch   TEXT NOT NULL DEFAULT '[]',
  updated INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS alarms (
  token    TEXT NOT NULL,
  alarm_id INTEGER NOT NULL,
  hour     INTEGER NOT NULL,
  minute   INTEGER NOT NULL,
  days     TEXT NOT NULL,
  red      INTEGER NOT NULL,
  yellow   INTEGER NOT NULL,
  wait_min INTEGER NOT NULL,
  next_at  INTEGER,
  PRIMARY KEY (token, alarm_id)
);

CREATE INDEX IF NOT EXISTS alarms_next ON alarms (next_at);

CREATE TABLE IF NOT EXISTS waiting (
  token     TEXT NOT NULL,
  alarm_id  INTEGER NOT NULL,
  occ       INTEGER NOT NULL,
  deadline  INTEGER NOT NULL,
  last_push INTEGER NOT NULL,
  PRIMARY KEY (token, alarm_id)
);

CREATE TABLE IF NOT EXISTS kv (
  k TEXT PRIMARY KEY,
  v TEXT NOT NULL,
  t INTEGER NOT NULL
);
