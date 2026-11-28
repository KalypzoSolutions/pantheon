package de.kalypzo.pantheon.core.player;

import de.kalypzo.pantheon.audit.AuditLog;
import de.kalypzo.pantheon.core.backend.MessageBackend;
import de.kalypzo.pantheon.core.backend.PresenceBackend;
import de.kalypzo.pantheon.core.backend.PresenceEntry;
import de.kalypzo.pantheon.core.language.LanguageImpl;
import de.kalypzo.pantheon.language.Arg;
import de.kalypzo.pantheon.language.LanguageProvider;
import de.kalypzo.pantheon.language.Message;
import de.kalypzo.pantheon.language.MessageKey;
import de.kalypzo.pantheon.player.ExpectedOnlinePlayerException;
import de.kalypzo.pantheon.player.LocalPanPlayer;
import de.kalypzo.pantheon.player.PanPlayer;
import de.kalypzo.pantheon.world.NetworkLocation;
import de.kalypzo.pantheon.world.TeleportResult;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.identity.Identity;
import net.kyori.adventure.pointer.Pointers;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.jspecify.annotations.Nullable;

/**
 * A player somewhere in the network (player.md §2-§4). The handle is only a UUID: every call looks at the
 * {@link PresenceCache} and at the players of this server, so it survives the quit and a server switch. Messages are
 * rendered here with the {@link LanguageImpl} of the plugin that owns the instance, in the language of the player,
 * and reach a player on another server through the {@link MessageBackend} (E2).
 *
 * <p>Stays free of Bukkit (R9): a player on this server is its own audience through its {@link LocalPanPlayer}, its
 * position comes from {@link LocalPanPlayer#location()}. Thread safe.
 *
 * <p>{@code mainThread} is part of the constructor of the card but unused here: Bukkit is read by the local handle.
 */
public final class NetworkPanPlayer implements PanPlayer {

    private static final Logger LOG = Logger.getLogger(NetworkPanPlayer.class.getName());

    private final UUID uuid;
    private final PresenceCache presence;
    private final Function<UUID, Optional<LocalPanPlayer>> local;
    private final MessageBackend messages;
    private final PresenceBackend presenceBackend;
    private final LanguageImpl language;
    private final LanguageProvider provider;
    private final Executor mainThread;
    private final @Nullable String knownName;
    private final @Nullable String nodeId;
    private final Function<UUID, AuditLog> audit;
    private final Pointers pointers;

    /** @param audit the audit log of a player (P-36: the node's one buffer, audit §4) */
    public NetworkPanPlayer(UUID uuid, PresenceCache presence, Function<UUID, Optional<LocalPanPlayer>> local,
                            MessageBackend messages, PresenceBackend presenceBackend, LanguageImpl language,
                            LanguageProvider provider, Executor mainThread, Function<UUID, AuditLog> audit) {
        this(uuid, presence, local, messages, presenceBackend, language, provider, mainThread, null, null, audit);
    }

    /**
     * @param knownName the name to use while the presence does not know the player (null: the UUID)
     * @param nodeId this server, reported by {@link #server()} for a local player the presence does not list yet
     * @param audit the audit log of a player (P-36: the node's one buffer, audit §4)
     */
    public NetworkPanPlayer(UUID uuid, PresenceCache presence, Function<UUID, Optional<LocalPanPlayer>> local,
                            MessageBackend messages, PresenceBackend presenceBackend, LanguageImpl language,
                            LanguageProvider provider, Executor mainThread, @Nullable String knownName,
                            @Nullable String nodeId, Function<UUID, AuditLog> audit) {
        this.uuid = Objects.requireNonNull(uuid, "uuid");
        this.presence = Objects.requireNonNull(presence, "presence");
        this.local = Objects.requireNonNull(local, "local");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.presenceBackend = Objects.requireNonNull(presenceBackend, "presenceBackend");
        this.language = Objects.requireNonNull(language, "language");
        this.provider = Objects.requireNonNull(provider, "provider");
        this.mainThread = Objects.requireNonNull(mainThread, "mainThread");
        this.knownName = knownName;
        this.nodeId = nodeId;
        this.audit = Objects.requireNonNull(audit, "audit");
        this.pointers = Pointers.builder()
                .withDynamic(Identity.UUID, () -> this.uuid)
                .withDynamic(Identity.NAME, this::name)
                .withDynamic(Identity.LOCALE, this::lang)
                .build();
    }

    /** A light handle like this one (audit §4): also for a player on another server or offline. */
    @Override
    public AuditLog audit() {
        return audit.apply(uuid);
    }

    @Override
    public UUID uniqueId() {
        return uuid;
    }

    @Override
    public String name() {
        return presence.get(uuid).map(PresenceEntry::name).orElseGet(() -> knownName != null ? knownName : uuid.toString());
    }

