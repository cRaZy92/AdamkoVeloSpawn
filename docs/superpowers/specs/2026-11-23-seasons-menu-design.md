# `/seasons` menu: design

Milestone 5 item "`/seasons` menu", built as a nostalgia board.

## Intent

A place for the regulars to look back at the seasons they played: each season with its years, whether
it is up right now, and a handful of numbers about its history (how many people played, how long the
world ran, how many hours everyone put in). It is **for nostalgia**, not for steering new players, so
the history leads and "go there" is a small extra on each entry.

## Goals

- `/seasons` for every player opens an inventory (chest) GUI, one item per configured season.
- Each item's tooltip shows:
  - display name (already carries the years, e.g. "Season 2 · 2024–2026")
  - live state: online with N playing / starting / offline
  - players: number of distinct players who ever played there
  - in-game days the world ran
  - combined playtime of all players, in hours
  - most played player and their hours
  - total deaths
  - "Click to visit" (or "You are here" on the current season)
- Clicking an item does exactly what `/play <season>` does today.
- Stats for finished seasons (s1, s2) are collected once and then never change; the live season's
  stats stay roughly current.

## Non-goals

- No per-player stats page ("my hours on s2"), leaderboards beyond the single "most played" line, or
  history graphs.
- No inventory GUI in the waiting room or the auth limbo (PicoLimbo can't show one).
- No Protocolize or other packet-level GUI on the proxy.
- The Pelican API key gets no new permissions.

## Anti-goals

- The menu must never be wrong in a way that hurts the nostalgia: a season with no collected stats
  shows "No stats yet", never zeros.
- Opening the menu must not start a season or do anything a click on an item hasn't asked for.

## Constraints (from the code today)

- Velocity 4.3.2-SNAPSHOT proxy, Paper 26.3 season servers with the companion plugin
  (`companion/`), Java 21 bytecode for the proxy jar, Java 25 for the companion.
- Proxy and companion talk over the plugin-message channel `adamkovelospawn:events`, and **plugin
  messages travel over a player's connection**: the companion can only send while at least one player
  whose connection has the channel registered is on that server, and the proxy can only send to a
  server through a player connected to it.
- The proxy registers the channel on the player's behalf after each server switch
  (`ChatService.onPostConnect`). Today that registration and the whole incoming-message handler are in
  `ChatService` and **skipped when `chat.enabled` is false**.
- s1, s2 are offline nearly always; seasons are started on demand and shut down when idle.

## Approaches considered

**Where the GUI is drawn**

1. **Companion draws it on the season server (picked).** The proxy sends the data to the companion on
   the player's current server, which opens a Paper inventory with real items and tooltips; clicks go
   back to the proxy. Uses stable Paper API. Only works on a server running the companion; elsewhere
   `/seasons` falls back to a chat list.
2. Protocolize on the proxy: works everywhere, but hooks Velocity internals and the proxy runs a 4.x
   snapshot, so it is likely to break on updates or not load at all. Rejected.
3. Chat list or written book from the proxy: no extra moving parts, but Adam wanted an inventory GUI.
   The chat list survives as the fallback.

**Where the numbers come from**

1. **Companion counts them from its own world (picked).** Paper knows the world folder and formats;
   no new panel permissions. Cost: a season has no stats until it has run once with the companion and
   a player has been on it (s1/s2 need one visit after installing).
2. Proxy reads `usercache.json`, `stats/*.json` and `level.dat` through the Pelican file API. Works
   while offline, but needs a file-reading API key on every season, an NBT reader in the proxy and
   guessing world folder names. Rejected.

## Design

### Companion: collecting stats

- On enable, and then every **10 minutes** (config, companion `config.yml`), the companion computes a
  snapshot asynchronously-safe (file reads off the main thread where Paper allows):
  - `players`: number of distinct players with a stats file in the main world's `stats/` folder
    (people who actually played, unlike `usercache.json`, which also holds name lookups).
  - `playtimeTicks`: sum of `minecraft:custom` → `minecraft:play_time` over all of them.
  - `deaths`: sum of `minecraft:custom` → `minecraft:deaths`.
  - `top`: name + play time of the player with the most play time (name resolved via the server's
    offline player / user cache; UUID if unknown).
  - `gameTime`: main overworld `World.getGameTime()` (ticks the world has actually run; unaffected by
    `/time set`), shown as `gameTime / 24000` days.
  - `collectedAt`: epoch millis.
- Reading the stats JSON files directly is the builder's call versus `OfflinePlayer#getStatistic`;
  pick whichever doesn't load every player's data on the main thread.
- The companion sends the latest snapshot as `{"v":1,"type":"stats", ...}` through any online player
  when the proxy registers the channel for them (`onChannelRegistered`) and after each periodic
  recompute if someone is online. With nobody online nothing is sent; the next arrival carries it.

### Proxy: storing stats

- New `SeasonStatsStore` saves the latest snapshot per season to
  `plugins/adamkovelospawn/season-stats.json` (same style as `LastServerStore`), keyed by server name,
  and keeps the newest by `collectedAt`. The server name comes from the connection, never the payload.
- Seasons removed from config keep their stored stats but aren't shown.

### Proxy: companion channel independent of chat

- Move channel registration, the `minecraft:register` send on post-connect and JSON parsing of
  incoming companion messages out of `ChatService` into a small `CompanionChannel` that is always on.
  It dispatches by `type`: `world|afk|death|advancement` → `ChatService` (still gated by
  `chat.enabled` and its exclude list, as today), `stats` → `SeasonStatsStore`, `menu-click` → the menu.
- Registration happens on every season server (the configured seasons), regardless of chat.

### Opening the menu

- `/seasons` (everyone, no args) on the proxy:
  - If the player is on a season server whose companion has been heard from (any message since the
    proxy started, or since its last restart of that server), the proxy sends
    `{"v":1,"type":"menu", "seasons":[...]}` to that server through the player's connection. Each entry:
    name, display name, icon material, live state (`online`/`starting`/`offline` + player count),
    current (bool), and the stored stats or null.
  - Otherwise (waiting room, limbo, a server without the companion, or no reply): send the clickable
    chat list: one line per season with state, hover showing the same stats, and a `[Visit]` click that
    runs `/play <season>`.
- Live state: online = `isReadyNow` true (ping + panel); starting = `SeasonManager.isStarting`;
  otherwise offline. Player count from `SeasonManager.playerCount`. The menu is built after these
  futures complete (short timeout; on timeout show "unknown").
- Order: config order of `servers.seasons` (newest first is how Adam writes it).

### Companion: drawing the menu

- Opens a chest inventory sized to the number of seasons (rows of 9), title from the message
  (MiniMessage, default "Seasons"). Items: the configured icon material, display name as item name,
  tooltip lines as listed in Goals. The current season's item glows (enchant glint) and reads
  "You are here". Offline seasons with no stats show "No stats yet".
- Taking items out is cancelled. A click on another season's item closes the inventory and sends
  `{"v":1,"type":"menu-click","season":"s2"}` to the proxy, which runs the same path as
  `/play s2` (extract the body of `PlayCommand.execute` into a reusable method).
- Text: all tooltip line templates live in the **proxy's** `messages` section (MiniMessage, with
  placeholders), rendered on the proxy and sent as serialized components (JSON), so wording stays in
  one config file. The companion just puts them on items.

