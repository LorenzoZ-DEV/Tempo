package dev.lorenzzz.tempo;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Traccia il tempo di gioco per UUID, suddiviso per giorno.
 * Totale = cumulativo, giornaliero = oggi, settimanale = lunedi-domenica corrente.
 */
public class PlaytimeManager {

    private static final int KEEP_DAYS = 30;

    private final Path dataFile;
    private final ZoneId zone;
    private final ConcurrentHashMap<UUID, TimeData> data = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> sessionStart = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, String> uuidToName = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, UUID> nameToUuid = new ConcurrentHashMap<>();
    private volatile Database database;

    public PlaytimeManager(Path dataDirectory, ZoneId zone) {
        this.dataFile = dataDirectory.resolve("playtimes.db");
        this.zone = zone;
    }

    public PlaytimeManager(Path dataDirectory) {
        this(dataDirectory, ZoneId.systemDefault());
    }

    /** Collega il database (null = usa il file locale come fallback). Va chiamato prima di load(). */
    public void setDatabase(Database database) {
        this.database = database;
    }

    public boolean isDatabase() {
        return database != null;
    }

    // ---------- join / quit ----------

    public void onJoin(UUID uuid, String name) {
        if (name != null) {
            uuidToName.put(uuid, name);
            nameToUuid.put(name.toLowerCase(Locale.ROOT), uuid);
        }
        data.computeIfAbsent(uuid, k -> new TimeData());
        sessionStart.put(uuid, System.currentTimeMillis());
    }

    public void onQuit(UUID uuid) {
        Long start = sessionStart.remove(uuid);
        if (start == null) {
            return;
        }
        long end = System.currentTimeMillis();
        Map<LocalDate, Long> split = splitByDay(start, end);
        TimeData td = data.computeIfAbsent(uuid, k -> new TimeData());
        synchronized (td) {
            for (Map.Entry<LocalDate, Long> e : split.entrySet()) {
                td.daily.merge(e.getKey(), e.getValue(), Long::sum);
            }
            td.totalSeconds += (end - start) / 1000L;
            prune(td);
        }
    }

