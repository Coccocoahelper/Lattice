# Adding a game, mode or network to the base

The plugin is a network shell. Core owns lobbies, menus, counts, scoreboards, maps, bots and commands; a game hangs off
it as a module; a mode is data. Bed Wars and SkyWars are the two shipped games.

## The pieces

- `Module` (`net.h2020.api.Module`) enables a feature and registers services through `H2020.register(...)`.
- `GameProvider` (`api/game/GameProvider`) creates games for one family: `create(GameMode, MapTemplate, String id)`.
- `Game` (`api/game/Game`) is a running match. `type()` returns a `GameMode`, so a game never depends on the enum.
- `GameMode` (`api/game/GameMode`) is one playable mode: id, family, team size, teams, icon, menu text, order, `insane()`.
  `GameType` implements it; anything else can be registered with `GameModes.register(...)` or `/h2020 addmode`.
- `GameRules` (`api/game/GameRules`) is what a family allows: build, break, pvp, hunger, fall damage, respawn, win
  condition. `GameListener` reads it, so a build-free or no-fall mode is config.

## The three data files

- **network.yml** — the server's identity: brand name, tab header/footer, MOTD, fake counts, per-family display name,
  menu title, sidebar title, icon, and per-family `rules`.
- **menus.yml** — per-mode icon, data, order, name, subtitle, description/lore. Anything not set falls back to the
  mode's own values, so the file can start empty.
- **shops/bedwars.yml** — generated from the code catalogue on first run, then applied over it: change a price or set
  `enabled: false`. `/h2020 reload` re-reads all three.

## Adding a mode (no code)

`/h2020 addmode bedwars_test BEDWARS 8 1` registers a mode at runtime; it appears in the menus, counts, tab completion
and `/h2020 create bedwars_test Airshow`. Add a `menus.yml` block with the same id to control how it looks.

## Adding a game (code)

1. New family: one `GameFamily` constant. Everything else about the family comes from `network.yml`.
2. `MyGame implements Game` — the bedwars and skywars games are the reference (`state`, `tick()`, `addPlayer`,
   `removePlayer`, `scoreboard()`, `end(reason)`).
3. `MyProvider implements GameProvider`, registered with `registerProvider(...)`.
4. `MyModule implements Module`, added to the enable list in `H2020Plugin`.
5. Maps: `MapTemplate` exposes `points(key)`, `allPoints()` and `MapKeys` helpers (`teamSpawn`, `teamBed`, ...), so the
   POI schema is already game-agnostic; a generator only has to fill it.
6. Bots: implement `BotBrain` per family (see `BedwarsBrain`, `SkywarsBrain`, `LobbyBrain`).

## What is still shaped like Bed Wars

- Bed rules (bed break, respawn, spectator) live in `GameListener`, not in `GameRules`.
- `ShopCatalog`'s item set is Bed Wars'; another game needs its own catalogue (the YAML loader is generic enough to copy).
- Recorder/replay capture is generic, but the event names it writes are Bed Wars events.

## Map points without regenerating

`/h2020 setpoi <map> <key> [head]` writes one point into that map's `map.yml` from where you stand (or
`/h2020 setpoi <map> <key> <x> <y> <z>` from console) and reloads the map service. Keys are
`team.<colour>.spawn|bed|generator|shop|upgrades`, plus `waiting.spawn` and `spectator.spawn`. Bed keys write both
halves; add `head` to move the head block only.

## Private games

`/h2020 private <mode> [map] [code] [max]` creates a game the queue and the bot filler never join; players enter with
`/h2020 joincode <code>`. The cap overrides the mode's player count, and the code doubles as the game's identity in
`/h2020 list`.

## Team chests

The chest already standing in every base is the team chest. Punch it with a stack in hand and that stack, and only
that stack, goes in; an enemy chest refuses, an ender chest takes your stack into your own ender chest. There is no
cooldown, and the game finds the chest by scanning the base box (or a radius around the bed) rather than placing one,
so maps keep whatever chests they were built with.
