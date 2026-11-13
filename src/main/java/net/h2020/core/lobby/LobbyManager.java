package net.h2020.core.lobby;

import net.h2020.api.H2020;
import net.h2020.api.core.Rank;
import net.h2020.api.game.GameFamily;
import net.h2020.api.map.MapService;
import net.h2020.core.Core;
import net.h2020.core.CoreUtil;
import net.h2020.core.game.LobbyHooks;
import net.h2020.core.profile.ProfileData;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
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
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPickupItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.event.weather.ThunderChangeEvent;
import org.bukkit.event.weather.WeatherChangeEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The main lobby and the Bed Wars / SkyWars lobbies: routing hooks for GameManager, hotbar items and menus,
 * scoreboards, player visibility, MVP+ join messages, protection and world settings.
 */
public final class LobbyManager implements LobbyHooks, Listener {
    private final Core core;
    private final LobbyMenus menus;
    private final LobbySidebars sidebars;
    private final Set<UUID> builders = new HashSet<>();
    private final Map<UUID, Long> visibilityCooldown = new HashMap<>();
    /** viewer -> players this manager hid from them */
    private final Map<UUID, Set<UUID>> hidden = new HashMap<>();
    private final Set<String> configuredWorlds = new HashSet<>();
    private final LobbyPads pads;

    public LobbyManager(Core core) {
        this.core = core;
        this.pads = new LobbyPads(core.plugin);
        this.menus = new LobbyMenus(core);
        this.sidebars = new LobbySidebars(core);
    }

    public LobbyMenus menus() { return menus; }

    /** NPC pads / hub spawns published by the maps module. */
    public LobbyPads pads() { return pads; }

    public void start() {
        Bukkit.getScheduler().runTaskTimer(core.plugin, new Runnable() {
            @Override public void run() { tickSidebars(); }
        }, 20L, 20L);
        Bukkit.getScheduler().runTaskTimer(core.plugin, new Runnable() {
            @Override public void run() { applyWorldSettings(); }
        }, 1L, 20L * 30);
        // players already online after /reload
        for (Player p : Bukkit.getOnlinePlayers()) if (!CoreUtil.isBot(p)) core.games.sendToLobby(p, null);
    }

    // ---------------- worlds ----------------

    @Override
    public World lobbyWorld(GameFamily family) {
        MapService ms = H2020.find(MapService.class);
        World w = null;
        if (ms != null) {
            try { w = family == null ? ms.mainLobby() : ms.familyLobby(family); } catch (Throwable ignored) {}
            if (w == null && family != null) {
                try { w = ms.mainLobby(); } catch (Throwable ignored) {}
            }
        }
        return w != null ? w : Bukkit.getWorlds().get(0);
    }

    public boolean isMainLobby(World w) { return w != null && w.equals(lobbyWorld(null)); }

    public boolean isLobby(World w) {
        if (w == null) return false;
        if (isMainLobby(w)) return true;
        for (GameFamily f : GameFamily.values()) if (w.equals(lobbyWorld(f))) return true;
        return false;
    }

    /** Family of a family-lobby world; null for the main lobby or any non-lobby world. */
    public GameFamily familyOf(World w) {
        if (w == null || isMainLobby(w)) return null;
        for (GameFamily f : GameFamily.values()) if (w.equals(lobbyWorld(f))) return f;
        return null;
    }

    private boolean inLobby(Player p) { return isLobby(p.getWorld()) && core.games.gameByWorld(p.getWorld()) == null; }

    @Override
    public Location spawn(World lobby) {
        ConfigurationSection c = core.plugin.getConfig().getConfigurationSection("core.lobby-spawns." + lobby.getName());
        if (c != null) return new Location(lobby, c.getDouble("x"), c.getDouble("y"), c.getDouble("z"), (float) c.getDouble("yaw"), (float) c.getDouble("pitch"));
        // generated hubs: the maps module publishes the spawn with its facing (world spawns have no yaw in 1.8)
        Location hub = pads.spawn(lobby);
        if (hub != null) return hub;
        Location s = lobby.getSpawnLocation();
        return new Location(lobby, s.getBlockX() + 0.5, s.getY(), s.getBlockZ() + 0.5, s.getYaw(), 0f);
    }

