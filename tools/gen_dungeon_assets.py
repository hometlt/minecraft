"""The story dungeons' assets (owner 19.10), pixel art drawn here, not SC2 art:

- the Xel'Naga vault's puzzle: the beam emitter (a lens on its front), the prism (a crystal with an arrow the way it
  sends the beam), the receiver (dark, and lit), the seal and the lock (xel'naga metal and glyphs);
- the Xel'Naga relic (an item);
- their names in en_us and ru_ru, the new dwellers' names, the messages, and the Moebius Foundation's four notes.

Deterministic. Needs Pillow:  python tools/gen_dungeon_assets.py
"""
import json
import math
import os
import random

from PIL import Image

ASSETS = os.path.join(os.path.dirname(__file__), '..', 'src', 'main', 'resources', 'assets', 'starmine')
BLOCK = os.path.join(ASSETS, 'textures', 'block')
ITEM = os.path.join(ASSETS, 'textures', 'item')

DARK, LIGHT = (22, 26, 34), (52, 58, 70)
CYAN, GOLD = (110, 255, 220), (240, 210, 110)


def clamp(v):
    return max(0, min(255, int(v)))


def mix(a, b, t):
    t = max(0.0, min(1.0, t))
    return tuple(clamp(a[i] + (b[i] - a[i]) * t) for i in range(3))


def write_json(path, value):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w', encoding='utf8', newline='\n') as f:
        json.dump(value, f, indent='\t', ensure_ascii=False)
        f.write('\n')


def metal(rnd, x, y):
    c = mix(DARK, LIGHT, rnd.random() * 0.3)
    return mix(c, (10, 12, 16), 0.5) if x in (0, 15) or y in (0, 15) else c


def draw(name, painter, folder=BLOCK):
    rnd = random.Random(name)
    image = Image.new('RGBA', (16, 16))
    for y in range(16):
        for x in range(16):
            image.putpixel((x, y), painter(rnd, x, y) + (255,))
    image.save(os.path.join(folder, name + '.png'))


def textures():
    def lens(rnd, x, y):
        d = math.hypot(x - 7.5, y - 7.5)
        if d < 3:
            return mix((255, 255, 255), CYAN, d / 3)
        if d < 5:
            return mix(CYAN, (20, 90, 90), (d - 3) / 2)
        if d < 6:
            return GOLD
        return metal(rnd, x, y)
    draw('xelnaga_beam_emitter_front', lens)

    def prism_top(rnd, x, y):
        c = mix((20, 70, 80), CYAN, 0.25 + 0.4 * ((x + 15 - y) % 7) / 7)
        if x in (0, 15) or y in (0, 15):
            c = GOLD
        if 6 <= x <= 9 and 2 <= y <= 10 or y in (2, 3, 4) and abs(x - 7.5) <= 5.5 - y:  # an arrow: the way the beam goes
            c = (255, 255, 255)
        return c
    draw('xelnaga_prism_top', prism_top)

    def prism_side(rnd, x, y):
        c = mix((20, 70, 80), CYAN, 0.2 + 0.5 * ((x + y) % 6) / 6)
        return GOLD if y in (0, 15) else c
    draw('xelnaga_prism_side', prism_side)

    for name, core in (('xelnaga_beam_receiver', (30, 60, 60)), ('xelnaga_beam_receiver_on', (180, 255, 235))):
        def receiver(rnd, x, y, core=core):
            d = max(abs(x - 7.5), abs(y - 7.5))
            if d < 3:
                return core
            if d < 4:
                return GOLD
            return metal(rnd, x, y)
        draw(name, receiver)

    def seal(rnd, x, y):
        c = metal(rnd, x, y)
        if (x + y) % 5 == 0 or (x - y) % 5 == 0:
            c = mix(c, CYAN, 0.45)
        return c
    draw('xelnaga_seal', seal)

    def relic(rnd, x, y):
        d = abs(x - 7.5) * 1.4 + abs(y - 7.5)
        if d < 3:
            return (255, 255, 255)
        if d < 7:
            return mix(CYAN, (60, 120, 255), (d - 3) / 4)
        return None
    image = Image.new('RGBA', (16, 16), (0, 0, 0, 0))
    rnd = random.Random('relic')
    for y in range(16):
        for x in range(16):
            c = relic(rnd, x, y)
            if c is not None:
                image.putpixel((x, y), c + (255,))
            elif y in (13, 14) and 4 <= x <= 11:
                image.putpixel((x, y), GOLD + (255,))  # its setting
    image.save(os.path.join(ITEM, 'xelnaga_relic.png'))


ROT = {'north': 0, 'east': 90, 'south': 180, 'west': 270}


