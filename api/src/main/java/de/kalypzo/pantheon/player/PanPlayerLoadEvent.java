package de.kalypzo.pantheon.player;

import de.kalypzo.pantheon.data.Doc;
import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/** Fired asynchronously after the data of a player has been loaded, before the join completes. */
public final class PanPlayerLoadEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID uniqueId;
    private final String name;
    private final Doc data;

    public PanPlayerLoadEvent(UUID uniqueId, String name, Doc data) {
        super(true);
        this.uniqueId = uniqueId;
        this.name = name;
        this.data = data;
    }

    public UUID uniqueId() {
        return uniqueId;
    }

    public String name() {
        return name;
    }

    public Doc data() {
        return data;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
