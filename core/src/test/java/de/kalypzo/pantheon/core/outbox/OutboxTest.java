package de.kalypzo.pantheon.core.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.kalypzo.pantheon.core.data.wire.DocRef;
import de.kalypzo.pantheon.core.data.wire.FieldCondition;
import de.kalypzo.pantheon.core.data.wire.FieldOp;
import de.kalypzo.pantheon.core.data.wire.FieldSpec;
import de.kalypzo.pantheon.core.data.wire.PlayerDoc;
import de.kalypzo.pantheon.core.data.wire.Predicate;
import de.kalypzo.pantheon.core.data.wire.ScalarType;
import de.kalypzo.pantheon.core.data.wire.StoreDoc;
import de.kalypzo.pantheon.core.data.wire.StoreRef;
import de.kalypzo.pantheon.core.data.wire.WriteRequest;
import de.kalypzo.pantheon.core.data.UuidV7;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The SQLite outbox (P-14; agora.md §8 Lokale Outbox). */
class OutboxTest {

    private static final FieldSpec COINS = new FieldSpec("economy", "coins", ScalarType.LONG, 100L);
    private static final FieldSpec TAG = new FieldSpec("economy", "tag", ScalarType.STRING, "");

    private final DocRef alice = new PlayerDoc(UUID.fromString("00000000-0000-0000-0000-00000000000a"));
    private final DocRef clan = new StoreDoc(new StoreRef("lobby", "clans"), "red");

    @TempDir
    Path dir;

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-27T10:00:00Z"));
    private final List<LogRecord> logs = new CopyOnWriteArrayList<>();
    private final Logger log = Logger.getLogger("OutboxTest." + UUID.randomUUID());
    private final List<Outbox> opened = new ArrayList<>();

