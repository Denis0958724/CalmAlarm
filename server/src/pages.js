// Сторінки політики конфіденційності й підтримки (потрібні для App Store).

const esc = (s) => String(s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c]);

function layout(title, body) {
  return `<!doctype html>
<html lang="uk">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${esc(title)}</title>
<style>
  :root { color-scheme: light dark; --bg: #f5f6fa; --fg: #181c24; --muted: #5a6273; --card: #fff; --accent: #2f5bd3; }
  @media (prefers-color-scheme: dark) { :root { --bg: #0e131d; --fg: #e6e9f0; --muted: #a9b2c3; --card: #161d2a; --accent: #9db7ff; } }
  body { margin: 0; background: var(--bg); color: var(--fg); font: 16px/1.55 -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; }
  main { max-width: 720px; margin: 0 auto; padding: 32px 16px 48px; }
  h1 { font-size: 28px; margin: 0 0 4px; }
  h2 { font-size: 19px; margin: 28px 0 8px; }
  p, li { color: var(--fg); }
  .muted { color: var(--muted); }
  .card { background: var(--card); border-radius: 16px; padding: 4px 20px 12px; margin-top: 20px; }
  a { color: var(--accent); }
</style>
</head>
<body><main>${body}</main></body>
</html>`;
}

function contact(env) {
  const email = env.SUPPORT_EMAIL ? String(env.SUPPORT_EMAIL) : '';
  return email
    ? `<a href="mailto:${esc(email)}">${esc(email)}</a>`
    : 'через сторінку застосунку CalmAlarm в App Store або Google Play';
}

export function privacyPage(env) {
  return layout(
    'CalmAlarm - політика конфіденційності',
    `
<h1>Політика конфіденційності CalmAlarm</h1>
<p class="muted">Оновлено: жовтень 2026</p>

<div class="card">
<h2>Коротко</h2>
<p>CalmAlarm не збирає ваших імен, телефонів, контактів, точної геолокації чи рекламних ідентифікаторів.
Немає реклами, немає стеження, немає продажу даних.</p>
</div>

<h2>Що зберігається на телефоні</h2>
<p>Будильники, вибраний регіон (область, район або місто) і налаштування зберігаються лише на вашому пристрої.</p>

<h2>Що отримує сервер CalmAlarm (лише iPhone)</h2>
<p>Щоб iPhone міг «почекати відбою», сервер надсилає йому тихі сигнали Apple. Для цього застосунок передає серверу:</p>
<ul>
  <li>анонімний токен сповіщень Apple (він не розкриває, хто ви);</li>
  <li>час і дні будильників, які чекають відбою, та обмеження очікування;</li>
  <li>номери вибраних регіонів (як на офіційній карті тривог) і часовий пояс телефона.</li>
</ul>
<p>Ці дані використовуються лише для надсилання сигналів, нікому не передаються й автоматично видаляються,
коли ви вимикаєте такі будильники, змінюєте налаштування або видаляєте застосунок.</p>

<h2>Звідки беруться дані про тривоги</h2>
<p>Застосунок напряму завантажує публічні дані: карту тривог (siren.pp.ua, дзеркало API «Повітряна тривога»),
резервне джерело ubilling.net.ua та публічні сторінки Telegram-каналів (Повітряні сили ЗСУ, «Повітряна тривога»,
канали моніторів). CalmAlarm не є державним сервісом і не пов'язаний із цими джерелами. Завжди дійте за офіційним сигналом тривоги.</p>

<h2>Діти</h2>
<p>Застосунок не збирає персональних даних, тому ним можуть користуватися люди будь-якого віку.</p>

<h2>Зв'язок</h2>
<p>Питання й прохання видалити дані: ${contact(env)}.</p>

<div class="card">
<h2>Summary (English)</h2>
<p>CalmAlarm collects no names, contacts, precise location or advertising identifiers and shows no ads.
On iPhone, the app sends our server an anonymous Apple push token, the times of alarms that wait for the all-clear,
the chosen alert region IDs and the time zone, solely to deliver silent push notifications. This data is not shared
and is deleted when the alarms are disabled or the app is removed. Air-raid data comes from public sources
(siren.pp.ua, ubilling.net.ua, public Telegram channels). CalmAlarm is not a government service.</p>
</div>
`,
  );
}

export function supportPage(env) {
  return layout(
    'CalmAlarm - підтримка',
    `
<h1>Підтримка CalmAlarm</h1>
<p>CalmAlarm - будильник, який не будить під час повітряної тривоги і дзвонить одразу після відбою.</p>

<h2>Часті питання</h2>
<p><b>Будильник задзвонив під час тривоги. Чому?</b><br>
На iPhone застосунок дізнається про тривогу через тихий сигнал від сервера. Якщо не було інтернету,
застосунок закрили свайпом у перемикачі програм або увімкнено режим енергозбереження, сигнал може не дійти.
Тоді будильник дзвонить як звичайний: краще прокинутися, ніж проспати.</p>
<p><b>Будильник не задзвонив.</b><br>
Перевірте, що в Параметрах → CalmAlarm дозволено «Будильники й таймери», і що тривога не тривала довше
за обмеження, задане в будильнику.</p>
<p><b>Звідки дані?</b><br>
Офіційна карта тривог (через siren.pp.ua) та публічні канали Повітряних сил і моніторів.</p>

<h2>Зв'язок</h2>
<p>${contact(env)}</p>
<p><a href="/privacy">Політика конфіденційності</a></p>
`,
  );
}
