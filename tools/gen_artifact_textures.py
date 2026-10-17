"""Draws StarMine's textures for the artifact and the Void of 17.10 (pixel art, not SC2 art):

- the Void portal (animated violet swirl with red sparks) and its frame;
- the artifact's pedestal (dark stone with gold trim; its top lit when the artifact is whole);
- the three parts of the artifact's key (zerg carapace, terran steel, protoss gold, each with a glowing core);
- the heroes' helmets (the race helmet recoloured, with gold).

Deterministic (fixed seed). Needs Pillow:  python tools/gen_artifact_textures.py
"""
import math
import os
import random

from PIL import Image

ROOT = os.path.join(os.path.dirname(__file__), '..', 'src', 'main', 'resources', 'assets', 'starmine', 'textures')
BLOCK = os.path.join(ROOT, 'block')
ITEM = os.path.join(ROOT, 'item')


def clamp(v):
    return max(0, min(255, int(v)))


def mix(a, b, t):
    t = max(0.0, min(1.0, t))
    return tuple(clamp(a[i] + (b[i] - a[i]) * t) for i in range(3))


def save(image, name, folder=BLOCK, frametime=None):
    path = os.path.join(folder, name + '.png')
    image.save(path)
    if frametime is not None:
        with open(path + '.mcmeta', 'w') as f:
            f.write('{\n\t"animation": {\n\t\t"frametime": %d,\n\t\t"interpolate": true\n\t}\n}\n' % frametime)


def void_portal():
    rnd = random.Random(1710)
    frames = 16
    image = Image.new('RGBA', (16, 16 * frames))
    sparks = [(rnd.random() * 16, rnd.random() * 16, rnd.random()) for _ in range(10)]
    for f in range(frames):
        t = math.tau * f / frames
        for y in range(16):
            for x in range(16):
                dx, dy = x - 7.5, y - 7.5
                r, a = math.hypot(dx, dy), math.atan2(dy, dx)
                v = 0.5 + 0.5 * math.sin(a * 2 - r * 0.8 + t * 2)
                w = 0.5 + 0.5 * math.sin(a * 5 + r * 0.4 - t)
                c = mix((8, 0, 18), (96, 24, 160), v * 0.75 + w * 0.15)
                image.putpixel((x, y + f * 16), c + (225,))
        for sx, sy, ph in sparks:
            k = 0.5 + 0.5 * math.sin(t * 2 + ph * math.tau)
            px, py = int(sx + f * 0.5) % 16, int(sy - f) % 16
            image.putpixel((px, py + f * 16), mix((120, 10, 30), (255, 70, 60), k) + (255,))
    save(image, 'void_portal', frametime=2)


def stone(rnd, x, y, dark, light):
    return mix(dark, light, rnd.random() * 0.35 + (0.15 if (x * 3 + y * 5) % 7 == 0 else 0))


def void_frame():
    rnd = random.Random(1711)
    side, top = Image.new('RGBA', (16, 16)), Image.new('RGBA', (16, 16))
    for y in range(16):
        for x in range(16):
            c = stone(rnd, x, y, (14, 10, 22), (40, 30, 56))
            if x in (0, 15) or y in (0, 15):
                c = (58, 40, 82)
            if y in (4, 11) and 2 <= x <= 13 and x % 3 != 0:
                c = (150, 60, 210)  # glyph lines
            side.putpixel((x, y), c + (255,))
            d = max(abs(x - 7.5), abs(y - 7.5))
            c = stone(rnd, x, y, (14, 10, 22), (40, 30, 56))
            if d > 6.5:
                c = (58, 40, 82)
            elif 2.5 < d < 3.6:
                c = (170, 70, 230)
            top.putpixel((x, y), c + (255,))
    save(side, 'void_portal_frame_side')
    save(top, 'void_portal_frame_top')


