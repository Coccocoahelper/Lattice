# Map conversion: Swofty HypixelRecreation -> Lattice

Source: https://github.com/Swofty-Developments/HypixelRecreation (map locations) and
https://files.catbox.moe/flri48.zip (`.polar` worlds). **These six maps are the entire rotation.** The older
hand-built maps are gone on purpose; do not add them back.

## RAM-only conversion

The intermediate Anvil world exists only because the slime importer's API takes a `File`. It is written to tmpfs and
deleted in the same process, so no Anvil copy ever survives:

```
CP="$(cat /tmp/cp.txt):run/WindSpigot.jar:tools/mapconvert/build:\
libs/swm/swoftyworldmanager-plugin-5.1.0.jar:libs/swm/swoftyworldmanager-api-5.1.0.jar"

/usr/lib/jvm/java-25-openjdk-arm64/bin/java -cp "$CP" PolarToAnvil in.polar --ram <slimeName>
```

That does convert -> import -> delete. Output confirms it:

```
  sections written=251 clippedBlocks=0
  unmapped states=0
  IMPORT: built CraftSlimeWorld for lighthouse
  slime=lighthouse (imported, staging removed)
```

`ls /dev/shm | grep ppolar` is empty afterwards. Pass a real path instead of `--ram` only when you want to inspect the
world; then `--force` replaces an existing directory.

JDK 25 is required: polar 1.16.0 and minestom are built for 25. `/tmp/cp.txt` holds the resolved runtime classpath
(polar, minestom, zstd-jni, fastutil, slf4j, annotations); regenerate it with a throwaway Gradle project if it is lost.

## What the slime importer requires (learned from its bytecode)

Read `.polar` with `PolarReader.read(byte[])`. `PolarLoader`'s static initialiser needs a live Minestom server and
throws `NullPointerException: serverProcess is null` headless.

For `Slime.importFolder` to accept the output world:

- `level.dat` with `Data{...}` containing `GameRules`, `SpawnX/Y/Z`;
- `data/` non-empty (seed `data/villages.dat` as an empty gzip NBT root);
- each chunk needs the **modern** `xPos` and `zPos` names and `Status: "full"`. 1.8 only writes
  `xPosition`/`zPosition`, so both pairs are written. Without `xPos`, `ChunkImporter.readChunk` throws
  `NoSuchElementException: No value present` and the import reports success with **zero** maps -- silent data loss;
- 1.8's `xPosition` holds the **absolute** chunk coordinate, not the region-relative one.

## Height

`.polar` worlds span Y -64..319; 1.8 spans 0..255. Sections outside 0..15 are dropped and counted
(`clippedBlocks`), never silently lost.

## The set

| map | family | islands/teams |
|---|---|---|
| Airshow | bedwars | 8 |
| Lighthouse | bedwars | 8 |
| Rooftop | bedwars | 8 |
| Speedway | bedwars | 8 |
| Ancient | skywars | 12 |
| Congo | skywars | 12 |

All six converted with **0 unmapped block states**. `haven` and `one_block` have configs in the Swofty repo but no world
file in the archive, so they cannot be converted; they are not in the rotation.
