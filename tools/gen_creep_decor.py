"""Draws StarMine's own creep decoration textures (16x16 pixel art, not SC2 art).

Writes assets/starmine/textures/block/creep_{tendril,tentacle_bottom,tentacle_top,pustule,vessels}.png.
Deterministic (fixed seed), standard library only:  python tools/gen_creep_decor.py
"""
import math
import os
import random
import struct
import zlib

OUT = os.path.join(os.path.dirname(__file__), '..', 'src', 'main', 'resources', 'assets', 'starmine', 'textures', 'block')
W = H = 16


def png(path, pixels):
    raw = b''.join(b'\x00' + bytes(c for px in row for c in px) for row in pixels)
    def chunk(kind, data):
        return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data) & 0xffffffff)
    data = b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', W, H, 8, 6, 0, 0, 0)) \
        + chunk(b'IDAT', zlib.compress(raw, 9)) + chunk(b'IEND', b'')
    with open(path, 'wb') as f:
        f.write(data)


def blank():
    return [[(0, 0, 0, 0) for _ in range(W)] for _ in range(H)]


def mix(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))


BASE, TIP, DARK = (70, 20, 82), (205, 95, 175), (34, 8, 40)


def strand(img, x0, phase, amp, width_bottom, width_top, top=1, bottom=15, rings=False):
    """A tentacle from the bottom row up, swaying by a sine, darker outline, ringed if asked."""
    for y in range(bottom, top - 1, -1):
        t = (bottom - y) / max(1, bottom - top)
        cx = x0 + math.sin(phase + t * 2.6) * amp
        width = width_bottom + (width_top - width_bottom) * t
        colour = mix(BASE, TIP, t)
        if rings and y % 3 == 0:
            colour = mix(colour, DARK, 0.45)
        left, right = int(round(cx - width / 2)), int(round(cx + width / 2))
        for x in range(left, max(left + 1, right)):
            if 0 <= x < W:
                img[y][x] = colour + (255,)
        for x in (left - 1, max(left + 1, right)):
            if 0 <= x < W and img[y][x][3] == 0:
                img[y][x] = DARK + (255,)


def tendril():
    img = blank()
    strand(img, 5, 0.3, 1.6, 2.2, 0.8, top=3)
    strand(img, 11, 2.1, 1.4, 1.8, 0.7, top=6)
    return img


def tentacle(top_half):
    img = blank()
    if top_half:
        strand(img, 8, 1.4, 2.4, 3.0, 1.0, top=2, rings=True)
        img[2][int(8 + math.sin(1.4 + 2.6) * 2.4) + 1] = TIP + (255,)
    else:
        strand(img, 8, -1.2, 1.2, 4.2, 3.0, top=0, rings=True)
    return img


def pustule(rng):
    img = [[mix((110, 34, 84), (140, 48, 100), rng.random()) + (255,) for _ in range(W)] for _ in range(H)]
    for _ in range(6):
        cx, cy, r = rng.uniform(2, 14), rng.uniform(2, 14), rng.uniform(1.5, 3.2)
        for y in range(H):
            for x in range(W):
                d = math.hypot(x - cx, y - cy)
                if d < r:
                    shine = 1 - d / r
                    img[y][x] = mix((176, 74, 140), (236, 168, 196), shine) + (255,)
                elif d < r + 0.8:
                    img[y][x] = mix(img[y][x][:3], DARK, 0.6) + (255,)
    return img


def vessels(rng):
    img = blank()
    def vein(x, y, angle, length, width):
        for _ in range(length):
            xi, yi = int(round(x)) % W, int(round(y)) % H
            img[yi][xi] = (120, 14, 44, 255) if width > 1 else (150, 30, 60, 230)
            if width > 1:
                img[yi][(xi + 1) % W] = (90, 8, 34, 255)
            x += math.cos(angle)
            y += math.sin(angle)
            angle += rng.uniform(-0.5, 0.5)
            if rng.random() < 0.12 and length > 4:
                vein(x, y, angle + rng.choice((-1, 1)) * 0.9, length // 2, 1)
    vein(0, rng.uniform(4, 12), 0.1, 18, 2)
    vein(rng.uniform(4, 12), 0, 1.5, 16, 2)
    return img


def main():
    os.makedirs(OUT, exist_ok=True)
    rng = random.Random(1213)
    png(os.path.join(OUT, 'creep_tendril.png'), tendril())
    png(os.path.join(OUT, 'creep_tentacle_bottom.png'), tentacle(False))
    png(os.path.join(OUT, 'creep_tentacle_top.png'), tentacle(True))
    png(os.path.join(OUT, 'creep_pustule.png'), pustule(rng))
    png(os.path.join(OUT, 'creep_vessels.png'), vessels(rng))


if __name__ == '__main__':
    main()
