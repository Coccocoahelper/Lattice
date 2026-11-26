package net.h2020.core.lobby;

import net.h2020.api.H2020;
import net.h2020.api.game.GameFamily;
import net.h2020.api.game.GameManager;
import net.h2020.api.game.GameMode;
import net.h2020.api.game.GameType;
import net.h2020.api.gui.ItemBuilder;
import net.h2020.api.gui.Menu;
import net.h2020.api.map.MapService;
import net.h2020.api.map.MapTemplate;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The map selector, built the way Hypixel's is.
 *
 * <p>Hypixel's selector is two things stacked: a row of mode buttons across the top, and beneath it a paginated grid
 * of maps for the mode you picked. Clicking a map does not change the queue - it marks that map as the one you want, and
 * you are then placed into a game on it rather than into whichever map the queue happens to open. The last row is
 * controls: previous page, next page, the current mode as a summary item, and a Play button that commits.
 *
 * <p>That is worth reproducing rather than simplifying to "click a map to play", because the difference is the whole
 * point of the feature: on a server with 265 Bed Wars maps, a player who wants Lighthouse wants Lighthouse, and a
 * selector that drops them into a random game does not do what it says.
 *
 * <p>Two deliberate departures. The grid shows only maps the mode can actually run - a SkyWars solos queue skips a
 * 12-team map only if the count disagrees, and an unplayable map is shown greyed with the reason rather than hidden, so
 * an empty-looking selector is explainable. And the mode row is the Lattice mode list, which is smaller than Hypixel's,
 * so it fits on one row with room to spare.
 */
public final class MapSelector {

    /** Per-player selection, kept so re-opening the menu shows what was chosen. */
    private static final Map<UUID, State> STATES = new HashMap<UUID, State>();
    private static final int ROWS = 6;
    private static final int PER_PAGE = 28;               // 4 rows of 7, centred
    private static final int[] GRID = gridSlots();

    /** Modes shown in the top row, in Hypixel's order: Bed Wars then SkyWars. Duels has its own command. */
    private static final GameType[] MODES = {
            GameType.BEDWARS_SOLO, GameType.BEDWARS_DOUBLES,
            GameType.BEDWARS_3V3V3V3, GameType.BEDWARS_4V4V4V4,
            GameType.SKYWARS_SOLO_NORMAL, GameType.SKYWARS_SOLO_INSANE,
            GameType.SKYWARS_TEAMS_NORMAL, GameType.SKYWARS_TEAMS_INSANE,
    };

    private MapSelector() {}

    private static final class State {
        GameType mode = GameType.BEDWARS_SOLO;
        String map;              // null = let the queue choose
        int page;
        boolean hideUnplayable = true;
    }

    /** Open the selector, defaulting to the mode the player last used. */
    public static void open(Player p) {
        State s = STATES.get(p.getUniqueId());
        if (s == null) { s = new State(); STATES.put(p.getUniqueId(), s); }
        render(p, s);
    }

    /** Open it already on a given mode - used by the lobby NPCs so a play pad lands on the right page. */
    public static void open(Player p, GameType mode) {
        State s = STATES.get(p.getUniqueId());
        if (s == null) { s = new State(); STATES.put(p.getUniqueId(), s); }
        if (mode != null) s.mode = mode;
        render(p, s);
    }

