package de.kalypzo.pantheon.smoke;

import de.kalypzo.pantheon.language.MessageKey;
import de.kalypzo.pantheon.language.MessageType;
import java.util.Set;

/** The texts of the smoke plugin ({@code lang/pantheonsmoke_<locale>.yml}). */
public enum SmokeMessage implements MessageKey {
    USAGE("smoke.usage", MessageType.DENY),
    COINS("smoke.coins"),
    COINS_ADDED("smoke.coins-added", MessageType.SUCCESS),
    PAY_SENT("smoke.pay.sent", MessageType.SUCCESS),
    PAY_RECEIVED("smoke.pay.received", MessageType.SUCCESS),
    PAY_FAILED("smoke.pay.failed", MessageType.DENY),
    MSG_SENT("smoke.msg.sent", MessageType.SUCCESS),
    MSG_RECEIVED("smoke.msg.received", MessageType.PROMPT),
    WHERE("smoke.where"),
    BUY_DONE("smoke.buy.done", MessageType.SUCCESS),
    BUY_TOO_POOR("smoke.buy.too-poor", MessageType.DENY),
    UNKNOWN_KIT("smoke.buy.unknown-kit", MessageType.DENY),
    TELEPORTED("smoke.teleported", MessageType.SUCCESS),
    FAILED("smoke.failed", MessageType.ERROR),
    /** Intentionally missing in {@code pantheonsmoke_en_US.yml}: the P-19 check reports it at startup (checklist). */
    UNTRANSLATED("smoke.untranslated");

    /** Every placeholder name the plugin uses. */
    public static final Set<String> PLACEHOLDERS =
            Set.of("coins", "amount", "player", "body", "server", "world", "x", "y", "z", "kit", "price", "reason",
                    "kits");

    private final String key;
    private final MessageType type;

    SmokeMessage(String key) {
        this(key, MessageType.DEFAULT);
    }

    SmokeMessage(String key, MessageType type) {
        this.key = key;
        this.type = type;
    }

    @Override
    public String key() {
        return key;
    }

    @Override
    public MessageType type() {
        return type;
    }
}
