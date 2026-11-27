package de.kalypzo.pantheon.core.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** P-33 / E40. */
class HiddenCommandsTest {

    private static final Object TELL = new Object();
    private static final Object AUTHME_LOGIN = new Object();
    private static final ResolvedCommand TELL_CMD = new ResolvedCommand(TELL, "msg", Set.of("tell", "w", "m"));
    private static final ResolvedCommand LOGIN_CMD =
            new ResolvedCommand(AUTHME_LOGIN, "login", Set.of("l", "log"));

    private static CommandResolver resolver(Map<String, ResolvedCommand> known) {
        return label -> Optional.ofNullable(known.get(label.toLowerCase(java.util.Locale.ROOT)));
    }

    private static final Map<String, ResolvedCommand> KNOWN = Map.of(
            "tell", TELL_CMD, "w", TELL_CMD, "m", TELL_CMD, "essentials:msg", TELL_CMD, "msg", TELL_CMD,
            "authme:l", LOGIN_CMD, "login", LOGIN_CMD, "l", LOGIN_CMD, "log", LOGIN_CMD);

    @Test
    void hiddenByIdentityCoversAliases() {
        HiddenCommands hidden = new HiddenCommands(resolver(KNOWN));
        assertThat(hidden.isHidden("tell")).isFalse();
        hidden.hide(TELL);
        assertThat(hidden.isHidden("tell")).isTrue();
        assertThat(hidden.isHidden("w")).isTrue();
        assertThat(hidden.isHidden("m")).isTrue();
        assertThat(hidden.isHidden("essentials:msg")).isTrue();
        assertThat(hidden.isHidden("ban")).isFalse();
    }

    @Test
    void hiddenByLabelResolvedAtCheckTime() {
        java.util.concurrent.ConcurrentHashMap<String, ResolvedCommand> known = new java.util.concurrent.ConcurrentHashMap<>();
        HiddenCommands hidden = new HiddenCommands(resolver(known));
        hidden.hide("authme:shop");
        ResolvedCommand shop = new ResolvedCommand(new Object(), "shop", Set.of("store"));
        assertThat(hidden.isHidden("store")).isFalse();
        known.put("authme:shop", shop);
        known.put("store", shop);
        assertThat(hidden.isHidden("store")).isTrue();
        assertThat(hidden.isHidden("shop")).isFalse(); // not resolvable here, so not matched
        known.put("shop", shop);
        assertThat(hidden.isHidden("shop")).isTrue();
    }

    @Test
    void defaultsMatchCaseAndNamespaceInsensitive() {
        HiddenCommands hidden = new HiddenCommands(resolver(KNOWN));
        assertThat(hidden.isHidden("LOGIN")).isTrue();
        assertThat(hidden.isHidden("authme:l")).isTrue();
        assertThat(hidden.isHidden("AuthMe:L")).isTrue();
        assertThat(hidden.isHidden("xauth:register")).isTrue();
        assertThat(hidden.isHidden("xauth:CHANGEPASS")).isTrue();
        // alias of a resolved command is a default: "log" resolves to the login command
        assertThat(hidden.isHidden("log")).isTrue();
    }

    @Test
    void unknownDefaultLabelStillHidden() {
        HiddenCommands hidden = new HiddenCommands(label -> Optional.empty());
        assertThat(hidden.isHidden("register")).isTrue();
        assertThat(hidden.isHidden("unknownplugin:reg")).isTrue();
        assertThat(hidden.isHidden("changepassword")).isTrue();
    }

    @Test
    void otherCommandsNotHidden() {
        HiddenCommands hidden = new HiddenCommands(resolver(KNOWN));
        assertThat(hidden.isHidden("ban")).isFalse();
        assertThat(hidden.isHidden("essentials:msg")).isFalse();
        assertThat(hidden.isHidden("loginx")).isFalse();
        assertThat(hidden.isHidden("")).isFalse();
    }

    @Test
    void hiddenLabelIsNormalisedLikeTheTypedLabel() {
        // Final review I-2: hideCommand("/2fa") must hide "/2fa <code>" (same normalisation as isHidden, R53).
        HiddenCommands hidden = new HiddenCommands(resolver(Map.of()));
        hidden.hide("/2fa");
        hidden.hide(" //Secret ");
        hidden.hide("/MyPlugin:Pin");
        assertThat(hidden.isHidden("2fa")).isTrue();
        assertThat(hidden.isHidden("/2FA")).isTrue();
        assertThat(hidden.isHidden("secret")).isTrue();
        assertThat(hidden.isHidden("myplugin:pin")).isTrue();
        assertThat(hidden.isHidden("ban")).isFalse();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> hidden.hide(" / "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
