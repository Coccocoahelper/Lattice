package net.h2020.practice.drill;

import net.h2020.api.core.Messages;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import net.h2020.practice.PracticeArena;
import net.h2020.practice.PracticeGame;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Block clutching: knocked off, and you save yourself on the way down.
 *
 * <p>A clutch is not a fall you chose. It is what happens when something takes the ground out from under you - a
 * projectile, a sword hit, a fireball - and you are already past the edge before you can be careful. What makes it a
 * save rather than luck is placing blocks under yourself <em>while falling</em> and landing on one, so a fall that
 * would have killed you ends on something you made.
 *
 * <p>So the drill never drops the player on its own. It stands them on a small platform over the void and fires arrows
 * at them from alternating sides. A hit that connects applies real knockback off the edge, and the attempt starts at
 * that moment:
 *
 * <ul>
 *   <li><b>save</b> - they land, below the platform, on a block they placed after the knock</li>
 *   <li><b>miss</b> - they reach the void without landing on one</li>
 * </ul>
 *
 * <p>Fall damage is the same skill against a different floor, so a save reports how far the fall was: at four blocks
 * or more, the clutch is what stood between the player and a death by fall damage on a real map.
 */
public final class BlockClutchDrill implements Drill {

    private static final int PLATFORM_Y = 72;
    /** Below this is the void: a miss. */
    private static final int VOID_Y = 40;
    /** Ticks between shots: long enough to land, clutch and reset, short enough to keep the drill busy. */
    private static final int SHOT_EVERY = 42;
    /** A fall this deep is lethal on solid ground from full health. */
    private static final double LETHAL_FALL = 3.5;

    private PracticeArena arena;
    private Location spawn;
    private int ticker;

    /** {knocks, saves, misses, bestMs, streak} */
    private final Map<UUID, long[]> rows = new HashMap<UUID, long[]>();
    /** When the current knock started; absent when the player is safe on the platform. */
    private final Map<UUID, Long> knockedAt = new HashMap<UUID, Long>();
    /** A block placed since the knock is what turns a fall into a save. */
    private final Map<UUID, Boolean> placedSinceKnock = new HashMap<UUID, Boolean>();
    /** Y at the moment of the knock, for the would-this-have-killed-you line. */
    private final Map<UUID, Double> knockedFrom = new HashMap<UUID, Double>();
    /** Alternate the firing side so the knock direction is not predictable. */
    private boolean leftSide;

    @Override public String id() { return "blockclutch"; }

    @Override public String display() { return "Block Clutching"; }

    @Override public String hint() {
        return "you will be shot off the platform - place blocks under yourself as you fall";
    }

    @Override
    public void build(PracticeArena a) {
        this.arena = a;
        // the platform the player stands on, and nothing underneath it: a miss is a fall into the void
        a.platform(0, PLATFORM_Y, 0, 2, Material.SANDSTONE);
        // Low walls on the Z ends only. They were on the X ends first, which is the axis the arrows come down - so
        // every shot hit a wall and no knock ever landed (measured: 19 arrows fired, 0 damage events). The shooting
        // axis has to stay clear.
        a.box(-3, PLATFORM_Y + 1, -3, 3, PLATFORM_Y + 2, -3, Material.SMOOTH_BRICK);
        a.box(-3, PLATFORM_Y + 1, 3, 3, PLATFORM_Y + 2, 3, Material.SMOOTH_BRICK);
        this.spawn = a.at(0.5, PLATFORM_Y + 1, 0.5, 0f);
    }

    @Override public Location spawn() { return spawn; }

    @Override public Location spectatorSpawn() { return arena.at(0.5, PLATFORM_Y + 6, 10.5, 0f); }

    @Override
    public void kit(Player p) {
        // blocks and nothing else: the drill is the clutch, not the fight
        p.getInventory().setItem(0, new ItemStack(Material.WOOL, 64));
        p.getInventory().setItem(1, new ItemStack(Material.SANDSTONE, 64));
    }

    @Override public void onJoin(PracticeGame g, Player p) { row(p); reset(p); }

    @Override
    public void tick(PracticeGame g) {
        ticker++;
        if (ticker % SHOT_EVERY == 0) {
            for (Player p : g.alivePlayers()) {
                if (g.isFollowing(p)) continue;
                if (knockedAt.containsKey(p.getUniqueId())) continue;   // already falling: let them work
                shoot(p);
            }
        }
        for (Player p : g.alivePlayers()) {
            if (g.isFollowing(p)) continue;
            Long at = knockedAt.get(p.getUniqueId());
            if (at == null) continue;
            double y = p.getLocation().getY();
            if (y < VOID_Y) {                                           // fell out without a save
                long[] r = row(p);
                r[2]++;
                r[4] = 0;
                p.sendMessage(Messages.get("practice.blockclutchdrill.missed", "p1", r[2]));
                p.playSound(p.getLocation(), Sound.NOTE_BASS, 0.6f, 0.7f);
                reset(p);
                p.teleport(spawn);
                continue;
            }
            boolean own = Boolean.TRUE.equals(placedSinceKnock.get(p.getUniqueId()));
            // a knock that did not take them off the edge is not a clutch: settle it as a miss so the drill moves on
            if (p.isOnGround() && y >= PLATFORM_Y - 0.2 && System.currentTimeMillis() - at > 2500) {
                long[] r = row(p);
                r[2]++;
                r[4] = 0;
                p.sendMessage(Messages.get("practice.blockclutchdrill.missed", "p1", r[2]));
                reset(p);
                p.teleport(spawn);
                continue;
            }
            if (p.isOnGround() && own && y < PLATFORM_Y) {              // landed on their own block: a save
                long[] r = row(p);
                long ms = System.currentTimeMillis() - at;
                double fell = knockedFrom.containsKey(p.getUniqueId())
                        ? knockedFrom.get(p.getUniqueId()) - y : 0;
                r[1]++;
                r[4]++;
                if (r[3] == 0 || ms < r[3]) r[3] = ms;
                String lethal = fell >= LETHAL_FALL
                        ? ChatColor.RED + " (" + String.format("%.1f", fell) + " blocks - that fall would have killed you)"
                        : ChatColor.GRAY + " (" + String.format("%.1f", fell) + " blocks, survivable)";
                p.sendMessage(Messages.get("practice.blockclutchdrill.saved", "p1", ms, "p2", r[1]) + lethal);
                p.playSound(p.getLocation(), Sound.LEVEL_UP, 0.7f, 1.6f);
                p.getInventory().addItem(new ItemStack(Material.WOOL, 32));
                reset(p);
                p.teleport(spawn);
            }
        }
    }

