package net.h2020.bots.ai.brain;

import net.h2020.api.bot.AimIntent;
import net.h2020.api.game.GameState;
import net.h2020.api.game.SkywarsView;
import net.h2020.bots.ai.inv.Inv;
import net.h2020.bots.ai.inv.Looter;
import net.h2020.bots.ai.inv.Organizer;
import net.h2020.bots.ai.motor.BlockBreaker;
import net.h2020.bots.ai.motor.Motor;
import net.h2020.bots.ai.nav.NavOptions;
import net.h2020.bots.ai.perception.Perception;
import net.h2020.bots.ai.perception.Sighting;
import net.h2020.bots.ai.util.AIMath;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * SkyWars: cage idling -> drop (eyes on the first chest) -> loot the whole own island -> equip and sort the hotbar
 * -> look around at the other islands -> decide like a 2020 player from persona and what they saw: rush the nearest
 * island player (best while they are still looting), go mid when it looks uncontested, hold the island and wait
 * for bridgers, third-party a fight nearby, back off and heal when hurt, loot an empty island, hunt the last players.
 * Reactions run above the plan: a player bridging over toward us is met at the end of their bridge, a bridge is
 * abandoned when someone waits at its end or looks down a bow at us, fights we would lose are not picked.
 */
public final class SkywarsBrain extends GameBrain {
    private enum St { CAGE, DROP, LOOT, ORGANIZE, GATHER, SCOUT, DECIDE, TRAVEL, CAMP, HUNT, RUSH, DEFEND, THIRD, REGEAR, END }

    /** What the bot set out to do (TRAVEL/LOOT are only how); for stats and for labelling the fights it starts. */
    private enum Plan { OWN, MID, RUSH, ISLAND, CAMP, HUNT, THIRD, DEFEND, REGEAR, GATHER }

    private final SkywarsView view;
    private St st = St.CAGE;
    private Plan plan = Plan.OWN;
    private long stTick;
    private int island = -1;
    private final Set<Location> looted = new HashSet<>();
    /** Chests we could not get to / open, with the tick until which they are skipped. */
    private final Map<Location, Long> unreachable = new HashMap<>();
    private List<Block> lootQueue = new ArrayList<>();
    /** Chests with no walking route: the second try may dig. */
    private final Set<Location> digFor = new HashSet<>();
    private Looter looter;
    private Organizer organizer;
    private BlockBreaker miner;
    private Location travelTo;
    private boolean travelLootMid;
    private int travelIsland = -1;
    private NavOptions travelOpts;
    private double travelRadius;
    private long campUntil;
    private int jumpTimer;
    private long nextDecision;

    // ---- strategic traits (stable per player) ----
    /** Likes going mid for the OP chests (some players always do, most don't open with it). */
    private double midLove;
    /** Waits for others to fight and cleans up. */
    private double thirdPartyLove;

    public SkywarsBrain(SkywarsView view) {
        super(view);
        this.view = view;
    }

    @Override
    protected void onGameStart() {
        looter = new Looter(ctx);
        organizer = new Organizer(ctx);
        miner = new BlockBreaker(ctx);
        island = view.islandOf(ctx.player);
        ctx.combat.allowThrowables = true;
        ctx.combat.allowBow = true;
        ctx.combat.allowGapple = true;
        ctx.combat.allowPearl = ctx.persona.tryhard;
        ctx.combat.leash = 45;
        ctx.combat.chaseOptions = net.h2020.bots.ai.combat.Combat.chase(NavOptions.bridging(10));
        ctx.combat.chaseOptions.parkourGap = 0; // bridge the gap instead of jumping it over the void
        ctx.combat.chaseOptions.maxNodes = 4000;
        Random tr = new Random(ctx.persona.seed * 31L + 0x5157);
        midLove = AIMath.clamp(tr.nextDouble() * 0.85 + (ctx.persona.tryhard ? 0.2 : 0) - 0.1, 0, 1);
        thirdPartyLove = AIMath.clamp(0.2 + tr.nextDouble() * 0.6 * (0.6 + ctx.persona.skill.awareness) - 0.3 * ctx.persona.skill.aggression, 0, 1);
    }

    private void set(St s) { st = s; stTick = ctx.tick; pendingAct = null; }

    // ---- human pacing: a look before committing to a plan, a breather after a chest ----
    private Runnable pendingAct;
    private long pendingUntil;
    private Location pendingLook;
    private long lootPauseUntil;
    private int crouchSpam;

    /**
     * Run a decision now, or first stand for a moment looking where we are about to go (players eye up the mid or
     * the next island before they commit; sweats hesitate less).
     */
    private void commit(Runnable act, Location lookAt) {
        double o = ctx.persona.skill.overall;
        if (lookAt != null && chance(0.55 - 0.3 * o)) {
            pendingAct = act;
            pendingLook = lookAt.clone().add(0, 1.2, 0);
            pendingUntil = ctx.tick + AIMath.range(ctx.rng, 4, 8 + (int) (16 * (1 - o)));
            return;
        }
        act.run();
    }

    @Override
    public String toString() {
        if (ctx == null) return "SkywarsBrain[" + st + "]";
        org.bukkit.Location l = ctx.pos();
        String fight = ctx.combat.engaged() ? " vs " + ctx.combat.target().getName() : "";
        return "SkywarsBrain[" + st + fight + "]{" + ctx.nav.describe() + " plan=" + plan + String.format(" @%.1f,%.1f,%.1f}", l.getX(), l.getY(), l.getZ());
    }

    private long inState() { return ctx.tick - stTick; }

    @Override
    protected void think() {
        pollState();
        if (island < 0) island = view.islandOf(ctx.player);
        GameState gs = game.state();
        if (gs == GameState.ENDING) { set(St.END); endTick(); return; }
        if (!view.cagesOpen() || gs == GameState.WAITING || gs == GameState.STARTING) { cageTick(); return; }
        if (st == St.CAGE) {
            set(St.DROP);
            openTick = ctx.tick;
            buildLootQueue(island);
            statsOpen();
        }
        statsTick();
        trailTick();
        idleCap();
        if (ctx.c.onGround() && !overVoid()) lastFooting = ctx.pos();
        if (st != St.DEFEND && !ctx.combat.engaged()) ctx.combat.allowChase = true;

        // fights interrupt everything except a GUI in progress
        boolean busyGui = looter.active() && ctx.c.containerOpen();
        if (!busyGui && st != St.DROP) react();
        if (!busyGui && st != St.DROP && fightPolicy(st == St.CAMP || st == St.HUNT || st == St.DEFEND ? 14 : 9)) {
            if (looter.active()) {
                // the chest we were at is still to do after the fight
                if (st == St.LOOT && looter.chest() != null) lootQueue.add(0, looter.chest());
                looter.cancel();
            }
            if (st == St.ORGANIZE) organizer.cancel();
            // shooting at a bridger from the edge: once they are on our ground, go after them properly
            if (!ctx.combat.allowChase && ctx.combat.target() != null) {
                Sighting ts = ctx.perception.get(ctx.combat.target());
                if (ctx.tick > noChaseUntil && (ts == null || ctx.tick - ts.overVoidTick > 30)) ctx.combat.allowChase = true;
            }
            ctx.combat.tick();
            return;
        }
        if (wasEngaged) { wasEngaged = false; afterFight(); }

        switch (st) {
            case DROP: dropTick(); break;
            case LOOT: lootTick(); break;
            case ORGANIZE:
                if (!organizer.tick() || inState() > 200) { organizer.cancel(); startScout(); }
                break;
            case GATHER: gatherTick(); break;
            case SCOUT: scoutTick(); break;
            case DECIDE: decide(); break;
            case TRAVEL: travelTick(); break;
            case CAMP: campTick(); break;
            case HUNT: huntTick(); break;
            case RUSH: rushTick(); break;
            case DEFEND: defendTick(); break;
            case THIRD: thirdTick(); break;
            case REGEAR: regearTick(); break;
            default: set(St.DECIDE);
        }
    }

    private boolean chance(double p) { return ctx.rng.nextDouble() < p; }

    // ------------------------------------------------------------------------------------------------ helpers

    private Location lastFooting;
    /** Shooting from where we stand (bridge / edge) without walking after the target until this tick. */
    private long noChaseUntil;

    /** Standing on a bridge / over the void (nothing below the block under our feet). */
    private boolean overVoid() {
        Location p = ctx.pos();
        return ctx.world().voidBelow(floor(p.getX()), floor(p.getY() + 0.01) - 1, floor(p.getZ()), 40);
    }

