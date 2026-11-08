# Lattice: the plugin split

Five jars, five jobs.

| jar | source | owns | depends on |
|---|---|---|---|
| `Lattice.jar` | `src/main/java` + `modules/api` | core (lobby, menus, scoreboard, profiles, commands, game manager), maps, **and the api** | - |
| `h2020-bots.jar` | `modules/bots` | virtual clients, movement, combat, navigation, brains, models | Hypixel2020 |
| `h2020-bedwars.jar` | `modules/bedwars` | Bed Wars game, shop, gadgets, listeners | Hypixel2020 |
| `h2020-skywars.jar` | `modules/skywars` | SkyWars game, kits, loot | Hypixel2020 |
| `h2020-replay.jar` | `modules/replay` | recorder, viewer, league, gate, training arena | Lattice, lattice-bots |

The API classes (`net.h2020.api.*`) ship **inside `Lattice.jar`**, so any plugin that declares `depend: [Lattice]`
can compile against `project(':api')` and see the same classes at runtime through Bukkit's classloader parentage - one
copy, no separate api jar to install.

## Writing another plugin

1. `depend: [Lattice]` (add `lattice-bots` for bots, `lattice-bedwars` for bed-specific work).
2. `compileOnly project(':api')` in Gradle.
3. Ask for services with `H2020.find(...)`: `BotService`, `GameManager`, `MapService`, `ScoreboardService`,
   `ProfileService`, `BedwarsTools`.
4. `BrainSelector.register(world -> ...)` to own the brain for your worlds, `Worlds.arena("your_world")` to mark a
   practice area, `AdminHooks.register("sub", ...)` to add `/h2020` subcommands.

A practice PvP plugin is one world, one listener, one brain factory, and no change to the five shipped jars.

## Building

```
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-arm64
bash gradlew :api:jar :bots:jar :bedwars:jar :skywars:jar :replay:jar jar
```

`./start-server.sh --build` builds and installs all five. By hand: `build/libs/Lattice.jar` plus
`modules/*/build/libs/lattice-*.jar` into `run/plugins/`. Do not leave an old `Hypixel2020.jar` there: it declares the
same commands and either copy can win.
