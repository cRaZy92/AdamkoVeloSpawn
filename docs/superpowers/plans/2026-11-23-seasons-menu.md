# `/seasons` Menu Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `/seasons` opens a chest GUI (drawn by the companion on the season server) listing every season with live state and historical stats; elsewhere it falls back to a clickable chat list.

**Architecture:** The companion periodically aggregates its world's stats and pushes them to the proxy over `adamkovelospawn:events`; the proxy persists them (`season-stats.json`). On `/seasons` the proxy renders every tooltip line itself (MiniMessage → JSON components) and sends a ready-to-draw `menu` payload to the companion, which opens the inventory and reports clicks back as `menu-click`, handled by the same path as `/play`.

**Tech Stack:** Java 21 bytecode (proxy, Velocity 4.3.2-SNAPSHOT API, Gson, Adventure), Java 25 (companion, Paper API 26.3), JUnit 5 + Mockito, existing `TestNetwork` test kit.

**Spec:** `docs/superpowers/specs/2026-11-23-seasons-menu-design.md`

## Global Constraints

- The Pelican API key gets no new permissions; no Protocolize.
- A season with no collected stats shows "No stats yet", never zeros.
- Opening the menu never starts a season; only a click (→ `/play` path) may.
- Server identity for incoming companion messages comes from the `ServerConnection`, never the payload; messages whose source is a player (not a `ServerConnection`) are dropped and never forwarded.
- Chat-derived companion events (`world|afk|death|advancement`) stay gated by `chat.enabled` and `chat.exclude-servers`; `stats` and `menu-click` work regardless of chat.
- Days = `gameTime / 24000`; hours = play-time ticks / 72 000 (20 ticks × 3600), rounded down; numbers formatted with `String.format(Locale.US, "%,d", n)`.
- Menu order = config order of `servers.seasons`.
- Companion stats interval default 10 minutes (`stats-interval-minutes` in companion `config.yml`).
- All player-facing text lives in the proxy's `messages` section.

## Review Focus

- **Stats message from an unconfigured server** (e.g. the waiting room or a removed season): must be ignored, not stored. → test in Task 2.
- **`menu-click` naming an unknown season or the player's current season**: unknown → ignored (no `/play`); current → the existing "already-there" message, no move. → tests in Task 5.
- **`/seasons` while the panel is unreachable / a ping hangs**: menu still opens with state "unknown" after the timeout, never hangs. → test in Task 4.
- **Corrupt or missing `season-stats.json`** at startup: starts empty, logs, no crash. → test in Task 1.
- **Older stats arriving after newer** (two players on the same server, reordered sends): newest `collectedAt` wins. → test in Task 1.

---

### Task 1: Season stats store

**Files:**
- Create: `src/main/java/sk/adamko/velospawn/store/SeasonStatsStore.java`
- Test: `src/test/java/sk/adamko/velospawn/store/SeasonStatsStoreTest.java`

**Interfaces:**
- Produces:
  - `public record SeasonStats(int players, long playtimeTicks, long deaths, String topName, long topTicks, long gameTime, long collectedAt)` (nested in `SeasonStatsStore`; `topName` may be null when nobody played)
  - `SeasonStatsStore(Path file, Logger logger, Executor writer)`, `void load()`, `void save()`, `Optional<SeasonStats> get(String server)`, `boolean put(String server, SeasonStats stats)` (returns false and keeps the old value when `stats.collectedAt()` ≤ stored)

- [ ] **Step 1: Write the failing tests** — same style as `LastServerStoreTest` (temp dir, direct executor `Runnable::run`):
  - `roundTripsThroughDisk`: put `s2` → new store on same file, `load()` → `get("s2")` equals the record.
  - `newestCollectedAtWins`: put `collectedAt=200`, then `collectedAt=100` → `put` returns false, `get` still has 200.
  - `corruptFileStartsEmpty`: write `"{not json"` to the file, `load()` → `get("s2")` empty, no exception.
  - `missingFileStartsEmpty`.
