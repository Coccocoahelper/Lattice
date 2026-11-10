package net.h2020.api.core;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Loads the configurable sidebars from {@code sidebar.yml} and bridges to PlaceholderAPI reflectively (the API is
 * deliberately not a compile dependency: it is not in libs/ and servers without it must still run).
 */
public final class Sidebars {
    public static final String FILE = "sidebar.yml";
    private static final String PAPI = "PlaceholderAPI";
    private static final String PAPI_CLASS = "me.clip.placeholderapi.PlaceholderAPI";

    private static JavaPlugin plugin;
    private static final Map<String, SidebarTemplate> BOARDS = new LinkedHashMap<String, SidebarTemplate>();
    private static final Set<String> ALL = new LinkedHashSet<String>();

    /** Resolved once, when PlaceholderAPI is present and enabled. */
    private static Method setPlaceholders;

    private Sidebars() {}

    public static synchronized void init(JavaPlugin plugin) {
        Sidebars.plugin = plugin;
        File file = new File(plugin.getDataFolder(), FILE);
        if (!file.isFile()) {
            try {
                plugin.saveResource(FILE, false);
            } catch (Throwable ignored) {
                // the resource is optional: a server may ship its own sidebar.yml or none at all
            }
        }
        reload();
    }

    /** Re-reads the file from disk. A missing/invalid file simply leaves no boards behind. */
    public static synchronized void reload() {
        BOARDS.clear();
        ALL.clear();
        if (plugin == null) return;
        File file = new File(plugin.getDataFolder(), FILE);
        if (!file.isFile()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection boards = yaml.getConfigurationSection("boards");
        if (boards == null) return;
        for (String name : boards.getKeys(false)) {
            ALL.add(name);
            ConfigurationSection board = boards.getConfigurationSection(name);
            if (board == null) continue;
            if (!board.getBoolean("enabled", true)) continue;
            String title = board.getString("title", "");
            List<SidebarTemplate.Line> lines = new ArrayList<SidebarTemplate.Line>();
            List<?> raw = board.getList("lines");
            if (raw != null) {
                for (Object entry : raw) {
                    if (entry instanceof String) {
                        lines.add(new SidebarTemplate.Line((String) entry, Collections.<String>emptySet()));
                    } else if (entry instanceof Map) {
                        Map<?, ?> map = (Map<?, ?>) entry;
                        Object text = map.get("text");
                        Set<String> when = new LinkedHashSet<String>();
                        Object condition = map.get("when");
                        if (condition != null) {
                            for (String part : String.valueOf(condition).split(",")) {
                                String flag = part.trim();
                                if (!flag.isEmpty()) when.add(flag);
                            }
                        }
                        lines.add(new SidebarTemplate.Line(text == null ? "" : String.valueOf(text), when));
                    }
                }
            }
            BOARDS.put(name, new SidebarTemplate(name, title, lines));
        }
    }

    /** The board, or null when it is absent or switched off. */
    public static synchronized SidebarTemplate get(String name) {
        if (name == null) return null;
        return BOARDS.get(name);
    }

    /** Every board in the file, including disabled ones. */
    public static synchronized Set<String> names() {
        return new LinkedHashSet<String>(ALL);
    }

    /** PlaceholderAPI bridge: never throws, returns the input unchanged when PlaceholderAPI is unavailable. */
    public static String apply(Player p, String text) {
        if (p == null || text == null || text.indexOf('%') < 0) return text;
        Method method = bridge();
        if (method == null) return text;
        try {
            Object out = method.invoke(null, p, text);
            return out instanceof String ? (String) out : text;
        } catch (Throwable ignored) {
            return text;
        }
    }

    /** Whether the reflective PlaceholderAPI bridge resolved. */
    public static boolean placeholderApi() {
        return bridge() != null;
    }

    private static synchronized Method bridge() {
        if (setPlaceholders != null) return setPlaceholders;
        try {
            if (!Bukkit.getPluginManager().isPluginEnabled(PAPI)) return null;
            Class<?> type = Class.forName(PAPI_CLASS);
            Method method = type.getMethod("setPlaceholders", Player.class, String.class);
            method.setAccessible(true);
            setPlaceholders = method;
            return method;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
