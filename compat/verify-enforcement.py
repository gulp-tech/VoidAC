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
