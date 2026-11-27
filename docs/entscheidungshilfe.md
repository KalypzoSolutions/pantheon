# Entscheidungshilfe: Wohin mit meinen Daten?

Kurzfassung von [data.md](data.md) und [player.md](player.md) für die Frage, welches Werkzeug von Pantheon man nimmt.

---

## In drei Fragen zum richtigen Ort

```
1. Muss es einen Quit oder Neustart überleben?
   └─ nein ─────────────────────────────────────────► SessionData        (player.session(KEY))

2. Gehört es zu einem Spieler?
   ├─ ja, Spieler ist auf diesem Server ────────────► player.data()      (LocalPanPlayer, synchron)
   ├─ ja, Spieler ist woanders oder offline
   │    ├─ nur schreiben ───────────────────────────► players().data(uuid)   (RemoteDoc, ohne Warten)
   │    └─ lesen ───────────────────────────────────► players().load(uuid)   (Future)
   └─ nein ─────────────────────────────────────────► weiter mit 3.

3. Klein (bis einige tausend Dokumente) UND ständig synchron gelesen?
   ├─ ja ───────────────────────────────────────────► store(...).cached()  (CachedStore)
   └─ nein ─────────────────────────────────────────► store(...)           (load / doc / query)
```

`onChange` ist **kein Ort für Daten**, sondern eine Reaktion darauf. Es kommt zusätzlich zu einer der Antworten oben,
siehe [unten](#onchange-wann-reagieren).

---

## Die Optionen im Vergleich

|                         | `player.data()`                  | `CachedStore`                         | `Store` (ungecacht)                     | `SessionData`             |
|-------------------------|----------------------------------|---------------------------------------|-----------------------------------------|---------------------------|
| **Für**                 | Persistente Daten eines Spielers | Kleine, oft gelesene Mengen           | Alles andere: viele oder selten gebrauchte Dokumente | Flüchtiger Spielerzustand |
| **Beispiele**           | Coins, Kits, Home, Einstellungen | Warps, Kits-Katalog, Shop-Angebote, Event-Konfig | Clans, Plots, Tickets, Logs mit `expire` | Combat-Tag, Cooldown, offenes Menü |
| **Lesen**               | synchron                         | synchron (`get`, `all`)               | Future (`load`, `query`)                | synchron                  |
| **Schreiben**           | sofort lokal, atomar im Hintergrund | wie links                          | `doc(id)` ohne Laden, oder am geladenen `Doc` | lokal, nie gespeichert |
| **Aktuell gehalten**    | ja, auch bei Änderungen anderer Server | ja, live über alle Server       | nein: ein geladenes `Doc` ist ein Schnappschuss | –                     |
| **Im Speicher**         | solange der Spieler hier ist     | immer, komplett                       | nur, was gerade geladen ist             | bis zum Quit              |
| **Kosten**              | ein Zugriff beim Login           | Laden beim Aktivieren (blockierend, ≤ 10 s) und nach jedem Reconnect komplett | ein Zugriff pro Laden oder Abfrage | keine            |

---

## Die Kriterien im Einzelnen

### Gehört es zu einem Spieler?

Hat jeder Spieler höchstens einen Wert davon, kommt es in die **Spielerdaten**, nicht in einen eigenen Store mit der
UUID als ID. Nur so ist es beim Join garantiert geladen, synchron lesbar, beim Serverwechsel aktuell und über
`players().query()` und `rank` als Rangliste abfragbar.

Ein eigener Store ist richtig, wenn die Daten **mehreren Spielern** gehören (Clan, Party, Plot) oder **pro Spieler
viele Einträge** entstehen (Tickets, Käufe, Strafen). Dann ist die UUID ein Feld, und man sucht mit
`query().where(MEMBERS, contains(uuid))`.

### `cached()` oder nicht?

`cached()` lohnt sich, wenn **alle drei** Punkte zutreffen:

1. **Klein:** bis einige tausend Dokumente. Der ganze Store liegt auf jedem Server, der ihn benutzt.
2. **Oft gelesen:** in Events, Commands, Menüs oder Tab-Completion, wo ein Future stört.
3. **Überall gebraucht:** nicht nur für einen einzelnen Spieler oder Vorgang.

Trifft einer nicht zu, bleibt der Store ungecacht. Typische Fehlgriffe:

- **Wächst unbegrenzt** (Logs, Tickets, Plots pro Spieler): nicht cachen. Nach jedem Reconnect wird alles neu geladen.
- **Nur gelegentlich gebraucht** (`/clan info`): `load` reicht, das Future kostet wenige Millisekunden.
- **Sortieren oder Filtern über viele Dokumente:** `query()` läuft in der Datenbank, das braucht keinen Cache.

### Lesen oder nur schreiben?

Wer nur ändert, lädt nicht: `store.doc(id).inc(...)` und `players().data(uuid).inc(...)` schreiben direkt und müssen
nicht abgewartet werden. Laden nur, wenn man den Wert wirklich anzeigen oder auswerten will.

### Muss die Datenbank entscheiden?

Ob eine Änderung erlaubt ist, entscheidet nie der Wert im Speicher, auch nicht bei `player.data()` oder `CachedStore`.
Er kann veraltet sein.

| Frage                                  | Werkzeug                             |
|----------------------------------------|--------------------------------------|
| Hat er genug?                          | `withdraw`                           |
| Kaufen: abbuchen und freischalten      | `atomic().withdraw(...).require(...).add(...)` |
| Geld zwischen zwei Dokumenten          | `transfer`                           |
| Gibt es die ID schon?                  | `create` (liefert `false`)           |
| Darf ein Wert nur einmal vorkommen?    | `unique` beim Aktivieren deklarieren |

---

## `onChange`: wann reagieren

`onChange` (an `players()` oder einem `Store`) nimmt man, wenn **etwas außerhalb der Daten** mitziehen muss, sobald
sich ein Feld ändert, auch durch einen anderen Server:

- Scoreboard, Bossbar, Tab-Liste, Nametag aktualisieren
- Eine abgeleitete lokale Struktur pflegen (z. B. ein Index `Spieler → Clan` aus einem gecachten Store)
- Auf ein `/pay` oder eine Admin-Änderung von einem anderen Server reagieren

**Nicht** nehmen für:

- **Die eigene Änderung direkt danach:** Wer `inc` aufruft, kann gleich selbst das Scoreboard aktualisieren.
- **Ranglisten und Hologramme:** Ein Timer, der alle paar Sekunden `query()` neu startet, ist einfacher und
  billiger.
- **Abwarten, ob ein Kauf geklappt hat:** Dafür ist das Future von `withdraw`/`atomic` da.

Gut zu wissen:

- Listener laufen auf dem **Haupt-Thread**, Bukkit darf direkt angefasst werden. Also kurz halten.
- `players().onChange` feuert nur für Spieler **auf diesem Server**.
- Bei einem **ungecachten** Store ist `before` nur best-effort (zuletzt hier gesehener Wert, sonst der Default). Wer
  sich auf `before` verlässt, nimmt `cached()`.
- Pantheon entfernt die Abos eines Plugins nach dessen `onDisable` selbst.

---

## Beispiele

| Daten                                   | Wahl                                                  |
|-----------------------------------------|-------------------------------------------------------|
| Coins eines Spielers                    | `player.data()`, Index für `/baltop`, `onChange` fürs Scoreboard |
| `/pay` an einen Offline-Spieler         | `players().transfer(...)`                             |
| Belohnung für einen Spieler irgendwo im Netzwerk | `players().data(uuid).inc(...)`              |
| Warps                                   | `CachedStore`                                         |
| Clans                                   | `Store`, `unique(OWNER)`, `create` für den Namen      |
| Clan-Kasse                              | `Store#transfer` bzw. `doc(id).inc(...)`              |
| Tickets mit Ablauf                      | `Store` mit `expire`                                  |
| Combat-Tag, Cooldown                    | `SessionData`                                         |
| Top-10-Hologramm                        | `players().query()` per Timer, kein `onChange`        |
