"""Draws StarMine's own textures for the world blocks of 16.10 (pixel art, not SC2 art):

- vespene and liquid creep, still (16x16) and flowing (32x32), animated;
- zerg flesh and glowing zerg flesh;
- the race portals (on: animated swirl; off: dark) and their frames;
- the nydus orifice;
- the liquid creep bucket (the vespene bucket recoloured).

Deterministic (fixed seed). Needs Pillow:  python tools/gen_world_textures.py
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
    return tuple(clamp(a[i] + (b[i] - a[i]) * t) for i in range(len(a)))


def noise(rnd, size):
    """A tileable smooth noise grid (sum of a few sine waves)."""
    waves = [(rnd.randint(1, 3), rnd.randint(1, 3), rnd.random() * math.tau, rnd.random() * 0.6 + 0.4) for _ in range(5)]
    def at(x, y, t=0.0):
        v = 0.0
        for fx, fy, ph, amp in waves:
            v += amp * math.sin(math.tau * (fx * x / size + fy * y / size) + ph + t)
        return v / sum(w[3] for w in waves) * 0.5 + 0.5
    return at


def save(image, name, folder=BLOCK, frametime=None):
    path = os.path.join(folder, name + '.png')
    image.save(path)
    if frametime is not None:
        with open(path + '.mcmeta', 'w') as f:
            f.write('{\n\t"animation": {\n\t\t"frametime": %d,\n\t\t"interpolate": true\n\t}\n}\n' % frametime)


def liquid(name, dark, light, glow, seed, size, frames, flow):
    rnd = random.Random(seed)
    n1, n2 = noise(rnd, size), noise(rnd, size)
    image = Image.new('RGBA', (size, size * frames))
    for f in range(frames):
        t = math.tau * f / frames
        for y in range(size):
            for x in range(size):
                yy = (y - f * size / frames) % size if flow else y
                v = n1(x, yy, t) * 0.7 + n2(x + yy * 0.3, yy, -t) * 0.3
                c = mix(dark, light, v)
                if v > 0.78:
                    c = mix(c, glow, (v - 0.78) / 0.22)
                image.putpixel((x, y + f * size), c + (205,))
    save(image, name, frametime=3)


def flesh(name, glowing, seed):
    rnd = random.Random(seed)
    n = noise(rnd, 16)
    image = Image.new('RGBA', (16, 16))
    for y in range(16):
        for x in range(16):
            v = n(x, y)
            c = mix((92, 22, 38), (170, 62, 72), v)
            if (x * 7 + y * 3 + rnd.randint(0, 2)) % 11 == 0:
                c = mix(c, (60, 12, 30), 0.6)  # folds
            if glowing and v > 0.62:
                c = mix(c, (255, 170, 120), (v - 0.62) / 0.38)  # glowing glands
            image.putpixel((x, y), c + (255,))
    save(image, name)


def portal(race, colours, seed):
    rnd = random.Random(seed)
    frames = 16
    on = Image.new('RGBA', (16, 16 * frames))
    dark, mid, bright = colours
    stars = [(rnd.random() * 16, rnd.random() * 16, rnd.random()) for _ in range(14)]
    for f in range(frames):
        t = math.tau * f / frames
        for y in range(16):
            for x in range(16):
                dx, dy = x - 7.5, y - 7.5
                r, a = math.hypot(dx, dy), math.atan2(dy, dx)
                v = 0.5 + 0.5 * math.sin(a * 3 + r * 0.9 - t * 2)
                c = mix(dark, mid, v * 0.8)
                on.putpixel((x, y + f * 16), c + (235,))
        for sx, sy, ph in stars:
            k = 0.5 + 0.5 * math.sin(t + ph * math.tau)
            px, py = int(sx) % 16, int(sy + f) % 16
            on.putpixel((px, py + f * 16), mix(mid, bright, k) + (255,))
    save(on, race + '_portal_on', frametime=2)
    off = Image.new('RGBA', (16, 16))
    for y in range(16):
        for x in range(16):
            v = rnd.random() * 0.15
            off.putpixel((x, y), mix((16, 16, 20), dark, v) + (255,))
    save(off, race + '_portal_off')


def frame(race, base, trim, accent, seed):
    rnd = random.Random(seed)
    side, top = Image.new('RGBA', (16, 16)), Image.new('RGBA', (16, 16))
    for y in range(16):
        for x in range(16):
            edge = x in (0, 15) or y in (0, 15)
            c = trim if edge else mix(base, trim, rnd.random() * 0.25)
            if y in (3, 12) and not edge:
                c = accent
            side.putpixel((x, y), c + (255,))
            d = max(abs(x - 7.5), abs(y - 7.5))
            t = trim if edge else accent if 2.5 < d < 4 else mix(base, trim, rnd.random() * 0.2)
            top.putpixel((x, y), t + (255,))
    save(side, race + '_portal_frame_side')
    save(top, race + '_portal_frame_top')


def orifice(seed):
    rnd = random.Random(seed)
    image = Image.new('RGBA', (16, 16))
    for y in range(16):
        for x in range(16):
            r = math.hypot(x - 7.5, y - 7.5)
            if r < 4:
                c = mix((40, 8, 50), (110, 40, 130), rnd.random() * 0.4)
            elif r < 6:
                c = (220, 200, 170) if (int(math.degrees(math.atan2(y - 7.5, x - 7.5))) // 30) % 2 == 0 else (120, 30, 50)  # teeth
            else:
                c = mix((92, 22, 38), (150, 52, 66), rnd.random())
            image.putpixel((x, y), c + (255,))
    save(image, 'nydus_orifice')


def creep_bucket():
    source = Image.open(os.path.join(ITEM, 'vespene_bucket.png')).convert('RGBA')
    out = Image.new('RGBA', source.size)
    for y in range(source.height):
        for x in range(source.width):
            r, g, b, a = source.getpixel((x, y))
            if g > r + 25 and g > b:  # vespene green: purple creep instead
                out.putpixel((x, y), (clamp(g * 0.75), clamp(g * 0.25), clamp(g * 0.85), a))
            else:
                out.putpixel((x, y), (r, g, b, a))
    out.save(os.path.join(ITEM, 'liquid_creep_bucket.png'))


def terrazine_bucket():
    """Owner 17.10: the terrazine bucket — the vespene bucket in terrazine's violet."""
    source = Image.open(os.path.join(ITEM, 'vespene_bucket.png')).convert('RGBA')
    out = Image.new('RGBA', source.size)
    for y in range(source.height):
        for x in range(source.width):
            r, g, b, a = source.getpixel((x, y))
            if g > r + 25 and g > b:
                out.putpixel((x, y), (clamp(g * 0.7), clamp(g * 0.35), clamp(g * 1.05), a))
            else:
                out.putpixel((x, y), (r, g, b, a))
    out.save(os.path.join(ITEM, 'terrazine_bucket.png'))


