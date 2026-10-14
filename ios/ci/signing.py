#!/usr/bin/env python3
"""Готує підпис для App Store без Mac і без Xcode-акаунта: усе через App Store Connect API.

1. Реєструє ідентифікатори застосунку й віджета та вмикає Push Notifications.
2. Створює (один раз) сертифікат Apple Distribution. Його ключ шифрується секретом
   і зберігається в репозиторії (ios/signing), щоб не створювати новий сертифікат щоразу.
3. Створює свіжі профілі App Store для обох ідентифікаторів.

Потрібні змінні середовища: ASC_KEY_ID, ASC_ISSUER_ID, ASC_KEY_P8, SIGNING_DIR, P12_PASSWORD_FILE.
"""
import base64
import hashlib
import json
import os
import pathlib
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

import jwt
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.serialization import pkcs12
from cryptography.x509.oid import NameOID

API = "https://api.appstoreconnect.apple.com/v1"
BUNDLES = [("ua.calmalarm.app", "CalmAlarm", True), ("ua.calmalarm.app.widgets", "CalmAlarm Widgets", False)]

KEY_ID = os.environ["ASC_KEY_ID"].strip()
ISSUER = os.environ["ASC_ISSUER_ID"].strip()
P8 = os.environ["ASC_KEY_P8"].strip().replace("\\n", "\n")
SIGNING_DIR = pathlib.Path(os.environ.get("SIGNING_DIR", "ios/signing"))
P12_OUT = pathlib.Path(os.environ.get("P12_OUT", "/tmp/dist.p12"))
P12_PASS = hashlib.sha256((P8 + "|calmalarm-p12").encode()).hexdigest()[:24]


def token():
    now = int(time.time())
    return jwt.encode(
        {"iss": ISSUER, "iat": now, "exp": now + 1100, "aud": "appstoreconnect-v1"},
        P8,
        algorithm="ES256",
        headers={"kid": KEY_ID, "typ": "JWT"},
    )


def call(method, path, body=None, ok=(200, 201, 204)):
    url = path if path.startswith("http") else API + path
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Authorization", "Bearer " + token())
    req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            raw = r.read()
            return r.status, (json.loads(raw) if raw else {})
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            payload = json.loads(raw)
        except Exception:
            payload = {"raw": raw.decode(errors="replace")}
        if e.code in ok:
            return e.code, payload
        return e.code, payload


def die(msg, payload=None):
    print("::error::" + msg)
    if payload is not None:
        print(json.dumps(payload, ensure_ascii=False, indent=1)[:4000])
    sys.exit(1)


def ensure_bundle(identifier, name, push):
    st, res = call("GET", f"/bundleIds?filter[identifier]={identifier}&limit=50")
    if st != 200:
        die("App Store Connect API не відповідає. Перевірте ключ (роль Admin) і секрети ASC_*.", res)
    found = [b for b in res.get("data", []) if b["attributes"]["identifier"] == identifier]
    if found:
        bid = found[0]["id"]
    else:
        st, res = call("POST", "/bundleIds", {
            "data": {"type": "bundleIds", "attributes": {"identifier": identifier, "name": name, "platform": "IOS"}}
        })
        if st not in (200, 201):
            die(f"Не вдалося зареєструвати {identifier}. Можливо, цей ідентифікатор уже зайнятий іншим розробником.", res)
        bid = res["data"]["id"]
        print(f"Зареєстровано {identifier}")
    if push:
        st, res = call("POST", "/bundleIdCapabilities", {
            "data": {
                "type": "bundleIdCapabilities",
                "attributes": {"capabilityType": "PUSH_NOTIFICATIONS"},
                "relationships": {"bundleId": {"data": {"type": "bundleIds", "id": bid}}},
            }
        })
        if st not in (200, 201, 409):
            print("Попередження: не вдалося ввімкнути Push Notifications:", json.dumps(res)[:500])
    return bid


def key_for_secret():
    return hashlib.sha256((P8 + "|calmalarm-signing").encode()).digest()


def load_saved_cert():
    meta = SIGNING_DIR / "dist.json"
    blob = SIGNING_DIR / "dist.key.enc"
    if not meta.exists() or not blob.exists():
        return None
    info = json.loads(meta.read_text())
    st, res = call("GET", f"/certificates/{info['id']}")
    if st != 200:
        print("Збережений сертифікат більше не дійсний - створюю новий.")
        return None
    raw = base64.b64decode(blob.read_text())
    try:
        pem = AESGCM(key_for_secret()).decrypt(raw[:12], raw[12:], None)
    except Exception:
        print("Не вдалося розшифрувати ключ (змінився ключ API?) - створюю новий сертифікат.")
        return None
    key = serialization.load_pem_private_key(pem, password=None)
    cert = x509.load_der_x509_certificate(base64.b64decode(res["data"]["attributes"]["certificateContent"]))
    return info["id"], key, cert


