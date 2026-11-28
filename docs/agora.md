# Agora

> Status: **Umgesetzt** (Agora `0.1.0-SNAPSHOT`, eigenes Repository `agora`). Dieses Dokument beschreibt den
> Microservice, mit dem Pantheon spricht. Plugin-Entwickler sehen ihn nicht, für sie gelten [data.md](data.md) und [player.md](player.md).

Agora ist ein eigenständiger Microservice. Er verwaltet:

- **persistente Daten:** Spielerdaten und Stores ([data.md](data.md)),
- **Presence:** wer online ist, auf welchem Server, mit welcher Sprache ([player.md §5](player.md#5-drei-datenebenen)),
- **Zustellung von Nachrichten** an Spieler auf anderen Servern ([player.md §4](player.md#4-nachrichten)).

Der Name folgt Olymp und Pantheon: Die Agora war der zentrale Platz der griechischen Stadt. Dort kamen alle
zusammen, man sah, wer da war, richtete Nachrichten aus und verwahrte öffentliche Aufzeichnungen.

---

## Inhalt

1. [Überblick](#1-überblick)
2. [Ports in Pantheon](#2-ports-in-pantheon)
3. [Der Node-Stream](#3-der-node-stream)
4. [Presence](#4-presence)
5. [Daten](#5-daten)
6. [Nachrichten](#6-nachrichten)
7. [Postgres](#7-postgres)
8. [Wenn Agora weg ist](#8-wenn-agora-weg-ist)
9. [Verbindungen und Sicherheit](#9-verbindungen-und-sicherheit)
10. [Entscheidungen](#10-entscheidungen)

---

## 1. Überblick

```
  Velocity-Proxy ─┐
  Paper-Server  ──┼──── gRPC ────▶       Agora       ────▶  Postgres (JSONB)
  Paper-Server  ──┘   Aufrufe +     (eine Instanz)
                      1 Stream
                      pro Node
```

- Ein **Node** ist ein Paper-Server oder ein Velocity-Proxy, auf dem Pantheon läuft.
- Jeder Node hält **eine** gRPC-Verbindung zum Dienst: einfache Aufrufe für Lesen und Schreiben, dazu **einen**
  bidirektionalen Stream für alles, was der Dienst von sich aus schickt.
- **Nur der Dienst spricht mit Postgres.** Auf den Nodes liegen keine Datenbank-Zugangsdaten.
- **Kein Redis.** Presence und Live-Updates laufen über den Stream, Nachrichten über den Proxy.
- **Eine Instanz.** Presence, Halter und Streams liegen im Speicher des Dienstes. Das hält ihn einfach. Mehrere
  Instanzen sind erst nötig, wenn eine nicht mehr reicht.

---

## 2. Ports in Pantheon

Pantheon spricht über vier schmale Interfaces mit dem Dienst. Für jedes gibt es eine In-Memory-Variante für Tests und
lokale Entwicklung.

```java
public interface DataBackend {

    // Lesen
    CompletableFuture<List<Row>> loadPlayer(UUID player);             // macht diesen Node zum Halter, siehe §5
    void releasePlayer(UUID player);                                  // beim Quit, läuft über den Stream
    CompletableFuture<List<Row>> load(DocRef doc);                    // leer = nicht vorhanden
    CompletableFuture<List<DocSnapshot>> query(QueryTarget target, QuerySpec spec);
    CompletableFuture<Long> count(QueryTarget target, List<FieldCondition> where);
    CompletableFuture<Map<RowRef, Long>> versions(Collection<RowRef> rows, Collection<UUID> players);

    // Schreiben: jede Operation trägt einen Idempotency-Key
    CompletableFuture<ApplyResult> apply(WriteRequest request);       // Key, Dokument, Operationen, Bedingungen
    CompletableFuture<CreateResult> create(String idempotencyKey, StoreDoc doc, Map<String, Object> data);
    CompletableFuture<Boolean> delete(String idempotencyKey, StoreDoc doc);
    CompletableFuture<Void> expire(String idempotencyKey, StoreDoc doc, Instant at);
    CompletableFuture<TransferResult> transfer(String idempotencyKey, DocRef from, DocRef to,
                                               FieldSpec field, long amount);
    CompletableFuture<Void> declareIndex(IndexDeclaration index);

    // Live-Updates
    void watchStore(StoreRef store);
    void unwatchStore(StoreRef store);
    CompletableFuture<Void> streamBarrier();                          // fertig, wenn Agora alle früheren Stream-Nachrichten verarbeitet hat
    Subscription onDocChanged(Consumer<DocChanged> listener);
}

public interface PresenceBackend {
    Collection<PresenceEntry> snapshot();                             // alle Online-Spieler, aus dem Speicher
    Subscription onPresenceChanged(Consumer<PresenceChange> listener);
    CompletableFuture<NetworkLocation> location(UUID player);         // fragt den Server des Spielers (Locate)
    void answerLocations(LocationResolver resolver);                  // beantwortet LocationRequests für diesen Node
    CompletableFuture<TeleportResult> teleport(UUID player, String server, @Nullable NetworkLocation location); // Teleport (§4)
    void answerArrivals(ArrivalHandler handler);                      // beantwortet PrepareArrival/CancelArrival für diesen Node
}

public interface MessageBackend {
    void deliver(UUID player, Delivery delivery);                     // fertig gerendert, Fire-and-forget
}

public interface AuditBackend {
    CompletableFuture<Void> append(AuditBundle bundle);               // gebündelt, ein erneut gesendetes Bündel speichert nichts doppelt
    CompletableFuture<TimelineResult> timeline(TimelineSpec spec);    // Zeitachse eines Spielers, siehe audit_log.md §8
}

public interface Backend extends DataBackend, PresenceBackend, MessageBackend, AuditBackend, AutoCloseable {
    void start(NodeState state);                                      // Hello mit gehaltenen Spielern und Stores
    ConnectionState state();                                          // CONNECTING, CONNECTED, DISCONNECTED, FATAL
    Subscription onConnectionState(Consumer<ConnectionState> listener);
}
```

| Implementierung    | Zweck                                                                                     |
|--------------------|-------------------------------------------------------------------------------------------|
| `InMemoryNetwork`  | Alle vier Ports in einem Prozess (`PANTHEON_BACKEND=memory`). Unit-Tests, lokale Entwicklung, einzelner Server. |
| `AgoraBackend`     | Produktion. Alle vier Ports über eine Verbindung zu Agora.                               |

**Eine gemeinsame Contract-Test-Suite** läuft gegen beide Implementierungen, die echte per Testcontainers mit
Postgres. Nur so verhalten sich `InMemoryNetwork` und Dienst bei Zahlen, Defaults, Bedingungen und `unique` gleich.

---

## 3. Der Node-Stream

```proto
service Agora {
  rpc Connect       (stream NodeMessage)    returns (stream ServiceMessage);
  rpc ClaimPresence (ClaimPresenceRequest)  returns (ClaimPresenceResponse);   // nur vom Proxy, beim Login (§4)
  rpc Locate        (LocateRequest)         returns (LocateResponse);          // Position eines Spielers (§4)
  rpc Teleport      (TeleportRequest)       returns (TeleportResponse);        // Netzwerk-Teleport, nur von Paper (§4)
  rpc AppendAudit   (AuditBundle)           returns (AppendAuditResponse);     // Audit-Einträge, gebündelt und gzip-komprimiert
  rpc Timeline      (TimelineRequest)       returns (TimelineResponse);        // Zeitachse eines Spielers, viewer ist Pflicht
  // dazu die einfachen Aufrufe aus §5
}
// AppendAudit und Timeline: Nachrichten, Grenzen und Fehlerverhalten in audit_log.md §8. UUIDs sind dort bytes
// (16 Byte, Big Endian) statt Strings. Agora setzt maxInboundMessageSize auf 16 MiB, ebenso der Pantheon-Kanal.

message NodeMessage {
  oneof payload {
    Hello         hello          = 1;   // zuerst, auch nach jedem Reconnect
    Heartbeat     heartbeat      = 2;   // alle 10 s
    WatchStore    watch_store    = 3;
    UnwatchStore  unwatch_store  = 4;
    KickResult    kick_result    = 5;   // nur vom Proxy, Antwort auf KickPlayer
    PlayerSwitched player_switched = 6; // nur vom Proxy
    PlayerLeft    player_left    = 7;   // nur vom Proxy
    LocationReply location_reply = 8;
    SendDelivery  send_delivery  = 9;   // fertig gerenderte Nachricht an einen Spieler (§6)
    ReleasePlayer release_player = 10;  // Halter abmelden
    Barrier       barrier        = 11;  // Stream-Barriere
    ArrivalPrepared arrival_prepared = 12; // nur von Paper, Antwort auf PrepareArrival
    PlayerArrived player_arrived   = 13; // nur von Paper
    ArrivalFailed arrival_failed     = 14; // nur von Paper
    ConnectResult connect_result     = 15; // nur vom Proxy, Antwort auf ConnectPlayer
  }
}

message ServiceMessage {
  oneof payload {
    Welcome         welcome          = 1;   // Antwort auf Hello, mit vollständiger Presence
    PresenceChanged presence_changed = 2;
    DocChanged      doc_changed      = 3;
    Deliver         deliver          = 4;   // nur an Proxys
    LocationRequest location_request = 5;
    KickPlayer      kick_player      = 6;   // nur an Proxys
    BarrierAck      barrier_ack      = 7;   // Antwort auf Barrier
    PrepareArrival  prepare_arrival  = 8;   // nur an Paper
    CancelArrival   cancel_arrival   = 9;   // nur an Paper
    ConnectPlayer   connect_player   = 10;  // nur an Proxys
  }
}

message Hello {
  string          node_id        = 1;   // eindeutiger Name, z. B. 'proxy-01' oder 'lobby-01'
  NodeKind        kind           = 2;   // PAPER oder VELOCITY
  repeated string held_players   = 3;   // nach einem Reconnect: wieder Halter werden
  repeated string watched_stores = 4;
  repeated PresenceEntry players = 5;   // nur vom Proxy: alle verbundenen Spieler
}

message Heartbeat {
  repeated string        held_players   = 1;
  repeated PresenceEntry players        = 2;   // nur vom Proxy
  repeated string        watched_stores = 3;   // auch Store-Halter reparieren sich nach höchstens 10 s
}

message TeleportRequest  { string player = 1; string server = 2; NetworkLocation location = 3; } // location fehlt = connect
enum TeleportStatus {
  TELEPORT_STATUS_UNSPECIFIED = 0; OK = 1; NOT_ONLINE = 2; SERVER_UNAVAILABLE = 3; WORLD_NOT_FOUND = 4;
  CONNECT_FAILED = 5; SUPERSEDED = 6; TIMEOUT = 7;
}
message TeleportResponse { TeleportStatus status = 1; }

message PrepareArrival  { string request_id = 1; string player = 2; NetworkLocation location = 3; }
message CancelArrival   { string request_id = 1; string player = 2; }
// Enum-Werte gelten in proto3 im ganzen Package: WORLD_NOT_FOUND steht schon in TeleportStatus, daher der Präfix.
enum ArrivalStatus { ARRIVAL_STATUS_UNSPECIFIED = 0; READY = 1; ARRIVAL_WORLD_NOT_FOUND = 2; ALREADY_HERE = 3; }
message ArrivalPrepared { string request_id = 1; ArrivalStatus status = 2; }
message PlayerArrived   { string request_id = 1; }
message ArrivalFailed   { string request_id = 1; }
message ConnectPlayer   { string request_id = 1; string player = 2; string server = 3; }
enum ConnectStatus { CONNECT_STATUS_UNSPECIFIED = 0; CONNECTED = 1; ALREADY_CONNECTED = 2; FAILED = 3; PLAYER_NOT_FOUND = 4; }
message ConnectResult   { string request_id = 1; ConnectStatus status = 2; }

message Barrier    { string request_id = 1; }
message BarrierAck { string request_id = 1; }   // Agora hat alle früheren Nachrichten dieses Streams verarbeitet
```

**Stream-Barriere.** Agora beantwortet `Barrier(request_id)` mit `BarrierAck(request_id)`, sobald alle früheren
Nachrichten desselben Streams verarbeitet sind. Pantheon nutzt sie an zwei Stellen:

- **Verbindungsaufbau.** Pantheon sendet direkt nach dem `Hello` eine Barriere. Kommt das `BarrierAck`, gilt der Node
  als verbunden (`CONNECTED`): Token geprüft, `Hello` verarbeitet. Auf `Welcome` wartet Pantheon nicht, es ersetzt nur
  die Presence-Kopie. Ein Stream, den Agora mit `UNAUTHENTICATED` beendet, erreicht nie `CONNECTED`, das ist ein
  Konfigurationsfehler (`FATAL`, [pantheon.md](pantheon.md#agora-verbindung)).
- **Neu laden nach `releasePlayer`.** `releasePlayer` läuft über den Stream, `LoadPlayer` ist ein einfacher Aufruf und
  könnte ihn überholen: Ein spätes Release würde den frischen Halter wieder entfernen. Hat ein Node für eine UUID
  gerade `releasePlayer` gesendet, wartet er vor dem nächsten `LoadPlayer` eine Barriere ab. Zusätzlich merkt sich Agora
  einen Release für die Dauer der Kulanzzeit (§4), damit ein veralteter Heartbeat den Halter nicht zurückbringt.

**Node-Namen** sind im Netzwerk eindeutig und kommen aus der Konfiguration des Nodes. Ein Paper-Server heißt genau so
wie in der Server-Liste der Proxys, damit der Server aus der Presence auf seinen Node zeigt, etwa für `location()`.
Meldet sich ein Name an, der schon verbunden ist, etwa nach einem schnellen Neustart, ersetzt die neue Verbindung die
alte, und der Dienst schließt den alten Stream.

**Der Stream ist die Lebendmeldung.** Bricht er ab, ist der Node weg: Der Dienst entfernt ihn als Halter und, wenn es
ein Proxy war, dessen Spieler aus der Presence. Eigene Pings braucht es nicht, gRPC-Keepalive erkennt tote
Verbindungen.

**Heartbeat mit vollständigem Zustand.** Alle 10 Sekunden schickt jeder Node, welche Spieler er hält und welche Stores
er beobachtet, ein Proxy zusätzlich alle seine Spieler mit Server. Der Dienst gleicht ab und korrigiert. Ein verlorenes Join oder Quit
repariert sich so spätestens nach 10 Sekunden.

**Zustellung.** Auf einer bestehenden Verbindung kommen Nachrichten vollständig und in Reihenfolge an. Verloren gehen
kann nur etwas, wenn der Stream abbricht. Dann verbindet sich der Node neu, schickt `Hello` mit seinem Zustand und
gleicht seine Dokumente per `versions` ab (§5). Einen periodischen Abgleich, einen Puffer oder Sequenznummern gibt es
deshalb nicht.

**Backpressure.** Kommt ein Node mit dem Lesen nicht hinterher und wächst seine Warteschlange im Dienst über eine
Grenze (10.000 Nachrichten), schließt der Dienst den Stream. Der Node verbindet sich neu und gleicht ab. Das ist
derselbe Weg wie bei jedem anderen Abbruch.

---

## 4. Presence

| Ereignis                     | Wer meldet                  | Was der Dienst tut                                  |
|------------------------------|-----------------------------|-----------------------------------------------------|
| Spieler betritt das Netzwerk | Proxy: `ClaimPresence`      | Doppelten Login auflösen (siehe unten), Eintrag anlegen, `PresenceChanged` an alle Nodes |
| Serverwechsel                | Proxy: `PlayerSwitched`     | Server ändern, `PresenceChanged` an alle Nodes      |
| Spieler verlässt das Netzwerk | Proxy: `PlayerLeft`        | Eintrag entfernen, `PresenceChanged` an alle Nodes  |
| Proxy-Stream bricht ab       | —                           | Alle Spieler dieses Proxys entfernen                |

- **Agora schreibt dazu Session-Einträge** ins Audit-Log ([audit_log.md §5](audit_log.md#5-was-pantheon-selbst-loggt)):
  `pantheon:join` beim ersten `PlayerSwitched`, `pantheon:switch` bei jedem weiteren, `pantheon:quit` bei `PlayerLeft`,
  beim Verdrängen (`reason = replaced`) und beim Abbruch des Proxy-Streams (`reason = proxy_lost`). Abgleiche aus dem
  Heartbeat und der Neuaufbau aus `Hello` erzeugen keine Einträge. Agora puffert sie intern und schreibt sie spätestens
  jede Sekunde, die Presence wartet nie auf Postgres.
- **Der Proxy ist die Quelle.** Er weiß als einziger sicher, wer verbunden ist und auf welchem Server. Paper-Server
  melden keine Presence.
- **Mehrere Proxys** (`proxy-01`, `proxy-02`, …). Ein Spieler gehört zu dem Proxy, über den er sich verbunden hat. Nur
  dieser Proxy meldet seinen Serverwechsel und Quit, nur an ihn gehen Nachrichten für den Spieler. Fällt ein Proxy
  aus, verschwinden nur dessen Spieler.
- Ein Eintrag enthält UUID, Name, Server, Proxy, Sprache und „online seit“. Der Server ist leer, bis der Proxy den
  Spieler mit dem ersten Server verbunden hat (`PlayerSwitched`), etwa 100 Byte. **Jeder Node hält eine
  vollständige Kopie.** Deshalb sind `isOnline`, `server()`, `name()` und `lang()` überall synchron.
- Die Sprache stammt aus dem Client des Spielers bzw. seiner gespeicherten Einstellung, siehe
  [language.md §8](language.md#8-sprache-eines-spielers). Sie kommt aus dem Login und wird in v1 nicht aktualisiert,
  ein Sprachwechsel erscheint erst beim nächsten Login in der Presence.
- **Kulanz:** Ein Heartbeat entfernt keine Einträge und Halter, die jünger als 10 Sekunden sind, denn er kann gebaut
  worden sein, bevor der neue Spieler drin war.
- **Neustart des Dienstes:** Die Presence ist danach leer. Jeder Proxy schickt nach dem Reconnect alle seine Spieler
  im `Hello` mit, jeder Node bekommt mit `Welcome` die vollständige Presence und ersetzt seine Kopie. Bis dahin arbeiten
  die Nodes mit ihrer alten Kopie weiter. PAPER-Nodes bekommen `Welcome` frühestens 15 Sekunden nach dem Start von
  Agora (`AgoraConfig.paperWelcomeDelay`, in Tests 0), damit ein früher Paper-Node nicht eine fast leere Presence
  übernimmt. Proxys bekommen es sofort. Doppelte Einträge können dabei nicht entstehen, weil Logins ohne Agora
  abgelehnt werden.

### Login und doppelter Login

Der Proxy **fragt** beim Login, statt nur zu melden. Im `LoginEvent` von Velocity ruft er `ClaimPresence` auf und
lässt den Spieler erst nach der Antwort herein. Ist der Spieler schon über einen anderen Proxy online, gewinnt der
neue Login, wie in Vanilla:

```
 proxy-01                           Agora                           proxy-02
 │  Spieler online                    │                                  │
 │                                    │◄──── ClaimPresence(Steve) ───────┤  Login von Steve
 │◄──── KickPlayer(Steve) ────────────┤                                  │
 │  kickt: „Von einem anderen Ort     │                                  │
 │  angemeldet“                       │                                  │
 ├──── KickResult(war online) ───────►│  alten Eintrag entfernen,        │
 │                                    │  neuen anlegen                   │
 │                                    ├──── ClaimPresence: OK ──────────►│  lässt Steve herein
```

- **Geister:** Steht ein Eintrag nur noch in der Presence, antwortet der alte Proxy „war nicht online“. Agora
  entfernt den Eintrag trotzdem, der neue Login klappt sofort.
- **Alter Proxy antwortet nicht** innerhalb von 3 Sekunden oder hat keinen Stream: Agora entfernt den Eintrag und
  bestätigt den neuen Login. Ein hängender Proxy darf niemanden aussperren.
- **Gleichzeitige Logins** desselben Spielers über zwei Proxys serialisiert Agora pro UUID. Der zweite Claim kickt den
  ersten.
- **Innerhalb eines Proxys** regelt Velocity doppelte Logins selbst. Agora sieht nur Claims über verschiedene Proxys.
- **Agora nicht erreichbar:** `ClaimPresence` scheitert, der Login wird abgelehnt (`CommonMessage.DATA_UNAVAILABLE`).

### Position eines Spielers

`PresenceBackend#location` ruft den einfachen Aufruf `Locate(player)` auf. Agora schlägt den Server des Spielers in der
Presence nach, schickt dessen Node ein `LocationRequest` und wartet höchstens 3 Sekunden auf das `LocationReply`
(ohne einen Thread zu blockieren).

| Fall                                                                      | Antwort                            | Pantheon                                  |
|---------------------------------------------------------------------------|------------------------------------|-------------------------------------------|
| Spieler offline, noch ohne Server oder Server meldet „nicht gefunden“       | `found = false`                    | Spieler nicht online                      |
| Server ohne Stream, keine Antwort in 3 s, Stream des Servers endet/ersetzt | `UNAVAILABLE` (oder `DEADLINE_EXCEEDED` am Client) | `ExpectedOnlinePlayerException(NETWORK)` |

Die Spielerdaten brauchen dafür nichts Besonderes. Der alte Paper-Server meldet beim Quit `releasePlayer`, der neue
wird beim Laden Halter. Überschneiden sich beide kurz, gilt dasselbe wie beim Serverwechsel (§5).

### Teleport

`PanPlayer#teleport` und `#connect` laufen über den einfachen Aufruf `Teleport(player, server, location?)`. `location`
fehlt bei `connect`. Aufrufen kann ihn nur ein `PAPER`-Node, andere bekommen `PERMISSION_DENIED`. Agora führt den
Ablauf selbst (ein Zustandsautomat pro Spieler) und antwortet erst am Ende. `NetworkLocation.server` muss dem `server`
des Requests entsprechen, sonst `INVALID_ARGUMENT`. Liegen Spieler und Ziel auf dem aufrufenden Server, ist es ein lokaler
Teleport ohne Agora.

```
 Aufrufer                    Agora                     Ziel (Paper)                Proxy des Spielers
 │ Teleport(p, loc) ───────►│                           │                             │
 │                          ├── PrepareArrival ────────►│ Welt da? Ort merken (TTL)   │
 │                          │◄── ArrivalPrepared ───────┤                             │
 │                          ├── ConnectPlayer ──────────┼────────────────────────────►│ connect()
 │                          │                           │ Spawn: Ort setzen, Join     │
 │                          │◄── PlayerArrived ─────────┤                             │
 │                          │◄── ConnectResult ─────────┼─────────────────────────────┤
 │◄── TeleportResponse(OK) ─┤                           │                             │
```

1. **Prüfen.** Presence-Eintrag mit Server fehlt → `NOT_ONLINE`. Zielserver ohne offene `PAPER`-Session oder Proxy ohne
   offene Session → `SERVER_UNAVAILABLE`.
2. **`PrepareArrival`** an das Ziel, auch wenn die Presence den Spieler schon dort sieht (sie kann veraltet sein).
   Antwort `ArrivalPrepared`: `READY` (Eintrag mit TTL angelegt), `ARRIVAL_WORLD_NOT_FOUND` → `WORLD_NOT_FOUND`,
   `ALREADY_HERE` (der Spieler ist beim Eintreffen schon lokal, das Ziel teleportiert selbst und meldet danach
   `PlayerArrived` oder `ArrivalFailed`, Agora schickt kein Connect), `ARRIVAL_STATUS_UNSPECIFIED` (der Node nimmt keine
   Ankünfte an) → `SERVER_UNAVAILABLE`.
3. **`ConnectPlayer`** an den Proxy, der `createConnectionRequest(server).connect()` ruft. `ConnectResult`:
   `CONNECTED` und `ALREADY_CONNECTED` → weiter auf `PlayerArrived` warten, `FAILED` → `CONNECT_FAILED`,
   `PLAYER_NOT_FOUND` → `NOT_ONLINE`.
4. **Ankunft.** Das Ziel setzt den Ort schon im `AsyncPlayerSpawnLocationEvent`, also ohne sichtbaren Sprung. Kam das
   `PrepareArrival` erst nach diesem Event, teleportiert es nach dem Join (Rückfall: lieber kurz springen als am
   falschen Ort stehen). Danach sendet es `PlayerArrived` (`ArrivalFailed` → `CONNECT_FAILED`). `PlayerArrived` kann vor
   dem `ConnectResult` ankommen, Agora akzeptiert beide Reihenfolgen. Es nimmt `PlayerArrived` und `ArrivalFailed` auch
   schon an, solange es noch auf `ArrivalPrepared` wartet: Bei `ALREADY_HERE` laufen Antwort und Report auf Paper in
   getrennten Aufgaben. Ein `ConnectResult(FAILED)` nach `PlayerArrived` ändert das Ergebnis nicht.
5. **Aufräumen.** Endet ein Teleport nach dem gesendeten `PrepareArrival` mit etwas anderem als `OK` (auch bei
   `TIMEOUT` ohne `ArrivalPrepared`), schickt Agora dem Ziel `CancelArrival(request_id)`, solange dessen Session noch
   offen ist. Das Ziel löscht den Eintrag nur bei passender `request_id`. Bleibt eine Lücke: Kommt der Spieler zwischen
   Timeout und Cancel doch noch an, steht er am Ort, der Aufrufer hat aber `TIMEOUT` bekommen.

**Zeiten.** Alle Grenzen sind in Agora einstellbar (Umgebungsvariablen, siehe Agora-README).

| Schritt | Grenze | Variable | Bei Ablauf |
|---|---|---|---|
| `PrepareArrival` → `ArrivalPrepared` | 3 s | `AGORA_TELEPORT_PREPARE_MS` | `TIMEOUT` |
| `ConnectPlayer` → `ConnectResult` | 10 s | `AGORA_TELEPORT_CONNECT_MS` | `TIMEOUT` |
| `ConnectPlayer` gesendet (bzw. `ALREADY_HERE`) → `PlayerArrived` | 25 s | `AGORA_TELEPORT_ARRIVAL_MS` | `TIMEOUT` |
| Gesamt ab Eingang des Aufrufs | 38 s | `AGORA_TELEPORT_TOTAL_MS` | `TIMEOUT` |
| vorgemerkter Ort auf dem Ziel | 35 s ab `PrepareArrival` | fest in Pantheon | Eintrag verfällt, normaler Spawn |
| offenes `ConnectPlayer` (Wartezeit eines Folge-Teleports) | Default 10 s ab Senden, gleich `AGORA_TELEPORT_CONNECT_MS` | `AGORA_TELEPORT_CONNECT_MS` | weiter, als wäre es da |

Der Client in Pantheon hat für `Teleport` eine eigene Deadline von 40 s, damit normalerweise Agora das Ergebnis
bestimmt. `ConnectResult` kommt erst nach dem JoinGame auf dem Ziel, und davor wartet der Login bis zu
`PANTHEON_LOGIN_TIMEOUT_MS + 1 s` auf die Daten. Wer den Login-Timeout über etwa 8 s setzt, erhöht
`AGORA_TELEPORT_CONNECT_MS` mit ([pantheon.md](pantheon.md#agora-verbindung)).

**Ein Teleport pro Spieler.** Agora hält pro Spieler höchstens einen laufenden Teleport, der neueste gewinnt. Der
alte endet mit `SUPERSEDED` (und bekommt sein `CancelArrival`). Ist von ihm oder einem früheren Teleport noch ein
`ConnectPlayer` offen, wartet der neue Teleport mit seinem **ganzen** Start (schon dem `PrepareArrival`) auf dessen
`ConnectResult`, höchstens `AGORA_TELEPORT_CONNECT_MS` (Default 10 s) ab dem Senden. Eine Erhöhung dieser Variable verlängert also auch die Wartezeit eines Folge-Teleports. Velocity lehnt einen zweiten Connect sonst mit
`CONNECTION_IN_PROGRESS` ab, und der alte Connect könnte den Spieler nach einem `OK` des neuen wieder wegziehen. Das
gilt auch, wenn der alte Teleport schon mit `OK` endete, bevor sein `ConnectResult` da war. Ein dritter Teleport löst
den wartenden sofort ab. **Grenze:** Ein lokaler Teleport (Spieler und Ziel auf dem aufrufenden Server) läuft an Agora vorbei
und löst einen laufenden Netzwerk-Teleport nicht ab ([player.md](player.md#7-lebenszyklus)).

**Wenn etwas wegfällt.** Endet die Session des Ziels, ist das `SERVER_UNAVAILABLE` (ohne Cancel), die des Proxys
`NOT_ONLINE`. Verlässt der Spieler das Netzwerk, ist es `NOT_ONLINE`. Fällt der Aufrufer weg, läuft der Ablauf zu Ende,
nur die Antwort geht ins Leere. Ist Agora nicht erreichbar, ist es `NETWORK_UNAVAILABLE`, ohne Wiederholung und ohne
Outbox. Startet Agora neu, gehen laufende Teleports verloren.

**Fehlerzuordnung im Client.** `AgoraBackend` bildet jeden Ausgang auf ein `TeleportResult` ab, es gibt nie eine
Exception (außer `NullPointerException` bei `null`).

| Ausgang | `TeleportResult` |
|---|---|
| keine Verbindung, `UNAVAILABLE` | `NETWORK_UNAVAILABLE` |
| `UNIMPLEMENTED` (Agora ist älter) | `NETWORK_UNAVAILABLE`, einmal WARN |
| `DEADLINE_EXCEEDED` | `TIMEOUT` |
| jeder andere gRPC-Status | `NETWORK_UNAVAILABLE`, WARN mit Status |
| `TeleportStatus` `UNSPECIFIED` oder unbekannt | `CONNECT_FAILED`, WARN |

Die Velocity-Plugins werden zusammen mit Agora aktualisiert: Ein alter Proxy kennt `ConnectPlayer` nicht, ignoriert es,
und der Teleport endet mit `TIMEOUT`.

---

## 5. Daten

### Aufrufe

```proto
rpc LoadPlayer  (LoadPlayerRequest)  returns (LoadPlayerResponse);
rpc Load        (LoadRequest)        returns (LoadResponse);
rpc Query       (QueryRequest)       returns (QueryResponse);
rpc Count       (CountRequest)       returns (CountResponse);
rpc Versions    (VersionsRequest)    returns (VersionsResponse);
rpc Apply       (ApplyRequest)       returns (ApplyResponse);
rpc Create      (CreateRequest)      returns (CreateResponse);
rpc Delete      (DeleteRequest)      returns (DeleteResponse);
rpc Expire      (ExpireRequest)      returns (ExpireResponse);
rpc Transfer    (TransferRequest)    returns (TransferResponse);
rpc DeclareIndex(DeclareIndexRequest) returns (DeclareIndexResponse);
// dazu Locate und Teleport (§4) und AppendAudit/Timeline ([audit_log.md](audit_log.md))

// Feldwerte als eigener Typ. google.protobuf.Struct führt alle Zahlen als double, long wäre nicht mehr exakt.
message Value {
  oneof kind {
    bool      null_value   = 1;
    bool      bool_value   = 2;
    sint64    long_value   = 3;
    double    double_value = 4;
    string    string_value = 5;
    ValueList list_value   = 6;
    ValueMap  map_value    = 7;
  }
}
```

// Jede Operation, Bedingung, Sortierung und jeder Index nennt das Feld samt Typ und Default.
message FieldSpec {
  string     namespace     = 1;
  string     name          = 2;
  ScalarType type          = 3;   // LONG, DOUBLE, BOOLEAN, STRING, LIST, SET, OBJECT
  Value      default_value = 4;   // fehlt = kein Default
}

message QuerySpec { repeated Condition where = 1; repeated Sort sort = 2; int32 limit = 3; int32 offset = 4; }
message VersionsRequest { repeated RowRef rows = 1; repeated string players = 2; }
```

- `FieldSpec` gibt Agora Typ und Default für `COALESCE` und die Casts (siehe „Defaults“).
- `QuerySpec.limit`: Default 100, Maximum 1000, sonst `INVALID_ARGUMENT`. `offset` ist nur intern, damit Pantheon
  große gecachte Stores seitenweise lädt.
- `VersionsRequest.players` fragt die Versionen **aller** Zeilen eines Spielers ab. So erkennt der Abgleich nach einem
  Reconnect auch Namespaces, die während des Ausfalls neu entstanden sind.
- **Spieler-Abfragen** haben als Basis die Zeile `pantheon` des Spielers mit einem `LEFT JOIN` pro benutztem Namespace.
  Der Default gilt damit auch für Spieler ohne Zeile im Namespace, Treffer enthalten alle Namespaces, und
  `PlayerFields.NAME` ist immer dabei. Bedingung oder Sortierung auf ein Feld aus einem fremden Namespace
  (bei Store-Abfragen) lehnt Agora mit `INVALID_ARGUMENT` ab.

Alle Aufrufe haben eine Deadline (5 s). Proto-Änderungen sind nur additiv, weil Dienst und Nodes nie gleichzeitig
deployt werden.

**Fehlercodes.** `ALREADY_EXISTS` (Beschreibung = Indexname): ein `unique` wurde verletzt, Pantheon wiederholt nicht.
`FAILED_PRECONDITION`: ein Index kann nicht gebaut werden, aus einem anderen Grund als Duplikaten. `INVALID_ARGUMENT`:
ungültige Anfrage, etwa `withdraw` nach einer nicht-additiven Operation auf dasselbe Feld, eine Summe, die `long`
überläuft, oder ein Postgres-Überlauf bei `inc` (SQLState 22003). `UNAUTHENTICATED`: Token fehlt oder falsch. Der
gRPC-Health-Service ist von der Prüfung ausgenommen.

### Halter: wer Änderungen bekommt

Der Dienst schickt ein `DocChanged` nur an Nodes, die das Dokument halten:

| Dokument               | Halter                                                                  |
|------------------------|-------------------------------------------------------------------------|
| Spielerdaten           | Jeder Node, der `LoadPlayer` aufgerufen und noch nicht `releasePlayer` gemeldet hat |
| Dokumente eines Stores | Jeder Node, der den Store per `WatchStore` beobachtet (`cached()` oder `onChange`) |

`LoadPlayer` trägt den Node **zuerst** als Halter ein und liest **danach**. Eine Änderung, die zwischen beidem
committet, steckt entweder schon im gelesenen Stand oder kommt als Event hinterher.

**Serverwechsel.** Der Proxy verbindet den Spieler zuerst mit B und trennt ihn danach von A. In dieser Zeit sind A und
B beide Halter:

```
 A                                  Dienst                              B
 │                                    │◄──── LoadPlayer (Halter: A, B) ──┤  coins = 100, v7
 │  withdraw(coins, 30) ─────────────►│  coins = 70, v9                  │
 │◄──── ApplyResponse v9 ─────────────┼──── DocChanged v9 ──────────────►│  übernimmt 70
 │  Quit: releasePlayer ─────────────►│  (Halter: B)                      │
```

Weil der Stream zuverlässig zustellt, braucht es dafür weder Übergabe-Protokoll noch Abgleich.

### Versionen und `DocChanged`

Jede Zeile trägt eine `version`, die bei jeder Änderung aus **einer gemeinsamen Sequenz** neu gezogen wird. Postgres
sperrt die Zeile bis zum Commit, also bekommt eine spätere Änderung derselben Zeile immer eine größere Version, auch
über Löschen und Neuanlegen hinweg.

```proto
message DocChanged {
  RowRef             ref             = 1;   // Spieler + Namespace bzw. Store + ID: genau eine Zeile
  int64              version         = 2;
  bool               deleted         = 3;
  map<string, Value> data            = 4;   // vollständiger Stand der Zeile, leer bei deleted
  string             idempotency_key = 5;   // Key des auslösenden Schreibzugriffs, leer bei Löschen durch `expire`
}
```

Das Event enthält den **vollständigen Stand**, nicht die Operation. Der Node ersetzt seinen Stand, wenn die Version
größer ist, und verwirft das Event sonst. Damit sind vertauschte Events, das eigene Echo und doppeltes Anwenden
harmlos. Pantheon ignoriert Events für Spieler und Stores, die der Node nicht (mehr) hält.

Der `idempotency_key` verhindert, dass das eigene Echo zusammen mit dem lokalen Wiederaufspielen der noch unterwegs
befindlichen Operationen doppelt zählt: Trägt ein Event den Key eines eigenen Requests, der noch unterwegs ist, verlassen dessen Operationen die Liste der
lokal aufzuspielenden Operationen. Sie sind im Stand des Events schon enthalten.

**Lokaler Wert auf dem Node** = zuletzt bekannter Stand + eigene unbedingte Operationen, die noch unterwegs sind. Kommt
ein neuer Stand, spielt Pantheon diese Operationen lokal erneut auf. Eine Operation fällt aus der Liste, sobald ihre
`ApplyResponse` da ist.

### Reihenfolge pro Dokument

Pantheon hat pro Dokument **höchstens einen** Schreib-Request unterwegs. Operationen, die währenddessen aufgerufen
werden, sammeln sich und gehen gemeinsam als nächster Request raus. Dadurch kommen sie in Aufrufreihenfolge an, und
`inc` pro abgebautem Block wird automatisch zusammengefasst.

### Bedingte und atomare Operationen

Ein `ApplyRequest` mit Bedingungen (`withdraw`, `require`, siehe [data.md §3](data.md#bedingte-operationen)) läuft in
**einer** Transaktion:

1. Idempotency-Key prüfen. Schon gesehen: gespeichertes Ergebnis zurückgeben.
2. Abgelaufene, noch nicht gelöschte Zeilen (`expires_at` ≤ jetzt) in derselben Transaktion löschen. Schreibzugriffe
   behandeln sie als nicht vorhanden.
3. Fehlende Zeilen leer anlegen (`INSERT … ON CONFLICT DO NOTHING`).
4. Pro betroffener Zeile ein `UPDATE … WHERE <Bedingungen>`, Zeilen in fester Reihenfolge (nach Namespace), damit es
   keine Deadlocks gibt.
5. Liefert ein `UPDATE` 0 Zeilen, ist eine Bedingung nicht erfüllt: Rollback, Ergebnis `false` (die Antwort enthält
   dann keine Zeilen).
6. Sonst Key mit Ergebnis vermerken, Commit, danach `DocChanged` an die Halter.

Bei Spielerdaten darf ein Request mehrere Namespaces umfassen, etwa `economy.coins` abbuchen und `lobby.kits`
erweitern. Das sind zwei Zeilen in einer Transaktion.

**`withdraw` wird gefaltet geprüft.** Mehrere additive Operationen (`inc`, `withdraw`) auf dasselbe Feld in einem
Request prüft Agora zusammen gegen den gespeicherten Wert, nicht Schritt für Schritt: Basis ist der gespeicherte Wert,
sonst der Default, sonst 0, dazu die Summe der Beträge. `inc(5)` und `withdraw(3)` auf einem fehlenden Feld sind also
erlaubt (Ergebnis 2). `withdraw` nach einer nicht-additiven Operation (`set`, `max` …) auf dasselbe Feld und eine
überlaufende Summe lehnt Agora als `INVALID_ARGUMENT` ab.

### Idempotenz

- Jede Schreiboperation bekommt beim Erzeugen einen `idempotency_key` (UUIDv7), der bei Wiederholungen gleich bleibt.
  Das gilt für **alle** schreibenden Aufrufe: `Apply`, `Create`, `Delete`, `Expire` und `Transfer`. Eine Wiederholung
  bekommt das gespeicherte Ergebnis zurück.
- Was einmal verschickt wurde, wird nicht mehr verändert. Neue Operationen kommen in den nächsten Request.
- Der Dienst speichert Keys mit Ergebnis **24 Stunden**. Pantheon schickt einen Request höchstens **20 Stunden** nach
  seiner Erzeugung, auch aus der Outbox (§8). Die Aufbewahrung muss immer deutlich länger sein als die Wiederhol-Dauer,
  sonst würde ein später Request doppelt ausgeführt.

### Defaults

Defaults stehen in der Felddeklaration im Plugin, nicht in der Datenbank. Damit `inc`, `withdraw`, Abfragen und
Sortierung trotzdem richtig rechnen, schickt Pantheon den Default jedes betroffenen Felds mit, und der Dienst setzt
ihn per `COALESCE` ein:

```sql
COALESCE((data->>'level')::int, 1)        -- Feld LEVEL mit Default 1
```

Defaults, Namespace und Store schreibt Agora als validierte, typisierte SQL-Literale in die Abfrage (keine
`?`-Parameter), damit der Ausdruck dem eines Index entspricht. Die Werte von Operationen und Bedingungen bleiben
gebunden. NaN und Infinity sind als `DOUBLE`-Default ungültig.

Daraus folgt: **Ein Default lässt sich nicht nachträglich ändern**, ohne dass alte Dokumente plötzlich anders
rechnen. Ein neuer Default ist ein neues Feld ([data.md §7](data.md#7-felder-ändern-sich)).

### Semantik von Operationen und Bedingungen

`InMemoryNetwork` und Agora rechnen identisch (die Contract-Suite prüft es):

- `set(null)` ist `unset`. In JSONB steht nie ein JSON-`null`. `exists` heißt: Schlüssel vorhanden.
- Operationen auf einem fehlenden Feld starten beim Default. Ohne Default starten `inc` und `withdraw` bei 0,
  `max`/`min` ergeben den Wert der Operation. Das Ergebnis wird geschrieben.
- `add` auf einem SET fügt nur ein, was fehlt, auf einer LIST hängt es an. `remove` entfernt alle Vorkommen.
- **Vergleiche mit fehlendem Wert** sind `false`, `not(p)` ist „p ist nicht true“. Eine Bedingung liefert nie SQL-NULL,
  sondern immer `true` oder `false`.
- `contains` auf SET/LIST ist **Element-Gleichheit** (tief, JSON-Gleichheit), nicht die Teilmengen-Semantik von
  Postgres `@>`.
- `eq`/`ne` auf SET-Feldern vergleichen reihenfolgeunabhängig, auf LIST reihenfolgeabhängig. `in` auf SET ist ein ODER
  aus solchen Mengen-Gleichheiten, auf LIST und OBJECT tiefe, reihenfolgeabhängige Gleichheit. NULL-Elemente der
  Liste werden ignoriert.
- Zahlen: `1` und `1.0` sind gleich. Bei DOUBLE-Feldern kann Agora ganzzahlige Werte als `long` liefern, Pantheon
  normalisiert beim Dekodieren über den Feldtyp. `Instant` steht als Epoch-Millis, Int-Felder werden beim Lesen auf
  den int-Bereich begrenzt.
- Sortierung: `NULLS LAST`, Strings binär (`COLLATE "C"`).
- Abgelaufene Dokumente gelten bei Schreibzugriffen als nicht vorhanden: `create` gelingt, `apply` und `transfer`
  starten bei den Defaults, `delete` liefert `false`, `unique` ignoriert sie. `apply` und `transfer` auf ein fehlendes
  Store-Dokument legen es an. Ein `create`, dessen ID existiert, liefert `created = false` ohne Zeile. `expire` löst
  selbst kein `DocChanged` aus.

### Indizes

Plugins **deklarieren** Indizes (`index`, `unique`). Der Dienst führt eine Liste in `pantheon_index` und legt jeden
Index **einmal** an, mit `CREATE INDEX CONCURRENTLY`, ohne die Tabelle zu sperren. Weitere Deklarationen desselben
Index sind wirkungslos.

- Scheitert ein `unique` an vorhandenen Duplikaten, steht der Index auf `failed` mit Fehlermeldung, und das Future der
  Deklaration scheitert mit `DuplicateValueException` (`ALREADY_EXISTS`). Ein gescheiterter Index wird bei der
  nächsten Deklaration erneut versucht.
- **Gleicher Name, andere Definition** (Unique, Typ, Default, Ausdruck): Agora löscht den alten Index und baut ihn neu.
- **Indexnamen** sind auf 63 Zeichen begrenzt (Postgres). Längere Namen kürzt Agora auf die ersten 54 Zeichen, `_` und
  8 Hex-Zeichen aus SHA-256.
- **`unique` gilt nur für ausdrücklich gesetzte Werte.** Dokumente und Spieler ohne das Feld (Default) kollidieren nie.
  Der Unique-Index ist dazu partiell (siehe §7).
- Indizes werden nicht automatisch entfernt. Nicht mehr gebrauchte löscht ein Admin von Hand.

### `expire`

Postgres kennt keine TTL. Der Dienst setzt `expires_at`, filtert abgelaufene Dokumente beim Lesen heraus und löscht
sie einmal pro Minute. Jede Löschung geht als `DocChanged` mit `deleted = true` an die Halter.

---

## 6. Nachrichten

Nachrichten an Spieler auf anderen Servern stellt **der Proxy** zu, nicht der Paper-Server des Spielers:

1. Der sendende Node rendert die Nachricht mit der Sprache aus der Presence
   ([language.md](language.md)).
2. Er schickt das fertige Component samt Typ (Chat, Action Bar, Title) und Sound als `SendDelivery` über seinen
   Stream an den Dienst, Fire-and-forget und in Reihenfolge.
3. Der Dienst schlägt in der Presence nach, über welchen Proxy der Spieler verbunden ist, und schickt ein `Deliver`
   an diesen Proxy.
4. Der Proxy sendet es über Velocity direkt an den Spieler.

Der Proxy hält die Verbindung des Spielers über jeden Serverwechsel hinweg. Eine Nachricht während eines Wechsels geht
deshalb nicht verloren. Ist der Spieler offline, verwirft der Dienst sie und schreibt einen Hinweis ins Debug-Log.

Spieler auf demselben Server bekommen Nachrichten direkt, ohne den Dienst.

---

## 7. Postgres

Die Tabellen gehören dem Dienst und kommen über seine Flyway-Migrationen. Alle tragen den Präfix `pantheon_`.

```sql
CREATE SEQUENCE pantheon_version;

-- Spielerdaten: eine Zeile pro Spieler und Namespace
CREATE TABLE pantheon_player (
    player     UUID        NOT NULL,
    namespace  TEXT        NOT NULL,
    data       JSONB       NOT NULL DEFAULT '{}',
    version    BIGINT      NOT NULL DEFAULT nextval('pantheon_version'),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (player, namespace)
);

-- Stores: eine Zeile pro Dokument
CREATE TABLE pantheon_doc (
    store      TEXT        NOT NULL,              -- '<namespace>:<store>', z. B. 'lobby:clans'
    id         TEXT        NOT NULL,
    data       JSONB       NOT NULL DEFAULT '{}',
    version    BIGINT      NOT NULL DEFAULT nextval('pantheon_version'),
    expires_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (store, id)
);

-- Bereits ausgeführte Schreiboperationen
CREATE TABLE pantheon_idempotency (
    idempotency_key TEXT        PRIMARY KEY,
    result          JSONB       NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Deklarierte Indizes
CREATE TABLE pantheon_index (
    name       TEXT        PRIMARY KEY,           -- z. B. 'pantheon_player_economy_coins'
    definition TEXT        NOT NULL,
    status     TEXT        NOT NULL,              -- 'pending', 'ready', 'failed'
    error      TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

**Eine Zeile pro Spieler und Namespace:** Postgres schreibt bei jedem Update die ganze Zeile neu. Kleine Zeilen halten
das billig, Namespaces sperren sich gegenseitig nicht, und ein `DocChanged` enthält nur einen Namespace. Der Login
bleibt ein einziger Zugriff: `SELECT namespace, data, version FROM pantheon_player WHERE player = $1`.

Der zuletzt bekannte Name eines Spielers (`PlayerFields.NAME`) liegt in der Zeile mit `namespace = 'pantheon'`.
Ranglisten holen ihn per JOIN dazu.

Das Audit-Log liegt in der Tabelle `pantheon_audit`, partitioniert nach Tag und nach UUIDv7-`id`. Schema, Partitionen
(von gestern bis heute plus zwei Tage, Aufräumen per `DROP`, 14 Tage Aufbewahrung) und die Zeitachsen-Abfrage stehen in
[audit_log.md §9](audit_log.md#9-postgres). Sie kommt wie alle Tabellen über Flyway (`V2__audit.sql`), die
Partitionen legt Agora selbst an.

### Operationen

Jede Operation ist Teil eines `UPDATE` auf eine Zeile und setzt dabei eine neue `version`. `$d` ist der mitgeschickte
Default des Felds.

| Operation       | SQL (vereinfacht)                                                                                 |
|-----------------|----------------------------------------------------------------------------------------------------|
| `set`           | `data = jsonb_set(data, '{home}', $value)`                                                         |
| `unset`         | `data = data - 'home'`                                                                             |
| `inc`           | `data = jsonb_set(data, '{coins}', to_jsonb(COALESCE((data->>'coins')::bigint, $d) + $delta))`    |
| `withdraw`      | wie `inc` mit `-$amount`, plus `WHERE COALESCE((data->>'coins')::bigint, $d) >= $amount`           |
| `max` / `min`   | `to_jsonb(GREATEST(…))` bzw. `LEAST(…)`                                                           |
| `add` / `remove`| `pantheon_set_add(data, 'kits', $element)` bzw. `pantheon_set_remove(…)`, zwei kleine SQL-Funktionen aus der Migration |
| `create`        | `INSERT … ON CONFLICT DO NOTHING`; 0 Zeilen = ID vergeben                                          |
| `transfer`      | Transaktion: `withdraw` beim Sender, `inc` beim Empfänger, beides oder nichts                      |

Mehrere Operationen auf dieselbe Zeile werden zu **einem** `UPDATE` mit verschachtelten `jsonb_set`.

**Zahlen** stehen in JSONB als `numeric` und bleiben dadurch exakt. Der Dienst liest sie mit `::bigint` bzw. `::int`.

### Indizes

Ausdrucks-Indizes gelten nur für ihren Namespace bzw. Store und enthalten denselben `COALESCE` wie die Abfragen, sonst
benutzt Postgres sie nicht:

Die Form ist auf den Hauptfall zugeschnitten, die Rangliste mit absteigender Sortierung: Ausdruck `DESC NULLS LAST`,
danach ein Tie-Break (bei Stores `id COLLATE "C"`, bei Spielern die UUID). Aufsteigende Sortierungen nutzen diesen
Index nicht. **Unique-Indizes** sind partiell, enthalten nur den rohen Ausdruck ohne `COALESCE` und ohne Tie-Break und
gelten nur für Zeilen, in denen das Feld vorhanden ist:

```sql
CREATE INDEX CONCURRENTLY IF NOT EXISTS pantheon_player_economy_coins
    ON pantheon_player ((COALESCE((data->>'coins')::bigint, 0)) DESC NULLS LAST, player ASC)
    WHERE namespace = 'economy';

CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS pantheon_doc_lobby_clans_owner
    ON pantheon_doc ((data->>'owner')) WHERE store = 'lobby:clans' AND (data->'owner') IS NOT NULL;
```

Der Insert einer Zeile nutzt `ON CONFLICT (store, id)` bzw. `(player, namespace)`, damit ein Unique-Konflikt nie
verschluckt wird. Reservierte SQL-Aliase in Abfragen sind `pantheon_l`, `pantheon_r` und `pantheon_e`.

### Verbindung

PostgreSQL 17. Ein HikariCP-Pool direkt zu Postgres (Default 16 Verbindungen). PgBouncer ist nicht nötig, weil es nur
einen Client gibt.

---

## 8. Wenn Agora weg ist

**Ausfall schränkt Funktionen ein, gefährdet aber keine Daten.**

| Funktion                                  | Verhalten ohne Dienst                                                       |
|-------------------------------------------|-----------------------------------------------------------------------------|
| Lesen aus dem Speicher, Presence          | Läuft weiter, die Werte werden nur alt.                                     |
| Nachrichten an Spieler auf diesem Server  | Laufen weiter.                                                              |
| Nachrichten an Spieler auf anderen Servern | Gehen verloren.                                                            |
| Neue Logins                               | Werden abgelehnt (`CommonMessage.DATA_UNAVAILABLE`).                        |
| `set`, `inc`, `add` …                     | Wirken lokal, landen in der lokalen Outbox und werden nachgeholt, sobald Agora wieder da ist. |
| `withdraw`, `atomic`, `transfer`, `create`, Abfragen | Futures scheitern sofort mit `DataUnavailableException`. Lieber „geht gerade nicht“ als ein Kauf, der verloren gehen kann. |

Nach dem Neustart verbinden sich alle Nodes neu, schicken `Hello` mit ihrem Zustand, holen ihre Outbox nach und
gleichen ihre Dokumente per `versions` ab.

### Lokale Outbox

Unbestätigte Schreibzugriffe sollen einen Neustart des Servers überstehen, etwa wenn Agora bei einem Reboot vor den
Paper-Servern herunterfährt. Dafür schreibt Pantheon sie in eine lokale SQLite-Datei (`plugins/Pantheon/outbox.db`).

**Wann geschrieben wird.** Nur, was nicht sofort bestätigt wird:

- Scheitert ein unbedingter Request (Agora nicht erreichbar, Deadline überschritten), kommen er und alle danach
  gesammelten Operationen für dasselbe Dokument sofort in die Outbox. Es gibt keinen zweiten Wiederholungspfad im
  Speicher. Der Drainer wiederholt mit Backoff von 1 bis 30 Sekunden.
- Solange für ein Dokument Einträge in der Outbox stehen, kommen neue Operationen dahinter. So bleibt die Reihenfolge
  pro Dokument erhalten.
- Beim Stoppen des Servers wartet Pantheon bis zu 10 Sekunden auf laufende Requests und schreibt den Rest in die
  Outbox. Der letzte Versand des Audit-Puffers (höchstens 2 Sekunden, [audit_log.md §7](audit_log.md#7-puffer-und-versand))
  läuft parallel dazu innerhalb derselben 10 Sekunden. Audit-Einträge, die dann noch im Puffer stehen, sind verloren,
  eine Outbox gibt es für sie nicht.

Im Normalbetrieb kostet die Outbox also nichts. Bei einem harten Absturz (`kill -9`, Stromausfall) gehen nur Requests
verloren, die gerade unterwegs waren und noch nicht in der Outbox standen, also Millisekunden.

```sql
CREATE TABLE outbox (
    seq             INTEGER PRIMARY KEY AUTOINCREMENT,
    idempotency_key TEXT    NOT NULL UNIQUE,
    doc             TEXT    NOT NULL,     -- z. B. 'player:<uuid>' oder 'lobby:clans/<id>'
    request         BLOB    NOT NULL,     -- serialisierter ApplyRequest, wird nie mehr verändert
    created_at      INTEGER NOT NULL      -- epoch millis
);

CREATE TABLE outbox_dead (                -- zu alt zum Nachholen, wartet auf einen Admin
    idempotency_key TEXT    PRIMARY KEY,
    doc             TEXT    NOT NULL,
    request         BLOB    NOT NULL,
    created_at      INTEGER NOT NULL
);
```

**Nachholen.** Sobald Agora erreichbar ist, auch direkt nach dem Start des Servers, schickt Pantheon die Einträge pro
Dokument in `seq`-Reihenfolge und löscht jeden nach der Bestätigung. War ein Request vor dem Absturz doch schon
angekommen, erkennt Agora ihn am Idempotency-Key und führt ihn nicht noch einmal aus.

**Abgelehnte Einträge.** Verstößt ein unbedingtes `set` gegen `unique` (`ALREADY_EXISTS`) oder lehnt Agora es als
ungültig ab (`INVALID_ARGUMENT`), wird es nicht wiederholt: Pantheon loggt einen Fehler und setzt den lokalen Wert
zurück. Umfasst der Request mehrere gebündelte Aufrufe, teilt Pantheon ihn auf und schickt jeden einzeln mit neuem
Key. Nur der schuldige Aufruf wird verworfen. `withdraw` kommt nur in bedingten Requests vor, dort bedeutet
`applied = false` eine Ablehnung. Ein bedingter Request wird erst nach den vor ihm eingereihten unbedingten
Operationen desselben Dokuments gesendet.

**Zu alte Einträge.** Ein Eintrag, der älter als 20 Stunden ist, wird nicht mehr geschickt, weil Agora seinen Key nach
24 Stunden löscht und ihn sonst doppelt ausführen könnte. Er wandert nach `outbox_dead`, und Pantheon loggt einen
Fehler mit dem Inhalt, damit ein Admin entscheidet.

**Was die Outbox nicht abdeckt:**

- **Bedingte Operationen** (`withdraw`, `atomic`, `transfer`, `create`) kommen nie hinein. Sie scheitern ohne Agora
  sofort, siehe oben.
- **Server ohne dauerhaftes Datenverzeichnis**, etwa ein Container ohne Volume, verlieren die Outbox mit dem
  Container. Paper-Server brauchen deshalb ein persistentes `plugins/Pantheon`. Kommt ein Server nie wieder, bleibt
  seine Outbox liegen.
- **`set` beim Nachholen** überschreibt einen Wert, den ein anderer Server inzwischen gesetzt hat, weil er später
  ankommt. `inc`, `add` usw. verrechnen sich dagegen korrekt. Bei Spielerdaten ist das kaum möglich, weil ohne Agora
  kein Serverwechsel klappt. Bei Store-Dokumenten ist es in Kauf genommen.

Proxys brauchen die Outbox nur, wenn Plugins auf ihnen Daten schreiben.

---

## 9. Verbindungen und Sicherheit

- **Ein `ManagedChannel` pro Node** für Stream und Aufrufe. Auf Paper und Velocity `grpc-netty-shaded`, damit es
  nicht mit deren eigenem Netty kollidiert.
- **Deadlines** auf jedem Aufruf (z. B. 5 s). Kein Thread eines Nodes wartet ohne Deadline.
- **Keepalive** alle 10 s, damit ein toter Stream schnell auffällt.
- **Authentifizierung:** ein Token pro Node aus der Konfiguration des Nodes, geprüft bei jedem Aufruf. Wie Pantheon
  Adresse, Node-Name und Token erhält, steht in [pantheon.md, Agora-Verbindung](pantheon.md#agora-verbindung).
  Agora liest die erlaubten Tokens aus einer Datei (`AGORA_TOKENS_FILE`): eine Zeile `node_id=token`, `#` beginnt
  einen Kommentar. Der Node schickt bei jedem Aufruf die Header `x-agora-node: <node_id>` und
  `authorization: Bearer <token>`.
- **Größe:** Die maximale eingehende Nachricht beträgt auf beiden Seiten 16 MiB.
- **Reihenfolge beim Deployment:** Agora vor Pantheon ausrollen. Pantheon baut seine Verbindung über `Barrier` auf (§3)
  und braucht dafür ein Agora, das sie kennt.
- **Keine Rechte pro Namespace** in der ersten Version. Jeder authentifizierte Node darf alles lesen und schreiben.
  Namespaces machen eine spätere Rechteprüfung möglich ([data.md §2](data.md#namespaces)).

---

## 10. Entscheidungen

### Festgelegt

- **Eigener Microservice für Daten und Presence**, mit dem Pantheon direkt spricht.
- **Name: Agora.**
- **Audit-Log in Agora:** vierter Port `AuditBackend`, zwei Aufrufe `AppendAudit` und `Timeline`, Tabelle `pantheon_audit`,
  Session-Einträge und `pantheon:audit_view` schreibt Agora selbst ([audit_log.md](audit_log.md)).
- **Eine Instanz**, Presence und Halter im Speicher.
- **Kein Redis.** Presence und Live-Updates über den Node-Stream, Nachrichten über den Proxy.
- **Ein Stream pro Node**, der zugleich Lebendmeldung ist, plus Heartbeat mit vollständigem Zustand alle 10 s.
- **Der Proxy ist die Quelle der Presence.**
- **`DocChanged` nur an Halter**, mit vollständigem Stand und `version` aus einer gemeinsamen Sequenz.
- **Abgleich nur nach einem Reconnect**, nicht periodisch.
- **Höchstens ein Schreib-Request pro Dokument unterwegs.**
- **Idempotency-Keys 24 Stunden**, Requests werden höchstens 20 Stunden nach ihrer Erzeugung geschickt.
- **Defaults per `COALESCE` im SQL**, ein geänderter Default ist ein neues Feld.
- **Indizes als Deklaration**, angelegt mit `CONCURRENTLY`.
- **Token pro Node**, keine Rechte pro Namespace.
- **Mehrere Proxys mit eindeutigen Namen** (`proxy-01`, `proxy-02`, …). Der Proxy, über den sich ein Spieler verbindet,
  meldet ihn.
- **`CONNECTED` nach `BarrierAck`**, nicht nach `Welcome`. `Welcome` ersetzt nur die Presence-Kopie.
- **Teleport als fester Ablauf in Agora** (Ansatz A), kein allgemeiner Request/Response-Kanal für Plugins. Agora sieht
  Presence, Zielserver und Proxy als einziges, und der Ablauf überlebt den Wegfall des Aufrufers ([§4](#teleport)).
- **Ein Teleport pro Spieler, der neueste gewinnt.** Der neue wartet auf ein noch offenes `ConnectPlayer` des Spielers
  (höchstens `AGORA_TELEPORT_CONNECT_MS`, Default 10 s), weil Velocity sonst einen zweiten Connect ablehnt oder der alte Connect den Spieler wegzieht.
- **`CancelArrival` nach jedem Abbruch nach `PrepareArrival`**, solange die Session des Ziels offen ist. So landet ein
  später ankommender Spieler nie an einem alten Ort.
- **Grenzen des Teleports:** Ein lokaler Teleport (Spieler und Ziel auf dem aufrufenden Server) läuft an Agora vorbei und löst keinen Netzwerk-Teleport ab. Ein
  Teleport geht nicht in die Outbox und wird nicht wiederholt. Die Zeiten stehen in `AGORA_TELEPORT_PREPARE_MS`,
  `AGORA_TELEPORT_CONNECT_MS`, `AGORA_TELEPORT_ARRIVAL_MS` und `AGORA_TELEPORT_TOTAL_MS`.
- **Presence-Kulanz:** Heartbeats entfernen keine Einträge unter 10 Sekunden, PAPER-Nodes bekommen `Welcome` nach einem
  Agora-Start frühestens nach 15 Sekunden.
- **Alle schreibenden Aufrufe sind idempotent.**
- **Doppelter Login über zwei Proxys: die alte Verbindung wird gekickt.** Der Proxy fragt per `ClaimPresence`, Agora
  kickt über den alten Proxy und bestätigt danach den neuen Login.
- **Lokale Outbox in SQLite** für Schreibzugriffe, die nicht sofort bestätigt werden, und beim Stoppen des Servers.
  Nachgeholt bis 20 Stunden, danach `outbox_dead` und Fehler-Log. Paper-Server brauchen dafür ein persistentes
  Datenverzeichnis.
