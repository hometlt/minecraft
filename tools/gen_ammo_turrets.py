"""Owner 19.10: the races' rounds (bone and toxic needles, energy charges; gauss rounds of gunpowder too) and turrets
(gauss, needle, photon) — icons drawn here, item models, recipes.
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


def main():
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
    # eight needles, an eye and vespene are ten: the grid holds nine — seven needles make seven
    shapeless('toxic_needle', [item('starmine:bone_needle')] * 7 + [item('minecraft:spider_eye'), item('starmine:vespene_bucket')],
              'starmine:toxic_needle', 7)
    shapeless('gauss_ammo_from_gunpowder', [item('minecraft:iron_nugget'), item('minecraft:gunpowder'), item('starmine:mineral_shard')],
              'starmine:gauss_ammo', 8)
    shapeless('energy_charge', [item('starmine:mineral_shard'), item('starmine:vespene_bucket'), item('starmine:protoss_armor_shard')],
              'starmine:energy_charge', 8)
    print('ammo written')


if __name__ == '__main__':
    main()
