package ru.voidpe.anticheat;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.Locale;

/**
 * Шаг 1 внедрения предсказания движения: <b>только измерения</b>.
 *
 * <p>Этот класс ничего не блокирует, не откатывает и не выдаёт улик. Он
 * сравнивает фактическое вертикальное перемещение игрока с тем, которое
 * предсказывает ванильная физика, и складывает расхождения в статистику.
 * Включение проверок на основе этих чисел — отдельный шаг, и он возможен
 * только после того, как на реальном сервере наберётся статистика.</p>
 *
 * <h2>Почему сначала измерения</h2>
 * <p>Предсказание движения в Boar (MIT) работает с допуском в
 * {@code 0.0001} блока на координату. На Bedrock-сервере с телефонами,
 * прокси и Geyser это почти наверняка даст массовые ложные срабатывания:
 * сам конфиг Boar признаёт, что не справляется с расхождением меньше
 * {@code 5.0E-5}. Поэтому сначала нужно узнать, какие расхождения реальны
 * <b>на этом сервере</b>, а не брать чужие числа.</p>
 *
 * <h2>Что именно считается</h2>
 * <p>Вертикаль — самая надёжная часть: гравитация не зависит от ввода,
 * трения и коллизий по горизонтали. Для падения без опоры:</p>
 * <pre>
 *   v.y -= 0.08          // гравитация
 *   v.y *= 0.98          // сопротивление воздуха
 * </pre>
 * <p>Эти две строки взяты из открытого кода Boar
 * ({@code GroundAndAirPredictionEngine.finalizeMovement}) и совпадают с
 * ванильным {@code LivingEntity.travel}. Мы предсказываем только свободное
 * падение: опора, вода, листва, элитра и левитация исключаются, потому что
 * там нужна полная симуляция коллизий.</p>
 *
 * <h2>Как читать результат</h2>
 * <p>Команда {@code /vac predict} печатает статистику. Если медианное
 * расхождение около нуля — физика на сервере совпадает с ванильной и можно
 * переходить к шагу 2. Если медиана большая или разброс огромный — на этом
 * сервере предсказание в таком виде работать не будет, и это лучше узнать
 * до того, как оно начнёт откатывать игроков.</p>
 */
final class PredictionProbe {

    /** Сколько выборок держим в памяти на игрока. */
    private static final int MAX_SAMPLES = 600;

    /** Границы разумного: всё, что больше, — не «расхождение», а телепорт. */
    private static final double MAX_ABS_OFFSET = 4.0;

    // --- ванильные константы (см. javadoc) ---
    private double velocityY;
    private boolean primed;

    // --- статистика ---
    private int samples;
    private int skips;
    private double sumAbs;
    private double maxAbs;
    private double lastOffset;
    /** Простейшая гистограмма: индекс = сантиметры расхождения, 0..20+. */
    private final int[] buckets = new int[21];

    /** Состояние, при котором предсказание не применяется (вода, полёт и т.п.). */
    private long suppressedTicks;

    /**
     * Пробует посчитать расхождение за один пакет движения.
     *
     * <p>Возвращает {@code true}, если выборка учтена. Отсутствие выборки —
     * норма: почти всегда причина в том, что игрок стоит на земле или
     * плывёт, и предсказание для этих случаев здесь не реализовано.</p>
     */
    boolean sample(Player player, Location from, Location to, boolean fromOnGround,
                   boolean toOnGround, int tick) {
        if (player == null || from == null || to == null) {
            return false;
        }
        // Опора, вода, полёт, транспорт — вне зоны предсказания шага 1.
        if (fromOnGround || toOnGround || suppressed(player)) {
            primed = false;
            velocityY = 0.0;
            suppressedTicks++;
            skips++;
            return false;
        }

        double dy = to.getY() - from.getY();

        if (!primed) {
            // Первый тик свободного падения: скорость ещё неизвестна, берём
            // фактическую дельту как начальное приближение.
            velocityY = dy;
            primed = true;
            skips++;
            return false;
        }

        // Шаг физики: гравитация, затем сопротивление воздуха.
        velocityY -= 0.08;
        velocityY *= 0.98;
        double predicted = velocityY;

        // Ограничитель: на Bedrock его нет, но при рассинхроне клиент может
        // прислать дельту больше блока — это уже не «расхождение физики».
        if (Math.abs(dy) > MAX_ABS_OFFSET * 2) {
            primed = false;
            velocityY = 0.0;
            skips++;
            return false;
        }

        double offset = Math.abs(dy - predicted);
        lastOffset = dy - predicted;
        samples++;
        sumAbs += offset;
        maxAbs = Math.max(maxAbs, offset);
        int bucket = Math.min(buckets.length - 1, (int) Math.round(offset * 100.0));
        buckets[Math.max(0, bucket)]++;

        // Догоняем предсказание фактом, чтобы ошибка не накапливалась:
        // иначе после одного сбоя все следующие выборки будут «неверными».
        velocityY = dy;
        return true;
    }

