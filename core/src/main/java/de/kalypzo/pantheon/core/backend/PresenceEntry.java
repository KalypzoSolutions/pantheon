package de.kalypzo.pantheon.core.backend;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record PresenceEntry(UUID player, String name, @Nullable String server, String proxy, Locale locale,
                            Instant onlineSince) {}
