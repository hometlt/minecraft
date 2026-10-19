"""Owner 19.10: the races' building blocks of armour — slab, stairs, wall (`armor_rampart`), fence, trapdoor, porthole,
bars: blockstates and models on vanilla's templates (the armour wall's texture; the trapdoor of the panel's; bars and
porthole drawn here), loot tables (a double slab drops two), recipes, block and item tags.
Run from the repository root: python tools/gen_armor_building.py"""
import io
import json
import os

from PIL import Image

ROOT = os.path.join(os.path.dirname(__file__), '..', 'src', 'main', 'resources')
ASSETS = os.path.join(ROOT, 'assets', 'starmine')
DATA = os.path.join(ROOT, 'data', 'starmine')
RACES = ['zerg', 'terran', 'protoss']
PALETTE = {
    'zerg': {'base': (58, 34, 56), 'dark': (32, 18, 30), 'light': (110, 70, 96), 'glow': (154, 224, 80)},
    'terran': {'base': (110, 116, 124), 'dark': (60, 64, 70), 'light': (168, 174, 182), 'glow': (64, 192, 255)},
    'protoss': {'base': (200, 160, 48), 'dark': (120, 90, 24), 'light': (236, 224, 196), 'glow': (64, 160, 255)},
}
FACING_Y = {'north': 0, 'east': 90, 'south': 180, 'west': 270}


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


def model(name, parent, textures):
    write(os.path.join(ASSETS, 'models', 'block', name + '.json'), {'parent': parent, 'textures': textures})


def state(name, obj):
    write(os.path.join(ASSETS, 'blockstates', name + '.json'), obj)


def item(name, parent=None, layer=None):
    obj = {'parent': parent} if parent else {'parent': 'minecraft:item/generated', 'textures': {'layer0': layer}}
    write(os.path.join(ASSETS, 'models', 'item', name + '.json'), obj)


def variant(model_id, x=0, y=0, uvlock=False):
    v = {'model': model_id}
    if x:
        v['x'] = x
    if y % 360:
        v['y'] = y % 360
    if uvlock:
        v['uvlock'] = True
    return v


def bars_texture(race):
    p = PALETTE[race]
    img = Image.new('RGBA', (16, 16), (0, 0, 0, 0))
    for y in range(16):
        for x in range(16):
            if x in (1, 2, 7, 8, 13, 14) or y in (0, 15):
                light = x in (1, 7, 13) or y == 0
                img.putpixel((x, y), (p['light'] if light else p['base']) + (255,))
    return img


def porthole_texture(race):
    p = PALETTE[race]
    img = Image.new('RGBA', (16, 16), (0, 0, 0, 0))
    for y in range(16):
        for x in range(16):
            d = ((x - 7.5) ** 2 + (y - 7.5) ** 2) ** 0.5
            if d > 6.5 or x in (0, 15) or y in (0, 15):
                img.putpixel((x, y), (p['light'] if d > 7.5 else p['base']) + (255,))
            elif d > 5.5:
                img.putpixel((x, y), p['dark'] + (255,))
            else:
                img.putpixel((x, y), (170, 220, 255, 70 if (x + y) % 5 else 130))
    for (x, y) in ((3, 3), (12, 3), (3, 12), (12, 12)):  # rivets
        img.putpixel((x, y), p['glow'] + (255,))
    return img