    private static int floor(double d) { int i = (int) d; return d < i ? i - 1 : i; }

    private boolean alive(Player p) {
        return p != null && p.isOnline() && !p.isDead() && p.getWorld() == ctx.player.getWorld() && game.alivePlayers().contains(p);
    }

    private boolean nearMid(Location l) {
        Location m = view.mid();
        if (m != null && m.getWorld() == l.getWorld() && AIMath.hDist(m, l) < 18) return true;
        for (Location c : view.midChests()) if (c.getWorld() == l.getWorld() && AIMath.hDist(c, l) < 7 && Math.abs(c.getY() - l.getY()) < 8) return true;
        return false;
    }

    private boolean onIsland(int isl, Location l) {
        Location c = isl >= 0 ? view.islandCenter(isl) : null;
        return c != null && c.getWorld() == l.getWorld() && AIMath.hDist(c, l) < 13 && Math.abs(c.getY() - l.getY()) < 18;
    }

    /** Where a player probably is: what we saw / heard, else (early on) their island, else nothing. */
    private Location whereIs(Player p) {
        Sighting s = ctx.perception.get(p);
        if (s != null && s.known && s.lastPos != null && s.confidence(ctx.tick) > 0.12) return s.estimate(ctx.tick);
        int isl = view.islandOf(p);
        // everyone saw everyone in the cages: in the first minute they are on (or just off) their own island
        if (isl >= 0 && inGameSeconds() < 50) return view.islandCenter(isl);
        return null;
    }

    /** Their strength as far as we know it (gear we saw); the average opening kit when we have not seen them. */
    private double powerOf(Player p) {
        Sighting s = ctx.perception.get(p);
        if (s != null && ctx.tick - s.gearTick < 20 * 90) return s.power();
        // not seen: everybody looted an island of the same kind, assume they are about as strong as us
        return ctx.perception.selfPower();
    }

    /** They have clearly better stuff than us: a fight we would probably lose. */
    private boolean outclassed(Player p) {
        Sighting s = ctx.perception.get(p);
        if (s == null || ctx.tick - s.gearTick > 20 * 90) return false;
        double margin = 3 + 8 * ctx.persona.skill.aggression;
        return s.power() > ctx.perception.selfPower() + margin && s.healthSeen > 8;
    }

    private final Map<UUID, Long> avoidUntil = new HashMap<>();

    private boolean avoiding(Player p) {
        Long t = avoidUntil.get(p.getUniqueId());
        return t != null && ctx.tick < t;
    }

    private void avoid(Player p, int ticks) { if (p != null) avoidUntil.put(p.getUniqueId(), ctx.tick + ticks); }

    private NavOptions bridgeOpts() {
        NavOptions o = NavOptions.bridging(Inv.count(ctx.player, Inv.BLOCK));
        o.pillar = true;
        o.breakBlocks = true; // SkyWars: everything is breakable; mine out of enclosed islands like players do
        o.maxNodes = 9000;
        // jumping a 2-3 block gap over the void is how the bridging bots died most (players just bridge it)
        o.parkourGap = 0;
        return o;
    }

    // ---- long routes in hops: players bridge in straight lines; one search across 80 blocks of void takes long ----
    private Location seg, segFor;
    private boolean segDirect;

    private Location waypoint(Location dest) {
        Location me = ctx.pos();
        double d = AIMath.hDist(me, dest);
        if (segDirect || d <= 34 || dest.getWorld() != me.getWorld()) { seg = null; return dest; }
        if (seg == null || segFor == null || segFor.distanceSquared(dest) > 100 || AIMath.hDist(me, seg) < 5) {
            double ux = (dest.getX() - me.getX()) / d, uz = (dest.getZ() - me.getZ()) / d;
            // a bridge runs at the height we leave from; drift toward the goal's height on the way
            double y = Math.floor(me.getY() + 0.01);
            double dy = dest.getY() - y;
            if (Math.abs(dy) > 3) y += Math.signum(dy) * Math.min(Math.abs(dy) * 0.4, 4);
            seg = new Location(me.getWorld(), me.getX() + ux * 24, y, me.getZ() + uz * 24);
            segFor = dest.clone();
        }
        return seg;
    }

    // ------------------------------------------------------------------------------------------------ cage

    private void cageTick() {
        st = St.CAGE;
        // jump around in the cage, look around (gaze handles the looking)
        if (--jumpTimer <= 0) {
            if (chance(0.35)) ctx.motor.jump(true);
            jumpTimer = AIMath.range(ctx.rng, 4, chance(0.2) ? 6 : 50);
        }
        if (crouchSpam > 0) {
            // crouch spam at the people in the other cages
            crouchSpam--;
            ctx.motor.sneak(crouchSpam % 4 < 2);
        } else if (chance(0.002 + 0.004 * ctx.persona.skill.chattiness)) {
            crouchSpam = AIMath.range(ctx.rng, 6, 22);
        } else if (chance(0.004)) ctx.motor.sneak(true);
        if (chance(0.01)) ctx.c.selectHotbar(ctx.rng.nextInt(9));
        else if (chance(0.006)) {
            // scroll through the hotbar a few slots
            int dir = chance(0.5) ? 1 : 8;
            ctx.c.selectHotbar((ctx.c.heldSlot() + dir) % 9);
        }
    }

    /** Falling out of the cage: eyes already on the first chest (after a reaction's delay), loot as soon as we land. */
    private int dropLookDelay = -1;

    private void dropTick() {
        if (dropLookDelay < 0) dropLookDelay = Math.max(1, AIMath.msToTicks(ctx.rng, ctx.persona.skill.reactionMs * 1.4));
        if (inState() >= dropLookDelay && !lootQueue.isEmpty()) {
            Block b = lootQueue.get(0);
            ctx.motor.aimPoint(Motor.P_TASK, b.getLocation().add(0.5, 0.6, 0.5), AimIntent.GLANCE, 2.0);
        }
        if (ctx.c.onGround() && inState() > 4) {
            buildLootQueue(island); // re-sorted from where we actually landed
            set(St.LOOT);
            plan = Plan.OWN;
        } else if (inState() > 60) { buildLootQueue(island); set(St.LOOT); }
    }

    // ------------------------------------------------------------------------------------------------ loot

    private void buildLootQueue(int isl) {
        lootQueue = new ArrayList<>();
        List<Location> chests = isl >= 0 ? view.islandChests(isl) : new ArrayList<Location>();
        for (Location l : chests) {
            Block b = l.getBlock();
            if ((b.getType() == Material.CHEST || b.getType() == Material.TRAPPED_CHEST) && !skipUnreachable(b)
                    && !(looted.contains(b.getLocation()) && !refilledSince(b))) lootQueue.add(b);
        }
        sortForLooting(lootQueue);
    }

    /**
     * Island chests in the order a player grabs them: the ones on the level we landed on first (nearest first), the
     * one down the ladder / in the cave last, instead of hopping down and back up between them.
     */
    private void sortForLooting(List<Block> list) {
        final Location me = ctx.pos();
        java.util.Collections.sort(list, new java.util.Comparator<Block>() {
            public int compare(Block a, Block b) { return Double.compare(cost(a), cost(b)); }
            private double cost(Block c) {
                double dy = Math.abs(c.getY() + 1 - me.getY());
                return Math.sqrt(AIMath.hDistSq(c.getX() + 0.5, c.getZ() + 0.5, me.getX(), me.getZ())) + (dy > 1.5 ? 6 + dy * 2 : dy);
            }
        });
    }

    /**
     * Where "going mid" leads: the nearest mid chest worth opening. Maps whose "mid" is a ring or several hubs
     * (Onionring, Fossil) spread their mid chests far apart; heading for the one map point would send all twelve
     * players to the same spot on one side.
     */
    private Location midGoal(Location me) {
        Location best = null;
        double bd = Double.MAX_VALUE;
        for (Location l : view.midChests()) {
            if (l.getWorld() != me.getWorld()) continue;
            Block b = l.getBlock();
            if (b.getType() != Material.CHEST && b.getType() != Material.TRAPPED_CHEST) continue;
            if (skipUnreachable(b) || (looted.contains(b.getLocation()) && !refilledSince(b))) continue;
            double d = l.distanceSquared(me);
            if (d < bd) { bd = d; best = l; }
        }
        Location m = view.mid();
        if (best == null) return m;
        // a chest right next to the centre: just go to the centre (the classic "mid")
        if (m != null && m.getWorld() == best.getWorld() && AIMath.hDist(m, best) < 20) return m;
        return best.clone().add(0.5, 1, 0.5);
    }

