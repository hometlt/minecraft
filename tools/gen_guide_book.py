#!/usr/bin/env python3
"""Writes StarMine's Patchouli field guide (dev.starmine.guide.Guide): assets/starmine/patchouli_books/field_guide/<en_us|ru_ru>.

Hand-written chapters (first steps, base, gear, world, each race's general information) are below; the races' troops
and buildings are generated from the code — UnitKind's table (SC2 numbers), the races' economy and building lists,
Ability.users() — and the lang files (names, ability tips). Run after adding a unit or an ability:
    python tools/gen_guide_book.py
GuideGameTests.guideIsInRussianAndEnglish checks every unit and ability is in the book.
"""
import json, os, re, sys, shutil

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BOOK = os.path.join(ROOT, 'src/main/resources/assets/starmine/patchouli_books/field_guide')

def t(en, ru):
    return {'en_us': en, 'ru_ru': ru}

def text(en, ru, title=None):
    p = {'type': 'patchouli:text', 'text': t(en, ru)}
    if title: p['title'] = title
    return p

def entity(eid, en, ru, name=None):
    return {'type': 'patchouli:entity', 'entity': 'starmine:' + eid, 'text': t(en, ru), 'rotate': True}

def spot(item, en, ru):
    return {'type': 'patchouli:spotlight', 'item': item, 'text': t(en, ru)}

def image(name, en, ru):
    return {'type': 'patchouli:image', 'images': ['starmine:textures/gui/guide/' + name + '.png'], 'border': True, 'text': t(en, ru)}

def craft(recipe, en, ru, recipe2=None):
    p = {'type': 'patchouli:crafting', 'recipe': 'starmine:' + recipe, 'text': t(en, ru)}
    if recipe2: p['recipe2'] = 'starmine:' + recipe2
    return p