- [ ] **Step 2: Run** `./gradlew test --tests '*SeasonStatsStoreTest'` — expect compile failure.
- [ ] **Step 3: Implement** mirroring `LastServerStore` (Gson pretty printing, `TreeMap` snapshot, tmp file + atomic move, write on `writer` after a successful `put`). JSON shape: `{ "s2": { "players": 41, ... } }`.
- [ ] **Step 4: Run** the same command — PASS.
- [ ] **Step 5: Commit** `git commit -m "Season stats store: newest snapshot per season, kept in season-stats.json"`

### Task 2: Companion channel independent of chat

Move channel registration, the `minecraft:register` send and parsing of incoming companion messages out of `ChatService`.

**Files:**
- Create: `src/main/java/sk/adamko/velospawn/companion/CompanionChannel.java`
- Modify: `src/main/java/sk/adamko/velospawn/chat/ChatService.java` (remove `registerChannel`, `onPostConnect`, `onPluginMessage`, `BACKEND_EVENTS`, `REGISTER`, `companionServers`; add the method below)
- Modify: `src/main/java/sk/adamko/velospawn/AdamkoVeloSpawn.java` (construct, register as listener, call `registerChannel()` instead of `chat.registerChannel()`)
- Modify: `src/test/java/sk/adamko/velospawn/testkit/TestNetwork.java` (add `public final SeasonStatsStore stats; public final CompanionChannel companion;`)
- Modify: `src/test/java/sk/adamko/velospawn/chat/ChatServiceTest.java` (route `fromSeason` and the player-source test through `net.companion.onPluginMessage`)
- Test: `src/test/java/sk/adamko/velospawn/companion/CompanionChannelTest.java`

**Interfaces:**
- Consumes: `SeasonStatsStore.put(String, SeasonStats)` (Task 1)
- Produces:
  - `public static final MinecraftChannelIdentifier CompanionChannel.EVENTS = from("adamkovelospawn:events")`
  - `CompanionChannel(ProxyServer proxy, Logger logger, Supplier<PluginConfig> config, ChatService chat, SeasonStatsStore stats)`
  - `void registerChannel()`
  - `@Subscribe void onPostConnect(ServerPostConnectEvent)` — sends `minecraft:register` with `EVENTS` on every configured **season** server (no chat check)
  - `@Subscribe void onPluginMessage(PluginMessageEvent)` — always marks handled for `EVENTS`; drops non-`ServerConnection` sources; parses JSON (malformed → debug log, return); records the server as companion-capable; dispatches on `type`: `stats` → store (only if `config.isSeason(server)`), `menu-click` → `menuClickHandler`, anything else → `chat.onCompanionEvent(conn, json)`
  - `boolean hasCompanion(String server)`
  - `void setMenuClickHandler(BiConsumer<Player, String> handler)` (Task 5 sets it; default no-op)
  - `void send(ServerConnection conn, JsonObject message)` — UTF-8 bytes on `EVENTS`
  - `ChatService.onCompanionEvent(ServerConnection conn, JsonObject o)` — the old body after parsing, returning early when `!enabled()` or the server is excluded; `ChatService` asks `CompanionChannel.hasCompanion` for its join-hold logic via a `Predicate<String>` setter `setCompanionCheck(Predicate<String>)`.

- [ ] **Step 1: Write the failing tests** in `CompanionChannelTest`, using `TestNetwork` and a helper that builds a `PluginMessageEvent(p.connectionNow(), p.mock, CompanionChannel.EVENTS, bytes)`:
  - `statsAreStoredEvenWithChatOff`: chat disabled (default), Bob on `s2`, send `{"v":1,"type":"stats","players":41,"playtimeTicks":166320000,"deaths":512,"topName":"Adamko","topTicks":36000000,"gameTime":28896000,"collectedAt":1000}` → `net.stats.get("s2").get().players() == 41`.
  - `statsFromAWaitingRoomAreIgnored`: Bob on `waiting`, same payload → `net.stats.get("waiting")` empty.
  - `registersTheChannelOnSeasonsWithChatOff`: connect Bob to `s3` → `bob.toBackend` contains `"s3|minecraft:register|adamkovelospawn:events"`.
  - `playerSourcedMessagesAreDroppedAndNotForwarded`: source = player mock → result not allowed, nothing stored.
