// The StarMine export list for Modkit's model installer (survey / fetch-assets / build-assets), kept in the mod's git
// (owner 17.10) instead of the parked copy in the Modkit workspace. The list itself is docs/sc2-models.json, made from
// UnitKind and ModeLook by tools/gen_sc2_models.py; this module only reads it and adds the team colour of each race.
//
//   import { KINDS, teamColor } from '<StarMine>/tools/sc2-export/kinds.mjs';
//   for (const kind of KINDS) ... kind.model, kind.unit, kind.mod, kind.race, kind.death, kind.form, kind.verify
//
// `node tools/sc2-export/kinds.mjs` prints the list (`--json` as JSON) for a quick look.
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const LIST = new URL('../../docs/sc2-models.json', import.meta.url);

/** The team colour each race's models are tinted with (the minimap's colours of the races). */
export const TEAM_COLORS = Object.freeze({
	ZERG: [0xc0, 0x60, 0xff],
	TERRAN: [0x60, 0xa0, 0xff],
	PROTOSS: [0xff, 0xd0, 0x40],
});

const parsed = JSON.parse(readFileSync(LIST, 'utf8'));
if (parsed.schemaVersion !== 1) throw new Error(`sc2-models.json: schema ${parsed.schemaVersion}, expected 1`);

/**
 * Every model StarMine draws: `model` the file name (models/sc2/<model>.smm, and <model>_death.smm when `death`), `kind`
 * its UnitKind (null for the creep tumor), `race`, `unit` the StarCraft II unit to take it from and `mod` the mod or
 * campaign that has it, `form` for a form's own model (ModeLook), `verify` when the source is a guess to check.
 */
export const KINDS = Object.freeze(parsed.models.map((entry) => Object.freeze({ ...entry, teamColor: TEAM_COLORS[entry.race] })));

/** The team colour of a kind's model. */
export function teamColor(kind) {
	return TEAM_COLORS[kind.race];
}

/** Only these models (`ONLY=kerrigan,raynor` as the installer takes it), or all. */
export function selected(only = process.env.ONLY) {
	if (!only) return KINDS;
	const wanted = new Set(only.split(',').map((name) => name.trim()).filter(Boolean));
	return KINDS.filter((kind) => wanted.has(kind.model));
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
	if (process.argv.includes('--json')) console.log(JSON.stringify(selected(), null, '\t'));
	else for (const kind of selected()) console.log(`${kind.model.padEnd(24)} ${kind.race.padEnd(8)} ${kind.mod}:${kind.unit}${kind.verify ? '  (verify)' : ''}`);
}
