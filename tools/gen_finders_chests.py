"""Owner 19.10: the mineral compass and the vespene detector (vanilla's compass needle frames under a rim of their own,
the frames chosen by the needle's angle as vanilla's compass does), the races' chests (orientable cubes) and boats
(vanilla's boat model in the race's stuff) — textures drawn here, models, blockstates, loot, recipes, tags.
Run from the repository root: python tools/gen_finders_chests.py"""
import io
import json
import os

from PIL import Image

ROOT = os.path.join(os.path.dirname(__file__), '..', 'src', 'main', 'resources')
ASSETS = os.path.join(ROOT, 'assets', 'starmine')
DATA = os.path.join(ROOT, 'data', 'starmine')
PALETTE = {
    'zerg': {'base': (58, 34, 56), 'dark': (32, 18, 30), 'light': (110, 70, 96), 'glow': (154, 224, 80)},
    'terran': {'base': (110, 116, 124), 'dark': (60, 64, 70), 'light': (168, 174, 182), 'glow': (64, 192, 255)},
    'protoss': {'base': (200, 160, 48), 'dark': (120, 90, 24), 'light': (236, 224, 196), 'glow': (64, 160, 255)},
}
CHESTS = {'zerg': 'zerg_chest', 'terran': 'terran_safe', 'protoss': 'protoss_chest'}


