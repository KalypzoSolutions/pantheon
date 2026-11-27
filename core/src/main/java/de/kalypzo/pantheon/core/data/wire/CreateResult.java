package de.kalypzo.pantheon.core.data.wire;

import org.jspecify.annotations.Nullable;

public record CreateResult(boolean created, @Nullable Row row) {}
