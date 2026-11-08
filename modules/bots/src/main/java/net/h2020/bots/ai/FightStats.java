package net.h2020.bots.ai;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * -Dh2020.fightdebug=true: per-player melee telemetry (swings, hits, accuracy, hit distance, longest combo), printed
 * every 30 s. Used to tune how fighting bots feels against human norms (accuracy ~50-75%, hits at 2.6-3.0 blocks).
 */
final class FightStats implements Listener {
    static final boolean ENABLED = Boolean.getBoolean("h2020.fightdebug");

    private static final class Rec {
        int swings, hits, combo, bestCombo, timesHit;
        double distSum;
        UUID lastVictim;
    }

    private final Map<UUID, Rec> recs = new HashMap<>();
    private final Map<UUID, String> names = new HashMap<>();

    static void install(JavaPlugin plugin) {
        if (!ENABLED) return;
        final FightStats fs = new FightStats();
        Bukkit.getPluginManager().registerEvents(fs, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() { public void run() { fs.report(); } }, 600L, 600L);
    }

    private Rec rec(Player p) {
        names.put(p.getUniqueId(), p.getName());
        Rec r = recs.get(p.getUniqueId());
        if (r == null) recs.put(p.getUniqueId(), r = new Rec());
        return r;
    }

    /** Why do bots fall into the void? Log what each one was doing. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVoid(org.bukkit.event.entity.EntityDamageEvent e) {
        if (e.getCause() != org.bukkit.event.entity.EntityDamageEvent.DamageCause.VOID || !(e.getEntity() instanceof Player)) return;
        Player p = (Player) e.getEntity();
        net.h2020.bots.ai.brain.BaseBrain b = BotAI.brainOf(p);
        if (b == null || b.context() == null) return;
        Bukkit.getLogger().info("[falldebug] " + p.getName() + " " + b + " nav=" + b.context().nav.describe()
                + " style=" + b.context().persona.bridgeStyle + " lastHurt=" + (p.getLastDamageCause() == null ? "-" : p.getLastDamageCause().getCause()));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onGameDeath(net.h2020.api.game.events.GameKillEvent e) {
        Player p = e.victim();
        if (p == null || p.getLocation().getY() > 20) return; // only falls into the void
        net.h2020.bots.ai.brain.BaseBrain b = BotAI.brainOf(p);
        if (b == null || b.context() == null) return;
        Bukkit.getLogger().info("[falldebug] " + p.getName() + " " + b + " nav=" + b.context().nav.describe()
                + " style=" + b.context().persona.bridgeStyle + " killer=" + (e.killer() == null ? "-" : e.killer().getName()));
    }

    /** Item use: fireballs, pearls, arrows, eggs/snowballs, TNT, golden apples and potions. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onProjectile(org.bukkit.event.entity.ProjectileLaunchEvent e) {
        if (e.getEntity().getShooter() instanceof Player)
            Bukkit.getLogger().info("[itemdebug] " + ((Player) e.getEntity().getShooter()).getName() + " launched " + e.getEntity().getType());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTnt(org.bukkit.event.block.BlockPlaceEvent e) {
        if (e.getBlock().getType() == org.bukkit.Material.TNT) Bukkit.getLogger().info("[itemdebug] " + e.getPlayer().getName() + " placed TNT");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onConsume(org.bukkit.event.player.PlayerItemConsumeEvent e) {
        Bukkit.getLogger().info("[itemdebug] " + e.getPlayer().getName() + " consumed " + e.getItem().getType());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSwing(PlayerAnimationEvent e) { rec(e.getPlayer()).swings++; }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player) || !(e.getEntity() instanceof Player)) return;
        Player a = (Player) e.getDamager(), v = (Player) e.getEntity();
        Rec ra = rec(a), rv = rec(v);
        ra.hits++;
        ra.distSum += a.getEyeLocation().distance(v.getLocation().add(0, 0.9, 0));
        ra.combo = v.getUniqueId().equals(ra.lastVictim) ? ra.combo + 1 : 1;
        ra.lastVictim = v.getUniqueId();
        ra.bestCombo = Math.max(ra.bestCombo, ra.combo);
        rv.timesHit++;
        rv.combo = 0; // getting hit breaks your own combo
    }

    private void report() {
        int sw = 0, hi = 0; double ds = 0;
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<UUID, Rec> en : recs.entrySet()) {
            Rec r = en.getValue();
            if (r.swings < 10) continue;
            sw += r.swings; hi += r.hits; ds += r.distSum;
            sb.append(String.format(" %s:%d/%d(%.0f%%,%.2fb,c%d)", names.get(en.getKey()), r.hits, r.swings,
                    100.0 * r.hits / r.swings, r.hits == 0 ? 0 : r.distSum / r.hits, r.bestCombo));
        }
        if (sw > 0) Bukkit.getLogger().info(String.format("[fightdebug] total %d/%d hits/swings (%.0f%%) avg dist %.2f |%s",
                hi, sw, 100.0 * hi / sw, hi == 0 ? 0 : ds / hi, sb));
        recs.clear();
    }
}
