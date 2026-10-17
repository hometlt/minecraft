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
            text('Three ways to play, mixed as you like:$(li)spawn eggs: a unit you place is yours;$(li)a supply platform, in survival;$(li)the command view ($(k:key.starmine.command)) and taking control of one of your units.',
                 'Три способа играть, в любом сочетании:$(li)яйца призыва: поставленный вами юнит — ваш;$(li)склад-платформа, в выживании;$(li)командный вид ($(k:key.starmine.command)) и управление одним из своих юнитов.'),
        ]),
        ('sides', t('Sides and Races', 'Стороны и расы'), 'minecraft:shield', [
            text('The races are always foes of each other: wild zerg, terrans and protoss fight on sight. Your units are your allies, neutral to other players\' units until struck.$(br2)Villagers and golems leave your units alone unless they attack the village.',
                 'Расы всегда враждебны друг другу: дикие зерги, терраны и протоссы сражаются, едва увидев друг друга. Ваши юниты — ваши союзники и нейтральны к юнитам других игроков, пока те не ударят.$(br2)Жители и големы не трогают ваших юнитов, если те не нападают на деревню.'),
            text('The races keep apart: a pack, a claim or a nydus worm of one race never appears within 96 blocks of the wild of another — their bases, claims, buildings and guards. Waves, raids and drops are attacks and go where they are sent.',
                 'Расы не селятся рядом: стая, захват или нидусов червь одной расы не появляется ближе 96 блоков к дикому другой — её базам, захватам, зданиям и стражам. Волны, набеги и десанты — это атаки, они идут туда, куда посланы.'),
        ]),
        ('models', t('Models', 'Модели'), 'minecraft:armor_stand', [
            text('StarMine carries no Blizzard assets: Modkit takes the models, textures and sounds from your own copy of StarCraft II. Without them every unit and building is drawn by StarMine\'s own stand-in, built of coloured blocks in its race\'s colours — soldiers, beasts, vehicles, ships, walkers and buildings — with glowing eyes, crystals and engines.',
                 'В StarMine нет ассетов Blizzard: модели, текстуры и звуки Modkit берёт из вашей копии StarCraft II. Без них каждый юнит и здание рисуется собственной запасной моделью StarMine из цветных блоков в цветах расы — солдаты, звери, машины, корабли, шагоходы и здания — со светящимися глазами, кристаллами и двигателями.'),
            image('stand_ins', 'Stand-ins: marines, a hellion, a siege tank, a thor, an overlord.', 'Запасные модели: пехотинцы, геллион, осадный танк, тор, повелитель.'),
        ]),
        ('goals', t('Goals', 'Цели'), 'minecraft:knowledge_book', [
            text('Your goals are in the Advancements screen, tab StarMine. $(l)/stargoals$() lists them and the next step; hints come now and then (game rule starmineHints).$(br2)Lost this book? Craft a new one — $(l)a book and a mineral shard$() — or type $(l)/starguide$().',
                 'Ваши цели — на экране достижений, вкладка StarMine. $(l)/stargoals$() показывает их и следующий шаг; подсказки приходят время от времени (правило starmineHints).$(br2)Потеряли книгу? Скрафтите новую — $(l)книга и осколок минерала$() — или наберите $(l)/starguide$().'),
        ]),
    ]),
    ('base', t('Your Base', 'Ваша база'), t('Founding a base, the command view, resources.', 'Основание базы, командный вид, ресурсы.'), 'starmine:command_panel', [
        ('founding', t('Founding a Base', 'Основание базы'), 'starmine:commandcenter_spawn_egg', [
            text('A main building — hatchery, command center or nexus — placed from a spawn egg founds your base: 50 minerals and 4 workers. One base per dimension, shared by all three races.',
                 'Главное здание — инкубатор, командный центр или нексус — поставленное из яйца призыва, основывает вашу базу: 50 минералов и 4 рабочих. Одна база на измерение, общая для всех трёх рас.'),
            craft('terran_platform', 'A map ringed with 8 armor shards of a race makes its supply platform. Placed, it is a building of its race: monsters and the wild races attack it. Lay $(l)100 minerals$() in it and use the command panel on it: a transport brings a worker, who founds your base there.',
                  'Карта в кольце из 8 осколков брони расы — её склад-платформа. Поставленная, она — здание своей расы: на неё нападают монстры и дикие расы. Положите в неё $(l)100 минералов$() и примените командную панель: транспорт привезёт рабочего, и он основывает вашу базу у платформы.', 'zerg_platform'),
            text('Workers bring what they gather to the nearest $(l)storage$(): a main building or a supply platform. They take $(l)minerals first$(); only where there are none other valuable blocks. All storages are one bank of minerals and vespene: open any of them like a chest — minerals show as mineral shards; anything valuable laid in turns into minerals by its worth.',
                 'Рабочие несут добытое в ближайшее $(l)хранилище$(): главное здание или склад-платформу. Они берут $(l)сначала минералы$(), другие ценные блоки — только где минералов нет. Все хранилища — один банк минералов и веспена: любое открывается как сундук, минералы видны осколками, всё ценное, что положено внутрь, превращается в минералы по своей цене.'),
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
        ]),
        ('resources', t('Minerals and Vespene', 'Минералы и веспен'), 'starmine:mineral_shard', [
            spot('starmine:mineral_shard', 'Workers mine ores and valuable blocks and carry them home. Blue mineral crystals hold 500 each, 5 a trip; a crystal stands on mineralized earth and cannot be broken — your hand takes a shard.',
                 'Рабочие добывают руду и ценные блоки и несут домой. В синем кристалле минералов 500, по 5 за ходку; кристалл стоит на минерализованной земле и не ломается — рука берёт осколок.'),
            craft('vespene_canister', 'Vespene is a slow green liquid rising in veins from bedrock. A gas building goes on an outlet; a canister of iron and glass carries it.',
                  'Веспен — медленная зелёная жидкость, поднимающаяся жилами от бедрока. Газовое здание ставится на выход жилы; канистра из железа и стекла его хранит.'),
            text('Mineral fields stand by most vespene geysers, and underground lie $(l)mineral halls$(): big caves with a flat floor and glowing lichen, a geyser in the middle and 4 to 7 crystals round it.$(br2)Water on vespene makes cobblestone, as on lava (on an outlet the water turns, not the outlet). A vespene outlet open to the sky slowly grows up into a $(l)volcano$() — a block every 3 minutes, a cobblestone cone round it. A gas building put on a volcano breaks it: the cone and the column go and the building stands at the ground.',
                 'У большинства гейзеров веспена стоят поля минералов, а под землёй — $(l)минеральные залы$(): большие пещеры с ровным полом и светящимся лишайником, гейзер посередине и 4–7 кристаллов вокруг.$(br2)Вода на веспене даёт булыжник, как на лаве (на выходе жилы булыжником становится вода, а не выход). Открытый к небу выход веспена медленно растёт вверх $(l)вулканом$() — блок в 3 минуты, вокруг конус из булыжника. Газовое здание, поставленное на вулкан, разрушает его: конус и столб исчезают, здание встаёт у земли.'),
            text('Supply comes from overlords, supply depots and pylons. Prices, build times and supply follow StarCraft II. A worker with nothing in reach explores farther, on the land and under it; a worker in danger gets an escort, and a dark place is lit its race\'s way.',
                 'Снабжение дают повелители, склады и пилоны. Цены, время и снабжение — как в StarCraft II. Рабочий без дела ищет дальше, по земле и под землёй; рабочему в опасности база даёт охрану, а тёмное место освещает по-своему.'),
        ]),
    ]),
    ('gear', t('Suits, Modules, Guns', 'Костюмы, модули, оружие'), t('Crafting schemes of the gear.', 'Схемы крафта снаряжения.'), 'starmine:terran_chestplate', [
        ('suits', t('Race Suits', 'Костюмы рас'), 'starmine:terran_chestplate', [
            text('Units killed by you or your army drop armor shards of their race, sometimes an ability module. Shards make a race suit, crafted like vanilla armor.$(br2)Terran: as strong as diamond.$(br)Protoss: shields that come back.$(br)Zerg: regeneration, faster on creep.',
                 'Юниты, убитые вами или вашей армией, роняют осколки брони своей расы, иногда — модуль способности. Из осколков — костюм расы, крафт как у обычной брони.$(br2)Терраны: прочный, как алмаз.$(br)Протоссы: восстанавливающиеся щиты.$(br)Зерги: регенерация, быстрее на слизи.'),
        ]),
        ('suit_terran', t('Terran Suit', 'Костюм терранов'), 'starmine:terran_helmet', [
            text('Terran armor shards in the shapes of vanilla armor: helmet 5, chestplate 8, leggings 7, boots 4. As strong as diamond, hard to knock back; its modules work in the full suit.',
                 'Осколки брони терранов в формах обычной брони: шлем — 5, нагрудник — 8, поножи — 7, ботинки — 4. Прочен, как алмаз, держит удар; модули терранов работают в полном костюме.'),
            craft('terran_helmet', 'Helmet and chestplate.', 'Шлем и нагрудник.', 'terran_chestplate'),
            craft('terran_leggings', 'Leggings and boots.', 'Поножи и ботинки.', 'terran_boots'),
        ]),
        ('suit_protoss', t('Protoss Suit', 'Костюм протоссов'), 'starmine:protoss_helmet', [
            text('Protoss armor shards in the shapes of vanilla armor: helmet 5, chestplate 8, leggings 7, boots 4. As iron; the full suit gives shields of 4 hearts that come back 7 seconds after the last hit.',
                 'Осколки брони протоссов в формах обычной брони: шлем — 5, нагрудник — 8, поножи — 7, ботинки — 4. Как железо; полный костюм даёт щиты на 4 сердца, они возвращаются через 7 секунд после последнего удара.'),
            craft('protoss_helmet', 'Helmet and chestplate.', 'Шлем и нагрудник.', 'protoss_chestplate'),
            craft('protoss_leggings', 'Leggings and boots.', 'Поножи и ботинки.', 'protoss_boots'),
        ]),
        ('suit_zerg', t('Zerg Suit', 'Костюм зергов'), 'starmine:zerg_helmet', [
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
        ]),
    ]),
    ('world', t('The World', 'Мир'), t('Packs, dungeons, sky ships and heroes.', 'Отряды, подземелья, корабли и герои.'), 'minecraft:filled_map', [
        ('wild', t('The Wild', 'Дикие земли'), 'minecraft:campfire', [
            text('Wild packs roam: zerg in caves, protoss by a lone pylon, terrans at a campfire. They wake when a player comes near. Rare ruined terran outposts, zerg nests and xel\'naga temples hide underground.',
                 'По миру бродят дикие отряды: зерги в пещерах, протоссы у одинокого пилона, терраны у костра. Они просыпаются, когда подходит игрок. Под землёй прячутся редкие разрушенные аванпосты терранов, гнёзда зергов и храмы зел-нага.'),
            text('The races claim land: a wild base sends parties to take caves and fields and dress them its way. Now and then they land a squad by your home; the game rule starmineAiPressure sets how often.',
                 'Расы захватывают земли: дикая база посылает отряды занять пещеры и поля и обустроить их по-своему. Иногда они высаживают отряд у вашего дома; как часто — задаёт правило starmineAiPressure.'),
            text('Beyond the first three bases near your spawn, $(l)far bases$() of the races lie all over the world: one in about every other region of 1536 by 1536 blocks, never within 1000 blocks of the spawn — a terran base over a technological labyrinth, a protoss base over ancient temples or a zerg lair with its tunnels. They are built when you come within 128 blocks; their sky ships and heroes are only over the first three.$(br2)No monster spawns within 30 blocks of any unit or building of any race.',
                 'Кроме первых трёх баз у места появления, по всему миру лежат $(l)дальние базы$() рас: примерно в каждой второй области 1536 на 1536 блоков, не ближе 1000 блоков к спавну, — база терранов над технолабиринтом, база протоссов над древними храмами или логово зергов с туннелями. Они строятся, когда вы подходите на 128 блоков; корабли и герои — только над первыми тремя.$(br2)Монстры не появляются в 30 блоках от любого юнита или здания любой расы.'),
            text('The races are always foes and keep apart: a pack, a claim or a nydus worm of one race never appears within 96 blocks of another race\'s wild.',
                 'Расы всегда враждебны и не селятся рядом: стая, захват или нидусов червь одной расы не появляется ближе 96 блоков к дикому другой.'),
        ]),
    ]),
    ('endgame', t('Bosses, Artifact, Void', 'Боссы, артефакт, Пустота'), t('The heroes, their helmets, the xel\'naga artifact, the stratosphere and the Void.', 'Герои, их шлемы, артефакт зел-нага, стратосфера и Пустота.'), 'starmine:artifact_pedestal', [
        ('kerrigan', t('Kerrigan', 'Керриган'), 'starmine:kerrigan_crown', [
            entity('kerrigan', 'The Queen of Blades, in the head of the leviathan high in the stratosphere over the zerg lair.',
                   'Королева Клинков — в голове левиафана высоко в стратосфере над логовом зергов.'),
            text('Leaping strike and psionic shift. At 2/3 health she calls her brood (zerglings, a hydralisk, a roach) and her shields come back; at 1/3 she rages for the rest of the fight and calls more. Her bar shows the phase.$(br2)$(l)When beaten$() Amun\'s hold breaks and the hero joins you (see Amun\'s Hold), leaving: Kerrigan\'s Crown, the rest of a zerg suit, 16 chitin shards, 8 diamonds.',
                 'Удар в прыжке и пси-рывок. На 2/3 здоровья зовёт выводок (зерглинги, гидралиск, таракан), щиты восстанавливаются; на 1/3 впадает в ярость до конца боя и зовёт ещё. Фаза видна на полосе.$(br2)$(l)Побеждённый$() герой освобождается от власти Амуна и переходит к вам (см. «Власть Амуна»), оставляя: корона Керриган, остальной костюм зергов, 16 осколков хитина, 8 алмазов.'),
        ]),
        ('raynor', t('Raynor', 'Рейнор'), 'starmine:raynor_helmet', [
            entity('raynor', 'Jim Raynor holds the bridge of the Hyperion high in the stratosphere over the terran base.',
                   'Джим Рейнор держит мостик «Гипериона» высоко в стратосфере над базой терранов.'),
            text('A grenade and a call to the Hyperion (a Yamato blast). At 2/3 health he calls marines, a marauder and a ghost; at 1/3 he rages and calls more.$(br2)$(l)When beaten$() Amun\'s hold breaks and the hero joins you (see Amun\'s Hold), leaving: Raynor\'s Helmet, the rest of a terran suit, 16 shards, 8 diamonds.',
                 'Граната и вызов «Гипериона» (залп Ямато). На 2/3 здоровья зовёт пехотинцев, мародёра и призрака; на 1/3 впадает в ярость и зовёт ещё.$(br2)$(l)Побеждённый$() герой освобождается от власти Амуна и переходит к вам (см. «Власть Амуна»), оставляя: шлем Рейнора, остальной костюм терранов, 16 осколков, 8 алмазов.'),
        ]),
        ('artanis', t('Artanis', 'Артанис'), 'starmine:artanis_helmet', [
            entity('artanis', 'Hierarch Artanis waits under the dome of the mothership high in the stratosphere over the protoss base.',
                   'Иерарх Артанис ждёт под куполом материнского корабля высоко в стратосфере над базой протоссов.'),
            text('A blinding dash and psionic storm. At 2/3 health he calls zealots, a stalker and a high templar and his shields come back; at 1/3 he rages and calls more.$(br2)$(l)When beaten$() Amun\'s hold breaks and the hero joins you (see Amun\'s Hold), leaving: Artanis\'s Helmet, the rest of a protoss suit, 16 shards, 8 diamonds.',
                 'Молниеносный рывок и пси-шторм. На 2/3 здоровья зовёт фанатиков, сталкера и высшего тамплиера, щиты восстанавливаются; на 1/3 впадает в ярость и зовёт ещё.$(br2)$(l)Побеждённый$() герой освобождается от власти Амуна и переходит к вам (см. «Власть Амуна»), оставляя: шлем Артаниса, остальной костюм протоссов, 16 осколков, 8 алмазов.'),
        ]),
        ('amuns_hold', t('Amun\'s Hold', 'Власть Амуна'), 'minecraft:wither_rose', [
            text('The three heroes fight for Amun: black and red motes swirl round them. Their arena on the ship cannot be broken, and they never leave it. $(l)Beaten, a hero does not die$(): at a tenth of its health Amun\'s hold breaks, the hero says so, and it goes over to you $(l)with its whole base and army$() (joined to your base when you have one there). The hero stays on its ship.',
                 'Три героя сражаются за Амуна: вокруг них клубятся чёрные и красные частицы. Их арену на корабле не сломать, и они с неё не уходят. $(l)Побеждённый герой не умирает$(): на десятой части здоровья власть Амуна спадает, герой говорит об этом и переходит к вам $(l)со всей базой и войсками$() (они вливаются в вашу базу, если она там есть). Герой остаётся на корабле.'),
            text('Each hero\'s $(l)part of the artifact\'s key$() stands beside it on the arena. While Amun holds the hero it cannot be broken; once the hero is free, take it.',
                 '$(l)Часть ключа-артефакта$() каждого героя стоит рядом с ним на арене. Пока Амун держит героя, её не сломать; когда герой свободен — забирайте.'),
        ]),
        ('helmets', t('Heroes\' Helmets', 'Шлемы героев'), 'starmine:kerrigan_crown', [
            spot('starmine:kerrigan_crown', 'Kerrigan\'s Crown: a piece of the zerg suit with a perk of its own — regeneration in any armor, a heart every 2 seconds (faster on creep). With the whole zerg suit it heals twice as fast.',
                 'Корона Керриган: часть костюма зергов со своим перком — регенерация в любой броне, сердце раз в 2 секунды (на слизи быстрее). С полным костюмом зергов лечит вдвое быстрее.'),
            spot('starmine:raynor_helmet', 'Raynor\'s Helmet: a piece of the terran suit with heavy plating — 6 armor and 4 toughness instead of 3 and 2, and steadier against knockback.',
                 'Шлем Рейнора: часть костюма терранов с тяжёлой бронёй — 6 брони и 4 прочности вместо 3 и 2, и лучше держит отбрасывание.'),
            spot('starmine:artanis_helmet', 'Artanis\'s Helmet: a piece of the protoss suit; in the whole suit its shields come back three times faster and twice as soon after a hit.',
                 'Шлем Артаниса: часть костюма протоссов; в полном костюме щиты восстанавливаются втрое быстрее и вдвое раньше после удара.'),
        ]),
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
        ('stratosphere', t('The Stratosphere', 'Стратосфера'), 'minecraft:elytra', [
            image('stratosphere', 'The Hyperion over the Earth.', '«Гиперион» над Землёй.'),
            text('A world of its own above the overworld: black sky and stars, the huge Earth below across the horizon, the same sun, moon, day and night. The leviathan, the Hyperion and the mothership hang there over their bases.$(br2)You cannot fly up alone: ride a flying unit (or play as one, or sit in a transport) above height 300. Whatever falls below its floor comes down into the overworld high over the same place; a player falls slowly.',
                 'Отдельный мир над Верхним: чёрное небо и звёзды, внизу во весь горизонт огромная Земля, те же солнце, луна, день и ночь. Там над своими базами висят левиафан, «Гиперион» и материнский корабль.$(br2)Сами туда не взлететь: поднимитесь выше высоты 300 на летающем юните (или в его теле, или в транспорте). Всё, что упадёт ниже её дна, вернётся в Верхний мир высоко над тем же местом; игрок падает медленно.'),
        ]),
        ('void', t('The Void', 'Пустота'), 'starmine:void_portal_frame', [
            image('void', 'The first island of the Void.', 'Первый остров Пустоты.'),
            text('Amun\'s dimension, reached only through the artifact\'s portal: eternal night, a black sky with red flashes, islands in the dark joined by paths of void bricks, little to take (a few mineral crystals and chests), no monsters of the overworld. One arrives on the first island beside the portal back. Falling off the islands is death.$(br2)Four large $(l)islands of riches$() hold a lake inside and a field of six crystals: vespene lakes, one of $(l)terrazine$() (violet, worth twice vespene) and one with $(l)gold minerals$() (each trip worth twice). A lake\'s two outlets take gas buildings and fill again from the lake.$(br2)Your army answers your command panel there as at home; buildings cannot be put down away from your base\'s world.',
                 'Измерение Амуна, куда ведёт только портал артефакта: вечная ночь, чёрное небо с красными всполохами, острова во тьме, связанные тропами из кирпичей Пустоты, мало ресурсов (несколько кристаллов минералов и сундуков), никаких монстров Верхнего мира. Прибытие — на первом острове рядом с порталом назад. Упасть с островов — смерть.$(br2)На четырёх больших $(l)островах богатств$() — озеро внутри и поле из шести кристаллов: озёра веспена, одно — $(l)терразина$() (фиолетовый, вдвое ценнее веспена), и остров с $(l)золотыми минералами$() (каждая ходка вдвое ценнее). Два выхода озера берут газовые здания и наполняются из озера.$(br2)Ваша армия слушается командной панели и там, как дома; здания вдали от мира вашей базы не ставятся.'),
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
        ('sky', t('The Leviathan', 'Левиафан'), 'starmine:glowing_zerg_flesh', [
            text('High in the stratosphere above the lair floats the leviathan, a beast of flesh: passages of flesh, pools of liquid creep, eggs and a chest in its belly. Destroy the lair\'s hatchery: a portal to the beast opens where it stood (the portal hall under the lair wakes too); kill the guards round it and step in.',
                 'Высоко в стратосфере над логовом парит левиафан — чудовище из плоти: ходы в плоти, лужи жидкой слизи, яйца и сундук в чреве. Уничтожьте инкубатор логова: на его месте откроется портал внутрь чудовища (проснётся и зал порталов под логовом); убейте стражей рядом и войдите.'),
        ]),
        ('upgrades', t('Upgrades', 'Улучшения'), 'starmine:evolutionchamber_spawn_egg', [
            text('Evolution chamber: $(l)melee attacks$() (zerglings, banelings, ultralisks), $(l)missile attacks$() (roaches, hydralisks, queens…) and $(l)ground carapace$(). Spire: $(l)flyer attacks$() and $(l)flyer carapace$(). Three levels each: level 2 needs a lair, level 3 a hive.',
                 'Эволюционная камера: $(l)атаки ближнего боя$() (зерглинги, гиблинги, ультралиски), $(l)дальние атаки$() (тараканы, гидралиски, королевы…) и $(l)наземный панцирь$(). Шпиль: $(l)атаки летунов$() и $(l)панцирь летунов$(). По три уровня: для 2-го нужно логово, для 3-го — улей.'),
            text('Each level of a weapons upgrade adds about a tenth of a strike (at least 1) to every strike of the units it is for; each armor level is 1 armor, taken off every strike that reaches health (light units have little armor, armored ones more; bonus damage against light or armored comes on top). Shields: 1 shield armor and a tenth more shields a level. The unit panel shows a unit\'s levels.',
                 'Каждый уровень улучшения оружия добавляет примерно десятую часть удара (не меньше 1) к каждому удару юнитов, для которых оно; каждый уровень брони — 1 броня, она вычитается из каждого удара по здоровью (у лёгких юнитов брони мало, у бронированных больше; бонусный урон по лёгким или бронированным — сверх того). Щиты: 1 броня щитов и на десятую больше щитов за уровень. Панель юнита показывает его уровни.'),
        ]),
        ('boss', t('Boss: Kerrigan', 'Босс: Керриган'), 'starmine:zerg_chestplate', [
            entity('kerrigan', 'The Queen of Blades waits in the leviathan\'s heart. At 2/3 health she calls her brood; at 1/3 she rages. Reward: a full zerg suit, 16 shards, 8 diamonds.',
                   'Королева Клинков ждёт в сердце левиафана. На 2/3 здоровья она зовёт выводок, на 1/3 впадает в ярость. Награда: полный костюм зергов, 16 осколков, 8 алмазов.'),
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
            text('The stratosphere is a world of its own above the overworld: black sky and stars, the huge Earth below across the horizon, the same sun, moon, day and night. You cannot fly up alone: ride a flying unit (or play as one, or sit in a transport) above height 300 and you arrive in the stratosphere over the same place. Whatever falls below its floor drops back into the overworld high over the same place; a player falls slowly for 30 seconds.',
                 'Стратосфера — отдельный мир над Верхним: чёрное небо и звёзды, внизу во весь горизонт огромная Земля, те же солнце, луна, день и ночь. Сам туда не взлететь: поднимитесь выше высоты 300 верхом на летающем юните (или в его теле, или в транспорте) — и окажетесь в стратосфере над тем же местом. Всё, что упадёт ниже её дна, вернётся в Верхний мир высоко над тем же местом; игрок 30 секунд падает медленно.'),
            text('High in the stratosphere above the base hangs the battlecruiser Hyperion: a hull with a hammer nose, a bridge, turrets and engines, two decks of cabins and passages, a hangar, an engine room with the reactor core and a hold of chests. Destroy the base\'s main building: a portal to the ship opens where it stood (the portal hall under the base wakes too); kill the guards round it and step in.',
                 'Высоко в стратосфере над базой висит линкор «Гиперион»: корпус с носом-«молотом», мостик, турели и двигатели, две палубы кают и коридоров, ангар, машинное отделение с реакторным ядром и трюм с сундуками. Уничтожьте главное здание базы: на его месте откроется портал на корабль (проснётся и зал порталов под базой); убейте стражей рядом и войдите.'),
        ]),
        ('upgrades', t('Upgrades', 'Улучшения'), 'starmine:engineeringbay_spawn_egg', [
            text('Engineering bay: $(l)infantry weapons$() and $(l)infantry armor$(). Armory: $(l)vehicle weapons$(), $(l)ship weapons$() and $(l)vehicle and ship plating$(). Three levels each; levels 2 and 3 of the infantry upgrades need an armory.',
                 'Инженерный отсек: $(l)оружие пехоты$() и $(l)броня пехоты$(). Оружейная: $(l)оружие техники$(), $(l)оружие кораблей$() и $(l)броня техники и кораблей$(). По три уровня; для 2-го и 3-го уровня пехотных улучшений нужна оружейная.'),
            text('Each level of a weapons upgrade adds about a tenth of a strike (at least 1) to every strike of the units it is for; each armor level is 1 armor, taken off every strike that reaches health (light units have little armor, armored ones more; bonus damage against light or armored comes on top). Shields: 1 shield armor and a tenth more shields a level. The unit panel shows a unit\'s levels.',
                 'Каждый уровень улучшения оружия добавляет примерно десятую часть удара (не меньше 1) к каждому удару юнитов, для которых оно; каждый уровень брони — 1 броня, она вычитается из каждого удара по здоровью (у лёгких юнитов брони мало, у бронированных больше; бонусный урон по лёгким или бронированным — сверх того). Щиты: 1 броня щитов и на десятую больше щитов за уровень. Панель юнита показывает его уровни.'),
        ]),
        ('boss', t('Boss: Raynor', 'Босс: Рейнор'), 'starmine:terran_helmet', [
            entity('raynor', 'Jim Raynor holds the Hyperion\'s bridge. At 2/3 health he calls his marines; at 1/3 he rages. Reward: a full terran suit, 16 shards, 8 diamonds.',
                   'Джим Рейнор держит мостик «Гипериона». На 2/3 здоровья он зовёт пехотинцев, на 1/3 впадает в ярость. Награда: полный костюм терранов, 16 осколков, 8 алмазов.'),
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
            text('High in the stratosphere above the base floats the mothership: a disc with a dome, a golden ring and arms, a vortex beneath, six technical halls with power crystals and a hall of chests. Destroy the base\'s main building: a portal to the ship opens where it stood (the portal hall under the base wakes too); kill the guards round it and step in.',
                 'Высоко в стратосфере над базой парит материнский корабль: диск с куполом, золотое кольцо и рукава, воронка снизу, шесть технических залов с энергокристаллами и зал с сундуками. Уничтожьте главное здание базы: на его месте откроется портал на корабль (проснётся и зал порталов под базой); убейте стражей рядом и войдите.'),
        ]),
        ('upgrades', t('Upgrades', 'Улучшения'), 'starmine:forge_spawn_egg', [
            text('Forge: $(l)ground weapons$(), $(l)ground armor$() and $(l)shields$() (every unit and building with shields). Cybernetics core: $(l)air weapons$() and $(l)air armor$(). Three levels each: forge levels 2-3 need a twilight council, air levels 2-3 a fleet beacon.',
                 'Кузница: $(l)наземное оружие$(), $(l)наземная броня$() и $(l)щиты$() (все юниты и здания со щитами). Кибернетическое ядро: $(l)воздушное оружие$() и $(l)воздушная броня$(). По три уровня: для 2–3 уровня кузницы нужен Сумрачный совет, для воздушных — маяк флота.'),
            text('Each level of a weapons upgrade adds about a tenth of a strike (at least 1) to every strike of the units it is for; each armor level is 1 armor, taken off every strike that reaches health (light units have little armor, armored ones more; bonus damage against light or armored comes on top). Shields: 1 shield armor and a tenth more shields a level. The unit panel shows a unit\'s levels.',
                 'Каждый уровень улучшения оружия добавляет примерно десятую часть удара (не меньше 1) к каждому удару юнитов, для которых оно; каждый уровень брони — 1 броня, она вычитается из каждого удара по здоровью (у лёгких юнитов брони мало, у бронированных больше; бонусный урон по лёгким или бронированным — сверх того). Щиты: 1 броня щитов и на десятую больше щитов за уровень. Панель юнита показывает его уровни.'),
        ]),
        ('boss', t('Boss: Artanis', 'Босс: Артанис'), 'starmine:protoss_chestplate', [
            entity('artanis', 'Hierarch Artanis waits in the mothership. At 2/3 health he calls his guard and restores his shields; at 1/3 he rages. Reward: a full protoss suit, 16 shards, 8 diamonds.',
                   'Иерарх Артанис ждёт в материнском корабле. На 2/3 здоровья он зовёт стражу и восстанавливает щиты, на 1/3 впадает в ярость. Награда: полный костюм протоссов, 16 осколков, 8 алмазов.'),
        ]),
    ],
}
RACE = {'ZERG': (t('Zerg', 'Зерги'), t('The swarm: larvae, creep, burrowing and nydus corridors. Hostile to every living thing.', 'Рой: личинки, слизь, закапывание и нидус-коридоры. Враждебен всему живому.'), 'starmine:hatchery_spawn_egg'),
        'TERRAN': (t('Terrans', 'Терраны'), t('Exiles of humanity: SCVs, bunkers, siege tanks and buildings that fly.', 'Изгнанники человечества: КСМ, бункеры, осадные танки и летающие здания.'), 'starmine:commandcenter_spawn_egg'),
        'PROTOSS': (t('Protoss', 'Протоссы'), t('An ancient race of psionic warriors: pylons, warp, shields and the mothership.', 'Древняя раса псионных воинов: пилоны, призыв, щиты и материнский корабль.'), 'starmine:nexus_spawn_egg')}
SUB = [('units', t('Troops', 'Войска')), ('buildings', t('Buildings', 'Постройки')), ('general', t('General Information', 'Общая информация'))]
SUB_ICON = {('ZERG', 'units'): 'zergling', ('ZERG', 'buildings'): 'spawningpool', ('TERRAN', 'units'): 'marine',
            ('TERRAN', 'buildings'): 'barracks', ('PROTOSS', 'units'): 'zealot', ('PROTOSS', 'buildings'): 'gateway'}
HEROES = {'KERRIGAN', 'RAYNOR', 'ARTANIS'}
# what a troop does that its numbers do not say (owner 17.10)
NOTES = {
    'HELLBAT': t('A unit of its own: trained at a factory once there is an armory, or a hellion turned into one (Hellbat Mode, and back). Biological and mechanical: a medivac heals it, an SCV repairs it. Its flames set foes alight; +12 against light.',
                 'Отдельный юнит: тренируется на заводе, когда есть оружейная, или геллион превращается в него (режим геллбата и обратно). Биологический и механический: медэвак лечит, КСМ чинит. Огонь поджигает врагов; +12 по лёгким.'),
    'WIDOW_MINE': t('Burrow it (R): hidden in the ground, it fires a $(l)sentinel missile$() at the first foe within 5, on the ground or in the air — 125 (+35 to shields), 40 to the foes around — then rearms for 29 s. Unburrowed it has no attack. Detectors see it.',
                    'Закопайте её (R): спрятанная в земле, она пускает $(l)ракету «Страж»$() в первого врага ближе 5, на земле или в воздухе — 125 (+35 по щитам), 40 врагам рядом — и перезаряжается 29 с. Не закопанная — без атаки. Детекторы её видят.'),
    'VIKING_ASSAULT': t('A unit of its own: a viking that has landed (Assault Mode) walks and fires its twin gatling cannon at the ground, +8 against mechanical; it takes off again as a viking.',
                        'Отдельный юнит: севший викинг (режим посадки) ходит и стреляет по земле спаренными пулемётами, +8 по механическим; взлетает снова викингом.'),
    'LIBERATOR': t('In the air it fires concord cannons at flying foes, with splash. $(l)Defender Mode$() (E): it stops and strikes ground units in its zone for 75 a shot; it cannot move or shoot air until it leaves the mode.',
                   'В воздухе бьёт летающих врагов залпами с уроном по площади. $(l)Режим защитника$() (E): останавливается и бьёт наземные цели в своей зоне по 75 за выстрел; пока не выйдет из режима, не двигается и не стреляет по воздуху.'),
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
