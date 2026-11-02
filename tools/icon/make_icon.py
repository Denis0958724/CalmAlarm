"""Іконка CalmAlarm: будильник із місяцем на темному тлі з синьо-фіолетовим сяйвом.

Запуск (потрібні Pillow і Node з Playwright):
    python3 tools/icon/make_icon.py
Перезаписує іконки Android (mipmap-*, монохромна, сповіщення) та iOS (AppIcon),
а також кладе icon-1024.png і icon-512.png (для магазинів) у tools/icon/out/.
"""
import json, math, os, subprocess, sys
from PIL import Image

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, 'out')
os.makedirs(OUT, exist_ok=True)

# ---- геометрія (полотно 1024) ----
CX, CY, R = 512, 574, 248      # центр і радіус кільця
RING_W = 58
BELL_D, BELL_R = R + 70, 90    # відстань дзвоників від центру і їхній радіус


def rot(px, py, ang):
    a = math.radians(ang)
    return px * math.cos(a) - py * math.sin(a), px * math.sin(a) + py * math.cos(a)


def bell_path(theta, f=lambda x, y: (x, y)):
    """Купол дзвоника, повернутий назовні під кутом theta."""
    t = math.radians(theta)
    bx, by = CX + BELL_D * math.cos(t), CY + BELL_D * math.sin(t)

    def P(x, y):
        rx, ry = rot(x, y, theta + 90)
        return f(bx + rx, by + ry)
    r = BELL_R
    (x0, y0), (x1, y1), (qx, qy) = P(-r, 0), P(r, 0), P(0, r * 0.22)
    rr = f(CX + r, 0)[0] - f(CX, 0)[0]
    return f'M{x0:.2f},{y0:.2f} A{rr:.2f},{rr:.2f} 0 0 1 {x1:.2f},{y1:.2f} Q{qx:.2f},{qy:.2f} {x0:.2f},{y0:.2f} Z'


def moon_path(f=lambda x, y: (x, y)):
    mr, mx, my = 128, CX - 14, CY + 10
    a0, a1 = math.radians(-60), math.radians(32)
    (sx, sy) = f(mx + mr * math.cos(a0), my + mr * math.sin(a0))
    (ex, ey) = f(mx + mr * math.cos(a1), my + mr * math.sin(a1))
    k = f(CX + 1, 0)[0] - f(CX, 0)[0]
    return (f'M{sx:.2f},{sy:.2f} A{mr*k:.2f},{mr*k:.2f} 0 1 0 {ex:.2f},{ey:.2f} '
            f'A{mr*0.8*k:.2f},{mr*0.8*k:.2f} 0 1 1 {sx:.2f},{sy:.2f} Z')


def star_path(x, y, s, f=lambda x, y: (x, y)):
    k = s * 0.16
    pts = [(x, y - s), (x + k, y - k), (x + k, y - k), (x + s, y), (x + k, y + k), (x + k, y + k), (x, y + s),
           (x - k, y + k), (x - k, y + k), (x - s, y), (x - k, y - k), (x - k, y - k), (x, y - s)]
    p = [f(*q) for q in pts]
    d = f'M{p[0][0]:.2f},{p[0][1]:.2f}'
    for i in range(1, 13, 3):
        d += ' C' + ' '.join(f'{a:.2f},{b:.2f}' for a, b in p[i:i + 3])
    return d + ' Z'


STARS = [(CX + 92, CY - 78, 40), (CX + 138, CY + 6, 20)]


def ring_path(r, f=lambda x, y: (x, y)):
    (x0, y0), (x1, y1) = f(CX, CY - r), f(CX, CY + r)
    k = (f(CX + r, 0)[0] - f(CX, 0)[0])
    return f'M{x0:.2f},{y0:.2f} A{k:.2f},{k:.2f} 0 1 1 {x1:.2f},{y1:.2f} A{k:.2f},{k:.2f} 0 1 1 {x0:.2f},{y0:.2f}'


# ---- SVG ----
U = 'gradientUnits="userSpaceOnUse"'
DEFS = f'''
<linearGradient id="dk" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#101C44"/><stop offset="1" stop-color="#070B1C"/></linearGradient>
<radialGradient id="g1" {U} cx="250" cy="200" r="620"><stop offset="0" stop-color="#4F8CFF" stop-opacity="0.55"/><stop offset="1" stop-color="#4F8CFF" stop-opacity="0"/></radialGradient>
<radialGradient id="g2" {U} cx="880" cy="900" r="640"><stop offset="0" stop-color="#7C5CFF" stop-opacity="0.55"/><stop offset="1" stop-color="#7C5CFF" stop-opacity="0"/></radialGradient>
<linearGradient id="ring" {U} x1="{CX-R}" y1="{CY-R}" x2="{CX+R}" y2="{CY+R}"><stop offset="0" stop-color="#7DB2FF"/><stop offset="0.5" stop-color="#5E7BFF"/><stop offset="1" stop-color="#9A72FF"/></linearGradient>
<linearGradient id="bellg" {U} x1="230" y1="0" x2="800" y2="0"><stop offset="0" stop-color="#6FA6FF"/><stop offset="1" stop-color="#8E6CFF"/></linearGradient>
<radialGradient id="face" {U} cx="{CX}" cy="{CY-80}" r="{R}"><stop offset="0" stop-color="#FFFFFF" stop-opacity="0.16"/><stop offset="1" stop-color="#FFFFFF" stop-opacity="0.04"/></radialGradient>
<filter id="glow" x="-40%" y="-40%" width="180%" height="180%"><feGaussianBlur stdDeviation="26"/></filter>
<filter id="mglow" x="-40%" y="-40%" width="180%" height="180%"><feGaussianBlur stdDeviation="14"/></filter>
'''
moon = f'<path d="{moon_path()}"/>'
stars = ''.join(f'<path d="{star_path(*s)}"/>' for s in STARS)
GLYPH = f'''<g fill="url(#bellg)"><path d="{bell_path(-130)}"/><path d="{bell_path(-50)}"/></g>
<circle cx="{CX}" cy="{CY}" r="{R}" fill="none" stroke="url(#ring)" stroke-width="64" filter="url(#glow)" opacity="0.85"/>
<circle cx="{CX}" cy="{CY}" r="{R-32}" fill="url(#face)"/>
<circle cx="{CX}" cy="{CY}" r="{R}" fill="none" stroke="url(#ring)" stroke-width="{RING_W}"/>
<circle cx="{CX}" cy="{CY}" r="{R+29}" fill="none" stroke="#fff" stroke-opacity="0.18" stroke-width="2"/>
<g fill="#BFD3FF" filter="url(#mglow)" opacity="0.7">{moon}</g><g fill="#fff">{moon}{stars}</g>'''


