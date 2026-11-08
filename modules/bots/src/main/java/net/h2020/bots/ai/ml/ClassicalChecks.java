package net.h2020.bots.ai.ml;

import net.h2020.api.stats.CombatStats;
import net.h2020.api.game.GeneratorRegistry;
import net.h2020.bots.ai.inv.Inv;
import net.h2020.bots.ai.perception.PlayerStats;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything that does not need to be asked. These are plain checks on what anybody can see of a player, run for
 * every player a few times a second, and they write the answer straight into the fact book:
 * <ul>
 * <li>in a generator / on the diamonds / on the emeralds - distance to that point of interest and standing still;</li>
 * <li>at the shop or the upgrades NPC - same idea;</li>
 * <li>fighting - damage dealt or taken in the last seconds; healing - something eaten or drunk;</li>
 * <li>holding a sword, wearing diamond armour - what is in the hand / on the body;</li>
 * <li>bridging vs placing - recent placements over the void or on solid ground;</li>
 * <li>bridging to mid - the heading while bridging points at the emerald generator.</li>
 * </ul>
 * Only the things nobody can see (where you are going, what you want to do next, how a fight feels from your side)
 * are left to the questions.
 */
public final class ClassicalChecks {
    private static long resolved;
    private static long checked;
    /** Distances to each island, sampled about once a second, to see which base somebody is closing on. */
    private static final java.util.Map<java.util.UUID, double[]> LAST_DIST = new java.util.HashMap<java.util.UUID, double[]>();
    private static final java.util.Map<java.util.UUID, Long> LAST_DIST_AT = new java.util.HashMap<java.util.UUID, Long>();

    private ClassicalChecks() {}

    public static void install(JavaPlugin p) {
        Bukkit.getScheduler().runTaskTimer(p, new Runnable() {
            @Override public void run() { tick(); }
        }, 120L, 10L);
    }

