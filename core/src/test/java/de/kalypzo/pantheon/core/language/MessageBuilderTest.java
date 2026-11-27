package de.kalypzo.pantheon.core.language;

import de.kalypzo.pantheon.language.ThemeStyle;
import static de.kalypzo.pantheon.language.Arg.arg;
import static org.assertj.core.api.Assertions.assertThat;

import de.kalypzo.pantheon.core.audit.NoAudit;
import de.kalypzo.pantheon.language.Arg;
import de.kalypzo.pantheon.language.Message;
import de.kalypzo.pantheon.language.MessageKey;
import de.kalypzo.pantheon.language.MessageType;
import de.kalypzo.pantheon.player.LocalPanPlayer;
import de.kalypzo.pantheon.player.PanPlayer;
import de.kalypzo.pantheon.world.NetworkLocation;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.title.Title;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MessageBuilderTest {

    private static final Locale DE = Locale.of("de", "DE");
    private static final Locale EN = Locale.of("en", "US");
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private static final MessageKey OK = typed("ok", MessageType.SUCCESS);
    private static final MessageKey HELLO = typed("hello", MessageType.DEFAULT);
    private static final MessageKey TITLE = typed("title", MessageType.DEFAULT);
    private static final MessageKey TITLE_STR = typed("title-str", MessageType.DEFAULT);
    private static final MessageKey LINES = typed("lines", MessageType.DEFAULT);
    private static final MessageKey PFX = MessageKey.of("pfx");

    private final Map<String, LanguageEntry> de = new LinkedHashMap<>();
    private final Map<String, LanguageEntry> en = new LinkedHashMap<>();
    private LanguageConfigImpl config;
    private LanguageHooks hooks;
    private LanguageImpl lang;

    private static MessageKey typed(String key, MessageType type) {
        return new MessageKey() {
            @Override
            public String key() {
                return key;
            }

            @Override
            public MessageType type() {
                return type;
            }
        };
    }

    @BeforeEach
    void setUp() {
        text(de, "ok", "Erledigt");
        text(en, "ok", "Done");
        text(de, "hello", "Hallo <name>");
        text(en, "hello", "Hello <name>");
        de.put("title", new LanguageEntry.TitleEntry("Titel", "Untertitel"));
        en.put("title", new LanguageEntry.TitleEntry("Title", "Subtitle"));
        text(de, "title-str", "Nur Titel");
        text(en, "title-str", "Only title");
        text(de, "lines", "a<newline>b<br>c");
        text(en, "lines", "a<newline>b<br>c");
        text(de, "pfx", "<red>VORNE");
        text(en, "pfx", "<blue>FRONT");
        config = new LanguageConfigImpl("lobby");
        hooks = new LanguageHooks();
        Renderer renderer = new Renderer("lobby", new LocaleResolver(Map.of(DE, de, EN, en)), new ArgAdapters(),
                Logger.getAnonymousLogger(), config::defaultLocale);
        lang = new LanguageImpl("lobby", config, hooks, () -> renderer, () -> {});
    }

    private static void text(Map<String, LanguageEntry> m, String key, String value) {
        m.put(key, new LanguageEntry.Text(value));
    }

    private static Optional<TextColor> colorOf(Component root, String text) {
        return find(root, text, Style.empty());
    }

    private static Optional<TextColor> find(Component c, String text, Style inherited) {
        Style effective = c.style().merge(inherited, Style.Merge.Strategy.IF_ABSENT_ON_TARGET);
        if (c instanceof TextComponent t && t.content().contains(text)) {
            return Optional.ofNullable(effective.color());
        }
        for (Component child : c.children()) {
            Optional<TextColor> r = find(child, text, effective);
            if (r.isPresent()) {
                return r;
            }
        }
        return Optional.empty();
    }

    private static String plain(Component c) {
        return PLAIN.serialize(c);
    }

    @Test
    void chatUsesTypeTemplateAndPrefix() {
        RecordingAudience a = new RecordingAudience();
        lang.message(OK).send(a);
        assertThat(a.messages()).hasSize(1);
        Component m = a.messages().get(0);
        assertThat(plain(m)).isEqualTo("lobby » Erledigt");
        assertThat(colorOf(m, "Erledigt")).contains(TextColor.color(0x7ef874));
        assertThat(colorOf(m, "lobby")).contains(TextColor.color(0xfb923c));
    }

    @Test
    void actionbarHasNoPrefix() {
        RecordingAudience a = new RecordingAudience();
        lang.message(OK).actionbar(a);
        assertThat(a.actionbars()).hasSize(1);
        assertThat(plain(a.actionbars().get(0))).isEqualTo("Erledigt");
        assertThat(colorOf(a.actionbars().get(0), "Erledigt")).contains(TextColor.color(0x7ef874));
        assertThat(a.messages()).isEmpty();
        assertThat(a.sounds()).hasSize(1);
    }

    @Test
    void typeSoundPlayed() {
        assertSound(MessageType.PROMPT, "block.note_block.pling");
        assertSound(MessageType.SUCCESS, "entity.experience_orb.pickup");
        assertSound(MessageType.DENY, "entity.villager.no");
        assertSound(MessageType.ERROR, "block.anvil.destroy");
        RecordingAudience a = new RecordingAudience();
        lang.message(HELLO).with("name", "x").type(MessageType.DEFAULT).send(a);
        lang.message(HELLO).with("name", "x").type(MessageType.PLAIN).send(a);
        assertThat(a.sounds()).isEmpty();
    }

    private void assertSound(MessageType type, String key) {
        RecordingAudience a = new RecordingAudience();
        lang.message(HELLO).with("name", "x").type(type).send(a);
        assertThat(a.sounds()).hasSize(1);
        Sound s = a.sounds().get(0);
        assertThat(s.name()).isEqualTo(Key.key(key));
        assertThat(s.source()).isEqualTo(Sound.Source.MASTER);
        assertThat(s.volume()).isEqualTo(1f);
        assertThat(s.pitch()).isEqualTo(1f);
    }

    @Test
    void silentSuppressesSound() {
        RecordingAudience a = new RecordingAudience();
        lang.message(OK).silent().send(a);
        lang.message(OK).silent().actionbar(a);
        assertThat(a.messages()).hasSize(1);
        assertThat(a.sounds()).isEmpty();
    }

    @Test
    void prefixAndTemplateUseTheme() {
        ThemeImpl theme = ThemeImpl.of(Map.of(
                ThemeStyle.HIGHLIGHT, Style.style(TextColor.color(0x123456)),
                ThemeStyle.P, Style.style(TextColor.color(0x654321))));
        Renderer renderer = new Renderer("lobby", new LocaleResolver(Map.of(DE, de, EN, en)), new ArgAdapters(),
                Logger.getAnonymousLogger(), config::defaultLocale, () -> theme);
        LanguageImpl themed = new LanguageImpl("lobby", config, hooks, () -> renderer, () -> {}, () -> theme);
        RecordingAudience a = new RecordingAudience();
        themed.message(HELLO).with("name", "x").send(a);
        assertThat(colorOf(a.messages().get(0), "lobby")).contains(TextColor.color(0x123456));
        assertThat(colorOf(a.messages().get(0), "Hallo ")).contains(TextColor.color(0x654321));
    }

    @Test
    void typeOverrideWins() {
        RecordingAudience a = new RecordingAudience();
        lang.message(OK).type(MessageType.DENY).send(a);
        assertThat(colorOf(a.messages().get(0), "Erledigt")).contains(TextColor.color(0xf87171));
        assertThat(a.sounds().get(0).name()).isEqualTo(Key.key("entity.villager.no"));
    }

    @Test
    void customTypeFallsBackToDefaultParts() {
        MessageType reward = MessageType.of("reward");
        config.style(reward, s -> s.chat("<prefix> <gold>* <content>"));
        RecordingAudience a = new RecordingAudience();
        lang.message(HELLO).with("name", "x").type(reward).send(a);
        lang.message(HELLO).with("name", "x").type(reward).actionbar(a);
        assertThat(plain(a.messages().get(0))).isEqualTo("lobby » * Hallo x");
        assertThat(colorOf(a.messages().get(0), "Hallo ")).contains(NamedTextColor.GOLD);
        // actionbar template and sound come from DEFAULT
        assertThat(plain(a.actionbars().get(0))).isEqualTo("Hallo x");
        assertThat(colorOf(a.actionbars().get(0), "Hallo ")).contains(TextColor.color(0xd4d4d4));
        assertThat(a.sounds()).isEmpty();

        // a type without any style behaves like DEFAULT
        RecordingAudience b = new RecordingAudience();
        lang.message(HELLO).with("name", "x").type(MessageType.of("unstyled")).send(b);
        assertThat(plain(b.messages().get(0))).isEqualTo("lobby » Hallo x");

        // a sound set on DEFAULT is inherited
        Sound ding = Sound.sound(Key.key("custom.ding"), Sound.Source.MASTER, 1f, 1f);
        config.style(MessageType.DEFAULT, s -> s.sound(ding));
        RecordingAudience c = new RecordingAudience();
        lang.message(HELLO).with("name", "x").type(reward).send(c);
        assertThat(c.sounds()).containsExactly(ding);
    }

    @Test
    void builderIsImmutable() {
        Message m = lang.message(HELLO);
        Message m1 = m.with("name", "Anna");
        Message m2 = m1.with("other", "x");
        assertThat(m2).isNotSameAs(m1);
        assertThat(m1).isNotSameAs(m);
        Message m3 = m1.count(3).silent().type(MessageType.DENY);
        assertThat(plain(m1.component(DE))).isEqualTo("Hallo Anna");
        // m1 has neither silent nor type nor count
        RecordingAudience a = new RecordingAudience();
        m1.send(a);
        assertThat(a.sounds()).isEmpty();
        assertThat(colorOf(a.messages().get(0), "Hallo ")).contains(TextColor.color(0xd4d4d4));
        // m is still without name, shows the unresolved tag
        assertThat(plain(m.component(DE))).doesNotContain("Anna");
        m3.send(a);
        assertThat(a.sounds()).isEmpty();
    }

    @Test
    void sendCollectionRendersPerRecipientLocale() {
        PanPlayerStub german = new PanPlayerStub(DE);
        PanPlayerStub english = new PanPlayerStub(EN);
        lang.message(HELLO).with("name", "x").send(List.of(german, english));
        assertThat(plain(german.recording.messages().get(0))).endsWith("Hallo x");
        assertThat(plain(english.recording.messages().get(0))).endsWith("Hello x");
    }

    @Test
    void forwardingAudienceIsDecomposedButPanPlayerIsNot() {
        RecordingAudience console = new RecordingAudience();
        PanPlayerStub english = new PanPlayerStub(EN);
        Audience group = Audience.audience(console, english);
        lang.message(HELLO).with("name", "x").send(group);
        assertThat(console.messages()).hasSize(1);
        assertThat(plain(console.messages().get(0))).endsWith("Hallo x");
        // PanPlayer is one recipient: exactly one message, in its language, via audience()
        assertThat(english.recording.messages()).hasSize(1);
        assertThat(plain(english.recording.messages().get(0))).endsWith("Hello x");
        assertThat(english.recording.sounds()).isEmpty();

        // playerLookup takes precedence and the resulting PanPlayer is used as target
        RecordingAudience bukkitLike = new RecordingAudience();
        PanPlayerStub mapped = new PanPlayerStub(EN);
        hooks.playerLookup(a -> a == bukkitLike ? Optional.of(mapped) : Optional.empty());
        lang.message(HELLO).with("name", "y").send(bukkitLike);
        assertThat(bukkitLike.messages()).isEmpty();
        assertThat(plain(mapped.recording.messages().get(0))).endsWith("Hello y");
    }

    @Test
    void missingKeyFallsBackToConfiguredDefaultLocale() {
        config.defaultLocale(EN);
        Locale fr = Locale.FRANCE;
        assertThat(plain(lang.message(OK).component(fr))).isEqualTo("Done");
        config.defaultLocale(DE);
        assertThat(plain(lang.message(OK).component(fr))).isEqualTo("Erledigt");
    }

    @Test
    void nonPlayerGetsDefaultLocale() {
        config.defaultLocale(EN);
        RecordingAudience console = new RecordingAudience();
        lang.message(HELLO).with("name", "x").send(console);
        assertThat(plain(console.messages().get(0))).endsWith("Hello x");
        assertThat(lang.locale(console)).isEqualTo(EN);
        assertThat(lang.locale(new PanPlayerStub(DE))).isEqualTo(DE);
        assertThat(lang.defaultLocale()).isEqualTo(EN);
    }

    @Test
    void titleWithSubtitle() {
        RecordingAudience a = new RecordingAudience();
        Title.Times times = Title.Times.times(java.time.Duration.ofMillis(200), java.time.Duration.ofSeconds(2),
                java.time.Duration.ofMillis(500));
        lang.message(TITLE).type(MessageType.SUCCESS).title(a, times);
        assertThat(a.titles()).hasSize(1);
        Title t = a.titles().get(0);
        assertThat(plain(t.title())).isEqualTo("Titel");
        assertThat(plain(t.subtitle())).isEqualTo("Untertitel");
        assertThat(colorOf(t.title(), "Titel")).contains(TextColor.color(0x7ef874));
        assertThat(colorOf(t.subtitle(), "Untertitel")).contains(TextColor.color(0xd4d4d4));
        assertThat(t.times()).isEqualTo(times);
        assertThat(a.sounds()).hasSize(1);
    }

    @Test
    void stringEntryAsTitleOnly() {
        RecordingAudience a = new RecordingAudience();
        lang.message(TITLE_STR).title(a);
        Title t = a.titles().get(0);
        assertThat(plain(t.title())).isEqualTo("Nur Titel");
        assertThat(plain(t.subtitle())).isEmpty();
    }

    @Test
    void linesSplitOnNewline() {
        List<Component> lines = lang.message(LINES).lines(DE);
        assertThat(lines.stream().map(MessageBuilderTest::plain)).containsExactly("a", "b", "c");
        assertThat(lines).allSatisfy(l -> assertThat(l.decoration(TextDecoration.ITALIC))
                .isEqualTo(TextDecoration.State.FALSE));
    }

    @Test
    void itemTextNotItalic() {
        Component c = lang.message(HELLO).with("name", "x").component(DE);
        assertThat(c.decoration(TextDecoration.ITALIC)).isEqualTo(TextDecoration.State.FALSE);
        assertThat(plain(c)).isEqualTo("Hallo x");
        // no template, no prefix, no sound
        RecordingAudience a = new RecordingAudience();
        lang.message(OK).component(a);
        assertThat(a.sounds()).isEmpty();
    }

    @Test
    void plainStripsFormatting() {
        config.style(MessageType.SUCCESS, s -> s.chat("<prefix> <success><b><content>"));
        text(de, "fmt", "<red>Rot</red> und <bold>fett</bold>");
        assertThat(lang.message(MessageKey.of("fmt")).plain(DE)).isEqualTo("Rot und fett");
        assertThat(lang.message(OK).plain(new RecordingAudience())).isEqualTo("Erledigt");
    }

    @Test
    void prefixFromMessageKeyPerLocale() {
        config.prefix(PFX);
        PanPlayerStub german = new PanPlayerStub(DE);
        PanPlayerStub english = new PanPlayerStub(EN);
        lang.message(HELLO).with("name", "x").send(List.of(german, english));
        Component d = german.recording.messages().get(0);
        Component e = english.recording.messages().get(0);
        assertThat(plain(d)).isEqualTo("VORNE Hallo x");
        assertThat(plain(e)).isEqualTo("FRONT Hello x");
        assertThat(colorOf(d, "VORNE")).contains(NamedTextColor.RED);
        assertThat(colorOf(e, "FRONT")).contains(NamedTextColor.BLUE);

        config.prefix("<green>Fix");
        RecordingAudience a = new RecordingAudience();
        lang.message(HELLO).with("name", "x").send(a);
        assertThat(plain(a.messages().get(0))).isEqualTo("Fix Hallo x");
    }

    @Test
    void panPlayerShortcutEqualsBuilder() {
        PanPlayerStub shortcut = new PanPlayerStub(EN);
        shortcut.lang = lang;
        shortcut.sendMessage(HELLO, arg("name", "Bob"));
        PanPlayerStub builder = new PanPlayerStub(EN);
        lang.message(HELLO).with("name", "Bob").send(builder);
        assertThat(shortcut.recording.messages()).isEqualTo(builder.recording.messages());
        assertThat(shortcut.recording.sounds()).isEqualTo(builder.recording.sounds());
        lang.send(builder, HELLO, arg("name", "Bob"));
        assertThat(builder.recording.messages().get(1)).isEqualTo(builder.recording.messages().get(0));
    }

    /** A PanPlayer for tests: forwards to a recording audience; its shortcut delegates to the Language. */
    private static final class PanPlayerStub implements PanPlayer {
        final RecordingAudience recording = new RecordingAudience();
        final Locale locale;
        LanguageImpl lang;

        PanPlayerStub(Locale locale) {
            this.locale = locale;
        }

        @Override
        public Audience audience() {
            return recording;
        }

        @Override
        public de.kalypzo.pantheon.audit.AuditLog audit() {
            return NoAudit.INSTANCE; // these tests do not log
        }

        @Override
        public UUID uniqueId() {
            return UUID.nameUUIDFromBytes(locale.toString().getBytes());
        }

        @Override
        public String name() {
            return "stub";
        }

        @Override
        public Locale lang() {
            return locale;
        }

        @Override
        public boolean isOnline() {
            return true;
        }

        @Override
        public boolean isLocal() {
            return false;
        }

        @Override
        public Optional<String> server() {
            return Optional.empty();
        }

        @Override
        public Optional<LocalPanPlayer> local() {
            return Optional.empty();
        }

        @Override
        public CompletableFuture<NetworkLocation> location() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void sendMessage(MessageKey key, Arg... args) {
            if (lang != null) {
                lang.send(this, key, args);
            }
        }

        @Override
        public void sendActionBar(MessageKey key, Arg... args) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void showTitle(MessageKey key, Arg... args) {
            throw new UnsupportedOperationException();
        }
    }
}
