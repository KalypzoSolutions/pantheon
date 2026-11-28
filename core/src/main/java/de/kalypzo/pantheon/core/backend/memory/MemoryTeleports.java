package de.kalypzo.pantheon.core.backend.memory;

import de.kalypzo.pantheon.core.backend.ArrivalHandler;
import de.kalypzo.pantheon.core.backend.ArrivalStatus;
import de.kalypzo.pantheon.core.backend.ConnectStatus;
import de.kalypzo.pantheon.core.backend.ProxyConnector;
import de.kalypzo.pantheon.world.NetworkLocation;
import de.kalypzo.pantheon.world.TeleportResult;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jspecify.annotations.Nullable;

/**
 * The teleport automaton of the {@link InMemoryNetwork}, by the same rules as Agora's {@code TeleportService}. The
 * target session is the node of that id while it is connected and answers arrivals; {@code PrepareArrival} and
 * {@code CancelArrival} are calls of its {@link ArrivalHandler}, {@code ConnectPlayer} is a call of the
 * {@link ProxyConnector}. Every transition of a player runs under the lock of its slot and only changes state; the
 * calls out (which return synchronously here and re-enter the automaton) and the completion of result futures run
 * after the lock is released. Under a slot lock the network lock may be taken for a read, never the other way round.
 */
final class MemoryTeleports {

    private static final Logger LOG = Logger.getLogger(MemoryTeleports.class.getName());

    private enum Phase { WAITING, PREPARING, CONNECTING, ARRIVING }

    private static final class Flow {
        final String requestId = UUID.randomUUID().toString();
        final UUID player;
        final String server;
        final @Nullable NetworkLocation location; // null: connect only
        final CompletableFuture<TeleportResult> result = new CompletableFuture<>();
        Phase phase = Phase.WAITING;
        InMemoryNetwork.@Nullable Node target;
        @Nullable ProxyConnector proxy;
        boolean prepareSent;
        boolean connectAnswered;
        boolean done;

        Flow(UUID player, String server, @Nullable NetworkLocation location) {
            this.player = player;
            this.server = server;
            this.location = location;
        }
    }

    /** An open {@code ConnectPlayer}; compared by identity, so its expiry never clears a newer one. */
    private record OpenConnect(String requestId) {}

    private static final class Slot {
        @Nullable Flow current;
        @Nullable OpenConnect openConnect;
        boolean removed;
    }

    private final InMemoryNetwork network;
    private final TeleportTimeouts timeouts;
    private final ConcurrentHashMap<UUID, Slot> slots = new ConcurrentHashMap<>();
    /** Flows that called prepare, until they ended and their ConnectPlayer (if any) is no longer open. */
    private final ConcurrentHashMap<String, Flow> flows = new ConcurrentHashMap<>();
    private volatile @Nullable ProxyConnector proxy;

    MemoryTeleports(InMemoryNetwork network, TeleportTimeouts timeouts) {
        this.network = network;
        this.timeouts = timeouts;
    }

    void proxy(@Nullable ProxyConnector connector) {
        this.proxy = connector;
    }

    /** AR-1: a running teleport of the player ends with SUPERSEDED; while a ConnectPlayer is open, this one waits. */
    CompletableFuture<TeleportResult> teleport(UUID player, String server, @Nullable NetworkLocation location) {
        Flow flow = new Flow(player, server, location);
        transition(player, true, (slot, actions) -> {
            if (slot.current != null) finish(slot, slot.current, TeleportResult.SUPERSEDED, actions);
            slot.current = flow;
            after(timeouts.total(), player, (s, a) -> {
                if (!flow.done) finish(s, flow, TeleportResult.TIMEOUT, a);
            });
            if (slot.openConnect == null) start(slot, flow, actions); // else WAITING until it is cleared (AR-8)
        });
        return flow.result;
    }

    /** AR-6: {@code PlayerArrived}/{@code ArrivalFailed}, counted only from the node the prepare went to. */
    void reportArrival(InMemoryNetwork.Node from, String requestId, boolean arrived) {
        Flow flow = flows.get(requestId);
        if (flow == null || flow.target != from) {
            LOG.fine(() -> "Arrival report '" + requestId + "' from '" + from.nodeId() + "' matches no teleport");
            return;
        }
        transition(flow.player, false, (slot, actions) -> {
            if (flow.done) { // flows holds no WAITING flow
                LOG.fine(() -> "Arrival report '" + requestId + "' is late");
                return;
            }
            finish(slot, flow, arrived ? TeleportResult.OK : TeleportResult.CONNECT_FAILED, actions);
        });
    }

