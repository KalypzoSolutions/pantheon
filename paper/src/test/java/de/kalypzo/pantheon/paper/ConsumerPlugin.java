package de.kalypzo.pantheon.paper;

import de.kalypzo.pantheon.Pantheon;
import de.kalypzo.pantheon.data.CachedStore;
import de.kalypzo.pantheon.data.DataNamespace;
import org.bukkit.plugin.java.JavaPlugin;

/** A consumer that uses Pantheon in onEnable and, like a typical plugin, again in onDisable. */
public class ConsumerPlugin extends JavaPlugin {

    static final DataNamespace LOBBY = DataNamespace.of("lobby");

    Pantheon enabledWith;
    CachedStore cached;
    Pantheon disabledWith;
    int cachedDocsInOnDisable = -1;

    @Override
    public void onEnable() {
        enabledWith = Pantheon.get(this);
        cached = enabledWith.store(LOBBY, "clans").cached();
    }

    @Override
    public void onDisable() {
        try {
            disabledWith = Pantheon.get(this);
            cachedDocsInOnDisable = cached.all().size();
        } catch (IllegalStateException e) {
            // MockBukkit's unmock may disable Pantheon first; a real server disables dependents first
            disabledWith = null;
        }
    }

    /** Paper's order: the PluginDisableEvent comes first, then setEnabled(false), which runs onDisable. */
    void disableLikePaper() {
        getServer().getPluginManager().callEvent(new org.bukkit.event.server.PluginDisableEvent(this));
        setEnabled(false);
    }
}
