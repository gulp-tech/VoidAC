package ru.voidpe.anticheat;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Распознавание «инструментов с серверным мультиблоком» — бур, кирка-3x3 и
 * подобные чары, которые ломают несколько блоков за одно действие игрока.
 *
 * <p><b>Зачем это нужно античиту.</b> Такой инструмент создаёт 9–18 настоящих
 * {@code BlockBreakEvent} в одном тике от имени одного игрока. С точки зрения
 * любой проверки на скорость это неотличимо от Nuker: тот тоже ломает много
 * блоков сразу. Разница только в источнике — блоки ломает серверное расширение
 * по метке на предмете, а не клиент по своей воле.</p>
 *
 * <p><b>Почему прежняя проверка не работала.</b> Она была жёстко привязана к
 * двум пространствам имён ({@code voidenchants}, {@code voiditems}) и к типу
 * {@code INTEGER}. Если чар называется иначе, лежит в другом плагине или хранит
 * уровень строкой — исключение не срабатывало, и игрок получал ложные улики
 * FastBreak/AutoMine. Теперь всё это настраивается в конфиге.</p>
 *
 * <p><b>Что именно читается.</b> Только метки на самом предмете
 * ({@code PersistentDataContainer}). Клиент их подделать не может: PDC живёт
 * на сервере и приходит в пакете только как обычный предмет. Поэтому
 * «надеть» метку бура на алмазную кирку через чит-клиент невозможно.</p>
 */
final class ToolExempt {

    /** Настройки исключения, читаются из {@code world.drill-exempt.*}. */
    record Settings(
            boolean enabled,
            Set<String> namespaces,
            Set<String> keys,
            Set<String> nameContains,
            int maxLevel,
            boolean requireAnyValue
    ) {
        static Settings disabled() {
            return new Settings(false, Set.of(), Set.of(), Set.of(), 0, false);
        }

        static Settings of(boolean enabled, List<String> namespaces, List<String> keys,
                           List<String> nameContains, int maxLevel) {
            return new Settings(enabled,
                    normalize(namespaces),
                    normalize(keys),
                    normalize(nameContains),
                    Math.max(0, maxLevel),
                    true);
        }

        private static Set<String> normalize(List<String> values) {
            Set<String> set = new HashSet<>();
            if (values != null) {
                for (String value : values) {
                    if (value != null && !value.isBlank()) {
                        set.add(value.trim().toLowerCase(Locale.ROOT));
                    }
                }
            }
            return set;
        }

        /** Пустой список пространств имён означает «любое». */
        boolean namespaceAllowed(String namespace) {
            return namespaces.isEmpty() || namespaces.contains(namespace.toLowerCase(Locale.ROOT));
        }
    }

    /** Кэш на тик: у бура все события приходят подряд, читать PDC каждый раз незачем. */
    private int cachedTick = Integer.MIN_VALUE;
    private boolean cachedResult;

    /**
     * Ломает ли этот предмет несколько блоков за одно действие игрока.
     *
     * <p>Результат кэшируется на тик: у бура 9–18 событий за тик, и каждое
     * чтение PDC — это копирование метаданных предмета.</p>
     */
    boolean multiBlockTool(ItemStack tool, Settings settings, int currentTick) {
        if (settings == null || !settings.enabled()) {
            return false;
        }
        if (cachedTick == currentTick) {
            return cachedResult;
        }
        cachedTick = currentTick;
        cachedResult = inspect(tool, settings);
        return cachedResult;
    }

    /** Сброс кэша при смене предмета/тике — вызывается аккуратно, чтобы не протух. */
    void invalidate() {
        cachedTick = Integer.MIN_VALUE;
    }

    private boolean inspect(ItemStack tool, Settings settings) {
        if (tool == null || tool.getType().isAir()) {
            return false;
        }
        try {
            if (!tool.hasItemMeta()) {
                return byName(tool, settings);
            }
            var meta = tool.getItemMeta();
            if (meta == null) {
                return byName(tool, settings);
            }
            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            // Ключи предмета, а не всего мира: у бура их единицы.
            for (NamespacedKey key : pdc.getKeys()) {
                if (!settings.namespaceAllowed(key.getNamespace())) {
                    continue;
                }
                if (!settings.keys().isEmpty()
                        && !settings.keys().contains(key.getKey().toLowerCase(Locale.ROOT))) {
                    continue;
                }
                if (hasPositiveValue(pdc, key, settings)) {
                    return true;
                }
            }
            return byName(tool, settings);
        } catch (Throwable ignored) {
            // Неизвестный предмет/реализация PDC — считаем обычным инструментом,
            // проверки продолжают работать. Ошибка не должна отключать античит.
            return false;
        }
    }

    /**
     * Уровень чара в PDC. Тип заранее неизвестен: разные плагины пишут
     * {@code Integer}, {@code String} или {@code Byte}. Перебираем известные
     * варианты, потому что {@code get} с неверным типом возвращает null.
     */
    private boolean hasPositiveValue(PersistentDataContainer pdc, NamespacedKey key, Settings settings) {
        Integer integer = tryGet(pdc, key, PersistentDataType.INTEGER);
        if (integer != null) {
            return levelOk(integer, settings);
        }
        Byte bytes = tryGet(pdc, key, PersistentDataType.BYTE);
        if (bytes != null) {
            return levelOk(bytes.intValue(), settings);
        }
        Short shorts = tryGet(pdc, key, PersistentDataType.SHORT);
        if (shorts != null) {
            return levelOk(shorts.intValue(), settings);
        }
        Long longs = tryGet(pdc, key, PersistentDataType.LONG);
        if (longs != null) {
            return levelOk(longs.intValue(), settings);
        }
        String text = tryGet(pdc, key, PersistentDataType.STRING);
        if (text != null) {
            try {
                return levelOk(Integer.parseInt(text.trim()), settings);
            } catch (NumberFormatException notANumber) {
                // Метка есть, но это не уровень (например, "true"). Если
                // значение непустое — считаем инструментом с расширением:
                // сам факт метки уже означает серверную механику.
                return !text.isBlank();
            }
        }
        // Метка есть, но значение другого типа — проверяем «есть ли вообще».
        return settings.requireAnyValue() && pdc.has(key);
    }

    private static <T, Z> Z tryGet(PersistentDataContainer pdc, NamespacedKey key,
                                   PersistentDataType<T, Z> type) {
        try {
            return pdc.get(key, type);
        } catch (Throwable wrongType) {
            return null;
        }
    }

    private boolean levelOk(int level, Settings settings) {
        if (level <= 0) {
            return false;
        }
        // Потолок уровня: защита от мусорных меток вроде 9999.
        return settings.maxLevel() == 0 || level <= settings.maxLevel();
    }

    /**
     * Запасной путь: имя предмета содержит одну из подстрок, например
     * «Бур» или «Drill». Нужен, если чар реализован без PDC — через
     * переименование предмета. По умолчанию список пуст.
     */
    private boolean byName(ItemStack tool, Settings settings) {
        if (settings.nameContains().isEmpty()) {
            return false;
        }
        try {
            if (!tool.hasItemMeta()) {
                return false;
            }
            var meta = tool.getItemMeta();
            if (meta == null || !meta.hasDisplayName()) {
                return false;
            }
            String name = org.bukkit.ChatColor.stripColor(meta.getDisplayName());
            if (name == null) {
                return false;
            }
            String lower = name.toLowerCase(Locale.ROOT);
            for (String needle : settings.nameContains()) {
                if (lower.contains(needle)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            return false;
        }
        return false;
    }
}