    /** AR-11: the player left the network. */
    void removed(UUID player) {
        transition(player, false, (slot, actions) -> {
            if (slot.current != null) finish(slot, slot.current, TeleportResult.NOT_ONLINE, actions);
        });
    }

    /** AR-10: a gone target ends its teleport, without cancel (it is not connected). */
    void nodeGone(InMemoryNetwork.Node node) {
        for (UUID player : slots.keySet()) {
            transition(player, false, (slot, actions) -> {
                if (slot.current != null && slot.current.target == node) {
                    finish(slot, slot.current, TeleportResult.SERVER_UNAVAILABLE, actions);
                }
            });
        }
    }

    /** AR-3: checks presence, target and proxy, then calls prepare on the target. */
    private void start(Slot slot, Flow flow, List<Runnable> actions) {
        if (!network.onServer(flow.player)) {
            finish(slot, flow, TeleportResult.NOT_ONLINE, actions);
            return;
        }
        InMemoryNetwork.Node target = network.existingNode(flow.server);
        ArrivalHandler handler = target == null ? null : target.openHandler();
        ProxyConnector connector = proxy;
        if (handler == null || connector == null) {
            LOG.fine(() -> "Teleport of " + flow.player + " to '" + flow.server + "': target or proxy not connected");
            finish(slot, flow, TeleportResult.SERVER_UNAVAILABLE, actions);
            return;
        }
        flow.target = target;
        flow.proxy = connector;
        flow.prepareSent = true;
        flow.phase = Phase.PREPARING;
        flows.put(flow.requestId, flow);
        actions.add(() -> prepare(handler, flow));
        after(timeouts.prepare(), flow.player, (s, a) -> {
            if (!flow.done && flow.phase == Phase.PREPARING) finish(s, flow, TeleportResult.TIMEOUT, a);
        });
    }

    /** Outside the lock: {@code PrepareArrival}; no answer that can be read is {@code UNSPECIFIED}. */
    private void prepare(ArrivalHandler handler, Flow flow) {
        CompletableFuture<ArrivalStatus> answer;
        try {
            answer = handler.prepare(flow.requestId, flow.player, flow.location);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Arrival handler of '" + flow.server + "' failed", e);
            answer = null;
        }
        if (answer == null) {
            arrivalPrepared(flow, null);
            return;
        }
        answer.whenComplete((status, error) -> {
            if (error != null) LOG.log(Level.WARNING, "Arrival handler of '" + flow.server + "' failed", error);
            arrivalPrepared(flow, error == null ? status : null);
        });
    }

    /** AR-4: {@code ArrivalPrepared}; {@code null} is {@code UNSPECIFIED}. */
    private void arrivalPrepared(Flow flow, @Nullable ArrivalStatus status) {
        transition(flow.player, false, (slot, actions) -> {
            if (flow.done || flow.phase != Phase.PREPARING) {
                LOG.fine(() -> "ArrivalPrepared '" + flow.requestId + "' is late");
                return;
            }
            if (status == null) {
                finish(slot, flow, TeleportResult.SERVER_UNAVAILABLE, actions);
                return;
            }
            switch (status) {
                case READY -> {
                    flow.phase = Phase.CONNECTING; // AR-2: no other ConnectPlayer of the player is open here
                    OpenConnect open = new OpenConnect(flow.requestId);
                    slot.openConnect = open;
                    ProxyConnector connector = Objects.requireNonNull(flow.proxy);
                    actions.add(() -> connect(connector, flow, open));
                    // The open ConnectPlayer expires as long after its sending as the ConnectResult may take.
                    after(timeouts.connect(), flow.player, (s, a) -> {
                        if (s.openConnect == open) clearOpenConnect(s, a);
                    });
                    after(timeouts.connect(), flow.player, (s, a) -> {
                        if (!flow.done && flow.phase == Phase.CONNECTING && !flow.connectAnswered) {
                            finish(s, flow, TeleportResult.TIMEOUT, a);
                        }
                    });
                    arrivalTimer(flow);
                }
                case ALREADY_HERE -> {
                    flow.phase = Phase.ARRIVING;
                    arrivalTimer(flow);
                }
                case WORLD_NOT_FOUND -> finish(slot, flow, TeleportResult.WORLD_NOT_FOUND, actions);
            }
        });
    }