    @BeforeEach
    void setUp() {
        log.setUseParentHandlers(false);
        log.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                logs.add(record);
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        });
    }

    @AfterEach
    void tearDown() {
        opened.forEach(Outbox::close);
    }

    private Path file() {
        return dir.resolve("Pantheon").resolve("outbox.db");
    }

    private Outbox open() {
        Outbox o = Outbox.open(file(), clock, log);
        opened.add(o);
        return o;
    }

    private WriteRequest request(DocRef doc, FieldOp... ops) {
        return new WriteRequest(UuidV7.next(clock).toString(), doc, List.of(ops), List.of(), clock.instant());
    }

    @Test
    void survivesReopen() {
        Outbox first = open();
        WriteRequest a = request(alice, new FieldOp.Inc(COINS, 5), new FieldOp.Set(TAG, "x"));
        WriteRequest b = request(clan, new FieldOp.Inc(COINS, -2));
        first.append(Outbox.docKey(alice), a);
        first.append(Outbox.docKey(clan), b);
        first.close();
        opened.remove(first);

        Outbox second = open();
        assertThat(second.hasEntries("player:" + ((PlayerDoc) alice).player())).isTrue();
        assertThat(second.hasEntries("lobby:clans/red")).isTrue();
        Map<String, List<OutboxEntry>> pending = second.pendingByDoc();
        assertThat(pending).containsOnlyKeys(Outbox.docKey(alice), Outbox.docKey(clan));
        OutboxEntry e = pending.get(Outbox.docKey(alice)).getFirst();
        assertThat(e.idempotencyKey()).isEqualTo(a.idempotencyKey());
        assertThat(e.docKey()).isEqualTo(Outbox.docKey(alice));
        assertThat(e.request()).isEqualTo(a);
        assertThat(e.createdAt()).isEqualTo(a.createdAt());
        assertThat(pending.get(Outbox.docKey(clan)).getFirst().request()).isEqualTo(b);

        second.remove(a.idempotencyKey());
        assertThat(second.hasEntries(Outbox.docKey(alice))).isFalse();
        second.close();
        opened.remove(second);
        assertThat(open().pendingByDoc()).containsOnlyKeys(Outbox.docKey(clan));
    }

    @Test
    void entriesKeepSeqOrderPerDoc() {
        Outbox outbox = open();
        List<WriteRequest> forAlice = new ArrayList<>();
        List<WriteRequest> forClan = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            WriteRequest a = request(alice, new FieldOp.Inc(COINS, i));
            WriteRequest c = request(clan, new FieldOp.Set(TAG, "t" + i));
            outbox.append(Outbox.docKey(alice), a);
            outbox.append(Outbox.docKey(clan), c);
            forAlice.add(a);
            forClan.add(c);
        }
        outbox.close();
        opened.remove(outbox);

        Map<String, List<OutboxEntry>> pending = open().pendingByDoc();
        assertThat(pending.get(Outbox.docKey(alice))).extracting(OutboxEntry::request).containsExactlyElementsOf(forAlice);
        assertThat(pending.get(Outbox.docKey(clan))).extracting(OutboxEntry::request).containsExactlyElementsOf(forClan);
        assertThat(pending.get(Outbox.docKey(alice))).extracting(OutboxEntry::seq).isSorted().doesNotHaveDuplicates();
    }

    @Test
    void olderThan20hMovesToDeadAndLogsError() throws Exception {
        Outbox outbox = open();
        WriteRequest a = request(alice, new FieldOp.Inc(COINS, 7));
        outbox.append(Outbox.docKey(alice), a);
        clock.advance(Duration.ofHours(20).plusMinutes(1));

        assertThat(outbox.moveExpired(Duration.ofHours(20))).isEqualTo(1);

        assertThat(outbox.hasEntries(Outbox.docKey(alice))).isFalse();
        assertThat(outbox.pendingByDoc()).isEmpty();
        LogRecord error = logs.stream().filter(r -> r.getLevel() == Level.SEVERE).findFirst().orElseThrow();
        assertThat(error.getMessage()).contains(Outbox.docKey(alice)).contains(a.idempotencyKey())
                .contains("\"op\":\"inc\"").contains("\"field\":\"economy.coins\"").contains("\"delta\":7");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file());
             ResultSet rs = c.createStatement().executeQuery("SELECT idempotency_key, doc FROM outbox_dead")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo(a.idempotencyKey());
            assertThat(rs.getString(2)).isEqualTo(Outbox.docKey(alice));
        }
        outbox.close();
        opened.remove(outbox);
        assertThat(open().pendingByDoc()).isEmpty();
    }

    @Test
    void boundaryAt20h() {
        Outbox outbox = open();
        WriteRequest old = request(alice, new FieldOp.Inc(COINS, 1));
        outbox.append(Outbox.docKey(alice), old);
        clock.advance(Duration.ofMinutes(2));
        WriteRequest young = request(clan, new FieldOp.Inc(COINS, 1));
        outbox.append(Outbox.docKey(clan), young);
        clock.advance(Duration.ofHours(19).plusMinutes(59)); // old: 20 h 01 min, young: 19 h 59 min

        assertThat(outbox.moveExpired(Duration.ofHours(20))).isEqualTo(1);

        assertThat(outbox.hasEntries(Outbox.docKey(alice))).isFalse();
        assertThat(outbox.pendingByDoc()).containsOnlyKeys(Outbox.docKey(clan));
        assertThat(outbox.pendingByDoc().get(Outbox.docKey(clan))).extracting(OutboxEntry::request)
                .containsExactly(young);
    }

    @Test
    void expiryFollowsCreationTimeNotAppendOrder() {
        Outbox outbox = open();
        Instant start = clock.instant();
        WriteRequest young = new WriteRequest(UuidV7.next(clock).toString(), clan, List.of(new FieldOp.Inc(COINS, 1)),
                List.of(), start.plusSeconds(120));
        WriteRequest old = new WriteRequest(UuidV7.next(clock).toString(), alice, List.of(new FieldOp.Inc(COINS, 1)),
                List.of(), start);
        outbox.append(Outbox.docKey(clan), young); // appended first, created later
        outbox.append(Outbox.docKey(alice), old);
        clock.advance(Duration.ofHours(20).plusMinutes(1));

        assertThat(outbox.moveExpired(Duration.ofHours(20))).isEqualTo(1);

        assertThat(outbox.contains(old.idempotencyKey())).isFalse();
        assertThat(outbox.contains(young.idempotencyKey())).isTrue();
        assertThat(outbox.pending(Outbox.docKey(clan))).extracting(OutboxEntry::request).containsExactly(young);
    }

    @Test
    void unreadableEntryMovesToDeadOnOpen() throws Exception {
        open().close();
        opened.clear();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file())) {
            c.createStatement().executeUpdate("INSERT INTO outbox (idempotency_key, doc, request, created_at) "
                    + "VALUES ('broken', 'player:x', X'FFFFFFFF', 0)");
        }

        assertThat(open().pendingByDoc()).isEmpty();

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file());
             ResultSet rs = c.createStatement().executeQuery(
                     "SELECT (SELECT count(*) FROM outbox), (SELECT count(*) FROM outbox_dead)")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isZero();
            assertThat(rs.getInt(2)).isEqualTo(1);
        }
        assertThat(logs).anyMatch(r -> r.getLevel() == Level.SEVERE && r.getMessage().contains("broken"));
    }

    @Test
    void conditionalNeverAppended() {
        Outbox outbox = open();
        WriteRequest withdraw = request(alice, new FieldOp.Withdraw(COINS, 10));
        WriteRequest conditioned = new WriteRequest(UuidV7.next(clock).toString(), alice,
                List.of(new FieldOp.Inc(COINS, 1)),
                List.of(new FieldCondition(COINS, new Predicate.Gte(10L))), clock.instant());

        assertThatThrownBy(() -> outbox.append(Outbox.docKey(alice), withdraw))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> outbox.append(Outbox.docKey(alice), conditioned))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(outbox.hasEntries(Outbox.docKey(alice))).isFalse();
        outbox.close();
        opened.remove(outbox);
        assertThat(open().pendingByDoc()).isEmpty();
    }
}
