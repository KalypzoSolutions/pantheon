package de.kalypzo.pantheon.core.audit;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Builds and validates one audit entry (audit_log.md §2, E32). Bukkit-free, not thread-safe.
 *
 * <p>Rules: at most 16 free keys of {@code [a-z0-9_]+} (a 17th, an invalid or the reserved key {@code truncated}
 * throws {@link IllegalArgumentException}); values are String, long (any integral type), double (any floating type,
 * finite only) or boolean; strings longer than 512 chars are cut and {@code truncated = true} is added, which does not
 * count towards the 16. Agora drops entries with NUL characters, so NUL in string values is replaced by U+FFFD.
 * {@code entity(...)} sets three keys, the last call wins; so does {@code target(...)}.
 */
public final class EntryBuilder {

    public static final int MAX_KEYS = 16;
    public static final int MAX_TEXT = 512;
    public static final String TRUNCATED = "truncated";

    private static final Pattern KEY = Pattern.compile("[a-z0-9_]+");
    private static final Pattern ACTION = Pattern.compile("[a-z0-9_]+:[a-z0-9_]+");

    private final UUID player;
    private final String action;
    private @Nullable Position position;
    private @Nullable UUID target;
    private @Nullable UUID trace;
    private final Map<String, Object> with = new LinkedHashMap<>();
    private final Set<String> truncatedKeys = new HashSet<>();

    public EntryBuilder(UUID player, String action) {
        this.player = Objects.requireNonNull(player, "player");
        Objects.requireNonNull(action, "action");
        if (!ACTION.matcher(action).matches()) {
            throw new IllegalArgumentException("invalid action (expected <namespace>:<name>): " + action);
        }
        this.action = action;
    }

    public UUID player() {
        return player;
    }

    public String action() {
        return action;
    }

    public EntryBuilder position(Position p) {
        this.position = Objects.requireNonNull(p, "position");
        return this;
    }

    public EntryBuilder target(UUID t) {
        this.target = Objects.requireNonNull(t, "target");
        return this;
    }

    public EntryBuilder trace(@Nullable UUID t) {
        this.trace = t;
        return this;
    }

    /** Sets {@code entity_type}, {@code entity_uuid} and {@code entity_name} (omitted if null). Last call wins. */
    public EntryBuilder entity(String type, UUID uuid, @Nullable String name) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(uuid, "uuid");
        with.remove("entity_name");
        truncatedKeys.remove("entity_name");
        with("entity_type", type);
        with("entity_uuid", uuid.toString());
        if (name != null) {
            with("entity_name", name);
        }
        return this;
    }

    public EntryBuilder with(String key, Object value) {
        Objects.requireNonNull(key, "key");
        if (!KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("invalid key (expected [a-z0-9_]+): " + key);
        }
        if (key.equals(TRUNCATED)) {
            throw new IllegalArgumentException("key is reserved: " + TRUNCATED);
        }
        if (!with.containsKey(key) && with.size() >= MAX_KEYS) {
            throw new IllegalArgumentException("more than " + MAX_KEYS + " keys: " + key);
        }
        Object stored = normalize(key, value);
        with.put(key, stored);
        return this;
    }

    private Object normalize(String key, @Nullable Object value) {
        truncatedKeys.remove(key);
        switch (value) {
            case null -> throw new IllegalArgumentException("value of '" + key + "' is null");
            case String s -> {
                String clean = s.indexOf('\0') >= 0 ? s.replace('\0', '�') : s;
                if (clean.length() > MAX_TEXT) {
                    int end = MAX_TEXT;
                    if (Character.isHighSurrogate(clean.charAt(end - 1))) {
                        end--; // do not split a surrogate pair
                    }
                    truncatedKeys.add(key);
                    return clean.substring(0, end);
                }
                return clean;
            }
            case Long l -> {
                return l;
            }
            case Integer i -> {
                return i.longValue();
            }
            case Short i -> {
                return i.longValue();
            }
            case Byte i -> {
                return i.longValue();
            }
            case Double d -> {
                return finite(key, d);
            }
            case Float f -> {
                return finite(key, f.doubleValue());
            }
            case Boolean b -> {
                return b;
            }
            default -> throw new IllegalArgumentException("unsupported value type for '" + key + "': "
                    + value.getClass().getName());
        }
    }

    private static Double finite(String key, double d) {
        if (!Double.isFinite(d)) {
            throw new IllegalArgumentException("value of '" + key + "' is NaN or infinite");
        }
        return d;
    }

    /** The trace set with {@link #trace}, or null if the caller did not set one. */
    public @Nullable UUID explicitTrace() {
        return trace;
    }

    /** The entry with the given id; the trace is the explicit one if set, else {@code activeTrace}. */
    public AuditRecord build(UUID id, @Nullable UUID activeTrace) {
        Map<String, Object> out = new LinkedHashMap<>(with);
        if (!truncatedKeys.isEmpty()) {
            out.put(TRUNCATED, true);
        }
        return new AuditRecord(id, player, action, position, target, trace != null ? trace : activeTrace, out);
    }
}
