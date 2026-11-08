package ru.voidpe.anticheat;

/**
 * Оценка сетевого шума игрока без пакетного API.
 *
 * <p>Java-античиты берут пинг из транзакций; у Bedrock через Geyser пинг часто
 * 0 или сильно занижен, поэтому здесь измеряется то, что реально видно:
 * интервалы между move-событиями. Из них считается EMA интервала, EMA
 * отклонения (джиттер) и «нестабильное окно» после лаг-спайка.</p>
 *
 * <p>Все значения — чистые double, класс не зависит от Bukkit и покрыт
 * тестами: 10 000 «честных» профилей (ровный 20 Гц, джиттер, пачки пакетов,
 * спайки 0.3–5 с) не должны ни разу пометить игрока нестабильным дольше,
 * чем на время самого спайка.</p>
 */
public final class LagCompensation {

    /** Ограничения компенсации, читаются из config.yml один раз. */
    public record Limits(
            double pingFactorPer100ms,
            double maxPingFactor,
            double jitterFactorPerMs,
            double maxJitterFactor,
            double tpsFactorPerPoint,
            double maxTpsFactor,
            double spikeIntervalMs,
            double unstableHoldMs
    ) {
        public static Limits defaults() {
            return new Limits(0.020, 0.12, 0.0012, 0.30, 0.05, 0.45, 900.0, 2500.0);
        }

        public Limits sanitized() {
            return new Limits(
                    nonNegative(pingFactorPer100ms, 0.020),
                    clamp(maxPingFactor, 0.0, 1.0, 0.12),
                    nonNegative(jitterFactorPerMs, 0.0012),
                    clamp(maxJitterFactor, 0.0, 2.0, 0.30),
                    nonNegative(tpsFactorPerPoint, 0.05),
                    clamp(maxTpsFactor, 0.0, 2.0, 0.45),
                    clamp(spikeIntervalMs, 100.0, 10_000.0, 900.0),
                    clamp(unstableHoldMs, 0.0, 30_000.0, 2500.0));
        }
    }

    private static final double NOMINAL_INTERVAL_MS = 50.0;
    private static final double UNSTABLE_DEVIATION_MS = 240.0;
    private static final double IDLE_RESTART_MS = 2500.0;

    private double intervalEmaMs = NOMINAL_INTERVAL_MS;
    private double deviationEmaMs;
    private long lastSampleNanos;
    private long unstableUntilMillis;
    private int samples;
    private int spikes;

    /** Вызывается на каждом реальном перемещении (не чаще одного раза за пакет). */
    public void sample(long nowNanos, long nowMillis, int tickDelta, Limits limits) {
        Limits safe = limits == null ? Limits.defaults() : limits.sanitized();
        if (lastSampleNanos != 0L) {
            double interval = (nowNanos - lastSampleNanos) / 1_000_000.0;
            if (interval > IDLE_RESTART_MS) {
                // Игрок стоял на месте (move-событий не было) — это не лаг-спайк,
                // а просто пауза: начинаем статистику заново, без штрафов.
                lastSampleNanos = nowNanos;
                return;
            }
            double expected = tickDelta <= 0 ? NOMINAL_INTERVAL_MS : tickDelta * NOMINAL_INTERVAL_MS;
            double deviation = Math.abs(interval - expected);
            intervalEmaMs += (interval - intervalEmaMs) * 0.25;
            deviationEmaMs += (Math.min(deviation, 4000.0) - deviationEmaMs) * 0.30;
            samples++;
            if (interval > safe.spikeIntervalMs()) {
                spikes++;
                unstableUntilMillis = Math.max(unstableUntilMillis, nowMillis + (long) safe.unstableHoldMs());
            } else if (deviation > UNSTABLE_DEVIATION_MS) {
                // Пачка пакетов после короткого фриза — тоже повод не доверять
                // одному-двум следующим сэмплам.
                unstableUntilMillis = Math.max(unstableUntilMillis,
                        nowMillis + (long) (safe.unstableHoldMs() * 0.4));
            }
        }
        lastSampleNanos = nowNanos;
    }

    public void reset() {
        intervalEmaMs = NOMINAL_INTERVAL_MS;
        deviationEmaMs = 0.0;
        lastSampleNanos = 0L;
        unstableUntilMillis = 0L;
    }

    /** Пока true, движение не должно приводить даже к setback: сначала ждём стабилизации. */
    public boolean unstable(long nowMillis) {
        return nowMillis < unstableUntilMillis;
    }

    public double intervalMs() {
        return intervalEmaMs;
    }

    public double deviationMs() {
        return deviationEmaMs;
    }

    public int samples() {
        return samples;
    }

    public int spikes() {
        return spikes;
    }

    /**
     * Множитель допуска для порогов движения/боя: 1.0 — идеальная сеть,
     * до 1 + maxPing + maxJitter + maxTps на плохой.
     */
    public double tolerance(int pingMillis, double tps, Limits limits) {
        Limits safe = limits == null ? Limits.defaults() : limits.sanitized();
        double pingPart = Math.min(safe.maxPingFactor(),
                Math.max(0, pingMillis) / 100.0 * safe.pingFactorPer100ms());
        double jitterPart = Math.min(safe.maxJitterFactor(), deviationEmaMs * safe.jitterFactorPerMs());
        double tpsPart = Math.min(safe.maxTpsFactor(), Math.max(0.0, 20.0 - tps) * safe.tpsFactorPerPoint());
        return 1.0 + pingPart + jitterPart + tpsPart;
    }

    /**
     * Насколько можно верить одному нарушению: на шумном канале улика весит
     * меньше, поэтому одиночный выброс не превращается во VL.
     */
    public double confidence(int pingMillis, double tps) {
        double jitterPenalty = Math.min(0.55, deviationEmaMs / 500.0);
        double pingPenalty = Math.min(0.20, Math.max(0, pingMillis - 250) / 2500.0);
        double tpsPenalty = Math.min(0.25, Math.max(0.0, 19.5 - tps) * 0.15);
        return Math.max(0.25, 1.0 - jitterPenalty - pingPenalty - tpsPenalty);
    }

    /**
     * Сколько секунд реально прошло между сэмплами, если верить серверным
     * тикам. Нужна, чтобы пачка пакетов в одном тике не выглядела как
     * мгновенное перемещение на 5 блоков.
     */
    public static double effectiveSeconds(double rawSeconds, int tickDelta) {
        double tickSeconds = Math.max(0, tickDelta) / 20.0;
        return Math.max(rawSeconds, tickSeconds);
    }

    private static double nonNegative(double value, double fallback) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return fallback;
        }
        return Math.max(0.0, value);
    }

    private static double clamp(double value, double min, double max, double fallback) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return fallback;
        }
        return Math.max(min, Math.min(max, value));
    }
}
