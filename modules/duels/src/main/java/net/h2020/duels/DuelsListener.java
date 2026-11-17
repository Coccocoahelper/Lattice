package net.h2020.duels;

import net.h2020.api.H2020;
import net.h2020.api.game.GameState;
import net.h2020.api.game.events.GameStateChangeEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The rules a duel needs from Bukkit: hits, deaths, falls and quitting.
 *
 * <p>Every mode's differences are read off {@link DuelsMode} here rather than duplicated per mode, and the events do
 * nothing at all outside a live round - a duel's lobby is a normal world with normal players in it, and cancelling
 * damage there would leak into everything else on the server.
 */
public final class DuelsListener implements Listener {

    private final DuelsModule module;
    /** Last fall velocity per player, so a knockback can be told from a walk-off. */
    private final Map<UUID, Long> lastKnock = new HashMap<UUID, Long>();
    /** Health before a hit that must not cost any, restored at MONITOR once vanilla has applied it. */
    private final Map<UUID, Double> healthBefore = new HashMap<UUID, Double>();

    DuelsListener(DuelsModule module) { this.module = module; }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player) || !(e.getDamager() instanceof Player)) return;
        Player victim = (Player) e.getEntity();
        Player attacker = (Player) e.getDamager();
        DuelsGame g = module.gameOf(victim);
        if (g == null || g != module.gameOf(attacker) || !g.roundLive()) return;
        DuelsMode mode = g.mode();
        // Every mode lets the hit land. Bed Wars and SkyWars never touch the event to make a hit "count" - they let
        // vanilla apply it, which is what produces the hurt animation, the knockback and the 10-tick hit delay, and
        // then read the result. Doing it the other way round is what made a duel feel like PvP was off: a hit with
        // setDamage(0) or setCancelled(true) is a hit the client never sees land, so there is no hurt animation, no
        // knockback and no delay, and the opponent looks unhittable.
        if (mode.rangedOnly) {
            // A bow duel is decided by the bow alone. The melee hit is refused, but only after vanilla would have
            // applied it, and a refused hit must not look like a landed one.
            e.setCancelled(true);
            return;
        }
        // Boxing and Sumo deal no lasting damage, and the way to get that is NOT to zero or cancel the hit. Vanilla
        // applies a hit as a package: the health drop (which the client renders as the red hurt flash), the knockback,
        // and the ten-tick invulnerability window that is the hit delay. Reduce any one of them and the package falls
        // apart - a zero-damage hit shows no hurt animation and slips past the delay, a cancelled hit also loses the
        // knockback. So the hit is applied in full and the health is put back afterwards, which is exactly the shape
        // Bed Wars and SkyWars use when a hit must not kill.
        boolean noHealthLoss = mode.win() == DuelsMode.Win.HITS || mode.sumo;
        if (noHealthLoss) healthBefore.put(victim.getUniqueId(), victim.getHealth());
        g.onHit(attacker, victim);
    }

    /**
     * Put the health back after a Boxing or Sumo hit. Registered at MONITOR so vanilla has already applied the damage,
     * the knockback and the hurt timer: the victim saw and felt the hit, and simply did not lose the health.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDamageRestore(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player)) return;
        final Player p = (Player) e.getEntity();
        final Double before = healthBefore.remove(p.getUniqueId());
        if (before == null) return;
        // One tick later, not here: inside the event the server has not written the new health yet, so setting it now
        // is overwritten by the apply step that follows. Scheduling it puts the restore after the hit has really
        // landed - the client has already been sent the hurt flash and the knockback by then.
        org.bukkit.Bukkit.getScheduler().runTask(module.plugin(), new Runnable() {
            @Override public void run() {
                if (!p.isOnline() || p.isDead()) return;
                DuelsGame g = module.gameOf(p);
                if (g != null && g.state() == net.h2020.api.game.GameState.ENDING) return;
                if (p.getHealth() <= 0) return;
                p.setHealth(Math.min(before, p.getMaxHealth()));
            }
        });
    }

    /**
     * Boxing and Sumo keep a full hunger bar. Neither mode has food in its kit, so a bar that drains is a bar that can
     * never be refilled: the fight would slow down into starvation sprinting and then stop, which is not a duel. The
     * bar is pinned rather than topped up on a timer so no frame ever shows it empty.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onFood(FoodLevelChangeEvent e) {
        if (!(e.getEntity() instanceof Player)) return;
        Player p = (Player) e.getEntity();
        DuelsGame g = module.gameOf(p);
        if (g == null || !g.keepsHungerFull()) return;
        net.h2020.api.stats.CombatStats.inc("duels.hunger.kept");
        e.setCancelled(true);
        if (p.getFoodLevel() != 20) p.setFoodLevel(20);
        if (p.getSaturation() < 5f) p.setSaturation(5f);
    }

    /** A player who dies in a live duel loses the round, whoever is credited. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        DuelsGame g = module.gameOf(p);
        if (g == null || !g.roundLive()) return;
        e.setDeathMessage(null);
        e.setKeepInventory(true);
        g.onDeath(p);
    }

    /**
     * Sumo's fall, and the border's reminder. A sumo player touching the void under the platform has lost; the game
     * does the same check on a timer, so a body that never fires a move event is still caught.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        DuelsGame g = module.gameOf(e.getPlayer());
        if (g == null || !g.roundLive()) return;
        if (e.getTo().getY() > DuelsArena.FLOOR_Y - 3) return;
        if (!g.isSumo()) return;
        Player p = e.getPlayer();
        Player other = g.opponentOf(p);
        if (other != null) g.onDeath(p);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onLateDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player)) return;
        Player p = (Player) e.getEntity();
        DuelsGame g = module.gameOf(p);
        if (g == null || !g.roundLive()) return;
        if (e.getCause() == EntityDamageEvent.DamageCause.VOID) {
            // the void is a loss, not a death by fall damage: resolve it here so a body in the void does not sit
            // there taking damage until it drops
            g.onDeath(p);
            return;
        }
        if (g.mode().sumo) e.setCancelled(true);          // sumo platform: no fall damage, only the fall itself
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        DuelsGame g = module.gameOf(e.getPlayer());
        if (g != null) g.removePlayer(e.getPlayer());
    }

    /** A duel that has reached PLAYING is not joinable, so the queue must not keep filling it. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onState(GameStateChangeEvent e) {
        if (!(e.game() instanceof DuelsGame)) return;
        if (e.to() == GameState.PLAYING) module.markStarted((DuelsGame) e.game());
        if (e.to() == GameState.ENDING) module.unregister((DuelsGame) e.game());
    }

    /** Remember a knock for the fall-vs-knockback distinction the scoreboard and the recap use. */
    void noteKnock(Player p) { lastKnock.put(p.getUniqueId(), System.currentTimeMillis()); }

    boolean knockedRecently(Player p) {
        Long t = lastKnock.get(p.getUniqueId());
        return t != null && System.currentTimeMillis() - t < 4000L;
    }

    /** Unused hook kept for the knockback vector a Sumo punch should carry on 1.8. */
    static Vector knockback(double dx, double dz) {
        Vector v = new Vector(dx, 0.36, dz).normalize().multiply(0.42);
        v.setY(0.36);
        return v;
    }
}
