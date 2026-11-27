package de.kalypzo.pantheon.core.player;

import de.kalypzo.pantheon.data.Doc;
import java.util.Objects;

/** The result of {@link LoginCoordinator#preLogin}: let the player in with the loaded data, or refuse the login. */
public sealed interface LoginOutcome {

    /** The data is loaded and this node is its holder; the player may join. */
    record Allowed(Doc doc) implements LoginOutcome {
        public Allowed {
            Objects.requireNonNull(doc, "doc");
        }
    }

    /**
     * The data could not be loaded (Agora unavailable, timeout): the login is refused with
     * {@code CommonMessage.DATA_UNAVAILABLE}, never let in with empty data (player.md §9).
     */
    record Denied(Throwable cause) implements LoginOutcome {
        public Denied {
            Objects.requireNonNull(cause, "cause");
        }
    }
}