    private void buildMidQueue() {
        lootQueue = new ArrayList<>();
        Location me = ctx.pos();
        for (Location l : view.midChests()) {
            // only the chests of the mid area we are in (ring / hub maps spread them over the whole map)
            if (l.getWorld() != me.getWorld() || AIMath.hDist(l, me) > 28) continue;
            Block b = l.getBlock();
            if (b.getType() != Material.CHEST && b.getType() != Material.TRAPPED_CHEST) continue;
            if (skipUnreachable(b)) continue;
            if (looted.contains(b.getLocation()) && !refilledSince(b)) continue;
            if (b.getState() instanceof Chest && view.chestLooted((Chest) b.getState()) && chance(0.6)) continue;
            lootQueue.add(b);
        }
        sortByDistance(lootQueue);
    }

    /**
     * A chest this bot looted counts as refilled once nobody has opened it since the last refill (SkywarsView resets
     * chestLooted on refill; the bot's own opening set it). Forget it from the looted memory so it is visited again.
     */
    private boolean refilledSince(Block b) {
        if (!(b.getState() instanceof Chest)) return false;
        if (view.chestLooted((Chest) b.getState())) return false;
        looted.remove(b.getLocation());
        return true;
    }

    private boolean skipUnreachable(Block b) {
        Long until = unreachable.get(b.getLocation());
        return until != null && ctx.tick < until;
    }

    private void sortByDistance(List<Block> list) {
        final Location me = ctx.pos();
        java.util.Collections.sort(list, new java.util.Comparator<Block>() {
            public int compare(Block a, Block b) {
                return Double.compare(a.getLocation().distanceSquared(me), b.getLocation().distanceSquared(me));
            }
        });
    }

    private void lootTick() {
        if (ctx.tick < lootPauseUntil) return; // standing for a second after a chest: the gaze looks around
        if (!looter.active()) {
            while (!lootQueue.isEmpty() && looted.contains(lootQueue.get(0).getLocation())) lootQueue.remove(0);
            if (lootQueue.isEmpty()) {
                if (ownLootDone < 0 && plan == Plan.OWN) ownLootDone = ctx.tick;
                organizer.reset();
                set(ctx.persona.skill.overall < 0.2 && chance(0.5) ? St.DECIDE : St.ORGANIZE);
                return;
            }
            Block next = lootQueue.remove(0);
            NavOptions lo = NavOptions.walk();
            // walk (stairs, ladders) to island chests like a player; dig only when there is no walking route at all
            lo.breakBlocks = digFor.contains(next.getLocation());
            // the chest room under the floor / the cave below the spawn (Crumble's hollow towers): players just
            // jump down the hole and eat the fall damage at full health
            if (ctx.health() >= 15) { lo.maxFall = 8; lo.maxFallDamage = 5; }
            lo.parkourGap = Math.min(1, lo.parkourGap < 0 ? 1 : lo.parkourGap);
            looter.start(next, lo);
        }
        Looter.Result r = looter.tick();
        if (r != Looter.Result.RUNNING && looter.chest() != null) {
            Location cl = looter.chest().getLocation();
            if (r == Looter.Result.FAILED) earlyLootFails++;
            if (r == Looter.Result.FAILED && looter.noRoute() && digFor.add(cl)) {
                lootQueue.add(looter.chest()); // no walking route: come back to it last and dig in
                return;
            }
            looted.add(cl);
            // never opened it, so it still counts as "not looted since the refill": without this the mid queue
            // offers the same unreachable chest again and again
            if (r == Looter.Result.FAILED) unreachable.put(looter.chest().getLocation(), ctx.tick + 20 * 90);
            else if (chance(0.2 - 0.15 * ctx.persona.skill.overall)) lootPauseUntil = ctx.tick + AIMath.range(ctx.rng, 3, 10);
        }
        if (inState() > 20 * 60) { looter.cancel(); set(St.ORGANIZE); }
    }

    // ------------------------------------------------------------------------------------------------ gathering

    private Block gatherTarget;
    private Location gatherDrop;
    private long gatherDropTick, gatherStart;
    private int gatherBlocked;

    /** Break island blocks that drop something placeable (what players do when they run out), then walk over the drop. */
    private void gatherTick() {
        if (Inv.count(ctx.player, Inv.BLOCK) >= 20 || inState() > 20 * 20) { ctx.nav.stop(); gatherTarget = null; gatherDrop = null; set(St.DECIDE); return; }
        if (gatherDrop != null) {
            ctx.nav.goTo(gatherDrop, 0.7, NavOptions.walk());
            ctx.nav.tick();
            if (ctx.nav.arrived() || ctx.nav.failed() || ctx.tick - gatherDropTick > 60) { ctx.nav.stop(); gatherDrop = null; }
            return;
        }
        if (gatherTarget == null) {
            gatherTarget = findGatherBlock(6);
            if (gatherTarget == null) { campUntil = ctx.tick + 20 * 6; set(St.CAMP); return; }
            miner.start(gatherTarget.getX(), gatherTarget.getY(), gatherTarget.getZ(), false);
            gatherBlocked = 0;
            gatherStart = Long.MAX_VALUE;
        }
        if (ctx.eye().distance(gatherTarget.getLocation().add(0.5, 0.5, 0.5)) <= 4.0 && gatherStart == Long.MAX_VALUE) gatherStart = ctx.tick;
        if (ctx.eye().distance(gatherTarget.getLocation().add(0.5, 0.5, 0.5)) > 4.0) {
            ctx.nav.goTo(gatherTarget.getLocation().add(0.5, 0, 0.5), 2.5, NavOptions.walk());
            ctx.nav.tick();
            if (ctx.nav.failed()) { unreachable.put(gatherTarget.getLocation(), ctx.tick + 20 * 60); gatherTarget = null; }
            return;
        }
        ctx.nav.stop();
        BlockBreaker.Result r = miner.tick();
        // something else is in the way of the crosshair (or it simply takes too long by hand): pick another block
        Block tb = ctx.c.targetedBlock();
        if (r == BlockBreaker.Result.RUNNING && (tb == null || !tb.equals(gatherTarget))) gatherBlocked++;
        if (r == BlockBreaker.Result.RUNNING && (gatherBlocked > 15 || ctx.tick - gatherStart > 20 * 7)) {
            miner.cancel();
            r = BlockBreaker.Result.FAILED;
        }
        if (r == BlockBreaker.Result.RUNNING) return;
        if (r == BlockBreaker.Result.DONE) { gatherDrop = gatherTarget.getLocation().add(0.5, 0, 0.5); gatherDropTick = ctx.tick; }
        else unreachable.put(gatherTarget.getLocation(), ctx.tick + 20 * 60);
        gatherTarget = null;
    }

    /** Nearest exposed block at body height that drops a building block with the tools we have (never the floor we stand on). */
    private Block findGatherBlock(int r) {
        Location me = ctx.pos();
        boolean pick = Inv.has(ctx.player, Inv.PICKAXE);
        Block best = null;
        double bd = Double.MAX_VALUE;
        for (int dx = -r; dx <= r; dx++) for (int dy = 0; dy <= 1; dy++) for (int dz = -r; dz <= r; dz++) {
            Block b = me.getWorld().getBlockAt(me.getBlockX() + dx, me.getBlockY() + dy, me.getBlockZ() + dz);
            if (!gatherable(b.getType(), pick) || skipUnreachable(b)) continue;
            boolean exposed = false;
            for (org.bukkit.block.BlockFace f : new org.bukkit.block.BlockFace[]{org.bukkit.block.BlockFace.NORTH,
                    org.bukkit.block.BlockFace.SOUTH, org.bukkit.block.BlockFace.EAST, org.bukkit.block.BlockFace.WEST}) {
                Material n = b.getRelative(f).getType();
                if (n == Material.AIR) { exposed = true; break; }
            }
            if (!exposed) continue;
            // keep what holds the island (and us) up: only blocks with something solid under them
            if (b.getRelative(org.bukkit.block.BlockFace.DOWN).getType() == Material.AIR && dy == 0) continue;
            double d = b.getLocation().add(0.5, 0.5, 0.5).distanceSquared(me);
            if (d < bd) { bd = d; best = b; }
        }
        return best;
    }

