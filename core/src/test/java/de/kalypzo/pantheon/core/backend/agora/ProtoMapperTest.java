package de.kalypzo.pantheon.core.backend.agora;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import de.kalypzo.agora.proto.v1.AuditEntry;
import de.kalypzo.agora.proto.v1.AuditValue;
import de.kalypzo.agora.proto.v1.StoredAuditEntry;
import de.kalypzo.agora.proto.v1.TimelineRequest;
import de.kalypzo.agora.proto.v1.TimelineResponse;
import de.kalypzo.pantheon.core.audit.AuditBundle;
import de.kalypzo.pantheon.core.audit.AuditRecord;
import de.kalypzo.pantheon.core.audit.Position;
import de.kalypzo.pantheon.core.audit.StoredAuditRecord;
import de.kalypzo.pantheon.core.audit.TimelineResult;
import de.kalypzo.pantheon.core.audit.TimelineSpec;
import de.kalypzo.pantheon.core.audit.Viewer;
import de.kalypzo.pantheon.core.backend.Delivery;
import de.kalypzo.pantheon.core.backend.DeliveryKind;
import de.kalypzo.pantheon.core.backend.PresenceEntry;
import de.kalypzo.pantheon.core.data.wire.DocChanged;
import de.kalypzo.pantheon.core.data.wire.DocRef;
import de.kalypzo.pantheon.core.data.wire.DocSnapshot;
import de.kalypzo.pantheon.core.data.wire.FieldCondition;
import de.kalypzo.pantheon.core.data.wire.FieldOp;
import de.kalypzo.pantheon.core.data.wire.FieldSpec;
import de.kalypzo.pantheon.core.data.wire.PlayerDoc;
import de.kalypzo.pantheon.core.data.wire.PlayerRow;
import de.kalypzo.pantheon.core.data.wire.Predicate;
import de.kalypzo.pantheon.core.data.wire.QuerySpec;
import de.kalypzo.pantheon.core.data.wire.QueryTarget;
import de.kalypzo.pantheon.core.data.wire.Row;
import de.kalypzo.pantheon.core.data.wire.ScalarType;
import de.kalypzo.pantheon.core.data.wire.SortSpec;
import de.kalypzo.pantheon.core.data.wire.StoreDoc;
import de.kalypzo.pantheon.core.data.wire.StoreRef;
import de.kalypzo.pantheon.core.data.wire.WriteRequest;
import de.kalypzo.pantheon.world.NetworkLocation;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.junit.jupiter.api.Test;

class ProtoMapperTest {

    private static final FieldSpec COINS = new FieldSpec("economy", "coins", ScalarType.LONG, 0L);
    private static final FieldSpec RATIO = new FieldSpec("economy", "ratio", ScalarType.DOUBLE, 1.5);
    private static final FieldSpec TAGS = new FieldSpec("lobby", "tags", ScalarType.SET, List.of());
    private static final FieldSpec NAME = new FieldSpec("pantheon", "name", ScalarType.STRING, null);
    private static final UUID PLAYER = UUID.fromString("00000000-0000-7000-8000-000000000001");

    private static Object rt(Object value) {
        return ProtoMapper.fromProtoValue(ProtoMapper.toProtoValue(value));
    }

