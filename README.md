# Lattice

## What is Lattice?

Lattice is a recreation of Hypixel **SkyWars** and **Bed Wars** for 1.8.9, with bots that play them. It is not a mockup
of the games: the lobbies, the shops, the generators, the beds and the queue are the ones a 2020 player would recognise,
and every bot in them is a real server-side client holding a sword, bridging across a gap and missing its clicks.

It is about 140 MB on disk once built, and it runs in roughly 1.6 GB of RAM and 1.2 CPU cores with eighty bots online,
so it will happily live on a laptop you already own.

## What does it include?

**Bed Wars**: Solo, Doubles, 3v3v3v3 and 4v4v4v4, with the item shop, team upgrades and traps, generators, beds, bed
bugs, dream defenders, fireballs, TNT and bridge eggs, `/shout`, and mid-2020 (v1.6) prices.

**SkyWars**: Solo and Teams, Normal and Insane, with cages, kits, loot tables, refills, Doom and end phases, and
spectators.

**Bots**: each one is an emulated server-side player, checked by the anticheat like anybody else. They aim and click at
human speeds, bridge in the styles people use, loot, chat, and carry 2020 names and skins; a configurable share of them
cheats.

**Six maps**, and they are the entire rotation: `maps/bedwars/` holds Airshow, Lighthouse, Rooftop and Speedway,
`maps/skywars/` holds Ancient and Congo. They are Swofty's Hypixel Recreation maps, converted from the original
`.polar` worlds with their real Hypixel locations, so the islands, shops, generators and beds sit where Hypixel put
them rather than where an importer guessed. `tools/mapconvert/` documents the conversion. The real Hypixel lobby is
not included.

One caveat on the maps: neither SkyWars map is an eight-team map, so `teams_normal` and `teams_insane` have nowhere to
run. Every other mode does.

**Maps carry tags**, because not every map should always be in the rotation. A map's `map.yml` can say
`tags: [dreams]` or `tags: [seasonal, christmas]`, and `maps/disabled-tags` in `config.yml` switches a whole tag off:
list `seasonal` there and no seasonal map will be picked, while everything else carries on. A map is off when any one of
its tags is listed, an untagged map can never be switched off, and a tag kept out of the rotation is also refused by
`/h2020 create` rather than only being skipped by the queue. `/lattice tags` prints every tag in use, how many maps
carry it and whether it is switched off, and `/lattice maptag <map> <tag,tag|->` edits a map's tags in place.

**Five jars**: `Lattice.jar` (core, maps and the API), `lattice-bots.jar`, `lattice-bedwars.jar`, `lattice-skywars.jar`
and `lattice-replay.jar`. You can leave a game out and the rest still boots.

Lattice was put together in about one to two hours with Claude, and the anticheat is not fully accurate. Fix it
yourself, or source the "accurate" one the billionaire is making.

## What do I need?

