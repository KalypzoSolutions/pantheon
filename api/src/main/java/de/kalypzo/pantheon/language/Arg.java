package de.kalypzo.pantheon.language;

import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags;

/** A placeholder for a message. Names are {@code [a-z0-9_-]+} and must not clash with MiniMessage tags. */
public sealed interface Arg {

    /** Names that are reserved in addition to every standard MiniMessage tag. */
    Set<String> RESERVED = java.util.stream.Stream.concat(
                    java.util.stream.Stream.of("arg", "argument", "prefix", "content", "count"),
                    java.util.Arrays.stream(ThemeStyle.values()).map(ThemeStyle::tagName))
            .collect(java.util.stream.Collectors.toUnmodifiableSet());

    /** Placeholder with a plain value; how it is rendered depends on its type. */
    record Value(String name, Object value) implements Arg {
        public Value {
            checkName(name);
            Objects.requireNonNull(value, "value");
        }
    }

    /** Placeholder computed per recipient. */
    record PerViewer(String name, Function<Audience, ?> value) implements Arg {
        public PerViewer {
            checkName(name);
            Objects.requireNonNull(value, "value");
        }
    }

    /** Placeholder whose value is read as MiniMessage. Only for trusted input. */
    record Parsed(String name, String miniMessage) implements Arg {
        public Parsed {
            checkName(name);
            Objects.requireNonNull(miniMessage, "miniMessage");
        }
    }

    /** An Adventure resolver, passed on unchanged. */
    record Resolver(TagResolver resolver) implements Arg {
        public Resolver {
            Objects.requireNonNull(resolver, "resolver");
        }
    }

    static Arg arg(String name, Object value) {
        return new Value(name, value);
    }

    static Arg arg(String name, Function<Audience, ?> perViewer) {
        return new PerViewer(name, perViewer);
    }

    static Arg parsed(String name, String miniMessage) {
        return new Parsed(name, miniMessage);
    }

    static Arg of(TagResolver resolver) {
        return new Resolver(resolver);
    }

    /** True if the name is valid and not reserved. */
    static boolean isValidName(String name) {
        return Names.NAME.matcher(name).matches() && !isReserved(name);
    }

    /** True for every standard MiniMessage tag and the names in {@link #RESERVED}. */
    static boolean isReserved(String name) {
        return RESERVED.contains(name) || StandardTags.defaults().has(name);
    }

    /** @throws IllegalArgumentException if the name is invalid or reserved */
    static void checkName(String name) {
        Objects.requireNonNull(name, "name");
        if (!Names.NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Invalid placeholder name '" + name + "', expected [a-z0-9_-]+");
        }
        if (isReserved(name)) {
            throw new IllegalArgumentException("Placeholder name '" + name + "' is reserved");
        }
    }

    /** Holder, because interfaces cannot have private fields. */
    final class Names {
        private static final Pattern NAME = Pattern.compile("[a-z0-9_-]+");

        private Names() {}
    }
}
