package net.h2020.bedwars;

import org.bukkit.entity.Player;

/** Per-player in-game state (permanent items, stats, respawn/spectator status, combat tag). */
public final class BwPlayer {
    public enum DamageKind { MELEE, BOW, EXPLOSION, MOB, OTHER }

    public final Player player;
    public BwTeam team;

    // permanent shop state
    /** 0 = leather, 1 = chainmail, 2 = iron, 3 = diamond */
    public int armorTier;
    /** 0 = none, 1..4 = wood/iron/gold/diamond pickaxe */
    public int pickaxeTier;
    /** 0 = none, 1..4 = wood/stone/iron/diamond axe */
    public int axeTier;
    public boolean shears;

    // stats
    public int kills, finalKills, bedsBroken;
    public int coins;

    // state
    public boolean eliminated;
    /** Tick at which a respawning player comes back (-1 = not respawning). */
    public long respawnAt = -1;
    /** Last whole second announced in the respawn title. */
    public int lastRespawnSecond = -1;

    // combat tag
    public Player lastDamager;
    public long lastDamageAt;
    public DamageKind lastDamageKind = DamageKind.OTHER;
    public String lastDamageMobName;

    public long trapImmuneUntil;
    public long lastShout;
    public long lastFireball;
    public boolean invisArmorHidden;

    BwPlayer(Player player) { this.player = player; }

    public boolean respawning() { return respawnAt >= 0; }

    /** Alive and on the map (not respawning, not eliminated). */
    public boolean playing() { return !eliminated && respawnAt < 0; }

    public Player player() { return player; }
    public BwTeam team() { return team; }
}