    private static void render(final Player p, final State s) {
        final List<MapTemplate> all = mapsFor(s.mode);
        final List<MapTemplate> shown = new ArrayList<MapTemplate>();
        for (MapTemplate t : all) {
            if (s.hideUnplayable && !playable(t, s.mode)) continue;
            shown.add(t);
        }
        int pages = Math.max(1, (shown.size() + PER_PAGE - 1) / PER_PAGE);
        if (s.page >= pages) s.page = pages - 1;
        if (s.page < 0) s.page = 0;
        // a map that is no longer on the page set (mode changed) must not stay selected invisibly
        if (s.map != null) {
            boolean stillThere = false;
            for (MapTemplate t : shown) if (t.name().equalsIgnoreCase(s.map)) { stillThere = true; break; }
            if (!stillThere) s.map = null;
        }

        String title = ChatColor.DARK_GRAY + "Map Selector " + ChatColor.GRAY + "(" + s.mode.displayName + ") "
                + ChatColor.DARK_GRAY + (s.page + 1) + "/" + pages;
        Menu m = new Menu(title, ROWS);

        // ---- top row: the modes
        int slot = 0;
        for (final GameType t : MODES) {
            boolean on = t == s.mode;
            ItemBuilder b = new ItemBuilder(iconFor(t))
                    .name((on ? ChatColor.GREEN + "" + ChatColor.BOLD : ChatColor.GRAY + "") + t.displayName)
                    .lore(on ? ChatColor.GREEN + "Selected" : ChatColor.YELLOW + "Click to view")
                    .lore(ChatColor.DARK_GRAY + String.valueOf(t.maxPlayers()) + " players, "
                            + mapsFor(t).size() + " maps");
            if (on) b.glow();
            m.set(slot++, b.build(), new Menu.Click() {
                @Override public void click(Player clicker, ClickType type) {
                    s.mode = t;
                    s.page = 0;
                    s.map = null;
                    render(clicker, s);
                }
            });
        }
        // the mode row is 8 wide; slot 8 summarises the selection
        m.set(8, new ItemBuilder(Material.MAP)
                .name(ChatColor.AQUA + "Selection")
                .lore(ChatColor.GRAY + "Mode: " + ChatColor.WHITE + s.mode.displayName)
                .lore(ChatColor.GRAY + "Map: " + ChatColor.WHITE + (s.map == null ? "any" : s.map))
                .lore("")
                .lore(ChatColor.YELLOW + "Click a map below, then Play")
                .build());

        // ---- the map grid
        int from = s.page * PER_PAGE;
        for (int i = 0; i < PER_PAGE && from + i < shown.size(); i++) {
            final MapTemplate t = shown.get(from + i);
            boolean chosen = s.map != null && s.map.equalsIgnoreCase(t.name());
            boolean ok = playable(t, s.mode);
            ItemBuilder b = new ItemBuilder(ok ? Material.PAPER : Material.PAPER)
                    .name((chosen ? ChatColor.GREEN + "" + ChatColor.BOLD : ChatColor.WHITE + "") + t.name())
                    .lore(ChatColor.GRAY + s.mode.displayName + ChatColor.DARK_GRAY + " - " + s.mode.teams() + " teams");
            String added = t.addedDate();
            if (added != null && !added.isEmpty()) b.lore(ChatColor.DARK_GRAY + "Added " + added);
            if (t.buildFloorY() >= 0) {
                b.lore(ChatColor.DARK_GRAY + "Build " + t.buildFloorY() + " to " + t.buildLimitY());
            }
            if (!ok) {
                b.lore(ChatColor.RED + "Not playable yet: no arena points");
            } else if (chosen) {
                b.lore(ChatColor.GREEN + "Selected - press Play");
            } else {
                b.lore(ChatColor.YELLOW + "Click to select");
            }
            if (chosen) b.glow();
            m.set(GRID[i], b.build(), new Menu.Click() {
                @Override public void click(Player clicker, ClickType type) {
                    if (!ok) return;
                    s.map = chosen ? null : t.name();
                    render(clicker, s);
                }
            });
        }
        if (shown.isEmpty()) {
            m.set(GRID[0], new ItemBuilder(Material.BARRIER)
                    .name(ChatColor.RED + "No playable maps")
                    .lore(ChatColor.GRAY + "Every " + s.mode.displayName + " map needs its arena points authored.")
                    .lore(ChatColor.GRAY + "Show them anyway with the filter below.")
                    .build());
        }

        // ---- bottom row: controls
        int bar = 45;
        m.set(bar, new ItemBuilder(Material.ARROW)
                .name(ChatColor.GREEN + "Previous page")
                .build(), new Menu.Click() {
            @Override public void click(Player clicker, ClickType type) {
                if (s.page > 0) { s.page--; render(clicker, s); }
            }
        });
        m.set(bar + 1, new ItemBuilder(Material.ARROW)
                .name(ChatColor.GREEN + "Next page")
                .build(), new Menu.Click() {
            @Override public void click(Player clicker, ClickType type) {
                int pages = Math.max(1, (shown.size() + PER_PAGE - 1) / PER_PAGE);
                if (s.page < pages - 1) { s.page++; render(clicker, s); }
            }
        });
        m.set(bar + 3, new ItemBuilder(Material.HOPPER)
                .name(ChatColor.AQUA + "Filter")
                .lore(ChatColor.GRAY + "Unplayable maps: " + (s.hideUnplayable
                        ? ChatColor.RED + "hidden" : ChatColor.GREEN + "shown"))
                .lore("")
                .lore(ChatColor.YELLOW + "Click to toggle")
                .lore(ChatColor.DARK_GRAY + (shown.size() + " shown of " + all.size() + " total"))
                .build(), new Menu.Click() {
            @Override public void click(Player clicker, ClickType type) {
                s.hideUnplayable = !s.hideUnplayable;
                s.page = 0;
                render(clicker, s);
            }
        });
        m.set(bar + 5, new ItemBuilder(Material.EMERALD_BLOCK)
                .name(ChatColor.GREEN + "" + ChatColor.BOLD + "Play " + s.mode.displayName)
                .lore(ChatColor.GRAY + "Map: " + ChatColor.WHITE + (s.map == null ? "any" : s.map))
                .lore("")
                .lore(ChatColor.YELLOW + "Click to join")
                .build(), new Menu.Click() {
            @Override public void click(Player clicker, ClickType type) {
                play(clicker, s);
            }
        });
        m.set(bar + 7, new ItemBuilder(Material.BARRIER)
                .name(ChatColor.RED + "Close")
                .build(), new Menu.Click() {
            @Override public void click(Player clicker, ClickType type) { clicker.closeInventory(); }
        });

        m.open(p);
    }