- [ ] **Step 2: Run** `./gradlew test --tests '*CompanionChannelTest'` — compile failure.
- [ ] **Step 3: Implement** `CompanionChannel`, move code out of `ChatService`, wire `AdamkoVeloSpawn` (new `SeasonStatsStore(dataDirectory.resolve("season-stats.json"), logger, ioExecutor)`, `load()` on init, `save()` on shutdown) and `TestNetwork`. Update `ChatServiceTest` references to `CompanionChannel.EVENTS` / `net.companion`.
- [ ] **Step 4: Run** `./gradlew test` — all PASS (existing chat/companion tests unchanged in assertions).
- [ ] **Step 5: Commit** `git commit -m "Companion channel stands on its own, so season stats flow with chat off"`

### Task 3: `Router.play` — the `/play` path as a reusable method

**Files:**
- Modify: `src/main/java/sk/adamko/velospawn/route/Router.java`
- Modify: `src/main/java/sk/adamko/velospawn/command/PlayCommand.java` (body becomes `plugin.router().play(player, season)` after resolving the default season)
- Test: `src/test/java/sk/adamko/velospawn/route/RouterTest.java`

**Interfaces:**
- Produces: `public void play(Player player, String season)` in `Router` — validates (`unknown-season`, `already-there` messages), connects when ready, else `queue.enqueue(player, season, current)`. Exactly the existing `PlayCommand.execute` logic after argument parsing.

- [ ] **Step 1: Write the failing tests:**
  - `playToRunningSeasonConnects`: s2 running, Bob on s3 → `net.router.play(bob.mock, "s2")` → Bob lands on s2.
  - `playToOffSeasonQueuesAndStartsIt`: s2 offline → `net.startCalls("s2") == 1`, Bob still on s3.
  - `playToCurrentSeasonSaysAlreadyThere`: Bob on s2 → chat contains "You are already on Season 2 · 2024–2026".
- [ ] **Step 2: Run** `./gradlew test --tests '*RouterTest'` — compile failure.
- [ ] **Step 3: Implement** by moving the logic; `PlayCommand` keeps argument parsing and tab completion only.
- [ ] **Step 4: Run** `./gradlew test` — PASS.
- [ ] **Step 5: Commit** `git commit -m "The /play path lives in the router so other entry points can use it"`

### Task 4: Menu model and rendering

**Files:**
- Create: `src/main/java/sk/adamko/velospawn/menu/SeasonsMenu.java`
- Modify: `src/main/java/sk/adamko/velospawn/config/PluginConfig.java` (`SeasonConfig` gets `String icon` as last component; existing 5- and 6-arg constructors pass `"PAPER"`)
- Modify: `src/main/java/sk/adamko/velospawn/config/ConfigLoader.java` (`n.node("icon").getString("PAPER")`, upper-cased)
- Modify: `src/main/resources/config.yml` (icons: s3 `GRASS_BLOCK`, s2 `SAND`, s1 `OAK_SAPLING`; comment line documenting `icon:`; message keys below)
- Test: `src/test/java/sk/adamko/velospawn/menu/SeasonsMenuTest.java`, `src/test/java/sk/adamko/velospawn/config/ConfigLoaderTest.java` (icon parsed, default `PAPER`)

