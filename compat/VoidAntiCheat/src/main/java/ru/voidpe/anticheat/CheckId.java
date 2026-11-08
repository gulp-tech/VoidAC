package ru.voidpe.anticheat;

import java.util.Locale;

/** Все проверки VoidAntiCheat. Ключ совпадает с секцией в config.yml. */
public enum CheckId {
    SPEED("speed", "Speed"),
    FLY("fly", "Fly / Hover"),
    TIMER("timer", "Timer"),
    NOFALL("nofall", "NoFall"),
    STEP("step", "Step"),
    JESUS("jesus", "Jesus"),
    SPIDER("spider", "Spider / Climb"),
    ELYTRA("elytra", "Elytra"),
    VEHICLE("vehicle", "Boat Speed"),
    VELOCITY("velocity", "Velocity"),
    REACH("reach", "Reach"),
    KILLAURA("killaura", "KillAura"),
    AUTOCLICKER("autoclicker", "AutoClicker"),
    FASTBREAK("fastbreak", "FastBreak / Nuker"),
    FASTPLACE("fastplace", "FastPlace"),
    SCAFFOLD("scaffold", "Scaffold"),
    BLOCKREACH("blockreach", "BlockReach"),
    BADPACKETS("badpackets", "BadPackets"),
    NOCLIP("noclip", "NoClip"),
    /**
     * Автоматическая добыча ресурсов: клиент сам наводится на руду и держит
     * кирку. Отличается от FastBreak тем, что скорость нормальная — улика в
     * том, ЧТО ломается и как выбирается цель, а не как быстро.
     */
    AUTOMINE("automine", "AutoMine"),
    /**
     * Бот, играющий за игрока: ровные интервалы действий, отсутствие простоев,
     * повтор одного маршрута, действия без задержки человеческой реакции.
     */
    MINEBOT("minebot", "MineBot"),
    /** Автоматическая постройка схем: повторяемый узор и порядок блоков. */
    BUILDPATTERN("buildpattern", "BuildPattern");

    private final String key;
    private final String display;

    CheckId(String key, String display) {
        this.key = key;
        this.display = display;
    }

    public String key() {
        return key;
    }

    public String display() {
        return display;
    }

    public String bypassPermission() {
        return "voidanticheat.bypass." + key;
    }

    public static CheckId byKey(String text) {
        if (text == null) {
            return null;
        }
        String normalized = text.toLowerCase(Locale.ROOT).replace('-', '_');
        for (CheckId check : values()) {
            if (check.key.equals(normalized) || check.name().toLowerCase(Locale.ROOT).equals(normalized)) {
                return check;
            }
        }
        return null;
    }
}
