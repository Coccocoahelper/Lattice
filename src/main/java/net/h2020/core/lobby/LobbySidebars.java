package net.h2020.core.lobby;

import net.h2020.api.game.GameFamily;
import net.h2020.core.Core;
import net.h2020.core.HypixelFormat;
import net.h2020.core.profile.ProfileData;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** Main lobby / Bed Wars lobby / SkyWars lobby sidebars (summer 2020 layouts). */
public final class LobbySidebars {
    private static final String WWW = ChatColor.YELLOW + "www.hypixel.net";
    private final Core core;

    public LobbySidebars(Core core) { this.core = core; }

    public void update(Player p, GameFamily family) {
        ProfileData d = core.profiles.data(p);
        if (family == null) {
            core.scoreboards.setSidebar(p, ChatColor.YELLOW + "" + ChatColor.BOLD + "HYPIXEL", main(d));
            return;
        }
        // title comes from the family profile, so network.yml can rename a lobby without touching this class
        String title = net.h2020.api.game.Families.of(family).colouredSidebarTitle();
        if (family == GameFamily.BEDWARS) core.scoreboards.setSidebar(p, title, bedwars(d));
        else core.scoreboards.setSidebar(p, title, skywars(d));
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
