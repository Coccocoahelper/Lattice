package net.h2020.api.bot;

import net.h2020.api.core.Rank;
import org.bukkit.ChatColor;

import java.util.UUID;

/** Identity + personality of a bot. Immutable once spawned. */
public final class BotProfile {
    public final String name;
    public final UUID uuid;
    /** Mojang textures property (may be null -> default skin). */
    public final String skinValue, skinSignature;
    public final Rank rank;
    public final ChatColor plusColor;
    public final int networkLevel;
    /** Simulated latency shown in tab and used to delay the bot's reactions/packets (ms). */
    public final int pingMs;
    public final BotSkill skill;
    public final CheatProfile cheat;

    public BotProfile(String name, UUID uuid, String skinValue, String skinSignature, Rank rank, ChatColor plusColor,
                      int networkLevel, int pingMs, BotSkill skill, CheatProfile cheat) {
        this.name = name; this.uuid = uuid; this.skinValue = skinValue; this.skinSignature = skinSignature;
        this.rank = rank; this.plusColor = plusColor; this.networkLevel = networkLevel; this.pingMs = pingMs;
        this.skill = skill; this.cheat = cheat;
    }
}
