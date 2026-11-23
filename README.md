# AdamkoVeloSpawn

Velocity plugin (`adamkovelospawn`) that owns where every player goes: routing, on-demand
season starts via Pelican, the waiting room, idle shutdown, network chat and the `/seasons` menu.
Milestones 1–4 of the original plan are implemented, plus the `/seasons` menu from milestone 5.

## Build

Needs JDK 25 to compile (every Velocity 4.x API jar is Java 25 bytecode); the jar itself targets
Java 21 (`--release 21`).

```
./gradlew build
# -> build/libs/AdamkoVeloSpawn-1.0.0-SNAPSHOT.jar (CI builds are 1.0.<build number>)
```

Or download it: every push to `main` is built and tested by GitHub Actions and published as a
release (`v1.0.<build number>`, e.g. `v1.0.14`) with both jars attached (the proxy plugin and the season-server companion);
the newest is marked "Latest".

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
| Readiness | The ping decides: once the season answers, it is up (then `join-delay-seconds`, then queued players move, 3 tries 2 s apart). The panel's state is only believed when it says `stopping`, because Pelican caches `/resources` for up to 20 s and can still say "starting" after the server is up. |
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
| `/seasons` | everyone | Every season with its state and history; a chest menu on season servers, a clickable list elsewhere |
| `/msg <player> <text>` (`/tell`, `/w`, `/whisper`, `/m`), `/r` | everyone | Cross-server private messages — only registered when `chat.enabled: true` |

## Network chat (milestone 4, `chat.enabled`)

- Chat is **forwarded, never cancelled**: modern clients sign chat, and Velocity disconnects players if a
  plugin denies a signed message. Players on the sender's server see the backend's normal line; players
  on other seasons see `chat.format` (`{server}`, `{display}`, `{prefix}`, `{player}`, `{message}`).
- `{prefix}` comes from LuckPerms (soft dependency; `&`/`§` legacy codes or MiniMessage).
- Servers in `exclude-servers` (limbo, waiting) neither send nor receive network chat.
- Network join line when a player first reaches a season; leave line on disconnect (sent to other servers).
- `chat.discord`: optional webhook mirror in the EssentialsX Discord style. Each post appears as the
  player (`[Season 1 - Nether] - Steve`, with their head from mc-heads.net as the avatar): `➡️ Steve has joined Season 1 · 2023!`,
  `🔀 Steve moved to Season 2`, `⬅️ Steve has left!` and their chat messages. Trips through the waiting
  room don't count as moves. Every text is configurable, and `""` turns that event off. Markdown and masked links
  in chat are escaped, `@` mentions are disabled, and posts go out in order (a rate limit is retried once).
  The older `discord-webhook` key still works.
- AFK, deaths and advancements happen on the season servers, which the proxy can't see. Put
  `AdamkoVeloSpawn-Companion-<version>.jar` (from the same release) into `plugins/` on each season server
  (Paper 26.x; EssentialsX for AFK) and they are posted too: `🚶 Steve is now AFK!`,
  `⌨️ Steve is no longer AFK!`, `💀 Steve was blown up by Creeper`,
  `🥇 Steve has completed the advancement **Diamonds!**!` (templates `afk`, `afk-return`, `death`,
  `advancement`). The companion changes nothing in game (besides drawing the `/seasons` menu). It only forwards what the
  game itself announces (respecting the death/advancement message game rules, skipping recipes and
  vanished players). The proxy only accepts these from a server connection, so a player can't fake one.
- The companion also reports each player's world, so Discord names read `[Season 2 - Nether] - Steve`
  (`{location}`; `{world}` alone is the world). Names come from `chat.discord.worlds`: by folder name
  first, then by kind (`normal`/`nether`/`the_end`). Join and switch posts wait up to 3 s for the world on
  servers where the companion runs; without it they go out at once as `[Season 2] - Steve`.
- Paper only sends plugin messages on channels the player's connection registered, and a vanilla client
  never registers the companion's channel, so after every switch to a season the proxy registers
  `adamkovelospawn:events` on the player's behalf; the companion starts talking once that arrives.
- Discord → Minecraft: set `chat.discord.bot-token` and `channel-id` and a bot (e.g. the one EssentialsX
  Discord used) reads that channel. Messages show on every season (not limbo/waiting) as `to-minecraft`,
  `[Discord] Name: text` by default, with the name in the member's role colour. Bots and webhooks are
  ignored, so the plugin's own posts never echo back. The bot needs **Message Content Intent** (Developer
  Portal → Bot → Privileged Gateway Intents), plus View Channel and Read Message History; the console says
  which one is missing. JDA is bundled (relocated), so the jar is about 13 MB.

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

## Seasons menu (`/seasons`)

A look back at every season: its years (from `display-name`), whether it is up right now, how many
people played there, how many in-game days the world ran, the hours everyone played together, the most
played person and total deaths.

- On a season server with the companion, `/seasons` opens a chest with one item per season (the item is
  `icon:` under `servers.seasons.<name>`, a Minecraft item id without `minecraft:`, default `PAPER`), centred
  in the row, with the season number from the server name (s3 → 3) as the item count
  (`seasons-menu.show-numbers: false` turns that off). Your current season
  glows and says "You are here"; clicking another one is the same as `/play <season>`. In the waiting
  room or the auth limbo (PicoLimbo can't show a chest) you get the same seasons as a chat list: hover
  for the numbers, click to visit. Opening the menu never starts anything.
- The numbers come from the companion: it counts its own world once after startup, through Paper's
  statistics (play time and deaths per player, the overworld's game time). Seasons restart often (idle
  shutdown), so the live season's numbers are at most one run behind. The companion can only talk while
  someone is online, so the first player to arrive carries the count. The proxy keeps the newest numbers per season in
  `plugins/adamkovelospawn/season-stats.json`; finished seasons never need to run again.
- A season with no numbers yet says "No stats yet". After installing the companion on an old season,
  start it once (`/avs start s1`) and join it for a moment.
- Wording is in `messages` (`seasons-*`; the lore templates are split into tooltip lines at `\n`).

## Messages

All player-facing text is MiniMessage in `config.yml` → `messages`. Placeholders use `{name}`; player
text is inserted literally (no tag injection). An empty message means "say nothing".
New keys added in later versions are filled in automatically on load.

## Automated tests

`./gradlew test` — 158 tests (plus `./gradlew :companion:test` for the companion's stats and menu parsing). The core ones run the real `Router`, `WaitQueue`, `SeasonManager` and
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
13. *(seasons menu)* `/seasons` on s3: a chest, s3 glows with "You are here", the state lines match
    `/avs status`. Nothing in the chest can be taken.
14. *(seasons menu)* `/seasons` in `waiting`: a chat list; hovering shows the numbers, clicking Season 2
    runs `/play s2`.
15. *(seasons menu)* On s3 with s2 off, click Season 2 in the chest: the chest closes, the action bar
    counts the start, then you are moved.
16. *(seasons menu)* After a fresh companion install on s1: s1 says "No stats yet" until someone has been
    on it; then its numbers appear and survive a proxy restart (`season-stats.json`).

## Cut-over from VelocityPteroPower

1. Stop Velocity, move `velocitypteropower-*.jar` out of `plugins/`.
2. Add the `waiting` PicoLimbo, register it, `try = ["waiting"]`.
3. Drop in `AdamkoVeloSpawn-<version>.jar` from the latest release, start Velocity once to generate the config, fill in the panel
   URL/key, `/avs reload`, run the checklist.
4. Rollback: swap the old jar back and set `try = ["s3"]`.