    @Test
    void longMaxStaysExact() {
        assertThat(rt(Long.MAX_VALUE)).isEqualTo(Long.MAX_VALUE);
        assertThat(rt(Long.MIN_VALUE)).isEqualTo(Long.MIN_VALUE);
        assertThat(rt(Long.MAX_VALUE - 1)).isEqualTo(Long.MAX_VALUE - 1);
        assertThat(rt(0L)).isEqualTo(0L);
        assertThat(rt(Double.MAX_VALUE)).isEqualTo(Double.MAX_VALUE);
        assertThat(rt(-0.0)).isEqualTo(-0.0);
        assertThat((Double) rt(Double.NaN)).isNaN();
        assertThat(rt(Double.POSITIVE_INFINITY)).isEqualTo(Double.POSITIVE_INFINITY);
        // via FieldOp too
        FieldOp op = new FieldOp.Set(COINS, Long.MAX_VALUE);
        assertThat(ProtoMapper.fromProto(ProtoMapper.toProto(op))).isEqualTo(op);
        // DOUBLE fields: Agora may return an integral value as long, normalised via the spec
        var asLong = de.kalypzo.agora.proto.v1.FieldOp.newBuilder().setField(ProtoMapper.toProto(RATIO))
                .setSet(ProtoMapper.toProtoValue(3L)).build();
        assertThat(ProtoMapper.fromProto(asLong)).isEqualTo(new FieldOp.Set(RATIO, 3.0));
        assertThat(ProtoMapper.toProto(new FieldOp.Set(RATIO, 3L)).getSet().hasDoubleValue()).isTrue();
        var defaultAsLong = de.kalypzo.agora.proto.v1.FieldSpec.newBuilder().setNamespace("economy").setName("ratio")
                .setType(de.kalypzo.agora.proto.v1.ScalarType.DOUBLE).setDefaultValue(ProtoMapper.toProtoValue(2L)).build();
        assertThat(ProtoMapper.fromProto(defaultAsLong).defaultValue()).isEqualTo(2.0);
    }

    @Test
    void nullValueRoundTrip() {
        assertThat(rt(null)).isNull();
        assertThat(ProtoMapper.toProtoValue(null).hasNullValue()).isTrue();
        assertThat(ProtoMapper.fromProto(ProtoMapper.toProto(NAME)).defaultValue()).isNull();
        assertThat(ProtoMapper.toProto(NAME).hasDefaultValue()).isFalse();
        FieldOp set = new FieldOp.Set(NAME, null);
        assertThat(ProtoMapper.fromProto(ProtoMapper.toProto(set))).isEqualTo(set);
        Predicate eq = new Predicate.Eq(null);
        assertThat(ProtoMapper.fromProto(ProtoMapper.toProto(eq))).isEqualTo(eq);
        assertThat(rt(java.util.Arrays.asList(1L, null))).isEqualTo(java.util.Arrays.asList(1L, null));
    }

