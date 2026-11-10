package ru.voidpe.anticheat;

/**
 * Token bucket горизонтальной скорости в блоках.
 *
 * <p>Разовый рывок (прыжок, поршень, лёд) съедает запас токенов, но не даёт
 * улики: нужен перерасход больше {@code burst}. Лаг-спайк обрабатывается
 * отдельно — сэмпл выбрасывается и токены пополняются консервативно, иначе
 * «наверстывание» пачки пакетов выглядело бы как Speed.</p>
 */
public final class SpeedBudget {

    private double tokens;
    private boolean initialized;

    public void reset(double cap) {
        tokens = cap;
        initialized = true;
    }

    /** Сброс в «мягкий» запас после телепорта/грейса. */
    public void softReset(double cap) {
        if (!initialized) {
            tokens = cap;
            initialized = true;
        } else {
            tokens = Math.max(tokens, Math.min(cap, cap * 0.45));
        }
    }

    public double tokens() {
        return tokens;
    }

    /**
     * @return перерасход в блоках сверх допустимого рывка; 0 — нарушения нет
     */
    public double update(double horizontalBlocks, double allowedBps, double elapsedSeconds,
                         double cap, double burst) {
        if (!initialized) {
            tokens = cap;
            initialized = true;
        }
        double safeCap = Math.max(0.5, cap);
        double safeBurst = Math.max(0.1, burst);
        tokens = Math.min(safeCap, tokens + Math.max(0.0, allowedBps) * Math.max(0.0, elapsedSeconds));
        tokens -= Math.max(0.0, horizontalBlocks);
        if (tokens < -safeBurst) {
            double excess = -tokens - safeBurst;
            tokens = -safeBurst * 0.40;
            return excess;
        }
        if (tokens < -safeBurst * 3.0) {
            tokens = -safeBurst * 3.0;
        }
        return 0.0;
    }

    /** Лаг/грейс: не штрафуем, но и не копим бесконечный запас. */
    public void discard(double allowedBps, double cap) {
        if (!initialized) {
            reset(cap);
            return;
        }
        tokens = Math.min(cap, Math.max(tokens, Math.max(0.0, allowedBps) * 0.35));
    }
}
