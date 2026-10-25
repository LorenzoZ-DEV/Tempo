package dev.lorenzzz.tempo;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

public class PlaytimeCommand implements SimpleCommand {

    private final ProxyServer proxy;
    private final PlaytimeManager manager;

    public PlaytimeCommand(ProxyServer proxy, PlaytimeManager manager) {
        this.proxy = proxy;
        this.manager = manager;
    }

    @Override
    public void execute(Invocation invocation) {
        String[] args = invocation.arguments();

        if (args.length == 0) {
            if (!(invocation.source() instanceof Player self)) {
                invocation.source().sendMessage(Component.text("Uso: /tempo <giocatore>", NamedTextColor.RED));
                return;
            }
            sendStats(invocation.source(), self.getUniqueId(), manager.knownName(self.getUniqueId()));
            return;
        }

        if (args.length > 1) {
            invocation.source().sendMessage(Component.text("Uso: /tempo [giocatore]", NamedTextColor.RED));
            return;
        }

        String targetName = args[0];

        // 1) giocatore online?
        Optional<Player> online = proxy.getPlayer(targetName);
        if (online.isPresent()) {
            Player p = online.get();
            sendStats(invocation.source(), p.getUniqueId(), p.getUsername());
            return;
        }

        // 2) giocatore conosciuto (offline)? Cerca in memoria e poi direttamente sul DB.
        // Velocity esegue i comandi in async, quindi la query DB mirata e' sicura qui.
        Optional<UUID> known = manager.resolveUuidDeep(targetName);
        if (known.isPresent()) {
            sendStats(invocation.source(), known.get(), manager.knownName(known.get()));
            return;
        }

        invocation.source().sendMessage(
                Component.text("Giocatore '" + targetName + "' non trovato (mai entrato o nome errato).", NamedTextColor.RED));
    }

    private void sendStats(com.velocitypowered.api.command.CommandSource source, UUID uuid, String name) {
        if (!manager.hasData(uuid)) {
            source.sendMessage(Component.text("Nessun dato di tempo per '" + name + "'.", NamedTextColor.RED));
            return;
        }
        long daily = manager.getDailySeconds(uuid);
        long weekly = manager.getWeeklySeconds(uuid);
        long total = manager.getTotalSeconds(uuid);

        source.sendMessage(Component.text("----- Tempo di " + name + " -----", NamedTextColor.GOLD));
        source.sendMessage(Component.text("Giornaliero: ", NamedTextColor.GRAY)
                .append(Component.text(PlaytimeManager.formatDuration(daily), NamedTextColor.GREEN)));
        source.sendMessage(Component.text("Settimanale: ", NamedTextColor.GRAY)
                .append(Component.text(PlaytimeManager.formatDuration(weekly), NamedTextColor.GREEN)));
        source.sendMessage(Component.text("Totale: ", NamedTextColor.GRAY)
                .append(Component.text(PlaytimeManager.formatDuration(total), NamedTextColor.GREEN)));
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length > 1) {
            return List.of();
        }
        String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        return proxy.getAllPlayers().stream()
                .map(Player::getUsername)
                .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(prefix))
                .sorted()
                .collect(Collectors.toList());
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission("tempo.headstaff");
    }
}
