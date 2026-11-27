package de.kalypzo.pantheon.core.language;

import de.kalypzo.pantheon.language.ThemeStyle;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Reads the node's {@code theme.yml}. Never fails: every problem is logged and falls back to the default. */
public final class ThemeLoader {

    private static final String RESOURCE = "/pantheon/theme.yml";
    private static final Pattern HEX = Pattern.compile("^#[0-9a-fA-F]{6}$");
    private static final Map<String, TextDecoration> DECORATIONS = Map.of(
            "bold", TextDecoration.BOLD,
            "italic", TextDecoration.ITALIC,
            "underlined", TextDecoration.UNDERLINED,
            "strikethrough", TextDecoration.STRIKETHROUGH,
            "obfuscated", TextDecoration.OBFUSCATED);

    private ThemeLoader() {}

    /** Reads the file, or writes the bundled default there if it is missing. Logs {@code Theme <hash>}. */
    public static ThemeImpl load(Path file, Logger log) {
        ThemeImpl theme;
        if (Files.notExists(file)) {
            writeDefault(file, log);
            theme = ThemeImpl.DEFAULT;
        } else {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                theme = parse(reader, log);
            } catch (IOException | RuntimeException e) {
                log.log(Level.SEVERE, "Cannot read " + file + ", using the default theme", e);
                theme = ThemeImpl.DEFAULT;
            }
        }
        log.info("Theme " + theme.hash());
        return theme;
    }

    private static void writeDefault(Path file, Logger log) {
        try (InputStream in = ThemeLoader.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IOException("missing resource " + RESOURCE);
            }
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.copy(in, file);
        } catch (IOException | RuntimeException e) {
            log.warning("Cannot write the default theme to " + file + ": " + e);
        }
    }

    /** Parses a theme file. Invalid YAML gives the default theme and a SEVERE, invalid entries a WARNING each. */
    public static ThemeImpl parse(Reader reader, Logger log) {
        Object root;
        try {
            root = new Yaml(new SafeConstructor(new LoaderOptions())).load(reader);
        } catch (RuntimeException e) {
            log.severe("theme.yml is not valid YAML, using the default theme: " + e.getMessage());
            return ThemeImpl.DEFAULT;
        }
        if (root == null) {
            return ThemeImpl.DEFAULT;
        }
        if (!(root instanceof Map<?, ?> map)) {
            log.severe("theme.yml must be a map with 'styles', using the default theme");
            return ThemeImpl.DEFAULT;
        }
        Object styles = map.get("styles");
        if (styles == null) {
            return ThemeImpl.DEFAULT;
        }
        if (!(styles instanceof Map<?, ?> entries)) {
            log.severe("theme.yml: 'styles' must be a map, using the default theme");
            return ThemeImpl.DEFAULT;
        }
        Map<ThemeStyle, Style> overrides = new EnumMap<>(ThemeStyle.class);
        for (Map.Entry<?, ?> e : entries.entrySet()) {
            String name = String.valueOf(e.getKey());
            Optional<ThemeStyle> tag = tag(name);
            if (tag.isEmpty()) {
                log.warning("theme.yml: unknown tag '" + name + "' ignored");
                continue;
            }
            try {
                overrides.put(tag.get(), entry(name, e.getValue(), log));
            } catch (IllegalArgumentException ex) {
                log.warning("theme.yml: tag '" + name + "' uses its default: " + ex.getMessage());
            }
        }
        return ThemeImpl.of(overrides);
    }

    private static Optional<ThemeStyle> tag(String name) {
        for (ThemeStyle s : ThemeStyle.values()) {
            if (s.tagName().equals(name)) {
                return Optional.of(s);
            }
        }
        return Optional.empty();
    }

    private static Style entry(String name, Object value, Logger log) {
        if (value instanceof String s) {
            return Style.style(color(s));
        }
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(value == null
                    ? "no value (an unquoted # starts a YAML comment, put the color in quotes)"
                    : "expected a color string or an object, got " + value);
        }
        Style.Builder b = Style.style();
        for (Map.Entry<?, ?> f : map.entrySet()) {
            String key = String.valueOf(f.getKey());
            Object v = f.getValue();
            if (key.equals("color")) {
                if (!(v instanceof String s)) {
                    throw new IllegalArgumentException("color must be a string in quotes, got " + v);
                }
                b.color(color(s));
            } else if (DECORATIONS.containsKey(key)) {
                if (!(v instanceof Boolean on)) {
                    throw new IllegalArgumentException(key + " must be true or false, got " + v);
                }
                b.decoration(DECORATIONS.get(key), on);
            } else {
                log.warning("theme.yml: unknown key '" + key + "' in tag '" + name + "' ignored");
            }
        }
        return b.build();
    }

    private static TextColor color(String value) {
        if (HEX.matcher(value).matches()) {
            return TextColor.fromHexString(value);
        }
        NamedTextColor named = NamedTextColor.NAMES.value(value.toLowerCase(Locale.ROOT));
        if (named == null) {
            throw new IllegalArgumentException("invalid color '" + value + "', expected \"#rrggbb\" or a name like gray");
        }
        return named;
    }
}
