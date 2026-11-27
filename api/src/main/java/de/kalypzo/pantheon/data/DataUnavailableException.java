package de.kalypzo.pantheon.data;

/** Agora is not reachable, so the operation could not be carried out. */
public class DataUnavailableException extends RuntimeException {

    public DataUnavailableException() {
        super();
    }

    public DataUnavailableException(String message) {
        super(message);
    }

    public DataUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
