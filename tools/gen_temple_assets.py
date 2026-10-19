"""The xel'naga temples' assets (owner 19.10), pixel art drawn here, not SC2 art:

- the Nether's and the End's parts of the key: the overworld's parts recoloured (the Nether's glow ember red on
  scorched metal, the End's pale violet on bone white), with their block states and models;
- xel'naga metal (dark, faint lines), its glyph (glowing green-gold signs), the temple's door, the key socket and the
  triangle portal's sheet (animated);
- their names in en_us and ru_ru, the worlds' names, the temples' messages and the keepers' names.

Deterministic. Needs Pillow:  python tools/gen_temple_assets.py
"""
import json
import math
import os
import random

from PIL import Image

ASSETS = os.path.join(os.path.dirname(__file__), '..', 'src', 'main', 'resources', 'assets', 'starmine')
BLOCK = os.path.join(ASSETS, 'textures', 'block')


def clamp(v):
    return max(0, min(255, int(v)))


def mix(a, b, t):
    t = max(0.0, min(1.0, t))
    return tuple(clamp(a[i] + (b[i] - a[i]) * t) for i in range(3))


def write_json(path, value):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w', encoding='utf8', newline='\n') as f:
        json.dump(value, f, indent='\t', ensure_ascii=False)
        f.write('\n')


def save(image, name, frametime=None):
    path = os.path.join(BLOCK, name + '.png')
    image.save(path)
    if frametime is not None:
        with open(path + '.mcmeta', 'w', encoding='utf8', newline='\n') as f:
            f.write('{\n\t"animation": {\n\t\t"frametime": %d,\n\t\t"interpolate": true\n\t}\n}\n' % frametime)


# ---- the other worlds' parts ----

WORLDS = {
    'nether': ((255, 90, 30), (70, 20, 16)),  # glow, metal tint
    'end': ((200, 130, 255), (225, 220, 190)),
}


def core(face, x, y):
    """Whether a pixel is in the part's glowing core (as gen_artifact_textures.py draws it)."""
    if face == 'side':
        return 5 <= x <= 10 and 3 <= y <= 12 and 1 - abs(x - 7.5) / 3 - abs(y - 7.5) / 6 > 0
    return math.hypot(x - 7.5, y - 7.5) < 3


def recolour(pixel, glow, tint, lit):
    r, g, b, a = pixel
    bright = (r * 0.3 + g * 0.59 + b * 0.11) / 255.0
    if lit:
        return mix(glow, (255, 255, 255), max(0.0, bright - 0.6)) + (a,)
    return mix(mix((0, 0, 0), tint, 0.45 + bright * 0.9), (r, g, b), 0.35) + (a,)  # the race's own colour shows through


def parts():
    for world, (glow, tint) in WORLDS.items():
        for race in ('zerg', 'terran', 'protoss'):
            name = '%s_%s_key_part' % (world, race)
            for face in ('side', 'top'):
                src = Image.open(os.path.join(BLOCK, '%s_key_part_%s.png' % (race, face))).convert('RGBA')
                out = Image.new('RGBA', src.size)
                for y in range(src.size[1]):
                    for x in range(src.size[0]):
                        out.putpixel((x, y), recolour(src.getpixel((x, y)), glow, tint, core(face, x, y)))
                save(out, '%s_%s' % (name, face))
            with open(os.path.join(ASSETS, 'models', 'block', '%s_key_part.json' % race), encoding='utf8') as f:
                model = json.load(f)
            model['textures'] = {'particle': '#side', 'side': 'starmine:block/%s_side' % name, 'top': 'starmine:block/%s_top' % name}
            write_json(os.path.join(ASSETS, 'models', 'block', name + '.json'), model)
            write_json(os.path.join(ASSETS, 'models', 'item', name + '.json'), {'parent': 'starmine:block/' + name})
            write_json(os.path.join(ASSETS, 'blockstates', name + '.json'), {'variants': {'': {'model': 'starmine:block/' + name}}})


# ---- xel'naga metal ----

DARK, LIGHT, LINE = (22, 26, 34), (52, 58, 70), (60, 150, 140)
GOLD, GREEN = (240, 210, 110), (120, 255, 200)


def metal(rnd, x, y):
    c = mix(DARK, LIGHT, rnd.random() * 0.3 + (0.25 if (x + y * 3) % 11 == 0 else 0))
    if x in (0, 15) or y in (0, 15):
        c = mix(c, (10, 12, 16), 0.5)
    return c