    /** /h2020 setspawn */
    public void setSpawn(Location l) {
        String path = "core.lobby-spawns." + l.getWorld().getName();
        core.plugin.getConfig().set(path + ".x", Math.round(l.getX() * 100) / 100.0);
        core.plugin.getConfig().set(path + ".y", Math.round(l.getY() * 100) / 100.0);
        core.plugin.getConfig().set(path + ".z", Math.round(l.getZ() * 100) / 100.0);
        core.plugin.getConfig().set(path + ".yaw", Math.round(l.getYaw()));
        core.plugin.getConfig().set(path + ".pitch", 0);
        core.plugin.saveConfig();
        l.getWorld().setSpawnLocation(l.getBlockX(), l.getBlockY(), l.getBlockZ());
    }

    /** Always day, no weather, no mobs, no fire spread. */
    public void applyWorldSettings() {
        List<World> worlds = new ArrayList<>();
        worlds.add(lobbyWorld(null));
        for (GameFamily f : GameFamily.values()) {
            World w = lobbyWorld(f);
            if (!worlds.contains(w)) worlds.add(w);
        }
        for (World w : worlds) {
            w.setTime(6000L);
            w.setStorm(false);
            w.setThundering(false);
            w.setWeatherDuration(Integer.MAX_VALUE / 2);
            if (configuredWorlds.add(w.getName())) {
                w.setGameRuleValue("doDaylightCycle", "false");
                w.setGameRuleValue("doMobSpawning", "false");
                w.setGameRuleValue("doFireTick", "false");
                w.setGameRuleValue("mobGriefing", "false");
                w.setGameRuleValue("keepInventory", "true");
                w.setSpawnFlags(false, false);
                w.setDifficulty(Difficulty.PEACEFUL);
                w.setKeepSpawnInMemory(true);
                w.setPVP(false);
                for (Entity e : w.getEntities()) {
                    if (e instanceof org.bukkit.entity.Monster || e instanceof org.bukkit.entity.Animals) e.remove();
                }
            }
        }
    }

    // ---------------- routing hooks ----------------

    /** Lobby selector / NPC / game menu "connect" to a lobby (null = main). */
    public void connectFamily(Player p, GameFamily family) {
        core.games.sendToLobby(p, family);
    }

    @Override
    public void entered(Player p, GameFamily family) {
        ProfileData d = core.profiles.data(p);
        boolean bot = CoreUtil.isBot(p);
        p.setGameMode(GameMode.ADVENTURE);
        LobbyItems.give(p, family, d.playersHidden);
        // Hypixel lobbies show your network level on the XP bar
        p.setLevel(d.networkLevel);
        p.setExp((float) Math.max(0, Math.min(0.999, d.levelProgress)));
        applyNametag(p);
        if (!bot) {
            sidebars.update(p, family);
            core.scoreboards.setTabHeaderFooter(p, core.config.tabHeader, core.config.tabFooter);
            joinMessage(p, d);
        }
        final UUID id = p.getUniqueId();
        Bukkit.getScheduler().runTaskLater(core.plugin, new Runnable() {
            @Override public void run() {
                Player pl = Bukkit.getPlayer(id);
                if (pl != null) refreshAround(pl);
            }
        }, 2L);
    }

    @Override
    public void leaving(Player p) {
        Set<UUID> mine = hidden.remove(p.getUniqueId());
        if (mine != null) for (UUID id : mine) {
            Player o = Bukkit.getPlayer(id);
            if (o != null) p.showPlayer(o);
        }
        for (Map.Entry<UUID, Set<UUID>> e : hidden.entrySet()) {
            if (e.getValue().remove(p.getUniqueId())) {
                Player v = Bukkit.getPlayer(e.getKey());
                if (v != null) v.showPlayer(p);
            }
        }
    }

    public void applyNametag(Player p) {
        Rank r = core.profiles.rank(p);
        core.scoreboards.setNametag(p, core.profiles.nametagPrefix(p), "", r.color);
    }

