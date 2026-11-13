package net.h2020.bedwars;

import net.h2020.api.stats.CombatStats;
import net.h2020.api.game.GameState;
import net.h2020.api.game.TeamColor;
import net.h2020.bedwars.shop.ItemShop;
import net.h2020.bedwars.shop.ShopCategory;
import net.h2020.bedwars.shop.UpgradeShop;
import net.h2020.bedwars.util.Items;
import net.h2020.bedwars.util.Menu;
import net.h2020.bedwars.world.ShopNpc;
import net.h2020.bedwars.world.TeamForge;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Egg;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Fireball;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Silverfish;
import org.bukkit.entity.Snowball;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.Villager;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.ExplosionPrimeEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerEggThrowEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPickupItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.weather.WeatherChangeEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.projectiles.ProjectileSource;

import java.util.Iterator;

/** The single module-wide listener: resolves the game by world (or player) and applies Bed Wars rules. */
final class GameListener implements Listener {
    private final BedwarsModule module;

    GameListener(BedwarsModule module) { this.module = module; }

    private BedwarsGame at(org.bukkit.World w) { return module.gameAt(w); }

    // =================================================================== blocks

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        BedwarsGame g = at(e.getBlock().getWorld());
        if (g == null) return;
        Player p = e.getPlayer();
        BwPlayer bp = g.data(p);
        if (g.state != GameState.PLAYING || bp == null || !bp.playing()) { e.setCancelled(true); return; }
        if (!net.h2020.api.game.GameRules.of(g.type.family()).build) { e.setCancelled(true); return; }
        Block b = e.getBlockPlaced();
        String deny = g.placeDenial(b);
        if (deny != null) {
            e.setCancelled(true);
            p.sendMessage(deny);
            return;
        }
        g.markPlaced(b);
        if (b.getType() == Material.TNT) g.gadgets.placeTnt(b, p);
        else if (b.getType() == Material.SPONGE) g.gadgets.sponge(b);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        BedwarsGame g = at(e.getBlock().getWorld());
        if (g == null) return;
        Player p = e.getPlayer();
        BwPlayer bp = g.data(p);
        if (g.state != GameState.PLAYING || bp == null || !bp.playing()) { e.setCancelled(true); return; }
        if (!net.h2020.api.game.GameRules.of(g.type.family()).breakBlocks) { e.setCancelled(true); return; }
        Block b = e.getBlock();
        if (b.getType() == Material.BED_BLOCK) {
            e.setCancelled(true);
            BwTeam owner = g.bedOwner(b);
            if (owner == null) return;
            if (owner == bp.team) {
                net.h2020.api.core.Messages.send(p, "bedwars.gamelistener.you-can-t-destroy");
                return;
            }
            g.destroyBed(owner, p);
            return;
        }
        if (!g.isPlaced(b)) {
            e.setCancelled(true);
            net.h2020.api.core.Messages.send(p, "bedwars.gamelistener.you-can-only-break");
            return;
        }
        g.unmarkPlaced(b);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucket(PlayerBucketEmptyEvent e) {
        final Player p = e.getPlayer();
        BedwarsGame g = at(p.getWorld());
        if (g == null) return;
        BwPlayer bp = g.data(p);
        if (g.state != GameState.PLAYING || bp == null || !bp.playing()) { e.setCancelled(true); return; }
        Block target = e.getBlockClicked().getRelative(e.getBlockFace());
        String deny = g.placeDenial(target);
        if (deny != null) {
            e.setCancelled(true);
            p.sendMessage(deny);
            return;
        }
        g.markPlaced(target);
        // the bucket is used up (Hypixel doesn't give the empty bucket back)
        Bukkit.getScheduler().runTask(module.plugin(), new Runnable() {
            @Override public void run() {
                ItemStack hand = p.getItemInHand();
                if (hand != null && hand.getType() == Material.BUCKET) p.setItemInHand(null);
                p.getInventory().remove(Material.BUCKET);
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent e) {
        if (at(e.getPlayer().getWorld()) != null) e.setCancelled(true);
    }

    /**
     * Punching a fireball back. In 1.8 a fireball is not a damageable entity, so a left click on it produces no attack
     * event - only the arm swing. That swing is what Hypixel's deflect is built on, and it is what the bots send.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSwing(PlayerAnimationEvent e) {
        BedwarsGame g = at(e.getPlayer().getWorld());
        if (g == null || g.state != GameState.PLAYING) return;
        if (!g.gadgets.hasLiveFireball()) return;
        BwPlayer bp = g.data(e.getPlayer());
        if (bp == null || !bp.playing()) return;
        g.gadgets.tryDeflect(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) {
        BedwarsGame g = at(e.getLocation().getWorld());
        if (g == null) return;
        e.setYield(0);
        Entity ent = e.getEntity();
        boolean dragon = ent instanceof EnderDragon || (ent != null && g.dragons.isDragon(ent));
        Iterator<Block> it = e.blockList().iterator();
        while (it.hasNext()) {
            Block b = it.next();
            if (g.state != GameState.PLAYING || b.getType() == Material.BED_BLOCK) { it.remove(); continue; }
            if (dragon) { g.unmarkPlaced(b); continue; }
            if (!g.isPlaced(b) || blastProof(b.getType())) { it.remove(); continue; }
            g.unmarkPlaced(b);
        }
        if (ent instanceof Fireball) g.gadgets.forgetFireball((Fireball) ent);
        if (!dragon && g.state == GameState.PLAYING && (ent instanceof Fireball || ent instanceof TNTPrimed)) {
            g.gadgets.explosionKnockback(e.getLocation(), ent instanceof Fireball);
        }
    }

    private static boolean blastProof(Material m) {
        return m == Material.STAINED_GLASS || m == Material.GLASS || m == Material.OBSIDIAN || m == Material.ENDER_STONE;
    }

    @EventHandler(ignoreCancelled = true)
    public void onPrime(ExplosionPrimeEvent e) {
        if (at(e.getEntity().getWorld()) != null) e.setFire(false);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) { if (at(e.getBlock().getWorld()) != null) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent e) {
        if (at(e.getBlock().getWorld()) != null && e.getCause() != BlockIgniteEvent.IgniteCause.FLINT_AND_STEEL) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent e) { if (at(e.getBlock().getWorld()) != null) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onDecay(LeavesDecayEvent e) { if (at(e.getBlock().getWorld()) != null) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onFade(BlockFadeEvent e) { if (at(e.getBlock().getWorld()) != null) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onChangeBlock(EntityChangeBlockEvent e) {
        if (at(e.getBlock().getWorld()) != null && (e.getEntity() instanceof Silverfish || e.getEntity() instanceof EnderDragon)) e.setCancelled(true);
    }

    // =================================================================== damage

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        Entity ent = e.getEntity();
        BedwarsGame g = at(ent.getWorld());
        if (g == null) return;
        if (ent instanceof Player && e instanceof EntityDamageByEntityEvent
                && ((EntityDamageByEntityEvent) e).getDamager() instanceof Player
                && !net.h2020.api.game.GameRules.of(g.type.family()).pvp) {
            e.setCancelled(true);
            return;
        }
        if (ent instanceof Player) {
            Combat.onPlayerDamage(g, (Player) ent, e);
            return;
        }
        ShopNpc npc = g.npcOf(ent);
        if (npc != null || ent instanceof ArmorStand || ent instanceof Villager || g.dragons.isDragon(ent)
                || ent instanceof org.bukkit.entity.ComplexEntityPart) {
            e.setCancelled(true);
            if (npc != null && e instanceof EntityDamageByEntityEvent && ((EntityDamageByEntityEvent) e).getDamager() instanceof Player) {
                openNpc(g, (Player) ((EntityDamageByEntityEvent) e).getDamager(), npc);
            }
            return;
        }
        Gadgets.OwnedMob m = g.gadgets.mob(ent);
        if (m != null && e instanceof EntityDamageByEntityEvent) {
            Entity d = ((EntityDamageByEntityEvent) e).getDamager();
            Player attacker = null;
            if (d instanceof Player) attacker = (Player) d;
            else if (d instanceof Projectile && ((Projectile) d).getShooter() instanceof Player) attacker = (Player) ((Projectile) d).getShooter();
            if (attacker != null) {
                BwPlayer a = g.data(attacker);
                if (a == null || !a.playing() || a.team == m.team) e.setCancelled(true);
            } else {
                Gadgets.OwnedMob other = g.gadgets.mob(d);
                if (other != null && other.team == m.team) e.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTarget(EntityTargetEvent e) {
        BedwarsGame g = at(e.getEntity().getWorld());
        if (g == null) return;
        Gadgets.OwnedMob m = g.gadgets.mob(e.getEntity());
        if (m != null) {
            if (e.getTarget() != null && !g.gadgets.validTarget(m, e.getTarget())) e.setCancelled(true);
            return;
        }
        BwTeam dragonTeam = g.dragons.teamOf(e.getEntity());
        if (dragonTeam != null && e.getTarget() instanceof Player) {
            BwPlayer bp = g.data((Player) e.getTarget());
            if (bp == null || !bp.playing() || bp.team == dragonTeam) e.setCancelled(true);
        }
    }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent e) {
        if (e.getEntity() instanceof Player) return;
        if (at(e.getEntity().getWorld()) == null) return;
        e.getDrops().clear();
        e.setDroppedExp(0);
    }

    /** Safety net: something killed a player for real (e.g. /kill). Treat it as a Bed Wars death. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerDeath(PlayerDeathEvent e) {
        final Player p = e.getEntity();
        final BedwarsGame g = at(p.getWorld());
        if (g == null) return;
        e.getDrops().clear();
        e.setDroppedExp(0);
        e.setDeathMessage(null);
        final BwPlayer bp = g.data(p);
        final EntityDamageEvent.DamageCause cause = p.getLastDamageCause() == null ? EntityDamageEvent.DamageCause.CUSTOM
                : p.getLastDamageCause().getCause();
        Bukkit.getScheduler().runTask(module.plugin(), new Runnable() {
            @Override public void run() {
                if (!p.isOnline()) return;
                if (p.isDead()) p.spigot().respawn();
                if (bp != null && bp.playing() && g.state == GameState.PLAYING) g.handleDeath(bp, cause);
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onRespawn(PlayerRespawnEvent e) {
        BedwarsGame g = module.gameOf(e.getPlayer());
        if (g == null) return;
        boolean lobby = g.state == GameState.WAITING || g.state == GameState.STARTING;
        e.setRespawnLocation(lobby ? g.waitingLobby : g.spectatorSpawn);
    }

    @EventHandler(ignoreCancelled = true)
    public void onFood(FoodLevelChangeEvent e) {
        if (at(e.getEntity().getWorld()) == null) return;
        e.setCancelled(true);
        if (e.getEntity() instanceof Player) ((Player) e.getEntity()).setFoodLevel(20);
    }

    // =================================================================== interaction

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        BedwarsGame g = at(p.getWorld());
        if (g == null) return;
        BwPlayer bp = g.data(p);
        ItemStack item = e.getItem();
        Action a = e.getAction();
        boolean right = a == Action.RIGHT_CLICK_AIR || a == Action.RIGHT_CLICK_BLOCK;
        if (bp == null) { e.setCancelled(true); return; }
        if (a == Action.LEFT_CLICK_BLOCK && e.getClickedBlock() != null
                && (e.getClickedBlock().getType() == Material.CHEST || e.getClickedBlock().getType() == Material.ENDER_CHEST)
                && g.state == GameState.PLAYING && bp.playing()) {
            TeamChest.deposit(g, bp, e.getClickedBlock(), p);
            e.setCancelled(true);
            return;
        }
        if (right && item != null && item.getType() == Material.PAPER && item.hasItemMeta()
                && item.getItemMeta().hasDisplayName() && item.getItemMeta().getDisplayName().contains("Play Again")) {
            e.setCancelled(true);
            net.h2020.api.core.PlayAgain.requeue(p);
            return;
        }
        if (g.state == GameState.WAITING || g.state == GameState.STARTING) {
            e.setCancelled(true);
            if (right && item != null && item.getType() == Material.BED) g.leaveToLobby(p, false);
            return;
        }
        if (!bp.playing() || g.state != GameState.PLAYING) {
            e.setCancelled(true);
            if (right && item != null) {
                if (item.getType() == Material.COMPASS && bp.eliminated) SpectatorMenu.open(g, p);
                else if (item.getType() == Material.PAPER && bp.eliminated) g.leaveToLobby(p, true);
                else if (item.getType() == Material.BED) g.leaveToLobby(p, false);
            }
            return;
        }
        if (right && item != null) {
            if (item.getType() == Material.FIREBALL) {
                e.setCancelled(true);
                g.gadgets.launchFireball(p);
                return;
            }
            if (item.getType() == Material.CHEST && item.hasItemMeta() && item.getItemMeta().hasDisplayName()
                    && item.getItemMeta().getDisplayName().contains("Pop-up Tower")) {
                e.setCancelled(true);
                if (a != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null) return;
                Location at = e.getClickedBlock().getRelative(e.getBlockFace()).getLocation();
                byte wool = bp.team == null ? 0 : bp.team.color.woolData();
                net.h2020.bedwars.world.PopUpTower.build(module.plugin(), p, at, wool, p.getLocation().getYaw());
                consumeHand(p);
                net.h2020.api.stats.CombatStats.inc("tower.used");
                return;
            }
            if (item.getType() == Material.MONSTER_EGG) {
                e.setCancelled(true);
                if (a == Action.RIGHT_CLICK_BLOCK && e.getClickedBlock() != null) {
                    Location at = e.getClickedBlock().getRelative(e.getBlockFace()).getLocation().add(0.5, 0, 0.5);
                    g.gadgets.spawnDreamDefender(at, p);
                    consumeHand(p);
                }
                return;
            }
        }
        if (a == Action.RIGHT_CLICK_BLOCK && e.getClickedBlock() != null) {
            Block b = e.getClickedBlock();
            switch (b.getType()) {
                case BED_BLOCK:
                case WORKBENCH: case FURNACE: case BURNING_FURNACE: case ENCHANTMENT_TABLE: case ANVIL:
                case BREWING_STAND: case BEACON: case HOPPER: case DROPPER: case DISPENSER: case CAULDRON:
                    e.setUseInteractedBlock(Event.Result.DENY);
                    break;
                case CHEST: case TRAPPED_CHEST: {
                    BwTeam owner = chestOwner(g, b.getLocation());
                    if (owner != null && owner != bp.team && owner.alive()) {
                        e.setCancelled(true);
                        p.sendMessage(ChatColor.RED + "You cannot open this chest as the " + owner.coloredName()
                                + " Team " + ChatColor.RED + "is not eliminated!");
                    }
                    break;
                }
                default: break;
            }
        }
    }

    /** Team chests sit next to each island spawn. */
    private static BwTeam chestOwner(BedwarsGame g, Location chest) {
        BwTeam best = null;
        double bestD = 12 * 12;
        for (BwTeam t : g.teams) {
            if (t.spawn == null) continue;
            double d = t.spawn.distanceSquared(chest);
            if (d < bestD) { bestD = d; best = t; }
        }
        return best;
    }

    /** Wooden sword / pickaxe / axe: the tools the permanent upgrades are built on. */
    private static boolean isTierOneTool(ItemStack i) {
        if (i == null) return false;
        Material m = i.getType();
        return m == Material.WOOD_SWORD || m == Material.WOOD_PICKAXE || m == Material.WOOD_AXE;
    }

    private static void consumeHand(Player p) {
        ItemStack hand = p.getItemInHand();
        if (hand == null) return;
        if (hand.getAmount() <= 1) p.setItemInHand(null);
        else { hand.setAmount(hand.getAmount() - 1); p.setItemInHand(hand); }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        Player p = e.getPlayer();
        BedwarsGame g = at(p.getWorld());
        if (g == null) return;
        Entity t = e.getRightClicked();
        ShopNpc npc = g.npcOf(t);
        if (npc != null || t instanceof ArmorStand || t instanceof Villager) e.setCancelled(true);
        BwPlayer bp = g.data(p);
        if (bp == null || !bp.playing()) { e.setCancelled(true); return; }
        if (npc != null) openNpc(g, p, npc);
    }

    private static void openNpc(BedwarsGame g, Player p, ShopNpc npc) {
        BwPlayer bp = g.data(p);
        if (bp == null || !bp.playing() || g.state != GameState.PLAYING) return;
        if (npc.kind() == ShopNpc.Kind.ITEM_SHOP) ItemShop.open(g, p, ShopCategory.QUICK_BUY);
        else UpgradeShop.open(g, p);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteractAt(PlayerInteractAtEntityEvent e) {
        if (at(e.getPlayer().getWorld()) != null && e.getRightClicked() instanceof ArmorStand) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent e) {
        if (at(e.getPlayer().getWorld()) != null) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onHanging(HangingBreakEvent e) { if (at(e.getEntity().getWorld()) != null) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onBed(PlayerBedEnterEvent e) { if (at(e.getPlayer().getWorld()) != null) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent e) {
        final Player p = e.getPlayer();
        BedwarsGame g = at(p.getWorld());
        if (g == null) return;
        BwPlayer bp = g.data(p);
        if (bp == null || !bp.playing()) { e.setCancelled(true); return; }
        Material m = e.getItem().getType();
        if (m == Material.MILK_BUCKET) {
            // Magic Milk: trap immunity, keeps potion effects
            e.setCancelled(true);
            consumeHand(p);
            bp.trapImmuneUntil = System.currentTimeMillis() + 30000;
            p.playSound(p.getLocation(), Sound.BURP, 0.5f, 1f);
        } else if (m == Material.POTION) {
            Bukkit.getScheduler().runTask(module.plugin(), new Runnable() {
                @Override public void run() { p.getInventory().remove(Material.GLASS_BOTTLE); }
            });
        }
    }

    // =================================================================== projectiles

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onLaunch(ProjectileLaunchEvent e) {
        Projectile pr = e.getEntity();
        BedwarsGame g = at(pr.getWorld());
        if (g == null) return;
        ProjectileSource s = pr.getShooter();
        if (!(s instanceof Player)) return;
        Player p = (Player) s;
        BwPlayer bp = g.data(p);
        if (bp == null || !bp.playing() || g.state != GameState.PLAYING) { e.setCancelled(true); return; }
        if (pr instanceof Egg) g.gadgets.bridgeEgg((Egg) pr, p);
    }

    @EventHandler
    public void onHit(ProjectileHitEvent e) {
        Projectile pr = e.getEntity();
        BedwarsGame g = at(pr.getWorld());
        if (g == null || g.state != GameState.PLAYING) return;
        if (pr instanceof Snowball && pr.getShooter() instanceof Player) {
            Player p = (Player) pr.getShooter();
            BwPlayer bp = g.data(p);
            if (bp != null && bp.playing()) g.gadgets.spawnBedbug(pr.getLocation(), p);
        }
    }

    @EventHandler
    public void onEggThrow(PlayerEggThrowEvent e) {
        if (at(e.getPlayer().getWorld()) != null) e.setHatching(false);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPearl(PlayerTeleportEvent e) {
        if (e.getCause() != PlayerTeleportEvent.TeleportCause.ENDER_PEARL) return;
        BedwarsGame g = at(e.getFrom().getWorld());
        if (g == null) return;
        BwPlayer bp = g.data(e.getPlayer());
        if (bp == null || !bp.playing() || g.state != GameState.PLAYING) e.setCancelled(true);
    }

    // =================================================================== items / inventory

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        Player p = e.getPlayer();
        BedwarsGame g = at(p.getWorld());
        if (g == null) return;
        BwPlayer bp = g.data(p);
        if (bp == null || !bp.playing() || g.state != GameState.PLAYING) { e.setCancelled(true); return; }
        Material m = e.getItemDrop().getItemStack().getType();
        // the kit (sword, armour, compass) cannot be handed around, but tools can: shears and pickaxes/axes are how
        // a teammate gets a tool, and they can be put into chests with them too
        if (m == Material.WOOD_SWORD || m == Material.COMPASS || Items.isArmor(m)) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPickup(PlayerPickupItemEvent e) {
        Player p = e.getPlayer();
        BedwarsGame g = at(p.getWorld());
        if (g == null) return;
        BwPlayer bp = g.data(p);
        if (bp == null || !bp.playing() || g.state != GameState.PLAYING) { e.setCancelled(true); return; }
        Item it = e.getItem();
        if (g.soloMode() || bp.team == null || !it.hasMetadata(TeamForge.META)) return;
        String key = null;
        for (MetadataValue v : it.getMetadata(TeamForge.META)) if (v.getOwningPlugin() == module.plugin()) key = v.asString();
        if (key == null || !key.equals(bp.team.color.name())) return;
        // Hypixel "iron splitting": teammates standing on the forge receive a copy
        BwTeam team = g.team(TeamColor.valueOf(key));
        if (team == null || team.generator == null) return;
        int amount = it.getItemStack().getAmount() - e.getRemaining();
        if (amount <= 0) return;
        Location forge = team.generator;
        for (Player m : team.members) {
            if (m == p) continue;
            BwPlayer bm = g.data(m);
            if (bm == null || !bm.playing()) continue;
            Location l = m.getLocation();
            if (l.getWorld() != forge.getWorld()) continue;
            double dx = l.getX() - forge.getX(), dz = l.getZ() - forge.getZ(), dy = l.getY() - forge.getY();
            if (dx * dx + dz * dz <= 2.2 * 2.2 && dy > -1 && dy < 2.5) {
                ItemStack copy = new ItemStack(it.getItemStack().getType(), amount);
                for (ItemStack left : m.getInventory().addItem(copy).values()) m.getWorld().dropItem(l, left);
                m.playSound(l, Sound.ITEM_PICKUP, 0.2f, 1.6f);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player)) return;
        Player p = (Player) e.getWhoClicked();
        Inventory top = e.getView().getTopInventory();
        if (top != null && top.getHolder() instanceof Menu) {
            e.setCancelled(true);
            final int raw = e.getRawSlot();
            if (raw >= 0 && raw < top.getSize()) {
                final Menu menu = (Menu) top.getHolder();
                final Player clicker = p;
                final org.bukkit.event.inventory.ClickType type = e.getClick();
                // handled next tick: opening/closing windows inside the click event is unsafe in 1.8
                Bukkit.getScheduler().runTask(module.plugin(), new Runnable() {
                    @Override public void run() {
                        if (clicker.isOnline() && clicker.getOpenInventory().getTopInventory().getHolder() == menu) {
                            menu.handle(clicker, raw, type);
                            clicker.updateInventory();
                        }
                    }
                });
            }
            return;
        }
        BedwarsGame g = at(p.getWorld());
        if (g == null) return;
        BwPlayer bp = g.data(p);
        if (bp == null || !bp.playing() || g.state != GameState.PLAYING) { e.setCancelled(true); return; }
        InventoryType.SlotType st = e.getSlotType();
        if (st == InventoryType.SlotType.ARMOR || st == InventoryType.SlotType.CRAFTING || st == InventoryType.SlotType.RESULT) {
            e.setCancelled(true);
            return;
        }
        // ender chest: tools are allowed in (shears, pickaxes, axes), except the tier-one tool, which stays in the
        // inventory - it is the one the tiering is built on and losing it would strand the permanent-tool upgrade
        if (top != null && top.getType() == InventoryType.ENDER_CHEST) {
            ItemStack moving = null;
            boolean intoChest = e.getRawSlot() < top.getSize();
            if (e.isShiftClick() && !intoChest) moving = e.getCurrentItem();
            else if (intoChest) moving = e.getCursor();
            if (isTierOneTool(moving)) {
                e.setCancelled(true);
                net.h2020.api.core.Messages.send(p, "bedwars.gamelistener.the-first-tier-tool");
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrag(InventoryDragEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (top != null && top.getHolder() instanceof Menu) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onCraft(CraftItemEvent e) {
        if (at(e.getWhoClicked().getWorld()) != null) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent e) {
        if (!(e.getPlayer() instanceof Player)) return;
        // a replay watcher stands in an instance world but is not in the game: their menus and chests are none of ours
        if (net.h2020.api.core.ReplayState.isWatching(((Player) e.getPlayer()).getUniqueId())) return;
        BedwarsGame g = at(e.getPlayer().getWorld());
        if (g == null || e.getInventory().getHolder() instanceof Menu) return;
        BwPlayer bp = g.data((Player) e.getPlayer());
        if (bp == null || !bp.playing()) e.setCancelled(true);
    }

    // =================================================================== movement / world

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (e.getTo().getY() >= 0 || e.getFrom().getBlockY() == e.getTo().getBlockY()) return;
        final Player p = e.getPlayer();
        final BedwarsGame g = at(p.getWorld());
        if (g == null) return;
        Bukkit.getScheduler().runTask(module.plugin(), new Runnable() {
            @Override public void run() {
                BwPlayer bp = g.data(p);
                if (bp == null || p.getWorld() != g.world || p.getLocation().getY() >= 0) return;
                if (g.state == GameState.PLAYING && bp.playing()) g.handleDeath(bp, EntityDamageEvent.DamageCause.VOID);
                else {
                    p.setFallDistance(0);
                    boolean lobby = g.state == GameState.WAITING || g.state == GameState.STARTING;
                    p.teleport(lobby ? g.waitingLobby : g.spectatorSpawn);
                }
            }
        });
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent e) {
        if (at(e.getWorld()) != null) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent e) {
        if (at(e.getLocation().getWorld()) == null) return;
        if (e.getSpawnReason() != CreatureSpawnEvent.SpawnReason.CUSTOM) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onWeather(WeatherChangeEvent e) {
        if (e.toWeatherState() && at(e.getWorld()) != null) e.setCancelled(true);
    }

    // =================================================================== chat / connection

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent e) {
        final Player p = e.getPlayer();
        final BedwarsGame g = module.gameOf(p);
        if (g == null) return;
        GameState s = g.state;
        if (s != GameState.PLAYING && s != GameState.ENDING) return;
        e.setCancelled(true);
        final String msg = e.getMessage();
        Bukkit.getScheduler().runTask(module.plugin(), new Runnable() {
            @Override public void run() { if (p.isOnline()) g.chat(p, msg); }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        BedwarsGame g = module.gameOf(e.getPlayer());
        if (g != null) g.removePlayer(e.getPlayer());
        // bots never come back with the same UUID: drop their per-session Quick Buy layout
        if (BedwarsGame.isBot(e.getPlayer())) module.forgetQuickBuy(e.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent e) {
        BedwarsGame g = module.gameOf(e.getPlayer());
        if (g != null && e.getFrom() == g.world && e.getPlayer().getWorld() != g.world) g.removePlayer(e.getPlayer());
    }
}
