package ru.voidpe.anticheat;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.UUID;

/**
 * Точный определитель ввода: на чём играет человек и каким способом он
 * управляет персонажем.
 *
 * <p><b>Зачем отдельный класс.</b> Раньше категория выводилась одной строкой:
 * «мобильная ОС → сенсор». Это смешивало три разных случая — телефон с
 * экрана, телефон с подключённой мышью и ПК, который притворился телефоном.
 * Для античита разница принципиальна: у тача CPS считается иначе, у телефона
 * с мышью — как у мыши, а подмена платформы сама по себе является уликой.</p>
 *
 * <p><b>Источники по убыванию достоверности.</b></p>
 * <ol>
 *   <li>живая Bedrock-сессия в ядре (Geyser): {@code platform}, {@code input},
 *       {@code device_model}, {@code protocol}, {@code xuid} — настоящие данные
 *       клиента, их нельзя подделать с телефона;</li>
 *   <li>Floodgate: {@code deviceOs} + {@code inputMode} — грубее, но надёжно;</li>
 *   <li>поведение: реальные интервалы движения и поворотов — единственное, что
 *       остаётся, когда оба источника молчат.</li>
 * </ol>
 *
 * <p><b>Чего класс не делает.</b> Он никого не наказывает и не меняет VL.
 * Максимум — пишет предупреждение о противоречивых данных. Решение о лимитах
 * принимают проверки, читая {@link InputKind}.</p>
 */
final class DeviceProbe {

    /**
     * Категория ввода. Именно она определяет лимиты проверок.
     *
     * <ul>
     *   <li>{@code DESKTOP_KBM} — ПК с клавиатурой и мышью (Windows/macOS/Linux);</li>
     *   <li>{@code MOBILE_TOUCH} — телефон/планшет с экрана (Android/iOS);</li>
     *   <li>{@code MOBILE_KBM} — телефон/планшет, к которому подключены мышь и
     *       клавиатура (OTG/Bluetooth) — «телефон с крестиком» в меню ввода;</li>
     *   <li>{@code CONTROLLER} — геймпад: Xbox, PlayStation, Switch;</li>
     *   <li>{@code UNKNOWN} — данных нет; лимиты обязаны быть мягкими.</li>
     * </ul>
     */
    enum InputKind {
        DESKTOP_KBM("ПК: клавиатура + мышь", true, false),
        MOBILE_TOUCH("телефон/планшет: сенсор", false, true),
        MOBILE_KBM("телефон/планшет: клавиатура + мышь", true, true),
        CONTROLLER("геймпад / консоль", false, false),
        UNKNOWN("неизвестно", false, true);

        private final String label;
        private final boolean mouseLike;
        private final boolean softLimits;

        InputKind(String label, boolean mouseLike, boolean softLimits) {
            this.label = label;
            this.mouseLike = mouseLike;
            this.softLimits = softLimits;
        }

        /** Человекочитаемое название для /vac info. */
        String label() {
            return label;
        }

        /**
         * Ввод похож на мышь: ровный ритм кликов и высокий CPS здесь улика.
         * У сенсора и геймпада удержание даёт ровный ритм само по себе.
         */
        boolean mouseLike() {
            return mouseLike;
        }

        /**
         * Нужны мягкие (широкие) лимиты движения. Правда для всех, кроме ПК:
         * у сенсора пакеты идут пачками, у консоли крупный шаг стика.
         */
        boolean softLimits() {
            return softLimits;
        }
    }

    /** Совпадает ли определённый ввод с тем, что заявляет клиент. */
    enum Verdict {
        /** Источники согласны, либо данных мало для вывода. */
        CONSISTENT,
        /**
         * Клиент заявил мобильную платформу, но управление мышиное и модель
         * устройства пустая — типичная подмена {@code DeviceOS=Android}
         * с настольного чит-клиента.
         */
        PLATFORM_SPOOF_SUSPECT,
        /** Явное противоречие платформы и ввода без признаков подмены. */
        CONTRADICTORY
    }

    /** Итог опроса для одного игрока. */
    record Profile(
            InputKind kind,
            String platform,
            String rawInput,
            String deviceModel,
            String uiProfile,
            boolean liveSession,
            Verdict verdict,
            String detail
    ) {
        static Profile unknown(String why) {
            return new Profile(InputKind.UNKNOWN, "UNKNOWN", "UNKNOWN", "", "Classic",
                    false, Verdict.CONSISTENT, why);
        }

        boolean suspectedSpoof() {
            return verdict == Verdict.PLATFORM_SPOOF_SUSPECT;
        }
    }

