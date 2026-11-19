# Готовые конфиги (20.11.2026)

## paper-world-defaults.yml.new

Замена для `config/paper-world-defaults.yml`.

Изменено ТОЛЬКО в секции `anticheat.anti-xray`:
- `enabled: false` → **`true`** — главное, Anti-Xray не работал вообще;
- добавлены `ancient_debris`, `nether_gold_ore`, `nether_quartz_ore`,
  `spawner` — их не было в hidden-blocks, Нижний мир не защищён;
- `max-block-height: 64` → **`128`** — руды в горах выше 64 были видны.

Остальные секции (`chunks`, `entities`, `tick-rates`, ...) не тронуты —
проверено сравнением всех ключей верхнего уровня.

`engine-mode` оставлен **1** (обычная замена): клиент рисует мир нормально,
ложных руд нет, нагрузка минимальная. Режим 2 на Bedrock даёт артефакты.

## Установка

1. Остановить сервер.
2. `config/paper-world-defaults.yml` → скопировать в `backups/` себе.
3. Заменить на `paper-world-defaults.yml.new`.
4. Если есть папки миров с `paper-world.yml` — проверить, что там нет
   своего `anticheat.anti-xray` (он переопределит общий).
5. Запустить.

## Anti-ESP (в плагине VoidAntiCheat)

В `plugins/VoidAntiCheat/config.yml`:

```yaml
anti-esp:
  enabled: true          # было false
  only-if-core-inactive: false
  level: BALANCED
```

`only-if-core-inactive: false` обязателен, если у вас стоит
PurpurBedrockAC. Иначе Anti-ESP будет уступать ядру и не включится.
