package net.h2020.practice.drill;

import net.h2020.practice.PracticeArena;
import net.h2020.practice.PracticeGame;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Fireball;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Fireball deflecting: fireballs come down a lane at you and you knock them back with the sword.
 *
 * <p>This is the mechanic that decides most Bed Wars fights at range, and it is pure timing - the fireball has to be
 * hit while it is still far enough that the return does not land on you. The drill counts a deflect when the fireball
 * is damaged by the player before it reaches them, and a hit when one detonates on them, so the HUD is a direct
 * readout of the skill.
 */
public final class FireballDrill implements Drill {

    private static final int LANE = 26;          // blocks from the launcher to the player
    private static final int MIN_GAP = 26;       // ticks between fireballs at the calmest
    private static final int MAX_GAP = 46;

    private PracticeArena arena;
    private Location spawn;
    private Location muzzle;
    private int next = 40;
    /** Fireballs launched this session, so a drill can be checked without watching the arena. */
    private int launched;
    private final Map<UUID, int[]> score = new HashMap<UUID, int[]>();   // {deflected, hit, streak, best}

    @Override public String id() { return "fireball"; }
    @Override public String display() { return "Fireball Deflecting"; }

    @Override public String hint() {
        return "fireballs come down the lane - hit them with the sword before they reach you";
    }

    @Override
    public void build(PracticeArena a) {
        this.arena = a;
        int z0 = 0, z1 = -LANE;
        // a tongue of ground to stand on, and one out at the far end for the launcher
        a.platform(0, 64, z0, 3, Material.SANDSTONE);
        a.platform(0, 64, z1, 2, Material.OBSIDIAN);
        // a low rim so a deflected fireball has something to hit and the lane reads as a lane
        a.rim(0, 64, (z0 + z1) / 2, 4, 1, Material.SMOOTH_BRICK);
        this.spawn = a.at(0.5, 65, z0 + 0.5, 0f);
        this.muzzle = a.at(0.5, 66.2, z1 + 0.5);
    }

    @Override public Location spawn() { return spawn; }
    @Override public Location spectatorSpawn() { return arena.at(0.5, 72, 8.5, 0f); }

    @Override
    public void kit(Player p) {
        ItemStack sword = new ItemStack(Material.IRON_SWORD);
        ItemMeta m = sword.getItemMeta();
        m.setDisplayName(ChatColor.AQUA + "Deflector");
        m.addEnchant(Enchantment.DURABILITY, 10, true);
        sword.setItemMeta(m);
        p.getInventory().setItem(0, sword);
    }

    @Override
    public void start(PracticeGame g) {
        next = 30;
    }

    @Override
    public void tick(PracticeGame g) {
        if (--next > 0) return;
        java.util.List<Player> alive = g.alivePlayers();
        if (alive.isEmpty()) { next = 20; return; }
        Player target = alive.get(g.world().getPlayers().size() > 1
                ? (int) (Math.random() * alive.size()) : 0);
        Fireball f = g.world().spawn(muzzle, Fireball.class);
        f.setIsIncendiary(false);
        f.setYield(1.0f);
        // aim at the chest, with a small lead: a fireball that always arrives on the same line is not practice
        Location t = target.getEyeLocation();
        double lead = 0.35 + Math.random() * 0.35;
        Vector dir = t.clone().add(target.getVelocity().multiply(lead)).toVector()
                .subtract(muzzle.toVector()).normalize();
        f.setDirection(dir);
        f.setVelocity(dir.multiply(0.9));
        launched++;
        next = MIN_GAP + (int) (Math.random() * (MAX_GAP - MIN_GAP));
    }

    @Override public void onJoin(PracticeGame g, Player p) { score.put(p.getUniqueId(), new int[4]); }

    /** A fireball damaged by the player is a deflect: send it back the way it came. */
    @Override
    public void onAttack(PracticeGame g, Player p, Entity target) {
        if (!(target instanceof Fireball)) return;
        Fireball f = (Fireball) target;
        if (f.getLocation().distanceSquared(p.getLocation()) > 36) return;   // out of reach: not a deflect
        Vector back = f.getLocation().toVector().subtract(p.getEyeLocation().toVector()).normalize();
        f.setDirection(back);
        f.setVelocity(back.multiply(1.4));
        f.setYield(0f);
        int[] s = row(p);
        s[0]++;
        s[2]++;
        if (s[2] > s[3]) s[3] = s[2];
        p.playSound(p.getLocation(), Sound.ITEM_BREAK, 0.6f, 1.6f);
        p.sendMessage(ChatColor.AQUA + "Deflected" + ChatColor.GRAY + "  streak " + ChatColor.WHITE + s[2]);
    }

    /** A fireball that reaches the player is a miss, but it costs health and a fall, not the session. */
    @Override
    public boolean onDamage(PracticeGame g, Player p, EntityDamageEvent e) {
        int[] s = row(p);
        s[1]++;
        s[2] = 0;
        p.sendMessage(ChatColor.RED + "Hit" + ChatColor.GRAY + "  " + ChatColor.WHITE + s[1]
                + ChatColor.GRAY + " taken this session");
        if (p.getHealth() - e.getDamage() <= 0) {
            // never let the drill kill: the point is the timing, not surviving a bad roll
            e.setCancelled(true);
            p.setHealth(p.getMaxHealth());
        }
        return false;
    }

    @Override
    public void onFall(PracticeGame g, Player p) {
        int[] s = row(p);
        s[2] = 0;
    }

    @Override
    public String hud(PracticeGame g, Player p) {
        int[] s = row(p);
        return ChatColor.AQUA + "Deflected " + ChatColor.WHITE + s[0]
                + ChatColor.DARK_GRAY + " | " + ChatColor.RED + "Hit " + ChatColor.WHITE + s[1]
                + ChatColor.DARK_GRAY + " | " + ChatColor.YELLOW + "Streak " + ChatColor.WHITE + s[2]
                + ChatColor.DARK_GRAY + " (best " + s[3] + ")";
    }

    @Override
    public java.util.List<String> stats(PracticeGame g, Player p) {
        int[] s = row(p);
        java.util.List<String> out = new java.util.ArrayList<String>();
        int total = s[0] + s[1];
        out.add("fireballs launched " + launched);
        out.add("deflected " + s[0] + " of " + total + (total > 0
                ? " (" + Math.round(100.0 * s[0] / total) + "%)" : ""));
        out.add("best streak " + s[3]);
        return out;
    }

    private int[] row(Player p) { return score.computeIfAbsent(p.getUniqueId(), k -> new int[4]); }
}
