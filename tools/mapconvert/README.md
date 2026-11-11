# Map conversion: Swofty HypixelRecreation -> Lattice

Source: https://github.com/Swofty-Developments/HypixelRecreation (map locations) and
https://files.catbox.moe/flri48.zip (`.polar` worlds).

## Tools

`PolarToAnvil` reads a Minestom `.polar` world and writes a 1.8-loadable Anvil world folder
(`level.dat`, `region/*.mca`, `data/villages.dat`).

```
CP="$(cat /tmp/cp.txt):run/WindSpigot.jar:tools/mapconvert/build"
JAVA_HOME=/usr/lib/jvm/java-25-openjdk-arm64 /usr/lib/jvm/java-25-openjdk-arm64/bin/javac \
    -cp "$(cat /tmp/cp.txt):run/WindSpigot.jar" -d tools/mapconvert/build tools/mapconvert/PolarToAnvil.java
/usr/lib/jvm/java-25-openjdk-arm64/bin/java -cp "$CP" PolarToAnvil in.polar out/world --force
```

JDK 25 is required: polar 1.16.0 and minestom are compiled for 25. `/tmp/cp.txt` holds the resolved runtime
classpath (polar, minestom, zstd-jni, fastutil, slf4j, annotations); regenerate it with the throwaway Gradle project
described in the task log if it is lost.

`MapsJsonToYaml` converts `configuration/bedwars/maps.json` and the skywars `*_config.json` files to `map.yml`.

## What the slime importer requires (learned the hard way)

The `.polar` reader MUST be `PolarReader.read(byte[])`. `PolarLoader`'s static initialiser needs a live Minestom
server and throws `NullPointerException: serverProcess is null` headless.

For `Slime.importFolder` to accept the output world:

- `level.dat` with `Data{...}` containing `GameRules`, `SpawnX/Y/Z`;
- `data/` non-empty (seed `data/villages.dat` as an empty gzip NBT root);
- **each chunk needs `xPos` and `zPos`** (the modern names) and `Status: "full"`. 1.8 only writes
  `xPosition`/`zPosition`, so both pairs are written. Without `xPos`, `ChunkImporter.readChunk` throws
  `NoSuchElementException: No value present` and the import silently reports success with zero maps.

## Height

`.polar` worlds span Y -64..319; 1.8 spans 0..255. Sections outside 0..15 are dropped and counted, not silently lost.

## Result

Six worlds converted, **0 unmapped block states** in every one:

| map | family | chunks written |
|---|---|---|
| airshow | bedwars | 1024+ |
| lighthouse | bedwars | |
| rooftop | bedwars | |
| speedway | bedwars | |
| ancient | skywars | |
| congo | skywars | |

`haven` and `one_block` have configs in the repo but **no world file in the archive** — nothing to convert.
