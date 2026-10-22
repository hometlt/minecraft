# Масштабы SC2: состояние проверки 21.10.2026

## Уточнение после выбора пользователем VoidMulti из CASC

Обычный CASC codec успешно открыл установленную SC2: 666719 записей,
root key `9c1f3d3d30ca252e33fc67ca05cbe1ea`. Для этого MCP запущен без
перехвата корня через content-source config. Через публичные `blizzard/casc.entry-info`,
`container.extract` и `resources/read` получены 28 каталогов UnitData, ActorData,
ModelData, FootprintData для Core, Liberty, LibertyMulti, Swarm, SwarmMulti, Void,
VoidMulti. Сохранение проверяет размер и SHA256 целого файла и каждого чанка.
Игра не читалась обходным CASC-экспортёром и не изменялась.

Экспорт и квитанции: `C:/MKTEMP/starmine-sc2-scale-catalogs/receipts.json`;
воспроизводящий публичные MCP-вызовы клиент: `starmine-mcp-export-scale-catalogs.py`.
`VoidMulti/Base.SC2Data/GameData/UnitData.xml`: 179627 байт,
SHA256 `5a077cd270e105e68501f428fdd30b7423bec83e31a617cae26044326d2c3a5a`.

Попытка composed workspace семи извлечённых пакетов дошла до SC2 resolved provider,
но анализ отклонён: `analyze: value has more than 4000000 nodes`.
Доказательство: `C:/MKTEMP/starmine-voidmulti-calculated-node-budget-result.json`.
Отдельный VoidMulti открывается как `partial`, snapshot
`provider-token:sc2-data-a73633683e919c4e`; список опубликован, однако запрос
`list_subjects` по возвращённому workspace handle отклоняется как `unknown-handle`.
Обычный codec успешно перечисляет Actor/Battlecruiser, но `select_subject` возвращает
`SC2 entity calculations are published by the resolved-data service`.
Это два конкретных ограничения публичного MCP, а не отсутствие VoidMulti в установке.
Повтор с документированным CLI `--trust-extensions` не снял ограничение:
resolved provider всё равно запустился worker-brokered и вернул тот же лимит узлов.

Полный итоговый Actor Scale и размеры связанных M3 пока не подтверждены.
Изолированный partial-каталог не заменяет рассчитанное наследование всех зависимостей;
массовые изменения размеров на его основе не внесены. Предыдущие ошибки открытия
виртуального пути ниже — история попыток, а не утверждение, что CASC не читается.

## Подтверждённое ограничение проверки

В этой сессии вызван настоящий публичный Modkit MCP через штатный stdio-сервер
`C:/MODDING/modkit-core/apps/mcp/dist/main.js`. Применены навыки
`inspect-sc2-resolved-data` и `transfer-modkit-game-content`.
Публичное обнаружение подтвердило StarCraft II **5.0.17.98310**,
game ID **blizzard/starcraft-2**, установленный в разрешённом корне.
Нативные файлы игры и CASC напрямую не читались; исходная игра не изменялась.

Открытие dependency-composed calculated/resolved workspace через публичную операцию
`modkit.open_resolved_workspace` с правильным game ID, enUS и базовым пакетом
`Mods/VoidMulti.SC2Mod` вернуло `x3.mcp/host-operation-failed`.
Проверены контексты установки и отдельного loose subject в C:/MKTEMP. Значений
акторов, связанных моделей и рассчитанных размеров этот маршрут не вернул.
`--allow-edits` включался только потому, что текущий Host предоставляет открытие
resolved workspace внутри этого флага; propose/prepare/commit не вызывались.

Доказательства в C:/MKTEMP:

- `starmine-calculated-mcp-capabilities.json`: snapshot
  `inventory-v1:f49d0c976f9265e44c94d736ae223c39`, ответ capabilities capped.
- `starmine-scale-discovery.json`: complete, проверенная установка 5.0.17.98310.
- `starmine-scale-correct-workspace-result.json` и
  `starmine-scale-loose-workspace-result.json`: invalid, host-operation-failed.
- Клиент MCP: `starmine-calculated-mcp.py`; публичный журнал `starmine-public-mcp.log`.

**Точный пересчёт всех зданий/юнитов по актуальным calculated data и Actor Scale пока
не выполнен. Текущие размеры в Minecraft не следует выдавать за эту проверку.**

## Что уже использует Minecraft

Размещение зданий в UnitKind использует сетку из трёх блоков на клетку SC2:

| Группа | Текущий footprint SC2 | Minecraft |
| --- | --- | --- |
| Главное здание | 5×5 | 15×15 |
| Большинство производственных/технологических зданий | 3×3 | 9×9 |
| Малые здания и колонии | 2×2 | 6×6 |
| Платформы | 1×1 | 3×3 |

Это существующая таблица footprint, а не свежая выгрузка SC2. Сам вулкан по решению
владельца отдельно имеет 7×7×4 блоков Minecraft.
Мобильные юниты используют исходную таблицу размеров импортированных моделей,
UNIT_SIZE и дополнительные множители крупных кораблей. Рабочие ограничены телом
1.9 блока ради прохода в 2×2. Таким образом, визуальный размер, физическое тело,
footprint стройки и масштаб клетки сейчас являются разными величинами.

## Что нужно получить из рабочего MCP-контекста

Для каждого точного Unit ID и его выбранного актора сохранить calculated/effective
значения, provenance и dependency snapshot: игровой footprint/радиус столкновения,
ссылку на актор, ссылку актора на модель, итоговые множители масштаба актора и модели,
нативные геометрические bounds модели и состояние/анимацию измерения. Точные field IDs
надо брать из текущего descriptor/schema; этот документ их не выдумывает.

Масштаб геометрии рассчитывается из нативных единиц модели, итоговых множителей
модели/актора и перевода клетки в три блока. Ground footprint и collision нельзя
получать простым растяжением геометрии до рамки стройки: анимационные выступы,
пристройки и разные пропорции моделей требуют отдельной проверки.
При разных масштабах по осям сохранять все компоненты, не только максимальную.

После восстановления публичного resolved workspace сначала проверить по одному
представителю каждой расы и крупный корабль, затем выгрузить полный корпус.
Сравнить сохранённые исходные значения с реально отрисованными bounds и физическим
телом Minecraft, а также с габаритами транспорта, проходов и порталов. Unknown
оставлять unknown; не заменять отсутствующее значение единицей или нулём.