def bg(x0, size):
    return (f'<rect x="{x0}" y="{x0}" width="{size}" height="{size}" fill="url(#dk)"/>'
            f'<rect x="{x0}" y="{x0}" width="{size}" height="{size}" fill="url(#g1)"/>'
            f'<rect x="{x0}" y="{x0}" width="{size}" height="{size}" fill="url(#g2)"/>')


def svg(body, vb='0 0 1024 1024'):
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="1024" height="1024" viewBox="{vb}">'
            f'<defs>{DEFS}</defs>{body}</svg>')


# Android: шар 108dp, видима частина 72dp, тож полотно 1024 = 72dp, а весь шар = 1536.
AVB = '-256 -256 1536 1536'
files = {
    'icon.svg': svg(bg(0, 1024) + GLYPH),
    'fg.svg': svg(GLYPH, AVB),
    'bg.svg': svg(bg(-256, 1536), AVB),
}
for n, s in files.items():
    open(os.path.join(OUT, n), 'w').write(s)

jobs = [[os.path.join(OUT, 'icon.svg'), os.path.join(OUT, 'icon-1024.png'), 1024]]
DENS = {'mdpi': 108, 'hdpi': 162, 'xhdpi': 216, 'xxhdpi': 324, 'xxxhdpi': 432}
for d, px in DENS.items():
    for layer in ('fg', 'bg'):
        jobs.append([os.path.join(OUT, f'{layer}.svg'), os.path.join(OUT, f'{layer}-{d}.png'), px])
subprocess.run(['node', os.path.join(HERE, 'render.js'), json.dumps(jobs)], check=True)

# iOS: 1024 без прозорості
ios = Image.open(os.path.join(OUT, 'icon-1024.png')).convert('RGB')
ios.save(os.path.join(ROOT, 'ios/CalmAlarm/Assets.xcassets/AppIcon.appiconset/icon.png'), optimize=True)
ios.save(os.path.join(OUT, 'icon-1024.png'))
ios.resize((512, 512), Image.LANCZOS).save(os.path.join(OUT, 'icon-512.png'))

# Android: шари адаптивної іконки
res = os.path.join(ROOT, 'app/src/main/res')
for d in DENS:
    dd = os.path.join(res, f'mipmap-{d}')
    os.makedirs(dd, exist_ok=True)
    Image.open(os.path.join(OUT, f'fg-{d}.png')).save(os.path.join(dd, 'ic_launcher_foreground.png'), optimize=True)
    Image.open(os.path.join(OUT, f'bg-{d}.png')).convert('RGB').save(os.path.join(dd, 'ic_launcher_background.png'), optimize=True)


def vector(size, vp, paths):
    body = ''.join(paths)
    return (f'<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            f'    android:width="{size}dp"\n    android:height="{size}dp"\n'
            f'    android:viewportWidth="{vp}"\n    android:viewportHeight="{vp}">\n{body}</vector>\n')


def fill(d, c):
    return f'    <path\n        android:fillColor="{c}"\n        android:pathData="{d}" />\n'


def stroke(d, c, w):
    return (f'    <path\n        android:fillColor="#00000000"\n        android:strokeColor="{c}"\n'
            f'        android:strokeWidth="{w:.2f}"\n        android:pathData="{d}" />\n')


def glyph_vector(f, k, color, small=False):
    p = [fill(bell_path(-130, f), color), fill(bell_path(-50, f), color),
         stroke(ring_path(R, f), color, RING_W * k), fill(moon_path(f), color)]
    for s in (STARS[:1] if small else STARS):
        p.append(fill(star_path(*s, f=f), color))
    return p


# Монохромна (тематичні іконки Android 13+): той самий масштаб, що й кольорова
k = 72 / 1024
mono = lambda x, y: (18 + x * k, 18 + y * k)
open(os.path.join(res, 'drawable/ic_launcher_monochrome.xml'), 'w').write(
    vector(108, 108, glyph_vector(mono, k, '#FF000000')))

# Значок сповіщень 24dp: межі малюнка приблизно x 220..804, y 166..851
kn = 22.4 / 685
notif = lambda x, y: (12 + (x - 512) * kn, 12 + (y - 508.5) * kn)
open(os.path.join(res, 'drawable/ic_notif.xml'), 'w').write(
    vector(24, 24, glyph_vector(notif, kn, '#FFFFFFFF', small=True)))
print('ok')
