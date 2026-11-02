"""Іконка CalmAlarm: симетричний будильник (стрілки 10:10) на темному тлі із сяйвом по центру.

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

# ---- геометрія (полотно 1024), усе симетрично відносно вертикалі ----
K = 1.05                        # загальний масштаб малюнка
R = 232 * K                     # радіус кільця (по середині лінії)
RING_W = 54 * K
BELL_D, BELL_R = R + 60 * K, 68 * K   # дзвоники: відстань від центру і радіус
LEG_R0, LEG_R1, LEG_W = R + 20 * K, R + 76 * K, 42 * K  # ніжки
LEG_A = 60                       # кут ніжок від горизонталі
HAND_L, HAND_W, DOT_R = 0.62 * R, 36 * K, 26 * K       # стрілки 10:10 однакової довжини
BELL_A = 50                      # кут дзвоників від горизонталі
CX = 512
# межі малюнка по вертикалі, щоб поставити його точно в центр полотна
_top = (BELL_D * math.sin(math.radians(BELL_A)) + BELL_R)
_half_w = BELL_D * math.cos(math.radians(BELL_A)) + BELL_R
_bot = (LEG_R1 * math.sin(math.radians(LEG_A)) + LEG_W / 2)
CY = 512 + (_top - _bot) / 2


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
    (x0, y0), (x1, y1), (qx, qy) = P(-r, 0), P(r, 0), P(0, r * 0.28)
    rr = f(CX + r, 0)[0] - f(CX, 0)[0]
    return f'M{x0:.2f},{y0:.2f} A{rr:.2f},{rr:.2f} 0 0 1 {x1:.2f},{y1:.2f} Q{qx:.2f},{qy:.2f} {x0:.2f},{y0:.2f} Z'


def ray(theta, r0, r1, f=lambda x, y: (x, y)):
    t = math.radians(theta)
    (x0, y0) = f(CX + r0 * math.cos(t), CY + r0 * math.sin(t))
    (x1, y1) = f(CX + r1 * math.cos(t), CY + r1 * math.sin(t))
    return f'M{x0:.2f},{y0:.2f} L{x1:.2f},{y1:.2f}'


def legs_path(f=lambda x, y: (x, y)):
    return ray(180 - LEG_A, LEG_R0, LEG_R1, f) + ' ' + ray(LEG_A, LEG_R0, LEG_R1, f)


def hands_path(f=lambda x, y: (x, y)):
    # 10:10: дзеркально, по 60 градусів від «12»
    return ray(-150, 0, HAND_L, f) + ' ' + ray(-30, 0, HAND_L, f)


def dot_path(r, f=lambda x, y: (x, y)):
    (x0, y0), (x1, y1) = f(CX - r, CY), f(CX + r, CY)
    k = (x1 - x0) / 2
    return f'M{x0:.2f},{y0:.2f} A{k:.2f},{k:.2f} 0 1 1 {x1:.2f},{y1:.2f} A{k:.2f},{k:.2f} 0 1 1 {x0:.2f},{y0:.2f} Z'


def ring_path(r, f=lambda x, y: (x, y)):
    (x0, y0), (x1, y1) = f(CX, CY - r), f(CX, CY + r)
    k = (f(CX + r, 0)[0] - f(CX, 0)[0])
    return f'M{x0:.2f},{y0:.2f} A{k:.2f},{k:.2f} 0 1 1 {x1:.2f},{y1:.2f} A{k:.2f},{k:.2f} 0 1 1 {x0:.2f},{y0:.2f} Z'


# ---- SVG ----
U = 'gradientUnits="userSpaceOnUse"'
TOP, BOT = CY - _top, CY + _bot
DEFS = f'''
<linearGradient id="dk" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#111D46"/><stop offset="1" stop-color="#060A18"/></linearGradient>
<radialGradient id="g1" {U} cx="512" cy="{CY:.1f}" r="560"><stop offset="0" stop-color="#5C6DFF" stop-opacity="0.55"/><stop offset="0.55" stop-color="#4F8CFF" stop-opacity="0.16"/><stop offset="1" stop-color="#4F8CFF" stop-opacity="0"/></radialGradient>
<radialGradient id="g2" {U} cx="512" cy="-80" r="620"><stop offset="0" stop-color="#4F8CFF" stop-opacity="0.30"/><stop offset="1" stop-color="#4F8CFF" stop-opacity="0"/></radialGradient>
<linearGradient id="acc" {U} x1="0" y1="{TOP:.1f}" x2="0" y2="{BOT:.1f}"><stop offset="0" stop-color="#7FB4FF"/><stop offset="0.5" stop-color="#6A80FF"/><stop offset="1" stop-color="#8F6CFF"/></linearGradient>
<radialGradient id="face" {U} cx="512" cy="{CY - R*0.45:.1f}" r="{R:.1f}"><stop offset="0" stop-color="#FFFFFF" stop-opacity="0.15"/><stop offset="1" stop-color="#FFFFFF" stop-opacity="0.04"/></radialGradient>
<filter id="glow" x="-40%" y="-40%" width="180%" height="180%"><feGaussianBlur stdDeviation="26"/></filter>
'''
GLYPH = f'''<g fill="url(#acc)"><path d="{bell_path(-180 + BELL_A)}"/><path d="{bell_path(-BELL_A)}"/></g>
<path d="{legs_path()}" stroke="url(#acc)" stroke-width="{LEG_W:.1f}" stroke-linecap="round" fill="none"/>
<circle cx="{CX}" cy="{CY:.1f}" r="{R:.1f}" fill="none" stroke="url(#acc)" stroke-width="{RING_W+8:.1f}" filter="url(#glow)" opacity="0.85"/>
<circle cx="{CX}" cy="{CY:.1f}" r="{R-RING_W/2:.1f}" fill="url(#face)"/>
<circle cx="{CX}" cy="{CY:.1f}" r="{R:.1f}" fill="none" stroke="url(#acc)" stroke-width="{RING_W:.1f}"/>
<circle cx="{CX}" cy="{CY:.1f}" r="{R+RING_W/2:.1f}" fill="none" stroke="#fff" stroke-opacity="0.16" stroke-width="2"/>
<path d="{hands_path()}" stroke="#fff" stroke-width="{HAND_W:.1f}" stroke-linecap="round" fill="none"/>
<circle cx="{CX}" cy="{CY:.1f}" r="{DOT_R:.1f}" fill="#fff"/>'''


def bg(x0, size):
    return ''.join(f'<rect x="{x0}" y="{x0}" width="{size}" height="{size}" fill="url(#{g})"/>' for g in ('dk', 'g2', 'g1'))


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


def stroke_round(d, c, w):
    return (f'    <path\n        android:fillColor="#00000000"\n        android:strokeColor="{c}"\n'
            f'        android:strokeWidth="{w:.2f}"\n        android:strokeLineCap="round"\n        android:pathData="{d}" />\n')


def glyph_vector(f, k, color):
    return [fill(bell_path(-180 + BELL_A, f), color), fill(bell_path(-BELL_A, f), color),
            stroke_round(legs_path(f), color, LEG_W * k),
            stroke(ring_path(R, f), color, RING_W * k),
            stroke_round(hands_path(f), color, HAND_W * k),
            fill(dot_path(DOT_R, f), color)]


# Монохромна (тематичні іконки Android 13+): той самий масштаб, що й кольорова
k = 72 / 1024
mono = lambda x, y: (18 + x * k, 18 + y * k)
open(os.path.join(res, 'drawable/ic_launcher_monochrome.xml'), 'w').write(
    vector(108, 108, glyph_vector(mono, k, '#FF000000')))

# Значок сповіщень 24dp: малюнок вписано в 22.4dp
kn = 22.4 / max(_top + _bot, 2 * _half_w)
notif = lambda x, y: (12 + (x - CX) * kn, 12 + (y - CY) * kn + (_top - _bot) / 2 * kn)
open(os.path.join(res, 'drawable/ic_notif.xml'), 'w').write(
    vector(24, 24, glyph_vector(notif, kn, '#FFFFFFFF')))
print('ok', round(CY, 1), round(CY - _top, 1), round(CY + _bot, 1))
