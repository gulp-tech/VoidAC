package ru.voidpe.anticheat;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Floodgate через рефлексию: плагин не падает без Floodgate и не тянет
 * compile-time зависимость. Нужны только isFloodgatePlayer и метаданные ввода.
 */
public final class FloodgateHook {

    private final Logger log;
    private Object api;
    private Method isFloodgatePlayer;
    private Method getPlayer;

    /**
     * Найден ли Floodgate хотя бы на уровне плагина (без API).
     * Нужно, чтобы отличить «плагина нет» от «плагин есть, но ещё не поднялся».
     */
    private boolean pluginPresent;

    /** Сколько раз пробовали достучаться до API. */
    private int attempts;

    public FloodgateHook(Logger log) {
        this.log = log;
        init();
    }

    /**
     * Повторная попытка подключиться к API.
     *
     * <p><b>Зачем.</b> На реальном сервере порядок включения оказался таким:
     * VoidAntiCheat включается на 3 секунды РАНЬШЕ Geyser-Spigot (наш плагин
     * 17:31:16, Geyser 17:31:18). Floodgate поднимается вместе с Geyser, то
     * есть в момент нашего {@code onEnable} его API ещё нет — и плагин навсегда
     * оставался в fallback-режиме, хотя Floodgate в системе был. Повторная
     * попытка из периодической задачи это исправляет.</p>
     *
     * @return {@code true}, если API теперь доступна
     */
    public boolean retry() {
        if (available()) {
            return true;
        }
        attempts++;
        if (!pluginPresent) {
            // Плагина нет вообще (например, сервер без Geyser) — не спамим.
            return false;
        }
        init();
        if (available()) {
            log.info("Floodgate подключён со " + attempts + "-й попытки (плагин поднялся позже)");
            return true;
        }
        return false;
    }

    /** Сколько попыток переподключения было. */
    public int attempts() {
        return attempts;
    }

    private void init() {
        Plugin plugin = Bukkit.getPluginManager().getPlugin("Floodgate");
        if (plugin == null) {
            plugin = Bukkit.getPluginManager().getPlugin("floodgate");
        }
        if (plugin == null) {
            pluginPresent = false;
            log.warning("Floodgate не найден: VoidAntiCheat будет использовать fallback из config.yml");
            return;
        }
        pluginPresent = true;
        try {
            Class<?> apiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi",
                    true, plugin.getClass().getClassLoader());
            Method getInstance = findMethod(apiClass, "getInstance");
            api = getInstance == null ? null : getInstance.invoke(null);
            if (api != null) {
                isFloodgatePlayer = findMethod(api.getClass(), "isFloodgatePlayer", UUID.class);
                getPlayer = findMethod(api.getClass(), "getPlayer", UUID.class);
                if (isFloodgatePlayer != null) {
                    log.info("Floodgate подключён: проверяются только Bedrock-игроки");
                    return;
                }
            }
            log.warning("Floodgate найден, но API недоступна: включён fallback из config.yml");
        } catch (Throwable t) {
            log.warning("Floodgate hook не удался: " + rootMessage(t));
        }
    }

    public boolean available() {
        return api != null && isFloodgatePlayer != null;
    }

    public boolean isBedrock(UUID uuid) {
        if (!available()) {
            return false;
        }
        try {
            Object value = isFloodgatePlayer.invoke(api, uuid);
            return Boolean.TRUE.equals(value);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** KEYBOARD_MOUSE, TOUCH, CONTROLLER, UNKNOWN и т.п. */
    public String inputMode(UUID uuid) {
        return enumValue(uuid, "getInputMode");
    }

    /** GOOGLE, IOS, UWP, XBOX, PS4, NX, UNKNOWN и т.п. */
    public String deviceOs(UUID uuid) {
        return enumValue(uuid, "getDeviceOs");
    }

    private String enumValue(UUID uuid, String getterName) {
        if (api == null || getPlayer == null) {
            return "UNKNOWN";
        }
        try {
            Object floodgatePlayer = getPlayer.invoke(api, uuid);
            if (floodgatePlayer == null) {
                return "UNKNOWN";
            }
            Method getter = findMethod(floodgatePlayer.getClass(), getterName);
            Object value = getter == null ? null : getter.invoke(floodgatePlayer);
            if (value == null) {
                return "UNKNOWN";
            }
            try {
                Method name = value.getClass().getMethod("name");
                return String.valueOf(name.invoke(value)).toUpperCase(Locale.ROOT);
            } catch (Throwable ignored) {
                return String.valueOf(value).toUpperCase(Locale.ROOT);
            }
        } catch (Throwable ignored) {
            return "UNKNOWN";
        }
    }

    private static String rootMessage(Throwable t) {
        Throwable current = t;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    static Method findMethod(Class<?> type, String name, Class<?>... params) {
        Class<?> current = type;
        while (current != null) {
            try {
                Method method = current.getMethod(name, params);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            }
        }
        for (Class<?> iface : type.getInterfaces()) {
            try {
                Method method = iface.getMethod(name, params);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
                // следующий интерфейс
            }
        }
        return null;
    }
}
