package de.kalypzo.pantheon.core.language;

import static org.assertj.core.api.Assertions.assertThat;

import de.kalypzo.pantheon.language.LanguageSource;
import de.kalypzo.pantheon.language.Message;
import de.kalypzo.pantheon.language.MessageKey;
import de.kalypzo.pantheon.language.MessageType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LanguageCheckTest {

    private static final Locale DE = Locale.of("de", "DE");
    private static final Locale EN = Locale.of("en", "US");
    private static final Map<MessageType, MessageStyles.Overrides> NO_STYLES = Map.of();

    enum ShopMessage implements MessageKey {
        TOO_POOR("shop.too-poor"),
        BOUGHT("shop.bought"),
        KIT_LORE("kit.lore");

        private final String key;

        ShopMessage(String key) {
            this.key = key;
        }

        @Override
        public String key() {
            return key;
        }
    }

    enum GameMessage implements MessageKey {
        START("game.start"),
        STOP("game.stop");

        private final String key;

        GameMessage(String key) {
            this.key = key;
        }

        @Override
        public String key() {
            return key;
        }

        @Override
        public MessageType type() {
            return MessageType.of("game");
        }
    }

    private static Map<String, LanguageEntry> file(String... kv) {
        Map<String, LanguageEntry> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], new LanguageEntry.Text(kv[i + 1]));
        }
        return m;
    }

    private static Map<Locale, Map<String, LanguageEntry>> files(Map<String, LanguageEntry> de,
            Map<String, LanguageEntry> en) {
        Map<Locale, Map<String, LanguageEntry>> m = new LinkedHashMap<>();
        m.put(DE, de);
        m.put(EN, en);
        return m;
    }

    private static CheckReport check(Map<Locale, Map<String, LanguageEntry>> files,
            List<Class<? extends MessageKey>> enums, Set<String> allowUnused) {
        return LanguageCheck.check("lobby", files, enums, NO_STYLES, allowUnused);
    }

    private static final Map<String, LanguageEntry> FULL_DE =
            file("shop.too-poor", "Zu arm", "shop.bought", "Gekauft <price>", "kit.lore", "Lore");

    @Test
    void reportsMissingKeysWithEnumConstant() {
        CheckReport r = check(files(FULL_DE, file("shop.bought", "Bought <price>")), List.of(ShopMessage.class),
                Set.of());
        assertThat(r.ok()).isFalse();
        assertThat(r.logLines()).containsExactly(
                "[Language] lobby_en_US.yml: 2 Keys fehlen",
                "[Language]   - shop.too-poor   (ShopMessage.TOO_POOR)",
                "[Language]   - kit.lore        (ShopMessage.KIT_LORE)");
        assertThat(r.assertionMessage()).isEqualTo(
                "lobby_en_US.yml: Key fehlt: shop.too-poor (ShopMessage.TOO_POOR)\n"
                        + "lobby_en_US.yml: Key fehlt: kit.lore (ShopMessage.KIT_LORE)");
        assertThat(r.findings()).first().isEqualTo(new CheckReport.Finding(CheckReport.Kind.MISSING,
                "lobby_en_US.yml", "shop.too-poor", "", "ShopMessage.TOO_POOR"));
    }

    @Test
    void singularMissingKey() {
        CheckReport r = check(files(FULL_DE, file("shop.bought", "B <price>", "kit.lore", "L")),
                List.of(ShopMessage.class), Set.of());
        assertThat(r.logLines()).containsExactly(
                "[Language] lobby_en_US.yml: 1 Key fehlt",
                "[Language]   - shop.too-poor   (ShopMessage.TOO_POOR)");
    }

    @Test
    void reportsUnusedKeys() {
        Map<String, LanguageEntry> de = new LinkedHashMap<>(FULL_DE);
        de.put("shop.old-banner", new LanguageEntry.Text("alt"));
        CheckReport r = check(files(de, file("shop.too-poor", "x", "shop.bought", "B <price>", "kit.lore", "L")),
                List.of(ShopMessage.class), Set.of());
        assertThat(r.logLines()).containsExactly(
                "[Language] lobby_de_DE.yml: 1 Key wird nicht verwendet: shop.old-banner");
        assertThat(r.assertionMessage()).isEqualTo("lobby_de_DE.yml: Key wird nicht verwendet: shop.old-banner");
    }

    /**
     * P-28 (E2E): Pantheon checks when the instance is created, before the plugin's {@code onEnable} has called
     * {@code register}. Without a registered enum no key can be called unused.
     */
    @Test
    void noRegisteredEnumReportsNoUnusedKeys() {
        CheckReport r = check(files(FULL_DE, file("shop.too-poor", "x", "shop.bought", "B <price>", "kit.lore", "L")),
                List.of(), Set.of());
        assertThat(r.findings()).isEmpty();
    }

    @Test
    void reportsPlaceholderMismatch() {
        CheckReport r = check(files(FULL_DE,
                file("shop.too-poor", "x", "shop.bought", "Bought <cost>", "kit.lore", "L")),
                List.of(ShopMessage.class), Set.of());
        assertThat(r.assertionMessage()).isEqualTo(
                "lobby_en_US.yml: Platzhalter weicht ab bei shop.bought: de_DE hat <price>, en_US hat <cost>");
        assertThat(r.logLines()).containsExactly("[Language] lobby_en_US.yml: Platzhalter weicht ab bei "
                + "shop.bought: de_DE hat <price>, en_US hat <cost>");
    }

    @Test
    void reportsReservedPlaceholderInFile() {
        Map<String, LanguageEntry> de = new LinkedHashMap<>(FULL_DE);
        de.put("shop.bought", new LanguageEntry.Text("Punkte: <score>"));
        CheckReport r = check(files(de, file("shop.too-poor", "x", "shop.bought", "Points: <score>", "kit.lore", "L")),
                List.of(ShopMessage.class), Set.of());
        assertThat(r.findings()).extracting(CheckReport.Finding::kind)
                .containsOnly(CheckReport.Kind.RESERVED_PLACEHOLDER);
        assertThat(r.assertionMessage()).contains("lobby_de_DE.yml: Reservierter Platzhalter <score> bei shop.bought");
    }

    @Test
    void reservedRulesFollowR17() {
        Map<String, LanguageEntry> de = new LinkedHashMap<>(FULL_DE);
        de.put("shop.bought", new LanguageEntry.Plural(Map.of("one", "<count> Stück", "other", "<count> Stücke")));
        de.put("shop.too-poor", new LanguageEntry.Text("<arg> <content> <red>rot<b>fett<newline><score:a:b>"));
        CheckReport r = check(files(de, de), List.of(ShopMessage.class), Set.of());
        assertThat(r.findings()).extracting(CheckReport.Finding::detail)
                .anyMatch(d -> d.contains("<arg>")).anyMatch(d -> d.contains("<content>"))
                .noneMatch(d -> d.contains("<count>") || d.contains("<red>") || d.contains("<b>")
                        || d.contains("<newline>") || d.contains("<score>"));
    }

    @Test
    void headingTagsAreNotPlaceholders() {
        assertThat(Placeholders.of("<h1>a</h1><h2>b</h2> <name>")).containsExactly("name");
    }

    @Test
    void standardAndThemeTagsAreNotPlaceholders() {
        assertThat(Placeholders.of("<red>a</red> <b>b</b> <p>c <highlight>d <success>e <deny>f <error>g <prompt>h "
                + "<gradient:red:blue>i <hover:show_text:'x'>j <newline><reset> <price> <kit-name>"))
                .containsExactlyInAnyOrder("price", "kit-name");
        Map<String, LanguageEntry> de = file("shop.too-poor", "<red>Zu <b>arm</b> <p>x<highlight>y",
                "shop.bought", "<price>", "kit.lore", "L");
        CheckReport r = check(files(de, de), List.of(ShopMessage.class), Set.of());
        assertThat(r.ok()).isTrue();
        assertThat(r.logLines()).isEmpty();
    }

    @Test
    void unstyledCustomTypeWarnsOnce() {
        Map<String, LanguageEntry> f = file("game.start", "a", "game.stop", "b");
        CheckReport r = LanguageCheck.check("lobby", files(f, f), List.of(GameMessage.class), NO_STYLES, Set.of());
        assertThat(r.findings()).hasSize(1);
        assertThat(r.findings().get(0).kind()).isEqualTo(CheckReport.Kind.UNSTYLED_TYPE);
        assertThat(r.logLines()).hasSize(1);
        assertThat(r.logLines().get(0)).contains("game").contains("GameMessage.START");
        assertThat(r.ok()).isTrue();

        Map<MessageType, MessageStyles.Overrides> styled = Map.of(MessageType.of("game"), new MessageStyles.Overrides());
        assertThat(LanguageCheck.check("lobby", files(f, f), List.of(GameMessage.class), styled, Set.of()).findings())
                .isEmpty();
    }

    @Test
    void allowUnusedPrefixSkipsDynamicKeys() {
        Map<String, LanguageEntry> de = new LinkedHashMap<>(FULL_DE);
        de.put("kit.warrior.name", new LanguageEntry.Text("Krieger"));
        Map<String, LanguageEntry> en = file("shop.too-poor", "x", "shop.bought", "B <price>", "kit.lore", "L",
                "kit.warrior.name", "Warrior");
        assertThat(check(files(de, en), List.of(ShopMessage.class), Set.of()).findings()).hasSize(2);
        assertThat(check(files(de, en), List.of(ShopMessage.class), Set.of("kit.warrior.")).ok()).isTrue();
    }

    @Test
    void pantheonOwnKeysAndLocalesAreIgnored() {
        Map<String, LanguageEntry> de = new LinkedHashMap<>(FULL_DE);
        de.put("pantheon.no-permission", new LanguageEntry.Text("x"));
        Map<Locale, Map<String, LanguageEntry>> m = files(de, FULL_DE);
        m.put(Locale.FRANCE, file("pantheon.no-permission", "y"));
        assertThat(check(m, List.of(ShopMessage.class), Set.of()).ok()).isTrue();
    }

    @TempDir
    Path tmp;

    @Test
    void reloadPicksUpChangedFile() throws IOException {
        Path jar = tmp.resolve("jar/lang");
        Files.createDirectories(jar);
        Files.writeString(jar.resolve("lobby_de_DE.yml"), "shop:\n  bought: \"Alt\"\n");
        Path data = tmp.resolve("data");
        Files.createDirectories(data.resolve("lang"));

        List<String> logged = new java.util.ArrayList<>();
        Logger log = Logger.getAnonymousLogger();
        log.setUseParentHandlers(false);
        log.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                logged.add(record.getMessage());
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        });
        LanguageConfigImpl config = new LanguageConfigImpl("lobby");
        config.register(ShopMessage.class);
        config.source(LanguageSource.pluginResources(), LanguageSource.dataFolder());
        LanguageImpl lang = LanguageBootstrap.create("lobby", config, new LanguageHooks(),
                ResourceLister.ofDirectory(tmp.resolve("jar")), ResourceLister.ofDirectory(tmp.resolve("none")), data,
                log, () -> ThemeImpl.DEFAULT);
        Message bought = lang.message(ShopMessage.BOUGHT);
        assertThat(bought.plain(DE)).isEqualTo("Alt");
        assertThat(logged).anyMatch(l -> l.contains("shop.too-poor"));

        Files.writeString(data.resolve("lang/lobby_de_DE.yml"),
                "shop:\n  bought: \"Neu\"\n  too-poor: \"arm\"\n");
        Files.writeString(jar.resolve("lobby_de_DE.yml"), "shop:\n  bought: \"Alt\"\n  kit-x: \"x\"\n");
        logged.clear();
        lang.reload();
        assertThat(bought.plain(DE)).isEqualTo("Neu");
        assertThat(logged).noneMatch(l -> l.contains("shop.too-poor"));
        assertThat(logged).anyMatch(l -> l.contains("shop.kit-x"));
    }
}
