package de.kalypzo.pantheon.core.language;

import de.kalypzo.pantheon.player.PanPlayer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.audience.ForwardingAudience;

/** Splits an audience into single recipients, each with its language. */
final class RecipientExpander {

    /** One recipient: where to deliver, and the language to render in. */
    record Recipient(Audience audience, Locale locale) {}

    private final LanguageHooks hooks;
    private final Function<PanPlayer, Locale> localeOf;
    private final Supplier<Locale> defaultLocale;

    RecipientExpander(LanguageHooks hooks, Function<PanPlayer, Locale> localeOf, Supplier<Locale> defaultLocale) {
        this.hooks = hooks;
        this.localeOf = localeOf;
        this.defaultLocale = defaultLocale;
    }

    List<Recipient> expand(Audience audience) {
        List<Recipient> out = new ArrayList<>();
        expand(audience, out);
        return out;
    }

    private void expand(Audience audience, List<Recipient> out) {
        // PanPlayer first: it is a ForwardingAudience but one recipient
        if (audience instanceof PanPlayer player) {
            out.add(player(player));
            return;
        }
        Optional<PanPlayer> looked = hooks.playerLookup().apply(audience);
        if (looked.isPresent()) {
            out.add(player(looked.get()));
            return;
        }
        if (audience instanceof ForwardingAudience forwarding) {
            // audiences() instead of forEachAudience: Adventure's version would also unpack a nested PanPlayer
            for (Audience member : forwarding.audiences()) {
                expand(member, out);
            }
            return;
        }
        out.add(new Recipient(audience, defaultLocale.get()));
    }

    private Recipient player(PanPlayer player) {
        Locale locale = localeOf.apply(player);
        return new Recipient(player, locale != null ? locale : defaultLocale.get());
    }
}
