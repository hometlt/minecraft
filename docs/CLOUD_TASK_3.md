# Задание для агента: подхватить изменения 17.10, доработать конвертер моделей, собрать версию на тест

Постановка от владельца, 17.10.2026. Репозитории: мод https://github.com/hometlt/minecraft и ModKit
(`hometlt/ModKit`, рабочее пространство с `modkit-core` и `modkit-blizzard`). Отчёты владельцу — **на русском**.
Общие правила и устройство кода — `docs/CLOUD_TASK.md` (§1–§3, §6) и `docs/ARCHITECTURE.md`; что мод ждёт от
ModKit — `docs/SC2_EXPORT.md`. Прочитать до первой правки. Итоговый отчёт — `docs/CLOUD_REPORT_4.md`.

---

## 1. Подхватить изменения и смержить

- `main` мода — **4bf2d81** (в нём ветка `claude/gallant-ride-em1bhh` и наложение карт SC2 из `main`). Если у тебя
  своя ветка, влей в неё `main` merge-коммитом (без rebase и force-push); конфликты разрешать, сохраняя обе стороны.
  - `src/main/resources/fabric.mod.json`, список `fabric-gametest`: объединение классов обеих сторон.
  - Документация: оба раздела.
- После слияния, по порядку:
  1. `gradlew compileJava compileClientJava`;
  2. `python3 tools/gen_sc2_models.py` (список моделей не должен измениться — иначе закоммитить новый);
  3. `tools/run_gametests.sh` — light, затем heavy; на момент 4bf2d81 все 300 тестов зелёные.
- Что пришло в 17.10 (подробности — README, справочник `tools/gen_guide_book.py`, история коммитов):
  - Большой шпиль — превращение самого шпиля: `Modes.GREATER`, способность `GREATER_SPIRE`, вид выше. Без него нет
    владык стай.
  - Опухоли слизи расползаются только у баз и с целью: `creep/TumorSpread`.
  - Базы Нижнего мира стоят в больших залах (`DimensionBases.hall`); герои Энда висят над базами.
  - Вторжение Амуна:
    - осколки падают ночью и сами выпускают войска;
    - сила растёт по дням (`AmunInvasion.power/troops/every`);
    - осколки и разломы работают в 256 блоках от игрока;
    - на миникарте видны как ◆ и ◎.
  - Рабочие Амуна ходят без телепортов. Тош невидим временно, Зератул лишь изредка показывается.
  - Модели форм `<вид>_<форма>.smm` теперь загружаются (`StarMineModels`), если они есть в ассетах.
  - Тесты: наборы `-PstarmineGameTests=light|heavy` и скрипт `tools/run_gametests.sh`.

## 2. Конвертер моделей (ModKit)

Список моделей теперь в git мода — **`tools/sc2-export/kinds.mjs`**. Он читает `docs/sc2-models.json`, который
делает `tools/gen_sc2_models.py` из `UnitKind` и форм `ModeLook`. Это 142 модели: виды юнитов с моделью гибели,
формы и опухоль слизи.

1. **Источник списка.** Установщик (`survey.mjs` / `fetch-assets.mjs` / `build-assets.mjs`, прототип в
   `parked/GAME-MINECRAFT/starmine-export`) берёт список из `<StarMine>/tools/sc2-export/kinds.mjs`. Свой список он
   больше не держит.
   - `import { KINDS, selected, teamColor } from '<StarMine>/tools/sc2-export/kinds.mjs'`.
   - Поля записи: `model`, `kind`, `race`, `unit`, `mod`, `death`, `form`, `verify`, `teamColor`.
   - `selected()` учитывает `ONLY=...`.
   - Путь к моду — параметр `--starmine`, как у `export-starmine-icons.mjs`. Если прототип стоит переносить в
     `modkit-blizzard/scripts`, по правилам рабочего пространства, — перенести (загрузчик модов —
     `starcraft2/modules/mod/shared/workspace`, своего не писать).
2. **Новые модели.** Экспортировать все записи списка. Новые с 17.10:
   - юниты и здания: `cyclone`, `raven`, `autoturret`, `sensortower`, `lurkerden`;
   - герои: `tosh`, `zeratul`, `zagara`, `horner`, `stukov`, `selendis`;
   - формы: `spire_greater` (GreaterSpire), `commandcenter_orbital`, `commandcenter_planetary`, взлетевшие здания
     (`*_airborne`), `supplydepot_lowered`, `gateway_warpgate`, `siegetank_sieged`, `liberator_defender`,
     `warpprism_phasing`, `thor_high_impact`, закопанные `*_burrowed`.
   
   Для форм модель гибели не нужна (`death: false`).
3. **`verify: true`.** Юниты SC2 для героев вне мультиплеера угаданы: Абатур, Свонн, Каракс, Тош, Зератул, Загара,
   Хорнер, Стуков, Селендис.
   - Найти настоящие юниты, актёры и модели в данных кампаний (VoidStory, SwarmStory, LibertyStory) и в
     кооперативных модах.
   - Исправить таблицу `SOURCES` в `tools/gen_sc2_models.py`, пересобрать JSON, убрать `verify` у проверенных.
   - Нет модели в данных игры — оставить, как есть: клиент рисует замену через `UnitKind.look()`. Записать это в отчёт.
4. **Цвет команды.** Подкраска по расе — `teamColor(kind)`: зерги `#C060FF`, терраны `#60A0FF`, протоссы `#FFD040`.
5. **Воксельный режим.** Пакет должен покрыть **все** модели списка.
   - `selectedModels` в `models/sc2/model-package.json` = все `model` из `KINDS`; модели гибели — по `death`.
   - `build.gradle` отказывается собирать частичный пакет. Модели, которых нет в игре, перечислить в маркере явно
     (`lossesAreExplicit`), а не пропускать молча.
   - Масштаб не удваивать: размер задаёт `UnitKind`.
6. **Проверка конвертера** — тестами ModKit по `modkit-blizzard/AGENTS.md`
   (`npx vitest run --config test/vitest.config.mjs <пути>`, нужные гейты). Изменение публичного API ядра — через
   vendor hop.

## 3. Собрать новую версию на тест

1. Экспорт из установленной SC2 владельца в `sc2-assets/`: модели (обычные и `voxel`), значки
   (`export-starmine-icons.mjs`), звуки (`export-starmine-sounds.mjs`, язык — `--voice-locale`, по умолчанию английский).
2. Проверка звуков: `python3 tools/check_sounds.py sc2-assets`.
3. Сборка личного JAR:
   `gradlew build -PincludeSc2Assets -PstarmineModelMode=voxel -PstarmineAssetsDir=<sc2-assets>` (и `original`
   для сравнения).
4. Проверка архива: `tools/check_model_package.ps1`.
5. Ассеты SC2 и личные JAR в git **не класть**; передать владельцу путь к файлам.
6. `tools/run_gametests.sh` на итоговом коммите — все зелёные; упавшее перезапустить отдельно и записать в отчёт
   оба результата.
7. Короткий список ручной проверки для владельца в отчёте:
   - большой шпиль меняет вид;
   - новые модели героев в Нижнем мире и Энде;
   - зал базы в Нижнем мире;
   - осколки ночью и на миникарте;
   - опухоли расползаются к минералам;
   - формы юнитов (осада, закапывание, взлёт).

## Не входит

Врата Пустоты — владелец доработает после теста. Способности SC2, которых в моде нет: микробная завеса, укоренение
краулеров, сброс припасов, выделение слизи, заражение, разбор бункера, режимы наблюдения. Это отдельная задача, не
эта.
