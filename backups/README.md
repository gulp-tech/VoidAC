# Бэкапы VoidAntiCheat

## Точка отката: `vac-stable-37c63fa`

| | |
|---|---|
| Git-тег | `vac-stable-37c63fa` (коммит `dbbb527`) |
| JAR | `backups/VoidAntiCheat-stable-37c63fa.jar` |
| Размер | 153 872 байт |
| SHA-256 | `37c63fa531583055cb276795a921e144e16a341b95a04d98c03b032ba5ad4f3f` |
| Тесты | 250/250 |

Что в этой версии:
- фикс StackOverflowError, догон Floodgate;
- aim/hitbox/silent rotation, точность прицела (с минимальной дистанцией);
- ChestAura с реальной историей позиций;
- права модератора `voidanticheat.staff`;
- удалён мёртвый код.

## Как откатиться

**Из git:**
```bash
git fetch origin --tags
git checkout vac-stable-37c63fa -- compat/
```

**Из JAR (без сборки):**
```
/plugins/VoidAntiCheat.jar  <-  backups/VoidAntiCheat-stable-37c63fa.jar
```

## Правило

Перед любой жёсткой правкой:
1. тег `vac-stable-<хеш>` на текущий коммит;
2. копия JAR в `backups/`;
3. запись в этот README: что было, что сломали.

Точки отката не удаляются — только добавляются новые.
