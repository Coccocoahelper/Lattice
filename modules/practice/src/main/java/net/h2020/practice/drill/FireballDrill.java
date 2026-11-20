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
    /** Blocks per tick down the lane. 0.55 is eleven blocks a second, about two seconds of travel. */
    private static final double FIREBALL_SPEED = 0.55;

    private PracticeArena arena;
    private Location spawn;
    private Location muzzle;
    private int next = 40;
    /** Fireballs launched this session, so a drill can be checked without watching the arena. */
    private int launched;
    private final Map<UUID, int[]> score = new HashMap<UUID, int[]>();   // {deflected, hit, streak, best}
    /**
     * Fireballs still in the air. One that misses flies past and never dies on its own - a long session left hundreds
     * of them drifting out of the arena, each one an entity the server keeps ticking.
     */
    private final java.util.List<Fireball> inFlight = new java.util.ArrayList<Fireball>();

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
        sweep();
        if (--next > 0) return;
        java.util.List<Player> alive = g.alivePlayers();
        if (alive.isEmpty()) { next = 20; return; }
        // pick from the participants, never from the world: getPlayers() counts spectators, and the two sizes are not
        // the same thing - the old guard could take the random branch for a one-player drill
        Player target = alive.get(alive.size() > 1 ? (int) (Math.random() * alive.size()) : 0);
        Fireball f = g.world().spawn(muzzle, Fireball.class);
        f.setIsIncendiary(false);
        f.setYield(1.0f);
        // aim at the chest, with a small lead: a fireball that always arrives on the same line is not practice
        Location t = target.getEyeLocation();
        double lead = 0.35 + Math.random() * 0.35;
        Vector dir = t.clone().add(target.getVelocity().multiply(lead)).toVector()
                .subtract(muzzle.toVector()).normalize();
        f.setDirection(dir);
        // 0.9 blocks a tick was eighteen blocks a second down a twenty-six block lane: a second and a half from
        // launch to your chest, which left no time to read the line. Slower is not easier to deflect, it is
        // actually practice - you get to see the approach and choose the swing.
        f.setVelocity(dir.multiply(FIREBALL_SPEED));
        inFlight.add(f);
        launched++;
        next = MIN_GAP + (int) (Math.random() * (MAX_GAP - MIN_GAP));
    }

    @Override public void onJoin(PracticeGame g, Player p) { score.put(p.getUniqueId(), new int[4]); }

    /** Drop the ones that are gone or have flown past the arena, and the per-player rows with their player. */
    private void sweep() {
        for (int i = inFlight.size() - 1; i >= 0; i--) {
            Fireball f = inFlight.get(i);
            if (!f.isValid()) { inFlight.remove(i); continue; }
            // a fireball past the player's end of the lane is not coming back
            if (f.getLocation().getZ() > spawn.getZ() + 6 || f.getTicksLived() > 200) {
                f.remove();
                inFlight.remove(i);
            }
        }
    }

    @Override
    public void onQuit(PracticeGame g, Player p) {
        score.remove(p.getUniqueId());
    }

    @Override
    public void cleanup(PracticeGame g) {
        for (Fireball f : inFlight) { try { f.remove(); } catch (Throwable ignored) { } }
        inFlight.clear();
        score.clear();
    }

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
        p.sendMessage(net.h2020.api.core.Messages.get("practice.fireballdrill.deflected-streak","p1",s[2]));
    }

    /** A fireball that reaches the player is a miss, but it costs health and a fall, not the session. */
    @Override
    public boolean onDamage(PracticeGame g, Player p, EntityDamageEvent e) {
        // Only a fireball counts against the drill. The lane has a rim and a fall, and a player who clipped the edge
        // used to lose their streak to fall damage - a miss the drill never fired.
        boolean fromFireball = false;
        if (e instanceof org.bukkit.event.entity.EntityDamageByEntityEvent) {
            org.bukkit.entity.Entity d = ((org.bukkit.event.entity.EntityDamageByEntityEvent) e).getDamager();
            fromFireball = d instanceof Fireball;
        }
        if (!fromFireball) return false;
        int[] s = row(p);
        s[1]++;
        s[2] = 0;
        p.sendMessage(net.h2020.api.core.Messages.get("practice.fireballdrill.hit-taken-this-session","p1",s[1]));
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
