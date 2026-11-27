package de.kalypzo.pantheon.audit;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** One page of a timeline with the cursor for the next one. */
public record TimelinePage(List<TimelineEntry> entries, Optional<UUID> cursor) {}
