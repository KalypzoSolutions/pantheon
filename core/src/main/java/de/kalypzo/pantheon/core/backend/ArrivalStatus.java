package de.kalypzo.pantheon.core.backend;

/** The answer of the target server to {@code PrepareArrival}. */
public enum ArrivalStatus {
    READY,
    WORLD_NOT_FOUND,
    ALREADY_HERE
}
