package de.kalypzo.pantheon.paper.audit;

import de.kalypzo.pantheon.core.audit.ChatAudit;
import de.kalypzo.pantheon.core.audit.CommandAudit;
import de.kalypzo.pantheon.core.audit.CommandResolver;
import de.kalypzo.pantheon.core.audit.HiddenCommands;
import de.kalypzo.pantheon.core.audit.Position;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.jspecify.annotations.Nullable;

/**
 * Logs chat ({@code pantheon:chat}) and commands ({@code pantheon:command}) of the node's players (audit_log.md §5).
 * Both run on {@code MONITOR} with {@code ignoreCancelled = false}, so a cancelled message or command is logged with
 * {@code cancelled = true}. The chat text is plain text only (no JSON, no MiniMessage tags). The position comes from
 * {@code player.getLocation()}, also in the async chat event (E35). Hidden commands (audit §6) are never logged. No IP
 * addresses. Entries go through the player's {@link PaperAuditLog}: with ID, without automatic trace, into the buffer.
 * A failure here is logged and never reaches the event.
 */
public final class AuditListener implements Listener {

    private final PaperAuditService service;
    private final HiddenCommands hidden;
    private final CommandResolver resolver;
    private final Logger log;

    public AuditListener(PaperAuditService service, HiddenCommands hidden, CommandResolver resolver, Logger log) {
        this.service = Objects.requireNonNull(service, "service");
        this.hidden = Objects.requireNonNull(hidden, "hidden");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.log = Objects.requireNonNull(log, "log");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onChat(AsyncChatEvent event) {
        try {
            Player player = event.getPlayer();
            String text = PlainTextComponentSerializer.plainText().serialize(event.message());
            log(player, ChatAudit.of(player.getUniqueId(), text, event.isCancelled(), position(player)));
        } catch (RuntimeException e) {
            log.log(Level.WARNING, "audit: chat entry failed", e);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        try {
            Player player = event.getPlayer();
            CommandAudit.of(player.getUniqueId(), event.getMessage(), event.isCancelled(), position(player), hidden,
                    resolver).ifPresent(entry -> log(player, entry));
        } catch (RuntimeException e) {
            log.log(Level.WARNING, "audit: command entry failed", e); // never with the typed line: may hold secrets
        }
    }

    private void log(Player player, de.kalypzo.pantheon.core.audit.EntryBuilder entry) {
        UUID uuid = player.getUniqueId();
        ((PaperAuditLog) service.player(uuid)).entry(entry);
    }

    private static @Nullable Position position(Player player) {
        Location l = player.getLocation();
        if (l == null || l.getWorld() == null) return null;
        try {
            return new Position(l.getWorld().getName(), l.getX(), l.getY(), l.getZ());
        } catch (IllegalArgumentException e) { // non-finite coordinates: Agora would drop the entry
            return null;
        }
    }
}