CATEGORIES = [
    ('basics', t('First Steps', 'Первые шаги'), t('Where to begin, goals and sides.', 'С чего начать, цели и стороны.'), 'minecraft:compass', [
        ('welcome', t('Welcome, Commander', 'Добро пожаловать, командир'), 'minecraft:writable_book', [
            text('Three races of StarCraft II have come to this world: the $(l)Zerg$(), the $(l)Terrans$() and the $(l)Protoss$(). Their main bases lie about 480 blocks from where you first appeared.$(br2)Wild units are foes to every player; units of your own fight for you.',
                 'В этот мир пришли три расы StarCraft II: $(l)зерги$(), $(l)терраны$() и $(l)протоссы$(). Их главные базы стоят примерно в 480 блоках от места, где вы появились.$(br2)Дикие юниты враждебны всем игрокам; ваши юниты сражаются за вас.'),
            text('Three ways to play, mixed as you like:$(li)spawn eggs: a unit you place is yours;$(li)a founding pedestal of mineral blocks, in survival;$(li)the command view ($(k:key.starmine.command)) and taking control of one of your units.',
                 'Три способа играть, в любом сочетании:$(li)яйца призыва: поставленный вами юнит — ваш;$(li)постамент основания из блоков минералов, в выживании;$(li)командный вид ($(k:key.starmine.command)) и управление одним из своих юнитов.'),
        ]),
        ('sides', t('Sides and Races', 'Стороны и расы'), 'minecraft:shield', [
            text('The races are always foes of each other: wild zerg, terrans and protoss fight on sight. Your units are your allies, neutral to other players\' units until struck.$(br2)Villagers and golems leave your units alone unless they attack the village.',
                 'Расы всегда враждебны друг другу: дикие зерги, терраны и протоссы сражаются, едва увидев друг друга. Ваши юниты — ваши союзники и нейтральны к юнитам других игроков, пока те не ударят.$(br2)Жители и големы не трогают ваших юнитов, если те не нападают на деревню.'),
            text('The races keep apart: a pack, a claim or a nydus worm of one race never appears within 96 blocks of the wild of another — their bases, claims, buildings and guards. Waves, raids and drops are attacks and go where they are sent.',
                 'Расы не селятся рядом: стая, захват или нидусов червь одной расы не появляется ближе 96 блоков к дикому другой — её базам, захватам, зданиям и стражам. Волны, набеги и десанты — это атаки, они идут туда, куда посланы.'),
        ]),
        ('goals', t('Goals', 'Цели'), 'minecraft:knowledge_book', [
            text('Your goals are in the Advancements screen, tab StarMine. $(l)/stargoals$() lists them and the next step; hints come now and then (game rule starmineHints).$(br2)Lost this book? Craft a new one — $(l)a book and a mineral shard$() — or type $(l)/starguide$().',
                 'Ваши цели — на экране достижений, вкладка StarMine. $(l)/stargoals$() показывает их и следующий шаг; подсказки приходят время от времени (правило starmineHints).$(br2)Потеряли книгу? Скрафтите новую — $(l)книга и осколок минерала$() — или наберите $(l)/starguide$().'),
        ]),
        ('models', t('Models', 'Модели'), 'minecraft:armor_stand', [
            text('StarMine carries no Blizzard assets: Modkit takes the models, textures and sounds from your own copy of StarCraft II. Without them every unit and building is drawn by StarMine\'s own stand-in, built of coloured blocks in its race\'s colours — soldiers, beasts, vehicles, ships, walkers and buildings — with glowing eyes, crystals and engines.',
                 'В StarMine нет ассетов Blizzard: модели, текстуры и звуки Modkit берёт из вашей копии StarCraft II. Без них каждый юнит и здание рисуется собственной запасной моделью StarMine из цветных блоков в цветах расы — солдаты, звери, машины, корабли, шагоходы и здания — со светящимися глазами, кристаллами и двигателями.'),
            image('stand_ins', 'Stand-ins: marines, a hellion, a siege tank, a thor, an overlord.', 'Запасные модели: пехотинцы, геллион, осадный танк, тор, повелитель.'),
        ]),
    ]),
    ('base', t('Your Base', 'Ваша база'), t('Founding a base, the command view, resources, upgrades.', 'Основание базы, командный вид, ресурсы, улучшения.'), 'starmine:command_panel', [
        ('founding', t('Founding a Base', 'Основание базы'), 'starmine:commandcenter_spawn_egg', [
            text('A main building — hatchery, command center or nexus — placed from a spawn egg founds your base: 50 minerals and 4 workers. One base per dimension, shared by all three races.',
                 'Главное здание — инкубатор, командный центр или нексус — поставленное из яйца призыва, основывает вашу базу: 50 минералов и 4 рабочих. Одна база на измерение, общая для всех трёх рас.'),
            craft('terran_platform', 'A map ringed with 8 armor shards of a race makes its $(l)race block$(). Set it on top of $(l)3 x 3 mineral blocks$(): that is a founding pedestal. Right-click the race block while you have no base here: the pedestal goes, a transport brings a worker, who founds your base there and builds its main building at once. Your second base needs a step pyramid: 5 x 5, then 3 x 3, then the race block; every one after that 7 x 7 under it all.',
                  'Карта в кольце из 8 осколков брони расы — её $(l)блок расы$(). Поставьте его поверх $(l)3 x 3 блоков минералов$() — это постамент основания. Правый клик по блоку расы, пока у вас нет базы здесь: постамент исчезает, транспорт привозит рабочего, он основывает базу на этом месте и сразу строит главное здание. Вторая база потребует ступенчатую пирамиду: 5 x 5, сверху 3 x 3 и блок расы; каждая следующая — ещё и 7 x 7 в основании.', 'zerg_platform'),
            craft('mineral_block', 'Nine mineral shards make a $(l)block of minerals$() (and back). It is mined like an ore, by hand, pickaxe or worker, but it is not broken: each time it gives a shard and keeps the rest, nine times. $(l)Rich minerals$(), gold, lie rarely deep in the stone: two shards a time.',
                  'Девять осколков минералов — $(l)блок минералов$() (и обратно). Он добывается как руда — рукой, киркой или рабочим, — но не ломается: каждый раз даёт осколок и остаётся, девять раз. $(l)Богатые минералы$(), золотые, редко встречаются глубоко в камне: по два осколка за раз.', 'mineral_shards_from_block'),
            text('Workers bring what they gather to the nearest $(l)storage$(): a main building. They take $(l)minerals first$(); only where there are none other valuable blocks. Take it out where they brought it: right-click the main building and it opens like a chest — minerals show as mineral shards; anything valuable laid in turns into minerals by its worth. (Supply platforms of older worlds still keep their bank.)',
                 'Рабочие несут добытое в ближайшее $(l)хранилище$(): главное здание. Они берут $(l)сначала минералы$(), другие ценные блоки — только где минералов нет. Забирайте добытое там, куда его принесли: правый клик по главному зданию открывает его как сундук — минералы видны осколками, всё ценное, что положено внутрь, превращается в минералы по своей цене. (Склады-платформы старых миров по-прежнему хранят свой банк.)'),
            text('Units and buildings cost only $(l)minerals and vespene$(), as in StarCraft II. Build the main building with a worker when you have its price.',
                 'Юниты и здания стоят только $(l)минералы и веспен$(), как в StarCraft II. Главное здание стройте рабочим, когда хватит его цены.'),
        ]),
        ('command', t('Command View', 'Командный вид'), 'starmine:command_panel', [
            craft('command_panel', 'The command panel: a map, 4 repeaters, 4 comparators. Hold it and press the command key: a camera from above.',
                  'Командная панель: карта, 4 повторителя, 4 компаратора. Держите её и нажмите клавишу командования: камера сверху.'),
            text('WASD moves, the wheel lifts, Q and E turn. Left click selects, right click orders. Ctrl+digit makes a group, F2 selects the army. The card uses SC2 hotkeys: M move, S stop, H hold, P patrol, A attack; workers build with B and V, then a left click puts the building, Esc or a right click cancels.',
                 'WASD — движение, колесо — высота, Q и E — поворот. Левый клик — выбор, правый — приказ. Ctrl+цифра — группа, F2 — вся армия. На карточке горячие клавиши SC2: M движение, S стоп, H держать, P патруль, A атака; рабочие строят через B и V, затем левый клик ставит здание, Esc или правый клик — отмена.'),
            text('Workers: G and a click mine that block; G and a drag mine the whole area (up to 32 by 32) — minerals first — until it is empty. Selected workers show their area and the block they mine. A and a click on any creature — a monster, an animal — attacks it. As in SC2, some weapons hit only the air (a flying viking, corruptor, phoenix) or only the ground (a tank, a colossus, a banshee).',
                 'Рабочие: G и клик — добывать этот блок; G и рамка — всю обведённую область (до 32 на 32), сначала минералы, пока не опустеет. У выделенных рабочих видны их область и добываемый блок. A и клик по любому существу — монстру, животному — атака. Как в SC2, одно оружие бьёт только по воздуху (викинг в полёте, корруптор, феникс), другое только по земле (танк, колосс, баньши).'),
            text('The camera goes anywhere — keys, screen edges, a click on the minimap — however far you stand: the world round it is loaded for you. A cyan arrow on the minimap is you, pointing where you look.$(br2)$(l)F4$() or the ☺ button selects YOU as a hero: you carry out move, stop, hold, patrol and attack orders yourself, striking with what is in your right hand. The card then shows your hotbar: click an item to take it in hand, again to use it. The command panel used on one of your units takes control of it.',
                 'Камера уходит куда угодно — клавишами, краями экрана, кликом по миникарте, — как бы далеко вы ни стояли: мир вокруг неё загружается для вас. Голубая стрелка на миникарте — это вы, она смотрит туда же, куда вы.$(br2)$(l)F4$() или кнопка ☺ выделяет ВАС как героя: приказы движения, стопа, удержания, патруля и атаки вы выполняете сами и бьёте тем, что в правой руке. Карточка тогда показывает панель быстрого доступа: клик по предмету берёт его в руку, второй — применяет. Командная панель, применённая к своему юниту, даёт управлять им.'),
            text('The $(l)middle button$() held and dragged pans the view; held on the $(l)minimap$() it drags the map itself. Terran barracks, factories, starports and command centers $(l)lift off$() (L), fly where they are sent and land again (L). Units that change form — siege mode, burrow, phasing, defender mode, a hellion into a hellbat — play their $(l)morph$() and look different after; the AI uses every ability its units have.',
                 '$(l)Средняя кнопка$(), зажатая и протянутая, двигает вид; зажатая на $(l)миникарте$() — тянет саму карту. Казармы, заводы, космопорты и командные центры терранов $(l)взлетают$() (L), летят, куда посланы, и снова садятся (L). Юниты, меняющие форму — осадный режим, закапывание, фаза, режим защитника, геллион в геллбата, — показывают $(l)морфинг$() и после выглядят иначе; ИИ пользуется всеми способностями своих юнитов.'),
            text("$(l)Tunnels and caves:$() the four buttons at the top left cut the land away — ▼ and ▲ lower and raise a level above which no block is drawn, + and - a radius round the camera — so an army underground is seen and commanded; all off by default. Your units hidden from the camera show as framed icons. $(l)Aircraft$() fly higher or lower with the card's Higher and Lower buttons, down to a block over the ground: into a cave's mouth or a tunnel.$(br2)Sent where they cannot walk, ground armies wait while workers cut them a tunnel, stairs or a bridge — in ordinary worlds; on a StarCraft II battle map the map's relief stays as it is.",
                 "$(l)Тоннели и пещеры:$() четыре кнопки в левом верхнем углу срезают ландшафт — ▼ и ▲ опускают и поднимают уровень, выше которого блоки не рисуются, + и - — радиус вокруг камеры, — чтобы видеть армию под землёй и командовать ею; по умолчанию срез выключен. Ваши юниты, скрытые от камеры, видны значками в рамке. $(l)Авиация$() летит выше или ниже кнопками «Выше» и «Ниже», вплоть до блока над землёй: в устье пещеры или тоннель.$(br2)Посланная туда, куда не пройти, наземная армия ждёт, пока рабочие прокопают тоннель, лестницу или мост — в обычных мирах; на боевой карте StarCraft II рельеф карты остаётся как есть."),
            text('$(l)Autocast$(): right-click an ability\'s button on the command card and the units cast it themselves when it fits (a gold frame turns round the button); right-click again to stop. The zealot\'s charge is on from the start, as in StarCraft II. A worker\'s $(l)Gather$() button does the same for seeking resources by itself.',
                 '$(l)Автоприменение$(): правый клик по кнопке способности на карточке команд — юниты применяют её сами, когда уместно (вокруг кнопки бежит золотая рамка); повторный правый клик выключает. Рывок фанатика включён сразу, как в StarCraft II. Кнопка рабочего $(l)«Добывать»$() так же включает и выключает самостоятельный поиск ресурсов.'),
        ]),
        ('resources', t('Minerals and Vespene', 'Минералы и веспен'), 'starmine:mineral_shard', [
            spot('starmine:mineral_shard', 'Workers mine ores and valuable blocks and carry them home. Blue mineral crystals hold 500 each, 5 a trip; a crystal stands on mineralized earth and cannot be broken — your hand takes a shard.',
                 'Рабочие добывают руду и ценные блоки и несут домой. В синем кристалле минералов 500, по 5 за ходку; кристалл стоит на минерализованной земле и не ломается — рука берёт осколок.'),
            craft('vespene_canister', 'Vespene is a slow green liquid rising in veins from bedrock. A gas building goes on an outlet; a canister of iron and glass carries it.',
                  'Веспен — медленная зелёная жидкость, поднимающаяся жилами от бедрока. Газовое здание ставится на выход жилы; канистра из железа и стекла его хранит.'),
            craft('vespene_torch', 'A vespene bucket over a stick makes four $(l)vespene torches$(), burning green. Vespene, glass and iron make a $(l)vespene lamp$(), as bright as glowstone.',
                  'Ведро веспена над палкой — четыре $(l)веспеновых факела$() с зелёным пламенем. Веспен, стекло и железо — $(l)веспеновая лампа$(), яркая, как светокамень.', 'vespene_lamp'),
            craft('vespene_fuel', 'An empty canister and four buckets of vespene make $(l)vespene fuel$(): 64 smeltings in a furnace (eight coal), and the empty canister comes back. Glass among four mineral shards makes four panes of $(l)mineral glass$(), as strong against blasts as obsidian.',
                  'Пустая канистра и четыре ведра веспена — $(l)веспеновое топливо$(): 64 переплавки в печи (восемь углей), пустая канистра возвращается. Стекло между четырьмя минералами — четыре $(l)минеральных стекла$(), стойких к взрывам, как обсидиан.', 'mineral_glass'),
            text('Mineral fields stand by most vespene geysers, and underground lie $(l)mineral halls$(): big caves with a flat floor and glowing lichen, a geyser in the middle and 4 to 7 crystals round it.$(br2)Water on vespene makes cobblestone, as on lava (on an outlet the water turns, not the outlet). A vespene outlet open to the sky slowly grows up into a $(l)volcano$() — a block every 3 minutes, a cobblestone cone round it. A gas building put on a volcano breaks it: the cone and the column go and the building stands at the ground.',
                 'У большинства гейзеров веспена стоят поля минералов, а под землёй — $(l)минеральные залы$(): большие пещеры с ровным полом и светящимся лишайником, гейзер посередине и 4–7 кристаллов вокруг.$(br2)Вода на веспене даёт булыжник, как на лаве (на выходе жилы булыжником становится вода, а не выход). Открытый к небу выход веспена медленно растёт вверх $(l)вулканом$() — блок в 3 минуты, вокруг конус из булыжника. Газовое здание, поставленное на вулкан, разрушает его: конус и столб исчезают, здание встаёт у земли.'),
            text('Supply comes from overlords, supply depots and pylons. Prices, build times and supply follow StarCraft II. Workers find minerals and geysers within 100 blocks of their store, other valuables within 10 blocks of themselves, walls or not; with nothing in reach they explore the land and the caves. A worker in danger gets an escort, and a dark place is lit its race\'s way.$(br2)A new base stands on 20 blocks of plain stone under a top of $(l)concrete$() its workers never dig.',
                 'Снабжение дают повелители, склады и пилоны. Цены, время и снабжение — как в StarCraft II. Рабочие находят минералы и гейзеры в 100 блоках от хранилища, прочее ценное — в 10 блоках от себя, и за стенами тоже; если рядом пусто, разведывают окрестности и пещеры. Рабочему в опасности база даёт охрану, а тёмное место освещает по-своему.$(br2)Новая база стоит на 20 блоках простого камня под $(l)бетоном$(), который рабочие не копают.'),
        ]),
        ('upgrades', t('Upgrades', 'Улучшения'), 'starmine:engineeringbay_spawn_egg', [
            text('Each level of a weapons upgrade adds about a tenth of a strike (at least 1) to every strike of the units it is for; each armor level is 1 armor, taken off every strike that reaches health (light units have little armor, armored ones more; bonus damage against light or armored comes on top). Shields: 1 shield armor and a tenth more shields a level. The unit panel shows a unit\'s levels.',
                 'Каждый уровень улучшения оружия добавляет примерно десятую часть удара (не меньше 1) к каждому удару юнитов, для которых оно; каждый уровень брони — 1 броня, она вычитается из каждого удара по здоровью (у лёгких юнитов брони мало, у бронированных больше; бонусный урон по лёгким или бронированным — сверх того). Щиты: 1 броня щитов и на десятую больше щитов за уровень. Панель юнита показывает его уровни.'),
        ]),
    ]),
    ('gear', t('Gear and Crafting', 'Снаряжение и ремесло'), t('Suits, modules, guns, race tools, armour blocks and other crafts.', 'Костюмы, модули, оружие, инструменты рас, бронеблоки и прочие крафты.'), 'starmine:terran_chestplate', [
        ('suits', t('Race Suits', 'Костюмы рас'), 'starmine:terran_chestplate', [
            text('Units killed by you or your army drop armor shards of their race, sometimes an ability module. Shards make a race suit, crafted like vanilla armor.$(br2)Terran: as strong as diamond.$(br)Protoss: shields that come back.$(br)Zerg: regeneration, faster on creep.',
                 'Юниты, убитые вами или вашей армией, роняют осколки брони своей расы, иногда — модуль способности. Из осколков — костюм расы, крафт как у обычной брони.$(br2)Терраны: прочный, как алмаз.$(br)Протоссы: восстанавливающиеся щиты.$(br)Зерги: регенерация, быстрее на слизи.'),
            text('Terran armor shards in the shapes of vanilla armor: helmet 5, chestplate 8, leggings 7, boots 4. As strong as diamond, hard to knock back; its modules work in the full suit.',
                 'Осколки брони терранов в формах обычной брони: шлем — 5, нагрудник — 8, поножи — 7, ботинки — 4. Прочен, как алмаз, держит удар; модули терранов работают в полном костюме.'),
            craft('terran_helmet', 'Helmet and chestplate.', 'Шлем и нагрудник.', 'terran_chestplate'),
            craft('terran_leggings', 'Leggings and boots.', 'Поножи и ботинки.', 'terran_boots'),
            text('Protoss armor shards in the shapes of vanilla armor: helmet 5, chestplate 8, leggings 7, boots 4. As iron; the full suit gives shields of 4 hearts that come back 7 seconds after the last hit.',
                 'Осколки брони протоссов в формах обычной брони: шлем — 5, нагрудник — 8, поножи — 7, ботинки — 4. Как железо; полный костюм даёт щиты на 4 сердца, они возвращаются через 7 секунд после последнего удара.'),
            craft('protoss_helmet', 'Helmet and chestplate.', 'Шлем и нагрудник.', 'protoss_chestplate'),
            craft('protoss_leggings', 'Leggings and boots.', 'Поножи и ботинки.', 'protoss_boots'),
            text('Zerg chitin shards in the shapes of vanilla armor: helmet 5, chestplate 8, leggings 7, boots 4. As iron; the full suit regenerates a heart every 4 seconds, four times faster on creep.',
                 'Осколки хитина зергов в формах обычной брони: шлем — 5, нагрудник — 8, поножи — 7, ботинки — 4. Как железо; полный костюм восстанавливает сердце за 4 секунды, на слизи вчетверо быстрее.'),
            craft('zerg_helmet', 'Helmet and chestplate.', 'Шлем и нагрудник.', 'zerg_chestplate'),
            craft('zerg_leggings', 'Leggings and boots.', 'Поножи и ботинки.', 'zerg_boots'),
        ]),
        ('modules', t('Ability Modules', 'Модули способностей'), 'starmine:module_stimpack', [
            text('A module casts its ability: in the body of a unit you control, or in the full suit of its race. It has 24 uses.',
                 'Модуль применяет свою способность: в теле юнита под вашим управлением или в полном костюме своей расы. У него 24 применения.'),
            craft('module_stimpack', 'Any module: 3 armor shards of its race, 2 mineral shards and a vespene bucket (the bucket comes back).',
                  'Любой модуль: 3 осколка брони его расы, 2 осколка минерала и ведро веспена (ведро возвращается).', 'module_burrow'),
        ]),
        ('guns', t('Guns', 'Оружие'), 'starmine:gauss_rifle', [
            craft('gauss_rifle', 'Gauss rifle: 5 damage, 32 blocks, gauss rounds. Right-click fires where you aim; walls stop the shot.',
                  'Гаусс-винтовка: 5 урона, 32 блока, гаусс-патроны. Правый клик — выстрел туда, куда вы целитесь; стены останавливают выстрел.', 'gauss_ammo'),
            craft('canister_rifle', 'Sniper rifle (the ghost\'s): 16 damage (+5 against light), 64 blocks, canister rounds.',
                  'Снайперская винтовка (призрака): 16 урона (+5 по лёгким), 64 блока, патроны-канистры.', 'canister_round'),
            craft('grenade_launcher', 'Punisher grenade launcher (the marauder\'s): grenades of 8 (+8 against armored) that slow what they hit. Punisher grenades: 8 from gunpowder, iron and a mineral shard.',
                  'Гранатомёт «Каратель» (мародёра): гранаты на 8 урона (+8 по бронированным), замедляют цель. Гранаты: 8 штук из пороха, железа и осколка минерала.', 'punisher_grenade'),
            craft('reaper_pistols', 'P-38 Scythe pistols (the reaper\'s): two shots of 3 at once, 18 blocks, gauss rounds. KD8 charge: thrown, a blast of 4 that throws back everything round it; blocks stay.',
                  'Пистолеты P-38 «Коса» (головореза): два выстрела по 3 сразу, 18 блоков, гаусс-патроны. Заряд KD8: бросок, взрыв на 4 урона отбрасывает всех вокруг; блоки целы.', 'kd8_charge'),
            craft('psi_blade', 'Psi blade (the zealot\'s): a protoss blade sharper than diamond, mended with protoss armor shards.',
                  'Пси-клинок (фанатика): протосский клинок острее алмазного, чинится осколками брони протоссов.'),
            text('Marshals in villages sell rounds, grenades, KD8 charges and the guns themselves for emeralds.',
                 'Маршалы в деревнях продают патроны, гранаты, заряды KD8 и само оружие за изумруды.'),
            text('Large villages have a sheriff: he lives by the village in a house of stone bricks with an iron door and iron shutters on its windows, his chest full of grenades and gauss rounds, and guards it with a gauss rifle. He sells a few things of his own pick (rounds, grenades, terran armor pieces and shards, stimpacks, guns) for mineral shards, the dearer ones for a bucket of vespene too.',
                 'В крупных деревнях есть шериф: он живёт рядом в доме из каменных кирпичей с железной дверью и железными люками на окнах, в его сундуке гранаты и гаусс-патроны, и он охраняет его с гаусс-винтовкой. Шериф продаёт кое-что на свой выбор (патроны, гранаты, части и осколки терранской брони, стимпаки, оружие) за осколки минералов, а то, что подороже, — ещё и за ведро веспена.'),
        ]),
        ('race_tools', t('Race Tools', 'Инструменты рас'), 'starmine:protoss_pickaxe', [
            text('A race\'s armour shards and sticks make its pickaxe, axe, shovel, hoe and sword, as iron ones are made; five shards round an iron ingot make its shield. All are of iron\'s level and mended with the race\'s shards.$(br2)$(l)Zerg$() (250 uses): living chitin mends itself, a use every 10 s, three times as fast on creep. The axe bites wood a little faster, the shovel digs creep fast, the hoe makes soil that never dries, the sword poisons one blow in five.',
                 'Из фрагментов брони расы и палок делаются её кирка, топор, лопата, мотыга и меч — как железные; пять фрагментов вокруг железного слитка — её щит. Все — уровня железа и чинятся фрагментами расы.$(br2)$(l)Зерги$() (250 использований): живой хитин чинится сам — по единице за 10 с, на слизи втрое быстрее. Топор чуть быстрее рубит дерево, лопата быстро копает слизь, мотыга даёт почву, которая не пересыхает, меч травит каждым пятым ударом.'),
            text('$(l)Terrans$() (375 uses): plain steel, half again as tough; the terran shield held takes blasts better (60 % of their damage).$(br2)$(l)Protoss$() (250 uses): an energy shield takes the first 30 wears of the tool; 30 s after the last it is whole again.',
                 '$(l)Терраны$() (375 использований): простая сталь, в полтора раза прочнее; терранский щит в руках ослабляет взрывы (60 % урона).$(br2)$(l)Протоссы$() (250 использований): энергощит принимает на себя первые 30 износов инструмента; через 30 с после последнего он снова полон.'),
            craft('zerg_pickaxe', 'The zerg pickaxe and sword.', 'Зергская кирка и меч.', 'zerg_sword'),
            craft('bone_needle', 'The races\' $(l)rounds$(): bone needles (a bone and a zerg shard: 8), toxic needles (4 needles, a spider eye and vespene: 4 — they poison), gauss rounds (an iron nugget, gunpowder and a mineral shard: 8), energy charges (a mineral shard, vespene and a protoss shard: 8). A dispenser fires them straight and fast; so do the turrets.',
                  '$(l)Боеприпасы$() рас: костяные иглы (кость и фрагмент зергов: 8), ядовитые иглы (4 иглы, паучий глаз и веспен: 4 — отравляют), гауссовые патроны (железный самородок, порох и минерал: 8), энергетические заряды (минерал, веспен и фрагмент протоссов: 8). Раздатчик стреляет ими прямо и быстро, как и турели.', 'toxic_needle'),
            craft('gauss_turret', 'The races\' $(l)turrets$(): the gauss turret (a dispenser, a terran armour slab, redstone, iron), the needle turret (a dispenser, a zerg shard, bone meal, zerg flesh), the photon turret (a dispenser, a protoss shard, minerals, vespene). Set one down and load it with its rounds by right-clicking (empty-handed: how many it has; sneaking, its owner takes it up). It shoots monsters and the wild within 12 blocks it sees — never players nor your own —, and monsters go for it. The needle turret heals on creep; the photon turret\'s shield comes back when it is left alone. Your base\'s weapons levels add to its strikes, its race\'s range research (Hi-Sec Auto Tracking, grooved spines, extended thermal lance) adds 4 blocks of reach; in the command view you select it and order it to fire at a foe.',
                  '$(l)Турели$() рас: гаусс-турель (раздатчик, полублок брони терранов, редстоун, железо), игольная (раздатчик, фрагмент зергов, костная мука, плоть зергов), фотонная (раздатчик, фрагмент протоссов, минералы, веспен). Поставьте и зарядите её снарядами правым кликом (пустой рукой — сколько осталось; сидя владелец забирает её). Она стреляет по монстрам и диким в 12 блоках на виду — не по игрокам и не по своим, — а монстры нападают на неё. Игольная лечится на слизи, щит фотонной восстанавливается, если её не трогать. Уровни оружия вашей базы усиливают её удар, исследование дальности расы («Хай-Сек», желобчатые шипы, удлинённое термокопьё) — +4 блока; в командном виде её выделяют и приказывают бить в цель.', 'needle_turret'),
            craft('stasis_trap', '$(l)Traps$(): the protoss stasis trap (a dispenser, a protoss armour slab, an ender pearl, minerals) shuts a foe within 2.5 blocks in stasis for 5 s, then rests 30 s. Abathur\'s $(l)toxic nest$() (a zerg shard, gunpowder, a spider eye, zerg flesh) lies flat like the ground and bursts under a foe — harm and poison round it, no block broken — throwing creep about. Foes are monsters, the wild and other players; your units are spared.',
                  '$(l)Ловушки$(): стазисная ловушка протоссов (раздатчик, полублок брони протоссов, жемчуг Края, минералы) запирает противника в 2,5 блока в стазис на 5 с и отдыхает 30 с. $(l)Токсичное гнездо$() Абатура (фрагмент зергов, порох, паучий глаз, плоть зергов) лежит на земле незаметным слоем и лопается под противником — урон и яд вокруг, блоки целы — и разбрасывает слизь. Противники — монстры, дикие и чужие игроки; ваши юниты не задеваются.', 'toxic_nest'),
            craft('mineral_compass', 'The $(l)mineral compass$() (a compass among four mineral shards) points to the nearest mineral field within 128 blocks; the $(l)vespene detector$() (a compass, vespene, redstone) to the nearest vespene vein. The races\' chests hold 27 stacks: the zerg chest (eight zerg shards) heals, the terran safe (a chest among eight terran shards) takes blasts, the protoss chest (a chest, minerals, protoss shards) is shielded.',
                  '$(l)Минеральный компас$() (компас среди четырёх минералов) указывает на ближайшее поле минералов в 128 блоках, $(l)детектор веспена$() (компас, веспен, редстоун) — на ближайшую жилу. Сундуки рас держат 27 стаков: зергский (восемь фрагментов зергов) заживает, терранский сейф (сундук среди восьми фрагментов терранов) выдерживает взрывы, протосский (сундук, минералы, фрагменты протоссов) защищён щитом.', 'vespene_detector'),
            craft('zerg_boat', 'Five of a race\'s shards make its $(l)boat$(); none breaks on a fall or a knock. The zerg boat heals itself, the terran boat takes half the harm, the protoss boat is a little faster and a shield takes its first blows. The terran boat is a glass cabin: while it stands, it takes the blows meant for its riders. The jump key lifts the protoss boat over the water for 8 s, and sends the zerg boat under it, closed and breathable, its riders spared.',
                  'Пять фрагментов брони расы — её $(l)лодка$(); ни одна не ломается от падения или удара. Зергская заживает сама, терранская получает вдвое меньше урона, протосская чуть быстрее, и щит принимает её первые удары. Терранская — стеклянная кабина: пока она цела, удары по сидящим принимает она. Клавиша прыжка поднимает протосскую над водой на 8 с, а зергскую уводит под воду — закрытую, с воздухом, и сидящие в ней неуязвимы.', 'protoss_boat'),
            craft('terran_repair_tool', 'Two shards, as shears are made, make a race\'s $(l)repair tool$(): it mends a quarter of a block\'s kept damage a use. The terran one mends what is mechanical (terran armour, metal), the zerg mender what lives (zerg armour, flesh, creep, wood, wool, bone), the protoss restorer brings protoss blocks\' shields back.',
                  'Два фрагмента, как ножницы, — $(l)ремонтный инструмент$() расы: за раз он чинит четверть накопленного урона блока. Терранский чинит механическое (броню терранов, металл), зергский лекарь — живое (броню зергов, плоть, слизь, дерево, шерсть, кость), протосский восстановитель возвращает щиты блоков протоссов.', 'zerg_repair_tool'),
            craft('protoss_shield', 'The protoss shield and pickaxe.', 'Протосский щит и кирка.', 'protoss_pickaxe'),
        ]),
        ('armor', t('Armour and Doors', 'Броня и двери'), 'starmine:terran_armor_wall', [
            craft('terran_armor_stairs', 'Armour walls make a race\'s $(l)building blocks$(): slabs (3 make 6), stairs (6 make 4), walls (6 make 6). Its shards make fences (with sticks), trapdoors, $(l)portholes$() (round glass: blast-proof windows) and bars (round iron bars). All are the race\'s armour, as tough and with its ways.',
                  'Из бронестен — $(l)строительные блоки$() расы: полублоки (3 → 6), ступени (6 → 4), стенки (6 → 6). Из её фрагментов — заборы (с палками), люки, $(l)иллюминаторы$() (вокруг стекла: взрывостойкие окна) и решётки (вокруг железной решётки). Всё это — броня расы, такая же прочная и с её свойствами.', 'protoss_porthole'),
            craft('terran_armor_wall', 'Nine armour shards of a race make its $(l)armour wall$(): as hard as obsidian, and far tougher than any stone against weapons and blasts. At a stonecutter it becomes plating or a lit panel and back. The sky ships and the bases\' foundations are built of it.',
                  'Девять осколков брони расы — её $(l)бронестена$(): прочна как обсидиан и куда крепче любого камня против оружия и взрывов. На камнерезе она становится бронеплитой или светящейся панелью и обратно. Из неё построены небесные корабли и фундаменты баз.', 'zerg_armor_wall'),
            craft('terran_door', 'A race\'s $(l)door$(): a zerg sphincter (flesh and shards), a terran airlock (iron), a protoss shield gate (gold). Doors side by side are one door, 2 x 2 or 3 x 3: it opens whole when you or your units come near (another\'s door does not open for you), and breaking any block of it breaks it all.',
                  '$(l)Дверь$() расы: сфинктер зергов (плоть и осколки), шлюз терранов (железо), щитовые ворота протоссов (золото). Двери рядом — одна дверь, 2 x 2 или 3 x 3: она открывается вся, когда рядом вы или ваши юниты (чужая для вас не откроется), а сломав любой её блок, вы ломаете её целиком.', 'protoss_door'),
            text('$(l)Weapons wear blocks$(): every shot cracks the block it hits, as a tool does, until it breaks. The cracks stay — saved with the world, they do not pass by themselves — and so does the work on a block you began to mine and left. Zerg armour heals itself, terran armour is half again as tough and shrugs off blasts, a protoss block\'s shield takes its first 5 hits and comes back 30 s after the last. Loose ground (what a shovel digs, what falls) and creep keep nothing: their cracks settle back. Any block but the unbreakable can be shot through — armour takes the longest.$(br2)On the terrans\' and protoss\' sky ships stand $(l)craft no one mans$(): right-click one empty-handed to take it and drive it; destroyed, it throws you out where it fell.',
                 '$(l)Оружие разрушает блоки$(): каждый выстрел трескает блок, как инструмент, пока он не сломается; трещины остаются — сохраняются с миром и сами не проходят, как и работа над блоком, который вы начали копать и бросили. Броня зергов заживает сама, броня терранов в полтора раза прочнее и почти не боится взрывов, щит блока протоссов принимает первые 5 ударов и восстанавливается через 30 с после последнего. Сыпучее (то, что копают лопатой, падающие блоки) и слизь ничего не хранят: их трещины затягиваются'),
        ]),
        ('blocks', t('Blocks and Items', 'Блоки и предметы'), 'starmine:terrazine_bucket', [
            spot('starmine:terrazine_bucket', 'Terrazine: a violet liquid of the Void\'s rich islands, worth twice vespene; a gas building on its outlet or a bucket takes it. Liquid creep (a bucket of it too) lies in zerg tunnels: zerg run four times as fast in it.',
                 'Терразин — фиолетовая жидкость богатых островов Пустоты, вдвое ценнее веспена; его берёт газовое здание на выходе или ведро. Жидкая слизь (и ведро с ней) лежит в туннелях зергов: зерги бегут в ней вчетверо быстрее.'),
            spot('starmine:void_stone_bricks', 'Void stone, its bricks and void crystals build the islands of the Void; the crystals glow. The race portals and the nydus orifice belong to the bases: a portal leads into its race\'s sky ship while its guards live; a nydus worm comes out of an orifice.',
                 'Камень Пустоты, его кирпичи и кристаллы Пустоты слагают острова Пустоты; кристаллы светятся. Порталы рас и отверстие нидуса — части баз: портал ведёт в корабль своей расы, пока живы его стражи; из отверстия выходит нидусов червь.'),
            text('The $(l)nuclear missile$() is a thing of its own: it rises out of a ghost academy, flies out of sight and comes down on the ghost\'s red dot. You can shoot it down. Larvae wait by hatcheries for the card to turn them into eggs; an egg hatches its unit when its time is up.',
                 '$(l)Ядерная ракета$() — отдельный объект: она взлетает из академии призраков, уходит из виду и падает на красную точку призрака. Её можно сбить. Личинки ждут у инкубаторов, пока карточка не превратит их в яйца; яйцо выводит свой юнит, когда выйдет срок.'),
        ]),
        ('potions', t('Potions of Attributes', 'Зелья атрибутов'), 'minecraft:potion', [
            text('Every unit, player and creature has SC2 $(l)attributes$(): light, armored, biological, mechanical, psionic, massive, structure, heroic, detector. You are biological, light and heroic. Weapons do bonus damage against some: a marauder +10 to armored, an adept +12 to light, an archon +10 to biological, a corruptor +6 to massive.',
                 'У каждого юнита, игрока и существа есть $(l)атрибуты$() SC2: лёгкий, бронированный, биологический, механический, псионический, массивный, здание, героический, детектор. Вы — биологический, лёгкий и героический. Оружие бьёт сильнее по некоторым: мародёр +10 по бронированным, адепт +12 по лёгким, архонт +10 по биологическим, порча +6 по массивным.'),
            text('Brew from an awkward potion: $(l)light$() (a feather, faster), $(l)armored$() (an iron ingot, +4 armor), $(l)biological$() (rotten flesh), $(l)mechanical$() (a copper ingot, +2 armor), $(l)psionic$() (an amethyst shard), $(l)massive$() (obsidian: no knockback, more health, walks through force fields and shatters them), $(l)detector$() (an ender eye: sees cloaked units), $(l)radar$() (a glow ink sac: sees creatures through walls within 48 blocks).',
                 'Варятся из грубого зелья: $(l)лёгкий$() (перо, быстрее), $(l)бронированный$() (железный слиток, +4 брони), $(l)биологический$() (гнилая плоть), $(l)механический$() (медный слиток, +2 брони), $(l)псионический$() (осколок аметиста), $(l)массивный$() (обсидиан: без отбрасывания, больше здоровья, проходит и разбивает силовые поля), $(l)детектор$() (око Края: видит невидимых), $(l)радар$() (светящийся чернильный мешок: существа сквозь стены в 48 блоках).'),
            text('Redstone makes a potion last 8 minutes instead of 3; gunpowder makes it a splash potion, dragon\'s breath a lingering one. Thrown, they take on players, creatures and units, never on buildings. A terran $(l)sensor tower$() shows its player everything that moves within 30 SC2 units, through walls.',
                 'Редстоун продлевает зелье до 8 минут вместо 3; порох делает его взрывным, драконье дыхание — оседающим. Брошенные, они действуют на игроков, существ и юнитов, но не на здания. Терранская $(l)сенсорная башня$() показывает своему игроку всё, что движется в 30 единицах SC2, сквозь стены.'),
        ]),
    ]),
    ('world', t('The World', 'Мир'), t('Packs, dungeons, temples, the stratosphere and Amun\'s invasion.', 'Отряды, подземелья, храмы, стратосфера и вторжение Амуна.'), 'minecraft:filled_map', [
        ('wild', t('The Wild', 'Дикие земли'), 'minecraft:campfire', [
            text('Wild packs roam: zerg in caves, protoss by a lone pylon, terrans at a campfire. They wake when a player comes near. Rare ruined terran outposts, zerg nests and xel\'naga temples hide underground.',
                 'По миру бродят дикие отряды: зерги в пещерах, протоссы у одинокого пилона, терраны у костра. Они просыпаются, когда подходит игрок. Под землёй прячутся редкие разрушенные аванпосты терранов, гнёзда зергов и храмы зел-нага.'),
            text('The races claim land: a wild base sends parties to take caves and fields and dress them its way. Now and then they land a squad by your home; the game rule starmineAiPressure sets how often.',
                 'Расы захватывают земли: дикая база посылает отряды занять пещеры и поля и обустроить их по-своему. Иногда они высаживают отряд у вашего дома; как часто — задаёт правило starmineAiPressure.'),
            text('Beyond the first three bases near your spawn, $(l)far bases$() of the races lie all over the world: one in about every other region of 1536 by 1536 blocks, never within 1000 blocks of the spawn — a terran base over a technological labyrinth, a protoss base over ancient temples or a zerg lair with its tunnels. They are built when you come within 128 blocks; their sky ships and heroes are only over the first three.$(br2)No monster spawns within 30 blocks of any unit or building of any race.',
                 'Кроме первых трёх баз у места появления, по всему миру лежат $(l)дальние базы$() рас: примерно в каждой второй области 1536 на 1536 блоков, не ближе 1000 блоков к спавну, — база терранов над технолабиринтом, база протоссов над древними храмами или логово зергов с туннелями. Они строятся, когда вы подходите на 128 блоков; корабли и герои — только над первыми тремя.$(br2)Монстры не появляются в 30 блоках от любого юнита или здания любой расы.'),
            text('Traders of the other races: a $(l)protoss preserver$() keeps one xel\'naga temple in two (no guards then) and sells protoss gear, void shards and the Xel\'Naga relic for minerals and vespene; $(l)Dehaka$() lives in his cave of flesh and bones and sells zerg gear — and takes essence: rotten flesh, spider eyes, bones, ender pearls for minerals.',
                 'Торговцы других рас: $(l)протосс-хранитель$() живёт в каждом втором храме зел-нага (стражи там нет) и продаёт снаряжение протоссов, осколки пустоты и реликвию зел-нага за минералы и веспен; $(l)Дехака$() живёт в своей пещере из плоти и костей, продаёт снаряжение зергов — и берёт сущность: гнилую плоть, паучьи глаза, кости, жемчуг Края в обмен на минералы.'),
            text('The races are always foes and keep apart: a pack, a claim or a nydus worm of one race never appears within 96 blocks of another race\'s wild.',
                 'Расы всегда враждебны и не селятся рядом: стая, захват или нидусов червь одной расы не появляется ближе 96 блоков к дикому другой.'),
        ]),
        ('story_dungeons', t('Story Dungeons', 'Сюжетные подземелья'), 'starmine:xelnaga_relic', [
            text('Rare and large, one of five: $(l)the infested mine$() — a headframe over a shaft, galleries with rails, props and vents overgrown with flesh and creep, infested terrans and two infestors in the far room; $(l)the brood nest$() — a womb of flesh under the ground, eggs everywhere and the $(l)brood mother$(), who lays egg after egg while you are near: kill her and the eggs stop.',
                 'Редкие и большие, одно из пяти: $(l)заражённая шахта$() — копёр над стволом, штреки с рельсами, крепью и вентиляцией, заросшие плотью и слизью, заражённые терраны и два инфестора в дальнем зале; $(l)гнездо матки$() — утроба из плоти под землёй, всюду яйца и $(l)матка выводка$(), которая откладывает яйцо за яйцом, пока вы рядом: убейте её — и яйца кончатся.'),
            text('$(l)The Xel\'Naga vault$(): a hall of unbreakable metal with a beam puzzle. Use the prisms to turn them: each sends the beam the way its arrow points. When the beam reaches the receiver the seal opens, and the $(l)Xel\'Naga relic$() waits inside. $(l)The sunken scout$(): on a deep sea\'s floor, a protoss ship under a bubble of air, the relic in its hull.',
                 '$(l)Хранилище зел-нага$(): зал из неразрушимого металла с головоломкой из лучей. Поворачивайте призмы (использование): каждая направляет луч туда, куда смотрит её стрелка. Когда луч дойдёт до приёмника, печать откроется, а внутри ждёт $(l)реликвия зел-нага$(). $(l)Затонувший разведчик$(): на дне глубокого моря — корабль протоссов под пузырём воздуха, реликвия в его корпусе.'),
            text('$(l)Moebius\'s laboratory$(): cells of captured zerg behind glass, laboratories with the Foundation\'s notes on lecterns — four of them tell what happened there — and at the end the broken stasis chamber of the $(l)hybrid$(): it blinks behind you with a psionic shockwave and, hurt, calls psionic storms. It carries the relic.$(br2)$(l)The relic$(), used, shows every creature within 48 blocks through walls for 30 seconds and heals you; it rests two minutes after.',
                 '$(l)Лаборатория Мебиуса$(): камеры с пленными зергами за стеклом, лаборатории с записками Фонда на кафедрах — четыре записки рассказывают, что здесь случилось, — и в конце разбитая стазисная камера $(l)гибрида$(): он телепортируется вам за спину с пси-волной, а раненым вызывает пси-шторм. Реликвия у него.$(br2)$(l)Реликвия$() при использовании на 30 секунд показывает сквозь стены всех существ в 48 блоках и лечит вас; потом две минуты отдыхает.'),
        ]),
        ('temple', t('The Xel\'Naga Temples', 'Храмы зел-нага'), 'starmine:xelnaga_glyph', [
            text('Every world with heroes keeps $(l)one xel\'naga temple$(): the overworld\'s 50-100 chunks from the spawn, the Nether\'s grown into its rock a few hundred blocks out, the End\'s on an island far out beyond the dragon. $(l)Each hero carries a part of its world\'s key$() — the overworld\'s guardians and ships\' heroes, the Nether\'s and the End\'s heroes — and each world\'s key is its own. Use a part in the air: it tells how far its temple is, and which way.',
                 'В каждом мире с героями есть $(l)один храм зел-нага$(): в Верхнем мире — в 50-100 чанках от точки появления, в Незере — вросший в скалу в нескольких сотнях блоков, в Энде — на острове далеко за драконом. $(l)Каждый герой носит часть ключа своего мира$() — стражи баз и герои кораблей Верхнего мира, герои Незера и Энда, — и у каждого мира свой ключ. Используйте часть ключа в воздухе: она скажет, как далеко храм и в какой стороне.'),
            text('A great pyramid of $(l)xel\'naga metal$(), which nothing breaks. A wide passage leads in from the south to its $(l)door$(): nothing breaks it either — only a part of that world\'s key opens it (use it on the door). Inside, a $(l)labyrinth$() of rooms and passages ten blocks wide and fourteen high — an army passes, thors and colossi, flyers too — kept by $(l)xel\'naga constructs, sentinels and void monoliths$(), who fight anyone who comes in. Chests wait in some dead ends.',
                 'Огромная пирамида из $(l)металла зел-нага$(), который ничто не берёт. Широкий проход с юга ведёт к $(l)двери$(): её тоже не сломать — открывает только часть ключа этого мира (используйте её на двери). Внутри — $(l)лабиринт$() комнат и проходов в десять блоков шириной и четырнадцать высотой — пройдёт армия, торы и колоссы, пролетит авиация, — его стерегут $(l)конструкты, стражи и монолиты Пустоты зел-нага$(), они нападают на всех, кто войдёт. В некоторых тупиках ждут сундуки.'),
            text('In its middle lies the $(l)hall of keys$(): three sockets before a huge triangle of glowing signs. Set a part of that world\'s key on each socket — zerg, terran and protoss — and the $(l)portal$() in the triangle opens; take one off and it closes. Walk in, with your army round you (16 blocks), or send your units onto it: you come out in the Void by that world\'s triangle beside its first island. Each temple has its own triangle there, so through the Void you go from any world whose temple is open to any other.',
                 'В его середине — $(l)зал ключей$(): три гнезда перед огромным треугольником из светящихся знаков. Поставьте на каждое гнездо часть ключа этого мира — зергов, терранов и протоссов, — и в треугольнике откроется $(l)портал$(); снимите любую — он закроется. Войдите в него вместе с армией вокруг вас (16 блоков) или отправьте туда юнитов: вы выйдете в Пустоте у треугольника этого мира рядом с её первым островом. У каждого храма там свой треугольник, так что через Пустоту можно пройти из любого мира с открытым храмом в любой другой.'),
        ]),
        ('stratosphere', t('The Stratosphere', 'Стратосфера'), 'minecraft:elytra', [
            image('stratosphere', 'The Hyperion over the Earth.', '«Гиперион» над Землёй.'),
            text('A world of its own above the overworld: black sky and stars, the huge Earth below across the horizon, the same sun, moon, day and night. The leviathan, the Hyperion and the mothership hang there over their bases.$(br2)You cannot fly up alone: ride a flying unit (or play as one, or sit in a transport) above height 300. Whatever falls below its floor comes down into the overworld high over the same place; a player falls slowly for 30 seconds.$(br2)$(l)The way aboard$(): destroy a race base\'s main building (the hatchery of the lair, a command center, a nexus) — a portal to its ship opens where it stood (the portal hall under the base wakes too); kill the guards round it and step in. Once its guardian is freed, the heroic transport flies you up as well (see Guardians of the Bases).',
                 'Отдельный мир над Верхним: чёрное небо и звёзды, внизу во весь горизонт огромная Земля, те же солнце, луна, день и ночь. Там над своими базами висят левиафан, «Гиперион» и материнский корабль.$(br2)Сами туда не взлететь: поднимитесь выше высоты 300 на летающем юните (или в его теле, или в транспорте). Всё, что упадёт ниже её дна, вернётся в Верхний мир высоко над тем же местом; игрок падает медленно 30 секунд.$(br2)$(l)Как попасть на борт$(): уничтожьте главное здание базы расы (инкубатор логова, командный центр, нексус) — на его месте откроется портал на её корабль (проснётся и зал порталов под базой); убейте стражей рядом и войдите. Когда страж базы освобождён, наверх доставит и героический транспорт (см. «Стражи баз»).'),
        ]),
        ('platforms', t('Space Platforms', 'Космические платформы'), 'minecraft:iron_block', [
            text('Platforms float in the stratosphere, built as you come near: a steel deck with a mineral field and a $(l)vespene store$() — a glazed tank in the hull with its outlets in the deck under ventilation grates (a gas building goes on an outlet; emptied, it fills from the tank). Inside: lit passages, a hangar open to space, and dark tunnels. Wild troops of one race hold the deck, and more lurk in the dark.$(br2)Flyers go up into the stratosphere and down again by the $(l)Up/Down$() button (Y) of the command card.',
                 'В стратосфере парят платформы — они строятся, когда вы рядом: стальная палуба с полем минералов и $(l)веспеновым хранилищем$() — остеклённым баком в корпусе с выходами в палубе под вентиляционными решётками (газовое здание ставится на выход; опустевший, он наполняется из бака). Внутри — освещённые проходы, ангар с выходом в космос и тёмные туннели. Палубу держат дикие войска одной расы, ещё больше прячется во тьме.$(br2)Летающие юниты поднимаются в стратосферу и спускаются обратно кнопкой $(l)«Подняться/Опуститься»$() (Y) на карточке команд.'),
        ]),
        ('invasion', t('Amun\'s Invasion', 'Вторжение Амуна'), 'starmine:void_shard', [
            text('Amun sleeps while nobody fights the commanders. He $(l)wakes when you take the first part of a key$() from a hero, and comes into $(l)that hero\'s world$() — the overworld, the Nether or the End, each by its own keys. First the sky there turns blood red and everyone hears that Amun has woken; then by night $(l)void shards$() fall near you, darkening the land round them and making his troops fierce, and $(l)rifts$() open by them, sending out his troops of all three races; the shard itself sends out troops now and then until you break it.$(br2)The more parts of a world\'s key you have taken, the harder he presses there: with one, a shard or two, seldom, and small troops; with three, full waves of every kind. It grows a little more each two days. Shards and rifts work only within 16 chunks of a player and show on the minimap. They fight only you and your units — the races leave them be — and every few minutes march on your bases.',
                 'Амун спит, пока никто не трогает командиров. Он $(l)пробуждается, когда вы забираете у героя первую часть ключа$(), и приходит $(l)в мир этого героя$() — в Верхний мир, Незер или Энд, у каждого свои ключи. Сначала небо там становится кроваво-красным и все слышат, что Амун пробудился; потом по ночам рядом с вами падают $(l)осколки пустоты$(), затемняя землю вокруг и ожесточая его войска, а возле них раскрываются $(l)разломы$(), из которых выходят его войска всех трёх рас. Сам осколок время от времени выпускает войска, пока его не разобьют.$(br2)Чем больше частей ключа этого мира вы забрали, тем сильнее натиск: с одной — один-два осколка, редко, и мелкие войска; с тремя — полные волны любых войск. Каждые два дня он ещё немного крепнет. Осколки и разломы работают только в 16 чанках от игрока и видны на миникарте. Они сражаются только с вами и вашими юнитами — расы их не трогают — и каждые несколько минут идут на ваши базы.'),
            text('His workers make the way wherever you are: they tunnel, bridge and build steps; where there is no way by land, his transports carry the troops over. His troops wear his curse, black and red, and leave nothing — no experience, no loot — like everything that dies in the Void.',
                 'Его рабочие прокладывают путь, где бы вы ни были: роют туннели, строят мосты и ступени; где по земле не пройти, его транспорты переносят войска. Его войска носят его проклятие, чёрное с красным, и ничего не оставляют — ни опыта, ни добычи, — как и всё, что гибнет в Пустоте.'),
            spot('starmine:void_gate', 'A broken shard leaves $(l)void shards$(); the Void\'s crystals hold them too. Eight round an ender eye make a $(l)void gate$(): used, it takes you and your units near it into the Void, and a gate in the Void takes you out. The Void is small: a block there is 16 of the overworld\'s.',
                 'Разбитый осколок оставляет $(l)осколки пустоты$(); есть они и в кристаллах Пустоты. Восемь вокруг ока Края — это $(l)врата Пустоты$(): они переносят вас и ваших юнитов рядом в Пустоту, а врата в Пустоте — обратно. Пустота мала: блок там — это 16 блоков Верхнего мира.'),
        ]),
    ]),
    ('heroes', t('Heroes', 'Герои'), t('The bosses, the guardians of the bases, the heroes of the Nether and the End, their helmets and powers.', 'Боссы, стражи баз, герои Нижнего мира и Энда, их шлемы и силы.'), 'starmine:kerrigan_crown', [
        ('kerrigan', t('Kerrigan', 'Керриган'), 'starmine:kerrigan_crown', [
            entity('kerrigan', 'The Queen of Blades, in the head of the leviathan high in the stratosphere over the zerg lair.',
                   'Королева Клинков — в голове левиафана высоко в стратосфере над логовом зергов.'),
            text('Leaping strike and psionic shift; she dodges shots, comes at you from the side and falls back when badly hurt. At 2/3 health she calls mutalisks and a corruptor; at 1/3 she rages and calls brood lords. Her bar shows the phase.$(br2)$(l)When beaten$() Amun\'s hold breaks and the hero joins you (see Amun\'s Hold), leaving: Kerrigan\'s Crown, the rest of a zerg suit, 16 chitin shards, 8 diamonds.',
                 'Удар в прыжке и пси-рывок; она уворачивается от выстрелов, заходит сбоку и отступает, когда сильно ранена. На 2/3 здоровья зовёт муталисков и губителя; на 1/3 впадает в ярость и зовёт стражей. Фаза видна на полосе.$(br2)$(l)Побеждённый$() герой освобождается от власти Амуна и переходит к вам (см. «Власть Амуна»), оставляя: корона Керриган, остальной костюм зергов, 16 осколков хитина, 8 алмазов.'),
        ]),
        ('raynor', t('Raynor', 'Рейнор'), 'starmine:raynor_helmet', [
            entity('raynor', 'Jim Raynor holds the bridge of the Hyperion high in the stratosphere over the terran base.',
                   'Джим Рейнор держит мостик «Гипериона» высоко в стратосфере над базой терранов.'),
            text('A grenade and a call to the Hyperion (a Yamato blast); he dodges, keeps his distance and falls back when hurt. At 2/3 health he calls vikings and banshees; at 1/3 a battlecruiser and liberators, whose zones on the ground you must leave.$(br2)$(l)When beaten$() Amun\'s hold breaks and the hero joins you (see Amun\'s Hold), leaving: Raynor\'s Helmet, the rest of a terran suit, 16 shards, 8 diamonds.',
                 'Граната и вызов «Гипериона» (залп Ямато); уворачивается, держит дистанцию и отступает при уроне. На 2/3 здоровья зовёт викингов и баньши; на 1/3 — крейсер и освободителей: из их зон на земле надо уходить.$(br2)$(l)Побеждённый$() герой освобождается от власти Амуна и переходит к вам (см. «Власть Амуна»), оставляя: шлем Рейнора, остальной костюм терранов, 16 осколков, 8 алмазов.'),
        ]),
        ('artanis', t('Artanis', 'Артанис'), 'starmine:artanis_helmet', [
            entity('artanis', 'Hierarch Artanis waits under the dome of the mothership high in the stratosphere over the protoss base.',
                   'Иерарх Артанис ждёт под куполом материнского корабля высоко в стратосфере над базой протоссов.'),
            text('A blinding dash and psionic storm; he fights as a zealot, dodges and flanks. His shields come back only out of the fight — but when they are gone he kneels and bursts in a flash that throws you back (once in two minutes), his shields whole again. At 2/3 health void rays and phoenixes come in; at 1/3 a tempest and a carrier.$(br2)$(l)When beaten$() Amun\'s hold breaks and the hero joins you (see Amun\'s Hold), leaving: Artanis\'s Helmet, the rest of a protoss suit, 16 shards, 8 diamonds.',
                 'Молниеносный рывок и пси-шторм; бьётся как фанатик, уворачивается и заходит сбоку. Щиты восстанавливаются только вне боя — но, потеряв их, он встаёт на колено и вспыхивает, отбрасывая вас (раз в две минуты), и щиты снова полны. На 2/3 здоровья прилетают излучатели пустоты и фениксы; на 1/3 — буреносец и авианосец.$(br2)$(l)Побеждённый$() герой освобождается от власти Амуна и переходит к вам (см. «Власть Амуна»), оставляя: шлем Артаниса, остальной костюм протоссов, 16 осколков, 8 алмазов.'),
        ]),
        ('helmets', t('Heroes\' Helmets', 'Шлемы героев'), 'starmine:kerrigan_crown', [
            spot('starmine:kerrigan_crown', 'Kerrigan\'s Crown: a piece of the zerg suit with a perk of its own — regeneration in any armor, a heart every 2 seconds (faster on creep). With the whole zerg suit it heals twice as fast.',
                 'Корона Керриган: часть костюма зергов со своим перком — регенерация в любой броне, сердце раз в 2 секунды (на слизи быстрее). С полным костюмом зергов лечит вдвое быстрее.'),
            spot('starmine:raynor_helmet', 'Raynor\'s Helmet: a piece of the terran suit with heavy plating — 6 armor and 4 toughness instead of 3 and 2, and steadier against knockback.',
                 'Шлем Рейнора: часть костюма терранов с тяжёлой бронёй — 6 брони и 4 прочности вместо 3 и 2, и лучше держит отбрасывание.'),
            spot('starmine:artanis_helmet', 'Artanis\'s Helmet: a piece of the protoss suit; in the whole suit its shields come back three times faster and twice as soon after a hit.',
                 'Шлем Артаниса: часть костюма протоссов; в полном костюме щиты восстанавливаются втрое быстрее и вдвое раньше после удара.'),
        ]),
        ('boss_powers', t('Bosses\' Powers', 'Силы боссов'), 'minecraft:nether_star', [
            text('Each phase a boss uses stronger powers; the strikes that hurt most show first — a line or a ring of motes: step out of it. $(l)Kerrigan$(): pools of $(l)liquid creep$() lie on her arena, she heals standing in them and goes to them when hurt — break them (one blow each); new pools grow at each phase. Third phase: the $(l)blade storm$(), a star of dashes round you.',
                 'С каждой фазой босс использует более сильные способности; самые опасные удары сначала показываются — линией или кольцом частиц: уходите из них. $(l)Керриган$(): на её арене лужи $(l)жидкого крипа$(), стоя в них, она лечится и идёт к ним раненой — разбивайте их (каждую с одного удара); на каждой фазе вырастают новые. Третья фаза: $(l)Буря клинков$() — звезда из рывков вокруг вас.'),
            text('$(l)Raynor$(): his energy fills; full, he aims for two seconds — a red line shows the shot, fixed for its last half second — and fires a round that pierces everything for 30 blocks: step off the line. Second phase: the $(l)Hyperion\'s laser$() burns a line across the deck after its warning. Third: needle rounds in a fan, and his energy fills faster.',
                 '$(l)Рейнор$(): его энергия копится; полная — он две секунды целится (красная линия показывает выстрел, последние полсекунды она не двигается) и стреляет патроном, пробивающим всё на 30 блоков: сойдите с линии. Вторая фаза: $(l)лазер «Гипериона»$() после предупреждения прожигает линию по палубе. Третья: иглы веером, и энергия копится быстрее.'),
            text('$(l)Artanis$(): second phase, walls of psionic force cut the arena for eight seconds; third, the $(l)Spear of Adun$() burns three lines across it from orbit. $(l)The other worlds\' heroes$(): Tosh a lockdown, then a nuke; Zeratul a void prison, then a storm of blinks; Zagara more banelings and hydralisks each phase; Horner a missile barrage; Stukov infested terrans; Selendis purifier lines.',
                 '$(l)Артанис$(): во второй фазе стены пси-силы на восемь секунд делят арену; в третьей $(l)Копьё Адуна$() с орбиты прожигает три линии. $(l)Герои других миров$(): Тош — блокировка, потом ядерный удар; Зератул — тюрьма Пустоты, потом шторм рывков; Загара с каждой фазой бросает больше гиблингов и гидралисков; Хорнер — ракетный залп; Стуков — заражённые терраны; Селендис — линии очищения.'),
        ]),
        ('guardians', t('Guardians of the Bases', 'Стражи баз'), 'starmine:karax_spawn_egg', [
            text('The three main bases near your spawn are guarded by heroes under Amun\'s hold: $(l)Abathur$() at the zerg lair, $(l)Swann$() at the terran base, $(l)Karax$() at the protoss base. They wait in the $(l)great hall$() at the bottom of the levels under their base: tunnels wide enough for an army, with rooms and guards — flesh and creep for the zerg, technical tunnels with pipes for the terrans, hidden temples for the protoss. The way down opens on the base\'s east side.$(br2)Beaten, a guardian does not die: Amun\'s hold breaks, it tells you how Amun held it and its troops, and it goes over to you with its whole base and army.',
                 'Три главные базы у места появления охраняют герои во власти Амуна: $(l)Абатур$() у логова зергов, $(l)Свонн$() у базы терранов, $(l)Каракс$() у базы протоссов. Они ждут в $(l)большом зале$() на дне уровней под своей базой: туннели, где пройдёт армия, с комнатами и стражей — плоть и крип у зергов, технические туннели с трубами у терранов, тайные храмы у протоссов. Спуск открывается с восточной стороны базы.$(br2)Побеждённый страж не умирает: власть Амуна спадает, он рассказывает, как Амун держал его и его войска, и переходит к вам со всей базой и армией.'),
            text('A threatened guardian calls its base\'s army to it. $(l)Karax$() sends air drones and warps in photon cannons for a while; at 2/3 health he raises two $(l)khaydarin monoliths$() that shoot from afar through up to 3 blocks (break the crystal on top); at 1/3 the Spear of Adun\'s $(l)purifier beam$() follows you, cutting blocks — run.$(br2)$(l)Swann$() fires grenades and lays $(l)mines$() that blow up 5 blocks round; at 2/3 he sets up $(l)flaming Betties$(); at 1/3 he climbs into $(l)Odin$(), whose barrage breaks blocks and who once drops a nuke — leave the red dot.$(br2)$(l)Abathur$() lays toxic nests (seen a moment, then only by a detector), evolves his units, roots you with tentacles; his hall\'s creep tumors heal him — burn them. At 1/3 he becomes a leaping $(l)brutalisk$() with a symbiote and sheds a copy of himself; Karax flies a low $(l)archive guardian$(). The great forms break the blocks in their way.',
                 'Под угрозой страж зовёт к себе армию своей базы. $(l)Каракс$() выпускает воздушных дронов и ненадолго ставит фотонные пушки; на 2/3 здоровья воздвигает два $(l)кайдариновых монолита$(), бьющих издалека сквозь 3 блока (разбейте кристалл на вершине); на 1/3 $(l)луч воздаяния$() Копья Адуна идёт за вами, прожигая блоки, — бегите.$(br2)$(l)Свонн$() стреляет из гранатомёта и ставит $(l)мины$(), взрывающиеся в 5 блоках; на 2/3 ставит $(l)огненных Бетти$(); на 1/3 садится в $(l)Одина$(): его залпы ломают блоки, и один раз он бросает ядерную ракету — уходите от красной точки.$(br2)$(l)Абатур$() ставит токсичные гнёзда (видны миг, потом — только детектору), развивает своих юнитов, опутывает вас щупальцами; опухоли крипа в его зале лечат его — выжгите их. На 1/3 он становится прыгающим $(l)бруталиском$() с симбионтом и сбрасывает свою копию; Каракс летит на низком $(l)страже архивов$(). Великие формы ломают блоки на пути.'),
            text('Only then does its race\'s ship come to the stratosphere (the leviathan after Abathur, the Hyperion after Swann, the mothership after Karax), and a $(l)heroic transport$() waits by the guardian — an overlord, a medivac or a warp prism, marked on your minimap. Click it with the command panel: it lifts you into the sky, into the stratosphere, to the $(l)hangar under the ship$(), and waits there. Click it again to go back down. Order it with the command panel to wait elsewhere.',
                 'Только тогда корабль его расы появляется в стратосфере (левиафан — после Абатура, «Гиперион» — после Свонна, материнский корабль — после Каракса), а рядом со стражем ждёт $(l)героический транспорт$() — повелитель, медэвак или призма, отмеченные на миникарте. Щёлкните по нему командной панелью: он поднимет вас в небо, в стратосферу, в $(l)ангар под кораблём$() и будет ждать там. Щёлкните снова — спуститесь обратно. Командной панелью его можно переставить в другое место.'),
        ]),
        ('nether_heroes', t('Heroes of the Nether', 'Герои Нижнего мира'), 'starmine:zagara_spawn_egg', [
            text('The Nether has its races too: three bases near the place under your spawn, each in a great hall carved into the rock with its hero in it — $(l)Zagara$()\'s lair, $(l)Tosh$()\'s base and $(l)Zeratul$()\'s — and, far rarer than above, mineral halls with their geysers. Tosh cloaks a while in a fight, Zeratul is cloaked and shows only now and then (a detector sees them always); Zagara throws banelings, calls hydralisks and digs under the ground to come up behind you.',
                 'В Нижнем мире тоже есть расы: три базы у места под вашим спавном, каждая в большом зале, высеченном в скале, с героем в нём, — логово $(l)Загары$(), база $(l)Тоша$() и база $(l)Зератула$(), — и, гораздо реже, чем наверху, минеральные залы с гейзерами. Тош в бою на время становится невидимым, а Зератул невидим почти всегда и лишь изредка показывается (детектор видит их всегда); Загара бросает гиблингов, зовёт гидралисков и прокапывается под землёй, чтобы выйти у вас за спиной.'),
            text('Beaten, each is freed with its base, like the overworld\'s heroes, and gives the Nether\'s part of the artifact\'s key of its race. In the fight against Amun they bring more troops: ghosts, dark templar, banelings and hydralisks.',
                 'Побеждённый, каждый освобождается вместе с базой, как герои Верхнего мира, и отдаёт часть ключа артефакта своей расы. В битве с Амуном они приводят дополнительные войска: призраков, тёмных тамплиеров, гиблингов и гидралисков.'),
        ]),
        ('end_heroes', t('Heroes of the End', 'Герои Энда'), 'starmine:horner_spawn_egg', [
            text('Far from the dragon, about 1300 blocks from the middle, the End holds three bases on islands of their own: $(l)Stukov$()\'s, $(l)Horner$()\'s and $(l)Selendis$()\'s. The heroes are heroic ships over their bases: Horner fires the Yamato cannon, Stukov drops broodling eggs, Selendis launches interceptors and warps time. Beaten, they are freed with their bases, give the End\'s parts of the key and bring battlecruisers, brood lords and a carrier against Amun.',
                 'Далеко от дракона, примерно в 1300 блоках от центра, в Энде стоят три базы на собственных островах: $(l)Стукова$(), $(l)Хорнера$() и $(l)Селендис$(). Герои — героические корабли над своими базами: Хорнер стреляет из «Ямато», Стуков сбрасывает яйца с личинками, Селендис выпускает перехватчики и искривляет время. Побеждённые, они освобождаются с базами, отдают части ключа Энда и приводят против Амуна линейные крейсера, владык стай и авианосец.'),
            text('Every world has its three heroes and its three parts of the key: whole on a pedestal in any world, the artifact opens the portal to the Void. A freed hero is yours to command and to take along — on foot, on transports, through the portal. Should one fall, it comes back at its base after 30 minutes.',
                 'В каждом мире свои три героя и свои три части ключа: собранный на пьедестале в любом мире, артефакт открывает портал в Пустоту. Освобождённым героем вы командуете и берёте его с собой — пешком, на транспортах, через портал. Если он падёт, то через 30 минут вернётся на свою базу.'),
        ]),
        ('amuns_hold', t('Amun\'s Hold', 'Власть Амуна'), 'minecraft:wither_rose', [
            text('The three heroes fight for Amun: black and red motes swirl round them. Their arena on the ship cannot be broken, and they never leave it. $(l)Beaten, a hero does not die$(): at a tenth of its health Amun\'s hold breaks, the hero says so, and it goes over to you $(l)with its whole base and army$() (joined to your base when you have one there). The hero stays on its ship.',
                 'Три героя сражаются за Амуна: вокруг них клубятся чёрные и красные частицы. Их арену на корабле не сломать, и они с неё не уходят. $(l)Побеждённый герой не умирает$(): на десятой части здоровья власть Амуна спадает, герой говорит об этом и переходит к вам $(l)со всей базой и войсками$() (они вливаются в вашу базу, если она там есть). Герой остаётся на корабле.'),
            text('Each hero\'s $(l)part of the artifact\'s key$() stands beside it on the arena. While Amun holds the hero it cannot be broken; once the hero is free, take it.',
                 '$(l)Часть ключа-артефакта$() каждого героя стоит рядом с ним на арене. Пока Амун держит героя, её не сломать; когда герой свободен — забирайте.'),
        ]),
    ]),
    ('endgame', t('Artifact and the Void', 'Артефакт и Пустота'), t('The xel\'naga artifact, the strike force, the Void and Amun.', 'Артефакт зел-нага, ударная армия, Пустота и Амун.'), 'starmine:artifact_pedestal', [
        ('artifact', t('The Xel\'Naga Artifact', 'Артефакт зел-нага'), 'starmine:artifact_pedestal', [
            image('artifact', 'The whole artifact and its beam.', 'Собранный артефакт и его луч.'),
            text('Each hero keeps a $(l)part of the artifact\'s key$() on its arena (see Amun\'s Hold). Put a xel\'naga pedestal down in the overworld and stack the three parts on it, one on another, like a beacon\'s crystal on its base — zerg, terran and protoss, in any order. The pedestal lights, a violet beam rises, and $(l)high in the sky over it$() (at least 48 blocks up, at height 200 or more) a portal to the Void opens.',
                 'У каждого героя на арене — $(l)часть ключа-артефакта$() (см. «Власть Амуна»). Поставьте постамент зел-нага в Верхнем мире и сложите на него три части одну на другую, как кристалл маяка на основание, — зергов, терранов и протоссов, в любом порядке. Постамент загорается, вверх уходит фиолетовый луч, и $(l)высоко в небе над ним$() (не ниже 48 блоков над ним и не ниже высоты 200) открывается портал в Пустоту.'),
            craft('artifact_pedestal', 'The pedestal: a shard of each race over obsidian and an amethyst block.',
                  'Постамент: по осколку брони каждой расы над обсидианом и аметистовым блоком.'),
            text('The portal and its frame cannot be broken. $(l)Take the artifact apart$() — any part, or the pedestal — and the portal is gone.$(br2)Fly into the portal from below, on a flyer or as one, or build up to it. $(l)Units$(): select them and order them onto the portal or onto the artifact — they are carried through by its beam. The portal on the Void\'s first island leads back to the artifact.',
                 'Портал и его рамку не сломать. $(l)Разберите артефакт$() — любую часть или постамент — и портал исчезнет.$(br2)Влетайте в портал снизу — на летающем юните или в его теле — или постройте к нему лестницу. $(l)Юниты$(): выделите их и пошлите на портал или на артефакт — луч перенесёт их. Портал на первом острове Пустоты ведёт назад к артефакту.'),
        ]),
        ('strike', t('The Strike Force', 'Ударная армия'), 'starmine:artifact_pedestal', [
            text('The first time you make the artifact whole, the three freed heroes come to it and your $(l)strike force$() gathers round it: transports — overlords, medivacs, a warp prism — full of workers and ground troops of all three races, and an air fleet of mutalisks, a corruptor, vikings, a banshee, phoenixes and a void ray. It goes up the beam into the portal, and the heroes go back to their ships.$(br2)In the Void it is your army, of your $(l)base in the Void$() (600 minerals and 300 vespene to start): build there and train more.',
                 'Когда вы впервые собираете артефакт, к нему приходят три освобождённых героя, а вокруг собирается ваша $(l)ударная армия$(): транспорты — повелители, медэваки, призма искривления — с рабочими и наземными войсками всех трёх рас и воздушный флот из муталисков, искажателя, викингов, баньши, фениксов и луча Бездны. Она уходит лучом в портал, а герои возвращаются на корабли.$(br2)В Пустоте это ваша армия вашей $(l)базы в Пустоте$() (600 минералов и 300 веспена на старте): стройте там и тренируйте ещё.'),
        ]),
        ('void', t('The Void', 'Пустота'), 'starmine:void_portal_frame', [
            image('void', 'The first island of the Void.', 'Первый остров Пустоты.'),
            text('Amun\'s dimension, reached only through the artifact\'s portal: eternal night, a black sky with red flashes, islands in the dark joined by paths of void bricks, little to take (a few mineral crystals and chests), no monsters of the overworld. One arrives on the first island beside the portal back. Falling off the islands is death.$(br2)Four large $(l)islands of riches$() hold a lake inside and a field of six crystals: vespene lakes, one of $(l)terrazine$() (violet, worth twice vespene) and one with $(l)gold minerals$() (each trip worth twice). A lake\'s two outlets take gas buildings and fill again from the lake.$(br2)Beyond the first islands more lie without end, rarer the farther out, each joined by a wide path. Amun flies low and opens $(l)black holes$(): they grow, draw everything in and burst with every block inside — get out of them.$(br2)Your army answers your command panel there as at home; buildings cannot be put down away from your base\'s world.',
                 'Измерение Амуна, куда ведёт только портал артефакта: вечная ночь, чёрное небо с красными всполохами, острова во тьме, связанные тропами из кирпичей Пустоты, мало ресурсов (несколько кристаллов минералов и сундуков), никаких монстров Верхнего мира. Прибытие — на первом острове рядом с порталом назад. Упасть с островов — смерть.$(br2)На четырёх больших $(l)островах богатств$() — озеро внутри и поле из шести кристаллов: озёра веспена, одно — $(l)терразина$() (фиолетовый, вдвое ценнее веспена), и остров с $(l)золотыми минералами$() (каждая ходка вдвое ценнее). Два выхода озера берут газовые здания и наполняются из озера.$(br2)За первыми островами лежат ещё, без конца, тем реже, чем дальше, и каждый связан широкой тропой. Амун летает низко и открывает $(l)чёрные дыры$(): они растут, затягивают всё вокруг и взрываются со всеми блоками внутри — уходите из них.$(br2)Ваша армия слушается командной панели и там, как дома; здания вдали от мира вашей базы не ставятся.'),
        ]),
        ('amun', t('Amun', 'Амун'), 'minecraft:dragon_egg', [
            image('amun', 'Amun\'s island: the spires with their shards, their beams on Amun.', 'Остров Амуна: шпили с осколками и их лучи к Амуну.'),
            {'type': 'patchouli:entity', 'entity': 'starmine:amun', 'scale': 0.25, 'offset': 0.4, 'rotate': True,
             'text': t('Amun, the fallen xel\'naga: a great winged beast circling his island, the farthest of the Void.', 'Амун, падший зел-нага: огромное крылатое чудовище, кружащее над своим островом, самым дальним в Пустоте.')},
            text('He flies over his foes and $(l)breaks every block in his way$(): the islands crumble where he passes. From afar he hurls void bolts (wither skulls, some of which break blocks) and dragon\'s breath; up close a pulse hurts and throws back all round him. 1000 health, heavy armor.',
                 'Он летает над врагами и $(l)ломает все блоки на своём пути$(): острова крошатся там, где он пролетел. Издалека мечет снаряды Пустоты (черепа иссушителя, некоторые ломают блоки) и драконье дыхание; вблизи — импульс, ранящий и отбрасывающий всех вокруг. 1000 здоровья, тяжёлая броня.'),
            {'type': 'patchouli:entity', 'entity': 'starmine:void_shard', 'rotate': True,
             'text': t('$(l)Void shards$() float on obsidian spires — six round Amun\'s island, four on others. While any stands, Amun cannot be hurt, and the shards near him heal him by their beams. Break them all first: by hand, with arrows, or with your army.', '$(l)Осколки пустоты$() парят на обсидиановых шпилях — шесть вокруг острова Амуна, четыре на других. Пока стоит хоть один, Амун неуязвим, а ближние осколки лечат его лучами. Сначала разбейте их все — руками, стрелами или армией.')},
            {'type': 'patchouli:entity', 'entity': 'starmine:void_rift', 'rotate': False,
             'text': t('Every 45 seconds Amun opens a $(l)void rift$() near a foe (up to four). Out of it come troops of $(l)all three races$() — his, not their race\'s — every 15 seconds, stronger and stronger: zerglings and marines first, ultralisks, thors and archons at last. Break the rifts; all close when Amun dies.', 'Каждые 45 секунд Амун открывает $(l)разлом Пустоты$() рядом с врагом (до четырёх). Из него каждые 15 секунд выходят войска $(l)всех трёх рас$() — его, а не своей расы, — всё сильнее: сначала зерглинги и пехотинцы, под конец ультралиски, торы и архонты. Ломайте разломы; все закроются, когда Амун умрёт.')},
            text('$(l)When Amun dies$(), his rifts close, he leaves a dragon egg, nether stars and diamonds, and $(l)the game is complete$(): everyone in the Void sees the end and the credits, and wakes at home.',
                 '$(l)Когда Амун умирает$(), его разломы закрываются, он оставляет яйцо дракона, звёзды Незера и алмазы, и $(l)игра пройдена$(): все, кто в Пустоте, видят финал и титры и просыпаются дома.'),
        ]),
    ]),
]

