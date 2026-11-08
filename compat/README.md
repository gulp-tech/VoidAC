# compat/ — адаптация VoidAntiCheat 2.0.4 под Purpurbedrock 26.2

- `VoidAntiCheat-2.0.4-compat.patch` — патч (`diff -u`), применять из корня модуля:
  `cd VoidAntiCheat && patch -p1 < ../VoidAntiCheat-2.0.4-compat.patch`
- `VoidAntiCheat/` — пропатченная копия модуля (в т.ч. новый `BedrockCoreBridge.java`)

Статистика: 8 файлов, 1925 строк. Патч самодостаточен — создаёт
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

Проверено: `patch --dry-run` чисто; синтаксис всех `.java` — tree-sitter-java OK;
`config.yml` — валидный YAML.
НЕ проверено: компиляция (нет javac/Maven) и поведение на живых клиентах.

Подробности и разбор ядра: `../COMPAT-REPORT.md`.

## Сборка (Gradle, добавлена)

```bash
cd VoidAntiCheat
./gradlew -q printApiVersion -PpurpurVersion=26.2-R0.1-SNAPSHOT   # проверка свойства
./gradlew build                                                   # -> build/libs/VoidAntiCheat-2.0.4.jar
```

API подключается как `compileOnly`, в JAR не попадает (проверяется задачей
`verifyNoApiInJar`). Maven-сборка (`pom.xml`) сохранена и работает так же.
