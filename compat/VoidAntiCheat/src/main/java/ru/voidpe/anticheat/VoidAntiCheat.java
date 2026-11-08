package ru.voidpe.anticheat;

import com.destroystokyo.paper.event.player.PlayerElytraBoostEvent;
import io.papermc.paper.event.player.PlayerArmSwingEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerRiptideEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * VoidAntiCheat — античит VoidPE для Bedrock-игроков (Geyser/Floodgate).
 *
 * <p>Философия (по мотивам Grim, Boar, Flint и BedrockGuard):</p>
 *
 * <ul>
 *   <li><b>Улики, а не мгновенные штрафы.</b> Каждая проверка копит вес в
 *       буфере; VL растёт только когда буфер переполнен. Повторы внутри
 *       sample-gap весят вчетверо меньше, поэтому лаг-спайк с пачкой пакетов
 *       не превращается в пять улик.</li>
 *   <li><b>Три независимых слоя.</b> Детект (VL) → коррекция (cancel/setback)
 *       → наказание. Наказание требует, чтобы VL держался выше порога
 *       несколько секунд, шло несколько разных проверок, пинг был разумным,
 *       а TPS — нормальным. По умолчанию автобана и кика нет вообще.</li>
 *   <li><b>Компенсация лагов.</b> Пинг Bedrock через Geyser часто нулевой,
 *       поэтому джиттер считается по интервалам move-пакетов; на шумном
 *       канале улика весит меньше, а пороги растут.</li>
 *   <li><b>Исключения вместо порогов.</b> Вода, паутина, лёд, слизень, мёд,
 *       сено, порошковый снег, лестницы, строительные леса, транспорт,
 *       элитра, Levitation, Slow Falling, Riptide, поршни, взрывы, недавний
 *       урон и опора под ногами отключают соответствующие проверки. Именно
 *       это даёт ноль ложных срабатываний на честной Bedrock-физике.</li>
 *   <li><b>Предсказание вертикали.</b> Gravity/drag-модель с допуском:
 *       наказываем только за набор высоты &gt; 3 блоков в воздухе и за
 *       зависание &gt; 2 секунд без опоры.</li>
 *   <li><b>Reach с перемоткой.</b> Хранимая история хитбокса цели: удар
 *       проверяется по минимальной дистанции в окне пинга, а допуск растёт
 *       от скорости цели и джиттера.</li>
 * </ul>
 *
 * <p>Оптимизация: на PlayerMoveEvent O(1) примитивов и десяток чтений блоков
 * с кэшем на тик; история позиций не хранится; тяжёлый I/O — в одном фоновом
 * потоке.</p>
 */
