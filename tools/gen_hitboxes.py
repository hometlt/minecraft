#!/usr/bin/env python3
"""Hit boxes of buildings from their SC2 models (owner 22.10: a building's box was its whole footprint, so a shot or a click
at a unit beside it struck the building). A building's model is drawn stretched over its footprint (UnitRenderer); its box
is the body of that drawn model: across, twice the distance from its middle that 75% of its vertices keep within
(max of |x| and |z|, the stand pose's first frame); up, the height 97% keep under. Writes
src/main/resources/data/starmine/hitboxes.json: {kind id: [across, up]}, both as fractions of the footprint's width.

usage: python tools/gen_hitboxes.py <assets dir: the folder holding assets/starmine/models/sc2>"""
import json, os, re, struct, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MODELS = os.path.join(sys.argv[1], 'assets/starmine/models/sc2')
OUT = os.path.join(ROOT, 'src/main/resources/data/starmine/hitboxes.json')
KINDS = os.path.join(ROOT, 'src/main/java/dev/starmine/entity/UnitKind.java')


def footprints():
    """Kind id -> footprint width (blocks) for the buildings: UnitKind's table, read like gen_guide_book reads it."""
    text = open(KINDS, encoding='utf8').read()
    out = {}
    for m in re.finditer(r'^\t([A-Z_]+)\(\s*(?:Race\.([A-Z]+),\s*)?([^)]*)\)[,;]', text, re.M):
        name, args = m.group(1), [a.strip() for a in m.group(3).split(',')]
        floats = [a for a in args if re.fullmatch(r'[\d.]+f', a)]  # the hitbox's width and height (the model size's)
        if len(floats) < 2: continue
        fly = next(i for i, a in enumerate(args) if a in ('true', 'false'))
        speed = args[fly - 1]  # the speed stands just before `flying` (protoss and heroes carry shields before it)
        out[name.lower().replace('_', '')] = dict(width=float(floats[-2][:-1]), height=float(floats[-1][:-1]),
                                                  structure=name in ('HATCHERY', 'EGG') or float(speed) == 0)
    return out


def posed(path):
    b = open(path, 'rb').read()
    n = struct.unpack('<I', b[4:8])[0]
    h = json.loads(b[8:8 + n])
    body = 8 + n
    clip = next((c for c in h['clips'] if c['name'] == 'stand'), h['clips'][0] if h['clips'] else None)
    if clip is None: return h, []
    pal = struct.unpack_from('<%df' % (h['slots'] * 12), b, body + clip['offset'])
    used = set()
    idx = struct.unpack_from('<%dI' % h['indexCount'], b, body + h['vertexCount'] * 52)
    for part in h['parts']: used.update(idx[part['first']:part['first'] + part['count']])
    out = []
    for i in sorted(used):
        o = body + i * 52
        px, py, pz = struct.unpack_from('<3f', b, o)
        j = struct.unpack_from('<4B', b, o + 32)
        w = struct.unpack_from('<4f', b, o + 36)
        x = y = z = 0.0
        for k in range(4):
            if not w[k]: continue
            m = pal[j[k] * 12:j[k] * 12 + 12]
            x += w[k] * (m[0] * px + m[1] * py + m[2] * pz + m[3])
            y += w[k] * (m[4] * px + m[5] * py + m[6] * pz + m[7])
            z += w[k] * (m[8] * px + m[9] * py + m[10] * pz + m[11])
        out.append((x, y, z))
    return h, out


def pct(values, p):
    values = sorted(values)
    return values[min(len(values) - 1, int(len(values) * p))] if values else 0


def main():
    table = footprints()
    result = {}
    for kid, k in sorted(table.items()):
        if not k.get('structure'): continue
        path = os.path.join(MODELS, kid + '.smm')
        if not os.path.exists(path): continue
        h, verts = posed(path)
        if len(verts) < 20: continue
        size = h['size']
        body = 0.65 if kid == 'pylon' else 1.0  # as UnitRenderer draws a building: its widest side the footprint's width
        reach = pct([max(abs(x / size[0]), abs(z / size[2])) for x, y, z in verts], 0.75) * body
        up = pct([y / max(size[0], size[2]) for x, y, z in verts], 0.97) * body
        across = round(min(1.0, max(0.4, 2 * reach)), 3)
        result[kid] = [across, round(max(0.1, up), 3)]
        print(f'{kid:20s} box: {across:5.3f} of the footprint across, {result[kid][1]:5.3f} of it up')
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, 'w', encoding='utf8', newline='\n') as f:
        json.dump({'schemaVersion': 1, 'boxes': result}, f, indent='\t')
        f.write('\n')
    print(len(result), 'boxes ->', os.path.relpath(OUT, ROOT))


if __name__ == '__main__':
    main()