def write(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with io.open(path, 'w', encoding='utf8', newline='\n') as f:
        json.dump(obj, f, indent='\t', ensure_ascii=False)
        f.write('\n')


def merge(path, values):
    old = []
    if os.path.exists(path):
        with io.open(path, encoding='utf8') as f:
            old = json.load(f)['values']
    write(path, {'replace': False, 'values': old + [v for v in values if v not in old]})


def rim(colour):
    """A ring round the compass face in the finder's colour (over vanilla's compass frames)."""
    img = Image.new('RGBA', (16, 16), (0, 0, 0, 0))
    for y in range(16):
        for x in range(16):
            d = ((x - 7.5) ** 2 + (y - 7.5) ** 2) ** 0.5
            if 6.2 < d < 7.4:
                img.putpixel((x, y), colour + (255,))
    return img


def face(race, part):
    p = PALETTE[race]
    img = Image.new('RGBA', (16, 16))
    for y in range(16):
        for x in range(16):
            edge = x in (0, 15) or y in (0, 15)
            c = p['light'] if edge else p['base']
            if part == 'front' and 6 <= x <= 9 and 6 <= y <= 9:
                c = p['glow'] if 7 <= x <= 8 and 7 <= y <= 8 else p['dark']  # the lock
            if part == 'front' and y == 4 and not edge:
                c = p['dark']  # the lid's seam
            if part == 'top' and (x in (3, 12) or y in (3, 12)) and not edge:
                c = p['dark']
            img.putpixel((x, y), c + (255,))
    return img


def boat_sheet(race):
    """The boat's 128x64 sheet: planks of the race's stuff, its seams darker, a glowing line on the sides."""
    p = PALETTE[race]
    img = Image.new('RGBA', (128, 64))
    for y in range(64):
        for x in range(128):
            c = p['dark'] if y % 6 == 0 else p['base'] if (x // 8 + y // 6) % 2 else p['light']
            if y % 19 == 9:
                c = p['glow']
            img.putpixel((x, y), c + (255,))
    return img


def boat_icon(race):
    p = PALETTE[race]
    img = Image.new('RGBA', (16, 16), (0, 0, 0, 0))
    for y in range(8, 13):
        inset = (y - 8) // 2
        for x in range(1 + inset, 15 - inset):
            c = p['light'] if y == 8 else p['dark'] if y == 12 else p['base']
            img.putpixel((x, y), c + (255,))
    img.putpixel((4, 10), p['glow'] + (255,))
    img.putpixel((11, 10), p['glow'] + (255,))
    return img


def boats():
    for race in PALETTE:
        name = race + '_boat'
        path = os.path.join(ASSETS, 'textures', 'entity', 'boat', race + '.png')
        os.makedirs(os.path.dirname(path), exist_ok=True)
        boat_sheet(race).save(path)
        boat_icon(race).save(os.path.join(ASSETS, 'textures', 'item', name + '.png'))
        write(os.path.join(ASSETS, 'models', 'item', name + '.json'),
              {'parent': 'minecraft:item/generated', 'textures': {'layer0': 'starmine:item/' + name}})
        write(os.path.join(DATA, 'recipe', name + '.json'), {
            'type': 'minecraft:crafting_shaped', 'category': 'misc', 'pattern': ['# #', '###'],
            'key': {'#': {'item': 'starmine:%s_armor_shard' % race}}, 'result': {'id': 'starmine:' + name, 'count': 1}})


def main():
    boats()
    models = os.path.join(ASSETS, 'models')
    for name, colour in (('mineral_compass', (90, 190, 255)), ('vespene_detector', (90, 230, 70))):
        rim(colour).save(os.path.join(ASSETS, 'textures', 'item', name + '_rim.png'))
        overrides = []
        for i in range(32):
            frame = '%02d' % ((16 + i) % 32)
            model = 'starmine:item/%s_%s' % (name, frame)
            write(os.path.join(models, 'item', '%s_%s.json' % (name, frame)), {
                'parent': 'minecraft:item/generated',
                'textures': {'layer0': 'minecraft:item/compass_' + frame, 'layer1': 'starmine:item/%s_rim' % name}})
            overrides.append({'predicate': {'angle': 0 if i == 0 else (i - 0.5) / 32}, 'model': model})
        overrides.append({'predicate': {'angle': 0.984375}, 'model': 'starmine:item/%s_16' % name})
        write(os.path.join(models, 'item', name + '.json'), {
            'parent': 'minecraft:item/generated',
            'textures': {'layer0': 'minecraft:item/compass_16', 'layer1': 'starmine:item/%s_rim' % name}, 'overrides': overrides})
    item = lambda n: {'item': n}
    write(os.path.join(DATA, 'recipe', 'mineral_compass.json'), {
        'type': 'minecraft:crafting_shaped', 'category': 'equipment', 'pattern': [' M ', 'MCM', ' M '],
        'key': {'M': item('starmine:mineral_shard'), 'C': item('minecraft:compass')}, 'result': {'id': 'starmine:mineral_compass', 'count': 1}})
    write(os.path.join(DATA, 'recipe', 'vespene_detector.json'), {
        'type': 'minecraft:crafting_shapeless', 'category': 'equipment',
        'ingredients': [item('minecraft:compass'), item('starmine:vespene_bucket'), item('minecraft:redstone')],
        'result': {'id': 'starmine:vespene_detector', 'count': 1}})

    pickaxe = []
    for race, name in CHESTS.items():
        for part in ('front', 'side', 'top'):
            face(race, part).save(os.path.join(ASSETS, 'textures', 'block', '%s_%s.png' % (name, part)))
        write(os.path.join(models, 'block', name + '.json'), {'parent': 'minecraft:block/orientable', 'textures': {
            'front': 'starmine:block/%s_front' % name, 'side': 'starmine:block/%s_side' % name, 'top': 'starmine:block/%s_top' % name}})
        write(os.path.join(models, 'item', name + '.json'), {'parent': 'starmine:block/' + name})
        write(os.path.join(ASSETS, 'blockstates', name + '.json'), {'variants': {
            'facing=north': {'model': 'starmine:block/' + name}, 'facing=east': {'model': 'starmine:block/' + name, 'y': 90},
            'facing=south': {'model': 'starmine:block/' + name, 'y': 180}, 'facing=west': {'model': 'starmine:block/' + name, 'y': 270}}})
        write(os.path.join(DATA, 'loot_table', 'blocks', name + '.json'), {
            'type': 'minecraft:block', 'pools': [{'rolls': 1, 'entries': [{'type': 'minecraft:item', 'name': 'starmine:' + name}],
                                                  'conditions': [{'condition': 'minecraft:survives_explosion'}]}]})
        pickaxe.append('starmine:' + name)
    shard = lambda race: item('starmine:%s_armor_shard' % race)
    chest = item('minecraft:chest')
    write(os.path.join(DATA, 'recipe', 'zerg_chest.json'), {'type': 'minecraft:crafting_shaped', 'category': 'misc',
          'pattern': ['###', '# #', '###'], 'key': {'#': shard('zerg')}, 'result': {'id': 'starmine:zerg_chest', 'count': 1}})
    write(os.path.join(DATA, 'recipe', 'terran_safe.json'), {'type': 'minecraft:crafting_shaped', 'category': 'misc',
          'pattern': ['###', '#C#', '###'], 'key': {'#': shard('terran'), 'C': chest}, 'result': {'id': 'starmine:terran_safe', 'count': 1}})
    write(os.path.join(DATA, 'recipe', 'protoss_chest.json'), {'type': 'minecraft:crafting_shaped', 'category': 'misc',
          'pattern': ['PMP', 'MCM', 'PMP'], 'key': {'P': shard('protoss'), 'M': item('starmine:mineral_shard'), 'C': chest},
          'result': {'id': 'starmine:protoss_chest', 'count': 1}})
    merge(os.path.join(ROOT, 'data', 'minecraft', 'tags', 'block', 'mineable', 'pickaxe.json'), pickaxe)
    print('finders and chests written')


if __name__ == '__main__':
    main()
