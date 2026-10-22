"""Void crystal building parts, guaranteed crystal drops, redstone amplification and portal surface. Run from repo root."""
import json
import math
from pathlib import Path
from PIL import Image

ROOT = Path(__file__).resolve().parents[1] / 'src/main/resources'
A = ROOT / 'assets/starmine'
D = ROOT / 'data/starmine'

def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent='\t') + '\n', encoding='utf-8', newline='\n')

def recipe(name, pattern, key, result, count=1):
    write(D / f'recipe/{name}.json', {'type': 'minecraft:crafting_shaped', 'category': 'building', 'pattern': pattern,
          'key': {k: {'item': v} for k, v in key.items()}, 'result': {'id': result, 'count': count}})

def merge_tag(name, ids):
    path = ROOT / f'data/minecraft/tags/block/{name}.json'
    old = json.loads(path.read_text()) if path.exists() else {'replace': False, 'values': []}
    old['values'] += [i for i in ids if i not in old['values']]
    write(path, old)


def main():
    names = ['void_crystal_block', 'void_crystal_slab', 'void_crystal_stairs', 'void_crystal_wall', 'chiseled_void_crystal', 'void_lantern']
    for part, source in [('slab', 'slab'), ('stairs', 'stairs'), ('wall', 'rampart')]:
        name = f'void_crystal_{part}'
        old = f'terran_armor_{source}'
        paths = [A / f'blockstates/{old}.json', A / f'models/item/{old}.json', *sorted((A / 'models/block').glob(old + '*.json'))]
        for path in paths:
            content = path.read_text().replace(old, name).replace('starmine:block/terran_armor_wall', 'starmine:block/void_crystal')
            write(path.with_name(path.name.replace(old, name)), json.loads(content))
    for name in ['void_crystal_block', 'chiseled_void_crystal']:
        write(A / f'blockstates/{name}.json', {'variants': {'': {'model': f'starmine:block/{name}'}}})
        write(A / f'models/block/{name}.json', {'parent': 'minecraft:block/cube_all', 'textures': {'all': 'starmine:block/' + ('void_crystal' if name.endswith('block') else name)}})
        write(A / f'models/item/{name}.json', {'parent': f'starmine:block/{name}'})
    write(A / 'blockstates/void_lantern.json', {'variants': {'hanging=false': {'model': 'starmine:block/void_lantern'}, 'hanging=true': {'model': 'starmine:block/void_lantern_hanging'}}})
    for suffix, parent in [('', 'template_lantern'), ('_hanging', 'template_hanging_lantern')]:
        write(A / f'models/block/void_lantern{suffix}.json', {'parent': 'minecraft:block/' + parent, 'textures': {'lantern': 'starmine:block/void_crystal'}})
    write(A / 'models/item/void_lantern.json', {'parent': 'starmine:block/void_lantern'})
    for name in names:
        functions = []
        if name.endswith('_slab'):
            functions = [{'function': 'minecraft:set_count', 'count': 2, 'conditions': [{'condition': 'minecraft:block_state_property', 'block': f'starmine:{name}', 'properties': {'type': 'double'}}]}]
        write(D / f'loot_table/blocks/{name}.json', {'type': 'minecraft:block', 'pools': [{'rolls': 1, 'entries': [{'type': 'minecraft:item', 'name': f'starmine:{name}', 'functions': functions}], 'conditions': [{'condition': 'minecraft:survives_explosion'}]}]})
    crystal, block = 'starmine:void_shard', 'starmine:void_crystal_block'
    recipe('void_crystal_block', ['CCC', 'CCC', 'CCC'], {'C': crystal}, block)
    write(D / 'recipe/void_crystals_from_block.json', {'type': 'minecraft:crafting_shapeless', 'category': 'misc', 'ingredients': [{'item': block}], 'result': {'id': crystal, 'count': 9}})
    recipe('void_crystal_slab', ['BBB'], {'B': block}, 'starmine:void_crystal_slab', 6)
    recipe('void_crystal_stairs', ['B  ', 'BB ', 'BBB'], {'B': block}, 'starmine:void_crystal_stairs', 4)
    recipe('void_crystal_wall', ['BBB', 'BBB'], {'B': block}, 'starmine:void_crystal_wall', 6)
    recipe('chiseled_void_crystal', ['B', 'B'], {'B': 'starmine:void_crystal_slab'}, 'starmine:chiseled_void_crystal')
    recipe('void_lantern', ['III', 'ICI', 'III'], {'I': 'minecraft:iron_nugget', 'C': crystal}, 'starmine:void_lantern')
    write(D / 'recipe/void_redstone.json', {'type': 'minecraft:crafting_shapeless', 'category': 'redstone', 'ingredients': [{'item': 'minecraft:redstone'}, {'item': crystal}], 'result': {'id': 'minecraft:redstone', 'count': 4}})
    for name, count in [('void_crystal_slab', 2), ('void_crystal_stairs', 1), ('void_crystal_wall', 1), ('chiseled_void_crystal', 1)]:
        write(D / f'recipe/{name}_stonecutting.json', {'type': 'minecraft:stonecutting', 'ingredient': {'item': block}, 'result': {'id': f'starmine:{name}', 'count': count}})
    # Retain the old block id for existing worlds, but stop crafting the obsolete instant-travel gate.
    old_recipe = D / 'recipe/void_gate.json'
    if old_recipe.exists():
        old_recipe.unlink()
    write(D / 'loot_table/blocks/void_crystal.json', {'type': 'minecraft:block', 'pools': [{'rolls': 1, 'entries': [{'type': 'minecraft:item', 'name': crystal, 'functions': [{'function': 'minecraft:set_count', 'count': {'type': 'minecraft:uniform', 'min': 2, 'max': 4}}]}], 'conditions': [{'condition': 'minecraft:survives_explosion'}]}]})
    merge_tag('mineable/pickaxe', [f'starmine:{name}' for name in names])
    for tag, name in [('slabs', 'void_crystal_slab'), ('stairs', 'void_crystal_stairs'), ('walls', 'void_crystal_wall')]:
        merge_tag(tag, ['starmine:' + name])
        write(ROOT / f'data/minecraft/tags/item/{tag}.json', merge_item_tag(tag, 'starmine:' + name))
    image = Image.new('RGBA', (64, 64))
    for y in range(64):
        for x in range(64):
            v = int(65 + 160 * (0.5 + 0.5 * math.sin(x * math.pi / 8 + math.sin(y * math.pi / 16) * 3)) ** 3)
            image.putpixel((x, y), (v, v, v, 235))
    image.save(A / 'textures/entity/void_portal_surface.png')
    carved = Image.new('RGBA', (16, 16))
    for y in range(16):
        for x in range(16):
            ring = max(abs(x - 7.5), abs(y - 7.5))
            carved.putpixel((x, y), (115, 55, 170, 255) if int(ring) % 3 else (190, 125, 245, 255))
    carved.save(A / 'textures/block/chiseled_void_crystal.png')
    translations = {
        'en_us': ['Void Crystal Block', 'Void Crystal Slab', 'Void Crystal Stairs', 'Void Crystal Wall', 'Chiseled Void Crystal', 'Void Lantern'],
        'ru_ru': ['Блок кристалла Пустоты', 'Плита из кристалла Пустоты', 'Ступени из кристалла Пустоты', 'Ограда из кристалла Пустоты', 'Резной кристалл Пустоты', 'Фонарь Пустоты']}
    for lang, labels in translations.items():
        path = A / f'lang/{lang}.json'
        data = json.loads(path.read_text(encoding='utf-8'))
        data.update({f'block.starmine.{name}': label for name, label in zip(names, labels)})
        data['item.starmine.void_shard'] = 'Кристалл Пустоты' if lang == 'ru_ru' else 'Void Crystal'
        data['message.starmine.gate_pattern'] = ('Поставьте камень мира в центр и 1–4 блока кристалла в каждую из 6 сторон. Лучи должны быть одинаковыми; огниво активирует портал. Нужны свободное место и другое измерение.' if lang == 'ru_ru' else 'Place a world stone in the centre and 1–4 crystal blocks in each of six directions. Equal arms, open space and another destination are required; light with flint and steel.')
        data['entity.starmine.player_void_gate'] = 'Портал' if lang == 'ru_ru' else 'Portal'
        write(path, data)
    print('Void crystal resources generated')


def merge_item_tag(tag, name):
    path = ROOT / f'data/minecraft/tags/item/{tag}.json'
    old = json.loads(path.read_text()) if path.exists() else {'replace': False, 'values': []}
    if name not in old['values']:
        old['values'].append(name)
    return old


if __name__ == '__main__':
    main()

