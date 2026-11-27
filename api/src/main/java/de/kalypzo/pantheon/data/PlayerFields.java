package de.kalypzo.pantheon.data;

/** Fields every player document contains. */
public final class PlayerFields {

    public static final DataNamespace PANTHEON = DataNamespace.of("pantheon");

    /** The last known name of the player. */
    public static final Field<String> NAME = PANTHEON.ofString("name", "");

    private PlayerFields() {
    }
}
