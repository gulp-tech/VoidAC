package ru.voidpe.anticheat;

import org.bukkit.Location;

import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Горячее состояние одного игрока. Все поля примитивные/ограниченные по размеру:
 * античит не хранит историю мира и не делает тяжёлых выборок на каждый пакет.
 */
public final class PlayerData {

    public record TimedTarget(long timeMillis, UUID target) {
    }

    public final UUID uuid;
    public boolean bedrock;
    public String inputMode = "UNKNOWN";
    public String deviceOs = "UNKNOWN";
    /**
     * Категория устройства из ядра/Geyser: {@code windows}, {@code android},
     * {@code ios}, {@code xbox}, {@code ps4_5}, {@code switch}, {@code phone_kbm}
     * либо {@code unknown}. {@code unknown} — «данных нет», и все проверки
     * обязаны вести себя мягче, а не строже.
     */
    public String deviceId = "unknown";
    /** Пинг из Geyser-сессии; -1 = неизвестен (тогда работает джиттер). */
    public int sessionPing = -1;
    public String profile = "default";

    public boolean bypassAll;
    public boolean excludedHere;
    public long graceUntilMillis;

    // --- Согласование движения со сторонними плагинами и ядром ---
    /**
     * true — следующее move-событие считается согласованием позиции, а не
     * движением игрока (после телепорта/возрождения/смены мира).
     */
    public boolean unsyncedPosition;
    /** Кэш на тик: инструмент ломает несколько блоков серверным расширением. */
    public boolean multiBreakTick;
    public int multiBreakTickId = Integer.MIN_VALUE;

    // Последняя подтверждённая позиция (примитивы — без лишних Location на каждый move).
    public boolean hasLast;
    public String lastWorld = "";
    public double lastX;
    public double lastY;
    public double lastZ;
    public float lastYaw;
    public float lastPitch;
    public long lastMoveNanos;
    public int lastServerTick;
    public boolean lastOnGround;

    // Последняя безопасная точка для setback.
    public Location safeLocation;
    public long safeUpdatedMillis;

    // --- Подсистемы с чистой, покрытой тестами логикой.
    public final Map<CheckId, ViolationState> checks = new EnumMap<>(CheckId.class);
    public final LagCompensation lag = new LagCompensation();
    public final PredictionMeter prediction = new PredictionMeter();
    public final Map<UUID, TargetHistory> targets = new HashMap<>();

    // Speed: token bucket в блоках.
    public final SpeedBudget speed = new SpeedBudget();
    public long speedRefillNanos;
    public double lastHorizontalBps;

    // Вертикальное движение.
    public int airTicks;
    public int climbStreak;
    public int slowFallStreak;
    public int jesusTicks;
    public int noclipStreak;
    public double peakY;
    public double lastDeltaY;
    public long velocityGraceUntilMillis;
    public long elytraGraceUntilMillis;

    // События мира, которые легально меняют движение.
    public long riptideUntilMillis;
    public long pistonUntilMillis;
    public long explosionUntilMillis;
    public long lastDamageMillis;
    public long lastBlockChangeMillis;

    // Timer: окно количества position-движений к серверным тикам.
    public long timerWindowStartNanos;
    public int timerWindowStartTick;
    public int timerPackets;
    public int timerSuspiciousWindows;

    // Velocity / AntiKB: ожидаемое перемещение после PlayerVelocityEvent.
    public boolean velocityPending;
    public long velocityStartMillis;
    public int velocityStartTick;
    public double velocityUnitX;
    public double velocityUnitZ;
    public double velocityExpectedBlocks;
    public double velocityTravelBlocks;

    public boolean setbackRequested;
    public CheckId lastCheck;
    public String lastDetails = "";
    public long lastViolationMillis;
    public long lastDecayMillis;

    // Ограниченные окна событий.
    public final ArrayDeque<Long> swings = new ArrayDeque<>();
    public final ArrayDeque<Long> breaks = new ArrayDeque<>();
    public final ArrayDeque<Long> places = new ArrayDeque<>();
    public final ArrayDeque<TimedTarget> attacks = new ArrayDeque<>();
    public int lastBreakTick = -1;
    public int breaksThisTick;
    public int lastPlaceTick = -1;
    public int placesThisTick;
    public int scaffoldPlaces;
    public long scaffoldWindowStartMillis;
    public long lastBigRotationMillis;
    public float rotationBeforeAttack;
    public int cpsHighWindows;

    // Транспорт.
    public String vehicleWorld = "";
    public double vehicleX;
    public double vehicleZ;
    public long vehicleLastNanos;
    public double vehicleTokens = 5.0;

    // Кэш снимка окружения на тик: Bedrock часто присылает 2 пакета за тик.
    public int envTick = Integer.MIN_VALUE;
    public double envX;
    public double envY;
    public double envZ;
    public EnvironmentSnapshot env;

    public PlayerData(UUID uuid) {
        this.uuid = uuid;
        for (CheckId check : CheckId.values()) {
            checks.put(check, new ViolationState(check));
        }
        lastDecayMillis = System.currentTimeMillis();
    }

    public ViolationState state(CheckId check) {
        ViolationState state = checks.get(check);
        if (state == null) {
            state = new ViolationState(check);
            checks.put(check, state);
        }
        return state;
    }

    public double vl(CheckId check) {
        return state(check).vl();
    }

    public void setVl(CheckId check, double value) {
        state(check).setVl(value);
    }

    public TargetHistory history(UUID target) {
        TargetHistory history = targets.get(target);
        if (history == null) {
            history = new TargetHistory();
            targets.put(target, history);
        }
        return history;
    }

    public void rememberPosition(Location to, int serverTick, long nowNanos, boolean onGround) {
        hasLast = true;
        lastWorld = to.getWorld() == null ? "" : to.getWorld().getName();
        lastX = to.getX();
        lastY = to.getY();
        lastZ = to.getZ();
        lastYaw = to.getYaw();
        lastPitch = to.getPitch();
        lastMoveNanos = nowNanos;
        lastServerTick = serverTick;
        lastOnGround = onGround;
    }

    public void resetMovement(Location to, int serverTick, long nowNanos, boolean onGround) {
        rememberPosition(to, serverTick, nowNanos, onGround);
        resetAirState();
        speed.softReset(4.5);
        timerWindowStartNanos = nowNanos;
        timerWindowStartTick = serverTick;
        timerPackets = 0;
        velocityPending = false;
        env = null;
        envTick = Integer.MIN_VALUE;
        lag.reset();
    }

    public void resetAirState() {
        airTicks = 0;
        climbStreak = 0;
        slowFallStreak = 0;
        jesusTicks = 0;
        peakY = lastY;
        prediction.reset();
    }

    /** Сбросить «сколько держится выше порога» у всех проверок (телепорт, setback). */
    public void clearSustainAll() {
        for (ViolationState state : checks.values()) {
            state.clearSustain();
        }
    }

    /** Полный сброс подозрений (смерть, ручной /vac reset). */
    public void clearViolations() {
        for (ViolationState state : checks.values()) {
            state.reset();
        }
        targets.clear();
    }
}