    /**
     * Признаки того, что свободного падения нет. Полная проверка потребовала
     * бы симуляции коллизий, поэтому здесь только дешёвые и однозначные случаи.
     */
    private boolean suppressed(Player player) {
        try {
            if (player.isFlying() || player.isGliding() || player.isSwimming()
                    || player.isInsideVehicle() || player.isClimbing()) {
                return true;
            }
            // Вода и лава: другая физика (0.02 вместо 0.08, иное сопротивление).
            if (player.isInWater() || player.getRemainingAir() < player.getMaximumAir()) {
                return true;
            }
            // Левитация меняет гравитацию на противоположную.
            if (player.hasPotionEffect(org.bukkit.potion.PotionEffectType.LEVITATION)
                    || player.hasPotionEffect(org.bukkit.potion.PotionEffectType.SLOW_FALLING)
                    || player.hasPotionEffect(org.bukkit.potion.PotionEffectType.JUMP_BOOST)) {
                return true;
            }
            return false;
        } catch (Throwable ignored) {
            return true;
        }
    }

    // --- отчёт ---

    int samples() {
        return samples;
    }

    boolean hasData() {
        return samples > 0;
    }

    /** Среднее абсолютное расхождение в блоках. */
    double meanAbs() {
        return samples == 0 ? 0.0 : sumAbs / samples;
    }

    double maxAbs() {
        return maxAbs;
    }

    double lastOffset() {
        return lastOffset;
    }

    /**
     * Медиана по гистограмме: точное значение не нужно, важен порядок —
     * 0.001 это норма, 0.05 это уже сломанная физика.
     */
    double medianAbs() {
        if (samples == 0) {
            return 0.0;
        }
        int half = samples / 2;
        int acc = 0;
        for (int i = 0; i < buckets.length; i++) {
            acc += buckets[i];
            if (acc >= half) {
                return i / 100.0;
            }
        }
        return (buckets.length - 1) / 100.0;
    }

    /** Доля выборок, где расхождение больше порога (в блоках). */
    double shareAbove(double threshold) {
        if (samples == 0) {
            return 0.0;
        }
        int limit = (int) Math.ceil(threshold * 100.0);
        int above = 0;
        for (int i = Math.min(limit, buckets.length); i < buckets.length; i++) {
            above += buckets[i];
        }
        return (double) above / samples;
    }

    void reset() {
        samples = 0;
        skips = 0;
        sumAbs = 0;
        maxAbs = 0;
        lastOffset = 0;
        velocityY = 0;
        primed = false;
        suppressedTicks = 0;
        java.util.Arrays.fill(buckets, 0);
    }

    /**
     * Готовая строка для чата: без вранья о точности, с явным числом выборок.
     * Если выборок мало, так и пишем — иначе «0.000» будет читаться как успех.
     */
    String describe() {
        if (samples == 0) {
            return String.format(Locale.ROOT,
                    "§7предсказание: §cнет данных§7 (пропущено %d пакетов: опора/вода/полёт)", skips);
        }
        String verdict;
        double median = medianAbs();
        if (samples < 100) {
            verdict = "§eмало выборок — вывод рано делать";
        } else if (median <= 0.002) {
            verdict = "§aфизика совпадает с ванильной";
        } else if (median <= 0.02) {
            verdict = "§eнебольшое расхождение — нужен допуск";
        } else {
            verdict = "§cбольшое расхождение — предсказание в таком виде не годится";
        }
        return String.format(Locale.ROOT,
                "§7предсказание: выборок §f%d§7, медиана §f%.4f§7, среднее §f%.4f§7, "
                        + "максимум §f%.4f§7, >0.01 бл: §f%.1f%%§7 — %s",
                samples, median, meanAbs(), maxAbs(), shareAbove(0.01) * 100.0, verdict);
    }
}
