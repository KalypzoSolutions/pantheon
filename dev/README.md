# Pantheon dev network (P-28)

A local network for the manual end-to-end smoke test: Postgres, Agora, two Velocity proxies, two Paper servers and
the smoke plugin. Use it for development only. The tokens, the forwarding secret and offline mode are public test
values.

```
client ──25565──> proxy-01 ─┐                ┌─> lobby-01 (Paper 26.3, Pantheon + PantheonSmoke)
client ──25566──> proxy-02 ─┴─ modern fwd. ──┴─> lobby-02 (Paper 26.3, Pantheon + PantheonSmoke)
                      all four nodes ──gRPC──> agora:9090 ──> postgres (17-alpine)
```

| Service    | Image                                   | Node id / host port          |
|------------|-----------------------------------------|------------------------------|
| `postgres` | `postgres:17-alpine`                    | internal only                |
| `agora`    | `agora:0.1.0-SNAPSHOT` (local, Jib)     | internal only (`agora:9090`) |
| `proxy-01` | `itzg/mc-proxy:java25`, Velocity 4.3.1  | `proxy-01`, **localhost:25565** |
| `proxy-02` | `itzg/mc-proxy:java25`, Velocity 4.3.1  | `proxy-02`, **localhost:25566** |
| `lobby-01` | `itzg/minecraft-server:java25`, Paper 26.3 | `lobby-01`, only through a proxy |
| `lobby-02` | `itzg/minecraft-server:java25`, Paper 26.3 | `lobby-02`, only through a proxy |

The Velocity server names (`velocity/velocity.toml`) equal the `PANTHEON_NODE_ID` of the Paper servers. Agora
reads the node tokens from `agora/tokens.txt` (`node_id=token`, E20). The Minecraft EULA is accepted in
`docker-compose.yml` (`EULA=TRUE`).

## Build

```bash
# 1. Agora image (in the Agora repo, next to this one)
cd ../agora && ./gradlew :agora-server:jibDockerBuild publishToMavenLocal

# 2. Pantheon plugins and the smoke plugin (in this repo)
./gradlew build
#    -> paper/build/libs/pantheon-paper.jar          (shadow jar)
#    -> velocity/build/libs/pantheon-velocity.jar     (shadow jar)
#    -> dev/smoke-plugin/build/libs/pantheon-smoke.jar
```

The jars are mounted read-only. The itzg images copy them into the server on every start if they are newer, so after a
rebuild `docker compose restart lobby-01` (or the proxy) is enough.

## Start

```bash
cd dev
docker compose up -d          # Agora waits for Postgres, all Pantheon nodes wait for Agora (healthcheck, R49)
docker compose ps             # all six "healthy" after about 1 minute (first start: downloads Paper and Velocity)
docker compose logs -f agora  # "Agora listening on port 9090", then "Node 'proxy-01' (VELOCITY) connected" ...
```

The boot is fine when you see:

- `agora`: `Successfully applied 2 migrations` (V2 = audit, partitions `pantheon_audit_YYYYMMDD`), `Agora listening on port 9090`, and four lines
  `Node '<id>' (VELOCITY|PAPER) connected`. PAPER nodes get `Welcome` only 15 s after the Agora start (E11).
- `lobby-0x`: `[Pantheon] Pantheon started as node 'lobby-0x'`, `[PantheonSmoke] Smoke plugin ready`.
- `lobby-0x`: the intentionally missing translation (P-19):
  ```
  [PantheonSmoke] [Language] pantheonsmoke_en_US.yml: 1 Key fehlt
  [PantheonSmoke] [Language]   - smoke.untranslated   (SmokeMessage.UNTRANSLATED)
  ```

## Connect

Add two servers in the Minecraft 26.3 client: `localhost:25565` (proxy-01) and `localhost:25566` (proxy-02).
The proxies run in **offline mode**: any user name works, the UUID is derived from the name. Two clients with
different names test messages and payments; two clients with the **same** name test the double login. Switch servers
with Velocity's `/server lobby-01` and `/server lobby-02`.

Server console: `docker compose exec lobby-01 rcon-cli` (e.g. `op <name>`, `stop`).

## Smoke commands (`/pt`)

| Command                     | Pantheon API                                                     |
|-----------------------------|------------------------------------------------------------------|
| `/pt coins`                 | `getPlayer(p).data().get(COINS)` (namespace `smoke`, starts at 0) and the bought kits |
| `/pt coins add <n>`         | unconditional `data().inc(COINS, n)`: applied locally at once, outbox when Agora is down |
| `/pt pay <name> <n>`        | `players().transfer(COINS, me, target, n)` (conditional)          |
| `/pt msg <name> [text]`     | `players().online(name)` + `PanPlayer.sendMessage` (cross-server) |
| `/pt where <name>`          | `PanPlayer.location()`: server, world and coordinates             |
| `/pt audit <name> [hours]`  | `audit().timeline(uuid).viewer(sender).between(..)`: the timeline as text lines like audit_log.md §10 (default 24 h, newest 40 entries, console allowed; from the console the lines are not echoed over rcon, look at the `audit_view` row instead) |
| `/pt shop`                  | `audit().begin(smoke:open)`, `action(smoke:buy)`, `trace.end(smoke:close)`: one trace with three entries |
| `/pt tp <server> <world> <x> <y> <z>` | `PanPlayer.teleport(NetworkLocation)` on yourself: "Teleport: OK" or the failure text |
| `/pt connect <server>`      | `PanPlayer.connect(server)` on yourself: "Teleport: OK" or the failure text |
| `/pt buy starter` / `vip`   | `atomic().withdraw(COINS, 50 / 200).add(KITS, kit).commit()`, then the items (16 bread / 4 diamonds) |

