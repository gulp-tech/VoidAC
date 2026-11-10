# VoidAntiCheat → Purpurbedrock 26.2: анализ ядра и адаптация

Дата: 2026-11-07. Ветка: `arena/ba853bde-voidac`.
Источники: приложенный снапшот ядра (`Purpurbedrock-arena-16aa0e1c`, 535 файлов)
+ `VoidAntiCheat` 2.0.4 из `GeyserPlugins`.

Всё ниже — по прочитанному коду. Где проверить нечем, сказано прямо.

---

## 1. Как ядро обрабатывает Bedrock-игроков

Bedrock-протокол действительно встроен — это не сток-Purpur. Пакет
`org.purpurmc.purpur.bedrock` (13 классов, 4075 строк):

| Класс | Роль |
|---|---|
| `BedrockGatekeeper` | отсев чистых Java-клиентов (`only-allow-bedrock`), определение Bedrock по каналу/префиксу/порту/pipeline/`isBedrockUuid` |
| `BedrockMovementGuard` | обработка `PlayerFailMoveEvent`: терпимость к `MOVED_WRONGLY` (в пределах `max-moved-wrongly-squared = 3.25`), к `MOVED_TOO_QUICKLY` (в пределах 16.0), самолечение при `CLIPPED_INTO_BLOCK` |
| `BedrockGhostBlockResync` | точечная пересылка блоков и содержимого (ghost blocks) после отменённых ломаний/установок, «слишком далеко», попадания в блок |
| `BedrockCombatHelper` | `isBedrockPlayer`, скидка на урон топоров под Bedrock, криты в спринте, мгновенный и приседательный блок щитом |
| `BedrockPlaceholderHook` | 827 строк: `resolveOnline/Offline/ByUuid`, `device`, `device_id`, `input`, `version`, `ping`, `protocol`, `xui`, `is_bedrock`, классификация устройств |
| `BedrockAntiXrayHelper` | Anti-Xray `EngineMode.HIDE` только для руд, безопасно для палитр Bedrock |
| `JavaChestMenuManager` | встроенные Java-меню (сундуки) для Bedrock |
| `BedrockNpcOptimizer`, `BedrockChunkHelper`, `BedrockChunkDowngrader` | оптимизации и совместимость версий |
| `BedrockBridgeBootstrap` | подъём моста/заглушек |

Точки врезки — в патчах ядра (`0023-Purpurbedrock-engine.patch`,
`0006-Purpurbedrock-paper-engine.patch`): `ServerGamePacketListenerImpl`
(движение, `PlayerFailMoveEvent`, меню), `ServerPlayerGameMode`
(ломание/установка), `Player#attack` (боёвка), `Level` (`purpurConfig`).

**Важное следствие для античита.** `PlayerMoveEvent` в Paper — это уже
*результат* обработки сервером, а не сырой пакет. Ядро вмешивается **до**
Bukkit-событий: гасит ложные `MOVED_WRONGLY`/`MOVED_TOO_QUICKLY` и подменяет
блоки. Значит античит видит «сглаженную» картину и не должен повторно
наказывать за то, что ядро уже признало легальным.

---

## 2. Боёвка: ядро снимает кулдаун атаки на уровне NMS

Это самое важное для адаптации боевых проверок. В патче движка:

```java
// Player#attack
if (this.level().purpurConfig.bedrockCombatEnabled) this.attackStrengthTicker = 9999;
float attackStrengthScale = bedrockCombatEnabled ? 1.0F : this.getAttackStrengthScale(0.5F);
if (!bedrockCombatEnabled) baseDamage *= this.baseDamageScaleFactor();  // полный урон каждым ударом
```

`PurpurWorldConfig.bedrockCombatEnabled` — **`true` по умолчанию**
(ключ `gameplay-mechanics.bedrock-combat.enabled`, флаг **по-мирный**).
Там же: `allow-sprint-crits` (криты в спринте), `instant-shield-block`
(щит без 5-тиковой задержки), скидка на урон топоров, множитель отбрасывания.

Плюс отдельный плагин **VoidPvP** дополнительно поднимает `ATTACK_SPEED` и
обнуляет `noDamageTicks` (`FastCritsListener:231`).

**Вывод: на этом сервере высокая частота ударов — штатная механика двух слоёв
сразу, а не признак чита.** Прежний VAC определял «сервер без кд» только по
наличию плагина `VoidPvP`. Ядро он не учитывал — то есть при выключенном или
отсутствующем VoidPvP сырой CPS снова считался бы нарушением, хотя ядро всё
равно даёт нулевой кулдаун. Исправлено.

---