# ---- each race: general information (hand-written), troops and buildings (from the code) ----

GENERAL = {
    'ZERG': [
        ('base', t('The Hive', 'Улей'), 'starmine:hatchery_spawn_egg', [
            entity('hatchery', 'The hatchery is the swarm\'s heart: larvae become cocoons, cocoons become units; a drone turns INTO a building. The lair stands 480 blocks from where you first appeared, ringed by its buildings, with tunnels and halls of eggs beneath.',
                   'Инкубатор — сердце роя: личинки становятся коконами, коконы — юнитами; дрон сам превращается в здание. Логово стоит в 480 блоках от места вашего появления, вокруг — его здания, под ним — туннели и залы с яйцами.'),
            text('Creep spreads round hatcheries and tumors: on it zerg run faster and heal. Wild zerg hate every living thing, raid villages, dig tunnels and claim caves with flesh and tumors. Nydus corridors run from the base: a long tunnel of liquid creep to a nydus worm, through which warriors are sent and guarded.',
                 'Слизь растёт вокруг инкубаторов и опухолей: на ней зерги быстрее и лечатся. Дикие зерги ненавидят всё живое, нападают на деревни, роют туннели и захватывают пещеры плотью и опухолями. От базы идут нидус-коридоры: длинный туннель жидкой слизи к нидусову червю, через который перебрасывают и охраняют воинов.'),
        ]),
        ('tumors', t('Creep Tumors', 'Опухоли слизи'), 'starmine:module_creep_tumor', [
            text('A creep tumor shows for its first 5 seconds, then burrows: only its own side and $(l)detectors$() see it — a raven, an observer, an overseer, a turret, a cannon, a spore crawler, a detector potion. Soon after it plants one new tumor at the edge of its creep, as in SC2 — only a base\'s tumor, and with a purpose: round the base, toward the minerals it mines or wants (under the ground too), toward its foes only over its own land. A wild tumor with no base does not spread. It has 50 hit points: arrows and shots wound it, and units that see a foe\'s tumor within reach shoot it down themselves.',
                 'Опухоль слизи видна первые 5 секунд, потом закапывается: её видят только своя сторона и $(l)детекторы$() — ворон, наблюдатель, надзиратель, турель, пушка, споровый краулер, зелье детектора. Вскоре она ставит одну новую опухоль на краю своей слизи, как в SC2. Расползаются только опухоли базы и с целью: вокруг базы, к минералам, которые она добывает или хочет добывать (и под землю), к врагам — лишь в пределах своей территории. Дикая опухоль без базы не расползается. У неё 50 здоровья: её ранят стрелы и выстрелы, а юниты, видящие чужую опухоль в пределах выстрела, сами её расстреливают.'),
            text('You may send your own tumor\'s spread yourself, as in SC2: in the command view click the tumor, then right-click creep within its reach (10 blocks) — the new tumor grows there. A tumor spreads once; one that already spread says so.',
                 'Свою опухоль можно направить самому, как в SC2: в командном режиме щёлкните по опухоли, затем правой кнопкой — по слизи в её радиусе (10 блоков): новая опухоль вырастет там. Опухоль расползается один раз; уже расползшаяся сообщит об этом.'),
        ]),
        ('sky', t('The Leviathan', 'Левиафан'), 'starmine:glowing_zerg_flesh', [
            text('High in the stratosphere above the lair floats the leviathan, a beast of flesh: passages of flesh, pools of liquid creep, eggs and a chest in its belly. Kerrigan waits in its head; the way in — see The Stratosphere.',
                 'Высоко в стратосфере над логовом парит левиафан — чудовище из плоти: ходы в плоти, лужи жидкой слизи, яйца и сундук в чреве. В его голове ждёт Керриган; как попасть внутрь — см. «Стратосфера».'),
        ]),
        ('upgrades', t('Upgrades', 'Улучшения'), 'starmine:evolutionchamber_spawn_egg', [
            text('Evolution chamber: $(l)melee attacks$() (zerglings, banelings, ultralisks), $(l)missile attacks$() (roaches, hydralisks, queens…) and $(l)ground carapace$(). Spire: $(l)flyer attacks$() and $(l)flyer carapace$(). Three levels each: level 2 needs a lair, level 3 a hive. With a hive the spire itself grows into a $(l)greater spire$() (100/150, 71 s; it stands taller): then corruptors may become brood lords.',
                 'Эволюционная камера: $(l)атаки ближнего боя$() (зерглинги, гиблинги, ультралиски), $(l)дальние атаки$() (тараканы, гидралиски, королевы…) и $(l)наземный панцирь$(). Шпиль: $(l)атаки летунов$() и $(l)панцирь летунов$(). По три уровня: для 2-го нужно логово, для 3-го — улей. С ульем сам шпиль вырастает в $(l)большой шпиль$() (100/150, 71 с; он выше): тогда порчи могут стать владыками стай.'),
        ]),
    ],
    'TERRAN': [
        ('base', t('The Base', 'База'), 'starmine:commandcenter_spawn_egg', [
            entity('commandcenter', 'A paved square 480 blocks from where you first appeared: a command center, depots, barracks, factory, starport, engineering bay, bunker and turrets. An SCV builds and stays by the building; buildings lift off and land.',
                   'Бетонная площадь в 480 блоках от места вашего появления: командный центр, склады, казармы, завод, космопорт, инженерный отсек, бункер и турели. КСМ строит и стоит рядом, пока строит; здания взлетают и садятся.'),
            text('Under the base lies a steel labyrinth with labs and a reactor core. Terrans claim land with concrete, lamps and cables; their camps have a campfire, a forge and an antenna, guarded by marines.',
                 'Под базой — стальной лабиринт с лабораториями и реакторным ядром. Терраны захватывают земли бетоном, фонарями и кабелями; в их лагерях костёр, кузня и антенна под охраной пехотинцев.'),
        ]),
        ('sky', t('The Hyperion', '«Гиперион»'), 'minecraft:iron_block', [
            text('High in the stratosphere above the base hangs the battlecruiser Hyperion: a hull with a hammer nose, a bridge, turrets and engines, two decks of cabins and passages, a hangar, an engine room with the reactor core and a hold of chests. Raynor holds its bridge; the way in — see The Stratosphere.',
                 'Высоко в стратосфере над базой висит линкор «Гиперион»: корпус с носом-«молотом», мостик, турели и двигатели, две палубы кают и коридоров, ангар, машинное отделение с реакторным ядром и трюм с сундуками. Рейнор держит его мостик; как попасть на борт — см. «Стратосфера».'),
        ]),
        ('upgrades', t('Upgrades', 'Улучшения'), 'starmine:engineeringbay_spawn_egg', [
            text('Engineering bay: $(l)infantry weapons$() and $(l)infantry armor$(). Armory: $(l)vehicle weapons$(), $(l)ship weapons$() and $(l)vehicle and ship plating$(). Three levels each; levels 2 and 3 of the infantry upgrades need an armory.',
                 'Инженерный отсек: $(l)оружие пехоты$() и $(l)броня пехоты$(). Оружейная: $(l)оружие техники$(), $(l)оружие кораблей$() и $(l)броня техники и кораблей$(). По три уровня; для 2-го и 3-го уровня пехотных улучшений нужна оружейная.'),
        ]),
    ],
    'PROTOSS': [
        ('base', t('The Base', 'База'), 'starmine:nexus_spawn_egg', [
            entity('nexus', 'A sandstone plaza 480 blocks from where you first appeared: a nexus, pylons, gateways, forge, cybernetics core, stargate, cannon and battery. Probes warp buildings in; they need a pylon\'s power field. Shields come back after a fight.',
                   'Площадь из песчаника в 480 блоках от места вашего появления: нексус, пилоны, врата, кузница, кибернетическое ядро, звёздные врата, пушка и батарея. Зонды призывают здания, им нужно поле энергии пилона. Щиты восстанавливаются после боя.'),
            text('Under the base lie ancient temples with shrines. Zealots guard every lone pylon in the wild; dark templars hide in caves.',
                 'Под базой — древние храмы со святилищами. Зилоты охраняют каждый одинокий пилон, тёмные тамплиеры прячутся в пещерах.'),
        ]),
        ('sky', t('The Mothership', 'Материнский корабль'), 'minecraft:gold_block', [
            text('High in the stratosphere above the base floats the mothership: a disc with a dome, a golden ring and arms, a vortex beneath, six technical halls with power crystals and a hall of chests. Artanis waits under its dome; the way in — see The Stratosphere.',
                 'Высоко в стратосфере над базой парит материнский корабль: диск с куполом, золотое кольцо и рукава, воронка снизу, шесть технических залов с энергокристаллами и зал с сундуками. Артанис ждёт под его куполом; как попасть на борт — см. «Стратосфера».'),
        ]),
        ('upgrades', t('Upgrades', 'Улучшения'), 'starmine:forge_spawn_egg', [
            text('Forge: $(l)ground weapons$(), $(l)ground armor$() and $(l)shields$() (every unit and building with shields). Cybernetics core: $(l)air weapons$() and $(l)air armor$(). Three levels each: forge levels 2-3 need a twilight council, air levels 2-3 a fleet beacon.',
                 'Кузница: $(l)наземное оружие$(), $(l)наземная броня$() и $(l)щиты$() (все юниты и здания со щитами). Кибернетическое ядро: $(l)воздушное оружие$() и $(l)воздушная броня$(). По три уровня: для 2–3 уровня кузницы нужен Сумрачный совет, для воздушных — маяк флота.'),
        ]),
    ],
}
RACE = {'ZERG': (t('Zerg', 'Зерги'), t('The swarm: larvae, creep, burrowing and nydus corridors. Hostile to every living thing.', 'Рой: личинки, слизь, закапывание и нидус-коридоры. Враждебен всему живому.'), 'starmine:hatchery_spawn_egg'),
        'TERRAN': (t('Terrans', 'Терраны'), t('Exiles of humanity: SCVs, bunkers, siege tanks and buildings that fly.', 'Изгнанники человечества: КСМ, бункеры, осадные танки и летающие здания.'), 'starmine:commandcenter_spawn_egg'),
        'PROTOSS': (t('Protoss', 'Протоссы'), t('An ancient race of psionic warriors: pylons, warp, shields and the mothership.', 'Древняя раса псионных воинов: пилоны, призыв, щиты и материнский корабль.'), 'starmine:nexus_spawn_egg')}