    private static boolean gatherable(Material m, boolean pick) {
        switch (m) {
            case DIRT: case GRASS: case WOOL: case WOOD: case LOG: case LOG_2:
                return true;
            case STONE: case COBBLESTONE: case SANDSTONE: case ENDER_STONE: case STAINED_CLAY: case HARD_CLAY:
            case MOSSY_COBBLESTONE: case SMOOTH_BRICK: case BRICK: case NETHERRACK: case QUARTZ_BLOCK:
                return pick;
            default:
                return false;
        }
    }

    // ------------------------------------------------------------------------------------------------ looking around

    private final List<Location> scoutLooks = new ArrayList<>();
    private long scoutNext;

    /**
     * After looting, a player stands for a moment and looks over at the other islands and the mid: who is still
     * looting, who is already bridging, who is coming. (It is also how the bot's perception, which only sees what
     * is in front of its eyes, learns where everyone is before it decides.)
     */
    private void startScout() {
        scoutLooks.clear();
        Location me = ctx.pos();
        List<Location> isl = new ArrayList<>();
        for (int i = 0; i < view.islandCount(); i++) {
            if (i == island) continue;
            Location c = view.islandCenter(i);
            if (c != null && c.getWorld() == me.getWorld()) isl.add(c);
        }
        final Location m = me;
        java.util.Collections.sort(isl, new java.util.Comparator<Location>() {
            public int compare(Location a, Location b) { return Double.compare(a.distanceSquared(m), b.distanceSquared(m)); }
        });
        int n = 1 + (int) Math.round(ctx.persona.skill.awareness * 2.2) + (chance(0.3) ? 1 : 0);
        for (int i = 0; i < Math.min(n, isl.size()); i++) scoutLooks.add(isl.get(i).clone().add(0, 2, 0));
        if (view.mid() != null && chance(0.7)) scoutLooks.add(ctx.rng.nextInt(Math.max(1, scoutLooks.size())), view.mid().clone().add(0, 1.5, 0));
        if (chance(0.25 * ctx.persona.skill.overall) && scoutLooks.size() > 1) scoutLooks.remove(scoutLooks.size() - 1); // sweats look less
        scoutNext = -1;
        set(St.SCOUT);
    }

    private void scoutTick() {
        if (scoutLooks.isEmpty()) { set(St.DECIDE); return; }
        Location l = scoutLooks.get(0);
        ctx.motor.aimPoint(Motor.P_TASK, l, AimIntent.GLANCE, 3.0);
        edgeCrouch();
        if (scoutNext < 0) scoutNext = ctx.tick + AIMath.range(ctx.rng, 9, 22 + (int) (10 * (1 - ctx.persona.skill.overall)));
        if (ctx.tick >= scoutNext) { scoutLooks.remove(0); scoutNext = -1; }
        // something worth reacting to came up: decide now
        if (ctx.perception.recentFight(40) != null && inState() > 10) set(St.DECIDE);
    }

    /** Standing still next to the void: crouch (most players do, the careful ones always). */
    private void edgeCrouch() {
        Location p = ctx.pos();
        if (!ctx.c.onGround()) return;
        int sides = ctx.world().voidSides(floor(p.getX()), floor(p.getY() + 0.01), floor(p.getZ()));
        // (no creep-sneaking along edges)
    }

    // ---- nobody idles at spawn: 25 s after the own island is looted, still there and not fighting -> mid ----
    private long ownLootDone = -1, idleCapUntil;

    private void idleCap() {
        if (ownLootDone < 0 || ctx.combat.engaged() || ctx.tick < idleCapUntil || ctx.tick - ownLootDone < 20 * 25) return;
        if (st == St.TRAVEL || st == St.RUSH || st == St.HUNT || st == St.DEFEND || st == St.THIRD || st == St.DROP) return;
        if (ctx.c.containerOpen() || ctx.nav.doingAction() || !onIsland(island, ctx.pos())) return;
        idleCapUntil = ctx.tick + 20 * 20;
        if (looter.active()) looter.cancel();
        if (st == St.ORGANIZE) organizer.cancel();
        Location mid = midGoal(ctx.pos());
        SwStats.reaction(ctx.player.getWorld(), ctx.player.getName(), "idle-cap");
        if (mid != null && Inv.count(ctx.player, Inv.BLOCK) >= 8) { choose(Plan.MID, "idle cap"); travel(mid, true, -1); }
        else { choose(Plan.HUNT, "idle cap"); set(St.HUNT); }
    }

    // ------------------------------------------------------------------------------------------------ reactions

    private long nextReact;
    private final Map<UUID, Long> dangerSeen = new HashMap<>();

    /** Awareness above the plan: someone bridging over to us, danger at the end of our own bridge, being hurt. */
    private void react() {
        if (ctx.tick < nextReact) return;
        nextReact = ctx.tick + 4;
        if (ctx.combat.engaged()) return;
        boolean onBridge = overVoid();
        // 1) our bridge: somebody waits at its end, or looks down a bow at us
        if (onBridge && (st == St.TRAVEL || st == St.RUSH || st == St.THIRD || st == St.HUNT) && ctx.nav.active()) {
            Sighting d = bridgeDanger();
            if (d != null) {
                Long seen = dangerSeen.get(d.player.getUniqueId());
                if (seen == null || ctx.tick - seen > 20 * 8) {
                    dangerSeen.put(d.player.getUniqueId(), ctx.tick);
                    // the bold keep going (and fight), most turn back; with a bow or snowballs some shoot first
                    double back = 0.85 - 0.55 * ctx.persona.skill.aggression;
                    boolean ranged = Inv.has(ctx.player, Inv.THROWABLE) || (Inv.has(ctx.player, Inv.BOW) && Inv.has(ctx.player, Inv.ARROW));
                    if (ranged && chance(0.5)) {
                        SwStats.reaction(ctx.player.getWorld(), ctx.player.getName(), "bridge-shoot");
                        ctx.nav.stop();
                        ctx.combat.allowChase = false;
                        noChaseUntil = ctx.tick + 20 * 5;
                        ctx.combat.engage(d.player);
                        return;
                    }
                    if (chance(back)) {
                        SwStats.reaction(ctx.player.getWorld(), ctx.player.getName(), ctx.perception.aimingBowAtMe(d) ? "bridge-abort-bow" : "bridge-abort-waiting");
                        avoid(d.player, 20 * 30);
                        retreatHome();
                        return;
                    }
                }
            }
        }
        // 2) on an island: a player bridging toward us -> meet them at the end of their bridge
        if (!onBridge && st != St.DEFEND && st != St.RUSH && st != St.TRAVEL && st != St.HUNT && st != St.THIRD && st != St.DROP) {
            Sighting b = incomingBridger();
            if (b != null) startDefend(b);
        }
    }

    /** The bridge we are on is a bad idea: an enemy waits on land near its end, or is aiming a bow at us. */
    private Sighting bridgeDanger() {
        Location me = ctx.pos();
        Location goal = travelGoalForDanger();
        for (Sighting s : ctx.perception.visibleEnemies()) {
            if (s.lastPos == null) continue;
            Location e = s.estimate(ctx.tick);
            double d = e.distance(me);
            if (ctx.perception.aimingBowAtMe(s) && (ctx.health() < 17 || chance(0.3))) return s;
            if (goal == null || d < 7 || d > 32) continue;
            boolean onLand = ctx.tick - s.overVoidTick > 30;
            // waiting at the end of our bridge: on land, near where it lands, barely moving, and not weaker than us
            if (onLand && AIMath.hDist(e, goal) < 8 && s.lastVel.length() < 0.12
                    && powerOf(s.player) > ctx.perception.selfPower() - 6 + 10 * ctx.persona.skill.aggression) return s;
        }
        return null;
    }

    private Location travelGoalForDanger() {
        if (st == St.TRAVEL) return travelTo;
        if (st == St.RUSH && rushTarget != null) return whereIs(rushTarget);
        return ctx.nav.goal();
    }

    /** Walk back to the last ground we stood on (our island usually) and think again. */
    private void retreatHome() {
        ctx.nav.stop();
        Location back = lastFooting != null ? lastFooting : view.islandCenter(island);
        if (back == null) { set(St.DECIDE); return; }
        NavOptions o = NavOptions.walk();
        o.avoidEnemies = true;
        travelTo = back.clone();
        travelLootMid = false;
        travelIsland = -1;
        travelOpts = o;
        travelRadius = 1.5;
        segDirect = true;
        set(St.TRAVEL);
        nextDecision = ctx.tick + 20 * 6;
    }

