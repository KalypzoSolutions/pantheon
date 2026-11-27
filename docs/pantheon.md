# Pantheon Docs


Die `Pantheon` Interface ist das zentrale Schnittstelle zu allen Servies.
    Basierend auf den Erfahrungen der letzten Jahren und persönlichen Preferenzen entsteht dieses Bibliothek.

```java
Pantheon pantheon = Pantheon.get(myPlugin)
```

### Einbinden

Die API liegt auf Maven Central. Zur Laufzeit stellt das Pantheon-Plugin sie bereit, deshalb `compileOnly`:

```kotlin
repositories {
    mavenCentral()
    // nur für -SNAPSHOT-Versionen:
    maven("https://central.sonatype.com/repository/maven-snapshots/")
}

dependencies {
    compileOnly("de.kalypzo:pantheon-api:<version>")
    testImplementation(testFixtures("de.kalypzo:pantheon-api:<version>")) // LanguageAssertions
}
```

Veröffentlicht werden `de.kalypzo:pantheon-api`, `de.kalypzo:pantheon-core` und `de.kalypzo:agora-client`.
Die Plugin-Jars für Paper und Velocity hängen am jeweiligen GitHub-Release
(<https://github.com/KalypzoSolutions/pantheon/releases>).

**Release:** Tag `v<version>` pushen. Der Workflow `release.yml` baut mit `-Pversion=<version>`, gibt auf Central
frei und legt das GitHub-Release an. Voraussetzung: `agora` in `gradle/libs.versions.toml` ist eine
veröffentlichte Version (kein `-SNAPSHOT`). Danach `version` in `gradle.properties` auf die nächste `-SNAPSHOT`
setzen. **Snapshot:** jeder Push auf `main`.

### CraftEngine Utilities


### Einstellungen
Im Vordergrund steht configure-by-code. Die Config kann mit `pantheon.config().` erreicht werden.

#### Agora-Verbindung
Ausnahme von configure-by-code: Die Verbindung zu [Agora](agora.md) gehört dem Node, nicht dem Plugin. Alle Plugins
auf einem Server teilen sich einen Kanal ([agora.md §9](agora.md#9-verbindungen-und-sicherheit)), und der Token ist ein
Secret, das nicht im Plugin-Code oder im Jar landen darf. Pantheon liest die Werte deshalb beim Start aus
Umgebungsvariablen:

| Variable                 | Bedeutung                                                                  |
|--------------------------|----------------------------------------------------------------------------|
| `PANTHEON_AGORA_ADDRESS` | Adresse des Dienstes, z. B. `agora:9090`                                    |
| `PANTHEON_NODE_ID`       | Eindeutiger Node-Name, gleich dem Namen in der Server-Liste der Proxys      |
| `PANTHEON_AGORA_TOKEN`   | Token dieses Nodes, wird bei jedem Aufruf mitgeschickt und von Agora geprüft |
| `PANTHEON_BACKEND`       | `agora` (Default) oder `memory`. Bei `memory` laufen Daten, Presence und Nachrichten in einem Prozess (Tests, lokale Entwicklung), die drei Agora-Variablen entfallen. `PANTHEON_NODE_ID` hat dann den Default `local` |
| `PANTHEON_LOGIN_TIMEOUT_MS` | Timeout für Laden und Login in Millisekunden, Default `5000` ([player.md §7](player.md#7-lebenszyklus)) |

- Fehlt ein Wert oder lehnt Agora den Token ab (`UNAUTHENTICATED`), ist das ein Konfigurationsfehler: Pantheon
  startet nicht und meldet das im Log. Ist Agora nur nicht erreichbar, startet der Node normal und verbindet sich
  später ([agora.md §8](agora.md#8-wenn-agora-weg-ist)).
- `onEnable` wartet höchstens 5 Sekunden auf das erste Ergebnis der Verbindung. Der Node gilt als verbunden
  (`CONNECTED`), sobald Agora die erste Barriere nach dem `Hello` bestätigt hat. Das beweist Token und
  verarbeitetes `Hello`, auf `Welcome` wartet Pantheon nicht ([agora.md §3](agora.md#3-der-node-stream)).
- Meldet Agora zur Laufzeit bei einem einfachen Aufruf `UNAUTHENTICATED` oder `PERMISSION_DENIED` (etwa wegen eines
  widerrufenen Tokens), scheitern die betroffenen Futures mit `DataUnavailableException`. Das Plugin wird dafür nicht
  gestoppt.
- **Reihenfolge beim Deployment:** Agora vor Pantheon ausrollen. Pantheon braucht die Barriere des Dienstes für den
  Verbindungsaufbau, ein älteres Agora ohne sie kommt nicht über `CONNECTING` hinaus.
- Das Velocity-Modul hängt nicht vom Modul `core` ab. Kick- und Ablehnungstexte dort sind feste Texte, weil Velocity
  seine eigene Adventure-Version mitbringt.
- Der Token erscheint nie im Log, auch nicht gekürzt.
- `pantheon.config()` bietet keinen Setter für Adresse oder Token. Ein Plugin kann die Verbindung also weder ändern
  noch auslesen.

#### Theme
Zweite Ausnahme von configure-by-code: Das Theme gehört dem Node, nicht dem Plugin. Alle Plugins auf einem Server
rendern ihre Theme-Tags (`<p>`, `<highlight>`, `<success>`, `<deny>`, `<error>`, `<prompt>`, `<h1>`, `<h2>`) mit
`plugins/Pantheon/theme.yml`. Fehlt die Datei, schreibt Pantheon beim Start die Defaults hinein
([language.md §7](language.md#7-message-types)). Für ein einheitliches Bild muss die Datei auf allen Servern gleich
sein, denn gerendert wird auf dem sendenden Server.

```yaml
styles:
  p:
    color: "#d4d4d4"
    italic: false
  success: "#7ef874"     # Kurzform: nur Farbe
  h1:
    color: "#ffedd5"
    bold: true
```

- Pro Tag ein String (Farbe) oder ein Objekt mit `color`, `bold`, `italic`, `underlined`, `strikethrough`,
  `obfuscated`. Farben sind `"#rrggbb"` oder ein Minecraft-Name wie `gray`.
- **Hex-Werte in Anführungszeichen.** In YAML beginnt mit `#` ein Kommentar, `success: #7ef874` hat also keinen Wert.
- **Ergänzen statt ersetzen:** Was ein Eintrag weglässt, kommt vom Default. Eine Dekoration des Defaults schaltet
  `false` ab.
- **Fehler:** Ein ungültiger Eintrag fällt komplett auf seinen Default zurück, unbekannte Tags und Schlüssel werden
  ignoriert, jeweils mit `WARNING`. Ist die Datei kein gültiges YAML, gelten alle Defaults (`SEVERE`). Am Theme
  scheitert nie ein Start.
- Gelesen wird nur beim Start. Danach steht `Theme <hash>` im Log. Ein abweichender Hash zeigt einen Server mit
  anderer Datei.
- Aus Code: `pantheon.theme().style(ThemeStyle.H1)` bzw. `.color(...)`, etwa für selbst gebaute Components. Ändern
  kann ein Plugin das Theme nicht.

### Audit-Log
Plugins schreiben Einträge pro Spieler mit `player.audit().action(...)`, `pantheon.audit()` liefert Spieler-Handles für
Offline-Spieler, versteckte Commands und das Lesen der Zeitachse. Pantheon puffert die Einträge pro Node, schickt sie
gebündelt an [Agora](agora.md) und loggt Chat und Commands selbst. Agora hält sie 14 Tage. Alles Weitere steht in
[audit_log.md](audit_log.md), die API am Spieler in [player.md §2](player.md#2-zwei-typen-panplayer-und-localpanplayer).

```java
player.audit().action(ShopAudit.BUY, d -> d.with("item", "diamond_sword"));
pantheon.audit().hideCommand(getCommand("msg"));
```

### Lebenszyklus pro Plugin
Pantheon legt pro Plugin eine eigene Instanz an. Sie wird **nach** dem `onDisable` des Plugins abgebaut
(`PluginDisableEvent`, geschlossen auf dem nächsten Tick): Alle `onChange`-Abos der Instanz werden entfernt, laufende
Schreibzugriffe laufen zu Ende. Bis dahin liefert `Pantheon.get(plugin)` die vorhandene Instanz, danach wirft es eine
`IllegalStateException`. Ein Plugin, das erneut aktiviert wird, bekommt eine frische Instanz. Ein `get` für ein
deaktiviertes oder gerade deaktivierendes Plugin legt nie eine neue Instanz an. Scheitert die Konfiguration
(`ConfigError`), meldet Pantheon das Plugin vor dem Deaktivieren ab.

### Language
Konsumenten vom Pantheon können Language-Packs definieren. 
Standardmäßig ist pantheon so konfiguriert, dass die Language-Packs direkt aus dem Plugin gelesen werden.
Das ganze sind .yml Dateien im Resource-Ordner 
- `resources/lang/<plugin>_de_DE.yml`
- `resources/lang/<plugin>_en_US.yml` 
mit <plugin> | Der Plugin-Name in der plugin.yml in lowercase.
> Das ganze empfiehlt sich wenn in einer Mono-Repo mehrere language Dateien existieren.

#### MiniMessage
Der Inhalt wird mit dem pantheon-MiniMessage parser gelesen, was widerum einige styling tags mit sich bringt.
Beachtet das abhängig davon wie die Nachricht gesendet wird, die Nachricht nochmal gewrapped wird.



