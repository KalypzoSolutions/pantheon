package de.kalypzo.pantheon.velocity;

import net.kyori.adventure.text.Component;

/** Fixed texts (E19): the proxy does not depend on core and has no translation. */
public final class ProxyTexts {

    /** Same German text as {@code pantheon.data-unavailable}. */
    public static final Component DATA_UNAVAILABLE =
            Component.text("Die Spielerdaten sind gerade nicht erreichbar. Bitte versuche es gleich noch einmal.");

    public static final Component LOGGED_IN_ELSEWHERE = Component.text("Von einem anderen Ort angemeldet");

    private ProxyTexts() {
    }
}
