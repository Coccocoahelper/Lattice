package net.h2020.skywars.game;

import net.h2020.api.game.GameState;
import net.h2020.skywars.SkywarsModule;
import net.h2020.skywars.kit.Kit;
import net.h2020.skywars.kit.KitMenu;
import net.h2020.skywars.util.Items;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.block.Chest;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.ComplexEntityPart;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerPickupItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import net.h2020.api.H2020;
import net.h2020.api.bot.BotService;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.projectiles.ProjectileSource;

import java.util.Iterator;
import java.util.UUID;

/** All Bukkit events for every running SkyWars game, routed to the game the player is in. */
public final class SkywarsListener implements Listener {
    private final SkywarsModule module;

    public SkywarsListener(SkywarsModule module) { this.module = module; }

    private SkywarsGame game(Player p) { return p == null ? null : module.gameOf(p); }

    // ------------------------------------------------------------------ connection / watchdog
    @EventHandler(priority = EventPriority.LOW)
    public void onQuit(PlayerQuitEvent e) {
        SkywarsGame g = game(e.getPlayer());
        if (g != null) g.removePlayer(e.getPlayer());
        BotService bs = H2020.find(BotService.class);
        try { if (bs != null && bs.isBot(e.getPlayer())) module.forgetKit(e.getPlayer()); } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------------ damage
    /** Rules: no damage pre-game/ending/for spectators, no friendly fire, spawn fall protection; tracks last hits. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamageRules(EntityDamageEvent e) {
        Player attacker = null;
        if (e instanceof EntityDamageByEntityEvent) {
            attacker = attackerOf(((EntityDamageByEntityEvent) e).getDamager());
            SkywarsGame ag = game(attacker);
            if (ag != null && (ag.preGame() || !ag.isAlive(attacker))) { e.setCancelled(true); return; }
        }
        if (!(e.getEntity() instanceof Player)) return;
        Player victim = (Player) e.getEntity();
        SkywarsGame g = game(victim);
        if (g == null) return;
        if (g.state() != GameState.PLAYING || !g.isAlive(victim)) {
            e.setCancelled(true);
            if (e.getCause() == EntityDamageEvent.DamageCause.FIRE_TICK) victim.setFireTicks(0);
            return;
        }
        if (e.getCause() == EntityDamageEvent.DamageCause.FALL && g.fallProtected(victim)) { e.setCancelled(true); return; }
        if (attacker != null && !attacker.equals(victim)) {
            if (!g.contains(attacker) || g.sameTeam(victim, attacker)) { e.setCancelled(true); return; }
            g.recordHit(victim, attacker, e.getCause() == EntityDamageEvent.DamageCause.PROJECTILE);
        }
    }

    /** Lethal damage never kills: the game eliminates the player and turns them into a spectator. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLethal(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player)) return;
        Player victim = (Player) e.getEntity();
        SkywarsGame g = game(victim);
        if (g == null || g.state() != GameState.PLAYING || !g.isAlive(victim)) return;
        boolean isVoid = e.getCause() == EntityDamageEvent.DamageCause.VOID;
        if (!isVoid && victim.getHealth() - e.getFinalDamage() > 0) return;
        e.setCancelled(true);

        Entity damager = e instanceof EntityDamageByEntityEvent ? ((EntityDamageByEntityEvent) e).getDamager() : null;
        Player direct = attackerOf(damager);
        DeathCause cause = causeOf(e.getCause(), damager);
        Player killer = direct != null && !direct.equals(victim) ? direct : g.recentAttacker(victim);
        if (cause == DeathCause.DRAGON) killer = null;
        g.eliminate(victim, cause, killer, !isVoid, false);
    }

    /** Fallback for deaths that bypass damage events (/kill, plugins): vanilla drops, instant respawn as spectator. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent e) {
        final Player p = e.getEntity();
        SkywarsGame g = game(p);
        if (g == null) return;
        e.setDeathMessage(null);
        e.setKeepLevel(false);
        e.setDroppedExp(0);
        if (g.state() == GameState.PLAYING && g.isAlive(p)) {
            EntityDamageEvent last = p.getLastDamageCause();
            Entity damager = last instanceof EntityDamageByEntityEvent ? ((EntityDamageByEntityEvent) last).getDamager() : null;
            DeathCause cause = last == null ? DeathCause.OTHER : causeOf(last.getCause(), damager);
            Player killer = p.getKiller() != null ? p.getKiller() : g.recentAttacker(p);
            if (cause == DeathCause.VOID) e.getDrops().clear();
            g.eliminate(p, cause, killer, true, true);
        } else {
            e.getDrops().clear();
        }
        Bukkit.getScheduler().runTask(module.plugin(), new Runnable() {
            @Override public void run() { if (p.isOnline() && p.isDead()) p.spigot().respawn(); }
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent e) {
        final Player p = e.getPlayer();
        final SkywarsGame g = game(p);
        if (g == null || g.preGame()) return;
        e.setRespawnLocation(g.spectatorSpawn());
        Bukkit.getScheduler().runTask(module.plugin(), new Runnable() {
            @Override public void run() { if (p.isOnline() && g.contains(p)) g.afterRespawn(p); }
        });
    }

    @EventHandler(ignoreCancelled = true)
    public void onTarget(EntityTargetEvent e) {
        if (!(e.getTarget() instanceof Player)) return;
        SkywarsGame g = game((Player) e.getTarget());
        if (g != null && !g.isAlive((Player) e.getTarget())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onFood(FoodLevelChangeEvent e) {
        if (!(e.getEntity() instanceof Player)) return;
        Player p = (Player) e.getEntity();
        SkywarsGame g = game(p);
        if (g == null) return;
        if (g.state() != GameState.PLAYING || !g.isAlive(p)) {
            e.setCancelled(true);
            p.setFoodLevel(20);
        }
    }

    // ------------------------------------------------------------------ blocks
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        SkywarsGame g = game(e.getPlayer());
        if (g == null) {
            SkywarsGame wg = module.gameIn(e.getBlock().getWorld());
            if (wg != null && wg.isCageBlock(e.getBlock())) e.setCancelled(true);
            return;
        }
        if (g.preGame() || !g.isAlive(e.getPlayer()) || g.isCageBlock(e.getBlock())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        SkywarsGame g = game(e.getPlayer());
        if (g == null) return;
        if (g.preGame() || !g.isAlive(e.getPlayer())) { e.setCancelled(true); return; }
        if (!g.canBuildAt(e.getBlock().getLocation())) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(ChatColor.RED + "You can't place blocks here!");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucket(PlayerBucketEmptyEvent e) {
        SkywarsGame g = game(e.getPlayer());
        if (g == null) return;
        if (g.preGame() || !g.isAlive(e.getPlayer())) { e.setCancelled(true); return; }
        if (!g.canBuildAt(e.getBlockClicked().getRelative(e.getBlockFace()).getLocation())) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(ChatColor.RED + "You can't place blocks here!");
        }
    }

    // ------------------------------------------------------------------ items / menus
    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        SkywarsGame g = game(p);
        if (g == null) return;
        boolean right = e.getAction() == Action.RIGHT_CLICK_AIR || e.getAction() == Action.RIGHT_CLICK_BLOCK;
        ItemStack it = e.getItem();
        if (g.preGame()) {
            e.setCancelled(true);
            if (!right || it == null) return;
            if (Items.is(it, Material.BOW, SkywarsGame.KIT_SELECTOR)) g.openKitMenu(p);
            else if (Items.is(it, Material.BED, SkywarsGame.LEAVE)) g.leaveToLobby(p);
            return;
        }
        if (!g.isAlive(p)) {
            e.setCancelled(true);
            if (!right || it == null) return;
            if (Items.is(it, Material.COMPASS, SkywarsGame.TELEPORTER)) g.openTeleporter(p);
            else if (Items.is(it, Material.PAPER, SkywarsGame.PLAY_AGAIN)) g.playAgain(p);
            else if (Items.is(it, Material.BED, SkywarsGame.RETURN_TO_LOBBY)) g.leaveToLobby(p);
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        SkywarsGame g = game(e.getPlayer());
        if (g != null && (g.preGame() || !g.isAlive(e.getPlayer()))) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent e) {
        if (e.getPlayer() instanceof org.bukkit.entity.Player
                && net.h2020.api.core.ReplayState.isWatching(((org.bukkit.entity.Player) e.getPlayer()).getUniqueId())) return;
        if (!(e.getPlayer() instanceof Player)) return;
        Player p = (Player) e.getPlayer();
        SkywarsGame g = game(p);
        if (g == null || !g.isAlive(p)) return;
        InventoryHolder h = e.getInventory().getHolder();
        if (h instanceof Chest) g.markLooted(((Chest) h).getLocation());
        else if (h instanceof DoubleChest) {
            DoubleChest dc = (DoubleChest) h;
            if (dc.getLeftSide() instanceof Chest) g.markLooted(((Chest) dc.getLeftSide()).getLocation());
            if (dc.getRightSide() instanceof Chest) g.markLooted(((Chest) dc.getRightSide()).getLocation());
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player)) return;
        Player p = (Player) e.getWhoClicked();
        InventoryHolder h = e.getInventory().getHolder();
        if (h instanceof KitMenu) {
            e.setCancelled(true);
            SkywarsGame g = game(p);
            if (g == null || !g.preGame() || e.getRawSlot() >= e.getInventory().getSize()) return;
            if (KitMenu.isClose(e.getRawSlot())) { p.closeInventory(); return; }
            Kit k = ((KitMenu) h).kitAt(e.getRawSlot());
            if (k != null) { g.selectKit(p, k); p.closeInventory(); }
            return;
        }
        if (h instanceof TeleporterMenu) {
            e.setCancelled(true);
            SkywarsGame g = game(p);
            if (g == null || e.getRawSlot() >= e.getInventory().getSize()) return;
            UUID target = ((TeleporterMenu) h).targetAt(e.getRawSlot());
            if (target != null) { p.closeInventory(); g.spectate(p, target); }
            return;
        }
        SkywarsGame g = game(p);
        if (g != null && (g.preGame() || !g.isAlive(p))) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getWhoClicked() instanceof Player)) return;
        Player p = (Player) e.getWhoClicked();
        InventoryHolder h = e.getInventory().getHolder();
        SkywarsGame g = game(p);
        if (h instanceof KitMenu || h instanceof TeleporterMenu || (g != null && (g.preGame() || !g.isAlive(p)))) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onDrop(PlayerDropItemEvent e) {
        SkywarsGame g = game(e.getPlayer());
        if (g != null && (g.preGame() || !g.isAlive(e.getPlayer()))) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onPickup(PlayerPickupItemEvent e) {
        SkywarsGame g = game(e.getPlayer());
        if (g != null && (g.preGame() || !g.isAlive(e.getPlayer()))) e.setCancelled(true);
    }

    // ------------------------------------------------------------------ spectator chat
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent e) {
        Player p = e.getPlayer();
        SkywarsGame g = game(p);
        if (g == null || g.preGame() || g.state() == GameState.ENDING || g.isAlive(p)) return;
        e.setFormat(ChatColor.GRAY + "[SPECTATOR] " + e.getFormat());
        try {
            Iterator<Player> it = e.getRecipients().iterator();
            while (it.hasNext()) {
                Player r = it.next();
                if (g.contains(r) && g.isAlive(r)) it.remove();
            }
        } catch (UnsupportedOperationException ignored) {
            // recipients immutable for this event; leave it
        }
    }

    // ------------------------------------------------------------------ helpers
    static Player attackerOf(Entity damager) {
        if (damager instanceof Player) return (Player) damager;
        if (damager instanceof Projectile) {
            ProjectileSource s = ((Projectile) damager).getShooter();
            if (s instanceof Player) return (Player) s;
        }
        return null;
    }

    static DeathCause causeOf(EntityDamageEvent.DamageCause c, Entity damager) {
        if (damager instanceof EnderDragon || damager instanceof ComplexEntityPart) return DeathCause.DRAGON;
        switch (c) {
            case VOID: return DeathCause.VOID;
            case FALL: return DeathCause.FALL;
            case FIRE: case FIRE_TICK: case LAVA: return DeathCause.FIRE;
            case ENTITY_EXPLOSION: case BLOCK_EXPLOSION: return DeathCause.EXPLOSION;
            case ENTITY_ATTACK: return DeathCause.MELEE;
            case PROJECTILE: return damager instanceof Arrow ? DeathCause.BOW : DeathCause.PROJECTILE;
            default: return DeathCause.OTHER;
        }
    }
}
