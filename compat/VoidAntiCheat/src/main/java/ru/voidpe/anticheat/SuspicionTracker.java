package ru.voidpe.anticheat;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Суммарный уровень подозрительности (AVG) и история нарушений игрока.
 *
 * <h2>Зачем</h2>
 * <p>По отдельности цифры вроде «FastBreak 4.2» ничего не говорят: у честного
 * игрока на плохом интернете тоже бывает всплеск. Смысл появляется, когда
 * видно <b>сумму по всем проверкам</b> и то, <b>как она менялась</b>. Отсюда две
 * вещи:</p>
 * <ul>
 *   <li><b>AVG</b> — сумма VL по всем включённым проверкам. Это не «процент
 *       читерства» и не вероятность — это просто сумма, и подписывать её как
 *       приговор нельзя. Одно и то же число даёт один игрок, поймавший
 *       три проверки по чуть-чуть, и другой — с одной грубой;</li>
 *   <li><b>история</b> — что именно флагалось, когда и с какими деталями.
 *       Хранится в памяти на сессию и дописывается в файл, чтобы пережить
 *       перезапуск.</li>
 * </ul>
 *
 * <h2>Что здесь важно с точки зрения надёжности</h2>
 * <ul>
 *   <li>История ограничена по размеру на игрока ({@value #MAX_ENTRIES}) и по
 *       числу игроков в кэше ({@value #MAX_CACHED_PLAYERS}) — иначе за сутки
 *       работы на большом сервере это выросло бы в сотни мегабайт;</li>
 *   <li>запись на диск не блокирует игровой поток: строки складываются в
 *       ограниченную очередь, при переполнении отбрасываются;</li>
 *   <li>метка над головой обновляется не чаще раза в секунду на игрока —
 *       частая пересборка scoreboard стоит дорого;</li>
 *   <li>всё выключается в конфиге ({@code suspicion.*}).</li>
 * </ul>
 */
final class SuspicionTracker {

    /** Сколько записей истории держим на игрока в памяти. */
    private static final int MAX_ENTRIES = 200;

    /** Сколько игроков держим в кэше (вытеснение по времени последней записи). */
    private static final int MAX_CACHED_PLAYERS = 500;

    private static final DateTimeFormatter FILE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** Одна запись истории: что, сколько и когда. */
    record Entry(long timeMillis, String check, double vl, String details) {
        String render() {
            return String.format(Locale.ROOT, "  %s  §f%s §7VL §f%.1f §7%s",
                    FILE_TIME.format(LocalDateTime.ofInstant(
                            Instant.ofEpochMilli(timeMillis), ZoneId.systemDefault())),
                    check, vl, details == null ? "" : details);
        }

        String csv() {
            return timeMillis + ";" + check + ";" + String.format(Locale.ROOT, "%.2f", vl)
                    + ";" + (details == null ? "" : details.replace(';', ','));
        }
    }

    private final VoidAntiCheat plugin;

    /** История по игрокам. Ключ — UUID, значение — ограниченная очередь. */
    private final Map<UUID, Deque<Entry>> history = new ConcurrentHashMap<>();

    /** Пик AVG за сессию: «сколько было максимум» часто важнее текущего. */
    private final Map<UUID, Double> peak = new ConcurrentHashMap<>();

    /** Очередь на запись: ограничена, чтобы диск не стал причиной OOM. */
    private final Deque<String> writeQueue = new ArrayDeque<>();
    private static final int MAX_QUEUE = 5000;

    private final File historyFile;
    private boolean writing;

    SuspicionTracker(VoidAntiCheat plugin) {
        this.plugin = plugin;
        this.historyFile = new File(plugin.getDataFolder(), "history.csv");
    }

    // ------------------------------------------------------------------
    // AVG
    // ------------------------------------------------------------------

    /**
     * Суммарный VL по всем проверкам. Не включает выключенные проверки:
     * иначе администратор отключил бы шумную проверку, а её старые значения
     * продолжали бы висеть в сумме и пугать стафф.
     */
    double avg(PlayerData data) {
        if (data == null) {
            return 0.0;
        }
        double sum = 0.0;
        for (CheckId check : CheckId.values()) {
            CheckSettings cfg = plugin.checkSettings(check);
            if (cfg == null || !cfg.enabled()) {
                continue;
            }
            double vl = data.state(check).vl();
            if (vl > 0) {
                sum += vl;
            }
        }
        return sum;
    }

    double peak(UUID uuid) {
        Double value = peak.get(uuid);
        return value == null ? 0.0 : value;
    }

    // ------------------------------------------------------------------
    // История
    // ------------------------------------------------------------------

    /**
     * Регистрирует нарушение. Вызывается из {@code flag()}, поэтому обязана
     * быть дешёвой: никакого диска в игровом потоке.
     */
    void record(PlayerData data, CheckId check, double vl, String details) {
        if (data == null || check == null) {
            return;
        }
        UUID uuid = data.uuid;
        Deque<Entry> entries = history.computeIfAbsent(uuid, key -> new ArrayDeque<>());
        Entry entry = new Entry(System.currentTimeMillis(), check.key(), vl, details);
        synchronized (entries) {
            entries.addLast(entry);
            while (entries.size() > MAX_ENTRIES) {
                entries.removeFirst();
            }
        }
        double current = avg(data);
        peak.merge(uuid, current, Math::max);
        enqueue(entry.csv());

        if (history.size() > MAX_CACHED_PLAYERS) {
            evictOldest();
        }
    }

    /** Вытесняет историю игрока, который давно не появлялся. */
    private void evictOldest() {
        UUID oldest = null;
        long oldestTime = Long.MAX_VALUE;
        for (Map.Entry<UUID, Deque<Entry>> e : history.entrySet()) {
            Deque<Entry> deque = e.getValue();
            Long last = null;
            synchronized (deque) {
                Entry lastEntry = deque.peekLast();
                if (lastEntry != null) {
                    last = lastEntry.timeMillis();
                }
            }
            long time = last == null ? Long.MIN_VALUE : last;
            if (time < oldestTime) {
                oldestTime = time;
                oldest = e.getKey();
            }
        }
        if (oldest != null) {
            Deque<Entry> removed = history.remove(oldest);
            if (removed != null) {
                removed.clear();
            }
            peak.remove(oldest);
        }
    }

    List<Entry> entries(UUID uuid) {
        Deque<Entry> deque = history.get(uuid);
        if (deque == null) {
            return List.of();
        }
        synchronized (deque) {
            return new ArrayList<>(deque);
        }
    }

    /** Сводка по проверкам: сколько раз и сколько VL всего. */
    Map<String, int[]> summary(UUID uuid) {
        Map<String, int[]> result = new LinkedHashMap<>();
        for (Entry entry : entries(uuid)) {
            int[] stats = result.computeIfAbsent(entry.check(), key -> new int[2]);
            stats[0]++;
            stats[1] += (int) Math.round(entry.vl());
        }
        return result;
    }

    void clear(UUID uuid) {
        Deque<Entry> removed = history.remove(uuid);
        if (removed != null) {
            removed.clear();
        }
        peak.remove(uuid);
    }

    // ------------------------------------------------------------------
    // Запись на диск
    // ------------------------------------------------------------------

    private void enqueue(String line) {
        if (line == null || !plugin.confSuspicion("suspicion.history-enabled", true)) {
            return;
        }
        synchronized (writeQueue) {
            if (writeQueue.size() >= MAX_QUEUE) {
                // Лучше потерять строку истории, чем память.
                return;
            }
            writeQueue.addLast(line);
            if (writing) {
                return;
            }
            writing = true;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, this::drain);
    }

    private void drain() {
        List<String> batch = new ArrayList<>(64);
        while (true) {
            synchronized (writeQueue) {
                if (writeQueue.isEmpty()) {
                    writing = false;
                    return;
                }
                while (!writeQueue.isEmpty() && batch.size() < 64) {
                    batch.add(writeQueue.pollFirst());
                }
            }
            try {
                StringBuilder sb = new StringBuilder();
                for (String line : batch) {
                    sb.append(line).append('\n');
                }
                Files.writeString(historyFile.toPath(), sb.toString(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                plugin.getLogger().fine("Не удалось записать историю нарушений: " + e.getMessage());
            }
            batch.clear();
        }
    }

    // ------------------------------------------------------------------
    // Метка AVG над головой
    // ------------------------------------------------------------------

    /** Порог, выше которого над головой появляется метка. */
    private double labelThreshold = 5.0;

    /** Показывать ли метку. */
    private boolean labelEnabled = true;

    void configure(boolean labelEnabled, double labelThreshold) {
        this.labelEnabled = labelEnabled;
        this.labelThreshold = Math.max(0.0, labelThreshold);
    }

    /**
     * Обновляет метку над головой для тех, кто смотрит.
     *
     * <p>Отдельный scoreboard на каждого «подозреваемого» создаётся лениво и
     * только для тех, чей AVG выше порога — иначе на сервере с сотней игроков
     * мы бы держали сотню лишних scoreboard'ов и рассылали их всем вокруг.</p>
     *
     * <p>Важно: боковой scoreboard не подменяется — используется только
     * {@code Player#setPlayerListName} и заголовок команды, чтобы не ломать
     * скорборд другим плагинам (это прямое требование: не мешать чужим
     * плагинам).</p>
     */
    void refreshLabel(PlayerData data, Player target) {
        if (!labelEnabled || data == null || target == null) {
            return;
        }
        double avg = avg(data);
        // Не чаще раза в секунду: пересборка строки — это работа с компонентами.
        long now = System.currentTimeMillis();
        if (now - data.lastAvgLabelMillis < 1000L) {
            return;
        }
        data.lastAvgLabelMillis = now;
        data.lastAvg = avg;

        if (avg < labelThreshold) {
            return;
        }
        // Метка уходит только стаффу — игрок не должен видеть свою «шкалу».
        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (!staff.hasPermission("voidanticheat.alerts")) {
                continue;
            }
            if (staff.getUniqueId().equals(target.getUniqueId())) {
                continue;
            }
            if (staff.getWorld() != target.getWorld()) {
                continue;
            }
            if (staff.getLocation().distanceSquared(target.getLocation()) > 48 * 48) {
                continue;
            }
            // Механизм намеренно простой и безопасный: actionbar, а не
            // сущность/голограмма. Создание armor stand или display-сущности
            // потребовало бы правки мира и могло бы ломаться на Bedrock.
            staff.sendActionBar(plugin.compPublic("§7AVG §f" + target.getName() + " §7= §f"
                    + String.format(Locale.ROOT, "%.1f", avg)));
        }
    }

    void resetRuntime() {
        synchronized (writeQueue) {
            writeQueue.clear();
        }
    }

    String renderHistory(Player player) {
        List<Entry> list = entries(player.getUniqueId());
        if (list.isEmpty()) {
            return "§7История пуста (в памяти нет записей об этом игроке).";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("§7Записей: §f").append(list.size()).append('\n');
        // Показываем последние 10: вся история редко нужна, а чат ограничен.
        int from = Math.max(0, list.size() - 10);
        for (Iterator<Entry> it = list.subList(from, list.size()).iterator(); it.hasNext(); ) {
            sb.append(it.next().render());
            if (it.hasNext()) {
                sb.append('\n');
            }
        }
        return sb.toString();
    }
}
