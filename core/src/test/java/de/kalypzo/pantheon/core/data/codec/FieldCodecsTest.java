package de.kalypzo.pantheon.core.data.codec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.kalypzo.pantheon.data.Field;
import de.kalypzo.pantheon.data.DataNamespace;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FieldCodecsTest {

    private static final DataNamespace NS = DataNamespace.of("codec_test");

    public enum Mode { FAST, SLOW }

    public record Home(String world, double x, double y, double z, float yaw, float pitch) {}

    public record Inner(String label, List<Integer> numbers) {}

    public record Outer(Inner inner, Set<UUID> ids, java.util.Map<String, Inner> byName, Mode mode, Instant at,
                        int count, Long boxed) {}

    public record Bad(Object thing) {}

    @Test
    void longStaysExact() {
        Field<Long> f = NS.ofLong("big", 0L);
        FieldCodec<Long> codec = FieldCodecs.forField(f);
        long v = Long.MAX_VALUE - 1;
        Object wire = codec.encode(v);
        assertThat(wire).isInstanceOf(Long.class).isEqualTo(v);
        assertThat(codec.decode(wire)).isEqualTo(v);
    }

    @Test
    void instantAsEpochMillis() {
        Field<Instant> f = NS.ofInstant("when");
        FieldCodec<Instant> codec = FieldCodecs.forField(f);
        Instant t = Instant.ofEpochMilli(1_700_000_000_123L);
        assertThat(codec.encode(t)).isEqualTo(1_700_000_000_123L);
        assertThat(codec.decode(1_700_000_000_123L)).isEqualTo(t);
        assertThat(codec.decode(null)).isNull();
    }

    @Test
    void uuidSetRoundTrip() {
        Field<Set<UUID>> f = NS.ofSet("friends_set", UUID.class);
        FieldCodec<Set<UUID>> codec = FieldCodecs.forField(f);
        Set<UUID> in = Set.of(UUID.randomUUID(), UUID.randomUUID());
        Object wire = codec.encode(in);
        assertThat(wire).isInstanceOf(List.class);
        assertThat((List<?>) wire).allMatch(String.class::isInstance);
        assertThat(codec.decode(wire)).isEqualTo(in);
    }

    @Test
    void enumAsName() {
        Field<Mode> f = NS.ofEnum("mode", Mode.class, Mode.FAST);
        FieldCodec<Mode> codec = FieldCodecs.forField(f);
        assertThat(codec.encode(Mode.SLOW)).isEqualTo("SLOW");
        assertThat(codec.decode("SLOW")).isEqualTo(Mode.SLOW);
    }

    @Test
    void homeRecordRoundTrip() {
        Field<Home> f = NS.of("home", Home.class);
        FieldCodec<Home> codec = FieldCodecs.forField(f);
        Home h = new Home("world", 1.5, 64, -3.25, 90.5f, -10f);
        Object wire = codec.encode(h);
        assertThat(wire).isInstanceOf(java.util.Map.class);
        assertThat(codec.decode(wire)).isEqualTo(h);
    }

    @Test
    void nestedRecordRoundTrip() {
        Field<Outer> f = NS.of("outer", Outer.class);
        FieldCodec<Outer> codec = FieldCodecs.forField(f);
        Outer o = new Outer(new Inner("a", List.of(1, 2)), Set.of(UUID.randomUUID()),
                java.util.Map.of("k", new Inner("b", List.of())), Mode.SLOW, Instant.ofEpochMilli(5), 7, null);
        assertThat(codec.decode(codec.encode(o))).isEqualTo(o);
    }

    @Test
    void intClampsOutOfRange() {
        Field<Integer> f = NS.ofInt("level", 1);
        FieldCodec<Integer> codec = FieldCodecs.forField(f);
        assertThat(codec.decode(Long.MAX_VALUE)).isEqualTo(Integer.MAX_VALUE);
        assertThat(codec.decode(Long.MIN_VALUE)).isEqualTo(Integer.MIN_VALUE);
        assertThat(codec.decode(42L)).isEqualTo(42);
    }

    @Test
    void unsupportedRecordComponentFails() {
        Field<Bad> f = NS.of("bad", Bad.class);
        assertThatThrownBy(() -> FieldCodecs.forField(f)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void missingUsesDefault() {
        Field<Integer> level = NS.ofInt("level2", 5);
        assertThat(FieldCodecs.forField(level).decode(null)).isEqualTo(5);
        Field<Set<String>> kits = NS.ofSet("kits2", String.class);
        assertThat(FieldCodecs.forField(kits).decode(null)).isEmpty();
        Field<Mode> mode = NS.ofEnum("mode2", Mode.class, Mode.SLOW);
        assertThat(FieldCodecs.forField(mode).decode(null)).isEqualTo(Mode.SLOW);
        Field<Instant> at = NS.ofInstant("at2");
        assertThat(FieldCodecs.forField(at).decode(null)).isNull();
    }
}
