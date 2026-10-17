#!/usr/bin/env python3
"""The models StarMine draws, for Modkit's model export: docs/sc2-models.json (read by tools/sc2-export/kinds.mjs).

Every unit kind (UnitKind.java) with its death model, the forms with a look of their own (ModeLook: `<kind>_<form>`),
the creep tumor — each with the StarCraft II unit to take it from and the mod or campaign that has it. `verify: true`
marks a source that is a best guess (heroes outside the multiplayer data) to check against the game's data.
Run after adding a kind or a form: python3 tools/gen_sc2_models.py
"""
import json
import pathlib
import re

ROOT = pathlib.Path(__file__).resolve().parent.parent
KINDS = ROOT / 'src/main/java/dev/starmine/entity/UnitKind.java'
OUT = ROOT / 'docs/sc2-models.json'

MULTI = 'Mods/VoidMulti.SC2Mod'
VOID = 'Campaigns/VoidStory.SC2Campaign'
SWARM = 'Campaigns/SwarmStory.SC2Campaign'
LIBERTY = 'Campaigns/LibertyStory.SC2Campaign'

# kinds whose SC2 unit is not their name in CamelCase: (unit, mod, verify)
SOURCES = {
	'LURKER': ('LurkerMP', MULTI, False),
	'LURKER_DEN': ('LurkerDenMP', MULTI, False),
	'SWARM_HOST': ('SwarmHostMP', MULTI, False),
	'LOCUST': ('LocustMP', MULTI, False),
	'VIKING': ('VikingFighter', MULTI, False),
	'VIKING_ASSAULT': ('VikingAssault', MULTI, False),
	'HELLBAT': ('HellionTank', MULTI, False),
	'WIDOW_MINE': ('WidowMine', MULTI, False),
	'KERRIGAN': ('KerriganVoid', VOID, False),
	'RAYNOR': ('RaynorCommando', VOID, False),
	'ARTANIS': ('ArtanisVoid', VOID, False),
	'ABATHUR': ('Abathur', SWARM, True),
	'SWANN': ('Swann', LIBERTY, True),
	'KARAX': ('KaraxVoid', VOID, True),
	'TOSH': ('Tosh', LIBERTY, True),
	'ZERATUL': ('ZeratulVoid', VOID, True),
	'ZAGARA': ('Zagara', SWARM, True),
	'HORNER': ('HornerBattlecruiser', VOID, True),
	'STUKOV': ('InfestedStukov', VOID, True),
	'SELENDIS': ('SelendisCarrier', VOID, True),
}
# the mod's own (no SC2 model: drawn from its blocks)
OWN = {'ZERG_PLATFORM', 'TERRAN_PLATFORM', 'PROTOSS_PLATFORM'}
# forms with a model of their own (ModeLook `<kind>_<form>`): kind, form, SC2 unit
FORMS = [
	('SPIRE', 'greater', 'GreaterSpire'),
	('COMMAND_CENTER', 'orbital', 'OrbitalCommand'),
	('COMMAND_CENTER', 'planetary', 'PlanetaryFortress'),
	('COMMAND_CENTER', 'airborne', 'CommandCenterFlying'),
	('BARRACKS', 'airborne', 'BarracksFlying'),
	('FACTORY', 'airborne', 'FactoryFlying'),
	('STARPORT', 'airborne', 'StarportFlying'),
	('SUPPLY_DEPOT', 'lowered', 'SupplyDepotLowered'),
	('GATEWAY', 'warpgate', 'WarpGate'),
	('SIEGE_TANK', 'sieged', 'SiegeTankSieged'),
	('LIBERATOR', 'defender', 'LiberatorAG'),
	('WARP_PRISM', 'phasing', 'WarpPrismPhasing'),
	('THOR', 'high_impact', 'ThorAP'),
] + [(kind, 'burrowed', unit) for kind, unit in [
	('DRONE', 'DroneBurrowed'), ('ZERGLING', 'ZerglingBurrowed'), ('BANELING', 'BanelingBurrowed'), ('ROACH', 'RoachBurrowed'),
	('RAVAGER', 'RavagerBurrowed'), ('HYDRALISK', 'HydraliskBurrowed'), ('LURKER', 'LurkerMPBurrowed'), ('QUEEN', 'QueenBurrowed'),
	('INFESTOR', 'InfestorBurrowed'), ('SWARM_HOST', 'SwarmHostBurrowedMP'), ('WIDOW_MINE', 'WidowMineBurrowed'),
	('ULTRALISK', 'UltraliskBurrowed')]]


def kinds():
	text = KINDS.read_text(encoding='utf-8')
	body = text[text.index('public enum UnitKind'):]
	body = body[:body.index(';\n')]
	# the constants with their race: the first argument when it is one (`Race.TERRAN`), else the zerg
	return [(name, race or 'ZERG') for name, race in re.findall(r'^\t([A-Z_]+)\(\s*(?:Race\.([A-Z]+))?', body, re.M)]


def camel(name):
	return ''.join(part.capitalize() for part in name.split('_'))


def ident(name):
	return name.lower().replace('_', '')


def main():
	all_kinds = kinds()
	models = []
	races = dict(all_kinds)
	all_kinds = [name for name, _ in all_kinds]
	for kind in all_kinds:
		if kind in OWN:
			continue
		unit, mod, verify = SOURCES.get(kind, (camel(kind), MULTI, False))
		entry = {'model': ident(kind), 'kind': kind, 'race': races[kind], 'unit': unit, 'mod': mod, 'death': True}
		if verify:
			entry['verify'] = True
		models.append(entry)
	for kind, form, unit in FORMS:
		assert kind in all_kinds, kind
		models.append({'model': ident(kind) + '_' + form, 'kind': kind, 'race': races[kind], 'form': form, 'unit': unit, 'mod': MULTI, 'death': False})
	models.append({'model': 'creeptumor', 'kind': None, 'race': 'ZERG', 'unit': 'CreepTumorBurrowed', 'mod': MULTI, 'death': False})
	OUT.write_text(json.dumps({'schemaVersion': 1, 'models': models}, indent='\t', ensure_ascii=False) + '\n', encoding='utf-8')
	print(f'{len(models)} models -> {OUT.relative_to(ROOT)}')


if __name__ == '__main__':
	main()
