# AdamkoVeloSpawn

Velocity plugin (`adamkovelospawn`) that owns where every player goes: routing, on-demand
season starts via Pelican, the waiting room, idle shutdown and network chat.
Milestones 1–4 of the original plan are implemented.

## Build

Needs JDK 25 to compile (every Velocity 4.x API jar is Java 25 bytecode); the jar itself targets
Java 21 (`--release 21`).

```
./gradlew build
# -> build/libs/AdamkoVeloSpawn-1.0.0.jar
```

Or download it: every push to `main` is built and tested by GitHub Actions and published as a
release (`v1.0.0-build.<n>`) with the jar attached; the newest is marked "Latest".

Copy the jar to Velocity's `plugins/`. First start writes `plugins/adamkovelospawn/config.yml`
(the bundled default is `src/main/resources/config.yml`). Set `panel.url`, `panel.api-key` and each season's `panel-id`.

## Setup on the proxy

- `velocity.toml` (hosts and ports are yours):

  ```toml
  [servers]
  s3 = "<host>:<port>"
  s2 = "<host>:<port>"
  s1 = "<host>:<port>"
  limbo = "<host>:<port>"
  waiting = "<host>:<port>"
  try = ["waiting"]
  ```

  The plugin logs an error for every configured server missing from `[servers]`, and a warning if
  `try` is anything other than `["waiting"]`.
- Server names are permanent IDs (`s1`, `s2`, `s3`, later `s4`); `servers.newest` is only a pointer and
  years live only in `display-name`. Adding a season = register it in `velocity.toml`, add it under
  `servers.seasons`, move `newest`, `/avs reload`.
- VeloAuth (external mode, `server-name: limbo`, `premium.check-enabled: false`): unchanged. It sends logged-in players to the `try` list
  (`waiting`); this plugin redirects that connect to the real season.
- Remove `velocitypteropower-*.jar` (keep it aside as fallback).

### Why `try = ["waiting"]` (VeloAuth check)

VeloAuth (rafalohaki/VeloAuth 1.6.x) fires **no** "authenticated" event and has **no** option to turn
off its post-login transfer. It handles `ServerPreConnectEvent` at the highest priority and *denies*
connects for unauthenticated players; after `/login` it pings the `try` servers and connects to the first
that answers. So:

- This plugin's `ServerPreConnectEvent` handler runs after VeloAuth (priority -100) and ignores
  anything VeloAuth denied: an event still allowed here belongs to an authenticated player.
- `waiting` is always up, so VeloAuth's transfer never fails; the plugin either redirects it to the
  season (if running) or lets the player into `waiting` and moves them when the season is ready.

### "One voice": silence VeloAuth's move messages

