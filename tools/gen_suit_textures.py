"""Draws StarMine's own race suit textures (pixel art made here, not SC2 or Minecraft art).

Writes, for each race (zerg, terran, protoss):
  assets/starmine/textures/item/{race}_armor_shard.png and {race}_{helmet,chestplate,leggings,boots}.png (16x16)
  assets/starmine/textures/models/armor/{race}_suit_layer_1.png and _layer_2.png (64x32, the worn suit)
Deterministic (fixed seed), standard library only:  python tools/gen_suit_textures.py
"""
import os
import random
import struct
import zlib

ROOT = os.path.join(os.path.dirname(__file__), '..', 'src', 'main', 'resources', 'assets', 'starmine', 'textures')

# base, light, dark, accent: zerg chitin, terran steel plating, protoss gold alloy with blue light
PALETTES = {
    'zerg': ((92, 52, 96), (168, 112, 150), (40, 18, 44), (214, 160, 60)),
    'terran': ((92, 110, 132), (168, 186, 204), (38, 46, 60), (230, 150, 40)),
    'protoss': ((190, 150, 60), (246, 220, 130), (96, 66, 20), (80, 190, 255)),
}

# 16x16 masks: '#' body, '+' accent, '.' empty
MASKS = {
    'helmet': [
        '................',
        '................',
        '................',
        '....########....',
        '...##########...',
        '..############..',
        '..############..',
        '..###++++++###..',
        '..##+......+##..',
        '..##........##..',
        '..##........##..',
        '..###......###..',
        '................',
        '................',
        '................',
        '................',
    ],
    'chestplate': [
        '................',
        '..####....####..',
        '.#####....#####.',
        '.##############.',
        '.###+######+###.',
        '.###########.##.',
        '.##.########.##.',
        '.##.###++###.##.',
        '.##.###++###.##.',
        '....########....',
        '....########....',
        '....###++###....',
        '....########....',
        '....########....',
        '................',
        '................',
    ],
    'leggings': [
        '................',
        '...##########...',
        '...####++####...',
        '...##########...',
        '...####..####...',
        '...####..####...',
        '...###....###...',
        '...###....###...',
        '...##+....+##...',
        '...###....###...',
        '...###....###...',
        '...###....###...',
        '...###....###...',
        '...###....###...',
        '................',
        '................',
    ],
    'boots': [
        '................',
        '................',
        '................',
        '................',
        '................',
        '................',
        '..###......###..',
        '..###......###..',
        '..##+......+##..',
        '..###......###..',
        '.####.....####..',
        '#####....#####..',
        '#####....#####..',
        '................',
        '................',
        '................',
    ],
    'shard': [
        '................',
        '................',
        '.........##.....',
        '........###.....',
        '.......####.....',
        '......#####.....',
        '.....###+##.....',
        '....###+###.....',
        '...###+####.....',
        '...##+#####.....',
        '...########.....',
        '....#######.....',
        '.....#####......',
        '......###.......',
        '................',
        '................',
    ],
}


def png(path, pixels):
    h, w = len(pixels), len(pixels[0])
    raw = b''.join(b'\x00' + bytes(c for px in row for c in px) for row in pixels)

    def chunk(kind, data):
        return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data) & 0xffffffff)

    data = b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 6, 0, 0, 0)) \
        + chunk(b'IDAT', zlib.compress(raw, 9)) + chunk(b'IEND', b'')
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'wb') as f:
        f.write(data)


def mix(a, b, t):
    return tuple(max(0, min(255, int(a[i] + (b[i] - a[i]) * t))) for i in range(3))


def icon(mask, palette, rng):
    """Body shaded from light (top left) to dark (bottom right), a dark outline, accent pixels."""
    base, light, dark, accent = palette
    out = [[(0, 0, 0, 0)] * 16 for _ in range(16)]
    filled = lambda x, y: 0 <= x < 16 and 0 <= y < 16 and mask[y][x] != '.'
    for y in range(16):
        for x in range(16):
            if not filled(x, y):
                continue
            edge = not all(filled(x + dx, y + dy) for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)))
            if mask[y][x] == '+':
                c = accent
            elif edge:
                c = dark
            else:
                t = (x + y) / 30
                c = mix(light, base, min(1, t * 1.6)) if t < 0.5 else mix(base, dark, (t - 0.5) * 1.2)
                c = mix(c, (0, 0, 0), rng.random() * 0.08)
            out[y][x] = c + (255,)
    return out


def suit_layer(palette, rng, layer):
    """The worn suit (64x32, the humanoid armor layout): plates with seams, lighter rims, accent lights.
    Layer 1 is the helmet, chestplate and boots; layer 2 the leggings. The outer head layer stays empty."""
    base, light, dark, accent = palette
    out = [[(0, 0, 0, 0)] * 64 for _ in range(32)]

    def paint(x0, y0, x1, y1):
        for y in range(y0, y1):
            for x in range(x0, x1):
                seam = (y - y0) % 4 == 3 or (x - x0) % 6 == 5
                c = dark if seam else mix(base, light, 0.35 if (y - y0) % 4 == 0 else 0.0)
                c = mix(c, (0, 0, 0), rng.random() * 0.1)
                if not seam and rng.random() < 0.025:
                    c = accent
                out[y][x] = c + (255,)

    if layer == 1:
        paint(0, 0, 32, 16)    # head
        paint(16, 16, 40, 32)  # body
        paint(40, 16, 56, 32)  # arms
        paint(0, 16, 16, 32)   # legs (boots)
    else:
        paint(16, 16, 40, 32)  # body (the belt part of the leggings)
        paint(0, 16, 16, 32)   # legs
    return out


def main():
    rng = random.Random(1015)
    for race, palette in PALETTES.items():
        png(os.path.join(ROOT, 'item', race + '_armor_shard.png'), icon(MASKS['shard'], palette, rng))
        for piece in ('helmet', 'chestplate', 'leggings', 'boots'):
            png(os.path.join(ROOT, 'item', race + '_' + piece + '.png'), icon(MASKS[piece], palette, rng))
        for layer in (1, 2):
            png(os.path.join(ROOT, 'models', 'armor', '%s_suit_layer_%d.png' % (race, layer)), suit_layer(palette, rng, layer))


if __name__ == '__main__':
    main()
