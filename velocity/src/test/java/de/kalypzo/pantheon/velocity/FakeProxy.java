package de.kalypzo.pantheon.velocity;

import de.kalypzo.agora.proto.v1.ClaimPresenceRequest;
import de.kalypzo.agora.proto.v1.ClaimPresenceResponse;
import de.kalypzo.agora.proto.v1.NodeMessage;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

/** Fakes for the Velocity seam and the Agora link. */
final class FakeProxy {

    private FakeProxy() {
    }

    static final class Player implements ProxyPlayer {
        final UUID uuid;
        final String name;
        volatile String server;
        volatile String locale = "de_DE";
        final List<Object> received = new CopyOnWriteArrayList<>();
        volatile Component disconnected;
        volatile boolean soundSupported = true;

        Player(UUID uuid, String name, String server) {
            this.uuid = uuid;
            this.name = name;
            this.server = server;
        }

        @Override public UUID uuid() { return uuid; }
        @Override public String name() { return name; }
        @Override public Optional<String> currentServer() { return Optional.ofNullable(server); }
        @Override public String locale() { return locale; }
        @Override public void sendMessage(Component c) { received.add(c); }
        @Override public void sendActionBar(Component c) { received.add(Map.entry("actionbar", c)); }
        @Override public void showTitle(Title t) { received.add(t); }
        @Override public boolean playSound(Sound s) { if (soundSupported) received.add(s); return soundSupported; }
        @Override public void disconnect(Component c) { disconnected = c; }
    }

    static final class Players implements ProxyPlayers {
        final Map<UUID, Player> online = new LinkedHashMap<>();

        Player add(Player p) {
            synchronized (online) { online.put(p.uuid, p); }
            return p;
        }

        @Override public Collection<ProxyPlayer> online() {
            synchronized (online) { return new ArrayList<>(online.values()); }
        }

        @Override public Optional<ProxyPlayer> find(UUID uuid) {
            synchronized (online) { return Optional.ofNullable(online.get(uuid)); }
        }
    }

    static final class Link implements AgoraLink {
        final List<ClaimPresenceRequest> claims = new CopyOnWriteArrayList<>();
        final List<NodeMessage> sent = new CopyOnWriteArrayList<>();
        volatile CompletableFuture<ClaimPresenceResponse> nextClaim = CompletableFuture.completedFuture(
                ClaimPresenceResponse.newBuilder().setAccepted(true).build());

        @Override public CompletableFuture<ClaimPresenceResponse> claimPresence(ClaimPresenceRequest request) {
            claims.add(request);
            return nextClaim;
        }

        @Override public boolean send(NodeMessage message) { sent.add(message); return true; }
    }
}
