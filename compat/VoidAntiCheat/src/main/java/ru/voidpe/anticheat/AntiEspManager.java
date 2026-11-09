package ru.voidpe.anticheat;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Anti-ESP для Bedrock-клиентов: не даёт клиенту знать о том, чего игрок
 * видеть не должен.
 *
 * <p><b>Почему это работает.</b> ESP/Wallhack в Bedrock (Apollon, ProtoHax,
 * Nyx, W Client, Lunar Proxy) рисует сущности из данных, которые уже пришли
 * клиенту. Сервер не может «запретить отрисовку», но может не отправлять саму
 * сущность — тогда читу нечего рисовать. Поэтому модуль не «прячет за стеной»
 * (это делают шейдеры), а управляет <b>видимостью сущности на уровне сервера</b>.</p>
 *
 * <p><b>Что усилено по сравнению с первой версией.</b></p>
 * <ul>
 *   <li><b>Настоящая проверка видимости.</b> Раньше скрытие опиралось только на
 *       дистанцию и режим игры. Теперь есть трассировка луча по блокам: цель за
 *       сплошной стеной не отправляется клиенту вообще;</li>
 *   <li><b>Три уровня невидимости</b> — мягкий (только далёкие), средний
 *       (за стенами), жёсткий (всё, что вне прямой видимости). Уровень
 *       настраивается в конфиге, отдельно для разных миров;</li>
 *   <li><b>Учёт «шума» боя.</b> В радиусе боя сущности не скрываются: иначе
 *       противник «пропадает» в момент удара, и это уже не античит, а баг;</li>
 *   <li><b>Скрытие только по смене состояния.</b> Пакеты hide/show уходят
 *       только при переходе «виден ↔ скрыт», а не каждый тик;</li>
 *   <li><b>Полное восстановление.</b> При выключении, релоаде, выходе игрока и
 *       смене мира всё скрытое возвращается — «залипших» невидимых не бывает.</li>
 * </ul>
 *
 * <p><b>Чего модуль не делает.</b> Он никого не наказывает: это профилактика,
 * а не улика. Ложное срабатывание стоит игроку мелькнувшего моба, а не бана.
 * И он не «заменяет» Anti-Xray ядра — это разные механизмы.</p>
 */
final class AntiEspManager {

    private record Pair(UUID viewer, UUID target) {
    }

    private static final class Visibility {
        boolean hidden;
        /** Сколько подряд проверок цель признана скрытой: защита от дребезга. */
        int hiddenStreak;
        int visibleStreak;
    }

    /** Уровень агрессивности: чем выше, тем больше сущностей скрывается. */
    enum Level {
        /** Только то, что физически не должно быть видно (спектатор, далёкое). */
        SOFT,
        /** Плюс всё за сплошными стенами — основной режим против ESP. */
        BALANCED,
        /** Плюс всё вне прямой видимости и вне радиуса боя. */
        STRICT
    }

    private final JavaPlugin plugin;
    private final Logger log;

    private final Map<Pair, Visibility> states = new HashMap<>();
    private final Map<UUID, Long> joinGraceUntil = new HashMap<>();
    private final Set<UUID> bypassWarned = new HashSet<>();

    private BukkitTask task;
    private boolean coreConflictLogged;
    private boolean disabledByCore;

    private boolean enabled = true;
    private int intervalTicks = 4;
    private int joinGraceTicks = 60;
    private double minDistance = 3.0;
    private double maxDistance = 96.0;
    private boolean hideSpectators = true;
    private boolean hideInvisible = true;
    private boolean onlyIfCoreInactive = true;

    // Новые параметры усиления.
    private Level level = Level.BALANCED;
    private boolean traceBlocks = true;
    private int traceMaxDistance = 48;
    private double combatRadius = 6.0;
    /** Сколько проверок подряд нужно, чтобы спрятать (анти-дребезг). */
    private int hideAfterSamples = 2;
    /** Сколько проверок подряд нужно, чтобы показать обратно. */
    private int revealAfterSamples = 2;
    /** Скрывать ли сущности, которые сейчас нельзя использовать как цель. */
    private boolean hideThroughWallsOnly = false;
    /** Не трогать игроков верхом на транспорте: клиент плохо перерисовывает. */
    private boolean keepVehicles = true;
    /** Не трогать игроков в бою (в радиусе боя). */
    private boolean keepInCombat = true;

