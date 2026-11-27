package de.kalypzo.pantheon.core.language;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/** Reads placeholders out of templates (language.md §5.5, §10). */
public final class Placeholders {

    private static final Pattern TAG = Pattern.compile("<(/?)(!?)([A-Za-z0-9_-]+)((?::[^<>]*)?)>");
    private static final Set<String> THEME = java.util.Arrays.stream(de.kalypzo.pantheon.language.ThemeStyle.values())
            .map(de.kalypzo.pantheon.language.ThemeStyle::tagName)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    /** Reserved in every message file, whatever their arguments (R17). */
    private static final Set<String> ALWAYS_RESERVED = Set.of("arg", "argument", "prefix", "content");
    private static final MiniMessage MINI = ThemeImpl.DEFAULT.miniMessage();
    private static final java.util.Map<String, Boolean> BARE_OK = new ConcurrentHashMap<>();

    private Placeholders() {}

    /** Tag names of a template without standard tags and without theme tags, in order of appearance. */
    public static Set<String> of(String template) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = TAG.matcher(template);
        while (m.find()) {
            if (!m.group(1).isEmpty() || !m.group(2).isEmpty()) {
                continue;
            }
            String name = m.group(3).toLowerCase(Locale.ROOT);
            if (!StandardTags.defaults().has(name) && !THEME.contains(name)) {
                out.add(name);
            }
        }
        return out;
    }

    /**
     * Names in a message template that cannot work as placeholders: a bare standard tag that needs arguments
     * ({@code <score>}), {@code arg}, {@code argument}, {@code prefix}, {@code content}, and {@code count} unless
     * the entry is a plural. Standard tags that work bare ({@code <red>}, {@code <newline>}) are fine.
     */
    public static Set<String> reserved(String template, boolean plural) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = TAG.matcher(template);
        while (m.find()) {
            if (!m.group(1).isEmpty() || !m.group(2).isEmpty()) {
                continue;
            }
            String name = m.group(3).toLowerCase(Locale.ROOT);
            boolean bare = m.group(4).isEmpty();
            if (ALWAYS_RESERVED.contains(name) || (!plural && name.equals("count"))
                    || (bare && StandardTags.defaults().has(name) && !worksBare(name))) {
                out.add(name);
            }
        }
        return out;
    }

    private static boolean worksBare(String name) {
        return BARE_OK.computeIfAbsent(name, n -> {
            // A tag that cannot be built without arguments stays in the text as it was written.
            try {
                return !PlainTextComponentSerializer.plainText().serialize(MINI.deserialize("<" + n + ">x")).contains("<");
            } catch (RuntimeException e) {
                return false;
            }
        });
    }
}