**Java 21 or newer**, for both building and running. The launcher looks in `.tools/jdk*`, then `JAVA_HOME`, then your
`PATH`, and refuses to start without it: [download Temurin 21](https://adoptium.net/temurin/releases/?version=21).

You do not need a database, a proxy or any other plugin. The world manager and the server jar are downloaded on the
first start.

## How do I run it?

The easiest way is to paste this repo's link into Claude and ask it to set the server up. Doing it yourself is four
steps:

1. Download the repo (green **Code** button → **Download ZIP**, then unzip) or `git clone` it.
2. Start the server: `start-server.bat` on Windows, `./start-server.sh` on Mac or Linux. Add `--build` (or `build` on
   Windows) to compile first; without it the launcher uses the jars already in `run/plugins/`.
3. Accept the Minecraft EULA when it asks, once.
4. Wait for `Done` in the window, then join `localhost` on Minecraft 1.8.9. You are made admin automatically.

To stop it, type `stop` in its window.

Two flags are worth knowing. `--clean` wipes `run/plugins/*.jar` before installing, so a stale jar cannot load
alongside the new ones, and `--fifo` runs headless with the console on `run/cmd.fifo` and the log in `run/server.log`,
which is how you drive it from a script.

## How do worlds work?

Worlds are handled by [Continued Slime World Manager](https://github.com/Swofty-Developments/Continued-Slime-World-Manager),
the 1.8 variant. The launcher downloads it into `libs/swm/`, installs the plugin into `run/plugins/`, and passes
`-javaagent:` for its class modifier, which the plugin needs in order to work at all.

**Block data** ships in `slime_worlds/`, twenty-three files of 1.1 MB covering the twenty maps and three lobbies, and the
launcher copies them into `run/slime_worlds/`. `maps/<family>/<map>/` holds only a `map.yml`, so a fresh install has
every map without a conversion step.

**One copy per game.** A game or a replay asks for a world and gets its own clone, `instances/swm_<map>_<n>`, so you can
watch a replay on Airshow while a game is running on Airshow and neither will ever see the other's blocks. Copies are
unloaded with `save=false`, so nothing is written back and the template is untouched.

**Converting.** `/lattice convert all` imports every map and every lobby into the slime store. `/h2020 slimeprobe <map>`
spawns one copy, counts the blocks at its mid point and releases it again, which is the quickest way to check that the
store is healthy. If you delete the world manager entirely, everything still runs: the instance creation falls back to
copying the folder.

**The placeholder world.** The only world Minecraft still generates for itself is `world`, and it generates air only
(`level-type=FLAT`, `generator-settings=3;minecraft:air;1`). The End is off in `bukkit.yml` and the Nether never starts,
so no terrain exists outside the slime store.

## What commands are there?

Everything hangs off `/lattice`; `/h2020` and `/lat` still work as aliases. Use the compass or the NPCs in the lobby to
join a game; settings live in `run/plugins/Lattice/config.yml`.

**Playing**: `/play <mode>` joins a game, where the mode is one of `solo_normal`, `solo_insane`, `teams_normal`,
`teams_insane`, `bedwars_eight_one`, `bedwars_eight_two`, `bedwars_four_three` or `bedwars_four_four`. `/lobby` takes you
back.

**Games**: `/h2020 create <mode> [map]` starts one on a specific map, `/h2020 games` lists them, `/h2020 watch <id>`
spectates one, `/h2020 fillspeed <x>` sets how fast bots fill a queue (1 is real 2020 timing), `/h2020 cheaters
<percent>` sets the share of bots that cheat, `/h2020 rank <player> <rank> [color]` gives somebody a rank, and
`/h2020 date today` puts today's date on the scoreboards instead of a 2020 one.

**Bots**: `/bot spawn [name] [cheat]` spawns one, `/bot list` shows what every bot is doing, `/bot settings` opens the
menu for skill, latency and behaviour switches, `/bot skill <0-1|default>` sets the skill of new bots, `/bot ml [reset]`
reports or clears the learned fight model, and `/bot pvp [v1|v2|ab|reset|debug]` switches fight behaviour and dumps the
combat telemetry.

**Maps**: `/lattice edit <map>` opens the in-game editor, `/h2020 mapimport <world> <name> [bedwars|skywars]` turns a
loaded world into a map template,
`/h2020 mapimportdir <folder> [bedwars|skywars] [tag,tag]` does a whole pack of world folders one map per second,
`/h2020 maptag <map> <tag,tag|->` rewrites a map's tags and `/h2020 tags` lists them.

**Everything else**: `/report <player>` opens the 2020 report menu, and `/spectate <player>` follows somebody's game.

## How do I change where something is on a map?

`/lattice edit <map>` puts you inside a private copy of the map, in creative and flying, with a setup book, a wand and
an exit. It is the same idea as the BedWars1058 arena setup addon, and it is how a map that imported almost right gets
fixed without touching the file by hand.

**The menu** has a page per kind of point. Teams gives each of the eight teams its spawn, bed, iron and gold generator,
shop villager, upgrades villager and two base corners. Generators lists every diamond and emerald point, with a button
to add one and shift-click to delete one. Lobby holds the waiting lobby spawn, the spectator spawn and mid. The last
page has the build limit and the two bounds corners.

**The wand** is a blaze rod. Arm a point in the menu, then left-click the block where it belongs — for a bed, click the
bed itself and both halves are written with the map's own facing rather than your yaw. Shift-clicking a menu item uses
your current position instead, which is what you want for a spawn or a villager.

Every write goes into that map's `map.yml` and reloads the maps immediately, so the next game uses it and nothing has to
be re-imported. `/h2020 setpoi <map> <key> [head]` does the same thing from a command, which is what you want in a
script, and `/h2020 edit <map> selftest` builds every page and reports the item counts so the menus can be checked from
the console.

Two limits worth stating: the editor moves points only — blocks you place in the copy are not saved back into the
template — and a rewrite serialises the whole `map.yml`, so hand-written comments in that file are lost the first time
a point is moved.

## How do the bots play?

Nothing below is a scripted loop. Every behaviour is driven through the same client input a person produces — crosshair,
keys, clicks — and every one of them is gated by the bot's persona, its skill, and what its client is actually allowed
to know.

### What do they do in a fight?

**Stomping**: a block placed into the step the opponent is taking, which kills their sprint and hands over a free hit.
If they are airborne a second block goes on top, so the jump cannot clear it.

**Two-block pillars**, and the strafe around them: two blocks between the two of you, then a strafe around the pillar.
Their client still renders the bot where it used to be, so the first hit off the flank lands.

**Middle exits**: on a line — a bridge, a corridor — the bot digs the middle out and fills the hole from its own side,
so the opponent has to jump a step while the bot hits them over it. Breaking a bed uses the same idea in reverse: dig in
through the middle of the defence and seal the cells beside the hole, so a defender cannot reach around it.

**Head hitters**: a block two above the bot's own head, placed off a side support where one exists, then a jump
underneath it. Less knockback taken, more momentum while crossing.

Older tools stay: tower up when low and chased, wall the bridge behind, cut the bridge with shears or a pickaxe.

**When cornered**, a wall at the bot's back means there is nothing to run to, so it plants, holds the block-hit longer
because the wall eats the knockback, and fights from there. **When the bed is gone** it switches to clutch mode: longer
leash, pearls and fireballs always on, and the shop buys the clutch kit — knockback stick, ender pearl, potions, TNT and
more blocks. The stick comes out for hits that are worth a void kill.

### What do they do while travelling?

**Bridge repair**: a hole somebody left in a line the bot is crossing gets a block back into it, rather than being
jumped over the void. **Head hitters** work on a contested bridge too. **Extensions** no longer stop at three blocks: a
rush bridges the last gap with whatever is left, or with a bridge egg when the wool is gone.

From the competitive bridge guide, the three things that transfer to Bed Wars: stacking while bridging, looking back at
the bridge while crossing it, and re-establishing height. A bot that gets put below somebody stacks back up to their
level instead of hitting their feet.

### How do they handle fireballs and beds?

**Fireballs** are read from their line and time to impact. A good player punches them back — a sword swing inside reach
sends the fireball back, since the server implements the deflect on the arm swing the way Hypixel does — and everyone
else steps out of the line, sideways onto ground when there is any, otherwise straight away from it. Fireballs fly at
Hypixel's measured constant of 26.9 blocks per second, not vanilla's accelerating speed.

**Beds**: rushers walk past players who are not stopping them, break the bed in reach in the middle of a fight when the
defender is busy, and buy the shears or pickaxe for the bed they are going for before anything else.

### How does team play work?

**Splitting**: a bot that finds a teammate on the island forge takes the spot just outside it — inside the split radius,
outside item pickup — and is fed by the teammate's pickups. If nothing arrives, it takes the forge itself.

**Callouts**: `inc` sends a bot back to guard the bed, `void` makes it remove itself (only while the bed is alive, since
with the bed gone that would be the final death), `drop` makes it hand its items over, and `split` sends it to the forge.

**Handing items over**: low on health with a teammate next to it, a bot drops its emeralds, diamonds, gold and iron at
their feet.

**Losing valuables gracefully**: when the learned fight model says a fight is lost, the bot gets the valuables out of the
way first — teammate, then the island's ender chest, and only then the void — so the kill loots nothing.

One of them bridges, definitively. Per bed, the bot closest to it is elected leader (ties by name, sticky for twenty
seconds), everybody within eighteen blocks follows on its line and never starts a parallel bridge, and the role is only
re-checked once a second so a respawn cannot flip it mid-bridge. They also rush together — the bed a teammate is already
rushing is the bed the next bot picks — and when two bots have one enemy and the model has already won that fight, the
weaker one leaves and takes the enemy's bed while the other keeps fighting, with a hand-off claim so only one of them
ever does it.

Teams that lose their bed get hunted: the last player of such a team is pulled to the front of the queue.

### How do they know things a client shouldn't?

**Invisible players** are never seen, because the client never renders them. They are found the way a player finds one:
the armour that stays visible on the body up close, the potion particles that follow them, and game sense — the memory
of where they were heading, extrapolated further than for a visible player. Aim and range checks then use that guess,
not their real position.

**Latency** is real. Bots see other players where they were half a round trip ago, `(their ping + the bot's ping) / 2`,
interpolated between server ticks, and their reaction is delayed by the same amount. A 180 ms bot genuinely fights the
past position of a 20 ms bot.

**Breaking and building rules**: bots only dig blocks a player placed. A map block stops a route dead and the navigator
replans around it, and the bridge cut and middle exit refuse map blocks too. A cut is skipped when a teammate is
standing on or just behind the cell that would drop, which does not apply to opponents. The bed defence gets two full
passes over every open cell from every stand around the bed, so it stops leaving holes, and placements are paced faster
with the policy crediting fast ones more than slow ones.

**The tab list and spectators**: the tab list only lists the players you are actually with — your game's players if you
are in one, your lobby's if you are in a lobby. Bots never see spectators or dead players, because a game brain's
perception filter only accepts players of that game who are alive. Dead players get the usual spectator compass.

**Movement**: bots sprint on straights and sprint-jump across flat ground when nobody is fighting them. Airborne is more
knockback, so never in a fight, and the jump cadence is capped by food.

## How do the bots learn?

**From people, always.** Every fight that involves a human is sampled from both points of view — features at the first
hit, a win for the killer and a loss for the victim — and feeds the same model the bots use, so human play teaches them
who wins what. `/bot ml` shows how many samples came from people.

**From bots, only if you ask.** `/bot ml bots on|off` defaults to off. Bot labels are cheap, but a hundred bots on guard
duty once made "idle, wants to defend" the answer to everything. Each class is also weighted by its rarity, so a common
one cannot swamp a rare one.

**The fight model** is an online logistic regression over eighteen variables of the fight — health on both sides, gear,
weapon, distance, blocks, gapple, buffs, teammates nearby, void beside each of them, hits taken, combo, armour — which
snapshots at first reach and is labelled when one of them dies. It never answers with a coin flip: until it has seen
enough fights it runs on a hand-written Bed Wars duel baseline, and the learned weights replace that baseline over the
first 150 or so fights. Predictions break off hopeless fights and decide when to hand over, chest or void the
valuables. It saves to `plugins/Lattice/fight-model.yml` and keeps improving across restarts.

**The placement policy** picks which placement to go for — nothing, stomp, two-block pillar, middle exit, head hitter,
tower, bridge wall, bridge cut. Each action scores as the persona's habit plus a learned correction from the fight
context, updated with REINFORCE against a baseline. Geometry is never learned: whether a stomp is possible stays a
physics check.

**The aim policy** is a four-armed bandit over aim profiles — head with lead, chest, low torso, feet — rewarded by the
hit rate of the arm. It moves the point the human aim model heads for and how much lead it takes; the mouse work,
reaction time and error stay human.

**The fight flow model** is a linear prediction of where the opponent will be ten ticks from now, trained online against
what actually happened. It moves the cell a stomp goes into, picks the side of a two-block pillar to strafe around, and
serves as a lead term when aiming at a point.

**The duel policy** handles movement and clicks inside melee: a stance is chosen every ten ticks — press, circle left,
circle right, space, block-hit, reset, jump-crit — held, and scored on the exchange it produced. The aim policy and the
flow model feed it, so "where to aim" and "where they will be" are one decision.

**The next-opponent model** predicts who this bot meets next from its own perspective: distance, closing speed, whether
they are heading at our bed, how good they look, bed up or not, last of their team, time since seen. Teams that are out
are not candidates.

**The macro policy** picks the next big thing to do — shop, upgrade, iron, defend, rush, diamonds, emeralds, guard, share
or deposit. The hand-written utility scores are the prior and the learned correction comes from whether the chosen plan
achieved anything, bounded to ±35% so a model punished for one option can never promote something the utility considers
useless. Bots occasionally take their second-best idea, more so the worse they are.

**Team resources**: a bot with emeralds or diamonds goes home and puts them in the island chest, or the ender chest when
it is solo or the last one alive, and takes diamonds back out of the team chest before buying an upgrade. Teammates hear
about it: "going mid", "rushing blue", "got 3 ems", "dropping at base", "inc blue".

**Human placements** are their own dataset: every block a person places while an enemy is on him is one labelled example,
where the state is the input and what the block is — under the feet, a pillar, a wall in the enemy's face, a block over
the head, an extension — is the label. The placement policy gets a bonus for whatever a person picked in that exact
state, so the bots' own bandit only adjusts around human play.

**Team delegation** decides the team's jobs before any bot decides its own: one defender while the bed is soft, one
diamond runner, one emerald runner in teams of three or more, two rushers in a team of four, everybody else on beds.
Each bot then only picks the details of the job it was given, and assignments stay sticky while they remain valid, so a
bridge is never abandoned because the board shuffled.

`/bot ml` prints all of them and `/bot ml reset` clears them; the policy files sit next to `fight-model.yml`.

## What is wrong with them, and what did you do about it?

**Void falls.** Telemetry said 41 falls, 28 deaths and a 0/11 block clutch in one game. The cause was not the clutch
geometry but the hand: bots died with a stack of wool in the inventory and nothing in the hotbar, so they decided they
had no blocks and gave up. They now swap one in first, and the outcomes are counted so what is left is measurable.

**Generators.** Two bugs kept bots off the forges entirely. The macro policy was rewarding any task that lasted longer
than two seconds, so a long guard taught itself to be chosen and every bot ended up guarding; rewards are now per task
result and the learned term is a 0.35× correction on the hand-written scores. The split stance also deadlocked when the
"teammate on the forge" radius was larger than the stance itself, so both bots stood outside and nobody picked anything
up.

**Diagonal bridging** is unfinished, and honest about it. The stance faces back down the bridge and travels on the S key
so the body backs off the lip before each click, which is the geometry that makes a second block per stance legal, but
most attempts get stuck before the first block. So a bad stance can never cost a game: two failures put diagonals back
on the straight bridge for sixty seconds, then five minutes, then fifteen, then until it is switched on again. Success
clears the park.

**A bot's reasoning is inspectable.** `/bot why [name]` prints the role, the task, the block count, whether the shop plan
is empty, the target team and the ten macro scores that decided it. `/bot counters` dumps every telemetry counter.

## What movement tricks do they use?

**Neos.** The planner jumps around a wall instead of mining it: it backs up for runway if there is none, sprints, jumps
on the last tick that still clears, strafes forty-five degrees through the turn and re-aims at the landing. Both
"straight past the wall" and "around its end" landings are searched, and it is tried both when a path node wants through
a one or two block obstacle and when the body is pressed against one mid-path. A live 4v4 on Treenan has roughly six of
sixty-one bots with a neo available at any moment, and sixty to seventy completed neos per three-minute game with every
jump landing. The counters are `neo.start`, `neo.jump`, `neo.ok`, `neo.fell`, `neo.miss`, `neo.nospeed` and
`neo.timeout`.

**Ladder plonk.** Ladders cancel knockback, so when a hit is about to send a bot off an edge and it is carrying ladders,
it plants one under itself (`trick.ladder`). This is why the shop buys a ladder early, four iron after the first stack
of wool.

**Fireball jumps.** Jump first then throw at your feet for height; throw behind yourself for horizontal distance. Used
when a target is more than 2.2 blocks above (`trick.fireball.up` and `trick.fireball.horiz`).

**Fake swings.** A bare arm swing at 3.3 to 4.6 blocks, out of reach, baits a hit that whiffs; the probability rises with
skill (`trick.fake`). **Hit-select** is the opposite: sometimes the first exchange is deliberately theirs, and the bot
hits into the recovery (`trick.hitselect`).

**Generator rooms.** A losing fight retreats into a generator room when one is within nine blocks, because the walls eat
the knockback that would otherwise be fatal (`trick.genroom`).

`/bot neo` is the probe for all of this: it reports how many bots have a neo available on any heading and how many carry
the item kit.

## What are drive-thru and bed block-off?

**Drive-thru** places two short walls at the enemy's shoulders with the corridor between them left open. The blocks are
not the point — the walls take away the strafe, so the next hit sends them straight back down the corridor. It needs
three blocks, is most likely to start near an edge, and aborts if they step more than 1.8 blocks from where the walls
were planned (`block.drivethru`, `block.drivethru.place`).

**Bed block-off** is the same idea before a dig: if a defender is within eight blocks and the bot carries five or more,
three cells are walled across his path to the bed, 1.5 blocks out from the bed centre, and the dig only starts once the
wall is up. Cells that will not place are skipped rather than aborting the wall (`bed.raid.seal`, `bed.raid.seal.ok`,
and the reasons `bed.raid.seal.far`, `.noblocks`, `.nofoe`, `.nocells`).

## What is the diagonal stance?

`bots.diagonal-stance`, `/bot diag on|off`, or the toggle in the bot settings menu routes true diagonals through a
dedicated stance instead of the straight bridger. It is off by default, and it is the unfinished one described above.
The counters are `diag2.start`, `.place`, `.pair`, `.pair.miss`, `.rejected`, `.stuck`, `.fell`, `.done` and
`.cancel`.

## What about 1v1 duels?

`/h2020 create bedwars_duel [map]` builds a two-player duel on any **solo** map: eight islands, eight beds, only two
used. The first player to join takes **RED**, the second **AQUA**, and the other six teams start empty so they count as
eliminated from the first tick. The map is otherwise untouched, so the ring between the two islands is real middle
ground — emerald islands, diamond islands and the bridges between them all stay in play.

```
/h2020 create bedwars_duel Airshow     # 1v1 on Airshow
/h2020 start <gameId>                  # fill the second slot with a bot
```

Bots treat it as a solo game, one player per team, so they defend, rush and clutch exactly as they do in solo.

## What do replays and self-play do?

**Replays** (`/h2020 replay`, or the Replays book in the Game Menu) record at 20 TPS: positions, look, health, sneak,
held item and armour with leather dye, swing and hurt and eat animations, invisibility, blocks placed and broken with
crack stages, projectiles, dropped items, explosions and static NPCs. Playback runs in a game-owned world of the recorded
map, in adventure mode, with a hotbar for players, speed, ten-second skips, pause and stop. The world is put back exactly
as it was when a replay ends.

**Self-play** (`/h2020 train start|stop|report|hindsight`) runs bot-only games, snapshots policy generations, gates a
challenger against the champion on a held-out slice of the replay log, and replays stored decisions at a quarter learning
rate. Perception models stay human-only, and bot data stays opt-in so "idle, wants to defend" cannot come back.

## What changed recently?

**Stacking** is `N+1` blocks forward then one up — two-stacking is three forward, three-stacking is four forward — with
the rise done by the pillar motor so the click is timed against server lag. The rise is only accepted when the body is
physically one block higher, and the bridge continues from the new level afterwards.

**Placement arms** now include SHELTER, a two-high wall against a shooter, BACKSTEP, a block on the chaser while backing
out, and RAMP, one block in front that gets jumped on for high ground. The policy experiments with a novelty bonus per
untried arm plus a skill-scaled epsilon, so new arms actually get used instead of the policy settling on three.

**Diagonal bridging** starts on the axis whose first cell already has floor under it, so there is no corner hole, and
only clicks when the body is on the line, so the staircase does not drift into gaps.

## What if it does not work?

**"No JDK 21 found"**: install Temurin 21 and either put it in `.tools/` or set `JAVA_HOME`. The launcher checks the
version and will not start on anything older.

**The build fails**: check that the JDK the build uses is 21 or newer, and that you are running `bash gradlew` rather
than `./gradlew` if the wrapper has lost its execute bit.

**Solo Bed Wars says there is no game**: the server has no map block data. Copy `slime_worlds/` into `run/`, or start
through the launcher, which does it for you.

**I cannot connect**: use Minecraft **1.8.9**, and join `localhost` while the server window is open.
