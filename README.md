# Lattice

Lattice is a lightweight, self-hostable server that uses bots to fill queues. It is based on [discursion's Hypixel2020 setup](https://github.com/discursion/hypixel2020), but has many significant improvements. Currently, it has the following game modes:

- Sky Wars: Game types (Normal & Insane), team types (Solo & Teams)
- Bed Wars: Team types (1v1, Solo, Doubles, 3v3v3v3, 4v4v4v4)

It aims to be as lightweight as possible (at the very least self-hostable in a lower-end laptop), and uses many tricks to achieve higher performance. The ultimate goal is having this run well on 1 GB of RAM and 1 CPU core.

Note: this is primarily coded using an LLM. I will not take any responsibility for failures of any kind caused by the usage of this software.

## Standout Features

These are the features that stand out, compared to [the original](https://github.com/discursion/hypixel2020):

- WindSpigot as the server software, instead of CraftBukkit; Java 8 is also no longer used, in favour of Java 21+
- [Continued Slime World Manager](https://github.com/Swofty-Developments/Continued-Slime-World-Manager) instead of the original Slime World Manager or even Minecraft's world manager, as the arena manager. Compared to the original, it can have multiple games in the same map, and is much faster (maps have 1/3 less footprint on CSWM than on the original).
- Replays with most features you'd expect (still has issues).
- More realistic bot behaviour (including some ML components).
- Map importing done in-game with Java, instead of being a Python component.
- An in-game, easy-to-use map editor.

## TO-DO list

Structural improvements:
- Immensely simplify the bridging system (only have the ninja mode for now; use [Illusion's bridge assist](https://codeberg.org/IllusionClient/client/src/branch/master/src/main/java/org/codeberg/illusionclient/feature/module/player/BridgeAssist.java) as the reference for when to sneak, and use [LibreBounce's god bridge rotation mode for Scaffold](https://github.com/LibreBounce/LibreBounce/blob/main/src/main/java/net/ccbluex/liquidbounce/features/module/modules/world/scaffolds/Scaffold.kt#L1207) as the reference for rotations).
- Remove the map generator entirely
- Customization for menus and many other things

Game modes:

- Add a game mode for practicing Bed Wars-related mechanics, with the following sub-modes:
  - Fireball deflecting
  - Bow aiming
  - Game sense scenarios
- Practice/Duels game mode
- Recreate more servers than Hypixel alone; this includes 2019 CubeCraft, 2020 HiveMC, and current Minemen Club

Longer-term goals are:

- Port to another server software (either Minestom, PumpkinMC, or SteelMC)
- Allow to seamlessly add arenas, from normal Minecraft worlds to fully working maps

## License

This is licensed under the Expat license. For more information, check [`LICENSE`](LICENSE)
