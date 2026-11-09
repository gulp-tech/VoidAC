package ru.voidpe.anticheat;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;

/**
 * Настройки одной проверки из секции {@code checks.<key>}.
 *
 * <p>{@code buffer} — сколько «очков улик» нужно накопить, чтобы получить
 * первый VL. Большой буфер = проверка почти не даёт ложных срабатываний, но
 * и требует устойчивого нарушения. Для физически невозможных событий
 * (BadPackets) буфер можно выставить в 0 — там сама улика и есть доказательство.</p>
 */
public record CheckSettings(
        boolean enabled,
        double maxVl,
        double decayPerSecond,
        double setbackVl,
        double cancelVl,
        double punishVl,
        double bufferLimit,
        List<String> punishCommands
) {

    public static CheckSettings load(FileConfiguration config, CheckId check) {
        String base = "checks." + check.key();
        boolean enabled = config.getBoolean(base + ".enabled",
                config.getBoolean("checks.default.enabled", true));
        double maxVl = positive(config.getDouble(base + ".max-vl",
                config.getDouble("checks.default.max-vl", 20.0)), 20.0);
        double decay = Math.max(0.0, config.getDouble(base + ".decay-per-second",
                config.getDouble("checks.default.decay-per-second", 0.12)));
        double setback = config.getDouble(base + ".setback-vl",
                config.getDouble("checks.default.setback-vl", 0.0));
        double cancel = config.getDouble(base + ".cancel-vl",
                config.getDouble("checks.default.cancel-vl", 0.0));
        double punish = config.getDouble(base + ".punish-vl",
                config.getDouble("checks.default.punish-vl", 0.0));
        double buffer = Math.max(0.0, config.getDouble(base + ".buffer",
                config.getDouble("checks.default.buffer", 2.5)));
        List<String> commands = config.getStringList(base + ".punish-commands");
        if (commands.isEmpty()) {
            commands = config.getStringList("punishments.commands");
        }
        return new CheckSettings(enabled, maxVl, decay, setback, cancel, punish, buffer, List.copyOf(commands));
    }

    private static double positive(double value, double fallback) {
        return value > 0 ? value : fallback;
    }

    public boolean shouldSetback(double vl) {
        return setbackVl > 0 && vl + 1e-9 >= setbackVl;
    }

    public boolean shouldCancel(double vl) {
        return cancelVl > 0 && vl + 1e-9 >= cancelVl;
    }

    public boolean shouldPunish(double vl) {
        return punishVl > 0 && vl + 1e-9 >= punishVl && punishVl <= maxVl + 0.0001;
    }
}
