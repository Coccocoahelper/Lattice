package net.h2020.practice.drill;

import net.h2020.practice.PracticeArena;
import net.h2020.practice.PracticeGame;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.enchantments.Enchantment;
import net.h2020.api.bot.Bot;
import org.bukkit.entity.Player;
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

    /**
     * The bot targets are fake players, not zombies.
     *
     * <p>A zombie is not a valid target for the combat stack: it engages a {@link Player}, so a mob on a plinth is
     * invisible to the bow draw, and the drill reported Hits 0/0 with a bot standing six blocks away holding a bow.
     * A fake player is a real Player as far as every code path is concerned, so the bracket works exactly as it does
     * with a person shooting at a person - which is what the drill is for.
     */
    private final java.util.List<Bot> targetBots = new java.util.ArrayList<Bot>();

    private static final int[] RANGES = {10, 18, 26, 34};

    private PracticeArena arena;
    private Location spawn;
    /**
     * Targets by band, not a flat list. The list version respawned "the missing ones" by index, so killing the 18m
     * target put a 34m one back and the drill silently lost a band: the player's 18m column stopped moving and there
     * was no way to tell from the HUD.
     */
    private final Player[] targets = new Player[RANGES.length];
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
        // slot 1, not 9: the second row is not a hotbar slot, and a bot's combat stack only considers the hotbar, so
        // a drill bot with its only arrow in slot 9 could never draw
        p.getInventory().setItem(1, new ItemStack(Material.ARROW, 1));
    }

    @Override
    public void start(PracticeGame g) {
        spawnTargets(g);
    }

    /** One fake player per range, on its plinth. */
    private void spawnTargets(PracticeGame g) {
        despawnTargets();
        net.h2020.api.bot.BotService bs = net.h2020.api.H2020.find(net.h2020.api.bot.BotService.class);
        if (bs == null) return;
        for (int i = 0; i < RANGES.length; i++) {
            try {
                net.h2020.api.bot.BotProfile prof = bs.randomProfile();
                net.h2020.api.bot.Bot bot = bs.spawn(prof, arena.at(RANGES[i] + 0.5, 65, 0.5, 180f));
                if (bot == null || bot.player() == null) continue;
                bot.setBrain(null);              // a target stands still: it is not fighting back in this drill
                Player pl = bot.player();
                pl.setCustomName(ChatColor.YELLOW + "" + RANGES[i] + "m");
                pl.setCustomNameVisible(true);
                targets[i] = pl;
                targetBots.add(bot);
            } catch (Throwable t) {
                net.h2020.api.H2020.plugin().getLogger().warning("[bow] target " + RANGES[i] + "m: " + t);
            }
        }
    }

    /** Take the target bodies out of the arena; they are bots, so they have to be removed, not killed. */
    private void despawnTargets() {
        for (Bot b : new java.util.ArrayList<Bot>(targetBots)) {
            try { b.remove(); } catch (Throwable ignored) { }
        }
        targetBots.clear();
        for (int i = 0; i < targets.length; i++) targets[i] = null;
    }

    /** A no-AI zombie on its plinth: an arrow that reaches it is a hit, and it cannot walk off the marker. */
    @Override
    public void tick(PracticeGame g) {
        // pin every target to its range marker: without setAI the only way to keep a mob on a plinth is to hold it
        // there, and a target that wanders would silently change the distance it is meant to test
        for (int i = 0; i < targets.length; i++) {
            Player z = targets[i];
            if (z == null || !z.isOnline()) continue;
            Location anchor = arena.at(RANGES[i] + 0.5, 65, 0.5, 180f);
            if (z.getLocation().distanceSquared(anchor) > 0.04) z.teleport(anchor);
            z.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
        }
        if (++respawnClock % 20 != 0) return;
        // a body that died or disconnected goes back on its own plinth
        for (int i = 0; i < targets.length; i++) {
            if (targets[i] == null || !targets[i].isOnline()) {
                net.h2020.api.bot.BotService bs = net.h2020.api.H2020.find(net.h2020.api.bot.BotService.class);
                if (bs == null) continue;
                try {
                    net.h2020.api.bot.Bot bot = bs.spawn(bs.randomProfile(), arena.at(RANGES[i] + 0.5, 65, 0.5, 180f));
                    if (bot == null || bot.player() == null) continue;
                    bot.setBrain(null);
                    bot.player().setCustomName(ChatColor.YELLOW + "" + RANGES[i] + "m");
                    bot.player().setCustomNameVisible(true);
                    targets[i] = bot.player();
                    targetBots.add(bot);
                } catch (Throwable ignored) { }
            }
        }
    }

    @Override public void onJoin(PracticeGame g, Player p) { score.put(p.getUniqueId(), new int[7]); }

    /** Arrow hit: count the range band it came from. */
    public void onArrowHit(PracticeGame g, Player shooter, Arrow arrow, Entity hit) {
        // only a target body counts, not any player who happens to be standing in the lane
        int bandHit = -1;
        for (int i = 0; i < targets.length; i++) if (targets[i] == hit) bandHit = i;
        if (bandHit < 0) return;
        int[] s = row(shooter);
        s[0]++;
        double dist = arrow.getLocation().distance(shooter.getLocation());
        if (dist > s[2]) s[2] = (int) Math.round(dist);
        int band = bandOf(arrow.getLocation());
        if (band >= 0) s[3 + band]++;
        shooter.playSound(shooter.getLocation(), Sound.SUCCESSFUL_HIT, 0.7f, 1.4f);
        shooter.sendMessage(net.h2020.api.core.Messages.get("practice.bowdrill.hit-at-m-hits","p1",Math.round(dist),"p2",s[0],"p3",Math.round(100.0 * s[0] / Math.max(1, s[1]))));
        // the target steps back: an armour stand that stays put turns into a memorised angle
        // the target steps back: a body that stays put turns into a memorised angle. It is a bot, so it is removed
        // and respawned on its own plinth by tick().
        targets[bandHit] = null;
        for (net.h2020.api.bot.Bot b : new java.util.ArrayList<net.h2020.api.bot.Bot>(targetBots)) {
            if (b.player() == hit) { b.remove(); targetBots.remove(b); break; }
        }
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
        despawnTargets();
    }

    private int[] row(Player p) { return score.computeIfAbsent(p.getUniqueId(), k -> new int[7]); }
}
