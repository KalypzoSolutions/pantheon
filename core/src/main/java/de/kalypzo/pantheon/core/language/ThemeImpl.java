package de.kalypzo.pantheon.core.language;

import de.kalypzo.pantheon.language.Theme;
import de.kalypzo.pantheon.language.ThemeStyle;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags;

/** The node's theme: the defaults, overridden field by field. Immutable and thread safe. */
public final class ThemeImpl implements Theme {

    private static final Map<ThemeStyle, Style> DEFAULTS = defaults();

    /** The built-in theme, used without a {@code theme.yml} and for every invalid entry. */
    public static final ThemeImpl DEFAULT = of(Map.of());

    private final Map<ThemeStyle, Style> styles;
    private final TagResolver resolver;
    private final MiniMessage miniMessage;
    private final String hash;

    private ThemeImpl(Map<ThemeStyle, Style> styles) {
        this.styles = styles;
        TagResolver.Builder b = TagResolver.builder();
        styles.forEach((s, style) -> b.tag(s.tagName(), Tag.styling(builder -> builder.merge(style))));
        this.resolver = b.build();
        this.miniMessage = MiniMessage.builder().tags(TagResolver.resolver(StandardTags.defaults(), resolver)).build();
        this.hash = hash(styles);
    }

    /**
     * Each override is merged into its default: a set color replaces the color, every decoration that is not
     * {@code NOT_SET} replaces the decoration, everything else stays. Missing tags keep their default.
     */
    public static ThemeImpl of(Map<ThemeStyle, Style> overrides) {
        Map<ThemeStyle, Style> out = new EnumMap<>(ThemeStyle.class);
        for (ThemeStyle s : ThemeStyle.values()) {
            Style base = DEFAULTS.get(s);
            Style override = overrides.get(s);
            out.put(s, override == null ? base : merge(base, override));
        }
        return new ThemeImpl(java.util.Collections.unmodifiableMap(out));
    }

    private static Style merge(Style base, Style override) {
        Style.Builder b = base.toBuilder();
        if (override.color() != null) {
            b.color(override.color());
        }
        for (TextDecoration d : TextDecoration.values()) {
            TextDecoration.State state = override.decoration(d);
            if (state != TextDecoration.State.NOT_SET) {
                b.decoration(d, state);
            }
        }
        return b.build();
    }

    @Override
    public Style style(ThemeStyle style) {
        return styles.get(Objects.requireNonNull(style, "style"));
    }

    @Override
    public TextColor color(ThemeStyle style) {
        return Objects.requireNonNull(style(style).color(), "theme color");
    }

    /** One styling tag per theme tag. */
    public TagResolver resolver() {
        return resolver;
    }

    /** MiniMessage with the standard tags and this theme's tags. */
    public MiniMessage miniMessage() {
        return miniMessage;
    }

    /** First 8 hex characters of a SHA-256 over the resolved styles, to compare nodes in the log. */
    public String hash() {
        return hash;
    }

    private static String hash(Map<ThemeStyle, Style> styles) {
        StringBuilder sb = new StringBuilder();
        styles.forEach((s, style) -> {
            sb.append(s.tagName()).append('=').append(Objects.requireNonNull(style.color()).asHexString());
            for (TextDecoration d : TextDecoration.values()) {
                sb.append(',').append(d.name()).append(':').append(style.decoration(d).name());
            }
            sb.append(';');
        });
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<ThemeStyle, Style> defaults() {
        Map<ThemeStyle, Style> d = new EnumMap<>(ThemeStyle.class);
        d.put(ThemeStyle.P, Style.style(TextColor.color(0xd4d4d4)).decoration(TextDecoration.ITALIC, false));
        d.put(ThemeStyle.HIGHLIGHT, Style.style(TextColor.color(0xfb923c)));
        d.put(ThemeStyle.SUCCESS, Style.style(TextColor.color(0x7ef874)));
        d.put(ThemeStyle.DENY, Style.style(TextColor.color(0xf87171)));
        d.put(ThemeStyle.ERROR, Style.style(TextColor.color(0xdc2626)));
        d.put(ThemeStyle.PROMPT, Style.style(TextColor.color(0xfdba74)));
        d.put(ThemeStyle.H1, Style.style(TextColor.color(0xffedd5)));
        d.put(ThemeStyle.H2, Style.style(TextColor.color(0xfdba74)));
        return java.util.Collections.unmodifiableMap(d);
    }
}