public class VoidAntiCheat extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int VL_SUSTAIN_SETBACK = 0;
    private static final int VL_SUSTAIN_CANCEL = 1;
    private static final int VL_SUSTAIN_PUNISH = 2;

    private final Map<UUID, PlayerData> players = new HashMap<>();
    private final Map<CheckId, CheckSettings> settings = new EnumMap<>(CheckId.class);
    private final Set<UUID> alertsOff = new HashSet<>();

    private FloodgateHook floodgate;
    private BedrockCoreBridge coreBridge;
    private AntiEspManager antiEsp;
    private RegionHook regions;
    private BukkitTask secondTask;
    private ExecutorService ioExecutor;
    private HttpClient httpClient;
    private LagCompensation.Limits lagLimits = LagCompensation.Limits.defaults();
    /** null — ещё не проверяли; см. fastAttackServer(). */
    private Boolean fastAttackCache;
    /**
     * Кэш {@code bedrockCombatEnabled} ПО МИРАМ: флаг лежит в
     * {@code PurpurWorldConfig}, то есть у каждого мира свой. Один общий
     * {@code Boolean} вернул бы значение первого встреченного мира для всех.
     */
    private final Map<String, Boolean> coreCombatByWorld = new HashMap<>();

    private Attribute movementSpeedAttribute;
    private Attribute interactionRangeAttribute;
    private PotionEffectType slowFallingEffect;
    private PotionEffectType jumpBoostEffect;
    private PotionEffectType levitationEffect;
    private PotionEffectType dolphinsGraceEffect;
    private Enchantment soulSpeedEnchant;
    private Enchantment depthStriderEnchant;
    private Enchantment frostWalkerEnchant;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadCheckSettings();
        loadLagLimits();
        // Реестры — единственное место, зависящее от версии API. Если в новой
        // версии сервера что-то переименовали, античит должен работать дальше
        // с дефолтами, а не выключаться целиком (так было с Registry в 2.0.0:
        // IncompatibleClassChangeError убивал плагин при включении).
        try {
            resolveRegistryEntries();
        } catch (Throwable failure) {
            movementSpeedAttribute = null;
            interactionRangeAttribute = null;
            slowFallingEffect = null;
            jumpBoostEffect = null;
            levitationEffect = null;
            dolphinsGraceEffect = null;
            soulSpeedEnchant = null;
            depthStriderEnchant = null;
            frostWalkerEnchant = null;
            getLogger().warning("Не удалось прочитать реестры API (" + failure.getClass().getSimpleName()
                    + ": " + failure.getMessage() + ") — проверки работают на консервативных значениях по умолчанию");
        }
        floodgate = new FloodgateHook(getLogger());
        coreBridge = new BedrockCoreBridge(getLogger());
        antiEsp = new AntiEspManager(this, getLogger());
        antiEsp.configure(
                confBool("anti-esp.enabled", false),
                confInt("anti-esp.interval-ticks", 10),
                confInt("anti-esp.join-grace-ticks", 60),
                confDouble("anti-esp.min-distance", 2.0),
                confDouble("anti-esp.max-distance", 96.0),
                confBool("anti-esp.hide-spectators", true),
                confBool("anti-esp.hide-invisible", true),
                confBool("anti-esp.only-if-core-inactive", true));
        regions = new RegionHook(getLogger());
        ioExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "VoidAntiCheat-IO");
            thread.setDaemon(true);
            return thread;
        });
        httpClient = HttpClient.newHttpClient();
        getServer().getPluginManager().registerEvents(this, this);
        if (getCommand("vac") != null) {
            getCommand("vac").setExecutor(this);
            getCommand("vac").setTabCompleter(this);
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            createData(player, false);
        }
        secondTask = Bukkit.getScheduler().runTaskTimer(this, this::secondTick, 20L, 20L);
        if (!floodgate.available() && confBool("settings.bedrock-only", true)
                && !confBool("settings.check-all-if-floodgate-missing", true)) {
            getLogger().warning("Floodgate недоступна, а fallback выключен: проверки Bedrock сейчас не работают");
        }
        getLogger().info("VoidAntiCheat включён: Bedrock-only=" + confBool("settings.bedrock-only", true)
                + ", Floodgate=" + floodgate.available()
                + ", проверок=" + settings.size()
                + ", коррекция=" + confBool("settings.enforcement", true)
                + ", автонаказания=" + confBool("settings.auto-punish", false));
    }

    @Override
    public void onDisable() {
        if (secondTask != null) {
            secondTask.cancel();
        }
        if (ioExecutor != null) {
            ioExecutor.shutdownNow();
        }
        if (antiEsp != null) {
            antiEsp.reset();
            antiEsp.stop(false);
        }
        players.clear();
        alertsOff.clear();
    }

    /**
     * Поиск в реестре без привязки к сигнатуре {@code Registry#get}.
     * На 1.21.x это был {@code get(NamespacedKey)}, на новых сборках (Paper 26.x)
     * метод принимает adventure {@code Key} — прямой вызов давал NoSuchMethodError
     * и все проверки оставались на консервативных значениях по умолчанию.
     */
    private static <T> T registryLookup(org.bukkit.Registry<T> registry, String... keys) {
        if (registry == null) {
            return null;
        }
        for (String raw : keys) {
            String full = raw.indexOf(':') >= 0 ? raw : "minecraft:" + raw;
            Object value = registryValue(registry, full);
            if (value != null) {
                @SuppressWarnings("unchecked")
                T typed = (T) value;
                return typed;
            }
        }
        return null;
    }

    private static Object registryValue(Object registry, String fullKey) {
        try {
            java.lang.reflect.Method get = registry.getClass().getMethod("get", NamespacedKey.class);
            Object value = get.invoke(registry, NamespacedKey.fromString(fullKey));
            if (value != null) {
                return value;
            }
        } catch (Throwable ignored) {
            // на этой версии сервера такого метода нет — пробуем следующий вариант
        }
        try {
            Class<?> keyClass = Class.forName("net.kyori.adventure.key.Key");
            Object key = keyClass.getMethod("key", String.class).invoke(null, fullKey);
            java.lang.reflect.Method get = registry.getClass().getMethod("get", keyClass);
            return get.invoke(registry, key);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void resolveRegistryEntries() {
        movementSpeedAttribute = registryLookup(Registry.ATTRIBUTE,
                "movement_speed", "generic.movement_speed");
        interactionRangeAttribute = registryLookup(Registry.ATTRIBUTE, "entity_interaction_range");
        slowFallingEffect = registryLookup(Registry.EFFECT, "slow_falling");
        jumpBoostEffect = registryLookup(Registry.EFFECT, "jump_boost");
        levitationEffect = registryLookup(Registry.EFFECT, "levitation");
        dolphinsGraceEffect = registryLookup(Registry.EFFECT, "dolphins_grace");
        soulSpeedEnchant = registryLookup(Registry.ENCHANTMENT, "soul_speed");
        depthStriderEnchant = registryLookup(Registry.ENCHANTMENT, "depth_strider");
        frostWalkerEnchant = registryLookup(Registry.ENCHANTMENT, "frost_walker");
    }

    private void loadCheckSettings() {
        settings.clear();
        for (CheckId check : CheckId.values()) {
            settings.put(check, CheckSettings.load(getConfig(), check));
        }
    }

    private void loadLagLimits() {
        lagLimits = new LagCompensation.Limits(
                confDouble("compensation.ping-factor-per-100ms", 0.020),
                confDouble("compensation.max-ping-factor", 0.12),
                confDouble("compensation.jitter-factor-per-ms", 0.0012),
                confDouble("compensation.max-jitter-factor", 0.30),
                confDouble("compensation.tps-factor-per-point", 0.05),
                confDouble("compensation.max-tps-factor", 0.45),
                confDouble("compensation.spike-interval-ms", 900.0),
                confDouble("compensation.unstable-hold-ms", 2500.0)).sanitized();
    }

    private CheckSettings setting(CheckId check) {
        return settings.get(check);
    }

    // ------------------------------------------------------------ конфиг-хелперы

    private boolean confBool(String path, boolean fallback) {
        return getConfig().getBoolean(path, fallback);
    }

    private int confInt(String path, int fallback) {
        return getConfig().getInt(path, fallback);
    }

    private long confLong(String path, long fallback) {
        return getConfig().getLong(path, fallback);
    }

    private double confDouble(String path, double fallback) {
        return getConfig().getDouble(path, fallback);
    }

    private Component comp(String legacy) {
        return LegacyComponentSerializer.legacySection().deserialize(TextUtil.color(legacy))
                .decoration(TextDecoration.ITALIC, false);
    }

    private String plain(String legacy) {
        return PlainTextComponentSerializer.plainText().serialize(comp(legacy));
    }

    // ------------------------------------------------------------ данные игрока

    private PlayerData createData(Player player, boolean joinGrace) {
        PlayerData data = new PlayerData(player.getUniqueId());
        boolean bedrockOnly = confBool("settings.bedrock-only", true);
        if (!bedrockOnly) {
            data.bedrock = true;
        } else if (floodgate.available()) {
            data.bedrock = floodgate.isBedrock(player.getUniqueId());
        } else {
            data.bedrock = confBool("settings.check-all-if-floodgate-missing", true);
        }
        // Устройство: приоритет у ядра (живая Geyser-сессия), затем Floodgate.
        // Ядро отдаёт правдоподобные значения даже без сессии (device_id=android,
        // device=Android, is_bedrock=true при only-allow-bedrock), поэтому берём
        // deviceId только когда сессия реально есть. Иначе — unknown, и проверки
        // работают на мягких лимитах, а не наказывают за отсутствие данных.
        if (data.bedrock && coreBridge != null) {
            data.deviceId = coreBridge.deviceId(player);
            data.sessionPing = coreBridge.ping(player);
        }
        boolean haveCoreDevice = !BedrockCoreBridge.DEVICE_UNKNOWN.equals(data.deviceId);
        if (data.bedrock && floodgate.available()) {
            data.inputMode = floodgate.inputMode(player.getUniqueId());
            data.deviceOs = floodgate.deviceOs(player.getUniqueId());
        }
        if (!haveCoreDevice && BedrockCoreBridge.isTouchFamily(deviceIdFromFloodgate(data.deviceOs, data.inputMode))) {
            // Floodgate знает платформу, но не модель: мобильная ОС = сенсорный класс.
            data.deviceId = deviceIdFromFloodgate(data.deviceOs, data.inputMode);
        }
        data.profile = data.bedrock ? "bedrock" : "java";
        data.bypassAll = player.hasPermission("voidanticheat.bypass");
        data.excludedHere = isExcluded(player.getLocation());
        long now = System.currentTimeMillis();
        if (joinGrace) {
            data.graceUntilMillis = now + confLong("settings.join-grace-ms", 3500L);
        }
        data.resetMovement(player.getLocation(), Bukkit.getCurrentTick(), System.nanoTime(), player.isOnGround());
        updateSafeLocation(player, player.getLocation(), data);
        players.put(player.getUniqueId(), data);
        return data;
    }

    /**
     * Категория устройства по данным Floodgate, когда у ядра живой сессии нет.
     * Это запасной путь: модель устройства отсюда не получить, поэтому
     * телефон с клавиатурой неотличим от телефона — и это правильно, потому что
     * оба случая должны получать сенсорные (мягкие) лимиты.
     * {@code UNKNOWN} остаётся {@code unknown} — проверки обязаны это учитывать.
     */
    private String deviceIdFromFloodgate(String deviceOs, String inputMode) {
        String os = deviceOs == null ? "UNKNOWN" : deviceOs.toUpperCase(Locale.ROOT);
        return switch (os) {
            case "GOOGLE", "ANDROID", "AMAZON", "FIREOS", "WINDOWS_PHONE" -> "android";
            case "IOS", "TVOS", "APPLE_TV" -> "ios";
            case "XBOX", "XBOX_ONE", "XBOX_SERIES" -> "xbox";
            case "PS4", "PS5", "PLAYSTATION", "ORBIS" -> "ps4_5";
            case "NX", "SWITCH", "NINTENDO", "NINTENDO_SWITCH" -> "switch";
            case "UWP", "WIN32", "WINDOWS", "WINDOWS_X86", "OSX", "MACOS", "LINUX", "DEDICATED" -> "windows";
            default -> "unknown";
        };
    }

    /** Только для тестов/диагностики: состояние без создания. */
    PlayerData peek(Player player) {
        return player == null ? null : players.get(player.getUniqueId());
    }

    private PlayerData data(Player player) {
        PlayerData data = players.get(player.getUniqueId());
        return data == null ? createData(player, false) : data;
    }

    private boolean isBedrockOnlyMode() {
        return confBool("settings.bedrock-only", true);
    }

    private boolean shouldCheck(Player player, PlayerData data) {
        if (!confBool("settings.enabled", true) || player == null || data == null) {
            return false;
        }
        if (isBedrockOnlyMode() && !data.bedrock) {
            return false;
        }
        if (data.bypassAll || data.excludedHere || !player.isOnline() || player.isDead()) {
            return false;
        }
        GameMode mode = player.getGameMode();
        return mode == GameMode.SURVIVAL || mode == GameMode.ADVENTURE;
    }

    /**
     * Миры и регионы, где античит молчит.
     *
     * <p>Имена миров поддерживают маски, чтобы не перечислять каждый мир
     * вручную и не пересобирать jar:</p>
     * <ul>
     *   <li>{@code world} — точное имя;</li>
     *   <li>{@code arena-*} — всё, что начинается с {@code arena-};</li>
     *   <li>{@code *_test} — всё, что заканчивается на {@code _test};</li>
     *   <li>{@code lobby?} — {@code ?} заменяет ровно один символ;</li>
     *   <li>{@code world_nether} в списке — регистр не важен.</li>
     * </ul>
     * <p>Проверка идёт по имени мира, поэтому не зависит от смены мира,
     * телепорта и загрузки чанков.</p>
     */
    private boolean isExcluded(Location location) {
        if (location == null || location.getWorld() == null) {
            return false;
        }
        String world = location.getWorld().getName().toLowerCase(Locale.ROOT);
        if (worldMatchesAny(world, getConfig().getStringList("settings.excluded-worlds"))) {
            return true;
        }
        // Обратный вариант: whitelist миров. Пусто = проверять везде.
        List<String> only = getConfig().getStringList("settings.only-worlds");
        if (!only.isEmpty() && !worldMatchesAny(world, only)) {
            return true;
        }
        return regions != null && regions.inRegion(location,
                getConfig().getStringList("settings.excluded-worldguard-regions"));
    }

    /**
     * Совпадение имени мира с маской. Реализация намеренно без регулярных
     * выражений: конфиг читают люди, и случайная {@code *} не должна
     * превращаться в дорогой regex на каждом move-событии.
     */
    static boolean worldMatches(String pattern, String worldName) {
        if (pattern == null || worldName == null) {
            return false;
        }
        String p = pattern.trim().toLowerCase(Locale.ROOT);
        if (p.isEmpty()) {
            return false;
        }
        if (p.indexOf('*') < 0 && p.indexOf('?') < 0) {
            return p.equals(worldName);
        }
        return wildcardMatch(p, worldName, 0, 0);
    }

    private static boolean wildcardMatch(String pattern, String text, int pi, int ti) {
        while (pi < pattern.length()) {
            char pc = pattern.charAt(pi);
            if (pc == '*') {
                // Схлопываем подряд идущие звёздочки.
                while (pi + 1 < pattern.length() && pattern.charAt(pi + 1) == '*') {
                    pi++;
                }
                if (pi + 1 == pattern.length()) {
                    return true;
                }
                for (int i = ti; i <= text.length(); i++) {
                    if (wildcardMatch(pattern, text, pi + 1, i)) {
                        return true;
                    }
                }
                return false;
            }
            if (ti >= text.length()) {
                return false;
            }
            if (pc != '?' && pc != text.charAt(ti)) {
                return false;
            }
            pi++;
            ti++;
        }
        return ti == text.length();
    }

    private static boolean worldMatchesAny(String worldName, List<String> patterns) {
        for (String pattern : patterns) {
            if (worldMatches(pattern, worldName)) {
                return true;
            }
        }
        return false;
    }

    private boolean inGrace(PlayerData data) {
        return System.currentTimeMillis() < data.graceUntilMillis;
    }

    private void grantGrace(PlayerData data, long millis) {
        data.graceUntilMillis = Math.max(data.graceUntilMillis, System.currentTimeMillis() + Math.max(0L, millis));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        createData(event.getPlayer(), true);
        if (antiEsp != null) {
            antiEsp.onJoin(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        if (antiEsp != null) {
            antiEsp.onQuit(event.getPlayer());
        }
        players.remove(event.getPlayer().getUniqueId());
        alertsOff.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        PlayerData data = data(event.getPlayer());
        if (event.getTo() != null) {
            grantGrace(data, confLong("settings.teleport-grace-ms", 1750L));
            data.resetMovement(event.getTo(), Bukkit.getCurrentTick(), System.nanoTime(), event.getPlayer().isOnGround());
            // Клиент пришлёт свою позицию позже, и она может не совпасть с точкой
            // телепорта (RTP, дом клана, вход в данж, AuthSpawn). Первое движение
            // после телепорта — это согласование, а не движение игрока.
            data.unsyncedPosition = true;
            data.clearSustainAll();
            updateSafeLocation(event.getPlayer(), event.getTo(), data);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        PlayerData data = data(event.getPlayer());
        grantGrace(data, confLong("settings.respawn-grace-ms", 3500L));
        data.resetMovement(event.getRespawnLocation(), Bukkit.getCurrentTick(), System.nanoTime(), true);
        data.unsyncedPosition = true;
        data.safeLocation = event.getRespawnLocation().clone();
        data.clearSustainAll();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        PlayerData data = data(event.getPlayer());
        grantGrace(data, confLong("settings.world-change-grace-ms", 2500L));
        data.resetMovement(event.getPlayer().getLocation(), Bukkit.getCurrentTick(), System.nanoTime(),
                event.getPlayer().isOnGround());
        data.unsyncedPosition = true;
        data.clearSustainAll();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        PlayerData data = data(event.getPlayer());
        grantGrace(data, 1000L);
        data.resetMovement(event.getPlayer().getLocation(), Bukkit.getCurrentTick(), System.nanoTime(),
                event.getPlayer().isOnGround());
        data.clearSustainAll();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        PlayerData data = players.get(event.getEntity().getUniqueId());
        if (data != null) {
            data.clearViolations();
            grantGrace(data, confLong("settings.respawn-grace-ms", 3500L));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        PlayerData data = players.get(player.getUniqueId());
        if (data == null) {
            return;
        }
        data.lastDamageMillis = System.currentTimeMillis();
        grantGrace(data, confLong("settings.damage-grace-ms", 500L));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        gracePiston(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        gracePiston(event.getBlock());
    }

    /** Поршень двигает игрока без PlayerVelocityEvent — без грейса это чистый ложный Fly/Speed. */
    private void gracePiston(Block piston) {
        World world = piston.getWorld();
        if (world == null) {
            return;
        }
        long until = System.currentTimeMillis() + confLong("settings.piston-grace-ms", 1200L);
        Location base = piston.getLocation();
        double radiusSq = Math.pow(confDouble("settings.piston-grace-radius", 4.0), 2.0);
        for (Player player : world.getPlayers()) {
            if (player.getLocation().distanceSquared(base) > radiusSq) {
                continue;
            }
            PlayerData data = players.get(player.getUniqueId());
            if (data != null) {
                data.pistonUntilMillis = Math.max(data.pistonUntilMillis, until);
                grantGrace(data, 600L);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onExplosion(EntityExplodeEvent event) {
        Location center = event.getLocation();
        World world = center.getWorld();
        if (world == null) {
            return;
        }
        long until = System.currentTimeMillis() + confLong("settings.explosion-grace-ms", 1200L);
        double radiusSq = Math.pow(confDouble("settings.explosion-grace-radius", 10.0), 2.0);
        for (Player player : world.getPlayers()) {
            if (player.getLocation().distanceSquared(center) > radiusSq) {
                continue;
            }
            PlayerData data = players.get(player.getUniqueId());
            if (data != null) {
                data.explosionUntilMillis = Math.max(data.explosionUntilMillis, until);
                grantGrace(data, 700L);
            }
        }
    }

    // ------------------------------------------------------------ нарушения

    /**
     * Записывает улику. Возвращает true, если действие по этой проверке
     * сейчас надо отменить (cancel-vl + устойчивость + enforcement).
     *
     * <p>Setback и наказание применяются здесь же, но с собственными
     * требованиями устойчивости и кулдаунами.</p>
     */
    private boolean flag(Player player, PlayerData data, CheckId check, double points, String details) {
        if (points <= 0 || !shouldCheck(player, data)) {
            return false;
        }
        CheckSettings cfg = setting(check);
        if (cfg == null || !cfg.enabled() || player.hasPermission(check.bypassPermission())) {
            return false;
        }
        long now = System.currentTimeMillis();
        ViolationState state = data.state(check);
        double tps = currentTps();
        int ping = player.getPing();
        double confidence = data.lag.confidence(ping, tps);
        if (data.lag.unstable(now)) {
            confidence *= 0.55;
        }
        double weighted = points * confidence;
        double limit = Math.max(0.05, cfg.bufferLimit());
        if (!state.record(weighted, now, sampleGap(check), limit)) {
            if (confBool("settings.debug", false)) {
                getLogger().info("[debug] " + player.getName() + " " + check.key()
                        + " улика " + formatVl(weighted) + "/" + formatVl(limit)
                        + " (буфер " + formatVl(state.buffer()) + ") — " + details);
            }
            return false;
        }

        if (state.vl() > cfg.maxVl()) {
            // VL ограничен: длинная серия нарушений не должна уходить в тысячи
            // и потом часами не спадать после исправления игрока.
            state.setVl(cfg.maxVl());
        }
        double vl = state.vl();
        data.lastCheck = check;
        data.lastDetails = details;
        data.lastViolationMillis = now;

        maybeAlert(player, data, check, cfg, state, details, now);

        boolean enforcement = confBool("settings.enforcement", true);
        long aboveSetback = cfg.setbackVl() > 0
                ? state.aboveForMillis(VL_SUSTAIN_SETBACK, cfg.setbackVl(), now) : 0L;
        long aboveCancel = cfg.cancelVl() > 0
                ? state.aboveForMillis(VL_SUSTAIN_CANCEL, cfg.cancelVl(), now) : 0L;
        long abovePunish = cfg.punishVl() > 0
                ? state.aboveForMillis(VL_SUSTAIN_PUNISH, cfg.punishVl(), now) : 0L;

        if (!enforcement) {
            return false;
        }

        if (cfg.shouldSetback(vl) && isMovementCheck(check) && !data.lag.unstable(now)
                && aboveSetback >= confLong("settings.setback-sustain-ms", 700L)
                && cooldownOk(state, now, confLong("settings.setback-cooldown-ms", 900L))) {
            data.setbackRequested = true;
            state.markAction(now);
        }

        // Проверки «физически невозможно» (buffer = 0, например BadPackets)
        // корректируются сразу: устойчивость нужна только эвристикам.
        long cancelSustain = cfg.bufferLimit() <= 0.05
                ? 0L : confLong("settings.cancel-sustain-ms", 250L);
        boolean cancel = cfg.shouldCancel(vl)
                && aboveCancel >= cancelSustain
                && cooldownOk(state, now, confLong("settings.cancel-cooldown-ms", 400L));
        if (cancel) {
            state.markAction(now);
        }

        if (cfg.shouldPunish(vl) && confBool("settings.auto-punish", false)
                && abovePunish >= confLong("settings.punish-min-above-seconds", 20L) * 1000L
                && ping <= confInt("settings.punish-max-ping", 400)
                && tps >= confDouble("settings.punish-min-tps", 17.0)
                && distinctActive(data) >= confInt("settings.punish-min-distinct-checks", 2)
                && cooldownOk(state, now, confLong("settings.punish-cooldown-seconds", 60L) * 1000L)) {
            state.markAction(now);
            runPunishments(player, check, cfg, vl, details);
            data.setVl(check, cfg.maxVl() * 0.45);
            state.clearSustain();
        }
        return cancel;
    }

    private boolean cooldownOk(ViolationState state, long now, long cooldownMillis) {
        long last = state.lastActionMillis();
        return last == 0L || now - last >= Math.max(0L, cooldownMillis);
    }

    private long sampleGap(CheckId check) {
        if (check == CheckId.BADPACKETS) {
            return 0L;
        }
        if (isMovementCheck(check)) {
            return confLong("settings.sample-gap-movement-ms", 220L);
        }
        if (isCombatCheck(check)) {
            return confLong("settings.sample-gap-combat-ms", 120L);
        }
        return confLong("settings.sample-gap-block-ms", 80L);
    }

    private boolean isMovementCheck(CheckId check) {
        return switch (check) {
            case SPEED, FLY, STEP, JESUS, SPIDER, NOCLIP, TIMER, ELYTRA, VEHICLE -> true;
            default -> false;
        };
    }

    private boolean isCombatCheck(CheckId check) {
        return switch (check) {
            case REACH, KILLAURA, AUTOCLICKER, VELOCITY -> true;
            default -> false;
        };
    }

    /** Сколько проверок сейчас имеют заметный VL — защита от наказания за одну шумную проверку. */
    private int distinctActive(PlayerData data) {
        int count = 0;
        for (CheckId check : CheckId.values()) {
            if (data.vl(check) >= 1.0) {
                count++;
            }
        }
        return count;
    }

    private void maybeAlert(Player player, PlayerData data, CheckId check, CheckSettings cfg,
                            ViolationState state, String details, long now) {
        double vl = state.vl();
        if (vl < confDouble("settings.alert-min-vl", 1.0)) {
            return;
        }
        long last = state.lastAlertMillis();
        if (last != 0L && now - last < confLong("settings.alert-cooldown-ms", 2500L)) {
            return;
        }
        state.markAlert(now);

        String text = replaceAlert(getConfig().getString("messages.alert", ""), player, check, vl, cfg.maxVl(), details);
        String plainText = plain(getConfig().getString("messages.prefix", "") + text);
        if (confBool("settings.log-console", true)) {
            getLogger().info(plainText);
        }
        if (confBool("settings.log-file", true)) {
            writeLogLine(plainText);
        }
        if (confBool("settings.staff-alerts", true)) {
            String hover = replaceAlert(getConfig().getString("messages.alert-hover", ""), player, check, vl, cfg.maxVl(), details);
            Component component = comp(getConfig().getString("messages.prefix", "") + text)
                    .clickEvent(ClickEvent.runCommand("/vac info " + player.getName()))
                    .hoverEvent(HoverEvent.showText(comp(hover)));
            for (Player staff : Bukkit.getOnlinePlayers()) {
                if (staff.hasPermission("voidanticheat.alerts") && !alertsOff.contains(staff.getUniqueId())) {
                    staff.sendMessage(component);
                }
            }
        }
        if (confBool("webhook.enabled", false)
                && vl >= confDouble("webhook.min-vl", 10.0)) {
            sendWebhook(plainText);
        }
    }

    private String replaceAlert(String text, Player player, CheckId check, double vl, double maxVl, String details) {
        if (text == null) {
            return "";
        }
        PlayerData data = players.get(player.getUniqueId());
        double jitter = data == null ? 0.0 : data.lag.deviationMs();
        return text.replace("{player}", player.getName())
                .replace("{check}", check.display())
                .replace("{vl}", formatVl(vl))
                .replace("{max_vl}", formatVl(maxVl))
                .replace("{details}", details == null ? "" : details)
                .replace("{ping}", String.valueOf(player.getPing()))
                .replace("{jitter}", formatVl(jitter))
                .replace("{tps}", formatVl(currentTps()));
    }

    private void runPunishments(Player player, CheckId check, CheckSettings cfg, double vl, String details) {
        for (String raw : cfg.punishCommands()) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String command = raw.replace("%player%", player.getName())
                    .replace("%check%", check.display())
                    .replace("%vl%", formatVl(vl))
                    .replace("%max_vl%", formatVl(cfg.maxVl()))
                    .replace("%details%", details == null ? "" : details)
                    .replace("%ping%", String.valueOf(player.getPing()))
                    .replace("%tps%", formatVl(currentTps()))
                    .replace("%world%", player.getWorld().getName());
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
        }
        if (confBool("webhook.enabled", false)) {
            sendWebhook(plain(getConfig().getString("messages.prefix", "")
                    + "&cНаказание: " + player.getName() + " — " + check.display() + " VL " + formatVl(vl)
                    + " (" + details + ")"));
        }
    }

    private void writeLogLine(String plainText) {
        if (ioExecutor == null || ioExecutor.isShutdown()) {
            return;
        }
        String line = "[" + LocalDateTime.now().format(LOG_TIME) + "] " + plainText + System.lineSeparator();
        ioExecutor.execute(() -> {
            try {
                File file = new File(getDataFolder(), "violations.log");
                Files.writeString(file.toPath(), line, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (Throwable ignored) {
                // Лог не должен ронять античит.
            }
        });
    }

    private void sendWebhook(String plainText) {
        String url = getConfig().getString("webhook.url", "");
        if (url == null || url.isBlank() || ioExecutor == null || ioExecutor.isShutdown()) {
            return;
        }
        String json = "{\"content\":\"" + escapeJson(plainText) + "\"}";
        ioExecutor.execute(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                        .build();
                httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding());
            } catch (Throwable ignored) {
                // Webhook не должен влиять на игровой поток.
            }
        });
    }

    private String escapeJson(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    private String formatVl(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.001) {
            return String.valueOf((long) Math.rint(value));
        }
        return String.format(Locale.ROOT, "%.1f", value);
    }

    /** VoidPvP (или другой плагин без кд атаки) делает высокий CPS легальным. */
    private boolean fastAttackServer(Player player) {
        if (fastAttackCache == null) {
            PluginManager manager = Bukkit.getPluginManager();
            // Null cooldown on this core is NOT a plugin feature: Purpurbedrock
            // patches Player#attack to skip the 1.9 cooldown entirely for Bedrock
            // players (PurpurWorldConfig.bedrockCombatEnabled = true by default:
            // attackStrengthTicker = 9999, attackStrengthScale = 1.0). VoidPvP
            // additionally raises ATTACK_SPEED and zeroes noDamageTicks.
            // Therefore raw CPS is legitimate server mechanics here, not a cheat
            // signal, and only click-interval regularity is checked.
            fastAttackCache = confBool("settings.fast-attack-server", false)
                    || manager.getPlugin("VoidPvP") != null
                    || (confBool("settings.core-zero-attack-cooldown", true) && coreCombatEnabled(player));
            if (fastAttackCache) {
                getLogger().info("Обнаружен режим без кд атаки: сырой CPS не считается автокликером, "
                        + "проверяется только ровность интервалов.");
            }
        }
        return fastAttackCache;
    }

    /**
     * Включён ли в ядре Bedrock-боёвка с нулевым кулдауном для ЭТОГО мира.
     *
     * <p>Флаг — {@code PurpurWorldConfig.bedrockCombatEnabled}
     * (по умолчанию {@code true}, ключ
     * {@code gameplay-mechanics.bedrock-combat.enabled}). Он именно
     * per-world, поэтому берётся из мира игрока, а не глобально.</p>
     *
     * <p>Честная оговорка: {@code World#getHandle()} и {@code .purpurConfig} —
     * внутренности ядра. Если они переименованы, мы получим ошибку и уйдём в
     * безопасную сторону (считать, что нулевой кулдаун есть). Ошибка при этом
     * означает лишь, что частота ударов перестанет быть сама по себе уликой —
     * остаются ровность интервалов, Reach и KillAura.</p>
     */
    private boolean coreCombatEnabled(Player player) {
        if (player == null || player.getWorld() == null) {
            return true;
        }
        String worldName = player.getWorld().getName();
        Boolean cached = coreCombatByWorld.get(worldName);
        if (cached != null) {
            return cached;
        }
        boolean enabled = true; // безопасное направление при любой неопределённости
        try {
            Class<?> worldConfig = Class.forName("org.purpurmc.purpur.PurpurWorldConfig");
            Object config = player.getWorld().getClass().getMethod("getHandle").invoke(player.getWorld());
            Object worldConfigInstance = config.getClass().getField("purpurConfig").get(config);
            Object value = worldConfig.getField("bedrockCombatEnabled").get(worldConfigInstance);
            enabled = value instanceof Boolean flag ? flag : true;
        } catch (ClassNotFoundException notThisCore) {
            // Не это ядро (или внутренности переименованы): ориентируемся только
            // на settings.fast-attack-server, чтобы не выдавать неизвестное
            // за известное. Частота ударов тогда снова учитывается, но остаются
            // ровность интервалов, Reach и KillAura.
            enabled = false;
        } catch (Throwable ignored) {
            enabled = true;
        }
        coreCombatByWorld.put(worldName, enabled);
        return enabled;
    }

    private double currentTps() {
        double[] tps = Bukkit.getTPS();
        return tps.length == 0 ? 20.0 : Math.min(20.0, tps[0]);
    }

    // ------------------------------------------------------------ setback и безопасная точка

    private void updateSafeLocation(Player player, Location location, PlayerData data) {
        if (location == null || location.getWorld() == null || player.getVehicle() != null || player.isGliding()) {
            return;
        }
        if (!player.isOnGround() || player.isFlying()) {
            return;
        }
        Block below = blockAt(location, -0.25);
        if (below == null || !below.getType().isSolid() || below.isLiquid()) {
            return;
        }
        if (isLiquid(blockAt(location, 0.05))) {
            return;
        }
        data.safeLocation = location.clone();
        data.safeUpdatedMillis = System.currentTimeMillis();
    }

    private Location safeOr(PlayerData data, Location fallback) {
        if (data.safeLocation != null && data.safeLocation.getWorld() != null) {
            Location safe = data.safeLocation.clone();
            safe.setYaw(fallback.getYaw());
            safe.setPitch(fallback.getPitch());
            return safe;
        }
        return fallback.clone();
    }

    private boolean setbackPlayer(Player player, PlayerData data) {
        if (data.safeLocation == null || data.safeLocation.getWorld() == null) {
            return false;
        }
        Location safe = data.safeLocation.clone();
        safe.setYaw(player.getLocation().getYaw());
        safe.setPitch(player.getLocation().getPitch());
        if (player.getVehicle() != null) {
            player.leaveVehicle();
        }
        boolean ok = player.teleport(safe);
        if (ok) {
            grantGrace(data, 650L);
            data.resetMovement(safe, Bukkit.getCurrentTick(), System.nanoTime(), true);
            data.setbackRequested = false;
            data.clearSustainAll();
        }
        return ok;
    }

    // ------------------------------------------------------------ движение

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        PlayerData data = data(player);
        Location to = event.getTo();
        if (to == null || to.getWorld() == null) {
            return;
        }
        long nowNanos = System.nanoTime();
        long nowMillis = System.currentTimeMillis();
        int tick = Bukkit.getCurrentTick();

        if (!shouldCheck(player, data)) {
            data.resetMovement(to, tick, nowNanos, player.isOnGround());
            return;
        }

        if (!isFinite(to.getX()) || !isFinite(to.getY()) || !isFinite(to.getZ())
                || !isFinite(to.getYaw()) || !isFinite(to.getPitch())
                || to.getPitch() < -90.5f || to.getPitch() > 90.5f
                || to.getYaw() < -100_000f || to.getYaw() > 100_000f) {
            flag(player, data, CheckId.BADPACKETS, 2.0, "невозможные координаты/углы");
            if (confBool("settings.enforcement", true)) {
                event.setTo(event.getFrom().clone());
            }
            data.resetMovement(event.getFrom(), tick, nowNanos, player.isOnGround());
            return;
        }

        if (!data.hasLast || !to.getWorld().getName().equals(data.lastWorld)) {
            data.resetMovement(to, tick, nowNanos, player.isOnGround());
            updateSafeLocation(player, to, data);
            return;
        }

        // Согласование со сторонними телепортами и коррекциями ядра.
        //
        // PlayerMoveEvent не всегда начинается там, где мы запомнили позицию:
        // ночной RTP, выход из кровати, портал, телепорт плагина и принудительная
        // коррекция ядра (BedrockMovementGuard) могут не пройти через
        // PlayerTeleportEvent. Раньше эта разница считалась движением и уходила в
        // Speed/Fly/Timer/NoClip, а setback возвращал игрока назад. Теперь такое
        // событие — не движение: фиксируем позицию, даём короткий грейс и
        // начинаем статистику заново.
        double fromGap = Math.sqrt(Math.pow(event.getFrom().getX() - data.lastX, 2)
                + Math.pow(event.getFrom().getY() - data.lastY, 2)
                + Math.pow(event.getFrom().getZ() - data.lastZ, 2));
        double syncEpsilon = confDouble("movement.sync-epsilon-blocks", 0.08);
        if (data.unsyncedPosition || fromGap > syncEpsilon) {
            data.unsyncedPosition = false;
            grantGrace(data, confLong("settings.unsynced-grace-ms", 600L));
            data.resetMovement(to, tick, nowNanos, player.isOnGround());
            updateSafeLocation(player, to, data);
            if (confBool("settings.debug", false) && fromGap > syncEpsilon) {
                getLogger().info("[debug] " + player.getName()
                        + " серверное перемещение на " + formatVl(fromGap)
                        + " бл. — событие не считается движением игрока");
            }
            return;
        }

        double dx = to.getX() - data.lastX;
        double dy = to.getY() - data.lastY;
        double dz = to.getZ() - data.lastZ;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        boolean tickChanged = tick != data.lastServerTick;
        int tickDelta = tickChanged ? Math.max(1, tick - data.lastServerTick) : 0;
        boolean moved = horizontal > 0.0001 || Math.abs(dy) > 0.0001;

        if (moved) {
            data.timerPackets++;
            data.lag.sample(nowNanos, nowMillis, tickDelta, lagLimits);
        }

        float yawDelta = Math.abs(angleDifference(to.getYaw(), data.lastYaw));
        float pitchDelta = Math.abs(to.getPitch() - data.lastPitch);
        if (yawDelta > 125f || pitchDelta > 70f) {
            data.lastBigRotationMillis = nowMillis;
            data.rotationBeforeAttack = yawDelta;
        }

        double teleportDelta = confDouble("movement.teleport-delta-blocks", 24.0);
        if ((horizontal > teleportDelta || Math.abs(dy) > teleportDelta) && !inGrace(data)) {
            flag(player, data, CheckId.BADPACKETS, 2.0,
                    "скачок позиции " + formatVl(Math.max(horizontal, Math.abs(dy))) + " бл.");
            event.setTo(safeOr(data, event.getFrom()));
            data.resetMovement(event.getTo(), tick, nowNanos, player.isOnGround());
            return;
        }

        EnvironmentSnapshot env = scanEnvironment(player, data, to);

        // Транспорт проверяется отдельно: физика пассажира тут не работает.
        if (player.getVehicle() != null) {
            vehicleCheck(player, data, to, nowNanos, nowMillis, env);
            data.rememberPosition(to, tick, nowNanos, player.isOnGround());
            return;
        }

        // Грейсы и просадки TPS: не штрафуем, но и не «копим» наверстывание.
        if (inGrace(data) || currentTps() < confDouble("settings.min-tps-for-movement", 16.0)) {
            data.resetAirState();
            data.speed.softReset(confDouble("movement.speed-token-cap", 4.5));
            updateSafeLocation(player, to, data);
            data.rememberPosition(to, tick, nowNanos, player.isOnGround());
            return;
        }

        evaluateVelocity(player, data, dx, dz, tick, env);

        if (player.isGliding() || env.gliding()) {
            elytraCheck(player, data, to, dy, horizontal, nowNanos, nowMillis, tickChanged, env);
            data.speedRefillNanos = nowNanos;
            data.rememberPosition(to, tick, nowNanos, player.isOnGround());
            return;
        }

        if (!env.speedImmune()) {
            speedCheck(player, data, to, horizontal, nowNanos, nowMillis, tickDelta, env);
        }

        // Jesus проверяется отдельно: игрок «на поверхности» воды формально
        // не в жидкости, но env.inWater() это ловит.
        if (env.inWater() && !player.isSwimming()) {
            jesusCheck(player, data, to, horizontal, env);
        } else {
            data.jesusTicks = 0;
        }

        if (tickChanged) {
            verticalChecks(player, data, to, dy, horizontal, tickDelta, env, nowMillis);
            noclipCheck(player, data, to, env, nowMillis);
        }

        if (data.setbackRequested) {
            Location safe = safeOr(data, to);
            event.setTo(safe);
            data.resetMovement(safe, tick, nowNanos, true);
            data.setbackRequested = false;
            return;
        }

        updateSafeLocation(player, to, data);
        data.rememberPosition(to, tick, nowNanos, player.isOnGround());
    }

    /**
     * Дешёвый снимок окружения: десяток чтений блоков, кэш на серверный тик
     * (Bedrock часто присылает два move-пакета за тик).
     */
    private EnvironmentSnapshot scanEnvironment(Player player, PlayerData data, Location to) {
        int tick = Bukkit.getCurrentTick();
        if (data.env != null && data.envTick == tick
                && Math.abs(data.envX - to.getX()) < 0.08
                && Math.abs(data.envY - to.getY()) < 0.08
                && Math.abs(data.envZ - to.getZ()) < 0.08) {
            return data.env;
        }
        World world = to.getWorld();
        if (world == null) {
            return EnvironmentSnapshot.neutral();
        }
        long now = System.currentTimeMillis();
        Block feet = blockAt(to, 0.05);
        Block below = blockAt(to, -0.25);
        Block low = blockAt(to, -0.85);
        Block head = blockAt(to, 1.05);
        Material feetType = typeOf(feet);
        Material belowType = typeOf(below);
        Material lowType = typeOf(low);
        Material headType = typeOf(head);

        boolean inWater = player.isInWater() || feetType == Material.WATER
                || belowType == Material.WATER || headType == Material.WATER;
        boolean inLava = feetType == Material.LAVA || belowType == Material.LAVA || headType == Material.LAVA;
        boolean inBubble = feetType == Material.BUBBLE_COLUMN || belowType == Material.BUBBLE_COLUMN
                || headType == Material.BUBBLE_COLUMN;
        boolean inCobweb = feetType == Material.COBWEB || headType == Material.COBWEB
                || belowType == Material.COBWEB;
        boolean inPowderSnow = feetType == Material.POWDER_SNOW || headType == Material.POWDER_SNOW;
        boolean climbable = isClimbable(feet) || isClimbable(head) || isClimbable(blockAt(to, -0.55));
        boolean scaffolding = feetType == Material.SCAFFOLDING || belowType == Material.SCAFFOLDING;

        boolean slime = belowType == Material.SLIME_BLOCK || lowType == Material.SLIME_BLOCK;
        boolean honey = belowType == Material.HONEY_BLOCK || lowType == Material.HONEY_BLOCK;
        boolean hay = belowType == Material.HAY_BLOCK || lowType == Material.HAY_BLOCK;
        boolean ice = isIce(belowType) || isIce(lowType);
        boolean solidSupport = below != null && below.getType().isSolid() || low != null && low.getType().isSolid();

        boolean entitySupport = false;
        if (!player.isOnGround() && player.getVehicle() == null) {
            BoundingBox area = new BoundingBox(to.getX() - 0.55, to.getY() - 1.60, to.getZ() - 0.55,
                    to.getX() + 0.55, to.getY() - 0.06, to.getZ() + 0.55);
            for (Entity entity : world.getNearbyEntities(area)) {
                if (entity.equals(player) || entity instanceof Player) {
                    continue;
                }
                if (entity instanceof LivingEntity || entity instanceof Boat) {
                    entitySupport = true;
                    break;
                }
            }
        }

        double surface = 1.0;
        if (ice) {
            Material icy = isIce(belowType) ? belowType : lowType;
            surface = switch (icy) {
                case BLUE_ICE -> confDouble("movement.blue-ice-factor", 2.35);
                case PACKED_ICE -> confDouble("movement.packed-ice-factor", 1.95);
                case FROSTED_ICE -> confDouble("movement.frosted-ice-factor", 1.45);
                default -> confDouble("movement.ice-factor", 1.70);
            };
        } else if (belowType == Material.SOUL_SAND || belowType == Material.SOUL_SOIL
                || lowType == Material.SOUL_SAND || lowType == Material.SOUL_SOIL) {
            int soulSpeed = enchantLevel(player.getInventory().getBoots(), soulSpeedEnchant);
            if (soulSpeed > 0) {
                surface = 1.0 + soulSpeed * 0.34;
            }
        }

        EnvironmentSnapshot snapshot = EnvironmentSnapshot.builder()
                .inWater(inWater)
                .inLava(inLava)
                .inBubble(inBubble)
                .inCobweb(inCobweb)
                .inPowderSnow(inPowderSnow)
                .climbable(climbable)
                .scaffolding(scaffolding)
                .slimeSurface(slime)
                .honeySurface(honey)
                .haySurface(hay)
                .iceSurface(ice)
                .solidSupportBelow(solidSupport)
                .entitySupportBelow(entitySupport)
                .vehicle(player.getVehicle() != null)
                .gliding(player.isGliding())
                .flying(player.isFlying())
                .levitation(effect(player, levitationEffect) != null)
                .slowFalling(effect(player, slowFallingEffect) != null)
                .frostWalker(enchantLevel(player.getInventory().getBoots(), frostWalkerEnchant) > 0)
                .riptide(now < data.riptideUntilMillis)
                .pistonPushed(now < data.pistonUntilMillis)
                .recentExplosion(now < data.explosionUntilMillis)
                .recentDamage(data.lastDamageMillis > 0L
                        && now - data.lastDamageMillis < confLong("settings.damage-grace-ms", 500L))
                .sneaking(player.isSneaking())
                .swimming(player.isSwimming())
                .sleeping(player.isSleeping())
                .jumpBoost(effectAmplifier(player, jumpBoostEffect))
                .surfaceSpeedMultiplier(surface)
                .build();

        data.env = snapshot;
        data.envTick = tick;
        data.envX = to.getX();
        data.envY = to.getY();
        data.envZ = to.getZ();
        return snapshot;
    }

    private void speedCheck(Player player, PlayerData data, Location to, double horizontal,
                            long nowNanos, long nowMillis, int tickDelta, EnvironmentSnapshot env) {
        double rawElapsed = data.speedRefillNanos == 0L ? 0.0 : (nowNanos - data.speedRefillNanos) / 1_000_000_000.0;
        data.speedRefillNanos = nowNanos;
        double cap = confDouble("movement.speed-token-cap", 4.5);
        double allowed = allowedHorizontalBps(player, data, to, env);
        double elapsed = LagCompensation.effectiveSeconds(rawElapsed, tickDelta);

        // Долгий фриз/лаг: пакеты придут пачкой, «наверстывание» — не чит.
        // Сэмпл выбрасываем, токены пополняем консервативно.
        if (rawElapsed == 0.0 || rawElapsed > confDouble("movement.speed-lag-skip-seconds", 0.60)
                || data.lag.unstable(nowMillis)) {
            data.speed.discard(allowed, cap);
            return;
        }

        if (elapsed > 0.004) {
            data.lastHorizontalBps = horizontal / elapsed;
        }
        double burst = confDouble("movement.speed-burst-blocks", 1.60);
        double excess = data.speed.update(horizontal, allowed, elapsed, cap, burst);
        if (excess > 0.0 && horizontal > 0.06) {
            double points = Math.min(3.0, 0.8 + excess * 0.5);
            flag(player, data, CheckId.SPEED, points,
                    "горизонтальная скорость " + formatVl(data.lastHorizontalBps) + " бл/с, лимит ~"
                            + formatVl(allowed) + " бл/с");
        }
    }

    private double allowedHorizontalBps(Player player, PlayerData data, Location to, EnvironmentSnapshot env) {
        double base;
        if (env.fluidLike()) {
            base = player.isSwimming() ? 5.9 : 4.7;
            int depthStrider = enchantLevel(player.getInventory().getBoots(), depthStriderEnchant);
            base += Math.min(3, Math.max(0, depthStrider)) * 0.65;
            if (effect(player, dolphinsGraceEffect) != null) {
                // Dolphin's Grace разгоняет до ~9.6 бл/с — без этого любая
                // проплывка с дельфином давала бы Speed.
                base = Math.max(base, confDouble("movement.dolphins-grace-bps", 10.0));
            }
        } else {
            double attribute = attributeValue(player, movementSpeedAttribute, 0.1);
            base = attribute * 43.17;
            if (player.isSneaking()) {
                base = Math.min(base, 1.75);
            } else if (player.isSprinting()) {
                base *= 1.30;
            }
            if (!player.isOnGround()) {
                // Bedrock спринт-прыжки и сохранение инерции шумнее Java-модели.
                base *= 1.30;
            }
            base *= Math.max(1.0, env.surfaceSpeedMultiplier());
        }
        if (System.currentTimeMillis() < data.velocityGraceUntilMillis) {
            Vector velocity = player.getVelocity();
            double velocityBps = Math.sqrt(velocity.getX() * velocity.getX() + velocity.getZ() * velocity.getZ()) * 20.0;
            base = Math.max(base, velocityBps * 1.45);
        }
        double tolerance = data.lag.tolerance(player.getPing(), currentTps(), lagLimits);
        if (data.bedrock) {
            tolerance += confDouble("settings.bedrock-extra-tolerance", 0.04);
        }
        return Math.max(3.4, Math.min(60.0, base * tolerance));
    }

    private void verticalChecks(Player player, PlayerData data, Location to, double rawDy,
                                double horizontal, int tickDelta, EnvironmentSnapshot env, long nowMillis) {
        boolean onGround = player.isOnGround();
        double dy = rawDy / Math.max(1, tickDelta);

        if (env.verticalExempt()) {
            data.resetAirState();
            data.lastDeltaY = dy;
            return;
        }

        // Сервер сам подбросил игрока: Riptide, wind charge, поршень, прыжковая
        // платформа, взрыв, любой плагин через setVelocity. Клиент такое не
        // подделает (getVelocity — серверное значение), поэтому это безопасное
        // исключение, а не дыра.
        Vector serverVelocity = player.getVelocity();
        boolean launchedByServer = nowMillis < data.velocityGraceUntilMillis
                || (serverVelocity != null && serverVelocity.getY() > confDouble("movement.server-launch-velocity-y", 0.22));
        if (launchedByServer) {
            data.resetAirState();
            data.lastDeltaY = dy;
            return;
        }

        if (onGround) {
            if (!data.lastOnGround) {
                landingCheck(player, data, to, env, nowMillis);
            } else {
                stepCheck(player, data, dy, tickDelta, env, nowMillis);
            }
            data.resetAirState();
            data.peakY = to.getY();
            data.lastDeltaY = dy;
            return;
        }

        boolean leftGround = data.lastOnGround;
        if (leftGround) {
            data.airTicks = 0;
            data.peakY = Math.max(data.lastY, to.getY());
        }
        data.airTicks += Math.max(1, tickDelta);
        data.peakY = Math.max(data.peakY, to.getY());

        if (tickDelta > 3) {
            // Слишком большой разрыв между сэмплами: модель неприменима.
            data.resetAirState();
            data.lastDeltaY = dy;
            return;
        }

        PredictionMeter.Verdict verdict = data.prediction.tick(rawDy, Math.max(1, tickDelta), leftGround,
                confDouble("movement.fly-ascent-blocks", 3.4),
                confInt("movement.fly-hover-ticks", 40));

        if (!data.lag.unstable(nowMillis)) {
            if (verdict.impossibleAscent()) {
                flag(player, data, CheckId.FLY, 2.0,
                        "набор " + formatVl(verdict.ascent()) + " бл. в воздухе без прыжка, отклонение "
                                + formatVl(verdict.offset()));
            } else if (verdict.hover() && !env.hoverExempt()) {
                flag(player, data, CheckId.FLY, 1.0,
                        "зависание в воздухе " + verdict.airTicks() + " тиков, dy=" + formatVl(dy));
            }
        }

        // Слишком медленное падение после разгона вниз (антигравитация / NoFall-подобное).
        if (!env.hoverExempt() && data.airTicks >= 14 && dy > -0.22 && data.lastDeltaY < 0.02
                && !data.lag.unstable(nowMillis)) {
            data.slowFallStreak++;
            if (data.slowFallStreak >= confInt("movement.slow-fall-ticks", 8)) {
                flag(player, data, CheckId.FLY, 1.0,
                        "слишком медленное падение, dy=" + formatVl(dy) + " бл/тик");
                data.slowFallStreak = 0;
            }
        } else {
            data.slowFallStreak = 0;
        }

        // Spider/Climb: подъём у стены без лестницы/лианы/воды.
        if (dy > 0.075 && dy < 0.68 && isNearWall(to) && !env.climbable() && !env.fluidLike()
                && !data.lag.unstable(nowMillis) && !env.recentDamage()) {
            data.climbStreak++;
            if (data.climbStreak >= confInt("movement.climb-ticks", 10)) {
                flag(player, data, CheckId.SPIDER, 1.0,
                        "подъём по стене без лестницы, dy=" + formatVl(dy));
                data.climbStreak = 0;
            }
        } else {
            data.climbStreak = 0;
        }

        data.lastDeltaY = dy;
    }

    private void landingCheck(Player player, PlayerData data, Location to, EnvironmentSnapshot env, long nowMillis) {
        if (env.nofallExempt() || data.lag.unstable(nowMillis)) {
            return;
        }
        double drop = data.peakY - to.getY();
        if (drop < confDouble("movement.nofall-min-drop", 5.0)) {
            return;
        }
        // Приземление на лодку/моба/полу-блок серверная дистанция падения
        // не показательна — такие случаи не проверяем.
        Block landing = blockAt(to, -0.25);
        if (landing == null || !landing.getType().isSolid()) {
            return;
        }
        // В Bukkit это float — не меняем на double, иначе NoSuchMethodError.
        float serverFall = player.getFallDistance();
        double ratio = confDouble("movement.nofall-server-fall-ratio", 0.30);
        if (serverFall < drop * ratio) {
            flag(player, data, CheckId.NOFALL, 1.2,
                    "падение " + formatVl(drop) + " бл., серверная дистанция " + formatVl(serverFall));
        }
    }

    private void stepCheck(Player player, PlayerData data, double dy, int tickDelta,
                           EnvironmentSnapshot env, long nowMillis) {
        if (tickDelta != 1 || nowMillis < data.velocityGraceUntilMillis || env.stepExempt()
                || data.lag.unstable(nowMillis)) {
            return;
        }
        if (dy >= confDouble("movement.step-min-dy", 1.02)) {
            flag(player, data, CheckId.STEP, 1.2,
                    "мгновенный подъём на " + formatVl(dy) + " бл. за тик");
        }
    }

    private void jesusCheck(Player player, PlayerData data, Location to, double horizontal,
                            EnvironmentSnapshot env) {
        if (env.jesusExempt()) {
            data.jesusTicks = 0;
            return;
        }
        Block below = blockAt(to, -0.25);
        Block feet = blockAt(to, 0.05);
        boolean standingOnLiquid = isLiquid(below) && !isLiquid(feet)
                && player.isOnGround() && !player.isSwimming();
        if (standingOnLiquid && horizontal > 0.015) {
            data.jesusTicks++;
            if (data.jesusTicks >= confInt("movement.jesus-ticks", 9)) {
                flag(player, data, CheckId.JESUS, 1.0, "ходьба по поверхности жидкости");
                data.jesusTicks = 0;
            }
        } else {
            data.jesusTicks = 0;
        }
    }

    private void noclipCheck(Player player, PlayerData data, Location to, EnvironmentSnapshot env, long nowMillis) {
        if (env.noclipExempt() || (data.lastBlockChangeMillis > 0L
                && nowMillis - data.lastBlockChangeMillis < confLong("settings.block-change-grace-ms", 1500L))) {
            data.noclipStreak = 0;
            return;
        }
        Block feet = blockAt(to, 0.10);
        Block head = blockAt(to, 1.55);
        boolean inside = isHardSolid(feet) && isHardSolid(head);
        if (inside) {
            data.noclipStreak++;
            if (data.noclipStreak >= confInt("movement.noclip-ticks", 4)) {
                flag(player, data, CheckId.NOCLIP, 1.2,
                        "позиция внутри твёрдых блоков " + name(feet) + "/" + name(head));
                data.noclipStreak = 0;
            }
        } else {
            data.noclipStreak = 0;
        }
    }

    private void elytraCheck(Player player, PlayerData data, Location to, double rawDy,
                             double horizontal, long nowNanos, long nowMillis, boolean tickChanged,
                             EnvironmentSnapshot env) {
        if (tickChanged && !env.fluidLike() && nowMillis >= data.elytraGraceUntilMillis
                && nowMillis >= data.velocityGraceUntilMillis && !data.lag.unstable(nowMillis)) {
            double dy = rawDy;
            if (dy > confDouble("elytra.climb-dy-per-tick", 0.62)) {
                data.climbStreak++;
                if (data.climbStreak >= confInt("elytra.climb-ticks", 6)) {
                    flag(player, data, CheckId.ELYTRA, 1.2,
                            "набор высоты на элитре без фейерверка, dy=" + formatVl(dy));
                    data.climbStreak = 0;
                }
            } else {
                data.climbStreak = 0;
            }
        }
        if (data.speedRefillNanos > 0L) {
            double elapsed = (nowNanos - data.speedRefillNanos) / 1_000_000_000.0;
            double limit = confDouble("elytra.max-horizontal-bps", 118.0)
                    * data.lag.tolerance(player.getPing(), currentTps(), lagLimits);
            if (elapsed > 0.01 && !data.lag.unstable(nowMillis) && horizontal / elapsed > limit) {
                flag(player, data, CheckId.ELYTRA, 1.0,
                        "скорость элитры " + formatVl(horizontal / elapsed) + " бл/с");
            }
        }
    }

    private void vehicleCheck(Player player, PlayerData data, Location to, long nowNanos,
                              long nowMillis, EnvironmentSnapshot env) {
        Entity vehicle = player.getVehicle();
        if (!(vehicle instanceof Boat)) {
            data.vehicleLastNanos = 0L;
            return;
        }
        if (data.vehicleLastNanos == 0L || !to.getWorld().getName().equals(data.vehicleWorld)) {
            data.vehicleWorld = to.getWorld().getName();
            data.vehicleX = to.getX();
            data.vehicleZ = to.getZ();
            data.vehicleLastNanos = nowNanos;
            data.vehicleTokens = confDouble("vehicle.burst-blocks", 3.0) + 2.0;
            return;
        }
        double elapsed = (nowNanos - data.vehicleLastNanos) / 1_000_000_000.0;
        data.vehicleLastNanos = nowNanos;
        double dx = to.getX() - data.vehicleX;
        double dz = to.getZ() - data.vehicleZ;
        data.vehicleX = to.getX();
        data.vehicleZ = to.getZ();
        if (elapsed <= 0.001 || elapsed > 0.60 || data.lag.unstable(nowMillis)) {
            return;
        }
        Block below = blockAt(vehicle.getLocation(), -0.35);
        Material belowType = typeOf(below);
        double max;
        if (belowType == Material.BLUE_ICE) {
            max = confDouble("vehicle.blue-ice-max-bps", 48.0);
        } else if (belowType == Material.PACKED_ICE) {
            max = confDouble("vehicle.packed-ice-max-bps", 40.0);
        } else if (isIce(belowType)) {
            max = confDouble("vehicle.ice-max-bps", 34.0);
        } else if (env.fluidLike() || vehicle.isInWater()) {
            max = confDouble("vehicle.water-max-bps", 16.0);
        } else {
            max = confDouble("vehicle.ground-max-bps", 14.0);
        }
        max *= data.lag.tolerance(player.getPing(), currentTps(), lagLimits);
        double burst = confDouble("vehicle.burst-blocks", 3.0);
        double distance = Math.sqrt(dx * dx + dz * dz);
        data.vehicleTokens = Math.min(burst + 2.0, data.vehicleTokens + max * elapsed);
        data.vehicleTokens -= distance;
        if (data.vehicleTokens < -burst) {
            flag(player, data, CheckId.VEHICLE, 1.2,
                    "скорость лодки " + formatVl(distance / elapsed) + " бл/с, лимит ~" + formatVl(max));
            data.vehicleTokens = -burst * 0.45;
        }
    }

    // ------------------------------------------------------------ velocity

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVelocity(PlayerVelocityEvent event) {
        Player player = event.getPlayer();
        PlayerData data = data(player);
        if (!shouldCheck(player, data)) {
            return;
        }
        grantGrace(data, confLong("settings.velocity-grace-ms", 900L));
        data.velocityGraceUntilMillis = System.currentTimeMillis()
                + confLong("settings.velocity-grace-ms", 900L);
        Vector velocity = event.getVelocity();
        double horizontal = Math.sqrt(velocity.getX() * velocity.getX() + velocity.getZ() * velocity.getZ());
        int ticks = Math.max(1, confInt("combat.velocity-check-ticks", 5));
        if (horizontal > 0.16) {
            double expected = 0.0;
            double current = horizontal;
            for (int i = 0; i < ticks; i++) {
                expected += current;
                current *= 0.91;
            }
            data.velocityPending = true;
            data.velocityStartMillis = System.currentTimeMillis();
            data.velocityStartTick = Bukkit.getCurrentTick();
            data.velocityUnitX = velocity.getX() / horizontal;
            data.velocityUnitZ = velocity.getZ() / horizontal;
            data.velocityExpectedBlocks = expected;
            data.velocityTravelBlocks = 0.0;
        }
    }

    private void evaluateVelocity(Player player, PlayerData data, double dx, double dz, int tick,
                                  EnvironmentSnapshot env) {
        if (!data.velocityPending) {
            return;
        }
        data.velocityTravelBlocks += dx * data.velocityUnitX + dz * data.velocityUnitZ;
        int ticks = Math.max(1, confInt("combat.velocity-check-ticks", 5));
        if (tick < data.velocityStartTick + ticks) {
            return;
        }
        data.velocityPending = false;
        double expected = data.velocityExpectedBlocks;
        if (expected < confDouble("combat.velocity-min-expected-blocks", 1.10)) {
            return;
        }
        // Нокбэк гасится трением о землю, водой, лестницей, транспортом и
        // элитрой — это не AntiKB.
        if (player.isOnGround() || env.movementImmune() || env.slowFalling()
                || data.lag.unstable(System.currentTimeMillis())) {
            return;
        }
        if (isWallInDirection(player, data.velocityUnitX, data.velocityUnitZ)) {
            return;
        }
        double ratio = data.velocityTravelBlocks / expected;
        if (ratio < confDouble("combat.velocity-min-travel-ratio", 0.30)) {
            flag(player, data, CheckId.VELOCITY, 1.2,
                    "нокбэк пройден " + formatVl(Math.max(0.0, ratio * 100.0)) + "% от ожидаемого");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onElytraBoost(PlayerElytraBoostEvent event) {
        PlayerData data = data(event.getPlayer());
        data.elytraGraceUntilMillis = System.currentTimeMillis()
                + confLong("elytra.boost-grace-ms", 4000L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRiptide(PlayerRiptideEvent event) {
        PlayerData data = data(event.getPlayer());
        long now = System.currentTimeMillis();
        data.elytraGraceUntilMillis = now + confLong("elytra.boost-grace-ms", 4000L);
        data.velocityGraceUntilMillis = now + 2500L;
        data.riptideUntilMillis = now + confLong("settings.riptide-grace-ms", 1500L);
        grantGrace(data, 900L);
    }

    // ------------------------------------------------------------ бой

    /**
     * Ранняя проверка Reach. Стоит на LOWEST, потому что именно здесь можно
     * отменить удар; алерты/буфер общие.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onAttackReach(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker) || !(event.getEntity() instanceof LivingEntity target)) {
            return;
        }
        PlayerData data = data(attacker);
        if (!shouldCheck(attacker, data) || inGrace(data)) {
            return;
        }
        long now = System.currentTimeMillis();
        ReachVerdict verdict = measureReach(attacker, data, target, now);
        if (verdict.hard()) {
            flag(attacker, data, CheckId.REACH, 2.0, verdict.details());
            if (confBool("settings.enforcement", true)) {
                event.setCancelled(true);
            }
        } else if (verdict.distance() > verdict.limit()) {
            double points = verdict.distance() > verdict.limit() + 0.75 ? 2.0 : 1.0;
            flag(attacker, data, CheckId.REACH, points, verdict.details());
        }
    }

    private record ReachVerdict(double distance, double limit, boolean hard, String details) {
    }

    /**
     * Двусторонняя компенсация: минимальная дистанция по истории хитбокса
     * цели за окно пинга + допуск от скорости цели, джиттера и тач-ввода.
     */
    private ReachVerdict measureReach(Player attacker, PlayerData data, LivingEntity target, long now) {
        BoundingBox box = target.getBoundingBox();
        Vector eye = attacker.getEyeLocation().toVector();
        double current = boxDistance(eye, box);

        int attackerPing = effectivePing(attacker, data);
        int targetPing = target instanceof Player targetPlayer ? targetPlayer.getPing() : 0;
        long rewindWindow = Math.min(confLong("combat.reach-rewind-max-ms", 800L),
                Math.max(150L, attackerPing + targetPing + 120L));
        TargetHistory history = data.history(target.getUniqueId());
        double distance = history.minDistance(eye.getX(), eye.getY(), eye.getZ(), now, rewindWindow, current);
        history.remember(TargetHistory.sample(now,
                box.getMinX(), box.getMinY(), box.getMinZ(), box.getMaxX(), box.getMaxY(), box.getMaxZ()));

        // Ввод: явный сенсор/мобильная ОС/телефон с КМ — сенсорный лимит.
        // Неизвестное устройство тоже получает сенсорный (мягкий) лимит:
        // отсутствие данных не должно ухудшать положение игрока.
        boolean touchLike = BedrockCoreBridge.isTouchFamily(data.deviceId)
                || "TOUCH".equalsIgnoreCase(data.inputMode)
                || (!BedrockCoreBridge.isDesktopOrConsole(data.deviceId)
                    && !"KEYBOARD_MOUSE".equalsIgnoreCase(data.inputMode)
                    && !"CONTROLLER".equalsIgnoreCase(data.inputMode));
        double limit = touchLike
                ? confDouble("combat.reach-touch-max-blocks", 3.65)
                : confDouble("combat.reach-max-blocks", 3.45);
        if (data.bedrock) {
            limit += confDouble("settings.bedrock-reach-extra-blocks", 0.10);
        }
        double interaction = attributeValue(attacker, interactionRangeAttribute, 3.0);
        if (interaction > 3.0) {
            limit = Math.max(limit, interaction + 0.42);
        }
        double pingComp = Math.min(confDouble("combat.reach-ping-comp-max-blocks", 0.45),
                (attackerPing + targetPing) / 100.0
                        * confDouble("combat.reach-ping-comp-per-100ms", 0.045));
        limit += pingComp;
        Vector targetVelocity = target.getVelocity();
        double targetBps = Math.sqrt(targetVelocity.getX() * targetVelocity.getX()
                + targetVelocity.getZ() * targetVelocity.getZ()) * 20.0;
        limit += Math.min(confDouble("combat.reach-move-comp-max-blocks", 0.35),
                targetBps * confDouble("combat.reach-move-comp-per-bps", 0.02));
        double tolerance = Math.min(confDouble("combat.reach-max-tolerance", 1.30),
                data.lag.tolerance(attackerPing, currentTps(), lagLimits));
        limit *= tolerance;

        double hard = confDouble("combat.reach-hard-cancel-blocks", 4.80) * Math.min(1.15, tolerance);
        boolean hardHit = distance > hard && distance > limit + 0.70 && !data.lag.unstable(now);
        String details = "удар на " + formatVl(distance) + " бл., лимит ~" + formatVl(limit)
                + (hardHit ? " (невозможная дистанция)" : "");
        return new ReachVerdict(distance, limit, hardHit, details);
    }

    private static double boxDistance(Vector eye, BoundingBox box) {
        double nearestX = Math.max(box.getMinX(), Math.min(eye.getX(), box.getMaxX()));
        double nearestY = Math.max(box.getMinY(), Math.min(eye.getY(), box.getMaxY()));
        double nearestZ = Math.max(box.getMinZ(), Math.min(eye.getZ(), box.getMaxZ()));
        double dx = eye.getX() - nearestX;
        double dy = eye.getY() - nearestY;
        double dz = eye.getZ() - nearestZ;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker) || !(event.getEntity() instanceof LivingEntity target)) {
            return;
        }
        PlayerData data = data(attacker);
        if (!shouldCheck(attacker, data)) {
            return;
        }
        long now = System.currentTimeMillis();
        recordAttack(data, target, now);
        if (inGrace(data) || data.lag.unstable(now)) {
            return;
        }

        // KillAura: несколько разных целей за короткое окно.
        //
        // На этом ядре серия ударов по нескольким мобам — штатная механика, а не
        // мультиаура: Player#attack для Bedrock обнуляет кулдаун
        // (attackStrengthTicker = 9999) и отключает Java-sweep
        // (bedrockAllowSweepAttack = false), поэтому «много целей за секунду»
        // получается само, если бить по толпе. Плюс отбрасывание усилено
        // (knockback-multiplier 1.05) и мобы разлетаются по разным целям.
        // Поэтому на таком сервере порог поднимается, а не остаётся «заведомо
        // выше человеческого PvP» из Java-мира.
        int distinct = distinctTargets(data, now, confLong("combat.killaura-window-ms", 1000L));
        int distinctLimit = confInt("combat.killaura-multi-targets", 5);
        if (confBool("settings.core-zero-attack-cooldown", true) && coreCombatEnabled(attacker)) {
            distinctLimit = Math.max(distinctLimit,
                    confInt("combat.killaura-multi-targets-bedrock-combat", 9));
        }
        if (distinct >= distinctLimit) {
            flag(attacker, data, CheckId.KILLAURA, 1.0, distinct + " разных целей за 1 сек.");
            data.attacks.clear();
        }

        // Удары сквозь стену: только серией и не на короткой дистанции.
        double distance = eyeToBoxDistance(attacker, target);
        if (distance > confDouble("combat.killaura-wall-min-distance", 1.90)
                && attacker.getPing() < confInt("combat.killaura-wall-max-ping", 320)
                && !attacker.hasLineOfSight(target)) {
            flag(attacker, data, CheckId.KILLAURA, 0.7,
                    "удар без прямой видимости на " + formatVl(distance) + " бл.");
        }
    }

    private void recordAttack(PlayerData data, LivingEntity target, long now) {
        while (!data.attacks.isEmpty() && now - data.attacks.peekFirst().timeMillis() > 2500L) {
            data.attacks.removeFirst();
        }
        data.attacks.addLast(new PlayerData.TimedTarget(now, target.getUniqueId()));
        while (data.attacks.size() > 24) {
            data.attacks.removeFirst();
        }
    }

    private int distinctTargets(PlayerData data, long now, long windowMillis) {
        Set<UUID> targets = new HashSet<>();
        for (PlayerData.TimedTarget attack : data.attacks) {
            if (now - attack.timeMillis() <= windowMillis) {
                targets.add(attack.target());
            }
        }
        return targets.size();
    }

    private double eyeToBoxDistance(Player attacker, LivingEntity target) {
        return boxDistance(attacker.getEyeLocation().toVector(), target.getBoundingBox());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onArmSwing(PlayerArmSwingEvent event) {
        Player player = event.getPlayer();
        if (event.getHand() == EquipmentSlot.OFF_HAND) {
            return;
        }
        PlayerData data = data(player);
        if (!shouldCheck(player, data)) {
            return;
        }
        long now = System.currentTimeMillis();
        data.swings.addLast(now);
        while (!data.swings.isEmpty() && now - data.swings.peekFirst() > 3000L) {
            data.swings.removeFirst();
        }
        while (data.swings.size() > 120) {
            data.swings.removeFirst();
        }

        int lastSecond = 0;
        for (Long time : data.swings) {
            if (now - time <= 1000L) {
                lastSecond++;
            }
        }
        boolean touch = BedrockCoreBridge.isTouchFamily(data.deviceId)
                || "TOUCH".equalsIgnoreCase(data.inputMode)
                || (!BedrockCoreBridge.isDesktopOrConsole(data.deviceId)
                    && !"KEYBOARD_MOUSE".equalsIgnoreCase(data.inputMode)
                    && !"CONTROLLER".equalsIgnoreCase(data.inputMode));
        // VoidPvP убирает ванильный кулдаун атаки, поэтому высокий CPS там —
        // штатное поведение, а не автокликер. Оставляем только проверку
        // машинной ровности интервалов (её спам не даёт).
        boolean fastAttack = fastAttackServer(player);
        int maxCps = touch
                ? confInt("combat.autoclicker-touch-cps", 27)
                : confInt("combat.autoclicker-cps", 24);
        if (!fastAttack && lastSecond > maxCps) {
            data.cpsHighWindows = Math.min(6, data.cpsHighWindows + 1);
        } else {
            data.cpsHighWindows = Math.max(0, data.cpsHighWindows - 1);
        }
        boolean sustained = !fastAttack && data.cpsHighWindows >= confInt("combat.autoclicker-high-windows", 2);
        if (sustained) {
            // Кулдаун здесь не нужен: буфер сам решает, сколько улик нужно
            // для VL, а искусственная пауза только прятала бы спам.
            flag(player, data, CheckId.AUTOCLICKER, 1.0,
                    "CPS " + lastSecond + " (лимит " + maxCps + ") два окна подряд");
        }

        int sample = confInt("combat.autoclicker-sample-clicks", 32);
        // На тач-вводе удержание даёт почти идеальный тиковый ритм — это не
        // автокликер, поэтому ровность проверяем только мышь/геймпад и только
        // при явно машинной частоте.
        if (!touch && data.swings.size() >= sample) {
            List<Long> times = new ArrayList<>(data.swings);
            int from = times.size() - sample;
            double total = 0.0;
            List<Double> intervals = new ArrayList<>();
            for (int i = from + 1; i < times.size(); i++) {
                double interval = times.get(i) - times.get(i - 1);
                intervals.add(interval);
                total += interval;
            }
            if (!intervals.isEmpty()) {
                double avg = total / intervals.size();
                if (avg > 0) {
                    double variance = 0.0;
                    for (double interval : intervals) {
                        variance += (interval - avg) * (interval - avg);
                    }
                    double deviation = Math.sqrt(variance / intervals.size());
                    double cps = 1000.0 / avg;
                    if (cps >= confDouble("combat.autoclicker-consistent-min-cps", 26.0)
                            && deviation <= confDouble("combat.autoclicker-consistent-max-deviation-ms", 1.0)) {
                        flag(player, data, CheckId.AUTOCLICKER, 1.2,
                                "слишком ровные клики " + formatVl(cps) + " CPS, отклонение "
                                        + formatVl(deviation) + " мс");
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------ блоки

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        PlayerData data = data(player);
        if (!shouldCheck(player, data)) {
            return;
        }
        long now = System.currentTimeMillis();
        data.lastBlockChangeMillis = now;
        if (blockReachViolation(player, data, event.getBlock())) {
            event.setCancelled(true);
            return;
        }
        int tick = Bukkit.getCurrentTick();
        // Бур VoidEnchants (3x3 / 3x3x2) ломает блоки серверным расширением, а не
        // отдельными действиями игрока, поэтому FastBreak такие разрушения не
        // считает. Источник проверяется по факту: уровень чара лежит в PDC
        // инструмента (Namespace voidenchants, ключ drill) — клиент подделать его
        // не может. Отключается: world.fastbreak-server-multiblock-exempt.
        if (serverMultiBlockBreak(player, data, tick)) {
            return;
        }
        if (data.lastBreakTick == tick) {
            data.breaksThisTick++;
        } else {
            data.lastBreakTick = tick;
            data.breaksThisTick = 1;
        }
        data.breaks.addLast(now);
        pruneWindow(data.breaks, now, 1000L, 80);
        int sameTickLimit = confInt("world.fastbreak-same-tick", 4);
        int perSecondLimit = confInt("world.fastbreak-per-second", 28);
        boolean lowTps = currentTps() < confDouble("settings.min-tps-for-movement", 16.0);
        if (!lowTps && (data.breaksThisTick >= sameTickLimit || data.breaks.size() > perSecondLimit)) {
            boolean cancel = flag(player, data, CheckId.FASTBREAK,
                    data.breaksThisTick >= sameTickLimit ? 2.0 : 1.0,
                    data.breaksThisTick >= sameTickLimit
                            ? data.breaksThisTick + " блоков за тик"
                            : data.breaks.size() + " блоков/сек");
            if (cancel || data.breaksThisTick > sameTickLimit + 2) {
                event.setCancelled(true);
            }
        }
    }

    /**
     * Инструмент ломает несколько блоков за одно действие сервером
     * (Бур VoidEnchants I/II). Результат кэшируется на тик: копирование
     * метаданных предмета не нужно дёргать на каждый блок.
     */
    private boolean serverMultiBlockBreak(Player player, PlayerData data, int tick) {
        if (!confBool("world.fastbreak-server-multiblock-exempt", true)) {
            return false;
        }
        if (data.multiBreakTickId == tick) {
            return data.multiBreakTick;
        }
        data.multiBreakTickId = tick;
        data.multiBreakTick = false;
        try {
            ItemStack tool = player.getInventory().getItemInMainHand();
            if (tool == null || tool.getType().isAir() || !tool.hasItemMeta()) {
                return false;
            }
            PersistentDataContainer pdc = tool.getItemMeta().getPersistentDataContainer();
            for (NamespacedKey key : pdc.getKeys()) {
                if (!"drill".equals(key.getKey())) {
                    continue;
                }
                String namespace = key.getNamespace();
                if (!"voidenchants".equals(namespace) && !"voiditems".equals(namespace)) {
                    continue;
                }
                Integer level = pdc.get(key, PersistentDataType.INTEGER);
                if (level != null && level > 0) {
                    data.multiBreakTick = true;
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // Неизвестный предмет — считаем обычным разрушением, проверка работает.
        }
        return false;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        PlayerData data = data(player);
        if (!shouldCheck(player, data)) {
            return;
        }
        long now = System.currentTimeMillis();
        data.lastBlockChangeMillis = now;
        if (blockReachViolation(player, data, event.getBlockPlaced())) {
            event.setCancelled(true);
            return;
        }
        int tick = Bukkit.getCurrentTick();
        if (data.lastPlaceTick == tick) {
            data.placesThisTick++;
        } else {
            data.lastPlaceTick = tick;
            data.placesThisTick = 1;
        }
        data.places.addLast(now);
        pruneWindow(data.places, now, 1000L, 80);
        int sameTickLimit = confInt("world.fastplace-same-tick", 4);
        int perSecondLimit = confInt("world.fastplace-per-second", 28);
        boolean lowTps = currentTps() < confDouble("settings.min-tps-for-movement", 16.0);
        if (!lowTps && (data.placesThisTick >= sameTickLimit || data.places.size() > perSecondLimit)) {
            boolean cancel = flag(player, data, CheckId.FASTPLACE,
                    data.placesThisTick >= sameTickLimit ? 2.0 : 1.0,
                    data.placesThisTick >= sameTickLimit
                            ? data.placesThisTick + " блоков за тик"
                            : data.places.size() + " блоков/сек");
            if (cancel || data.placesThisTick > sameTickLimit + 2) {
                event.setCancelled(true);
                return;
            }
        }
        scaffoldCheck(player, data, event, now);
    }

    private boolean blockReachViolation(Player player, PlayerData data, Block block) {
        Location center = block.getLocation().add(0.5, 0.5, 0.5);
        double distance = player.getEyeLocation().distance(center);
        double tolerance = data.lag.tolerance(player.getPing(), currentTps(), lagLimits);
        // Ядро Purpurbedrock само расширяет допустимую дальность по блокам:
        // ServerPlayerGameMode/ServerGamePacketListenerImpl передают в
        // isWithinBlockInteractionRange множитель bedrockBlockReachTolerance
        // (2.25 по умолчанию) вместо ванильной 1.0. Отменять на 7 блоках то,
        // что сервер разрешает до ~10, — это откат штатных действий игрока,
        // поэтому лимиты масштабируются тем же множителем.
        double coreFactor = coreBridge == null ? 1.0 : coreBridge.blockReachFactor(player);
        double hard = confDouble("world.blockreach-hard-cancel-blocks", 7.0)
                * Math.min(1.2, tolerance) * coreFactor;
        double max = confDouble("world.blockreach-max-blocks", 6.20) * tolerance * coreFactor;
        if (distance > hard) {
            flag(player, data, CheckId.BLOCKREACH, 2.0,
                    "блок на " + formatVl(distance) + " бл. (жёсткий предел " + formatVl(hard) + ")");
            return confBool("settings.enforcement", true);
        }
        if (distance > max) {
            return flag(player, data, CheckId.BLOCKREACH, 1.0,
                    "блок на " + formatVl(distance) + " бл., лимит ~" + formatVl(max));
        }
        return false;
    }

    private void scaffoldCheck(Player player, PlayerData data, BlockPlaceEvent event, long now) {
        Block placed = event.getBlockPlaced();
        Location loc = player.getLocation();
        double centerX = placed.getX() + 0.5;
        double centerZ = placed.getZ() + 0.5;
        double horizontalDistance = Math.sqrt(Math.pow(loc.getX() - centerX, 2) + Math.pow(loc.getZ() - centerZ, 2));
        boolean underFeet = placed.getY() < Math.floor(loc.getY()) && horizontalDistance <= 0.85;
        if (!underFeet || player.isSneaking() || player.getVehicle() != null) {
            return;
        }
        // У Bedrock есть легальное быстрое строительство вперёд, поэтому смотрим
        // только строгую серию блоков строго под ногами на скорости и без взгляда вниз.
        if (data.lastHorizontalBps < confDouble("world.scaffold-min-speed-bps", 3.0)
                || loc.getPitch() > confDouble("world.scaffold-max-pitch", 55.0)) {
            return;
        }
        long window = confLong("world.scaffold-window-ms", 5000L);
        if (now - data.scaffoldWindowStartMillis > window) {
            data.scaffoldWindowStartMillis = now;
            data.scaffoldPlaces = 0;
        }
        data.scaffoldPlaces++;
        if (data.scaffoldPlaces >= confInt("world.scaffold-places-in-window", 4)) {
            boolean cancel = flag(player, data, CheckId.SCAFFOLD, 1.0,
                    "серия блоков строго под ногами на скорости " + formatVl(data.lastHorizontalBps) + " бл/с");
            data.scaffoldPlaces = 0;
            if (cancel) {
                event.setCancelled(true);
            }
        }
    }

    private void pruneWindow(ArrayDeque<Long> deque, long now, long windowMillis, int maxSize) {
        while (!deque.isEmpty() && now - deque.peekFirst() > windowMillis) {
            deque.removeFirst();
        }
        while (deque.size() > maxSize) {
            deque.removeFirst();
        }
    }

    // ------------------------------------------------------------ секундный тик

    private void secondTick() {
        long now = System.currentTimeMillis();
        int tick = Bukkit.getCurrentTick();
        long cleanResetMillis = confLong("settings.vl-reset-clean-seconds", 90L) * 1000L;
        for (Player player : Bukkit.getOnlinePlayers()) {
            PlayerData data = players.get(player.getUniqueId());
            if (data == null) {
                continue;
            }
            data.bypassAll = player.hasPermission("voidanticheat.bypass");
            data.excludedHere = isExcluded(player.getLocation());

            long elapsed = data.lastDecayMillis == 0L ? 1000L : Math.max(0L, now - data.lastDecayMillis);
            data.lastDecayMillis = now;
            for (CheckId check : CheckId.values()) {
                CheckSettings cfg = setting(check);
                ViolationState state = data.state(check);
                state.decay(cfg == null ? 0.12 : cfg.decayPerSecond(), elapsed);
                if (cleanResetMillis > 0L && state.vl() <= 0.0
                        && state.cleanForMillis(now) >= cleanResetMillis) {
                    state.reset();
                }
            }
            if (now - data.lastViolationMillis > 10_000L) {
                data.targets.clear();
            }

            timerCheck(player, data, tick, now);
        }
        // Удаляем данные ушедших, даже если QuitEvent где-то потерялся.
        players.keySet().removeIf(uuid -> Bukkit.getPlayer(uuid) == null);
    }

    private void timerCheck(Player player, PlayerData data, int tick, long nowMillis) {
        if (!shouldCheck(player, data) || inGrace(data) || player.getVehicle() != null) {
            data.timerWindowStartNanos = System.nanoTime();
            data.timerWindowStartTick = tick;
            data.timerPackets = 0;
            data.timerSuspiciousWindows = 0;
            return;
        }
        int windowTicks = Math.max(40, confInt("movement.timer-window-ticks", 100));
        int elapsedTicks = tick - data.timerWindowStartTick;
        if (elapsedTicks < windowTicks) {
            return;
        }
        double ratio = data.timerPackets / (double) Math.max(1, elapsedTicks);
        double allowedRatio = confDouble("movement.timer-ratio", 1.50);
        allowedRatio += Math.min(0.25, Math.max(0, player.getPing() - 200) / 1000.0);
        allowedRatio += Math.max(0.0, 19.5 - currentTps()) * 0.10;
        int extra = confInt("movement.timer-extra-packets", 40);
        boolean suspicious = ratio > allowedRatio && data.timerPackets > elapsedTicks + extra
                && !data.lag.unstable(nowMillis)
                && currentTps() >= confDouble("settings.min-tps-for-movement", 16.0);
        if (suspicious) {
            data.timerSuspiciousWindows = Math.min(6, data.timerSuspiciousWindows + 1);
        } else {
            data.timerSuspiciousWindows = Math.max(0, data.timerSuspiciousWindows - 1);
        }
        if (data.timerSuspiciousWindows >= confInt("movement.timer-windows", 3)) {
            flag(player, data, CheckId.TIMER, ratio > 1.5 ? 2.0 : 1.0,
                    "движений " + data.timerPackets + " за " + elapsedTicks + " тиков (x"
                            + formatVl(ratio) + ")");
            data.timerSuspiciousWindows = 0;
        }
        data.timerWindowStartNanos = System.nanoTime();
        data.timerWindowStartTick = tick;
        data.timerPackets = 0;
    }

    // ------------------------------------------------------------ помощники мира/физики

    private boolean isFinite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    private float angleDifference(float a, float b) {
        float diff = (a - b) % 360f;
        if (diff > 180f) {
            diff -= 360f;
        } else if (diff < -180f) {
            diff += 360f;
        }
        return diff;
    }

    private Block blockAt(Location location, double dy) {
        World world = location.getWorld();
        if (world == null) {
            return null;
        }
        return world.getBlockAt((int) Math.floor(location.getX()),
                (int) Math.floor(location.getY() + dy), (int) Math.floor(location.getZ()));
    }

    private Material typeOf(Block block) {
        return block == null ? Material.AIR : block.getType();
    }

    private boolean isLiquid(Block block) {
        return block != null && block.isLiquid();
    }

    private boolean isHardSolid(Block block) {
        return block != null && block.getType().isOccluding();
    }

    private String name(Block block) {
        return block == null ? "AIR" : block.getType().name();
    }

    private boolean isIce(Material type) {
        return type == Material.ICE || type == Material.FROSTED_ICE
                || type == Material.PACKED_ICE || type == Material.BLUE_ICE;
    }

    private boolean isClimbable(Block block) {
        if (block == null) {
            return false;
        }
        Material type = block.getType();
        return type == Material.LADDER || type == Material.VINE || type == Material.SCAFFOLDING
                || type == Material.TWISTING_VINES || type == Material.WEEPING_VINES
                || type == Material.TWISTING_VINES_PLANT || type == Material.WEEPING_VINES_PLANT
                || type == Material.CAVE_VINES || type == Material.CAVE_VINES_PLANT;
    }

    private boolean isNearWall(Location to) {
        int x = (int) Math.floor(to.getX());
        int y = (int) Math.floor(to.getY() + 0.25);
        int z = (int) Math.floor(to.getZ());
        World world = to.getWorld();
        if (world == null) {
            return false;
        }
        return world.getBlockAt(x + 1, y, z).getType().isSolid()
                || world.getBlockAt(x - 1, y, z).getType().isSolid()
                || world.getBlockAt(x, y, z + 1).getType().isSolid()
                || world.getBlockAt(x, y, z - 1).getType().isSolid();
    }

    private boolean isWallInDirection(Player player, double unitX, double unitZ) {
        Location loc = player.getLocation();
        int x = (int) Math.floor(loc.getX() + unitX * 0.85);
        int z = (int) Math.floor(loc.getZ() + unitZ * 0.85);
        World world = loc.getWorld();
        if (world == null) {
            return false;
        }
        int y = (int) Math.floor(loc.getY());
        return world.getBlockAt(x, y, z).getType().isSolid()
                || world.getBlockAt(x, y + 1, z).getType().isSolid();
    }

    private PotionEffect effect(Player player, PotionEffectType type) {
        return type == null ? null : player.getPotionEffect(type);
    }

    private int effectAmplifier(Player player, PotionEffectType type) {
        PotionEffect effect = effect(player, type);
        return effect == null ? -1 : effect.getAmplifier();
    }

    private double attributeValue(Player player, Attribute attribute, double fallback) {
        if (attribute == null) {
            return fallback;
        }
        AttributeInstance instance = player.getAttribute(attribute);
        return instance == null ? fallback : instance.getValue();
    }

    private int enchantLevel(ItemStack item, Enchantment enchantment) {
        if (item == null || enchantment == null || item.getItemMeta() == null) {
            return 0;
        }
        return item.getEnchantmentLevel(enchantment);
    }

    // ------------------------------------------------------------ команды

    private void tell(CommandSender sender, String key, String... pairs) {
        String text = getConfig().getString("messages." + key, "");
        if (text == null || text.isEmpty()) {
            return;
        }
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            text = text.replace(pairs[i], pairs[i + 1]);
        }
        String prefix = getConfig().getString("messages.prefix", "");
        sender.sendMessage(comp((prefix == null ? "" : prefix) + text));
    }

    private boolean admin(CommandSender sender) {
        return sender.hasPermission("voidanticheat.admin");
    }

    /** /vac check &lt;id&gt; [on|off|status] — переключение проверки без пересборки jar. */
    private boolean commandCheck(CommandSender sender, String[] args) {
        if (args.length < 2) {
            usage(sender, "check");
            sender.sendMessage(comp("&7Проверки: &f" + checkKeyList()));
            return true;
        }
        CheckId check = CheckId.byKey(args[1]);
        if (check == null) {
            sender.sendMessage(comp("&cНет такой проверки: &f" + args[1]));
            sender.sendMessage(comp("&7Есть: &f" + checkKeyList()));
            return true;
        }
        CheckSettings current = setting(check);
        boolean enabled = current != null && current.enabled();
        if (args.length >= 3) {
            String mode = args[2].toLowerCase(Locale.ROOT);
            if ("on".equals(mode) || "enable".equals(mode)) {
                enabled = true;
            } else if ("off".equals(mode) || "disable".equals(mode)) {
                enabled = false;
            } else if (!"status".equals(mode)) {
                usage(sender, "check");
                return true;
            }
        }
        if (current != null) {
            // Временное состояние: переживает /vac reload? Нет — reload читает
            // config.yml заново. Это осознанно: файл остаётся источником правды.
            settings.put(check, new CheckSettings(enabled, current.maxVl(),
                    current.decayPerSecond(), current.setbackVl(), current.cancelVl(),
                    current.punishVl(), current.bufferLimit(), current.punishCommands()));
        }
        sender.sendMessage(comp("&7Проверка &f" + check.key() + "&7 (&f" + check.display()
                + "&7): " + (enabled ? "&aвключена" : "&cвыключена")));
        if (!enabled) {
            sender.sendMessage(comp("&8Буфер и VL этой проверки больше не растут; "
                    + "уже накопленные VL затухнут сами."));
        }
        return true;
    }

    private String checkKeyList() {
        StringBuilder builder = new StringBuilder();
        for (CheckId check : CheckId.values()) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(check.key());
        }
        return builder.toString();
    }

    /** /vac world ... — миры, где античит молчит. Работает сразу, без reload. */
    private boolean commandWorld(CommandSender sender, String[] args) {
        String action = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "list";
        switch (action) {
            case "list" -> {
                List<String> excluded = getConfig().getStringList("settings.excluded-worlds");
                List<String> only = getConfig().getStringList("settings.only-worlds");
                sender.sendMessage(comp("&7Исключённые миры (&f" + excluded.size() + "&7): &f"
                        + (excluded.isEmpty() ? "—" : String.join(", ", excluded))));
                sender.sendMessage(comp("&7Только эти миры (&f" + only.size() + "&7): &f"
                        + (only.isEmpty() ? "— (проверяются все)" : String.join(", ", only))));
                sender.sendMessage(comp("&8Маски: world, arena-*, *_test, lobby?. "
                        + "Переключение сбрасывает данные игроков в затронутых мирах."));
                return true;
            }
            case "add" -> {
                if (args.length < 3) {
                    usage(sender, "world");
                    return true;
                }
                String pattern = args[2];
                List<String> excluded = new ArrayList<>(getConfig().getStringList("settings.excluded-worlds"));
                if (worldMatchesAny(pattern.toLowerCase(Locale.ROOT), excluded)) {
                    sender.sendMessage(comp("&7Уже в списке: &f" + pattern));
                    return true;
                }
                excluded.add(pattern);
                getConfig().set("settings.excluded-worlds", excluded);
                saveConfig();
                refreshExclusions();
                sender.sendMessage(comp("&aДобавлено в исключения: &f" + pattern
                        + "&8 (сохранено в config.yml)"));
                return true;
            }
            case "remove", "del" -> {
                if (args.length < 3) {
                    usage(sender, "world");
                    return true;
                }
                List<String> excluded = new ArrayList<>(getConfig().getStringList("settings.excluded-worlds"));
                if (!excluded.remove(args[2])) {
                    sender.sendMessage(comp("&cНет в списке: &f" + args[2]));
                    return true;
                }
                getConfig().set("settings.excluded-worlds", excluded);
                saveConfig();
                refreshExclusions();
                sender.sendMessage(comp("&aУбрано из исключений: &f" + args[2]));
                return true;
            }
            case "only" -> {
                if (args.length < 3) {
                    usage(sender, "world");
                    return true;
                }
                List<String> only = new ArrayList<>();
                for (int i = 2; i < args.length; i++) {
                    only.add(args[i]);
                }
                getConfig().set("settings.only-worlds", only);
                saveConfig();
                refreshExclusions();
                sender.sendMessage(comp("&aПроверять только: &f" + String.join(", ", only)));
                return true;
            }
            case "clear-only" -> {
                getConfig().set("settings.only-worlds", new ArrayList<String>());
                saveConfig();
                refreshExclusions();
                sender.sendMessage(comp("&aWhitelist миров очищен: проверяются все миры"));
                return true;
            }
            default -> {
                usage(sender, "world");
                return true;
            }
        }
    }

    /**
     * Смена исключений должна сразу влиять на игроков: у тех, кто оказался в
     * исключённом мире, обнуляем состояние движения, иначе после возврата из
     * «тихого» мира останется накопленная скорость/воздух и проверка сработает
     * на первом же шаге.
     */
    private void refreshExclusions() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            PlayerData data = players.get(player.getUniqueId());
            if (data == null) {
                continue;
            }
            boolean excluded = isExcluded(player.getLocation());
            if (excluded != data.excludedHere) {
                data.excludedHere = excluded;
                data.resetMovement(player.getLocation(), Bukkit.getCurrentTick(),
                        System.nanoTime(), player.isOnGround());
                data.clearSustainAll();
            }
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!admin(sender)) {
            tell(sender, "no-permission");
            return true;
        }
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload" -> {
                reloadConfig();
                loadCheckSettings();
                loadLagLimits();
                coreCombatByWorld.clear();
                if (antiEsp != null) {
                    antiEsp.reset();
                    antiEsp.stop(false);
                    antiEsp.configure(
                            confBool("anti-esp.enabled", false),
                            confInt("anti-esp.interval-ticks", 10),
                            confInt("anti-esp.join-grace-ticks", 60),
                            confDouble("anti-esp.min-distance", 2.0),
                            confDouble("anti-esp.max-distance", 96.0),
                            confBool("anti-esp.hide-spectators", true),
                            confBool("anti-esp.hide-invisible", true),
                            confBool("anti-esp.only-if-core-inactive", true));
                }
                // Буферы/VL не сбрасываем: reload не должен прощать нарушения.
                tell(sender, "reloaded");
                return true;
            }
            case "check" -> {
                // /vac check <id> [on|off|status] — включение проверки на лету,
                // без правки jar и без рестарта. Значение живёт до перезапуска
                // или /vac reload (тогда снова берётся из config.yml).
                return commandCheck(sender, args);
            }
            case "world" -> {
                // /vac world list | add <маска> | remove <маска> | only <маска...> | clear-only
                return commandWorld(sender, args);
            }
            case "alerts" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(comp("&7Команда alerts доступна только игроку."));
                    return true;
                }
                if (alertsOff.remove(player.getUniqueId())) {
                    tell(sender, "alerts-on");
                } else {
                    alertsOff.add(player.getUniqueId());
                    tell(sender, "alerts-off");
                }
                return true;
            }
            case "info" -> {
                if (args.length < 2) {
                    usage(sender, "info");
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    tell(sender, "player-not-found");
                    return true;
                }
                PlayerData data = data(target);
                tell(sender, "info-header",
                        "{player}", target.getName(),
                        "{bedrock}", String.valueOf(data.bedrock),
                        "{input}", data.inputMode,
                        "{device}", data.deviceOs,
                        "{ping}", String.valueOf(target.getPing()));
                tell(sender, "info-lag",
                        "{interval}", formatVl(data.lag.intervalMs()),
                        "{jitter}", formatVl(data.lag.deviationMs()),
                        "{tolerance}", String.format(Locale.ROOT, "%.2f",
                                data.lag.tolerance(target.getPing(), currentTps(), lagLimits)));
                List<CheckId> active = new ArrayList<>();
                for (CheckId check : CheckId.values()) {
                    if (data.vl(check) > 0) {
                        active.add(check);
                    }
                }
                if (active.isEmpty()) {
                    tell(sender, "info-none");
                } else {
                    active.sort(Comparator.comparingDouble((CheckId c) -> data.vl(c)).reversed());
                    for (CheckId check : active) {
                        tell(sender, "info-line",
                                "{check}", check.display(),
                                "{vl}", formatVl(data.vl(check)),
                                "{max_vl}", formatVl(setting(check).maxVl()),
                                "{buffer}", formatVl(data.state(check).buffer()));
                    }
                }
                if (data.lastCheck != null) {
                    sender.sendMessage(comp("&7Последнее: &f" + data.lastCheck.display()
                            + " &8• &7" + data.lastDetails));
                }
                return true;
            }
            case "reset" -> {
                if (args.length < 2) {
                    usage(sender, "reset");
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    tell(sender, "player-not-found");
                    return true;
                }
                PlayerData data = data(target);
                String scope = "";
                if (args.length >= 3) {
                    CheckId check = CheckId.byKey(args[2]);
                    if (check == null) {
                        tell(sender, "check-not-found");
                        return true;
                    }
                    data.state(check).reset();
                    scope = " (" + check.display() + ")";
                } else {
                    data.clearViolations();
                }
                tell(sender, "reset-done", "{player}", target.getName(), "{scope}", scope);
                return true;
            }
            case "setback" -> {
                if (args.length < 2) {
                    usage(sender, "setback");
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    tell(sender, "player-not-found");
                    return true;
                }
                PlayerData data = data(target);
                if (setbackPlayer(target, data)) {
                    tell(sender, "setback-done", "{player}", target.getName());
                } else {
                    tell(sender, "no-safe-location");
                }
                return true;
            }
            case "help" -> {
                if (args.length >= 2) {
                    String topic = args[1].toLowerCase(Locale.ROOT);
                    if (!getConfig().isList("messages.command." + topic)) {
                        tell(sender, "unknown-subcommand", "{sub}", topic);
                        sendHelp(sender);
                    } else {
                        sendHelp(sender, topic);
                    }
                    return true;
                }
                sendHelp(sender);
                return true;
            }
            default -> {
                if (!sub.isEmpty()) {
                    tell(sender, "unknown-subcommand", "{sub}", sub);
                    sendHelp(sender);
                    return true;
                }
                tell(sender, "status",
                        "{state}", getConfig().getString(confBool("settings.enabled", true)
                                ? "messages.state-on" : "messages.state-off", ""),
                        "{floodgate}", floodgate.available() ? "подключён" : "fallback",
                        "{tracked}", String.valueOf(players.size()),
                        "{enforcement}", confBool("settings.enforcement", true) ? "вкл" : "только алерты",
                        "{tps}", formatVl(currentTps()));
                return true;
            }
        }
    }

    private void sendHelp(CommandSender sender) {
        sendLines(sender, getConfig().getStringList("messages.help"));
    }

    /**
     * Собственная справка одной команды: {@code messages.command.<команда>} —
     * список строк (описание + примеры + право). Так у каждой подкоманды есть
     * свой текст в конфиге, а не общая простыня.
     */
    private void sendHelp(CommandSender sender, String sub) {
        List<String> lines = getConfig().getStringList("messages.command." + sub);
        if (lines.isEmpty()) {
            sendHelp(sender);
            return;
        }
        sendLines(sender, lines);
    }

    private void sendLines(CommandSender sender, List<String> lines) {
        String prefix = getConfig().getString("messages.prefix", "");
        for (String line : lines) {
            sender.sendMessage(comp((prefix == null ? "" : prefix) + line));
        }
    }

    /**
     * Ошибка «не хватает аргументов»: говорим, чего не хватает, и сразу
     * печатаем справку этой команды. Раньше вместо этого выводилось
     * «игрок не найден», что сбивало с толку.
     */
    private void usage(CommandSender sender, String sub) {
        tell(sender, "usage-format", "{command}", sub, "{usage}", usageOf(sub));
        sendHelp(sender, sub);
    }

    private String usageOf(String sub) {
        return getConfig().getString("messages.usage." + sub, "/vac " + sub);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        // Ничего не показываем тем, кто не может пользоваться командой, и
        // никогда не показываем аргументы сверх поддерживаемых: иначе клиент
        // рисует подсказки там, где команда их не примет.
        if (!admin(sender) || args.length == 0) {
            return List.of();
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);

        List<String> options = args.length == 1
                ? List.of("info", "alerts", "reset", "setback", "reload", "check", "world", "help")
                : suggestionsFor(sub, args);

        if (options.isEmpty()) {
            return List.of();
        }
        // Дедупликация + сортировка + префиксный фильтр: у Bedrock-клиента окно
        // подсказок маленькое, порядок должен быть предсказуемым.
        List<String> out = new ArrayList<>();
        for (String option : options) {
            if (option != null && option.toLowerCase(Locale.ROOT).startsWith(prefix) && !out.contains(option)) {
                out.add(option);
            }
        }
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out.size() > 100 ? new ArrayList<>(out.subList(0, 100)) : out;
    }

    /** Подсказки для конкретной подкоманды и позиции аргумента. */
    private List<String> suggestionsFor(String sub, String[] args) {
        String action = args[1].toLowerCase(Locale.ROOT);
        return switch (sub) {
            // Ровно один аргумент — ник. Дальше подсказывать нечего.
            case "info", "setback" -> args.length == 2 ? onlinePlayerNames() : List.of();
            // Без аргументов.
            case "alerts", "reload" -> List.of();
            // /vac help <команда>
            case "help" -> args.length == 2 ? List.of("info", "alerts", "reset", "setback",
                    "check", "world", "reload", "help") : List.of();
            case "reset" -> switch (args.length) {
                case 2 -> onlinePlayerNames();
                case 3 -> checkKeys();
                default -> List.of();
            };
            case "check" -> switch (args.length) {
                case 2 -> checkKeys();
                case 3 -> {
                    // Подсказываем «противоположное» текущему состоянию первым.
                    CheckId known = CheckId.byKey(args[1]);
                    CheckSettings cfg = known == null ? null : setting(known);
                    boolean on = cfg != null && cfg.enabled();
                    yield on ? List.of("off", "on", "status") : List.of("on", "off", "status");
                }
                default -> List.of();
            };
            case "world" -> switch (args.length) {
                case 2 -> List.of("list", "add", "remove", "only", "clear-only");
                case 3 -> switch (action) {
                    // remove — только то, что реально есть в списке.
                    case "remove", "del" -> excludedWorlds();
                    // only — имена существующих миров.
                    case "only" -> allWorldNames();
                    // add — существующие миры + готовые маски как подсказка.
                    case "add" -> concatLists(allWorldNames(),
                            List.of("arena-*", "*_test", "lobby?", "minigames-*"));
                    default -> List.of();
                };
                default -> List.of();
            };
            default -> List.of();
        };
    }

    private List<String> onlinePlayerNames() {
        List<String> names = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            names.add(player.getName());
        }
        return names;
    }

    private List<String> checkKeys() {
        List<String> keys = new ArrayList<>();
        for (CheckId check : CheckId.values()) {
            keys.add(check.key());
        }
        return keys;
    }

    private List<String> excludedWorlds() {
        return new ArrayList<>(getConfig().getStringList("settings.excluded-worlds"));
    }

    private List<String> allWorldNames() {
        List<String> names = new ArrayList<>();
        for (World world : Bukkit.getWorlds()) {
            names.add(world.getName());
        }
        return names;
    }

    private static List<String> concatLists(List<String> first, List<String> second) {
        List<String> merged = new ArrayList<>(first);
        merged.addAll(second);
        return merged;
    }
}
