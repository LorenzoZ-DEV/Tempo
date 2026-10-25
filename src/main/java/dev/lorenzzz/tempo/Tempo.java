package dev.lorenzzz.tempo;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

public class Tempo {

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;
    private final PlaytimeManager manager;
    private ScheduledTask autosaveTask;
    private Database database;

    @Inject
    public Tempo(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
        this.manager = new PlaytimeManager(dataDirectory);
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        // Database (HikariCP): se non raggiungibile si usa il file locale come fallback
        try {
            DatabaseConfig dbConfig = DatabaseConfig.load(dataDirectory);
            Database candidate = new Database(dbConfig);
            candidate.init();
            database = candidate;
            manager.setDatabase(database);
            logger.info("Database collegato: {}", dbConfig.url);
        } catch (Exception e) {
            logger.warn("Database non raggiungibile, uso il file locale come fallback: {}", e.getMessage());
        }
        try {
            manager.load();
        } catch (Exception e) {
            // Lettura DB fallita dopo init riuscita: disattiva il DB ed usa il file,
            // con errore esplicito nel log (niente fallback silenzioso).
            logger.warn("Lettura dal database fallita, passo al file locale: {}", e.toString());
            if (database != null) {
                try {
                    database.close();
                } catch (Exception ignored) {
                }
                database = null;
            }
            manager.setDatabase(null);
            try {
                manager.load();
            } catch (Exception e2) {
                logger.warn("Errore durante il caricamento dei tempi di gioco dal file", e2);
            }
        }

        // Giocatori gia' online (es. reload): falli partire da ora
        for (Player p : proxy.getAllPlayers()) {
            manager.onJoin(p.getUniqueId(), p.getUsername());
        }

        CommandManager commands = proxy.getCommandManager();
        commands.register(commands.metaBuilder("tempo")
                .aliases("tempoplay", "ptempo")
                .plugin(this)
                .build(), new PlaytimeCommand(proxy, manager));

        // Autosave + flush sessioni ogni 5 minuti (anti-crash). Task async: ok fare I/O DB.
        autosaveTask = proxy.getScheduler().buildTask(this, () -> {
            try {
                manager.flushOnlineSessions();
                manager.saveAll();
            } catch (Exception e) {
                logger.warn("Errore durante l'autosave dei tempi di gioco", e);
            }
        }).repeat(5L, TimeUnit.MINUTES).delay(5L, TimeUnit.MINUTES).schedule();

        logger.info("Tempo abilitato: /tempo [giocatore] (giornaliero, settimanale, totale)");
    }

    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
        Player player = event.getPlayer();
        manager.onJoin(player.getUniqueId(), player.getUsername());
        if (manager.isDatabase()) {
            // Persiste subito nome+riga: il giocatore resta visibile da /tempo anche
            // se e' offline e il proxy crasha prima del quit/autosave.
            proxy.getScheduler().buildTask(this, () -> {
                try {
                    manager.savePlayer(player.getUniqueId());
                } catch (Exception e) {
                    logger.warn("Errore durante il salvataggio iniziale di {}", player.getUsername(), e);
                }
            }).schedule();
        }
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        manager.onQuit(event.getPlayer().getUniqueId());
        if (manager.isDatabase()) {
            // I/O DB in async per non bloccare l'event loop
            proxy.getScheduler().buildTask(this, () -> {
                try {
                    manager.savePlayer(event.getPlayer().getUniqueId());
                } catch (Exception e) {
                    logger.warn("Errore durante il salvataggio del tempo di {}", event.getPlayer().getUsername(), e);
                }
            }).schedule();
        } else {
            manager.save();
        }
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (autosaveTask != null) {
            autosaveTask.cancel();
        }
        // Fissa il tempo residuo delle sessioni aperte prima di salvare
        try {
            manager.flushOnlineSessions();
            manager.saveAll();
        } catch (Exception e) {
            logger.warn("Errore durante il salvataggio finale dei tempi di gioco", e);
        } finally {
            if (database != null) {
                database.close();
                database = null;
            }
        }
    }

    // Accesso per test / altri comandi
    public PlaytimeManager getManager() {
        return manager;
    }
}
