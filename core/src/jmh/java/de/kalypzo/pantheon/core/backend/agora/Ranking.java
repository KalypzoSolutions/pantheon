package de.kalypzo.pantheon.core.backend.agora;

import static de.kalypzo.pantheon.core.backend.agora.Bench.SQL_PLAYER;
import static de.kalypzo.pantheon.core.backend.agora.Bench.scalar;
import static de.kalypzo.pantheon.core.backend.agora.Bench.sql;

import de.kalypzo.agora.data.ReadService;
import de.kalypzo.pantheon.core.backend.Backend;
import de.kalypzo.pantheon.core.data.codec.FieldSpecs;
import de.kalypzo.pantheon.core.data.wire.DocRef;
import de.kalypzo.pantheon.core.data.wire.DocSnapshot;
import de.kalypzo.pantheon.core.data.wire.IndexDeclaration;
import de.kalypzo.pantheon.core.data.wire.PlayerDoc;
import de.kalypzo.pantheon.core.data.wire.QuerySpec;
import de.kalypzo.pantheon.core.data.wire.QueryTarget;
import de.kalypzo.pantheon.core.data.wire.SortSpec;
import de.kalypzo.pantheon.core.data.wire.StoreDoc;
import de.kalypzo.pantheon.core.data.wire.StoreRef;
import de.kalypzo.pantheon.data.DataNamespace;
import de.kalypzo.pantheon.data.Field;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;

/**
 * The ranking data of S3: {@code bench_idx} (declared index) and {@code bench_noidx} (never declared) with the same
 * values, once as player rows and once as store documents. Seeded by SQL and only ever grown, so the runner goes
 * through N in ascending order.
 */
final class Ranking {

    static final Field<Long> IDX = DataNamespace.of("bench_idx").ofLong("coins", 0L);
    static final Field<Long> NOIDX = DataNamespace.of("bench_noidx").ofLong("coins", 0L);
    static final StoreRef IDX_STORE = new StoreRef("bench_idx", "rank");
    static final StoreRef NOIDX_STORE = new StoreRef("bench_noidx", "rank");

    private Ranking() {}

    static boolean players(String target) {
        return target.equals("players");
    }

    static QueryTarget target(String target, boolean indexed) {
        if (players(target)) return new QueryTarget.Players();
        return new QueryTarget.StoreTarget(indexed ? IDX_STORE : NOIDX_STORE);
    }

    static DocRef doc(String target, boolean indexed, long i) {
        if (players(target)) return new PlayerDoc(Bench.player(i));
        return new StoreDoc(indexed ? IDX_STORE : NOIDX_STORE, "d" + i);
    }

    static List<DocSnapshot> top10(Backend node, String target, boolean indexed, boolean descending) {
        QuerySpec spec = new QuerySpec(List.of(), List.of(new SortSpec(FieldSpecs.of(indexed ? IDX : NOIDX),
                descending)), 10, 0);
        return Bench.get(node.query(target(target, indexed), spec));
    }

    /** Pseudo-random, reproducible coins for row {@code i}. */
    private static final String SQL_COINS = "((i::bigint * 7919) % 1000003)";

    /** Grows the seeded rows of {@code target} to {@code n}; returns how many there are. */
    static long ensureSeeded(DataSource ds, String target, long n) {
        long have = seeded(ds, target);
        if (have < n) {
            String rows = "FROM generate_series(" + (have + 1) + ", " + n + ") i";
            if (players(target)) {
                sql(ds, "INSERT INTO pantheon_player (player, namespace, data) SELECT " + SQL_PLAYER + ", ns.n, "
                        + "CASE ns.n WHEN 'pantheon' THEN jsonb_build_object('name', 'p' || i) "
                        + "ELSE jsonb_build_object('coins', " + SQL_COINS + ") END " + rows
                        + " CROSS JOIN (VALUES ('pantheon'), ('bench_idx'), ('bench_noidx')) ns(n) "
                        + "ON CONFLICT DO NOTHING", "ANALYZE pantheon_player");
            } else {
                sql(ds, "INSERT INTO pantheon_doc (store, id, data) SELECT s.s, 'd' || i, "
                        + "jsonb_build_object('coins', " + SQL_COINS + ") " + rows
                        + " CROSS JOIN (VALUES ('bench_idx:rank'), ('bench_noidx:rank')) s(s) "
                        + "ON CONFLICT DO NOTHING", "ANALYZE pantheon_doc");
            }
            have = n;
        }
        return have;
    }

    static long seeded(DataSource ds, String target) {
        return players(target)
                ? scalar(ds, "SELECT count(*) FROM pantheon_player WHERE namespace = 'bench_idx'")
                : scalar(ds, "SELECT count(*) FROM pantheon_doc WHERE store = 'bench_idx:rank'");
    }

    /**
     * Declares the index on {@code bench_idx} and waits until Agora reports it ready (a build over many rows may
     * outlast the call's deadline, the status in {@code pantheon_index} does not). Returns its name.
     */
    static String declareIndex(Backend node, DataSource ds, String target) {
        try {
            node.declareIndex(new IndexDeclaration(target(target, true), FieldSpecs.of(IDX), false))
                    .get(5, TimeUnit.MINUTES);
        } catch (Exception e) {
            System.err.println("[bench] declareIndex did not complete (" + e + "), waiting for pantheon_index");
        }
        String table = players(target) ? "pantheon_player" : "pantheon_doc";
        String where = "name LIKE '" + table + "_bench_idx%' AND status = 'ready'";
        long deadline = System.nanoTime() + Duration.ofMinutes(5).toNanos();
        while (scalar(ds, "SELECT count(*) FROM pantheon_index WHERE " + where) == 0) {
            if (System.nanoTime() > deadline) throw new IllegalStateException("index on " + target + " not ready");
            sleep(200);
        }
        return name(ds, "SELECT name FROM pantheon_index WHERE " + where);
    }

    private static String name(DataSource ds, String sql) {
        try (var c = ds.getConnection(); var s = c.createStatement(); var r = s.executeQuery(sql)) {
            r.next();
            return r.getString(1);
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The plan Postgres picks for the exact SQL Agora runs ({@code ReadService.playerQuerySql}/{@code storeQuerySql},
     * public for this purpose), without forcing anything: {@code order} = the index delivers the order (no Sort node),
     * {@code read} = the index is read but all rows are sorted afterwards, {@code none} = the index is not used.
     */
    static String plan(DataSource ds, String target, String index, boolean descending) {
        QuerySpec spec = new QuerySpec(List.of(), List.of(new SortSpec(FieldSpecs.of(IDX), descending)), 10, 0);
        de.kalypzo.agora.proto.v1.QuerySpec proto = ProtoMapper.toProto(spec);
        var sql = players(target) ? ReadService.playerQuerySql(proto, 10)
                : ReadService.storeQuerySql(IDX_STORE.namespace() + ":" + IDX_STORE.name(), proto, 10);
        StringBuilder plan = new StringBuilder();
        try (var c = ds.getConnection(); var ps = c.prepareStatement("EXPLAIN " + sql.sql())) {
            for (int i = 0; i < sql.params().size(); i++) ps.setObject(i + 1, sql.params().get(i));
            try (var r = ps.executeQuery()) {
                while (r.next()) plan.append(r.getString(1)).append('\n');
            }
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
        String text = plan.toString();
        if (!text.contains(index)) return "none";
        return text.contains("Sort") ? "read" : "order";
    }

    static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
