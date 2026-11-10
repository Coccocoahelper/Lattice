package net.h2020.core.lobby;

import net.h2020.api.core.SidebarTemplate;
import net.h2020.api.core.Sidebars;
import net.h2020.api.game.GameFamily;
import net.h2020.core.Core;
import net.h2020.core.HypixelFormat;
import net.h2020.core.profile.ProfileData;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Main lobby / Bed Wars lobby / SkyWars lobby sidebars (summer 2020 layouts). */
public final class LobbySidebars {
    private static final String WWW = ChatColor.YELLOW + "www.hypixel.net";
    private final Core core;

    public LobbySidebars(Core core) { this.core = core; }

    public void update(Player p, GameFamily family) {
        ProfileData d = core.profiles.data(p);
        String board = family == null ? "main"
                : family == GameFamily.BEDWARS ? "bedwars-lobby" : "skywars-lobby";
        SidebarTemplate t = Sidebars.get(board);
        if (t == null) {
            // no board in sidebar.yml: keep the hard-coded layout
            if (family == null) {
                core.scoreboards.setSidebar(p, ChatColor.YELLOW + "" + ChatColor.BOLD + "HYPIXEL", main(d));
            } else {
                // title comes from the family profile, so network.yml can rename a lobby without touching this class
                String title = net.h2020.api.game.Families.of(family).colouredSidebarTitle();
                if (family == GameFamily.BEDWARS) core.scoreboards.setSidebar(p, title, bedwars(d));
                else core.scoreboards.setSidebar(p, title, skywars(d));
            }
            return;
        }
        Map<String, String> vars = vars(d, family);
        core.scoreboards.setSidebar(p, t.renderTitle(p, vars), t.render(p, vars, flags(family), null));
    }

    /** The values the hard-coded builders above produced, colour codes included. */
    /** Public so /lattice sidebar can preview a lobby board with the real values filled in. */
    public Map<String, String> varsFor(Player p, GameFamily family) {
        return vars(core.profiles.data(p), family);
    }

    public static Set<String> flagsFor(GameFamily family) { return flags(family); }

    private Map<String, String> vars(ProfileData d, GameFamily family) {
        Map<String, String> v = new HashMap<String, String>();
        if (family == null) {
            v.put("date", HypixelFormat.dateLine("lobby" + d.lobbyNumber));
            v.put("rank", HypixelFormat.rankName(d.rank, d.plusColor));
            v.put("ap", HypixelFormat.commas(d.achievementPoints));
            v.put("guild", d.guild == null ? ChatColor.GRAY + "None" : ChatColor.AQUA + d.guild);
            v.put("level", String.valueOf(d.networkLevel));
            v.put("lobby", "#" + d.lobbyNumber);
            v.put("players", HypixelFormat.commas(core.counts.network()));
        } else if (family == GameFamily.BEDWARS) {
            int need = HypixelFormat.bedwarsExpForStar(d.bwStar);
            v.put("date", HypixelFormat.dateLine("bedwarslobby" + d.lobbyNumber));
            v.put("bw_star", HypixelFormat.bedwarsStarColor(d.bwStar) + "" + d.bwStar + "✫");
            v.put("bw_progress", ChatColor.AQUA + HypixelFormat.shortNumber(d.bwExp) + ChatColor.GRAY + "/"
                    + ChatColor.GREEN + HypixelFormat.shortNumber(need));
            v.put("bw_bar", HypixelFormat.progressBar(d.bwExp / (double) need));
            v.put("bw_loot", ChatColor.YELLOW + String.valueOf(d.bwLootChests));
            v.put("bw_coins", ChatColor.GOLD + HypixelFormat.commas(d.bwCoins));
            v.put("bw_kills", ChatColor.GREEN + HypixelFormat.commas(d.bwKills));
            v.put("bw_wins", ChatColor.GREEN + HypixelFormat.commas(d.bwWins));
        } else {
            v.put("date", HypixelFormat.dateLine("skywarslobby" + d.lobbyNumber));
            v.put("sw_level", HypixelFormat.skywarsLevel(d.swLevel));
            v.put("sw_progress", ChatColor.AQUA + String.valueOf(d.swExpProgressPct) + "%");
            v.put("sw_bar", HypixelFormat.progressBar(d.swExpProgressPct / 100.0));
            v.put("sw_kills", ChatColor.GREEN + HypixelFormat.commas(d.swKills));
            v.put("sw_wins", ChatColor.GREEN + HypixelFormat.commas(d.swWins));
            v.put("sw_coins", ChatColor.GOLD + HypixelFormat.commas(d.swCoins));
            v.put("sw_souls", ChatColor.AQUA + HypixelFormat.commas(d.swSouls));
            v.put("sw_tokens", ChatColor.DARK_GREEN + HypixelFormat.commas(d.swTokens));
        }
        return v;
    }

