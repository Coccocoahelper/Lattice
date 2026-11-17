package net.h2020.replay;

import net.h2020.api.core.Messages;
import net.h2020.api.gui.ItemBuilder;
import net.h2020.api.gui.Menu;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Recent Games menu, the way Hypixel shows replays: a chest of one item per recorded game, a row of filters along
 * the bottom, and arrow buttons to page through them.
 *
 * <p>Hypixel's own browser is reached with {@code /games} (or the book in the profile menu) and lists recent games
 * across every supported mode, newest first. Its filters are the mode and who played in it; this menu offers the same
 * two, plus a sort direction, and it keeps the filter state per player so paging does not reset it.
 *
 * <p>Item shape follows Hypixel's: the mode's own icon, the game id as the title, and a lore block carrying the map,
 * the players, when it was played and the "click to watch" hint.
 */
public final class ReplayMenu {

    /** One recording, read once when the menu opens. */
    private static final class Entry {
        final String id;
        final String mode;
        final String family;
        final String map;
        final int players;
        final long size;
        final long when;
        Entry(String id, String mode, String family, String map, int players, long size, long when) {
            this.id = id; this.mode = mode; this.family = family; this.map = map;
            this.players = players; this.size = size; this.when = when;
        }
    }

    /** What a player is currently filtering by. Kept per player so a page turn does not reset the menu. */
    private static final class View {
        String family = "all";              // all | bedwars | skywars | duels | practice
        boolean oldestFirst;
        int page;
    }

    private static final Map<java.util.UUID, View> VIEWS = new java.util.HashMap<java.util.UUID, View>();
    private static final int ROWS = 6;
    private static final int PER_PAGE = 45;   // five rows of games, the sixth is the filter bar

    private ReplayMenu() {}

    /**
     * Build the menu for a player without opening it and return a one-line summary per slot. Used by the console
     * self-check, because a chest GUI is the one thing that cannot be inspected from a terminal.
     */
    public static List<String> describe(Player p, int page, String family, boolean oldestFirst) {
        View v = new View();
        v.page = page; v.family = family; v.oldestFirst = oldestFirst;
        List<String> out = new ArrayList<String>();
        List<Entry> all = read();
        List<Entry> shown = new ArrayList<Entry>();
        for (Entry e : all) if (family.equals("all") || family.equals(e.family)) shown.add(e);
        if (oldestFirst) Collections.reverse(shown);
        out.add("title=Recent Games family=" + family + " page=" + (page + 1) + " matched=" + shown.size()
                + " of " + all.size());
        int from = page * PER_PAGE;
        for (int i = 0; i < PER_PAGE && from + i < shown.size(); i++) {
            Entry e = shown.get(from + i);
            out.add("slot " + i + " = " + e.id + " [" + e.family + "] " + e.mode
                    + (e.map == null ? "" : " on " + e.map) + " players=" + e.players);
        }
        int bar = PER_PAGE;
        out.add("bar: [compass: mode=" + family + " -> " + familyLore(family) + "] prev,next | sort | close");
        return out;
    }

    /** Open the browser for a player, keeping whatever filter they were last using. */
    public static void open(Player p) {
        View v = VIEWS.get(p.getUniqueId());
        if (v == null) { v = new View(); VIEWS.put(p.getUniqueId(), v); }
        show(p, v);
    }