## 3. Данные устройств: `resolveOnline` врёт «по умолчанию»

`BedrockPlaceholderHook.resolveOnline(Player, String)` существует и отдаёт
`device`, `device_id`, `input`, `version`, `ping`, `protocol`, `ui`, `xuid`,
`is_bedrock`. Но есть три ловушки:

1. **`is_bedrock` возвращает `"true"` ВСЕМ**, если включён
   `bedrock.only-allow-bedrock` (по умолчанию включён):
   `session != null || isBedrockUuid(uuid) || PurpurConfig.bedrockOnlyAllowBedrock`.
   Это флаг «сервер только для Bedrock», а не «этот игрок Bedrock».
2. **`device` / `device_id` без живой сессии** отдают значения по умолчанию:
   `bedrockPlaceholderDeviceAndroid` («Android») и `"android"`.
3. **`protocol` без сессии** — `"0"`, `device_model` — пустая строка.

То есть по этим полям нельзя отличить реального телефона от отсутствия данных.
**Реальность сессии** подтверждают только `protocol > 0`, непустой
`device_model` или непустой `xuid` — их без Geyser-сессии получить нельзя.

Классификация устройств в ядре (для справки): `windows`, `android`, `ios`,
`xbox`, `ps4_5`, `switch`, `phone_kbm` (телефон с клавиатурой/мышью, sticky
45 с). Плюс анти-спуф: «Android + DefaultInputMode=KBM + пустая модель» → `windows`.

**Про `BedrockBridgeInfo.java`.** Он есть, но лежит **не в ядре**, а в
отдельном плагине `purpurbedrock-anticheat` (`PurpurBedrockAC`), и обращается к
ядру ровно через `Class.forName("...BedrockPlaceholderHook")` +
`resolveOnline(Player,String)`. Это не публичный API. Поэтому в VAC я сделал
свой маленький мост **с тем же проверенным шаблоном**, а не тянул класс из
чужого jar (его может не быть).

---

## 4. Главный вывод: в ядре уже есть свой античит

`purpurbedrock-anticheat` — отдельный плагин **`PurpurBedrockAC 26.2.10`**
(`plugin.yml`: main `org.purpurmc.purpur.anticheat.PurpurBedrockACPlugin`,
команда `/pac`). 6824 строки, **21 проверка**:

`reach, hitbox, killaura, autoclicker, criticals, velocity, fly, airjump, speed,
step, highjump, spider, jesus, noslow, nofall, phase, timer, scaffold, fastbreak,
inventory, badpackets`

Плюс `AntiEspManager` (hide/show с интервалом 4 тика и грейсом 300 тиков),
алерты с подтверждением (3 улики за 12 с), голограмма AVG, меню истории,
адаптивный движок, **своя проверка `inventory`** (ChestStealer/InventoryMove) и
**свой `scaffold`/`fastbreak`/`phase`/`badpackets`**.

**Это меняет постановку задачи.** Вопрос не «адаптировать античит под ядро», а
«не запускать две системы одинаковых проверок». Пересечение с VAC 2.0.4 —
практически полное: 19 проверок VAC против 21 у `PurpurBedrockAC`, причём
`PurpurBedrockAC` написан именно под этот Geyser-слой (откат хитбокса на 18
тиков, токен-бакет Timer с кредитом лага до 3500 мс, конус обзора для сенсора
вместо строгого 3D-луча, `noslow` с авто-щитом).

Различия, которые я вижу в коде:

| | `PurpurBedrockAC` (ядро) | `VoidAntiCheat` 2.0.4 |
|---|---|---|
| Данные устройства | своя Geyser-сессия + `phone_kbm` + анти-спуф | только Floodgate-рефлексия, `inputMode` часто `UNKNOWN` |
| Откат хитбокса | 18 тиков под пинг | окно пинга, но пинг часто 0 |
| Инвентарь | есть проверка | нет вообще (конфликта с `VoidShulker` не будет) |
| Anti-ESP / голограмма / меню | есть | нет |
| Наказания | `auto-punish-enabled: false` по умолчанию | `auto-punish: false` |
| Порог срабатывания | подтверждение 3 улики за 12 с | буферы улик + затухание VL |

**Рекомендация (без изменений в коде, это твоё решение):** одновременно держать
оба — плохо. Две системы дают двойные отмены, двойные setback и неразбираемые
алерты. Разумно: оставить `PurpurBedrockAC` как основной (он глубже знает
Geyser и устройства), а VAC использовать как второй слой **только там, где он
сильнее** — либо выключить `enforcement` у одного из них на время сравнения.

