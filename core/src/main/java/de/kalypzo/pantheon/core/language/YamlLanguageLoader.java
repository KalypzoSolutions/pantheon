package de.kalypzo.pantheon.core.language;

import java.io.Reader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Reads a language YAML file into a flat map of dotted keys. */
public final class YamlLanguageLoader {

    private static final Set<String> PLURAL_KEYS = Set.of("zero", "one", "two", "few", "many", "other");

    private YamlLanguageLoader() {}

    public static Map<String, LanguageEntry> load(Reader reader) {
        Object root = new Yaml(new SafeConstructor(new LoaderOptions())).load(reader);
        Map<String, LanguageEntry> out = new LinkedHashMap<>();
        if (root instanceof Map<?, ?> map) {
            walk("", map, out);
        }
        return out;
    }

    private static void walk(String prefix, Map<?, ?> map, Map<String, LanguageEntry> out) {
        for (var e : map.entrySet()) {
            String key = prefix.isEmpty() ? String.valueOf(e.getKey()) : prefix + "." + e.getKey();
            Object v = e.getValue();
            if (v instanceof Map<?, ?> sub) {
                LanguageEntry special = special(sub);
                if (special != null) {
                    out.put(key, special);
                } else {
                    walk(key, sub, out);
                }
            } else if (v instanceof List<?> list) {
                List<String> lines = new ArrayList<>();
                for (Object o : list) {
                    lines.add(str(o));
                }
                out.put(key, new LanguageEntry.Lines(lines));
            } else {
                out.put(key, new LanguageEntry.Text(str(v)));
            }
        }
    }

    private static LanguageEntry special(Map<?, ?> map) {
        if (map.isEmpty() || !map.values().stream().allMatch(v -> v != null && !(v instanceof Map) && !(v instanceof List))) {
            return null;
        }
        Set<String> keys = new java.util.HashSet<>();
        map.keySet().forEach(k -> keys.add(String.valueOf(k)));
        if (keys.contains("title") && Set.of("title", "subtitle").containsAll(keys)) {
            Object sub = map.get("subtitle");
            return new LanguageEntry.TitleEntry(str(map.get("title")), sub == null ? null : str(sub));
        }
        if (keys.contains("other") && PLURAL_KEYS.containsAll(keys)) {
            Map<String, String> forms = new LinkedHashMap<>();
            map.forEach((k, v) -> forms.put(String.valueOf(k), str(v)));
            return new LanguageEntry.Plural(forms);
        }
        return null;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
