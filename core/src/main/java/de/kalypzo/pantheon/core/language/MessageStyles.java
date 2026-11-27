package de.kalypzo.pantheon.core.language;

import de.kalypzo.pantheon.language.MessageType;
import de.kalypzo.pantheon.language.StyleBuilder;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.jspecify.annotations.Nullable;

/** Templates and sounds of message types (language.md §7, E16). */
public final class MessageStyles {


    private static final Map<MessageType, Overrides> BUILTIN = Map.of(
            MessageType.PLAIN, builtin("<content>", "<content>", "<content>", null),
            MessageType.PROMPT, builtin("<newline>[!] <prefix> <highlight><b><content>",
                    "<highlight><b><content>", "<highlight><b><content>", sound("block.note_block.pling")),
            MessageType.DEFAULT, builtin("<prefix> <p><content>", "<p><content>", "<p><content>", null),
            MessageType.SUCCESS, builtin("<prefix> <success><content>", "<success><content>",
                    "<success><content>", sound("entity.experience_orb.pickup")),
            MessageType.DENY, builtin("<prefix> <deny><content>", "<deny><content>", "<deny><content>",
                    sound("entity.villager.no")),
            MessageType.ERROR, builtin("<error>[x] <prefix> <error><content>", "<error><content>",
                    "<error><content>", sound("block.anvil.destroy")));

    private MessageStyles() {}

    /** The effective style of a type. */
    public record Style(String chat, String actionbar, String title, String subtitle, @Nullable Sound sound) {}

    /** Mutable style of one type; unset parts are {@code null}. This is what a plugin configures. */
    public static final class Overrides implements StyleBuilder {
        private volatile @Nullable String chat;
        private volatile @Nullable String actionbar;
        private volatile @Nullable String title;
        private volatile @Nullable String subtitle;
        private volatile @Nullable Sound sound;
        private volatile boolean noSound;

        @Override
        public Overrides chat(String template) {
            this.chat = template;
            return this;
        }

        @Override
        public Overrides actionbar(String template) {
            this.actionbar = template;
            return this;
        }

        @Override
        public Overrides title(String template) {
            this.title = template;
            return this;
        }

        @Override
        public Overrides subtitle(String template) {
            this.subtitle = template;
            return this;
        }

        @Override
        public Overrides sound(Sound sound) {
            this.sound = sound;
            this.noSound = false;
            return this;
        }

        @Override
        public Overrides noSound() {
            this.sound = null;
            this.noSound = true;
            return this;
        }
    }

    private static Overrides builtin(String chat, String actionbar, String title, @Nullable Sound sound) {
        Overrides o = new Overrides().chat(chat).actionbar(actionbar).title(title).subtitle("<p><content>");
        if (sound != null) {
            o.sound(sound);
        }
        return o;
    }

    private static Sound sound(String key) {
        return Sound.sound(Key.key(key), Sound.Source.MASTER, 1f, 1f);
    }

    /**
     * Resolves a type: the plugin's own style, then the built-in one; a type that is not built in takes every
     * missing part from {@code DEFAULT} (the plugin's style, then the built-in one).
     */
    public static Style resolve(MessageType type, Map<MessageType, Overrides> user) {
        List<Overrides> layers = layers(type, user);
        String chat = first(layers, o -> o.chat);
        String actionbar = first(layers, o -> o.actionbar);
        String title = first(layers, o -> o.title);
        String subtitle = first(layers, o -> o.subtitle);
        Sound sound = null;
        for (Overrides o : layers) {
            if (o.noSound) {
                break;
            }
            if (o.sound != null) {
                sound = o.sound;
                break;
            }
        }
        return new Style(chat, actionbar, title, subtitle, sound);
    }

    /** True if the type is built in or the plugin configured a style for it. */
    public static boolean hasStyle(MessageType type, Map<MessageType, Overrides> user) {
        return BUILTIN.containsKey(type) || user.containsKey(type);
    }

    private static List<Overrides> layers(MessageType type, Map<MessageType, Overrides> user) {
        java.util.ArrayList<Overrides> layers = new java.util.ArrayList<>();
        add(layers, user.get(type));
        if (BUILTIN.containsKey(type)) {
            layers.add(BUILTIN.get(type));
        } else {
            add(layers, user.get(MessageType.DEFAULT));
            layers.add(BUILTIN.get(MessageType.DEFAULT));
        }
        return layers;
    }

    private static void add(List<Overrides> layers, @Nullable Overrides o) {
        if (o != null) {
            layers.add(o);
        }
    }

    private static String first(List<Overrides> layers, java.util.function.Function<Overrides, @Nullable String> part) {
        for (Overrides o : layers) {
            String v = part.apply(o);
            if (v != null) {
                return v;
            }
        }
        throw new IllegalStateException("built-in style incomplete");
    }

    /** Fills {@code <prefix>} and {@code <content>} of a style template. */
    public static Component apply(ThemeImpl theme, String template, Component prefix, Component content) {
        return theme.miniMessage().deserialize(template, TagResolver.resolver(
                TagResolver.resolver("prefix", Tag.inserting(prefix)),
                TagResolver.resolver("content", Tag.inserting(content))));
    }

    /** A prefix given as MiniMessage. */
    public static Component prefix(ThemeImpl theme, String miniMessage) {
        return theme.miniMessage().deserialize(miniMessage);
    }
}
