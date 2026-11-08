package net.h2020.core;

import net.h2020.api.bot.CheatProfile;
import net.h2020.api.core.Rank;
import net.h2020.api.game.GameType;
import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

/** Snapshot of the "core:" config section. Re-read on /h2020 reload. */
public final class CoreConfig {
    public String date = "07/14/20";
    /** Server identity, from network.yml. Falls back to the Hypixel values above when the file is absent. */
    public String networkName = "MC.HYPIXEL.NET";
    public String motd = "&e&lHYPIXEL &7- &fBed Wars, SkyWars and more";
    public Rank defaultRank = Rank.MVP_PLUS;
    public ChatColor defaultPlusColor = ChatColor.RED;
    public int defaultLevel = 87;
    public String tabHeader = ChatColor.AQUA + "You are playing on " + ChatColor.YELLOW + ChatColor.BOLD + "MC.HYPIXEL.NET";
    public String tabFooter = ChatColor.AQUA + "Ranks, Boosters & MORE! " + ChatColor.RED + ChatColor.BOLD + "STORE.HYPIXEL.NET";
    public int lobbyNumber = 3;
    public int lobbyCount = 12;
    public int lobbyBots = 15;
    public boolean fakePlayerCounts = false;
    public int backgroundGames = 0;
    public final List<GameType> backgroundTypes = new ArrayList<>();
    public boolean botFill = true;
    public double fillMinSeconds = 20, fillMaxSeconds = 60, fillSpeed = 1.0;
    public double cheaterPercent = 0;
    public final List<CheatProfile> cheatTypes = new ArrayList<>();
    public double requeueChance = 0.55, lobbyChance = 0.25, leaveAfterElimination = 0.7;
    public String npcSkinBedwars = "", npcSkinSkywars = "";
    /** Players connecting from this machine (localhost) are made op on join, so a fresh server is usable at once. */
    public boolean opLocalPlayers = true;

    private JavaPlugin plugin;

    /** network.yml owns the brand: name, tab lines, MOTD and fake counts. Read after config.yml so it wins. */
    public void applyNetwork(JavaPlugin plugin) {
        java.io.File f = new java.io.File(plugin.getDataFolder(), "network.yml");
        if (!f.isFile()) return;
        org.bukkit.configuration.file.FileConfiguration n =
                org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(f);
        if (n.isString("brand.name")) networkName = n.getString("brand.name");
        if (n.isString("brand.tab-header")) tabHeader = color(n.getString("brand.tab-header"));
        if (n.isString("brand.tab-footer")) tabFooter = color(n.getString("brand.tab-footer"));
        if (n.isString("brand.motd")) motd = n.getString("brand.motd");
        if (n.isSet("brand.fake-player-counts")) fakePlayerCounts = n.getBoolean("brand.fake-player-counts");
        if (n.isSet("brand.lobby-number")) lobbyNumber = n.getInt("brand.lobby-number");
        net.h2020.api.game.Families.load(plugin.getDataFolder());
        net.h2020.api.game.GameRules.load(plugin.getDataFolder());
        net.h2020.api.game.GameModes.loadMenus(plugin.getDataFolder());
    }

    public void load(JavaPlugin plugin) {
        this.plugin = plugin;
        ConfigurationSection c = plugin.getConfig().getConfigurationSection("core");
        if (c == null) c = new MemoryConfiguration();
        date = c.getString("date", date);
        Rank r = parseEnum(Rank.class, c.getString("default-rank"));
        if (r != null) defaultRank = r;
        ChatColor pc = parseEnum(ChatColor.class, c.getString("default-plus-color"));
        if (pc != null) defaultPlusColor = pc;
        defaultLevel = c.getInt("default-level", defaultLevel);
        if (c.isString("tab.header")) tabHeader = color(c.getString("tab.header"));
        if (c.isString("tab.footer")) tabFooter = color(c.getString("tab.footer"));
        lobbyNumber = Math.max(1, c.getInt("lobby-number", lobbyNumber));
        lobbyCount = Math.max(1, Math.min(28, c.getInt("lobby-count", lobbyCount)));
        lobbyBots = Math.max(0, c.getInt("lobby-bots", lobbyBots));
        fakePlayerCounts = c.getBoolean("fake-player-counts", fakePlayerCounts);
        backgroundGames = Math.max(0, c.getInt("background-games", backgroundGames));
        backgroundTypes.clear();
        for (String s : c.getStringList("background-types")) {
            GameType t = GameType.byId(s);
            if (t != null) backgroundTypes.add(t);
        }
        if (backgroundTypes.isEmpty()) {
            backgroundTypes.add(GameType.BEDWARS_SOLO);
            backgroundTypes.add(GameType.SKYWARS_SOLO_NORMAL);
        }
        botFill = c.getBoolean("bot-fill.enabled", botFill);
        fillMinSeconds = Math.max(1, c.getDouble("bot-fill.min-seconds", fillMinSeconds));
        fillMaxSeconds = Math.max(fillMinSeconds, c.getDouble("bot-fill.max-seconds", fillMaxSeconds));
        fillSpeed = Math.max(0.05, c.getDouble("bot-fill.speed", fillSpeed));
        cheaterPercent = Math.max(0, Math.min(100, c.getDouble("bot-fill.cheater-percent", cheaterPercent)));
        cheatTypes.clear();
        for (String s : c.getStringList("bot-fill.cheat-types")) {
            CheatProfile cp = parseEnum(CheatProfile.class, s);
            if (cp != null && cp != CheatProfile.NONE) cheatTypes.add(cp);
        }
        if (cheatTypes.isEmpty()) {
            for (CheatProfile cp : CheatProfile.values()) if (cp != CheatProfile.NONE) cheatTypes.add(cp);
        }
        requeueChance = c.getDouble("bot-churn.requeue-chance", requeueChance);
        lobbyChance = c.getDouble("bot-churn.lobby-chance", lobbyChance);
        leaveAfterElimination = c.getDouble("bot-churn.leave-after-elimination", leaveAfterElimination);
        npcSkinBedwars = c.getString("npc-skins.bedwars", "");
        npcSkinSkywars = c.getString("npc-skins.skywars", "");
        opLocalPlayers = c.getBoolean("op-local-players", opLocalPlayers);
    }

    /** Persist the fake date (used by /h2020 date). */
    public void saveDate(String newDate) {
        date = newDate;
        plugin.getConfig().set("core.date", newDate);
        plugin.saveConfig();
    }

    public static String color(String s) { return s == null ? "" : ChatColor.translateAlternateColorCodes('&', s); }

    public static <E extends Enum<E>> E parseEnum(Class<E> type, String s) {
        if (s == null) return null;
        try { return Enum.valueOf(type, s.trim().toUpperCase().replace(' ', '_').replace("+", "_PLUS")); }
        catch (IllegalArgumentException e) { return null; }
    }
}