Что я НЕ делал: не отключал VAC и не удалял проверки. Ниже — точечная адаптация.

---

## 5. Найденные проблемы и что исправлено

Патч: **`compat/VoidAntiCheat-2.1-compat.patch`** (421 строка, 226 добавлено,
8 строк заменено — ровно те, что были ошибочны). Применяется `patch -p1`
чисто (проверено `--dry-run`). Пропатченная копия — `compat/VoidAntiCheat/`.

### P1. Быстрая атака считалась читом, хотя ядро снимает кулдаун
`fastAttackServer()` учитывал только `settings.fast-attack-server` и наличие
`VoidPvP`. Теперь дополнительно читается флаг ядра **для мира игрока**
(`World#getHandle().purpurConfig.bedrockCombatEnabled`). Не прочитать → считаем
«включён» (безопасная сторона). Ключ `settings.core-zero-attack-cooldown: true`.

### P2. Неизвестное устройство ухудшало положение игрока
Reach и AutoClicker сравнивали только с `"TOUCH"`. Теперь:
мобильные ОС и `phone_kbm` → сенсорный (мягкий) лимит; **`unknown` тоже
мягкий**; строгие лимиты — только при подтверждённом `windows`/консолях или
явном `KEYBOARD_MOUSE`/`CONTROLLER`.

### P3. Неизвестный пинг обнулял компенсацию
`getPing()` у Bedrock через Geyser часто 0. Ноль обнулял окно перемотки и
допуск — «неизвестно» наказывалось строже измеренного. Добавлен
`effectivePing()`: Bukkit-пинг → пинг Geyser-сессии → `compensation.unknown-ping-assumed-ms: 120`.

### P4. Расхождение позиции сервера считалось движением
`onMove` сравнивает `event.getFrom()` с запомненной позицией. При расхождении
событие не считается движением: фиксируем позицию, короткий грейс, статистика
заново. Отдельно флаг `unsyncedPosition` после телепорта/возрождения/смены мира.

### P5. FastBreak штрафовал серверный Бур 3×3
`VoidEnchants` ломает 3×3/3×3×2 настоящими синтетическими `BlockBreakEvent`,
и VAC при `fastbreak-same-tick: 4` **отменял** их → бур недоламывал блоки.
Теперь происхождение проверяется по PDC инструмента (`voidenchants:drill`).

### P6. Мост к анализатору устройств ядра
Новый `BedrockCoreBridge` — тот же шаблон, что в `BedrockBridgeInfo`, но с
проверкой «данные реальные» (см. раздел 3). При отсутствии данных —
`unknown`, и это не считается нарушением.

Новые ключи: `settings.core-zero-attack-cooldown`,
`compensation.unknown-ping-assumed-ms`, `settings.unsynced-grace-ms`,
`world.fastbreak-server-multiblock-exempt`, `movement.sync-epsilon-blocks`.
Всё выключаемо.

---

## 6. Чего я сознательно НЕ делал

- **Не включал Boar.** Boar — расширение Geyser, проверяющее движение на
  уровне Bedrock-пакетов. Вместе с `PurpurBedrockAC` и VAC это уже третья
  система на одних и тех же данных. Решение за тобой, но по коду это лишний
  слой, а не недостающий.
- **Не дублировал Anti-Xray и Anti-ESP ядра.** В `BedrockAntiXrayHelper`
  используется `EngineMode.HIDE` только для руд (осознанно, ради палитр
  Bedrock); `AntiEspManager` владеет hide/show. VAC не вызывает
  `hidePlayer/showPlayer` вообще — проверено grep, пересечения нет.
- **Не трогал скины, `JavaChestMenuManager`, меню.** У ядра свой
  `JavaChestMenuManager` + `bedrockMenusInstantResync`; VAC инвентарь не слушает.
- **Не менял пороги Reach/CPS по числам** — без живых замеров это гадание.
- **Не включал автобаны/кики.**

---

## 7. Что проверено автоматически, а что нет

**Проверено:**
- Прочитан весь Bedrock-слой ядра (13 классов) и патчи врезки; подтверждены
  `attackStrengthTicker = 9999`, `attackStrengthScale = 1.0F`, значения по
  умолчанию `bedrockCombatEnabled = true`, `max-moved-wrongly-squared = 3.25`,
  `max-floating-ticks = 200`, `reach-tolerance = 2.25`, `EngineMode.HIDE`.
- Найден и разобран `PurpurBedrockAC` (21 проверка), `BedrockBridgeInfo`
  (рефлексия к `resolveOnline`), `AntiEspManager`.
- Патч применяется чисто; синтаксис всех 14 `.java` — tree-sitter-java без
  ошибок; `config.yml` — валидный YAML.

