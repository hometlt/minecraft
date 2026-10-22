#!/usr/bin/env python3
"""Writes the views seen through a void gate (owner 22.10: a portal shows faint outlines of the world it leads to):
equirectangular panoramas, 1024 x 512, seamless round the horizon, one per destination, into
src/main/resources/assets/starmine/textures/entity/portal_view/<name>.png. Drawn, not taken from any game:
a sky, layers of silhouettes on the horizon, stars or glow. Run: python tools/gen_portal_views.py (needs Pillow)."""
import math, os, random
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, 'src/main/resources/assets/starmine/textures/entity/portal_view')
W, H = 1024, 512


def ridge(u, seed, freqs):
    """A seamless height round the horizon (u in 0..1): integer frequencies of sines."""
    r = random.Random(seed)
    return sum(a * math.sin(2 * math.pi * (f * u + r.random())) for f, a in freqs)


def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))


def sky(img, top, horizon, bottom):
    px = img.load()
    for y in range(H):
        t = y / (H - 1)
        c = lerp(top, horizon, min(1, t / 0.5)) if t < 0.5 else lerp(horizon, bottom, (t - 0.5) / 0.5)
        for x in range(W):
            px[x, y] = c


def layer(img, base, amp, colour, seed, freqs, jag=0.0):
    px = img.load()
    r = random.Random(seed + 99)
    for x in range(W):
        u = x / W
        h = base + amp * ridge(u, seed, freqs) + (jag * (r.random() - 0.5) if jag else 0)
        top = int(H * (0.5 - h))
        for y in range(max(0, top), H):
            px[x, y] = colour


def stars(img, count, seed, colour=(255, 255, 255), below=0.55):
    px = img.load()
    r = random.Random(seed)
    for _ in range(count):
        x, y = r.randrange(W), r.randrange(int(H * below))
        b = r.random()
        c = lerp((40, 40, 60), colour, b)
        px[x, y] = c
        if b > 0.9 and x + 1 < W: px[x + 1, y] = c


def overworld():
    img = Image.new('RGB', (W, H))
    sky(img, (96, 150, 225), (190, 215, 245), (70, 110, 60))
    layer(img, 0.10, 0.05, (120, 150, 190), 1, [(2, 1), (5, 0.5), (11, 0.2)])  # far mountains
    layer(img, 0.05, 0.035, (80, 120, 90), 2, [(3, 1), (7, 0.6), (17, 0.3)])  # hills
    layer(img, 0.01, 0.02, (50, 90, 50), 3, [(6, 1), (13, 0.5), (29, 0.4)], jag=0.012)  # forest edge
    return img


def nether():
    img = Image.new('RGB', (W, H))
    sky(img, (40, 5, 5), (150, 40, 20), (60, 10, 10))
    layer(img, 0.14, 0.07, (90, 20, 20), 4, [(3, 1), (8, 0.7), (19, 0.4)], jag=0.03)  # crags
    layer(img, 0.04, 0.04, (55, 10, 12), 5, [(5, 1), (12, 0.6), (31, 0.4)], jag=0.02)
    px = img.load()
    for x in range(W):  # a lava glow low down
        for y in range(int(H * 0.56), int(H * 0.6)):
            px[x, y] = lerp(px[x, y], (255, 120, 30), 0.6)
    return img


def end():
    img = Image.new('RGB', (W, H))
    sky(img, (8, 4, 16), (40, 25, 50), (20, 15, 25))
    stars(img, 900, 6, (230, 210, 255), 0.7)
    r = random.Random(7)
    px = img.load()
    for _ in range(9):  # floating islands with pillars
        cx, cy, w = r.randrange(W), int(H * (0.42 + r.random() * 0.15)), 20 + r.randrange(60)
        for x in range(cx - w, cx + w):
            depth = int((1 - abs(x - cx) / w) * w * 0.4)
            for y in range(cy, cy + depth): px[x % W, y] = (200, 195, 140)
        for k in range(r.randrange(3)):
            pxl = cx + r.randrange(-w // 2, w // 2)
            for y in range(cy - 30 - r.randrange(40), cy):
                for dx in range(3): px[(pxl + dx) % W, y] = (25, 20, 35)
    return img


def void():
    img = Image.new('RGB', (W, H))
    sky(img, (10, 0, 20), (70, 20, 110), (15, 0, 25))
    stars(img, 1400, 8, (255, 220, 255), 1.0)
    layer(img, 0.02, 0.06, (40, 10, 70), 9, [(4, 1), (9, 0.6), (23, 0.5)], jag=0.04)  # crystal shards
    return img


def stratosphere():
    img = Image.new('RGB', (W, H))
    sky(img, (0, 0, 5), (10, 15, 40), (0, 0, 5))
    stars(img, 1200, 10, (255, 255, 255), 0.6)
    px = img.load()
    for x in range(W):  # the earth's limb, blue, below the horizon
        for y in range(int(H * 0.58), H):
            t = (y - H * 0.58) / (H * 0.42)
            px[x, y] = lerp((120, 180, 255), (20, 60, 130), min(1, t * 2))
    return img


os.makedirs(OUT, exist_ok=True)
for name, make in [('overworld', overworld), ('nether', nether), ('end', end), ('void', void), ('stratosphere', stratosphere)]:
    make().save(os.path.join(OUT, name + '.png'))
    print(name)