    /** Sposta il tempo accumulato finora nel backup persistente (anti-crash), azzerando il cronometro. */
    public void flushOnlineSessions() {
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Long> e : sessionStart.entrySet()) {
            UUID uuid = e.getKey();
            long start = e.getValue();
            if (now <= start) {
                continue;
            }
            Map<LocalDate, Long> split = splitByDay(start, now);
            TimeData td = data.computeIfAbsent(uuid, k -> new TimeData());
            synchronized (td) {
                for (Map.Entry<LocalDate, Long> s : split.entrySet()) {
                    td.daily.merge(s.getKey(), s.getValue(), Long::sum);
                }
                td.totalSeconds += (now - start) / 1000L;
                prune(td);
            }
            sessionStart.put(uuid, now);
        }
    }

    // ---------- query ----------

    public Optional<UUID> resolveUuid(String name) {
        if (name == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(nameToUuid.get(name.toLowerCase(Locale.ROOT)));
    }

    /**
     * Risolve un nome prima in memoria, poi con query diretta sul DB (case-insensitive).
     * Copre i giocatori offline assenti dalla cache (es. dati scritti da un altro proxy
     * con DB condiviso, o join avvenuti mentre il DB non era raggiungibile).
     * Il risultato dal DB viene unito alla cache in memoria.
     */
    public Optional<UUID> resolveUuidDeep(String name) {
        Optional<UUID> mem = resolveUuid(name);
        if (mem.isPresent()) {
            return mem;
        }
        Database db = database;
        if (db == null || name == null || name.isBlank()) {
            return Optional.empty();
        }
        try {
            Optional<Database.PlayerEntry> e =
                    db.loadByName(name.trim(), LocalDate.now(zone).minusDays(KEEP_DAYS));
            if (e.isEmpty()) {
                return Optional.empty();
            }
            mergeEntry(e.get());
            return Optional.of(e.get().uuid());
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    private void mergeEntry(Database.PlayerEntry e) {
        TimeData td = data.computeIfAbsent(e.uuid(), k -> new TimeData());
        synchronized (td) {
            td.totalSeconds = Math.max(td.totalSeconds, e.totalSeconds());
            for (Map.Entry<LocalDate, Long> d : e.daily().entrySet()) {
                td.daily.merge(d.getKey(), d.getValue(), Math::max);
            }
            prune(td);
        }
        if (e.name() != null && !e.name().isEmpty()) {
            uuidToName.put(e.uuid(), e.name());
            nameToUuid.put(e.name().toLowerCase(Locale.ROOT), e.uuid());
        }
    }

    public String knownName(UUID uuid) {
        return uuidToName.getOrDefault(uuid, uuid.toString());
    }

    public boolean hasData(UUID uuid) {
        return data.containsKey(uuid);
    }

    public long getTotalSeconds(UUID uuid) {
        TimeData td = data.get(uuid);
        long base = td == null ? 0L : snapshotTotal(td);
        return base + liveSeconds(uuid, null, null);
    }

    public long getDailySeconds(UUID uuid) {
        LocalDate today = LocalDate.now(zone);
        TimeData td = data.get(uuid);
        long base = td == null ? 0L : snapshotDay(td, today);
        return base + liveSeconds(uuid, today, today);
    }

    public long getWeeklySeconds(UUID uuid) {
        LocalDate today = LocalDate.now(zone);
        LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate sunday = monday.plusDays(6);
        TimeData td = data.get(uuid);
        long base = td == null ? 0L : snapshotRange(td, monday, sunday);
        return base + liveSeconds(uuid, monday, sunday);
    }

    /** Secondi della sessione corrente che ricadono nel range [from..to] (null = nessun filtro). */
    private long liveSeconds(UUID uuid, LocalDate from, LocalDate to) {
        Long start = sessionStart.get(uuid);
        if (start == null) {
            return 0L;
        }
        long now = System.currentTimeMillis();
        if (now <= start) {
            return 0L;
        }
        if (from == null) {
            return (now - start) / 1000L;
        }
        Map<LocalDate, Long> split = splitByDay(start, now);
        long sum = 0L;
        for (Map.Entry<LocalDate, Long> e : split.entrySet()) {
            if (!e.getKey().isBefore(from) && (to == null || !e.getKey().isAfter(to))) {
                sum += e.getValue();
            }
        }
        return sum;
    }

    private static long snapshotTotal(TimeData td) {
        synchronized (td) {
            return td.totalSeconds;
        }
    }

    private static long snapshotDay(TimeData td, LocalDate day) {
        synchronized (td) {
            return td.daily.getOrDefault(day, 0L);
        }
    }

    private static long snapshotRange(TimeData td, LocalDate from, LocalDate to) {
        synchronized (td) {
            long sum = 0L;
            for (Map.Entry<LocalDate, Long> e : td.daily.entrySet()) {
                if (!e.getKey().isBefore(from) && !e.getKey().isAfter(to)) {
                    sum += e.getValue();
                }
            }
            return sum;
        }
    }

    /** Divide una sessione [startMillis..endMillis] in secondi per giorno (gestisce la mezzanotte). */
    private Map<LocalDate, Long> splitByDay(long startMillis, long endMillis) {
        Map<LocalDate, Long> out = new HashMap<>();
        if (endMillis <= startMillis) {
            return out;
        }
        long cursor = startMillis;
        while (cursor < endMillis) {
            LocalDate day = new java.util.Date(cursor).toInstant().atZone(zone).toLocalDate();
            long nextMidnight = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
            long sliceEnd = Math.min(endMillis, nextMidnight);
            long secs = (sliceEnd - cursor) / 1000L;
            if (secs > 0) {
                out.merge(day, secs, Long::sum);
            } else if (sliceEnd > cursor) {
                // sessione brevissima (<1s): conta comunque sul giorno di inizio per non perderla del tutto? no, ignoriamo
            }
            cursor = sliceEnd;
        }
        return out;
    }

    private void prune(TimeData td) {
        if (td.daily.size() <= KEEP_DAYS) {
            return;
        }
        LocalDate cutoff = LocalDate.now(zone).minusDays(KEEP_DAYS);
        td.daily.keySet().removeIf(d -> d.isBefore(cutoff));
    }

    // ---------- persistenza ----------

    /**
     * Carica dal database se collegato, altrimenti dal file locale.
     * Se il DB e' collegato ma la lettura fallisce, lancia eccezione (il chiamante
     * decide il fallback) invece di ricadere in silenzio sul file.
     */
    public void load() throws Exception {
        Database db = database;
        if (db != null) {
            Map<UUID, Database.PlayerRow> rows = db.loadAll(LocalDate.now(zone).minusDays(KEEP_DAYS));
            data.clear();
            uuidToName.clear();
            nameToUuid.clear();
            for (Map.Entry<UUID, Database.PlayerRow> e : rows.entrySet()) {
                TimeData td = new TimeData();
                td.totalSeconds = e.getValue().totalSeconds();
                td.daily.putAll(e.getValue().daily());
                data.put(e.getKey(), td);
                String name = e.getValue().name();
                if (name != null && !name.isEmpty()) {
                    uuidToName.put(e.getKey(), name);
                    nameToUuid.put(name.toLowerCase(Locale.ROOT), e.getKey());
                }
            }
            return;
        }
        loadFromFile();
    }

    /** Scrive un singolo giocatore sul DB (o tutto il file se senza DB). Da chiamare in async. */
    public void savePlayer(UUID uuid) throws Exception {
        Database db = database;
        if (db == null) {
            save();
            return;
        }
        Database.PlayerRow row = snapshotRow(uuid);
        if (row == null) {
            return;
        }
        db.savePlayer(uuid, row);
    }

    /** Scrive tutti i giocatori sul DB (o il file se senza DB). Da chiamare in async / shutdown. */
    public void saveAll() throws Exception {
        Database db = database;
        if (db == null) {
            save();
            return;
        }
        Map<UUID, Database.PlayerRow> rows = new HashMap<>();
        for (UUID uuid : data.keySet()) {
            Database.PlayerRow row = snapshotRow(uuid);
            if (row != null) {
                rows.put(uuid, row);
            }
        }
        db.saveAll(rows);
        db.pruneDaily(LocalDate.now(zone).minusDays(KEEP_DAYS));
    }

    private Database.PlayerRow snapshotRow(UUID uuid) {
        TimeData td = data.get(uuid);
        if (td == null) {
            return null;
        }
        synchronized (td) {
            return new Database.PlayerRow(
                    uuidToName.getOrDefault(uuid, ""),
                    td.totalSeconds,
                    new TreeMap<>(td.daily));
        }
    }

    /**
     * Formato file (una riga per giocatore, UTF-8):
     * uuid;name;totalSeconds;YYYY-MM-DD=sec,YYYY-MM-DD=sec
     */
    public void loadFromFile() {
        data.clear();
        uuidToName.clear();
        nameToUuid.clear();
        if (!Files.exists(dataFile)) {
            return;
        }
        try (BufferedReader r = Files.newBufferedReader(dataFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] parts = line.split(";", 4);
                if (parts.length < 3) {
                    continue;
                }
                try {
                    UUID uuid = UUID.fromString(parts[0].trim());
                    String name = parts[1].trim();
                    long total = Long.parseLong(parts[2].trim());
                    TimeData td = new TimeData();
                    td.totalSeconds = Math.max(0L, total);
                    if (parts.length == 4 && !parts[3].isBlank()) {
                        for (String kv : parts[3].split(",")) {
                            String[] d = kv.split("=", 2);
                            if (d.length != 2) {
                                continue;
                            }
                            LocalDate date = LocalDate.parse(d[0].trim());
                            long secs = Math.max(0L, Long.parseLong(d[1].trim()));
                            if (secs > 0) {
                                td.daily.put(date, secs);
                            }
                        }
                    }
                    data.put(uuid, td);
                    if (!name.isEmpty()) {
                        uuidToName.put(uuid, name);
                        nameToUuid.put(name.toLowerCase(Locale.ROOT), uuid);
                    }
                } catch (Exception ignored) {
                    // riga corrotta: saltala
                }
            }
        } catch (IOException ignored) {
        }
    }

    public synchronized void save() {
        try {
            Files.createDirectories(dataFile.getParent());
            Path tmp = dataFile.resolveSibling("playtimes.db.tmp");
            try (BufferedWriter w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                for (Map.Entry<UUID, TimeData> e : data.entrySet()) {
                    UUID uuid = e.getKey();
                    TimeData td = e.getValue();
                    String name;
                    String daily;
                    long total;
                    synchronized (td) {
                        total = td.totalSeconds;
                        StringBuilder sb = new StringBuilder();
                        for (Map.Entry<LocalDate, Long> d : td.daily.entrySet()) {
                            if (sb.length() > 0) {
                                sb.append(',');
                            }
                            sb.append(d.getKey()).append('=').append(d.getValue());
                        }
                        daily = sb.toString();
                    }
                    name = uuidToName.getOrDefault(uuid, "");
                    // i nomi non devono contenere ';' o ',' o '=' o newline
                    name = name.replace(";", "").replace(",", "").replace("=", "").replace("\n", "");
                    w.write(uuid + ";" + name + ";" + total + ";" + daily);
                    w.newLine();
                }
            }
            Files.move(tmp, dataFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ignored) {
        }
    }

    public static String formatDuration(long totalSeconds) {
        if (totalSeconds < 0) {
            totalSeconds = 0;
        }
        long days = totalSeconds / 86400;
        long hours = (totalSeconds % 86400) / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        if (days > 0) {
            return days + "g " + hours + "h " + minutes + "m";
        }
        if (hours > 0) {
            return hours + "h " + minutes + "m";
        }
        if (minutes > 0) {
            return minutes + "m " + seconds + "s";
        }
        return seconds + "s";
    }

    private static final class TimeData {
        long totalSeconds;
        final TreeMap<LocalDate, Long> daily = new TreeMap<>();
    }
}
