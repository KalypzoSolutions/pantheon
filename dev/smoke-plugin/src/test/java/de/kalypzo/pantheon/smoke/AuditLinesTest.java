package de.kalypzo.pantheon.smoke;

import static org.assertj.core.api.Assertions.assertThat;

import de.kalypzo.pantheon.audit.AuditPosition;
import de.kalypzo.pantheon.audit.TimelineEntry;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuditLinesTest {

    private static final UUID PLAYER = new UUID(1, 2);

    private static TimelineEntry entry(String at, String server, String action, UUID trace, AuditPosition pos,
            Map<String, Object> with) {
        return new TimelineEntry(UUID.randomUUID(), Instant.parse(at), PLAYER, server, action,
                Optional.ofNullable(pos), Optional.empty(), Optional.ofNullable(trace), with);
    }

    @Test
    void plainEntryShowsTimeServerActionAndWith() {
        Map<String, Object> with = new LinkedHashMap<>();
        with.put("from", "lobby-01");
        with.put("to", "lobby-02");
        var e = entry("2026-11-27T14:03:05Z", "proxy-01", "pantheon:switch", null, null, with);

        assertThat(AuditLines.format(List.of(e), ZoneOffset.UTC)).singleElement().asString()
                .startsWith("27.11. 14:03:05  proxy-01")
                .contains("pantheon:switch")
                .endsWith("from=lobby-01 to=lobby-02");
    }

    @Test
    void positionIsShownAsWorldAndRoundedCoordinates() {
        var e = entry("2026-11-27T14:02:05Z", "lobby-01", "shop:open", null, new AuditPosition("world", 12.4, 64, -3.6),
                Map.of());

        assertThat(AuditLines.format(List.of(e), ZoneOffset.UTC).getFirst()).contains("world 12/64/-4");
    }

    @Test
    void traceEntriesGetABracket() {
        UUID t = UUID.randomUUID();
        var open = entry("2026-11-27T14:02:05Z", "lobby-01", "smoke:open", t, null, Map.of());
        var buy = entry("2026-11-27T14:02:06Z", "lobby-01", "smoke:buy", t, null, Map.of());
        var close = entry("2026-11-27T14:02:07Z", "lobby-01", "smoke:close", t, null, Map.of());
        var other = entry("2026-11-27T14:02:08Z", "lobby-01", "pantheon:quit", null, null, Map.of());

        List<String> lines = AuditLines.format(List.of(open, buy, close, other), ZoneOffset.UTC);

        assertThat(lines.get(0)).contains("┌ smoke:open");
        assertThat(lines.get(1)).contains("│ smoke:buy");
        assertThat(lines.get(2)).contains("└ smoke:close");
        assertThat(lines.get(3)).doesNotContain("┌", "│", "└").contains("pantheon:quit");
    }

    @Test
    void valuesAreFormattedPlainly() {
        Map<String, Object> with = new LinkedHashMap<>();
        with.put("amount", 250L);
        with.put("ok", true);
        with.put("text", "hast du kurz zeit?");
        var e = entry("2026-11-27T14:02:09Z", "lobby-01", "economy:pay", null, null, with);

        assertThat(AuditLines.format(List.of(e), ZoneOffset.UTC).getFirst())
                .endsWith("amount=250 ok=true text=hast du kurz zeit?");
    }
}