    /** A visible enemy over the void, closing in on us: they are bridging over. */
    private Sighting incomingBridger() {
        Location me = ctx.pos();
        Sighting best = null;
        double bd = 36;
        for (Sighting s : ctx.perception.visibleEnemies()) {
            if (s.lastPos == null || ctx.tick - s.overVoidTick > 12) continue;
            Location e = s.estimate(ctx.tick);
            double d = e.distance(me);
            if (d < 5 || d > bd) continue;
            Vector toMe = me.toVector().subtract(e.toVector());
            toMe.setY(0);
            double len = toMe.length();
            if (len < 0.1) continue;
            double closing = s.lastVel.clone().setY(0).dot(toMe.multiply(1.0 / len));
            if (closing < 0.025) continue;
            bd = d;
            best = s;
        }
        return best;
    }

    private Player defendTarget;
    private Location defendSpot;
    private long defendUntil;
    private boolean defendShot;
    private St defendFrom;

    private void startDefend(Sighting b) {
        if (looter.active()) {
            if (st == St.LOOT && looter.chest() != null) lootQueue.add(0, looter.chest());
            looter.cancel();
        }
        if (st == St.ORGANIZE) organizer.cancel();
        defendFrom = st == St.LOOT || st == St.ORGANIZE ? st : St.DECIDE;
        defendTarget = b.player;
        defendSpot = landingSpot(b.estimate(ctx.tick));
        defendUntil = ctx.tick + 20 * 12;
        defendShot = false;
        ctx.nav.stop();
        SwStats.reaction(ctx.player.getWorld(), ctx.player.getName(), "defend-bridger");
        Plan was = plan;
        plan = Plan.DEFEND;
        defendPlanBefore = was;
        set(St.DEFEND);
        if (game.type().teamSize() > 1) ctx.chat.callout(chance(0.5) ? "someone bridging" : "incoming", 0.3);
    }

    private Plan defendPlanBefore = Plan.OWN;

    private void endDefend() {
        ctx.combat.allowChase = true;
        plan = defendPlanBefore == Plan.DEFEND ? Plan.CAMP : defendPlanBefore;
        defendTarget = null;
        if (defendFrom == St.LOOT) set(St.LOOT);
        else if (defendFrom == St.ORGANIZE) { organizer.reset(); set(St.ORGANIZE); }
        else set(St.DECIDE);
    }

    /** The edge of our ground nearest to where the bridger comes from (one block in from the void). */
    private Location landingSpot(Location from) {
        Location me = ctx.pos();
        int bx = floor(me.getX()), by = floor(me.getY() + 0.01), bz = floor(me.getZ());
        net.h2020.bots.ai.world.WorldView wv = ctx.world();
        double best = Double.MAX_VALUE;
        int[] cell = null;
        for (int dx = -9; dx <= 9; dx++) for (int dz = -9; dz <= 9; dz++) for (int dy = -2; dy <= 2; dy++) {
            int x = bx + dx, y = by + dy, z = bz + dz;
            if (!wv.standable(x, y, z) || wv.voidBelow(x, y, z, 40)) continue;
            double score = AIMath.hDistSq(x + 0.5, z + 0.5, from.getX(), from.getZ()) + Math.abs(dy) * 4;
            // one block in from the edge: the bridger has to step onto our ground to reach us
            if (wv.voidSides(x, y, z) > 0) score += 6;
            if (score < best) { best = score; cell = new int[]{x, y, z}; }
        }
        if (cell == null) return null;
        double s = wv.stand(cell[0], cell[1], cell[2]);
        return new Location(me.getWorld(), cell[0] + 0.5, Double.isNaN(s) ? cell[1] : s, cell[2] + 0.5);
    }

    private void defendTick() {
        Player t = defendTarget;
        Sighting s = ctx.perception.get(t);
        if (!alive(t) || s == null || s.unseenSeconds(ctx.tick) > 5) { endDefend(); return; }
        if (ctx.tick > defendUntil) {
            // waited long enough at the edge: go and fight them
            ctx.combat.allowChase = true;
            if (s.visible && s.estimate(ctx.tick).distance(ctx.pos()) < 20) ctx.combat.engage(t);
            endDefend();
            return;
        }
        Location est = s.estimate(ctx.tick);
        Location me = ctx.pos();
        double d = est.distance(me);
        boolean bridging = ctx.tick - s.overVoidTick < 30;
        // they landed (or gave up and turned around: back to what we did)
        if (!bridging && d < 9) { ctx.combat.allowChase = true; goFor(t); return; }
        if (!bridging && d > 14) { endDefend(); return; }
        if (d < 4.3) { goFor(t); return; }
        if (ctx.tick % 20 == 0) {
            Location ls = landingSpot(est);
            if (ls != null) defendSpot = ls;
        }
        if (defendSpot != null && AIMath.hDist(me, defendSpot) > 1.2) {
            NavOptions o = NavOptions.walk();
            o.avoidEnemies = false;
            ctx.nav.goTo(defendSpot, 0.9, o);
            ctx.nav.tick();
            if (ctx.nav.failed()) { ctx.nav.stop(); defendSpot = null; }
        } else {
            ctx.nav.stop();
            edgeCrouch();
            // (no crouch-waiting at the edge)
        }
        ctx.motor.aimEntity(Motor.P_TASK, t, AimIntent.GLANCE);
        // snowballs / bow at the bridger (combat shoots, but must not walk out onto their bridge)
        boolean ranged = Inv.has(ctx.player, Inv.THROWABLE) || (Inv.has(ctx.player, Inv.BOW) && Inv.has(ctx.player, Inv.ARROW));
        if (!defendShot && ranged && d < 24 && d > 6) {
            defendShot = true;
            ctx.combat.allowChase = false;
            goFor(t);
        }
    }

    // ------------------------------------------------------------------------------------------------ fights

    private boolean wasEngaged;
    private String fightOrigin = "?";
    private boolean firstFightDone;

    /** BaseBrain's fight picking, plus SkyWars judgement: let others fight, wait for bridgers, skip fights we lose. */
    private boolean fightPolicy(double range) {
        if (!ctx.combat.engaged()) {
            Sighting best = ctx.perception.bestThreat();
            if (best != null && best.lastPos != null) {
                boolean hurtMe = ctx.tick - best.lastHurtMeTick < 60;
                double d = best.estimate(ctx.tick).distance(ctx.pos());
                if (!hurtMe && d > 4.3) {
                    // meet the bridger at the end of the bridge instead of walking out onto it
                    if (st == St.DEFEND && best.player == defendTarget) return false;
                    // let the two of them fight it out first
                    if (st == St.THIRD && !thirdGo && (best.player == thirdA || best.player == thirdB)) return false;
                    // somebody we cannot beat: do not pick that fight (they still get fought if they come to us)
                    if (outclassed(best.player) && st != St.HUNT && plan != Plan.MID && !nearMid(ctx.pos()) && d > 8) return false;
                    // nearly dead: no new fights
                    if (ctx.health() < 7) return false;
                    if (avoiding(best.player) && d > 8) return false;
                }
            }
        }
        boolean r = combatPolicy(range);
        if (r && !wasEngaged) { wasEngaged = true; fightStartTick = ctx.tick; fightCounted = false; }
        // a fight is one that lasts a second (an engage dropped at once - target out of sight - is not)
        if (r && !fightCounted && ctx.tick - fightStartTick >= 20) { fightCounted = true; noteFightStart(); }
        return r;
    }

    private long fightStartTick;
    private boolean fightCounted = true;

    /**
     * Start the fight with someone we went for. Combat drops a target it cannot see, so when they are not in
     * sight yet we turn to look at them first (perception picks them up on its next look) and walk on.
     */
    private void goFor(Player t) {
        Sighting s = ctx.perception.get(t);
        if (s != null && s.visible) { ctx.combat.engage(t); return; }
        ctx.motor.aimEntity(Motor.P_TASK, t, AimIntent.GLANCE);
        Location tl = t.getLocation();
        NavOptions o = bridgeOpts();
        o.avoidEnemies = false;
        ctx.nav.follow(tl, 2, o);
        ctx.nav.tick();
    }

