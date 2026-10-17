# Что StarMine ждёт от Modkit (экспорт из StarCraft II)

Модели, текстуры и звуки мод не содержит: их готовит Modkit из вашей копии StarCraft II в каталог
`sc2-assets/` (`assets/starmine/**/sc2/**` и `assets/starmine/sounds.json`). Здесь — что именно нужно.

## Звуковые события

| Событие | Когда звучит | Что взять из SC2 |
| --- | --- | --- |
| `<юнит>.fire` | **каждый** выстрел или удар оружия юнита (и игрока в его роли) | звук оружия юнита (weapon launch / impact), не реплику |
| `<юнит>.attack` | изредка (каждый 4-й выстрел) | реплика атаки (`Attack` VO) |
| `<юнит>.ambient` | раз в ~90 секунд | реплики «Yes» / «What» / idle |
| `<юнит>.hurt` | не чаще раза в 8 секунд | звук ранения |
| `<юнит>.death` | смерть | звук гибели |
| `ability.<id>` | каждое применение способности | звук каста способности (stim, blink, storm…) |

`<юнит>` — имя из `UnitKind` в нижнем регистре без подчёркиваний (`siegetank`), `<id>` — из `Ability`
(`psionic_storm`). Полный список событий и проверка готового каталога:

```text
python tools/check_sounds.py sc2-assets
```

Пока события нет в `sounds.json`, Minecraft просто молчит на нём (в логе клиента — предупреждение).

## Значки карточки команд

Каждое действие карточки (`client/CommandScreen`) — движение, стоп, удержание, патруль, атака, сбор,
возврат груза, строительство и продвинутое строительство, точка сбора, отмена, каждая способность
`Ability` и каждое исследование `Research` (оружие и броня — по расе и уровню) — показывает значок
своей кнопки SC2. Какая кнопка у какого действия — одна таблица `race/Sc2Buttons` (id `CButton` в
Data VoidMulti); проверка — игровой тест `RaceGameTests.everyCommandHasAnSc2Button`.

Экспорт (ModKit-Blizzard, `scripts/export-starmine-icons.mjs`, общий код —
`starcraft2/modules/mod/shared/export/command-icons.ts`): читает id кнопок из `Sc2Buttons.java`,
разрешает `Button.Icon` через загрузчик модов, переводит DDS в PNG кодеком изображений Modkit и
масштабирует до 64 px (ffmpeg):

```text
pnpm --dir starcraft2 exec tsx ../scripts/export-starmine-icons.mjs --starmine <StarMine> --out <sc2-assets>
```

Результат: `assets/starmine/textures/gui/sc2/command/<id кнопки в нижнем регистре>.png` и индекс
`assets/starmine/sc2-command-icons.json` (`{ имя: { button, icon } }`). Нет PNG — карточка рисует подпись.
Значки юнитов и зданий — прежние `textures/item/sc2/<вид>.png` (яйца призыва).

## Язык реплик

По умолчанию реплики **английские**; язык — выбор при установке:
`--voice-locale=ruRU` (или переменная `MODKIT_VOICE_LOCALE`). Локализованные ассеты SC2 лежат в
`<locale>.SC2Assets/…`; на установке другого языка CASC называет файлы всех языков, но хранит только
свои — экспортёр берёт выбранный язык онлайн. Общий код выбора: ModKit-Blizzard,
`starcraft2/modules/mod/shared/core/voice-locale.ts` (ветка `main`):
`voiceLocaleFrom(argv, env)`, `localizedAssetPath(path, locale)`, `pickVoiceLocale(wanted, held)`.

## Почему немецкая речь

Установка SC2 на немецком хранит только `deDE.SC2Assets`; экспортёр брал локализованные звуки «как
есть», то есть немецкие. Исправление — пропускать каждый локализованный путь через
`localizedAssetPath(path, voiceLocaleFrom(...))` перед чтением.

## Воксельные модели

Воксельный пакет обязан покрыть **все** модели (`build.gradle` отказывается собирать частичный пакет).
Если в игре часть юнитов «не воксельные», значит сборка шла в режиме `original` или каталог
`starmineAssetsDir` указывал на старый пакет; проверка готового JAR — `tools/check_model_package.ps1`.

## Герои

Kerrigan, Raynor и Artanis рисуются своими моделями из StarCraft II, если они есть в ассетах:
в мультиплеерных данных (VoidMulti) героев нет, Modkit берёт их из кампании
`Campaigns/VoidStory.SC2Campaign` — Керриган `KerriganVoid` (`KerriganInfested.m3`), Рейнор
`RaynorCommando` (`MarineRaynorHEV.m3`), Артанис `ArtanisVoid` (`Artanis.m3`, гибель — `Artanis_Death.m3`).
Оттуда же их реплики и звуки их способностей. Без этих моделей клиент по-прежнему рисует замену
(`UnitKind.look()`: королева, морпех, зилот с подкраской).

## Карты

Карта StarCraft II ложится на обычный мир (`world/MapOverlay.java`): под землёй — обычная генерация
Minecraft, над ней — рельеф, утёсы и объекты карты блоками. Путь Modkit: SC2Map → UVP (мир с моделями и
текстурами) → кодировщик `minecraft-world-overlay` расширения Minecraft → датапак с
`data/modkit/world_overlay/<имя>.nbt`. Три блока на клетку SC2; блок земли — по цвету и имени текстуры;
деревья, камни и утёсы — вокселизация их моделей. Минеральные поля становятся кристаллами, гейзеры —
жилами веспена от бедрока; первые три точки старта — места баз терранов, протоссов и логова зергов,
четвёртая (или центр карты) — точка появления.

Установка: при создании мира «Пакеты данных» → перетащить zip карты (или положить его в
`saves/<мир>/datapacks` до первого входа). Уже сгенерированные чанки карта не меняет.

## Где экспортёр

- Звуки: ModKit-Blizzard, `scripts/export-starmine-sounds.mjs`
  (`starcraft2/modules/mod/shared/export/unit-sounds.ts`):
  `pnpm --dir starcraft2 exec tsx ../scripts/export-starmine-sounds.mjs --starmine <StarMine> [--out <каталог>] [--voice-locale=ruRU]`.
- Модели и текстуры: прототип установщика `kinds.mjs` / `survey.mjs` / `fetch-assets.mjs` /
  `build-assets.mjs` (в рабочем пространстве Modkit, `parked/GAME-MINECRAFT/starmine-export`, вне git);
  герои — с `MOD=Campaigns/VoidStory.SC2Campaign ONLY=kerrigan,raynor,artanis`.
