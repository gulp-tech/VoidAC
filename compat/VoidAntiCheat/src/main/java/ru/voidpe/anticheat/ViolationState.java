package ru.voidpe.anticheat;

/**
 * Состояние одной проверки у одного игрока: буфер улик, VL, «сколько держится
 * выше порога» и кулдауны действий.
 *
 * <p>Главная идея (как в Grim/Lucy/HawkAC): одно подозрительное событие не
 * должно сразу превращаться во VL и уж тем более в действие. События капаются
 * в буфер; VL растёт только когда буфер переполнен. Повторы чаще
 * {@code sampleGapMillis} весят вчетверо меньше — так лаг-спайк, в котором
 * пришло пять пакетов разом, не даёт пять полных улик. Действия (setback,
 * cancel, наказание) требуют ещё и устойчивости: VL должен держаться выше
 * порога заданное время.</p>
 */
public final class ViolationState {

    private final CheckId check;

    private double vl;
    private double buffer;
    private long lastSampleMillis;
    private long lastFlagMillis;
    private long lastAlertMillis;
    private long lastActionMillis;
    private final long[] aboveSinceMillis = new long[3];
    private long cleanSinceMillis;
    private int samples;
    private int violations;

    public ViolationState(CheckId check) {
        this.check = check;
    }

    public CheckId check() {
        return check;
    }

    public double vl() {
        return vl;
    }

    public void setVl(double value) {
        vl = Math.max(0.0, value);
        if (vl <= 0.001) {
            vl = 0.0;
            clearSustain();
        }
    }

    public double buffer() {
        return buffer;
    }

    public int samples() {
        return samples;
    }

    public int violations() {
        return violations;
    }

    public long lastFlagMillis() {
        return lastFlagMillis;
    }

    public long lastAlertMillis() {
        return lastAlertMillis;
    }

    public void markAlert(long nowMillis) {
        lastAlertMillis = nowMillis;
    }

    public long lastActionMillis() {
        return lastActionMillis;
    }

    public void markAction(long nowMillis) {
        lastActionMillis = nowMillis;
    }

    /** true, если событие пришло достаточно редко, чтобы считаться полноценной уликой. */
    public boolean fresh(long nowMillis, long sampleGapMillis) {
        return lastSampleMillis == 0L || nowMillis - lastSampleMillis >= Math.max(0L, sampleGapMillis);
    }

    /**
     * Кладёт улику в буфер и, если буфер переполнен, начисляет VL.
     *
     * @return true, если порог буфера преодолён и VL вырос
     */
    public boolean record(double points, long nowMillis, long sampleGapMillis, double bufferLimit) {
        if (points <= 0.0) {
            return false;
        }
        double limit = Math.max(0.1, bufferLimit);
        boolean fresh = fresh(nowMillis, sampleGapMillis);
        double weight = fresh ? 1.0 : 0.25;
        samples++;
        buffer = Math.min(limit * 3.0, buffer + points * weight);
        lastSampleMillis = nowMillis;
        if (buffer + 1e-9 < limit) {
            return false;
        }
        buffer = Math.max(0.0, buffer - limit);
        // Улика, которая с трудом пробила буфер, даёт минимум VL;
        // грубая (много очков) — больше.
        vl += Math.max(0.5, Math.min(points, 4.0));
        violations++;
        lastFlagMillis = nowMillis;
        cleanSinceMillis = 0L;
        return true;
    }

    /** Медленное затухание VL и буфера. */
    public void decay(double perSecond, long elapsedMillis) {
        if (elapsedMillis <= 0L) {
            return;
        }
        double seconds = elapsedMillis / 1000.0;
        if (perSecond > 0.0) {
            double next = vl - perSecond * seconds;
            vl = Math.max(0.0, next);
        }
        buffer = Math.max(0.0, buffer * Math.pow(0.85, seconds));
        if (vl <= 0.001) {
            vl = 0.0;
            clearSustain();
            if (buffer <= 0.001) {
                buffer = 0.0;
                if (cleanSinceMillis == 0L) {
                    cleanSinceMillis = System.currentTimeMillis();
                }
            }
        }
    }

    /**
     * Сколько миллисекунд VL непрерывно держится не ниже порога.
     * Используется, чтобы setback/наказание требовали устойчивости, а не
     * одного выброса.
     */
    public long aboveForMillis(int slot, double threshold, long nowMillis) {
        int index = Math.max(0, Math.min(aboveSinceMillis.length - 1, slot));
        if (threshold <= 0.0 || vl + 1e-9 < threshold) {
            aboveSinceMillis[index] = 0L;
            return 0L;
        }
        if (aboveSinceMillis[index] == 0L) {
            aboveSinceMillis[index] = nowMillis;
            return 0L;
        }
        return Math.max(0L, nowMillis - aboveSinceMillis[index]);
    }

    public void clearSustain() {
        java.util.Arrays.fill(aboveSinceMillis, 0L);
    }

    /** Время подряд без единой улики (мс); 0, если улики ещё есть. */
    public long cleanForMillis(long nowMillis) {
        if (cleanSinceMillis == 0L) {
            return 0L;
        }
        return Math.max(0L, nowMillis - cleanSinceMillis);
    }

    public void reset() {
        vl = 0.0;
        buffer = 0.0;
        clearSustain();
        lastSampleMillis = 0L;
        lastFlagMillis = 0L;
        lastActionMillis = 0L;
        samples = 0;
        violations = 0;
    }
}