    @Override
    public Locale lang() {
        // the marker means the language configured as default; E26: the presence language is not read by it
        if (provider == LanguageProvider.defaultLocale()) return language.defaultLocale();
        Locale locale = provider.lang(this);
        return locale != null ? locale : language.defaultLocale();
    }

    @Override
    public boolean isOnline() {
        return local.apply(uuid).isPresent() || presence.get(uuid).isPresent();
    }

    @Override
    public boolean isLocal() {
        return local.apply(uuid).isPresent();
    }

    @Override
    public Optional<String> server() {
        Optional<String> listed = presence.get(uuid).map(PresenceEntry::server);
        if (listed.isPresent()) return listed;
        return nodeId != null && isLocal() ? Optional.of(nodeId) : Optional.empty();
    }

    @Override
    public Optional<LocalPanPlayer> local() {
        return local.apply(uuid);
    }

    @Override
    public CompletableFuture<NetworkLocation> location() {
        Optional<LocalPanPlayer> here = local.apply(uuid);
        if (here.isPresent()) {
            try {
                return here.get().location(); // R9: read on the main thread by the local handle
            } catch (RuntimeException e) {
                return CompletableFuture.failedFuture(e);
            }
        }
        if (presence.get(uuid).isEmpty()) {
            return CompletableFuture.failedFuture(
                    new ExpectedOnlinePlayerException(uuid, ExpectedOnlinePlayerException.Scope.NETWORK));
        }
        return presenceBackend.location(uuid); // R42: timeout and found=false are mapped by the backend
    }

    @Override
    public CompletableFuture<TeleportResult> teleport(NetworkLocation location) {
        Objects.requireNonNull(location, "location");
        Optional<LocalPanPlayer> here = local.apply(uuid);
        if (here.isPresent() && location.serverName().equals(nodeId)) {
            return viaHandle(here.get(), h -> h.teleport(location));
        }
        return viaBackend(here.isPresent(), location.serverName(), location);
    }

    @Override
    public CompletableFuture<TeleportResult> connect(String server) {
        Objects.requireNonNull(server, "server");
        Optional<LocalPanPlayer> here = local.apply(uuid);
        if (here.isPresent() && server.equals(nodeId)) return CompletableFuture.completedFuture(TeleportResult.OK);
        return viaBackend(here.isPresent(), server, null);
    }

    private CompletableFuture<TeleportResult> viaHandle(LocalPanPlayer handle,
            Function<LocalPanPlayer, CompletableFuture<TeleportResult>> call) {
        try {
            return call.apply(handle);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Teleport von " + uuid + " fehlgeschlagen", e);
            return CompletableFuture.completedFuture(TeleportResult.CONNECT_FAILED);
        }
    }

    private CompletableFuture<TeleportResult> viaBackend(boolean isLocal, String server,
                                                         @Nullable NetworkLocation location) {
        if (!isLocal && presence.get(uuid).isEmpty()) {
            return CompletableFuture.completedFuture(TeleportResult.NOT_ONLINE);
        }
        return presenceBackend.teleport(uuid, server, location);
    }

    @Override
    public Audience audience() {
        Optional<LocalPanPlayer> here = local.apply(uuid);
        if (here.isPresent()) return here.get();
        if (presence.get(uuid).isPresent()) return new RemoteAudience(uuid, messages);
        if (LOG.isLoggable(Level.FINE)) LOG.log(Level.FINE, "Nachricht an " + uuid + " verworfen: offline");
        return Audience.empty();
    }

    /** Adventure splits a title into parts for a forwarding audience; a remote player needs it whole. */
    @Override
    public void showTitle(net.kyori.adventure.title.Title title) {
        audience().showTitle(title);
    }

    @Override
    public Pointers pointers() {
        return pointers;
    }

    // ---------- messages (language.md §4) ----------

    @Override
    public void sendMessage(MessageKey key, Arg... args) {
        message(key, args).send(this);
    }

    @Override
    public void sendActionBar(MessageKey key, Arg... args) {
        message(key, args).actionbar(this);
    }

    @Override
    public void showTitle(MessageKey key, Arg... args) {
        message(key, args).title(this);
    }

    private Message message(MessageKey key, Arg... args) {
        Objects.requireNonNull(key, "key");
        Message message = language.message(key);
        for (Arg arg : args) {
            message = switch (arg) {
                case Arg.Value v -> message.with(v.name(), v.value());
                case Arg.PerViewer v -> message.with(v.name(), v.value());
                case Arg.Parsed p -> message.with(Placeholder.parsed(p.name(), p.miniMessage()));
                case Arg.Resolver r -> message.with(r.resolver());
            };
        }
        return message;
    }

    /** R52: equal iff the other is a PanPlayer with the same UUID, whatever the view or the state. */
    @Override
    public boolean equals(Object o) {
        return o instanceof PanPlayer p && p.uniqueId().equals(uuid);
    }

    @Override
    public int hashCode() {
        return uuid.hashCode();
    }

    @Override
    public String toString() {
        return "PanPlayer[" + uuid + "]";
    }
}
