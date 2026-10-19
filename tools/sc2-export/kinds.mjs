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

/**
 * The wild races' team colours (dev.starmine.faction.TeamColors), for previews. Owner 19.10: the mod tints each side in its
 * own colour, so a model's textures are exported WITHOUT the team colour baked in, and each texture with team colour
 * gets a mask beside it, `<texture>_team.png`: RGB the diffuse's grey shading, A the team colour mask (SC2's diffuse alpha /
 * the team colour layer). The mod draws the mask over the model in the side's colour (SkinnedModelDrawer.drawTeam).
 */
export const TEAM_COLORS = Object.freeze({
	ZERG: [0x8a, 0x2b, 0xd0],
	TERRAN: [0x20, 0x60, 0xff],
	PROTOSS: [0xeb, 0xd1, 0x29],
});

/** Every exported texture with team colour needs its mask (see TEAM_COLORS). */
export const TEAM_MASK_SUFFIX = '_team';

const parsed = JSON.parse(readFileSync(LIST, 'utf8'));
if (parsed.schemaVersion !== 1) throw new Error(`sc2-models.json: schema ${parsed.schemaVersion}, expected 1`);

/**
 * Every model StarMine draws: `model` the file name (models/sc2/<model>.smm, and <model>_death.smm when `death`), `kind`
 * its UnitKind (null for the creep tumor), `race`, `unit` the StarCraft II unit to take it from and `mod` the mod or
 * campaign that has it, `form` for a form's own model (ModeLook), `verify` when the source is a guess to check,
 * `sc2Model` / `sc2ModelPath` when the unit's actor draws no model: the CModel entry or the file to take instead
 * (`sc2ModelAnims`: its animation files when the entry names none).
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
