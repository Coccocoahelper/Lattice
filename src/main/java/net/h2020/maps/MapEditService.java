package net.h2020.maps;

import net.h2020.api.H2020;
import net.h2020.api.game.TeamColor;
import net.h2020.api.map.MapService;
import net.h2020.api.map.MapTemplate;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * In-game map editor, the BedWars1058 ArenaSetup equivalent: stand in a private copy of the map, open the setup book
 * and move any point — spawns, beds, generators, shop and upgrade NPCs, the waiting lobby, spectator spawn, mid,
 * build limit and the team base boxes — by clicking the block with the wand. Every write goes straight into that
 * map's map.yml, so the map is fixed without regenerating or re-importing it.
 *
 * <p>The editor moves points only: blocks you place in the instance world are a copy and are not saved to the
 * template. Building a map is a world edit, not a setup task.
 */
public final class MapEditService implements Listener {

    private static final String TITLE = ChatColor.DARK_GRAY + "Map Editor: ";
    private static final byte[] TEAM_DYE = {14, 11, 13, 4, 3, 0, 6, 7};   // red, blue, green, yellow, aqua, white, pink, gray

    public static final class Session {
        final UUID player;
        final String map;
        final File dir;
        final World world;
        final String worldName;
        String team = "red";
        int page = 0;
        String armed;
        boolean armedBed;
        int armedIndex = -1;      // -2 append a new generator, >=0 replace that one, -1 replace the first
        /** Fast setup: the point keys still to set. Empty means the wizard is not running. */
        List<String> queue = new ArrayList<String>();
        String queueTeam;
        /**
         * True while the ARENA wizard is running: every team, then diamonds, emeralds, lobby, mid, spectator, heights.
         *
         * <p>Different from a per-team fast setup in one way that matters: the wizard decides what comes next from what
         * is still missing, so it can be started on a half-finished map and will only ask for the gaps.
         */
        boolean arenaWizard;
        /** Which team the arena wizard is on, and which of its five points is next (0..4). */
        int arenaTeamIdx;
        int arenaStep;
        /** The arena wizard's tail: the keys after every team is done. */
        List<String> arenaTail = new ArrayList<String>();
        /** Asking for the two build heights, which are typed numbers rather than blocks. */
        boolean awaitHeights;
        boolean holograms = true;

        Session(UUID player, String map, File dir, World world, String worldName) {
            this.player = player;
            this.map = map;
            this.dir = dir;
            this.world = world;
            this.worldName = worldName;
        }
    }

    /** Marks our inventories so a click in somebody else's chest is never ours to handle. */
    public static final class Holder implements InventoryHolder {
        final UUID owner;
        final int page;
        final String team;
        Inventory inventory;

        Holder(UUID owner, int page, String team) {
            this.owner = owner;
            this.page = page;
            this.team = team;
        }

        @Override public Inventory getInventory() { return inventory; }
    }

    private final JavaPlugin plugin;
    private final MapServiceImpl service;
    private final Map<UUID, Session> sessions = new HashMap<UUID, Session>();
    /** slot -> action, rebuilt with every menu so a stale inventory cannot fire an old action. */
    private final Map<UUID, Map<Integer, String>> actions = new HashMap<UUID, Map<Integer, String>>();
    /** Floating point labels spawned for each session. */
    private final Map<UUID, List<ArmorStand>> holograms = new HashMap<UUID, List<ArmorStand>>();

    public MapEditService(JavaPlugin plugin, MapServiceImpl service) {
        this.plugin = plugin;
        this.service = service;
    }

    public void register() { Bukkit.getPluginManager().registerEvents(this, plugin); }

    public void unregister() {
        for (Session s : new ArrayList<Session>(sessions.values())) close(s, true);
        HandlerList.unregisterAll(this);
    }

    public boolean inSession(Player p) { return sessions.containsKey(p.getUniqueId()); }

    /** True while anybody is editing this map: setup commands must not write under an open editor. */
    public boolean inSession(String map) {
        if (map == null) return false;
        for (Session s : sessions.values()) if (map.equalsIgnoreCase(s.map)) return true;
        return false;
    }

    // ===================================================================================== open / close

    /** Enter the editor: a private copy of the map, creative flight, the setup book and the wand. */
    public String open(Player p, String mapName) {
        MapService ms = H2020.find(MapService.class);
        MapTemplate t = ms == null ? null : ms.byName(mapName);
        if (!(t instanceof StoredMapTemplate)) return "No map " + mapName;
        StoredMapTemplate st = (StoredMapTemplate) t;
        if (sessions.containsKey(p.getUniqueId())) close(sessions.get(p.getUniqueId()), true);

        String worldName = "edit_" + st.folder.getName() + "_" + (System.currentTimeMillis() % 100000);
        World w;
        try {
            w = st.createInstance(worldName);
        } catch (Throwable e) {
            return "Could not open a copy of " + st.name() + ": " + e.getMessage();
        }
        if (w == null) return "Could not open a copy of " + st.name();

        Session s = new Session(p.getUniqueId(), st.name(), st.folder(), w, w.getName());
        sessions.put(p.getUniqueId(), s);

        Vector at = st.point("waiting.lobby");
        if (at == null) at = st.point("mid");
        if (at == null) at = new Vector(0.5, 96, 0.5);
        Location spawn = new Location(w, at.getX(), at.getY(), at.getZ());
        p.teleport(spawn);
        p.setGameMode(org.bukkit.GameMode.CREATIVE);
        p.setAllowFlight(true);
        p.setFlying(true);
        p.getInventory().clear();
        p.getInventory().setItem(0, item(Material.BOOK, ChatColor.AQUA + "Map Editor",
                ChatColor.GRAY + "Right-click: open the setup menu",
                ChatColor.GRAY + "Map: " + ChatColor.WHITE + st.name()));
        p.getInventory().setItem(1, item(Material.BLAZE_ROD, ChatColor.YELLOW + "Setup Wand",
                ChatColor.GRAY + "Left-click a block: set the armed point there",
                ChatColor.GRAY + "Right-click: open the setup menu"));
        p.getInventory().setItem(7, item(Material.PAPER, ChatColor.GREEN + "Save and leave",
                ChatColor.GRAY + "Every change is already saved.",
                ChatColor.GRAY + "Right-click to return to the lobby."));
        p.getInventory().setItem(8, item(Material.COMPASS, ChatColor.RED + "Exit editor",
                ChatColor.GRAY + "Right-click to leave; nothing is lost."));
        p.updateInventory();
        spawnHolograms(s);
        p.sendMessage(net.h2020.api.core.Messages.get("core.mapeditservice.editing-right-click-the","p1",st.name));
        return null;
    }

