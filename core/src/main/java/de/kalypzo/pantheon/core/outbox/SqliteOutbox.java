package de.kalypzo.pantheon.core.outbox;

import de.kalypzo.pantheon.core.backend.agora.ProtoMapper;
import de.kalypzo.pantheon.core.data.wire.FieldOp;
import de.kalypzo.pantheon.core.data.wire.WriteRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jspecify.annotations.Nullable;

/**
 * The outbox in a SQLite file (agora.md §8, schema verbatim). Every entry is also kept in memory: reads never touch
 * the file, writes go through to it at once (WAL, prepared statements), so a restart sees every appended entry.
 * Requests are stored with {@link ProtoMapper#serialize}. Thread-safe.
 */
final class SqliteOutbox implements Outbox {

    private static final String SCHEMA_OUTBOX = """
            CREATE TABLE IF NOT EXISTS outbox (
                seq             INTEGER PRIMARY KEY AUTOINCREMENT,
                idempotency_key TEXT    NOT NULL UNIQUE,
                doc             TEXT    NOT NULL,
                request         BLOB    NOT NULL,
                created_at      INTEGER NOT NULL
            )""";
    private static final String SCHEMA_DEAD = """
            CREATE TABLE IF NOT EXISTS outbox_dead (
                idempotency_key TEXT    PRIMARY KEY,
                doc             TEXT    NOT NULL,
                request         BLOB    NOT NULL,
                created_at      INTEGER NOT NULL
            )""";

    private final Connection connection;
    private final Clock clock;
    private final Logger log;
    private final PreparedStatement insert;
    private final PreparedStatement delete;
    private final PreparedStatement insertDead;

    private final Object lock = new Object();
    /** Doc key → entries by seq. The map is read without lock ({@link #hasEntries}); inner maps only under lock. */
    private final Map<String, TreeMap<Long, OutboxEntry>> byDoc = new ConcurrentHashMap<>();
    private final Map<String, OutboxEntry> byKey = new HashMap<>(); // guarded by lock
    /** Oldest first, so {@link #moveExpired} stops at the first young entry. Guarded by lock. */
    private final TreeSet<OutboxEntry> byAge = new TreeSet<>(
            Comparator.comparing(OutboxEntry::createdAt).thenComparingLong(OutboxEntry::seq));
    private boolean closed;                                          // guarded by lock

    private SqliteOutbox(Connection connection, Clock clock, Logger log) throws SQLException {
        this.connection = connection;
        this.clock = clock;
        this.log = log;
        try (Statement st = connection.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA synchronous=NORMAL");
            st.execute(SCHEMA_OUTBOX);
            st.execute(SCHEMA_DEAD);
        }
        this.insert = connection.prepareStatement(
                "INSERT INTO outbox (idempotency_key, doc, request, created_at) VALUES (?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS);
        this.delete = connection.prepareStatement("DELETE FROM outbox WHERE idempotency_key = ?");
        this.insertDead = connection.prepareStatement(
                "INSERT OR REPLACE INTO outbox_dead (idempotency_key, doc, request, created_at) VALUES (?, ?, ?, ?)");
        load();
    }

    static SqliteOutbox open(Path file, Clock clock, Logger log) {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(log, "log");
        Connection c = null;
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            try {
                Class.forName("org.sqlite.JDBC"); // plugin class loaders do not always see the driver via SPI
            } catch (ClassNotFoundException ignored) {
                // DriverManager may still find it
            }
            c = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
            return new SqliteOutbox(c, clock, log);
        } catch (IOException | SQLException e) {
            if (c != null) {
                try {
                    c.close();
                } catch (SQLException suppressed) {
                    e.addSuppressed(suppressed);
                }
            }
            throw new IllegalStateException("Could not open the outbox " + file, e);
        }
    }