## Checklist (a human with two Minecraft clients)

- [ ] Login, `/pt coins` zeigt 0. Nach `/pt buy` stimmt der Kontostand.
  (Erst `/pt coins add 100`, dann `/pt buy starter`: 50 übrig.)
- [ ] Ein Kauf auf `lobby-01`, während der Spieler nach `lobby-02` wechselt: Der Endstand ist auf beiden Servern
  korrekt. (`/pt buy starter` und sofort `/server lobby-02`, dort `/pt coins`, zurück und noch einmal.)
- [ ] `/pt msg` an einen Spieler auf dem anderen Server, auch mitten im Serverwechsel.
- [ ] `/pt where` zeigt Server und Koordinaten eines entfernten Spielers.
- [ ] Doppelter Login über `proxy-01` und `proxy-02`: Die alte Verbindung wird mit „Von einem anderen Ort angemeldet“
  gekickt. (Gleicher Name auf `localhost:25565` und `localhost:25566`.)
- [ ] Agora stoppen: Neue Logins werden abgelehnt, `/pt buy` meldet einen Fehler, `/pt coins`-Änderungen per `inc`
  wirken lokal. Agora starten: Die Outbox wird nachgeholt, die Werte stimmen.
  (`docker compose stop agora` … `/pt coins add 10` … `docker compose start agora`.)
- [ ] `lobby-01` neu starten, während Agora gestoppt ist: Die Outbox überlebt und wird nachgeholt.
  (`docker compose stop agora`, `/pt coins add 10` auf lobby-01, `docker compose restart lobby-01`,
  `docker compose start agora`; die Outbox liegt in `plugins/Pantheon/outbox.db` im Volume `lobby-01-data`.)
- [ ] Fehlende Übersetzung im Smoke-Plugin: Beim Start erscheint die Meldung aus P-19.
  (`docker compose logs lobby-01 | grep Language`.)
- [ ] Network teleport: vorher Agora vom Feature-Branch bauen (`./gradlew :agora-server:jibDockerBuild` im
  Agora-Worktree).
  - [ ] `/pt tp lobby-02 world 0 80 0` von `lobby-01`: Der Spieler kommt direkt am Ort an, ohne sichtbaren Sprung.
  - [ ] Ziel in einer anderen Welt als dem Spawn von `lobby-02`: Der Spieler kommt am Ort an.
  - [ ] `/pt tp` zweimal schnell hintereinander auf zwei verschiedene Server: Der Spieler landet am Ziel des zweiten.
  - [ ] `/pt tp lobby-02 nether 0 80 0`: Meldung „Diese Welt gibt es …“.
  - [ ] `/pt connect lobby-01` auf `lobby-01`: „Teleport: OK“ ohne Wechsel.

### Audit checklist (P-39)

- [ ] Chatten, `/pt coins`, Serverwechsel und Quit erscheinen in `/pt audit <name>` mit dem richtigen Server.
- [ ] `/login test` erscheint nicht.
- [ ] `/pt shop` erscheint als eine Trace mit drei Einträgen (`smoke:open`, `smoke:buy`, `smoke:close`).
- [ ] Der zweite Spieler liest die Zeitachse des ersten (`/pt audit <erster>`): In der Zeitachse des ersten steht danach
  ein `pantheon:audit_view`.
- [ ] Agora 30 s stoppen, dabei chatten, Agora starten: Die Nachrichten kommen nach, keine doppelt.
  (`docker compose stop agora` ... `docker compose start agora`.)
- [ ] Doppelter Login über `proxy-01` und `proxy-02`: Beim alten Eintrag steht `quit` mit `reason = replaced`.

Useful while testing: `docker compose logs -f agora lobby-01 lobby-02 proxy-01 proxy-02`, and the data in Postgres:
`docker compose exec postgres psql -U agora -d agora -c '\dt pantheon_*'`.

## Stop and reset

```bash
docker compose down        # stop and remove the containers, keep the worlds, outboxes and the database
docker compose down -v     # also delete the volumes: database, worlds and outboxes start from scratch
```

Worlds, outboxes and the database live only in Docker volumes (`pantheon-dev_*`); downloaded server jars stay inside
the containers. Nothing of it belongs in git (`.gitignore`: `dev/**/data/`, `dev/**/logs/`, `dev/**/*.jar`).

To use real Mojang accounts instead, set `online-mode = true` and `force-key-authentication = true` in
`velocity/velocity.toml` (Paper stays in offline mode behind Velocity).
