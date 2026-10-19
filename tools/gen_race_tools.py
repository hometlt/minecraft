"""Owner 19.10: the races' tools (pickaxe, axe, shovel, hoe, sword, repair tool) and shields — textures drawn here (16x16 icons, 64x64
shield sheets in vanilla's shield layout), item models, recipes (the race's armour shards and sticks, as vanilla's iron
tools; a shield of five shards round an iron ingot), the vanilla tool and enchanting tags.
Run from the repository root: python tools/gen_race_tools.py"""
import io
import json
import os

from PIL import Image

ROOT = os.path.join(os.path.dirname(__file__), '..', 'src', 'main', 'resources')
ASSETS = os.path.join(ROOT, 'assets', 'starmine')
DATA = os.path.join(ROOT, 'data', 'starmine')
TAGS = os.path.join(ROOT, 'data', 'minecraft', 'tags', 'item')
RACES = ['zerg', 'terran', 'protoss']
PALETTE = {
    'zerg': {'base': (110, 70, 96), 'dark': (58, 34, 56), 'light': (170, 120, 150), 'glow': (154, 224, 80), 'handle': (90, 60, 50)},
    'terran': {'base': (150, 156, 164), 'dark': (80, 84, 90), 'light': (208, 214, 222), 'glow': (64, 192, 255), 'handle': (70, 74, 80)},
    'protoss': {'base': (220, 180, 64), 'dark': (140, 104, 30), 'light': (246, 234, 206), 'glow': (64, 160, 255), 'handle': (120, 96, 60)},
}
# 16x16 icons: H handle, B head, L head's light edge, D head's dark edge, G the race's glow
SHAPES = {
    'pickaxe': ['................', '....LLLLLLL.....', '...LBBBBBBBD....', '..LBD....HBBD...', '..BD....H..DB...', '..D....H....D...',
                '......H.........', '.....H..........', '....H...........', '...H............', '..H.............', '.H..............',
                '................', '................', '................', '................'],
    'axe': ['................', '......LLL.......', '.....LBBBL......', '....LBBBBBD.....', '....BBBBHBD.....', '.....DBH.D......',
            '......H.........', '.....H..........', '....H...........', '...H............', '..H.............', '.H..............',
            '................', '................', '................', '................'],
    'shovel': ['................', '.........LL.....', '........LBBD....', '........BBBD....', '.........BD.....', '........H.......',
               '.......H........', '......H.........', '.....H..........', '....H...........', '...H............', '..H.............',
               '.H..............', '................', '................', '................'],
    'hoe': ['................', '.....LLLL.......', '....LBBBBD......', '.........HD.....', '........H.......', '.......H........',
            '......H.........', '.....H..........', '....H...........', '...H............', '..H.............', '.H..............',
            '................', '................', '................', '................'],
    'repair_tool': ['................', '................', '...LL......LL...', '..LBBD....LBBD..', '..BDDB....BDDB..', '..LBBL....LBBL..',
                    '...DBB....BBD...', '.....DB..BD.....', '......DGGD......', '......BDDB......', '.....HB..BH.....', '....HH....HH....',
                    '...HH......HH...', '..HH........HH..', '................', '................'],
    'sword': ['................', '.............LL.', '............LGD.', '...........LBD..', '..........LBD...', '.........LBD....',
              '........LBD.....', '.......LBD......', '...D..LBD.......', '....DLBD........', '.....DD.........', '....HDD.........',
              '...H...D........', '..H.............', '.H..............', '................'],
}


