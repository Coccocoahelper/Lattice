# Features

These are the features Lattice has, written in-depth.

## Maps

Maps are handled using [Continued Slime World Manager](https://github.com/Swofty-Developments/Continued-Slime-World-Manager), which is the only actively maintained fork for 1.8. Using Continued Slime World Manager gives much better performance and low disk usage, as it was specifically built by the Hypixel team for that very purpose. 

<details> <summary>Technical details</summary>
The launcher downloads it into `libs/swm/`, installs the plugin into `run/plugins/`, and passes
`-javaagent:` for its class modifier, which the plugin needs in order to work at all.

When a game or a replay requests for a world, it gets its own clone, allowing for any map to have infinite arenas. `instances/swm_<map>_<n>`, so you can
watch a replay on Airshow while a game is running on Airshow and neither will ever see the other's blocks. Copies are
unloaded with `save=false`, so nothing is written back and the template is untouched.

The worlds are stored in `slime-worlds/`, which are copied to `run/slime_worlds/` on runtime.

`/lattice edit <map>` opens the in-game editor, `/h2020 mapimport <world> <name> [bedwars|skywars]` turns a
loaded world into a map template,
`/h2020 mapimportdir <folder> [bedwars|skywars] [tag,tag]` does a whole pack of world folders one map per second,
`/h2020 maptag <map> <tag,tag|->` rewrites a map's tags and `/h2020 tags` lists them.
</details>