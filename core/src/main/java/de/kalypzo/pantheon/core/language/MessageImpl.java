package de.kalypzo.pantheon.core.language;

import de.kalypzo.pantheon.core.language.RecipientExpander.Recipient;
import de.kalypzo.pantheon.language.Arg;
import de.kalypzo.pantheon.language.Message;
import de.kalypzo.pantheon.language.MessageKey;
import de.kalypzo.pantheon.language.MessageType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.title.Title;
import org.jspecify.annotations.Nullable;

/** An immutable prepared message. */
final class MessageImpl implements Message {

    private final LanguageImpl lang;
    private final MessageKey key;
    private final List<Arg> args;
    private final @Nullable Long count;
    private final @Nullable MessageType type;
    private final boolean silent;

    private MessageImpl(LanguageImpl lang, MessageKey key, List<Arg> args, @Nullable Long count,
            @Nullable MessageType type, boolean silent) {
        this.lang = lang;
        this.key = key;
        this.args = List.copyOf(args);
        this.count = count;
        this.type = type;
        this.silent = silent;
    }

    static MessageImpl of(LanguageImpl lang, MessageKey key) {
        return new MessageImpl(lang, Objects.requireNonNull(key, "key"), List.of(), null, null, false);
    }

    MessageImpl withArgs(List<Arg> more) {
        List<Arg> all = new ArrayList<>(args);
        all.addAll(more);
        return new MessageImpl(lang, key, all, count, type, silent);
    }

    @Override
    public Message with(String name, Object value) {
        return withArgs(List.of(Arg.arg(name, value)));
    }

    @Override
    public Message with(String name, Function<Audience, ?> perViewer) {
        return withArgs(List.of(Arg.arg(name, perViewer)));
    }

    @Override
    public Message with(TagResolver... resolvers) {
        List<Arg> more = new ArrayList<>();
        for (TagResolver r : resolvers) {
            more.add(Arg.of(r));
        }
        return withArgs(more);
    }

    @Override
    public Message count(long count) {
        return new MessageImpl(lang, key, args, count, type, silent);
    }

    @Override
    public Message type(MessageType type) {
        return new MessageImpl(lang, key, args, count, Objects.requireNonNull(type, "type"), silent);
    }

    @Override
    public Message silent() {
        return new MessageImpl(lang, key, args, count, type, true);
    }

    private MessageStyles.Style style() {
        return MessageStyles.resolve(type != null ? type : key.type(), lang.config().styles());
    }

    // --- channels with template and sound

    @Override
    public void send(Audience audience) {
        send(List.of(audience));
    }

    @Override
    public void send(Collection<? extends Audience> audiences) {
        MessageStyles.Style style = style();
        forEachRecipient(audiences, r -> {
            Component content = lang.renderer().render(key, r.locale(), r.audience(), args, count);
            r.audience().sendMessage(
                    MessageStyles.apply(lang.theme(), style.chat(), lang.prefix(r.locale(), r.audience()), content));
            playSound(r, style);
        });
    }

    @Override
    public void broadcast() {
        send(lang.hooks().broadcastTarget().get());
    }

    @Override
    public void actionbar(Audience audience) {
        actionbar(List.of(audience));
    }

    @Override
    public void actionbar(Collection<? extends Audience> audiences) {
        MessageStyles.Style style = style();
        forEachRecipient(audiences, r -> {
            Component content = lang.renderer().render(key, r.locale(), r.audience(), args, count);
            r.audience().sendActionBar(
                    MessageStyles.apply(lang.theme(), style.actionbar(), lang.prefix(r.locale(), r.audience()), content));
            playSound(r, style);
        });
    }

    @Override
    public void title(Audience audience) {
        title(List.of(audience), null);
    }

    @Override
    public void title(Audience audience, Title.Times times) {
        title(List.of(audience), times);
    }

    @Override
    public void title(Collection<? extends Audience> audiences, Title.Times times) {
        MessageStyles.Style style = style();
        forEachRecipient(audiences, r -> {
            Renderer.Titled t = lang.renderer().renderTitle(key, r.locale(), r.audience(), args, count);
            Component prefix = lang.prefix(r.locale(), r.audience());
            Component title = MessageStyles.apply(lang.theme(), style.title(), prefix, t.title());
            Component subtitle = t.subtitle() == null
                    ? Component.empty()
                    : MessageStyles.apply(lang.theme(), style.subtitle(), prefix, t.subtitle());
            r.audience().showTitle(times == null ? Title.title(title, subtitle) : Title.title(title, subtitle, times));
            playSound(r, style);
        });
    }

    private void forEachRecipient(Collection<? extends Audience> audiences, java.util.function.Consumer<Recipient> action) {
        for (Audience audience : audiences) {
            for (Recipient r : lang.expander().expand(audience)) {
                action.accept(r);
            }
        }
    }

    private void playSound(Recipient r, MessageStyles.Style style) {
        if (!silent && style.sound() != null) {
            r.audience().playSound(style.sound());
        }
    }

    // --- rendering only

    @Override
    public Component component(Audience audience) {
        return component(lang.locale(audience), audience);
    }

    @Override
    public Component component(Locale locale) {
        return component(locale, Audience.empty());
    }

    private Component component(Locale locale, Audience viewer) {
        return notItalic(lang.renderer().render(key, locale, viewer, args, count));
    }

    @Override
    public List<Component> lines(Audience audience) {
        return lines(lang.locale(audience), audience);
    }

    @Override
    public List<Component> lines(Locale locale) {
        return lines(locale, Audience.empty());
    }

    private List<Component> lines(Locale locale, Audience viewer) {
        return lang.renderer().renderLines(key, locale, viewer, args, count).stream()
                .map(MessageImpl::notItalic)
                .toList();
    }

    @Override
    public String plain(Audience audience) {
        return PlainTextComponentSerializer.plainText().serialize(component(audience));
    }

    @Override
    public String plain(Locale locale) {
        return PlainTextComponentSerializer.plainText().serialize(component(locale));
    }

    private static Component notItalic(Component c) {
        return c.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }
}
