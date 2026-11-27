package de.kalypzo.pantheon.paper.audit;

import de.kalypzo.pantheon.core.audit.CommandResolver;
import de.kalypzo.pantheon.core.audit.ResolvedCommand;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.bukkit.Server;
import org.bukkit.command.Command;

/**
 * Resolves a typed label ({@code pay}, {@code shop:pay}) through the server's command map ({@code
 * Bukkit.getCommandMap()}), read at each call so commands registered later count. The identity is the
 * {@link Command} object, as {@code AuditService.hideCommand(Command)} hides it.
 */
public final class PaperCommandResolver implements CommandResolver {

    private final Server server;

    public PaperCommandResolver(Server server) {
        this.server = Objects.requireNonNull(server, "server");
    }

    @Override
    public Optional<ResolvedCommand> resolve(String label) {
        Command command = server.getCommandMap().getCommand(label);
        if (command == null) return Optional.empty();
        Set<String> aliases = new HashSet<>();
        for (String alias : command.getAliases()) {
            if (alias != null) aliases.add(alias);
        }
        return Optional.of(new ResolvedCommand(command, command.getName(), Set.copyOf(aliases)));
    }
}
