# Передача локальной работы по моду StarMine (задание 4, 19–20.10.2026)

Документ для агента, который продолжит работу локально на машине владельца (`C:\MODDING`). Ведётся по ходу
работы: раздел «Состояние» — текущее, без истории. Отчёт владельцу по заданию — `docs/CLOUD_REPORT_5.md`
(пишется в конце). Отвечать владельцу по-русски.

## 1. Что за задание

`docs/CLOUD_TASK_4.md` (прочитать целиком). Коротко:

1. влить `origin/main` (облачные изменения с 17.10);
2. модели новых форм (`spinecrawler_uprooted`, `sporecrawler_uprooted`, `overseer_oversight`,
   `observer_surveillance`);
3. **цвета команд**: текстуры без запечённого цвета расы + маски `<текстура>_team.png`
   (воксели: `<вид>_voxel_team.png`), проверка в игре, проверка масок в `tools/check_model_package.ps1`;
4. звуки каста 7 новых способностей;
5. личные JAR (`original` и `voxel`) в `C:\MKTEMP\starmine-1910`, тесты, push в `origin main`;
6. отчёт `docs/CLOUD_REPORT_5.md` со списком ручной проверки (раздел 6 задания).

Владелец также попросил **запустить игру на тест со всеми моделями и звуками**.

## 2. Где что лежит

