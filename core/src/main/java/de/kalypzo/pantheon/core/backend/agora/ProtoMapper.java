package de.kalypzo.pantheon.core.backend.agora;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import de.kalypzo.agora.proto.v1.ApplyRequest;
import de.kalypzo.agora.proto.v1.AuditEntry;
import de.kalypzo.agora.proto.v1.AuditLocation;
import de.kalypzo.agora.proto.v1.AuditValue;
import de.kalypzo.agora.proto.v1.AuditViewer;
import de.kalypzo.agora.proto.v1.StoredAuditEntry;
import de.kalypzo.agora.proto.v1.TeleportStatus;
import de.kalypzo.agora.proto.v1.TimelineRequest;
import de.kalypzo.agora.proto.v1.TimelineResponse;
import de.kalypzo.agora.proto.v1.Condition;
import de.kalypzo.agora.proto.v1.Sort;
import de.kalypzo.agora.proto.v1.SoundSpec;
import de.kalypzo.agora.proto.v1.TitleTimes;
import de.kalypzo.agora.proto.v1.Value;
import de.kalypzo.agora.proto.v1.ValueList;
import de.kalypzo.agora.proto.v1.ValueMap;
import de.kalypzo.pantheon.core.audit.AuditBundle;
import de.kalypzo.pantheon.core.audit.AuditRecord;
import de.kalypzo.pantheon.core.audit.Position;
import de.kalypzo.pantheon.core.audit.StoredAuditRecord;
import de.kalypzo.pantheon.core.audit.TimelineResult;
import de.kalypzo.pantheon.core.audit.TimelineSpec;
import de.kalypzo.pantheon.core.audit.Viewer;
import de.kalypzo.pantheon.core.backend.ArrivalStatus;
import de.kalypzo.pantheon.core.backend.ConnectStatus;
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
import de.kalypzo.pantheon.core.data.wire.RowRef;
import de.kalypzo.pantheon.core.data.wire.ScalarType;
import de.kalypzo.pantheon.core.data.wire.SortSpec;
import de.kalypzo.pantheon.core.data.wire.StoreDoc;
import de.kalypzo.pantheon.core.data.wire.StoreRef;
import de.kalypzo.pantheon.core.data.wire.WriteRequest;
import de.kalypzo.pantheon.world.NetworkLocation;
import de.kalypzo.pantheon.world.TeleportResult;
import java.nio.ByteBuffer;
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
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.title.Title;
import org.jspecify.annotations.Nullable;

/**
 * Pure, stateless mapping between the core wire model and the Agora proto messages.
 *
 * <p>Wire values are {@code null | Boolean | Long | Double | String | List | Map<String,Object>}. Numbers of a
 * {@link FieldSpec} are normalised via its {@link ScalarType} in both directions (Agora may return an integral value
 * of a DOUBLE field as long). Proto types that share a simple name with a wire type are fully qualified.
 */
public final class ProtoMapper {

    /** Language used when a peer sends no locale (E26 / language default {@code de_DE}). */
    private static final Locale DEFAULT_LOCALE = Locale.GERMANY;
    private static final GsonComponentSerializer GSON = GsonComponentSerializer.gson();

    private ProtoMapper() {}

    // ---------- values ----------

