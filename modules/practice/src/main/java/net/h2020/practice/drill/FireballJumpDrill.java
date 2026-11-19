package net.h2020.practice.drill;

import net.h2020.api.core.Messages;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Fireball;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import net.h2020.practice.PracticeArena;
import net.h2020.practice.PracticeGame;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Fireball jumping: throw one at your feet and ride the blast.
 *
 * <p>The move is what saves a bridge fight you were losing - a fireball into the block ahead, jump as it goes off, and
 * the knockback carries you up and forward instead of into the void. Get it wrong and you are the one launched off the
 * map, which is why the drill is a gap and not a wall: the only way across is a good one.
 *
 * <p>Scored on the thing that matters: crossings. A jump that gains height but lands you back where you started is a
 * wasted fireball, so it counts in the hud and not on the score.
 */
public final class FireballJumpDrill implements Drill {

    private static final int GAP = 7;              // blocks of nothing between the two platforms
    private static final int PLATFORM = 3;         // radius of each platform

    private PracticeArena arena;
    private Location spawn;
    private Location landing;
    private final Map<UUID, long[]> rows = new HashMap<UUID, long[]>();      // {start, crossings, boosts, best, done}
    private final Map<UUID, Long> lastThrow = new HashMap<UUID, Long>();
    /** Fireballs in flight, kept at the Bed Wars constant speed: vanilla 1.8 adds 0.1 a tick otherwise. */
    private final List<Fireball> held = new ArrayList<Fireball>();

    @Override public String id() { return "fireballjump"; }
    @Override public String display() { return "Fireball Jumping"; }

    @Override public String hint() {
        return "fireball the block ahead, jump with the blast, land on the far side";
    }

    @Override
    public void build(PracticeArena a) {
        this.arena = a;
        a.platform(0, 64, 0, PLATFORM, Material.SANDSTONE);
        a.platform(0, 64, -(GAP + PLATFORM * 2), PLATFORM, Material.SANDSTONE);
        // a rim on the far side so a good jump has something to aim at, and the gap reads as a gap
        a.rim(0, 64, -(GAP + PLATFORM * 2), PLATFORM, 1, Material.SMOOTH_BRICK);
        this.spawn = a.at(0.5, 65, 0.5, 0f);
        this.landing = a.at(0.5, 65, -(GAP + PLATFORM * 2) + 0.5, 0f);
    }

    @Override public Location spawn() { return spawn; }
    @Override public Location spectatorSpawn() { return arena.at(0.5, 74, 8.5, 0f); }

    @Override
    public void kit(Player p) {
        p.getInventory().setItem(0, new ItemStack(Material.FIREBALL, 16));
        p.getInventory().setItem(8, new ItemStack(Material.SANDSTONE, 16));
    }

    @Override public void onJoin(PracticeGame g, Player p) { row(p); }

    /** Right-click with a fireball: launch it the way Hypixel does, along the look vector. */
    @Override
    public void onInteract(PracticeGame g, Player p, ItemStack item) {
        if (item == null || item.getType() != Material.FIREBALL) return;
        if (p.getLocation().getZ() < -(GAP + PLATFORM * 2) - 2) { p.teleport(spawn); return; }   // already across
        // exactly what a Bed Wars fireball is: same yield, same constant speed, same cooldown. A drill that throws a
        // different projectile teaches a different jump.
        Fireball f = p.launchProjectile(Fireball.class);
        f.setIsIncendiary(false);
        f.setYield(2.0f);
        Vector dir = p.getEyeLocation().getDirection().normalize();
        f.setDirection(dir);
        f.setVelocity(dir.clone().multiply(net.h2020.api.game.Projectiles.FIREBALL));
        held.add(f);
        lastThrow.put(p.getUniqueId(), System.currentTimeMillis());
        p.playSound(p.getLocation(), Sound.GHAST_FIREBALL, 0.7f, 1.2f);
        if (item.getAmount() <= 1) p.setItemInHand(null);
        else { item.setAmount(item.getAmount() - 1); p.setItemInHand(item); }
    }

    @Override
    public void tick(PracticeGame g) {
        for (int i = held.size() - 1; i >= 0; i--) {
            Fireball f = held.get(i);
            if (!f.isValid()) { held.remove(i); continue; }
            Vector v = f.getVelocity();
            double len = v.length();
            if (len > 1e-4) f.setVelocity(v.multiply(net.h2020.api.game.Projectiles.FIREBALL / len));
        }
        for (Player p : g.alivePlayers()) {
            if (g.isFollowing(p)) continue;
            long[] r = row(p);
            long now = System.currentTimeMillis();

            // a boost: thrown within the last two seconds and moving up fast without a jump having been pressed
            Long thrown = lastThrow.get(p.getUniqueId());
            if (thrown != null && now - thrown < 2000 && p.getVelocity().getY() > 0.42
                    && now - r[0] > 250) {          // one boost per launch: without the window a single throw counted
                                                     // twice whenever the climb stayed fast for two ticks
                if (r[0] == 0) r[0] = now;
                r[2]++;
                p.playSound(p.getLocation(), Sound.SUCCESSFUL_HIT, 0.6f, 1.8f);
            }

            // a crossing: standing on the far platform
            if (p.getLocation().getZ() <= -(GAP + PLATFORM * 2) + PLATFORM + 0.5 && p.isOnGround()) {
                long ms = r[0] > 0 ? now - r[0] : 0;
                r[1]++;
                r[4]++;
                if (r[3] == 0 || (ms > 0 && ms < r[3])) r[3] = ms;
                p.sendMessage(Messages.get("practice.fireballjumpdrill.crossed", "p1", ms, "p2", r[1]));
                p.playSound(p.getLocation(), Sound.LEVEL_UP, 0.7f, 1.6f);
                r[0] = 0;
                p.teleport(spawn);
            }
        }
    }

    @Override public void onFall(PracticeGame g, Player p) { row(p)[0] = 0; p.teleport(spawn); }

    @Override
    public String hud(PracticeGame g, Player p) {
        long[] r = row(p);
        return ChatColor.AQUA + "Fireball jump" + ChatColor.DARK_GRAY + " | " + ChatColor.WHITE + r[1]
                + ChatColor.GRAY + " crossed" + ChatColor.DARK_GRAY + ", " + ChatColor.WHITE + r[2] + " boosts"
                + ChatColor.DARK_GRAY + " | " + ChatColor.YELLOW + "Best " + ChatColor.WHITE
                + (r[3] == 0 ? "-" : r[3] + "ms")
                + ChatColor.DARK_GRAY + " | " + ChatColor.GREEN + "Done " + ChatColor.WHITE + r[4];
    }

    private long[] row(Player p) {
        long[] r = rows.get(p.getUniqueId());
        if (r == null) { r = new long[5]; rows.put(p.getUniqueId(), r); }
        return r;
    }

    @Override public String best(Player p) { long[] r = row(p); return r[3] == 0 ? "-" : r[3] + "ms"; }
    @Override public String completed(Player p) { return String.valueOf(row(p)[4]); }
    @Override public long bestMillis(Player p) { return row(p)[3]; }
    @Override public String blocksToPlace(Player p) { return "-"; }
}
