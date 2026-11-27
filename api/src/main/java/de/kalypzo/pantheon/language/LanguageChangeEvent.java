package de.kalypzo.pantheon.language;

import de.kalypzo.pantheon.player.PanPlayer;
import java.util.Locale;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/** Fired when the language of a player changes. */
public final class LanguageChangeEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final PanPlayer player;
    private final Locale from;
    private final Locale to;

    public LanguageChangeEvent(PanPlayer player, Locale from, Locale to, boolean async) {
        super(async);
        this.player = player;
        this.from = from;
        this.to = to;
    }

    public PanPlayer player() {
        return player;
    }

    public Locale from() {
        return from;
    }

    public Locale to() {
        return to;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