| Что | Где |
| --- | --- |
| Мод (git, `main` → `origin` https://github.com/hometlt/minecraft) | `C:\MODDING\StarMine` |
| Прототип установщика моделей (вне git!) | `C:\MODDING\parked\GAME-MINECRAFT\starmine-export` (`survey.mjs`, `fetch-assets.mjs`, `build-assets.mjs`, `kinds.mjs`, `smm.mjs`, `icons.mjs`) |
| Воксельный конвейер (вне git) | `C:\MODDING\parked\GAME-MINECRAFT\voxel-preview` (`starmine-all.mjs`, `preview.mjs`, `animated.mjs`) |
| Экспорт звуков и значков карточки (git ModKit-Blizzard) | `C:\MODDING\modkit-extensions-blizzard\scripts\export-starmine-{sounds,icons}.mjs`, таблица способностей — `starcraft2/modules/mod/shared/export/unit-sounds.ts` (`STARMINE_ABILITIES`) |
| Рабочий каталог установщика (сырые M3/UVM, survey) | `C:\MKTEMP\starmine-work` (`raw/<модель>/model.uvm`, `raw/manifest.json`, `survey.json`, `team-masks.json`) |
| Кеш DDS→PNG текстур (без подкраски) | `C:\MKTEMP\starmine\textures` |
| Бинарник obj2voxel v1.3.4 (SHA-256 `862ee8c4…fc6`) | `C:\MKTEMP\uvm-voxel-preview\obj2voxel-v1.3.4.exe` (скачан заново 19.10 с разрешения владельца — прежний кто-то удалил) |
| ffmpeg | `C:\Apps\ffmpeg\bin\ffmpeg.exe` — **всегда** `export FFMPEG=C:/Apps/ffmpeg/bin/ffmpeg.exe` (поиск в WinGet возвращает пусто, скрипты тогда падают с `The argument 'file' cannot be empty`) |
| Результаты этого задания | `C:\MKTEMP\starmine-1910\` (см. §4) |
| Установленная SC2 | `F:\GAMES\Blizzard\StarCraft II` |

## 3. Правила и ловушки

- Ассеты SC2 и JAR **никогда** не класть в git.
- Сборки и тесты Gradle — с `-PstarmineBuildRoot=F:/MKTEMP/sm-build-root` и `JAVA_TOOL_OPTIONS=-Xmx3g`
  (на C: мало места, памяти машины мало; рядом работают чужие тяжёлые процессы — не убивать).
- Тесты: `./gradlew runGametest -PstarmineBuildRoot=... -PstarmineGameTests=light|heavy|<полное.имя.Класса>`,
  отчёт — `F:/MKTEMP/sm-build-root/gametest/junit.xml`. `tools/run_gametests.sh` пишет в `build/`, поэтому
  вызывал Gradle напрямую.
- Клиент на тест: `./gradlew runHarness -PstarmineBuildRoot=F:/MKTEMP/sm-build-root -PstarmineHarness=<сценарий>
  -PstarmineAssetsDir=<корень над assets/>`; скриншоты — `F:/MKTEMP/sm-build-root/harness/run/screenshots/`.
  Сценарий `teams` (добавлен 19.10) — ряды диких, своих и юнитов Амуна плюс новые формы.
- git: `git -c safe.directory='*' …`, коммитить только свои пути (`commit -- <пути>`), не stash/reset/switch.
  В StarMine `core.autocrlf=true`: после генераторов `git status` может показывать «изменённые» файлы без
  реального диффа (`git diff` пуст) — это не правка. Python-генераторы на Windows пишут CRLF — перед коммитом
  `sed -i 's/\r$//'`.
- `check_sounds.py` под Windows запускать с `PYTHONUTF8=1`.
- `decodePngSamples` (codec-kit) — **асинхронная**, нужен `await`.
- В `modkit-extensions-blizzard` много чужих незакоммиченных правок — трогать и коммитить только
  `starcraft2/modules/mod/shared/export/unit-sounds.ts`.

## 4. Состояние (обновляется)

### Слияние и тесты

- `origin/main` влит fast-forward до `8671665` (локальных расхождений не было). Компиляция — ок.
- `gen_sc2_models.py`: было 157, добавлена форма `overlord_sacs` (OverlordTransport) → **158** (коммит `bd04276`).
  `gen_guide_book.py`, `gen_goals.py` — файлы побайтно не меняются.
- Тесты после слияния: light **267/267**; heavy **111/112** — упал
  `DroneGameTests.droneBeyondItsHatcheryFindsTheMinerals` (план поиска пути кончился), отдельно класс
  `DroneGameTests` — **13/13**: нестабилен под нагрузкой (в это время шёл экспорт моделей).

### Модели и цвета команд (сделано, в `parked` — вне git)

- `starmine-export/kinds.mjs`: клипы форм `uprooted` (`Stand`/`Walk`), `oversight` (`Stand 01`/`Spell`),
  `surveillance` (`Stand Work`), `sacs`; укоренённый ползун стоит в `Stand Burrow` (споровый ползун атакует
  только в `Attack Burrow 01`, значит укоренённое состояние = burrow), раньше стоял в `Stand` (это поза на
  ногах). `TEAM_MASK_SUFFIX` берётся из списка мода.
- `starmine-export/build-assets.mjs`, шаг текстур: диффуз пишется как есть (RGB, A=255); маска
  `<текстура>_team.png`: RGB = яркость×2 (серый), A = 255 − альфа диффуза SC2; пиксели с долей < 8/255 —
  прозрачные; маска пишется, если таких пикселей > 0,2 %. Список — `C:\MKTEMP\starmine-work\team-masks.json`:
  **134 текстуры с маской, 25 без** (герои, кости/кровь, повелитель, `overlordtransport_dif`, корруптор,
  щит дисраптора, конструкты зел-нага и т. п. — у них альфа диффуза не задаёт цвет команды). Флага team colour в
  материале M3 (UVM extras) нет — зона берётся по альфе, как и прежняя «запечка».
- Воксели: `voxel-preview/preview.mjs` `voxelTeamWeights` — второй прогон obj2voxel по «текстурам веса»
  (серый = альфа маски) на той же сетке, соединение ячеек по координатам, карта «цвет атласа → средняя доля»
  в `<модель>/team-weights.json`; `build-assets.mjs` (voxel) пишет `<вид>_voxel_team.png` той же раскладки, что
  атлас. Ограничение: у одинакового цвета внутри и вне зоны доля усредняется.
- Вокселизация 158/158 (`C:\MKTEMP\starmine-1910\voxel-uvm`), Абатур/Свонн/Хорнер теперь тоже есть.
  Воксельный пакет: **144 маски** `_voxel_team`.
- Клиент мода (`SkinnedModelDrawer.drawTeam`, `entityTranslucent`) не менялся: в игре проверено харнессом
  `teams` — дикие терраны синие, свои юниты цвета игрока (зелёный), Амун красный, зерги фиолетовые; два кадра
  с интервалом 10 тиков совпадают, мерцания и затемнения нет (скриншоты `harness-teams-*.png`, сравнение
  `C:\MKTEMP\starmine-1910\flicker-tank.png`).

### Каталоги результатов `C:\MKTEMP\starmine-1910`

| Каталог | Содержимое |
| --- | --- |
| `original\` | корень для `-PstarmineAssetsDir`: модели, текстуры с масками, значки карточки (189) и яиц (130); звуки — добавить из `sounds\` |
| `voxel\` | воксельный пакет (копия `original` + воксельные SMM, атласы и маски) |
| `voxel-uvm\` | промежуточные воксельные UVM и `team-weights.json` |
| `sounds\` | экспорт звуков (`assets/starmine/sounds/**`, `sounds.json`) |
| `icons\` | экспорт значков карточки |
| `*.log` | логи всех шагов |

### Звуки

- В `unit-sounds.ts` добавлены 7 новых способностей и 9 недостававших (`greater_spire`, `ventral_sacs`,
  `defender_mode`, `salvage`, `lock_on`, `auto_turret`, `interference_matrix`, `anti_armor_missile`,
  `shade_cancel`). Подмены, у которых в SC2 нет своего звука каста: `microbial_shroud` ←
  `Infestor_PlagueAttackLaunch`, `uproot` ← `Zerg_BurrowLarge`, `greater_spire`/`ventral_sacs` ←
  `Zerg_BuildingMorphStart`. Найдены родные: `Overlord_GenerateCreep`, `Overseer_OversightEyeBirth`,
  `Observer_SiegeMode_Start`, `OrbitalCommand_CalldownExtraSuppliesDrop`; `contaminate` — из Data.
  Нет звука вообще: `lower_depot`.
- Итог (третий прогон, `sounds3.log`): **503 из 705** событий, 0 отсутствующих файлов. Без звука в SC2:
  `ability.lower_depot`, `ability.salvage`; 66 `.fire` (юниты без оружия), 42 `.attack` и 43 `.hurt` (здания и
  юниты без таких реплик), 1 `.ambient`, 1 `.death`. Звуки разложены в `original\` и `voxel\`.
- Таблица закоммичена в ModKit-Blizzard: `77ba320e` (только `unit-sounds.ts`, не отправлено — push ModKit не
  просили). Поиск имён CSound: `C:\MKTEMP\sndprobe\probe.mjs` (`pnpm --dir starcraft2 exec tsx
  /c/MKTEMP/sndprobe/probe.mjs "<regex>"` из `modkit-extensions-blizzard`).

### Воксели — отложены (решение владельца 20.10)

Воксельные модели тормозят в игре; владелец решил их **отбросить** и позже сделать другой алгоритм создания
воксельных моделей. Воксельный JAR не собирается. Сделанное для масок вокселей (`voxelTeamWeights` в
`voxel-preview/preview.mjs`, атлас `_voxel_team.png` в `build-assets.mjs`) и пакет `C:\MKTEMP\starmine-1910\voxel\`
остаются как заготовка; клиент мода рисует маску вокселей тем же `drawTeam`.

### JAR и игра

- Личный JAR с исходными моделями: `C:\MKTEMP\starmine-1910\jars\starmine-1.0.0-original.jar` (269 МБ).
  `check_model_package.ps1`: 3061 файл совпадает с ассетами, 134 маски; без маски — `abathur`, `artanis`,
  `corruptor`, `hybrid`, `karax`, `kerrigan`, `overlord`, `overlord_sacs`, `protosstrader`, `raynor`,
  `voidmonolith`, `voidshard`, `xelnagaconstruct`, `xelnagasentinel` (у повелителя и корруптора в SC2 цвет
  команды, видимо, не в альфе диффуза — не исследовано).
- Игра для владельца: `./gradlew runClient -PstarmineBuildRoot=F:/MKTEMP/sm-build-root
  -PstarmineAssetsDir=C:/MKTEMP/starmine-1910/original` (лог `C:\MKTEMP\starmine-1910\client.log`).

### Текущий шаг (20.10, ~01:25)

- Задание 4 выполнено, кроме отложенных вокселей. Итоговые тесты на `bd04276`: light **267/267**, heavy **112/112**.
- Мод: коммиты `bd04276` (код) и коммит с `CLOUD_REPORT_5.md` и этим документом отправлены в `origin main`.
  ModKit-Blizzard `77ba320e` (таблица звуков) — закоммичен локально, **не отправлен** (push ModKit не просили).
- Если в `F:\MKTEMP\sm-build-root\libs` лежит `starmine-1.0.0-voxel.jar` — недописанный остаток прерванной сборки,
  не использовать. Демоны Gradle и чужие процессы не останавливать.

## 5. Что осталось

1. Ручная проверка в игре владельцем (список — раздел 6 `CLOUD_TASK_4.md`, повторён в `CLOUD_REPORT_5.md`).
3. Правки `parked/GAME-MINECRAFT` (вне git) описаны выше — при переносе установщика в ModKit их не потерять.
4. Повелитель и корруптор без маски цвета команды — найти, где у них team colour в M3.
5. Новый алгоритм воксельных моделей (позже, по решению владельца).
