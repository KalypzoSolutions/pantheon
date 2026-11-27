package de.kalypzo.pantheon.audit;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** One entry. {@code action} is the key, e.g. {@code shop:buy}. {@code with} values are String, Long, Double or Boolean. */
public record TimelineEntry(
        UUID id,
        Instant at,
        UUID player,
        String server,
        String action,
        Optional<AuditPosition> position,
        Optional<UUID> target,
        Optional<UUID> trace,
        Map<String, Object> with) {}
