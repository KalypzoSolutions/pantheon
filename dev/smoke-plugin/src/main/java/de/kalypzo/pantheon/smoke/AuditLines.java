package de.kalypzo.pantheon.smoke;

import de.kalypzo.pantheon.audit.AuditPosition;
import de.kalypzo.pantheon.audit.TimelineEntry;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Renders timeline entries as text lines like audit_log.md §10. Pure, so it can be tested without a server. */
final class AuditLines {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd.MM. HH:mm:ss");

    private AuditLines() {}

    /** @param chronological oldest first; a trace is drawn as a bracket from its first to its last entry */
    static List<String> format(List<TimelineEntry> chronological, ZoneId zone) {
        Map<UUID, Integer> first = new HashMap<>();
        Map<UUID, Integer> last = new HashMap<>();
        for (int i = 0; i < chronological.size(); i++) {
            int index = i;
            chronological.get(i).trace().ifPresent(t -> {
                first.putIfAbsent(t, index);
                last.put(t, index);
            });
        }
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < chronological.size(); i++) {
            TimelineEntry e = chronological.get(i);
            int index = i;
            String bracket = e.trace().map(t -> first.get(t) == index ? "┌ " : last.get(t) == index ? "└ " : "│ ")
                    .orElse("  ");
            StringBuilder with = new StringBuilder();
            e.with().forEach((k, v) -> with.append(with.isEmpty() ? "" : " ").append(k).append('=').append(v));
            String line = String.format("%s  %-9s  %s%-22s %-16s %s", TIME.format(e.at().atZone(zone)), e.server(),
                    bracket, e.action(), e.position().map(AuditLines::position).orElse(""), with);
            lines.add(line.stripTrailing());
        }
        return lines;
    }

    private static String position(AuditPosition p) {
        return p.world() + " " + Math.round(p.x()) + "/" + Math.round(p.y()) + "/" + Math.round(p.z());
    }
}