    public static Value toProtoValue(@Nullable Object value) {
        Value.Builder b = Value.newBuilder();
        switch (value) {
            case null -> b.setNullValue(true);
            case Boolean v -> b.setBoolValue(v);
            case Long v -> b.setLongValue(v);
            case Double v -> b.setDoubleValue(v);
            case String v -> b.setStringValue(v);
            case List<?> list -> {
                ValueList.Builder l = ValueList.newBuilder();
                for (Object o : list) {
                    l.addValues(toProtoValue(o));
                }
                b.setListValue(l);
            }
            case Map<?, ?> map -> {
                ValueMap.Builder m = ValueMap.newBuilder();
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    m.putFields((String) e.getKey(), toProtoValue(e.getValue()));
                }
                b.setMapValue(m);
            }
            default -> throw new IllegalArgumentException("Not a wire value: " + value.getClass().getName());
        }
        return b.build();
    }

    public static @Nullable Object fromProtoValue(Value value) {
        return switch (value.getKindCase()) {
            case NULL_VALUE, KIND_NOT_SET -> null;
            case BOOL_VALUE -> value.getBoolValue();
            case LONG_VALUE -> value.getLongValue();
            case DOUBLE_VALUE -> value.getDoubleValue();
            case STRING_VALUE -> value.getStringValue();
            case LIST_VALUE -> fromProtoList(value.getListValue());
            case MAP_VALUE -> fromProtoFields(value.getMapValue().getFieldsMap());
        };
    }

    private static List<Object> fromProtoList(ValueList list) {
        List<Object> out = new ArrayList<>(list.getValuesCount());
        for (Value v : list.getValuesList()) {
            out.add(fromProtoValue(v));
        }
        return out;
    }

    private static Map<String, Object> fromProtoFields(Map<String, Value> fields) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Value> e : fields.entrySet()) {
            out.put(e.getKey(), fromProtoValue(e.getValue()));
        }
        return out;
    }

    private static Map<String, Value> toProtoFields(Map<String, Object> data) {
        Map<String, Value> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : data.entrySet()) {
            out.put(e.getKey(), toProtoValue(e.getValue()));
        }
        return out;
    }

    /** Normalises a scalar number to the type of the field; everything else passes unchanged. */
    private static @Nullable Object coerce(FieldSpec spec, @Nullable Object value) {
        if (!(value instanceof Number n)) {
            return value;
        }
        return switch (spec.type()) {
            case DOUBLE -> n.doubleValue();
            case LONG -> {
                if (n instanceof Long) {
                    yield n;
                }
                double d = n.doubleValue();
                yield d == Math.rint(d) && d >= -0x1p63 && d < 0x1p63 ? (Object) (long) d : n;
            }
            default -> value;
        };
    }

    private static Value coerced(FieldSpec spec, @Nullable Object value) {
        return toProtoValue(coerce(spec, value));
    }

    private static @Nullable Object coerced(FieldSpec spec, Value value) {
        return coerce(spec, fromProtoValue(value));
    }

    // ---------- field spec ----------

    public static de.kalypzo.agora.proto.v1.FieldSpec toProto(FieldSpec spec) {
        var b = de.kalypzo.agora.proto.v1.FieldSpec.newBuilder().setNamespace(spec.namespace()).setName(spec.name())
                .setType(toProto(spec.type()));
        if (spec.defaultValue() != null) {
            b.setDefaultValue(coerced(spec, spec.defaultValue()));
        }
        return b.build();
    }

    public static FieldSpec fromProto(de.kalypzo.agora.proto.v1.FieldSpec spec) {
        ScalarType type = fromProto(spec.getType());
        FieldSpec bare = new FieldSpec(spec.getNamespace(), spec.getName(), type, null);
        Object def = spec.hasDefaultValue() ? coerced(bare, spec.getDefaultValue()) : null;
        return new FieldSpec(spec.getNamespace(), spec.getName(), type, def);
    }

    public static de.kalypzo.agora.proto.v1.ScalarType toProto(ScalarType type) {
        return de.kalypzo.agora.proto.v1.ScalarType.valueOf(type.name());
    }

    public static ScalarType fromProto(de.kalypzo.agora.proto.v1.ScalarType type) {
        if (type == de.kalypzo.agora.proto.v1.ScalarType.SCALAR_TYPE_UNSPECIFIED
                || type == de.kalypzo.agora.proto.v1.ScalarType.UNRECOGNIZED) {
            throw new IllegalArgumentException("Unknown scalar type: " + type);
        }
        return ScalarType.valueOf(type.name());
    }

    // ---------- ops ----------

    public static de.kalypzo.agora.proto.v1.FieldOp toProto(FieldOp op) {
        FieldSpec f = op.field();
        var b = de.kalypzo.agora.proto.v1.FieldOp.newBuilder().setField(toProto(f));
        switch (op) {
            case FieldOp.Set o -> b.setSet(coerced(f, o.value()));
            case FieldOp.Unset o -> b.setUnset(true);
            case FieldOp.Inc o -> b.setInc(o.delta());
            case FieldOp.Max o -> b.setMax(coerced(f, o.value()));
            case FieldOp.Min o -> b.setMin(coerced(f, o.value()));
            case FieldOp.Add o -> b.setAdd(toProtoValue(o.element()));
            case FieldOp.Remove o -> b.setRemove(toProtoValue(o.element()));
            case FieldOp.Withdraw o -> b.setWithdraw(o.amount());
        }
        return b.build();
    }

    public static FieldOp fromProto(de.kalypzo.agora.proto.v1.FieldOp op) {
        FieldSpec f = fromProto(op.getField());
        return switch (op.getOpCase()) {
            case SET -> new FieldOp.Set(f, coerced(f, op.getSet()));
            case UNSET -> new FieldOp.Unset(f);
            case INC -> new FieldOp.Inc(f, op.getInc());
            case MAX -> new FieldOp.Max(f, nonNull(coerced(f, op.getMax())));
            case MIN -> new FieldOp.Min(f, nonNull(coerced(f, op.getMin())));
            case ADD -> new FieldOp.Add(f, fromProtoValue(op.getAdd()));
            case REMOVE -> new FieldOp.Remove(f, fromProtoValue(op.getRemove()));
            case WITHDRAW -> new FieldOp.Withdraw(f, op.getWithdraw());
            case OP_NOT_SET -> throw new IllegalArgumentException("FieldOp without operation");
        };
    }

    private static Object nonNull(@Nullable Object o) {
        if (o == null) {
            throw new IllegalArgumentException("Null value not allowed here");
        }
        return o;
    }

    // ---------- predicates, conditions, sort ----------

    /** Predicate values are mapped without a field; see {@link #toProto(FieldCondition)} for normalisation. */
    public static de.kalypzo.agora.proto.v1.Predicate toProto(Predicate p) {
        return toProto(null, p);
    }

    public static Predicate fromProto(de.kalypzo.agora.proto.v1.Predicate p) {
        return fromProto(null, p);
    }

    private static Value pv(@Nullable FieldSpec f, @Nullable Object v) {
        return f == null ? toProtoValue(v) : coerced(f, v);
    }

    private static @Nullable Object pv(@Nullable FieldSpec f, Value v) {
        return f == null ? fromProtoValue(v) : coerced(f, v);
    }

    private static de.kalypzo.agora.proto.v1.Predicate toProto(@Nullable FieldSpec f, Predicate p) {
        var b = de.kalypzo.agora.proto.v1.Predicate.newBuilder();
        switch (p) {
            case Predicate.Eq x -> b.setEq(pv(f, x.value()));
            case Predicate.Ne x -> b.setNe(pv(f, x.value()));
            case Predicate.Gt x -> b.setGt(pv(f, x.value()));
            case Predicate.Gte x -> b.setGte(pv(f, x.value()));
            case Predicate.Lt x -> b.setLt(pv(f, x.value()));
            case Predicate.Lte x -> b.setLte(pv(f, x.value()));
            case Predicate.Contains x -> b.setContains(toProtoValue(x.value()));
            case Predicate.In x -> {
                ValueList.Builder l = ValueList.newBuilder();
                for (Object o : x.values()) {
                    l.addValues(pv(f, o));
                }
                b.setInValues(l);
            }
            case Predicate.Exists x -> b.setExists(true);
            case Predicate.Not x -> b.setNegate(toProto(f, x.predicate()));
        }
        return b.build();
    }

    private static Predicate fromProto(@Nullable FieldSpec f, de.kalypzo.agora.proto.v1.Predicate p) {
        return switch (p.getKindCase()) {
            case EQ -> new Predicate.Eq(pv(f, p.getEq()));
            case NE -> new Predicate.Ne(pv(f, p.getNe()));
            case GT -> new Predicate.Gt(nonNull(pv(f, p.getGt())));
            case GTE -> new Predicate.Gte(nonNull(pv(f, p.getGte())));
            case LT -> new Predicate.Lt(nonNull(pv(f, p.getLt())));
            case LTE -> new Predicate.Lte(nonNull(pv(f, p.getLte())));
            case CONTAINS -> new Predicate.Contains(nonNull(fromProtoValue(p.getContains())));
            case IN_VALUES -> {
                List<Object> values = new ArrayList<>();
                for (Value v : p.getInValues().getValuesList()) {
                    values.add(pv(f, v));
                }
                yield new Predicate.In(values);
            }
            case EXISTS -> new Predicate.Exists();
            case NEGATE -> new Predicate.Not(fromProto(f, p.getNegate()));
            case KIND_NOT_SET -> throw new IllegalArgumentException("Predicate without kind");
        };
    }

    public static Condition toProto(FieldCondition c) {
        return Condition.newBuilder().setField(toProto(c.field())).setPredicate(toProto(c.field(), c.predicate())).build();
    }

    public static FieldCondition fromProto(Condition c) {
        FieldSpec f = fromProto(c.getField());
        return new FieldCondition(f, fromProto(f, c.getPredicate()));
    }

    public static Sort toProto(SortSpec s) {
        return Sort.newBuilder().setField(toProto(s.field())).setDescending(s.descending()).build();
    }

    public static SortSpec fromProto(Sort s) {
        return new SortSpec(fromProto(s.getField()), s.getDescending());
    }

    // ---------- targets and queries ----------

    public static de.kalypzo.agora.proto.v1.QueryTarget toProto(QueryTarget t) {
        var b = de.kalypzo.agora.proto.v1.QueryTarget.newBuilder();
        switch (t) {
            case QueryTarget.Players p -> b.setPlayers(true);
            case QueryTarget.StoreTarget s -> b.setStore(s.store().qualified());
        }
        return b.build();
    }

    public static QueryTarget fromProto(de.kalypzo.agora.proto.v1.QueryTarget t) {
        return switch (t.getTargetCase()) {
            case PLAYERS -> new QueryTarget.Players();
            case STORE -> new QueryTarget.StoreTarget(storeRef(t.getStore()));
            case TARGET_NOT_SET -> throw new IllegalArgumentException("QueryTarget without target");
        };
    }

    public static de.kalypzo.agora.proto.v1.QuerySpec toProto(QuerySpec q) {
        var b = de.kalypzo.agora.proto.v1.QuerySpec.newBuilder().setLimit(q.limit()).setOffset(q.offset());
        q.where().forEach(c -> b.addWhere(toProto(c)));
        q.sort().forEach(s -> b.addSort(toProto(s)));
        return b.build();
    }

    public static QuerySpec fromProto(de.kalypzo.agora.proto.v1.QuerySpec q) {
        return new QuerySpec(q.getWhereList().stream().map(ProtoMapper::fromProto).toList(),
                q.getSortList().stream().map(ProtoMapper::fromProto).toList(), q.getLimit(), q.getOffset());
    }

    private static StoreRef storeRef(String qualified) {
        int i = qualified.indexOf(':');
        if (i <= 0 || i == qualified.length() - 1) {
            throw new IllegalArgumentException("Not a qualified store name: " + qualified);
        }
        return new StoreRef(qualified.substring(0, i), qualified.substring(i + 1));
    }

    // ---------- references, rows ----------

    private static de.kalypzo.agora.proto.v1.StoreDoc toProtoStoreDoc(StoreDoc d) {
        return de.kalypzo.agora.proto.v1.StoreDoc.newBuilder().setStore(d.store().qualified()).setId(d.id()).build();
    }

    private static StoreDoc fromProtoStoreDoc(de.kalypzo.agora.proto.v1.StoreDoc d) {
        return new StoreDoc(storeRef(d.getStore()), d.getId());
    }

    public static de.kalypzo.agora.proto.v1.DocRef toProto(DocRef ref) {
        var b = de.kalypzo.agora.proto.v1.DocRef.newBuilder();
        switch (ref) {
            case PlayerDoc p -> b.setPlayer(p.player().toString());
            case StoreDoc s -> b.setStoreDoc(toProtoStoreDoc(s));
        }
        return b.build();
    }

    public static DocRef fromProto(de.kalypzo.agora.proto.v1.DocRef ref) {
        return switch (ref.getTargetCase()) {
            case PLAYER -> new PlayerDoc(UUID.fromString(ref.getPlayer()));
            case STORE_DOC -> fromProtoStoreDoc(ref.getStoreDoc());
            case TARGET_NOT_SET -> throw new IllegalArgumentException("DocRef without target");
        };
    }

    public static de.kalypzo.agora.proto.v1.RowRef toProto(RowRef ref) {
        var b = de.kalypzo.agora.proto.v1.RowRef.newBuilder();
        switch (ref) {
            case PlayerRow p -> b.setPlayerRow(de.kalypzo.agora.proto.v1.PlayerRow.newBuilder()
                    .setPlayer(p.player().toString()).setNamespace(p.namespace()));
            case StoreDoc s -> b.setStoreDoc(toProtoStoreDoc(s));
        }
        return b.build();
    }

    public static RowRef fromProto(de.kalypzo.agora.proto.v1.RowRef ref) {
        return switch (ref.getTargetCase()) {
            case PLAYER_ROW -> new PlayerRow(UUID.fromString(ref.getPlayerRow().getPlayer()),
                    ref.getPlayerRow().getNamespace());
            case STORE_DOC -> fromProtoStoreDoc(ref.getStoreDoc());
            case TARGET_NOT_SET -> throw new IllegalArgumentException("RowRef without target");
        };
    }

    public static de.kalypzo.agora.proto.v1.Row toProto(Row row) {
        return de.kalypzo.agora.proto.v1.Row.newBuilder().setRef(toProto(row.ref())).setVersion(row.version())
                .putAllData(toProtoFields(row.data())).build();
    }

    public static Row fromProto(de.kalypzo.agora.proto.v1.Row row) {
        return new Row(fromProto(row.getRef()), row.getVersion(), fromProtoFields(row.getDataMap()));
    }

    public static de.kalypzo.agora.proto.v1.DocSnapshot toProto(DocSnapshot s) {
        var b = de.kalypzo.agora.proto.v1.DocSnapshot.newBuilder().setDoc(toProto(s.doc()));
        s.rows().forEach(r -> b.addRows(toProto(r)));
        return b.build();
    }

    public static DocSnapshot fromProto(de.kalypzo.agora.proto.v1.DocSnapshot s) {
        return new DocSnapshot(fromProto(s.getDoc()), s.getRowsList().stream().map(ProtoMapper::fromProto).toList());
    }

    public static de.kalypzo.agora.proto.v1.DocChanged toProto(DocChanged c) {
        var b = de.kalypzo.agora.proto.v1.DocChanged.newBuilder().setRef(toProto(c.ref())).setVersion(c.version())
                .setDeleted(c.deleted()).putAllData(toProtoFields(c.data()));
        if (c.idempotencyKey() != null) {
            b.setIdempotencyKey(c.idempotencyKey());
        }
        return b.build();
    }

    public static DocChanged fromProto(de.kalypzo.agora.proto.v1.DocChanged c) {
        String key = c.getIdempotencyKey();
        return new DocChanged(fromProto(c.getRef()), c.getVersion(), c.getDeleted(), fromProtoFields(c.getDataMap()),
                key.isEmpty() ? null : key);
    }

    // ---------- write request ----------

    public static ApplyRequest toProto(WriteRequest r) {
        var b = ApplyRequest.newBuilder().setIdempotencyKey(r.idempotencyKey()).setDoc(toProto(r.doc()))
                .setCreatedAtMillis(r.createdAt().toEpochMilli());
        r.ops().forEach(o -> b.addOps(toProto(o)));
        r.conditions().forEach(c -> b.addConditions(toProto(c)));
        return b.build();
    }

    public static WriteRequest fromProto(ApplyRequest r) {
        return new WriteRequest(r.getIdempotencyKey(), fromProto(r.getDoc()),
                r.getOpsList().stream().map(ProtoMapper::fromProto).toList(),
                r.getConditionsList().stream().map(ProtoMapper::fromProto).toList(),
                Instant.ofEpochMilli(r.getCreatedAtMillis()));
    }

    /** For the outbox. */
    public static byte[] serialize(WriteRequest request) {
        return toProto(request).toByteArray();
    }

    /** @throws IllegalArgumentException if the bytes are not a valid request */
    public static WriteRequest deserialize(byte[] bytes) {
        try {
            return fromProto(ApplyRequest.parseFrom(bytes));
        } catch (InvalidProtocolBufferException e) {
            throw new IllegalArgumentException("Invalid serialized write request", e);
        }
    }

    // ---------- presence, location ----------

    public static de.kalypzo.agora.proto.v1.PresenceEntry toProto(PresenceEntry e) {
        return de.kalypzo.agora.proto.v1.PresenceEntry.newBuilder().setPlayer(e.player().toString())
                .setName(e.name()).setServer(e.server() == null ? "" : e.server()).setProxy(e.proxy())
                .setLocale(e.locale().toLanguageTag()).setOnlineSinceMillis(e.onlineSince().toEpochMilli()).build();
    }

    public static PresenceEntry fromProto(de.kalypzo.agora.proto.v1.PresenceEntry e) {
        Locale locale = e.getLocale().isEmpty() ? DEFAULT_LOCALE : Locale.forLanguageTag(e.getLocale());
        return new PresenceEntry(UUID.fromString(e.getPlayer()), e.getName(),
                e.getServer().isEmpty() ? null : e.getServer(), e.getProxy(), locale,
                Instant.ofEpochMilli(e.getOnlineSinceMillis()));
    }

    public static de.kalypzo.agora.proto.v1.NetworkLocation toProto(NetworkLocation l) {
        return de.kalypzo.agora.proto.v1.NetworkLocation.newBuilder().setServer(l.serverName())
                .setWorld(l.worldName()).setX(l.x()).setY(l.y()).setZ(l.z()).setYaw(l.yaw()).setPitch(l.pitch()).build();
    }

    public static NetworkLocation fromProto(de.kalypzo.agora.proto.v1.NetworkLocation l) {
        return new NetworkLocation(l.getServer(), l.getWorld(), l.getX(), l.getY(), l.getZ(), l.getYaw(), l.getPitch());
    }

    // ---------- teleport ----------

    public static de.kalypzo.agora.proto.v1.ArrivalStatus toProto(ArrivalStatus s) {
        return switch (s) {
            case READY -> de.kalypzo.agora.proto.v1.ArrivalStatus.READY;
            case WORLD_NOT_FOUND -> de.kalypzo.agora.proto.v1.ArrivalStatus.ARRIVAL_WORLD_NOT_FOUND;
            case ALREADY_HERE -> de.kalypzo.agora.proto.v1.ArrivalStatus.ALREADY_HERE;
        };
    }

    public static de.kalypzo.agora.proto.v1.ConnectStatus toProto(ConnectStatus s) {
        return switch (s) {
            case CONNECTED -> de.kalypzo.agora.proto.v1.ConnectStatus.CONNECTED;
            case ALREADY_CONNECTED -> de.kalypzo.agora.proto.v1.ConnectStatus.ALREADY_CONNECTED;
            case FAILED -> de.kalypzo.agora.proto.v1.ConnectStatus.FAILED;
            case PLAYER_NOT_FOUND -> de.kalypzo.agora.proto.v1.ConnectStatus.PLAYER_NOT_FOUND;
        };
    }

    /** {@code null} for {@code UNSPECIFIED} or a value this client does not know. */
    public static @Nullable TeleportResult fromProto(TeleportStatus s) {
        return switch (s) {
            case OK -> TeleportResult.OK;
            case NOT_ONLINE -> TeleportResult.NOT_ONLINE;
            case SERVER_UNAVAILABLE -> TeleportResult.SERVER_UNAVAILABLE;
            case WORLD_NOT_FOUND -> TeleportResult.WORLD_NOT_FOUND;
            case CONNECT_FAILED -> TeleportResult.CONNECT_FAILED;
            case SUPERSEDED -> TeleportResult.SUPERSEDED;
            case TIMEOUT -> TeleportResult.TIMEOUT;
            case TELEPORT_STATUS_UNSPECIFIED, UNRECOGNIZED -> null;
        };
    }

    // ---------- delivery ----------

    public static de.kalypzo.agora.proto.v1.Delivery toProto(Delivery d) {
        var b = de.kalypzo.agora.proto.v1.Delivery.newBuilder()
                .setKind(de.kalypzo.agora.proto.v1.DeliveryKind.valueOf(d.kind().name()));
        if (d.content() != null) {
            b.setComponentJson(GSON.serialize(d.content()));
        }
        if (d.subtitle() != null) {
            b.setSubtitleJson(GSON.serialize(d.subtitle()));
        }
        if (d.times() != null) {
            b.setTimes(TitleTimes.newBuilder().setFadeInMillis(d.times().fadeIn().toMillis())
                    .setStayMillis(d.times().stay().toMillis()).setFadeOutMillis(d.times().fadeOut().toMillis()));
        }
        if (d.sound() != null) {
            Sound s = d.sound();
            b.setSound(SoundSpec.newBuilder().setKey(s.name().asString()).setSource(s.source().name().toLowerCase(Locale.ROOT))
                    .setVolume(s.volume()).setPitch(s.pitch()));
        }
        return b.build();
    }

    public static Delivery fromProto(de.kalypzo.agora.proto.v1.Delivery d) {
        var kind = d.getKind();
        if (kind == de.kalypzo.agora.proto.v1.DeliveryKind.DELIVERY_KIND_UNSPECIFIED
                || kind == de.kalypzo.agora.proto.v1.DeliveryKind.UNRECOGNIZED) {
            throw new IllegalArgumentException("Unknown delivery kind: " + kind);
        }
        Component content = d.getComponentJson().isEmpty() ? null : GSON.deserialize(d.getComponentJson());
        Component subtitle = d.getSubtitleJson().isEmpty() ? null : GSON.deserialize(d.getSubtitleJson());
        Title.Times times = d.hasTimes()
                ? Title.Times.times(Duration.ofMillis(d.getTimes().getFadeInMillis()),
                        Duration.ofMillis(d.getTimes().getStayMillis()), Duration.ofMillis(d.getTimes().getFadeOutMillis()))
                : null;
        Sound sound = null;
        if (d.hasSound()) {
            SoundSpec s = d.getSound();
            Sound.Source source = Sound.Source.NAMES.value(s.getSource());
            sound = Sound.sound(Key.key(s.getKey()), source == null ? Sound.Source.MASTER : source, s.getVolume(),
                    s.getPitch());
        }
        return new Delivery(DeliveryKind.valueOf(kind.name()), content, subtitle, times, sound);
    }
    // ---------- audit (audit_log.md §8, E28: UUIDs as 16 bytes, big endian) ----------

    public static ByteString toBytes(UUID uuid) {
        return ByteString.copyFrom(ByteBuffer.allocate(16).putLong(uuid.getMostSignificantBits())
                .putLong(uuid.getLeastSignificantBits()).array());
    }

    /** @throws IllegalArgumentException unless exactly 16 bytes */
    public static UUID fromBytes(ByteString bytes) {
        if (bytes.size() != 16) {
            throw new IllegalArgumentException("UUID must be 16 bytes, got " + bytes.size());
        }
        ByteBuffer b = bytes.asReadOnlyByteBuffer();
        return new UUID(b.getLong(), b.getLong());
    }

    /** {@code with} values must be String, Long, Double or Boolean, otherwise {@link IllegalArgumentException}. */
    public static AuditEntry toProto(AuditRecord r) {
        AuditEntry.Builder b = AuditEntry.newBuilder().setId(toBytes(r.id())).setPlayer(toBytes(r.player()))
                .setAction(r.action());
        Position pos = r.position();
        if (pos != null) {
            b.setLocation(AuditLocation.newBuilder().setWorld(pos.world()).setX(pos.x()).setY(pos.y()).setZ(pos.z()));
        }
        if (r.target() != null) {
            b.setTarget(toBytes(r.target()));
        }
        if (r.trace() != null) {
            b.setTrace(toBytes(r.trace()));
        }
        for (Map.Entry<String, Object> e : r.with().entrySet()) {
            b.putWith(e.getKey(), toAuditValue(e.getKey(), e.getValue()));
        }
        return b.build();
    }

    private static AuditValue toAuditValue(String key, Object value) {
        AuditValue.Builder v = AuditValue.newBuilder();
        switch (value) {
            case String x -> v.setS(x);
            case Long x -> v.setL(x);
            case Double x -> v.setD(x);
            case Boolean x -> v.setB(x);
            default -> throw new IllegalArgumentException(
                    "Not an audit value for '" + key + "': " + value.getClass().getName());
        }
        return v.build();
    }

    /** Values are decoded by the oneof (an integral double stays a {@link Double}); unset values are skipped. */
    public static AuditRecord fromProto(AuditEntry e) {
        Position pos = e.hasLocation()
                ? new Position(e.getLocation().getWorld(), e.getLocation().getX(), e.getLocation().getY(),
                        e.getLocation().getZ())
                : null;
        Map<String, Object> with = new LinkedHashMap<>();
        for (Map.Entry<String, AuditValue> w : e.getWithMap().entrySet()) {
            AuditValue v = w.getValue();
            Object value = switch (v.getValueCase()) {
                case S -> v.getS();
                case L -> v.getL();
                case D -> v.getD();
                case B -> v.getB();
                case VALUE_NOT_SET -> null;
            };
            if (value != null) {
                with.put(w.getKey(), value);
            }
        }
        return new AuditRecord(fromBytes(e.getId()), fromBytes(e.getPlayer()), e.getAction(), pos,
                e.hasTarget() ? fromBytes(e.getTarget()) : null, e.hasTrace() ? fromBytes(e.getTrace()) : null, with);
    }

    public static de.kalypzo.agora.proto.v1.AuditBundle toProto(AuditBundle bundle) {
        var b = de.kalypzo.agora.proto.v1.AuditBundle.newBuilder().setDropped(bundle.dropped());
        for (AuditRecord r : bundle.entries()) {
            b.addEntries(toProto(r));
        }
        return b.build();
    }

    public static AuditBundle fromProto(de.kalypzo.agora.proto.v1.AuditBundle bundle) {
        List<AuditRecord> entries = new ArrayList<>(bundle.getEntriesCount());
        for (AuditEntry e : bundle.getEntriesList()) {
            entries.add(fromProto(e));
        }
        return new AuditBundle(entries, bundle.getDropped());
    }

    public static StoredAuditEntry toProto(StoredAuditRecord r) {
        return StoredAuditEntry.newBuilder().setEntry(toProto(r.record())).setServer(r.server()).build();
    }

    public static StoredAuditRecord fromProto(StoredAuditEntry e) {
        return new StoredAuditRecord(fromProto(e.getEntry()), e.getServer());
    }

    /**
     * Unset {@code from}/{@code to} become 0 (R26); a missing viewer stays missing and a viewer without player has no
     * {@code player} (Agora answers INVALID_ARGUMENT resp. writes the nil UUID, E37). {@code limit} goes out as uint32.
     */
    public static TimelineRequest toProto(TimelineSpec spec) {
        TimelineRequest.Builder b = TimelineRequest.newBuilder().setPlayer(toBytes(spec.player()))
                .setFromMillis(spec.from() == null ? 0 : spec.from().toEpochMilli())
                .setToMillis(spec.to() == null ? 0 : spec.to().toEpochMilli())
                .addAllActions(spec.actions()).setLimit(spec.limit());
        if (spec.cursor() != null) {
            b.setCursor(toBytes(spec.cursor()));
        }
        if (spec.trace() != null) {
            b.setTrace(toBytes(spec.trace()));
        }
        Viewer v = spec.viewer();
        if (v != null) {
            AuditViewer.Builder vb = AuditViewer.newBuilder().setName(v.name() == null ? "" : v.name())
                    .setVia(v.via() == null ? "" : v.via());
            if (v.player() != null) {
                vb.setPlayer(toBytes(v.player()));
            }
            b.setViewer(vb);
        }
        return b.build();
    }

    public static TimelineSpec fromProto(TimelineRequest r) {
        Viewer viewer = r.hasViewer()
                ? new Viewer(r.getViewer().hasPlayer() ? fromBytes(r.getViewer().getPlayer()) : null,
                        r.getViewer().getName(), r.getViewer().getVia())
                : null;
        return new TimelineSpec(fromBytes(r.getPlayer()),
                r.getFromMillis() == 0 ? null : Instant.ofEpochMilli(r.getFromMillis()),
                r.getToMillis() == 0 ? null : Instant.ofEpochMilli(r.getToMillis()), r.getActionsList(), r.getLimit(),
                r.hasCursor() ? fromBytes(r.getCursor()) : null, r.hasTrace() ? fromBytes(r.getTrace()) : null, viewer);
    }

    /**
     * One page. A missing or empty cursor means "no further entries". A page that is exactly full carries a cursor
     * although the next page may turn out empty; an empty page with a cursor is passed on as it is.
     */
    public static TimelineResult fromProto(TimelineResponse r) {
        List<StoredAuditRecord> entries = new ArrayList<>(r.getEntriesCount());
        for (StoredAuditEntry e : r.getEntriesList()) {
            entries.add(fromProto(e));
        }
        UUID cursor = r.hasCursor() && !r.getCursor().isEmpty() ? fromBytes(r.getCursor()) : null;
        return new TimelineResult(entries, cursor);
    }
}
