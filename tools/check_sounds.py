"""Checks a Modkit-prepared assets folder against the sound events StarMine plays (docs/SC2_EXPORT.md).

Every unit kind has `<kind>.fire` (its weapon), `<kind>.attack` (an attack line), `<kind>.death`,
`<kind>.ambient` (idle lines), `<kind>.hurt`, `<kind>.select` and `<kind>.yes`; every ability has
`ability.<id>`, and the three races have `music.rts.<race>`. Lists the events
sounds.json lacks and its files that are missing:  python tools/check_sounds.py <assets dir>
(the folder that holds assets/starmine/sounds.json, default sc2-assets)."""
import json
import os
import re
import sys

SRC = os.path.join(os.path.dirname(__file__), '..', 'src', 'main', 'java', 'dev', 'starmine')


def events():
    kinds = re.findall(r'^\t([A-Z_]+)\(', open(os.path.join(SRC, 'entity', 'UnitKind.java')).read(), re.M)
    abilities = re.findall(r'^\t[A-Z0-9_]+\(\s*"([a-z0-9_]+)"', open(os.path.join(SRC, 'ability', 'Ability.java')).read(), re.M)
    wanted = []
    for kind in kinds:
        kid = kind.lower().replace('_', '')
        wanted += [kid + '.' + e for e in ('fire', 'attack', 'death', 'ambient', 'hurt', 'select', 'yes')]
    wanted += ['ability.' + a for a in abilities]
    wanted += ['music.rts.' + race for race in ('terran', 'protoss', 'zerg')]
    return wanted


if __name__ == '__main__':
    root = sys.argv[1] if len(sys.argv) > 1 else 'sc2-assets'
    path = os.path.join(root, 'assets', 'starmine', 'sounds.json')
    have = json.load(open(path)) if os.path.exists(path) else {}
    wanted = events()
    missing = [e for e in wanted if e not in have or not have[e].get('sounds')]
    files = []
    for event, spec in have.items():
        for sound in spec.get('sounds', []):
            name = sound if isinstance(sound, str) else sound.get('name', '')
            ns, _, rel = name.partition(':')
            if ns == 'starmine' and not os.path.exists(os.path.join(root, 'assets', 'starmine', 'sounds', rel + '.ogg')):
                files.append(name)
    print(f'{len(wanted) - len(missing)} of {len(wanted)} sound events present; {len(files)} files missing')
    for kind in ('fire', 'ability'):
        group = [e for e in missing if e.endswith('.' + kind) or e.startswith(kind + '.')]
        if group:
            print(f'missing {kind}: {len(group)}, e.g. ' + ', '.join(group[:8]))
    sys.exit(1 if missing or files else 0)