    /** Join the chosen game, on the chosen map when one is chosen. */
    private static void play(Player p, State s) {
        GameManager gm = H2020.find(GameManager.class);
        MapService maps = H2020.find(MapService.class);
        if (gm == null || maps == null) {
            p.sendMessage(ChatColor.RED + "Matchmaking is not available right now.");
            return;
        }
        p.closeInventory();
        if (s.map == null) {
            gm.queue(p, s.mode);
            p.sendMessage(ChatColor.GRAY + "Joining " + ChatColor.WHITE + s.mode.displayName
                    + ChatColor.GRAY + " on any map...");
            return;
        }
        MapTemplate t = maps.byName(s.map);
        if (t == null) {
            gm.queue(p, s.mode);
            p.sendMessage(ChatColor.RED + "That map is no longer loaded - joining any " + s.mode.displayName + ".");
            return;
        }
        gm.queue(p, s.mode, t);
        p.sendMessage(ChatColor.GRAY + "Joining " + ChatColor.WHITE + s.mode.displayName
                + ChatColor.GRAY + " on " + ChatColor.WHITE + t.name() + ChatColor.GRAY + "...");
    }

    /**
     * Build the menu's contents as text, without opening it.
     *
     * <p>A chest GUI is the one thing that cannot be inspected from a terminal, so this is how the layout, the mode row,
     * the map grid and the playability filter are checked on a live server: {@code /maps describe} prints it.
     */
    public static List<String> describe(Player p, GameType mode, int page) {
        List<String> out = new ArrayList<String>();
        GameType m = mode == null ? GameType.BEDWARS_SOLO : mode;
        List<MapTemplate> all = mapsFor(m);
        int playable = 0;
        for (MapTemplate t : all) if (playable(t, m)) playable++;
        out.add("mode=" + m.id() + " (" + m.displayName + ") teams=" + m.teams()
                + " maps=" + all.size() + " playable=" + playable);
        out.add("modes row: " + modeRow());
        int pages = Math.max(1, (playable + PER_PAGE - 1) / PER_PAGE);
        out.add("grid: " + PER_PAGE + " per page, " + pages + " page(s), slots "
                + GRID[0] + ".." + GRID[PER_PAGE - 1]);
        int shown = 0;
        for (MapTemplate t : all) {
            if (!playable(t, m)) continue;
            if (shown >= 6) break;
            out.add("  map: " + t.name() + " playable");
            shown++;
        }
        if (playable == 0) out.add("  (no playable maps - every " + m.displayName + " map needs arena points)");
        out.add("bar: prev, next | filter | PLAY " + m.displayName + " | close");
        return out;
    }

