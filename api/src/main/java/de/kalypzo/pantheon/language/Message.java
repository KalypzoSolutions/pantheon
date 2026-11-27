package de.kalypzo.pantheon.language;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;

/** An immutable, prepared message. Every modifier returns a new instance. Thread safe. */
public interface Message {

    Message with(String name, Object value);

    Message with(String name, Function<Audience, ?> perViewer);

    Message with(TagResolver... resolvers);

    /** Picks the plural form and sets {@code <count>}. */
    Message count(long count);

    /** Overrides the message type for this call. */
    Message type(MessageType type);

    /** Suppresses the sound of the message type. */
    Message silent();

    void send(Audience audience);

    void send(Collection<? extends Audience> audiences);

    /** To all online players and the console. */
    void broadcast();

    void actionbar(Audience audience);

    void actionbar(Collection<? extends Audience> audiences);

    void title(Audience audience);

    void title(Audience audience, Title.Times times);

    void title(Collection<? extends Audience> audiences, Title.Times times);

    Component component(Audience audience);

    Component component(Locale locale);

    List<Component> lines(Audience audience);

    List<Component> lines(Locale locale);

    String plain(Audience audience);

    String plain(Locale locale);
}
