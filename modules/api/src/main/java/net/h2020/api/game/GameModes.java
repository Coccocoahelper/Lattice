package net.h2020.api.game;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every playable mode on this server, built-ins plus anything registered at runtime. The lobby menus, the player counts
 * and the admin command tree all read this list, so a new mode (or a whole new game, once its family has a provider)
 * shows up everywhere without touching core.
 */
public final class GameModes {
    private static final Map<String, GameMode> BY_ID = new LinkedHashMap<>();
    /** menus.yml overrides, keyed by mode id. */
    private static final Map<String, Map<String, Object>> MENU = new LinkedHashMap<>();
    private static boolean installed;

    private GameModes() {}

    /** Register the built-in {@link GameType} values. Called once by the core module. */
    public static synchronized void install() {
        if (installed) return;
        installed = true;
        for (GameType t : GameType.values()) BY_ID.put(t.id().toLowerCase(), t);
    }

    public static synchronized void register(GameMode mode) {
        install();
        if (mode != null && mode.id() != null) BY_ID.put(mode.id().toLowerCase(), mode);
    }

    public static synchronized List<GameMode> all() {
        install();
        return new ArrayList<>(BY_ID.values());
    }

    public static synchronized List<GameMode> of(GameFamily family) {
        install();
        List<GameMode> out = new ArrayList<>();
        for (GameMode m : BY_ID.values()) if (m.family() == family) out.add(m);
        out.sort(Comparator.<GameMode>comparingInt(m -> display(m).order).thenComparing(GameMode::id));
        return out;
    }

    /** Read menus.yml: icon, data, order and the three text lines per mode id. Missing file = code defaults. */
    public static synchronized void loadMenus(java.io.File dataFolder) {
        java.io.File f = new java.io.File(dataFolder, "menus.yml");
        if (!f.isFile()) return;
        org.bukkit.configuration.file.FileConfiguration c =
                org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(f);
        for (String id : c.getKeys(false)) {
            if (c.getConfigurationSection(id) == null) continue;
            Map<String, Object> o = new LinkedHashMap<String, Object>();
            for (String k : c.getConfigurationSection(id).getKeys(false)) o.put(k, c.get(id + "." + k));
            MENU.put(id.toLowerCase(), o);
        }
    }

    /** Effective menu presentation: the mode's own defaults with menus.yml applied on top. */
    public static ModeDisplay display(GameMode mode) {
        ModeDisplay d = new ModeDisplay(mode.icon(), mode.iconData(), mode.menuOrder(), mode.menuName(),
                mode.menuSubtitle(), mode.description());
        Map<String, Object> o = MENU.get(mode.id().toLowerCase());
        if (o == null) return d;
        if (o.get("icon") instanceof String) {
            org.bukkit.Material m = org.bukkit.Material.getMaterial(((String) o.get("icon")).toUpperCase());
            if (m != null) d.icon = m;
        }
        if (o.get("data") instanceof Number) d.data = ((Number) o.get("data")).shortValue();
        if (o.get("order") instanceof Number) d.order = ((Number) o.get("order")).intValue();
        if (o.get("name") instanceof String) d.name = org.bukkit.ChatColor.translateAlternateColorCodes('&', (String) o.get("name"));
        if (o.get("subtitle") instanceof String) d.subtitle = (String) o.get("subtitle");
        if (o.get("description") instanceof String) d.description = (String) o.get("description");
        if (o.get("lore") instanceof java.util.List) {
            StringBuilder sb = new StringBuilder();
            for (Object line : (java.util.List<?>) o.get("lore")) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(line);
            }
            d.description = sb.toString();
        }
        return d;
    }

    public static synchronized GameMode byId(String id) {
        install();
        return id == null ? null : BY_ID.get(id.toLowerCase());
    }

    /** The type to fall back to when a family has no registered mode (should not happen once a module is enabled). */
    public static synchronized GameMode first(GameFamily family) {
        List<GameMode> l = of(family);
        return l.isEmpty() ? null : l.get(0);
    }
}