def models():
    m = lambda name, model: write_json(os.path.join(ASSETS, 'models', 'block', name + '.json'), model)
    m('xelnaga_beam_emitter', {'parent': 'minecraft:block/orientable', 'textures': {
        'front': 'starmine:block/xelnaga_beam_emitter_front', 'side': 'starmine:block/xelnaga_metal', 'top': 'starmine:block/xelnaga_metal'}})
    m('xelnaga_prism', {'parent': 'minecraft:block/cube_bottom_top', 'textures': {
        'top': 'starmine:block/xelnaga_prism_top', 'bottom': 'starmine:block/xelnaga_prism_side', 'side': 'starmine:block/xelnaga_prism_side'}})
    m('xelnaga_beam_receiver', {'parent': 'minecraft:block/cube_all', 'textures': {'all': 'starmine:block/xelnaga_beam_receiver'}})
    m('xelnaga_beam_receiver_on', {'parent': 'minecraft:block/cube_all', 'textures': {'all': 'starmine:block/xelnaga_beam_receiver_on'}})
    m('xelnaga_seal', {'parent': 'minecraft:block/cube_all', 'textures': {'all': 'starmine:block/xelnaga_seal'}})
    m('xelnaga_vault_lock', {'parent': 'minecraft:block/cube_all', 'textures': {'all': 'starmine:block/xelnaga_glyph'}})
    bs = lambda name, value: write_json(os.path.join(ASSETS, 'blockstates', name + '.json'), value)
    # the prism's arrow on its top points north in the texture: the model turns with the way it faces
    bs('xelnaga_beam_emitter', {'variants': {'facing=' + f: {'model': 'starmine:block/xelnaga_beam_emitter', 'y': r} for f, r in ROT.items()}})
    bs('xelnaga_prism', {'variants': {'facing=' + f: {'model': 'starmine:block/xelnaga_prism', 'y': r} for f, r in ROT.items()}})
    bs('xelnaga_beam_receiver', {'variants': {'lit=false': {'model': 'starmine:block/xelnaga_beam_receiver'},
                                              'lit=true': {'model': 'starmine:block/xelnaga_beam_receiver_on'}}})
    bs('xelnaga_seal', {'variants': {'': {'model': 'starmine:block/xelnaga_seal'}}})
    bs('xelnaga_vault_lock', {'variants': {'': {'model': 'starmine:block/xelnaga_vault_lock'}}})
    for name in ('xelnaga_beam_emitter', 'xelnaga_prism', 'xelnaga_beam_receiver', 'xelnaga_seal', 'xelnaga_vault_lock'):
        write_json(os.path.join(ASSETS, 'models', 'item', name + '.json'), {'parent': 'starmine:block/' + name})
    write_json(os.path.join(ASSETS, 'models', 'item', 'xelnaga_relic.json'), {'parent': 'minecraft:item/generated', 'textures': {'layer0': 'starmine:item/xelnaga_relic'}})


NOTES = {
    1: (('Moebius Log: Specimens', 'Журнал Мебиуса: образцы'), [
        ('The zerg specimens arrived from the Koprulu fringe alive, as Dr. Narud wished. Cells two and three hold a hydralisk and an infestor. The infestor tries the glass every night.',
         'Образцы зергов доставлены живыми, как и хотел доктор Наруд. В камерах два и три — гидралиск и инфестор. Инфестор каждую ночь пробует стекло на прочность.'),
        ('Narud insists on protoss essence as well. He will not say where he gets it. The crates come sealed with a sign none of us can read — a triangle of light.',
         'Наруд требует и сущность протоссов. Откуда он её берёт — не говорит. Ящики приходят запечатанными знаком, который никто из нас не может прочесть: треугольник из света.'),
        ('Security reminder: no one enters the east chamber alone. The marines are not told what is inside. Neither, in truth, are we.',
         'Напоминание охране: в восточную камеру в одиночку не входить. Пехотинцам не говорят, что внутри. По правде говоря, нам тоже.'),
    ]),
    2: (('Moebius Log: The Joining', 'Журнал Мебиуса: слияние'), [
        ('Trial 41. The zerg tissue took the protoss graft and did not reject it. For the first time the two lived in one body for more than a minute.',
         'Опыт 41. Ткань зергов приняла трансплантат протоссов и не отторгла его. Впервые обе природы прожили в одном теле дольше минуты.'),
        ('It does not scream. It listens. When Narud enters, it turns its head to him before the door opens.',
         'Оно не кричит. Оно слушает. Когда входит Наруд, оно поворачивает голову к нему ещё до того, как откроется дверь.'),
        ('Narud calls it "the first of the new ones". He speaks of a master who will return, and of a world remade. I write it down because I was told to write everything down.',
         'Наруд называет его «первым из новых». Он говорит о хозяине, который вернётся, и о переделанном мире. Записываю, потому что велено записывать всё.'),
    ]),
    3: (('Moebius Log: The Voice', 'Журнал Мебиуса: голос'), [
        ('Three technicians report the same dream: a voice in the dark, older than the stars, calling itself by one name — Amun.',
         'Трое техников видят один и тот же сон: голос во тьме, старше звёзд, называет себя одним именем — Амун.'),
        ('The hybrid grows stronger when they sleep. The stasis field needs more power each day. Reactor two is at its limit.',
         'Гибрид крепнет, пока они спят. Стазисному полю каждый день нужно больше энергии. Второй реактор на пределе.'),
        ('I asked Narud to stop. He smiled and said the work was never ours to stop. I am leaving this note where the next one will find it.',
         'Я попросил Наруда остановиться. Он улыбнулся и сказал, что эта работа никогда не была нашей и не нам её останавливать. Оставляю записку там, где её найдёт следующий.'),
    ]),
    4: (('Moebius Log: The Last Entry', 'Журнал Мебиуса: последняя запись'), [
        ('The field failed at 03:12. The glass broke inward, as if pulled. The specimen did not run. It waited for us.',
         'Поле отказало в 03:12. Стекло лопнуло внутрь, будто его втянуло. Образец не побежал. Он ждал нас.'),
        ('The guards held the corridor. The zerg in the cells went still, as if they knew who their master was now.',
         'Охрана удержала коридор. Зерги в камерах затихли, будто поняли, кто теперь их хозяин.'),
        ('If you read this: the hybrid carries a relic of the xel\'naga, the one Narud took from the triangle crates. Destroy the creature. Take the relic far from Amun\'s voice.',
         'Если вы это читаете: у гибрида реликвия зел-нага — та, что Наруд взял из ящиков с треугольником. Уничтожьте тварь. Унесите реликвию подальше от голоса Амуна.'),
    ]),
}

