package de.kalypzo.pantheon.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class AuditDataNamespaceTest {

    @Test
    void keyIsNamespaceColonName() {
        AuditAction buy = AuditNamespace.of("shop").action("buy");
        assertThat(buy.key()).isEqualTo("shop:buy");
        assertThat(buy.name()).isEqualTo("buy");
        assertThat(buy.namespace().name()).isEqualTo("shop");
    }

    @Test
    void rejectsInvalidNamespaceAndActionName() {
        for (String bad : List.of("Shop", "shop-x", "")) {
            assertThatThrownBy(() -> AuditNamespace.of(bad)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> AuditNamespace.of("shop").action(bad))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void ofIsInterned() {
        assertThat(AuditNamespace.of("shop")).isSameAs(AuditNamespace.of("shop"));
    }

    @Test
    void actionsEqualByKey() {
        AuditAction a = AuditNamespace.of("shop").action("buy");
        AuditAction b = AuditNamespace.of("shop").action("buy");
        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(AuditNamespace.of("shop").action("sell"));
    }

    @Test
    void pantheonActionsHaveSpecNames() {
        assertThat(List.of(
                        AuditActions.CHAT,
                        AuditActions.COMMAND,
                        AuditActions.JOIN,
                        AuditActions.QUIT,
                        AuditActions.SWITCH,
                        AuditActions.PRIVATE_MESSAGE,
                        AuditActions.AUDIT_VIEW))
                .extracting(AuditAction::key)
                .containsExactly(
                        "pantheon:chat",
                        "pantheon:command",
                        "pantheon:join",
                        "pantheon:quit",
                        "pantheon:switch",
                        "pantheon:private_message",
                        "pantheon:audit_view");
    }
}