    private void joinMessage(Player p, ProfileData d) {
        String msg;
        if (d.rank == Rank.MVP_PLUS_PLUS) {
            msg = " " + ChatColor.AQUA + ">" + ChatColor.RED + ">" + ChatColor.GREEN + ">" + ChatColor.RESET + " "
                    + d.rank.prefix(d.plusColor) + p.getName() + ChatColor.GOLD + " joined the lobby! "
                    + ChatColor.GREEN + "<" + ChatColor.RED + "<" + ChatColor.AQUA + "<";
        } else if (d.rank == Rank.MVP_PLUS) {
            msg = d.rank.prefix(d.plusColor) + p.getName() + ChatColor.GOLD + " joined the lobby!";
        } else {
            return;
        }
        for (Player o : p.getWorld().getPlayers()) o.sendMessage(msg);
    }

    // ---------------- visibility ----------------

    private void refreshVisibility(Player viewer) {
        Set<UUID> cur = hidden.get(viewer.getUniqueId());
        if (cur == null) { cur = new HashSet<>(); hidden.put(viewer.getUniqueId(), cur); }
        Set<UUID> want = new HashSet<>();
        if (!CoreUtil.isBot(viewer) && inLobby(viewer) && !net.h2020.core.arena.PvpArena.isArena(viewer.getWorld())
                && core.profiles.data(viewer).playersHidden) {
            for (Player o : viewer.getWorld().getPlayers()) if (o != viewer) want.add(o.getUniqueId());
        }
        for (UUID id : new ArrayList<>(cur)) {
            if (!want.contains(id)) {
                Player o = Bukkit.getPlayer(id);
                if (o != null) viewer.showPlayer(o);
                cur.remove(id);
            }
        }
        for (UUID id : want) {
            if (cur.add(id)) {
                Player o = Bukkit.getPlayer(id);
                if (o != null) viewer.hidePlayer(o);
            }
        }
        if (cur.isEmpty()) hidden.remove(viewer.getUniqueId());
    }

