"""Owner 19.10: the races' rounds (bone and toxic needles, energy charges; gauss rounds of gunpowder too) and turrets
(gauss, needle, photon), the stasis trap and Abathur's toxic nest — textures drawn here, models, blockstates, loot,
recipes.
Run from the repository root: python tools/gen_ammo_turrets.py"""
import io
import json
import os

from PIL import Image

ROOT = os.path.join(os.path.dirname(__file__), '..', 'src', 'main', 'resources')
ASSETS = os.path.join(ROOT, 'assets', 'starmine')
DATA = os.path.join(ROOT, 'data', 'starmine')


def write(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with io.open(path, 'w', encoding='utf8', newline='\n') as f:
        json.dump(obj, f, indent='\t', ensure_ascii=False)
        f.write('\n')


def needle(tip, body):
    img = Image.new('RGBA', (16, 16), (0, 0, 0, 0))
    for i in range(3, 13):
        img.putpixel((i, 15 - i), (body if i < 11 else tip) + (255,))
        if i < 11:
            img.putpixel((i + 1, 15 - i), tuple(int(v * 0.75) for v in body) + (255,))
    return img


def charge():
    img = Image.new('RGBA', (16, 16), (0, 0, 0, 0))
    for y in range(16):
        for x in range(16):
            d = abs(x - 7.5) + abs(y - 7.5)
            if d < 4:
                img.putpixel((x, y), (220, 240, 255, 255))
            elif d < 6:
                img.putpixel((x, y), (64, 160, 255, 255))
            elif d < 7:
                img.putpixel((x, y), (200, 160, 48, 255))
    return img


TURRET = {'gauss': ((110, 116, 124), (168, 174, 182), (64, 192, 255)), 'needle': ((58, 34, 56), (110, 70, 96), (154, 224, 80)),
          'photon': ((200, 160, 48), (236, 224, 196), (64, 160, 255))}


def turret(base, light, glow):
    """A squat base and a head with its barrel."""
    img = Image.new('RGBA', (16, 16), (0, 0, 0, 0))
    for y in range(11, 15):
        for x in range(3, 13):
            img.putpixel((x, y), (light if y == 11 else base) + (255,))
    for y in range(5, 11):
        for x in range(5, 11):
            img.putpixel((x, y), (light if x == 5 or y == 5 else base) + (255,))
    for x in range(10, 15):
        img.putpixel((x, 7), (60, 60, 64, 255))
        img.putpixel((x, 8), (40, 40, 44, 255))
    img.putpixel((7, 7), glow + (255,))
    img.putpixel((8, 8), glow + (255,))
    return img


def traps():
    blocks = os.path.join(ASSETS, 'textures', 'block')
    trap = Image.new('RGBA', (16, 16))
    for y in range(16):
        for x in range(16):
            d = abs(x - 7.5) + abs(y - 7.5)
            c = (64, 160, 255) if d < 3 else (236, 224, 196) if d < 5 else (200, 160, 48) if (x + y) % 4 else (150, 116, 30)
            trap.putpixel((x, y), c + (255,))
    trap.save(os.path.join(blocks, 'stasis_trap.png'))
    field = Image.new('RGBA', (16, 16))
    for y in range(16):
        for x in range(16):
            edge = x in (0, 15) or y in (0, 15)
            field.putpixel((x, y), (120, 200, 255, 200) if edge else (90, 170, 255, 90 if (x * 7 + y * 3) % 11 else 140))
    field.save(os.path.join(blocks, 'stasis_field.png'))
    nest = Image.new('RGBA', (16, 16))
    for y in range(16):
        for x in range(16):
            k = ((x * 13 + y * 7) % 9) / 9
            c = (int(92 + 30 * k), int(70 + 20 * k), int(48 + 10 * k))  # the ground's brown
            if (x * 5 + y * 11) % 17 == 0:
                c = (130, 200, 60)  # the poison in it, a speck here and there
            nest.putpixel((x, y), c + (255,))
    nest.save(os.path.join(blocks, 'toxic_nest.png'))
    states = os.path.join(ASSETS, 'blockstates')
    models = os.path.join(ASSETS, 'models')
    for name in ('stasis_trap', 'stasis_field', 'toxic_nest'):
        write(os.path.join(states, name + '.json'), {'variants': {'': {'model': 'starmine:block/' + name}}})
    write(os.path.join(models, 'block', 'stasis_trap.json'), {'parent': 'minecraft:block/pressure_plate_up', 'textures': {'texture': 'starmine:block/stasis_trap'}})
    write(os.path.join(models, 'block', 'stasis_field.json'), {'parent': 'minecraft:block/cube_all', 'textures': {'all': 'starmine:block/stasis_field'}})
    write(os.path.join(models, 'block', 'toxic_nest.json'), {'parent': 'minecraft:block/carpet', 'textures': {'wool': 'starmine:block/toxic_nest'}})
    for name in ('stasis_trap', 'toxic_nest'):
        write(os.path.join(models, 'item', name + '.json'), {'parent': 'starmine:block/' + name})
        write(os.path.join(DATA, 'loot_table', 'blocks', name + '.json'), {
            'type': 'minecraft:block', 'pools': [{'rolls': 1, 'entries': [{'type': 'minecraft:item', 'name': 'starmine:' + name}],
                                                  'conditions': [{'condition': 'minecraft:survives_explosion'}]}]})
    item = lambda name: {'item': name}
    write(os.path.join(DATA, 'recipe', 'stasis_trap.json'), {
        'type': 'minecraft:crafting_shaped', 'category': 'equipment', 'pattern': [' P ', 'MDM', ' E '],
        'key': {'P': item('starmine:protoss_armor_slab'), 'M': item('starmine:mineral_shard'), 'D': item('minecraft:dispenser'),
                'E': item('minecraft:ender_pearl')}, 'result': {'id': 'starmine:stasis_trap', 'count': 1}})
    write(os.path.join(DATA, 'recipe', 'toxic_nest.json'), {
        'type': 'minecraft:crafting_shapeless', 'category': 'equipment',
        'ingredients': [item('starmine:zerg_armor_shard'), item('minecraft:gunpowder'), item('minecraft:spider_eye'), item('starmine:zerg_flesh')],
        'result': {'id': 'starmine:toxic_nest', 'count': 1}})


def main():
    traps()
    icons = {'bone_needle': needle((255, 255, 240), (226, 218, 190)), 'toxic_needle': needle((150, 240, 60), (190, 210, 120)),
             'energy_charge': charge()}
    for name, img in icons.items():
        path = os.path.join(ASSETS, 'textures', 'item', name + '.png')
        os.makedirs(os.path.dirname(path), exist_ok=True)
        img.save(path)
        write(os.path.join(ASSETS, 'models', 'item', name + '.json'),
              {'parent': 'minecraft:item/generated', 'textures': {'layer0': 'starmine:item/' + name}})
    for kind, colours in TURRET.items():
        name = kind + '_turret'
        turret(*colours).save(os.path.join(ASSETS, 'textures', 'item', name + '.png'))
        write(os.path.join(ASSETS, 'models', 'item', name + '.json'),
              {'parent': 'minecraft:item/generated', 'textures': {'layer0': 'starmine:item/' + name}})
    item = lambda name: {'item': name}
    shaped = lambda name, pattern, key: write(os.path.join(DATA, 'recipe', name + '.json'), {
        'type': 'minecraft:crafting_shaped', 'category': 'equipment', 'pattern': pattern, 'key': key,
        'result': {'id': 'starmine:' + name, 'count': 1}})
    dispenser = item('minecraft:dispenser')
    shaped('gauss_turret', [' P ', 'RDR', ' I '], {'P': item('starmine:terran_armor_slab'), 'R': item('minecraft:redstone'), 'D': dispenser,
                                                   'I': item('minecraft:iron_ingot')})
    # creep is no item: zerg flesh stands for it
    shaped('needle_turret', [' S ', 'BDB', ' F '], {'S': item('starmine:zerg_armor_shard'), 'B': item('minecraft:bone_meal'), 'D': dispenser,
                                                    'F': item('starmine:zerg_flesh')})
    shaped('photon_turret', [' S ', 'MDM', ' V '], {'S': item('starmine:protoss_armor_shard'), 'M': item('starmine:mineral_shard'), 'D': dispenser,
                                                    'V': item('starmine:vespene_bucket')})
    shapeless = lambda name, ingredients, result, count: write(os.path.join(DATA, 'recipe', name + '.json'), {
        'type': 'minecraft:crafting_shapeless', 'category': 'equipment', 'ingredients': ingredients,
        'result': {'id': result, 'count': count}})
    shapeless('bone_needle', [item('minecraft:bone'), item('starmine:zerg_armor_shard')], 'starmine:bone_needle', 8)
    # owner 19.10: four needles, an eye and vespene make four (eight with them would not fit the grid)
    shapeless('toxic_needle', [item('starmine:bone_needle')] * 4 + [item('minecraft:spider_eye'), item('starmine:vespene_bucket')],
              'starmine:toxic_needle', 4)
    # owner 19.10: one recipe for gauss rounds (the old one of two nuggets and a mineral is gone)
    shapeless('gauss_ammo', [item('minecraft:iron_nugget'), item('minecraft:gunpowder'), item('starmine:mineral_shard')],
              'starmine:gauss_ammo', 8)
    shapeless('energy_charge', [item('starmine:mineral_shard'), item('starmine:vespene_bucket'), item('starmine:protoss_armor_shard')],
              'starmine:energy_charge', 8)
    print('ammo written')


if __name__ == '__main__':
    main()
