package dev.lorenzzz.tempo;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Accesso al database via HikariCP (MariaDB/MySQL).
 * Tabelle:
 *   tempo_total(uuid PK, name, total_seconds)
 *   tempo_daily(uuid, day, seconds, PK(uuid, day))
 */
public class Database {

    private static final String CREATE_TOTAL = "CREATE TABLE IF NOT EXISTS tempo_total ("
            + "uuid VARCHAR(36) NOT NULL PRIMARY KEY,"
            + "name VARCHAR(32) NOT NULL DEFAULT '',"
            + "total_seconds BIGINT NOT NULL DEFAULT 0"
            + ")";

    private static final String CREATE_DAILY = "CREATE TABLE IF NOT EXISTS tempo_daily ("
            + "uuid VARCHAR(36) NOT NULL,"
            + "day DATE NOT NULL,"
            + "seconds BIGINT NOT NULL DEFAULT 0,"
            + "PRIMARY KEY (uuid, day)"
            + ")";

    private static final String UPSERT_TOTAL = "INSERT INTO tempo_total (uuid, name, total_seconds) VALUES (?, ?, ?)"
            + " ON DUPLICATE KEY UPDATE name = VALUES(name), total_seconds = VALUES(total_seconds)";

    private static final String UPSERT_DAILY = "INSERT INTO tempo_daily (uuid, day, seconds) VALUES (?, ?, ?)"
            + " ON DUPLICATE KEY UPDATE seconds = VALUES(seconds)";

    /** Riga giocatore: nome + totale + secondi per giorno. */
    public record PlayerRow(String name, long totalSeconds, Map<LocalDate, Long> daily) {
    }

    /** Riga giocatore con UUID (per lookup diretto da DB). */
    public record PlayerEntry(UUID uuid, String name, long totalSeconds, Map<LocalDate, Long> daily) {
    }

    private final HikariDataSource pool;

    public Database(DatabaseConfig cfg) {
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl(cfg.url);
        hc.setUsername(cfg.username);
        hc.setPassword(cfg.password);
        hc.setMaximumPoolSize(Math.max(1, cfg.maximumPoolSize));
        hc.setConnectionTimeout(Math.max(1_000L, cfg.connectionTimeoutMs));
        hc.setPoolName("tempo-hikari");
        this.pool = new HikariDataSource(hc);
    }

    /** Crea le tabelle se mancano. Lancia eccezione se il DB non e' raggiungibile. */
    public void init() throws SQLException {
        try (Connection c = pool.getConnection();
             Statement st = c.createStatement()) {
            st.execute(CREATE_TOTAL);
            st.execute(CREATE_DAILY);
        }
    }

    public void close() {
        pool.close();
    }