def write(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with io.open(path, 'w', encoding='utf8', newline='\n') as f:
        json.dump(obj, f, indent='\t', ensure_ascii=False)
        f.write('\n')


def merge(path, values):
    """Adds values to a tag file, keeping what other generators put there."""
    old = []
    if os.path.exists(path):
        with io.open(path, encoding='utf8') as f:
            old = json.load(f)['values']
    write(path, {'replace': False, 'values': old + [v for v in values if v not in old]})


def icon(race, kind):
    p = PALETTE[race]
    img = Image.new('RGBA', (16, 16), (0, 0, 0, 0))
    colours = {'H': p['handle'], 'B': p['base'], 'L': p['light'], 'D': p['dark'], 'G': p['glow']}
    for y, row in enumerate(SHAPES[kind]):
        for x, ch in enumerate(row):
            if ch in colours:
                img.putpixel((x, y), colours[ch] + (255,))
    # the race's mark on the head: a glowing vein (zerg), a rivet (terran), a crystal (protoss)
    mark = {'pickaxe': (6, 2), 'axe': (6, 3), 'shovel': (9, 2), 'hoe': (6, 2), 'sword': (11, 4), 'repair_tool': (7, 8)}[kind]
    img.putpixel(mark, p['glow'] + (255,))
    return img


def shield(race):
    """Vanilla's shield sheet layout: the plate box (12x22x1) from (0,0), the handle (2x6x6) from (26,0)."""
    p = PALETTE[race]
    img = Image.new('RGBA', (64, 64), (0, 0, 0, 0))
    for y in range(24):
        for x in range(28):
            img.putpixel((x, y), p['dark'] + (255,))
    for y in range(1, 23):  # the front face
        for x in range(1, 13):
            edge = x in (1, 12) or y in (1, 22)
            img.putpixel((x, y), (p['light'] if edge else p['base']) + (255,))
    for y in range(8, 15):  # the race's emblem
        for x in range(5, 9):
            img.putpixel((x, y), p['glow'] + (255,))
    for y in range(12):
        for x in range(26, 42):
            img.putpixel((x, y), p['handle'] + (255,))
    return img


def main():
    shards = lambda race: {'item': 'starmine:%s_armor_shard' % race}
    stick = {'item': 'minecraft:stick'}
    patterns = {'pickaxe': ['###', ' | ', ' | '], 'axe': ['##', '#|', ' |'], 'shovel': ['#', '|', '|'],
                'hoe': ['##', ' |', ' |'], 'sword': ['#', '#', '|'], 'repair_tool': [' #', '# ']}
    tags = {kind: [] for kind in ('pickaxes', 'axes', 'shovels', 'hoes', 'swords')}
    shields = []
    for race in RACES:
        for kind in SHAPES:
            name = '%s_%s' % (race, kind)
            path = os.path.join(ASSETS, 'textures', 'item', name + '.png')
            os.makedirs(os.path.dirname(path), exist_ok=True)
            icon(race, kind).save(path)
            write(os.path.join(ASSETS, 'models', 'item', name + '.json'),
                  {'parent': 'minecraft:item/generated' if kind == 'repair_tool' else 'minecraft:item/handheld', 'textures': {'layer0': 'starmine:item/' + name}})
            write(os.path.join(DATA, 'recipe', name + '.json'), {
                'type': 'minecraft:crafting_shaped', 'category': 'equipment', 'pattern': patterns[kind],
                'key': {'#': shards(race), '|': stick} if '|' in ''.join(patterns[kind]) else {'#': shards(race)},
                'result': {'id': 'starmine:' + name, 'count': 1}})
            if kind + 's' in tags:
                tags[kind + 's'].append('starmine:' + name)
        path = os.path.join(ASSETS, 'textures', 'entity', 'shield', race + '.png')
        os.makedirs(os.path.dirname(path), exist_ok=True)
        shield(race).save(path)
        write(os.path.join(ASSETS, 'models', 'item', race + '_shield.json'), {
            'parent': 'minecraft:item/shield', 'textures': {'particle': 'starmine:item/%s_sword' % race},
            'overrides': [{'predicate': {'blocking': 1}, 'model': 'minecraft:item/shield_blocking'}]})
        write(os.path.join(DATA, 'recipe', race + '_shield.json'), {
            'type': 'minecraft:crafting_shaped', 'category': 'equipment', 'pattern': ['#I#', '###', ' # '],
            'key': {'#': shards(race), 'I': {'item': 'minecraft:iron_ingot'}}, 'result': {'id': 'starmine:%s_shield' % race, 'count': 1}})
        shields.append('starmine:%s_shield' % race)
    for tag, values in ([(t, v) for t, v in tags.items()] + [(os.path.join('enchantable', 'durability'), shields)]):
        merge(os.path.join(TAGS, tag + '.json'), values)
    print('race tools written')


if __name__ == '__main__':
    main()
