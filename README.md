# Lattice

Lattice is a self-hostable server that uses bots to fill queues, and has the following game modes:

- Sky Wars: Game types (Normal & Insane), team types (Solo & Teams)
- Bed Wars: Team types (1v1, Solo, Doubles, 3v3v3v3, 4v4v4v4)

It aims to be as lightweight as possible (at the very least runnable in a lower-end laptop), and partly achieves this by using an [updated fork of Slime World Manager](https://github.com/Swofty-Developments/Continued-Slime-World-Manager), which not only is actively maintained, but has ~33% less disk footprint than the original.

Note: this piece of software is primarily coded using an LLM. I will not take any responsibility for failures of any kind caused by the usage of this software.

## TO-DO list

- Immensely simplify the bridging system (only have the ninja mode for now; use [Illusion's bridge assist](https://codeberg.org/IllusionClient/client/src/branch/master/src/main/java/org/codeberg/illusionclient/feature/module/player/BridgeAssist.java) as the reference for when to sneak, and use [LibreBounce's god bridge rotation mode for Scaffold](https://github.com/LibreBounce/LibreBounce/blob/main/src/main/java/net/ccbluex/liquidbounce/features/module/modules/world/scaffolds/Scaffold.kt#L1207) as the reference for rotations).
- Add a practicing game mode, with:
  - Fireball deflecting
  - Bow aiming
- Remove the map generator entirely

## License

This is licensed under the GNU General Public License, version 3.0. For more information, check `[LICENSE](LICENSE)`