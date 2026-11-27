package de.kalypzo.pantheon.core.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import de.kalypzo.pantheon.core.audit.AuditBackend;
import de.kalypzo.pantheon.core.audit.AuditBundle;
import de.kalypzo.pantheon.core.audit.AuditRecord;
import de.kalypzo.pantheon.core.audit.Position;
import de.kalypzo.pantheon.core.audit.StoredAuditRecord;
import de.kalypzo.pantheon.core.audit.TimelineResult;
import de.kalypzo.pantheon.core.audit.TimelineSpec;
import de.kalypzo.pantheon.core.audit.Viewer;
import de.kalypzo.pantheon.core.data.UuidV7;
import de.kalypzo.pantheon.data.DataUnavailableException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The audit contract (audit_log.md §7 Port, §8, §10, E29, E33, E37, E38, R16, R26, R48): every {@link AuditBackend}
 * must pass it, the in-memory network as well as a real Agora. Only ports and {@link ContractFixture} are used; proxy
 * actions go through the fixture, so a real Agora drives them through a simulated proxy. Times in the past are made
 * with {@link UuidV7#lowerBound} plus random bits, never with a shifted clock. Every test uses fresh players, so it
 * also runs against a shared network; session entries may be written asynchronously (Agora buffers them up to 1 s),
 * so they are awaited.
 */
public abstract class AuditBackendContract {

    protected static final String PROXY = "proxy-01";
    private static final Duration WAIT = Duration.ofSeconds(10);
    private static final UUID NIL = new UUID(0, 0);
    private static final String AUDIT_VIEW = "pantheon:audit_view";

    private ContractFixture fx;
    private Backend a;
    private Backend b;

    /** A new, empty-enough network; called once per test and closed after it. */
    protected abstract ContractFixture fixture();

    @BeforeEach
    void setUpContract() {
        fx = fixture();
        a = fx.node("lobby-01");
        b = fx.node("lobby-02");
    }

    @AfterEach
    void tearDownContract() {
        fx.close();
    }

    // ---------- append and read ----------

    @Test
    void appendedEntriesAppearInTimeline() throws Exception {
        UUID p = UUID.randomUUID();
        UUID trace = idAt(Instant.now().minusSeconds(60));
        AuditRecord full = new AuditRecord(idAt(Instant.now().minusSeconds(30)), p, "shop:buy",
                new Position("world", 12.5, 64, -3), null, trace,
                Map.of("item", "diamond_sword", "amount", 5L, "price", 3.0, "sale", true));
        AuditRecord bare = new AuditRecord(idAt(Instant.now().minusSeconds(20)), p, "shop:close", null, null, null,
                Map.of());

        get(a.append(new AuditBundle(List.of(full, bare), 0)));

        await().atMost(WAIT).ignoreExceptions().untilAsserted(() -> assertThat(records(page(a, q(p))))
                .containsExactly(bare, full));
    }

    @Test
    void serverIsAppendingNode() throws Exception {
        UUID p = UUID.randomUUID();
        AuditRecord r = rec(p, "shop:buy", Instant.now().minusSeconds(5));

        get(b.append(new AuditBundle(List.of(r), 0)));

        await().atMost(WAIT).ignoreExceptions().untilAsserted(() -> assertThat(page(a, q(p)).entries())
                .singleElement().isEqualTo(new StoredAuditRecord(r, "lobby-02")));
    }

    @Test
    void targetPutsEntryInBothTimelines() throws Exception {
        UUID p = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        AuditRecord r = new AuditRecord(idAt(Instant.now().minusSeconds(5)), p, "pantheon:private_message", null,
                target, null, Map.of("text", "hast du kurz zeit?"));

        get(a.append(new AuditBundle(List.of(r), 0)));

        await().atMost(WAIT).ignoreExceptions().untilAsserted(() -> {
            assertThat(records(page(a, q(p).actions("pantheon:private_message")))).containsExactly(r);
            assertThat(records(page(a, q(target).actions("pantheon:private_message")))).containsExactly(r);
        });
    }

    @Test
    void newestFirstAndCursorPaging() throws Exception {
        UUID p = UUID.randomUUID();
        Instant now = Instant.now();
        List<AuditRecord> byAge = new ArrayList<>();
        for (int i = 1; i <= 5; i++) byAge.add(rec(p, "test:step", now.minus(Duration.ofMinutes(i))));
        List<AuditRecord> shuffled = new ArrayList<>(byAge);
        Collections.shuffle(shuffled);

        get(a.append(new AuditBundle(shuffled, 0)));

        await().atMost(WAIT).ignoreExceptions().untilAsserted(() -> {
            TimelineResult first = page(a, q(p).actions("test:*").limit(2));
            assertThat(records(first)).containsExactly(byAge.get(0), byAge.get(1));
            assertThat(first.cursor()).isEqualTo(byAge.get(1).id());

            TimelineResult second = page(a, q(p).actions("test:*").limit(2).cursor(first.cursor()));
            assertThat(records(second)).containsExactly(byAge.get(2), byAge.get(3));
            assertThat(second.cursor()).isEqualTo(byAge.get(3).id());

            TimelineResult last = page(a, q(p).actions("test:*").limit(2).cursor(second.cursor()));
            assertThat(records(last)).containsExactly(byAge.get(4));
            assertThat(last.cursor()).isNull();
        });
    }

    @Test
    void actionAndNamespaceFilter() throws Exception {
        UUID p = UUID.randomUUID();
        Instant now = Instant.now();
        AuditRecord buy = rec(p, "shop:buy", now.minusSeconds(50));
        AuditRecord sell = rec(p, "shop:sell", now.minusSeconds(40));
        AuditRecord pay = rec(p, "economy:pay", now.minusSeconds(30));
        AuditRecord shopx = rec(p, "shopx:buy", now.minusSeconds(20));
        AuditRecord underscore = rec(p, "shop_x:buy", now.minusSeconds(15));
        AuditRecord other = rec(p, "shopaxb:buy", now.minusSeconds(10));

        get(a.append(new AuditBundle(List.of(buy, sell, pay, shopx, underscore, other), 0)));

        await().atMost(WAIT).ignoreExceptions().untilAsserted(() -> {
            assertThat(records(page(a, q(p).actions("shop:buy")))).containsExactly(buy);
            assertThat(records(page(a, q(p).actions("shop:*")))).containsExactly(sell, buy);
            assertThat(records(page(a, q(p).actions("shop:buy", "economy:pay")))).containsExactly(pay, buy);
            assertThat(records(page(a, q(p).actions("economy:*", "shop:sell")))).containsExactly(pay, sell);
            // '_' is no wildcard in a namespace pattern
            assertThat(records(page(a, q(p).actions("shop_x:*")))).containsExactly(underscore);
        });
        assertThat(failure(a.timeline(q(p).actions("shop").build()))).isInstanceOf(IllegalArgumentException.class);
        assertThat(failure(a.timeline(q(p).actions("Shop:*").build()))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void traceFilter() throws Exception {
        UUID p = UUID.randomUUID();
        UUID t1 = idAt(Instant.now().minusSeconds(60));
        UUID t2 = idAt(Instant.now().minusSeconds(59));
        Instant now = Instant.now();
        AuditRecord open = traced(p, "shop:open", now.minusSeconds(50), t1);
        AuditRecord other = traced(p, "shop:open", now.minusSeconds(45), t2);
        AuditRecord untraced = rec(p, "chat:message", now.minusSeconds(42));
        AuditRecord buy = traced(p, "shop:buy", now.minusSeconds(40), t1);

        get(a.append(new AuditBundle(List.of(open, other, untraced, buy), 0)));

        await().atMost(WAIT).ignoreExceptions().untilAsserted(() -> {
            assertThat(records(page(a, q(p).trace(t1)))).containsExactly(buy, open);
            assertThat(records(page(a, q(p).trace(t2)))).containsExactly(other);
        });
    }

    @Test
    void limitClampedTo500() throws Exception {
        UUID p = UUID.randomUUID();
        Instant base = Instant.now().minus(Duration.ofMinutes(10));
        List<AuditRecord> many = new ArrayList<>();
        for (int i = 0; i < 501; i++) many.add(rec(p, "test:many", base.plusMillis(i)));

        get(a.append(new AuditBundle(many, 0)));

        await().atMost(WAIT).ignoreExceptions().untilAsserted(() -> {
            TimelineResult page = page(a, q(p).actions("test:many").limit(1000));
            assertThat(page.entries()).hasSize(500);
            assertThat(page.cursor()).isEqualTo(page.entries().get(499).record().id());
            TimelineResult rest = page(a, q(p).actions("test:many").limit(1000).cursor(page.cursor()));
            assertThat(records(rest)).containsExactly(many.getFirst());
            assertThat(rest.cursor()).isNull();
        });
        // E33: limit 0 means the default of 100
        TimelineResult byDefault = page(a, q(p).actions("test:many").limit(0));
        assertThat(byDefault.entries()).hasSize(100);
        assertThat(byDefault.cursor()).isNotNull();
    }

    @Test
    void defaultRange24h() throws Exception {
        UUID p = UUID.randomUUID();
        Instant now = Instant.now();
        AuditRecord old = rec(p, "test:old", now.minus(Duration.ofHours(23)));
        AuditRecord recent = rec(p, "test:recent", now.minus(Duration.ofMinutes(1)));

        get(a.append(new AuditBundle(List.of(old, recent), 0)));

        await().atMost(WAIT).ignoreExceptions().untilAsserted(() -> {
            assertThat(records(page(a, q(p).actions("test:*")))).containsExactly(recent, old);
            assertThat(records(page(a, q(p).actions("test:*").between(now.minus(Duration.ofHours(2)), now))))
                    .containsExactly(recent);
        });
        // `from` is inclusive, `to` exclusive (E33), at millisecond precision
        Instant at = UuidV7.timestamp(recent.id());
        assertThat(records(page(a, q(p).actions("test:*").between(at.minusSeconds(60), at)))).isEmpty();
        assertThat(records(page(a, q(p).actions("test:*").between(at.minusSeconds(60), at.plusMillis(1)))))
                .containsExactly(recent);
        assertThat(records(page(a, q(p).actions("test:*").between(at, now)))).containsExactly(recent);
    }

    // ---------- idempotency and validation ----------

    /** Review focus 5: Agora stored the bundle, the answer got lost, Pantheon sends it again. */
    @Test
    void resentBundleStoredOnce() throws Exception {
        UUID p = UUID.randomUUID();
        Instant now = Instant.now();
        AuditRecord r1 = rec(p, "shop:buy", now.minusSeconds(30));
        AuditRecord r2 = rec(p, "shop:sell", now.minusSeconds(20));
        AuditRecord r3 = rec(p, "shop:close", now.minusSeconds(10));
        AuditBundle bundle = new AuditBundle(List.of(r1, r2), 0);

        get(a.append(bundle));
        get(a.append(bundle));
        get(b.append(new AuditBundle(List.of(r2, r3), 0))); // overlaps, from another node

        await().atMost(WAIT).ignoreExceptions().untilAsserted(() -> {
            TimelineResult page = page(a, q(p).actions("shop:*"));
            assertThat(records(page)).containsExactly(r3, r2, r1);
            // the first write wins, also for the server
            assertThat(page.entries()).extracting(StoredAuditRecord::server)
                    .containsExactly("lobby-02", "lobby-01", "lobby-01");
        });
    }

    /** §8, E29: outside −24 h/+1 h or invalid, an entry is dropped alone; the bundle is still accepted. */
    @Test
    void outOfWindowDropped() throws Exception {
        UUID p = UUID.randomUUID();
        Instant now = Instant.now();
        AuditRecord tooOld = rec(p, "test:old", now.minus(Duration.ofHours(25)));
        AuditRecord tooNew = rec(p, "test:future", now.plus(Duration.ofHours(2)));
        AuditRecord badAction = rec(p, "Test:Bad", now.minusSeconds(10));
        AuditRecord nulInText = new AuditRecord(idAt(now.minusSeconds(9)), p, "test:nul", null, null, null,
                Map.of("text", "a\0b"));
        AuditRecord valid = rec(p, "test:ok", now.minusSeconds(5));
        AuditRecord soon = rec(p, "test:soon", now.plus(Duration.ofMinutes(30)));

        get(a.append(new AuditBundle(List.of(tooOld, tooNew, badAction, nulInText, valid, soon), 3)));

        await().atMost(WAIT).ignoreExceptions().untilAsserted(() -> assertThat(records(page(a, q(p)
                .between(now.minus(Duration.ofHours(26)), now.plus(Duration.ofHours(3))))))
                .containsExactly(soon, valid));
    }

    @Test
    void missingViewerRejectedWithIAE() {
        UUID p = UUID.randomUUID();
        TimelineSpec noViewer = new TimelineSpec(p, null, null, List.of(), 100, null, null, null);
        TimelineSpec noName = q(p).viewer(new Viewer(UUID.randomUUID(), "", "command")).build();

        assertThat(failure(a.timeline(noViewer))).isInstanceOf(IllegalArgumentException.class);
        assertThat(failure(a.timeline(noName))).isInstanceOf(IllegalArgumentException.class);
    }

    /** §10, R16: the first page of a request writes {@code audit_view}; it is not on its own page, cursor pages write none. */
    @Test
    void auditViewOnFirstPageOnly() throws Exception {
        UUID p = UUID.randomUUID();
        UUID staff = UUID.randomUUID();
        Viewer notch = new Viewer(staff, "Notch", "command");
        Instant now = Instant.now();
        AuditRecord r1 = rec(p, "test:one", now.minusSeconds(30));
        AuditRecord r2 = rec(p, "test:two", now.minusSeconds(20));
        AuditRecord r3 = rec(p, "test:three", now.minusSeconds(10));
        get(a.append(new AuditBundle(List.of(r1, r2, r3), 0))); // committed when the append completes

        // the very first read of p, no range, no filter: its own view is not on its page
        Instant before = Instant.now();
        TimelineResult first = page(a, q(p).limit(2).viewer(notch));
        Instant after = Instant.now();
        assertThat(first.entries()).extracting(StoredAuditRecord::record).containsExactly(r3, r2);
        TimelineResult next = page(a, q(p).limit(2).cursor(first.cursor()).viewer(notch));
        assertThat(next.entries()).extracting(StoredAuditRecord::record).containsExactly(r1);
        // also when `to` lies after the view, the view is not on its own page
        UUID other = UUID.randomUUID();
        TimelineResult ahead = page(a, q(p).between(now.minusSeconds(60), now.plus(Duration.ofHours(1)))
                .viewer(new Viewer(other, "Jeb", "web")));
        assertThat(viewsOf(ahead)).noneSatisfy(v -> assertThat(v.record().player()).isEqualTo(other));
        assertThat(records(ahead)).containsExactly(r3, r2, r1);

        // later reads see exactly one view of Notch: the cursor page wrote none. A view is only visible once `to`
        // (now, exclusive at millisecond precision) has passed it, and every read adds its own view, so filter by viewer
        await().atMost(WAIT).ignoreExceptions().untilAsserted(() -> assertThat(viewsOf(page(b, q(p).actions(AUDIT_VIEW)))
                .stream().filter(v -> v.record().player().equals(staff)).toList()).singleElement().satisfies(v -> {
                    assertThat(v.server()).isEqualTo("lobby-01");
                    AuditRecord r = v.record();
                    assertThat(r.target()).isEqualTo(p);
                    assertThat(r.position()).isNull();
                    assertThat(r.trace()).isNull();
                    assertThat(r.with()).containsOnlyKeys("viewer_name", "via", "from", "to")
                            .containsEntry("viewer_name", "Notch").containsEntry("via", "command");
                    long to = (Long) r.with().get("to");
                    long from = (Long) r.with().get("from");
                    assertThat(to).isBetween(before.toEpochMilli(), after.toEpochMilli());
                    assertThat(to - from).isEqualTo(Duration.ofHours(24).toMillis());
                }));
        // the viewer's own timeline shows it too
        await().atMost(WAIT).ignoreExceptions().untilAsserted(() -> assertThat(viewsOf(page(b, q(staff)
                .actions(AUDIT_VIEW))).stream().filter(v -> v.record().player().equals(staff)).toList())
                .extracting(v -> v.record().target()).containsExactly(p));

        // filtered read from the console (E37): nil UUID, filters and range in `with`
        UUID trace = idAt(now.minusSeconds(100));
        Instant from = now.minus(Duration.ofHours(6));
        page(a, q(p).viewer(new Viewer(null, "console", "command")).between(from, now)
                .actions("test:one", "shop:*").trace(trace));
        await().atMost(WAIT).ignoreExceptions().untilAsserted(() -> assertThat(viewsOf(page(b, q(p).actions(AUDIT_VIEW)))
                .stream().filter(v -> "console".equals(v.record().with().get("viewer_name"))).toList())
                .singleElement().satisfies(v -> {
                    assertThat(v.record().player()).isEqualTo(NIL);
                    assertThat(v.record().with()).containsEntry("via", "command")
                            .containsEntry("from", from.toEpochMilli()).containsEntry("to", now.toEpochMilli())
                            .containsEntry("actions", "test:one,shop:*").containsEntry("trace", trace.toString());
                }));
    }

    // ---------- presence ----------

    /** E38: join, switch and quit (reason left) from the presence; the proxy is the server of these entries. */
    @Test
    void sessionEntriesFromPresence() {
        UUID p = UUID.randomUUID();
        String[] session = {"pantheon:join", "pantheon:switch", "pantheon:quit"};
        fx.join(new PresenceEntry(p, "Alice", null, PROXY, Locale.GERMANY, Instant.now()));
        fx.switchServer(p, "lobby-01");

        await().atMost(WAIT).ignoreExceptions().untilAsserted(() -> assertThat(page(a, q(p).actions(session))
                .entries()).singleElement().satisfies(e -> {
                    assertThat(e.server()).isEqualTo(PROXY);
                    assertThat(e.record().action()).isEqualTo("pantheon:join");
                    assertThat(e.record().with()).isEqualTo(Map.of("to", "lobby-01"));
                }));

        fx.switchServer(p, "lobby-02");
        await().atMost(WAIT).ignoreExceptions().untilAsserted(() -> assertThat(records(page(a, q(p).actions(session))))
                .extracting(AuditRecord::action).containsExactly("pantheon:switch", "pantheon:join"));

        fx.leave(p);
        await().atMost(WAIT).ignoreExceptions().untilAsserted(() -> {
            List<StoredAuditRecord> entries = page(a, q(p).actions(session)).entries();
            assertThat(entries).extracting(e -> e.record().action())
                    .containsExactly("pantheon:quit", "pantheon:switch", "pantheon:join");
            assertThat(entries).extracting(StoredAuditRecord::server).containsOnly(PROXY);
            assertThat(entries).allSatisfy(e -> assertThat(e.record().player()).isEqualTo(p));
            assertThat(entries.get(0).record().with()).isEqualTo(Map.of("from", "lobby-02", "reason", "left"));
            assertThat(entries.get(1).record().with()).isEqualTo(Map.of("from", "lobby-01", "to", "lobby-02"));
        });
    }

    // ---------- outage ----------

    @Test
    void outageFailsAppendWithDataUnavailable() throws Exception {
        UUID p = UUID.randomUUID();
        AuditRecord r = rec(p, "test:after", Instant.now().minusSeconds(1));
        await().atMost(WAIT).until(() -> a.state() == ConnectionState.CONNECTED);
        fx.outage();
        try {
            await().atMost(WAIT).until(() -> a.state() == ConnectionState.DISCONNECTED);
            assertThat(failure(a.append(new AuditBundle(List.of(r), 0)))).isInstanceOf(DataUnavailableException.class);
            assertThat(failure(a.timeline(q(p).build()))).isInstanceOf(DataUnavailableException.class);
        } finally {
            fx.restore();
        }
        await().atMost(Duration.ofSeconds(30)).until(() -> a.state() == ConnectionState.CONNECTED);
        await().atMost(WAIT).ignoreExceptions().untilAsserted(() -> {
            get(a.append(new AuditBundle(List.of(r), 0)));
            assertThat(records(page(a, q(p).actions("test:*")))).containsExactly(r);
        });
    }

    // ---------- helpers ----------

    /** A UUIDv7 at this instant: {@link UuidV7#lowerBound} plus random bits. */
    protected static UUID idAt(Instant t) {
        UUID low = UuidV7.lowerBound(t);
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        return new UUID(low.getMostSignificantBits() | (rnd.nextLong() & 0xFFFL),
                low.getLeastSignificantBits() | (rnd.nextLong() & 0x3FFF_FFFF_FFFF_FFFFL));
    }

    private static AuditRecord rec(UUID player, String action, Instant at) {
        return new AuditRecord(idAt(at), player, action, null, null, null, Map.of());
    }

    private static AuditRecord traced(UUID player, String action, Instant at, UUID trace) {
        return new AuditRecord(idAt(at), player, action, null, null, trace, Map.of());
    }

    private static Q q(UUID player) {
        return new Q(player);
    }

    private static TimelineResult page(AuditBackend backend, Q query) throws Exception {
        return get(backend.timeline(query.build()));
    }

    /** The entries without {@code audit_view}, which every read of an unfiltered timeline may add. */
    private static List<AuditRecord> records(TimelineResult page) {
        return page.entries().stream().map(StoredAuditRecord::record)
                .filter(r -> !r.action().equals(AUDIT_VIEW)).toList();
    }

    private static List<StoredAuditRecord> viewsOf(TimelineResult page) {
        return page.entries().stream().filter(e -> e.record().action().equals(AUDIT_VIEW)).toList();
    }

    protected static <T> T get(CompletableFuture<T> future) throws Exception {
        try {
            return future.get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Exception ex) throw ex;
            throw e;
        }
    }

    protected static Throwable failure(CompletableFuture<?> future) {
        try {
            future.get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            return e.getCause();
        } catch (InterruptedException | TimeoutException e) {
            throw new AssertionError("future did not fail in time", e);
        }
        throw new AssertionError("future completed normally");
    }

    /** A small builder for {@link TimelineSpec}; the default viewer is a staff member reading via {@code test}. */
    private static final class Q {
        private final UUID player;
        private @Nullable Instant from;
        private @Nullable Instant to;
        private List<String> actions = List.of();
        private int limit = 100;
        private @Nullable UUID cursor;
        private @Nullable UUID trace;
        private Viewer viewer = new Viewer(UUID.randomUUID(), "contract", "test");

        Q(UUID player) {
            this.player = player;
        }

        Q between(Instant from, Instant to) {
            this.from = from;
            this.to = to;
            return this;
        }

        Q actions(String... actions) {
            this.actions = List.of(actions);
            return this;
        }

        Q limit(int limit) {
            this.limit = limit;
            return this;
        }

        Q cursor(@Nullable UUID cursor) {
            this.cursor = cursor;
            return this;
        }

        Q trace(UUID trace) {
            this.trace = trace;
            return this;
        }

        Q viewer(Viewer viewer) {
            this.viewer = viewer;
            return this;
        }

        TimelineSpec build() {
            return new TimelineSpec(player, from, to, actions, limit, cursor, trace, viewer);
        }
    }
}
