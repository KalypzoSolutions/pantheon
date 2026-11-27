package de.kalypzo.pantheon.core.language;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Result of {@link LanguageCheck}: what is wrong with the language files of one plugin. */
public final class CheckReport {

    public enum Kind { MISSING, UNUSED, PLACEHOLDER_MISMATCH, RESERVED_PLACEHOLDER, UNSTYLED_TYPE }

    /**
     * One problem. {@code file} is empty for {@link Kind#UNSTYLED_TYPE}.
     */
    public record Finding(Kind kind, String file, String key, String detail, @Nullable String enumConstant) {}

    private static final String PREFIX = "[Language] ";

    private final List<Finding> findings;

    CheckReport(List<Finding> findings) {
        this.findings = List.copyOf(findings);
    }

    public List<Finding> findings() {
        return findings;
    }

    /** No finding that fails a build. An unstyled type is only a warning: it falls back to {@code DEFAULT}. */
    public boolean ok() {
        return findings.stream().allMatch(f -> f.kind() == Kind.UNSTYLED_TYPE);
    }

    /** Lines for the startup log (all at WARNING). Missing and unused keys are grouped per file. */
    public List<String> logLines() {
        List<String> out = new ArrayList<>();
        Map<String, List<Finding>> grouped = new LinkedHashMap<>();
        for (Finding f : findings) {
            if (f.kind() == Kind.MISSING || f.kind() == Kind.UNUSED) {
                grouped.computeIfAbsent(f.kind() + "\0" + f.file(), k -> new ArrayList<>()).add(f);
            }
        }
        List<String> done = new ArrayList<>();
        for (Finding f : findings) {
            if (f.kind() == Kind.MISSING || f.kind() == Kind.UNUSED) {
                String group = f.kind() + "\0" + f.file();
                if (!done.contains(group)) {
                    done.add(group);
                    out.addAll(group(f.kind(), f.file(), grouped.get(group)));
                }
            } else {
                out.add(PREFIX + (f.kind() == Kind.UNSTYLED_TYPE ? unstyled(f) : line(f)));
            }
        }
        return out;
    }

    /** One line per failing finding, for {@code AssertionError}. */
    public String assertionMessage() {
        return String.join("\n", findings.stream().filter(f -> f.kind() != Kind.UNSTYLED_TYPE)
                .map(CheckReport::line).toList());
    }

    private static List<String> group(Kind kind, String file, List<Finding> list) {
        List<String> out = new ArrayList<>();
        int n = list.size();
        if (kind == Kind.MISSING) {
            out.add(PREFIX + file + ": " + n + (n == 1 ? " Key fehlt" : " Keys fehlen"));
            int width = list.stream().mapToInt(f -> f.key().length()).max().orElse(0) + 3;
            for (Finding f : list) {
                out.add(PREFIX + "  - " + f.key() + " ".repeat(width - f.key().length()) + "(" + f.enumConstant() + ")");
            }
        } else {
            out.add(PREFIX + file + ": " + n + (n == 1 ? " Key wird nicht verwendet: " : " Keys werden nicht verwendet: ")
                    + String.join(", ", list.stream().map(Finding::key).toList()));
        }
        return out;
    }

    private static String line(Finding f) {
        return switch (f.kind()) {
            case MISSING -> f.file() + ": Key fehlt: " + f.key() + " (" + f.enumConstant() + ")"
                    + (f.detail().isEmpty() ? "" : " - " + f.detail());
            case UNUSED -> f.file() + ": Key wird nicht verwendet: " + f.key();
            case PLACEHOLDER_MISMATCH -> f.file() + ": Platzhalter weicht ab bei " + f.key() + ": " + f.detail();
            case RESERVED_PLACEHOLDER -> f.file() + ": Reservierter Platzhalter " + f.detail() + " bei " + f.key();
            case UNSTYLED_TYPE -> unstyled(f);
        };
    }

    private static String unstyled(Finding f) {
        return "Message-Type '" + f.key() + "' hat keinen eigenen Stil, es gilt der Stil von DEFAULT ("
                + f.enumConstant() + ")";
    }
}