    private static String modeRow() {
        StringBuilder b = new StringBuilder();
        for (GameType t : MODES) {
            if (b.length() > 0) b.append(", ");
            b.append(t.displayName).append('[').append(mapsFor(t).size()).append(']');
        }
        return b.toString();
    }

    /** Every map of the mode's family whose team count the mode can seat. */
    private static List<MapTemplate> mapsFor(GameMode mode) {
        MapService ms = H2020.find(MapService.class);
        if (ms == null) return Collections.emptyList();
        List<MapTemplate> out = new ArrayList<MapTemplate>();
        try {
            for (MapTemplate t : ms.templates(mode.family())) {
                if (t.name() == null || t.name().isEmpty()) continue;
                if (!fits(t, mode)) continue;
                out.add(t);
            }
        } catch (Throwable ignored) {
        }
        Collections.sort(out, new java.util.Comparator<MapTemplate>() {
            @Override public int compare(MapTemplate a, MapTemplate b) {
                return a.name().compareToIgnoreCase(b.name());
            }
        });
        return out;
    }

    /** Does this map's team count match what the mode seats? */
    private static boolean fits(MapTemplate t, GameMode mode) {
        try {
            List<Integer> counts = t.supportedTeamCounts();
            if (counts == null || counts.isEmpty()) return true;      // unconstrained
            return counts.contains(mode.teams());
        } catch (Throwable ignored) {
            return true;
        }
    }

    /**
     * Can this map actually start a game?
     *
     * <p>The answer is "it has the points the game needs", which differs per family: Bed Wars needs team spawns and falls
     * back to generated islands without them, while SkyWars refuses outright without island cages. A map with no points
     * at all is import-only - a real world nobody has authored yet - and is what the greyed-out state means.
     */
    private static boolean playable(MapTemplate t, GameMode mode) {
        try {
            if (mode.family() == GameFamily.SKYWARS) {
                return !t.points(net.h2020.api.map.MapKeys.islandCage(0)).isEmpty();
            }
            return !t.points(net.h2020.api.map.MapKeys.teamSpawn(net.h2020.api.game.TeamColor.RED)).isEmpty();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Material iconFor(GameType t) {
        switch (t) {
            case BEDWARS_SOLO: return Material.WOOD_SWORD;
            case BEDWARS_DOUBLES: return Material.IRON_SWORD;
            case BEDWARS_3V3V3V3: return Material.DIAMOND_SWORD;
            case BEDWARS_4V4V4V4: return Material.DIAMOND_CHESTPLATE;
            case SKYWARS_SOLO_NORMAL: return Material.FEATHER;
            case SKYWARS_SOLO_INSANE: return Material.FIREWORK;
            case SKYWARS_TEAMS_NORMAL: return Material.GOLD_SWORD;
            case SKYWARS_TEAMS_INSANE: return Material.BLAZE_POWDER;
            default: return Material.PAPER;
        }
    }

    /** 4 rows of 7, centred in a 9-wide grid: columns 1..7, rows 1..4. */
    private static int[] gridSlots() {
        int[] out = new int[PER_PAGE];
        int i = 0;
        for (int row = 1; row <= 4; row++) {
            for (int col = 1; col <= 7; col++) out[i++] = row * 9 + col;
        }
        return out;
    }
}