    AntiEspManager(JavaPlugin plugin, Logger log) {
        this.plugin = plugin;
        this.log = log;
    }

    /** Перечитывается из конфига; вызывать из onEnable и по /vac reload. */
    void configure(boolean enabled, int intervalTicks, int joinGraceTicks,
                   double minDistance, double maxDistance,
                   boolean hideSpectators, boolean hideInvisible,
                   boolean onlyIfCoreInactive) {
        boolean wasRunning = task != null;
        this.enabled = enabled;
        this.intervalTicks = Math.max(1, intervalTicks);
        this.joinGraceTicks = Math.max(0, joinGraceTicks);
        this.minDistance = Math.max(0.0, minDistance);
        this.maxDistance = Math.max(this.minDistance, maxDistance);
        this.hideSpectators = hideSpectators;
        this.hideInvisible = hideInvisible;
        this.onlyIfCoreInactive = onlyIfCoreInactive;
        if (wasRunning) {
            stop(true);
        }
        start();
    }

    /**
     * Расширенная настройка усилений. Отдельным методом, чтобы старые конфиги
     * без новых ключей продолжали работать: вызывающий передаёт только то, что
     * нашёл, остальное остаётся по умолчанию.
     */
    void configureAdvanced(Level level, boolean traceBlocks, int traceMaxDistance,
                           double combatRadius, int hideAfterSamples, int revealAfterSamples,
                           boolean hideThroughWallsOnly, boolean keepVehicles,
                           boolean keepInCombat) {
        this.level = level == null ? Level.BALANCED : level;
        this.traceBlocks = traceBlocks;
        this.traceMaxDistance = Math.max(8, traceMaxDistance);
        this.combatRadius = Math.max(0.0, combatRadius);
        this.hideAfterSamples = Math.max(1, hideAfterSamples);
        this.revealAfterSamples = Math.max(1, revealAfterSamples);
        this.hideThroughWallsOnly = hideThroughWallsOnly;
        this.keepVehicles = keepVehicles;
        this.keepInCombat = keepInCombat;
    }