**Не проверено:**
- **Компиляция.** В песочнице нет javac/Maven и закрыт Maven Central.
  Отдельный риск: `coreCombatEnabled` использует внутренности ядра
  (`getHandle().purpurConfig`) через рефлексию — это проверяется только
  запуском на сервере. Поведение при ошибке безопасное.
- **Живые клиенты** (обязательно): телефон и ПК; телефон с клавиатурой;
  удержание `VoidAuthSpawn`; Бур I/II; PvP-комбо с VoidPvP и без него;
  обрыв Wi-Fi 1–3 с; копьё и MACE; лодка по синему льду.
- **`/pac devices` против реальных клиентов** — сверьте, что ядро показывает
  те же категории, что VAC в алертах.

---

## 8. Что предлагаю решить

1. **Какой античит основной** — `PurpurBedrockAC` (глубже знает Geyser) или
   VAC. Держать оба с `enforcement: true` не стоит.
2. Разрешить собрать пропатченный VAC и неделю погонять с
   `settings.enforcement: false`, сверяя алерты с `/pac history`.
3. Прислать `Purpurbedrock-arena-*.zip` от той сборки, что стоит на сервере,
   если она новее этой — проверить `bedrockCombatEnabled` и `resolveOnline`
   ещё раз.


---

## 9. Что добавлено после 26.2 (Anti-ESP, гибкая настройка, миры)

### Anti-ESP (`AntiEspManager.java`)
Скрывает от клиента игроков, которых не должно быть видно: наблюдателей,
невидимых без подсветки, слишком далёких. Это **профилактика** против
ESP/Wallhack-паков (Apollon/Horion/Toolbox), VL за сокрытие не начисляется.

Ключевое ограничение: в ядре уже есть `AntiEspManager` (в `PurpurBedrockAC`,
интервал 4 тика). Два владельца `hideEntity/showEntity` = мигание игроков и
лишние пакеты, поэтому модуль VAC **по умолчанию не запускается**, если
Anti-ESP ядра активен (`anti-esp.only-if-core-inactive: true`) и пишет об этом
строку в лог.

При написании нашёлся и исправлен неочевидный баг: `canSee()` возвращает
`false` и в случае, когда скрыли **мы сами**. Если по этому признаку «отпускать»
пару, состояние теряется и игрок остаётся невидимым навсегда. Теперь скрытие
помечается как наше (`state.hidden`), а `canSee()` используется только для
обнаружения **чужого** скрытия (Vanish/привилегии), и все пути восстановления
вызывают `showEntity` без проверки `canSee`.

### Гибкая настройка без правки jar
- `/vac check <id> [on|off]` — включение/выключение любой проверки на лету.
- `/vac world list|add|remove|only|clear-only` — управление мирами; изменения
  сразу пишутся в `config.yml` и применяются к онлайну (у игроков, чей статус
  изменился, обнуляется состояние движения — иначе после «тихого» мира
  накопленная скорость дала бы ложняк).
- `/vac reload` перечитывает всё, включая Anti-ESP и компенсацию.
- Пороги каждой проверки (`buffer`, `setback-vl`, `cancel-vl`, `punish-vl`,
  `punish-commands`) уже были настраиваемыми — это не менялось.

### Миры с масками
`settings.excluded-worlds` и новое `settings.only-worlds` понимают маски:
`world`, `arena-*`, `*_test`, `lobby?`. Реализовано без регулярных выражений
(свой матчер), регистр не важен, проверка по имени мира — устойчиво к
телепортам и смене мира.

### Честно про «контрит всё»
Покрыты: Reach, MultiAura/WallHit, AntiKnockback, Fly/Glide/Hover, Bhop/Speed,
Step, Phase/NoClip, Timer (ускорение), BadPackets, Scaffold, Nuker/InstaBreak,
Spider, Jesus, NoFall, ElytraFly, BoatSpeed, AutoClicker (только ровность —
кулдаун снят ядром).

**Не покрыты:** HitBox-расширение (нет отдельной проверки; частично ловится
Reach), пакетные Criticals (ядро само даёт криты в спринте), NoSlow,
InventoryMove/ChestStealer (сознательно не трогаем — меню `VoidShulker` и
ядровый `JavaChestMenuManager`), BedFucker сквозь застройку.

Обещать «ноль ложных и ловит всё» нельзя: это обратные величины. Отдельно:
у ядра уже есть 21 проверка, включая `hitbox` с конусом для сенсора —
если нужен именно `hitbox`, логичнее включить его в `PurpurBedrockAC`, а не
писать второй.