SUB = [('units', t('Troops', 'Войска')), ('buildings', t('Buildings', 'Постройки')), ('general', t('General Information', 'Общая информация'))]
SUB_ICON = {('ZERG', 'units'): 'zergling', ('ZERG', 'buildings'): 'spawningpool', ('TERRAN', 'units'): 'marine',
            ('TERRAN', 'buildings'): 'barracks', ('PROTOSS', 'units'): 'zealot', ('PROTOSS', 'buildings'): 'gateway'}
HEROES = {'KERRIGAN', 'RAYNOR', 'ARTANIS', 'ABATHUR', 'SWANN', 'KARAX', 'TOSH', 'ZERATUL', 'ZAGARA', 'HORNER', 'STUKOV', 'SELENDIS'}
# what a troop does that its numbers do not say (owner 17.10)
NOTES = {
    'GHOST': t('$(l)Nuke$() (N): arm a nuclear missile in a ghost academy\'s silo first (100/100, one an academy). The ghost then points a red laser at a place for 10 s while the missile rises out of the academy and falls on the red dot: huge damage round it, a shallow crater. An order to the ghost, or its death, lets the missile go: it blows up small in the air; shot down or striking something on its way, it does the same.',
               '$(l)Ядерный удар$() (N): сначала зарядите ракету в шахте академии призраков (100/100, по одной на академию). Затем призрак 10 с держит красный лазер на месте удара, а ракета взлетает из академии и падает на красную точку: огромный урон вокруг, неглубокая воронка. Приказ призраку или его смерть отпускают ракету: она взрывается в воздухе небольшим взрывом; сбитая или налетевшая на препятствие — тоже.'),
    'BATTLECRUISER': t('$(l)Tactical Jump$() (T): 4 s of charging — the cruiser holds still and holds fire while blue sparks gather round it and a shimmer marks the place — then it jumps anywhere. $(l)Yamato$() (Y): 240 to one target after 2 s.',
                       '$(l)Тактический прыжок$() (T): 4 с подготовки — крейсер стоит и не стреляет, вокруг собираются синие искры, место выхода мерцает, — затем прыжок в любую точку. $(l)«Ямато»$() (Y): 240 урона по одной цели через 2 с.'),
    'SENTRY': t('$(l)Hallucination$() (C) opens a choice: a probe (four), a zealot, adept or stalker (two), an immortal, archon, colossus, disruptor, warp prism, phoenix, void ray or oracle (one). The illusions last 43 s, deal no damage and take double.',
                '$(l)Галлюцинация$() (C) открывает выбор: зонд (четыре), фанатик, адепт или сталкер (по два), бессмертный, архонт, колосс, разрушитель, призма искривления, феникс, луч пустоты или оракул (по одному). Иллюзии живут 43 с, не наносят урона и получают двойной.'),
    'GHOST_ACADEMY': t('Researches personal cloaking and enhanced shockwaves, and $(l)arms its silo with a nuke$() (100/100, 43 s): one missile an academy, launched by a ghost.',
                       'Исследует личную маскировку и усиленные ударные волны и $(l)заряжает шахту ядерной ракетой$() (100/100, 43 с): по одной ракете на академию, запускает её призрак.'),
    'HELLBAT': t('A unit of its own: trained at a factory once there is an armory, or a hellion turned into one (Hellbat Mode, and back). Biological and mechanical: a medivac heals it, an SCV repairs it. Its flames set foes alight; +12 against light.',
                 'Отдельный юнит: тренируется на заводе, когда есть оружейная, или геллион превращается в него (режим геллбата и обратно). Биологический и механический: медэвак лечит, КСМ чинит. Огонь поджигает врагов; +12 по лёгким.'),
    'WIDOW_MINE': t('Burrow it (R): hidden in the ground, it fires a $(l)sentinel missile$() at the first foe within 5, on the ground or in the air — 125 (+35 to shields), 40 to the foes around — then rearms for 29 s. Unburrowed it has no attack. Detectors see it.',
                    'Закопайте её (R): спрятанная в земле, она пускает $(l)ракету «Страж»$() в первого врага ближе 5, на земле или в воздухе — 125 (+35 по щитам), 40 врагам рядом — и перезаряжается 29 с. Не закопанная — без атаки. Детекторы её видят.'),
    'VIKING_ASSAULT': t('A unit of its own: a viking that has landed (Assault Mode) walks and fires its twin gatling cannon at the ground, +8 against mechanical; it takes off again as a viking.',
                        'Отдельный юнит: севший викинг (режим посадки) ходит и стреляет по земле спаренными пулемётами, +8 по механическим; взлетает снова викингом.'),
    'LIBERATOR': t('In the air it fires concord cannons at flying foes, with splash. $(l)Defender Mode$() (E): it stops and strikes ground units in its zone for 75 a shot; it cannot move or shoot air until it leaves the mode.',
                   'В воздухе бьёт летающих врагов залпами с уроном по площади. $(l)Режим защитника$() (E): останавливается и бьёт наземные цели в своей зоне по 75 за выстрел; пока не выйдет из режима, не двигается и не стреляет по воздуху.'),
    'XELNAGA_CONSTRUCT': t('A keeper of the xel\'naga temples (Zeratul\'s co-op avatar): wild, of no side, it fights anyone in its temple and does not leave it.',
                           'Страж храмов зел-нага (аватар Зератула из совместного режима): дикий, ничей, нападает на всех в своём храме и не покидает его.'),
    'XELNAGA_SENTINEL': t('The temples\' great keeper: slow and hard to kill, its blows strike everything round it.',
                          'Большой страж храмов: медленный и живучий, его удары бьют всех вокруг.'),
    'VOID_MONOLITH': t('Zeratul\'s void monolith, standing in the temples\' rooms: it strikes ground and air from far off. Break it or go round it.',
                       'Монолит Пустоты Зератула стоит в комнатах храмов: бьёт по земле и воздуху издалека. Разрушьте его или обойдите.'),
    'INFESTED_TERRAN': t('A terran taken by the zerg: it lives in the infested mine and shoots like the marine it was. Zerg now.',
                         'Терран, захваченный зергами: живёт в заражённой шахте и стреляет, как пехотинец, которым был. Теперь — зерг.'),
    'BROOD_MOTHER': t('The queen of the brood nest: while a foe is near she lays eggs round her that hatch into zerglings, roaches and hydralisks.',
                      'Королева гнезда: пока рядом враг, откладывает вокруг яйца, из которых выводятся зерглинги, тараканы и гидралиски.'),
    'HYBRID': t('Moebius\'s hybrid, zerg and protoss in one: it blinks behind its foe with a psionic shockwave and, hurt, calls psionic storms. It leaves the Xel\'Naga relic.',
                'Гибрид Мебиуса, зерг и протосс в одном: телепортируется за спину врагу с пси-волной, раненым вызывает пси-шторм. Оставляет реликвию зел-нага.'),
}