def xelnaga():
    rnd = random.Random(1910)
    m = Image.new('RGBA', (16, 16))
    for y in range(16):
        for x in range(16):
            c = metal(rnd, x, y)
            if (y == 7 and 3 <= x <= 12) or (x == 7 and 3 <= y <= 12 and y % 3):
                c = mix(c, LINE, 0.55)  # faint tracery
            m.putpixel((x, y), c + (255,))
    save(m, 'xelnaga_metal')
    g = Image.new('RGBA', (16, 16))
    for y in range(16):
        for x in range(16):
            c = metal(rnd, x, y)
            d = math.hypot(x - 7.5, y - 7.5)
            if 4.6 < d < 5.6 or (x in (7, 8) and 2 <= y <= 13) or (y in (7, 8) and 2 <= x <= 13 and abs(x - 7.5) > 2.5):
                c = mix(GREEN, GOLD, (x + y) / 30)  # the glowing sign
            g.putpixel((x, y), c + (255,))
    save(g, 'xelnaga_glyph')
    door = Image.new('RGBA', (16, 16))
    for y in range(16):
        for x in range(16):
            c = metal(rnd, x, y)
            if abs(x - 7.5) + abs(y - 7.5) in (5, 6):
                c = mix(GREEN, (255, 255, 255), 0.2)  # the lock's diamond
            elif abs(x - 7.5) < 1 and abs(y - 7.5) < 1:
                c = GOLD
            elif x in (1, 14) or y in (1, 14):
                c = mix(c, GOLD, 0.6)
            door.putpixel((x, y), c + (255,))
    save(door, 'xelnaga_door')
    top, side = Image.new('RGBA', (16, 16)), Image.new('RGBA', (16, 16))
    for y in range(16):
        for x in range(16):
            d = math.hypot(x - 7.5, y - 7.5)
            c = metal(rnd, x, y)
            if d < 3.2:
                c = mix(GREEN, (255, 255, 255), (3.2 - d) / 5)
            elif 5 < d < 6.2:
                c = GOLD
            top.putpixel((x, y), c + (255,))
            c = metal(rnd, x, y)
            if y in (0, 1, 14, 15):
                c = mix(GOLD, (120, 90, 30), rnd.random() * 0.4)
            if 6 <= y <= 9 and x % 4 == 1:
                c = GREEN
            side.putpixel((x, y), c + (255,))
    save(top, 'xelnaga_socket_top')
    save(side, 'xelnaga_socket_side')
    frames = 16
    portal = Image.new('RGBA', (16, 16 * frames))
    for f in range(frames):
        t = math.tau * f / frames
        for y in range(16):
            for x in range(16):
                dx, dy = x - 7.5, y - 7.5
                r, a = math.hypot(dx, dy), math.atan2(dy, dx)
                v = 0.5 + 0.5 * math.sin(a * 3 - r * 0.9 + t * 2)
                w = 0.5 + 0.5 * math.sin(r * 0.7 - t)
                c = mix((10, 40, 60), (110, 255, 220), v * 0.6 + w * 0.2)
                c = mix(c, GOLD, max(0, v - 0.85) * 4)
                portal.putpixel((x, y + f * 16), c + (210,))
    save(portal, 'xelnaga_portal', frametime=2)
    for name, model in (('xelnaga_metal', {'parent': 'minecraft:block/cube_all', 'textures': {'all': 'starmine:block/xelnaga_metal'}}),
                        ('xelnaga_glyph', {'parent': 'minecraft:block/cube_all', 'textures': {'all': 'starmine:block/xelnaga_glyph'}}),
                        ('xelnaga_door', {'parent': 'minecraft:block/cube_all', 'textures': {'all': 'starmine:block/xelnaga_door'}}),
                        ('xelnaga_socket', {'parent': 'minecraft:block/cube_bottom_top', 'textures': {
                            'top': 'starmine:block/xelnaga_socket_top', 'bottom': 'starmine:block/xelnaga_metal', 'side': 'starmine:block/xelnaga_socket_side'}}),
                        ('xelnaga_portal', {'parent': 'minecraft:block/cube_all', 'textures': {'all': 'starmine:block/xelnaga_portal'}})):
        write_json(os.path.join(ASSETS, 'models', 'block', name + '.json'), model)
        write_json(os.path.join(ASSETS, 'blockstates', name + '.json'), {'variants': {'': {'model': 'starmine:block/' + name}}})
        if name != 'xelnaga_portal':
            write_json(os.path.join(ASSETS, 'models', 'item', name + '.json'), {'parent': 'starmine:block/' + name})


# ---- names ----

