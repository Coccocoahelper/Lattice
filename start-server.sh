#!/usr/bin/env bash
# Start a Lattice server on WindSpigot.
#
#   ./start-server.sh            use the jars already built in build/libs and modules/*/build/libs
#   ./start-server.sh --build    run the Gradle build first, then start
#   ./start-server.sh --clean    drop run/plugins/*.jar before installing, so no stale jar can load
#   ./start-server.sh --fifo     run headless: console input is run/cmd.fifo, output goes to run/server.log
#
# The plugin is six jars now: core, bots, Bed Wars, SkyWars and replay. They are copied into
# run/plugins, and the server data lives in run/plugins/Lattice (an old Hypixel2020 folder is
# migrated by the plugin on first boot).
#
# Env:
#   PLUGIN_SRC   directory holding Lattice.jar and lattice-*.jar, if not the local build
#   JAVA_HOME    JDK 21 or newer; otherwise .tools/jdk* or java on PATH is used
#   LATTICE_MEM  heap, default "2G 4G" (initial and max)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
RUN="$ROOT/run"
DATA="$RUN/plugins/Lattice"
WINDSPIGOT_URL="${WINDSPIGOT_URL:-https://github.com/Wind-Development/WindSpigot/releases/latest/download/WindSpigot-2.1.4.jar}"

BUILD=0
CLEAN=0
FIFO=0
for arg in "$@"; do
  case "$arg" in
    --build) BUILD=1 ;;
    --clean) CLEAN=1 ;;
    --fifo) FIFO=1 ;;
    -h|--help) awk 'NR>1 && /^#/ {sub(/^# ?/,""); print; next} NR>1 {exit}' "$0"; exit 0 ;;
    *) echo "unknown option: $arg (try --help)" >&2; exit 2 ;;
  esac
done

download() {
  if command -v curl >/dev/null 2>&1; then curl -fsSL "$1" -o "$2"
  elif command -v wget >/dev/null 2>&1; then wget -qO "$2" "$1"
  else echo "Neither curl nor wget is installed." >&2; exit 1
  fi
}

# --- Java 21+ -------------------------------------------------------------------------------------
java_major() { "$1" -version 2>&1 | sed -n '1s/.*version "\([0-9]*\).*/\1/p'; }

JAVA=""
for candidate in "$ROOT"/.tools/jdk*/bin/java "${JAVA_HOME:+$JAVA_HOME/bin/java}" "$(command -v java || true)"; do
  [ -n "$candidate" ] && [ -x "$candidate" ] || continue
  major="$(java_major "$candidate")"
  if [ -n "$major" ] && [ "$major" -ge 21 ] 2>/dev/null; then JAVA="$candidate"; break; fi
done
if [ -z "$JAVA" ]; then
  echo "No JDK 21+ found. Install Temurin 21 (https://adoptium.net/temurin/releases/) and set JAVA_HOME." >&2
  exit 1
fi
echo "Java: $JAVA ($(java_major "$JAVA"))"

# --- build ----------------------------------------------------------------------------------------
if [ "$BUILD" = "1" ]; then
  echo "Building..."
  ( cd "$ROOT" && JAVA_HOME="$(dirname "$(dirname "$JAVA")")" \
      bash gradlew --no-daemon jar :bots:jar :bedwars:jar :skywars:jar :replay:jar :practice:jar )
fi

# --- the five plugin jars -------------------------------------------------------------------------
source_jar() {
  case "$1" in
    Lattice) echo "$ROOT/build/libs/Lattice.jar" ;;
    *) echo "$ROOT/modules/$1/build/libs/lattice-$1.jar" ;;
  esac
}

