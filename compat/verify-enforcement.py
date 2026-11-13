#!/usr/bin/env python3
"""Статическая проверка: `settings.enforcement: false` не должен ничего исправлять.

Зачем: это регрессионный тест, а не документация. Он запускается в CI и падает,
если кто-то в будущем добавит новую отмену/откат/наказание мимо гейта
`enforcementEnabled()`. Ровно такая ошибка уже была трижды (откат при скачке
позиции, аварийные отмены FastBreak и FastPlace) и нашлась только ручным аудитом.

Проверки:
  1. Каждая точка коррекции (`setCancelled(true)`, `event.setTo(`) находится под
     гейтом или вызывается только через `flag()` (который сам возвращает false).
  2. Внутри `flag()` есть ранний выход `if (!enforcement) return false;` ДО любых
     действий, и наказания вызываются после него.
  3. `runPunishments()` вызывается ровно в одном месте — внутри `flag()`.
  4. `teleport()` есть только в `setbackPlayer()`, а `setbackPlayer()` вызывается
     только из ручной команды `/vac setback`.
  5. Периодическая задача (`secondTick`) сама по себе ничего не отменяет,
     не телепортирует и не наказывает — всё идёт через `flag()`.

Выход: 0 — всё под контролем, 1 — найдена незагейченная коррекция.
"""

from __future__ import annotations

import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parent
SRC = ROOT / "VoidAntiCheat/src/main/java/ru/voidpe/anticheat/VoidAntiCheat.java"

# Насколько назад смотреть в поисках гейта. Отмены пишутся в стиле
# `if (enforcementEnabled() && ...) { event.setCancelled(true); }`, поэтому
# гейт стоит на 1-2 строки выше; 6 даёт запас на форматирование.
WINDOW = 6

failures: list[str] = []
checks = 0


def fail(msg: str) -> None:
    failures.append(msg)


def body_of(lines: list[str], signature: str) -> tuple[int, int]:
    """Возвращает (начало, конец) тела метода по сигнатуре, считая скобки."""
    start = next((i for i, l in enumerate(lines) if signature in l), None)
    if start is None:
        raise SystemExit(f"не найдена сигнатура: {signature}")
    depth = 0
    opened = False
    for i in range(start, len(lines)):
        depth += lines[i].count("{") - lines[i].count("}")
        if "{" in lines[i]:
            opened = True
        if opened and depth == 0:
            return start, i
    raise SystemExit(f"не закрыто тело: {signature}")


