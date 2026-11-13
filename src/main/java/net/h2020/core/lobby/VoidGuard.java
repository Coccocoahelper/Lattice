package net.h2020.core.lobby;

import net.h2020.api.H2020;
import net.h2020.api.game.Game;
import net.h2020.api.game.GameManager;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.logging.Logger;

/**
 * Keeps people in the hub: walk off the edge of a lobby and you are put back on it, the way every Hypixel lobby has
 * always worked.
 *
 * <p>It is a sweep rather than a move check on purpose. A {@code PlayerMoveEvent} handler runs for every player on every
 * movement packet, which is the busiest event on the server; four times a second over the online list costs nothing and
 * cannot be outrun, because nobody falls thirty blocks in a quarter of a second.
 *
 * <p>Only hubs are guarded. A player in a game is left alone - falling there is a mechanic, not a mistake - and so is a
 * spectator, who is meant to be able to fly anywhere. Damage from falling and from the void is cancelled in a hub too,
 * so the rescue never arrives with broken legs.
 */
public final class VoidGuard implements Listener {

    /** How far below a hub's floor counts as "out of the world". */
    private static final double MARGIN = 25;
    /** A rescue cannot repeat for the same body inside this window, so a failed teleport cannot become a loop. */
    private static final long COOLDOWN_MS = 1000L;

    private final JavaPlugin plugin;
    private final LobbyManager lobby;
    private BukkitTask task;
    private final java.util.Map<java.util.UUID, Long> rescued = new java.util.concurrent.ConcurrentHashMap<java.util.UUID, Long>();
    private final java.util.Map<java.util.UUID, Long> joinedAt = new java.util.concurrent.ConcurrentHashMap<java.util.UUID, Long>();

    public VoidGuard(JavaPlugin plugin, LobbyManager lobby) {
        this.plugin = plugin;
        this.lobby = lobby;
    }

    public void start() {
        if (task != null) return;
        task = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            @Override public void run() { sweep(); }
        }, 40L, 4L);
    }

    public void stop() {
        if (task != null) { task.cancel(); task = null; }
    }

    /** One pass over the online players: anybody below a hub's floor goes back to that hub's spawn. */
    private void sweep() {
        Logger log = plugin.getLogger();
        GameManager gm = H2020.find(GameManager.class);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!p.isOnline() || p.isDead()) continue;
            // a body that has just spawned has not been placed yet and sits at y=0 for a tick. The age comes from the
            // join event, not from getTicksLived(): a fake player's entity never ages, so that check skipped every bot
            // for ever and the guard silently stopped working on them.
            Long since = joinedAt.get(p.getUniqueId());
            if (since != null && System.currentTimeMillis() - since < 1500L) continue;
            GameMode mode = p.getGameMode();
            if (mode == GameMode.SPECTATOR || mode == GameMode.CREATIVE) continue;
            World w = p.getWorld();
            if (w == null || !lobby.isLobby(w)) continue;
            // a game on a hub world (a waiting lobby) handles its own falls
            Game g = gm == null ? null : gm.gameOf(p);
            if (g != null) continue;
            Location spawn = lobby.spawn(w);
            if (spawn == null) spawn = w.getSpawnLocation();
            double floor = floorOf(w, spawn);
            if (p.getLocation().getY() > floor) continue;
            Long last = rescued.get(p.getUniqueId());
            if (last != null && System.currentTimeMillis() - last < COOLDOWN_MS) continue;
            rescued.put(p.getUniqueId(), System.currentTimeMillis());
            double fellFrom = p.getLocation().getY();
            p.teleport(spawn);
            p.setFallDistance(0f);
            p.setFireTicks(0);
            p.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
            log.info("[void] " + p.getName() + " fell out of " + w.getName() + " from y=" + Math.round(fellFrom)
                    + " (floor " + Math.round(floor) + "), returned to the hub spawn at y=" + Math.round(spawn.getY()));
        }
    }

    /**
     * Where a hub's world ends. The anchor is the lower of the hub's own spawn pad and the world spawn, because a slime
     * hub's level.dat carries the map's original spawn height (the bedwars hub reports 103 while everybody stands at
     * 72), and anchoring on that alone put the floor above the players' feet and rescued them where they stood.
     */
    private static double floorOf(World w, Location hubSpawn) {
        double anchor = Math.min(w.getSpawnLocation().getY(), hubSpawn.getY());
        return Math.max(1, anchor - MARGIN);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(org.bukkit.event.player.PlayerJoinEvent e) {
        joinedAt.put(e.getPlayer().getUniqueId(), System.currentTimeMillis());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent e) {
        joinedAt.remove(e.getPlayer().getUniqueId());
        rescued.remove(e.getPlayer().getUniqueId());
    }

    /** Falling in a hub costs nothing: the guard is already on its way, and a rescue with damage is not a rescue. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player)) return;
        Player p = (Player) e.getEntity();
        if (!lobby.isLobby(p.getWorld())) return;
        GameManager gm = H2020.find(GameManager.class);
        if (gm != null && gm.gameOf(p) != null) return;
        EntityDamageEvent.DamageCause cause = e.getCause();
        if (cause == EntityDamageEvent.DamageCause.FALL || cause == EntityDamageEvent.DamageCause.VOID) {
            e.setCancelled(true);
            p.setFallDistance(0f);
        }
    }
}
