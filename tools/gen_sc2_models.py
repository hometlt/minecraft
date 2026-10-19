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

# kinds whose SC2 unit is not their name in CamelCase: (unit, mod, verify). Heroes checked 17.10 against the
# campaigns' Data (actor model found in the installed game); Abathur, Swann and Horner take a model named in MODELS.
SOURCES = {
	'SCV': ('SCV', MULTI, False),
	'TEMPLAR_ARCHIVES': ('TemplarArchive', MULTI, False),
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
	'ABATHUR': ('Abathur', SWARM, False),
	'SWANN': ('Swann', VOID, False),
	'KARAX': ('KaraxChampion', VOID, False),
	'TOSH': ('Tosh', LIBERTY, False),
	'ZERATUL': ('ZeratulVoid', VOID, False),
	'ZAGARA': ('ZaGara', SWARM, False),
	'HORNER': ('Horner', VOID, False),
	'STUKOV': ('InfestedStukov', VOID, False),
	'SELENDIS': ('Selendis', VOID, False),
	'XELNAGA_CONSTRUCT': ('XelNagaConstructMini', VOID, True),
	'XELNAGA_SENTINEL': ('XelNagaConstructForm', VOID, True),
	'VOID_MONOLITH': ('MonolithCannon', VOID, True),
	'INFESTED_TERRAN': ('InfestedTerran', VOID, True),
	'BROOD_MOTHER': ('BroodMother', VOID, True),
	'HYBRID': ('HybridExperimented', VOID, True),
}
# kinds whose unit draws no model of its own in the game's data (checked 18.10): the model to take instead,
# a CModel entry of the mod (`sc2Model`) or a file (`sc2ModelPath`), with animation files (`sc2ModelAnims`) the entry lacks. Swann: his Model `Swann` (the armed Swann of
# the campaign, MarauderSwann.m3); Horner: the Hyperion's first officer of
# the story mode (SMX3_FirstOfficer; CivilianHorner's older SM_FirstOfficer has an animation file too big to convert); Abathur
# never walks the field in SC2, the closest is his 3D portrait (the co-op one, kept in Base.SC2Assets).
MODELS = {
	'SWANN': {'sc2Model': 'Swann'},
	'HORNER': {'sc2ModelPath': r'Assets\StoryModeCharacters\Terran\SMX3_FirstOfficer\SMX3_FirstOfficer.m3',
		'sc2ModelAnims': [r'Assets\StoryModeCharacters\Terran\SMX3_FirstOfficer_RequiredAnims\SMX3_FirstOfficer_RequiredAnims.m3a']},
	'ABATHUR': {'sc2ModelPath': 'Assets\\Portraits\\Zerg\\AbathurEx3_Portrait\\AbathurEx3_Portrait.m3'},
	# owner 19.10: the xel'naga temples' keepers, Zeratul's co-op avatars and void monolith (Base.SC2Assets; no campaign unit draws them)
	'XELNAGA_CONSTRUCT': {'sc2ModelPath': 'Assets\\Coop\\Zeratul\\Units\\Construct_Mini_Zeratul_Coop\\Construct_Mini_Zeratul_Coop.m3'},
	'XELNAGA_SENTINEL': {'sc2ModelPath': 'Assets\\Coop\\Zeratul\\Units\\Construct_Form_Zeratul_Coop\\Construct_Form_Zeratul_Coop.m3'},
	'VOID_MONOLITH': {'sc2ModelPath': 'Assets\\Coop\\Zeratul\\Buildings\\MonolithCannon_Zeratul_Coop\\MonolithCannon_Zeratul_Coop.m3'},
	# owner 19.10: the story dungeons' dwellers: the infested marine, the expedition queen (the brood mother), Moebius's experimented hybrid
	'INFESTED_TERRAN': {'sc2ModelPath': 'Assets\\Units\\Zerg\\InfestedMarine\\InfestedMarine.m3'},
	'BROOD_MOTHER': {'sc2ModelPath': 'Assets\\Units\\Zerg\\ExpeditionQueenLevel3\\ExpeditionQueenLevel3.m3'},
	'HYBRID': {'sc2ModelPath': 'Assets\\Units\\Protoss\\Hybrid_ProtossAdvanced_Experimented\\Hybrid_ProtossAdvanced_Experimented.m3'},
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
	('SPINE_CRAWLER', 'uprooted', 'SpineCrawlerUprooted'),
	('SPORE_CRAWLER', 'uprooted', 'SporeCrawlerUprooted'),
	('OVERSEER', 'oversight', 'OverseerSiegeMode'),
	('OBSERVER', 'surveillance', 'ObserverSiegeMode'),
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
		entry = {'model': ident(kind), 'kind': kind, 'race': races[kind], 'unit': unit, 'mod': mod, 'death': kind not in MODELS, **MODELS.get(kind, {})}
		if verify:
			entry['verify'] = True
		models.append(entry)
	for kind, form, unit in FORMS:
		assert kind in all_kinds, kind
		models.append({'model': ident(kind) + '_' + form, 'kind': kind, 'race': races[kind], 'form': form, 'unit': unit, 'mod': MULTI, 'death': False})
	models.append({'model': 'creeptumor', 'kind': None, 'race': 'ZERG', 'unit': 'CreepTumorBurrowed', 'mod': MULTI, 'death': False})
	# the Void's foes (world/Amun, VoidShard, VoidRift: not unit kinds), from the Legacy of the Void epilogue
	for model, unit, sc2_model in [('amun', 'AmonEpilogue03Unit', 'Amon'), ('voidshard', 'AmonUmbraSpireEpilogue03', 'Void_ShardFortress'),
			('voidrift', 'VoidRift', 'VoidRift')]:
		models.append({'model': model, 'kind': None, 'race': 'PROTOSS', 'unit': unit, 'mod': VOID, 'death': False, 'sc2Model': sc2_model})
	# owner 19.10: the traders (gear/Townsfolk, not unit kinds): the protoss preserver of the temples, Dehaka in his cave
	for model, race, path in [('protosstrader', 'PROTOSS', 'Assets\\Units\\Protoss\\Preserver\\Preserver_00.m3'),
			('dehaka', 'ZERG', 'Assets\\Units\\Zerg\\Dehaka\\Dehaka.m3')]:
		models.append({'model': model, 'kind': None, 'race': race, 'unit': model.capitalize(), 'mod': VOID, 'death': False, 'sc2ModelPath': path})
	OUT.write_text(json.dumps({'schemaVersion': 1, 'models': models}, indent='\t', ensure_ascii=False) + '\n', encoding='utf-8')
	print(f'{len(models)} models -> {OUT.relative_to(ROOT)}')


if __name__ == '__main__':
	main()