mkdir -p "$RUN/plugins"
if [ "$CLEAN" = "1" ]; then
  rm -f "$RUN/plugins"/*.jar
fi

# the old single jar and the h2020-* modules would load a second copy of every command
for stale in "$RUN/plugins/Hypixel2020.jar" "$RUN/plugins"/h2020-*.jar; do
  [ -f "$stale" ] || continue
  mkdir -p "$RUN/plugins/disabled"
  mv "$stale" "$RUN/plugins/disabled/"
  echo "Moved stale $(basename "$stale") to plugins/disabled/"
done

for name in Lattice bots bedwars skywars replay practice; do
  jar="$name.jar"
  [ "$name" = "Lattice" ] || jar="lattice-$name.jar"
  src=""
  if [ -n "${PLUGIN_SRC:-}" ] && [ -f "$PLUGIN_SRC/$jar" ]; then
    src="$PLUGIN_SRC/$jar"
  elif [ -f "$(source_jar "$name")" ]; then
    src="$(source_jar "$name")"
  fi
  if [ -z "$src" ]; then
    # a packaged server already has its jars in run/plugins: keep them instead of demanding a build
    if [ -f "$RUN/plugins/$jar" ]; then echo "  $jar (installed)"; continue; fi
    echo "Missing $jar. Run './start-server.sh --build', or 'bash gradlew jar', or set PLUGIN_SRC." >&2
    exit 1
  fi
  cp -f "$src" "$RUN/plugins/$jar"
done
echo "Plugins: $(cd "$RUN/plugins" && ls Lattice.jar lattice-*.jar | tr '\n' ' ')"

# --- maps: one folder per family -------------------------------------------------------------------
mkdir -p "$DATA/maps/bedwars" "$DATA/maps/skywars"
install_maps() {
  from="$1"
  family="$2"
  [ -d "$from" ] || return 0
  n=0
  for m in "$from"/*/; do
    [ -d "$m" ] || continue
    name="$(basename "$m")"
    if [ -d "$DATA/maps/$family/$name" ]; then continue; fi
    if [ -d "$DATA/maps/$name" ]; then continue; fi   # still flat: the plugin files it on boot
    cp -r "$m" "$DATA/maps/$family/$name"
    n=$((n + 1))
  done
  if [ "$n" -gt 0 ]; then echo "Installed $n $family map(s)"; fi
  return 0
}
install_maps "$ROOT/maps/bedwars" bedwars
install_maps "$ROOT/maps/skywars" skywars
install_maps "$ROOT/import/ready/bedwars" bedwars
install_maps "$ROOT/import/ready/skywars" skywars
# a map dropped straight into import/ready/ is filed on boot using the family in its map.yml
if [ -d "$ROOT/import/ready" ]; then
  for m in "$ROOT/import/ready"/*/; do
    [ -d "$m" ] || continue
    name="$(basename "$m")"
    if [ -d "$DATA/maps/$name" ] || [ -d "$DATA/maps/bedwars/$name" ] || [ -d "$DATA/maps/skywars/$name" ]; then
      continue
    fi
    cp -r "$m" "$DATA/maps/$name"
  done
fi

# --- the block data for the maps: one file per world, they go straight into the data source ------------------------
if [ -d "$ROOT/slime_worlds" ]; then
  mkdir -p "$RUN/slime_worlds"
  n=0
  for f in "$ROOT"/slime_worlds/*.swofty; do
    [ -f "$f" ] || continue
    b="$(basename "$f")"
    if [ -f "$RUN/slime_worlds/$b" ]; then continue; fi
    cp -f "$f" "$RUN/slime_worlds/$b"
    n=$((n + 1))
  done
  if [ "$n" -gt 0 ]; then echo "Installed $n slime world(s)"; fi
fi

# --- the slime world manager (optional, but the default: maps load from one file and every game gets a copy) ----------
SWM_VERSION="${SWM_VERSION:-5.1.0}"
SWM_BASE="https://github.com/Swofty-Developments/Continued-Slime-World-Manager/releases/download/$SWM_VERSION"
fetch_swm() {
  local name="$1"
  local jar="$ROOT/libs/swm/$name-$SWM_VERSION.jar"
  if [ ! -f "$jar" ]; then
    mkdir -p "$ROOT/libs/swm"
    echo "Downloading $name $SWM_VERSION..."
    download "$SWM_BASE/$name-$SWM_VERSION.jar" "$jar" || { echo "Could not download $name" >&2; return 1; }
  fi
  echo "$jar"
}
if PLUGIN_JAR="$(fetch_swm swoftyworldmanager-plugin)"; then
  cp -f "$PLUGIN_JAR" "$RUN/plugins/"
  if AGENT_JAR="$(fetch_swm swoftyworldmanager-classmodifier)"; then cp -f "$AGENT_JAR" "$RUN/"; fi
else
  echo "Continuing without the slime world manager: worlds stay as folders."
fi

# --- server files -----------------------------------------------------------------------------------
if [ ! -f "$RUN/WindSpigot.jar" ]; then
  echo "Downloading WindSpigot..."
  download "$WINDSPIGOT_URL" "$RUN/WindSpigot.jar"
fi

if [ ! -f "$RUN/server.properties" ]; then
  # tr keeps writing after head has its 20 bytes, so pipefail would abort the script on SIGPIPE
  set +o pipefail
  pw="$(LC_ALL=C tr -dc 'a-z0-9' </dev/urandom | head -c 20)"
  set -o pipefail
  sed "s/CHANGE_ME/$pw/" "$ROOT/server-defaults/server.properties" > "$RUN/server.properties"
fi
[ -f "$RUN/spigot.yml" ] || cp "$ROOT/server-defaults/spigot.yml" "$RUN/spigot.yml"
[ -f "$RUN/windspigot.yml" ] || cp "$ROOT/server-defaults/windspigot.yml" "$RUN/windspigot.yml"
[ -f "$RUN/knockback.yml" ] || cp "$ROOT/server-defaults/knockback.yml" "$RUN/knockback.yml"
# the End is a world nobody here plays in; the air world in server.properties is the only Minecraft-generator world
if [ -f "$RUN/bukkit.yml" ] && grep -q "allow-end: true" "$RUN/bukkit.yml"; then
  sed -i 's/allow-end: true/allow-end: false/' "$RUN/bukkit.yml"
  echo "Turned the End world off in bukkit.yml"
fi

if ! grep -q "eula=true" "$RUN/eula.txt" 2>/dev/null; then
  echo "Minecraft servers need you to accept the Minecraft EULA: https://aka.ms/MinecraftEULA"
  read -r -p "Do you accept it? (y/n) " eula
  if [ "$eula" != "y" ] && [ "$eula" != "Y" ]; then
    echo "The server can't start without accepting the EULA."
    exit 1
  fi
  echo "eula=true" > "$RUN/eula.txt"
fi

# the slime world manager loads a class modifier agent; without it the plugin refuses to work
AGENT=""
for a in "$RUN"/swoftyworldmanager-classmodifier-*.jar; do
  if [ -f "$a" ]; then AGENT="-javaagent:$a"; fi
done
if [ -n "$AGENT" ]; then echo "Agent: $(basename "${AGENT#-javaagent:}")"; fi

read -r mem_min mem_max <<<"${LATTICE_MEM:-2G 4G}"
cd "$RUN"
if [ "$FIFO" = "1" ]; then
  # headless: keep the console on a named pipe, log to a file, so scripts and agents can drive it
  [ -p cmd.fifo ] || { rm -f cmd.fifo; mkfifo cmd.fifo; }
  ( setsid nohup sh -c 'sleep infinity > cmd.fifo' >/dev/null 2>&1 & )
  echo "Console: echo '<command>' > run/cmd.fifo   Log: run/server.log"
  exec "$JAVA" $AGENT -Xms"$mem_min" -Xmx"$mem_max" -XX:+UseG1GC ${LATTICE_JVM_OPTS:-} -jar WindSpigot.jar nogui \
      > server.log 2>&1 < cmd.fifo
fi
exec "$JAVA" $AGENT -Xms"$mem_min" -Xmx"$mem_max" -XX:+UseG1GC ${LATTICE_JVM_OPTS:-} -jar WindSpigot.jar nogui
