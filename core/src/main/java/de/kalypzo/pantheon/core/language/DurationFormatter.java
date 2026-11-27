package de.kalypzo.pantheon.core.language;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;

/** Formats a Duration as e.g. {@code 2 Minuten 5 Sekunden}. Zero units are omitted; zero is {@code 0 Sekunden}. */
public final class DurationFormatter {

    /** Renders one unit ({@code day|hour|minute|second}) with its count, from {@code pantheon.time.<unit>}. */
    @FunctionalInterface
    public interface UnitRenderer {
        Component render(String unit, long count);
    }

    private DurationFormatter() {}

    public static Component format(Duration duration, UnitRenderer units) {
        Duration d = duration.abs();
        long[] values = {d.toDays(), d.toHoursPart(), d.toMinutesPart(), d.toSecondsPart()};
        String[] names = {"day", "hour", "minute", "second"};
        List<Component> parts = new ArrayList<>();
        for (int i = 0; i < values.length; i++) {
            if (values[i] > 0) {
                parts.add(units.render(names[i], values[i]));
            }
        }
        if (parts.isEmpty()) {
            parts.add(units.render("second", 0));
        }
        return Component.join(net.kyori.adventure.text.JoinConfiguration.separator(Component.space()), parts);
    }
}