if __name__ == '__main__':
    liquid('vespene_still', (20, 90, 40), (90, 220, 110), (210, 255, 200), 1, 16, 16, False)
    liquid('vespene_flow', (20, 90, 40), (90, 220, 110), (210, 255, 200), 2, 32, 16, True)
    liquid('liquid_creep_still', (40, 10, 50), (120, 40, 140), (220, 120, 230), 3, 16, 16, False)
    liquid('liquid_creep_flow', (40, 10, 50), (120, 40, 140), (220, 120, 230), 4, 32, 16, True)
    liquid('terrazine_still', (50, 20, 110), (150, 80, 230), (240, 200, 255), 5, 16, 16, False)
    liquid('terrazine_flow', (50, 20, 110), (150, 80, 230), (240, 200, 255), 6, 32, 16, True)
    terrazine_bucket()
    flesh('zerg_flesh', False, 5)
    flesh('glowing_zerg_flesh', True, 5)
    portal('zerg', ((30, 6, 30), (150, 40, 120), (255, 170, 210)), 6)
    portal('terran', ((8, 20, 40), (60, 140, 220), (220, 240, 255)), 7)
    portal('protoss', ((30, 22, 6), (210, 160, 50), (255, 245, 200)), 8)
    frame('zerg', (80, 20, 40), (40, 10, 25), (230, 150, 110), 9)
    frame('terran', (110, 115, 120), (60, 64, 70), (240, 200, 40), 10)
    frame('protoss', (200, 190, 150), (150, 120, 60), (80, 180, 230), 11)
    orifice(12)
    creep_bucket()


def mineral_textures():
    """The mineral crystal (facets: light lines on blue), mineralized earth (dirt with blue grains), the shard item."""
    rnd = random.Random(13)
    crystal = Image.new('RGBA', (16, 16))
    for y in range(16):
        for x in range(16):
            v = y / 15
            c = mix((150, 230, 255), (30, 90, 200), v)
            if x % 8 in (0, 7):
                c = mix(c, (230, 250, 255), 0.6)  # the edges between facets
            if rnd.random() < 0.06:
                c = (255, 255, 255)
            crystal.putpixel((x, y), c + (255,))
    save(crystal, 'mineral_crystal')
    earth = Image.new('RGBA', (16, 16))
    for y in range(16):
        for x in range(16):
            c = mix((95, 66, 45), (130, 92, 62), rnd.random())
            if rnd.random() < 0.12:
                c = mix((70, 160, 230), (170, 230, 255), rnd.random())
            earth.putpixel((x, y), c + (255,))
    save(earth, 'mineralized_earth')
    shard = Image.new('RGBA', (16, 16), (0, 0, 0, 0))
    for y in range(2, 15):
        half = (y - 2) * 0.35 + 0.5
        for x in range(16):
            if abs(x - 7.5 - (14 - y) * 0.15) <= half:
                t = (x - 4) / 8
                shard.putpixel((x, y), mix((170, 235, 255), (30, 90, 200), max(0, min(1, t))) + (255,))
    shard.save(os.path.join(ITEM, 'mineral_shard.png'))


if __name__ == '__main__':
    mineral_textures()
