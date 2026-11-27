# Pantheon Audit-Log

> Status: **Umgesetzt.** Baut auf [player.md](player.md) und [agora.md](agora.md)
> (Dienst, Postgres) auf. Umsetzung: [Plan](superpowers/plans/2026-11-27-pantheon-audit.md).

Das Audit-Log hält fest, was ein Spieler im Netzwerk getan hat: was er geschrieben hat, welche Commands er ausgeführt
hat, wann er wo war und was Plugins über ihn melden. Später zeigt ein Webinterface oder ein Command daraus eine
**Zeitachse pro Spieler**. Einträge werden **14 Tage** aufbewahrt.

```java
public static final AuditNamespace SHOP = AuditNamespace.of("shop");
public static final AuditAction SHOP_BUY = SHOP.action("buy");                  // → 'shop:buy'

player.audit().action(SHOP_BUY, d -> d
    .location(npc.getLocation())
    .with("item", "diamond_sword")
    .with("price", 250));
```

Chat, Commands, Join, Quit und Serverwechsel loggt Pantheon selbst. Plugins loggen nur, was nur sie wissen.

### Voraussetzungen und Umfang

Das Audit-Log setzt auf dem Stand aus [`docs/superpowers/plans/2026-11-27-pantheon-agora.md`](superpowers/plans/2026-11-27-pantheon-agora.md)
auf: Agora als eigenes Repo `agora`, in Pantheon die Module `api`, `core`, `agora-client`, `paper` und `velocity`,
dazu Presence und die Node-Verbindung. Es wird erst gebaut, wenn diese stehen.

| Teil                                                       | Wo                       |
|------------------------------------------------------------|--------------------------|
| `AuditAction`, `AuditLog`, `AuditDescriptor`, `AuditActions` | `api`                  |
| Puffer, Versand, `AuditBackend`, In-Memory-Variante        | `core`                   |
| Proto, `AppendAudit`/`Timeline`-Client                     | `agora-client` / `agora` |
| Listener für Chat und Commands, versteckte Commands        | `paper`                  |
| `AppendAudit`, `Timeline`, Session-Einträge, Partitionen, Migration | `agora`         |
| Privatnachrichten loggen, `/msg` und `/r` verstecken       | Nachrichten-Plugin       |