    /**
     * Поведенческий отпечаток на случай, когда ни ядро, ни Floodgate не дали
     * данных. Только два числа: медианный интервал между событиями движения и
     * медианный шаг поворота. Ни то, ни другое не является уликой чита —
     * это способ выбрать лимиты, а не наказать.
     */
    static final class Behaviour {
        private final long[] intervals = new long[32];
        private final float[] turns = new float[32];
        private int intervalCount;
        private int turnCount;
        private int intervalIndex;
        private int turnIndex;

        void move(long nowMillis) {
            // Первое событие ничего не даёт: интервала ещё нет.
            if (lastMoveMillis != 0L) {
                long delta = nowMillis - lastMoveMillis;
                if (delta > 0 && delta < 2000) {
                    intervals[intervalIndex] = delta;
                    intervalIndex = (intervalIndex + 1) % intervals.length;
                    if (intervalCount < intervals.length) {
                        intervalCount++;
                    }
                }
            }
            lastMoveMillis = nowMillis;
        }

        private long lastMoveMillis;

        void turn(float yawDelta) {
            float abs = Math.abs(yawDelta);
            if (abs <= 0.0f || abs > 180.0f) {
                return;
            }
            turns[turnIndex] = abs;
            turnIndex = (turnIndex + 1) % turns.length;
            if (turnCount < turns.length) {
                turnCount++;
            }
        }

        /** Медианный интервал движения, мс; 0 — данных нет. */
        long medianInterval() {
            // Math.round, а не приведение: median() считает в double.
            return Math.round(median(intervalCount, true));
        }

        /** Медианный шаг поворота, градусы; 0 — данных нет. */
        float medianTurn() {
            return (float) median(turnCount, false);
        }

        boolean enough() {
            return intervalCount >= 16 && turnCount >= 16;
        }

        private double median(int count, boolean longs) {
            if (count == 0) {
                return 0;
            }
            double[] copy = new double[count];
            for (int i = 0; i < count; i++) {
                copy[i] = longs ? intervals[i] : turns[i];
            }
            java.util.Arrays.sort(copy);
            int mid = count / 2;
            return count % 2 == 1 ? copy[mid] : (copy[mid - 1] + copy[mid]) / 2.0;
        }
    }

    private final BedrockCoreBridge bridge;
    private final FloodgateHook floodgate;

    DeviceProbe(BedrockCoreBridge bridge, FloodgateHook floodgate) {
        this.bridge = bridge;
        this.floodgate = floodgate;
    }

    /**
     * Опрос всех источников. Результат кэшируется вызывающим на несколько
     * секунд: данные сессии меняются медленно, а /vac info не должен дёргать
     * рефлексию чаще, чем нужно.
     */
    Profile probe(Player player) {
        if (player == null) {
            return Profile.unknown("нет игрока");
        }
        UUID id = player.getUniqueId();

        // 1. Живая сессия ядра.
        if (bridge != null && bridge.available() && bridge.sessionDataAvailable(player)) {
            String platform = blank(bridge.queryPublic(player, "platform"));
            String input = blank(bridge.queryPublic(player, "input_raw"));
            String model = blank(bridge.queryPublic(player, "device_model"));
            String ui = blank(bridge.queryPublic(player, "ui"));
            InputKind kind = classify(platform, input, model);
            Verdict verdict = verdictFor(platform, input, model, kind);
            return new Profile(kind, platform.isEmpty() ? "UNKNOWN" : platform,
                    input.isEmpty() ? "UNKNOWN" : input,
                    model, ui.isEmpty() ? "Classic" : ui,
                    true, verdict, describe(kind, platform, input, model));
        }

        // 2. Floodgate.
        if (floodgate != null && floodgate.available()) {
            String os = blank(floodgate.deviceOs(id));
            String input = blank(floodgate.inputMode(id));
            InputKind kind = classify(os, input, "");
            Verdict verdict = verdictFor(os, input, "", kind);
            return new Profile(kind, os.isEmpty() ? "UNKNOWN" : os,
                    input.isEmpty() ? "UNKNOWN" : input, "",
                    "Classic", false, verdict, describe(kind, os, input, ""));
        }

        // 3. Данных нет — ни устройства, ни ввода.
        return Profile.unknown("ни сессии ядра, ни Floodgate");
    }