JAVA = os.path.join(ROOT, 'src/main/java/dev/starmine')

def read(path):
    return open(os.path.join(JAVA, path), encoding='utf8').read()

def kinds():
    """UnitKind's table: name -> race, SC2 health, shields, speed, flying, attack, damage, period, range."""
    out = {}
    for m in re.finditer(r'^\t([A-Z_]+)\(\s*(Race\.([A-Z]+),\s*)?([^)]*)\)[,;]', read('entity/UnitKind.java'), re.M):
        name, race, args = m.group(1), m.group(3) or 'ZERG', [a.strip() for a in m.group(4).split(',')]
        shields = '0'
        if race == 'PROTOSS' or name in HEROES:
            shields, args = args[1], [args[0]] + args[2:]
        out[name] = dict(race=race, hp=float(args[0]), shields=float(shields), speed=float(args[1]), fly=args[2] == 'true',
                         attack=args[3].split('.')[-1], damage=float(args[4]), period=float(args[5]), range=float(args[6]))
    return out

def economies():
    """Kind -> minerals, vespene, supply taken, build seconds, supply given (the races' economy())."""
    out = {}
    for file in ('race/ZergRace.java', 'race/TerranRace.java', 'race/ProtossRace.java'):
        for m in re.finditer(r'case ([A-Z_, ]+) -> new Economy\((\d+), (\d+), ([\d.]+), (normal|seconds)\(([\d.]+)\), (\d+)', read(file)):
            seconds = float(m.group(6)) / (1.4 if m.group(5) == 'normal' else 1)
            for name in m.group(1).split(','):
                out[name.strip()] = (int(m.group(2)), int(m.group(3)), float(m.group(4)), seconds, int(m.group(7)))
    return out

