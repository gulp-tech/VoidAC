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
 * Anti-ESP для Bedrock-клиентов: скрывает игроков, которые по серверным данным
 * не могут быть видны, но чей клиент их всё равно рисует (читы вида ESP/Wallhack
 * из паков Apollon/Horion/Toolbox).
 *
 * <p><b>Почему нельзя просто «скрывать всех и часто».</b> В ядре уже есть
 * {@code AntiEspManager} (плагин {@code PurpurBedrockAC}), который владеет
 * hide/show с интервалом 4 тика. Второй такой же цикл даёт конфликт владения:
 * один плагин прячет, другой показывает — мигание игроков и лишние пакеты.
 * Поэтому:</p>
 * <ul>
 *   <li>если Anti-ESP ядра активен, этот модуль <b>по умолчанию выключен</b>
 *       ({@code anti-esp.only-if-core-inactive: true});</li>
 *   <li>если ты сознательно ведёшь Anti-ESP здесь — выключи его в ядре
 *       ({@code /pac} или конфиг ядра), иначе два владельца видимости;</li>
 *   <li>сам модуль работает редко (по умолчанию раз в 10 тиков) и только по
 *       игрокам, у которых сменилось состояние — стабильные пары не трогаются,
 *       поэтому нет постоянного hide/show.</li>
 * </ul>
 *
 * <p><b>Что считается «не должен быть виден»</b> (проверки одна за другой,
 * каждая дёшева и не трогает чанки):</p>
 * <ol>
 *   <li>цель в другом мире, мертва, в другом измерении;</li>
 *   <li>цель в режиме наблюдателя — её не должно быть видно никому;</li>
 *   <li>цель невидима (эффект невидимости) и не подсвечена — на Bedrock
 *       невидимость рисуется иначе, и читы её игнорируют;</li>
 *   <li>цель за пределами серверной дистанции прорисовки;</li>
 *   <li>цель скрыта от viewer самим сервером/плагином (Vanish, привилегии) —
 *       такие пары мы <b>не трогаем вообще</b>, чтобы не «показать» ванишнутого.</li>
 * </ol>
 *
 * <p>Ложные срабатывания тут не наказывают игрока: это сокрытие, а не VL.
 * Но и «доказательством чита» оно не является — это профилактика. Информация о
 * устройстве для Anti-ESP не используется.</p>
 */
final class AntiEspManager {

    private record Pair(UUID viewer, UUID target) {
    }

    private static final class Visibility {
        boolean hidden;
    }

    private final JavaPlugin plugin;
    private final Logger log;

    private final Map<Pair, Visibility> states = new HashMap<>();
    private final Map<UUID, Long> joinGraceUntil = new HashMap<>();
    /** Кому запрещено прятать (право обхода) — проверяется на тике. */
    private final Set<UUID> bypassWarned = new HashSet<>();

    private BukkitTask task;
    private boolean coreConflictLogged;
    private boolean disabledByCore;

    private boolean enabled = true;
    private int intervalTicks = 10;
    private int joinGraceTicks = 60;
    private double minDistance = 2.0;
    private double maxDistance = 96.0;
    private boolean hideSpectators = true;
    private boolean hideInvisible = true;
    private boolean onlyIfCoreInactive = true;

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
        this.intervalTicks = Math.max(2, intervalTicks);
        this.joinGraceTicks = Math.max(0, joinGraceTicks);
        this.minDistance = Math.max(0.0, minDistance);
        this.maxDistance = Math.max(this.minDistance, maxDistance);
        this.hideSpectators = hideSpectators;
        this.hideInvisible = hideInvisible;
        this.onlyIfCoreInactive = onlyIfCoreInactive;
        if (wasRunning) {
            // Перезапуск с новыми числами; сначала честно вернуть всё, что скрыли.
            stop(true);
        }
        start();
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
            // У ядра это Class.forName-конфиг плагина, файл plugins/PurpurBedrockAC/config.yml.
            java.io.File file = new java.io.File(core.getDataFolder(), "config.yml");
            if (!file.isFile()) {
                return true; // плагин есть, конфиг ещё не создан — считаем, что владеет он
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

    /** Новый игрок: не трогаем пары в первые секунды, пока клиент грузит мир. */
    private long readyAt(UUID uuid) {
        return joinGraceUntil.getOrDefault(uuid, 0L);
    }

    void onJoin(Player player) {
        joinGraceUntil.put(player.getUniqueId(), System.currentTimeMillis() + joinGraceTicks * 50L);
        // Свежий серверный Player — старое состояние пар недействительно.
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
            // Показываем без проверки canSee: скрытие наше, поэтому canSee == false
            // именно из-за нас — этим условием игрок остался бы невидимым навсегда.
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
            // Без проверки canSee — см. комментарий в revealForTarget.
            if (viewer != null && target != null) {
                viewer.showEntity(plugin, target);
            }
            entry.getValue().hidden = false;
        }
    }

    /** Полный сброс (reload/выключение): вернуть всем всё, что мы скрыли. */
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
                //
                // Важно: canSee() == false и тогда, когда скрыли МЫ САМИ, поэтому
                // проверять надо вместе с нашим состоянием. Иначе мы бы
                // «забывали» собственное скрытие на следующем тике, теряли
                // владение парой и оставляли игрока невидимым навсегда.
                if (!state.hidden && !viewer.canSee(target)) {
                    continue; // скрывает кто-то другой — не вмешиваемся
                }
                if (now < readyAt(target.getUniqueId())) {
                    continue;
                }

                boolean shouldHide = shouldHide(viewer, target, world);
                if (shouldHide && !state.hidden) {
                    viewer.hideEntity(plugin, target);
                    state.hidden = true;
                } else if (!shouldHide && state.hidden) {
                    viewer.showEntity(plugin, target);
                    state.hidden = false;
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
     * Игрок, который сейчас работает с серверным GUI (сундук, магазин
     * DeluxeMenus, меню VoidShulker/кейсов), не должен внезапно исчезать из
     * мира: клиент может не перерисовать инвентарь, а в Bedrock это выглядит
     * как зависшее меню. Пока инвентарь открыт — не трогаем пару.
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
        if (hideSpectators && target.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
            return true;
        }
        if (hideInvisible && target.isInvisible() && !target.isGlowing()) {
            return true;
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
        return distance < minDistance && minDistance > 0.0;
    }
}
