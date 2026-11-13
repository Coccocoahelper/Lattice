package net.h2020.practice.drill;

import net.h2020.practice.PracticeArena;
import net.h2020.practice.PracticeGame;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Zombie;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Bow aiming: targets at four ranges, and the drill only counts the arrow that actually hits one.
 *
 * <p>Bed Wars bow fights are decided at 15 to 35 blocks, where the drop is real and leading a moving player matters, so
 * the targets are spread across exactly that band and each band is scored on its own. A hit is counted from the
 * projectile, not from the bow, so a shot that clips a target while falling still counts - which is what a player
 * means by a good shot.
 */
public final class BowDrill implements Drill {

    private static final int[] RANGES = {10, 18, 26, 34};

    private PracticeArena arena;
    private Location spawn;
    private final List<Zombie> targets = new ArrayList<Zombie>();
    private final Map<UUID, int[]> score = new HashMap<UUID, int[]>();   // {hits, shots, longest, byRange0..3}
    private int respawnClock;

    @Override public String id() { return "bow"; }
    @Override public String display() { return "Bow Aiming"; }

    @Override public String hint() {
        return "four targets at 10, 18, 26 and 34 blocks - hit as many as you can";
    }

    @Override
    public void build(PracticeArena a) {
        this.arena = a;
        a.platform(0, 64, 0, 3, Material.SANDSTONE);
        a.rim(0, 64, 0, 3, 1, Material.SMOOTH_BRICK);
        this.spawn = a.at(0.5, 65, 0.5, 180f);
        int r = 0;
        for (int range : RANGES) {
            // a plinth at the range, with the target standing on it: the plinth is what makes the distance readable
            a.platform(range, 64, 0, 1, Material.OBSIDIAN);
            r++;
        }
    }

    @Override public Location spawn() { return spawn; }
    @Override public Location spectatorSpawn() { return arena.at(0.5, 71, 10.5, 180f); }

    @Override
    public void kit(Player p) {
        ItemStack bow = new ItemStack(Material.BOW);
        ItemMeta m = bow.getItemMeta();
        m.setDisplayName(ChatColor.AQUA + "Practice Bow");
        m.addEnchant(Enchantment.ARROW_INFINITE, 1, true);
        m.addEnchant(Enchantment.ARROW_DAMAGE, 1, true);
        bow.setItemMeta(m);
        p.getInventory().setItem(0, bow);
        p.getInventory().setItem(9, new ItemStack(Material.ARROW, 1));
    }

    @Override
    public void start(PracticeGame g) {
        spawnTargets(g);
    }

    /** One armour stand per range, standing on its plinth, so a hit is a hit and not a block break. */
    private void spawnTargets(PracticeGame g) {
        for (Zombie s : targets) if (!s.isDead()) s.remove();
        targets.clear();
        for (int i = 0; i < RANGES.length; i++) targets.add(stand(g, i));
    }

    /** A no-AI zombie on its plinth: an arrow that reaches it is a hit, and it cannot walk off the marker. */
    private Zombie stand(PracticeGame g, int i) {
        Location at = arena.at(RANGES[i] + 0.5, 65, 0.5, 180f);
        Zombie z = g.world().spawn(at, Zombie.class);
        // this API has no setAI/setGravity/setSilent, so the target is pinned by the drill instead: see tick()
        z.setCanPickupItems(false);
        z.setRemoveWhenFarAway(false);
        z.setCustomName(ChatColor.YELLOW + "" + RANGES[i] + "m");
        z.setCustomNameVisible(true);
        ItemStack helmet = new ItemStack(Material.WOOL, 1, (short) (i == 0 ? 14 : i == 1 ? 4 : i == 2 ? 5 : 10));
        z.getEquipment().setHelmet(helmet);
        z.getEquipment().setHelmetDropChance(0f);
        return z;
    }

