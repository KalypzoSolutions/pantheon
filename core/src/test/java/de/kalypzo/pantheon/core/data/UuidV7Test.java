package de.kalypzo.pantheon.core.data;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** RFC 9562 UUIDv7 (P-13). */
class UuidV7Test {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-11-27T12:00:00.123Z"), ZoneOffset.UTC);

    @Test
    void versionNibbleIs7() {
        UUID id = UuidV7.next(FIXED);

        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2); // RFC 9562 variant 10
        // the clock's millis, or later if another test in this JVM already used a later time (monotonic)
        assertThat(id.getMostSignificantBits() >>> 16).isGreaterThanOrEqualTo(FIXED.millis());
        assertThat(id.toString().charAt(14)).isEqualTo('7');
    }

    @Test
    void monotonicWithinSameMillisecond() {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 10_000; i++) {
            ids.add(UuidV7.next(FIXED).toString());
        }

        // the canonical lower-case form sorts like the unsigned bytes
        List<String> sorted = new ArrayList<>(ids);
        sorted.sort(null);
        assertThat(ids).isEqualTo(sorted).doesNotHaveDuplicates();
        assertThat(UUID.fromString(ids.getLast()).version()).isEqualTo(7);
    }

    @Test
    void lowerBoundOfKnownDays() {
        assertThat(UuidV7.lowerBound(Instant.parse("2026-11-27T00:00:00Z")))
                .isEqualTo(UUID.fromString("01a21a4d-4000-7000-8000-000000000000"));
        assertThat(UuidV7.lowerBound(Instant.parse("2026-11-28T00:00:00Z")))
                .isEqualTo(UUID.fromString("01a21f73-9c00-7000-8000-000000000000"));
    }

    @Test
    void timestampRoundTrip() {
        Instant t = Instant.parse("2026-11-27T12:34:56.789Z");
        assertThat(UuidV7.timestamp(UuidV7.lowerBound(t))).isEqualTo(t);
        UUID id = new UuidV7.Generator().next(Clock.fixed(t, ZoneOffset.UTC));
        assertThat(UuidV7.timestamp(id)).isEqualTo(t);
        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2);
    }

    @Test
    void monotonicWithinMillisecond() {
        Clock clock = Clock.fixed(Instant.parse("2026-11-27T12:00:00Z"), ZoneOffset.UTC);
        UuidV7.Generator gen = new UuidV7.Generator();
        byte[] previous = null;
        for (int i = 0; i < 1000; i++) {
            UUID id = gen.next(clock);
            assertThat(id.version()).isEqualTo(7);
            assertThat(id.variant()).isEqualTo(2);
            byte[] bytes = java.nio.ByteBuffer.allocate(16).putLong(id.getMostSignificantBits())
                    .putLong(id.getLeastSignificantBits()).array();
            if (previous != null) {
                assertThat(java.util.Arrays.compareUnsigned(previous, bytes)).isNegative();
            }
            previous = bytes;
        }
    }

    @Test
    void generatorsAreIndependentOfSharedState() {
        UuidV7.next(Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"), ZoneOffset.UTC));
        Instant t = Instant.parse("2026-11-27T12:00:00Z");
        UUID id = new UuidV7.Generator().next(Clock.fixed(t, ZoneOffset.UTC));
        assertThat(UuidV7.timestamp(id)).isEqualTo(t);
    }
}