def buildings():
    out = set()
    for file in ('race/ZergRace.java', 'race/TerranRace.java', 'race/ProtossRace.java'):
        m = re.search(r'List<UnitKind> buildings\(\) \{\s*return List\.of\(([^)]*)\)', read(file))
        out |= {n.strip() for n in m.group(1).split(',')}
    return out

def abilities():
    """Ability id -> the kinds that use it (Ability.users())."""
    src = read('ability/Ability.java')
    ids = dict(re.findall(r'^\t([A-Z0-9_]+)\(\s*"([a-z0-9_]+)"', src, re.M))
    users = {}
    for m in re.finditer(r'case ([A-Z0-9_, ]+) -> List\.of\(([A-Za-z_., ]*)\)', src[src.index('public List<UnitKind> users()'):]):
        for constant in m.group(1).split(','):
            if constant.strip() in ids:
                users[ids[constant.strip()]] = [k.strip().replace('UnitKind.', '') for k in m.group(2).split(',') if k.strip()]
    return users

LANG = {lang: json.load(open(os.path.join(ROOT, f'src/main/resources/assets/starmine/lang/{lang}.json'), encoding='utf8')) for lang in ('en_us', 'ru_ru')}
LABEL = {'en_us': dict(hp='Health', shields='shields', damage='Damage', every='every', s='s', range='range', speed='Speed', fly='flying',
                       ground='on the ground', cost='Cost', minerals='minerals', vespene='vespene', supply='supply', gives='Gives supply',
                       time='Build time', abilities='Abilities', none='No attack', melee='melee', sc2='StarCraft II numbers (LotV).'),
         'ru_ru': dict(hp='Здоровье', shields='щиты', damage='Урон', every='раз в', s='с', range='дальность', speed='Скорость', fly='летает',
                       ground='по земле', cost='Цена', minerals='минералов', vespene='веспена', supply='снабжения', gives='Даёт снабжения',
                       time='Время', abilities='Способности', none='Без атаки', melee='ближний бой', sc2='Цифры StarCraft II (LotV).')}

