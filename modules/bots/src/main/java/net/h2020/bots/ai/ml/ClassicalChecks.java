package net.h2020.bots.ai.ml;

import net.h2020.api.stats.CombatStats;
import net.h2020.api.game.GeneratorRegistry;
import net.h2020.bots.ai.inv.Inv;
import net.h2020.bots.ai.perception.PlayerStats;
import net.h2020.bots.ai.perception.SoundBus;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Everything that does not need to be asked: plain checks on what anybody can see of a player, run a few times a
 * second, written straight into the fact book.
 *
 * <p>Three rules the checks are held to, because every one of them was broken in an earlier version and the mistakes
 * all look the same from the outside - a confident answer that is wrong:
 * <ol>
 * <li><b>Every fact must be decided in both directions.</b> A fact that is only ever written {@code true} teaches the
 *   book nothing and can never be scored as a miss. BRIDGING_TO_ENEMY was written true or not at all.</li>
 * <li><b>No evidence may be stale.</b> A pickup from a minute ago is not a diamond run now. The check that wrote
 *   GATHERING_DIAMONDS from {@code lastPickupType} with no recency test was reading a lifetime value.</li>
 * <li><b>A fact decided by two branches must be decided once.</b> RUNNING was written twice in the same pass with
 *   different meanings, so the second write silently overwrote the first.</li>
 * </ol>
 *
 * <p>Where a check can see corroboration, it raises its confidence rather than its answer: the fact system blends
 * confidence into the belief, so a check that agrees with two independent observations should say so.
 */
public final class ClassicalChecks {
    private static long resolved;
    private static int checkThrew;
    private static long checked;

    /** Distances to each island, sampled about once a second, to see which base somebody is closing on. */
    private static final Map<UUID, double[]> LAST_DIST = new HashMap<UUID, double[]>();
    private static final Map<UUID, Long> LAST_DIST_AT = new HashMap<UUID, Long>();

    /**
     * What the last check decided for a (player, fact), and when.
     *
     * <p>Only kept so the check can be scored: a question that a human answers a moment later is a free label for the
     * check that made the same call. Without this the checks have a confidence argument in the source and no measured
     * accuracy, and there is no way to know which of them to trust when the switch is on.
     */
    private static final Map<UUID, boolean[]> LAST_DECISION = new HashMap<UUID, boolean[]>();
    private static final Map<UUID, long[]> DECIDED_AT = new HashMap<UUID, long[]>();
    /** Per fact: how often a human answer agreed / disagreed with the check that had just run. */
    private static final long[] HIT = new long[QuestionBook.FACTS];
    private static final long[] MISS = new long[QuestionBook.FACTS];
    /** Per fact: how often the check fired at all, so "never wrong" can be told from "never right". */
    private static final long[] FIRED = new long[QuestionBook.FACTS];

    /** The player's own team, learned from where he keeps standing rather than from a scoreboard anybody can read. */
    private static final Map<UUID, String> HOME_ISLAND = new HashMap<UUID, String>();

    /** ~3 s in ticks: how long a pickup still counts as "he just picked that up". */
    private static final int PICKUP_WINDOW = 60;
    /** ~5 s: how long ago a block placement still describes what he is doing now. */
    private static final int PLACE_WINDOW = 100;

    private ClassicalChecks() {}

    public static void install(JavaPlugin p) {
        Bukkit.getScheduler().runTaskTimer(p, new Runnable() {
            @Override public void run() { tick(); }
        }, 120L, 10L);
    }