RACES = {'zerg': ('Zerg', 'зергов'), 'terran': ('Terran', 'терранов'), 'protoss': ('Protoss', 'протоссов')}
LANG = {
    'en_us': {
        'block.starmine.xelnaga_metal': "Xel'Naga Metal",
        'block.starmine.xelnaga_glyph': "Xel'Naga Glyph",
        'block.starmine.xelnaga_door': "Xel'Naga Temple Door",
        'block.starmine.xelnaga_socket': "Xel'Naga Key Socket",
        'block.starmine.xelnaga_portal': "Xel'Naga Portal",
        'entity.starmine.xelnagaconstruct': "Xel'Naga Construct",
        'item.starmine.xelnagaconstruct_spawn_egg': "Xel'Naga Construct Spawn Egg",
        'entity.starmine.xelnagasentinel': "Xel'Naga Sentinel",
        'item.starmine.xelnagasentinel_spawn_egg': "Xel'Naga Sentinel Spawn Egg",
        'entity.starmine.voidmonolith': 'Void Monolith',
        'item.starmine.voidmonolith_spawn_egg': 'Void Monolith Spawn Egg',
        'world.starmine.overworld': 'the overworld',
        'world.starmine.nether': 'the Nether',
        'world.starmine.end': 'the End',
        'message.starmine.amun_awakened': 'Amun has awoken! His troops come into %s.',
        'message.starmine.temple_way': "The Xel'Naga temple: %s blocks to the %s",
        'message.starmine.temple_here': "The Xel'Naga temple is here",
        'message.starmine.temple_elsewhere': "This key opens the Xel'Naga temple in %s",
        'message.starmine.temple_door_locked': "Only a part of this world's key opens the temple's door",
        'message.starmine.temple_door_opened': "The temple's door opens",
        'message.starmine.temple_portal_opened': 'The three parts are in place: the portal to the Void opens!',
        'direction.starmine.n': 'north', 'direction.starmine.ne': 'north-east', 'direction.starmine.e': 'east',
        'direction.starmine.se': 'south-east', 'direction.starmine.s': 'south', 'direction.starmine.sw': 'south-west',
        'direction.starmine.w': 'west', 'direction.starmine.nw': 'north-west',
    },
    'ru_ru': {
        'block.starmine.xelnaga_metal': 'Металл Зел’Нага',
        'block.starmine.xelnaga_glyph': 'Знак Зел’Нага',
        'block.starmine.xelnaga_door': 'Дверь храма Зел’Нага',
        'block.starmine.xelnaga_socket': 'Гнездо ключа Зел’Нага',
        'block.starmine.xelnaga_portal': 'Портал Зел’Нага',
        'entity.starmine.xelnagaconstruct': 'Конструкт Зел’Нага',
        'item.starmine.xelnagaconstruct_spawn_egg': 'Яйцо призыва: конструкт Зел’Нага',
        'entity.starmine.xelnagasentinel': 'Страж Зел’Нага',
        'item.starmine.xelnagasentinel_spawn_egg': 'Яйцо призыва: страж Зел’Нага',
        'entity.starmine.voidmonolith': 'Монолит Пустоты',
        'item.starmine.voidmonolith_spawn_egg': 'Яйцо призыва: монолит Пустоты',
        'world.starmine.overworld': 'верхний мир',
        'world.starmine.nether': 'Незер',
        'world.starmine.end': 'Энд',
        'message.starmine.amun_awakened': 'Амун пробудился! Его войска идут в %s.',
        'message.starmine.temple_way': 'Храм Зел’Нага: %s блоков, на %s',
        'message.starmine.temple_here': 'Храм Зел’Нага здесь',
        'message.starmine.temple_elsewhere': 'Этот ключ открывает храм Зел’Нага в мире: %s',
        'message.starmine.temple_door_locked': 'Дверь храма открывает только часть ключа этого мира',
        'message.starmine.temple_door_opened': 'Дверь храма открывается',
        'message.starmine.temple_portal_opened': 'Все три части на месте: портал в Пустоту открылся!',
        'direction.starmine.n': 'север', 'direction.starmine.ne': 'северо-восток', 'direction.starmine.e': 'восток',
        'direction.starmine.se': 'юго-восток', 'direction.starmine.s': 'юг', 'direction.starmine.sw': 'юго-запад',
        'direction.starmine.w': 'запад', 'direction.starmine.nw': 'северо-запад',
    },
}
WORLD_NAMES = {'nether': ('Nether', 'Незера'), 'end': ('End', 'Энда')}


def lang():
    for code, names in LANG.items():
        path = os.path.join(ASSETS, 'lang', code + '.json')
        with open(path, encoding='utf8') as f:
            table = json.load(f)
        table.update(names)
        for world, (en_world, ru_world) in WORLD_NAMES.items():
            for race, (en, ru) in RACES.items():
                key = 'block.starmine.%s_%s_key_part' % (world, race)
                table[key] = ('Key Artifact: %s %s Part' % (en_world, en)) if code == 'en_us' else ('Ключ-артефакт %s: часть %s' % (ru_world, ru))
        table.pop('message.starmine.amun_invades', None)
        write_json(path, table)


if __name__ == '__main__':
    parts()
    xelnaga()
    lang()
    print('ok')
