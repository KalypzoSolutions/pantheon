package de.kalypzo.pantheon.core.backend.agora;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import de.kalypzo.pantheon.core.data.UuidV7;
import de.kalypzo.pantheon.core.data.codec.FieldCodecs;
import de.kalypzo.pantheon.core.data.codec.FieldSpecs;
import de.kalypzo.pantheon.core.data.wire.DocRef;
import de.kalypzo.pantheon.core.data.wire.FieldOp;
import de.kalypzo.pantheon.core.data.wire.WriteRequest;
import de.kalypzo.pantheon.data.DataNamespace;
import de.kalypzo.pantheon.data.Field;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;

/** Shared helpers of the benchmarks (docs/benchmark.md). */
final class Bench {

    static final DataNamespace NS = DataNamespace.of("bench");
    static final Field<Long> COINS = NS.ofLong("coins", 0L);
    static final Duration WAIT = Duration.ofSeconds(30);

    private Bench() {}

    static String key() {
        return UuidV7.next(Clock.systemUTC()).toString();
    }

    static WriteRequest write(DocRef doc, FieldOp... ops) {
        return new WriteRequest(key(), doc, List.of(ops), List.of(), Instant.now());
    }

    static FieldOp inc(Field<?> f, long delta) {
        return new FieldOp.Inc(FieldSpecs.of(f), delta);
    }

    static FieldOp withdraw(Field<?> f, long amount) {
        return new FieldOp.Withdraw(FieldSpecs.of(f), amount);
    }

    static long coins(Map<String, Object> data) {
        return FieldCodecs.forField(COINS).decode(data.get(COINS.name()));
    }

    static <T> T get(CompletableFuture<T> f) {
        try {
            return f.get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Deterministic player ids for seeded rows; the same expression as {@link #SQL_PLAYER}. */
    static UUID player(long i) {
        return new UUID(0xbe0c000000000000L, i);
    }

    static final String SQL_PLAYER = "('be0c0000-0000-0000-0000-' || lpad(to_hex(i), 12, '0'))::uuid";

    static HikariDataSource pool(int size) {
        HikariConfig c = new HikariConfig();
        c.setJdbcUrl(AgoraContractFixture.Db.URL);
        c.setUsername(AgoraContractFixture.Db.USER);
        c.setPassword(AgoraContractFixture.Db.PASSWORD);
        c.setMaximumPoolSize(size);
        c.setMinimumIdle(size);
        return new HikariDataSource(c);
    }

    static void sql(DataSource ds, String... statements) {
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            for (String sql : statements) s.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    static long scalar(DataSource ds, String sql) {
        try (Connection c = ds.getConnection(); Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
            return r.next() ? r.getLong(1) : 0;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Fails unless Postgres holds exactly {@code expected} coins for the player: idle is not the same as stored. */
    static void verifyCoins(UUID player, long expected, String what) {
        try (HikariDataSource ds = pool(1)) {
            long stored = scalar(ds, "SELECT coalesce((data->>'coins')::bigint, 0) FROM pantheon_player WHERE player = '"
                    + player + "' AND namespace = '" + NS.name() + "'");
            record("stored.jsonl", String.format("{\"what\": \"%s\", \"expected\": %d, \"stored\": %d}",
                    what, expected, stored));
            if (stored != expected) throw new IllegalStateException(what + ": " + expected + " incs, " + stored + " stored");
        }
    }

    /** Appends one JSON line to {@code <bench.out>/<file>} (forks run one after another). */
    static void record(String file, String json) {
        String out = System.getProperty("bench.out");
        if (out == null) return;
        try {
            Files.writeString(Path.of(out, file), json + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Calls that warm the JIT before a once-per-tick measurement (-Dbench.warmup, default 20000). */
    static int warmup() {
        return Integer.getInteger("bench.warmup", quick() ? 5000 : 20_000);
    }

    static boolean quick() {
        return Boolean.getBoolean("bench.quick");
    }
}
