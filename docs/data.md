# Pantheon Data API

> Status: **Umgesetzt.** Agora, den Dienst dahinter, beschreibt [agora.md](agora.md).

Änderungen sind **atomare Feld-Operationen**, die [Agora](agora.md) in der Datenbank ausführt. Es gibt
kein SQL, kein `save()`, und es liegt nur im Speicher, was gerade gebraucht wird.

```java
public static final Namespace ECONOMY = Namespace.of("economy");
public static final Field<Long> COINS = ECONOMY.ofLong("coins", 0L);

Doc data = player.data();
long coins = data.get(COINS);          // synchron
data.inc(COINS, 50);                   // atomar in der Datenbank, ohne Warten

pantheon.players().query().sortBy(COINS, DESC).limit(10).fetch();   // Rangliste
```

---

## Inhalt

1. [Grundidee](#1-grundidee)
2. [Felder deklarieren](#2-felder-deklarieren)
3. [Mit Dokumenten arbeiten](#3-mit-dokumenten-arbeiten)
4. [Stores für alles außer Spielern](#4-stores-für-alles-außer-spielern)
5. [Abfragen und Ranglisten](#5-abfragen-und-ranglisten)
6. [Geld überweisen](#6-geld-überweisen)
7. [Felder ändern sich](#7-felder-ändern-sich)
8. [Backend](#8-backend)
9. [Betrachtete Ansätze](#9-betrachtete-ansätze)
10. [Unterschiede zu Megalodon](#10-unterschiede-zu-megalodon)
11. [Entscheidungen und offene Fragen](#11-entscheidungen-und-offene-fragen)

---

## 1. Grundidee

| Begriff     | Bedeutung                                                                                   |
|-------------|---------------------------------------------------------------------------------------------|
| `DataNamespace` | Ein Bereich für Daten, z. B. `economy`. Gehört einem Plugin, unabhängig von dessen Namen.   |
| `Field<T>`  | Ein typisiertes Feld in einem Namespace, einmal als Konstante deklariert, z. B. `COINS`.    |
| `Doc`       | Ein Dokument: eine ID und Werte für beliebige Felder.                                       |
| `Store`     | Eine Sammlung von Dokumenten in einem Namespace, z. B. `lobby:clans`.                       |

Es gibt zwei Orte für Daten:

- **Spielerdaten** hängen am Spieler: `player.data()`. Pantheon lädt sie vor dem Join und hält sie, solange der Spieler
  auf dem Server ist. Lesen ist synchron. Details in [player.md §6](player.md#6-eigene-spielerdaten).
- **Alles andere** liegt in Stores: `pantheon.store(LOBBY, "clans")`. Dokumente werden bei Bedarf geladen. Kleine
  Stores können komplett im Speicher gehalten werden ([§4](#4-stores-für-alles-außer-spielern)).

**Im Speicher liegt nur:**
- die Spielerdaten der Spieler auf diesem Server,
- Dokumente, die man gerade geladen hat,
- Stores, die man ausdrücklich als `cached()` markiert hat.

---

## 2. Felder deklarieren

Felder werden einmal als Konstanten deklariert, wie Message-Keys. Jedes Feld gehört zu einem Namespace und hat einen
Typ und einen Default.

```java
public final class LobbyData {
    public static final Namespace LOBBY = Namespace.of("lobby");

    public static final Field<Integer>     LEVEL      = LOBBY.ofInt("level", 1);
    public static final Field<Long>        BEST_TIME  = LOBBY.ofLong("best_time", Long.MAX_VALUE);
    public static final Field<Boolean>     SCOREBOARD = LOBBY.ofBoolean("scoreboard", true);
    public static final Field<Set<String>> KITS       = LOBBY.ofSet("kits", String.class);
    public static final Field<List<UUID>>  FRIENDS    = LOBBY.ofList("friends", UUID.class);
    public static final Field<Instant>     LAST_VOTE  = LOBBY.ofInstant("last_vote");        // Default: null
    public static final Field<Home>        HOME       = LOBBY.of("home", Home.class);        // Objekt als Feldwert
}
```

- **Feld- und Namespace-Namen** bestehen aus `[a-z0-9_]`. Feldnamen sind pro Namespace eindeutig.
- **Default:** `get` liefert nie `null`, solange ein Default angegeben ist. Felder ohne Default (`ofInstant`, `of`)
  liefern `null` bzw. ein leeres `Optional` über `find`.
- **Objekte als Feldwert:** Ein Record kann ein ganzes Feld sein, etwa ein `Home` mit Welt und Koordinaten. Er wird
  als Ganzes gesetzt (`set`), nicht teilweise geändert.

```java
public record Home(String world, double x, double y, double z, float yaw, float pitch) {}
```

### Namespaces

Ein Namespace ist ein fester Name, **nicht** der Plugin-Name. Wird ein Plugin umbenannt, bleiben seine Daten erhalten.
Namespaces sind im ganzen Netzwerk eindeutig.

**Gemeinsame Daten.** Ein Namespace gehört dem Plugin, das ihn deklariert. Andere Plugins benutzen dessen Konstanten,
die es über sein API-Modul exportiert:

```java
// economy-api
public final class EconomyData {
    public static final Namespace ECONOMY = Namespace.of("economy");
    public static final Field<Long> COINS = ECONOMY.ofLong("coins", 0L);
}

// Shop-Plugin, hängt von economy-api ab
player.data().get(EconomyData.COINS);
```

Weil jedes Feld seinen Namespace kennt, umfasst `player.data()` alle Namespaces. Ein Feld, zwei Plugins, keine
Absprache über Strings.

**Schreiben in fremde Namespaces** ist in der ersten Version erlaubt und nur Konvention: Geschrieben wird über die
API des besitzenden Plugins, gelesen direkt. Der Namespace macht eine spätere Rechteprüfung in Agora möglich.

---

## 3. Mit Dokumenten arbeiten

```java
public interface Doc {

    String id();

    // Lesen: synchron, aus dem Speicher
    <T> T get(Field<T> field);
    <T> Optional<T> find(Field<T> field);
    boolean has(Field<?> field);

    // Unbedingte Operationen: wirken sofort lokal, werden im Hintergrund atomar ausgeführt
    <T> void set(Field<T> field, T value);
    void unset(Field<?> field);
    void inc(Field<? extends Number> field, long delta);
    <T extends Comparable<T>> void max(Field<T> field, T value);   // Highscore: nur wenn größer
    <T extends Comparable<T>> void min(Field<T> field, T value);   // Bestzeit: nur wenn kleiner
    <E> void add(Field<? extends Collection<E>> field, E element);
    <E> void remove(Field<? extends Collection<E>> field, E element);

    // Bedingte Operationen: die Datenbank entscheidet, das Ergebnis kommt als Future
    CompletableFuture<Boolean> withdraw(Field<Long> field, long amount);    // nur wenn genug da ist
    Atomic atomic();
}

public interface Atomic {
    Atomic withdraw(Field<Long> field, long amount);                        // Bedingung: genug da
    <T> Atomic require(Field<T> field, Condition<T> condition);             // weitere Bedingung
    <T> Atomic set(Field<T> field, T value);
    Atomic unset(Field<?> field);
    Atomic inc(Field<? extends Number> field, long delta);
    <E> Atomic add(Field<? extends Collection<E>> field, E element);
    <E> Atomic remove(Field<? extends Collection<E>> field, E element);
    CompletableFuture<Boolean> commit();     // true: alles ausgeführt, false: eine Bedingung war nicht erfüllt
}
```

### Warum atomare Feld-Operationen

Megalodon überschreibt beim `save()` das ganze Dokument. Schreiben zwei Server gleichzeitig, gewinnt der letzte, und
die Änderung des anderen ist weg.

Bei Pantheon geht nicht der neue Wert an die Datenbank, sondern die **Operation**: „erhöhe `coins` um 50“, „füge
`warrior` zu `kits` hinzu“. Die Datenbank führt sie atomar aus. Zwei Server, die gleichzeitig Münzen gutschreiben,
landen beide korrekt im Ergebnis, ganz ohne Versionsnummern oder Wiederholungen.

| Operation          | In der Datenbank                                      | Typischer Einsatz                     |
|--------------------|-------------------------------------------------------|---------------------------------------|
| `set`              | Feld überschreiben                                    | Einstellungen, Home, Rang             |
| `inc`              | Addieren                                              | Geld gutschreiben, Kills, Spielzeit   |
| `max` / `min`      | Nur setzen, wenn größer bzw. kleiner                  | Highscore, Bestzeit                   |
| `add` / `remove`   | Element zu einer Liste oder Menge hinzufügen/entfernen | Freigeschaltete Kits, Freunde        |
| `withdraw`         | Abziehen, nur wenn der Wert danach ≥ 0 ist            | Bezahlen                              |
| `atomic`           | Mehrere Operationen, alle oder keine, mit Bedingungen | Kaufen                                |

### Bedingte Operationen

Ob ein Spieler genug Geld hat, entscheidet **die Datenbank**, nicht der Wert im Speicher. Der kann veraltet sein,
etwa wenn ein Admin auf einem anderen Server gerade Geld abgezogen hat. Deshalb liefern `withdraw` und `atomic` ein
Future, und ihre Operationen wirken lokal erst, wenn die Datenbank sie bestätigt hat.

**Kaufen** heißt: abbuchen und freischalten, beides oder nichts.

```java
data.atomic()
    .withdraw(EconomyData.COINS, kit.price())
    .require(KITS, not(contains(kit.id())))          // nicht doppelt kaufen, auch bei Doppelklick
    .add(KITS, kit.id())
    .commit()
    .thenAcceptAsync(bought -> {
        if (bought) {
            player.sendMessage(ShopMessage.BOUGHT, arg("kit", kit.name()));
        } else {
            player.sendMessage(ShopMessage.NOT_POSSIBLE);
        }
    }, pantheon.mainThread());
```

- Eine atomare Gruppe betrifft **ein Dokument**. Bei Spielerdaten darf sie mehrere Namespaces umfassen, wie hier
  `economy` und `lobby`.
- Die Antwort kommt nach wenigen Millisekunden. Bis dahin zeigt `get` den alten Wert.
- Ist Agora nicht erreichbar, scheitert das Future mit `DataUnavailableException`. Gekauft wird dann nichts.

### Semantik der Operationen

- `set(field, null)` ist `unset`. Der Wert wird als fehlend gespeichert, nie als `null`.
- `add` fügt bei einem `Set` nur ein, was fehlt, bei einer `List` hängt es an. `remove` entfernt alle Vorkommen.
- `inc` auf einem fehlenden Feld ohne Default startet bei 0. Läuft die Summe über `long` hinaus, wirft Pantheon eine
  `IllegalArgumentException`.
- `Instant` wird als Epoch-Millis gespeichert. Ein `int`-Feld wird beim Lesen auf den int-Bereich begrenzt.
- `withdraw` gibt es **nur** in bedingten Anfragen. Mehrere additive Operationen auf dasselbe Feld prüft die Datenbank
  zusammen gegen den gespeicherten Wert (`inc(5)`, `withdraw(3)` auf einem fehlenden Feld ist erlaubt). `withdraw`
  nach einem `set` auf dasselbe Feld in derselben Gruppe ist nicht erlaubt.
- Abgelaufene, noch nicht gelöschte Dokumente gelten bei Schreibzugriffen als nicht vorhanden: `create` gelingt,
  `delete` liefert `false`.
- Jeder schreibende Aufruf ist idempotent ([agora.md §5](agora.md#idempotenz)).

### Schreiben ohne Warten, Fehler ohne Schweigen

Unbedingte Operationen sind `void`. Pantheon führt sie im Hintergrund aus:

- **Reihenfolge:** Pro Dokument ist höchstens ein Request unterwegs. Was währenddessen aufgerufen wird, sammelt
  Pantheon und schickt es als nächsten Request. So kommen Operationen in Aufrufreihenfolge an, und viele Operationen
  kurz hintereinander, etwa `inc` pro abgebautem Block, werden zu einem Zugriff.
- **Wiederholung:** Bei Verbindungsproblemen wiederholt Pantheon einen Request mit Backoff. Scheitert er, kommt er in
  eine **lokale Outbox** (SQLite) und übersteht so auch einen Neustart des Servers
  ([agora.md §8](agora.md#lokale-outbox)). Ein Idempotency-Key sorgt dafür, dass er nie doppelt ausgeführt wird
  ([agora.md §5](agora.md#idempotenz)).
- **Stoppen:** Beim Stoppen des Servers kommt alles, was nach 10 Sekunden noch nicht bestätigt ist, in die Outbox.
  Die Reihenfolge, in der Agora und die Server herunterfahren, spielt deshalb keine Rolle.
- **Abgelehnt:** Verstößt ein unbedingtes `set` gegen `unique`, wiederholt Pantheon es nicht. Es loggt einen `ERROR`
  und setzt den lokalen Wert zurück. Ein gebündelter Request mit mehreren Aufrufen wird dazu aufgeteilt, nur der
  schuldige Aufruf wird verworfen.
- **Aufgeben:** Ein Request, der nach 20 Stunden noch nicht angekommen ist, wird nicht mehr geschickt. Pantheon loggt
  einen Fehler mit den vollständigen Operationen, damit ein Admin sie nachtragen kann.
- **Absturz:** Verloren gehen nur Requests, die gerade unterwegs waren und noch nicht in der Outbox standen, also
  Millisekunden.

---

## 4. Stores für alles außer Spielern

```java
Store clans = pantheon.store(LOBBY, "clans");                   // Dokumente bei Bedarf laden
CachedStore warps = pantheon.store(LOBBY, "warps").cached();    // komplett im Speicher, synchron
```

Das legt man einmal im `onEnable` an und behält es als Feld. Felder eines Store-Dokuments stammen aus dem Namespace
des Stores, sonst wirft Pantheon eine `IllegalArgumentException`.

```java
public interface Store {

    CompletableFuture<Optional<Doc>> load(String id);
    CompletableFuture<Doc> loadOrCreate(String id);
    CompletableFuture<Boolean> create(String id, Consumer<Doc> init);   // false, wenn es die ID schon gibt
    RemoteDoc doc(String id);                                           // schreiben, ohne vorher zu laden
    CompletableFuture<Boolean> delete(String id);
    void expire(String id, Instant at);                                 // automatisch löschen

    Query query();
    CompletableFuture<Void> index(Field<?>... fields);                  // schneller suchen und sortieren
    CompletableFuture<Void> unique(Field<?> field);                     // Wert darf nur einmal vorkommen

    CachedStore cached();                                               // komplett im Speicher, synchron

    <T> Subscription onChange(Field<T> field, FieldListener<T> listener);

    CompletableFuture<Boolean> transfer(Field<Long> field, String fromId, String toId, long amount);
}

public interface CachedStore extends Store {
    Optional<Doc> get(String id);                                       // synchron
    Collection<Doc> all();                                              // synchron
}
```

### Ein Dokument laden und ändern

```java
clans.load(clanName).thenAccept(found -> found.ifPresentOrElse(
    clan -> {
        clan.add(MEMBERS, player.uniqueId());
        player.sendMessage(ClanMessage.JOINED, arg("clan", clanName));
    },
    () -> player.sendMessage(ClanMessage.NOT_FOUND)));
```

**Reihenfolge der Aufrufe.** `create`, `delete` und `expire` laufen wie Feld-Operationen durch die Warteschlange des
Dokuments und werden nach den davor aufgerufenen Operationen ausgeführt. `create` bleibt bedingt: Sein Ergebnis
bekommt der Aufrufer, aber erst nach den davor eingereihten Operationen. Ein `expire`, das scheitert, wird nur
geloggt und kommt nicht in die Outbox.

Ein geladenes `Doc` ist ein Schnappschuss. Eigene Operationen aktualisieren ihn, Änderungen anderer Server nicht. Wer
ein Dokument länger hält und aktuell braucht, nimmt `cached()` oder hört auf `onChange`.

### Schreiben, ohne zu laden

Oft muss man nichts lesen, nur ändern. `doc(id)` liefert ein `RemoteDoc` mit denselben Operationen, auch `withdraw`
und `atomic`, nur ohne Lesen:

```java
clans.doc(clanName).inc(CLAN_XP, 25);
```

### Regeln in der Datenbank absichern

Was wirklich stimmen muss, sichert die Datenbank ab, nicht ein Check im Code:

```java
clans.unique(OWNER);                                     // höchstens ein Clan pro Spieler

clans.create(name, clan -> {
    clan.set(OWNER, player.uniqueId());
    clan.set(MEMBERS, Set.of(player.uniqueId()));
}).thenAccept(created -> player.sendMessage(created ? ClanMessage.CREATED : ClanMessage.NAME_TAKEN));
```

`create` ist atomar: Von zwei gleichzeitigen Versuchen mit demselben Namen gewinnt genau einer. Ein Verstoß gegen
`unique` lässt das Future mit einer `DuplicateValueException` scheitern. `unique` gilt nur für **ausdrücklich gesetzte**
Werte: Dokumente, die das Feld nicht gesetzt haben (und nur den Default sehen), kollidieren nie miteinander.

### Indizes sind Deklarationen

`index` und `unique` legen nichts sofort an. Sie **melden** Agora, welcher Index gebraucht wird. Agora legt
ihn einmal an, ohne die Tabelle zu sperren, und ignoriert weitere Meldungen desselben Index
([agora.md §5](agora.md#indizes)). Das Future ist fertig, wenn der Index bereit ist. Bei `unique` scheitert es mit
`DuplicateValueException`, wenn schon doppelte Werte existieren. Ein gescheiterter Index wird bei der nächsten
Deklaration erneut versucht. Ändert sich die Definition unter demselben Namen, baut Agora ihn neu.

Die Indexform ist für die Rangliste gedacht (`sortBy(…, DESC)`, [§5](#ranglisten)): absteigend, `NULLS LAST`, mit der
ID bzw. der Spieler-UUID als Tie-Break. Aufsteigende Sortierungen sind nicht indexgestützt.

Ein Index gehört dem Plugin, dem der Namespace gehört. Wer `EconomyData.COINS` nur liest, deklariert keinen Index
darauf.

### Gecachte Stores

Für kleine Datenmengen, die ständig gelesen werden (Warps, Kits, Shop-Angebote, Event-Konfiguration):

```java
CachedStore warps = pantheon.store(LOBBY, "warps").cached();
warps.get("spawn").map(w -> w.get(LOCATION)).ifPresent(player::teleport);
```

Pantheon lädt den Store beim Aktivieren des Plugins und hält ihn über alle Server live aktuell. Faustregel: bis
einige tausend Dokumente.

- `cached()` lädt **blockierend**, höchstens 10 Sekunden. Scheitert das, startet der Store leer, Pantheon loggt einen
  `ERROR` und lädt nach dem Reconnect neu.
- Nach **jedem** Reconnect lädt Pantheon gecachte Stores komplett neu, sonst blieben Dokumente, die während des
  Ausfalls gelöscht oder angelegt wurden, unsichtbar.

**`onChange` und ungecachte Stores.** Bei einem Store ohne `cached()` ist `before` im Listener best-effort: der zuletzt
auf diesem Node gesehene Wert, sonst der Default. Das gilt auch nach dem Verdrängen aus dem Speicher (der Store
behält die Versionen größer als die Dokumente selbst, damit ein altes Event nicht noch einmal zählt).

**Listener** (`onChange`, auch bei Spielern) laufen auf dem **Haupt-Thread**, denn sie fassen typischerweise Bukkit an,
etwa das Scoreboard.

---

## 5. Abfragen und Ranglisten

Abfragen benutzen dieselben Feld-Konstanten. Sie laufen in der Datenbank und liefern deshalb ein Future.

```java
clans.query().where(MEMBERS, contains(player.uniqueId())).first();          // CompletableFuture<Optional<Doc>>

clans.query()
    .where(LEVEL, gte(10))
    .sortBy(LEVEL, DESC)
    .limit(20)
    .fetch();                                                                // CompletableFuture<List<Doc>>
```

| Bedingung                         | Bedeutung                                  |
|-----------------------------------|--------------------------------------------|
| `eq`, `ne`                        | gleich, ungleich                           |
| `gt`, `gte`, `lt`, `lte`          | größer, größer gleich, kleiner, kleiner gleich |
| `contains`                        | Liste oder Menge enthält Element           |
| `in`                              | Wert ist einer von mehreren                |
| `exists`                          | Feld ist gesetzt                           |
| `not`                             | Verneinung einer Bedingung                 |

Dieselben Bedingungen gelten für `require` in atomaren Gruppen. Fehlt ein Feld in einem Dokument, rechnet die
Datenbank mit dem Default aus der Deklaration. Genauer ([agora.md §5](agora.md#semantik-von-operationen-und-bedingungen)):

- Vergleiche mit einem fehlenden Wert sind `false`, `not(p)` heißt „p ist nicht wahr“. Eine Bedingung ist nie
  „unbekannt“.
- `contains` prüft Gleichheit mit einem Element (tief, auch bei verschachtelten Werten), keine Teilmenge.
- `eq`, `ne` und `in` auf einem `Set`-Feld vergleichen unabhängig von der Reihenfolge, bei `List` zählt sie.
- `limit`: Default 100, Maximum 1000. Sortierung: `NULLS LAST`, Strings binär.
- Bedingung oder Sortierung auf ein Feld, das nicht zum Namespace des Stores gehört, wird abgelehnt. Mehr als diese Bedingungen, Sortieren und `limit` gibt es bewusst
nicht.

### Ranglisten

Eine Rangliste ist eine Abfrage über die Spielerdaten:

```java
pantheon.players().index(COINS);                         // einmal im onEnable des Economy-Plugins

pantheon.players().query().sortBy(COINS, DESC).limit(10).fetch()
    .thenAccept(top -> baltop.show(viewer, top));

pantheon.players().rank(COINS, player.uniqueId())        // CompletableFuture<Long>, Platz 1 = reichster Spieler
    .thenAccept(rank -> player.sendMessage(EcoMessage.YOUR_RANK, arg("rank", rank)));
```

Der Rang ist die Anzahl der Spieler mit einem größeren Wert plus 1. Bei Gleichstand haben alle denselben Platz.

Jedes Spielerdokument enthält den zuletzt bekannten Namen (`PlayerFields.NAME`). Ranglisten zeigen deshalb auch
Offline-Spieler mit Namen an. Für ein Hologramm, das alle 30 Sekunden aktualisiert wird, reicht ein Timer, der die
Abfrage neu startet.

---

## 6. Geld überweisen

Eine Überweisung betrifft zwei Dokumente und muss trotzdem ganz oder gar nicht passieren:

```java
pantheon.players().transfer(COINS, sender.uniqueId(), target, amount)      // CompletableFuture<Boolean>
    .thenAcceptAsync(ok -> sender.sendMessage(ok ? PayMessage.SENT : PayMessage.TOO_POOR,
        arg("amount", amount)), pantheon.mainThread());
```

`transfer` zieht beim Sender ab, wenn genug da ist, und schreibt beim Empfänger gut. Beides passiert in einer
Datenbank-Transaktion, ein Absturz dazwischen ist ausgeschlossen. Ist einer der beiden online, aktualisiert Pantheon
seinen Wert im Speicher. Stores haben dieselbe Methode (`Store#transfer(field, fromId, toId, amount)`), etwa für eine
Clan-Kasse. Ein fehlendes Dokument legt `transfer` beim Empfänger an.

Allgemeine Transaktionen über beliebige Dokumente sind in der ersten Version nicht vorgesehen. `transfer` deckt den
häufigsten Fall ab, `atomic` alles innerhalb eines Dokuments.

---

## 7. Felder ändern sich

| Änderung                    | Was zu tun ist                                                                    |
|-----------------------------|-----------------------------------------------------------------------------------|
| Feld hinzufügen             | Nichts. Fehlt es in alten Dokumenten, liefert `get` den Default.                  |
| Feld nicht mehr benutzen    | Nichts. Der alte Wert bleibt in der Datenbank liegen und stört nicht.             |
| Feld umbenennen             | `LOBBY.ofLong("coins", 0L).readFrom("money")`: liest den alten Namen, solange der neue fehlt. |
| Typ eines Felds ändern      | Neues Feld mit neuem Namen anlegen.                                               |
| Default eines Felds ändern  | Neues Feld mit neuem Namen anlegen. Alte Dokumente würden sonst plötzlich anders rechnen, und Indizes passen nicht mehr. |

Es gibt keine Migrationsdateien und keine Schema-Versionen.

---

## 8. Backend

Pantheon spricht mit **Agora**, einem eigenen Microservice, der Daten, Presence und Zustellung von
Nachrichten verwaltet. Plugins sehen davon nichts: Kein Plugin schreibt SQL, keines pflegt Migrationen, und auf den
Servern liegen keine Datenbank-Zugangsdaten.

Für Tests und lokale Entwicklung gibt es das `InMemoryNetwork` (`PANTHEON_BACKEND=memory`). Eine gemeinsame Contract-Test-Suite stellt sicher, dass
es sich wie Agora verhält.

Protokoll, Tabellen, Live-Updates und Ausfallverhalten stehen in [agora.md](agora.md).

---

## 9. Betrachtete Ansätze

### Datenmodell

| Ansatz                                                        | Vorteil                                            | Nachteil                                              |
|---------------------------------------------------------------|----------------------------------------------------|-------------------------------------------------------|
| SQL mit Flyway, eigene Repositories (Totorix)                 | Volle Kontrolle, Constraints, Transaktionen         | SQL, Mapping und Migrationen in jedem Plugin          |
| Dokumente in MongoDB                                          | Feld-Operationen und Live-Updates eingebaut         | Zusätzliche Datenbank neben dem bestehenden Postgres  |
| Freie Dokumente mit `set("feld")` (Megalodon)                 | Schnell losgelegt                                   | Strings statt Typen, ganzes Dokument überschrieben, blockiert |
| Records, alles im Speicher (Entwurf 2)                        | Lesen immer synchron                                | Ganzer Zustand im Speicher, keine echten Abfragen     |
| **Dokumente mit typisierten Feldern und atomaren Operationen** (gewählt) | Bedienung wie Megalodon, typsicher, keine Konflikte, Abfragen und Transaktionen in Postgres | Agora muss Live-Updates und `expire` selbst bauen |

### Backend-Architektur

| Ansatz                                                        | Vorteil                                            | Nachteil                                              |
|---------------------------------------------------------------|----------------------------------------------------|-------------------------------------------------------|
| Server direkt an Postgres, Live-Updates über Änderungstabelle plus `NOTIFY` | Kein zusätzlicher Dienst             | Sequenzlücken, globaler Lock bei `NOTIFY`, Listener-Verbindung vorbei an PgBouncer, Zugangsdaten auf jedem Server |
| Server direkt an Postgres, Versions-Events über Redis Pub/Sub | Wenig Infrastruktur                                 | Zugangsdaten, PgBouncer-Sonderfälle, Idempotenz und Migrationen auf jedem Server, Events at-most-once |
| Datenschicht als Modul in einem bestehenden Dienst (Olymp)    | Kein neues Deployable                               | Koppelt Pantheon an dessen Releases und Ausfälle      |
| **Eigener Microservice für Daten und Presence: Agora** (festgelegt) | Ein Ort für SQL, Idempotenz, Presence und Zustellung. Zuverlässige Live-Updates über den Stream, keine Zugangsdaten auf den Servern, kein Redis | Zusätzliches Deployable, eine Instanz, Schreiben mit Bedingung braucht Agora |

---

## 10. Unterschiede zu Megalodon

| Thema            | Megalodon `Store`                                    | Pantheon                                                    |
|------------------|------------------------------------------------------|-------------------------------------------------------------|
| Felder           | Strings: `getInt("coins")`                           | Konstanten mit Namespace: `get(COINS)`                      |
| Schreiben        | `set` plus `save()`, ganzes Dokument, blockierend    | Atomare Feld-Operationen, sofort im Hintergrund             |
| Gleichzeitiges Schreiben | Letzter gewinnt, Änderungen gehen verloren   | Die Datenbank führt beide Operationen aus                   |
| Lesen            | Synchron, blockiert aber heimlich auf I/O            | Synchron nur aus dem Speicher, sonst Future                 |
| Abfragen         | Mongo-JSON als String                                | Typisierte Bedingungen über Feld-Konstanten                 |
| Zahlen           | Alle als `double`                                    | `long` bleibt `long`                                        |
| Regeln in der DB | Keine                                                | `unique`, atomares `create`, `withdraw`, `atomic`, `transfer` |
| Tests            | Nur gegen das echte Backend                          | `InMemoryNetwork` mit Contract-Tests                        |

Aus Megalodon übernommen: Dokumente mit Feldern, Stores mit Namespace, `expire` mit Zeitpunkt und Live-Updates.

---

## 11. Entscheidungen und offene Fragen

### Festgelegt

- **Postgres mit JSONB für Daten.**
- **Eigener Microservice Agora** für Daten und Presence ([agora.md](agora.md)).
- **Namespaces als feste Namen**, unabhängig vom Plugin-Namen. Felder kennen ihren Namespace, `player.data()` umfasst
  alle.
- **Gemeinsame Daten über exportierte Konstanten**, Schreibrechte in der ersten Version nur per Konvention.
- **`withdraw` und `atomic` liefern ein Future** und wirken lokal erst nach Bestätigung. Unbedingte Operationen
  bleiben synchron.
- **`require` mit denselben Bedingungen wie Abfragen**, plus `not`.
- **Lokale Outbox in SQLite** für Schreibzugriffe, die nicht sofort bestätigt werden. Nachgeholt bis 20 Stunden,
  danach Fehler-Log mit den Operationen.
- **Indizes als Deklaration**, angelegt von Agora.
- **Ein geänderter Default ist ein neues Feld.**
- **Eine Zeile pro Spieler und Namespace,** geladen mit einem einzigen `SELECT` beim Login.
- **`transfer` als einzige Transaktion über zwei Dokumente** in der ersten Version.
- **Keine Migrationen**, nur `readFrom` für umbenannte Felder.
- **Rang** = Anzahl der Spieler mit größerem Wert + 1, Gleichstand ergibt denselben Platz.
- **Semantik:** `set(null)` ≡ `unset`, `add` bei `Set` nur Fehlendes, `remove` alle Vorkommen. Vergleiche mit fehlenden
  Werten sind `false`, Sortierung `NULLS LAST`, Strings binär. `contains` ist Element-Gleichheit, `eq`/`ne`/`in` auf
  `Set` reihenfolgeunabhängig. `Instant` als Epoch-Millis. `inc` ohne Default startet bei 0.
- **`onChange`-Listener laufen auf dem Haupt-Thread.**
- **`cached()`** lädt blockierend (höchstens 10 s), startet bei Fehlern leer und lädt nach jedem Reconnect komplett neu.
  `onChange` hat bei ungecachten Stores einen best-effort-`before`.
- **`Store#transfer`** gehört zur API. Fehler bei `expire` werden nur geloggt, nicht in die Outbox geschrieben.
- **`unique` nur für gesetzte Werte**, Indizes mit Tie-Break und `DESC NULLS LAST`.
- **Reihenfolge:** `create`, `delete` und `expire` laufen durch die Dokument-Warteschlange.

### Offen

- **Eigene Idempotency-Keys für Plugins?** Für Vorgänge mit eigener ID, etwa eine Shop-Lieferung, wäre ein Key aus
  dieser ID robuster als einer, den Pantheon erzeugt. Erst bauen, wenn der erste Fall auftaucht.
