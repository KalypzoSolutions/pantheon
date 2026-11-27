# Pantheon Language API

> Status: **Umgesetzt.** Dieses Dokument beschreibt die API und ihr Verhalten.

Die Language API löst Nachrichten-Keys pro Spieler in dessen Sprache auf, setzt Platzhalter ein, verpackt das Ergebnis
im Stil des passenden Message-Types und stellt es über Chat, Actionbar, Title oder als Item-Text zu.

```java
LocalPanPlayer player = pantheon.getPlayer(bukkitPlayer);

player.sendMessage(ShopMessage.BOUGHT, arg("kit", kit), arg("price", 250));
```

---

## Inhalt

1. [Leitlinien](#1-leitlinien)
2. [Sprachdateien](#2-sprachdateien)
3. [Nachrichten deklarieren](#3-nachrichten-deklarieren)
4. [Senden](#4-senden)
5. [Platzhalter](#5-platzhalter)
6. [Kanäle: Chat, Actionbar, Title, Items](#6-kanäle-chat-actionbar-title-items)
7. [Message-Types](#7-message-types)
8. [Sprache eines Spielers](#8-sprache-eines-spielers)
9. [Konfiguration per Code](#9-konfiguration-per-code)
10. [Prüfen auf fehlende Übersetzungen](#10-prüfen-auf-fehlende-übersetzungen)
11. [Rezepte](#11-rezepte)
12. [Verhältnis zu Adventure](#12-verhältnis-zu-adventure)
13. [Unterschiede zu Megalodon](#13-unterschiede-zu-megalodon)
14. [Entscheidungen](#14-entscheidungen)

---

## 1. Leitlinien

- **Ein Weg pro Aufgabe.** Ein Kurzaufruf für den häufigsten Fall (`player.sendMessage(KEY, …)` am `PanPlayer`), ein
  Builder für alles andere.
  Keine Überladungs-Matrix aus Key × Translation × Type × Editor × Resolver.
- **Keys sind Code, nicht Strings.** Nachrichten werden einmal deklariert und danach nur noch über Konstanten
  referenziert. Tippfehler fallen beim Kompilieren oder spätestens beim Serverstart auf, nicht beim Spieler.
- **Bedeutung gehört zur Nachricht.** Ob eine Nachricht ein `deny` oder ein `success` ist, steht bei der Deklaration,
  nicht an jeder Aufrufstelle.
- **Jeder Empfänger bekommt seine Sprache.** Auch beim Broadcast an hundert Spieler.
- **Sicher per Default.** Spielereingaben (Namen, Chat, Schildtexte) werden nie als MiniMessage interpretiert, außer
  man fordert es ausdrücklich an.
- **Configure-by-code.** Sprachen, Prefix, Stile, Sounds und Adapter werden im Plugin festgelegt. YAML enthält nur Text.

---

## 2. Sprachdateien

Standardmäßig liest Pantheon die Sprachdateien direkt aus den Ressourcen des Plugins:

```
src/main/resources/lang/
├── lobby_de_DE.yml
└── lobby_en_US.yml
```

`lobby` ist der Plugin-Name aus der `plugin.yml` in Kleinbuchstaben. Alle Dateien nach dem Muster
`<plugin>_<locale>.yml` werden automatisch gefunden. Ein Eintrag im Code pro Sprache ist nicht nötig.

### Aufbau

YAML-Verschachtelung ergibt Keys mit Punkten. `shop.bought` ist also:

```yaml
shop:
  bought: "Du hast <kit> für <price> Münzen gekauft."
  too-poor: "Dir fehlen <missing> Münzen."
```

Neben einfachen Strings gibt es drei strukturierte Formen.

**Mehrzeilig** (Item-Lore, längere Chat-Blöcke): eine YAML-Liste.

```yaml
kit:
  warrior:
    name: "<highlight>Krieger"
    lore:
      - "<gray>Robust im Nahkampf."
      - ""
      - "<gray>Preis: <price> Münzen"
```

**Title** mit Untertitel: eine Map mit `title` und `subtitle`.

```yaml
round:
  start:
    title: "<highlight>Runde <round>"
    subtitle: "Viel Glück!"
```

**Plural** (siehe [§5.4](#54-plural)): eine Map mit `one` und `other`.

```yaml
coins:
  one: "<count> Münze"
  other: "<count> Münzen"
```

### Konventionen

- Keys in Kleinbuchstaben, Wörter mit `-` getrennt, Ebenen mit `.`: `shop.too-poor`.
- Platzhalter in `<snake_case>` oder einem Wort: `<price>`, `<target_name>`. MiniMessage erlaubt in Tag-Namen nur
  `[a-z0-9_-]`. `<targetName>` ist also **kein** gültiger Platzhalter (siehe [§5.5](#55-reservierte-namen)).
- Styling nur über die Pantheon-Tags (`<p>`, `<highlight>`, `<success>` …) statt fester Farben, damit Themes greifen.

---

## 3. Nachrichten deklarieren

Jedes Plugin deklariert seine Nachrichten als Enum, das `MessageKey` implementiert. Das zweite Argument ist der
Message-Type, mit dem die Nachricht standardmäßig gesendet wird.

```java
public enum ShopMessage implements MessageKey {
    BOUGHT("shop.bought", MessageType.SUCCESS),
    TOO_POOR("shop.too-poor", MessageType.DENY),
    CLOSED("shop.closed", MessageType.DENY),
    PURCHASE_FAILED("shop.purchase-failed", MessageType.ERROR),
    KIT_NAME("kit.name"),          // ohne Type: wird nicht gesendet, nur gerendert
    KIT_LORE("kit.lore");

    private final String key;
    private final MessageType type;

    ShopMessage(String key) { this(key, MessageType.DEFAULT); }
    ShopMessage(String key, MessageType type) { this.key = key; this.type = type; }

    @Override public String key() { return key; }
    @Override public MessageType type() { return type; }
}
```

Warum ein Enum:

- **Vollständigkeit prüfbar.** Pantheon kennt über `values()` alle Keys eines Plugins und kann beim Start melden,
  welche Übersetzungen fehlen (siehe [§10](#10-prüfen-auf-fehlende-übersetzungen)).
- **IDE-Komfort.** Autovervollständigung, „Find Usages“ und Umbenennen funktionieren.
- **Kein Plugin-Bezug nötig.** Der Key weiß nicht, zu welchem Plugin er gehört. Das entscheidet die
  `Language`-Instanz, über die gesendet wird. Dadurch entfällt das `Translation.of(megalodon, ...)`-Muster.

Es ist üblich, ein Enum pro Feature anzulegen (`ShopMessage`, `LobbyMessage`, `KitMessage`) statt eines großen.

### Dynamische Keys

Für Keys, die erst zur Laufzeit feststehen (z. B. ein Kit aus einer Config), gibt es `MessageKey.of(...)`:

```java
MessageKey name = MessageKey.of("kit." + kit.id() + ".name");
```

Dynamische Keys werden beim Start nicht geprüft. Pantheon meldet sie zur Laufzeit beim ersten Fehlen (siehe
[§10](#10-prüfen-auf-fehlende-übersetzungen)).

### Gemeinsame Nachrichten

Pantheon liefert eigene Standardtexte, die jedes Plugin ohne eigene Übersetzung nutzen kann:

```java
lang.send(sender, CommonMessage.NO_PERMISSION);
lang.send(sender, CommonMessage.PLAYER_NOT_FOUND, arg("name", input));
lang.send(sender, CommonMessage.PLAYERS_ONLY);
```

Definiert ein Plugin denselben Key in seiner eigenen Datei (`pantheon.no-permission`), gewinnt die Plugin-Version.

---

## 4. Senden

### Kurzform am PanPlayer

Der `PanPlayer` ist der zentrale Einstiegspunkt für alles, was einen Spieler betrifft, also auch für Nachrichten. Für den
Großteil aller Fälle reicht eine Zeile, mit dem Message-Type aus der Deklaration:

```java
player.sendMessage(ShopMessage.TOO_POOR, arg("missing", price - balance));
player.sendActionBar(GameMessage.BORDER_WARNING, arg("distance", 12));
player.showTitle(GameMessage.ROUND_START, arg("round", 3));
```

Das funktioniert auch für Spieler auf anderen Servern im Netzwerk. Wie der `PanPlayer` aufgebaut ist, steht im
nächsten Abschnitt, alles Weitere in [player.md](player.md).

### Kurzform für andere Empfänger

Für die Konsole, einen `CommandSender` oder jede andere Adventure-`Audience` gibt es dieselbe Kurzform an der
`Language`:

```java
Language lang = pantheon.language();

lang.send(sender, CommonMessage.PLAYERS_ONLY);
```

### Der PanPlayer

```java
public interface PanPlayer extends ForwardingAudience.Single {

    /** Wohin alles Gesendete geht: Bukkit-Player, Backend oder nichts (siehe player.md §4). */
    @Override
    Audience audience();

    /** Die Sprache dieses Spielers laut LanguageProvider. */
    Locale lang();

    void sendMessage(MessageKey key, Arg... args);

    void sendActionBar(MessageKey key, Arg... args);

    void showTitle(MessageKey key, Arg... args);
}
```

**Adventure kommt geschenkt.** Über `ForwardingAudience.Single` hat der `PanPlayer` alle Adventure-Methoden:
`sendMessage(Component)`, `sendActionBar(Component)`, `showTitle(Title)`, `playSound(Sound)`, Bossbars, Tab-Liste und
Pointer. Sie alle leiten an `audience()` weiter. Der `PanPlayer` erfindet hier nichts neu.

**Die Sprachmethoden setzen auf der Language API auf.** `player.sendMessage(KEY, args)` ist dasselbe wie
`lang.message(KEY).with(args).send(player)`. Gerendert wird mit `lang()` und der `Language` des Plugins. Das fertige
`Component` geht danach über `this.sendMessage(Component)` an `audience()`. Gleiches gilt für Actionbar, Title und den
Sound des Message-Types (`playSound`).

**Austauschbare Zustellung.** Weil alles über `audience()` läuft, kann eine Implementierung die Zustellung tauschen, ohne
die Sprachlogik anzufassen:

- **Spieler auf diesem Server:** der Bukkit-Player.
- **Spieler auf einem anderen Server:** das Backend. Gerendert wird trotzdem hier, verschickt wird das fertige
  Component ([player.md §4](player.md#4-nachrichten)).
- **Test:** eine aufzeichnende Audience. Ein Test oder KI-Agent prüft dann, welche Components samt Sounds angekommen
  sind, ganz ohne Server.
- **Später denkbar:** Zustellung über Packets oder ein Filter, etwa für stummgeschaltete Kanäle.

**Gebunden an das Plugin.** Ein `PanPlayer` stammt aus der Pantheon-Instanz eines Plugins. Seine Sprachmethoden lösen
Keys deshalb gegen die Sprachdateien genau dieses Plugins auf, plus `CommonMessage`.

Bewusst **keine** Überladungen mit `MessageType`, `Function<Component, Component>` oder `TagResolver...` am
`PanPlayer`. Wer mehr braucht, nimmt den Builder. Dieser funktioniert mit dem `PanPlayer` als Empfänger:

```java
lang.message(ShopMessage.BOUGHT).with("kit", kit).silent().send(player);
```

> Den Namen `showTitle` statt `sendTitle` habe ich von Adventure übernommen. Über `ForwardingAudience` hat der
> `PanPlayer` ohnehin schon `showTitle(Title)`, und so heißen beide Varianten gleich.

Wie man einen `PanPlayer` bekommt und was er sonst kann, steht in [player.md](player.md).

### Builder

Sobald mehr gewünscht ist (anderer Kanal, anderer Type, mehrere Empfänger), beginnt man mit `lang.message(...)`.
Der Builder ist unveränderlich: Jede Methode gibt eine neue `Message` zurück. Eine vorbereitete Nachricht kann
deshalb gefahrlos gespeichert und mehrfach gesendet werden.

```java
lang.message(ShopMessage.BOUGHT)
    .with("kit", kit)
    .with("price", 250)
    .send(player);
```

Was der Builder kann:

| Methode                                   | Wirkung                                                         |
|-------------------------------------------|-----------------------------------------------------------------|
| `.with(name, value)`                      | Platzhalter setzen (siehe [§5](#5-platzhalter))                  |
| `.with(name, viewer -> value)`            | Platzhalter, der pro Empfänger berechnet wird                   |
| `.count(n)`                               | Pluralform wählen, setzt zusätzlich `<count>`                   |
| `.type(MessageType)`                      | Message-Type für diesen Aufruf überschreiben                    |
| `.silent()`                               | Sound des Message-Types unterdrücken                            |
| `.send(audience)`                         | In den Chat senden                                              |
| `.send(Collection<? extends Audience>)`   | An mehrere Empfänger, jeweils in ihrer Sprache                  |
| `.broadcast()`                            | An alle Online-Spieler und die Konsole (`= .send(Bukkit.getServer())`) |
| `.actionbar(audience)`                    | In die Actionbar                                                |
| `.title(audience)` / `.title(audience, times)` | Als Title (siehe [§6](#6-kanäle-chat-actionbar-title-items)) |
| `.component(audience \| locale)`          | Nur rendern, als `Component`                                    |
| `.lines(audience \| locale)`              | Nur rendern, als `List<Component>` (Lore)                       |
| `.plain(audience \| locale)`              | Nur rendern, als String ohne Formatierung (Logs, Scoreboards)   |

### Empfänger sind Adventure-Audiences

Alle Methoden nehmen ein Adventure-`Audience` entgegen, also `Player`, `CommandSender`, `World`, `Server`, `Team` oder
ein selbst gebautes `Audience.audience(...)`. Eine zusammengesetzte Audience (`ForwardingAudience`) zerlegt Pantheon
über `forEachAudience` in ihre Mitglieder und rendert für jedes Mitglied einzeln. Ein `.send(world)` erreicht also
jeden Spieler der Welt in seiner Sprache.

Ein `PanPlayer` wird dabei **nicht** zerlegt, obwohl er technisch eine `ForwardingAudience` ist. Pantheon behandelt
ihn als einen Empfänger, nimmt seine Sprache aus `lang()` und stellt über sein `audience()` zu. Ein ausgetauschtes
`audience()` gilt damit auch für Nachrichten aus dem Builder.

Jedes andere Mitglied, das ein Bukkit-`Player` ist, wird über `pantheon.getPlayer(...)` zum `PanPlayer`. Danach
gilt dasselbe. Alle übrigen bekommen die Default-Sprache.

### Vorbereitete Nachricht wiederverwenden

```java
Message countdown = lang.message(GameMessage.STARTS_IN).silent();

for (int seconds : List.of(10, 5, 3, 2, 1)) {
    scheduler.later(Duration.ofSeconds(10 - seconds), () ->
        countdown.count(seconds).actionbar(game.players()));
}
```

---

## 5. Platzhalter

Platzhalter heißen im Text `<name>` und werden mit `arg(name, value)` (Kurzform) oder `.with(name, value)`
(Builder) gesetzt. `arg` kommt per Static Import:

```java
import static de.kalypzo.pantheon.language.Arg.arg;
```

### 5.1 Was eingesetzt werden kann

Der Wert entscheidet, wie er eingesetzt wird:

| Wert                    | Ergebnis                                                                    |
|-------------------------|-----------------------------------------------------------------------------|
| `String`                | Wörtlich. MiniMessage-Tags darin werden **nicht** ausgewertet (wie `Placeholder.unparsed`). |
| `ComponentLike`         | Unverändert eingesetzt, mit Hover, Click und Farbe (wie `Placeholder.component`). |
| `Number`                | In der Sprache des Empfängers formatiert: `1.250` (de) bzw. `1,250` (en). Optional mit Muster im Text: `<price:'#,##0.00'>`. |
| `TemporalAccessor`      | Datum/Uhrzeit in der Sprache des Empfängers. Muster im Text: `<date:'dd.MM.yyyy'>`. |
| `Duration`              | Lokalisiert: `2 Minuten 5 Sekunden` bzw. `2 minutes 5 seconds`.             |
| `Boolean`               | Auswahl im Text: `<online:'Online':'Offline'>` (wie `Formatter.booleanChoice`). |
| `MessageKey`            | Wird selbst übersetzt, in der Sprache des Empfängers.                       |
| `Message`               | Wie `MessageKey`, aber mit eigenen Platzhaltern.                            |
| `Player` / `OfflinePlayer` | Anzeigename des Spielers.                                                |
| eigener Typ             | Über einen registrierten Adapter, siehe [§9](#9-konfiguration-per-code).    |
| alles andere            | `toString()`, wörtlich. Pantheon warnt einmalig im Log.                     |

Die Syntax der Muster ist dieselbe wie bei Adventures `Formatter.number` und `Formatter.date`. Es gibt einen
Unterschied: Adventure formatiert ohne Locale-Argument mit der **JVM-Default-Locale**, Pantheon dagegen mit der
Sprache des Empfängers. Eine explizit angegebene Locale (`<price:'de-DE':'#.##'>`) wird weiterhin respektiert.

### Adventure-Resolver direkt verwenden

Alles, was Adventure bereits als `TagResolver` anbietet, kann unverändert mitgegeben werden. Für diese Fälle braucht
es kein Pantheon-Gegenstück:

```java
lang.message(PartyMessage.MEMBERS)
    .with(Formatter.joining("members", memberNames))
    .with(Placeholder.styling("party_color", party.color()))
    .send(player);
```

`.with(TagResolver...)` im Builder und `Arg.of(TagResolver)` in der Kurzform reichen die Resolver unverändert an
MiniMessage durch.

### 5.2 MiniMessage bewusst zulassen

Wenn ein Wert aus einer vertrauenswürdigen Quelle stammt und Formatierung enthalten soll:

```java
player.sendMessage(LobbyMessage.MOTD, Arg.parsed("motd", config.motd()));
```

`Arg.parsed` entspricht `Placeholder.parsed` und ist die einzige Stelle, an der ein String als MiniMessage gelesen wird.
Für Spielereingaben ist es tabu.

### 5.3 Platzhalter pro Empfänger

Beim Senden an viele Spieler kann ein Platzhalter pro Empfänger berechnet werden:

```java
lang.message(GameMessage.BORDER_SHRINKS)
    .with("distance", viewer -> border.distanceTo(viewer))
    .send(game.players());
```

### 5.4 Plural

Ist der Key als Map mit `one` und `other` angelegt, wählt `.count(n)` die passende Form und setzt `<count>`:

```java
lang.message(ShopMessage.COINS).count(balance).component(player);
// "1 Münze" bzw. "250 Münzen"
```

In der ersten Version gilt: `one` bei n == 1, sonst `other`. Fehlt eine Form, fällt sie auf `other` zurück. Das deckt
Deutsch und Englisch ab. Weitere Formen (`zero`, `few`, `many`) nach CLDR sind nicht enthalten.

Adventure bietet dafür `Formatter.choice` an, basierend auf Javas `ChoiceFormat`:
`<count:'0#Münzen|1#Münze|1<Münzen'>`. Ich schlage trotzdem die YAML-Map vor. Das `<` aus `ChoiceFormat` steht dort
mitten in MiniMessage-Syntax, das ist für Übersetzer schwer lesbar. Außerdem kennt `ChoiceFormat` nur Zahlenbereiche
und keine Sprachregeln. `Formatter.choice` bleibt über [Adventure-Resolver](#adventure-resolver-direkt-verwenden)
trotzdem nutzbar.

### 5.5 Reservierte Namen

Platzhalter sind MiniMessage-Tags. Deshalb gelten zwei Einschränkungen:

- **Erlaubte Zeichen:** nur `[a-z0-9_-]`, also keine Großbuchstaben und keine Punkte.
- **Keine Namen von Standard-Tags.** Ein Platzhalter `<score>`, `<key>`, `<color>`, `<font>`, `<head>`, `<data>`,
  `<lang>`, `<insert>`, `<selector>`, `<shadow>`, `<sprite>` oder `<reset>` würde mit dem gleichnamigen
  MiniMessage-Tag kollidieren. In Minispielen passiert das gerade bei `<score>` leicht. Außerdem reserviert
  Adventures Übersetzer `<arg>` und `<argument>` für indizierte Argumente.

  Reserviert ist genauer: jeder Name, den `StandardTags.defaults().has(name)` kennt, dazu `arg`, `argument`,
  `prefix`, `content`, `count` und die Theme-Tags `p`, `highlight`, `success`, `deny`, `error`, `prompt`, `h1`,
  `h2`. Ausnahmen der Prüfung: `count` ist in Plural-Einträgen erlaubt, `prefix` und `content` nur in
  Style-Templates. `h1` und `h2` sind neu reserviert: `arg("h1", …)` und `arg("h2", …)` werfen seitdem eine
  `IllegalArgumentException`.

Pantheon prüft beim Start und beim ersten Rendern auf beides und meldet Verstöße. `arg("targetName", …)` wirft sofort
eine `IllegalArgumentException`.

---

## 6. Kanäle: Chat, Actionbar, Title, Items

Derselbe Key kann über verschiedene Kanäle ausgegeben werden. Der Message-Type liefert pro Kanal ein eigenes Template.
Dieselbe Nachricht wird deshalb im Chat mit Prefix und in der Actionbar ohne Prefix dargestellt.

| Kanal       | Aufruf                       | Template des Message-Types | Sound |
|-------------|------------------------------|----------------------------|-------|
| Chat        | `.send(...)`                 | `chat`                     | ja    |
| Actionbar   | `.actionbar(...)`            | `actionbar`                | ja    |
| Title       | `.title(...)`                | `title` / `subtitle`       | ja    |
| Rendern     | `.component(...)`, `.lines(...)`, `.plain(...)` | keins   | nein  |

### Title

Ist der Key eine Map mit `title`/`subtitle` (siehe [§2](#aufbau)), werden beide Teile verwendet. Ist er ein einfacher
String, wird er als Title ohne Untertitel gezeigt.

```java
lang.message(GameMessage.ROUND_START)
    .with("round", round)
    .title(game.players(), Title.Times.times(ofMillis(200), ofSeconds(2), ofMillis(500)));
```

### Items

Für Item-Namen und Lore wird nur gerendert. Es gibt kein Template und keinen Prefix. Das Kursiv, das Minecraft
Item-Namen und Lore standardmäßig gibt, wird über `decorationIfAbsent(ITALIC, FALSE)` abgeschaltet:

```java
ItemStack icon = ItemStack.of(Material.IRON_SWORD);
icon.editMeta(meta -> {
    meta.displayName(lang.message(ShopMessage.KIT_NAME).component(player));
    meta.lore(lang.message(ShopMessage.KIT_LORE).with("price", kit.price()).lines(player));
});
```

`.lines(...)` liefert bei einer YAML-Liste eine Zeile pro Eintrag, bei einem String eine Zeile pro `<newline>`.

Items müssen bewusst **für einen bestimmten Spieler** gerendert werden. Paper übersetzt Komponenten in ItemStacks
nicht serverseitig (siehe [§12](#12-verhältnis-zu-adventure)). Außerdem wird ein `<newline>` in Adventure nicht zu
einer neuen Lore-Zeile. Genau das übernimmt `.lines(...)`.

---

## 7. Message-Types

Message-Types geben einer Nachricht Kontext und sorgen für einheitliche Effekte wie Farbe, Prefix und Sound über alle
Plugins hinweg.

| Type      | Wofür                                        | Chat-Template (Default)                          | Sound (Default)      |
|-----------|----------------------------------------------|--------------------------------------------------|----------------------|
| `PLAIN`   | Rohtext ohne Styling, nicht konfigurierbar    | `<content>`                                      | –                    |
| `PROMPT`  | Fordert den Spieler zu einer Handlung auf    | `<newline>[!] <prefix> <highlight><b><content>`  | Noteblock            |
| `DEFAULT` | Die typische Plugin-Nachricht                | `<prefix> <p><content>`                          | –                    |
| `SUCCESS` | Die Aktion war erfolgreich                    | `<prefix> <success><content>`                    | XP                   |
| `DENY`    | Die Aktion wird abgelehnt (Regel, Recht, Geld) | `<prefix> <deny><content>`                     | Villager „no“        |
| `ERROR`   | Technisch ist etwas schiefgelaufen           | `<error>[x] <prefix> <error><content>`           | Amboss zerbricht     |

`DENY` und `ERROR` sind bewusst getrennt: `DENY` bedeutet „du darfst/kannst das nicht“, `ERROR` bedeutet „wir haben
einen Fehler“. Ein `ERROR` ist immer ein Grund, ins Log zu schauen.

**Theme-Tags.** Ihre Styles legt der Node in `plugins/Pantheon/theme.yml` fest, für alle Plugins gleich
([pantheon.md](pantheon.md#theme)). Plugins können sie nicht überschreiben, nur über `pantheon.theme()` lesen. Ohne
Datei gelten diese Defaults:

| Tag           | Default                     |
|---------------|-----------------------------|
| `<p>`         | `#d4d4d4`, nicht kursiv     |
| `<highlight>` | `#fb923c`                   |
| `<success>`   | `#7ef874`                   |
| `<deny>`      | `#f87171`                   |
| `<error>`     | `#dc2626`                   |
| `<prompt>`    | `#fdba74`                   |
| `<h1>`        | `#ffedd5`                   |
| `<h2>`        | `#fdba74`                   |

**Sounds (Default).** Quelle der Sounds ist `MASTER`, Lautstärke 1, Tonhöhe 1.

| Type      | Sound                          |
|-----------|--------------------------------|
| `PROMPT`  | `block.note_block.pling`       |
| `SUCCESS` | `entity.experience_orb.pickup` |
| `DENY`    | `entity.villager.no`           |
| `ERROR`   | `block.anvil.destroy`          |

Der Default-Prefix ist `<highlight><Pluginname></highlight><dark_gray> »</dark_gray>`.

Im Template stehen zwei besondere Tags:

- `<content>` ist die übersetzte Nachricht.
- `<prefix>` ist der Prefix des Plugins (siehe [§9](#9-konfiguration-per-code)).

### Eigene Types

Plugins können zusätzliche Types definieren, etwa für Belohnungen:

```java
public static final MessageType REWARD = MessageType.of("reward");

pantheon.config().language()
    .style(REWARD, style -> style
        .chat("<prefix> <gold>★ <content>")
        .actionbar("<gold>★ <content>")
        .sound(Sound.sound(Key.key("entity.player.levelup"), Sound.Source.MASTER, 1f, 1.5f)));
```

Dafür gibt es weder eine Registry noch eine Vererbung. Ein eigener Type ist nur ein Name, an dem ein Style hängt:

- Alles, was im Style nicht gesetzt ist (ein Kanal-Template, der Sound), kommt von `DEFAULT`.
- Hat ein eigener Type gar keinen Style, verhält er sich wie `DEFAULT`. Pantheon meldet das beim Start einmalig.
- Eigene Types gelten nur im Plugin, das sie konfiguriert, weil jedes Plugin seine eigene Pantheon-Instanz hat.

`MessageType` ist deshalb kein Enum, sondern ein kleiner Werttyp mit den sechs eingebauten Konstanten.

---

## 8. Sprache eines Spielers

Welche Sprache ein Spieler hat, entscheidet ein austauschbarer `LanguageProvider`:

```java
@FunctionalInterface
public interface LanguageProvider {
    Locale lang(PanPlayer player);
}
```

**Standard ist Deutsch.** Ohne weitere Konfiguration bekommt jeder Spieler die Default-Sprache (`de_DE`). Andere Quellen
wie das Olymp-Backend oder die Client-Sprache werden später als eigene Implementierungen ergänzt, ohne dass sich
an der restlichen API etwas ändert.

```java
// Default, muss nicht gesetzt werden
pantheon.config().language().provider(LanguageProvider.defaultLocale());

// Beispiel für später: Client-Sprache, wo bekannt
pantheon.config().language().provider(player -> player.local()
    .map(local -> local.bukkit().locale())
    .orElse(Locale.GERMANY));
```

Jedes Plugin hat seine eigene Pantheon-Instanz und damit auch seinen eigenen Provider. Plugins, die dieselbe
Sprachquelle nutzen sollen, setzen jeweils dieselbe Implementierung.

Vertrag für Implementierungen:

- **Schnell und ohne I/O.** `lang` wird bei jedem Senden für jeden Empfänger aufgerufen, auch vom Main-Thread und
  auch für Spieler auf anderen Servern. Eine Olymp-Implementierung liest die Sprache deshalb aus der Presence
  ([player.md §5](player.md#5-drei-datenebenen)), die auf jedem Server synchron vorliegt.
- **Nicht `player.lang()` aufrufen** und auch nicht den Pointer `Identity.LOCALE` am `PanPlayer` lesen. Beide fragen
  wieder den Provider, das ergäbe eine Endlosschleife. Die Client-Sprache liest man direkt am Bukkit-Player.
- **Thread-sicher.** Gerendert wird auch aus asynchronen Tasks.
- **Nie `null`.** Wer nichts weiß, gibt die Default-Sprache zurück.

Wer kein Spieler ist, etwa die Konsole, ein Command-Block oder eine eigene `Audience`, bekommt immer die
Default-Sprache. Der Provider wird dafür nicht gefragt.

Liefert der Provider eine Sprache, für die es keine Datei gibt, greift der Fallback. Gesucht wird so:

```
de_AT  →  de_DE (gleiche Sprache, anderes Land)  →  Default-Sprache  →  sichtbarer Platzhalter
```

Fehlt ein Key in allen Sprachen, sieht der Spieler `lobby:shop.bought` in Rot, und das Log meldet den Key einmalig.

Die ermittelte Sprache lässt sich direkt abfragen, etwa um Datumsangaben selbst zu formatieren:

```java
Locale locale = lang.locale(player);
```

### Sprachwechsel

Pantheon fragt den Provider nur ab und merkt deshalb selbst nicht, wenn sich eine Sprache ändert. Das muss die
Provider-Implementierung melden, indem sie ein `LanguageChangeEvent` (Spieler, alte und neue Sprache) auslöst. Plugins
mit gecachten, gerenderten Texten (Scoreboards, Hologramme, Item-Menüs) rendern darauf neu. Der Default-Provider
löst dieses Event nie aus.

> Der Name `PlayerLocaleChangeEvent` ist bewusst nicht gewählt. Paper hat bereits
> `org.bukkit.event.player.PlayerLocaleChangeEvent`, und das feuert nur bei einer Änderung der Client-Sprache.

---

## 9. Konfiguration per Code

Alles wird im `onEnable` konfiguriert. Ohne Konfiguration gelten sinnvolle Defaults: Default-Sprache `de_DE`, Prefix
aus dem Plugin-Namen, Stile wie in [§7](#7-message-types).

```java
@Override
public void onEnable() {
    Pantheon pantheon = Pantheon.get(this);

    pantheon.config().language()
        .defaultLocale(Locale.GERMANY)
        .prefix("<gradient:#f5c542:#f58442>Lobby</gradient>")
        .style(MessageType.SUCCESS, style -> style.noSound())        // Erfolg ohne Sound
        .adapter(Kit.class, (kit, locale) -> kit.displayName(locale))
        .register(ShopMessage.class, LobbyMessage.class);
}
```

| Einstellung                     | Bedeutung                                                                 |
|---------------------------------|---------------------------------------------------------------------------|
| `defaultLocale(Locale)`         | Fallback-Sprache und Sprache der Konsole. Default: `de_DE`.               |
| `provider(LanguageProvider)`    | Woher die Sprache eines Spielers kommt. Default: immer `defaultLocale`.   |
| `prefix(String)`                | Wert von `<prefix>` als MiniMessage.                                      |
| `prefix(MessageKey)`            | Übersetzter Prefix, falls er pro Sprache unterschiedlich ist.             |
| `style(MessageType, …)`         | Templates je Kanal und Sound eines Types ändern.                          |
| `adapter(Class<T>, (T, Locale) -> Component)` | Wie ein eigener Typ als Platzhalter dargestellt wird.       |
| `register(Class<? extends MessageKey>...)` | Enums für die Prüfung beim Start bekannt machen.               |
| `source(LanguageSource...)`     | Woher die Dateien kommen (siehe unten).                                   |

### Quellen

Standard ist `LanguageSource.pluginResources()`. Weitere Quellen werden übereinandergelegt, die spätere gewinnt pro Key:

```java
pantheon.config().language()
    .source(LanguageSource.pluginResources(),
            LanguageSource.dataFolder());   // plugins/Lobby/lang/*.yml überschreibt einzelne Keys
```

So kann ein Server einzelne Texte anpassen, ohne das Plugin neu zu bauen.

### Neu laden

```java
lang.reload();   // liest alle Quellen neu, prüft erneut, wirft gecachte Renderings weg
```

---

## 10. Prüfen auf fehlende Übersetzungen

Beim Start prüft Pantheon jedes registrierte Enum gegen jede gefundene Sprachdatei:

```
[Lobby] [Language] lobby_en_US.yml: 2 Keys fehlen
[Lobby] [Language]   - shop.too-poor   (ShopMessage.TOO_POOR)
[Lobby] [Language]   - kit.lore        (ShopMessage.KIT_LORE)
[Lobby] [Language] lobby_de_DE.yml: 1 Key wird nicht verwendet: shop.old-banner
```

Platzhalter werden ebenfalls verglichen. Benutzt `de_DE` `<price>`, `en_US` aber `<cost>`, erscheint eine Warnung.

### Im Test

Damit fehlende Übersetzungen schon mit `./gradlew test` auffallen und nicht erst auf dem Server:

```java
@Test
void allTranslationsPresent() {
    LanguageAssertions.assertComplete("lobby", ShopMessage.class, LobbyMessage.class);
}
```

Der Test braucht keinen laufenden Server. Er liest `lang/*.yml` vom Test-Classpath und führt **dieselbe Prüfung** wie
beim Serverstart aus: fehlende Keys, ungenutzte Keys, abweichende oder reservierte Platzhalter. Schlägt sie fehl,
nennt die Meldung jede Abweichung mit Datei, Key und Enum-Konstante:

```
lobby_en_US.yml: Key fehlt: shop.too-poor (ShopMessage.TOO_POOR)
lobby_en_US.yml: Platzhalter weicht ab bei shop.bought: de_DE hat <price>, en_US hat <cost>
```

Der Mehrwert liegt vor allem in der Arbeit mit KI-Agenten. Ein Agent, der eine neue Nachricht anlegt und eine Sprache
vergisst, bekommt sofort eine eindeutige, maschinenlesbare Rückmeldung aus dem Build statt eines roten Keys im Spiel.

Damit die Library schlank bleibt, steckt der Helfer **nicht** in der Haupt-Library. Er wird über Gradles
Test-Fixtures ausgeliefert und landet nur auf dem Test-Classpath:

```kotlin
dependencies {
    testImplementation(testFixtures("de.kalypzo:pantheon-api:<version>"))
}
```

Im Kern ist es eine einzige Methode über der Prüflogik, die ohnehin für den Serverstart existiert.

---

## 11. Rezepte

### Befehls-Feedback

```java
// bukkitPlayer: org.bukkit.entity.Player, player: der zugehörige PanPlayer
if (!bukkitPlayer.hasPermission("lobby.fly")) {
    player.sendMessage(CommonMessage.NO_PERMISSION);
    return;
}
bukkitPlayer.setAllowFlight(!bukkitPlayer.getAllowFlight());
player.sendMessage(bukkitPlayer.getAllowFlight() ? LobbyMessage.FLY_ON : LobbyMessage.FLY_OFF);
```

### Kauf mit Ablehnung und Erfolg

```java
if (balance < kit.price()) {
    player.sendMessage(ShopMessage.TOO_POOR, arg("missing", kit.price() - balance));
    return;
}
economy.withdraw(player, kit.price()).whenComplete((ok, error) -> {
    if (error != null) {
        player.sendMessage(ShopMessage.PURCHASE_FAILED);          // ERROR, mit Sound
        logger.error("Kauf fehlgeschlagen", error);
        return;
    }
    player.sendMessage(ShopMessage.BOUGHT, arg("kit", kit), arg("price", kit.price()));
});
```

### Broadcast, jeder in seiner Sprache

```java
lang.message(GameMessage.PLAYER_ELIMINATED)
    .with("victim", victim)
    .with("killer", killer)
    .with("remaining", game.alive().size())
    .send(game.players());
```

### Aufforderung mit klickbarem Teil

```java
Component confirm = lang.message(CommonMessage.CLICK_TO_CONFIRM).component(player)
    .clickEvent(ClickEvent.runCommand("/party accept " + party.id()));

lang.message(PartyMessage.INVITED)           // PROMPT
    .with("leader", party.leader())
    .with("confirm", confirm)
    .send(player);
```

Der Click-Event wird im Code gesetzt und nicht im YAML (`<click:run_command:'/party accept <id>'>`). MiniMessage
setzt Platzhalter nicht in Argumente anderer Tags ein. Der Befehl wäre dort also nur mit `Arg.parsed` möglich, und
das ist bei variablen IDs genau die unsichere Variante.

### Verschachtelte Übersetzung

```yaml
kit:
  warrior.name: "Krieger"
shop:
  selected: "Ausgewählt: <kit>"
```

```java
player.sendMessage(ShopMessage.SELECTED, arg("kit", MessageKey.of("kit.warrior.name")));
// Jeder Empfänger sieht den Kit-Namen in seiner Sprache.
```

### Text für Logs oder Scoreboards

```java
String line = lang.message(LobbyMessage.SCOREBOARD_COINS).count(coins).plain(player);
```

---

## 12. Verhältnis zu Adventure

Geprüft gegen Adventure **5.2.0**, die Version, die Paper 26.3 mitbringt.

### Was Pantheon von Adventure übernimmt

| Adventure                                         | Rolle in Pantheon                                                     |
|---------------------------------------------------|-----------------------------------------------------------------------|
| `Audience`, `ForwardingAudience`                  | Empfänger aller Sendemethoden                                         |
| `ForwardingAudience.Single`                       | Basis des `PanPlayer`, Zustellung über austauschbares `audience()`    |
| `MiniMessage`, `TagResolver`, `Placeholder`, `Formatter` | Parsen, Platzhalter, Durchreichen eigener Resolver             |
| `Title`, `Title.Times`, `Sound`                   | Title-Kanal und Sounds der Message-Types                              |
| `MiniMessageTranslator` (`getMiniMessageString(key, locale)`) | Intern: Auflösen eines Keys gegen die YAML-Daten           |
| `TranslatableComponentRenderer.usingTranslationSource(...)` | Intern: Rendern mit der **von Pantheon** ermittelten Sprache |

Pantheon ist damit eine dünne Schicht über Adventure. Eigene Arbeit steckt nur in YAML-Laden, Key-Deklaration und
-Prüfung, Message-Types mit Templates je Kanal, dem `LanguageProvider` und der Typerkennung bei Platzhaltern.

### Adventures eigenes Übersetzungsmodell, und warum Pantheon nicht allein darauf setzt

Adventure hat einen eingebauten Weg für Übersetzungen pro Empfänger: Man registriert einen `MiniMessageTranslationStore`
im `GlobalTranslator` und sendet `Component.translatable("key", Argument.component("kit", …))`. Paper übersetzt dann
jede Komponente, die an einen Client geht, **serverseitig mit der Client-Sprache**. Das gilt für Chat, Actionbar,
Titles, Scoreboards, Tab-Liste und Entity-Namen.

Für Pantheon reicht das aus drei Gründen nicht als einziger Weg:

1. **Feste Sprachquelle.** Paper rendert immer mit der Client-Sprache. Der `LanguageProvider` würde ignoriert,
   auch der Default „Deutsch“.
2. **Keine Items.** Paper übersetzt Item-Namen und Lore ausdrücklich nicht serverseitig.
3. **Keine Templates und Sounds.** Ein `TranslatableComponent` weiß nichts von Message-Types.

Pantheon rendert deshalb selbst beim Senden für jeden Empfänger. Intern nutzt es dafür dieselben Bausteine
(`MiniMessageTranslator`, `TranslatableComponentRenderer`), aber mit der Locale aus dem `LanguageProvider`
([§8](#8-sprache-eines-spielers)).

### Bewusst nicht enthalten

Pantheon registriert **nichts** im `GlobalTranslator` und liefert keine `TranslatableComponent`s nach außen. Auf diesem
Weg würde Paper immer mit der Client-Sprache übersetzen, also am `LanguageProvider` vorbei. Ein Spieler sähe dann den
Chat auf Deutsch und Entity-Namen auf Englisch.

Für Texte, die einmal gesetzt und an viele Spieler verteilt werden (Entity-Namen, Scoreboard-Teams), gibt es zwei Wege:
Man rendert sie pro Spieler, etwa über Packets oder Per-Player-Scoreboards, oder man rendert sie einmal mit der
Default-Sprache (`.component(lang.defaultLocale())`).

`MessageKey` erweitert aus demselben Grund nicht Adventures `Translatable`.

---

## 13. Unterschiede zu Megalodon

| Thema                      | Megalodon                                                           | Pantheon                                                       |
|----------------------------|---------------------------------------------------------------------|----------------------------------------------------------------|
| Format                     | `.properties` Resource-Bundles                                      | YAML mit Listen, Title-Maps, Plural                            |
| Keys                       | Strings oder `Key`, Fehler erst zur Laufzeit                        | Enums, beim Start und optional im Test geprüft                 |
| Plugin-Bezug               | `Translation.of(megalodon, key)`                                    | Über die `Language`-Instanz, Key bleibt plugin-neutral         |
| Senden                     | ~20 Überladungen in `Messageable`                                   | 3 Methoden am `PanPlayer` plus unveränderlicher Builder        |
| Spieler als Audience       | `GamePlayer` mit eigenem `Messageable`                              | `PanPlayer extends ForwardingAudience.Single`                  |
| Message-Type               | An jeder Aufrufstelle                                               | Bei der Deklaration, pro Aufruf überschreibbar                 |
| Platzhalter                | `TagResolver...` von Hand                                           | `arg(name, value)` mit Typerkennung und Adaptern               |
| Spielereingaben            | Sicherheit hängt vom gewählten Resolver ab                          | Strings sind immer wörtlich, `Arg.parsed` ist explizit         |
| Sprachquelle               | `Localed`, fest am `GamePlayer`                                     | Austauschbarer `LanguageProvider`, Default Deutsch             |
| Fallback                   | Fest auf Englisch                                                   | Gleiche Sprache → Default-Sprache → sichtbarer Key             |
| Title/Subtitle             | Key-Suffix `.subtitle` (deprecated) oder zwei Translations          | Ein Key mit `title`/`subtitle`                                 |
| Lore                       | Automatischer Umbruch bei 100 Zeichen, `LoreAffixType`              | Zeilen kommen aus der YAML-Liste, Formatierung bleibt im Text  |
| Adventure-Übersetzer       | Nicht genutzt, eigenes Bundle-System                                | Intern `MiniMessageTranslator`, ohne `GlobalTranslator`         |

---

## 14. Entscheidungen

### Festgelegt

- **Enums für Keys.** Alternativen wären Konstanten-Klassen mit `MessageKey.of(...)` oder Code-Generierung aus dem
  YAML per Gradle-Plugin. Enums bieten die Prüfung beim Start ohne Build-Tooling. Code-Generierung wäre die stärkste
  Variante, aber auch die teuerste.
- **Message-Type in der Deklaration** statt im YAML. Übersetzer sollen Text ändern, aber nicht die Bedeutung.
- **Unveränderlicher Builder.** Kostet pro `.with()` eine kleine Kopie, dafür kann eine `Message` geteilt und
  wiederverwendet werden.
- **Kein automatischer Lore-Umbruch.** Umbrüche gehören in den Text, weil Satzlängen je Sprache verschieden sind.
- **`<deny>` und `<error>` als Theme-Tags** statt fester Farben wie `<#123456>` in der bisherigen Doku.
- **`showTitle` statt `sendTitle`** am `PanPlayer`, passend zu Adventures `showTitle(Title)`.
- **Pointer des `PanPlayer`:** `Identity.LOCALE` liefert `lang()` statt der Client-Sprache. So rendern auch fremde
  Adventure-Bausteine, die den Pointer lesen, in der Pantheon-Sprache.
- **Theme aus der `theme.yml` des Nodes**, Defaults in [§7](#7-message-types), keine Overrides durch Plugins.
- **Sounds und Default-Prefix** wie in [§7](#7-message-types) angegeben.
- **Plural in v1:** `one` bei n == 1, sonst `other`, fehlende Formen fallen auf `other` zurück ([§5.4](#54-plural)).
- **Reservierte Platzhalter:** alles, was `StandardTags.defaults()` kennt, dazu `arg`, `argument`, `prefix`, `content`,
  `count` und die Theme-Tags ([§5.5](#55-reservierte-namen)).
- **Kick- und Ablehnungstexte auf dem Proxy** sind feste Texte. Das Velocity-Modul hängt nicht von `core` ab.

### Abgestimmt

- **Sprachquelle:** austauschbarer `LanguageProvider#lang(PanPlayer)`, Default Deutsch ([§8](#8-sprache-eines-spielers)).
- **Eine Pantheon-Instanz pro Plugin.** Provider, Prefix, Styles und eigene Types gelten pro Plugin.
- **Eigene Message-Types** bleiben drin, in schlanker Form ([§7](#eigene-types)).
- **Plural** ist Teil der API ([§5.4](#54-plural)).
- **Kein Abbruch per Exception.** Das ist Aufgabe des jeweiligen Command-Frameworks, nicht von Pantheon.
- **Test-Helfer** `LanguageAssertions.assertComplete(...)` ist enthalten, aber nur als Test-Fixture und als dünne
  Hülle um die Prüfung beim Start ([§10](#im-test)).
- **Keine Brücke zum `GlobalTranslator`** ([§12](#bewusst-nicht-enthalten)).
- **`PanPlayer` als zentraler Einstiegspunkt**, als `ForwardingAudience.Single` mit `sendMessage`, `sendActionBar` und
  `showTitle` für `MessageKey`s. Die Methoden setzen auf der Language API auf ([§4](#der-panplayer)).