    /** Fire an arrow at the player from alternating sides; the hit is what starts the clutch. */
    private void shoot(Player p) {
        leftSide = !leftSide;
        double x = leftSide ? -9.5 : 9.5;
        Location from = new Location(p.getWorld(), x, p.getLocation().getY() + 1.2,
                p.getLocation().getZ() + (Math.random() * 4 - 2));
        Vector to = p.getLocation().add(0, 1.0, 0).toVector().subtract(from.toVector());
        Arrow arrow = p.getWorld().spawnArrow(from, to.normalize(), 2.6f, 0f);

        arrow.setShooter(p);          // its own arrow: the drill has no second player to fire it
        // the arrow's damage is not settable in 1.8's API; the hit is cancelled in onDamage instead, which is
        // also where the knockback is applied, so a zero-damage arrow is not needed to keep the player healthy
    }

    /**
     * The arrow connecting is what knocks the player off. Vanilla's arrow knockback is far too small to clear a
     * platform edge, so the shove is applied here - outward and up, the shape a sword hit gives, which is how a player
     * actually gets knocked off a bridge.
     */
    @Override
    public boolean onDamage(PracticeGame g, Player p, EntityDamageEvent e) {
        if (!(e instanceof EntityDamageByEntityEvent)) return false;
        Entity damager = ((EntityDamageByEntityEvent) e).getDamager();
        if (!(damager instanceof Projectile)) return false;
        e.setCancelled(true);                                      // no damage: the fall is the hazard
        if (knockedAt.containsKey(p.getUniqueId())) return true;    // already in the air; one knock per fall
        beginKnock(p);
        return true;
    }

    private void beginKnock(Player p) {
        knockedAt.put(p.getUniqueId(), System.currentTimeMillis());
        placedSinceKnock.put(p.getUniqueId(), false);
        knockedFrom.put(p.getUniqueId(), p.getLocation().getY());
        row(p)[0]++;
        // Push along X (the shooting axis), which is the direction the platform has no wall on. The platform is five
        // blocks wide and the walls are on Z, so a hit has to carry the player ~3 blocks in X to clear the edge -
        // hence a shove near a sprint hit's strength rather than a nudge, plus enough lift to leave the ground.
        double dir = p.getLocation().getX() >= 0 ? 1 : -1;
        Vector out = new Vector(dir * 0.95, 0.42, (Math.random() - 0.5) * 0.15);
        p.setVelocity(p.getVelocity().add(out));
        p.playSound(p.getLocation(), Sound.HURT_FLESH, 0.8f, 1.2f);
    }

    /** A block placed after the knock is what makes the landing a save. */
    @Override
    public void onPlace(PracticeGame g, Player p, Block b) {
        if (knockedAt.containsKey(p.getUniqueId())) placedSinceKnock.put(p.getUniqueId(), true);
    }

    /** Back to the platform, knock state cleared. */
    private void reset(Player p) {
        knockedAt.remove(p.getUniqueId());
        placedSinceKnock.remove(p.getUniqueId());
        knockedFrom.remove(p.getUniqueId());
        p.setFallDistance(0f);
    }

    @Override
    public String hud(PracticeGame g, Player p) {
        long[] r = row(p);
        boolean airborne = knockedAt.containsKey(p.getUniqueId());
        return ChatColor.AQUA + "Clutch" + ChatColor.DARK_GRAY + " | "
                + (airborne ? ChatColor.YELLOW + "FALLING" : ChatColor.GRAY + "ready")
                + ChatColor.DARK_GRAY + " | " + ChatColor.WHITE + r[1] + ChatColor.GRAY + " saved"
                + ChatColor.DARK_GRAY + ", " + ChatColor.WHITE + r[2] + ChatColor.GRAY + " missed"
                + ChatColor.DARK_GRAY + " | " + ChatColor.YELLOW + "Best " + ChatColor.WHITE
                + (r[3] == 0 ? "-" : r[3] + "ms")
                + ChatColor.DARK_GRAY + " | " + ChatColor.GREEN + "Knocks " + ChatColor.WHITE + r[0];
    }

    private long[] row(Player p) {
        long[] r = rows.get(p.getUniqueId());
        if (r == null) { r = new long[5]; rows.put(p.getUniqueId(), r); }
        return r;
    }

    @Override public String best(Player p) { long[] r = row(p); return r[3] == 0 ? "-" : r[3] + "ms"; }
    @Override public String completed(Player p) { return String.valueOf(row(p)[1]); }
    @Override public long bestMillis(Player p) { return row(p)[3]; }
    @Override public String blocksToPlace(Player p) { return "-"; }
}
