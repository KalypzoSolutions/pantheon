package de.kalypzo.pantheon.audit;

import java.util.UUID;
import org.bukkit.command.Command;

/** Entry point of the audit API, see {@code Pantheon.audit()}. Thread safe. */
public interface AuditService {

    /** Audit log for a player, also for offline players. */
    AuditLog player(UUID player);

    /** Never logs this command's arguments, whatever its label. */
    void hideCommand(Command command);

    /**
     * Never logs commands with this label. Leading slashes, surrounding whitespace and case are ignored
     * ({@code "/2FA"} hides {@code /2fa}); a blank label throws {@link IllegalArgumentException}.
     */
    void hideCommand(String label);

    TimelineQuery timeline(UUID player);
}
