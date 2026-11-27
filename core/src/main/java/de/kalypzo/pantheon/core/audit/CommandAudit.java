package de.kalypzo.pantheon.core.audit;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Builds the {@code pantheon:command} entry (audit_log.md §5). */
public final class CommandAudit {

    public static final String ACTION = "pantheon:command";

    private CommandAudit() {}

    /** Removes all leading slashes and whitespace, so {@code //login} and {@code / /login} are seen as {@code login}. */
    static String stripLeading(String s) {
        int i = 0;
        while (i < s.length() && (s.charAt(i) == '/' || Character.isWhitespace(s.charAt(i)))) {
            i++;
        }
        return s.substring(i);
    }

    /** Empty if the command is hidden. {@code rawMessage} is the typed message, with or without leading slash. */
    public static Optional<EntryBuilder> of(UUID player, String rawMessage, boolean cancelled,
                                            @Nullable Position position, HiddenCommands hidden,
                                            CommandResolver resolver) {
        String line = stripLeading(rawMessage);
        String stripped = line;
        int end = 0;
        while (end < stripped.length() && !Character.isWhitespace(stripped.charAt(end))) {
            end++;
        }
        String label = stripped.substring(0, end).toLowerCase(Locale.ROOT);
        if (hidden.isHidden(label)) {
            return Optional.empty();
        }
        Optional<ResolvedCommand> resolved = resolver.resolve(label);
        String command = resolved.map(ResolvedCommand::name).orElseGet(() -> HiddenCommands.withoutNamespace(label));
        EntryBuilder b = new EntryBuilder(player, ACTION)
                .with("line", line)
                .with("command", command)
                .with("cancelled", cancelled)
                .with("unknown", resolved.isEmpty());
        if (position != null) {
            b.position(position);
        }
        return Optional.of(b);
    }
}
