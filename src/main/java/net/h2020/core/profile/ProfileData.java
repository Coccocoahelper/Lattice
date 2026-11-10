package net.h2020.core.profile;

import net.h2020.api.core.Rank;
import net.h2020.core.HypixelFormat;
import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;

import java.util.Random;
import java.util.UUID;

/**
 * Network profile + the lobby-scoreboard stats Hypixel showed in 2020. Real players are persisted to profiles.yml,
 * bots get plausible values derived deterministically from their UUID and network level.
 */
public final class ProfileData {
    public final UUID uuid;
    public volatile String name;
    public volatile Rank rank = Rank.NONE;
    public volatile ChatColor plusColor = ChatColor.RED;
    public volatile int networkLevel = 1;
    /** Progress 0..1 towards the next network level (XP bar in lobbies). */
    public volatile double levelProgress = 0;
    public volatile int achievementPoints;
    public volatile int karma;
    public volatile String guild; // null = none
    public volatile int lobbyNumber;

    // Bed Wars
    public volatile int bwStar, bwExp, bwCoins, bwKills, bwFinalKills, bwWins, bwBedsBroken, bwLootChests;
    // SkyWars
    public volatile int swLevel, swExpProgressPct, swCoins, swSouls, swTokens, swKills, swWins;

    public volatile boolean playersHidden = false;
    /** Client-side latency simulation in milliseconds (0 = off). Real players only. */
    public volatile int simulatedPing = 0;

    public ProfileData(UUID uuid, String name) { this.uuid = uuid; this.name = name; }

    /** Fill stats with plausible values for a given network level (used for new players and bots). */
    public void randomizeStats(long seed) {
        Random r = new Random(seed);
        int lvl = Math.max(1, networkLevel);
        levelProgress = r.nextDouble();
        achievementPoints = (int) (lvl * (40 + r.nextInt(60)) + r.nextInt(300));
        karma = (int) (lvl * (1500 + r.nextInt(5000)) + r.nextInt(10000));
        guild = null;
        double grind = 0.3 + r.nextDouble() * 1.4;
        bwStar = Math.max(1, (int) Math.round(lvl * 0.9 * grind * (0.4 + r.nextDouble())));
        bwExp = r.nextInt(HypixelFormat.bedwarsExpForStar(bwStar));
        bwWins = (int) (bwStar * (2 + r.nextDouble() * 9));
        bwKills = (int) (bwWins * (2 + r.nextDouble() * 4) + r.nextInt(80));
        bwFinalKills = (int) (bwKills * (0.4 + r.nextDouble() * 0.5));
        bwBedsBroken = (int) (bwWins * (0.6 + r.nextDouble() * 1.4));
        bwCoins = (int) (bwStar * (600 + r.nextInt(2000)) + r.nextInt(5000));
        bwLootChests = r.nextInt(6);
        swLevel = Math.max(1, Math.min(60, (int) Math.round(Math.sqrt(lvl) * (0.8 + r.nextDouble() * 2.0))));
        swExpProgressPct = r.nextInt(100);
        swWins = (int) (swLevel * swLevel * (1.5 + r.nextDouble() * 6));
        swKills = (int) (swWins * (1.5 + r.nextDouble() * 3) + r.nextInt(60));
        swCoins = (int) (swLevel * (2000 + r.nextInt(8000)) + r.nextInt(5000));
        swSouls = r.nextInt(100);
        swTokens = r.nextInt(25000);
    }

    /** Add Bed Wars XP and roll over stars. */
    public void addBedwarsExp(int exp) {
        bwExp += exp;
        int need;
        while (bwExp >= (need = HypixelFormat.bedwarsExpForStar(bwStar))) { bwExp -= need; bwStar++; }
    }

    /** Add network experience fractionally (levelProgress), rolling levels. */
    public void addNetworkExp(int exp) {
        double p = levelProgress + exp / (double) HypixelFormat.networkExpForLevel(networkLevel);
        while (p >= 1) { p -= 1; networkLevel++; }
        levelProgress = p;
    }

    public void save(ConfigurationSection s) {
        s.set("name", name);
        s.set("rank", rank.name());
        s.set("plus-color", plusColor.name());
        s.set("level", networkLevel);
        s.set("level-progress", levelProgress);
        s.set("achievement-points", achievementPoints);
        s.set("karma", karma);
        s.set("guild", guild);
        s.set("lobby-number", lobbyNumber);
        s.set("players-hidden", playersHidden);
        s.set("simulated-ping", simulatedPing);
        s.set("bedwars.star", bwStar);
        s.set("bedwars.exp", bwExp);
        s.set("bedwars.coins", bwCoins);
        s.set("bedwars.kills", bwKills);
        s.set("bedwars.final-kills", bwFinalKills);
        s.set("bedwars.wins", bwWins);
        s.set("bedwars.beds-broken", bwBedsBroken);
        s.set("bedwars.loot-chests", bwLootChests);
        s.set("skywars.level", swLevel);
        s.set("skywars.progress", swExpProgressPct);
        s.set("skywars.coins", swCoins);
        s.set("skywars.souls", swSouls);
        s.set("skywars.tokens", swTokens);
        s.set("skywars.kills", swKills);
        s.set("skywars.wins", swWins);
    }

    public void load(ConfigurationSection s) {
        name = s.getString("name", name);
        try { rank = Rank.valueOf(s.getString("rank", rank.name())); } catch (IllegalArgumentException ignored) {}
        try { plusColor = ChatColor.valueOf(s.getString("plus-color", plusColor.name())); } catch (IllegalArgumentException ignored) {}
        networkLevel = s.getInt("level", networkLevel);
        levelProgress = s.getDouble("level-progress", levelProgress);
        achievementPoints = s.getInt("achievement-points", achievementPoints);
        karma = s.getInt("karma", karma);
        guild = s.getString("guild", null);
        lobbyNumber = s.getInt("lobby-number", lobbyNumber);
        playersHidden = s.getBoolean("players-hidden", false);
        simulatedPing = Math.max(0, Math.min(1000, s.getInt("simulated-ping", simulatedPing)));
        bwStar = s.getInt("bedwars.star", bwStar);
        bwExp = s.getInt("bedwars.exp", bwExp);
        bwCoins = s.getInt("bedwars.coins", bwCoins);
        bwKills = s.getInt("bedwars.kills", bwKills);
        bwFinalKills = s.getInt("bedwars.final-kills", bwFinalKills);
        bwWins = s.getInt("bedwars.wins", bwWins);
        bwBedsBroken = s.getInt("bedwars.beds-broken", bwBedsBroken);
        bwLootChests = s.getInt("bedwars.loot-chests", bwLootChests);
        swLevel = s.getInt("skywars.level", swLevel);
        swExpProgressPct = s.getInt("skywars.progress", swExpProgressPct);
        swCoins = s.getInt("skywars.coins", swCoins);
        swSouls = s.getInt("skywars.souls", swSouls);
        swTokens = s.getInt("skywars.tokens", swTokens);
        swKills = s.getInt("skywars.kills", swKills);
        swWins = s.getInt("skywars.wins", swWins);
    }
}