LANG = {
    'en_us': {
        'block.starmine.xelnaga_beam_emitter': "Xel'Naga Beam Emitter", 'block.starmine.xelnaga_prism': "Xel'Naga Prism",
        'block.starmine.xelnaga_beam_receiver': "Xel'Naga Beam Receiver", 'block.starmine.xelnaga_seal': "Xel'Naga Seal",
        'block.starmine.xelnaga_vault_lock': "Xel'Naga Vault Lock", 'item.starmine.xelnaga_relic': "Xel'Naga Relic",
        'entity.starmine.infestedterran': 'Infested Terran', 'item.starmine.infestedterran_spawn_egg': 'Infested Terran Spawn Egg',
        'entity.starmine.broodmother': 'Brood Mother', 'item.starmine.broodmother_spawn_egg': 'Brood Mother Spawn Egg',
        'entity.starmine.hybrid': 'Hybrid', 'item.starmine.hybrid_spawn_egg': 'Hybrid Spawn Egg',
        'message.starmine.vault_opened': 'The beam reaches the receiver: the vault\'s seal opens',
        'message.starmine.relic_used': 'The relic shows every creature round you',
    },
    'ru_ru': {
        'block.starmine.xelnaga_beam_emitter': 'Излучатель луча зел-нага', 'block.starmine.xelnaga_prism': 'Призма зел-нага',
        'block.starmine.xelnaga_beam_receiver': 'Приёмник луча зел-нага', 'block.starmine.xelnaga_seal': 'Печать зел-нага',
        'block.starmine.xelnaga_vault_lock': 'Замок хранилища зел-нага', 'item.starmine.xelnaga_relic': 'Реликвия зел-нага',
        'entity.starmine.infestedterran': 'Заражённый терран', 'item.starmine.infestedterran_spawn_egg': 'Яйцо призыва: заражённый терран',
        'entity.starmine.broodmother': 'Матка выводка', 'item.starmine.broodmother_spawn_egg': 'Яйцо призыва: матка выводка',
        'entity.starmine.hybrid': 'Гибрид', 'item.starmine.hybrid_spawn_egg': 'Яйцо призыва: гибрид',
        'message.starmine.vault_opened': 'Луч дошёл до приёмника: печать хранилища открылась',
        'message.starmine.relic_used': 'Реликвия показывает всех существ вокруг',
    },
}


def lang():
    for i, code in enumerate(('en_us', 'ru_ru')):
        path = os.path.join(ASSETS, 'lang', code + '.json')
        with open(path, encoding='utf8') as f:
            table = json.load(f)
        table.update(LANG[code])
        for n, (title, pages) in NOTES.items():
            table['book.starmine.moebius.%d.title' % n] = title[i]
            for p, page in enumerate(pages, 1):
                table['book.starmine.moebius.%d.page%d' % (n, p)] = page[i]
        write_json(path, table)


if __name__ == '__main__':
    textures()
    models()
    lang()
    print('ok')