def num(x):
    return '%g' % x

def stats(k, eco, lang):
    L = LABEL[lang]
    lines = [f"$(l){L['hp']}$(): {num(k['hp'])}" + (f", {L['shields']} {num(k['shields'])}" if k['shields'] else '')]
    if k['damage'] > 0:
        reach = L['melee'] if k['range'] == 0 else f"{L['range']} {num(k['range'])}"
        lines.append(f"$(l){L['damage']}$(): {num(k['damage'])}, {L['every']} {num(k['period'])} {L['s']}, {reach}")
    elif k['attack'] == 'NONE':
        lines.append(L['none'])
    if k['speed'] > 0:
        lines.append(f"$(l){L['speed']}$(): {num(k['speed'])}, {L['fly'] if k['fly'] else L['ground']}")
    if eco and (eco[0] or eco[1]):
        cost = f"$(l){L['cost']}$(): {eco[0]} {L['minerals']}" + (f", {eco[1]} {L['vespene']}" if eco[1] else '')
        if eco[2]: cost += f", {num(eco[2])} {L['supply']}"
        lines.append(cost)
        if eco[3]: lines.append(f"$(l){L['time']}$(): {round(eco[3])} {L['s']}")
    if eco and eco[4]: lines.append(f"$(l){L['gives']}$(): {eco[4]}")
    return '$(br)'.join(lines) + '$(br2)$(o)' + L['sc2'] + '$()'

