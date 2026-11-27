package de.kalypzo.pantheon.core.language;

import de.kalypzo.pantheon.language.Arg;
import de.kalypzo.pantheon.language.Message;
import de.kalypzo.pantheon.language.MessageKey;
import de.kalypzo.pantheon.player.PanPlayer;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.NumberFormat;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.time.temporal.ChronoField;
import java.time.temporal.TemporalAccessor;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.logging.Logger;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentLike;
import net.kyori.adventure.text.minimessage.Context;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.ArgumentQueue;
import net.kyori.adventure.text.minimessage.tag.resolver.Formatter;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

/** Turns Args into MiniMessage TagResolvers for one recipient (language.md §5.1). */
final class ArgResolvers {

    /** Renders a nested message key in the recipient's language. */
    @FunctionalInterface
    interface KeyRenderer {
        Component render(MessageKey key);
    }

    private final ArgAdapters adapters;
    private final MissingKeys seen;
    private final Logger log;
    private final String plugin;

    ArgResolvers(ArgAdapters adapters, MissingKeys seen, Logger log, String plugin) {
        this.adapters = adapters;
        this.seen = seen;
        this.log = log;
        this.plugin = plugin;
    }

    TagResolver resolve(List<Arg> args, Locale locale, Audience viewer, KeyRenderer keys,
            DurationFormatter.UnitRenderer units) {
        TagResolver.Builder b = TagResolver.builder();
        for (Arg arg : args) {
            switch (arg) {
                case Arg.Value v -> b.resolver(value(v.name(), v.value(), locale, viewer, keys, units));
                case Arg.PerViewer p -> {
                    Object value = p.value().apply(viewer);
                    b.resolver(value(p.name(), value == null ? "null" : value, locale, viewer, keys, units));
                }
                case Arg.Parsed p -> b.resolver(Placeholder.parsed(p.name(), p.miniMessage()));
                case Arg.Resolver r -> b.resolver(r.resolver());
            }
        }
        return b.build();
    }

    TagResolver value(String name, Object value, Locale locale, Audience viewer, KeyRenderer keys,
            DurationFormatter.UnitRenderer units) {
        if (value instanceof String s) {
            return Placeholder.unparsed(name, s);
        }
        if (value instanceof ComponentLike c) {
            return Placeholder.component(name, c);
        }
        if (value instanceof Number n) {
            return number(name, n, locale);
        }
        if (value instanceof Boolean bool) {
            return Formatter.booleanChoice(name, bool);
        }
        if (value instanceof Duration d) {
            return Placeholder.component(name, DurationFormatter.format(d, units));
        }
        if (value instanceof TemporalAccessor t) {
            return temporal(name, t, locale);
        }
        if (value instanceof MessageKey k) {
            return Placeholder.component(name, keys.render(k));
        }
        if (value instanceof Message m) {
            return Placeholder.component(name, m.component(viewer));
        }
        if (value instanceof PanPlayer p) {
            return Placeholder.unparsed(name, p.name());
        }
        Optional<Component> adapted = adapters.adapt(value, locale);
        if (adapted.isPresent()) {
            return Placeholder.component(name, adapted.get());
        }
        if (seen.firstUnknownType(value.getClass())) {
            log.warning(plugin + ": placeholder value of type " + value.getClass().getName()
                    + " has no adapter, using toString()");
        }
        return Placeholder.unparsed(name, String.valueOf(value));
    }

    static TagResolver number(String name, Number n, Locale locale) {
        return TagResolver.resolver(name, (BiFunction<ArgumentQueue, Context, Tag>) (queue, ctx) -> {
            NumberFormat format;
            if (queue.hasNext()) {
                String first = queue.pop().value();
                Locale l = locale;
                String pattern = first;
                if (queue.hasNext()) {
                    l = Locale.forLanguageTag(first);
                    pattern = queue.pop().value();
                }
                try {
                    format = new DecimalFormat(pattern, DecimalFormatSymbols.getInstance(l));
                } catch (IllegalArgumentException e) {
                    throw ctx.newException("Invalid number pattern '" + pattern + "'", queue);
                }
            } else {
                format = NumberFormat.getInstance(locale);
            }
            return Tag.inserting(Component.text(format.format(n)));
        });
    }

    private static TagResolver temporal(String name, TemporalAccessor value, Locale locale) {
        return TagResolver.resolver(name, (BiFunction<ArgumentQueue, Context, Tag>) (queue, ctx) -> {
            TemporalAccessor t = value instanceof Instant i ? i.atZone(ZoneId.systemDefault()) : value;
            Locale l = locale;
            DateTimeFormatter f;
            try {
                if (queue.hasNext()) {
                    String first = queue.pop().value();
                    String pattern = first;
                    if (queue.hasNext()) {
                        l = Locale.forLanguageTag(first);
                        pattern = queue.pop().value();
                    }
                    f = DateTimeFormatter.ofPattern(pattern, l);
                } else {
                    boolean date = t.isSupported(ChronoField.EPOCH_DAY);
                    boolean time = t.isSupported(ChronoField.NANO_OF_DAY);
                    f = date && time ? DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
                            : date ? DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
                            : DateTimeFormatter.ofLocalizedTime(FormatStyle.MEDIUM);
                    f = f.withLocale(l);
                }
                return Tag.inserting(Component.text(f.format(t)));
            } catch (RuntimeException e) {
                throw ctx.newException("Cannot format date: " + e.getMessage(), queue);
            }
        });
    }
}
