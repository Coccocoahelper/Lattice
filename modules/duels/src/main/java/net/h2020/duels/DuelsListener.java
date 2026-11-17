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

    DuelsListener(DuelsModule module) { this.module = module; }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player) || !(e.getDamager() instanceof Player)) return;
        Player victim = (Player) e.getEntity();
        Player attacker = (Player) e.getDamager();
        DuelsGame g = module.gameOf(victim);
        if (g == null || g != module.gameOf(attacker) || !g.roundLive()) return;
        DuelsMode mode = g.mode();
        if (mode.rangedOnly) {
            // melee does nothing in a bow duel; the arrow is the whole game
            e.setCancelled(true);
            return;
        }
        if (mode.win() == DuelsMode.Win.HITS) {
            // Boxing deals no damage, but the hit still has to land: cancelling the event kills the knockback as well
            // as the health, which is the opposite of Hypixel's boxing (a mode made of combos). Zero the damage and
            // keep the hit.
            e.setDamage(0.0);
            g.onHit(attacker, victim);
            return;
        }
        if (mode.sumo) {
            // sumo: keep the knockback, drop the damage
            e.setDamage(0.0);
            // a punch that does nothing still has to shove, or a sumo duel is two players nudging each other
            org.bukkit.util.Vector push = victim.getLocation().toVector().subtract(attacker.getLocation().toVector());
            push.setY(0);
            if (push.lengthSquared() > 1e-6) {
                push.normalize().multiply(0.55);
                push.setY(0.36);
                victim.setVelocity(victim.getVelocity().add(push));
            }
        }
        g.onHit(attacker, victim);
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
