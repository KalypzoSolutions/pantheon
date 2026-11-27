package de.kalypzo.pantheon.core.language;

import de.kalypzo.pantheon.player.PanPlayer;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import net.kyori.adventure.audience.Audience;

/** Connections to the platform module, set once at startup. Thread safe. */
public final class LanguageHooks {

    private volatile Function<Audience, Optional<PanPlayer>> playerLookup = a -> Optional.empty();
    private volatile Supplier<Audience> broadcastTarget = Audience::empty;
    private volatile Function<PanPlayer, Locale> localeOf = PanPlayer::lang;

    /** Maps a platform player (Bukkit) to its PanPlayer. */
    public Function<Audience, Optional<PanPlayer>> playerLookup() {
        return playerLookup;
    }

    public LanguageHooks playerLookup(Function<Audience, Optional<PanPlayer>> lookup) {
        this.playerLookup = Objects.requireNonNull(lookup, "lookup");
        return this;
    }

    /** Everything a broadcast goes to: all online players and the console. */
    public Supplier<Audience> broadcastTarget() {
        return broadcastTarget;
    }

    public LanguageHooks broadcastTarget(Supplier<Audience> target) {
        this.broadcastTarget = Objects.requireNonNull(target, "target");
        return this;
    }

    /** Default {@link PanPlayer#lang()}. */
    public Function<PanPlayer, Locale> localeOf() {
        return localeOf;
    }

    public LanguageHooks localeOf(Function<PanPlayer, Locale> localeOf) {
        this.localeOf = Objects.requireNonNull(localeOf, "localeOf");
        return this;
    }
}
