"""Writes the crafting recipes of the ability modules (owner 16.10): 3 armor shards of the ability's race,
2 mineral shards and a vespene bucket (the bucket comes back). Reads the abilities' users from
Ability.java and the races from UnitKind.java:  python tools/gen_module_recipes.py"""
import json
import os
import re

SRC = os.path.join(os.path.dirname(__file__), '..', 'src', 'main', 'java', 'dev', 'starmine')
OUT = os.path.join(os.path.dirname(__file__), '..', 'src', 'main', 'resources', 'data', 'starmine', 'recipe')

kinds = re.findall(r'^\t([A-Z_]+)\(', open(os.path.join(SRC, 'entity', 'UnitKind.java')).read(), re.M)
race = {}
current = 'zerg'
for kind in kinds:
    if kind == 'SCV':
        current = 'terran'
    if kind == 'PROBE':
        current = 'protoss'
    race[kind] = current
race.update({'KERRIGAN': 'zerg', 'RAYNOR': 'terran', 'ARTANIS': 'protoss'})

ability = open(os.path.join(SRC, 'ability', 'Ability.java')).read()
ids = dict(re.findall(r'^\t([A-Z0-9_]+)\(\s*"([a-z0-9_]+)"', ability, re.M))
users = {}
for cases, first in re.findall(r'case ([A-Z0-9_, ]+) -> List\.of\(([A-Z_]+)', ability):
    for name in cases.split(','):
        users[name.strip()] = first
count = 0
for name, ident in ids.items():
    if name not in users:
        continue
    r = race[users[name]]
    recipe = {'type': 'minecraft:crafting_shapeless', 'category': 'misc',
              'ingredients': [{'item': 'starmine:%s_armor_shard' % r}] * 3 + [{'item': 'starmine:mineral_shard'}] * 2
              + [{'item': 'starmine:vespene_bucket'}],
              'result': {'id': 'starmine:module_' + ident, 'count': 1}}
    with open(os.path.join(OUT, 'module_' + ident + '.json'), 'w') as f:
        json.dump(recipe, f, indent='\t')
        f.write('\n')
    count += 1
print(count, 'module recipes')
