package de.kalypzo.pantheon.core.language;

import de.kalypzo.pantheon.language.Arg;
import de.kalypzo.pantheon.language.MessageKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.jspecify.annotations.Nullable;

/**
 * Renders language entries to Components with theme tags and placeholders. Nothing is registered in the
 * GlobalTranslator. Thread safe.
 */
public final class Renderer {

    private static final Locale FALLBACK_LOCALE = Locale.of("de", "DE");
    private static final java.util.regex.Pattern LINE_BREAK =
            java.util.regex.Pattern.compile("(?i)<(?:newline|br)\\s*/?>");

    private final String plugin;
    private final LocaleResolver resolver;
    private final Logger log;
    private final MissingKeys seen = new MissingKeys();
    private final ArgResolvers args;

    private final java.util.function.Supplier<Locale> defaultLocale;
    private final java.util.function.Supplier<ThemeImpl> theme;

    /** With {@code de_DE} as fallback language. */
    public Renderer(String plugin, LocaleResolver resolver, ArgAdapters adapters, Logger log) {
        this(plugin, resolver, adapters, log, () -> FALLBACK_LOCALE);
    }

    /** @param defaultLocale the configured default language, last step of the lookup */
    public Renderer(String plugin, LocaleResolver resolver, ArgAdapters adapters, Logger log,
            java.util.function.Supplier<Locale> defaultLocale) {
        this(plugin, resolver, adapters, log, defaultLocale, () -> ThemeImpl.DEFAULT);
    }

    /**
     * @param defaultLocale the configured default language, last step of the lookup
     * @param theme the node's theme, read on every render
     */
    public Renderer(String plugin, LocaleResolver resolver, ArgAdapters adapters, Logger log,
            java.util.function.Supplier<Locale> defaultLocale, java.util.function.Supplier<ThemeImpl> theme) {
        this.defaultLocale = defaultLocale;
        this.theme = theme;
        this.plugin = plugin;
        this.resolver = resolver;
        this.log = log;
        this.args = new ArgResolvers(adapters, seen, log, plugin);
    }

    public Component render(MessageKey key, Locale locale, Audience viewer, List<Arg> args, @Nullable Long count) {
        Optional<LanguageEntry> entry = resolver.resolve(key.key(), locale, defaultLocale.get());
        if (entry.isEmpty()) {
            return missing(key, locale);
        }
        return switch (entry.get()) {
            case LanguageEntry.Text t -> deserialize(t.value(), locale, viewer, args, count);
            case LanguageEntry.TitleEntry t -> deserialize(t.title(), locale, viewer, args, count);
            case LanguageEntry.Plural p -> deserialize(plural(p, count), locale, viewer, args, count);
            case LanguageEntry.Lines l -> Component.join(
                    net.kyori.adventure.text.JoinConfiguration.newlines(),
                    l.lines().stream().map(s -> deserialize(s, locale, viewer, args, count)).toList());
        };
    }

    /** One Component per line; a title entry gives title and subtitle. */
    public List<Component> renderLines(MessageKey key, Locale locale, Audience viewer, List<Arg> args,
            @Nullable Long count) {
        Optional<LanguageEntry> entry = resolver.resolve(key.key(), locale, defaultLocale.get());
        if (entry.isEmpty()) {
            return List.of(missing(key, locale));
        }
        return switch (entry.get()) {
            case LanguageEntry.Lines l -> {
                List<Component> out = new ArrayList<>();
                for (String line : l.lines()) {
                    out.add(deserialize(line, locale, viewer, args, count));
                }
                yield out;
            }
            case LanguageEntry.TitleEntry t -> t.subtitle() == null
                    ? List.of(deserialize(t.title(), locale, viewer, args, count))
                    : List.of(deserialize(t.title(), locale, viewer, args, count),
                            deserialize(t.subtitle(), locale, viewer, args, count));
            case LanguageEntry.Text t -> splitLines(t.value(), locale, viewer, args, count);
            case LanguageEntry.Plural p -> splitLines(plural(p, count), locale, viewer, args, count);
        };
    }

    /** Title and optional subtitle: a title entry gives both, everything else only a title. */
    public Titled renderTitle(MessageKey key, Locale locale, Audience viewer, List<Arg> args, @Nullable Long count) {
        Optional<LanguageEntry> entry = resolver.resolve(key.key(), locale, defaultLocale.get());
        if (entry.isPresent() && entry.get() instanceof LanguageEntry.TitleEntry t) {
            Component title = deserialize(t.title(), locale, viewer, args, count);
            Component sub = t.subtitle() == null ? null : deserialize(t.subtitle(), locale, viewer, args, count);
            return new Titled(title, sub);
        }
        return new Titled(render(key, locale, viewer, args, count), null);
    }

    /** A rendered title. */
    public record Titled(Component title, @Nullable Component subtitle) {}

    private List<Component> splitLines(String template, Locale locale, Audience viewer, List<Arg> args,
            @Nullable Long count) {
        List<Component> out = new ArrayList<>();
        for (String line : LINE_BREAK.split(template, -1)) {
            out.add(deserialize(line, locale, viewer, args, count));
        }
        return out;
    }

    private static String plural(LanguageEntry.Plural p, @Nullable Long count) {
        Map<String, String> forms = p.forms();
        String t = PluralRules.select(forms, count == null ? 0 : count);
        return t != null ? t : "";
    }

    private Component missing(MessageKey key, Locale locale) {
        if (seen.firstMissing(key.key(), locale)) {
            log.warning(plugin + ": missing message '" + key.key() + "' for " + locale);
        }
        return Component.text(plugin + ":" + key.key(), NamedTextColor.RED);
    }

    private Component deserialize(String template, Locale locale, Audience viewer, List<Arg> list,
            @Nullable Long count) {
        DurationFormatter.UnitRenderer units = (unit, n) ->
                render(MessageKey.of("pantheon.time." + unit), locale, viewer, List.of(), n);
        TagResolver.Builder b = TagResolver.builder();
        b.resolver(args.resolve(list, locale, viewer, k -> render(k, locale, viewer, List.of(), null), units));
        if (count != null) {
            b.resolver(ArgResolvers.number("count", count, locale));
        }
        return theme.get().miniMessage().deserialize(template, b.build());
    }
}