def main() -> int:
    global checks

    if not SRC.exists():
        print(f"НЕ НАЙДЕН ИСХОДНИК: {SRC}", file=sys.stderr)
        return 1

    lines = SRC.read_text(encoding="utf-8").splitlines()

    # --- 1. все точки коррекции под гейтом -----------------------------------
    corrections = [i for i, l in enumerate(lines) if "setCancelled(true)" in l or "event.setTo(" in l]
    if not corrections:
        fail("не найдено ни одной точки коррекции — проверка потеряла смысл")

    for i in corrections:
        ctx = "\n".join(lines[max(0, i - WINDOW): i + 1])
        gated = False
        if "enforcementEnabled()" in ctx:
            gated = True
        else:
            # `flag()` возвращает true только при включённом enforcement, поэтому
            # чистое `if (cancel)` — корректный гейт. Но `if (cancel || <другое>)`
            # пропускает коррекцию по второму условию, и это уже дыра: именно так
            # работали аварийные отмены FastBreak/FastPlace.
            for line in lines[max(0, i - WINDOW): i + 1]:
                stripped = line.strip()
                if "boolean cancel = flag(" in line:
                    gated = True
                elif stripped.startswith("if (cancel") and "||" in stripped:
                    gated = False  # лишнее условие обходит flag()
                    break
                elif stripped.startswith("if (cancel") and "&&" not in stripped:
                    gated = True
        checks += 1
        if not gated:
            fail(f"строка {i + 1}: коррекция без гейта enforcementEnabled() -> {lines[i].strip()}")

    # --- 2. ранний выход внутри flag() ---------------------------------------
    flag_start, flag_end = body_of(lines, "private boolean flag(")
    flag_lines = lines[flag_start: flag_end + 1]
    early = next(
        (
            k
            for k, l in enumerate(flag_lines)
            if "if (!enforcement)" in l
        ),
        None,
    )
    checks += 1
    if early is None:
        fail("в flag() нет раннего выхода `if (!enforcement) return false;`")
    else:
        # выход должен быть ДО первой записи состояния
        checks += 1
        before = "\n".join(flag_lines[:early])
        if "setbackRequested = true" in before or "markAction(" in before or "runPunishments(" in before:
            fail("в flag() действия выполняются ДО проверки enforcement")
        if "return false;" not in "\n".join(flag_lines[early: early + 3]):
            fail("`if (!enforcement)` в flag() не возвращает false")

    # --- 3. наказания только из flag() --------------------------------------
    punish_calls = [i for i, l in enumerate(lines) if "runPunishments(" in l]
    checks += 1
    if len(punish_calls) != 2:
        fail(f"runPunishments должен встречаться 2 раза (определение + 1 вызов), найдено {len(punish_calls)}")
    for i in punish_calls:
        if "private void runPunishments(" in lines[i]:
            continue
        checks += 1
        if not (flag_start <= i <= flag_end):
            fail(f"строка {i + 1}: наказание вызывается вне flag()")
        elif early is not None and i - flag_start < early:
            fail(f"строка {i + 1}: наказание вызывается до проверки enforcement")

    # --- 4. телепорт только в ручной команде --------------------------------
    teleports = [i for i, l in enumerate(lines) if ".teleport(" in l]
    checks += 1
    if len(teleports) != 1:
        fail(f"ожидался ровно один teleport (в setbackPlayer), найдено {len(teleports)}")
    sb_start, sb_end = body_of(lines, "private boolean setbackPlayer(")
    for i in teleports:
        checks += 1
        if not (sb_start <= i <= sb_end):
            fail(f"строка {i + 1}: teleport вне setbackPlayer() — это автоматический откат")

    setbacks = [i for i, l in enumerate(lines) if "setbackPlayer(" in l]
    checks += 1
    if len(setbacks) != 2:
        fail(f"setbackPlayer должен встречаться 2 раза (определение + 1 вызов), найдено {len(setbacks)}")
    for i in setbacks:
        if sb_start <= i <= sb_end and "private boolean setbackPlayer(" in lines[i]:
            continue
        checks += 1
        cmd_ctx = "\n".join(lines[max(0, i - 12): i + 1])
        if 'case "setback"' not in cmd_ctx:
            fail(f"строка {i + 1}: setbackPlayer() вызывается не из команды /vac setback")

    # --- 4b. у каждой проверки есть секция в конфиге ------------------------
    # Новая проверка без секции молча работает на значениях по умолчанию — это
    # уже случалось, поэтому проверяем явно.
    import re as _re

    check_ids = _re.findall(r'^\s+([A-Z_]+)\("([a-z]+)",\s*"', SRC.read_text(encoding="utf-8"), _re.M)
    config = (ROOT / "VoidAntiCheat/src/main/resources/config.yml").read_text(encoding="utf-8")
    checks_block = config.split("\nchecks:", 1)[1].split("\nmessages:", 1)[0] if "\nchecks:" in config else ""
    for const, key in check_ids:
        checks += 1
        if f"\n  {key}:" not in checks_block:
            fail(f"проверка {const} ({key}) не имеет секции checks.{key} в config.yml")

    # --- 4c. каждый прочитанный ключ конфига существует ---------------------
    used = set(_re.findall(r'conf(?:Bool|Int|Long|Double|String)\("([^"]+)"', SRC.read_text(encoding="utf-8")))

    def flatten(node, prefix=""):
        flat = {}
        for key, value in node.items():
            full = f"{prefix}{key}"
            if isinstance(value, dict):
                flat.update(flatten(value, full + "."))
            else:
                flat[full] = value
        return flat

    try:
        import yaml

        flat_config = flatten(yaml.safe_load(config))
        for key in sorted(used):
            checks += 1
            if key not in flat_config:
                fail(f"код читает {key}, но такого ключа нет в config.yml — "
                     f"настройка не работает из конфига")
    except ImportError:
        print("  (PyYAML нет — проверка ключей конфига пропущена)", file=sys.stderr)

    # --- 4d. бур (серверный мультиблок) не должен давать улик -------------
    # Регрессия, которая уже случалась: бур шлёт 9-18 настоящих BlockBreakEvent
    # за тик, и проверки принимали это за Nuker.
    src_text = SRC.read_text(encoding="utf-8")
    break_body = src_text.split("public void onBlockBreak(BlockBreakEvent event) {", 1)[1]
    break_body = break_body.split("public void onBlockPlace", 1)[0]

    checks += 1
    if "drillTool" not in break_body:
        fail("onBlockBreak не определяет drillTool — бур снова будет флагаться")

    checks += 1
    if not _re.search(r"!drillTool && blockReachViolation", break_body):
        fail("BlockReach не исключает бур: угловые блоки 3x3 дальше ванильного лимита")

    checks += 1
    if not _re.search(r"!drillTool && enforcementEnabled\(\)", break_body):
        fail("аварийная отмена по счётчику не исключает бур — часть блоков вырежется")

    checks += 1
    if "serverMultiBlockBreak(player, data, tick)" not in break_body:
        fail("onBlockBreak не использует общее исключение мультиблока")

    checks += 1
    if "exclude-from-automine" not in src_text:
        fail("AutoMine не исключает разрушения бура")

    # Исключение должно быть настраиваемым, а не хардкодом namespace.
    checks += 1
    if "drill-exempt" not in src_text:
        fail("нет настройки world.drill-exempt — чар под другим именем сломает исключение")

    # --- 4e. предсказание движения: модель и измерения -------------------
    meter = ROOT / "VoidAntiCheat/src/main/java/ru/voidpe/anticheat/PredictionMeter.java"
    checks += 1
    if not meter.exists():
        fail("нет PredictionMeter — предсказание движения отсутствует")

    meter_text = meter.read_text(encoding="utf-8") if meter.exists() else ""
    checks += 1
    if "GRAVITY = 0.08" not in meter_text or "DRAG = 0.98" not in meter_text:
        fail("PredictionMeter не использует ванильные константы (0.08 / 0.98)")

    # Модель обязана стартовать с наблюдаемой скорости, а не с константы:
    # у Geyser импульс прыжка отличается на несколько процентов.
    checks += 1
    if "expectedVy = dy" not in meter_text:
        fail("PredictionMeter не стартует с фактической скорости — ложные на Bedrock")

    # Измерения не должны ничего корректировать.
    for forbidden in ("setCancelled", "teleport(", "kick(", "setTo", "flag("):
        checks += 1
        if forbidden in meter_text:
            fail(f"PredictionMeter содержит '{forbidden}' — класс должен только измерять")

    checks += 1
    if "describe()" not in meter_text or "medianAbs()" not in meter_text:
        fail("PredictionMeter не умеет отчитываться — нельзя решать по цифрам")

    checks += 1
    if 'world.prediction.measure' not in src_text:
        fail("нет ключа world.prediction.measure — статистику нельзя выключить")

    # --- 4f. надёжность: кэши, утечки, ограничения ------------------------
    # Кэш конфига обязан сбрасываться на reload, иначе изменение файла
    # не применяется до перезапуска (на этом уже спотыкались).
    checks += 1
    if "confCache.clear()" not in src_text:
        fail("кэш конфига не сбрасывается — /vac reload не применит scalars")

    checks += 1
    if "fastAttackCache = null" not in src_text:
        fail("fastAttackCache не сбрасывается на reload — настройка игнорируется")

    # Неограниченные очереди = OutOfMemoryError при медленном диске.
    checks += 1
    if "getQueue().size()" not in src_text:
        fail("очередь лога нарушений не ограничена — риск OOM")

    checks += 1
    if "MAX_LOG_BYTES" not in src_text:
        fail("файл нарушений не ограничен по размеру — вырастет бесконечно")

    # Карта целей игрока должна быть ограничена.
    pd_text = (ROOT / "VoidAntiCheat/src/main/java/ru/voidpe/anticheat/PlayerData.java").read_text(encoding="utf-8")
    checks += 1
    if "MAX_TARGETS" not in pd_text:
        fail("targets не ограничен — в массовом PvP карта растёт без предела")

    # --- 4g. AVG и история: инструмент стаффа, не наказание ----------------
    susp = ROOT / "VoidAntiCheat/src/main/java/ru/voidpe/anticheat/SuspicionTracker.java"
    checks += 1
    if not susp.exists():
        fail("нет SuspicionTracker — AVG и история не реализованы")

    susp_text = susp.read_text(encoding="utf-8") if susp.exists() else ""
    # AVG обязан быть ограничен по памяти: иначе за сутки вырастет в сотни МБ.
    for marker, why in (("MAX_ENTRIES", "история на игрока не ограничена"),
                        ("MAX_CACHED_PLAYERS", "число игроков в кэше не ограничено"),
                        ("MAX_QUEUE", "очередь записи на диск не ограничена")):
        checks += 1
        if marker not in susp_text:
            fail(f"{marker} отсутствует — {why}")

    # AVG не должен участвовать в наказаниях.
    for forbidden in ("kick(", "setCancelled", "setTo", "teleport", "ban("):
        checks += 1
        if forbidden in susp_text:
            fail(f"SuspicionTracker содержит '{forbidden}' — AVG не должен наказывать")

    checks += 1
    if "sendActionBar" not in susp_text:
        fail("метка AVG не выводится")

    checks += 1
    if "lastAvgLabelMillis" not in susp_text:
        fail("метка AVG без троттлинга — пересборка каждый тик")

    # --- 4h. запрет самовызова в методах (StackOverflowError) -------------
    # Реальная ошибка из продакшена: `return enforcementEnabled();` внутри
    # самого enforcementEnabled. Цикл -> StackOverflowError на каждом
    # PlayerMoveEvent -> движение вообще не проверялось. Ловим класс целиком.
    checks += 1
    recursion = _re.findall(
        r'(?:private|public|protected)\s+[\w<>\[\],\s]+?\s+(\w+)\s*\([^)]*\)\s*\{\s*return\s+\1\s*\([^)]*\)\s*;',
        src_text, _re.S)
    if recursion:
        fail(f"самовызов в методе {recursion} — бесконечная рекурсия, StackOverflowError")

    checks += 1
    if 'return confBool("settings.enforcement", true);' not in src_text:
        fail("enforcementEnabled не читает settings.enforcement напрямую")

    # --- 4i. KillAura/HitBox/Aim (Apollon и подобные) ---------------------
    for marker, why in (("aimAndHitboxChecks", "нет проверок молчаливого поворота и hitbox"),
                        ("isLookingAt", "нет проверки удара по цели вне поля зрения"),
                        ("aimMovedDegrees", "движение взгляда не накапливается")):
        checks += 1
        if marker not in src_text:
            fail(f"{marker} отсутствует — {why}")
            break

    checks += 1
    for marker in ("idleAimStreak", "edgeReachStreak"):
        if f"{marker}++" not in src_text:
            fail(f"{marker} не накапливается — проверка сработает с первого удара")

    checks += 1
    if "BLINDNESS" not in src_text or "isInvisible" not in src_text:
        fail("aim-проверка не исключает слепоту/невидимость — будут ложные")

    checks += 1
    if "combat.aim.max-ping" not in src_text:
        fail("aim-проверка не отключается на высоком пинге")

    # Обнуление окна ДО накопления делает сумму бесполезной: она никогда не
    # превышает одного межпакетного поворота, и тогда любой игрок выглядит
    # «не двигающим взгляд». Ловим именно этот порядок операций.
    checks += 1
    bad_window = _re.search(
        r"aimWindowStartMillis\s*=\s*nowMillis;\s*\n\s*data\.aimMovedDegrees\s*=\s*0\.0;\s*\n\s*}"
        r"\s*\n\s*data\.aimMovedDegrees\s*\+=", src_text)
    if bad_window:
        fail("окно aimMovedDegrees обнуляется до накопления — сумма всегда около нуля, "
             "idleAimStreak растёт у любого игрока")

    # Скрытый поворот: удар мимо взгляда при неподвижной камере.
    checks += 1
    for marker in ("silentRotationStreak", "silentAimWindowDegrees"):
        if marker not in src_text:
            fail(f"{marker} отсутствует — silent rotation от KillAura не ловится")
            break
    checks += 1
    if "combat.aim.silent-streak" not in src_text:
        fail("проверка скрытого поворота не требует серии")

    # ChestAura (com.arlen.app): открытие контейнера без подхода и без
    # движения взгляда. Обязательно требует серию и исключает слепоту,
    # иначе обычные игроки начнут ловить алерты.
    checks += 1
    if "onOpenContainer" not in src_text:
        fail("нет проверки ChestAura — контейнеры в радиусе не отслеживаются")
    checks += 1
    for marker in ("chestAuraStreak", "chestNearestDistance", "chestAimDegrees"):
        if marker not in src_text:
            fail(f"{marker} отсутствует — ChestAura не может отличить обычное открытие")
            break
    checks += 1
    if "combat.chestaura.streak" not in src_text:
        fail("ChestAura не требует серии — будут ложные срабатывания")
    checks += 1
    if "combat.chestaura.max-ping" not in src_text:
        fail("ChestAura не отключается на высоком пинге")

    # Боевые проверки обязаны стоять ДО отмены удара reach-проверкой, иначе
    # при включённом enforcement они не выполняются вовсе.
    checks += 1
    m = _re.search(r"@EventHandler\([^)]*\)\s*\n\s*public void onAttack\(", src_text)
    if not m:
        fail("не найден обработчик onAttack")
    elif "EventPriority.MONITOR" in m.group(0):
        fail("onAttack на MONITOR: reach уже мог отменить удар, боевые проверки не работают")

    # --- 5. периодическая задача ничего не делает сама ----------------------
    sec_start, sec_end = body_of(lines, "private void secondTick(")
    sec_body = "\n".join(lines[sec_start: sec_end + 1])
    checks += 1
    for forbidden in ("setCancelled(", ".teleport(", "runPunishments("):
        if forbidden in sec_body:
            fail(f"secondTick() содержит {forbidden} — отложенное действие мимо гейта")

    # --- итог ---------------------------------------------------------------
    if failures:
        print("ПРОВАЛ: enforcement: false не гарантирует режим «только алерты»\n", file=sys.stderr)
        for f in failures:
            print(f"  - {f}", file=sys.stderr)
        return 1

    print(f"УСПЕХ: {checks} проверок пройдено")
    print(f"  точек коррекции под гейтом: {len(corrections)}")
    print("  flag(): ранний выход при enforcement=false — есть")
    print("  наказания: только из flag(), после проверки enforcement")
    print("  телепорт: только setbackPlayer() <- /vac setback (ручная команда)")
    print("  secondTick(): отмен/телепортов/наказаний не содержит")
    return 0


if __name__ == "__main__":
    sys.exit(main())