**Interfaces:**
- Consumes: `SeasonStatsStore.get` (Task 1), `SeasonManager.isReadyNow`, `isStarting`, `playerCount`, `Messages.get`
- Produces:
  - `public enum LiveState { ONLINE, STARTING, OFFLINE, UNKNOWN }`
  - `public record Entry(String season, String icon, Component name, List<Component> lore, boolean current)`
  - `SeasonsMenu(Supplier<PluginConfig> config, SeasonManager seasons, SeasonStatsStore stats, Messages messages)`
  - `CompletableFuture<List<Entry>> entries(String currentServer)` — live state per season: `isReadyNow` true → ONLINE, else `isStarting` → STARTING, else OFFLINE; any future not done within **2 s** (`completeOnTimeout`) or failed → UNKNOWN
  - `JsonObject payload(List<Entry> entries)` — `{"v":1,"type":"menu","title":<json>,"items":[{"season","icon","name":<json>,"lore":[<json>...],"current"}]}`, components via `GsonComponentSerializer.gson().serializeToTree`
  - `Component chatList(List<Entry> entries)` — header line + one line per entry: name with hover = lore joined by newlines, click = `ClickEvent.runCommand("/play " + season)` (no click on the current one)

Message keys (add to `config.yml` `messages`; lore templates are split on `\n` into lines, each rendered separately):

```yaml
  seasons-title: "Seasons"
  seasons-state-online: "<green>● Online · {count} playing"
  seasons-state-starting: "<yellow>● Starting…"
  seasons-state-offline: "<gray>○ Offline"
  seasons-state-unknown: "<gray>? Unknown"
  seasons-lore: "{state}\n\n<white>{players}</white> <gray>players\n<white>{days}</white> <gray>in-game days\n<white>{hours}</white> <gray>hours played together\n<gray>Most played: <white>{top}</white> ({top_hours} h)\n<white>{deaths}</white> <gray>deaths"
  seasons-lore-no-stats: "{state}\n\n<gray>No stats yet"
  seasons-click: "<yellow>Click to visit"
  seasons-here: "<green>You are here"
  seasons-chat-header: "<gold>── Seasons ──"
  seasons-chat-line: " {name} <gray>·</gray> {state}"
```

The last lore line is `seasons-here` when `current`, else `seasons-click`. Item name = `Messages.render("<white>{display}", ...)` of `display-name`, no italics.

- [ ] **Step 1: Write the failing tests** (plain-text compare via `PlainTextComponentSerializer`):
  - `entryWithStatsShowsAllNumbers`: store `s2` stats (players 41, playtimeTicks 166 320 000, deaths 512, top "Adamko"/36 000 000, gameTime 28 896 000), s2 offline → s2 lore is `["○ Offline", "", "41 players", "1,204 in-game days", "2,310 hours played together", "Most played: Adamko (500 h)", "512 deaths", "Click to visit"]`.
  - `seasonWithoutStatsSaysNoStatsYet`: s1 has no stats → lore `["○ Offline", "", "No stats yet", "Click to visit"]`.
  - `onlineSeasonShowsPlayerCountAndCurrentSaysYouAreHere`: s3 running, Bob on s3, `entries("s3")` → s3 lore starts `"● Online · 1 playing"`, ends `"You are here"`, `current == true`.
  - `orderFollowsConfig`: seasons in order `s3, s2, s1`; icons `GRASS_BLOCK, SAND, OAK_SAPLING`.
  - `hangingPingShowsUnknownAfterTimeout`: make s2's ping future never complete; `entries` completes within 3 s with s2 state `"? Unknown"`.
  - `payloadCarriesJsonComponents`: `payload(...)` items[1].season == `"s2"`, `items[1].lore` size 8, `title` deserializes to "Seasons".
- [ ] **Step 2: Run** `./gradlew test --tests '*SeasonsMenuTest' --tests '*ConfigLoaderTest'` — compile failure.
- [ ] **Step 3: Implement** `SeasonsMenu` and the config changes.
- [ ] **Step 4: Run** `./gradlew test` — PASS.
- [ ] **Step 5: Commit** `git commit -m "Seasons menu model: live state and history per season, rendered on the proxy"`

### Task 5: `/seasons` command and clicks

