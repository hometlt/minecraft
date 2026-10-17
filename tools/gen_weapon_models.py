"""Builds StarMine's weapon models (owner 16.10: chunky industrial guns, in the manner of Immersive Engineering)
and their textures. Own art, not SC2's.

Each family is a 3D item model of boxes, held like a gun: its grip at the bottom, its muzzle up (+y), which
the hand turns forward. `module_weapon.json` picks the family by the `starmine:weapon` item property
(dev.starmine.possess.WeaponLook). Needs Pillow:  python tools/gen_weapon_models.py
"""
import json
import os
import random

from PIL import Image

ROOT = os.path.join(os.path.dirname(__file__), '..', 'src', 'main', 'resources', 'assets', 'starmine')
MODELS = os.path.join(ROOT, 'models', 'item')
TEXTURES = os.path.join(ROOT, 'textures', 'item', 'weapon')

# in the order of dev.starmine.possess.WeaponLook
FAMILIES = ['gauss_rifle', 'launcher', 'sniper', 'flamer', 'cannon', 'tool', 'psi_blade', 'disruptor', 'claws', 'spitter', 'pistol']

PALETTE = {
    'steel': ((120, 126, 134), (170, 176, 184)),
    'dark': ((44, 46, 52), (72, 76, 84)),
    'brass': ((150, 110, 40), (210, 170, 80)),
    'wood': ((90, 58, 32), (130, 88, 50)),
    'blue': ((40, 120, 230), (170, 230, 255)),
    'gold': ((190, 150, 50), (250, 220, 120)),
    'violet': ((110, 40, 160), (200, 130, 240)),
    'flame': ((200, 70, 10), (255, 200, 60)),
    'bone': ((200, 190, 160), (240, 234, 214)),
    'flesh': ((110, 30, 50), (170, 70, 80)),
    'green': ((60, 140, 40), (140, 230, 80)),
}


def texture(name, rnd):
    dark, light = PALETTE[name]
    image = Image.new('RGBA', (16, 16))
    for y in range(16):
        for x in range(16):
            t = rnd.random() * 0.35 + (0.3 if (x + y) % 8 == 0 else 0) + (0.25 if y in (0, 15) or x in (0, 15) else 0)
            image.putpixel((x, y), tuple(int(dark[i] + (light[i] - dark[i]) * min(1, t)) for i in range(3)) + (255,))
    image.save(os.path.join(TEXTURES, name + '.png'))


def box(frm, to, tex, rotate=None):
    faces = {}
    sx, sy, sz = (to[i] - frm[i] for i in range(3))
    def uv(a, b):
        return [0, 0, max(1, min(16, a)), max(1, min(16, b))]
    for face, dims in (('north', (sx, sy)), ('south', (sx, sy)), ('east', (sz, sy)), ('west', (sz, sy)), ('up', (sx, sz)), ('down', (sx, sz))):
        faces[face] = {'uv': uv(*dims), 'texture': '#' + tex}
    element = {'from': list(frm), 'to': list(to), 'faces': faces}
    if rotate:
        element['rotation'] = rotate
    return element


def ring(y, r, tex, cx=8, cz=8, h=1):
    return box([cx - r, y, cz - r], [cx + r, y + h, cz + r], tex)


def gauss_rifle():
    e = [box([7, 0, 7.5], [9, 5, 10], 'wood'),           # grip
         box([6.5, 4, 6.5], [9.5, 11, 10.5], 'dark'),     # receiver
         box([7, 2, 10.5], [9, 6, 12], 'steel'),          # magazine
         box([7.5, 11, 7.5], [8.5, 22, 8.5], 'steel')]    # barrel
    e += [ring(y, 1.2, 'brass') for y in (12, 14.5, 17, 19.5)]  # the gauss coils
    e.append(box([7.3, 22, 7.3], [8.7, 23.5, 8.7], 'dark'))       # muzzle
    return e, ['wood', 'dark', 'steel', 'brass']


def launcher():
    e = [box([7, 0, 9], [9, 5, 11], 'dark'),
         box([6, 4, 6], [10, 9, 10], 'steel'),
         box([5.5, 8, 5.5], [10.5, 20, 10.5], 'dark'),    # the tube
         ring(9, 2.8, 'brass'), ring(18, 2.8, 'brass'),
         box([6.5, 20, 6.5], [9.5, 21, 9.5], 'flame')]    # a warhead's glint
    return e, ['dark', 'steel', 'brass', 'flame']


def sniper():
    e = [box([7.2, 0, 8], [8.8, 6, 10.5], 'wood'),
         box([7, 5, 7], [9, 12, 10], 'dark'),
         box([7.6, 12, 7.6], [8.4, 26, 8.4], 'steel'),     # long thin barrel
         box([7.5, 8, 5], [8.5, 13, 6.5], 'blue'),         # the scope
         box([7.3, 26, 7.3], [8.7, 27.5, 8.7], 'dark')]
    return e, ['wood', 'dark', 'steel', 'blue']


def flamer():
    e = [box([7, 0, 7.5], [9, 5, 10], 'dark'),
         box([6.5, 4, 6.5], [9.5, 10, 10.5], 'steel'),
         box([9.5, 3, 7], [12.5, 11, 10], 'flame'),        # the fuel tank
         box([7.4, 10, 7.4], [8.6, 18, 8.6], 'brass'),
         box([7, 18, 7], [9, 19.5, 9], 'flame')]
    return e, ['dark', 'steel', 'flame', 'brass']


