package ru.voidpe.anticheat;

import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.logging.Logger;

/**
 * Мост к встроенному в ядро Purpurbedrock анализатору устройств
 * ({@code org.purpurmc.purpur.bedrock.BedrockPlaceholderHook}).
 *
 * <p>Зачем отдельный класс, а если в ядре уже есть {@code BedrockBridgeInfo}?
 * Потому что {@code BedrockBridgeInfo} — часть <b>другого</b> плагина
 * ({@code PurpurBedrockAC}), а не публичный API ядра. Тянуть класс из чужого
 * jar нельзя: его может не быть. Здесь используется ровно тот же проверенный
 * шаблон, что и в {@code BedrockBridgeInfo}: рефлексия по
 * {@code resolveOnline(Player, String)}, один раз на класс, все ошибки глушатся.</p>
 *
 * <p><b>Ловушка ядра, которую обязательно надо учитывать.</b>
 * {@code resolveOnline} отвечает не только реальными данными Geyser-сессии, но и
 * <i>значениями по умолчанию</i>:</p>
 * <ul>
 *   <li>{@code is_bedrock} возвращает {@code "true"} для всех, если включён
 *       {@code bedrock.only-allow-bedrock} (по умолчанию включён) — это признак
 *       «сервер только для Bedrock», а НЕ «этот игрок Bedrock»;</li>
 *   <li>{@code device} без сессии отдаёт {@code bedrock.placeholders.device.android}
 *       (по умолчанию «Android»), {@code device_id} — {@code "android"};</li>
 *   <li>{@code protocol} без сессии — {@code "0"}, {@code device_model} — пустую
 *       строку.</li>
 * </ul>
 * <p>Поэтому «данные реальные» проверяется только по значениям, которые
 * невозможно получить без живой Geyser-сессии: {@code protocol > 0},
 * непустой {@code device_model} или непустой {@code xuid}. Если ни одно не
 * подтвердилось — {@link #deviceId(Player)} вернёт {@code "unknown"}, и античит
 * обязан работать на безопасных (более мягких) значениях, а не считать
 * отсутствие данных нарушением.</p>
 */
final class BedrockCoreBridge {

    /** Категории устройства, которые отдаёт ядро (см. classifyDeviceId). */
    static final String DEVICE_UNKNOWN = "unknown";

    private final Logger log;
    private volatile Method resolveOnline;
    private volatile boolean checked;
    private volatile boolean reported;
    private volatile boolean warnedWeakBlockReach;

    BedrockCoreBridge(Logger log) {
        this.log = log;
    }

    private void init() {
        if (checked) {
            return;
        }
        checked = true;
        try {
            Class<?> hook = Class.forName("org.purpurmc.purpur.bedrock.BedrockPlaceholderHook");
            Method method = hook.getMethod("resolveOnline", Player.class, String.class);
            method.setAccessible(true);
            resolveOnline = method;
        } catch (Throwable failure) {
            resolveOnline = null;
            if (!reported) {
                reported = true;
                log.info("Ядро без встроенного анализатора устройств ("
                        + failure.getClass().getSimpleName()
                        + ") — данные устройства берутся из Floodgate, "
                        + "неизвестное состояние считается безопасным");
            }
        }
    }

    boolean available() {
        init();
        return resolveOnline != null;
    }

