package ru.voidpe.anticheat;

/**
 * Мини-предсказание вертикальной физики (Grim-подобный подход, но без пакетов).
 *
 * <p>Модель ванильная: после отрыва от земли скорость уменьшается на 0.08 и
 * умножается на 0.98 за тик. Сравниваем с фактическим dy, накопленным за
 * {@code tickDelta} тиков. Наказываем только за две физически невозможные
 * картины:</p>
 *
 * <ul>
 *   <li><b>impossible</b> — игрок в воздухе набрал больше 3 блоков высоты с
 *       постоянным отклонением от модели (прыжок даёт 1.25, Jump Boost II —
 *       около 2.6, поэтому порог 3.0 оставляет запас);</li>
 *   <li><b>hover</b> — больше 2 секунд «висит» без вертикального движения и
 *       без опоры под ногами.</li>
 * </ul>
 *
 * <p>Всё остальное (лаг, пачки пакетов, спринт-прыжки Bedrock) модель
 * поглощает: она стартует с наблюдаемой скорости прыжка, а не с константы,
 * поэтому не зависит от того, как Geyser пересчитал импульс.</p>
 */
public final class PredictionMeter {

    private static final double GRAVITY = 0.08;
    private static final double DRAG = 0.98;

    /** Результат одного тика наблюдения. */
    public record Verdict(boolean impossibleAscent, boolean hover, double ascent, double offset, int airTicks) {
        public static final Verdict NONE = new Verdict(false, false, 0.0, 0.0, 0);
    }

    // --- Статистика измерений (шаг 1 внедрения предсказания) ---
    // Нужна, чтобы решать по цифрам, а не по обещаниям: насколько сильное
    // расхождение реально дают телефоны, прокси и Geyser на этом сервере.
    private int statSamples;
    private double statSumAbs;
    private double statMaxAbs;
    private final int[] statBuckets = new int[BUCKETS];

    private static final int BUCKETS = 21;

    /** Выключатель статистики из конфига ({@code world.prediction.measure}). */
    private static volatile boolean measureEnabled = true;

    public static void setMeasureEnabled(boolean enabled) {
        measureEnabled = enabled;
    }

    private double expectedVy;
    private double offsetEma;
    private double ascent;
    private int airTicks;
    private int hoverCounter;
    private boolean initialized;

    /** Сброс при приземлении, телепорте, смене мира, посадке в транспорт. */
    public void reset() {
        expectedVy = 0.0;
        offsetEma = 0.0;
        ascent = 0.0;
        airTicks = 0;
        hoverCounter = 0;
        initialized = false;
    }

    public int airTicks() {
        return airTicks;
    }

    public double offset() {
        return offsetEma;
    }

    /**
     * @param dy            вертикальное смещение за сэмпл (может охватывать несколько тиков)
     * @param tickDelta     сколько серверных тиков прошло
     * @param leftGround    игрок только что оторвался от земли
     */
    public Verdict tick(double dy, int tickDelta, boolean leftGround) {
        return tick(dy, tickDelta, leftGround, 3.0, 40);
    }

    /**
     * @param ascentLimit  сколько блоков подряд можно набрать в воздухе
     * @param hoverTicks   сколько тиков «висения» считаем невозможными
     */
    public Verdict tick(double dy, int tickDelta, boolean leftGround, double ascentLimit, int hoverTicks) {
        int ticks = Math.max(1, tickDelta);
        if (leftGround || !initialized) {
            // Стартуем с фактической скорости: у Geyser импульс прыжка может
            // отличаться от ванильного на несколько процентов — это не чит.
            expectedVy = dy;
            initialized = true;
            airTicks = 0;
            ascent = 0.0;
            hoverCounter = 0;
            offsetEma = 0.0;
        }
        for (int i = 0; i < ticks; i++) {
            expectedVy = (expectedVy - GRAVITY) * DRAG;
        }
        double deviation = dy - expectedVy;
        recordStat(Math.abs(deviation));
        offsetEma += (Math.max(-2.0, Math.min(2.0, deviation)) - offsetEma) * 0.35;
        airTicks += ticks;
        if (dy > 0.004) {
            ascent += dy;
        }
        if (Math.abs(dy) < 0.035) {
            hoverCounter += ticks;
        } else {
            hoverCounter = 0;
        }
        boolean impossible = ascent > Math.max(2.20, ascentLimit) && offsetEma > 0.12 && airTicks > 24;
        boolean hover = hoverCounter > Math.max(20, hoverTicks) && airTicks > 40 && offsetEma > 0.06;
        return new Verdict(impossible, hover, ascent, offsetEma, airTicks);
    }

