"""Owner 19.10: the races' rounds (bone and toxic needles, energy charges; gauss rounds of gunpowder too) — icons drawn
here, item models, recipes.
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


def main():
    icons = {'bone_needle': needle((255, 255, 240), (226, 218, 190)), 'toxic_needle': needle((150, 240, 60), (190, 210, 120)),
             'energy_charge': charge()}
    for name, img in icons.items():
        path = os.path.join(ASSETS, 'textures', 'item', name + '.png')
        os.makedirs(os.path.dirname(path), exist_ok=True)
        img.save(path)
        write(os.path.join(ASSETS, 'models', 'item', name + '.json'),
              {'parent': 'minecraft:item/generated', 'textures': {'layer0': 'starmine:item/' + name}})
    item = lambda name: {'item': name}
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
