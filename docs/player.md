# Pantheon Player API

> Status: **Umgesetzt.** Aufbauend auf den vorbereiteten Interfaces `PanPlayer`, `PanPlayerFactory`,
> `ExpectedOnlinePlayerException` und `NetworkLocation`. Nachrichten an Spieler sind in [language.md](language.md)
> beschrieben.

Der `PanPlayer` ist der zentrale Einstiegspunkt für alles, was einen Spieler betrifft: wer er ist, wo er ist, welche
Sprache er spricht, welche Daten Plugins an ihm speichern und wie man ihm Nachrichten schickt. Das gilt auch, wenn er
gerade auf einem anderen Server im Netzwerk ist.

```java
LocalPanPlayer player = pantheon.getPlayer(event.getPlayer());

player.data().inc(EconomyData.COINS, 50);                      // synchron, atomar in der Datenbank
player.sendMessage(ShopMessage.BOUGHT, arg("kit", kit));
```

---

## Inhalt

1. [Leitlinien](#1-leitlinien)
2. [Zwei Typen: PanPlayer und LocalPanPlayer](#2-zwei-typen-panplayer-und-localpanplayer)
3. [Spieler bekommen](#3-spieler-bekommen)
4. [Nachrichten](#4-nachrichten)
5. [Drei Datenebenen](#5-drei-datenebenen)
6. [Eigene Spielerdaten](#6-eigene-spielerdaten)
7. [Lebenszyklus](#7-lebenszyklus)
8. [Serverwechsel](#8-serverwechsel)
9. [Fehlerfälle](#9-fehlerfälle)
10. [Threads und Futures](#10-threads-und-futures)
11. [Betrachtete Ansätze](#11-betrachtete-ansätze)
12. [Unterschiede zu Megalodon](#12-unterschiede-zu-megalodon)
13. [Entscheidungen und offene Fragen](#13-entscheidungen-und-offene-fragen)

---

## 1. Leitlinien

- **Was hier ist, ist synchron.** Ein `CompletableFuture` gibt es nur, wo wirklich ein anderer Server oder das Backend
  antworten muss. Den Großteil seines Codes, also Events, Commands und Menüs, schreibt man ohne ein einziges Future.
- **Daten sind da, bevor der Spieler da ist.** Geladen wird vor dem Join. Ab dem `PlayerJoinEvent` muss niemand mehr
  fragen, ob schon alles geladen ist.
- **Schreiben ist synchron, Speichern passiert im Hintergrund.** Wer Daten ändert, wartet nie auf eine Datenbank.
  Ausnahme sind Entscheidungen wie „hat er genug Geld?“: Die trifft die Datenbank, das Ergebnis kommt als Future.
- **Handles sind leicht.** Ein `PanPlayer` hält keine Daten fest. Man kann ihn in Maps speichern, ohne Speicher zu
  verlieren, wenn der Spieler geht.
- **Kein Spiegel der Bukkit-API.** Gesundheit, Inventar und Gamemode bleiben bei `bukkit()`. Pantheon ergänzt, was
  Bukkit nicht kann: Netzwerk, Sprache und persistente Daten.
- **Thread-sicher.** Alle Methoden dürfen von jedem Thread aufgerufen werden.

---

## 2. Zwei Typen: PanPlayer und LocalPanPlayer

Ob ein Zugriff synchron möglich ist, hängt davon ab, ob der Spieler auf **diesem** Server ist. Das drückt der Typ aus,
nicht eine Exception zur Laufzeit.

```java
/** Ein Spieler irgendwo im Netzwerk. */
public interface PanPlayer extends ForwardingAudience.Single {

    UUID uniqueId();
    String name();
    Locale lang();

    boolean isOnline();                           // irgendwo im Netzwerk
    boolean isLocal();                            // auf diesem Server
    Optional<String> server();                    // aktueller Server, leer wenn offline

    Optional<LocalPanPlayer> local();             // Zugriff auf die synchrone Seite, falls hier

    CompletableFuture<NetworkLocation> location();

    AuditLog audit();                             // Audit-Log für diesen Spieler, auch auf anderen Servern, siehe audit_log.md §4

    void sendMessage(MessageKey key, Arg... args);
    void sendActionBar(MessageKey key, Arg... args);
    void showTitle(MessageKey key, Arg... args);
}

/** Ein Spieler auf diesem Server. Alles synchron. */
public interface LocalPanPlayer extends PanPlayer {

    Player bukkit();

    boolean hasPermission(String permission);

    Doc data();                                   // Spielerdaten dieses Plugins, siehe §6

    <T> Optional<T> session(SessionData<T> key);
    <T> void session(SessionData<T> key, @Nullable T value);
}
```

Alles an `PanPlayer` außer `location()` ist trotzdem synchron. Möglich macht das die
[Presence](#5-drei-datenebenen): Name, Server und Sprache jedes Online-Spielers liegen auf jedem Server im Speicher.

`audit()` ist wie der `PanPlayer` selbst ein leichtes Handle ohne Zustand. Einträge gehen in den Puffer des Nodes und
blockieren nie, der Aufruf ist von jedem Thread erlaubt ([audit_log.md §4](audit_log.md#4-aus-plugins-loggen)). Die
aktive Trace eines Spielers endet im `PlayerQuitEvent` auf `MONITOR`, also nach den Plugin-Einträgen bis `HIGHEST`.

`location()` bleibt asynchron, weil die Koordinaten eines Spielers auf einem anderen Server nur dieser Server kennt.
Wer einen `LocalPanPlayer` hat, nimmt einfach `bukkit().getLocation()`.

### Wenn der Spieler geht

Ein Handle bleibt gültig, auch nachdem der Spieler den Server verlassen hat:

- `isLocal()` wird `false`, `local()` wird leer.
- Ein festgehaltener `LocalPanPlayer` wirft bei `bukkit()`, `get(...)` usw. eine
  `ExpectedOnlinePlayerException(uuid, LOCAL)`. Das passiert nur, wenn man ihn über den Quit hinaus aufbewahrt.
- Nachrichten an das Handle gehen an den Server, auf dem der Spieler jetzt ist, oder verfallen, wenn er offline ist.

---

## 3. Spieler bekommen

```java
// In Events und Commands: Der Spieler ist hier, das klappt immer.
LocalPanPlayer player = pantheon.getPlayer(bukkitPlayer);

// Spieler irgendwo im Netzwerk, z. B. für /msg oder Freundeslisten. Synchron über die Presence.
Optional<PanPlayer> friend = pantheon.players().online(uuid);
Optional<PanPlayer> target = pantheon.players().online("Notch");
```

Dass ein Spieler offline ist, ist ein normaler Fall und keine Ausnahme. Deshalb liefern die Methoden ein `Optional`
statt eine Exception zu werfen. Die Presence wird kurz nach einem Join oder Quit auf allen Servern aktualisiert. Wer
sie gerade abfragt, bekommt für wenige Millisekunden noch den alten Stand.

`PanPlayerFactory` wird damit zu:

```java
public interface PanPlayerFactory {
    LocalPanPlayer local(Player player);                                   // kann nicht scheitern
    Optional<PanPlayer> online(UUID uuid);
    Optional<PanPlayer> online(String name);
    Collection<PanPlayer> online();                                        // ganzes Netzwerk
    Collection<LocalPanPlayer> onlineLocal();                              // nur dieser Server
}
```

`pantheon.getPlayer(bukkitPlayer)` ist eine Kurzform für `pantheon.players().local(bukkitPlayer)`.

---

## 4. Nachrichten

`PanPlayer` ist eine `ForwardingAudience.Single` (siehe [language.md §4](language.md#der-panplayer)). Wohin `audience()`
zustellt, hängt davon ab, wo der Spieler ist:

| Spieler ist …            | `audience()` stellt zu über …                                   |
|--------------------------|-----------------------------------------------------------------|
| auf diesem Server        | den Bukkit-Player                                               |
| auf einem anderen Server | Agora an den Proxy des Spielers                                  |
| offline                  | nichts. Die Nachricht verfällt, im Debug-Log steht ein Hinweis. |

Gerendert wird immer auf dem **sendenden** Server, mit `lang()` aus der Presence. Über das Netzwerk geht das fertige
Component samt Sound. Für den Aufrufer ist das Senden in allen Fällen `void`, auch bei Spielern auf anderen Servern.

Zugestellt wird über den **Proxy**, nicht über den Paper-Server des Spielers. Der Proxy hält die Verbindung über jeden
Serverwechsel hinweg, eine Nachricht mitten im Wechsel geht deshalb nicht verloren
([agora.md §6](agora.md#6-nachrichten)).

```java
pantheon.players().online(friendId).ifPresent(friend ->
    friend.sendMessage(FriendMessage.REQUEST_RECEIVED, arg("from", player)));   // egal, wo der Freund ist
```

---

## 5. Drei Datenebenen

Pantheon trennt Spielerdaten nach Reichweite und Lebensdauer. Gerade diese Trennung macht synchronen Zugriff fast
überall möglich.

| Ebene        | Inhalt                                             | Liegt …                                   | Zugriff                         | Lebensdauer                            |
|--------------|----------------------------------------------------|-------------------------------------------|---------------------------------|----------------------------------------|
| **Presence** | UUID, Name, aktueller Server, Sprache, online seit | auf **jedem** Server im Netzwerk           | synchron, an jedem `PanPlayer`  | solange der Spieler im Netzwerk ist    |
| **Profil**   | Persistente Plugin-Daten (`player.data()`)         | nur auf dem Server, auf dem der Spieler ist | synchron am `LocalPanPlayer`   | geladen vor Join, entladen nach Quit   |
| **Session**  | Flüchtiger Zustand (`SessionData`)                 | nur lokal                                 | synchron am `LocalPanPlayer`    | bis zum Quit, wird nie gespeichert     |

**Presence** ist klein, etwa 100 Byte pro Spieler. Der Proxy meldet Join, Serverwechsel und Quit an den
Agora, das jede Änderung an alle Server verteilt ([agora.md §4](agora.md#4-presence)). Mit
1.000 Spielern sind das rund 100 KB pro Server. Dafür sind `isOnline`, `server()`, `name()` und `lang()` überall
synchron. Auch der `LanguageProvider` liest seine Sprache von hier ([language.md §8](language.md#8-sprache-eines-spielers)).

**Profil** ist das, was ein Plugin über einen Spieler dauerhaft wissen will, etwa Münzen, freigeschaltete Kits oder
Einstellungen. Es liegt im Speicher des Servers, auf dem der Spieler gerade ist.

**Session** ist für alles, was nur während des Aufenthalts zählt, etwa einen Combat-Tag, ein offenes Menü oder einen
Cooldown. Pantheon räumt sie beim Quit automatisch weg. Die übliche `Map<UUID, …>`, die nie aufgeräumt wird, entfällt.

---

## 6. Eigene Spielerdaten

Spielerdaten sind ein `Doc` aus der [Data API](data.md) mit typisierten Feldern und atomaren Feld-Operationen. Jedes
Feld gehört zu einem [Namespace](data.md#namespaces). Zwei Namespaces können beide ein Feld `coins` haben, und ein
Plugin kann die Felder eines anderen über dessen exportierte Konstanten benutzen.

### Felder deklarieren

```java
public final class LobbyData {
    public static final Namespace LOBBY = Namespace.of("lobby");

    public static final Field<Set<String>> KITS        = LOBBY.ofSet("kits", String.class);
    public static final Field<Home>        HOME        = LOBBY.of("home", Home.class);
    public static final SessionData<Instant> LAST_COMBAT = SessionData.of("last-combat");   // nie gespeichert
}
```

Registrieren muss man nichts. Pantheon lädt beim Login die Daten aller Namespaces für diesen Spieler in einem
Zugriff ([agora.md §7](agora.md#7-postgres)). Nur Felder, nach denen sortiert oder gesucht wird, bekommen einen
Index, deklariert vom Plugin, dem der Namespace gehört:

```java
pantheon.players().index(EconomyData.COINS);           // im Economy-Plugin
```

### Lesen und schreiben

```java
Doc data = player.data();

boolean scoreboard = data.get(SCOREBOARD);            // synchron
data.set(HOME, new Home(…));                          // sofort lokal, atomar im Hintergrund

data.atomic()                                         // Kaufen: die Datenbank entscheidet
    .withdraw(EconomyData.COINS, kit.price())
    .require(KITS, not(contains(kit.id())))
    .add(KITS, kit.id())
    .commit()
    .thenAcceptAsync(bought -> player.sendMessage(bought ? ShopMessage.BOUGHT : ShopMessage.NOT_POSSIBLE),
        pantheon.mainThread());
```

Alle Operationen stehen in [data.md §3](data.md#3-mit-dokumenten-arbeiten). Unbedingte (`set`, `inc`, `max`, `min`,
`add`, `remove`) wirken sofort im Speicher und werden im Hintergrund atomar ausgeführt. Bedingte (`withdraw`,
`atomic`) entscheidet die Datenbank, sie liefern ein Future.

### Auf Änderungen reagieren

```java
pantheon.players().onChange(COINS, (player, before, after) ->
    scoreboard.update(player, after));
```

Das feuert bei jeder Änderung an einem Spieler auf diesem Server. Dazu gehören auch Änderungen, die ein anderer
Server gemacht hat, etwa ein `/pay` von dort oder eine letzte Änderung beim [Serverwechsel](#8-serverwechsel).

### Spieler, die nicht hier sind

```java
pantheon.players().data(uuid).inc(COINS, 100);        // RemoteDoc: schreiben ohne Laden, muss man nicht abwarten
pantheon.players().load(uuid)                          // CompletableFuture<Optional<Doc>>: lesen
    .thenAccept(doc -> doc.ifPresent(d -> sender.sendMessage(EcoMessage.BALANCE,
        arg("name", d.get(PlayerFields.NAME)), arg("coins", d.get(COINS)))));
```

Ist der Spieler gerade auf einem anderen Server, bekommt dieser Server die Änderung sofort mit, und dort feuert
`onChange`.

### Ranglisten und Überweisungen

```java
pantheon.players().query().sortBy(COINS, DESC).limit(10).fetch();     // /baltop
pantheon.players().rank(COINS, player.uniqueId());                    // eigener Platz
pantheon.players().transfer(COINS, from, to, amount);                 // /pay, als Transaktion
```

Details in [data.md §5](data.md#5-abfragen-und-ranglisten) und [data.md §6](data.md#6-geld-überweisen).

---

## 7. Lebenszyklus

```
 Proxy                     Dieser Server
   │
   │  Spieler verbindet sich
   │  ─► Proxy fragt Agora (ClaimPresence), ein Login über einen anderen Proxy wird gekickt;
   │     Agora verteilt die Presence an alle Server
   │
   │                       AsyncPlayerPreLoginEvent           (eigener Thread, darf blockieren)
   │                         ├─ Spielerdaten laden: ein Zugriff für alle Namespaces,
   │                         │    dieser Server wird Halter und bekommt fortan Änderungen anderer Server
   │                         │    Fehler oder Timeout (5 s) ─► Login abgelehnt, siehe §9
   │                         └─ PanPlayerLoadEvent (async): Plugins können Daten vorbereiten
   │
   │                       PlayerJoinEvent
   │                         └─ GARANTIE: Daten vollständig da, alles synchron
   │
   │                       Spielen
   │                         ├─ get: synchron, aus dem Speicher
   │                         ├─ set / inc / add / …: sofort lokal, atomar im Hintergrund
   │                         └─ withdraw / atomic: Future, die Datenbank entscheidet
   │
   │                       PlayerQuitEvent
   │                         ├─ Plugins machen letzte Änderungen (alle Prioritäten bis HIGHEST)
   │                         └─ Pantheon (MONITOR):
   │                              ├─ Audit-Traces des Spielers beenden (audit_log.md §4)
   │                              ├─ Session-Daten verwerfen
   │                              ├─ Halter abmelden, Daten aus dem Speicher entfernen
   │                              └─ noch laufende Operationen laufen im Hintergrund zu Ende
   │
   │  Spieler verlässt das Netzwerk
   │  ─► Proxy meldet Quit, Agora entfernt die Presence
```

Zwei Zusagen an Plugin-Entwickler:

1. **Ab `PlayerJoinEvent`** sind die Spielerdaten geladen. `get` liefert nie „noch nicht da“.
2. **Jede Änderung bis einschließlich `PlayerQuitEvent` (Priorität `HIGHEST`) wird gespeichert.**

---

## 8. Serverwechsel

Bei einem Wechsel von Server A nach B verbindet der Proxy den Spieler **zuerst** mit B und trennt ihn **danach** von
A. B lädt die Daten also, während der Spieler auf A vielleicht noch etwas kauft.

Dafür braucht es kein Übergabe-Protokoll. Mit dem Laden wird B Halter der Spielerdaten, solange A es noch ist. Jede
Änderung von A kommt über den Stream von Agora zuverlässig bei B an ([agora.md §5](agora.md#halter-wer-änderungen-bekommt)):

```
 A                                   Agora                                B
 │                                     │◄──── Pre-Login: laden ───────────┤  coins = 100
 │  Kauf: withdraw(coins, 30) ────────►│  coins = 70                      │
 │                                     ├──── Änderung: coins = 70 ───────►│  übernimmt 70, onChange feuert
 │  Quit                               │                                  │
```

Hat B in der Zwischenzeit selbst `inc(coins, 5)` ausgeführt, führt die Datenbank beide Operationen aus, und am Ende
stehen 75 da. Verloren geht nichts. B zeigt höchstens für ein paar Millisekunden einen alten Wert.

---

## 9. Fehlerfälle

| Fall                                      | Verhalten                                                                                   |
|-------------------------------------------|---------------------------------------------------------------------------------------------|
| Agora beim Login nicht erreichbar         | Login wird abgelehnt (`CommonMessage.DATA_UNAVAILABLE`). **Nie** mit leeren Daten reinlassen. |
| Schreiben schlägt fehl                    | Neuer Versuch mit Backoff und demselben Idempotency-Key, auch nach dem Quit. Danach lokale Outbox, nachgeholt bis 20 Stunden, auch über einen Neustart hinweg. |
| `withdraw` oder `atomic` ohne Agora       | Future scheitert sofort mit `DataUnavailableException`. Es wird nichts gekauft.              |
| Server stürzt ab                          | Verloren sind nur Requests, die gerade unterwegs waren und noch nicht in der Outbox standen, also Millisekunden. |
| Server wird gestoppt                      | Im `onDisable` von Pantheon: bis zu 10 s auf laufende Operationen warten, den Rest in die Outbox schreiben. |
| Plugin wird deaktiviert                   | Seine laufenden Operationen werden abgeschlossen, danach entfernt Pantheon alle `onChange`-Abos des Plugins (nach dessen `onDisable`). |
| Login wird nach dem Pre-Login abgebrochen | Der Node gibt den Spieler frei, Halter und Daten verschwinden. Das gilt auch, wenn 30 Sekunden kein Join kommt. |
| Agora lehnt einen einfachen Aufruf mit `UNAUTHENTICATED` ab | Das Future scheitert mit `DataUnavailableException`. |

---

## 10. Threads und Futures

### Was synchron ist

- Alles am `LocalPanPlayer`, auch `player.data()` lesen und schreiben.
- Alles am `PanPlayer` außer `location()`, dank Presence.
- Gecachte Stores.
- Senden von Nachrichten, auch an Spieler auf anderen Servern (Fire-and-forget).
- Schreiben auf nicht geladene Dokumente (`players().data(uuid)`, `store.doc(id)`). Muss man nicht abwarten.

### Was asynchron ist

| Methode                                   | Warum                                                    |
|-------------------------------------------|----------------------------------------------------------|
| `PanPlayer#location()`                    | Die Koordinaten kennt nur der Server des Spielers.       |
| `players().load(uuid)`, `store.load(id)`  | Die Daten liegen in der Datenbank.                       |
| Abfragen, Ranglisten, `rank`              | Laufen in der Datenbank.                                 |
| `withdraw`, `atomic`, `transfer`, `create` | Das Ergebnis (geklappt oder nicht) kennt erst die Datenbank. |

Wer ein Future bekommt und danach Bukkit-Objekte anfasst, kehrt so auf den Main-Thread zurück:

```java
friend.location().thenAcceptAsync(loc -> teleportTo(player, loc), pantheon.mainThread());
```

`pantheon.mainThread()` ist ein `Executor`. Für Nachrichten an einen `PanPlayer` braucht es keinen Wechsel, weil er
thread-sicher ist.

Pantheon loggt Fehler in seinen eigenen Abläufen, etwa beim Hintergrund-Schreiben. Was ein Plugin an ein Future hängt,
liegt in dessen Verantwortung: Ein `thenAccept` ohne `exceptionally` verschluckt Fehler, wie bei jedem
`CompletableFuture`.

**Kein Folia.** Pantheon setzt einen Main-Thread voraus. Folia-Unterstützung würde Region-Scheduler statt
`mainThread()` brauchen und ist für die erste Version nicht vorgesehen.

---

## 11. Betrachtete Ansätze

### Typen

| Ansatz                                                   | Vorteil                                         | Nachteil                                                    |
|----------------------------------------------------------|-------------------------------------------------|-------------------------------------------------------------|
| Ein Interface, alles Netzwerkfähige asynchron             | Einheitlich                                     | Futures auch für Spieler, die direkt hier stehen            |
| Ein Interface, synchron, Exception wenn nicht lokal (Megalodon) | Einfach                                   | Ob ein Aufruf klappt, sieht man erst zur Laufzeit           |
| **Zwei Typen plus Presence** (gewählt)                    | Der Compiler zeigt, was synchron geht; kaum Futures | Ein Typ mehr                                           |

### Speichern

| Ansatz                                                | Vorteil                              | Nachteil                                                 |
|-------------------------------------------------------|--------------------------------------|----------------------------------------------------------|
| Laden bei Join, Speichern bei Quit                    | Trivial                              | Verliert Daten beim Serverwechsel                        |
| Write-behind plus Ownership-Übergabe (Entwurf 1)      | Wenige Schreibzugriffe               | Leases, Übergabe-Protokoll, viel Logik                   |
| Ganze Werte mit Versionsprüfung (Entwurf 2)           | Korrekt                              | Wiederholungen, Funktionen ohne Nebenwirkungen nötig     |
| **Atomare Feld-Operationen** (gewählt)                | Einfach, keine Konflikte, Verlust nur Millisekunden | Agora erzeugt das SQL für JSONB selbst |

---

## 12. Unterschiede zu Megalodon

| Thema                     | Megalodon `GamePlayer`                                              | Pantheon                                                    |
|---------------------------|---------------------------------------------------------------------|-------------------------------------------------------------|
| Reichweite                | Nur dieser Server                                                   | Ganzes Netzwerk (`PanPlayer`), synchron per Presence        |
| Offline/Online            | `GameOfflinePlayer` / `GamePlayer`, `GamePlayerOfflineException`    | `PanPlayer` / `LocalPanPlayer`, Typ statt Exception         |
| API-Umfang                | Spiegelt ~40 Bukkit-Methoden (Health, Food, Exp, Gamemode …)        | `bukkit()` für alles, was Bukkit schon kann                 |
| Spielerdaten              | Generischer `Store` mit `JsonObject` und `getInt("…")`              | `player.data()` mit typisierten Feldern, atomare Operationen |
| Laden                     | Pro Store, `getAsync`/`getOrCreateAsync`                             | Einmal vor Join, garantiert vollständig                     |
| Ranglisten                | Über freie Store-Queries als String                                 | Typisierte Abfrage über Feld-Konstanten                     |
| Futures                   | Auch ohne I/O (`GameTeam#addPlayer`, `GameCountdown#start`)          | Nur bei echtem Netzwerkzugriff                              |
| Session-Zustand           | Eigene Maps je Plugin                                               | `SessionData`, beim Quit automatisch aufgeräumt             |

---

## 13. Entscheidungen und offene Fragen

### Festgelegt

- **Aufteilung in `PanPlayer` und `LocalPanPlayer`** statt eines Interfaces. Das ist die größte Änderung an den
  vorbereiteten Interfaces.
- **`hasPermission` nur am `LocalPanPlayer`.** Für Spieler auf anderen Servern müssten alle Berechtigungen in die
  Presence. Das bläht sie stark auf.
- **Methodennamen ohne `get`-Präfix** (`location()`, `uniqueId()`, `name()`), passend zu Adventure, zu Records und zur
  Language API. Booleans behalten `is`.
- **`ExpectedOnlinePlayerException`** (in den vorbereiteten Interfaces war der Name noch falsch geschrieben). Sie
  bleibt nur für einen `LocalPanPlayer`, der über den Quit hinaus festgehalten wird, also für einen Programmierfehler.
  `Pantheon#getPlayer` wirft laut Javadoc noch eine `IllegalStateException`. Mit `LocalPanPlayer` kann es gar nicht
  mehr scheitern.
- **`players().online(uuid)` liefert ein `Optional`** statt einer Exception, weil „offline“ ein normaler Fall ist.
  `isOnline(uuid)` entfällt.
- **Nachrichten an entfernte Spieler über den Proxy**, damit sie einen Serverwechsel überstehen.
- **Kein Folia** in der ersten Version.
- **`NetworkLocation` mit `double` statt `float`** für x, y und z. `float` hat nur etwa sieben Stellen: Bei x = 100.000
  ist die Auflösung nur noch etwa 0,008 Blöcke, bei 1.000.000 schon 0,06. Bukkits `Location` nutzt `double`. `yaw` und
  `pitch` bleiben `float`.
- **Login-Timeout 5 s** als Default, per Config änderbar.
- **`rank`** ist die Anzahl der Spieler mit größerem Wert + 1. Bei Gleichstand haben alle denselben Platz.
- **`onChange`-Listener laufen auf dem Main-Thread** ([data.md §4](data.md#gecachte-stores)).
- **Pre-Login schreibt `pantheon.name`.** Wird der Login abgebrochen (etwa weil ein anderes Plugin `PlayerLoginEvent`
  ablehnt) oder kommt innerhalb von 30 Sekunden kein Join, gibt der Node den Spieler frei (`release`). Sonst bliebe er
  für immer Halter.
- **Die Sprache in der Presence** kommt aus dem Login und wird in v1 nicht aktualisiert.
- **Gleichheit:** Alle `PanPlayer`-Sichten (`LocalPanPlayer`, entfernter Spieler) sind nach UUID gleich, symmetrisch
  und mit passendem `hashCode`. `local()` folgt der aktuellen Session des Spielers.
- **Der Login-Timeout** steht in `PANTHEON_LOGIN_TIMEOUT_MS` ([pantheon.md](pantheon.md#agora-verbindung)).
- **`location()`** fragt Agora (`Locate`, höchstens 3 s). Ist der Spieler offline, ist er „nicht online“, antwortet
  sein Server nicht, scheitert das Future mit `ExpectedOnlinePlayerException(NETWORK)`
  ([agora.md §4](agora.md#position-eines-spielers)).