def cannon():
    e = [box([6.5, 0, 6.5], [9.5, 4, 9.5], 'dark'),
         box([5, 4, 5], [11, 11, 11], 'steel'),
         box([6.5, 11, 6.5], [9.5, 22, 9.5], 'dark'),
         ring(11, 3.5, 'brass', h=1.5), ring(21, 2, 'brass')]
    return e, ['dark', 'steel', 'brass']


def tool():
    e = [box([7, 0, 7], [9, 7, 9], 'dark'),
         box([6, 7, 6], [10, 11, 10], 'brass'),
         box([7.5, 11, 7.5], [8.5, 15, 8.5], 'steel'),
         box([7.2, 15, 7.2], [8.8, 16, 8.8], 'blue')]      # the welder's tip
    return e, ['dark', 'brass', 'steel', 'blue']


def psi_blade():
    e = [box([6, 0, 7], [10, 5, 9], 'gold'),              # the gauntlet
         box([5.5, 4, 6.5], [10.5, 6, 9.5], 'gold'),
         box([7.6, 6, 7.2], [8.4, 22, 8.8], 'blue', {'origin': [8, 6, 8], 'axis': 'z', 'angle': -22.5})]  # the psi blade
    return e, ['gold', 'blue']


def disruptor():
    e = [box([6.5, 0, 7], [9.5, 5, 9.5], 'gold'),
         box([6, 5, 6], [10, 11, 10], 'gold'),
         box([7, 11, 7], [9, 18, 9], 'violet'),
         ring(13, 2.2, 'blue'), ring(16, 1.8, 'blue')]
    return e, ['gold', 'violet', 'blue']


def claws():
    e = [box([6, 0, 6], [10, 6, 10], 'flesh')]
    for i, x in enumerate((6.2, 7.6, 9.0)):
        e.append(box([x, 6, 7.5], [x + 0.8, 18 - i, 8.5], 'bone', {'origin': [x, 6, 8], 'axis': 'z', 'angle': [-22.5, 0, 22.5][i]}))
    return e, ['flesh', 'bone']


def spitter():
    e = [box([6.5, 0, 6.5], [9.5, 6, 9.5], 'flesh'),
         box([5.5, 6, 5.5], [10.5, 12, 10.5], 'flesh'),
         box([7, 12, 7], [9, 17, 9], 'bone'),
         box([7.4, 17, 7.4], [8.6, 18, 8.6], 'green')]
    return e, ['flesh', 'bone', 'green']


def pistol():
    # owner 17.10: the reaper's P-38 Scythe — a short heavy pistol
    e = [box([7, 0, 6.5], [9, 6, 8.5], 'dark'),
         box([6.5, 6, 5.5], [9.5, 9, 10.5], 'steel'),
         box([7.25, 9, 7.25], [8.75, 13, 8.75], 'dark'),
         box([7, 13, 7], [9, 14, 9], 'brass'),
         box([6.75, 4, 8.5], [9.25, 6, 10], 'steel')]
    return e, ['dark', 'steel', 'brass']


DISPLAY = {
    'thirdperson_righthand': {'rotation': [0, 90, 0], 'translation': [0, -1, 1], 'scale': [0.8, 0.8, 0.8]},
    'thirdperson_lefthand': {'rotation': [0, 90, 0], 'translation': [0, -1, 1], 'scale': [0.8, 0.8, 0.8]},
    'firstperson_righthand': {'rotation': [-90, 0, 4], 'translation': [-1, 4, 0], 'scale': [0.55, 0.55, 0.55]},
    'firstperson_lefthand': {'rotation': [-90, 0, -4], 'translation': [-1, 4, 0], 'scale': [0.55, 0.55, 0.55]},
    'gui': {'rotation': [0, 0, -45], 'translation': [0, -2, 0], 'scale': [0.62, 0.62, 0.62]},
    'ground': {'rotation': [90, 0, 0], 'scale': [0.5, 0.5, 0.5]},
    'fixed': {'rotation': [0, 0, -45], 'scale': [0.7, 0.7, 0.7]},
}

if __name__ == '__main__':
    os.makedirs(TEXTURES, exist_ok=True)
    rnd = random.Random(16)
    for name in PALETTE:
        texture(name, rnd)
    overrides = []
    for i, family in enumerate(FAMILIES):
        elements, textures = globals()[family]()
        model = {'textures': {t: 'starmine:item/weapon/' + t for t in textures}, 'elements': elements, 'gui_light': 'front', 'display': DISPLAY}
        model['textures']['particle'] = 'starmine:item/weapon/' + textures[0]
        with open(os.path.join(MODELS, 'weapon_' + family + '.json'), 'w') as f:
            json.dump(model, f, indent='\t')
            f.write('\n')
        if i > 0:
            overrides.append({'predicate': {'starmine:weapon': round(i / 16, 4)}, 'model': 'starmine:item/weapon_' + family})
    with open(os.path.join(MODELS, 'module_weapon.json'), 'w') as f:
        json.dump({'parent': 'minecraft:item/generated', 'textures': {'layer0': 'starmine:item/module_weapon'},
                   'overrides': [{'predicate': {'starmine:weapon': round(0.5 / 16, 4)}, 'model': 'starmine:item/weapon_gauss_rifle'}] + overrides},
                  f, indent='\t')
        f.write('\n')
