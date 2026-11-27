package de.kalypzo.pantheon.core.language;

import static de.kalypzo.pantheon.language.Arg.arg;
import static org.assertj.core.api.Assertions.assertThat;

import de.kalypzo.pantheon.language.Arg;
import de.kalypzo.pantheon.language.MessageKey;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Formatter;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RendererTest {

    private static final Locale DE = Locale.of("de", "DE");
    private static final Locale EN = Locale.of("en", "US");
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private final List<LogRecord> records = new ArrayList<>();
    private final Map<String, LanguageEntry> de = new LinkedHashMap<>();
    private final Map<String, LanguageEntry> en = new LinkedHashMap<>();
    private ArgAdapters adapters;
    private Renderer renderer;

    @BeforeEach
    void setUp() {
        Logger log = Logger.getAnonymousLogger();
        log.setUseParentHandlers(false);
        log.addHandler(new Handler() {
            @Override
            public void publish(LogRecord r) {
                records.add(r);
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        });
        put("pantheon.time.minute", plural("<count> Minute", "<count> Minuten"), plural("<count> minute", "<count> minutes"));
        put("pantheon.time.second", plural("<count> Sekunde", "<count> Sekunden"), plural("<count> second", "<count> seconds"));
        put("pantheon.time.hour", plural("<count> Stunde", "<count> Stunden"), plural("<count> hour", "<count> hours"));
        put("pantheon.time.day", plural("<count> Tag", "<count> Tage"), plural("<count> day", "<count> days"));
        adapters = new ArgAdapters();
        rebuild(log);
    }

    private Logger logger;

    private void rebuild(Logger log) {
        logger = log;
        renderer = new Renderer("lobby", new LocaleResolver(Map.of(DE, de, EN, en)), adapters, log);
    }

    private static LanguageEntry.Plural plural(String one, String other) {
        return new LanguageEntry.Plural(Map.of("one", one, "other", other));
    }

    private void put(String key, LanguageEntry d, LanguageEntry e) {
        de.put(key, d);
        en.put(key, e);
    }

    private void text(String key, String d, String e) {
        put(key, new LanguageEntry.Text(d), new LanguageEntry.Text(e));
        rebuild(logger);
    }

    private String plain(String key, Locale locale, Arg... args) {
        return PLAIN.serialize(renderer.render(MessageKey.of(key), locale, Audience.empty(), List.of(args), null));
    }

    @Test
    void stringArgIsNotParsed() {
        text("k", "Hi <name>", "Hi <name>");
        assertThat(plain("k", DE, arg("name", "<red>x"))).isEqualTo("Hi <red>x");
        Component c = renderer.render(MessageKey.of("k"), DE, Audience.empty(), List.of(arg("name", "<red>x")), null);
        assertThat(c.children()).allSatisfy(ch -> assertThat(ch.color()).isNull());
    }

    @Test
    void componentArgKeepsHoverAndClick() {
        text("k", "<who>", "<who>");
        Component who = Component.text("Bob", NamedTextColor.GREEN)
                .hoverEvent(HoverEvent.showText(Component.text("tip")))
                .clickEvent(ClickEvent.runCommand("/x"));
        Component c = renderer.render(MessageKey.of("k"), DE, Audience.empty(), List.of(arg("who", who)), null);
        Component inner = c.children().isEmpty() ? c : c.children().get(0);
        assertThat(inner.hoverEvent()).isNotNull();
        assertThat(inner.clickEvent()).isNotNull();
        assertThat(inner.color()).isEqualTo(NamedTextColor.GREEN);
    }

    @Test
    void numberUsesViewerLocale() {
        text("k", "<n>", "<n>");
        assertThat(plain("k", DE, arg("n", 1250))).isEqualTo("1.250");
        assertThat(plain("k", EN, arg("n", 1250))).isEqualTo("1,250");
    }

    @Test
    void numberPatternFromTemplate() {
        text("k", "<price:'#,##0.00'>", "<price:'#,##0.00'>");
        assertThat(plain("k", DE, arg("price", 1234.5))).isEqualTo("1.234,50");
        assertThat(plain("k", EN, arg("price", 1234.5))).isEqualTo("1,234.50");
    }

    @Test
    void explicitLocaleInTemplateWins() {
        text("k", "<price:'en-US':'#,##0.00'>", "<price:'de-DE':'#,##0.00'>");
        assertThat(plain("k", DE, arg("price", 1234.5))).isEqualTo("1,234.50");
        assertThat(plain("k", EN, arg("price", 1234.5))).isEqualTo("1.234,50");
    }

    @Test
    void dateWithPattern() {
        text("k", "<d:'dd.MM.yyyy'>", "<d:'MMM d, yyyy'>");
        assertThat(plain("k", DE, arg("d", LocalDate.of(2026, 11, 27)))).isEqualTo("27.11.2026");
        assertThat(plain("k", EN, arg("d", LocalDate.of(2026, 11, 27)))).isEqualTo("Nov 27, 2026");
        text("m", "<d>", "<d>");
        assertThat(plain("m", DE, arg("d", LocalDate.of(2026, 11, 27)))).isEqualTo("27.11.2026");
    }

    @Test
    void durationLocalized() {
        text("k", "<t>", "<t>");
        Duration d = Duration.ofSeconds(125);
        assertThat(plain("k", DE, arg("t", d))).isEqualTo("2 Minuten 5 Sekunden");
        assertThat(plain("k", EN, arg("t", d))).isEqualTo("2 minutes 5 seconds");
        assertThat(plain("k", DE, arg("t", Duration.ZERO))).isEqualTo("0 Sekunden");
        assertThat(plain("k", DE, arg("t", Duration.ofSeconds(1)))).isEqualTo("1 Sekunde");
    }

    @Test
    void booleanChoice() {
        text("k", "<online:'Online':'Offline'>", "<online:'Online':'Offline'>");
        assertThat(plain("k", DE, arg("online", true))).isEqualTo("Online");
        assertThat(plain("k", DE, arg("online", false))).isEqualTo("Offline");
    }

    @Test
    void nestedKeyTranslatedPerViewer() {
        text("inner", "innen", "inside");
        text("k", "<x>!", "<x>!");
        assertThat(plain("k", DE, arg("x", MessageKey.of("inner")))).isEqualTo("innen!");
        assertThat(plain("k", EN, arg("x", MessageKey.of("inner")))).isEqualTo("inside!");
    }

    record Thing(String id) {}

    @Test
    void customAdapterUsed() {
        adapters.register(Thing.class, (t, loc) -> Component.text("Thing#" + t.id() + loc.getLanguage()));
        rebuild(logger);
        text("k", "<t>", "<t>");
        assertThat(plain("k", DE, arg("t", new Thing("7")))).isEqualTo("Thing#7de");
        assertThat(records).isEmpty();
    }

    @Test
    void unknownTypeWarnsOnce() {
        text("k", "<t>", "<t>");
        assertThat(plain("k", DE, arg("t", new Thing("7")))).isEqualTo("Thing[id=7]");
        plain("k", DE, arg("t", new Thing("8")));
        assertThat(records).hasSize(1);
        assertThat(records.get(0).getLevel()).isEqualTo(Level.WARNING);
    }

    @Test
    void parsedArgIsParsed() {
        text("k", "<m>", "<m>");
        Component c = renderer.render(MessageKey.of("k"), DE, Audience.empty(), List.of(Arg.parsed("m", "<red>x")), null);
        assertThat(PLAIN.serialize(c)).isEqualTo("x");
        Component inner = c.children().isEmpty() ? c : c.children().get(0);
        assertThat(inner.color()).isEqualTo(NamedTextColor.RED);
    }

    @Test
    void tagResolverPassthrough() {
        text("k", "<members>", "<members>");
        var r = Arg.of(Formatter.joining("members", Component.text("a"), Component.text("b")));
        assertThat(plain("k", DE, r)).isEqualTo("ab");
    }

    @Test
    void pluralOneOther() {
        put("coins", plural("<count> Münze", "<count> Münzen"), plural("<count> coin", "<count> coins"));
        rebuild(logger);
        var k = MessageKey.of("coins");
        assertThat(PLAIN.serialize(renderer.render(k, DE, Audience.empty(), List.of(), 1L))).isEqualTo("1 Münze");
        assertThat(PLAIN.serialize(renderer.render(k, DE, Audience.empty(), List.of(), 250L))).isEqualTo("250 Münzen");
        assertThat(PLAIN.serialize(renderer.render(k, EN, Audience.empty(), List.of(), 1250L))).isEqualTo("1,250 coins");
    }

    @Test
    void pluralMissingFormFallsBackToOther() {
        put("c", new LanguageEntry.Plural(Map.of("other", "<count> X")), new LanguageEntry.Plural(Map.of("other", "<count> X")));
        rebuild(logger);
        assertThat(PLAIN.serialize(renderer.render(MessageKey.of("c"), DE, Audience.empty(), List.of(), 1L)))
                .isEqualTo("1 X");
    }

    @Test
    void missingKeyVisibleAndLoggedOnce() {
        Component c = renderer.render(MessageKey.of("nope"), DE, Audience.empty(), List.of(), null);
        assertThat(PLAIN.serialize(c)).isEqualTo("lobby:nope");
        assertThat(c.color()).isEqualTo(NamedTextColor.RED);
        renderer.render(MessageKey.of("nope"), DE, Audience.empty(), List.of(), null);
        assertThat(records).hasSize(1);
        renderer.render(MessageKey.of("nope"), EN, Audience.empty(), List.of(), null);
        assertThat(records).hasSize(2);
    }

    @Test
    void themeTagsApplyColors() {
        text("k", "<p>a</p><highlight>b</highlight><success>c</success><deny>d</deny><error>e</error><prompt>f</prompt>",
                "x");
        Component c = renderer.render(MessageKey.of("k"), DE, Audience.empty(), List.of(), null);
        assertThat(c.children()).extracting(Component::color).containsExactly(
                NamedTextColor.GRAY, NamedTextColor.GOLD, NamedTextColor.GREEN,
                NamedTextColor.RED, NamedTextColor.DARK_RED, NamedTextColor.YELLOW);
        assertThat(ThemeTags.defaultPrefix("Lobby")).isEqualTo("<highlight>Lobby</highlight><dark_gray> »</dark_gray>");
    }

    @Test
    void perViewerArgEvaluatedPerRecipient() {
        text("k", "<who>", "<who>");
        Audience a = new Audience() {};
        Audience b = new Audience() {};
        Arg per = arg("who", (java.util.function.Function<Audience, ?>) v -> v == a ? "A" : "B");
        assertThat(PLAIN.serialize(renderer.render(MessageKey.of("k"), DE, a, List.of(per), null))).isEqualTo("A");
        assertThat(PLAIN.serialize(renderer.render(MessageKey.of("k"), DE, b, List.of(per), null))).isEqualTo("B");
    }

    @Test
    void renderLinesSplitsListEntries() {
        put("l", new LanguageEntry.Lines(List.of("a <n>", "")), new LanguageEntry.Lines(List.of("a")));
        rebuild(logger);
        List<Component> lines = renderer.renderLines(MessageKey.of("l"), DE, Audience.empty(), List.of(arg("n", 1)), null);
        assertThat(lines.stream().map(PLAIN::serialize).toList()).containsExactly("a 1", "");
    }
}