    @Override
    public void tick(PracticeGame g) {
        // pin every target to its range marker: without setAI the only way to keep a mob on a plinth is to hold it
        // there, and a target that wanders would silently change the distance it is meant to test
        for (Zombie z : targets) {
            if (z.isDead()) continue;
            Location anchor = arena.at(RANGES[targets.indexOf(z)] + 0.5, 65, 0.5, 180f);
            if (z.getLocation().distanceSquared(anchor) > 0.04) z.teleport(anchor);
            z.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
        }
        if (++respawnClock % 20 != 0) return;
        // drop the ones that are gone, then put back exactly the missing ones. Rebuilding the whole set whenever a
        // single target died is what produced 981 armour stands in the first run of this drill.
        java.util.Iterator<Zombie> it = targets.iterator();
        while (it.hasNext()) if (it.next().isDead()) it.remove();
        for (int i = targets.size(); i < RANGES.length; i++) targets.add(stand(g, i));
    }

    @Override public void onJoin(PracticeGame g, Player p) { score.put(p.getUniqueId(), new int[7]); }

    /** Arrow hit: count the range band it came from. */
    public void onArrowHit(PracticeGame g, Player shooter, Arrow arrow, Entity hit) {
        if (!(hit instanceof Zombie)) return;
        int[] s = row(shooter);
        s[0]++;
        double dist = arrow.getLocation().distance(shooter.getLocation());
        if (dist > s[2]) s[2] = (int) Math.round(dist);
        int band = bandOf(arrow.getLocation());
        if (band >= 0) s[3 + band]++;
        shooter.playSound(shooter.getLocation(), Sound.SUCCESSFUL_HIT, 0.7f, 1.4f);
        shooter.sendMessage(ChatColor.GREEN + "Hit" + ChatColor.GRAY + " at " + ChatColor.WHITE
                + Math.round(dist) + "m" + ChatColor.GRAY + "  (" + s[0] + " hits, "
                + Math.round(100.0 * s[0] / Math.max(1, s[1])) + "% of shots)");
        // the target steps back: an armour stand that stays put turns into a memorised angle
        if (hit instanceof Zombie) { targets.remove(hit); ((Zombie) hit).remove(); }
    }

    /** A shot fired, counted on the bow draw rather than on the arrow, so misses cost accuracy like they should. */
    public void onShoot(PracticeGame g, Player p) {
        row(p)[1]++;
    }

    private int bandOf(Location arrow) {
        double x = Math.abs(arrow.getX());
        int best = -1;
        double bestDiff = Double.MAX_VALUE;
        for (int i = 0; i < RANGES.length; i++) {
            double d = Math.abs(RANGES[i] - x);
            if (d < bestDiff && d <= 4) { bestDiff = d; best = i; }
        }
        return best;
    }

    @Override
    public String hud(PracticeGame g, Player p) {
        int[] s = row(p);
        int pct = s[1] == 0 ? 0 : (int) Math.round(100.0 * s[0] / s[1]);
        return ChatColor.GREEN + "Hits " + ChatColor.WHITE + s[0] + ChatColor.DARK_GRAY + "/"
                + ChatColor.WHITE + s[1] + ChatColor.DARK_GRAY + " (" + pct + "%)"
                + ChatColor.DARK_GRAY + " | " + ChatColor.YELLOW + "Longest " + ChatColor.WHITE + s[2] + "m";
    }

    @Override
    public List<String> stats(PracticeGame g, Player p) {
        int[] s = row(p);
        List<String> out = new ArrayList<String>();
        out.add("hits " + s[0] + " of " + s[1] + " shots" + (s[1] > 0
                ? " (" + Math.round(100.0 * s[0] / s[1]) + "%)" : ""));
        out.add("longest hit " + s[2] + "m");
        StringBuilder bands = new StringBuilder("by range: ");
        for (int i = 0; i < RANGES.length; i++) {
            if (i > 0) bands.append(", ");
            bands.append(RANGES[i]).append("m ").append(s[3 + i]);
        }
        out.add(bands.toString());
        return out;
    }

    @Override
    public void cleanup(PracticeGame g) {
        for (Zombie s : targets) if (!s.isDead()) s.remove();
        targets.clear();
    }

    private int[] row(Player p) { return score.computeIfAbsent(p.getUniqueId(), k -> new int[7]); }
}