    private void noteFightStart() {
        Location me = ctx.pos();
        String where = nearMid(me) ? "mid" : onIsland(island, me) ? "home" : "away";
        String p;
        switch (plan) {
            case RUSH: p = "rush"; break;
            case MID: p = "mid"; break;
            case THIRD: p = "third-party"; break;
            case DEFEND: p = "defend"; break;
            case CAMP: p = "camp"; break;
            case HUNT: p = "hunt"; break;
            case ISLAND: p = "island"; break;
            case REGEAR: p = "regear"; break;
            default: p = "loot";
        }
        fightOrigin = p.equals(where) ? p : p + "@" + where;
        SwStats.fight(ctx.player.getWorld(), fightOrigin, inGameSeconds(), !firstFightDone);
        firstFightDone = true;
    }

    /** A fight just ended (won, given up, target gone): hurt -> heal up; otherwise back to the plan or think again. */
    private void afterFight() {
        ctx.combat.allowChase = true;
        if (st == St.DEFEND) { endDefend(); return; }
        if (st == St.LOOT || st == St.ORGANIZE || st == St.GATHER || st == St.TRAVEL) return;
        if (ctx.health() < 8 && st != St.REGEAR) { startRegear(); return; }
        if (st == St.RUSH && alive(rushTarget) && !ctx.combat.ignoring(rushTarget)) return; // still going for them
        if (st == St.RUSH || st == St.THIRD || st == St.CAMP) set(St.DECIDE);
    }

    @Override
    public void onKill(Player victim, boolean finalKill) {
        super.onKill(victim, finalKill);
        if (!fightCounted) { fightCounted = true; noteFightStart(); }
        SwStats.kill(ctx.player.getWorld(), fightOrigin);
    }

    // ------------------------------------------------------------------------------------------------ decisions

    private boolean firstPlanDone;
    private int campsDone;

    private void choose(Plan p, String why) {
        plan = p;
        SwStats.plan(ctx.player.getWorld(), ctx.player.getName(), p.name(), !firstPlanDone, why);
        firstPlanDone = true;
    }

    private void decide() {
        if (pendingAct != null) {
            if (ctx.tick < pendingUntil) {
                if (pendingLook != null) ctx.motor.aimPoint(Motor.P_TASK, pendingLook, AimIntent.GLANCE, 1.5);
                edgeCrouch();
                return;
            }
            Runnable r = pendingAct;
            pendingAct = null;
            r.run();
            return;
        }
        int blocks = Inv.count(ctx.player, Inv.BLOCK);
        double armor = Inv.totalArmor(ctx.player);
        double aggr = ctx.persona.skill.aggression;
        double aware = ctx.persona.skill.awareness;
        int alive = game.alivePlayers().size();
        long sinceOpen = inGameSeconds();
        double hp = ctx.health();
        Location me = ctx.pos();
        Location mid = midGoal(me);
        boolean atMid = mid != null && mid.getWorld() == me.getWorld() && AIMath.hDist(me, mid) < 16;
        double myPower = ctx.perception.selfPower();

        // hurt and nobody on us: heal up before anything else
        if (hp < 8 && ctx.perception.enemiesWithin(10) == 0) { startRegear(); return; }

        // out of blocks on an island: nothing above works (every way off is a bridge or a pillar). Dig some up.
        if (blocks < 8 && !atMid && findGatherBlock(6) != null && chance(0.85)) {
            choose(Plan.GATHER, "blocks=" + blocks);
            set(St.GATHER); nextDecision = ctx.tick + 20 * 20; return;
        }
        if (atMid) {
            // already here: worth staying only for chests nobody emptied yet
            buildMidQueue();
            if (!lootQueue.isEmpty()) { set(St.LOOT); nextDecision = ctx.tick + 20 * 20; return; }
            // nothing left to open: fight whoever is at mid / coming to it
            choose(Plan.MID, "at mid, fight");
            plan = Plan.MID;
            set(St.HUNT);
            nextDecision = ctx.tick + 20 * 20;
            return;
        }

        // ---- rush: the best player to go for (closest, weakest, still looting, low) ----
        Player rushT = null;
        double rushScore = -9;
        for (Player p : game.alivePlayers()) {
            if (p == ctx.player || isAlly(p) || p.getWorld() != me.getWorld() || ctx.combat.ignoring(p)) continue;
            Location at = whereIs(p);
            if (at == null) continue;
            double d = AIMath.hDist(me, at);
            if (d > 75) continue;
            double needBlocks = Math.max(0, d - 12) * 0.9 + 3;
            if (blocks < needBlocks) continue;
            Sighting s = ctx.perception.get(p);
            double sc = 0.05 + 1.0 * aggr - d / 40.0;
            sc += AIMath.clamp((myPower - powerOf(p)) / 14.0, -0.7, 0.5);
            if (s != null && ctx.tick - s.gearTick < 20 * 30 && s.healthSeen < 10) sc += 0.5;          // chase the low one
            int theirIsl = view.islandOf(p);
            if (sinceOpen < 40 && onIsland(theirIsl, at)) sc += 0.3;                                    // still looting
            if (s != null && ctx.perception.aimingBowAtMe(s)) sc -= 0.6;
            if (s != null && ctx.tick - s.overVoidTick < 40 && nearMid(at)) sc -= 0.3;                   // off to mid already
            if (avoiding(p)) sc -= 1.2;
            if (outclassed(p)) sc -= 0.8;
            if (sc > rushScore) { rushScore = sc; rushT = p; }
        }

        // ---- mid: the OP chests, when it looks safe / uncontested ----
        double midScore = -9;
        if (mid != null && !atMid) {
            double dm = AIMath.hDist(me, mid);
            int contest = 0;
            for (Sighting s : ctx.perception.all()) {
                if (!s.known || s.ally || s.lastPos == null || s.confidence(ctx.tick) < 0.2) continue;
                if (nearMid(s.estimate(ctx.tick))) contest++;
            }
            midScore = 0.76 + midLove * 0.6 + aggr * 0.15 + (armor < 7 ? 0.2 : 0) + 0.05 * Math.min(contest, 3) - dm / 300.0
                    + (sinceOpen > 70 && view.refillSeconds() >= 0 && view.refillSeconds() < 30 ? 0.3 : 0);
            if (blocks < Math.max(0, dm - 14) * 0.9 + 4) midScore -= 2;
            if (looted.isEmpty()) midScore -= 0.4;
        }

        // ---- third party: two others fighting nearby ----
        Perception.Fight f = ctx.perception.recentFight(60);
        double thirdScore = -9;
        if (f != null && !isAlly(f.attacker) && !isAlly(f.victim)) {
            double df = AIMath.hDist(me, f.at);
            if (df < 55 && blocks >= Math.max(0, df - 14) * 0.9 + 3) thirdScore = 0.35 + 0.6 * thirdPartyLove + 0.25 * aggr + 0.2 * aware - df / 90.0;
        }

        // ---- hold the island: wait for bridgers, watch the others ----
        boolean home = onIsland(island, me);
        // (no waiting at spawn after looting: 2020 lobbies go mid or rush; holding is only the bridge-end
        // ambush reaction)
        double campScore = -9;

        // ---- an island nobody is on any more: free loot ----
        int freeIsl = freeIsland(me, blocks);
        double islScore = freeIsl < 0 || sinceOpen < 60 ? -9 : 0.1 + (armor < 10 ? 0.3 : 0)
                - AIMath.hDist(me, view.islandCenter(freeIsl)) / 60.0;

        // ---- late game: go get them ----
        Sighting seen = ctx.perception.nearestKnownEnemy(45);
        double huntScore = (alive <= 4 ? 1.1 : 0) + (sinceOpen > 100 ? 0.6 : 0) + aggr * 0.3
                + (seen != null && seen.visible && !ctx.combat.ignoring(seen.player) ? 0.25 : 0);

        double noise = 0.22;
        rushScore += ctx.rng.nextGaussian() * noise;
        midScore += ctx.rng.nextGaussian() * noise;
        thirdScore += ctx.rng.nextGaussian() * noise;
        campScore += ctx.rng.nextGaussian() * noise;
        islScore += ctx.rng.nextGaussian() * noise;
        huntScore += ctx.rng.nextGaussian() * noise * 0.5;

        double best = Math.max(Math.max(Math.max(rushScore, midScore), Math.max(thirdScore, campScore)), Math.max(islScore, huntScore));
        String why = String.format("rush=%.2f(%s) mid=%.2f third=%.2f camp=%.2f isl=%.2f hunt=%.2f blocks=%d armor=%.0f t=%ds",
                rushScore, rushT == null ? "-" : rushT.getName(), midScore, thirdScore, campScore, islScore, huntScore, blocks, armor, sinceOpen);
        nextDecision = ctx.tick + 20 * 20;
        if (best == rushScore && rushT != null) {
            final Player t = rushT;
            choose(Plan.RUSH, why);
            Location look = whereIs(t);
            commit(new Runnable() { public void run() { startRush(t); } }, look);
        } else if (best == midScore) {
            final Location midF = mid;
            choose(Plan.MID, why);
            commit(new Runnable() { public void run() { travel(midF, true, -1); } }, midF);
            if (game.type().teamSize() > 1) ctx.chat.callout(chance(0.5) ? "going mid" : "mid", 0.25);
        } else if (best == thirdScore && f != null) {
            choose(Plan.THIRD, why);
            startThird(f);
        } else if (best == islScore && freeIsl >= 0) {
            final Location c = view.islandCenter(freeIsl);
            final int n = freeIsl;
            choose(Plan.ISLAND, why);
            commit(new Runnable() { public void run() { travel(c, false, n); } }, c);
        } else if (best == campScore) {
            choose(Plan.CAMP, why);
            startCamp();
        } else {
            choose(Plan.HUNT, why);
            set(St.HUNT);
        }
    }