**Files:**
- Create: `src/main/java/sk/adamko/velospawn/command/SeasonsCommand.java`
- Modify: `src/main/java/sk/adamko/velospawn/AdamkoVeloSpawn.java` (construct `SeasonsMenu`, register `seasons`, `companion.setMenuClickHandler(...)`)
- Modify: `src/test/java/sk/adamko/velospawn/testkit/TestNetwork.java` (expose `public final SeasonsMenu menu;` and wire the click handler like the plugin)
- Test: `src/test/java/sk/adamko/velospawn/command/SeasonsCommandTest.java`

**Interfaces:**
- Consumes: `SeasonsMenu.entries/payload/chatList` (Task 4), `CompanionChannel.hasCompanion/send/setMenuClickHandler` (Task 2), `Router.play` (Task 3)
- Produces: `SeasonsCommand(SeasonsMenu menu, CompanionChannel companion)` implementing `SimpleCommand`; `static void onMenuClick(Router router, Supplier<PluginConfig> config, Player player, String season)` — ignores seasons not in config, else `router.play(player, season)`.

`execute`: non-player → plain "Only players can use /seasons."; current server = player's; `entries(current)` then: if `config.isSeason(current) && companion.hasCompanion(current)` → `companion.send(conn, payload)`; else `player.sendMessage(chatList)`.

- [ ] **Step 1: Write the failing tests:**
  - `onASeasonWithCompanionTheMenuGoesToTheServer`: Bob on s3, companion heard from s3 (send any `world` message first) → `bob.toBackend` has an entry starting `"s3|adamkovelospawn:events|"` whose JSON `type` is `"menu"`; Bob's chat empty.
  - `inTheWaitingRoomTheChatListIsShown`: Bob on waiting → Bob's chat contains "── Seasons ──" and a line containing "Season 2 · 2024–2026"; no menu message to backend.
  - `onASeasonWithoutCompanionTheChatListIsShown`: Bob on s2, nothing heard from s2 → chat list.
  - `menuClickRunsPlay`: s2 offline, Bob on s3 → companion message `{"type":"menu-click","season":"s2"}` → `net.startCalls("s2") == 1`.
  - `menuClickForUnknownSeasonDoesNothing`: `"season":"waiting"` and `"season":"s9"` → no start calls, no move, no chat.
  - `menuClickOnCurrentSeasonSaysAlreadyThere`: Bob on s3 clicks s3 → "You are already on Season 3 · 2026".
- [ ] **Step 2: Run** `./gradlew test --tests '*SeasonsCommandTest'` — compile failure.
- [ ] **Step 3: Implement** and wire.
- [ ] **Step 4: Run** `./gradlew build` — PASS.
- [ ] **Step 5: Commit** `git commit -m "/seasons: chest menu on season servers, clickable list elsewhere"`

### Task 6: Companion — collecting and sending stats

**Files:**
- Create: `companion/src/main/java/sk/adamko/velospawn/companion/SeasonStats.java`
- Create: `companion/src/main/java/sk/adamko/velospawn/companion/StatsCollector.java`
- Create: `companion/src/main/resources/config.yml` (`stats-interval-minutes: 10`)
- Modify: `companion/src/main/java/sk/adamko/velospawn/companion/Companion.java` (`saveDefaultConfig()`, start collector, send snapshot in `onChannelRegistered`; extract `sendJson(Player, JsonObject)` from `send`)
- Modify: `companion/build.gradle.kts` (JUnit 5 test deps + `tasks.test { useJUnitPlatform() }`, Paper API also on `testCompileOnly`)
- Test: `companion/src/test/java/sk/adamko/velospawn/companion/SeasonStatsTest.java`

**Interfaces:**
- Produces:
  - `record PlayerStat(String name, long playTicks, long deaths)` (nested in `SeasonStats`)
  - `record SeasonStats(int players, long playtimeTicks, long deaths, String topName, long topTicks, long gameTime, long collectedAt)` with `static SeasonStats aggregate(List<PlayerStat> stats, long gameTime, long collectedAt)` and `JsonObject toJson()` (adds `"v":1,"type":"stats"`; field names identical to the proxy record in Task 1)
  - `StatsCollector(Companion plugin)`, `void start(long intervalMinutes)`, `Optional<SeasonStats> latest()`