def researches():
    """Each research: the buildings it is done at (from race/Research.at)."""
    src = read(os.path.join(JAVA, 'race/Research.java'))
    body = src[src.index('public boolean at(UnitKind building)'):]
    body = body[:body.index('\n\t}')]
    out = {}
    for cases, rest in re.findall(r'case ([A-Z_, ]+) -> (building == [^;]+);', body):
        places = re.findall(r'UnitKind\.([A-Z_]+)', rest)
        for case in cases.split(','): out[case.strip()] = places
    return out

def research_pages(race, table):
    """Pages listing the race's unit upgrades done once (owner 17.10: every SC2 upgrade in the guide)."""
    leveled = {'WEAPONS', 'ARMOR', 'MELEE', 'VEHICLE_WEAPONS', 'SHIP_WEAPONS', 'PLATING', 'FLYER_ATTACKS', 'FLYER_CARAPACE', 'SHIELDS', 'AIR_WEAPONS', 'AIR_ARMOR', 'LAIR', 'HIVE'}
    items = [(r, places) for r, places in researches().items() if r not in leveled and any(table.get(b, {}).get('race') == race for b in places)]
    pages = {lang: [] for lang in LANG}
    for lang in LANG:
        lines = []
        for r, places in items:
            key = 'command.starmine.' + r.lower()
            where = ', '.join(LANG[lang].get('entity.starmine.' + b.lower().replace('_', ''), b) for b in places)
            lines.append(f"$(l){LANG[lang].get(key, r)}$() ({where}): {LANG[lang].get(key + '.tip', '')}")
        for i in range(0, len(lines), 3):
            page = {'type': 'patchouli:text', 'text': '$(br2)'.join(lines[i:i + 3])}
            if i == 0: page['title'] = {'en_us': 'Unit Upgrades', 'ru_ru': 'Улучшения юнитов'}[lang]
            pages[lang].append(page)
    return pages

def race_categories():
    """Per race: a category with an icon and description, its troops, buildings and general information under it."""
    table, eco, built, users = kinds(), economies(), buildings(), abilities()
    by_kind = {}
    for ability, kinds_of in users.items():
        for kind in kinds_of: by_kind.setdefault(kind, []).append(ability)
    out = []
    for ri, race in enumerate(('ZERG', 'TERRAN', 'PROTOSS')):
        name, desc, icon = RACE[race]
        r = race.lower()
        out.append(dict(id=r, name=name, desc=desc, icon=icon, sort=10 + ri, parent=None, entries=[]))
        for si, (sub, sub_name) in enumerate(SUB):
            entries = list(GENERAL[race]) if sub == 'general' else []
            if sub == 'general': # the upgrades entry: its pages, then every unit upgrade of the race
                extra = research_pages(race, table)
                entries = [(eid, ename, eicon, {lang: [pick(p, lang) for p in pages] + extra[lang] for lang in LANG}) if eid == 'upgrades' else (eid, ename, eicon, pages)
                           for eid, ename, eicon, pages in entries]
            if sub != 'general':
                for kind, k in table.items():
                    if k['race'] != race or kind in HEROES or kind in ('LARVA', 'EGG'): continue
                    if (sub == 'buildings') != (kind in built or k['speed'] == 0): continue
                    kid = kind.lower().replace('_', '')
                    pages = {}
                    for lang in LANG:
                        title = LANG[lang].get(f'entity.starmine.{kid}', kind)
                        pages[lang] = [{'type': 'patchouli:entity', 'entity': f'starmine:{kid}', 'name': title, 'rotate': True}]
                        pages[lang].append({'type': 'patchouli:text', 'title': title, 'text': stats(k, eco.get(kind), lang)})
                        if kind in NOTES:
                            pages[lang].append({'type': 'patchouli:text', 'text': NOTES[kind][lang]})
                        known = [a for a in by_kind.get(kind, [])]
                        if known:
                            pages[lang].append({'type': 'patchouli:text', 'title': LABEL[lang]['abilities'], 'text': '$(br2)'.join(
                                f"$(l){LANG[lang].get('ability.starmine.' + a, a)}$(): {LANG[lang].get('ability.starmine.' + a + '.tip', '')}" for a in known)})
                    entries.append((kid, {lang: LANG[lang].get(f'entity.starmine.{kid}', kind) for lang in LANG}, f'starmine:{kid}_spawn_egg', pages))
            sub_icon = icon if sub == 'general' else f'starmine:{SUB_ICON[(race, sub)]}_spawn_egg'
            out.append(dict(id=f'{r}_{sub}', name=sub_name, desc=t(f"{sub_name['en_us']}: {name['en_us']}.", f"{sub_name['ru_ru']}: {name['ru_ru']}."),
                            icon=sub_icon, sort=si, parent=f'starmine:{r}', entries=entries))
    return out

def write(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w', encoding='utf8', newline='\n') as f:
        json.dump(obj, f, ensure_ascii=False, indent='\t')
        f.write('\n')

def pick(page, lang):
    out = {}
    for k, v in page.items():
        out[k] = v[lang] if isinstance(v, dict) and lang in v else v
    return out

ALL = [dict(id=c[0], name=c[1], desc=c[2], icon=c[3], sort=i, parent=None, entries=c[4]) for i, c in enumerate(CATEGORIES)] + race_categories()
for lang in ('en_us', 'ru_ru'):
    if os.path.isdir(f'{BOOK}/{lang}'): shutil.rmtree(f'{BOOK}/{lang}')
    for c in ALL:
        category = {'name': c['name'][lang], 'description': c['desc'][lang], 'icon': c['icon'], 'sortnum': c['sort']}
        if c['parent']: category['parent'] = c['parent']
        write(f"{BOOK}/{lang}/categories/{c['id']}.json", category)
        for ei, (eid, ename, eicon, pages) in enumerate(c['entries']):
            body = pages[lang] if isinstance(pages, dict) else [pick(p, lang) for p in pages]
            write(f"{BOOK}/{lang}/entries/{c['id']}/{eid}.json", {'name': ename[lang], 'icon': eicon, 'category': f"starmine:{c['id']}",
                                                                   'sortnum': ei, 'pages': body})
write(os.path.join(ROOT, 'src/main/resources/data/starmine/patchouli_books/field_guide/book.json'), {
    'name': 'book.starmine.field_guide.name', 'landing_text': 'book.starmine.field_guide.landing', 'version': 1,
    'use_resource_pack': True, 'i18n': True, 'show_progress': False, 'model': 'patchouli:book_brown',
    'book_texture': 'patchouli:textures/gui/book_brown.png'})
print('ok', sum(len(c['entries']) for c in ALL), 'entries in', len(ALL), 'categories')