def pedestal():
    rnd = random.Random(1712)
    side = Image.new('RGBA', (16, 16))
    for y in range(16):
        for x in range(16):
            c = stone(rnd, x, y, (30, 28, 36), (62, 58, 72))
            if y in (0, 1, 14, 15):
                c = mix((150, 110, 30), (240, 200, 80), rnd.random() * 0.6)  # gold bands
            if 5 <= y <= 10 and x in (3, 12):
                c = (200, 160, 60)
            if 6 <= y <= 9 and 6 <= x <= 9:
                c = (120, 70, 190)  # the glyph of the three
            side.putpixel((x, y), c + (255,))
    save(side, 'artifact_pedestal_side')
    for name, glow in (('artifact_pedestal_top', (90, 50, 140)), ('artifact_pedestal_top_on', (230, 170, 255))):
        top = Image.new('RGBA', (16, 16))
        for y in range(16):
            for x in range(16):
                d = math.hypot(x - 7.5, y - 7.5)
                c = stone(rnd, x, y, (30, 28, 36), (62, 58, 72))
                if max(abs(x - 7.5), abs(y - 7.5)) > 6.5:
                    c = (210, 170, 70)
                elif d < 4.5:
                    c = mix(glow, (255, 255, 255) if 'on' in name else glow, (4.5 - d) / 4.5 * 0.6)
                elif d < 5.5:
                    c = (200, 160, 60)
                top.putpixel((x, y), c + (255,))
        save(top, name)
    bottom = Image.new('RGBA', (16, 16))
    for y in range(16):
        for x in range(16):
            bottom.putpixel((x, y), stone(rnd, x, y, (30, 28, 36), (62, 58, 72)) + (255,))
    save(bottom, 'artifact_pedestal_bottom')


PARTS = {
    'zerg': ((60, 14, 70), (140, 40, 120), (255, 140, 40)),
    'terran': ((70, 76, 88), (150, 160, 175), (90, 190, 255)),
    'protoss': ((150, 110, 30), (240, 205, 90), (90, 230, 255)),
}


def parts():
    for race, (dark, light, glow) in PARTS.items():
        rnd = random.Random(hash(race) & 0xFFFF)
        side, top = Image.new('RGBA', (16, 16)), Image.new('RGBA', (16, 16))
        for y in range(16):
            for x in range(16):
                c = mix(dark, light, rnd.random() * 0.4 + (0.3 if (x + y) % 5 == 0 else 0))
                if x in (2, 13):
                    c = mix(dark, (0, 0, 0), 0.3)
                if 5 <= x <= 10 and 3 <= y <= 12:
                    k = 1 - abs(x - 7.5) / 3 - abs(y - 7.5) / 6
                    if k > 0:
                        c = mix(c, glow, 0.4 + k)
                side.putpixel((x, y), c + (255,))
                d = math.hypot(x - 7.5, y - 7.5)
                c = mix(dark, light, rnd.random() * 0.4)
                if d < 3:
                    c = mix(glow, (255, 255, 255), (3 - d) / 6)
                elif 4.5 < d < 5.5:
                    c = light
                top.putpixel((x, y), c + (255,))
        save(side, race + '_key_part_side')
        save(top, race + '_key_part_top')


HELMETS = {
    'kerrigan_crown': ('zerg', (150, 40, 160), (255, 150, 40)),
    'raynor_helmet': ('terran', (120, 50, 40), (230, 190, 70)),
    'artanis_helmet': ('protoss', (230, 200, 90), (90, 230, 255)),
}


def helmets():
    for name, (race, tint, accent) in HELMETS.items():
        base = Image.open(os.path.join(ITEM, race + '_helmet.png')).convert('RGBA')
        out = Image.new('RGBA', base.size)
        for y in range(base.height):
            for x in range(base.width):
                r, g, b, a = base.getpixel((x, y))
                if a == 0:
                    continue
                lum = (r * 0.3 + g * 0.59 + b * 0.11) / 255
                c = mix((0, 0, 0), tint, lum * 1.4)
                if lum > 0.72:
                    c = mix(c, accent, 0.8)
                out.putpixel((x, y), c + (a,))
        # a crest of gold on top: the hero's mark
        top = min((y for y in range(base.height) for x in range(base.width) if base.getpixel((x, y))[3] > 0), default=0)
        for x in range(base.width // 2 - 2, base.width // 2 + 2):
            out.putpixel((x, max(0, top)), (255, 215, 80, 255))
        save(out, name, folder=ITEM)


if __name__ == '__main__':
    void_portal()
    void_frame()
    pedestal()
    parts()
    helmets()
    print('ok')