def create_cert():
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    csr = (
        x509.CertificateSigningRequestBuilder()
        .subject_name(x509.Name([
            x509.NameAttribute(NameOID.COMMON_NAME, "CalmAlarm CI"),
            x509.NameAttribute(NameOID.COUNTRY_NAME, "UA"),
        ]))
        .sign(key, hashes.SHA256())
    )
    csr_pem = csr.public_bytes(serialization.Encoding.PEM).decode()
    st, res = call("POST", "/certificates", {
        "data": {"type": "certificates", "attributes": {"certificateType": "DISTRIBUTION", "csrContent": csr_pem}}
    })
    if st not in (200, 201):
        die(
            "Apple не дала створити сертифікат Apple Distribution. Якщо їх уже забагато, видаліть старий у "
            "developer.apple.com → Certificates і запустіть знову.",
            res,
        )
    cid = res["data"]["id"]
    cert = x509.load_der_x509_certificate(base64.b64decode(res["data"]["attributes"]["certificateContent"]))
    pem = key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption())
    nonce = os.urandom(12)
    enc = nonce + AESGCM(key_for_secret()).encrypt(nonce, pem, None)
    SIGNING_DIR.mkdir(parents=True, exist_ok=True)
    (SIGNING_DIR / "dist.key.enc").write_text(base64.b64encode(enc).decode())
    (SIGNING_DIR / "dist.json").write_text(json.dumps({"id": cid, "created": time.strftime("%Y-%m-%d")}))
    print("Створено новий сертифікат Apple Distribution:", cid)
    return cid, key, cert


def make_profile(bid, identifier, cert_id):
    name = f"CalmAlarm CI {identifier}"
    st, res = call("GET", f"/profiles?filter[name]={urllib.parse.quote(name)}&limit=50")
    for p in res.get("data", []) if st == 200 else []:
        if p["attributes"]["name"] == name:
            call("DELETE", f"/profiles/{p['id']}")
    st, res = call("POST", "/profiles", {
        "data": {
            "type": "profiles",
            "attributes": {"name": name, "profileType": "IOS_APP_STORE"},
            "relationships": {
                "bundleId": {"data": {"type": "bundleIds", "id": bid}},
                "certificates": {"data": [{"type": "certificates", "id": cert_id}]},
            },
        }
    })
    if st not in (200, 201):
        die(f"Не вдалося створити профіль для {identifier}.", res)
    content = base64.b64decode(res["data"]["attributes"]["profileContent"])
    uuid = res["data"]["attributes"]["uuid"]
    folder = pathlib.Path.home() / "Library/MobileDevice/Provisioning Profiles"
    folder.mkdir(parents=True, exist_ok=True)
    (folder / f"{uuid}.mobileprovision").write_bytes(content)
    # Новіші Xcode шукають профілі тут.
    folder2 = pathlib.Path.home() / "Library/Developer/Xcode/UserData/Provisioning Profiles"
    folder2.mkdir(parents=True, exist_ok=True)
    (folder2 / f"{uuid}.mobileprovision").write_bytes(content)
    return name


def main():
    print(f"::add-mask::{P12_PASS}")
    ids = {ident: ensure_bundle(ident, name, push) for ident, name, push in BUNDLES}
    saved = load_saved_cert()
    cert_id, key, cert = saved if saved else create_cert()
    # Стара схема шифрування p12 (3DES + SHA1) - її точно приймає security на macOS.
    legacy = (
        serialization.PrivateFormat.PKCS12.encryption_builder()
        .kdf_rounds(50000)
        .key_cert_algorithm(pkcs12.PBES.PBESv1SHA1And3KeyTripleDESCBC)
        .hmac_hash(hashes.SHA1())
        .build(P12_PASS.encode())
    )
    P12_OUT.write_bytes(pkcs12.serialize_key_and_certificates(b"CalmAlarm", key, cert, None, legacy))
    names = {ident: make_profile(ids[ident], ident, cert_id) for ident, _, _ in BUNDLES}
    out = os.environ.get("GITHUB_OUTPUT")
    lines = [
        f"p12={P12_OUT}",
        f"p12_password={P12_PASS}",
        f"app_profile={names['ua.calmalarm.app']}",
        f"widget_profile={names['ua.calmalarm.app.widgets']}",
    ]
    if out:
        with open(out, "a") as f:
            f.write("\n".join(lines) + "\n")
    print("Підпис готовий.")


if __name__ == "__main__":
    main()
