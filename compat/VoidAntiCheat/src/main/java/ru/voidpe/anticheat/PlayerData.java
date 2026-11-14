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

    public record TimedTarget(long timeMillis, UUID target, double distance) {
        public TimedTarget(long timeMillis, UUID target) {
            this(timeMillis, target, 0.0);
        }
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
    /** Суммарный VL, посчитанный последним (для метки и быстрых отчётов). */
    public double lastAvg;
    /** Троттлинг метки AVG: не чаще раза в секунду на игрока. */
    public long lastAvgLabelMillis;
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
    public float lastYawValue;
    public float lastPitchValue;
    /** Накопленное движение взгляда за окно 500 мс (градусы). */
    public double aimMovedDegrees;
    public long aimWindowStartMillis;
    public int idleAimStreak;
    /**
     * Углы между взглядом и направлением на центр цели в момент удара.
     *
     * <p>HvH-логика: у читера настраивается поворот, радиус, задержка и CPS,
     * но НЕ точность наводки — без неё удар по цели не проходит. Поэтому
     * вместо «насколько сильно крутится камера» меряем «насколько точно она
     * встала на цель». Живой игрок распределён широко, aimbot сжат к нулю.</p>
     */
    public final ArrayDeque<Double> aimAngleErrors = new ArrayDeque<>();
    /** Сколько ударов подряд взгляд был почти идеально на цели. */
    public int preciseAimStreak;
    public int edgeReachStreak;
    public long lastOffscreenHitMillis;
    /**
     * Скрытый поворот: KillAura (silent rotation) бьёт по цели, повернув камеру
     * только на своей стороне, а на сервер шлёт исходный угол. В бинарнике
     * Apollon за это отвечают {@code rotations}/{@code rotationsRange} и
     * {@code emulateNativeAttack}. Признак — удар по цели, в которую игрок не
     * смотрит, но при этом его собственный взгляд за окно не двигался.
     * Человек, который мажет, взгляд всё равно поворачивает.
     */
    public int silentRotationStreak;
    /**
     * ChestAura / INFINITY_AURA (com.arlen.app, клиент Arlen). Модуль сам
     * открывает контейнеры вокруг игрока, не подходя к ним.
     *
     * <p>Улика в сочетании трёх вещей, которое у живого игрока не встречается:
     * контейнер далеко (за пределами ванильного доступа), игрок к нему не
     * приближался, и взгляд за последнюю секунду не двигался. Клик по
     * контейнеру руками требует как минимум подойти или посмотреть.</p>
     */
    public int chestAuraStreak;
    /** Минимальное расстояние до контейнера за окно 3 с, блоки. */
    public double chestNearestDistance = Double.MAX_VALUE;
    public long chestWindowStartMillis;
    /** Накопленный поворот взгляда за окно 1 с, градусы. */
    public double chestAimDegrees;
    /** Сколько градусов взгляд прошёл за окно [silentAimWindowStart, now]. */
    public double silentAimWindowDegrees;
    public long silentAimWindowStart;
    public int cpsHighWindows;

    // ---------------------------------------------------------------- ввод
    /**
     * Кэш определителя устройства. Рефлексия в горячем пути недопустима,
     * поэтому профиль опрашивается не чаще раза в несколько секунд.
     */
    public DeviceProbe.Profile device = DeviceProbe.Profile.unknown("ещё не опрашивался");
    public long deviceProbedAt;
    /** Поведенческий отпечаток, когда и ядро, и Floodgate молчат. */
    public final DeviceProbe.Behaviour behaviour = new DeviceProbe.Behaviour();
    /** Подозрение на подмену платформы уже записано в лог. */
    public boolean spoofWarned;

    // ------------------------------------------------------- добыча ресурсов
    /** Последние разрушенные блоки: тип и время. */
    public final ArrayDeque<BrokenBlock> brokenBlocks = new ArrayDeque<>();
    /** Сколько руды подряд сломано без единого промаха по камню. */
    public int oreStreak;
    /** Сколько разных руд за окно — бот берёт всё подряд. */
    public int oreVariety;
    public long oreStreakStart;
    /** Последний поворот перед разрушением блока (для AutoMine). */
    public float yawBeforeBreak;
    public double pitchBeforeBreak;

    // ------------------------------------------------------------- постройка
    /** Отпечатки установленных блоков за окно: смещение от игрока + тип. */
    public final ArrayDeque<String> placePattern = new ArrayDeque<>();
    /** Времена действий для проверки бота (удары, блоки, прыжки). */
    public final ArrayDeque<Long> botActions = new ArrayDeque<>();

    /** Сколько раз подряд повторился один и тот же отпечаток. */
    public int patternRepeats;
    public double lastPlaceOffsetX;
    public double lastPlaceOffsetZ;

    /**
     * Сломанный блок: что и когда. Тип нужен, чтобы отличать охоту за рудой
     * от обычной копки камня — именно на этом ловится AutoMine.
     */
    public record BrokenBlock(long timeMillis, org.bukkit.Material type, boolean ore) {
    }

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

    /**
     * Лимит целей. Окно компенсации пинга — 8 сэмплов на цель, а чистка
     * происходит раз в {@code secondTick} при 10 секундах тишины. Без лимита
     * игрок в массовом PvP (арена, клановая война) успевал завести историю
     * на каждого противника, и карта росла до конца боя. Пятнадцати целей
     * хватает: одновременно бьют максимум нескольких.
     */
    private static final int MAX_TARGETS = 15;

    public TargetHistory history(UUID target) {
        TargetHistory history = targets.get(target);
        if (history == null) {
            if (targets.size() >= MAX_TARGETS) {
                // Освобождаем место: сбрасываем самую старую по времени сэмпла.
                UUID oldest = null;
                long oldestTime = Long.MAX_VALUE;
                for (Map.Entry<UUID, TargetHistory> entry : targets.entrySet()) {
                    long newest = entry.getValue().newestTimeMillis();
                    if (newest < oldestTime) {
                        oldestTime = newest;
                        oldest = entry.getKey();
                    }
                }
                if (oldest != null) {
                    TargetHistory evicted = targets.remove(oldest);
                    if (evicted != null) {
                        evicted.clear();
                    }
                }
            }
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
        brokenBlocks.clear();
        placePattern.clear();
        botActions.clear();
        oreStreak = 0;
        oreVariety = 0;
        patternRepeats = 0;
    }
}