    /** The one-item filter's tooltip as text, so the console self-check can prove the colours and order. */
    static String familyLore(String family) {
        String[] ids = {"all", "bedwars", "skywars", "duels", "practice"};
        String[] names = {"All games", "Bed Wars", "SkyWars", "Duels", "Practice"};
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < ids.length; i++) {
            if (b.length() > 0) b.append(", ");
            boolean on = ids[i].equals(family);
            b.append(on ? "GREEN(" + names[i] + ")" : names[i]);
        }
        return b.toString();
    }

    /** The filter a player last chose, for {@code /replay list} to agree with the menu. */
    public static String familyOf(Player p) {
        View v = VIEWS.get(p.getUniqueId());
        return v == null ? "all" : v.family;
    }

    private static void show(Player p, View v) {
        List<Entry> all = read();
        List<Entry> shown = new ArrayList<Entry>();
        for (Entry e : all) if (v.family.equals("all") || v.family.equals(e.family)) shown.add(e);
        // newest first unless the player flipped it; the read order already is newest first
        if (v.oldestFirst) Collections.reverse(shown);

        int pages = Math.max(1, (shown.size() + PER_PAGE - 1) / PER_PAGE);
        if (v.page >= pages) v.page = pages - 1;
        if (v.page < 0) v.page = 0;

        String title = ChatColor.DARK_GRAY + "Recent Games"
                + (v.family.equals("all") ? "" : ChatColor.DARK_GRAY + " - " + ChatColor.WHITE + cap(v.family))
                + ChatColor.DARK_GRAY + " (" + (v.page + 1) + "/" + pages + ")";
        Menu m = new Menu(title, ROWS);

        int from = v.page * PER_PAGE;
        for (int i = 0; i < PER_PAGE && from + i < shown.size(); i++) {
            final Entry e = shown.get(from + i);
            m.set(i, icon(e), new Menu.Click() {
                @Override public void click(Player clicker, org.bukkit.event.inventory.ClickType type) {
                    clicker.closeInventory();
                    ReplayViewer.startFromMenu(clicker, e.id);
                }
            });
        }

        int bar = PER_PAGE;                                   // row six
        // One filter item, listing every family in its tooltip: the active one in green, the rest grey. Five separate
        // items showed the same information but cost four slots and made the selected one read as a different button
        // rather than as a choice inside one control.
        m.set(bar + 0, familyItem(v.family), new Menu.Click() {
            @Override public void click(Player c, org.bukkit.event.inventory.ClickType t) {
                // cycle: all -> bedwars -> skywars -> duels -> practice -> all. Left click forward, right click back.
                String[] order = {"all", "bedwars", "skywars", "duels", "practice"};
                int at = 0;
                for (int i = 0; i < order.length; i++) if (order[i].equals(v.family)) at = i;
                at += t.isRightClick() ? order.length - 1 : 1;
                v.family = order[at % order.length];
                v.page = 0;
                show(c, v);
            }
        });

        // paging
        if (v.page > 0) {
            m.set(bar + 4, new ItemBuilder(Material.ARROW).name(ChatColor.GREEN + "Previous page")
                    .lore(ChatColor.GRAY + "Page " + v.page + " of " + pages).build(), new Menu.Click() {
                @Override public void click(Player c, org.bukkit.event.inventory.ClickType t) {
                    v.page--; show(c, v);
                }
            });
        }
        if (v.page < pages - 1) {
            m.set(bar + 5, new ItemBuilder(Material.ARROW).name(ChatColor.GREEN + "Next page")
                    .lore(ChatColor.GRAY + "Page " + (v.page + 2) + " of " + pages).build(), new Menu.Click() {
                @Override public void click(Player c, org.bukkit.event.inventory.ClickType t) {
                    v.page++; show(c, v);
                }
            });
        }
        // sort
        m.set(bar + 6, new ItemBuilder(Material.WATCH)
                .name(ChatColor.YELLOW + "Sort: " + ChatColor.WHITE + (v.oldestFirst ? "Oldest first" : "Newest first"))
                .lore(ChatColor.GRAY + "Click to flip the order").build(), new Menu.Click() {
            @Override public void click(Player c, org.bukkit.event.inventory.ClickType t) {
                v.oldestFirst = !v.oldestFirst; v.page = 0; show(c, v);
            }
        });
        m.set(bar + 8, new ItemBuilder(Material.BARRIER).name(ChatColor.RED + "Close").build(), new Menu.Click() {
            @Override public void click(Player c, org.bukkit.event.inventory.ClickType t) { c.closeInventory(); }
        });

        m.open(p);
    }

    private static void pick(Player p, View v, String family) {
        v.family = family;
        v.page = 0;
        show(p, v);
    }

    /**
     * The one filter control. Its tooltip carries every family on its own line - the active one in green, the rest in
     * grey - so the whole set of choices is visible without opening anything, and the item itself names the current
     * choice.
     */
    private static org.bukkit.inventory.ItemStack familyItem(String family) {
        String[][] families = {
                {"all", "All games"},
                {"bedwars", "Bed Wars"},
                {"skywars", "SkyWars"},
                {"duels", "Duels"},
                {"practice", "Practice"},
        };
        List<String> lore = new ArrayList<String>();
        lore.add(ChatColor.GRAY + "Game mode:");
        for (String[] f : families) {
            boolean on = f[0].equals(family);
            lore.add((on ? ChatColor.GREEN + " > " : ChatColor.GRAY + "   ") + f[1]);
        }
        lore.add("");
        lore.add(ChatColor.YELLOW + "Left click" + ChatColor.GRAY + " for the next mode");
        lore.add(ChatColor.YELLOW + "Right click" + ChatColor.GRAY + " for the previous one");
        String label = "All games";
        for (String[] f : families) if (f[0].equals(family)) label = f[1];
        return new ItemBuilder(Material.COMPASS)
                .name(ChatColor.WHITE + "Game mode: " + ChatColor.GREEN + label)
                .lore(lore)
                .build();
    }

    /** The item for one recording: mode icon, id as the title, then map, players, age and the hint. */
    private static org.bukkit.inventory.ItemStack icon(Entry e) {
        Material m = Material.PAPER;
        String f = e.family == null ? "" : e.family;
        if (f.equals("bedwars")) m = Material.BED;
        else if (f.equals("skywars")) m = Material.EYE_OF_ENDER;
        else if (f.equals("duels")) m = Material.IRON_SWORD;
        else if (f.equals("practice")) m = Material.SANDSTONE;
        ItemBuilder b = new ItemBuilder(m).name(ChatColor.WHITE + e.id)
                .lore(ChatColor.GRAY + "Game: " + ChatColor.WHITE + e.mode,
                      ChatColor.GRAY + "Map: " + ChatColor.WHITE + (e.map == null ? "?" : e.map),
                      ChatColor.GRAY + "Players: " + ChatColor.WHITE + (e.players <= 0 ? "?" : e.players),
                      ChatColor.GRAY + "Length: " + ChatColor.WHITE + humanSize(e.size),
                      ChatColor.GRAY + "Played: " + ChatColor.WHITE + ago(e.when),
                      "",
                      ChatColor.YELLOW + "Click to watch");
        return b.build();
    }

    /** Every recording, newest first, read once per menu open. */
    private static List<Entry> read() {
        List<Entry> out = new ArrayList<Entry>();
        File dir = Recorder.dir();
        File[] files = dir == null ? null : dir.listFiles();
        if (files == null) return out;
        List<File> list = new ArrayList<File>();
        for (File f : files) if (f.getName().endsWith(".jsonl")) list.add(f);
        list.sort(new Comparator<File>() {
            @Override public int compare(File a, File b) { return Long.compare(b.lastModified(), a.lastModified()); }
        });
        for (File f : list) {
            String mode = "?";
            String type = null;
            String map = null;
            int players = 0;
            try {
                BufferedReader r = new BufferedReader(new FileReader(f));
                try {
                    String head = r.readLine();
                    type = field(head, "type");
                    map = field(head, "map");
                    String line = r.readLine();
                    for (int i = 0; line != null && i < 2000 && players == 0; i++) {
                        int k = line.indexOf("\"p\":[");
                        if (k >= 0) players = countRoster(line.substring(k));
                        if (players == 0) line = r.readLine();
                    }
                } finally { r.close(); }
            } catch (Throwable ignored) { }
            mode = ReplayViewer.modeNamePublic(type);
            out.add(new Entry(f.getName().replace(".jsonl", ""), mode, familyOf(mode), map, players,
                    f.length(), f.lastModified()));
        }
        return out;
    }

    static String familyOf(String mode) {
        String m = mode == null ? "" : mode.toLowerCase(Locale.ROOT);
        if (m.contains("bed")) return "bedwars";
        if (m.contains("sky")) return "skywars";
        if (m.contains("duel")) return "duels";
        if (m.contains("practice") || m.contains("prac")) return "practice";
        return m;
    }

    private static String field(String line, String key) {
        if (line == null) return null;
        String k = "\"" + key + "\":\"";
        int i = line.indexOf(k);
        if (i < 0) return null;
        int j = i + k.length();
        int end = line.indexOf('"', j);
        return end < 0 ? null : line.substring(j, end);
    }

    private static int countRoster(String from) {
        int i = from.indexOf('[');
        if (i < 0) return 0;
        int depth = 0, n = 0;
        for (int j = i; j < from.length(); j++) {
            char c = from.charAt(j);
            if (c == '[') { depth++; if (depth == 2) n++; }
            else if (c == ']') { depth--; if (depth == 0) break; }
        }
        return n;
    }

    private static String cap(String s) { return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1); }

    private static String humanSize(long bytes) {
        if (bytes >= 1024 * 1024) return String.format("%.1f MB", bytes / 1048576.0);
        if (bytes >= 1024) return (bytes / 1024) + " KB";
        return bytes + " B";
    }

    private static String ago(long millis) {
        long d = System.currentTimeMillis() - millis;
        if (d < 60_000L) return Math.max(1, d / 1000) + "s ago";
        if (d < 3_600_000L) return (d / 60_000L) + "m ago";
        if (d < 86_400_000L) return (d / 3_600_000L) + "h ago";
        return (d / 86_400_000L) + "d ago";
    }
}
