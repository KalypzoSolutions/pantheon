package de.kalypzo.pantheon.paper;

import de.kalypzo.pantheon.core.backend.PresenceEntry;
import de.kalypzo.pantheon.core.language.LanguageImpl;
import de.kalypzo.pantheon.core.player.NetworkPanPlayer;
import de.kalypzo.pantheon.core.player.PlayerDataEngine;
import de.kalypzo.pantheon.core.runtime.PluginScope;
import de.kalypzo.pantheon.data.Doc;
import de.kalypzo.pantheon.data.Field;
import de.kalypzo.pantheon.data.Query;
import de.kalypzo.pantheon.data.RemoteDoc;
import de.kalypzo.pantheon.data.Subscription;
import de.kalypzo.pantheon.language.LanguageProvider;
import de.kalypzo.pantheon.player.LocalPanPlayer;
import de.kalypzo.pantheon.player.PanPlayer;
import de.kalypzo.pantheon.player.PlayerFieldListener;
import de.kalypzo.pantheon.player.PlayerService;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * The players of one plugin's {@link PantheonImpl} (player.md §2-§4, §10): the shared player data of the runtime, and
 * {@link PanPlayer}s that render messages with this plugin's language. {@code onChange} belongs to the scope of the
 * plugin. Thread-safe.
 */
final class PaperPlayerService implements PlayerService {

    private final PantheonRuntime runtime;
    private final PluginScope scope;
    private final LanguageImpl language;
    private final LanguageProvider provider;

    /**
     * @param provider the language provider of the plugin as it is configured now, never the marker
     *                 ({@code defaultLocale()}): that one is resolved to the configured default language
     */
    PaperPlayerService(PantheonRuntime runtime, PluginScope scope, LanguageImpl language, LanguageProvider provider) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.language = Objects.requireNonNull(language, "language");
        this.provider = Objects.requireNonNull(provider, "provider");
    }

    private PlayerDataEngine engine() {
        return runtime.started().players();
    }

    /** The network view of a player for this plugin's language. */
    NetworkPanPlayer network(UUID uuid, @Nullable String name) {
        var started = runtime.started();
        return new NetworkPanPlayer(uuid, started.presence(), runtime::localPlayer, started.backend(),
                started.backend(), language, provider, runtime.mainThread(), name, started.nodeId(),
                runtime.audit()::player);
    }

    private LocalPanPlayer view(LocalPanPlayer handle) {
        UUID uuid = handle.uniqueId();
        return new PluginLocalPanPlayer(handle, network(uuid, handle.name()),
                () -> runtime.localPlayers().get(uuid).map(this::view));
    }

    // ---------- PanPlayerFactory ----------

    @Override
    public LocalPanPlayer local(Player player) {
        Objects.requireNonNull(player, "player");
        return view(runtime.panPlayers().local(player));
    }

    @Override
    public Optional<PanPlayer> online(UUID uuid) {
        Objects.requireNonNull(uuid, "uuid");
        Optional<LocalPanPlayerImpl> here = runtime.localPlayers().get(uuid);
        if (here.isPresent()) return Optional.of(view(here.get()));
        return runtime.started().presence().get(uuid).map(e -> network(e.player(), e.name()));
    }

    @Override
    public Optional<PanPlayer> online(String name) {
        Objects.requireNonNull(name, "name");
        for (LocalPanPlayerImpl p : runtime.localPlayers().all()) {
            if (p.name().equalsIgnoreCase(name)) return Optional.of(view(p));
        }
        return runtime.started().presence().byName(name).map(e -> network(e.player(), e.name()));
    }

    @Override
    public Collection<PanPlayer> online() {
        Map<UUID, PanPlayer> all = new LinkedHashMap<>();
        for (LocalPanPlayerImpl p : runtime.localPlayers().all()) all.put(p.uniqueId(), view(p));
        for (PresenceEntry e : runtime.started().presence().all()) {
            all.computeIfAbsent(e.player(), id -> network(id, e.name()));
        }
        return List.copyOf(all.values());
    }

    @Override
    public Collection<LocalPanPlayer> onlineLocal() {
        return runtime.localPlayers().all().stream().<LocalPanPlayer>map(this::view).toList();
    }

    // ---------- the data ----------

    @Override
    public RemoteDoc data(UUID player) {
        return engine().remote(player);
    }

    @Override
    public CompletableFuture<Optional<Doc>> load(UUID player) {
        return engine().loadSnapshot(player);
    }

    @Override
    public Query query() {
        return engine().query();
    }

    @Override
    public CompletableFuture<Long> rank(Field<? extends Number> field, UUID player) {
        return engine().rank(field, player);
    }

    @Override
    public CompletableFuture<Boolean> transfer(Field<Long> field, UUID from, UUID to, long amount) {
        return engine().transfer(field, from, to, amount);
    }

    @Override
    public CompletableFuture<Void> index(Field<?>... fields) {
        return engine().index(fields);
    }

    @Override
    public <T> Subscription onChange(Field<T> field, PlayerFieldListener<T> listener) {
        Objects.requireNonNull(listener, "listener");
        if (scope.isClosed()) return () -> {};
        return scope.onPlayerChange(engine(), field, (uuid, before, after) ->
                runtime.localPlayers().get(uuid).ifPresent(p -> listener.changed(view(p), before, after)));
    }
}