### Config additions (proxy `config.yml`)

```yaml
servers:
  seasons:
    s3:
      icon: GRASS_BLOCK          # item material for /seasons; default PAPER
messages:
  seasons-title: "Seasons"
  seasons-lore: [...]            # tooltip lines, placeholders {state} {players} {days} {hours} {top} {top-hours} {deaths}
  seasons-no-stats: "<gray>No stats yet"
  seasons-click: "<yellow>Click to visit"
  seasons-here: "<green>You are here"
  # plus chat-fallback line templates
```

### Rollout

1. Install the new companion on s1, s2, s3 (all run the same Paper version, so one jar fits all).
2. Start s1 and s2 once and have someone join each for a moment, so their stats are collected. After
   that they never need to run for the menu.

## Testing

- Unit tests on the proxy side, in the existing style (`FakePanel`, `TestNetwork`): stats store
  round-trip and newest-wins; menu payload built from config + stats + live states; fallback chosen
  when the player is in the waiting room or on a server the companion hasn't been heard from;
  `menu-click` routes to the `/play` path; chat events still flow only with `chat.enabled`, stats flow
  without it.
- Companion: stats aggregation as a pure function over parsed stats JSON, unit tested.
- Manual: README checklist entries for the GUI on s3, fallback in waiting, clicking s2 from s3.

## Left to the builder

- Exact JSON field names, inventory layout beyond "sized to the seasons", number formatting
  (thousands separators, hours rounded).
- How stats files are read on the companion (see above), and the reply timeout for the menu.
