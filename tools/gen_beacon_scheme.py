#!/usr/bin/env python3
"""The guide's scheme of the summoning beacon (owner 22.10): textures/gui/guide/beacon.png, 256 x 256 (Patchouli shows its
200 x 200 corner). Left above: the ring seen from above — 8 mineral blocks round the resource crate; the dashed circle:
no minerals or vespene within 9 blocks. Below: the ritual from the side — the command panel used on the crate, the beam,
the transport taking the ring up, the worker landing; the crate stays. Drawn from the mod's own block textures.
Run: python tools/gen_beacon_scheme.py (needs Pillow)."""
import os
from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TEX = os.path.join(ROOT, 'src/main/resources/assets/starmine/textures')
OUT = os.path.join(TEX, 'gui/guide/beacon.png')


def tex(name, size):
    return Image.open(os.path.join(TEX, 'block', name + '.png')).convert('RGBA').crop((0, 0, 16, 16)).resize((size, size), Image.NEAREST)


img = Image.new('RGBA', (256, 256), (0, 0, 0, 0))
d = ImageDraw.Draw(img)
d.rectangle((0, 0, 199, 199), fill=(235, 225, 200, 255))
mineral, crate = tex('mineral_block', 18), tex('terran_armor_panel', 18)
# from above: the ring (left half, top)
x0, y0 = 22, 22
d.ellipse((x0 - 14, y0 - 14, x0 + 3 * 18 + 14, y0 + 3 * 18 + 14), outline=(160, 60, 60, 255), width=1)
for i in range(3):
    for j in range(3):
        img.alpha_composite(crate if (i, j) == (1, 1) else mineral, (x0 + i * 18, y0 + j * 18))
d.text((x0 - 2, y0 + 3 * 18 + 16), '>= 9', fill=(160, 60, 60, 255))
# the command panel on the crate (an arrow to it)
d.line((112, 40, x0 + 3 * 18 + 4, y0 + 27), fill=(40, 40, 40, 255), width=2)
d.rectangle((112, 30, 138, 50), fill=(70, 80, 100, 255), outline=(20, 20, 20, 255))
d.rectangle((116, 34, 134, 46), fill=(120, 220, 140, 255))
d.text((144, 34), '1', fill=(20, 20, 20, 255))
# from the side (below): ring and crate, the beam, the transport
gy = 170
for i in range(-1, 2):
    img.alpha_composite(crate if i == 0 else mineral, (100 + i * 18 - 9, gy - 18))
d.line((0, gy, 199, gy), fill=(90, 70, 50, 255), width=2)
d.rectangle((97, 100, 103, gy - 18), fill=(120, 200, 255, 200))  # the beam
d.text((108, 120), '2', fill=(20, 20, 20, 255))
# the transport and its tractor beam to a mineral block
d.ellipse((130, 82, 178, 100), fill=(110, 120, 140, 255), outline=(20, 20, 20, 255))
d.rectangle((146, 76, 162, 84), fill=(150, 160, 180, 255))
d.line((150, 100, 118, gy - 10), fill=(255, 230, 120, 255), width=2)
d.text((182, 84), '3', fill=(20, 20, 20, 255))
# the worker down beside the crate
d.ellipse((60, gy - 14, 70, gy - 4), fill=(200, 170, 80, 255), outline=(20, 20, 20, 255))
d.rectangle((62, gy - 6, 68, gy), fill=(80, 90, 120, 255))
d.text((48, gy - 30), '4', fill=(20, 20, 20, 255))
os.makedirs(os.path.dirname(OUT), exist_ok=True)
img.save(OUT)
print(os.path.relpath(OUT, ROOT))