    /** Leave the editor by player (public entry point for commands and tests). */
    public boolean leave(Player p) {
        Session s = sessions.get(p.getUniqueId());
        if (s == null) return false;
        close(s, true);
        return true;
    }

    /** Leave the editor and drop the private world. */
    public void close(Session s, boolean teleportOut) {
        clearHolograms(s);
        s.queue.clear();
        s.queueTeam = null;
        sessions.remove(s.player);
        actions.remove(s.player);
        Player p = Bukkit.getPlayer(s.player);
        if (p != null) {
            p.getInventory().clear();
            p.setFlying(false);
            p.setAllowFlight(false);
            p.setGameMode(org.bukkit.GameMode.SURVIVAL);
            if (teleportOut) {
                MapService ms = H2020.find(MapService.class);
                World lobby = ms == null ? null : ms.mainLobby();
                if (lobby != null) p.teleport(lobby.getSpawnLocation());
            }
            p.sendMessage(net.h2020.api.core.Messages.get("core.mapeditservice.left-the-editor-for","p1",s.map));
        }
        MapTemplate t = H2020.find(MapService.class) == null ? null : H2020.find(MapService.class).byName(s.map);
        if (t instanceof StoredMapTemplate) {
            try { ((StoredMapTemplate) t).destroyInstance(s.world); } catch (Throwable ignored) { }
        } else {
            World w = Bukkit.getWorld(s.worldName);
            if (w != null) Bukkit.unloadWorld(w, false);
        }
    }

    // ===================================================================================== menu

    /** page 0 = overview, 1..n = team pages, then generators, lobby, limits. */
    public Inventory menu(Player p, Session s, int page, String team) {
        // opening the book again is the escape hatch from the fast setup wizard
        s.queue.clear();
        s.queueTeam = null;
        return menu(p.getUniqueId(), s, page, team, p.getName());
    }