    /** An island (not ours, not visited) whose player is dead or far away from it. */
    private int freeIsland(Location me, int blocks) {
        int best = -1;
        double bd = Double.MAX_VALUE;
        for (int i = 0; i < view.islandCount(); i++) {
            if (i == island || visitedIslands.contains(i)) continue;
            Location c = view.islandCenter(i);
            if (c == null || c.getWorld() != me.getWorld()) continue;
            boolean occupied = false;
            for (Player p : game.alivePlayers()) {
                if (p == ctx.player || view.islandOf(p) != i) continue;
                Location at = whereIs(p);
                if (at == null || AIMath.hDist(at, c) < 25 || inGameSeconds() < 30) { occupied = true; break; }
            }
            if (occupied) continue;
            double d = c.distance(me);
            if (blocks < Math.max(0, d - 14) * 0.9 + 3) continue;
            if (d < bd) { bd = d; best = i; }
        }
        return best;
    }

    private long inGameSeconds() { return Math.max(0, (ctx.tick - firstOpenTick()) / 20); }

    private long openTick = -1;

    private long firstOpenTick() {
        if (openTick < 0) openTick = ctx.tick;
        return openTick;
    }

    private final Set<Integer> visitedIslands = new HashSet<>();

    private void travel(Location to, boolean lootMid, int isl) {
        if (to == null) { set(St.HUNT); return; }
        travelTo = to.clone();
        travelLootMid = lootMid;
        travelIsland = isl;
        NavOptions o = bridgeOpts();
        o.maxNodes = 12000;
        travelOpts = o;
        travelRadius = lootMid ? 4 : 5;
        seg = null;
        segDirect = false;
        set(St.TRAVEL);
    }

    private void travelTick() {
        travelOpts.maxBlocks = travelOpts.bridge ? Inv.count(ctx.player, Inv.BLOCK) : travelOpts.maxBlocks;
        Location g = waypoint(travelTo);
        boolean hop = g != travelTo;
        // island travel knows the column, not the floor: a GoalXZ stops at the island edge instead of hunting for
        // the exact Y the island happens to sit at
        if (hop) ctx.nav.goTo(g, 3.5, travelOpts);
        else ctx.nav.goToXZ(g, travelRadius);
        ctx.nav.tick();
        if (ctx.nav.arrived()) {
            if (hop) { seg = null; return; } // next hop
            if (travelLootMid) { buildMidQueue(); set(St.LOOT); }
            else if (travelIsland >= 0) { visitedIslands.add(travelIsland); buildLootQueue(travelIsland); set(St.LOOT); }
            else set(St.DECIDE);
            return;
        }
        if (ctx.nav.failed() && hop) { ctx.nav.stop(); segDirect = true; return; } // hop has no route: the whole way at once
        if (ctx.nav.failed() || inState() > 20 * 120) {
            ctx.nav.stop();
            if (travelIsland >= 0) visitedIslands.add(travelIsland);
            boolean noBlocks = Inv.count(ctx.player, Inv.BLOCK) < 4;
            set(noBlocks ? (findGatherBlock(6) != null ? St.GATHER : St.CAMP) : St.DECIDE);
            campUntil = ctx.tick + 20 * 8;
        }
    }

    /** Switch plans, but finish the block being bridged / pillared first (letting go at the tip is how bots fell). */
    private void leave(St next) {
        if (ctx.nav.doingAction()) { ctx.nav.tick(); return; }
        ctx.nav.stop();
        set(next);
    }

    // ---- rush ----
    private Player rushTarget;
    private long rushUntil;

    private void startRush(Player t) {
        rushTarget = t;
        rushUntil = ctx.tick + 20 * 75;
        seg = null;
        segDirect = false;
        set(St.RUSH);
        if (game.type().teamSize() > 1) ctx.chat.callout(chance(0.5) ? "rushing " + t.getName() : "going " + t.getName(), 0.2);
    }

    private void rushTick() {
        Player t = rushTarget;
        if (!alive(t) || ctx.tick > rushUntil || ctx.combat.ignoring(t)) { leave(St.DECIDE); return; }
        Location me = ctx.pos();
        Location to = whereIs(t);
        // not where we thought (they left their island unseen): the maps are open, a look around finds them
        if (to == null || (AIMath.hDist(me, to) < 7 && t.getLocation().distance(me) > 9)) to = t.getLocation();
        // there: if they are not in sight (behind a wall / around a corner) go for them anyway
        if (t.getLocation().distance(me) < 5.5) { goFor(t); return; }
        NavOptions o = bridgeOpts();
        o.avoidEnemies = false;
        Location g = waypoint(to);
        ctx.nav.follow(g, g == to ? 3 : 3.5, o);
        ctx.nav.tick();
        if (ctx.nav.arrived() && g != to) seg = null;
        if (ctx.nav.failed()) {
            ctx.nav.stop();
            if (g != to) { segDirect = true; return; }
            avoid(t, 20 * 40);
            set(Inv.count(ctx.player, Inv.BLOCK) < 8 && findGatherBlock(6) != null ? St.GATHER : St.DECIDE);
        }
    }

    // ---- third party ----
    private Player thirdA, thirdB;
    private long thirdUntil;
    private boolean thirdGo;

    private void startThird(Perception.Fight f) {
        thirdA = f.attacker;
        thirdB = f.victim;
        thirdUntil = ctx.tick + 20 * 45;
        thirdGo = false;
        seg = null;
        segDirect = false;
        set(St.THIRD);
    }

    private void thirdTick() {
        boolean a = alive(thirdA), b = alive(thirdB);
        if ((!a && !b) || ctx.tick > thirdUntil) { leave(St.DECIDE); return; }
        Location me = ctx.pos();
        if (!a || !b) {
            // one of them died: the winner is hurt, go
            Player w = a ? thirdA : thirdB;
            thirdGo = true;
            if (w.getLocation().distance(me) < 40) goFor(w);
            else set(St.DECIDE);
            return;
        }
        Location la = thirdA.getLocation(), lb = thirdB.getLocation();
        Location center = la.clone().add(lb).multiply(0.5);
        double d = AIMath.hDist(me, center);
        Perception.Fight f = ctx.perception.recentFight(20 * 6);
        double low = Math.min(thirdA.getHealth(), thirdB.getHealth());
        if (d > 13) {
            NavOptions o = bridgeOpts();
            o.avoidEnemies = false;
            Location g = waypoint(center);
            ctx.nav.follow(g, 9, o);
            ctx.nav.tick();
            if (ctx.nav.failed()) { ctx.nav.stop(); set(St.DECIDE); }
            return;
        }
        // close enough to watch: hold, crouch at the edge, eyes on the one about to lose
        ctx.nav.stop();
        edgeCrouch();
        Player weak = thirdA.getHealth() < thirdB.getHealth() ? thirdA : thirdB;
        Player strong = weak == thirdA ? thirdB : thirdA;
        ctx.motor.aimEntity(Motor.P_TASK, weak, AimIntent.GLANCE);
        // one is nearly dead, or they stopped fighting: clean up (the weak one if in reach, else the winner)
        if (low < 7 || f == null) {
            thirdGo = true;
            Player go = weak.getLocation().distance(me) < 8 ? weak : strong;
            goFor(go);
        }
    }

