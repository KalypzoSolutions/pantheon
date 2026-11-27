package de.kalypzo.pantheon.core.audit;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Builds the {@code pantheon:chat} entry (audit_log.md §5). The text must already be plain text. */
public final class ChatAudit {

    public static final String ACTION = "pantheon:chat";

    private ChatAudit() {}

    public static EntryBuilder of(UUID player, String plainText, boolean cancelled, @Nullable Position position) {
        EntryBuilder b = new EntryBuilder(player, ACTION).with("text", plainText).with("cancelled", cancelled);
        if (position != null) {
            b.position(position);
        }
        return b;
    }
}
