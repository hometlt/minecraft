"""Owner 19.10: crafted blocks and items of the races' resources — the vespene torch (green flame), the vespene lamp,
mineral glass, vespene fuel; the armour wall broken back into shards. Textures drawn here, models, blockstates, loot
tables, recipes, tool tags, the green flame particle.
Run from the repository root: python tools/gen_craft_blocks.py"""
import io
import json
import os
import random

from PIL import Image

ROOT = os.path.join(os.path.dirname(__file__), '..', 'src', 'main', 'resources')
ASSETS = os.path.join(ROOT, 'assets', 'starmine')
DATA = os.path.join(ROOT, 'data', 'starmine')
RACES = ['zerg', 'terran', 'protoss']
GREEN, GREEN_LIGHT, GREEN_DARK = (90, 230, 70), (200, 255, 150), (30, 120, 30)


def write(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with io.open(path, 'w', encoding='utf8', newline='\n') as f:
        json.dump(obj, f, indent='\t', ensure_ascii=False)
        f.write('\n')


def png(image, *parts):
    path = os.path.join(ASSETS, 'textures', *parts) + '.png'
    os.makedirs(os.path.dirname(path), exist_ok=True)
    image.save(path)


def torch():
    """A stick (two pixels wide, rows 6-15 as vanilla's template) with a green flame at its top."""
    img = Image.new('RGBA', (16, 16), (0, 0, 0, 0))
    for y in range(8, 16):
        for x in (7, 8):
            img.putpixel((x, y), (104, 78, 46, 255) if x == 7 else (84, 62, 36, 255))
    for y, colours in ((6, (GREEN_LIGHT, GREEN)), (7, (GREEN, GREEN_DARK))):
        img.putpixel((7, y), colours[0] + (255,))
        img.putpixel((8, y), colours[1] + (255,))
    return img


def lamp(rnd):
    """A frame of iron round panes of green light."""
    img = Image.new('RGBA', (16, 16))
    for y in range(16):
        for x in range(16):
            edge = x in (0, 15) or y in (0, 15)
            cross = x in (7, 8) or y in (7, 8)
            if edge:
                c = (150, 154, 160)
            elif cross:
                c = (118, 122, 128)
            else:
                k = 0.85 + rnd.random() * 0.3
                d = min(abs(x - 3.5), abs(x - 11.5)) + min(abs(y - 3.5), abs(y - 11.5))
                base = GREEN_LIGHT if d < 2 else GREEN
                c = tuple(min(255, int(v * k)) for v in base)
            img.putpixel((x, y), c + (255,))
    return img


def mineral_glass(rnd):
    """Glass with a pale blue tint, a frame of mineral crystal and a few shards in it."""
    img = Image.new('RGBA', (16, 16), (0, 0, 0, 0))
    for y in range(16):
        for x in range(16):
            if x in (0, 15) or y in (0, 15):
                img.putpixel((x, y), (90, 170, 220, 255))
            else:
                img.putpixel((x, y), (150, 220, 255, 60))
    for _ in range(4):
        x, y = rnd.randint(2, 12), rnd.randint(2, 12)
        for i in range(3):
            img.putpixel((x + i, y + i), (190, 240, 255, 150))
    return img


def fuel():
    """The canister's look: a steel can with a green window."""
    img = Image.new('RGBA', (16, 16), (0, 0, 0, 0))
    for y in range(2, 15):
        for x in range(4, 12):
            steel = (120, 126, 134) if x in (4, 11) or y in (2, 14) else (160, 166, 172)
            img.putpixel((x, y), steel + (255,))
    for y in range(5, 12):
        for x in range(6, 10):
            img.putpixel((x, y), (GREEN_LIGHT if y < 7 else GREEN) + (255,))
    for x in range(6, 10):
        img.putpixel((x, 1), (90, 96, 104, 255))
    return img


def flame():
    """The green flame particle (vanilla's flame is an 8x8 drawn on a 16x16 sheet)."""
    img = Image.new('RGBA', (8, 8), (0, 0, 0, 0))
    rows = ['...##...', '..####..', '..#++#..', '.##++##.', '.#++++#.', '.#+**+#.', '.##**##.', '..####..']
    for y, row in enumerate(rows):
        for x, ch in enumerate(row):
            if ch == '#':
                img.putpixel((x, y), GREEN + (255,))
            elif ch == '+':
                img.putpixel((x, y), GREEN_LIGHT + (255,))
            elif ch == '*':
                img.putpixel((x, y), (240, 255, 220, 255))
    return img


def main():
    rnd = random.Random(1910)
    png(torch(), 'block', 'vespene_torch')
    png(lamp(rnd), 'block', 'vespene_lamp')
    png(mineral_glass(rnd), 'block', 'mineral_glass')
    png(fuel(), 'item', 'vespene_fuel')
    png(flame(), 'particle', 'vespene_flame')
    write(os.path.join(ASSETS, 'particles', 'vespene_flame.json'), {'textures': ['starmine:vespene_flame']})

    blockstates = os.path.join(ASSETS, 'blockstates')
    models = os.path.join(ASSETS, 'models')
    write(os.path.join(blockstates, 'vespene_torch.json'), {'variants': {'': {'model': 'starmine:block/vespene_torch'}}})
    write(os.path.join(blockstates, 'vespene_wall_torch.json'), {'variants': {
        'facing=east': {'model': 'starmine:block/vespene_wall_torch'},
        'facing=north': {'model': 'starmine:block/vespene_wall_torch', 'y': 270},
        'facing=south': {'model': 'starmine:block/vespene_wall_torch', 'y': 90},
        'facing=west': {'model': 'starmine:block/vespene_wall_torch', 'y': 180}}})
    write(os.path.join(models, 'block', 'vespene_torch.json'),
          {'parent': 'minecraft:block/template_torch', 'textures': {'torch': 'starmine:block/vespene_torch'}})
    write(os.path.join(models, 'block', 'vespene_wall_torch.json'),
          {'parent': 'minecraft:block/template_torch_wall', 'textures': {'torch': 'starmine:block/vespene_torch'}})
    write(os.path.join(models, 'item', 'vespene_torch.json'),
          {'parent': 'minecraft:item/generated', 'textures': {'layer0': 'starmine:block/vespene_torch'}})
    for cube in ('vespene_lamp', 'mineral_glass'):
        write(os.path.join(blockstates, cube + '.json'), {'variants': {'': {'model': 'starmine:block/' + cube}}})
        write(os.path.join(models, 'block', cube + '.json'), {'parent': 'minecraft:block/cube_all', 'textures': {'all': 'starmine:block/' + cube}})
        write(os.path.join(models, 'item', cube + '.json'), {'parent': 'starmine:block/' + cube})
    write(os.path.join(models, 'item', 'vespene_fuel.json'),
          {'parent': 'minecraft:item/generated', 'textures': {'layer0': 'starmine:item/vespene_fuel'}})

    for block in ('vespene_torch', 'vespene_lamp', 'mineral_glass'):
        write(os.path.join(DATA, 'loot_table', 'blocks', block + '.json'), {
            'type': 'minecraft:block',
            'pools': [{'rolls': 1, 'entries': [{'type': 'minecraft:item', 'name': 'starmine:' + block}],
                       'conditions': [{'condition': 'minecraft:survives_explosion'}]}]})

    recipes = os.path.join(DATA, 'recipe')
    item = lambda name: {'item': name}
    write(os.path.join(recipes, 'vespene_torch.json'), {
        'type': 'minecraft:crafting_shaped', 'pattern': ['V', 'S'],
        'key': {'V': item('starmine:vespene_bucket'), 'S': item('minecraft:stick')},
        'result': {'id': 'starmine:vespene_torch', 'count': 4}})
    write(os.path.join(recipes, 'vespene_lamp.json'), {
        'type': 'minecraft:crafting_shaped', 'pattern': [' I ', 'GVG', ' I '],
        'key': {'I': item('minecraft:iron_ingot'), 'G': item('minecraft:glass'), 'V': item('starmine:vespene_bucket')},
        'result': {'id': 'starmine:vespene_lamp', 'count': 1}})
    write(os.path.join(recipes, 'mineral_glass.json'), {
        'type': 'minecraft:crafting_shaped', 'pattern': [' M ', 'MGM', ' M '],
        'key': {'M': item('starmine:mineral_shard'), 'G': item('minecraft:glass')},
        'result': {'id': 'starmine:mineral_glass', 'count': 4}})
    write(os.path.join(recipes, 'vespene_fuel.json'), {
        'type': 'minecraft:crafting_shapeless',
        'ingredients': [item('starmine:vespene_canister')] + [item('starmine:vespene_bucket')] * 4,
        'result': {'id': 'starmine:vespene_fuel', 'count': 1}})
    for race in RACES:
        write(os.path.join(recipes, race + '_armor_shards_from_wall.json'), {
            'type': 'minecraft:crafting_shapeless', 'ingredients': [item('starmine:%s_armor_wall' % race)],
            'result': {'id': 'starmine:%s_armor_shard' % race, 'count': 9}})

    tag = os.path.join(ROOT, 'data', 'minecraft', 'tags', 'block', 'mineable', 'pickaxe.json')
    with io.open(tag, encoding='utf8') as f:
        pickaxe = json.load(f)
    for block in ('starmine:vespene_lamp', 'starmine:mineral_glass'):
        if block not in pickaxe['values']:
            pickaxe['values'].append(block)
    write(tag, pickaxe)
    print('crafted blocks written')


if __name__ == '__main__':
    main()
