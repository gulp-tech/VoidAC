package ru.voidpe.anticheat;

import java.util.ArrayDeque;

/**
 * Короткая история хитбокса цели для двусторонней компенсации пинга в Reach.
 *
 * <p>Без пакетов нельзя перемотать мир, зато можно хранить последние позиции
 * цели (они обновляются на её же move-событиях). Удар проверяется по
 * <b>минимальной</b> дистанции до любого из последних положений в окне
 * пинга: если игрок и цель лагают, честный удар на 3.2 блока не станет
 * «4.0 блоками» из-за рассинхрона.</p>
 */
public final class TargetHistory {

    private static final int MAX_SAMPLES = 8;

    public record Sample(long timeMillis, double centerX, double centerY, double centerZ,
                         double halfX, double halfY, double halfZ) {
    }

    private final ArrayDeque<Sample> samples = new ArrayDeque<>();

    public void remember(Sample sample) {
        if (sample == null) {
            return;
        }
        samples.addLast(sample);
        while (samples.size() > MAX_SAMPLES) {
            samples.removeFirst();
        }
    }

    public void clear() {
        samples.clear();
    }

    public int size() {
        return samples.size();
    }

    /**
     * Минимальная дистанция от точки (обычно глаз атакующего) до любого
     * хитбокса цели, наблюдавшегося за последние {@code windowMillis}.
     * Если истории нет — возвращает {@code current}.
     */
    public double minDistance(double eyeX, double eyeY, double eyeZ, long nowMillis,
                              long windowMillis, double current) {
        double best = current;
        for (Sample sample : samples) {
            if (nowMillis - sample.timeMillis() > windowMillis) {
                continue;
            }
            double dx = axisDistance(eyeX, sample.centerX(), sample.halfX());
            double dy = axisDistance(eyeY, sample.centerY(), sample.halfY());
            double dz = axisDistance(eyeZ, sample.centerZ(), sample.halfZ());
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (distance < best) {
                best = distance;
            }
        }
        return best;
    }

    /** Разница между текущей дистанцией и лучшей исторической: признак рассинхрона. */
    public double desync(double current, double best) {
        return Math.max(0.0, current - best);
    }

    private static double axisDistance(double point, double center, double half) {
        double min = center - half;
        double max = center + half;
        if (point < min) {
            return min - point;
        }
        if (point > max) {
            return point - max;
        }
        return 0.0;
    }

    /** Хитбокс цели в виде центра и половин — то, что нужно для minDistance. */
    public static Sample sample(long nowMillis, double minX, double minY, double minZ,
                                double maxX, double maxY, double maxZ) {
        return new Sample(nowMillis,
                (minX + maxX) / 2.0, (minY + maxY) / 2.0, (minZ + maxZ) / 2.0,
                (maxX - minX) / 2.0, (maxY - minY) / 2.0, (maxZ - minZ) / 2.0);
    }
}
