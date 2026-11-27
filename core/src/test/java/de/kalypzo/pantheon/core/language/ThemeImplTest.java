package de.kalypzo.pantheon.core.language;

import static de.kalypzo.pantheon.language.ThemeStyle.*;
import static org.assertj.core.api.Assertions.assertThat;

import de.kalypzo.pantheon.language.ThemeStyle;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.junit.jupiter.api.Test;

class ThemeImplTest {

    @Test
    void defaultsMatchSpec() {
        Map<ThemeStyle, Integer> expected = Map.of(P, 0xd4d4d4, HIGHLIGHT, 0xfb923c, SUCCESS, 0x7ef874,
                DENY, 0xf87171, ERROR, 0xdc2626, PROMPT, 0xfdba74, H1, 0xffedd5, H2, 0xfdba74);
        expected.forEach((s, rgb) -> assertThat(ThemeImpl.DEFAULT.color(s)).isEqualTo(TextColor.color(rgb)));
        assertThat(ThemeImpl.DEFAULT.style(P).decoration(TextDecoration.ITALIC)).isEqualTo(TextDecoration.State.FALSE);
        assertThat(ThemeImpl.DEFAULT.style(H1).decoration(TextDecoration.BOLD)).isEqualTo(TextDecoration.State.NOT_SET);
    }

    @Test
    void overrideKeepsDefaultFields() {
        ThemeImpl t = ThemeImpl.of(Map.of(P, Style.style(TextDecoration.BOLD)));
        assertThat(t.color(P)).isEqualTo(TextColor.color(0xd4d4d4));
        assertThat(t.style(P).decoration(TextDecoration.ITALIC)).isEqualTo(TextDecoration.State.FALSE);
        assertThat(t.style(P).decoration(TextDecoration.BOLD)).isEqualTo(TextDecoration.State.TRUE);
    }

    @Test
    void overrideColorKeepsDecorations() {
        ThemeImpl t = ThemeImpl.of(Map.of(P, Style.style(TextColor.color(0xffffff))));
        assertThat(t.color(P)).isEqualTo(TextColor.color(0xffffff));
        assertThat(t.style(P).decoration(TextDecoration.ITALIC)).isEqualTo(TextDecoration.State.FALSE);
    }

    @Test
    void falseSwitchesDefaultDecorationOff() {
        ThemeImpl on = ThemeImpl.of(Map.of(P, Style.style().decoration(TextDecoration.ITALIC, true).build()));
        assertThat(on.style(P).decoration(TextDecoration.ITALIC)).isEqualTo(TextDecoration.State.TRUE);
        ThemeImpl off = ThemeImpl.of(Map.of(H1, Style.style().decoration(TextDecoration.BOLD, false).build()));
        assertThat(off.style(H1).decoration(TextDecoration.BOLD)).isEqualTo(TextDecoration.State.FALSE);
    }

    @Test
    void resolverRendersTags() {
        Component c = ThemeImpl.DEFAULT.miniMessage().deserialize("<h1>a</h1><deny>b</deny>");
        assertThat(c.children()).extracting(Component::color)
                .containsExactly(TextColor.color(0xffedd5), TextColor.color(0xf87171));
    }

    @Test
    void hashIsStableAndChangesWithTheme() {
        assertThat(ThemeImpl.DEFAULT.hash()).hasSize(8).isEqualTo(ThemeImpl.of(Map.of()).hash());
        assertThat(ThemeImpl.of(Map.of(SUCCESS, Style.style(TextColor.color(0x000001)))).hash())
                .isNotEqualTo(ThemeImpl.DEFAULT.hash());
    }
}
