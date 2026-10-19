"""Owner 19.10: the races' armour walls (three looks each) and doors: textures drawn here, models, blockstates, loot tables,
recipes (nine armour shards a wall; looks swapped at a stonecutter; doors of flesh, iron or gold and shards), tool tags.
Run from the repository root: python tools/gen_armor_blocks.py"""
import io
import json
import os
import random

from PIL import Image

ROOT = os.path.join(os.path.dirname(__file__), '..', 'src', 'main', 'resources')
ASSETS = os.path.join(ROOT, 'assets', 'starmine')
DATA = os.path.join(ROOT, 'data', 'starmine')
RACES = ['zerg', 'terran', 'protoss']
STYLES = ['armor_wall', 'armor_plating', 'armor_panel']


def write(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with io.open(path, 'w', encoding='utf8', newline='\n') as f:
        json.dump(obj, f, indent='\t', ensure_ascii=False)
        f.write('\n')


def shade(c, k):
    return tuple(max(0, min(255, int(v * k))) for v in c[:3]) + (c[3] if len(c) > 3 else 255,)


PALETTE = {
    'zerg': {'base': (58, 34, 56), 'dark': (32, 18, 30), 'light': (110, 70, 96), 'glow': (154, 224, 80)},
    'terran': {'base': (110, 116, 124), 'dark': (60, 64, 70), 'light': (168, 174, 182), 'glow': (64, 192, 255)},
    'protoss': {'base': (200, 160, 48), 'dark': (120, 90, 24), 'light': (236, 224, 196), 'glow': (64, 160, 255)},
}


def wall(race, style, rnd):
    p = PALETTE[race]
    img = Image.new('RGBA', (16, 16))
    px = img.load()
    for x in range(16):
        for y in range(16):
            n = rnd.uniform(0.9, 1.08)
            c = shade(p['base'] + (255,), n)
            if race == 'zerg':  # chitin plates: rounded scales with dark seams
                sx, sy = (x + (y // 6) * 3) % 8, y % 6
                if sy == 5 or sx == 0: c = shade(p['dark'] + (255,), n)
                elif sy == 0: c = shade(p['light'] + (255,), n)
                if style == 'armor_plating' and (x + y) % 8 == 0: c = shade(p['light'] + (255,), n)
                if style == 'armor_panel' and (sy == 2 and sx in (3, 4) or (x * 3 + y * 5) % 23 == 0): c = p['glow'] + (255,)
            elif race == 'terran':  # steel plates with seams and rivets
                if x in (0, 15) or y in (0, 15) or (style != 'armor_wall' and (y == 7 or x == 7)): c = shade(p['dark'] + (255,), n)
                elif (x, y) in ((2, 2), (13, 2), (2, 13), (13, 13)): c = shade(p['light'] + (255,), 1.0)
                elif x == 1 or y == 1: c = shade(p['light'] + (255,), n)
                if style == 'armor_plating' and 3 <= y <= 5 and 3 <= x <= 12 and (x + y) % 2 == 0: c = shade(p['dark'] + (255,), n)
                if style == 'armor_panel' and y in (10, 11) and 2 <= x <= 13: c = p['glow'] + (255,)
            else:  # khalai alloy: gold with ivory inlay and blue lines
                if x in (0, 15) or y in (0, 15): c = shade(p['dark'] + (255,), n)
                elif 4 <= x <= 11 and 4 <= y <= 11: c = shade(p['light'] + (255,), n)
                if style == 'armor_plating' and (abs(x - 7.5) + abs(y - 7.5)) in (5.0, 6.0): c = shade(p['dark'] + (255,), n)
                if style == 'armor_panel' and (x == 7 or x == 8) and 3 <= y <= 12: c = p['glow'] + (255,)
                if style == 'armor_panel' and (y == 7 or y == 8) and 3 <= x <= 12: c = p['glow'] + (255,)
            px[x, y] = c
    return img


def door(race, open_, rnd):
    p = PALETTE[race]
    img = Image.new('RGBA', (16, 16), (0, 0, 0, 0))
    px = img.load()
    for x in range(16):
        for y in range(16):
            n = rnd.uniform(0.9, 1.08)
            edge = x in (0, 15) or y in (0, 15)
            dx, dy = x - 7.5, y - 7.5
            r = (dx * dx + dy * dy) ** 0.5
            if race == 'zerg':  # a valve of flesh: folds drawn in to the middle; open, a ring of lips round a hole
                flesh = (120, 40, 50)
                if open_:
                    if r >= 6.2: px[x, y] = shade(flesh + (255,), n * (0.7 if r > 7.4 else 1.1))
                else:
                    import math
                    fold = math.sin(math.atan2(dy, dx) * 6 + r * 0.5)
                    px[x, y] = shade(flesh + (255,), n * (0.8 + 0.35 * fold) * (0.6 if r < 1.2 else 1))
                    if edge: px[x, y] = shade(p['base'] + (255,), n)
            elif race == 'terran':  # an airlock: steel leaves with hazard stripes; open, the frame
                if edge or x in (1, 14) or y in (1, 14):
                    px[x, y] = shade(p['dark'] + (255,), n) if edge else shade(p['base'] + (255,), n)
                elif not open_:
                    stripe = ((x + y) // 3) % 2 == 0 and (y < 4 or y > 11)
                    px[x, y] = (230, 190, 30, 255) if stripe else (30, 30, 30, 255) if (y < 4 or y > 11) else shade(p['base'] + (255,), n)
                    if x in (7, 8): px[x, y] = shade(p['dark'] + (255,), n)
            else:  # a gate: a gold frame and, shut, a field of blue light
                if edge or x in (1, 14) or y in (1, 14):
                    px[x, y] = shade(p['base'] + (255,), n) if edge else shade(p['light'] + (255,), n)
                elif not open_:
                    a = 120 + int(40 * rnd.random())
                    px[x, y] = (90, 170, 255, a) if (x + y) % 5 else (170, 220, 255, a + 40)
    return img


def main():
    rnd = random.Random(19)
    tex = os.path.join(ASSETS, 'textures', 'block')
    pickaxe, diamond, en, ru = [], [], {}, {}
    names_en = {'zerg': 'Zerg', 'terran': 'Terran', 'protoss': 'Protoss'}
    names_ru = {'zerg': 'зергов', 'terran': 'терранов', 'protoss': 'протоссов'}
    style_en = {'armor_wall': 'Armor Wall', 'armor_plating': 'Armor Plating', 'armor_panel': 'Armor Panel'}
    style_ru = {'armor_wall': 'Бронестена', 'armor_plating': 'Бронеплита', 'armor_panel': 'Бронепанель'}
    door_en = {'zerg': 'Zerg Sphincter', 'terran': 'Terran Airlock', 'protoss': 'Protoss Shield Gate'}
    door_ru = {'zerg': 'Сфинктер зергов', 'terran': 'Шлюз терранов', 'protoss': 'Щитовые ворота протоссов'}
    door_from = {'zerg': 'starmine:zerg_flesh', 'terran': 'minecraft:iron_block', 'protoss': 'minecraft:gold_block'}
    for race in RACES:
        for style in STYLES:
            name = f'{race}_{style}'
            wall(race, style, rnd).save(os.path.join(tex, name + '.png'))
            write(os.path.join(ASSETS, 'blockstates', name + '.json'), {'variants': {'': {'model': f'starmine:block/{name}'}}})
            write(os.path.join(ASSETS, 'models', 'block', name + '.json'), {'parent': 'minecraft:block/cube_all', 'textures': {'all': f'starmine:block/{name}'}})
            write(os.path.join(ASSETS, 'models', 'item', name + '.json'), {'parent': f'starmine:block/{name}'})
            write(os.path.join(DATA, 'loot_table', 'blocks', name + '.json'), loot(name))
            pickaxe.append('starmine:' + name)
            diamond.append('starmine:' + name)
            en[f'block.starmine.{name}'] = f'{names_en[race]} {style_en[style]}'
            ru[f'block.starmine.{name}'] = f'{style_ru[style]} {names_ru[race]}'
            for other in STYLES:
                if other != style:
                    write(os.path.join(DATA, 'recipe', f'{race}_{other}_from_{style}.json'),
                          {'type': 'minecraft:stonecutting', 'ingredient': {'item': f'starmine:{name}'}, 'result': {'id': f'starmine:{race}_{other}', 'count': 1}})
        write(os.path.join(DATA, 'recipe', f'{race}_armor_wall.json'), {
            'type': 'minecraft:crafting_shaped', 'pattern': ['###', '###', '###'],
            'key': {'#': {'item': f'starmine:{race}_armor_shard'}}, 'result': {'id': f'starmine:{race}_armor_wall', 'count': 1}})
        name = f'{race}_door'
        for open_ in (False, True):
            suffix = '_open' if open_ else ''
            door(race, open_, rnd).save(os.path.join(tex, name + suffix + '.png'))
            write(os.path.join(ASSETS, 'models', 'block', name + suffix + '.json'),
                  {'parent': 'minecraft:block/cube_all', 'textures': {'all': f'starmine:block/{name}{suffix}'}})
        write(os.path.join(ASSETS, 'blockstates', name + '.json'), {'variants': {
            'open=false': {'model': f'starmine:block/{name}'}, 'open=true': {'model': f'starmine:block/{name}_open'}}})
        write(os.path.join(ASSETS, 'models', 'item', name + '.json'), {'parent': f'starmine:block/{name}'})
        write(os.path.join(DATA, 'loot_table', 'blocks', name + '.json'), loot(name))
        write(os.path.join(DATA, 'recipe', name + '.json'), {
            'type': 'minecraft:crafting_shaped', 'pattern': ['M#M', '#M#', 'M#M'],
            'key': {'#': {'item': f'starmine:{race}_armor_shard'}, 'M': {'item': door_from[race]}}, 'result': {'id': f'starmine:{name}', 'count': 4}})
        pickaxe.append('starmine:' + name)
        diamond.append('starmine:' + name)
        en[f'block.starmine.{name}'] = door_en[race]
        ru[f'block.starmine.{name}'] = door_ru[race]
    tags = os.path.join(ROOT, 'data', 'minecraft', 'tags', 'block')
    for tag, add in (('mineable/pickaxe', pickaxe), ('needs_diamond_tool', diamond)):
        path = os.path.join(tags, tag + '.json')
        values = json.load(io.open(path, encoding='utf8'))['values'] if os.path.exists(path) else []
        for v in add:
            if v not in values: values.append(v)
        write(path, {'replace': False, 'values': values})
    for lang, table in (('en_us', en), ('ru_ru', ru)):
        path = os.path.join(ASSETS, 'lang', lang + '.json')
        text = io.open(path, encoding='utf8', newline='').read()
        nl = '\r\n' if '\r\n' in text else '\n'
        anchor = '\t"message.starmine.boss_phase.3"'
        i = text.index(anchor)
        j = text.index(nl, i) + len(nl)
        lines = ''.join(f'\t"{k}": "{v}",{nl}' for k, v in table.items() if f'"{k}"' not in text)
        text = text[:j] + lines + text[j:]
        json.loads(text)
        io.open(path, 'w', encoding='utf8', newline='').write(text)
    print('ok', len(pickaxe), 'blocks')


def loot(name):
    return {'type': 'minecraft:block', 'pools': [{'rolls': 1, 'entries': [{'type': 'minecraft:item', 'name': f'starmine:{name}'}],
                                                 'conditions': [{'condition': 'minecraft:survives_explosion'}]}]}


if __name__ == '__main__':
    main()