    // ---------------------------------------------------------------
    // Шаг 1: измерения. Ничего не решают, только собирают статистику.
    // ---------------------------------------------------------------

    private void recordStat(double offset) {
        // Всё, что больше 4 блоков за тик, — не расхождение физики, а
        // телепорт или рассинхрон. Такие выборки не портят статистику.
        if (!measureEnabled || !Double.isFinite(offset) || offset > 4.0) {
            return;
        }
        statSamples++;
        statSumAbs += offset;
        statMaxAbs = Math.max(statMaxAbs, offset);
        int bucket = Math.min(BUCKETS - 1, (int) Math.round(offset * 100.0));
        statBuckets[Math.max(0, bucket)]++;
    }

    public int samples() {
        return statSamples;
    }

    /** Медиана абсолютного расхождения в блоках (по гистограмме). */
    public double medianAbs() {
        if (statSamples == 0) {
            return 0.0;
        }
        int half = statSamples / 2;
        int acc = 0;
        for (int i = 0; i < BUCKETS; i++) {
            acc += statBuckets[i];
            if (acc >= half) {
                return i / 100.0;
            }
        }
        return (BUCKETS - 1) / 100.0;
    }

    public double meanAbs() {
        return statSamples == 0 ? 0.0 : statSumAbs / statSamples;
    }

    public double maxAbs() {
        return statMaxAbs;
    }

    /** Доля выборок с расхождением больше порога (в блоках). */
    public double shareAbove(double threshold) {
        if (statSamples == 0) {
            return 0.0;
        }
        int limit = Math.min(BUCKETS, (int) Math.ceil(threshold * 100.0));
        int above = 0;
        for (int i = limit; i < BUCKETS; i++) {
            above += statBuckets[i];
        }
        return (double) above / statSamples;
    }

    public void resetStats() {
        statSamples = 0;
        statSumAbs = 0;
        statMaxAbs = 0;
        java.util.Arrays.fill(statBuckets, 0);
    }

    /**
     * Отчёт по-русски. Явно говорит, когда выборок мало: иной раз «0.0000»
     * читают как успех, хотя это просто отсутствие данных.
     */
    public String describe() {
        if (statSamples == 0) {
            return "\u00a77предсказание: \u00a7cнет данных\u00a77 (игрок не был в свободном падении)";
        }
        double median = medianAbs();
        String verdict;
        if (statSamples < 100) {
            verdict = "\u00a7eмало выборок — вывод делать рано (нужно 10-15 минут игры)";
        } else if (median <= 0.002) {
            verdict = "\u00a7aфизика совпадает с ванильной — можно снижать допуск";
        } else if (median <= 0.02) {
            verdict = "\u00a7eумеренное расхождение — предсказание годно, но нужен допуск";
        } else {
            verdict = "\u00a7cбольшое расхождение — в чистом виде не годится";
        }
        return String.format(java.util.Locale.ROOT,
                "\u00a77предсказание: выборок \u00a7f%d\u00a77, медиана \u00a7f%.4f\u00a77, "
                        + "среднее \u00a7f%.4f\u00a77, максимум \u00a7f%.4f\u00a77, >0.01 бл: \u00a7f%.1f%%\u00a77 — %s",
                statSamples, median, meanAbs(), maxAbs(), shareAbove(0.01) * 100.0, verdict);
    }
}
