package de.kalypzo.pantheon.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.kalypzo.pantheon.Pantheon;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class PantheonProviderTest {

    @AfterEach
    void cleanup() {
        PantheonProvider.unregister();
    }

    @Test
    void getWithoutRegistrationExplains() {
        PantheonProvider.unregister();
        assertThatThrownBy(() -> PantheonProvider.get(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Pantheon ist nicht geladen. Steht \"depend: [Pantheon]\" in der plugin.yml?");
    }

    @Test
    void reRegisterAfterUnregister() {
        Pantheon first = (Pantheon) java.lang.reflect.Proxy.newProxyInstance(
                Pantheon.class.getClassLoader(), new Class<?>[] {Pantheon.class}, (p, m, a) -> null);
        Pantheon second = (Pantheon) java.lang.reflect.Proxy.newProxyInstance(
                Pantheon.class.getClassLoader(), new Class<?>[] {Pantheon.class}, (p, m, a) -> null);
        PantheonProvider.register(plugin -> first);
        assertThat(PantheonProvider.get(null)).isSameAs(first);
        PantheonProvider.unregister();
        assertThatThrownBy(() -> PantheonProvider.get(null)).isInstanceOf(IllegalStateException.class);
        PantheonProvider.register(plugin -> second);
        assertThat(PantheonProvider.get(null)).isSameAs(second);
    }
}
