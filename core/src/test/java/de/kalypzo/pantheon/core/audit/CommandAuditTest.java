package de.kalypzo.pantheon.core.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** P-33. */
class CommandAuditTest {

    private static final UUID PLAYER = UUID.randomUUID();
    private static final UUID ID = UUID.randomUUID();
    private static final ResolvedCommand MSG = new ResolvedCommand(new Object(), "msg", Set.of("tell", "w"));
    private static final Map<String, ResolvedCommand> KNOWN =
            Map.of("tell", MSG, "msg", MSG, "essentials:msg", MSG, "w", MSG);
    private static final CommandResolver RESOLVER = l -> Optional.ofNullable(KNOWN.get(l.toLowerCase(java.util.Locale.ROOT)));
    private static final HiddenCommands HIDDEN = new HiddenCommands(RESOLVER);

    private static Optional<AuditRecord> audit(String raw, boolean cancelled) {
        return CommandAudit.of(PLAYER, raw, cancelled, null, HIDDEN, RESOLVER).map(b -> b.build(ID, null));
    }

    @Test
    void stripsSlashKeepsLine() {
        AuditRecord r = audit("/tell Bob hi there", false).orElseThrow();
        assertThat(r.action()).isEqualTo("pantheon:command");
        assertThat(r.with()).containsEntry("line", "tell Bob hi there");
    }

    @Test
    void aliasResolvesToName() {
        assertThat(audit("/w Bob hi", false).orElseThrow().with()).containsEntry("command", "msg")
                .containsEntry("unknown", false);
    }

    @Test
    void namespacedLabel() {
        assertThat(audit("/essentials:msg hi", false).orElseThrow().with()).containsEntry("command", "msg");
    }

    @Test
    void unknownMarked() {
        AuditRecord r = audit("/Foo:Bar x", false).orElseThrow();
        assertThat(r.with()).containsEntry("command", "bar").containsEntry("unknown", true);
    }

    @Test
    void cancelledMarked() {
        assertThat(audit("/w Bob", true).orElseThrow().with()).containsEntry("cancelled", true);
        assertThat(audit("/w Bob", false).orElseThrow().with()).containsEntry("cancelled", false);
    }

    @Test
    void hiddenProducesNothing() {
        assertThat(audit("/LOGIN geheim", false)).isEmpty();
        assertThat(audit("/authme:l geheim", false)).isEmpty();
        assertThat(audit("/register geheim geheim", false)).isEmpty();
    }

    @Test
    void longLineTruncated() {
        AuditRecord r = audit("/w Bob " + "x".repeat(600), false).orElseThrow();
        assertThat((String) r.with().get("line")).hasSize(512);
        assertThat(r.with()).containsEntry("truncated", true);
    }

    @Test
    void extraSlashesWhitespaceAndNestedNamespacesStillHidden() {
        assertThat(audit("//login pw", false)).isEmpty();
        assertThat(audit("/ /login pw", false)).isEmpty();
        assertThat(audit("/\t/login pw", false)).isEmpty();
        assertThat(audit("/a:b:login pw", false)).isEmpty();
        assertThat(audit("//REGISTER pw pw", false)).isEmpty();
        assertThat(audit("//w Bob hi", false).orElseThrow().with()).containsEntry("line", "w Bob hi");
    }
}
