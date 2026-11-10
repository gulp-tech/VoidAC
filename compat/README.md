# compat/ — адаптация VoidAntiCheat 2.0.4 под Purpurbedrock 26.2

- `VoidAntiCheat-2.0.4-compat.patch` — патч (`diff -u`), применять из корня модуля:
  `cd VoidAntiCheat && patch -p1 < ../VoidAntiCheat-2.0.4-compat.patch`
- `VoidAntiCheat/` — пропатченная копия модуля (в т.ч. новый `BedrockCoreBridge.java`)

Статистика: 8 файлов, 2082 строки. Патч самодостаточен — создаёт
`BedrockCoreBridge.java`, `AntiEspManager.java`, `build.gradle.kts`,
`settings.gradle.kts`.

Что исправлено:
1. Быстрая атака больше не чит: читается флаг ядра
   `PurpurWorldConfig.bedrockCombatEnabled` (по умолчанию true → нулевой кд
   атаки на NMS-уровне).
2. Неизвестное устройство → сенсорные (мягкие) лимиты Reach/CPS, а не строгие.
3. Неизвестный пинг не обнуляет компенсацию (`unknown-ping-assumed-ms: 120`).
4. Расхождение позиции сервера не считается движением (sync-guard в onMove).
5. FastBreak не штрафует серверный Бур VoidEnchants (PDC `voidenchants:drill`).
6. `BedrockCoreBridge` — мост к `BedrockPlaceholderHook.resolveOnline` с
   проверкой «данные реальные» (`protocol>0` / `device_model` / `xuid`).
7. BlockReach масштабируется множителем ядра `bedrockBlockReachTolerance`
   (2.25): ядро само разрешает ставить/добывать до ~10 блоков, отменять с 7
   нельзя. Предупреждение в лог + рекомендация ужесточить в конфиге ядра.
8. KillAura: при включённой Bedrock-боёвке ядра порог разных целей
   поднимается 5 → 9 (нулевой кд + отключённый Java-sweep + нокбэк 1.05).

Проверено: `patch -p1` чисто (8/8 файлов идентичны); синтаксис всех `.java` —
tree-sitter-java OK; `config.yml` — валидный YAML; **компиляция против
`purpur-api:26.2-R0.1-SNAPSHOT` в CI (Java 25) — успешно**; API в JAR — 0 файлов;
Maven-сборка `pom.xml` — успешно.
НЕ проверено: поведение на живых сервере и Bedrock-клиентах.

Подробности и разбор ядра: `../COMPAT-REPORT.md`.

## Сборка (Gradle, добавлена)

```bash
cd VoidAntiCheat
gradle -q printApiVersion -PpurpurVersion=26.2-R0.1-SNAPSHOT   # проверка свойства
gradle build -PpurpurVersion=26.2-R0.1-SNAPSHOT                # -> build/libs/VoidAntiCheat-2.0.4.jar
```

API подключается как `compileOnly`, в JAR не попадает (проверяется задачей
`verifyNoApiInJar`). Maven-сборка (`pom.xml`) сохранена и тоже проверена в CI.

**Готовая сборка:** `https://github.com/gulp-tech/VoidAC/releases/tag/vac-2.0.4-compat`
— `VoidAntiCheat-2.0.4.jar`, 105 437 байт,
SHA-256 `1d31a80a24f117a7cb2d15fd37bea0807d5e211c103ac2c5769c3ca7ec6e40ff`.

## Автотест инвариантов `enforcement`

```bash
python3 verify-enforcement.py     # 18 проверок, exit 0 = режим «только алерты» цел
```

Статический тест: падает, если новая отмена/откат/наказание появится мимо
`enforcementEnabled()`. Запускается в CI до сборки. Проверен мутацией —
ловит снятие гейта.