    // ---- camp / hold the island ----
    private void startCamp() {
        campsDone++;
        campUntil = ctx.tick + AIMath.range(ctx.rng, 20 * 8, 20 * 22);
        campLook = 0;
        set(St.CAMP);
    }

    private long campLook;
    private Location campAt;

    private void campTick() {
        // hold position, crouched at the edge, looking over at the other islands; throw at bridgers (reactions
        // turn a bridger into DEFEND); a fight heard nearby ends the wait
        if (ctx.tick > campUntil || ctx.perception.recentFight(30) != null) { set(St.DECIDE); return; }
        Sighting s = ctx.perception.nearestKnownEnemy(30);
        if (s != null && s.visible && !ctx.combat.ignoring(s.player)) {
            ctx.motor.aimEntity(Motor.P_TASK, s.player, AimIntent.GLANCE);
            if (s.estimate(ctx.tick).distance(ctx.pos()) < 22 && (Inv.has(ctx.player, Inv.THROWABLE) || Inv.has(ctx.player, Inv.BOW))
                    && ctx.tick - s.overVoidTick < 20) {
                ctx.combat.allowChase = false;
                goFor(s.player);
            }
            return;
        }
        if (ctx.tick >= campLook) {
            // look over at another island / the mid for a while
            int i = ctx.rng.nextInt(Math.max(1, view.islandCount()));
            Location c = i == island ? view.mid() : view.islandCenter(i);
            campAt = c == null ? null : c.clone().add(0, 2, 0);
            campLook = ctx.tick + AIMath.range(ctx.rng, 20, 60);
        }
        if (campAt != null && campLook - ctx.tick > 8) ctx.motor.aimPoint(Motor.P_TASK, campAt, AimIntent.GLANCE, 4.0);
        edgeCrouch();
    }

    // ---- regear: hurt, back off and heal ----
    private long regearUntil;

    private void startRegear() {
        choose(Plan.REGEAR, String.format("hp=%.0f", ctx.health()));
        regearUntil = ctx.tick + 20 * 9;
        set(St.REGEAR);
    }

    private void regearTick() {
        double hp = ctx.health();
        if (hp >= 14 || ctx.tick > regearUntil) { ctx.nav.stop(); set(St.DECIDE); return; }
        Location me = ctx.pos();
        Sighting near = ctx.perception.nearestKnownEnemy(16);
        if (near != null && near.lastPos != null && !overVoid()) {
            // walk away from them (stay on the ground: not toward the void)
            Location e = near.estimate(ctx.tick);
            double dx = me.getX() - e.getX(), dz = me.getZ() - e.getZ();
            double len = Math.max(0.1, Math.sqrt(dx * dx + dz * dz));
            Location away = me.clone().add(dx / len * 6, 0, dz / len * 6);
            NavOptions o = NavOptions.walk();
            o.extraVoidAversion = 2;
            ctx.nav.goTo(away, 2, o);
            ctx.nav.tick();
            return;
        }
        ctx.nav.stop();
        edgeCrouch();
        // eat something while waiting (regeneration needs a full hunger bar)
        if (ctx.player.getFoodLevel() < 20 && Inv.has(ctx.player, Inv.FOOD)) {
            if (Inv.equip(ctx.player, ctx.c, Inv.FOOD, 7) && Inv.holding(ctx.player, Inv.FOOD)) ctx.motor.holdRight();
        } else if (Inv.holding(ctx.player, Inv.FOOD)) Inv.equip(ctx.player, ctx.c, Inv.SWORD, 0);
    }

    // ---- hunt ----
    private void huntTick() {
        Location me = ctx.pos();
        // the closest / weakest one we know of
        Player tgt = null;
        double bs = Double.MAX_VALUE;
        for (Player p : game.alivePlayers()) {
            if (p == ctx.player || isAlly(p) || p.getWorld() != me.getWorld() || ctx.combat.ignoring(p)) continue;
            Location at = whereIs(p);
            if (at == null) at = p.getLocation(); // late game: everyone left is visible somewhere
            double sc = AIMath.hDist(me, at) + powerOf(p) * 0.6 + (avoiding(p) ? 40 : 0);
            if (sc < bs) { bs = sc; tgt = p; }
        }
        Location to = tgt != null ? whereIs(tgt) : null;
        if (tgt != null && to == null) to = tgt.getLocation();
        if (to == null) to = view.mid();
        if (to == null) { if (inState() > 100) set(St.DECIDE); return; }
        if (tgt != null && tgt.getLocation().distance(me) < 5.5) { goFor(tgt); return; }
        NavOptions o = bridgeOpts();
        o.maxNodes = 12000;
        o.avoidEnemies = false; // we are going for them
        Location g = waypoint(to);
        ctx.nav.follow(g, 3, o);
        ctx.nav.tick();
        if (ctx.nav.arrived() && g != to) seg = null;
        if (ctx.nav.arrived() || ctx.nav.failed()) {
            boolean stuck = ctx.nav.failed();
            ctx.nav.stop();
            if (stuck && g != to) { segDirect = true; return; }
            if (stuck && Inv.count(ctx.player, Inv.BLOCK) < 8 && findGatherBlock(6) != null) { set(St.GATHER); return; }
            if (inState() > 100) set(St.DECIDE);
        }
        if (ctx.tick > nextDecision) { nextDecision = ctx.tick + 400; set(St.DECIDE); }
    }

    private void endTick() {
        ctx.nav.stop();
        if (won() && --jumpTimer <= 0) { ctx.motor.jump(true); jumpTimer = AIMath.range(ctx.rng, 2, 20); }
    }

    // ------------------------------------------------------------------------------------------------ stats

    private int earlyStuck0, earlyFails0, earlyLootFails;
    private long earlyPlanning0;
    private boolean earlyDone;

    private void statsOpen() {
        SwStats.bot(ctx.player.getWorld());
        earlyStuck0 = ctx.nav.stuckEpisodes;
        earlyFails0 = ctx.nav.failuresTotal;
        earlyPlanning0 = ctx.nav.planningTicks;
        earlyLootFails = 0;
    }

    private void statsTick() {
        if (!earlyDone && inGameSeconds() >= 20) earlyReport(false);
    }

    private void earlyReport(boolean died) {
        if (earlyDone || openTick < 0) return;
        earlyDone = true;
        SwStats.early(ctx.player.getWorld(), ctx.nav.stuckEpisodes - earlyStuck0, ctx.nav.failuresTotal - earlyFails0,
                (ctx.nav.planningTicks - earlyPlanning0) / 20.0, earlyLootFails, died);
    }

    /** The last few seconds (state, nav move, position) for explaining a fall into the void. */
    private final String[] trail = new String[8];
    private int trailHead;
    private long lastHurtAny = -10000;

    @Override
    public void onHurt(Player attacker, double damage) {
        super.onHurt(attacker, damage);
        if (attacker != null) lastHurtAny = ctx.tick;
    }

    private void trailTick() {
        if (ctx.tick % 10 != 0) return;
        Location p = ctx.pos();
        trail[trailHead] = String.format("%s/%s%s%s@%.1f,%.1f,%.1f", st, ctx.nav.describe(), ctx.combat.engaged() ? "/F" : "",
                ctx.c.onGround() ? "" : "/air", p.getX(), p.getY(), p.getZ());
        trailHead = (trailHead + 1) % trail.length;
    }

    @Override
    public void onDeath(Player killer, boolean voidDeath) {
        if (voidDeath && killer == null && openTick >= 0) {
            String cause = ctx.tick - lastHurtAny < 200 ? "hit" : "self";
            SwStats.reaction(ctx.player.getWorld(), ctx.player.getName(), "voidfall-" + cause + "-" + st);
            if (SwStats.debug) {
                StringBuilder b = new StringBuilder();
                for (int i = 0; i < trail.length; i++) { String t = trail[(trailHead + i) % trail.length]; if (t != null) b.append(" | ").append(t); }
                org.bukkit.Bukkit.getLogger().info("[swdebug] " + ctx.player.getName() + " fell (" + cause + ")" + b);
            }
        }
        if (!earlyDone && openTick >= 0 && inGameSeconds() < 20) earlyReport(true);
        super.onDeath(killer, voidDeath);
        if (looter != null) looter.cancel();
    }
}