    public Inventory menu(UUID owner, Session s, int page, String team, String who) {
        boolean teamChanged = team != null && !team.equals(s.team);
        if (teamChanged) {
            // a half-finished wizard belongs to the old team
            s.queue.clear();
            s.queueTeam = null;
        }
        s.page = page;
        if (team != null) s.team = team;
        MapTemplate t = template(s);
        Inventory inv = Bukkit.createInventory(new Holder(owner, page, s.team), 27, TITLE + s.map);
        Map<Integer, String> act = new LinkedHashMap<Integer, String>();
        actions.put(owner, act);

        if (page == 0) {
            inv.setItem(10, item(Material.WOOL, ChatColor.WHITE + "Teams",
                    ChatColor.GRAY + "Spawn, bed, generator, shop and upgrades",
                    ChatColor.GRAY + "for each of the eight teams."));
            act.put(10, "page:1");
            inv.setItem(12, item(Material.DIAMOND, ChatColor.AQUA + "Generators",
                    ChatColor.GRAY + "Diamond and emerald generators." ));
            act.put(12, "page:9");
            inv.setItem(14, item(Material.BEACON, ChatColor.GOLD + "Lobby, spectator and mid",
                    ChatColor.GRAY + "Waiting lobby, spectator spawn, mid point."));
            act.put(14, "page:10");
            inv.setItem(16, item(Material.BARRIER, ChatColor.YELLOW + "Build limit and bounds",
                    ChatColor.GRAY + "Build height and the play box."));
            act.put(16, "page:11");
            inv.setItem(22, item(Material.COMPASS, ChatColor.RED + "Leave the editor",
                    ChatColor.GRAY + "Every change is already saved."));
            act.put(22, "exit");
            inv.setItem(24, item(Material.NAME_TAG, ChatColor.AQUA + "Holograms: "
                            + (s.holograms ? "on" : "off"),
                    ChatColor.GRAY + "Floating labels at every point already set.",
                    ChatColor.YELLOW + "Click to toggle"));
            act.put(24, "holograms");
        } else if (page == 1) {
            int slot = 10;
            for (int i = 0; i < TEAM_DYE.length; i++) {
                String color = TeamColor.values()[i].name().toLowerCase(Locale.ROOT);
                inv.setItem(slot, item(wool(TEAM_DYE[i]), ChatColor.WHITE + "" + ChatColor.BOLD + capitalise(color),
                        ChatColor.GRAY + "spawn " + ChatColor.WHITE + coord(t, "team." + color + ".spawn", 0),
                        ChatColor.GRAY + "bed   " + ChatColor.WHITE + coord(t, "team." + color + ".bed", 0),
                        ChatColor.GRAY + "gen   " + ChatColor.WHITE + coord(t, "team." + color + ".generator", 0),
                        "",
                        ChatColor.YELLOW + "Click to edit this team"));
                act.put(slot, "team:" + color);
                slot += i == 3 ? 2 : 1;              // two rows of four
            }
            inv.setItem(26, item(Material.ARROW, ChatColor.GRAY + "Back"));
            act.put(26, "page:0");
        } else if (page == 2) {
            String c = s.team;
            inv.setItem(4, item(wool(dyeOf(c)), ChatColor.WHITE + "" + ChatColor.BOLD + capitalise(c) + " team",
                    ChatColor.GRAY + "Click a point, then left-click the block with the wand."));
            point(inv, act, 10, Material.COMPASS, "Spawn", "team." + c + ".spawn", t, false);
            point(inv, act, 11, Material.BED, "Bed (click the bed block)", "team." + c + ".bed", t, true);
            point(inv, act, 12, Material.FURNACE, "Iron and gold generator", "team." + c + ".generator", t, false);
            point(inv, act, 13, Material.EMERALD, "Shop NPC / villager", "team." + c + ".shop", t, false);
            point(inv, act, 14, Material.DIAMOND, "Upgrades NPC / villager", "team." + c + ".upgrades", t, false);
            point(inv, act, 15, Material.GOLD_BLOCK, "Base corner 1", "team." + c + ".base", t, false);
            point(inv, act, 16, Material.GOLD_BLOCK, "Base corner 2", "team." + c + ".base", t, true);
            ItemStack fast = item(Material.NETHER_STAR, ChatColor.YELLOW + "Fast setup",
                    ChatColor.GRAY + "Walk spawn, bed, shop, upgrades, generator,",
                    ChatColor.GRAY + "then the base box is derived automatically.",
                    ChatColor.YELLOW + "Click to start the wizard");
            if (!s.queue.isEmpty() && c.equals(s.queueTeam)) fast = glow(fast);
            inv.setItem(20, fast);
            act.put(20, "fastsetup");
            // the arena-wide wizard, next to the per-team one: the per-team button is for fixing one base, this one
            // walks the whole map. It needs an ITEM, not just a handler - registering the action alone left a working
            // click handler on an empty slot, so the button did not exist to click.
            ItemStack fastAll = item(Material.BEACON, ChatColor.GOLD + "" + ChatColor.BOLD + "Arena setup",
                    ChatColor.GRAY + "Walks EVERY team, then diamonds, emeralds,",
                    ChatColor.GRAY + "waiting lobby, mid and spectator, then asks",
                    ChatColor.GRAY + "for the minimum and maximum build heights.",
                    "",
                    ChatColor.GRAY + "Only asks for what is still missing.",
                    ChatColor.YELLOW + "Click to start");
            if (s.arenaWizard) fastAll = glow(fastAll);
            inv.setItem(19, fastAll);
            act.put(19, "fastall");
            inv.setItem(22, item(Material.ARROW, ChatColor.GRAY + "Back"));
            act.put(22, "page:1");
            inv.setItem(26, item(Material.COMPASS, ChatColor.RED + "Leave the editor"));
            act.put(26, "exit");
        } else if (page == 9) {
            List<Vector> dia = t == null ? new ArrayList<Vector>() : t.points("gen.diamond");
            List<Vector> em = t == null ? new ArrayList<Vector>() : t.points("gen.emerald");
            inv.setItem(4, item(Material.DIAMOND, ChatColor.AQUA + "Diamond generators",
                    ChatColor.GRAY + "Click one to arm it, shift-click to delete it."));
            int slot = 9;
            for (int i = 0; i < dia.size() && slot < 17; i++, slot++) {
                inv.setItem(slot, item(Material.DIAMOND, ChatColor.AQUA + "Diamond " + (i + 1),
                        ChatColor.GRAY + at(dia.get(i)),
                        ChatColor.YELLOW + "Click: arm", ChatColor.DARK_RED + "Shift-click: delete"));
                act.put(slot, "arm:" + i + ":gen.diamond");
            }
            inv.setItem(18, item(Material.EMERALD, ChatColor.GREEN + "Emerald generators",
                    ChatColor.GRAY + "Click one to arm it, shift-click to delete it."));
            slot = 19;
            for (int i = 0; i < em.size() && slot < 26; i++, slot++) {
                inv.setItem(slot, item(Material.EMERALD, ChatColor.GREEN + "Emerald " + (i + 1),
                        ChatColor.GRAY + at(em.get(i)),
                        ChatColor.YELLOW + "Click: arm", ChatColor.DARK_RED + "Shift-click: delete"));
                act.put(slot, "arm:" + i + ":gen.emerald");
            }
            inv.setItem(0, item(Material.DIAMOND, ChatColor.AQUA + "Add diamond generator",
                    ChatColor.GRAY + "Arms a new one: left-click the block with the wand."));
            act.put(0, "new:gen.diamond");
            inv.setItem(1, item(Material.EMERALD, ChatColor.GREEN + "Add emerald generator",
                    ChatColor.GRAY + "Arms a new one: left-click the block with the wand."));
            act.put(1, "new:gen.emerald");
            inv.setItem(26, item(Material.ARROW, ChatColor.GRAY + "Back"));
            act.put(26, "page:0");
        } else if (page == 10) {
            point(inv, act, 10, Material.BEACON, "Waiting lobby (pre-game spawn)", "waiting.lobby", t, false);
            point(inv, act, 12, Material.COMPASS, "Spectator spawn", "spectator", t, false);
            point(inv, act, 14, Material.EYE_OF_ENDER, "Mid point", "mid", t, false);
            inv.setItem(16, item(Material.BEDROCK, ChatColor.YELLOW + "Build limit: " + ChatColor.WHITE
                    + (t == null ? "?" : t.buildLimitY()),
                    ChatColor.GRAY + "Click to set it to your current Y."));
            act.put(16, "limit");
            inv.setItem(26, item(Material.ARROW, ChatColor.GRAY + "Back"));
            act.put(26, "page:0");
        } else if (page == 11) {
            point(inv, act, 10, Material.STAINED_CLAY, "Bounds corner 1", "bounds.min", t, false);
            point(inv, act, 12, Material.STAINED_CLAY, "Bounds corner 2", "bounds.max", t, false);
            inv.setItem(14, item(Material.PAPER, ChatColor.GRAY + "Bounds: " + ChatColor.WHITE
                    + (t == null ? "?" : at(t.boundsMin()) + " -> " + at(t.boundsMax()))));
            inv.setItem(26, item(Material.ARROW, ChatColor.GRAY + "Back"));
            act.put(26, "page:0");
        }
        Holder h = (Holder) inv.getHolder();
        h.inventory = inv;
        if (teamChanged) spawnHolograms(s);
        return inv;
    }

