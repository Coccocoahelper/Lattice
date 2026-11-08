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
        p.sendMessage(ChatColor.AQUA + "Editing " + ChatColor.WHITE + st.name + ChatColor.AQUA
                + ". Right-click the book for the menu, arm a point, then left-click the block with the wand.");
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
            p.sendMessage(ChatColor.GRAY + "Left the editor for " + s.map + ".");
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
        return menu(p.getUniqueId(), s, page, team, p.getName());
    }

    public Inventory menu(UUID owner, Session s, int page, String team, String who) {
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
        sb.append("actions=").append(actions.isEmpty() ? 0 : actions.values().iterator().next().size());
        actions.remove(s.player);
        return sb.toString();
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
            if (s.armed == null) {
                e.getPlayer().sendMessage(ChatColor.RED + "Nothing armed - open the book and pick a point first.");
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
        if (a.startsWith("page:")) { p.openInventory(menu(p, s, Integer.parseInt(a.substring(5)), null)); return; }
        if (a.startsWith("team:")) { p.openInventory(menu(p, s, 2, a.substring(5))); return; }
        if (a.equals("limit")) {
            int y = p.getLocation().getBlockY();
            write(s, "build-limit", String.valueOf(y));
            p.sendMessage(ChatColor.GREEN + "Build limit set to y=" + y + ".");
            p.openInventory(menu(p, s, 10, null));
            return;
        }
        if (a.startsWith("new:")) {
            String key = a.substring(4);
            s.armed = key;
            s.armedBed = false;
            s.armedIndex = -2;
            p.closeInventory();
            p.sendMessage(ChatColor.YELLOW + "Armed a new " + key + " - left-click the block with the wand.");
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
                p.sendMessage(ChatColor.GREEN + "Removed " + gen + " " + (idx + 1) + ".");
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
            p.sendMessage(ChatColor.YELLOW + "Armed " + ChatColor.WHITE + s.armed + ChatColor.YELLOW
                    + " - left-click the block with the wand (or shift-click the item to use where you stand).");
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Holder) e.setCancelled(true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Session s = sessions.get(e.getPlayer().getUniqueId());
        if (s != null) close(s, false);
    }

    // ===================================================================================== writing

    /** The wand landed: write the armed point at that block. */
    private void apply(Player p, Session s, Block block) {
        boolean ok = write(s, s.armed, s.armedBed, null, block);
        if (ok) {
            p.sendMessage(ChatColor.GREEN + "Set " + s.armed + " to " + block.getX() + "," + block.getY() + ","
                    + block.getZ() + " on " + s.map + ChatColor.DARK_GRAY + " (map.yml written)");
        } else {
            p.sendMessage(ChatColor.RED + "Could not write " + s.armed + " into map.yml.");
        }
        s.armed = null;
        s.armedBed = false;
        s.armedIndex = -1;
    }

    private boolean write(Session s, String key, String value) {
        boolean ok = MapEditor.setScalar(s.dir, key, value);
        if (ok) service.reload();
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
        if (ok) service.reload();
        return ok;
    }

    // ===================================================================================== helpers

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