    private String query(Player player, String key) {
        init();
        Method method = resolveOnline;
        if (method == null || player == null) {
            return null;
        }
        try {
            Object value = method.invoke(null, player, key);
            return value == null ? null : value.toString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Есть ли для игрока настоящие данные Geyser-сессии.
     * Только они дают право строить на устройстве какие-либо выводы.
     */
    boolean sessionDataAvailable(Player player) {
        if (player == null || !available()) {
            return false;
        }
        String protocol = query(player, "protocol");
        if (protocol != null) {
            try {
                if (Integer.parseInt(protocol.trim()) > 0) {
                    return true;
                }
            } catch (NumberFormatException ignored) {
                // не число — считаем отсутствием данных
            }
        }
        String model = query(player, "device_model");
        if (model != null && !model.isBlank()) {
            return true;
        }
        String xuid = query(player, "xuid");
        return xuid != null && !xuid.isBlank();
    }

    /**
     * Машиночитаемая категория устройства:
     * {@code windows}, {@code android}, {@code ios}, {@code xbox},
     * {@code ps4_5}, {@code switch}, {@code phone_kbm} или {@code unknown}.
     *
     * <p>{@code unknown} возвращается, когда данных сессии нет: значение
     * {@code "android"} от ядра в этом случае — это fallback, а не факт.</p>
     */
    String deviceId(Player player) {
        if (player == null || !available() || !sessionDataAvailable(player)) {
            return DEVICE_UNKNOWN;
        }
        String raw = query(player, "device_id");
        if (raw == null) {
            return DEVICE_UNKNOWN;
        }
        String id = raw.trim().toLowerCase(Locale.ROOT);
        return switch (id) {
            case "windows", "android", "ios", "xbox", "ps4_5", "switch", "phone_kbm" -> id;
            default -> DEVICE_UNKNOWN;
        };
    }

    /**
     * Публичный доступ к произвольному ключу ядра — для точного определителя
     * ввода ({@link DeviceProbe}). Возвращает {@code null}, если сессии нет или
     * ядро такого ключа не знает.
     *
     * <p>Список ключей ядра: {@code platform}, {@code input}, {@code device_id},
     * {@code device_model}, {@code ui}, {@code protocol}, {@code xuid},
     * {@code version}, {@code ping}.</p>
     */
    String queryPublic(Player player, String key) {
        if (player == null || key == null || !available()) {
            return null;
        }
        return query(player, key);
    }

    /**
     * Пинг из Geyser-сессии, мс. {@code -1} — данных нет.
     * {@code Bukkit#getPing()} у Bedrock через Geyser часто 0, поэтому это
     * единственный честный источник; при {@code -1} вызывающий код должен
     * опираться на джиттер move-событий ({@link LagCompensation}).
     */
    int ping(Player player) {
        if (player == null || !available() || !sessionDataAvailable(player)) {
            return -1;
        }
        String raw = query(player, "ping");
        if (raw == null) {
            return -1;
        }
        try {
            int ping = Integer.parseInt(raw.trim());
            return ping > 0 ? ping : -1;
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    /**
     * Во сколько раз ядро расширяет допустимую дальность по БЛОКАМ.
     *
     * <p>Ядро подменяет ванильную проверку в {@code ServerPlayerGameMode} и
     * {@code ServerGamePacketListenerImpl}:
     * {@code isWithinBlockInteractionRange(pos, 1.0)} → {@code (..., reachAllowance)},
     * где {@code reachAllowance = bedrockBlockReachTolerance} (= 2.25 по умолчанию)
     * при включённом {@code bedrockPreventGhostBlocks}. Это множитель к атрибуту
     * {@code block_interaction_range} (4.5), то есть ядро разрешает добывать и
     * ставить блоки примерно до 10 блоков.</p>
     *
     * <p>Античит, который отменяет блок на 7 блоках, отменял бы то, что сервер
     * разрешает. Поэтому здесь возвращается тот же множитель.</p>
     *
     * <p><b>Важно:</b> при значении 2.25 проверка BlockReach фактически
     * выключается — это следствие настройки ядра, а не античита. Правильное
     * место для ужесточения — {@code bedrock.anti-ghost-blocks.reach-tolerance}
     * в конфиге ядра.</p>
     *
     * @return множитель ≥ 1.0; 1.0 — ядро дальность не расширяет
     */
    double blockReachFactor(Player player) {
        if (player == null || player.getWorld() == null) {
            return 1.0;
        }
        try {
            Object handle = player.getWorld().getClass().getMethod("getHandle").invoke(player.getWorld());
            Object worldConfig = handle.getClass().getField("purpurConfig").get(handle);
            Object enabled = worldConfig.getClass().getField("bedrockPreventGhostBlocks").get(worldConfig);
            if (!(enabled instanceof Boolean on) || !on) {
                return 1.0;
            }
            Object tolerance = worldConfig.getClass().getField("bedrockBlockReachTolerance").get(worldConfig);
            if (!(tolerance instanceof Double factor) || factor <= 1.0) {
                return 1.0;
            }
            if (!warnedWeakBlockReach) {
                warnedWeakBlockReach = true;
                log.warning(String.format(Locale.ROOT,
                        "Ядро расширяет дальность по блокам в x%.2f: BlockReach будет ослаблен. "
                                + "Ужесточать надо в конфиге ядра: "
                                + "bedrock.anti-ghost-blocks.reach-tolerance = 1.0", factor));
            }
            return factor;
        } catch (Throwable notThisCore) {
            return 1.0;
        }
    }

    /** Мобильная ОС или телефон с клавиатурой/мышью — это сенсорный класс ввода. */
    static boolean isTouchFamily(String deviceId) {
        return "android".equals(deviceId) || "ios".equals(deviceId) || "phone_kbm".equals(deviceId);
    }

    /** Настольная/консольная категория — здесь строгие лимиты оправданны. */
    static boolean isDesktopOrConsole(String deviceId) {
        return "windows".equals(deviceId) || "xbox".equals(deviceId)
                || "ps4_5".equals(deviceId) || "switch".equals(deviceId);
    }
}
