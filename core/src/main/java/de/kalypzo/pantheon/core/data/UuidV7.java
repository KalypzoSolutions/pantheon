package de.kalypzo.pantheon.core.data;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * UUIDv7 (RFC 9562): 48 bit Unix millis, version 7, variant {@code 10}, 74 random bits. Monotonic within the JVM:
 * within the same (or an earlier) millisecond the 74 bits are counted up by one (RFC 9562 §6.2, method 3), and a
 * new millisecond starts at a random value with the top bit cleared, leaving room to count. The one UuidV7 of core
 * (R8).
 */
public final class UuidV7 {

    private static final long RAND_A_MASK = 0x0FFFL;            // 12 bits
    private static final long RAND_B_MASK = 0x3FFF_FFFF_FFFF_FFFFL; // 62 bits
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Generator SHARED = new Generator();

    private UuidV7() {}

    /**
     * Monotonic generator state. The static {@link #next} shares one instance per JVM; components that run with their
     * own (test) clock must use their own instance, or a far-future clock would pin the shared one.
     */
    public static final class Generator {
        private long lastMillis = -1;  // guarded by this
        private long lastRandA;        // guarded by this
        private long lastRandB;        // guarded by this

        public synchronized UUID next(Clock clock) {
            long now = clock.millis();
            if (now > lastMillis) {
                lastMillis = now;
                lastRandA = RANDOM.nextLong() & (RAND_A_MASK >>> 1); // top bit clear: room to count
                lastRandB = RANDOM.nextLong() & RAND_B_MASK;
            } else if (lastRandB < RAND_B_MASK) {
                lastRandB++;
            } else if (lastRandA < RAND_A_MASK) {
                lastRandA++;
                lastRandB = 0;
            } else { // counter exhausted: borrow the next millisecond
                lastMillis++;
                lastRandA = 0;
                lastRandB = 0;
            }
            long msb = (lastMillis << 16) | 0x7000L | lastRandA;
            long lsb = 0x8000_0000_0000_0000L | lastRandB;
            return new UUID(msb, lsb);
        }
    }

    /** Next ID from the JVM-wide shared generator. */
    public static UUID next(Clock clock) {
        return SHARED.next(clock);
    }

    /** Smallest UUIDv7 of the instant's millisecond: all random bits 0. */
    public static UUID lowerBound(Instant t) {
        return new UUID((t.toEpochMilli() << 16) | 0x7000L, 0x8000_0000_0000_0000L);
    }

    /** The millisecond timestamp encoded in the ID. */
    public static Instant timestamp(UUID id) {
        return Instant.ofEpochMilli(id.getMostSignificantBits() >>> 16);
    }
}
