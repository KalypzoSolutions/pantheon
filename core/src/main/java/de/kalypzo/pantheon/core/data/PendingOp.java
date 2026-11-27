package de.kalypzo.pantheon.core.data;

import de.kalypzo.pantheon.core.data.wire.FieldOp;
import org.jspecify.annotations.Nullable;

/** An own unconditional op that is not yet confirmed; {@code requestKey} is set once it is part of a sent request. */
public record PendingOp(FieldOp op, @Nullable String requestKey) {}