    /** Carica tutti i giocatori; i giorni piu' vecchi di dailySince vengono ignorati. */
    public Map<UUID, PlayerRow> loadAll(LocalDate dailySince) throws SQLException {
        Map<UUID, String> names = new HashMap<>();
        Map<UUID, Long> totals = new HashMap<>();
        try (Connection c = pool.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT uuid, name, total_seconds FROM tempo_total");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                try {
                    UUID uuid = UUID.fromString(rs.getString(1));
                    names.put(uuid, rs.getString(2));
                    totals.put(uuid, Math.max(0L, rs.getLong(3)));
                } catch (IllegalArgumentException ignored) {
                    // uuid malformato: salta
                }
            }
        }
        Map<UUID, TreeMap<LocalDate, Long>> dailies = new HashMap<>();
        try (Connection c = pool.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT uuid, day, seconds FROM tempo_daily WHERE day >= ?")) {
            ps.setDate(1, Date.valueOf(dailySince));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    try {
                        UUID uuid = UUID.fromString(rs.getString(1));
                        LocalDate day = rs.getDate(2).toLocalDate();
                        long secs = Math.max(0L, rs.getLong(3));
                        if (secs > 0) {
                            dailies.computeIfAbsent(uuid, k -> new TreeMap<>()).put(day, secs);
                        }
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
        }
        Map<UUID, PlayerRow> out = new HashMap<>();
        for (Map.Entry<UUID, Long> e : totals.entrySet()) {
            UUID uuid = e.getKey();
            out.put(uuid, new PlayerRow(
                    names.getOrDefault(uuid, ""),
                    e.getValue(),
                    dailies.getOrDefault(uuid, new TreeMap<>())));
        }
        // giorni di uuid senza riga total (non dovrebbe succedere, ma per sicurezza)
        for (Map.Entry<UUID, TreeMap<LocalDate, Long>> e : dailies.entrySet()) {
            out.putIfAbsent(e.getKey(), new PlayerRow(names.getOrDefault(e.getKey(), ""), 0L, e.getValue()));
        }
        return out;
    }

    /**
     * Cerca un giocatore per nome (case-insensitive) direttamente sul DB.
     * Serve per i giocatori offline assenti dalla cache in memoria.
     */
    public java.util.Optional<PlayerEntry> loadByName(String name, LocalDate dailySince) throws SQLException {
        if (name == null || name.isBlank()) {
            return java.util.Optional.empty();
        }
        try (Connection c = pool.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT uuid, name, total_seconds FROM tempo_total WHERE LOWER(name) = LOWER(?) LIMIT 1")) {
            ps.setString(1, name.trim());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return java.util.Optional.empty();
                }
                UUID uuid;
                try {
                    uuid = UUID.fromString(rs.getString(1));
                } catch (IllegalArgumentException e) {
                    return java.util.Optional.empty();
                }
                String dbName = rs.getString(2);
                long total = Math.max(0L, rs.getLong(3));
                Map<LocalDate, Long> daily = new TreeMap<>();
                try (PreparedStatement ps2 = c.prepareStatement(
                        "SELECT day, seconds FROM tempo_daily WHERE uuid = ? AND day >= ?")) {
                    ps2.setString(1, uuid.toString());
                    ps2.setDate(2, Date.valueOf(dailySince));
                    try (ResultSet rs2 = ps2.executeQuery()) {
                        while (rs2.next()) {
                            long secs = Math.max(0L, rs2.getLong(2));
                            if (secs > 0) {
                                daily.put(rs2.getDate(1).toLocalDate(), secs);
                            }
                        }
                    }
                }
                return java.util.Optional.of(new PlayerEntry(uuid, dbName, total, daily));
            }
        }
    }

    public void savePlayer(UUID uuid, PlayerRow row) throws SQLException {
        try (Connection c = pool.getConnection()) {
            c.setAutoCommit(false);
            try {
                upsertTotal(c, uuid, row);
                upsertDaily(c, uuid, row.daily());
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        }
    }

    public void saveAll(Map<UUID, PlayerRow> rows) throws SQLException {
        if (rows.isEmpty()) {
            return;
        }
        try (Connection c = pool.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement t = c.prepareStatement(UPSERT_TOTAL);
                 PreparedStatement d = c.prepareStatement(UPSERT_DAILY)) {
                for (Map.Entry<UUID, PlayerRow> e : rows.entrySet()) {
                    String id = e.getKey().toString();
                    PlayerRow row = e.getValue();
                    t.setString(1, id);
                    t.setString(2, row.name() == null ? "" : row.name());
                    t.setLong(3, row.totalSeconds());
                    t.addBatch();
                    for (Map.Entry<LocalDate, Long> day : row.daily().entrySet()) {
                        d.setString(1, id);
                        d.setDate(2, Date.valueOf(day.getKey()));
                        d.setLong(3, day.getValue());
                        d.addBatch();
                    }
                }
                t.executeBatch();
                d.executeBatch();
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        }
    }

    public void pruneDaily(LocalDate cutoff) throws SQLException {
        try (Connection c = pool.getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM tempo_daily WHERE day < ?")) {
            ps.setDate(1, Date.valueOf(cutoff));
            ps.executeUpdate();
        }
    }

    private static void upsertTotal(Connection c, UUID uuid, PlayerRow row) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(UPSERT_TOTAL)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, row.name() == null ? "" : row.name());
            ps.setLong(3, row.totalSeconds());
            ps.executeUpdate();
        }
    }

    private static void upsertDaily(Connection c, UUID uuid, Map<LocalDate, Long> daily) throws SQLException {
        if (daily.isEmpty()) {
            return;
        }
        try (PreparedStatement ps = c.prepareStatement(UPSERT_DAILY)) {
            for (Map.Entry<LocalDate, Long> e : daily.entrySet()) {
                ps.setString(1, uuid.toString());
                ps.setDate(2, Date.valueOf(e.getKey()));
                ps.setLong(3, e.getValue());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }
}