**Nicht in der ersten Version:** `audit()` für Velocity-Plugins (der Plan schließt eine Pantheon-API für
Velocity-Plugins insgesamt aus), Webinterface und Command zum Lesen ([§10](#10-die-zeitachse-lesen)).

---

## Inhalt

1. [Leitlinien](#1-leitlinien)
2. [Ein Eintrag](#2-ein-eintrag)
3. [Aktionen deklarieren](#3-aktionen-deklarieren)
4. [Aus Plugins loggen](#4-aus-plugins-loggen)
5. [Was Pantheon selbst loggt](#5-was-pantheon-selbst-loggt)
6. [Versteckte Commands](#6-versteckte-commands)
7. [Puffer und Versand](#7-puffer-und-versand)
8. [Agora](#8-agora)
9. [Postgres](#9-postgres)
10. [Die Zeitachse lesen](#10-die-zeitachse-lesen)
11. [Datenschutz](#11-datenschutz)
12. [Betrachtete Ansätze](#12-betrachtete-ansätze)
13. [Entscheidungen und offene Fragen](#13-entscheidungen-und-offene-fragen)

---

## 1. Leitlinien

- **Loggen kostet nichts.** `audit().action(...)` blockiert nie, liefert kein Future und darf von jedem Thread
  aufgerufen werden. Der Eintrag landet in einem Puffer im Speicher, alles Weitere passiert im Hintergrund.
- **Fire-and-forget.** Kein Spieler wartet je auf das Audit-Log. Dafür darf bei einem Ausfall etwas verloren gehen.
  Es gibt **keine Outbox** wie bei den Spielerdaten.
- **Ein Eintrag gehört einem Spieler.** Entscheidend sind UUID und Zeitpunkt. Welches Plugin geloggt hat, wird nicht
  gespeichert.
- **Aktionen sind deklariert.** Jede Aktion ist eine Konstante in einem `AuditNamespace`, im selben Muster wie
  `Field` und `MessageKey`. So bleiben die Namen über Jahre einheitlich und filterbar.
- **Freie Schlüssel sind Zusatz.** Was zu einer Aktion dazugehört (Item, Preis, Grund), steht frei als Schlüssel und
  Wert daneben.
- **Gelesen wird pro Spieler.** Die einzige Abfrage ist „Zeitachse von Spieler X im Zeitraum Y“. Eine Suche über alle
  Spieler gibt es bewusst nicht ([§12](#suche)).

---

## 2. Ein Eintrag

| Feld       | Inhalt                                                          | Gesetzt von                       |
|------------|-----------------------------------------------------------------|-----------------------------------|
| `id`       | UUIDv7, auf dem Node erzeugt. Enthält den Zeitpunkt in Millisekunden, es gibt kein eigenes Zeitfeld | Pantheon |
| `player`   | UUID des Spielers, um den es geht                               | Pantheon (aus `player.audit()`)   |
| `server`   | Node, auf dem es passiert ist, z. B. `lobby-01`                 | Agora (aus dem Token, nicht fälschbar) |
| `action`   | z. B. `pantheon:chat`, `shop:buy`                               | Aufrufer, Pflicht                 |
| `location` | Welt und Koordinaten, optional                                  | Aufrufer                          |
| `target`   | UUID eines zweiten Spielers, optional                           | Aufrufer                          |
| `trace`    | UUIDv7 einer zusammenhängenden Sitzung, optional ([§4](#zusammenhängende-einträge-trace)) | Pantheon (aktive Trace) oder Aufrufer |
| `with`     | freie Schlüssel und Werte (`String`, `long`, `double`, `boolean`) | Aufrufer                        |

**Ein `target` bringt den Eintrag in zwei Zeitachsen.** Eine Privatnachricht von A an B erscheint bei A und bei B.
Dasselbe gilt für Handel, `/pay` oder einen Kampf.

**Grenzen:** höchstens 16 freie Schlüssel, Schlüssel aus `[a-z0-9_]`, Texte höchstens 512 Zeichen. Was länger ist,
wird gekürzt und bekommt `truncated = true`. Ein ungültiger Schlüssel ist ein Programmierfehler und wirft eine
`IllegalArgumentException`, damit er schon beim Entwickeln auffällt.

- **Der 17. Schlüssel** wirft ebenfalls eine `IllegalArgumentException`, wie ein ungültiger Schlüssel. `truncated`
  zählt nicht zu den 16. Bei `entity(...)` und `target(...)` gewinnt der letzte Aufruf.
- **Werte, die Agora ablehnen würde, bereinigt Pantheon vorher.** Ein NUL-Zeichen in einem Textwert wird durch U+FFFD
  ersetzt (Postgres-`jsonb` nimmt `\u0000` nicht an, Agora würde den ganzen Eintrag verwerfen). `NaN` und `Infinity`
  bei `double` sind nicht darstellbar und werfen eine `IllegalArgumentException`.
- **UUIDs sind `bytes`.** In den Audit-Nachrichten ([§8](#8-agora)) stehen UUIDs als 16 Byte, Big Endian, und nicht als
  String wie sonst in den Agora-Nachrichten. Bei diesem Volumen spart das Platz, und die Byte-Reihenfolge ist die
  Zeitreihenfolge.

---

## 3. Aktionen deklarieren

```java
// shop-api
public final class ShopAudit {
    public static final AuditNamespace SHOP = AuditNamespace.of("shop");

    public static final AuditAction OPEN = SHOP.action("open");
    public static final AuditAction BUY  = SHOP.action("buy");
    public static final AuditAction SELL = SHOP.action("sell");
}
```

- **Eigener Typ.** `AuditNamespace` ist unabhängig vom `DataNamespace` der [Data API](data.md#namespaces). Der Begriff
  „Namespace“ ist in der API schon belegt, und das Audit-Log soll nicht von der Data API abhängen. Meist heißt der
  Audit-Namespace eines Plugins trotzdem genauso wie sein Daten-Namespace.
- **Name:** `<namespace>:<name>`, beides aus `[a-z0-9_]`. `AuditNamespace.of(...)` ist interniert. Registrieren muss
  man nichts.
- **Umbenennen ist ein Bruch.** Eine umbenannte Aktion ist eine neue. Einträge unter dem alten Namen bleiben bis zu
  14 Tage stehen und sind danach weg. Die kurze Aufbewahrung macht das unkritisch.
- **Gemeinsame Aktionen.** Andere Plugins verwenden die exportierten Konstanten, genau wie bei Feldern.

Pantheon bringt seine eigenen Aktionen im Audit-Namespace `pantheon` mit (`AuditActions.PANTHEON`):

| Konstante                       | Name                       | Geschrieben von                         |
|---------------------------------|----------------------------|-----------------------------------------|
| `AuditActions.CHAT`             | `pantheon:chat`            | Pantheon ([§5](#5-was-pantheon-selbst-loggt)) |
| `AuditActions.COMMAND`          | `pantheon:command`         | Pantheon                                |
| `AuditActions.JOIN`             | `pantheon:join`            | Agora                                   |
| `AuditActions.QUIT`             | `pantheon:quit`            | Agora                                   |
| `AuditActions.SWITCH`           | `pantheon:switch`          | Agora                                   |
| `AuditActions.PRIVATE_MESSAGE`  | `pantheon:private_message` | dem Nachrichten-Plugin                  |
| `AuditActions.AUDIT_VIEW`       | `pantheon:audit_view`      | Agora ([§10](#wer-hat-gelesen))         |

`PRIVATE_MESSAGE` liegt bei Pantheon, damit jedes Nachrichten-Plugin dieselbe Aktion benutzt und die Zeitachse sie
einheitlich darstellen kann.

---

## 4. Aus Plugins loggen

```java
public interface PanPlayer {
    AuditLog audit();
    // …
}

public interface AuditLog {
    void action(AuditAction action);
    void action(AuditAction action, Consumer<AuditDescriptor> descriptor);

    AuditTrace begin(AuditAction action, Consumer<AuditDescriptor> descriptor);   // siehe „Trace“ unten
    Optional<AuditTrace> activeTrace();
}

public interface AuditTrace {
    UUID id();
    boolean isActive();
    void end();                                                         // beendet, ohne zu loggen
    void end(AuditAction action, Consumer<AuditDescriptor> descriptor); // loggt die Aktion mit Trace, dann Ende
}

public interface AuditDescriptor {
    AuditDescriptor location(Location location);            // Bukkit, Server ist dieser Node
    AuditDescriptor location(NetworkLocation location);
    AuditDescriptor target(UUID player);
    AuditDescriptor target(PanPlayer player);
    AuditDescriptor trace(AuditTrace trace);                // überschreibt die aktive Trace
    AuditDescriptor entity(Entity entity);                  // Kurzform, siehe unten
    AuditDescriptor with(String key, String value);
    AuditDescriptor with(String key, long value);
    AuditDescriptor with(String key, double value);
    AuditDescriptor with(String key, boolean value);
}
```

`audit()` hängt am `PanPlayer`, nicht nur am `LocalPanPlayer`. Man kann also auch über Spieler auf anderen Servern
loggen, etwa wenn ein Teammitglied hier jemanden bannt, der woanders spielt. Für Spieler, die gerade offline sind, gibt
es `pantheon.audit().player(uuid)`.

`audit()` ist wie der `PanPlayer` selbst ein leichtes Handle ohne eigenen Zustand
([player.md §1](player.md#1-leitlinien)). Alle Einträge eines Nodes gehen in denselben Puffer ([§7](#7-puffer-und-versand)).

**`entity(entity)`** setzt `location` auf den Ort der Entity sowie `entity_type`, `entity_uuid` und, falls vorhanden,
`entity_name`. Ist die Entity ein Spieler, wird sie zum `target`:

```java
player.audit().action(ShopAudit.OPEN, d -> d.entity(npc));
player.audit().action(TradeAudit.ACCEPT, d -> d.entity(otherPlayer).with("items", 3));   // landet bei beiden
```

**Privatnachrichten** loggt das Nachrichten-Plugin, weil nur es den Empfänger von `/r` kennt:

```java
sender.audit().action(AuditActions.PRIVATE_MESSAGE, d -> d.target(recipient).with("text", text));
```

### Zusammenhängende Einträge (Trace)

Manche Einträge gehören zusammen. Wer einen Shop öffnet, bezahlt und Items aus dem Inventar nimmt, erzeugt Einträge
aus verschiedenen Plugins und Events. Eine **Trace** verbindet sie: Alle Einträge einer Sitzung tragen dieselbe
`trace`-ID, und die Zeitachse kann sie gruppiert anzeigen oder danach filtern.

```java
// Shop-Plugin: NPC wird angeklickt
AuditTrace session = player.audit().begin(ShopAudit.OPEN, d -> d.entity(npc));   // loggt OPEN, startet die Trace

// … Economy-Plugin, weiß nichts vom Shop
player.audit().action(EconomyAudit.PAY, d -> d.with("amount", 250));               // trägt automatisch die Trace

// … Shop-Plugin, InventoryClickEvent ein paar Sekunden später
player.audit().action(ShopAudit.BUY, d -> d.with("item", "diamond_sword"));        // ebenfalls

// Shop-Plugin: InventoryCloseEvent
session.end(ShopAudit.CLOSE, d -> {});                                             // loggt CLOSE, beendet die Trace
```

Regeln:

- **Eine aktive Trace pro Spieler und Node.** Sie liegt wie Session-Daten nur lokal
  ([player.md §5](player.md#5-drei-datenebenen)). Ein neues `begin` beendet die alte Trace, ohne etwas zu loggen.
- **Sie endet** mit `end(...)`, spätestens beim Quit oder Serverwechsel. Einen Timeout gibt es nicht. Pantheon beendet
  die Traces eines Spielers im `PlayerQuitEvent` auf `MONITOR`. Einträge von Plugins bis `HIGHEST` tragen die Trace
  also noch, wie bei den Spielerdaten ([player.md §7](player.md#7-lebenszyklus)).
- **Sie gilt für alle Plugin-Einträge** über diesen Spieler auf diesem Node, egal welches Plugin sie schreibt. Darum
  muss keine API einen Trace-Parameter bekommen.
- **Nicht für `pantheon:*`.** Chat, Commands und Session-Einträge bekommen keine Trace, sonst stünde jede Chatnachricht
  während des Einkaufs in der Shop-Sitzung.
- **`d.trace(t)` von Hand** überschreibt die aktive Trace, etwa wenn ein Eintrag zu einer anderen Sitzung gehört, die
  das Plugin selbst festhält.
- **`target` und Trace zusammen:** Ein Handel zwischen A und B mit Trace erscheint in beiden Zeitachsen mit derselben
  ID.
- **ID-Format:** UUIDv7, 16 Byte. Das ist dieselbe Größe wie eine Trace-ID nach W3C Trace Context bzw. OpenTelemetry.
  Wer später technische Traces einführt, kann sie also verknüpfen ([§12](#trace)).

---

## 5. Was Pantheon selbst loggt

Für Chat und Commands gibt es **keine API**. Pantheon registriert auf jedem Paper-Server einen Listener:

| Event                          | Aktion             | Freie Schlüssel                                         |
|--------------------------------|--------------------|---------------------------------------------------------|
| `AsyncChatEvent` (MONITOR)     | `pantheon:chat`    | `text` (als Klartext), `cancelled`                      |
| `PlayerCommandPreprocessEvent` (MONITOR) | `pantheon:command` | `line` (ohne `/`), `command` (aufgelöster Name), `cancelled`, `unknown` |

- **Auch abgebrochene Events** werden geloggt, mit `cancelled = true`. Gerade ein Chat, den ein Mute verhindert hat,
  oder ein Command ohne Rechte ist für die Zeitachse interessant.
- **`command`** ist der Name des Commands, auf den das Label zeigt. Bei `/tell`, `/w` oder `/essentials:msg` steht dort
  also derselbe Name. Gibt es den Command nicht, ist `unknown = true`.
- **Ort** ist bei beiden der Standort des Spielers. Er kommt aus `player.getLocation()` im asynchronen Event. Das ist
  eine Kopie und höchstens minimal veraltet, ein eigener Cache lohnt sich nicht.

**Join, Quit und Serverwechsel** schreibt **Agora** selbst, wenn sich die Presence ändert
([agora.md §4](agora.md#4-presence)). Der Proxy meldet diese Ereignisse ohnehin, ein zweiter Weg über die Nodes wäre
doppelt:

| Ereignis in Agora                                   | Aktion            | Freie Schlüssel                |
|-----------------------------------------------------|-------------------|--------------------------------|
| erstes `PlayerSwitched` nach `ClaimPresence`        | `pantheon:join`   | `to`                           |
| jedes weitere `PlayerSwitched`                      | `pantheon:switch` | `from`, `to`                   |
| `PlayerLeft`                                        | `pantheon:quit`   | `from`, `reason = left`        |
| alter Eintrag wird bei doppeltem Login verdrängt    | `pantheon:quit`   | `from`, `reason = replaced`    |
| Proxy-Stream bricht ab, seine Spieler fallen weg    | `pantheon:quit`   | `from`, `reason = proxy_lost`  |

- **`server`** ist bei allen der Proxy, über den der Spieler verbunden ist bzw. war.
- **Erst mit dem ersten Server ist es ein Join.** Bei `ClaimPresence` ist der Server noch leer
  ([agora.md §4](agora.md#4-presence)). Kommt der Spieler nie auf einem Server an, etwa weil das Laden seiner Daten
  scheitert, entsteht weder `join` noch `quit`.
- **Abgleiche erzeugen keine Einträge.** Korrekturen aus dem Heartbeat und der Neuaufbau der Presence aus `Hello` nach
  einem Neustart von Agora sind keine echten Ereignisse. Sonst stünden nach jedem Neustart falsche Joins und Quits in
  jeder Zeitachse.

**Commands, die nur der Proxy ausführt** (`/server`, `/glist`, Proxy-Plugins), werden nicht geloggt. Pantheon hat
auf Velocity keinen Listener dafür.

---

## 6. Versteckte Commands

Manche Commands dürfen nicht unter `pantheon:command` erscheinen:

- weil sie ein **Geheimnis** enthalten, etwa `/login <passwort>`, oder
- weil ein Plugin sie **selbst besser loggt**, etwa `/msg` und `/r` als `pantheon:private_message` mit Empfänger.

Beides regelt eine Liste versteckter Commands. Ein versteckter Command wird vom Listener gar nicht geloggt.

```java
// Im Nachrichten-Plugin: es kennt seine eigenen Commands
pantheon.audit().hideCommand(getCommand("msg"));       // PluginCommand: alle Aliase sind mit erfasst
pantheon.audit().hideCommand(getCommand("reply"));

// Commands fremder Plugins, per Name
pantheon.audit().hideCommand("authme:login");
```

- **Die Liste gilt für den ganzen Node.** Jedes Plugin trägt bei, was es kennt, und alle Einträge zusammen gelten.
- **Verglichen wird der aufgelöste Command**, nicht das getippte Label. `/tell`, `/w`, `/m` und `/essentials:msg` sind
  damit auf einmal versteckt. Aufgelöst wird beim Event, die Reihenfolge, in der Plugins ihre Commands registrieren,
  spielt also keine Rolle.
- **Defaults:** Pantheon versteckt von sich aus `login`, `l`, `register`, `reg`, `changepassword` und
  `changepass`, egal aus welchem Plugin. Ein Passwort soll nie im Log stehen, auch wenn jemand vergessen hat, den
  Command zu verstecken.
- **Genaue Regel.** Ein Command ist versteckt, wenn der aufgelöste Command eine versteckte Identität hat, oder wenn das
  getippte Label ohne Namespace, der Name oder einer der Aliase des aufgelösten Commands in den Defaults steht.
  Groß- und Kleinschreibung spielt keine Rolle. Ein unbekanntes Label aus den Defaults ist ebenfalls versteckt, auch
  wenn es keinen Command dazu gibt. `/LOGIN`, `/authme:l` und `/register` eines unbekannten Plugins stehen also nie im
  Log.
- **Wie das Label gelesen wird.** Alle führenden Slashes und Leerzeichen werden entfernt, der Namespace wird am
  **letzten** `:` abgetrennt (`a:b:login` ergibt `login`), und verglichen wird mit `Locale.ROOT`. Bei
  `/LOGIN` mit türkischen Systemeinstellungen würde ein `I` sonst nicht zu `i`.

---

## 7. Puffer und Versand

```
 Plugin-Thread                Audit-Thread (einer pro Node)                  Agora
     │
     │ action(...) ──► Queue
     │ (kehrt sofort zurück)    │
     │                          ├─ alle 5 s oder bei 500 Einträgen:
     │                          │    AuditBundle bauen ──── AppendAudit (gzip) ───►│ in Postgres schreiben
     │                          │                       ◄──────── ok ──────────────┤
     │                          └─ Fehler: Bündel bleibt im Puffer, nächster Versuch beim nächsten Takt
```

- **Ein Puffer pro Node**, gemeinsam für alle Plugins. Größere Bündel komprimieren besser, und Agora bekommt weniger
  Aufrufe.
- **Ein Bündel geht raus**, sobald 500 Einträge gesammelt sind oder 5 Sekunden vergangen sind, je nachdem, was zuerst
  eintritt. Es ist immer nur ein Bündel unterwegs.
- **gzip macht gRPC.** Das Bündel ist eine Protobuf-Nachricht, der Aufruf wird mit `withCompression("gzip")`
  geschickt. Chat und Commands wiederholen sich stark, die Kompression lohnt sich deshalb.
- **Scheitert der Versand**, bleibt das Bündel im Puffer und geht beim nächsten Takt erneut raus. Doppelt gespeichert
  wird nichts, weil jeder Eintrag eine `id` hat ([§9](#9-postgres)).
- **Obergrenze 10.000 Einträge.** Ist Agora länger weg und der Puffer voll, fallen die **ältesten** Einträge weg.
  Pantheon zählt sie, schreibt höchstens einmal pro Minute eine Warnung ins Log und meldet die Zahl im nächsten Bündel
  als `dropped`.
- **Beim Stoppen** versucht Pantheon in `onDisable` noch einen letzten Versand, höchstens 2 Sekunden lang. Was dann
  noch im Puffer steht, ist verloren. Der Versand läuft **parallel** zum Leeren der Outbox der Spielerdaten, beide
  innerhalb der 10 Sekunden, die Pantheon beim Stoppen insgesamt wartet ([agora.md §8](agora.md#8-wenn-agora-weg-ist)).

Die Werte 500, 5 s, 10.000 und 2 s sind Defaults in Pantheon. Ein Plugin kann sie nicht ändern
([§13](#von-mir-entschieden-bitte-prüfen)).

### Port

Wie bei Daten, Presence und Nachrichten spricht Pantheon über ein schmales Interface mit dem Dienst
([agora.md §2](agora.md#2-ports-in-pantheon)):

```java
public interface AuditBackend {
    CompletableFuture<Void> append(AuditBundle bundle);
    CompletableFuture<TimelineResult> timeline(TimelineSpec spec);   // Spec: Spieler, Zeitraum, Filter, Cursor, Viewer
}
```

`TimelineSpec` und `TimelineResult` sind die Bukkit-freien Gegenstücke aus `core`. Die `TimelineQuery` und die
`TimelinePage` der API ([§10](#10-die-zeitachse-lesen)) bauen darauf auf.

| Implementierung    | Zweck                                                                             |
|--------------------|-----------------------------------------------------------------------------------|
| `InMemoryNetwork`  | Tests und lokale Entwicklung (`PANTHEON_BACKEND=memory`). Schreibt `join`, `switch` und `quit` (`reason = left`) aus seiner eigenen Presence. `replaced` und `proxy_lost` gibt es nur in Agora, das In-Memory-Netz kennt keine mehreren Proxys. |
| `AgoraBackend`     | Produktion.                                                                       |

Beide laufen gegen dieselbe Contract-Test-Suite (`AuditBackendContract`). Sie prüft unter anderem, dass ein erneut
gesendetes Bündel jeden Eintrag nur einmal speichert.

---

## 8. Agora

```proto
service Agora {
  // … bestehende Aufrufe aus agora.md
  rpc AppendAudit (AuditBundle)     returns (AppendAuditResponse);
  rpc Timeline    (TimelineRequest) returns (TimelineResponse);
}

message AuditBundle {
  repeated AuditEntry entries = 1;
  uint64             dropped  = 2;   // seit dem letzten Bündel verworfen, siehe §7
}

message AuditEntry {
  bytes                  id         = 1;   // UUIDv7, 16 Byte, enthält den Zeitpunkt
  bytes                  player     = 2;   // UUID, 16 Byte
  string                 action     = 3;   // 'shop:buy'
  optional AuditLocation location   = 4;
  optional bytes         target     = 5;
  map<string, AuditValue> with      = 6;
  optional bytes         trace      = 7;   // UUIDv7, 16 Byte
}

message AuditLocation { string world = 1; double x = 2; double y = 3; double z = 4; }

message AuditValue {
  oneof value { string s = 1; int64 l = 2; double d = 3; bool b = 4; }
}

message TimelineRequest {
  bytes           player     = 1;
  int64           from_millis = 2;
  int64           to_millis   = 3;
  repeated string actions    = 4;   // 'shop:buy' oder ganzer Namespace 'shop:*'; leer = alle
  uint32          limit      = 5;   // höchstens 500
  optional bytes  cursor     = 6;   // id des letzten Eintrags der vorigen Seite
  optional bytes  trace      = 7;   // nur Einträge dieser Trace
  AuditViewer     viewer     = 8;   // Pflicht: wer liest, siehe §10
}

message AuditViewer {
  optional bytes player = 1;   // UUID des Teammitglieds, falls bekannt
  string         name   = 2;   // Anzeigename, z. B. 'Notch', 'console' oder ein Web-Login
  string         via    = 3;   // 'command', 'web', …
}

message TimelineResponse {
  repeated StoredAuditEntry entries = 1;   // neueste zuerst
  optional bytes            cursor  = 2;   // leer = keine weiteren Einträge
}

message StoredAuditEntry {
  AuditEntry entry  = 1;
  string     server = 2;
}
```

- **`server` setzt Agora** aus dem authentifizierten Node. Ein Node kann nicht für einen anderen loggen.
- **Ungültige Einträge** verwirft Agora **einzeln** mit einer Warnung und zählt sie wie `dropped`: falsche
  UUID-Länge, ungültiger Aktionsname oder ein Zeitpunkt außerhalb des Fensters. Das Bündel gilt trotzdem als
  angenommen. Sonst bliebe ein Node mit einem kaputten Eintrag für immer an diesem Bündel hängen.
- **Zeitfenster:** Liegt der Zeitpunkt in der `id` mehr als 24 Stunden in der Vergangenheit oder mehr als eine Stunde
  in der Zukunft, etwa wegen einer falsch gehenden Uhr, ist der Eintrag ungültig. Für ihn gäbe es keine Partition, und
  er soll nicht das ganze Bündel scheitern lassen.
- **Nachrichtengröße:** Agora setzt `maxInboundMessageSize` auf 16 MiB, ebenso der Client-Kanal in Pantheon. Im
  ungünstigsten Fall hat ein Bündel 500 × 16 × 512 Zeichen, also etwa 4 MiB, und das ist der gRPC-Default.
- **Ein Bündel ist eine Transaktion.** Agora schreibt alle Einträge mit einem `INSERT … ON CONFLICT DO NOTHING` und
  antwortet erst danach.
- **`dropped`** erscheint in Agora als WARN mit Node-ID und Anzahl. Ein Metrik-System gibt es in v1 nicht. In der
  Zeitachse erscheint es ebenfalls nicht.
- **Rechte:** Wie überall darf jeder authentifizierte Node alles ([agora.md §9](agora.md#9-verbindungen-und-sicherheit)),
  also auch jede Zeitachse lesen. Wer im Spiel oder im Webinterface lesen darf, regelt die jeweilige Oberfläche.

---

## 9. Postgres

```sql
CREATE TABLE pantheon_audit (
    id         UUID             NOT NULL,          -- UUIDv7, enthält den Zeitpunkt
    player     UUID             NOT NULL,
    target     UUID,
    trace      UUID,
    server     TEXT             NOT NULL,
    action     TEXT             NOT NULL,
    world      TEXT,
    x          DOUBLE PRECISION,
    y          DOUBLE PRECISION,
    z          DOUBLE PRECISION,
    with_data  JSONB            NOT NULL DEFAULT '{}',
    PRIMARY KEY (id)
) PARTITION BY RANGE (id);

CREATE INDEX pantheon_audit_player ON pantheon_audit (player, id DESC);
CREATE INDEX pantheon_audit_target ON pantheon_audit (target, id DESC) WHERE target IS NOT NULL;
```

**Keine Zeitspalte.** Bei UUIDv7 sind die ersten 48 Bit die Unix-Zeit in Millisekunden, und Postgres vergleicht UUIDs
Byte für Byte. Nach `id` sortieren heißt also nach Zeit sortieren. Eine eigene Spalte `at` wäre doppelt, kostete
8 Byte pro Zeile und könnte der `id` widersprechen. Wer von Hand SQL schreibt, liest den Zeitpunkt mit
`uuid_extract_timestamp(id)` aus (seit Postgres 17).

**Eine Partition pro Tag** (`pantheon_audit_20261127` usw.). Ihre Grenzen sind UUID-Werte: Mitternacht UTC als
Zeitstempel in den ersten 48 Bit, der Rest minimal.

```sql
CREATE TABLE pantheon_audit_20261127 PARTITION OF pantheon_audit
    FOR VALUES FROM ('<uuid für 27.11.2026 00:00 UTC>') TO ('<uuid für 28.11.2026 00:00 UTC>');
```

Agora berechnet die Grenzen in Java und legt die Partitionen von **gestern** bis heute plus zwei Tage an. Das passiert
beim Start, **bevor** der Health-Dienst `SERVING` meldet (vor der ersten Partition darf kein `AppendAudit` ankommen),
und danach alle 24 Stunden. Gestern ist dabei, weil Agora Einträge bis 24 Stunden Alter annimmt und ein gültiger
Eintrag von gestern auf einem frischen Agora sonst keine Partition fände. Flyway legt nur die Haupttabelle an
(`V2__audit.sql`).

**Aufräumen heißt Partition löschen.** Täglich entfernt Agora jede Partition, deren Tag vollständig älter als 14 Tage
ist, per `DROP TABLE`. Das ist sofort erledigt, egal wie viele Zeilen darin liegen, und hinterlässt keinen Ballast wie
ein `DELETE`. Einträge bleiben damit 14 bis 15 Tage stehen.

**Zeitachse:**

```sql
SELECT … FROM pantheon_audit
WHERE (player = $1 OR target = $1)
  AND id >= $2 AND id < $3                              -- Zeitraum als UUID-Grenzen, in Java berechnet
  AND (action = ANY($4) OR action LIKE ANY($5))         -- nur mit Filter
  AND trace = $7                                        -- nur mit Trace-Filter
  AND id < $cursor                                      -- nur mit Cursor
ORDER BY id DESC
LIMIT $6;
```

Postgres beantwortet das über beide Indizes und sucht dabei nur in den Partitionen des Zeitraums, weil die Grenzen
feste Werte sind. Der Cursor ist einfach die `id` des letzten Eintrags. Ein eigener Index
auf `trace` ist nicht nötig: Auch mit Trace-Filter wird immer innerhalb der Zeitachse eines Spielers gesucht.

**Größenordnung (grobe Annahme):** 1.000 Spieler online, im Schnitt ein Eintrag alle 10 Sekunden pro Spieler, ergibt
etwa 100 Einträge pro Sekunde, 8,6 Mio. pro Tag und rund 120 Mio. in 14 Tagen. Bei etwa 200 Byte pro Zeile samt
Indizes sind das grob 25 Gigabyte. Für Postgres ist das unproblematisch. Wächst es um ein Vielfaches, ist ClickHouse
die naheliegende Alternative ([§12](#speicher)).

---

## 10. Die Zeitachse lesen

Webinterface und Command sind **eigene, spätere Projekte**. Beide benutzen nur `Timeline` aus [§8](#8-agora). In
Pantheon sieht das so aus:

```java
pantheon.audit().timeline(uuid)
    .viewer(sender)                                         // Pflicht, siehe „Wer hat gelesen“
    .between(Instant.now().minus(Duration.ofHours(6)), Instant.now())
    .actions(AuditActions.CHAT, AuditActions.PRIVATE_MESSAGE)
    .namespace(ShopAudit.SHOP)                              // alle 'shop:*'
    .limit(100)
    .fetch()                                                // CompletableFuture<TimelinePage>
    .thenAcceptAsync(page -> render(sender, page), pantheon.mainThread());
```

Eine Seite enthält höchstens 500 Einträge, neueste zuerst, und einen Cursor für die nächste. Ohne Zeitraum gelten die
letzten 24 Stunden.

- **`limit`:** Default 100, größere Werte als 500 werden auf 500 gekürzt. `to` ist exklusiv.
- **`viewer` ist Pflicht.** `fetch()` ohne `viewer` scheitert sofort mit `IllegalStateException("viewer fehlt")` und
  ohne Aufruf. Agora prüft zusätzlich.
- **`viewer(CommandSender)`:** Ein Spieler liefert UUID und Namen. Jeder andere Sender (Konsole, Command-Block) gilt als
  „kein Spieler“, also die Nil-UUID `new UUID(0, 0)`, mit `sender.getName()` als Namen. `via` ist der **Kanal**,
  hier `"command"`, nicht der Typ des Senders. Webinterface und andere Wege setzen ihren eigenen Wert.
- **Zeitraum in `TimelineRequest`:** `from_millis` und `to_millis` mit `0` bedeuten „nicht gesetzt“. `to` ist dann
  jetzt, `from` ist `to` minus 24 Stunden.
- **Weiterblättern:** Mit `cursor` und ohne ausdrückliches `from` gilt **keine** 24-Stunden-Untergrenze, nur die
  Aufbewahrung. `to` ist die Position des Cursors. So gehen beim Blättern keine Einträge verloren, auch wenn die erste
  Seite bei 24 Stunden endete, und ältere Einträge können folgen.
- **Einträge aus der Zukunft.** Agora nimmt Einträge bis eine Stunde in der Zukunft an (falsch gehende Uhr). Mit dem
  Default-`to` (jetzt) sind sie unsichtbar, bis die Uhr aufgeholt hat. Wer sie sucht, setzt `to` später.

**Darstellung.** Eine Oberfläche kennt die Plugins nicht. Jeder Eintrag beschreibt sich deshalb selbst und lässt sich
ohne weiteres Wissen als Zeile darstellen:

```
27.11. 14:02:05  lobby-01  ┌ shop:open              world 12/64/-3   entity_type=villager entity_name=Händler
27.11. 14:02:09  lobby-01  │ economy:pay                             amount=250
27.11. 14:02:11  lobby-01  └ shop:buy               world 12/64/-3   item=diamond_sword
27.11. 14:02:40  lobby-01  pantheon:private_message → Notch          text=hast du kurz zeit?
27.11. 14:03:05  proxy-01  pantheon:switch                           from=lobby-01 to=survival-02
```

Die Klammer links ist eine Trace. Ein Klick darauf zeigt nur ihre Einträge (`trace` in `TimelineRequest`).

### Wer hat gelesen

Jeder Blick in eine Zeitachse wird selbst geloggt, als `pantheon:audit_view`. Das schreibt **Agora** beim
`Timeline`-Aufruf, nicht die Oberfläche. So ist jeder Weg erfasst, auch einer, der später dazukommt.

Agora puffert seine Session-Einträge (`join`, `switch`, `quit`) intern und schreibt sie spätestens jede Sekunde, damit
die Presence nie auf Postgres warten muss. `audit_view` dagegen wird **synchron vor der Abfrage** geschrieben. Gelesen
werden darf nur, was protokolliert ist. Scheitert das Schreiben, scheitert `Timeline` mit `UNAVAILABLE`.

| Feld       | Inhalt                                                                  |
|------------|-------------------------------------------------------------------------|
| `player`   | das lesende Teammitglied (`viewer.player`), sonst eine feste UUID für „kein Spieler“ (Nil-UUID) |
| `target`   | der Spieler, dessen Zeitachse gelesen wurde                             |
| `with`     | `viewer_name`, `via`, `from`, `to`, `actions` und `trace`, falls gefiltert wurde |

- **Ohne `viewer` lehnt Agora den Aufruf ab** (`INVALID_ARGUMENT`). Anonym lesen geht nicht.
- **Nur die erste Seite** wird geloggt, also Aufrufe ohne `cursor`. Weiterblättern erzeugt keine neuen Einträge.
- **Der eigene `audit_view`-Eintrag fehlt in der Antwort.** Der gerade geschriebene Eintrag wird aus der aktuellen
  Seite ausgeschlossen und erscheint erst bei späteren Abfragen.
- **Wegen `target`** steht der Zugriff auch in der Zeitachse des gelesenen Spielers. Ein anderes Teammitglied sieht
  dort also, wer schon hineingeschaut hat.
- **Konsole und Web-Logins ohne Minecraft-Konto** stehen unter der Nil-UUID mit ihrem `viewer_name`. Ihre Zugriffe
  findet man über die Zeitachse des gelesenen Spielers.

Schönere Darstellungen für bekannte Aktionen (Icons, übersetzte Texte) kann die Oberfläche später selbst ergänzen.
Die Daten müssen sich dafür nicht ändern.

---

## 11. Datenschutz

Chat und Privatnachrichten sind personenbezogene Daten. Für den Betrieb heißt das:

- Die **Datenschutzerklärung** des Netzwerks muss das Audit-Log nennen: was geloggt wird, wozu (Moderation,
  Aufklärung von Regelverstößen) und wie lange (14 Tage).
- **Keine Passwörter:** versteckte Commands ([§6](#6-versteckte-commands)), mit Defaults für gängige Login-Commands.
- **Keine IP-Adressen.** Pantheon schreibt sie nie ins Audit-Log.
- **Lesen nur für berechtigtes Team.** Das regeln Webinterface und Command.
- **Jeder Lesezugriff wird protokolliert** ([§10](#wer-hat-gelesen)).
- **Löschen nach 14 Tagen** passiert automatisch und vollständig ([§9](#9-postgres)).

Das ist keine Rechtsberatung, sondern das, was die Technik dafür bereitstellt.

---

## 12. Betrachtete Ansätze

### Speicher

| Ansatz                                     | Bewertung                                                                                  |
|--------------------------------------------|--------------------------------------------------------------------------------------------|
| **Agora + Postgres, Tagespartitionen**     | **Gewählt.** Token, Verbindung und Betrieb existieren schon. Aufräumen per `DROP` ist billig. Mehr Last auf Agora, bei der erwarteten Größe unkritisch. |
| Eigener Audit-Dienst mit ClickHouse         | Stärkere Kompression, TTL eingebaut. Aber zweiter Dienst, zweite Datenbank, zweiter Token. Lohnt sich erst bei einem Vielfachen des Volumens. `AuditBackend` und `Timeline` halten den Umstieg offen. |
| Nodes schreiben direkt in die Datenbank     | Verworfen: Datenbank-Zugangsdaten auf jedem Node, genau das vermeidet [agora.md §1](agora.md#1-überblick). |

### Aktionsnamen

| Ansatz                                     | Bewertung                                                                                  |
|--------------------------------------------|--------------------------------------------------------------------------------------------|
| Freie Strings (`name("shop")`)             | Einfach, aber nach einem Jahr gibt es `shop`, `Shop` und `shop_buy` nebeneinander, Filter greifen nicht mehr. |
| **Deklarierte Konstanten im `AuditNamespace`** | **Gewählt.** Passt zu `Field` und `MessageKey`, Namen bleiben einheitlich und wiederverwendbar. |
| Plugin liefert Anzeigetext mit             | Schöner zu lesen, aber größere Einträge, nicht übersetzbar, und alte und neue Formulierungen mischen sich. |

### Privatnachrichten

| Ansatz                                     | Bewertung                                                                                  |
|--------------------------------------------|--------------------------------------------------------------------------------------------|
| Listener loggt `/msg` und `/r`             | Bei `/r` fehlt der Empfänger, die Nachricht erscheint nur beim Absender.                   |
| **Nachrichten-Plugin loggt selbst**        | **Gewählt.** Empfänger korrekt, Eintrag in beiden Zeitachsen. Das Plugin gehört uns.        |

### Suche

| Ansatz                                     | Bewertung                                                                                  |
|--------------------------------------------|--------------------------------------------------------------------------------------------|
| **Nur pro Spieler und Zeitraum**           | **Gewählt.** Zwei Indizes, kleine Abfragen, entspricht der Zeitachse.                      |
| Auch über alle Spieler (Text, Ort, Aktion) | Volltext und weitere Indizes, deutlich mehr Speicher und Aufwand. Bei Bedarf als eigenes Projekt. |

### Trace

| Ansatz                                     | Bewertung                                                                                  |
|--------------------------------------------|--------------------------------------------------------------------------------------------|
| Trace ausdrücklich weiterreichen           | Präzise, aber jede API, durch die eine Sitzung läuft (z. B. Economy), braucht einen Trace-Parameter. |
| **Aktive Trace am Spieler**                | **Gewählt.** Keine API ändert sich, die Sitzung überdauert beliebig viele Events.          |
| Trace nur für den aktuellen Aufruf (`ScopedValue`) | Verworfen: Erfasst keine späteren Events wie Inventar-Klicks.                     |
| OpenTelemetry                              | Verworfen fürs Audit-Log. Es ist für technische Diagnose gebaut, sampelt üblicherweise, verschickt einen Span erst an seinem Ende (lange Sitzungen gelten als Fehlmuster und gehen beim Absturz verloren), trägt Kontext nur pro Thread und sucht nach Trace-ID statt nach Spieler. Das SDK müsste in jedes Plugin geshadet werden, der Java-Agent geht in Plugins nicht. Übernommen ist nur das 16-Byte-Format der ID. Für Betriebsmetriken (Latenz, `dropped`, Puffer) bleibt OpenTelemetry eine gute Wahl, als eigenes Projekt. |

### Logger-Instanz pro Spieler

Der erste Entwurf sah eine `AuditLogger`-Instanz pro `PanPlayer` mit eigener `factory(...)` vor. Verworfen, weil ein
`PanPlayer` ein leichtes Handle ohne Zustand ist ([player.md §1](player.md#1-leitlinien)). `audit()` ist deshalb ein
Handle, gepuffert wird zentral pro Node.

---

## 13. Entscheidungen und offene Fragen

### Festgelegt

- **Zweck:** Zeitachse pro Spieler, später im Webinterface oder per Command.
- **Aufbewahrung 14 Tage.**
- **Fire-and-forget, gebündelt pro Node als `AuditBundle`, gzip-komprimiert.**
- **Chat und Commands loggt ein eingebauter Listener, ohne API.**
- **Versteckte Commands**, etwa für Passwörter und für `/msg` und `/r`.
- **Das Plugin ist egal**, gespeichert werden Spieler (UUID) und Zeitpunkt.
- **Freie Schlüssel** als zusätzliche Information.
- **Abfrage nur pro Spieler und Zeitraum** (Variante A).
- **Aktionen als deklarierte Konstanten** (Variante 2).
- **Privatnachrichten loggt das eigene Nachrichten-Plugin** mit `target` (Variante b).
- **Speicher in Agora und Postgres** (Variante 1).
- **Chat läuft auf Paper**, der Listener dort reicht.
- **Trace für zusammenhängende Einträge, aktiv am Spieler** (Variante 2).
- **Kein OpenTelemetry fürs Audit-Log.**
- **Lesezugriffe werden als `pantheon:audit_view` geloggt.**
- **Commands auf dem Proxy werden ignoriert.**
- **Eigener Typ `AuditNamespace`** statt des `DataNamespace` der Data API (`AuditNamespace.of("shop").action("buy")`).
- **Kein eigenes Zeitfeld.** Der Zeitpunkt steckt in der UUIDv7-`id`, partitioniert und sortiert wird nach `id`.

### Von mir entschieden, bitte prüfen

- **Kein Kategorie-Begriff.** Chat, Commands und Sessions sind Aktionen im Namespace `pantheon`. Gefiltert wird nach
  Aktion oder Namespace. Das ist ein Begriff weniger.
- **`action(...)` statt `interact(...)`**, weil nicht jede Aktion eine Interaktion ist (Kauf, Bann, Belohnung).
- **Join, Quit und Serverwechsel schreibt Agora**, nicht die Nodes. `join` erst beim ersten Server, `quit` mit
  `reason`, keine Einträge aus Abgleichen ([§5](#5-was-pantheon-selbst-loggt)).
- **Ein `target` bringt den Eintrag in beide Zeitachsen**, auch für Handel, `/pay` und Ähnliches.
- **`entity(entity)`** macht einen Spieler automatisch zum `target`.
- **Abgebrochene Chats und Commands werden mitgeloggt**, mit `cancelled = true`.
- **Einstellungen gehören dem Node.** Ein Plugin kann Puffer, Takt und Writer nicht ändern. Mehrere Plugins auf einem
  Server würden sich sonst widersprechen. `pantheon.config().auditLogger()` aus dem ersten Entwurf entfällt.
- **Versteckte Commands per Code**, als Liste für den ganzen Node, plus Defaults für Login-Commands. Es gibt keine
  Konfigurationsdatei.
- **Ungültige Einträge verwirft Agora einzeln**, mit Warnung, das Bündel gilt als angenommen. `dropped` ist ein
  WARN-Log, keine Metrik.
- **Zeitfenster, Partitionen, Quit-Trace, versteckte Commands:** Details in [§6](#6-versteckte-commands),
  [§8](#8-agora), [§9](#9-postgres) und [§10](#10-die-zeitachse-lesen).
- **Defaults:** 500 Einträge oder 5 s pro Bündel, 10.000 Einträge Obergrenze, 2 s Versand beim Stoppen, 16 freie
  Schlüssel, 512 Zeichen pro Text, Seiten mit höchstens 500 Einträgen.
- **Wiederholter Versand im Speicher**, solange der Puffer reicht, Duplikate verhindert die `id`.
- **Trace-Regeln:** eine aktive Trace pro Spieler und Node, ein neues `begin` beendet die alte, Ende spätestens bei
  Quit oder Serverwechsel, kein Timeout, keine Trace für `pantheon:*`.
- **Kein Index auf `trace`**, gefiltert wird nur innerhalb der Zeitachse eines Spielers.
- **`audit_view` schreibt Agora**, mit Pflichtfeld `viewer`, nur für die erste Seite, mit dem gelesenen Spieler als
  `target`. Leser ohne Minecraft-Konto stehen unter der Nil-UUID.

### Offen

- **Lücken sichtbar machen.** Soll die Zeitachse anzeigen, dass ein Server in einem Zeitraum Einträge verworfen hat?

### Dem Builder überlassen

- Wie die Agora-Jobs für Partitionen geplant werden (eigener Scheduler oder `pg_partman`).
- Ob Agora Bündel per `COPY` oder als mehrzeiliges `INSERT` schreibt.
- Namen der Metriken für `dropped`, Puffergröße und Versanddauer.