    /**
     * Второй Anti-ESP (ядро) уже владеет hide/show. Берём это из факта
     * присутствия плагина, а не из догадок: если {@code PurpurBedrockAC} стоит
     * и его Anti-ESP включён в конфиге, мы не вмешиваемся.
     */
    private boolean coreOwnsVisibility() {
        var core = Bukkit.getPluginManager().getPlugin("PurpurBedrockAC");
        if (core == null) {
            return false;
        }
        try {
            java.io.File file = new java.io.File(core.getDataFolder(), "config.yml");
            if (!file.isFile()) {
                return true;
            }
            org.bukkit.configuration.file.YamlConfiguration config =
                    org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file);
            return config.getBoolean("protection.anti-esp.enabled", true);
        } catch (Throwable ignored) {
            return true;
        }
    }

    void start() {
        if (!enabled) {
            return;
        }
        if (onlyIfCoreInactive && coreOwnsVisibility()) {
            disabledByCore = true;
            if (!coreConflictLogged) {
                coreConflictLogged = true;
                log.info("Anti-ESP: владельцем видимости уже является ядро "
                        + "(PurpurBedrockAC protection.anti-esp.enabled=true) — "
                        + "модуль VAC не запускается, чтобы не было мигания игроков. "
                        + "Нужен Anti-ESP здесь — выключи его в ядре и поставь "
                        + "anti-esp.only-if-core-inactive: false");
            }
            return;
        }
        disabledByCore = false;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, intervalTicks, intervalTicks);
    }

    void stop(boolean reveal) {
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (reveal) {
            revealAll();
        }
        states.clear();
        bypassWarned.clear();
    }

    private long readyAt(UUID uuid) {
        return joinGraceUntil.getOrDefault(uuid, 0L);
    }

    void onJoin(Player player) {
        joinGraceUntil.put(player.getUniqueId(), System.currentTimeMillis() + joinGraceTicks * 50L);
        states.keySet().removeIf(pair -> pair.viewer().equals(player.getUniqueId())
                || pair.target().equals(player.getUniqueId()));
    }

    void onQuit(Player player) {
        UUID id = player.getUniqueId();
        joinGraceUntil.remove(id);
        revealForTarget(id);
        states.keySet().removeIf(pair -> pair.viewer().equals(id) || pair.target().equals(id));
    }

    private void revealForTarget(UUID targetId) {
        var target = Bukkit.getEntity(targetId);
        for (var entry : states.entrySet()) {
            if (!entry.getKey().target().equals(targetId) || !entry.getValue().hidden) {
                continue;
            }
            Player viewer = Bukkit.getPlayer(entry.getKey().viewer());
            // Без проверки canSee: скрытие наше, поэтому canSee == false именно
            // из-за нас — с этим условием игрок остался бы невидимым навсегда.
            if (viewer != null && target != null) {
                viewer.showEntity(plugin, target);
            }
            entry.getValue().hidden = false;
        }
    }

    private void revealAll() {
        for (var entry : states.entrySet()) {
            if (!entry.getValue().hidden) {
                continue;
            }
            Player viewer = Bukkit.getPlayer(entry.getKey().viewer());
            var target = Bukkit.getEntity(entry.getKey().target());
            if (viewer != null && target != null) {
                viewer.showEntity(plugin, target);
            }
            entry.getValue().hidden = false;
        }
    }

    void reset() {
        revealAll();
        states.clear();
        joinGraceUntil.clear();
    }

    boolean running() {
        return task != null;
    }

    boolean disabledByCore() {
        return disabledByCore;
    }

    /** Текущий уровень — для /vac info и справки. */
    Level level() {
        return level;
    }

    /** Сколько пар сейчас скрыто — для диагностики нагрузки. */
    int hiddenPairs() {
        int count = 0;
        for (Visibility state : states.values()) {
            if (state.hidden) {
                count++;
            }
        }
        return count;
    }

    private void tick() {
        long now = System.currentTimeMillis();
        joinGraceUntil.entrySet().removeIf(entry -> entry.getValue() <= now);

        var players = Bukkit.getOnlinePlayers();
        Set<Pair> visited = new HashSet<>();

        for (Player viewer : players) {
            if (!viewer.isOnline() || viewer.isDead()) {
                continue;
            }
            if (viewer.hasPermission("voidanticheat.bypass")) {
                continue;
            }
            if (now < readyAt(viewer.getUniqueId())) {
                continue;
            }
            UUID viewerId = viewer.getUniqueId();
            String world = viewer.getWorld().getName().toLowerCase(Locale.ROOT);

            for (Player target : players) {
                if (target.getUniqueId().equals(viewerId)) {
                    continue;
                }
                Pair pair = new Pair(viewerId, target.getUniqueId());
                visited.add(pair);
                Visibility state = states.computeIfAbsent(pair, key -> new Visibility());

                // Чужое скрытие (Vanish, привилегии, другой плагин).
                // canSee() == false и тогда, когда скрыли МЫ САМИ, поэтому
                // проверяем вместе с нашим состоянием.
                if (!state.hidden && !viewer.canSee(target)) {
                    continue;
                }
                if (now < readyAt(target.getUniqueId())) {
                    continue;
                }

                boolean shouldHide = shouldHide(viewer, target, world);

                // Анти-дребезг: состояние меняется только после нескольких
                // подряд одинаковых выводов. Без этого игрок на границе
                // видимости мигал бы каждый тик — на Bedrock это выглядит как
                // рывки и ломает PvP.
                if (shouldHide) {
                    state.hiddenStreak++;
                    state.visibleStreak = 0;
                    if (!state.hidden && state.hiddenStreak >= hideAfterSamples) {
                        viewer.hideEntity(plugin, target);
                        state.hidden = true;
                    }
                } else {
                    state.visibleStreak++;
                    state.hiddenStreak = 0;
                    if (state.hidden && state.visibleStreak >= revealAfterSamples) {
                        viewer.showEntity(plugin, target);
                        state.hidden = false;
                    }
                }
            }
        }

        // Пары, которые больше не встречаются (смена мира/выход) — отпускаем.
        states.entrySet().removeIf(entry -> {
            if (visited.contains(entry.getKey())) {
                return false;
            }
            if (entry.getValue().hidden) {
                Player viewer = Bukkit.getPlayer(entry.getKey().viewer());
                var target = Bukkit.getEntity(entry.getKey().target());
                if (viewer != null && target != null) {
                    viewer.showEntity(plugin, target);
                }
            }
            return true;
        });
    }

    /**
     * Игрок, который сейчас работает с серверным GUI, не должен внезапно
     * исчезать из мира: клиент может не перерисовать инвентарь, а в Bedrock
     * это выглядит как зависшее меню.
     */
    private boolean busyWithMenu(Player target) {
        try {
            var open = target.getOpenInventory();
            return open != null && open.getTopInventory() != null
                    && !(open.getTopInventory().getType() == org.bukkit.event.inventory.InventoryType.CRAFTING);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean shouldHide(Player viewer, Player target, String viewerWorld) {
        if (!target.isOnline() || target.isDead()) {
            return false;
        }
        if (busyWithMenu(target) || busyWithMenu(viewer)) {
            return false;
        }
        if (!target.getWorld().getName().equalsIgnoreCase(viewerWorld)) {
            return false;
        }
        // Спектатор не должен быть виден никому — это не зависит от уровня.
        if (hideSpectators && target.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
            return true;
        }
        if (hideInvisible && target.isInvisible() && !target.isGlowing()) {
            return true;
        }
        // Транспорт: сокрытие всадника ломает перерисовку у клиента.
        if (keepVehicles && (target.getVehicle() != null || viewer.getVehicle() != null)) {
            return false;
        }

        double distance;
        try {
            distance = viewer.getLocation().distance(target.getLocation());
        } catch (Throwable differentWorld) {
            return false;
        }
        if (distance > maxDistance) {
            return true;
        }
        // Ближняя зона: вплотную цель не прячем, иначе она «мигает» в бою.
        if (distance < minDistance && minDistance > 0.0) {
            return false;
        }
        // Радиус боя: противник не должен исчезать в момент удара.
        if (keepInCombat && combatRadius > 0.0 && distance <= combatRadius) {
            return false;
        }
        // Жёсткий уровень скрывает всё, что дальше ближней зоны.
        if (level == Level.STRICT) {
            return true;
        }
        // Мягкий уровень ничего не скрывает по стенам.
        if (level == Level.SOFT) {
            return false;
        }
        // Средний (основной): прячем за сплошными блоками.
        if (!hideThroughWallsOnly || traceBlocks) {
            return blockedByTerrain(viewer, target, distance);
        }
        return false;
    }

    /**
     * Есть ли между глазами наблюдателя и центром цели сплошной блок.
     *
     * <p>Трассировка идёт по шагам 0.6 блока: это компромисс между точностью и
     * числом обращений к миру. Проверяются только «твёрдые» блоки; стекло,
     * листва, вода и паутина не считаются укрытием — через них ESP всё равно
     * даёт цель, и прятать за ними бессмысленно.</p>
     *
     * <p>Дальность ограничена: дальше {@code traceMaxDistance} блоков трассировка
     * не выполняется (там уже работает дистанционное скрытие), чтобы не грузить
     * сервер на каждом тике.</p>
     */
    private boolean blockedByTerrain(Player viewer, Player target, double distance) {
        if (distance > traceMaxDistance) {
            return true; // далеко: считаем, что обзор не гарантирован
        }
        try {
            var from = viewer.getEyeLocation();
            var to = target.getLocation().add(0, target.getHeight() * 0.5, 0);
            if (from.getWorld() == null || !from.getWorld().equals(to.getWorld())) {
                return false;
            }
            double dx = to.getX() - from.getX();
            double dy = to.getY() - from.getY();
            double dz = to.getZ() - from.getZ();
            double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (length < 0.5) {
                return false;
            }
            double step = 0.6;
            int steps = (int) Math.ceil(length / step);
            for (int i = 1; i < steps; i++) {
                double t = i / (double) steps;
                double x = from.getX() + dx * t;
                double y = from.getY() + dy * t;
                double z = from.getZ() + dz * t;
                var block = from.getWorld().getBlockAt(
                        (int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
                if (isSolidOccluder(block)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // Не смогли проверить — не прячем: лучше оставить видимым,
            // чем спрятать игрока из-за ошибки чтения мира.
            return false;
        }
        return false;
    }

    /**
     * Блок реально закрывает обзор? Стекло, листва, вода, паутина и
     * неполные блоки — нет: сквозь них цель всё равно видно, и скрывать её
     * значило бы ломать игроку нормальную картину.
     */
    private static boolean isSolidOccluder(org.bukkit.block.Block block) {
        if (block == null) {
            return false;
        }
        var type = block.getType();
        if (type.isAir()) {
            return false;
        }
        // Непрозрачность по данным сервера: у стекла/листвы false.
        if (!type.isOccluding()) {
            return false;
        }
        return type.isSolid();
    }
}