    /** Outside the lock: {@code ConnectPlayer}; no answer that can be read is {@code FAILED}. */
    private void connect(ProxyConnector connector, Flow flow, OpenConnect open) {
        CompletableFuture<ConnectStatus> answer;
        try {
            answer = connector.connect(flow.player, flow.server);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Proxy connector failed", e);
            answer = null;
        }
        if (answer == null) {
            connectResult(flow, open, ConnectStatus.FAILED);
            return;
        }
        answer.whenComplete((status, error) -> {
            if (error != null) LOG.log(Level.WARNING, "Proxy connector failed", error);
            connectResult(flow, open, error == null && status != null ? status : ConnectStatus.FAILED);
        });
    }

    /** AR-5: {@code ConnectResult}; always clears its open ConnectPlayer, even of an ended teleport. */
    private void connectResult(Flow flow, OpenConnect open, ConnectStatus status) {
        transition(flow.player, false, (slot, actions) -> {
            if (slot.openConnect == open) clearOpenConnect(slot, actions);
            if (flow.done || flow.phase != Phase.CONNECTING || flow.connectAnswered) {
                LOG.fine(() -> "ConnectResult '" + flow.requestId + "' is late");
                return;
            }
            switch (status) {
                case CONNECTED, ALREADY_CONNECTED -> flow.connectAnswered = true;
                case PLAYER_NOT_FOUND -> finish(slot, flow, TeleportResult.NOT_ONLINE, actions);
                case FAILED -> finish(slot, flow, TeleportResult.CONNECT_FAILED, actions);
            }
        });
    }

    private void arrivalTimer(Flow flow) {
        after(timeouts.arrival(), flow.player, (s, a) -> {
            if (!flow.done && (flow.phase == Phase.CONNECTING || flow.phase == Phase.ARRIVING)) {
                finish(s, flow, TeleportResult.TIMEOUT, a);
            }
        });
    }

    /** AR-9: ends {@code flow} exactly once; a non-OK end after prepare cancels the arrival on a connected target. */
    private void finish(Slot slot, Flow flow, TeleportResult result, List<Runnable> actions) {
        if (flow.done) return;
        flow.done = true;
        if (slot.current == flow) slot.current = null;
        if (slot.openConnect == null || !slot.openConnect.requestId().equals(flow.requestId)) {
            flows.remove(flow.requestId);
        }
        LOG.fine(() -> "Teleport '" + flow.requestId + "' of " + flow.player + " to '" + flow.server + "': " + result);
        InMemoryNetwork.Node target = flow.target;
        if (flow.prepareSent && result != TeleportResult.OK && target != null) {
            actions.add(() -> {
                ArrivalHandler handler = target.openHandler();
                if (handler != null) handler.cancel(flow.requestId, flow.player);
            });
        }
        actions.add(() -> flow.result.complete(result));
    }

    /** AR-8: clears the open ConnectPlayer; a waiting teleport starts now. */
    private void clearOpenConnect(Slot slot, List<Runnable> actions) {
        String requestId = Objects.requireNonNull(slot.openConnect).requestId();
        slot.openConnect = null;
        Flow flow = flows.get(requestId);
        if (flow != null && flow.done) flows.remove(requestId);
        if (slot.current != null && slot.current.phase == Phase.WAITING) start(slot, slot.current, actions);
    }

    /** Runs {@code step} on the player's slot after {@code delay}, on the common pool. */
    private void after(Duration delay, UUID player, BiConsumer<Slot, List<Runnable>> step) {
        CompletableFuture.delayedExecutor(delay.toMillis(), TimeUnit.MILLISECONDS)
                .execute(() -> transition(player, false, step));
    }

    /**
     * Runs {@code step} under the lock of the player's slot, then the collected actions outside of it. Without
     * {@code create} a missing slot means there is nothing to do; an emptied slot is removed.
     */
    private void transition(UUID player, boolean create, BiConsumer<Slot, List<Runnable>> step) {
        List<Runnable> actions = new ArrayList<>();
        while (true) {
            Slot slot = create ? slots.computeIfAbsent(player, k -> new Slot()) : slots.get(player);
            if (slot == null) return;
            synchronized (slot) {
                if (slot.removed) {
                    if (create) continue;
                    return;
                }
                step.accept(slot, actions);
                if (slot.current == null && slot.openConnect == null) {
                    slots.remove(player, slot);
                    slot.removed = true;
                }
            }
            break;
        }
        for (Runnable action : actions) {
            try {
                action.run();
            } catch (RuntimeException e) {
                // the other actions (e.g. a complete) still run
                LOG.log(Level.WARNING, "Teleport action for " + player + " failed", e);
            }
        }
    }
}
