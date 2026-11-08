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
}