    private void point(Inventory inv, Map<Integer, String> act, int slot, Material m, String label, String key,
                       MapTemplate t, boolean second) {
        String now = second ? coord(t, key, 1) : coord(t, key, 0);
        inv.setItem(slot, item(m, ChatColor.YELLOW + label,
                ChatColor.GRAY + "Now: " + ChatColor.WHITE + now,
                "",
                ChatColor.WHITE + "Click: arm, then left-click a block",
                ChatColor.WHITE + "Shift-click: set to where I stand"));
        act.put(slot, (second ? "arm2:" : "arm:") + key);
    }

    /** Build every page of the editor for a map and report the item counts, so the menu can be checked headless. */
    public String selftest(String mapName) {
        MapService ms = H2020.find(MapService.class);
        MapTemplate t = ms == null ? null : ms.byName(mapName);
        if (!(t instanceof StoredMapTemplate)) return "No map " + mapName;
        StoredMapTemplate st = (StoredMapTemplate) t;
        Session s = new Session(new UUID(0, 0), st.name(), st.folder(), st.worldFolder() != null
                ? Bukkit.getWorlds().get(0) : Bukkit.getWorlds().get(0), "selftest");
        StringBuilder sb = new StringBuilder("Map editor pages for " + st.name() + ": ");
        int[][] pages;
        try {
            for (int page : new int[]{0, 1, 2, 9, 10, 11}) {
                if (page == 2) s.team = "red";
                Inventory inv = menu(s.player, s, page, page == 2 ? "red" : null, "selftest");
                int filled = 0;
                for (int i = 0; i < inv.getSize(); i++) if (inv.getItem(i) != null) filled++;
                sb.append("p").append(page).append("=").append(filled).append("/").append(inv.getSize()).append(' ');
            }
        } catch (Throwable e) {
            return "Selftest failed: " + e;
        }
        // Cross-check actions against items. A handler with no item is an invisible button (this happened); an item
        // with no handler is a dead button. Neither shows up when the two are only counted.
        // build page 2 LAST so its action map is the one left in place (menu() replaces it every call)
        Inventory inv2 = menu(s.player, s, 2, "red", "selftest");
        Map<Integer, String> page2 = actions.get(s.player);
        int orphanActions = 0, deadItems = 0;
        if (page2 != null) {
            Inventory inv = inv2;
            for (java.util.Map.Entry<Integer, String> e : page2.entrySet()) {
                int slot = e.getKey();
                if (slot < inv.getSize() && inv.getItem(slot) == null) {
                    orphanActions++;
                    sb.append("NO-ITEM@").append(slot).append("(").append(e.getValue()).append(") ");
                }
            }
            for (int i = 0; i < inv.getSize(); i++) {
                // slot 4 is the team-colour header: a label with no action, which is correct and not a dead button
                if (i == 4) continue;
                if (inv.getItem(i) != null && !page2.containsKey(i)) {
                    deadItems++;
                    sb.append("DEAD@").append(i).append("(").append(inv.getItem(i).getType()).append(") ");
                }
            }
        }
        sb.append("actions=").append(page2 == null ? 0 : page2.size());
        sb.append(" orphanActions=").append(orphanActions);
        sb.append(" deadItems=").append(deadItems);
        sb.append(" fastallAt=").append(slotOf(page2, "fastall"));
        actions.remove(s.player);
        return sb.toString();
    }

    /** The slot an action was registered on, or -1 - so the arena-setup button's presence is assertable. */
    private static int slotOf(Map<Integer, String> page, String action) {
        if (page == null) return -1;
        for (java.util.Map.Entry<Integer, String> e : page.entrySet()) {
            if (action.equals(e.getValue())) return e.getKey();
        }
        return -1;
    }

    // ===================================================================================== events

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        Session s = sessions.get(e.getPlayer().getUniqueId());
        if (s == null) return;
        ItemStack hand = e.getPlayer().getItemInHand();
        if (hand == null) return;
        Material type = hand.getType();
        if (type != Material.BLAZE_ROD && type != Material.BOOK && type != Material.COMPASS
                && type != Material.PAPER) return;
        if (!e.getPlayer().getWorld().getName().equals(s.worldName)) return;