    private static Set<String> flags(GameFamily family) {
        Set<String> flags = new HashSet<String>();
        flags.add("lobby");
        flags.add(family == null ? "main" : family == GameFamily.BEDWARS ? "bedwars" : "skywars");
        return flags;
    }

    private List<String> main(ProfileData d) {
        List<String> l = new ArrayList<>();
        l.add(HypixelFormat.dateLine("lobby" + d.lobbyNumber));
        l.add("");
        l.add("Rank: " + HypixelFormat.rankName(d.rank, d.plusColor));
        l.add("Achievement Points: " + ChatColor.YELLOW + HypixelFormat.commas(d.achievementPoints));
        l.add("Guild: " + (d.guild == null ? ChatColor.GRAY + "None" : ChatColor.AQUA + d.guild));
        l.add("");
        l.add("Hypixel Level: " + ChatColor.GREEN + d.networkLevel);
        l.add("Lobby: " + ChatColor.GREEN + "#" + d.lobbyNumber);
        l.add("Players: " + ChatColor.GREEN + HypixelFormat.commas(core.counts.network()));
        l.add("");
        l.add(WWW);
        return l;
    }

    private List<String> bedwars(ProfileData d) {
        int need = HypixelFormat.bedwarsExpForStar(d.bwStar);
        List<String> l = new ArrayList<>();
        l.add(HypixelFormat.dateLine("bedwarslobby" + d.lobbyNumber));
        l.add("");
        l.add("Level: " + HypixelFormat.bedwarsStarColor(d.bwStar) + d.bwStar + "✫");
        l.add("");
        l.add("Progress: " + ChatColor.AQUA + HypixelFormat.shortNumber(d.bwExp) + ChatColor.GRAY + "/" + ChatColor.GREEN + HypixelFormat.shortNumber(need));
        l.add(HypixelFormat.progressBar(d.bwExp / (double) need));
        l.add("");
        l.add("Loot Chests: " + ChatColor.YELLOW + d.bwLootChests);
        l.add("");
        l.add("Coins: " + ChatColor.GOLD + HypixelFormat.commas(d.bwCoins));
        l.add("");
        l.add("Total Kills: " + ChatColor.GREEN + HypixelFormat.commas(d.bwKills));
        l.add("Total Wins: " + ChatColor.GREEN + HypixelFormat.commas(d.bwWins));
        l.add("");
        l.add(WWW);
        return l;
    }

    private List<String> skywars(ProfileData d) {
        List<String> l = new ArrayList<>();
        l.add(HypixelFormat.dateLine("skywarslobby" + d.lobbyNumber));
        l.add("");
        l.add("Your Level: " + HypixelFormat.skywarsLevel(d.swLevel));
        l.add("Progress: " + ChatColor.AQUA + d.swExpProgressPct + "%");
        l.add(HypixelFormat.progressBar(d.swExpProgressPct / 100.0));
        l.add("");
        l.add("Total Kills: " + ChatColor.GREEN + HypixelFormat.commas(d.swKills));
        l.add("Total Wins: " + ChatColor.GREEN + HypixelFormat.commas(d.swWins));
        l.add("");
        l.add("Coins: " + ChatColor.GOLD + HypixelFormat.commas(d.swCoins));
        l.add("Souls: " + ChatColor.AQUA + HypixelFormat.commas(d.swSouls));
        l.add("Tokens: " + ChatColor.DARK_GREEN + HypixelFormat.commas(d.swTokens));
        l.add("");
        l.add(WWW);
        return l;
    }
}