def main():
    blocks = {k: [] for k in ('slabs', 'stairs', 'walls', 'fences', 'trapdoors', 'pickaxe', 'diamond')}
    for race in RACES:
        wall_tex = 'starmine:block/%s_armor_wall' % race
        panel_tex = 'starmine:block/%s_armor_panel' % race
        n = lambda kind: '%s_%s' % (race, kind)
        sid = lambda kind: 'starmine:' + n(kind)
        mid = lambda name: 'starmine:block/' + name

        # slab
        slab = n('armor_slab')
        model(slab, 'minecraft:block/slab', {'bottom': wall_tex, 'top': wall_tex, 'side': wall_tex})
        model(slab + '_top', 'minecraft:block/slab_top', {'bottom': wall_tex, 'top': wall_tex, 'side': wall_tex})
        state(slab, {'variants': {'type=bottom': variant(mid(slab)), 'type=top': variant(mid(slab + '_top')),
                                  'type=double': variant(mid(n('armor_wall')))}})
        item(slab, mid(slab))

        # stairs (vanilla's rotations)
        stairs = n('armor_stairs')
        for suffix, parent in (('', 'stairs'), ('_inner', 'inner_stairs'), ('_outer', 'outer_stairs')):
            model(stairs + suffix, 'minecraft:block/' + parent, {'bottom': wall_tex, 'top': wall_tex, 'side': wall_tex})
        variants = {}
        base_y = {'east': 0, 'south': 90, 'west': 180, 'north': 270}
        for facing, by in base_y.items():
            for half in ('bottom', 'top'):
                for shape in ('straight', 'inner_left', 'inner_right', 'outer_left', 'outer_right'):
                    m = mid(stairs + ('_inner' if shape.startswith('inner') else '_outer' if shape.startswith('outer') else ''))
                    y = by
                    if half == 'bottom' and shape.endswith('left'):
                        y = by - 90
                    if half == 'top' and shape.endswith('right'):
                        y = by + 90
                    x = 180 if half == 'top' else 0
                    variants['facing=%s,half=%s,shape=%s' % (facing, half, shape)] = variant(m, x, y, uvlock=bool(x or y % 360))
        state(stairs, {'variants': variants})
        item(stairs, mid(stairs))

        # wall
        rampart = n('armor_rampart')
        model(rampart + '_post', 'minecraft:block/template_wall_post', {'wall': wall_tex})
        model(rampart + '_side', 'minecraft:block/template_wall_side', {'wall': wall_tex})
        model(rampart + '_side_tall', 'minecraft:block/template_wall_side_tall', {'wall': wall_tex})
        model(rampart + '_inventory', 'minecraft:block/wall_inventory', {'wall': wall_tex})
        parts = [{'when': {'up': 'true'}, 'apply': variant(mid(rampart + '_post'))}]
        for d, y in FACING_Y.items():
            parts.append({'when': {d: 'low'}, 'apply': variant(mid(rampart + '_side'), 0, y, True)})
            parts.append({'when': {d: 'tall'}, 'apply': variant(mid(rampart + '_side_tall'), 0, y, True)})
        state(rampart, {'multipart': parts})
        item(rampart, mid(rampart + '_inventory'))

        # fence
        fence = n('armor_fence')
        model(fence + '_post', 'minecraft:block/fence_post', {'texture': wall_tex})
        model(fence + '_side', 'minecraft:block/fence_side', {'texture': wall_tex})
        model(fence + '_inventory', 'minecraft:block/fence_inventory', {'texture': wall_tex})
        parts = [{'apply': variant(mid(fence + '_post'))}]
        for d, y in FACING_Y.items():
            parts.append({'when': {d: 'true'}, 'apply': variant(mid(fence + '_side'), 0, y, True)})
        state(fence, {'multipart': parts})
        item(fence, mid(fence + '_inventory'))

        # trapdoor (orientable)
        trapdoor = n('armor_trapdoor')
        for part in ('bottom', 'top', 'open'):
            model(trapdoor + '_' + part, 'minecraft:block/template_orientable_trapdoor_' + part, {'texture': panel_tex})
        variants = {}
        for facing, y in FACING_Y.items():
            for half in ('bottom', 'top'):
                for opened in ('false', 'true'):
                    if opened == 'true':
                        v = variant(mid(trapdoor + '_open'), 180 if half == 'top' else 0, y + (180 if half == 'top' else 0))
                    else:
                        v = variant(mid(trapdoor + '_' + half), 0, y)
                    variants['facing=%s,half=%s,open=%s' % (facing, half, opened)] = v
        state(trapdoor, {'variants': variants})
        item(trapdoor, mid(trapdoor + '_bottom'))

        # porthole and bars: panes
        for kind, draw in (('porthole', porthole_texture), ('armor_bars', bars_texture)):
            name = n(kind)
            path = os.path.join(ASSETS, 'textures', 'block', name + '.png')
            draw(race).save(path)
            tex = {'pane': mid(name), 'edge': mid(name)}
            for part in ('post', 'side', 'side_alt', 'noside', 'noside_alt'):
                model(name + '_' + part, 'minecraft:block/template_glass_pane_' + part, tex)
            pm = lambda part: mid(name + '_' + part)
            state(name, {'multipart': [
                {'apply': variant(pm('post'))},
                {'when': {'north': 'true'}, 'apply': variant(pm('side'))},
                {'when': {'east': 'true'}, 'apply': variant(pm('side'), 0, 90)},
                {'when': {'south': 'true'}, 'apply': variant(pm('side_alt'))},
                {'when': {'west': 'true'}, 'apply': variant(pm('side_alt'), 0, 90)},
                {'when': {'north': 'false'}, 'apply': variant(pm('noside'))},
                {'when': {'east': 'false'}, 'apply': variant(pm('noside_alt'))},
                {'when': {'south': 'false'}, 'apply': variant(pm('noside_alt'), 0, 90)},
                {'when': {'west': 'false'}, 'apply': variant(pm('noside'), 0, 270)}]})
            item(name, layer=mid(name))

        # loot
        for kind in ('armor_stairs', 'armor_rampart', 'armor_fence', 'armor_trapdoor', 'porthole', 'armor_bars'):
            write(os.path.join(DATA, 'loot_table', 'blocks', n(kind) + '.json'), {
                'type': 'minecraft:block', 'pools': [{'rolls': 1, 'entries': [{'type': 'minecraft:item', 'name': sid(kind)}],
                                                      'conditions': [{'condition': 'minecraft:survives_explosion'}]}]})
        write(os.path.join(DATA, 'loot_table', 'blocks', slab + '.json'), {
            'type': 'minecraft:block', 'pools': [{'rolls': 1, 'entries': [{'type': 'minecraft:item', 'name': sid('armor_slab'), 'functions': [
                {'function': 'minecraft:set_count', 'count': 2, 'add': False,
                 'conditions': [{'condition': 'minecraft:block_state_property', 'block': sid('armor_slab'), 'properties': {'type': 'double'}}]},
                {'function': 'minecraft:explosion_decay'}]}]}]})

        # recipes
        wall = {'item': sid('armor_wall')}
        shard = {'item': 'starmine:%s_armor_shard' % race}
        stick = {'item': 'minecraft:stick'}
        recipe = lambda kind, pattern, key, count: write(os.path.join(DATA, 'recipe', n(kind) + '.json'), {
            'type': 'minecraft:crafting_shaped', 'category': 'building', 'pattern': pattern, 'key': key,
            'result': {'id': sid(kind), 'count': count}})
        recipe('armor_slab', ['###'], {'#': wall}, 6)
        recipe('armor_stairs', ['#  ', '## ', '###'], {'#': wall}, 4)
        recipe('armor_rampart', ['###', '###'], {'#': wall}, 6)
        recipe('armor_fence', ['#|#', '#|#'], {'#': shard, '|': stick}, 3)
        recipe('armor_trapdoor', ['###', '###'], {'#': shard}, 2)
        recipe('porthole', [' # ', '#G#', ' # '], {'#': shard, 'G': {'item': 'minecraft:glass'}}, 1)
        recipe('armor_bars', ['#B#', '###'], {'#': shard, 'B': {'item': 'minecraft:iron_bars'}}, 8)

        blocks['slabs'].append(sid('armor_slab'))
        blocks['stairs'].append(sid('armor_stairs'))
        blocks['walls'].append(sid('armor_rampart'))
        blocks['fences'].append(sid('armor_fence'))
        blocks['trapdoors'].append(sid('armor_trapdoor'))
        for kind in ('armor_slab', 'armor_stairs', 'armor_rampart', 'armor_fence', 'armor_trapdoor', 'porthole', 'armor_bars'):
            blocks['pickaxe'].append(sid(kind))
            blocks['diamond'].append(sid(kind))

    block_tags = os.path.join(ROOT, 'data', 'minecraft', 'tags', 'block')
    item_tags = os.path.join(ROOT, 'data', 'minecraft', 'tags', 'item')
    for tag in ('slabs', 'stairs', 'walls', 'fences', 'trapdoors'):
        merge(os.path.join(block_tags, tag + '.json'), blocks[tag])
        merge(os.path.join(item_tags, tag + '.json'), blocks[tag])
    merge(os.path.join(block_tags, 'mineable', 'pickaxe.json'), blocks['pickaxe'])
    merge(os.path.join(block_tags, 'needs_diamond_tool.json'), blocks['diamond'])
    print('armour building blocks written')


if __name__ == '__main__':
    main()