    public static void tick() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p == null || !p.isOnline() || p.isDead()) continue;
            try { check(p); checked++; }
            catch (Throwable t) {
                CombatStats.inc("check.threw");
                if (checkThrew++ < 3) Bukkit.getLogger().warning("[ml] classical check failed for " + p.getName() + ": " + t);
            }
        }
    }

    private static void check(Player p) {
        // a bot is not a sample: its state is known to it, and letting bot ticks into the human model is what made
        // every prediction read "idle, wants to defend"
        if (net.h2020.bots.ai.BotAI.isBotPlayer(p)) return;
        checkBody(p, false);
    }

    /**
     * Run the checks against one player and say what happened, including for a bot.
     *
     * <p>On a server with no humans on it - which is most of the time here - {@link #check} returns before doing any
     * work, so there is no way to see whether the checks run at all, whether every fact gets decided, or whether one of
     * them throws. This runs the same body on demand, against whatever player is named, and reports the failures
     * instead of swallowing them.
     */
    public static List<String> selftest(Player p) {
        List<String> out = new ArrayList<String>();
        if (p == null) { out.add("§cno such player"); return out; }
        long before = resolved;
        int[] probeFired = new int[QuestionBook.FACTS];
        long[] saved = FIRED.clone();
        try {
            checkBody(p, true);
        } catch (Throwable t) {
            for (int f = 0; f < QuestionBook.FACTS; f++) FIRED[f] = saved[f];
            out.add("§c[ml] classical probe threw: §f" + t);
            StackTraceElement[] st = t.getStackTrace();
            for (int i = 0; i < Math.min(3, st.length); i++) out.add("§7  at " + st[i]);
            return out;
        }
        for (int f = 0; f < QuestionBook.FACTS; f++) { probeFired[f] = (int) (FIRED[f] - saved[f]); FIRED[f] = saved[f]; }
        int distinct = 0;
        for (int f = 0; f < QuestionBook.FACTS; f++) if (probeFired[f] > 0) distinct++;
        out.add(String.format("§6[ml] §eprobe on §f%s§7: §f%d§7 facts decided, §f%d§7 distinct",
                p.getName(), resolved - before, distinct));
        StringBuilder never = new StringBuilder("§7undecided:");
        boolean any = false;
        for (int f = 0; f < QuestionBook.FACTS; f++) {
            if (probeFired[f] > 0) continue;
            never.append(' ').append(QuestionBook.name(f));
            any = true;
        }
        if (any) out.add(never.toString());
        else out.add("§aall fifteen facts decided by the checks on this player");
        return out;
    }

    private static int countFired() {
        int n = 0;
        for (int f = 0; f < QuestionBook.FACTS; f++) if (FIRED[f] > 0) n++;
        return n;
    }

    private static void checkBody(Player p, boolean probe) {
        if (!probe) CombatStats.inc("check.human");

        final Location at = p.getLocation();
        final long now = SoundBus.now();

        // ---------------------------------------------------------------- movement, measured properly
        // getVelocity() is the SERVER's idea of movement and is zero for a player walking normally - the client sends
        // positions, not velocities, so a player on foot reports 0 and only knockback or a jump shows up. Everything
        // built on "still" was therefore reading "still" for anybody walking, and the two facts that depend on it
        // (IN_GENERATOR, SHOPPING) were decided on a value that is almost always the same. Track displacement instead.
        Motion m = motion(p, at, now);
        boolean still = m.speed < 0.06;
        boolean walking = m.speed >= 0.06;

        boolean placing = PlayerStats.recentPlacements(p) > 0;
        boolean placedRecently = within(p, PLACE_WINDOW, PlayerStats.lastPlaceTick(p));
        boolean hit = PlayerStats.recentHits(p) > 0;
        boolean hurt = PlayerStats.hitsTaken(p) > 0;
        boolean ate = PlayerStats.recentEat(p);
        boolean overVoid = overVoid(p);
        boolean clean = within(p, PICKUP_WINDOW, lastPickupTick(p));

        // ---------------------------------------------------------------- where they stand
        boolean inGen = GeneratorRegistry.near(p.getWorld(), GeneratorRegistry.TEAM_GEN, at, 3.0) && still;
        boolean onDias = GeneratorRegistry.near(p.getWorld(), GeneratorRegistry.DIAMOND_GEN, at, 4.0);
        boolean onEms = GeneratorRegistry.near(p.getWorld(), GeneratorRegistry.EMERALD_GEN, at, 4.0);
        boolean atShop = GeneratorRegistry.near(p.getWorld(), GeneratorRegistry.SHOP, at, 3.5) && still;

        // "in the generator" also covers walking into it, but only when the heading is real: a player whose velocity
        // reads zero can never satisfy headingTo, so use the tracked motion instead.
        Double genDist = GeneratorRegistry.nearest(p.getWorld(), GeneratorRegistry.TEAM_GEN, at);
        boolean walkingToGen = !inGen && genDist != null && genDist < 14 && headingTo(p, GeneratorRegistry.TEAM_GEN, m);
        // spent time standing on a team generator is the strongest evidence of which team somebody is on, and it is
        // needed by BRIDGING_TO_BASE (his own island) which otherwise guesses from the nearest base
        if (inGen) noteHome(p, at);
        fact(p, QuestionBook.IN_GENERATOR, inGen || walkingToGen, inGen ? 0.92 : (walkingToGen ? 0.6 : 0.75));

        ShopWatch.poll(p);                       // a human in a shop menu is a shop trip worth learning from
        // the pickup only counts if it just happened: lastPickupType is a lifetime value, and reading it without a
        // recency test made GATHERING_DIAMONDS permanently true for anybody who had ever touched a diamond
        fact(p, QuestionBook.GATHERING_DIAMONDS,
                onDias || (clean && "DIAMOND".equals(PlayerStats.lastPickupType(p))),
                onDias ? 0.9 : (clean ? 0.7 : 0.7));
        fact(p, QuestionBook.GATHERING_EMERALDS,
                onEms || (clean && "EMERALD".equals(PlayerStats.lastPickupType(p))),
                onEms ? 0.9 : (clean ? 0.75 : 0.7));
        // a shop is a menu: standing in it is not required once the menu is open, but nobody shops while sprinting
        fact(p, QuestionBook.SHOPPING, atShop || ShopWatch.inMenu(p), atShop ? 0.88 : 0.7);

        // ---------------------------------------------------------------- what they carry / wear (visible)
        ItemStack hand = p.getItemInHand();
        boolean swordInHand = hand != null && net.h2020.bots.ai.inv.Items.isSword(hand);
        // "got ur sword" means he has one, not that it is in his hand - combat players hold blocks to bridge. Counting
        // the whole inventory is what the question actually asks; the hand is only corroboration.
        boolean swordOwned = swordInHand || Inv.has(p, Inv.SWORD);
        fact(p, QuestionBook.HAS_SWORD, swordOwned, swordInHand ? 0.95 : 0.75);
        int diamondPieces = 0;
        for (ItemStack i : p.getInventory().getArmorContents()) {
            if (i != null && i.getType().name().startsWith("DIAMOND_")) diamondPieces++;
        }
        fact(p, QuestionBook.DIAMOND_ARMOUR, diamondPieces >= 2, diamondPieces >= 3 ? 0.95 : 0.85);

        // ---------------------------------------------------------------- bed defence
        boolean bedDefence = buildingBedDefence(p);
        fact(p, QuestionBook.DEFENDING, bedDefence, 0.9);

        // ---------------------------------------------------------------- low health
        // the game itself knows this one; the threshold is Hypixel's own "low" band rather than an arbitrary 8
        double hp = p.getHealth();
        fact(p, QuestionBook.LOW_HP, hp <= 8.0, hp <= 4.0 ? 0.99 : 0.96);

        // ---------------------------------------------------------------- which base, which heading
        String closing = closingOn(p, at, now);
        String looking = lookingAtBase(p, at);
        String own = HOME_ISLAND.get(p.getUniqueId());
        // fall back to the nearest base when nothing has been observed yet, but only close to it
        if (own == null) own = ownIsland(p, at);

        boolean bridging = placing && overVoid && placedRecently;
        // when he is not bridging at all, all three bridging facts are false and can say so. Writing them only while
        // bridging left the beliefs free to rise and never fall, and meant the facts were decided a few times per game
        // instead of every pass.
        if (!bridging) {
            fact(p, QuestionBook.BRIDGING_TO_ENEMY, false, 0.72);
            fact(p, QuestionBook.BRIDGING_TO_BASE, false, 0.72);
            fact(p, QuestionBook.BRIDGING_TO_MID, false, 0.7);
        }
        // bridging TO THEM: closing on or looking at a base that is not his own. Decided in BOTH directions - the old
        // version only ever wrote true, so the belief could rise and never fall, and a miss could never be recorded.
        if (bridging) {
            String target = closing != null ? closing : looking;
            boolean enemy = target != null && !target.equals(own);
            if (enemy) {
                IntentModel.setObservedBridge(p, target);
                // closing is measured movement and is much better evidence than a glance at the horizon
                fact(p, QuestionBook.BRIDGING_TO_ENEMY, true, target.equals(closing) ? 0.8 : 0.62);
                fact(p, QuestionBook.BRIDGING_TO_BASE, false, 0.75);
            } else if (target != null) {
                // crossing the void towards his own island: that is a bridge back, not an attack
                fact(p, QuestionBook.BRIDGING_TO_BASE, true, target.equals(closing) ? 0.8 : 0.65);
                fact(p, QuestionBook.BRIDGING_TO_ENEMY, false, 0.75);
            } else {
                // bridging with no base in view at all: not towards one, so neither of the two-way facts is true.
                // Both are still decided - leaving one unwritten in this branch is how a fact ends up never asked.
                fact(p, QuestionBook.BRIDGING_TO_ENEMY, false, 0.6);
                fact(p, QuestionBook.BRIDGING_TO_BASE, false, 0.6);
            }
            // the emerald generator is mid for every shipped map, and its heading is only meaningful while placing
            fact(p, QuestionBook.BRIDGING_TO_MID, headingTo(p, GeneratorRegistry.EMERALD_GEN, m), 0.7);
        }
        if (looking != null && !looking.equals(closing) && Inv.count(p, Inv.BLOCK) >= 8) {
            // staring at a base with a stack of wool in the bag: that is where he is going next
            IntentModel.setObservedRush(p, looking);
        }

        // ---------------------------------------------------------------- what they are doing
        fact(p, QuestionBook.FIGHTING, hit || hurt, hit && hurt ? 0.9 : 0.8);

        // backing off: somebody was hit recently and now the feet are going away from the closest player
        boolean facingAway = facingAwayFromNearest(p, m);
        // RUNNING used to be written twice in this method with two different meanings; the second write overwrote the
        // first and a fighting player fleeing was recorded as not running. One decision, one write.
        boolean running = facingAway && walking;
        fact(p, QuestionBook.BACKING_OFF, hit && facingAway, facingAway ? 0.78 : 0.7);
        // eating outside a fight is a heal without a retreat; inside one it is both
        fact(p, QuestionBook.RUNNING, running || (ate && walking && !hit), running ? 0.75 : 0.45);
        fact(p, QuestionBook.HEALING, ate, ate ? 0.85 : 0.7);


        // ---------------------------------------------------------------- activity
        if (bedDefence) BehaviourModel.noteClass(p, BehaviourModel.PLACING);
        else if (inGen || onDias || onEms) BehaviourModel.noteClass(p, BehaviourModel.GATHERING);
        else if (atShop) BehaviourModel.noteClass(p, BehaviourModel.SHOPPING);
        else if (hit || hurt) BehaviourModel.noteClass(p, BehaviourModel.FIGHTING);
        else if (placing) BehaviourModel.noteClass(p, overVoid ? BehaviourModel.BRIDGING : BehaviourModel.PLACING);
        else if (still) BehaviourModel.noteClass(p, BehaviourModel.IDLE);
        else BehaviourModel.noteClass(p, BehaviourModel.TRAVELLING);
    }

    // ------------------------------------------------------------------ movement

    /** What a player's feet actually did between two checks, since {@code getVelocity()} does not report walking. */
    private static final class Motion {
        double speed;            // blocks per tick, horizontal
        double dx, dz;           // unit heading when moving, else zero
    }

    private static final Map<UUID, Location> LAST_POS = new HashMap<UUID, Location>();
    private static final Map<UUID, Long> LAST_POS_AT = new HashMap<UUID, Long>();

    /**
     * Horizontal speed and heading from the change in position since the previous check.
     *
     * <p>{@code Player.getVelocity()} is the server's own motion vector and stays at zero for a player walking under
     * client authority - which is all of them in 1.8. Reading it made "standing still" true for anybody on foot, so
     * IN_GENERATOR and SHOPPING were decided on a value that was nearly constant, and {@code headingTo} could never be
     * satisfied by walking. Displacement between samples is what the player's feet actually did.
     */
    private static Motion motion(Player p, Location at, long now) {
        Motion m = new Motion();
        Location prev = LAST_POS.get(p.getUniqueId());
        Long prevAt = LAST_POS_AT.get(p.getUniqueId());
        if (prev != null && prevAt != null && prev.getWorld() == at.getWorld()) {
            long dt = Math.max(1, now - prevAt);
            double ddx = at.getX() - prev.getX(), ddz = at.getZ() - prev.getZ();
            double dist = Math.sqrt(ddx * ddx + ddz * ddz);
            m.speed = dist / dt;
            if (dist > 1e-4) { m.dx = ddx / dist; m.dz = ddz / dist; }
            // a teleport or a respawn is not movement: a 30-block jump in one sample is not a 30-block-per-tick sprint
            if (dist > 12) m.speed = 0;
        }
        LAST_POS.put(p.getUniqueId(), at.clone());
        LAST_POS_AT.put(p.getUniqueId(), now);
        return m;
    }

    /** Is the player's movement pointed at the nearest point of that type? Uses tracked motion, not velocity. */
    private static boolean headingTo(Player p, int poiType, Motion m) {
        if (m.dx == 0 && m.dz == 0) return false;
        Location me = p.getLocation();
        Location target = null;
        double best = Double.MAX_VALUE;
        for (Location l : GeneratorRegistry.points(p.getWorld(), poiType)) {
            if (l.getWorld() != me.getWorld()) continue;
            double d = l.distanceSquared(me);
            if (d < best) { best = d; target = l; }
        }
        if (target == null) return false;
        double dx = target.getX() - me.getX(), dz = target.getZ() - me.getZ();
        double l = Math.max(1e-3, Math.sqrt(dx * dx + dz * dz));
        return (m.dx * dx / l + m.dz * dz / l) > 0.35;      // within ~70 degrees of the target
    }

    // ------------------------------------------------------------------ helpers

    /**
     * True when the observable state answers the activity question on its own - the bot knows what this player is up
     * to without asking, so the question is not worth a chat line.
     */
    public static boolean explainsActivity(Player p) {
        Location at = p.getLocation();
        if (GeneratorRegistry.near(p.getWorld(), GeneratorRegistry.TEAM_GEN, at, 3.0)) return true;
        if (GeneratorRegistry.near(p.getWorld(), GeneratorRegistry.DIAMOND_GEN, at, 4.0)) return true;
        if (GeneratorRegistry.near(p.getWorld(), GeneratorRegistry.EMERALD_GEN, at, 4.0)) return true;
        if (GeneratorRegistry.near(p.getWorld(), GeneratorRegistry.SHOP, at, 3.5)) return true;
        if (PlayerStats.recentHits(p) > 0) return true;
        if (PlayerStats.hitsTaken(p) > 0) return true;
        if (PlayerStats.recentPlacements(p) >= 2) return true;
        if (buildingBedDefence(p)) return true;
        return false;
    }

    /**
     * Building the bed defence: the last block that went down is within a few blocks of a bed and the builder is
     * standing on that bed's island (so it is his own bed, not somebody else's he is digging through).
     */
    private static boolean buildingBedDefence(Player p) {
        Location placedAt = PlayerStats.lastPlace(p);
        long when = PlayerStats.lastPlaceTick(p);
        if (placedAt == null || when < 0 || !within(p, PLACE_WINDOW, when)) return false;
        if (PlayerStats.recentPlacements(p) == 0) return false;
        Location bed = null;
        double bd = 4.5;
        for (Location b : GeneratorRegistry.points(p.getWorld(), GeneratorRegistry.BED)) {
            if (b.getWorld() != placedAt.getWorld()) continue;
            double d = b.distance(placedAt);
            if (d < bd) { bd = d; bed = b; }
        }
        if (bed == null) return false;
        // and he is not just passing: his feet are near that bed too
        return bed.distance(p.getLocation()) < 9;
    }

    /** The island whose distance to the player is shrinking fastest right now (name, or null). */
    private static String closingOn(Player p, Location at, long now) {
        List<Location> isl = GeneratorRegistry.islands(p.getWorld());
        if (isl.isEmpty()) return null;
        double[] prev = LAST_DIST.get(p.getUniqueId());
        Long prevAt = LAST_DIST_AT.get(p.getUniqueId());
        double[] cur = new double[isl.size()];
        for (int i = 0; i < isl.size(); i++) cur[i] = isl.get(i).distance(at);
        String best = null;
        if (prev != null && prevAt != null && now - prevAt > 12 && prev.length == cur.length) {
            double secs = (now - prevAt) / 20.0;                 // ticks to seconds
            double fastest = 1.5;                                // blocks per second: faster than a walk, so deliberate
            int bestIdx = -1;
            for (int i = 0; i < cur.length; i++) {
                double rate = (prev[i] - cur[i]) / secs;          // positive = getting closer
                if (rate > fastest) { fastest = rate; bestIdx = i; }
            }
            if (bestIdx >= 0) best = GeneratorRegistry.islandName(p.getWorld(), bestIdx);
        }
        if (prev == null || prevAt == null || now - prevAt > 12) {
            LAST_DIST.put(p.getUniqueId(), cur);
            LAST_DIST_AT.put(p.getUniqueId(), now);
        }
        return best;
    }

    /** The base the player's crosshair is pointing at (within ~20 degrees, inside 60 blocks), or null. */
    private static String lookingAtBase(Player p, Location me) {
        org.bukkit.util.Vector look = me.getDirection();
        String best = null;
        double bestDot = 0.94;                                      // ~20 degrees
        List<Location> isl = GeneratorRegistry.islands(p.getWorld());
        for (int i = 0; i < isl.size(); i++) {
            Location l = isl.get(i);
            if (l.getWorld() != me.getWorld()) continue;
            double d = l.distance(me);
            if (d > 60) continue;
            double dx = l.getX() - me.getX(), dz = l.getZ() - me.getZ();
            double len = Math.max(1e-3, Math.sqrt(dx * dx + dz * dz));
            double dot = (look.getX() * dx + look.getZ() * dz) / len;
            if (dot > bestDot) { bestDot = dot; best = GeneratorRegistry.islandName(p.getWorld(), i); }
        }
        return best;
    }

    /**
     * Is the player turned away from the closest other player? Knockback moves somebody without turning him - a
     * player being hit keeps looking at the attacker - while somebody who decides to back off turns his head and
     * walks or strafes away. So the test is the angle between the look direction and the direction to that player
     * (dot < -0.25 is roughly more than 105 degrees off), with a little movement required so a stationary player
     * looking around is not mistaken for a runner.
     */
    private static boolean facingAwayFromNearest(Player p, Motion m) {
        Player near = null;
        double bd = 9;
        for (Player o : p.getWorld().getPlayers()) {
            if (o == p || o.isDead()) continue;
            double d = o.getLocation().distance(p.getLocation());
            if (d < bd) { bd = d; near = o; }
        }
        if (near == null) return false;
        org.bukkit.util.Vector look = p.getLocation().getDirection();
        double dx = near.getLocation().getX() - p.getLocation().getX();
        double dz = near.getLocation().getZ() - p.getLocation().getZ();
        double len = Math.max(1e-3, Math.sqrt(dx * dx + dz * dz));
        double dot = (look.getX() * dx + look.getZ() * dz) / len;
        return dot < -0.25 && m.speed > 0.05;
    }

    /**
     * Remember which base a player has been standing on a team generator of.
     *
     * <p>A human's team is not readable from the world, so "his own island" has to be learned: the generator he stands
     * in is the one that pays him, and BRIDGING_TO_BASE is meaningless without it. Sticky on purpose - one visit is
     * enough, and a player who wanders near another base on his way past should not have his home rewritten.
     */
    private static void noteHome(Player p, Location at) {
        String isl = GeneratorRegistry.islandName(p.getWorld(), GeneratorRegistry.islandIndex(p.getWorld(), at));
        if (isl == null) return;
        HOME_ISLAND.put(p.getUniqueId(), isl);
    }

    /** The island this player is closest to, if he is close to one at all. */
    private static String ownIsland(Player p, Location at) {
        List<Location> isl = GeneratorRegistry.islands(p.getWorld());
        String best = null;
        double bd = Double.MAX_VALUE;
        for (int i = 0; i < isl.size(); i++) {
            Location l = isl.get(i);
            if (l.getWorld() != p.getWorld()) continue;
            double d = l.distanceSquared(at);
            if (d < bd) { bd = d; best = GeneratorRegistry.islandName(p.getWorld(), i); }
        }
        return bd < 24 * 24 ? best : null;        // a base is only "his" if he is near it
    }

    private static boolean overVoid(Player p) {
        Location l = p.getLocation();
        for (int dy = 1; dy <= 20; dy++) {
            if (l.getWorld().getBlockAt(l.getBlockX(), l.getBlockY() - dy, l.getBlockZ()).getType() != Material.AIR) return false;
        }
        return true;
    }

    /** Was {@code when} no more than {@code window} ticks ago? */
    private static boolean within(Player p, int window, long when) {
        if (when <= 0) return false;
        long now = SoundBus.now();
        return now >= when && now - when <= window;
    }

    /** When the last pickup happened, on the same clock the rest of the perception layer uses. */
    private static long lastPickupTick(Player p) {
        // PlayerStats exposes recency as a boolean, not a timestamp; recentPickup covers the same window we want, so
        // use it rather than reaching into its private record.
        return PlayerStats.recentPickup(p) ? SoundBus.now() : -1;
    }

    private static void fact(Player p, int fact, boolean value, double confidence) {
        QuestionBook.setObserved(p, fact, value, confidence);
        remember(p, fact, value);
        resolved++;
        FIRED[fact]++;
        CombatStats.inc("check.fact");
    }

    private static void remember(Player p, int fact, boolean value) {
        boolean[] d = LAST_DECISION.get(p.getUniqueId());
        if (d == null) { d = new boolean[QuestionBook.FACTS]; LAST_DECISION.put(p.getUniqueId(), d); }
        long[] t = DECIDED_AT.get(p.getUniqueId());
        if (t == null) { t = new long[QuestionBook.FACTS]; DECIDED_AT.put(p.getUniqueId(), t); }
        d[fact] = value;
        t[fact] = System.currentTimeMillis();
    }

    /**
     * A human just answered {@code fact}. If a check decided the same thing within the last few seconds, score the
     * check against him - the answer is ground truth for exactly the question the check was answering.
     *
     * <p>Called from {@link QuestionBook#answer}. Only recent decisions count: a check from ten seconds ago was looking
     * at a different moment, and scoring it would blame the check for the player having moved.
     */
    public static void scoreAgainstAnswer(Player p, int fact, boolean yes) {
        if (p == null || fact < 0 || fact >= QuestionBook.FACTS) return;
        boolean[] d = LAST_DECISION.get(p.getUniqueId());
        long[] t = DECIDED_AT.get(p.getUniqueId());
        if (d == null || t == null || t[fact] <= 0) return;
        if (System.currentTimeMillis() - t[fact] > 4000) return;      // too old to be about the same moment
        if (d[fact] == yes) { HIT[fact]++; CombatStats.inc("check.agree"); }
        else { MISS[fact]++; CombatStats.inc("check.disagree"); }
        t[fact] = 0;                                                  // one score per decision
    }

    public static List<String> report() {
        List<String> out = new ArrayList<String>();
        out.add(String.format("§6[ml] §eclassical checks: §f%d§7 facts decided without asking (%d player-checks)",
                resolved, checked));
        long scored = 0, right = 0;
        for (int f = 0; f < QuestionBook.FACTS; f++) { scored += HIT[f] + MISS[f]; right += HIT[f]; }
        if (scored > 0) {
            out.add(String.format("§6[ml] §eaccuracy: §f%.0f%%§7 of §f%d§7 checks agreed with the player "
                    + "(§f%d§7 were wrong)", 100.0 * right / scored, scored, scored - right));
        } else {
            out.add("§6[ml] §eaccuracy: §7not scored yet - a check is scored when a player answers the same "
                    + "question within a few seconds of it");
        }
        // per-fact: the ones worth trusting with the switch on, and the ones that should stay a question
        StringBuilder line = new StringBuilder("§7by fact:");
        boolean any = false;
        for (int f = 0; f < QuestionBook.FACTS; f++) {
            long n = HIT[f] + MISS[f];
            if (n < 3) continue;
            line.append(' ').append(QuestionBook.name(f)).append('=')
                .append((int) (100.0 * HIT[f] / n)).append('%');
            any = true;
        }
        if (any) out.add(line.toString());
        // fires per fact: a check that never fires cannot be right, and cannot be scored either. Named facts that are
        // decided are the ones the switch is actually running on.
        StringBuilder fires = new StringBuilder("§7fire counts:");
        boolean anyFire = false;
        for (int f = 0; f < QuestionBook.FACTS; f++) {
            if (FIRED[f] == 0) continue;
            fires.append(' ').append(shortName(f)).append('=').append(FIRED[f]);
            anyFire = true;
        }
        if (anyFire) out.add(fires.toString());
        // facts nothing ever decides: the ones the checks are blind to, which stay a question whatever the switch says
        StringBuilder never = new StringBuilder("§7never decided:");
        boolean anyNever = false;
        for (int f = 0; f < QuestionBook.FACTS; f++) {
            if (FIRED[f] > 0) continue;
            never.append(' ').append(QuestionBook.name(f));
            anyNever = true;
        }
        if (anyNever) out.add(never.toString());
        // the fails: what the checks were most often wrong about. Worth naming, because a check that is wrong more
        // often than it is right should not be deciding anything when asking is switched off.
        int worst = -1; double worstRate = 0.55;
        for (int f = 0; f < QuestionBook.FACTS; f++) {
            long n = HIT[f] + MISS[f];
            if (n < 5) continue;
            double rate = MISS[f] / (double) n;
            if (rate > worstRate) { worstRate = rate; worst = f; }
        }
        if (worst >= 0) {
            out.add(String.format("§c[ml] §eworst check: §f%s§7 is wrong §f%.0f%%§7 of the time (§d%d§7 of §f%d§7) - "
                    + "keep it a question", QuestionBook.name(worst), worstRate * 100, MISS[worst],
                    HIT[worst] + MISS[worst]));
        }
        return out;
    }

    /** A short label, so the fire-count line fits on one chat line with all fifteen facts on it. */
    private static String shortName(int f) {
        switch (f) {
            case QuestionBook.IN_GENERATOR: return "gen";
            case QuestionBook.LOW_HP: return "low";
            case QuestionBook.DIAMOND_ARMOUR: return "diaarm";
            case QuestionBook.HAS_SWORD: return "sword";
            case QuestionBook.BRIDGING_TO_MID: return "mid";
            case QuestionBook.BRIDGING_TO_ENEMY: return "rush";
            case QuestionBook.BRIDGING_TO_BASE: return "back";
            case QuestionBook.RUNNING: return "run";
            case QuestionBook.FIGHTING: return "fight";
            case QuestionBook.GATHERING_DIAMONDS: return "diam";
            case QuestionBook.GATHERING_EMERALDS: return "emer";
            case QuestionBook.DEFENDING: return "def";
            case QuestionBook.SHOPPING: return "shop";
            case QuestionBook.HEALING: return "heal";
            case QuestionBook.BACKING_OFF: return "off";
            default: return "f" + f;
        }
    }

    public static void reset() {
        resolved = 0;
        checked = 0;
        LAST_DECISION.clear();
        DECIDED_AT.clear();
        LAST_POS.clear();
        LAST_POS_AT.clear();
        LAST_DIST.clear();
        LAST_DIST_AT.clear();
        HOME_ISLAND.clear();
        for (int f = 0; f < QuestionBook.FACTS; f++) { HIT[f] = 0; MISS[f] = 0; FIRED[f] = 0; }
    }
}