    /** Re-evaluate this player's view and everyone else's view of this player. */
    private void refreshAround(Player p) {
        refreshVisibility(p);
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (o == p || CoreUtil.isBot(o)) continue;
            if (hidden.containsKey(o.getUniqueId()) || core.profiles.data(o).playersHidden) refreshVisibility(o);
        }
    }

    private void toggleVisibility(Player p) {
        long now = System.currentTimeMillis();
        Long last = visibilityCooldown.get(p.getUniqueId());
        if (last != null && now - last < 3000) {
            p.sendMessage(net.h2020.api.core.Messages.get("core.lobby.visibility-cooldown"));
            return;
        }
        visibilityCooldown.put(p.getUniqueId(), now);
        ProfileData d = core.profiles.data(p);
        d.playersHidden = !d.playersHidden;
        p.getInventory().setItem(7, LobbyItems.visibility(d.playersHidden));
        p.sendMessage(d.playersHidden ? net.h2020.api.core.Messages.get("core.lobby.visibility-off") : net.h2020.api.core.Messages.get("core.lobby.visibility-on"));
        p.playSound(p.getLocation(), Sound.CLICK, 1f, 1f);
        refreshVisibility(p);
    }

    // ---------------- sidebars ----------------

    /** Real profile values for a lobby board, so /lattice sidebar preview shows what a player would actually see. */
    public java.util.Map<String, String> sidebarVars(Player p, net.h2020.api.game.GameFamily family) {
        return sidebars.varsFor(p, family);
    }


    private void tickSidebars() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!inLobby(p) || CoreUtil.isBot(p)) continue;
            sidebars.update(p, familyOf(p.getWorld()));
        }
    }

    // ---------------- join / quit / world change ----------------

    @EventHandler(priority = EventPriority.NORMAL)
    public void onJoin(PlayerJoinEvent e) {
        final Player p = e.getPlayer();
        final boolean bot = CoreUtil.isBot(p);
        Bukkit.getScheduler().runTaskLater(core.plugin, new Runnable() {
            @Override public void run() {
                if (!p.isOnline()) return;
                if (bot) {
                    // bots log in where the bots module put them; only dress them if that is a lobby
                    if (inLobby(p) && core.games.gameOf(p) == null) entered(p, familyOf(p.getWorld()));
                    else refreshAround(p);
                } else {
                    core.games.sendToLobby(p, null);
                }
            }
        }, bot ? 2L : 1L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        leaving(p);
        builders.remove(p.getUniqueId());
        visibilityCooldown.remove(p.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent e) {
        final Player p = e.getPlayer();
        if (!inLobby(p)) leaving(p);
        Bukkit.getScheduler().runTaskLater(core.plugin, new Runnable() {
            @Override public void run() { if (p.isOnline()) refreshAround(p); }
        }, 2L);
    }

    /** Profile change (e.g. bots module setting a bot's rank): refresh tags/boards when in a lobby. */
    public void profileChanged(Player p) {
        if (!p.isOnline() || !inLobby(p)) return;
        applyNametag(p);
        if (!CoreUtil.isBot(p)) sidebars.update(p, familyOf(p.getWorld()));
    }

    // ---------------- build mode ----------------

    public boolean toggleBuild(Player p) {
        if (!builders.remove(p.getUniqueId())) { builders.add(p.getUniqueId()); return true; }
        return false;
    }

    private boolean protectedFor(Player p) { return inLobby(p) && !builders.contains(p.getUniqueId()); }

    // ---------------- items ----------------

    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        if (!inLobby(p)) return;
        if (e.getAction() == Action.PHYSICAL) {
            Block b = e.getClickedBlock();
            if (b != null && b.getType() == Material.SOIL) e.setCancelled(true);
            return;
        }
        ItemStack item = e.getItem();
        LobbyItems.Kind kind = LobbyItems.kind(item);
        if (kind != null) {
            e.setCancelled(true);
            if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
            GameFamily fam = familyOf(p.getWorld());
            switch (kind) {
                case GAME_MENU:
                    if (fam == null) menus.openGameMenu(p);
                    else menus.openModeSelect(p, fam);
                    break;
                case PROFILE: menus.openProfile(p); break;
                case SHOP: if (fam != null) menus.openShop(p, fam); break;
                case COLLECTIBLES: menus.openCollectibles(p); break;
                case VISIBILITY: toggleVisibility(p); break;
                case LOBBY_SELECTOR: menus.openLobbySelector(p); break;
                default: break;
            }
            return;
        }
        if (builders.contains(p.getUniqueId())) return;
        if (e.getAction() == Action.RIGHT_CLICK_BLOCK && e.getClickedBlock() != null) {
            Material m = e.getClickedBlock().getType();
            switch (m) {
                case CHEST: case TRAPPED_CHEST: case ENDER_CHEST: case FURNACE: case BURNING_FURNACE: case DISPENSER:
                case DROPPER: case HOPPER: case BREWING_STAND: case ANVIL: case BEACON: case WORKBENCH: case ENCHANTMENT_TABLE:
                case TRAP_DOOR: case IRON_TRAPDOOR: case FENCE_GATE: case BED_BLOCK: case JUKEBOX: case NOTE_BLOCK:
                case DIODE_BLOCK_OFF: case DIODE_BLOCK_ON: case REDSTONE_COMPARATOR_OFF: case REDSTONE_COMPARATOR_ON:
                case LEVER: case DAYLIGHT_DETECTOR: case FLOWER_POT: case CAKE_BLOCK:
                    e.setCancelled(true);
                    break;
                default:
                    break;
            }
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onInventoryClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player)) return;
        Player p = (Player) e.getWhoClicked();
        if (!protectedFor(p) || p.getGameMode() == GameMode.CREATIVE) return;
        if (e.getClickedInventory() != null && e.getClickedInventory().equals(p.getInventory())) e.setCancelled(true);
        if (e.getClick() == org.bukkit.event.inventory.ClickType.NUMBER_KEY) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onDrop(PlayerDropItemEvent e) { if (protectedFor(e.getPlayer())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.LOW)
    public void onPickup(PlayerPickupItemEvent e) { if (protectedFor(e.getPlayer())) e.setCancelled(true); }

    // ---------------- protection ----------------

    @EventHandler(priority = EventPriority.LOW)
    public void onBreak(BlockBreakEvent e) { if (protectedFor(e.getPlayer())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.LOW)
    public void onPlace(BlockPlaceEvent e) { if (protectedFor(e.getPlayer())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.LOW)
    public void onBucket(PlayerBucketEmptyEvent e) { if (protectedFor(e.getPlayer())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.LOW)
    public void onBucketFill(PlayerBucketFillEvent e) { if (protectedFor(e.getPlayer())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.LOW)
    public void onEntityInteract(PlayerInteractEntityEvent e) { if (protectedFor(e.getPlayer())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.LOW)
    public void onArmorStand(PlayerArmorStandManipulateEvent e) { if (protectedFor(e.getPlayer())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.LOW)
    public void onHanging(HangingBreakEvent e) { if (isLobby(e.getEntity().getWorld())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.LOW)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player)) {
            if (isLobby(e.getEntity().getWorld()) && core.games.gameByWorld(e.getEntity().getWorld()) == null) e.setCancelled(true);
            return;
        }
        Player p = (Player) e.getEntity();
        if (!inLobby(p)) return;
        e.setCancelled(true);
        if (e.getCause() == EntityDamageEvent.DamageCause.VOID) toSpawn(p);
        p.setFireTicks(0);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onFood(FoodLevelChangeEvent e) {
        if (e.getEntity() instanceof Player && inLobby((Player) e.getEntity())) {
            e.setCancelled(true);
            ((Player) e.getEntity()).setFoodLevel(20);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (e.getTo().getBlockY() == e.getFrom().getBlockY() && e.getTo().getY() > 0) return;
        Player p = e.getPlayer();
        if (!inLobby(p)) return;
        Location spawn = spawn(p.getWorld());
        if (e.getTo().getY() < Math.max(0, spawn.getY() - 50)) toSpawn(p);
    }

    private void toSpawn(final Player p) {
        Bukkit.getScheduler().runTask(core.plugin, new Runnable() {
            @Override public void run() {
                if (!p.isOnline()) return;
                p.setFallDistance(0);
                p.teleport(spawn(p.getWorld()));
                p.setFallDistance(0);
            }
        });
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onFlight(PlayerToggleFlightEvent e) {
        Player p = e.getPlayer();
        if (!inLobby(p) || p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) return;
        if (builders.contains(p.getUniqueId())) return;
        e.setCancelled(true);
        p.setFlying(false);
        p.setAllowFlight(false);
    }

    // ---------------- world events ----------------

    private boolean lobbyWorldOnly(World w) { return isLobby(w) && core.games.gameByWorld(w) == null; }

    @EventHandler(priority = EventPriority.LOW)
    public void onSpawn(CreatureSpawnEvent e) {
        if (!lobbyWorldOnly(e.getLocation().getWorld())) return;
        if (e.getSpawnReason() != CreatureSpawnEvent.SpawnReason.CUSTOM && e.getEntityType() != org.bukkit.entity.EntityType.ARMOR_STAND) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onWeather(WeatherChangeEvent e) { if (e.toWeatherState() && lobbyWorldOnly(e.getWorld())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.LOW)
    public void onThunder(ThunderChangeEvent e) { if (e.toThunderState() && lobbyWorldOnly(e.getWorld())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.LOW)
    public void onSpread(BlockSpreadEvent e) {
        if (lobbyWorldOnly(e.getBlock().getWorld()) && e.getNewState().getType() == Material.FIRE) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onBurn(BlockBurnEvent e) { if (lobbyWorldOnly(e.getBlock().getWorld())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.LOW)
    public void onIgnite(BlockIgniteEvent e) {
        if (!lobbyWorldOnly(e.getBlock().getWorld())) return;
        if (e.getPlayer() != null && builders.contains(e.getPlayer().getUniqueId())) return;
        e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onDecay(LeavesDecayEvent e) { if (lobbyWorldOnly(e.getBlock().getWorld())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.LOW)
    public void onFade(BlockFadeEvent e) { if (lobbyWorldOnly(e.getBlock().getWorld())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.LOW)
    public void onExplode(EntityExplodeEvent e) {
        if (lobbyWorldOnly(e.getLocation().getWorld())) { e.blockList().clear(); e.setCancelled(true); }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onChangeBlock(EntityChangeBlockEvent e) { if (lobbyWorldOnly(e.getBlock().getWorld())) e.setCancelled(true); }
}
