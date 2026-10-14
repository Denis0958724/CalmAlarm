// Тихі сигнали Apple Push Notification service (APNs) через HTTP/2 з JWT-ключем (.p8).

function b64url(bytes) {
  let s = '';
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

const enc = new TextEncoder();

/** Підписує токен постачальника APNs (ES256). Apple дозволяє оновлювати його не частіше ніж раз на 20 хв. */
export async function makeJwt(keyPem, keyId, teamId, nowSec) {
  const header = b64url(enc.encode(JSON.stringify({ alg: 'ES256', kid: keyId })));
  const claims = b64url(enc.encode(JSON.stringify({ iss: teamId, iat: nowSec })));
  const body = String(keyPem)
    .replace(/-----[A-Z ]+-----/g, '')
    .replace(/\\n/g, '')
    .replace(/\s+/g, '');
  const der = Uint8Array.from(atob(body), (c) => c.charCodeAt(0));
  const key = await crypto.subtle.importKey('pkcs8', der, { name: 'ECDSA', namedCurve: 'P-256' }, false, ['sign']);
  const sig = await crypto.subtle.sign({ name: 'ECDSA', hash: 'SHA-256' }, key, enc.encode(`${header}.${claims}`));
  return `${header}.${claims}.${b64url(new Uint8Array(sig))}`;
}

/**
 * Надсилає тихий сигнал. Повертає:
 * 'ok' - доставлено до Apple; 'gone' - токен більше не дійсний (застосунок видалено);
 * 'retry' - тимчасова помилка, спробуємо наступної хвилини.
 */
export async function sendPush({ fetchImpl = fetch, jwt, topic, token, sandbox, kind, nowSec }) {
  const host = sandbox ? 'https://api.sandbox.push.apple.com' : 'https://api.push.apple.com';
  try {
    const res = await fetchImpl(`${host}/3/device/${token}`, {
      method: 'POST',
      headers: {
        authorization: `bearer ${jwt}`,
        'apns-topic': topic,
        'apns-push-type': 'background',
        'apns-priority': '5',
        'apns-expiration': String(nowSec + 600),
        'content-type': 'application/json',
      },
      body: JSON.stringify({ aps: { 'content-available': 1 }, t: kind }),
    });
    if (res.status === 200) return 'ok';
    let reason = '';
    try {
      reason = (await res.json())?.reason || '';
    } catch {
      // без тіла відповіді
    }
    if (res.status === 410 || reason === 'BadDeviceToken' || reason === 'Unregistered' || reason === 'DeviceTokenNotForTopic') {
      return 'gone';
    }
    return 'retry';
  } catch {
    return 'retry';
  }
}