    @Test
    void nestedMapAndListRoundTrip() {
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("a", List.of(1L, 2.5, "x", true));
        inner.put("b", Map.of());
        inner.put("c", List.of());
        inner.put("d", null);
        Map<String, Object> outer = new LinkedHashMap<>();
        outer.put("inner", inner);
        outer.put("list", List.of(List.of(List.of()), Map.of("k", Long.MIN_VALUE)));
        outer.put("empty", "");
        assertThat(rt(outer)).isEqualTo(outer);
        Row row = new Row(new PlayerRow(PLAYER, "lobby"), 7L, outer);
        assertThat(ProtoMapper.fromProto(ProtoMapper.toProto(row))).isEqualTo(row);
        Row empty = new Row(new StoreDoc(new StoreRef("lobby", "clans"), "id1"), 0L, Map.of());
        assertThat(ProtoMapper.fromProto(ProtoMapper.toProto(empty))).isEqualTo(empty);
        DocSnapshot snap = new DocSnapshot(new PlayerDoc(PLAYER), List.of(row));
        assertThat(ProtoMapper.fromProto(ProtoMapper.toProto(snap))).isEqualTo(snap);
        DocSnapshot noRows = new DocSnapshot(new StoreDoc(new StoreRef("lobby", "clans"), "x"), List.of());
        assertThat(ProtoMapper.fromProto(ProtoMapper.toProto(noRows))).isEqualTo(noRows);
        DocChanged changed = new DocChanged(new PlayerRow(PLAYER, "lobby"), 9L, false, outer, "key-1");
        assertThat(ProtoMapper.fromProto(ProtoMapper.toProto(changed))).isEqualTo(changed);
        DocChanged deleted = new DocChanged(new StoreDoc(new StoreRef("lobby", "clans"), "id"), 10L, true, Map.of(), null);
        assertThat(ProtoMapper.fromProto(ProtoMapper.toProto(deleted))).isEqualTo(deleted);
        assertThatThrownBy(() -> ProtoMapper.toProtoValue(new Object())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void allFieldOpsRoundTrip() {
        List<FieldOp> ops = List.of(
                new FieldOp.Set(COINS, 5L), new FieldOp.Unset(COINS), new FieldOp.Inc(COINS, Long.MIN_VALUE),
                new FieldOp.Max(COINS, 9L), new FieldOp.Min(RATIO, 0.5), new FieldOp.Add(TAGS, "vip"),
                new FieldOp.Remove(TAGS, "vip"), new FieldOp.Withdraw(COINS, Long.MAX_VALUE),
                new FieldOp.Set(TAGS, List.of("a", "b")));
        for (FieldOp op : ops) {
            assertThat(ProtoMapper.fromProto(ProtoMapper.toProto(op))).isEqualTo(op);
        }
        for (FieldSpec spec : List.of(COINS, RATIO, TAGS, NAME,
                new FieldSpec("a", "m", ScalarType.OBJECT, Map.of("x", 1L)),
                new FieldSpec("a", "b", ScalarType.BOOLEAN, false))) {
            assertThat(ProtoMapper.fromProto(ProtoMapper.toProto(spec))).isEqualTo(spec);
        }
        for (ScalarType t : ScalarType.values()) {
            FieldSpec s = new FieldSpec("n", "f", t, null);
            assertThat(ProtoMapper.fromProto(ProtoMapper.toProto(s))).isEqualTo(s);
        }
        SortSpec sort = new SortSpec(COINS, true);
        assertThat(ProtoMapper.fromProto(ProtoMapper.toProto(sort))).isEqualTo(sort);
        assertThat(ProtoMapper.fromProto(ProtoMapper.toProto(new SortSpec(RATIO, false)))).isEqualTo(new SortSpec(RATIO, false));
        for (QueryTarget t : List.<QueryTarget>of(new QueryTarget.Players(), new QueryTarget.StoreTarget(new StoreRef("lobby", "clans")))) {
            assertThat(ProtoMapper.fromProto(ProtoMapper.toProto(t))).isEqualTo(t);
        }
        QuerySpec spec = new QuerySpec(List.of(new FieldCondition(COINS, new Predicate.Gt(3L))),
                List.of(sort), 100, 20);
        assertThat(ProtoMapper.fromProto(ProtoMapper.toProto(spec))).isEqualTo(spec);
        for (DocRef ref : List.<DocRef>of(new PlayerDoc(PLAYER), new StoreDoc(new StoreRef("lobby", "clans"), "id"))) {
            assertThat(ProtoMapper.fromProto(ProtoMapper.toProto(ref))).isEqualTo(ref);
        }
        WriteRequest req = new WriteRequest("k", new PlayerDoc(PLAYER), ops, List.of(), Instant.ofEpochMilli(1234));
        assertThat(ProtoMapper.fromProto(ProtoMapper.toProto(req))).isEqualTo(req);
    }

    @Test
    void allPredicatesRoundTrip() {
        List<Predicate> predicates = new ArrayList<>(List.of(
                new Predicate.Eq(1L), new Predicate.Ne("x"), new Predicate.Gt(1L), new Predicate.Gte(2L),
                new Predicate.Lt(3L), new Predicate.Lte(Long.MAX_VALUE), new Predicate.Contains("vip"),
                new Predicate.In(List.of(1L, 2L, 3L)), new Predicate.In(List.of()), new Predicate.Exists(),
                new Predicate.Not(new Predicate.Not(new Predicate.Eq(true)))));
        for (Predicate p : predicates) {
            assertThat(ProtoMapper.fromProto(ProtoMapper.toProto(p))).isEqualTo(p);
            FieldCondition c = new FieldCondition(COINS, p);
            assertThat(ProtoMapper.fromProto(ProtoMapper.toProto(c))).isEqualTo(c);
        }
        // DOUBLE field condition values are normalised through the spec
        var proto = de.kalypzo.agora.proto.v1.Condition.newBuilder().setField(ProtoMapper.toProto(RATIO))
                .setPredicate(de.kalypzo.agora.proto.v1.Predicate.newBuilder().setGt(ProtoMapper.toProtoValue(2L))).build();
        assertThat(ProtoMapper.fromProto(proto)).isEqualTo(new FieldCondition(RATIO, new Predicate.Gt(2.0)));
        WriteRequest req = new WriteRequest("k", new StoreDoc(new StoreRef("a", "b"), "i"), List.of(new FieldOp.Withdraw(COINS, 5)),
                predicates.stream().map(p -> new FieldCondition(COINS, p)).toList(), Instant.ofEpochMilli(5));
        assertThat(ProtoMapper.fromProto(ProtoMapper.toProto(req))).isEqualTo(req);
    }

    @Test
    void deliveryComponentWithHoverAndClickRoundTrip() {
        Component c = Component.text("Hallo ", NamedTextColor.GOLD)
                .append(Component.text("klick").clickEvent(ClickEvent.runCommand("/spawn"))
                        .hoverEvent(HoverEvent.showText(Component.text("Tooltip", NamedTextColor.RED))));
        Delivery chat = new Delivery(DeliveryKind.CHAT, c, null, null, null);
        assertThat(ProtoMapper.fromProto(ProtoMapper.toProto(chat))).isEqualTo(chat);
        Delivery bar = new Delivery(DeliveryKind.ACTION_BAR, c, null, null, null);
        assertThat(ProtoMapper.fromProto(ProtoMapper.toProto(bar))).isEqualTo(bar);
        Title.Times times = Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(3), Duration.ofMillis(750));
        Delivery title = new Delivery(DeliveryKind.TITLE, c, Component.text("sub"), times, null);
        assertThat(ProtoMapper.fromProto(ProtoMapper.toProto(title))).isEqualTo(title);
        Delivery titleNoTimes = new Delivery(DeliveryKind.TITLE, c, Component.empty(), null, null);
        assertThat(ProtoMapper.fromProto(ProtoMapper.toProto(titleNoTimes)).times()).isNull();
        Sound sound = Sound.sound(Key.key("block.note_block.pling"), Sound.Source.MASTER, 1f, 0.5f);
        Delivery snd = new Delivery(DeliveryKind.SOUND, null, null, null, sound);
        Delivery back = ProtoMapper.fromProto(ProtoMapper.toProto(snd));
        assertThat(back).isEqualTo(snd);
        assertThat(back.content()).isNull();
        assertThat(ProtoMapper.toProto(snd).getSound().getSource()).isEqualTo("master");
        assertThat(ProtoMapper.toProto(snd).getSound().getKey()).isEqualTo("minecraft:block.note_block.pling");
    }

    @Test
    void presenceAndLocationRoundTrip() {
        PresenceEntry e = new PresenceEntry(PLAYER, "Steve", "lobby-1", "proxy-1", Locale.forLanguageTag("en-US"),
                Instant.ofEpochMilli(1700000000123L));
        assertThat(ProtoMapper.fromProto(ProtoMapper.toProto(e))).isEqualTo(e);
        assertThat(ProtoMapper.toProto(e).getLocale()).isEqualTo("en-US");
        PresenceEntry noServer = new PresenceEntry(PLAYER, "Steve", null, "proxy-1", Locale.GERMANY, Instant.ofEpochMilli(1));
        assertThat(ProtoMapper.fromProto(ProtoMapper.toProto(noServer))).isEqualTo(noServer);
        var emptyLocale = ProtoMapper.toProto(noServer).toBuilder().setLocale("").build();
        assertThat(ProtoMapper.fromProto(emptyLocale).locale()).isEqualTo(Locale.GERMANY);
        NetworkLocation loc = new NetworkLocation("lobby", "world", 1.5, -2.0, Double.MAX_VALUE, 90.5f, -10f);
        assertThat(ProtoMapper.fromProto(ProtoMapper.toProto(loc))).isEqualTo(loc);
    }

    @Test
    void writeRequestBytesRoundTrip() {
        Map<String, Object> obj = new LinkedHashMap<>();
        obj.put("n", Long.MAX_VALUE);
        obj.put("l", List.of(1L, "two", 3.5));
        WriteRequest req = new WriteRequest("018f0000-0000-7000-8000-000000000001",
                new StoreDoc(new StoreRef("lobby", "clans"), "c1"),
                List.of(new FieldOp.Inc(COINS, -7), new FieldOp.Set(new FieldSpec("lobby", "o", ScalarType.OBJECT, null), obj),
                        new FieldOp.Add(TAGS, "x")),
                List.of(new FieldCondition(COINS, new Predicate.Gte(5L))), Instant.ofEpochMilli(1_700_000_000_000L));
        byte[] bytes = ProtoMapper.serialize(req);
        assertThat(ProtoMapper.deserialize(bytes)).isEqualTo(req);
        WriteRequest plain = new WriteRequest("k", new PlayerDoc(PLAYER), List.of(), List.of(), Instant.ofEpochMilli(0));
        assertThat(ProtoMapper.deserialize(ProtoMapper.serialize(plain))).isEqualTo(plain);
        assertThatThrownBy(() -> ProtoMapper.deserialize(new byte[] {1, 2, 3})).isInstanceOf(IllegalArgumentException.class);
    }

    // ---------- audit (P-35, E28) ----------

    private static final UUID ENTRY_ID = UUID.fromString("019d6f3a-1b2c-7d4e-8f00-112233445566");
    private static final UUID TRACE = UUID.fromString("019d6f39-0000-7000-8000-00000000abcd");

    @Test
    void uuidBytesRoundTrip() {
        UUID u = UUID.fromString("01234567-89ab-cdef-fedc-ba9876543210");
        ByteString bytes = ProtoMapper.toBytes(u);
        // 16 bytes, big endian: the byte order is the textual (and for UUIDv7 the time) order
        assertThat(bytes.toByteArray()).containsExactly(0x01, 0x23, 0x45, 0x67, 0x89, 0xab, 0xcd, 0xef,
                0xfe, 0xdc, 0xba, 0x98, 0x76, 0x54, 0x32, 0x10);
        assertThat(ProtoMapper.fromBytes(bytes)).isEqualTo(u);
        assertThat(ProtoMapper.fromBytes(ProtoMapper.toBytes(new UUID(0, 0)))).isEqualTo(new UUID(0, 0));
        assertThat(ProtoMapper.fromBytes(ProtoMapper.toBytes(new UUID(-1, -1)))).isEqualTo(new UUID(-1, -1));
        assertThatThrownBy(() -> ProtoMapper.fromBytes(ByteString.copyFrom(new byte[15])))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProtoMapper.fromBytes(ByteString.EMPTY)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void auditRecordRoundTrip() {
        AuditRecord full = new AuditRecord(ENTRY_ID, PLAYER, "shop:buy", new Position("world", 12.5, 64, -3),
                UUID.randomUUID(), TRACE,
                Map.of("item", "diamond_sword", "amount", 5L, "price", 3.0, "whole", 10000000.0, "sale", true));
        AuditRecord bare = new AuditRecord(ENTRY_ID, PLAYER, "shop:close", null, null, null, Map.of());

        for (AuditRecord r : List.of(full, bare)) {
            assertThat(ProtoMapper.fromProto(ProtoMapper.toProto(r))).isEqualTo(r);
        }
        AuditEntry proto = ProtoMapper.toProto(full);
        assertThat(proto.getId()).isEqualTo(ProtoMapper.toBytes(ENTRY_ID));
        assertThat(proto.getWithMap().get("amount").getValueCase()).isEqualTo(AuditValue.ValueCase.L);
        assertThat(proto.getWithMap().get("whole").getValueCase()).isEqualTo(AuditValue.ValueCase.D);
        assertThat(ProtoMapper.toProto(bare).hasLocation()).isFalse();
        assertThat(ProtoMapper.toProto(bare).hasTarget()).isFalse();
        assertThat(ProtoMapper.toProto(bare).hasTrace()).isFalse();
        // type-true decoding: the oneof decides, an integral double stays a Double, a long stays a Long
        AuditRecord back = ProtoMapper.fromProto(proto);
        assertThat(back.with().get("whole")).isInstanceOf(Double.class).isEqualTo(10000000.0);
        assertThat(back.with().get("amount")).isInstanceOf(Long.class).isEqualTo(5L);

        AuditBundle bundle = new AuditBundle(List.of(full, bare), 7);
        assertThat(ProtoMapper.fromProto(ProtoMapper.toProto(bundle))).isEqualTo(bundle);
        assertThat(ProtoMapper.toProto(bundle).getDropped()).isEqualTo(7);

        StoredAuditRecord stored = new StoredAuditRecord(full, "lobby-02");
        assertThat(ProtoMapper.fromProto(ProtoMapper.toProto(stored))).isEqualTo(stored);
        assertThatThrownBy(() -> ProtoMapper.toProto(new AuditRecord(ENTRY_ID, PLAYER, "a:b", null, null, null,
                Map.of("list", List.of())))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void timelineSpecRoundTrip() {
        Viewer notch = new Viewer(UUID.randomUUID(), "Notch", "command");
        TimelineSpec full = new TimelineSpec(PLAYER, Instant.ofEpochMilli(1_700_000_000_000L),
                Instant.ofEpochMilli(1_700_000_360_000L), List.of("shop:buy", "economy:*"), 250, ENTRY_ID, TRACE, notch);
        TimelineSpec bare = new TimelineSpec(PLAYER, null, null, List.of(), 0, null, null,
                new Viewer(null, "console", "command"));

        for (TimelineSpec spec : List.of(full, bare)) {
            assertThat(ProtoMapper.fromProto(ProtoMapper.toProto(spec))).isEqualTo(spec);
        }
        TimelineRequest bareProto = ProtoMapper.toProto(bare);
        assertThat(bareProto.getFromMillis()).isZero(); // R26: 0 = unset
        assertThat(bareProto.getToMillis()).isZero();
        assertThat(bareProto.hasCursor()).isFalse();
        assertThat(bareProto.hasTrace()).isFalse();
        assertThat(bareProto.getViewer().hasPlayer()).isFalse(); // E37: Agora writes the nil UUID
        // no viewer: the request carries none and Agora answers INVALID_ARGUMENT
        TimelineSpec noViewer = new TimelineSpec(PLAYER, null, null, List.of(), 100, null, null, null);
        assertThat(ProtoMapper.toProto(noViewer).hasViewer()).isFalse();
        assertThat(ProtoMapper.fromProto(ProtoMapper.toProto(noViewer))).isEqualTo(noViewer);
    }

    @Test
    void timelineResponsePagesWithAndWithoutCursor() {
        StoredAuditRecord a = new StoredAuditRecord(new AuditRecord(ENTRY_ID, PLAYER, "shop:buy", null, null, null,
                Map.of("n", 1L)), "lobby-01");
        StoredAuditEntry proto = ProtoMapper.toProto(a);

        TimelineResult full = ProtoMapper.fromProto(TimelineResponse.newBuilder().addEntries(proto)
                .setCursor(ProtoMapper.toBytes(ENTRY_ID)).build());
        assertThat(full).isEqualTo(new TimelineResult(List.of(a), ENTRY_ID));
        // the page after an exactly full one may be empty; an empty page may still name a cursor
        assertThat(ProtoMapper.fromProto(TimelineResponse.getDefaultInstance()))
                .isEqualTo(new TimelineResult(List.of(), null));
        assertThat(ProtoMapper.fromProto(TimelineResponse.newBuilder().setCursor(ProtoMapper.toBytes(ENTRY_ID))
                .build())).isEqualTo(new TimelineResult(List.of(), ENTRY_ID));
        // an empty cursor means "no further entries"
        assertThat(ProtoMapper.fromProto(TimelineResponse.newBuilder().addEntries(proto).setCursor(ByteString.EMPTY)
                .build()).cursor()).isNull();
    }
}