Edit `plugins/veloauth/lang/messages_en.properties` **and** every other language your players' clients
use (VeloAuth's `detect-client-language: true` means Slovak clients get `messages_sk.properties`), or set
`detect-client-language: false`:

```properties
connection.connecting=
connection.retry=
connection.waiting_for_server=
connection.error.no_servers=
connection.error.game_server=
```

Note: VeloAuth sends `connection.connecting` unconditionally, so an empty value shows as one blank chat
line. If that bothers you, use a short neutral text instead (e.g. `&7Logged in.`).
Then `/vauth reload` (or restart).

## Behaviour summary

| Situation | What happens |
| --- | --- |
| Login (VeloAuth → `waiting`) | Destination = last season (if still configured) else `newest`. Running → redirected straight there. Off → stays in `waiting`, action-bar countdown, moved when ready. |
| `PostLoginEvent` | Destination is pre-started while the player types `/login` (`routing.prestart-on-connect`). |
| Connect to a season (`/server`, `/play`) | Live check (ping ≤ `ping-timeout-ms`, then panel state). Off + player already on a season → **stays there** (connect denied quietly) with countdown; otherwise redirected to `waiting`. |
| Readiness | Ping answers **and** panel says `running` (if the panel is unreachable but the ping works, the ping wins), then `join-delay-seconds`, then queued players move (3 tries, 2 s apart). |
| Several players, one season | One start attempt, one start call; all queued players move when ready. |
| Season `stopping` | Waits for `offline`, then sends one start. A stop we just sent is never treated as "ready" even if the panel still says running. |
| Crash during startup (`starting` → `offline`) | Reported as failed; no retry loop. |
| Timeout | Queued players get `start-failed`, stay in `waiting`; console logs the last panel state. |
| Panel unreachable | Players get `panel-unreachable`; console logs the error; no retry loop. |
| Leave `waiting` / disconnect while queued | Dropped from the queue; the season keeps starting, then idles out. |
| Kicked off a season (crash, `/avs stop`, ban…) | Velocity's fallback puts them in `waiting`; they're told (`season-down` or `kicked-to-waiting`) and **not** auto-routed back (no kick loops). `/play` takes them back. |
| Empty season | Idle timer of `idle-shutdown-minutes`, cancelled by any join or queued player. A sweep every minute re-arms timers for empty running seasons (proxy restart, manual starts). |
| Proxy restart | Queues lost; `last-servers.json` kept; idle timers re-armed by the sweep. |

## Identity: UUIDs only

Last-server data and queues are keyed by player UUID (offline UUIDs, since VeloAuth runs with premium
checks off), never by IP — behind a tunnel such as Pangolin every player arrives from the same address.

## Commands

| Command | Permission | |
| --- | --- | --- |
| `/avs status` | `adamkovelospawn.admin` | Live state of each season: panel state, ping, players, queue, start/idle timers |
| `/avs start <season>` / `/avs stop <season>` | `adamkovelospawn.admin` | Start (waits until ready) / stop via Pelican |
| `/avs reload` | `adamkovelospawn.admin` | Reload `config.yml` (old config kept on error) |
| `/play [season]` | everyone | Go to a season (default: your usual one); starts it if off |
| `/msg <player> <text>` (`/tell`, `/w`, `/whisper`, `/m`), `/r` | everyone | Cross-server private messages — only registered when `chat.enabled: true` |

## Network chat (milestone 4, `chat.enabled`)

- Chat is **forwarded, never cancelled**: modern clients sign chat, and Velocity disconnects players if a
  plugin denies a signed message. Players on the sender's server see the backend's normal line; players
  on other seasons see `chat.format` (`{server}`, `{display}`, `{prefix}`, `{player}`, `{message}`).
- `{prefix}` comes from LuckPerms (soft dependency; `&`/`§` legacy codes or MiniMessage).
- Servers in `exclude-servers` (limbo, waiting) neither send nor receive network chat.
- Network join line when a player first reaches a season; leave line on disconnect (sent to other servers).
- `chat.discord`: optional webhook mirror in the EssentialsX Discord style. Each post appears as the
  player (`[s1] - Steve`, with their head from mc-heads.net as the avatar): `➡️ Steve has joined!`,
  `🔀 Steve moved to Season 2`, `⬅️ Steve has left!` and their chat messages. Trips through the waiting
  room don't count as moves. Every text is configurable, and `""` turns that event off. Markdown and masked links
  in chat are escaped, `@` mentions are disabled, and posts go out in order (a rate limit is retried once).
  The older `discord-webhook` key still works. Deaths, advancements and AFK happen on the backends, so the proxy
  can't see them.

## Map mods (Xaero's, JourneyMap, VoxelMap)

Everyone joins through the same address, so without help these mods would merge s1, s2 and s3 into one
map. The plugin gives every server its own world ID, the way MapModCompanion does, but entirely on the
proxy (nothing to install on the backends):

| Mod | Channel | How |
| --- | --- | --- |
| JourneyMap, VoxelMap | `worldinfo:world_id` | The client asks; the proxy answers for the server the player is on (request never reaches the backend). Exact. |
| Xaero's Minimap / World Map | `xaerominimap:main`, `xaeroworldmap:main` | Pushed right after every server switch (`xaero-repeat-on-switch`, 1 s apart), then re-sent every `xaero-resend-seconds`. |

- **Xaero's limitation:** the mod forgets the ID after every respawn (death, nether/end portal), and
  Velocity's API can't see respawns. The periodic re-send restores it within `xaero-resend-seconds`
  (default 5 s); in that gap Xaero's may briefly fall back to its own world detection.
- IDs are derived from the server name and never change: s1 = `-1463720542`, s2 = `-1463720541`,
  s3 = `-1463720540` (logged at startup and shown in `/avs status`). `limbo` and `waiting` get their own IDs,
  so the void never ends up in a season's map. Override with `map-id: <number>` on a season.
- Existing maps: Xaero's/JourneyMap stored previous maps under the IDs they used before, so they'll start
  fresh. Moving an old map folder to the new ID's folder in the client's map directory may work, but that's
  untested.
- If a backend plugin also sends world IDs on these channels, the proxy rewrites them to its own ID.
- Clients without these mods ignore the packets. `map-mods.enabled: false` turns everything off.
- Packet formats are ported from [MapModCompanion](https://github.com/turikhay/MapModCompanion) (MIT;
  see `THIRD_PARTY_NOTICES.md`) and tested against its byte vectors.

## Messages

All player-facing text is MiniMessage in `config.yml` → `messages`. Placeholders use `{name}`; player
text is inserted literally (no tag injection). An empty message means "say nothing".
New keys added in later versions are filled in automatically on load.

## Automated tests

`./gradlew test` — 98 tests. The core ones run the real `Router`, `WaitQueue`, `SeasonManager` and
`ChatService` inside a simulated network (`src/test/java/.../testkit`): a fake Pelican panel, a virtual-clock
scheduler, and mocked Velocity players/servers whose connects fire the same events Velocity does. That
covers the checklist's logic (prefixed `checklist_` in `RouterTest`) plus timeouts, crashes during startup,
retries, idle timers, chat and map-mod packets. Not covered: the real Velocity event bus, VeloAuth itself, real Pelican HTTP,
and Minecraft clients — that's what the live checklist below is for.

## Test procedure (maps to the plan's checklist)

Two accounts (A = OP with `adamkovelospawn.admin`, B = normal), Velocity console open.
Use `/avs status` before each test to confirm the starting state; `/avs stop s3` to get a season off.

1. **Join with s3 off** — `/avs stop s3`, wait for `offline` in `/avs status`. B joins, logs in.
   Expect: console `Starting s3 via Pelican` (already at PostLogin), B lands in `waiting`, action bar
   `Starting Season 3 · 2026… Ns`, then one green "ready" line and a move to s3. No VeloAuth/other errors in chat.
2. **Join with s3 running** — B rejoins: straight to s3, no waiting room, no action bar.
3. **Join while s3 is stopping** — B on s3, A runs `/avs stop s3`, B is kicked to `waiting`
   (`season-down`/`kicked-to-waiting` line). B relogs immediately. Expect: waits through `stopping`,
   console `Starting s3`, B moved when ready.
4. **`/server s2` from s3 with s2 off** — B on s3 runs `/server s2` (or `/play s2`). Expect: B stays on
   s3 with the countdown, then moved to s2.
5. **Two players, one season** — s3 off; A and B join at about the same time. Expect: exactly one
   `Starting s3 via Pelican` line in the console; both moved.
6. **Leave during waiting** — s2 off; B runs `/play s2` from `waiting` and disconnects before ready.
   Expect: console `B left the queue for s2`, s2 still comes up, then `s2 is empty; idle shutdown in 15 min`.
7. **Last server** — B logs out on s2, rejoins: back on s2 (check `plugins/adamkovelospawn/last-servers.json`).
   Same with the archive: `/server s1` from s3 (s1 off) → stays on s3 with `Starting Season 1 · 2023…`,
   moved; relog → back on s1; s1 idles out after 10 min.
8. **Idle shutdown** — temporarily set `idle-shutdown-minutes: 1`, `/avs reload`. Leave s3 empty:
   `idle shutdown in 1 min` → `Idle shutdown: s3 ... stopping`. Repeat, but join within the minute:
   timer cancelled (`/avs status` shows no idle timer).
9. **Panel unreachable** — set `panel.url` to a wrong address, `/avs reload`, stop/start flow:
   B gets `panel-unreachable`, console shows one error per attempt, no repeated start calls.
10. **Proxy restart** — restart Velocity: `last-servers.json` kept; running empty seasons get an idle
    timer within a minute (`/avs status`).
11. *(milestone 4)* `chat.enabled: true`, `/avs reload`: A on s3, B on s2 — chat crosses with
    `[s3]`/`[s2]` prefix, `/msg`, `/r` work across servers, join/leave lines show on the other server.
12. *(map mods)* With Xaero's Minimap: join s3, walk a bit, `/server s2` — Xaero's shows a separate, empty
    map for s2; back on s3 the s3 map is intact. Die / use a portal: within ~5 s the map is still s3's.
    With JourneyMap or VoxelMap the same, without the delay.

## Cut-over from VelocityPteroPower

1. Stop Velocity, move `velocitypteropower-*.jar` out of `plugins/`.
2. Add the `waiting` PicoLimbo, register it, `try = ["waiting"]`.
3. Drop in `AdamkoVeloSpawn-1.0.0.jar`, start Velocity once to generate the config, fill in the panel
   URL/key, `/avs reload`, run the checklist.
4. Rollback: swap the old jar back and set `try = ["s3"]`.