        if (e.getAction() == Action.LEFT_CLICK_BLOCK && type == Material.BLAZE_ROD) {
            e.setCancelled(true);
            if (s.armed == null && s.queue.isEmpty()) {
                net.h2020.api.core.Messages.send(e.getPlayer(), "core.mapeditservice.nothing-armed-open-the");
                return;
            }
            apply(e.getPlayer(), s, e.getClickedBlock());
            return;
        }
        if (e.getAction() == Action.RIGHT_CLICK_AIR || e.getAction() == Action.RIGHT_CLICK_BLOCK) {
            if (type == Material.COMPASS || type == Material.PAPER) {
                e.setCancelled(true);
                close(s, true);
                return;
            }
            if (type == Material.BOOK || type == Material.BLAZE_ROD) {
                e.setCancelled(true);
                e.getPlayer().openInventory(menu(e.getPlayer(), s, 0, null));
            }
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Holder)) return;
        Holder h = (Holder) e.getInventory().getHolder();
        if (!(e.getWhoClicked() instanceof Player)) { e.setCancelled(true); return; }
        Player p = (Player) e.getWhoClicked();
        Session s = sessions.get(p.getUniqueId());
        e.setCancelled(true);
        if (s == null || !h.owner.equals(p.getUniqueId())) return;
        Map<Integer, String> act = actions.get(p.getUniqueId());
        String a = act == null ? null : act.get(e.getRawSlot());
        if (a == null) return;

        if (a.equals("exit")) { p.closeInventory(); close(s, true); return; }
        if (a.equals("fastall")) {
            startArenaWizard(p, s);
            return;
        }
        if (a.equals("fastsetup")) {
            String c = s.team;
            s.queue.clear();
            s.queue.add("team." + c + ".spawn");
            s.queue.add("team." + c + ".bed");
            s.queue.add("team." + c + ".shop");
            s.queue.add("team." + c + ".upgrades");
            s.queue.add("team." + c + ".generator");
            s.queueTeam = c;
            s.armed = null;
            s.armedBed = false;
            s.armedIndex = -1;
            p.closeInventory();
            p.sendMessage(net.h2020.api.core.Messages.get("core.mapeditservice.fast-setup-for-left","p1",capitalise(c)));
            return;
        }
        if (a.equals("holograms")) {
            s.holograms = !s.holograms;
            if (s.holograms) spawnHolograms(s); else clearHolograms(s);
            p.openInventory(menu(p, s, s.page, null));
            return;
        }
        if (a.startsWith("page:")) { p.openInventory(menu(p, s, Integer.parseInt(a.substring(5)), null)); return; }
        if (a.startsWith("team:")) { p.openInventory(menu(p, s, 2, a.substring(5))); return; }
        if (a.equals("limit")) {
            int y = p.getLocation().getBlockY();
            write(s, "build-limit", String.valueOf(y));
            p.sendMessage(net.h2020.api.core.Messages.get("core.mapeditservice.build-limit-set-to","p1",y));
            p.openInventory(menu(p, s, 10, null));
            return;
        }
        if (a.startsWith("new:")) {
            String key = a.substring(4);
            s.armed = key;
            s.armedBed = false;
            s.armedIndex = -2;
            p.closeInventory();
            p.sendMessage(net.h2020.api.core.Messages.get("core.mapeditservice.armed-a-new-left","p1",key));
            return;
        }
        if (a.startsWith("arm:") || a.startsWith("arm2:")) {
            boolean second = a.startsWith("arm2:");
            String key = a.substring(second ? 5 : 4);
            if (a.contains(":gen.") && e.isShiftClick()) {
                int idx = Integer.parseInt(key.substring(0, key.indexOf(':')));
                String gen = key.substring(key.indexOf(':') + 1);
                MapEditor.removePoint(s.dir, gen, idx);
                service.reload();
                spawnHolograms(s);
                p.sendMessage(net.h2020.api.core.Messages.get("core.mapeditservice.removed","p1",gen,"p2",(idx + 1)));
                p.openInventory(menu(p, s, 9, null));
                return;
            }
            if (a.contains(":gen.")) {
                int idx = Integer.parseInt(key.substring(0, key.indexOf(':')));
                String gen = key.substring(key.indexOf(':') + 1);
                s.armed = gen;
                s.armedBed = false;
                s.armedIndex = idx;
            } else {
                s.armed = key;
                s.armedBed = second || key.endsWith(".bed");
                s.armedIndex = -1;
            }
            if (e.isShiftClick()) {
                // set it to where the editor stands, no wand needed
                Location at = p.getLocation();
                boolean ok = write(s, s.armed, s.armedBed, at, null);
                p.sendMessage(ok ? ChatColor.GREEN + "Set " + s.armed + " to " + at.getBlockX() + ","
                        + at.getBlockY() + "," + at.getBlockZ() + "." : ChatColor.RED + "Could not write map.yml.");
                s.armed = null;
                p.closeInventory();
                return;
            }
            p.closeInventory();
            p.sendMessage(net.h2020.api.core.Messages.get("core.mapeditservice.armed-left-click-the","p1",s.armed));
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Holder) e.setCancelled(true);
    }

    /**
     * The heights step is typed rather than clicked, so the wizard listens for the next chat line.
     *
     * <p>Two numbers, or the word "auto". "auto" takes them from the map's own geometry, which is what an unsourced map
     * needs: the floor is the lowest block any team stands on, the ceiling the tallest. That is a *suggestion* and the
     * reply says so - a wiki figure is better when it exists, and the wizard tells you which it used.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onHeightsChat(org.bukkit.event.player.AsyncPlayerChatEvent e) {
        Session s = sessions.get(e.getPlayer().getUniqueId());
        if (s == null || !s.awaitHeights) return;
        String msg = e.getMessage().trim();
        e.setCancelled(true);
        if (msg.equalsIgnoreCase("cancel")) {
            s.awaitHeights = false;
            e.getPlayer().sendMessage(org.bukkit.ChatColor.GRAY + "Arena setup cancelled.");
            return;
        }
        Integer min = null, max = null;
        if (msg.equalsIgnoreCase("auto")) {
            int[] got = autoHeights(s);
            min = got[0];
            max = got[1];
        } else {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(-?\\d+)\\s+(-?\\d+)$").matcher(msg);
            if (!m.matches()) {
                e.getPlayer().sendMessage(org.bukkit.ChatColor.RED
                        + "Type two numbers (minimum then maximum), or 'auto', or 'cancel'.");
                return;
            }
            min = Integer.parseInt(m.group(1));
            max = Integer.parseInt(m.group(2));
        }
        if (max <= min) {
            e.getPlayer().sendMessage(org.bukkit.ChatColor.RED + "The maximum must be above the minimum.");
            return;
        }
        s.awaitHeights = false;
        boolean a = MapEditor.setScalar(s.dir, "build-floor", String.valueOf(min));
        boolean b = MapEditor.setScalar(s.dir, "build-limit", String.valueOf(max));
        if (a && b) {
            service.reload();
            e.getPlayer().sendMessage(org.bukkit.ChatColor.GREEN + "Arena setup complete: build floor " + min
                    + ", ceiling " + max + ".");
        } else {
            e.getPlayer().sendMessage(org.bukkit.ChatColor.RED + "Could not write the heights into map.yml.");
        }
    }

    /** Suggested heights from the world: the lowest team point's base and the tallest block over the beds. */
    private int[] autoHeights(Session s) {
        MapTemplate t = template(s);
        int lowest = Integer.MAX_VALUE, highest = Integer.MIN_VALUE;
        if (t != null) {
            for (net.h2020.api.game.TeamColor c : net.h2020.api.game.TeamColor.values()) {
                String id = c.name().toLowerCase(java.util.Locale.ROOT);
                for (String key : new String[]{"team." + id + ".spawn", "team." + id + ".bed",
                        net.h2020.api.map.MapKeys.MID, net.h2020.api.map.MapKeys.WAITING_LOBBY}) {
                    for (org.bukkit.util.Vector v : t.points(key)) {
                        lowest = Math.min(lowest, v.getBlockY());
                        highest = Math.max(highest, v.getBlockY());
                    }
                }
            }
        }
        if (lowest == Integer.MAX_VALUE) return new int[]{0, 255};
        // a little headroom below the lowest walking surface, and room to build above the highest point
        return new int[]{Math.max(0, lowest - 8), Math.min(255, highest + 40)};
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Session s = sessions.get(e.getPlayer().getUniqueId());
        if (s != null) close(s, false);
    }

    // ===================================================================================== writing

    /** The wand landed: write the armed point at that block. */
    private boolean apply(Player p, Session s, Block block) {
        boolean queued = !s.queue.isEmpty();
        if (queued) {
            // the wizard walks its own queue: the head is the key, and the bed needs its bed handling
            s.armed = s.queue.get(0);
            s.armedBed = s.armed.endsWith(".bed");
            s.armedIndex = -1;
        }
        String key = s.armed;
        if (key == null) return false;
        boolean ok = write(s, key, s.armedBed, null, block);
        if (ok) {
            p.sendMessage(net.h2020.api.core.Messages.get("core.mapeditservice.set-to-on-map","p1",key,"p2",block.getX(),"p3",block.getY(),"p4",block.getZ(),"p5",s.map));
        } else {
            p.sendMessage(net.h2020.api.core.Messages.get("core.mapeditservice.could-not-write-into","p1",key));
        }
        s.armed = null;
        s.armedBed = false;
        s.armedIndex = -1;
        if (ok && queued) {
            s.queue.remove(0);
            if (s.arenaWizard) {
                // the arena wizard owns the order: one more point of this team, then the next team, then the tail
                s.arenaStep++;
                advanceTeam(p, s, teamsOf(s));
            } else if (!s.queue.isEmpty()) {
                String next = s.queue.get(0);
                int step = 6 - s.queue.size();
                p.sendMessage(ChatColor.AQUA + "Fast setup for " + capitalise(s.queueTeam == null ? s.team : s.queueTeam)
                        + ": left-click the " + stepName(next) + " block with the wand (" + step + "/5)");
            } else {
                finishFastSetup(p, s);
            }
        }
        return ok;
    }

    /**
     * Start the arena wizard: every team in the map, then the shared points, then the build heights.
     *
     * <p>This is the "set up a whole arena" flow rather than "set up one team". The order is the order a map is actually
     * built in - the eight team bases first because they are the tedious part and the one where walking the arena
     * matters, then the points shared by every team (diamonds, emeralds, waiting lobby, mid, spectator), and finally the
     * two build heights, which are typed numbers rather than block clicks because they are not locations.
     *
     * <p>Only what is MISSING is asked for, so a half-authored map is not re-walked from the start.
     */
    private void startArenaWizard(Player p, Session s) {
        p.closeInventory();
        List<String> teams = teamsOf(s);
        if (teams.isEmpty()) {
            p.sendMessage(ChatColor.RED + "This map declares no teams - set teams: [..] in map.yml first.");
            return;
        }
        s.arenaWizard = true;
        s.arenaTeamIdx = 0;
        s.arenaStep = 0;
        s.arenaTail.clear();
        s.awaitHeights = false;
        // the shared points come after the teams; queue them only if they are missing, so a re-run fills gaps
        MapTemplate t = template(s);
        for (String[] pair : new String[][]{
                {net.h2020.api.map.MapKeys.DIAMOND_GEN, "diamond generator"},
                {net.h2020.api.map.MapKeys.EMERALD_GEN, "emerald generator"},
                {net.h2020.api.map.MapKeys.WAITING_LOBBY, "waiting lobby"},
                {net.h2020.api.map.MapKeys.MID, "mid point"},
                {net.h2020.api.map.MapKeys.SPECTATOR, "spectator point"}}) {
            if (t == null || t.points(pair[0]).isEmpty()) s.arenaTail.add(pair[0]);
        }
        advanceTeam(p, s, teams);
    }

    /** Point the wizard at the next team that still needs a point, or finish into the shared tail. */
    private void advanceTeam(Player p, Session s, List<String> teams) {
        String[] steps = {"spawn", "bed", "shop", "upgrades", "generator"};
        while (s.arenaTeamIdx < teams.size()) {
            String c = teams.get(s.arenaTeamIdx);
            while (s.arenaStep < steps.length) {
                String key = "team." + c + "." + steps[s.arenaStep];
                s.queue.clear();
                s.queueTeam = c;
                s.armed = key;
                s.armedBed = steps[s.arenaStep].equals("bed");
                s.armedIndex = -1;
                s.queue.add(key);
                int n = s.arenaTeamIdx + 1, total = teams.size();
                p.sendMessage(ChatColor.AQUA + "" + ChatColor.BOLD + "Arena setup "
                        + ChatColor.GRAY + "team " + n + "/" + total + " " + capitalise(c)
                        + " - step " + (s.arenaStep + 1) + "/" + steps.length + ": "
                        + ChatColor.WHITE + stepName(key));
                p.sendMessage(ChatColor.GRAY + "Left-click that block with the wand. "
                        + ChatColor.DARK_GRAY + "(/h2020 edit skip to skip, cancel to stop)");
                return;
            }
            s.arenaStep = 0;
            s.arenaTeamIdx++;
        }
        // every team is done - the shared points
        s.queueTeam = null;
        if (!s.arenaTail.isEmpty()) {
            String key = s.arenaTail.remove(0);
            s.queue.clear();
            s.queue.add(key);
            s.armed = key;
            s.armedBed = false;
            s.armedIndex = -1;
            p.sendMessage(ChatColor.AQUA + "" + ChatColor.BOLD + "Arena setup "
                    + ChatColor.GRAY + "shared: " + ChatColor.WHITE + stepName(key));
            return;
        }
        // and finally the heights, which are typed
        s.arenaWizard = false;
        s.awaitHeights = true;
        s.armed = null;
        p.sendMessage(ChatColor.AQUA + "" + ChatColor.BOLD + "Arena setup " + ChatColor.GRAY + "last step: heights");
        p.sendMessage(ChatColor.WHITE + "Type the minimum and maximum build heights, e.g. "
                + ChatColor.GREEN + "60 120");
        p.sendMessage(ChatColor.DARK_GRAY + "Or type " + ChatColor.GRAY + "auto" + ChatColor.DARK_GRAY
                + " to take them from the world's own geometry.");
    }

    /**
     * The team colours this map uses.
     *
     * <p>There is no key-list on a template, so this asks the map what it already has: a team is "in use" if any of its
     * five points exist. That is right for a half-authored map and costs one lookup per colour. When nothing has been
     * set yet it falls back to the declared team COUNT and the standard colour order, which is the order the map.yml
     * generator writes and the order Hypixel names them in.
     */
    private List<String> teamsOf(Session s) {
        List<String> used = new ArrayList<String>();
        MapTemplate t = template(s);
        String[] steps = {"spawn", "bed", "shop", "upgrades", "generator"};
        for (net.h2020.api.game.TeamColor c : net.h2020.api.game.TeamColor.values()) {
            String id = c.name().toLowerCase(java.util.Locale.ROOT);
            if (t == null) continue;
            for (String step : steps) {
                if (!t.points("team." + id + "." + step).isEmpty()) { used.add(id); break; }
            }
        }
        if (!used.isEmpty()) return used;
        int count = 8;
        try {
            List<Integer> declared = t == null ? null : t.supportedTeamCounts();
            if (declared != null && !declared.isEmpty()) count = declared.get(0);
        } catch (Throwable ignored) { }
        for (net.h2020.api.game.TeamColor c : net.h2020.api.game.TeamColor.values()) {
            if (used.size() >= count) break;
            used.add(c.name().toLowerCase(java.util.Locale.ROOT));
        }
        return used;
    }

    /** The wizard's five points are in: derive and write the team's base box, then announce the result. */
    private void finishFastSetup(Player p, Session s) {
        String c = s.queueTeam == null ? s.team : s.queueTeam;
        MapTemplate t = template(s);
        List<Vector> pts = new ArrayList<Vector>();
        addPoint(pts, t, "team." + c + ".spawn");
        addPoint(pts, t, "team." + c + ".bed");
        addPoint(pts, t, "team." + c + ".shop");
        addPoint(pts, t, "team." + c + ".upgrades");
        addPoint(pts, t, "team." + c + ".generator");
        Vector spawn = t == null ? null : t.point("team." + c + ".spawn");
        if (spawn == null || pts.isEmpty()) {
            p.sendMessage(net.h2020.api.core.Messages.get("core.mapeditservice.fast-setup-for-could-2","p1",capitalise(c)));
            s.queueTeam = null;
            return;
        }
        double minX = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (Vector v : pts) {
            minX = Math.min(minX, v.getX());
            maxX = Math.max(maxX, v.getX());
            minZ = Math.min(minZ, v.getZ());
            maxZ = Math.max(maxZ, v.getZ());
        }
        minX -= 6;
        maxX += 6;
        minZ -= 6;
        maxZ += 6;
        int y = spawn.getBlockY();
        Location min = new Location(s.world, minX, y - 10, minZ);
        Location max = new Location(s.world, maxX, y + 20, maxZ);
        boolean ok = MapEditor.setPair(s.dir, "team." + c + ".base", min, max);
        if (ok) {
            service.reload();
            spawnHolograms(s);
            p.sendMessage(net.h2020.api.core.Messages.get("core.mapeditservice.fast-setup-for-complete","p1",capitalise(c),"p2",(int) Math.round(minX),"p3",(int) Math.round(minZ),"p4",(int) Math.round(maxX),"p5",(int) Math.round(maxZ)));
        } else {
            p.sendMessage(net.h2020.api.core.Messages.get("core.mapeditservice.fast-setup-for-could","p1",capitalise(c)));
        }
        if (!s.arenaWizard) s.queueTeam = null;
    }

    private static void addPoint(List<Vector> out, MapTemplate t, String key) {
        if (t == null) return;
        List<Vector> pts = t.points(key);
        if (pts != null && !pts.isEmpty()) out.add(pts.get(0));
    }

    private static String stepName(String key) {
        if (key.endsWith(".spawn")) return "SPAWN";
        if (key.endsWith(".bed")) return "BED";
        if (key.endsWith(".shop")) return "SHOP";
        if (key.endsWith(".upgrades")) return "UPGRADES";
        if (key.endsWith(".generator")) return "GENERATOR";
        return key.toUpperCase(Locale.ROOT);
    }

    private boolean write(Session s, String key, String value) {
        boolean ok = MapEditor.setScalar(s.dir, key, value);
        if (ok) {
            service.reload();
            spawnHolograms(s);
        }
        return ok;
    }

    private boolean write(Session s, String key, boolean bed, Location at, Block block) {
        boolean ok;
        if (key.startsWith("bounds.")) {
            if (block == null) return false;
            ok = MapEditor.setBounds(s.dir, block, key.equals("bounds.max"));
        } else if (block != null && bed && block.getType() == Material.BED_BLOCK) {
            ok = MapEditor.setBed(s.dir, key, block);
        } else if (block != null) {
            Location loc = picker(block);
            ok = MapEditor.writePoint(s.dir, key, loc, bed, s.armedIndex);
        } else {
            ok = MapEditor.writePoint(s.dir, key, at, bed, s.armedIndex);
        }
        if (ok) {
            service.reload();
            spawnHolograms(s);
        }
        return ok;
    }

    // ===================================================================================== helpers

    /** A floating label over every point already set, so a builder can read the map without arming keys. */
    private void spawnHolograms(Session s) {
        clearHolograms(s);
        if (!s.holograms || s.world == null) return;
        MapTemplate t = template(s);
        if (t == null) return;
        List<ArmorStand> stands = new ArrayList<ArmorStand>();
        holograms.put(s.player, stands);

        String color = s.team == null ? "red" : s.team;
        ChatColor team = teamChat(color);
        String label = capitalise(color);
        String prefix = "team." + color + ".";
        stand(s, stands, first(t, prefix + "spawn"), holoName(team, label + " Spawn"));
        stand(s, stands, first(t, prefix + "bed"), holoName(team, label + " Bed"));
        stand(s, stands, first(t, prefix + "generator"), holoName(team, label + " Generator"));
        stand(s, stands, first(t, prefix + "shop"), holoName(team, label + " Shop"));
        stand(s, stands, first(t, prefix + "upgrades"), holoName(team, label + " Upgrades"));
        stand(s, stands, first(t, prefix + "base"), holoName(team, label + " Base"));

        stand(s, stands, first(t, "waiting.lobby"), holoName(ChatColor.YELLOW, "Lobby"));
        stand(s, stands, first(t, "spectator"), holoName(ChatColor.YELLOW, "Spectator"));
        stand(s, stands, first(t, "mid"), holoName(ChatColor.YELLOW, "Mid"));
        List<Vector> dia = t.points("gen.diamond");
        for (int i = 0; i < dia.size(); i++)
            stand(s, stands, dia.get(i), holoName(ChatColor.AQUA, "Diamond " + (i + 1)));
        List<Vector> em = t.points("gen.emerald");
        for (int i = 0; i < em.size(); i++)
            stand(s, stands, em.get(i), holoName(ChatColor.GREEN, "Emerald " + (i + 1)));
    }

    /** ChatColor.WHITE + "" + ChatColor.BOLD + "<Team> <Thing>", recoloured by point family. */
    private static String holoName(ChatColor color, String text) {
        return ChatColor.WHITE + "" + ChatColor.BOLD + color + text;
    }

    private void stand(Session s, List<ArmorStand> stands, Vector at, String name) {
        if (at == null) return;
        try {
            ArmorStand as = s.world.spawn(new Location(s.world, at.getX(), at.getY() + 1.5, at.getZ()),
                    ArmorStand.class);
            as.setVisible(false);
            as.setGravity(false);
            as.setSmall(true);
            as.setMarker(true);
            as.setCustomNameVisible(true);
            as.setCustomName(name);
            setInvulnerable(as);
            stands.add(as);
        } catch (Throwable ignored) {
            // a point in an unloaded chunk must not stop the editor from writing the point itself
        }
    }

    /** setInvulnerable(true); the 1.8.8 Bukkit Entity interface does not expose it, so call it where it exists. */
    private static void setInvulnerable(ArmorStand as) {
        try {
            as.getClass().getMethod("setInvulnerable", boolean.class).invoke(as, Boolean.TRUE);
        } catch (Throwable ignored) {
            // an older server simply keeps the marker stands damageable, which no builder will notice
        }
    }

    private void clearHolograms(Session s) {
        List<ArmorStand> stands = holograms.remove(s.player);
        if (stands == null) return;
        for (ArmorStand as : stands) {
            if (as == null || as.isDead()) continue;
            try { as.remove(); } catch (Throwable ignored) { }
        }
    }

    private static Vector first(MapTemplate t, String key) {
        if (t == null) return null;
        List<Vector> pts = t.points(key);
        return pts == null || pts.isEmpty() ? null : pts.get(0);
    }

    private static ChatColor teamChat(String color) {
        try { return TeamColor.valueOf(color.toUpperCase(Locale.ROOT)).chat; }
        catch (Throwable e) { return ChatColor.GRAY; }
    }

    private MapTemplate template(Session s) {
        MapService ms = H2020.find(MapService.class);
        return ms == null ? null : ms.byName(s.map);
    }

    private static String coord(MapTemplate t, String key, int index) {
        if (t == null) return "unset";
        List<Vector> pts = t.points(key);
        if (pts == null || pts.size() <= index) return "unset";
        return at(pts.get(index));
    }

    private static String at(Vector v) {
        return v == null ? "unset" : (v.getBlockX() + ", " + v.getBlockY() + ", " + v.getBlockZ());
    }

    private static String capitalise(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static byte dyeOf(String color) {
        int i = Arrays.asList(TeamColor.values()).indexOf(TeamColor.valueOf(color.toUpperCase(Locale.ROOT)));
        return i < 0 ? 0 : TEAM_DYE[i];
    }

    @SuppressWarnings("deprecation")
    private static ItemStack wool(byte dye) { return new ItemStack(Material.WOOL, 1, dye); }

    /** Enchanted glint without a real enchantment, used to show that the wizard is running. */
    @SuppressWarnings("deprecation")
    private static ItemStack glow(ItemStack stack) {
        stack.addUnsafeEnchantment(Enchantment.DURABILITY, 1);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    /**
     * Where a left-clicked block puts a point: the centre of the column, one block up, because almost every point is a
     * place somebody stands (spawn, generator, shop, mid, waiting lobby) and clicking the floor you are on would
     * otherwise bury the point in it. Beds and bounds keep the block itself - they are blocks, not standable spots.
     */
    public static Location picker(org.bukkit.block.Block block) {
        return new Location(block.getWorld(), block.getX() + 0.5, block.getY() + 1, block.getZ() + 0.5, 0f, 0f);
    }

    private static ItemStack item(ItemStack base, String name, String... lore) {
        ItemMeta meta = base.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            if (lore.length > 0) meta.setLore(new ArrayList<String>(Arrays.asList(lore)));
            base.setItemMeta(meta);
        }
        return base;
    }

    private static ItemStack item(Material m, String name, String... lore) {
        ItemStack s = new ItemStack(m);
        ItemMeta meta = s.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            if (lore.length > 0) meta.setLore(new ArrayList<String>(Arrays.asList(lore)));
            s.setItemMeta(meta);
        }
        return s;
    }
}
