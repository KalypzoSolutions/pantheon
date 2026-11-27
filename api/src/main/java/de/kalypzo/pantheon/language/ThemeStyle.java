package de.kalypzo.pantheon.language;

/** The fixed set of theme tags. Their styles come from the node's {@code theme.yml}. */
public enum ThemeStyle {
    P("p"),
    HIGHLIGHT("highlight"),
    SUCCESS("success"),
    DENY("deny"),
    ERROR("error"),
    PROMPT("prompt"),
    H1("h1"),
    H2("h2");

    private final String tagName;

    ThemeStyle(String tagName) {
        this.tagName = tagName;
    }

    /** The MiniMessage tag, e.g. {@code highlight} for {@code <highlight>}. */
    public String tagName() {
        return tagName;
    }
}