Aggregate rules: players = entries with `playTicks > 0`; top = highest `playTicks` (ties: first by name), null when no players; name null → UUID string is supplied by the caller.

Collection (every interval, first run 1 tick after enable, on the main thread — `Bukkit.getOfflinePlayers()` + `OfflinePlayer#getStatistic(Statistic.PLAY_ONE_MINUTE)` / `Statistic.DEATHS`, name fallback to UUID; `gameTime` from `Bukkit.getWorlds().get(0).getGameTime()`), then, if any player has the channel registered, send `toJson()` through the first such player.

- [ ] **Step 1: Write the failing tests:**
  - `aggregateSumsAndPicksTop`: `[("Adamko",36_000_000,10), ("Bob",1_000,2), ("Ghost",0,0)]`, gameTime 28_896_000, at 5 → players 2, playtimeTicks 36_001_000, deaths 12, topName "Adamko", topTicks 36_000_000.
  - `aggregateOfNobodyHasNoTop`: `[]` → players 0, topName null.
  - `jsonCarriesTypeAndFields`: `toJson()` has `type == "stats"`, `v == 1`, `players`, `collectedAt`.
- [ ] **Step 2: Run** `./gradlew :companion:test` — compile failure.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `./gradlew :companion:test :companion:build` — PASS.
- [ ] **Step 5: Commit** `git commit -m "Companion counts its world's players, playtime, deaths and days for /seasons"`

### Task 7: Companion — drawing the menu

**Files:**
- Create: `companion/src/main/java/sk/adamko/velospawn/companion/SeasonsMenuView.java`
- Modify: `companion/src/main/java/sk/adamko/velospawn/companion/Companion.java` (`registerIncomingPluginChannel(this, CHANNEL, ...)`, dispatch `type == "menu"` to the view, register the view as listener)

**Interfaces:**
- Consumes: the `menu` payload of Task 4; `Companion.sendJson` (Task 6)
- Produces: `SeasonsMenuView(Companion plugin)` implementing `Listener` and `InventoryHolder`-based tracking; `void open(Player player, JsonObject menu)`

Behaviour: inventory size = `9 * ceil(items/9)` (min 9); title and item names/lore via `GsonComponentSerializer.gson().deserializeFromTree`, with `decoration(ITALIC, false)` applied to name and each lore line; `Material.matchMaterial(icon)` falling back to `PAPER`; `current` items get `setEnchantmentGlintOverride(true)`. `InventoryClickEvent` and `InventoryDragEvent` in this inventory are cancelled; a click on a non-current item closes the inventory and sends `{"v":1,"type":"menu-click","season":"<season>"}`. Malformed payloads are logged at fine level and ignored.

No unit test (pure Bukkit glue); verified by the manual checklist in Task 8.

- [ ] **Step 1: Implement.**
- [ ] **Step 2: Run** `./gradlew build` — PASS.
- [ ] **Step 3: Commit** `git commit -m "Companion draws the /seasons chest menu and reports clicks"`

### Task 8: Docs

**Files:**
- Modify: `README.md` — `## Commands`: `/seasons`; a short `## Seasons menu` section (what it shows, stats come from the companion, `season-stats.json`, `icon:` setting, rollout: install companion on every season and have someone join s1/s2 once); top line → "Milestones 1–4 and the `/seasons` menu from milestone 5"; checklist entries:
  - `/seasons` on s3 → chest menu, s3 glows "You are here", tooltips match `/avs status` state.
  - `/seasons` in `waiting` → chat list; hover shows stats; clicking `[Season 2]` runs `/play s2`.
  - Click s2 in the chest on s3 with s2 off → waiting action bar, then moved.
  - After a fresh companion install on s1: s1 shows "No stats yet" until someone has been on it; then numbers appear and survive a proxy restart.

- [ ] **Step 1: Edit README.**
- [ ] **Step 2: Commit** `git commit -m "README: /seasons menu"`