    /**
     * Классификация. Вынесена отдельно, потому что её надо проверять тестом без
     * сервера: имена платформ и режимов ввода приходят из Geyser/Floodgate
     * строками и легко меняются между версиями.
     */
    static InputKind classify(String platform, String rawInput, String model) {
        String os = upper(platform);
        String input = upper(rawInput);
        String device = model == null ? "" : model.trim();

        boolean kbmNow = input.equals("KEYBOARD_MOUSE") || input.equals("MOUSE");
        boolean controllerNow = input.equals("CONTROLLER")
                || input.equals("GAME_PAD") || input.equals("GAMEPAD");
        boolean touchNow = input.equals("TOUCH");

        boolean mobile = isMobileOs(os);
        boolean desktop = isDesktopOs(os);
        boolean console = isConsoleOs(os);

        if (mobile) {
            // Телефон с подключённой мышью: так и называем, не притворяясь,
            // что это ПК. Лимиты при этом мышиные — см. InputKind.
            if (kbmNow) {
                return InputKind.MOBILE_KBM;
            }
            // Подмена: заявлена мобильная ОС, но режим ввода по умолчанию —
            // мышь, а модель пустая. Настоящий телефон так не выглядит.
            if (input.isEmpty() && device.isEmpty()) {
                return InputKind.UNKNOWN;
            }
            if (controllerNow) {
                return InputKind.CONTROLLER;
            }
            return InputKind.MOBILE_TOUCH;
        }

        if (console) {
            return InputKind.CONTROLLER;
        }
        if (desktop) {
            // На ПК возможен и геймпад, но решает текущий ввод.
            return controllerNow ? InputKind.CONTROLLER : InputKind.DESKTOP_KBM;
        }

        // Неизвестная ОС: верим только явному вводу.
        if (kbmNow) {
            return InputKind.DESKTOP_KBM;
        }
        if (controllerNow) {
            return InputKind.CONTROLLER;
        }
        if (touchNow) {
            return InputKind.MOBILE_TOUCH;
        }
        return InputKind.UNKNOWN;
    }

    static Verdict verdictFor(String platform, String rawInput, String model, InputKind kind) {
        String os = upper(platform);
        String input = upper(rawInput);
        String device = model == null ? "" : model.trim();
        if (os.isEmpty() || os.equals("UNKNOWN")) {
            return Verdict.CONSISTENT;
        }
        boolean mobile = isMobileOs(os);
        boolean kbm = input.equals("KEYBOARD_MOUSE") || input.equals("MOUSE");
        if (mobile && kind == InputKind.DESKTOP_KBM) {
            // Мобильная ОС + мышиный ввод, но классификация уже сказала «ПК» —
            // значит режим ввода по умолчанию был мышиным.
            return device.isEmpty() ? Verdict.PLATFORM_SPOOF_SUSPECT : Verdict.CONTRADICTORY;
        }
        if (mobile && kbm && device.isEmpty() && input.isEmpty()) {
            return Verdict.PLATFORM_SPOOF_SUSPECT;
        }
        return Verdict.CONSISTENT;
    }

    private static String describe(InputKind kind, String platform, String input, String model) {
        StringBuilder text = new StringBuilder(kind.label());
        text.append(" (").append(platform.isEmpty() ? "ОС неизвестна" : platform);
        if (!input.isEmpty() && !input.equals("UNKNOWN")) {
            text.append(", ввод ").append(input);
        }
        if (!model.isEmpty()) {
            text.append(", модель ").append(model);
        }
        return text.append(')').toString();
    }

    static boolean isMobileOs(String os) {
        return switch (upper(os)) {
            case "ANDROID", "GOOGLE", "AMAZON", "FIREOS", "WINDOWS_PHONE", "IOS", "IPADOS" -> true;
            default -> false;
        };
    }

    static boolean isDesktopOs(String os) {
        return switch (upper(os)) {
            case "WINDOWS", "WIN32", "WINDOWS_X86", "UWP", "OSX", "MACOS", "LINUX", "DEDICATED",
                 "WINDOWS_10", "WINDOWS_11" -> true;
            default -> false;
        };
    }

    static boolean isConsoleOs(String os) {
        return switch (upper(os)) {
            case "XBOX", "XBOX_ONE", "XBOX_SERIES", "PS4", "PS5", "PLAYSTATION", "ORBIS",
                 "NX", "SWITCH", "NINTENDO", "NINTENDO_SWITCH" -> true;
            default -> false;
        };
    }

    private static String upper(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String blank(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * Сколько секунд кэшировать профиль. Данные сессии не меняются мгновенно,
     * а событие движения приходит до 20 раз в секунду — рефлексия в горячем
     * пути недопустима.
     */
    static final long CACHE_MILLIS = 3000L;

    /** Проверка «данные устарели» для кэша вызывающей стороны. */
    static boolean stale(long cachedAtMillis, long nowMillis) {
        return nowMillis - cachedAtMillis > CACHE_MILLIS;
    }

    /** Служебное: предупреждение в лог при подозрении на подмену платформы. */
    void warnSpoofOnce(Player player, Profile profile, java.util.Set<UUID> warned) {
        if (profile == null || !profile.suspectedSpoof()) {
            return;
        }
        if (!warned.add(player.getUniqueId())) {
            return;
        }
        Bukkit.getLogger().warning("[VoidAntiCheat] " + player.getName()
                + ": клиент заявляет мобильную платформу (" + profile.platform()
                + "), но управление мышиное и модель устройства пустая —"
                + " похоже на подмену DeviceOS настольным клиентом");
    }
}
