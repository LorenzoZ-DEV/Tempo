package dev.lorenzzz.tempo;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Configurazione del database, letta da database.properties nella cartella del plugin. */
public class DatabaseConfig {

    public String url = "jdbc:mariadb://localhost:3306/velocity";
    public String username = "root";
    public String password = "";
    public int maximumPoolSize = 5;
    public long connectionTimeoutMs = 10_000L;

    public static DatabaseConfig load(Path dataDirectory) throws IOException {
        Files.createDirectories(dataDirectory);
        Path file = dataDirectory.resolve("database.properties");
        DatabaseConfig cfg = new DatabaseConfig();
        if (!Files.exists(file)) {
            cfg.save(file);
            return cfg;
        }
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(r);
        }
        cfg.url = p.getProperty("url", cfg.url).trim();
        cfg.username = p.getProperty("username", cfg.username).trim();
        cfg.password = p.getProperty("password", cfg.password);
        cfg.maximumPoolSize = parseInt(p.getProperty("maximum-pool-size"), cfg.maximumPoolSize);
        cfg.connectionTimeoutMs = parseLong(p.getProperty("connection-timeout-ms"), cfg.connectionTimeoutMs);
        return cfg;
    }

    private void save(Path file) throws IOException {
        try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            w.write("# Database di Tempo (HikariCP). Driver MariaDB, compatibile anche con MySQL.");
            w.newLine();
            w.write("# Esempio MySQL/MariaDB locale: jdbc:mariadb://localhost:3306/velocity");
            w.newLine();
            w.write("url=" + url);
            w.newLine();
            w.write("username=" + username);
            w.newLine();
            w.write("password=" + password);
            w.newLine();
            w.write("maximum-pool-size=" + maximumPoolSize);
            w.newLine();
            w.write("connection-timeout-ms=" + connectionTimeoutMs);
            w.newLine();
        }
    }

    private static int parseInt(String v, int def) {
        try {
            return Integer.parseInt(v.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static long parseLong(String v, long def) {
        try {
            return Long.parseLong(v.trim());
        } catch (Exception e) {
            return def;
        }
    }
}