    private void load() throws SQLException {
        record Unreadable(String key, String doc, byte[] request, long createdAt) {}
        List<Unreadable> unreadable = new ArrayList<>();
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT seq, idempotency_key, doc, request, created_at FROM outbox ORDER BY seq")) {
            while (rs.next()) {
                long seq = rs.getLong(1);
                String key = rs.getString(2);
                String doc = rs.getString(3);
                WriteRequest request;
                try {
                    request = ProtoMapper.deserialize(rs.getBytes(4));
                } catch (RuntimeException e) {
                    log.log(Level.SEVERE, "Outbox entry " + key + " of " + doc
                            + " cannot be read and is moved to outbox_dead", e);
                    unreadable.add(new Unreadable(key, doc, rs.getBytes(4), rs.getLong(5)));
                    continue;
                }
                index(new OutboxEntry(seq, key, doc, request, Instant.ofEpochMilli(rs.getLong(5))));
            }
        }
        for (Unreadable u : unreadable) { // an admin finds them where the expired ones are
            insertDead.setString(1, u.key());
            insertDead.setString(2, u.doc());
            insertDead.setBytes(3, u.request());
            insertDead.setLong(4, u.createdAt());
            insertDead.executeUpdate();
            delete.setString(1, u.key());
            delete.executeUpdate();
        }
    }

    @Override
    public void append(String docKey, WriteRequest request) {
        Objects.requireNonNull(docKey, "docKey");
        Objects.requireNonNull(request, "request");
        if (request.conditional()) {
            throw new IllegalArgumentException("conditional requests never go to the outbox: "
                    + request.idempotencyKey());
        }
        synchronized (lock) {
            ensureOpen();
            if (byKey.containsKey(request.idempotencyKey())) {
                throw new IllegalStateException("already in the outbox: " + request.idempotencyKey());
            }
            try {
                insert.setString(1, request.idempotencyKey());
                insert.setString(2, docKey);
                insert.setBytes(3, ProtoMapper.serialize(request));
                insert.setLong(4, request.createdAt().toEpochMilli());
                insert.executeUpdate();
                long seq;
                try (ResultSet keys = insert.getGeneratedKeys()) {
                    if (!keys.next()) throw new SQLException("no seq returned");
                    seq = keys.getLong(1);
                }
                index(new OutboxEntry(seq, request.idempotencyKey(), docKey, request, request.createdAt()));
            } catch (SQLException e) {
                throw new IllegalStateException("Could not append " + request.idempotencyKey() + " to the outbox", e);
            }
        }
    }

    @Override
    public boolean hasEntries(String docKey) {
        return byDoc.containsKey(docKey);
    }

    @Override
    public Map<String, List<OutboxEntry>> pendingByDoc() {
        synchronized (lock) {
            List<TreeMap<Long, OutboxEntry>> docs = new ArrayList<>(byDoc.values());
            docs.sort(Comparator.comparingLong(TreeMap::firstKey)); // oldest document first
            Map<String, List<OutboxEntry>> out = new LinkedHashMap<>();
            for (TreeMap<Long, OutboxEntry> entries : docs) {
                out.put(entries.firstEntry().getValue().docKey(), List.copyOf(entries.values()));
            }
            return out;
        }
    }

    @Override
    public List<OutboxEntry> pending(String docKey) {
        synchronized (lock) {
            TreeMap<Long, OutboxEntry> entries = byDoc.get(docKey);
            return entries == null ? List.of() : List.copyOf(entries.values());
        }
    }

    @Override
    public boolean contains(String idempotencyKey) {
        synchronized (lock) {
            return byKey.containsKey(idempotencyKey);
        }
    }

    /**
     * If the file delete fails, the entry still leaves the index (a restart replays it once more, which idempotency
     * makes harmless) and the failure is thrown.
     */
    @Override
    public void remove(String idempotencyKey) {
        synchronized (lock) {
            ensureOpen();
            OutboxEntry entry = byKey.get(idempotencyKey);
            if (entry == null) return;
            unindex(entry);
            try {
                delete.setString(1, idempotencyKey);
                delete.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("Could not remove " + idempotencyKey + " from the outbox file", e);
            }
        }
    }

    @Override
    public int moveExpired(Duration maxAge, Consumer<OutboxEntry> onDead) {
        Objects.requireNonNull(maxAge, "maxAge");
        Objects.requireNonNull(onDead, "onDead");
        List<OutboxEntry> moved = new ArrayList<>();
        synchronized (lock) {
            ensureOpen();
            Instant now = clock.instant();
            List<OutboxEntry> expired = new ArrayList<>();
            for (OutboxEntry e : byAge) { // oldest first: O(expired + 1)
                if (Duration.between(e.createdAt(), now).compareTo(maxAge) <= 0) break;
                expired.add(e);
            }
            if (expired.isEmpty()) return 0;
            expired.sort(Comparator.comparingLong(OutboxEntry::seq));
            try {
                connection.setAutoCommit(false);
                try {
                    for (OutboxEntry e : expired) {
                        insertDead.setString(1, e.idempotencyKey());
                        insertDead.setString(2, e.docKey());
                        insertDead.setBytes(3, ProtoMapper.serialize(e.request()));
                        insertDead.setLong(4, e.createdAt().toEpochMilli());
                        insertDead.executeUpdate();
                        delete.setString(1, e.idempotencyKey());
                        delete.executeUpdate();
                    }
                    connection.commit();
                } catch (SQLException e) {
                    connection.rollback();
                    throw e;
                } finally {
                    connection.setAutoCommit(true);
                }
            } catch (SQLException e) {
                throw new IllegalStateException("Could not move expired outbox entries to outbox_dead", e);
            }
            for (OutboxEntry e : expired) {
                unindex(e);
                moved.add(e);
                log.log(Level.SEVERE, "Outbox entry older than " + maxAge.toHours() + " h is not sent any more and "
                        + "was moved to outbox_dead, an admin has to decide: " + json(e));
            }
        }
        for (OutboxEntry e : moved) {
            try {
                onDead.accept(e);
            } catch (RuntimeException ex) {
                log.log(Level.WARNING, "Handling the dead outbox entry " + e.idempotencyKey() + " failed", ex);
            }
        }
        return moved.size();
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (closed) return;
            closed = true;
            byDoc.clear();
            byKey.clear();
            byAge.clear();
            try {
                insert.close();
                delete.close();
                insertDead.close();
                connection.close();
            } catch (SQLException e) {
                log.log(Level.WARNING, "Could not close the outbox", e);
            }
        }
    }

    // ---------- internals ----------

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("the outbox is closed");
    }

    private void index(OutboxEntry e) {
        byKey.put(e.idempotencyKey(), e);
        byAge.add(e);
        byDoc.computeIfAbsent(e.docKey(), k -> new TreeMap<>()).put(e.seq(), e);
    }

    private void unindex(OutboxEntry e) {
        byKey.remove(e.idempotencyKey());
        byAge.remove(e);
        TreeMap<Long, OutboxEntry> entries = byDoc.get(e.docKey());
        if (entries == null) return;
        entries.remove(e.seq());
        if (entries.isEmpty()) byDoc.remove(e.docKey());
    }

    /** Document, key, creation and ops as JSON, for the admin. */
    static String json(OutboxEntry e) {
        StringBuilder b = new StringBuilder("{\"doc\":");
        string(b, e.docKey());
        b.append(",\"idempotencyKey\":");
        string(b, e.idempotencyKey());
        b.append(",\"createdAt\":");
        string(b, e.createdAt().toString());
        b.append(",\"ops\":[");
        List<FieldOp> ops = e.request().ops();
        for (int i = 0; i < ops.size(); i++) {
            if (i > 0) b.append(',');
            op(b, ops.get(i));
        }
        return b.append("]}").toString();
    }

    private static void op(StringBuilder b, FieldOp op) {
        b.append("{\"op\":");
        String name = switch (op) {
            case FieldOp.Set s -> "set";
            case FieldOp.Unset u -> "unset";
            case FieldOp.Inc i -> "inc";
            case FieldOp.Max m -> "max";
            case FieldOp.Min m -> "min";
            case FieldOp.Add a -> "add";
            case FieldOp.Remove r -> "remove";
            case FieldOp.Withdraw w -> "withdraw";
        };
        string(b, name);
        b.append(",\"field\":");
        string(b, op.field().namespace() + "." + op.field().name());
        switch (op) {
            case FieldOp.Set s -> value(b.append(",\"value\":"), s.value());
            case FieldOp.Unset u -> { }
            case FieldOp.Inc i -> b.append(",\"delta\":").append(i.delta());
            case FieldOp.Max m -> value(b.append(",\"value\":"), m.value());
            case FieldOp.Min m -> value(b.append(",\"value\":"), m.value());
            case FieldOp.Add a -> value(b.append(",\"element\":"), a.element());
            case FieldOp.Remove r -> value(b.append(",\"element\":"), r.element());
            case FieldOp.Withdraw w -> b.append(",\"amount\":").append(w.amount());
        }
        b.append('}');
    }

    private static void value(StringBuilder b, @Nullable Object v) {
        switch (v) {
            case null -> b.append("null");
            case Boolean bool -> b.append(bool);
            case Number n -> b.append(n);
            case Collection<?> c -> {
                b.append('[');
                boolean first = true;
                for (Object o : c) {
                    if (!first) b.append(',');
                    first = false;
                    value(b, o);
                }
                b.append(']');
            }
            case Map<?, ?> m -> {
                b.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    if (!first) b.append(',');
                    first = false;
                    string(b, String.valueOf(e.getKey()));
                    b.append(':');
                    value(b, e.getValue());
                }
                b.append('}');
            }
            default -> string(b, v.toString());
        }
    }

    private static void string(StringBuilder b, String s) {
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        b.append('"');
    }
}