    public static void tick() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p == null || !p.isOnline() || p.isDead()) continue;
            try { check(p); checked++; } catch (Throwable ignored) { }
        }
    }

    private static void check(Player p) {
        // a bot is not a sample: its state is known to it, and letting bot ticks into the human model is what made
        // every prediction read "idle, wants to defend"
        if (net.h2020.bots.ai.BotAI.isBotPlayer(p)) return;
        net.h2020.api.stats.CombatStats.inc("check.human");
        Location at = p.getLocation();
        org.bukkit.util.Vector v = p.getVelocity();
        double speed = Math.sqrt(v.getX() * v.getX() + v.getZ() * v.getZ());
        boolean still = speed < 0.06;
        boolean placing = PlayerStats.recentPlacements(p) > 0;
        boolean hit = PlayerStats.recentHits(p) > 0;
        boolean ate = PlayerStats.recentEat(p);
        boolean overVoid = overVoid(p);

        // --- where they stand
        boolean inGen = GeneratorRegistry.near(p.getWorld(), GeneratorRegistry.TEAM_GEN, at, 3.0) && still;
        boolean onDias = GeneratorRegistry.near(p.getWorld(), GeneratorRegistry.DIAMOND_GEN, at, 4.0);
        boolean onEms = GeneratorRegistry.near(p.getWorld(), GeneratorRegistry.EMERALD_GEN, at, 4.0);
        boolean atShop = GeneratorRegistry.near(p.getWorld(), GeneratorRegistry.SHOP, at, 3.5) && still;

        // "in the generator" also covers walking into it: the direction is enough
        Double genDist = GeneratorRegistry.nearest(p.getWorld(), GeneratorRegistry.TEAM_GEN, at);
        boolean walkingToGen = !inGen && genDist != null && genDist < 14 && headingTo(p, GeneratorRegistry.TEAM_GEN);
        fact(p, QuestionBook.IN_GENERATOR, inGen || walkingToGen, inGen ? 0.9 : 0.7);
        ShopWatch.poll(p);                       // a human in a shop menu is a shop trip worth learning from
        fact(p, QuestionBook.GATHERING_DIAMONDS, onDias || "DIAMOND".equals(PlayerStats.lastPickupType(p)), 0.85);
        fact(p, QuestionBook.GATHERING_EMERALDS, onEms || "EMERALD".equals(PlayerStats.lastPickupType(p)), 0.85);
        fact(p, QuestionBook.SHOPPING, atShop, 0.85);

        // --- what they carry / wear (visible)
        ItemStack hand = p.getItemInHand();
        fact(p, QuestionBook.HAS_SWORD, hand != null && (hand.getType() == Material.WOOD_SWORD || hand.getType() == Material.STONE_SWORD
                || hand.getType() == Material.IRON_SWORD || hand.getType() == Material.DIAMOND_SWORD
                || hand.getType() == Material.GOLD_SWORD), 0.8);
        int diamondPieces = 0;
        for (ItemStack i : p.getInventory().getArmorContents()) {
            if (i != null && i.getType().name().startsWith("DIAMOND_")) diamondPieces++;
        }
        fact(p, QuestionBook.DIAMOND_ARMOUR, diamondPieces >= 2, 0.85);

        // --- bed defence: the last block went down next to a bed, and the builder is standing on that island
        boolean bedDefence = buildingBedDefence(p);
        fact(p, QuestionBook.DEFENDING, bedDefence, 0.9);

        // --- low health is a fact the game itself knows: never worth asking
        fact(p, QuestionBook.LOW_HP, p.getHealth() <= 8.0, 0.97);

        // --- which base they are closing on, and which one they are looking at
        String closing = closingOn(p);
        String looking = lookingAtBase(p);
        if (placing && overVoid) {
            if (closing != null) {
                IntentModel.setObservedBridge(p, closing);
                fact(p, QuestionBook.BRIDGING_TO_ENEMY, true, 0.7);
            } else if (looking != null) {
                IntentModel.setObservedBridge(p, looking);
                fact(p, QuestionBook.BRIDGING_TO_ENEMY, true, 0.6);
            }
        }
        if (looking != null && !looking.equals(closing) && Inv.count(p, Inv.BLOCK) >= 8) {
            // staring at a base with a stack of wool in the bag: that is where he is going next
            IntentModel.setObservedRush(p, looking);
        }

        // --- what they are doing
        fact(p, QuestionBook.FIGHTING, hit, 0.8);
        // backing off: somebody was hit recently and now the feet are going away from the closest player
        boolean facingAway = facingAwayFromNearest(p);
        fact(p, QuestionBook.BACKING_OFF, hit && facingAway, 0.75);
        fact(p, QuestionBook.RUNNING, hit && facingAway, 0.7);
        fact(p, QuestionBook.HEALING, ate, 0.8);
        if (!hit) fact(p, QuestionBook.RUNNING, ate, 0.4);        // eating out of a fight is only a weak sign
        if (placing) {
            fact(p, QuestionBook.BRIDGING_TO_MID, overVoid && headingTo(p, GeneratorRegistry.EMERALD_GEN), 0.7);
        }

        // --- and keep the activity model's own read in step with the obvious cases
        if (bedDefence) BehaviourModel.noteClass(p, BehaviourModel.PLACING);
        else if (inGen || onDias || onEms) BehaviourModel.noteClass(p, BehaviourModel.GATHERING);
        else if (atShop) BehaviourModel.noteClass(p, BehaviourModel.SHOPPING);
        else if (hit) BehaviourModel.noteClass(p, BehaviourModel.FIGHTING);
        else if (placing) BehaviourModel.noteClass(p, overVoid ? BehaviourModel.BRIDGING : BehaviourModel.PLACING);
        else if (still) BehaviourModel.noteClass(p, BehaviourModel.IDLE);
        else BehaviourModel.noteClass(p, BehaviourModel.TRAVELLING);
    }

    /**
     * True when the observable state answers the activity question on its own - the bot knows what this player is up
     * to without asking, so the question is not worth a chat line.
     */
    public static boolean explainsActivity(Player p) {
        Location at = p.getLocation();
        org.bukkit.util.Vector v = p.getVelocity();
        boolean still = Math.sqrt(v.getX() * v.getX() + v.getZ() * v.getZ()) < 0.06;
        if (GeneratorRegistry.near(p.getWorld(), GeneratorRegistry.TEAM_GEN, at, 3.0) && still) return true;
        if (GeneratorRegistry.near(p.getWorld(), GeneratorRegistry.DIAMOND_GEN, at, 4.0)) return true;
        if (GeneratorRegistry.near(p.getWorld(), GeneratorRegistry.EMERALD_GEN, at, 4.0)) return true;
        if (GeneratorRegistry.near(p.getWorld(), GeneratorRegistry.SHOP, at, 3.5) && still) return true;
        if (PlayerStats.recentHits(p) > 0) return true;
        if (PlayerStats.recentPlacements(p) >= 2) return true;
        if (buildingBedDefence(p)) return true;
        return false;
    }

    /** Is the player's movement pointed at the nearest point of that type? */
    private static boolean headingTo(Player p, int poiType) {
        org.bukkit.util.Vector v = p.getVelocity();
        if (v.lengthSquared() < 1e-4) return false;
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
        return (v.getX() * dx / l + v.getZ() * dz / l) > 0.05;
    }

    /**
     * Building the bed defence: the last block that went down is within a few blocks of a bed and the builder is
     * standing on that bed's island (so it is his own bed, not somebody else's he is digging through).
     */
    private static boolean buildingBedDefence(Player p) {
        Location placedAt = PlayerStats.lastPlace(p);
        long when = PlayerStats.lastPlaceTick(p);
        if (placedAt == null || when < 0 || PlayerStats.recentPlacements(p) == 0) return false;
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
    private static String closingOn(Player p) {
        java.util.List<Location> isl = GeneratorRegistry.islands(p.getWorld());
        if (isl.isEmpty()) return null;
        long now = System.currentTimeMillis();
        double[] prev = LAST_DIST.get(p.getUniqueId());
        Long at = LAST_DIST_AT.get(p.getUniqueId());
        double[] cur = new double[isl.size()];
        for (int i = 0; i < isl.size(); i++) cur[i] = isl.get(i).distance(p.getLocation());
        String best = null;
        if (prev != null && at != null && now - at > 600 && prev.length == cur.length) {
            double secs = (now - at) / 1000.0;
            double fastest = 0.4;                                   // blocks per second
            int bestIdx = -1;
            for (int i = 0; i < cur.length; i++) {
                double rate = (prev[i] - cur[i]) / secs;             // positive = getting closer
                if (rate > fastest) { fastest = rate; bestIdx = i; }
            }
            if (bestIdx >= 0) best = GeneratorRegistry.islandName(p.getWorld(), bestIdx);
        }
        if (prev == null || at == null || now - at > 600) {
            LAST_DIST.put(p.getUniqueId(), cur);
            LAST_DIST_AT.put(p.getUniqueId(), now);
        }
        return best;
    }

    /** The base the player's crosshair is pointing at (within ~20 degrees, inside 60 blocks), or null. */
    private static String lookingAtBase(Player p) {
        org.bukkit.util.Vector look = p.getLocation().getDirection();
        Location me = p.getLocation();
        String best = null;
        double bestDot = 0.94;                                      // ~20 degrees
        java.util.List<Location> isl = GeneratorRegistry.islands(p.getWorld());
        for (int i = 0; i < isl.size(); i++) {
            Location l = isl.get(i);
            if (l.getWorld() != me.getWorld()) continue;
            if (l.distance(me) > 60) continue;
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
    private static boolean facingAwayFromNearest(Player p) {
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
        org.bukkit.util.Vector v = p.getVelocity();
        double speed = Math.sqrt(v.getX() * v.getX() + v.getZ() * v.getZ());
        return dot < -0.25 && speed > 0.05;
    }

    private static boolean overVoid(Player p) {
        Location l = p.getLocation();
        for (int dy = 1; dy <= 20; dy++) {
            if (l.getWorld().getBlockAt(l.getBlockX(), l.getBlockY() - dy, l.getBlockZ()).getType() != Material.AIR) return false;
        }
        return true;
    }

    private static void fact(Player p, int fact, boolean value, double confidence) {
        QuestionBook.setObserved(p, fact, value, confidence);
        resolved++;
    }

    public static List<String> report() {
        List<String> out = new ArrayList<String>();
        out.add(String.format("§6[ml] §eclassical checks: §f%d§7 facts decided without asking (%d player-checks)",
                resolved, checked));
        return out;
    }

    public static void reset() { resolved = 0; checked = 0; }
}
