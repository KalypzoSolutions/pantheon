package de.kalypzo.pantheon.data;

/** A value violates a {@code unique} index. */
public class DuplicateValueException extends RuntimeException {

    private final String index;

    public DuplicateValueException(String index) {
        super("Duplicate value for unique index " + index);
        this.index = index;
    }

    public String index() {
        return index;
    }
}
