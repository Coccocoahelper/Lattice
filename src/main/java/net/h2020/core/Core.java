package net.h2020.core;

import net.h2020.core.game.BotQueueFiller;
import net.h2020.core.game.GameManagerImpl;
import net.h2020.core.lobby.LobbyBots;
import net.h2020.core.lobby.LobbyManager;
import net.h2020.core.lobby.PlayerCounts;
import net.h2020.core.lobby.npc.NpcManager;
import net.h2020.core.packet.PingSimulator;
import net.h2020.core.profile.ProfileServiceImpl;
import net.h2020.core.scoreboard.ScoreboardServiceImpl;
import org.bukkit.plugin.java.JavaPlugin;

/** Wiring of every core component (one instance, built by CoreModule). */
public final class Core {
    public final JavaPlugin plugin;
    public final CoreConfig config;
    public final ProfileServiceImpl profiles;
    public final ScoreboardServiceImpl scoreboards;
    public final GameManagerImpl games;
    public final BotQueueFiller filler;
    public final PlayerCounts counts;
    public LobbyManager lobby;
    public NpcManager npcs;
    public LobbyBots lobbyBots;
    public PingSimulator ping;

    public Core(JavaPlugin plugin, CoreConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.profiles = new ProfileServiceImpl(plugin, config);
        this.scoreboards = new ScoreboardServiceImpl();
        this.games = new GameManagerImpl(plugin, config);
        this.filler = new BotQueueFiller(plugin, config, games);
        this.counts = new PlayerCounts(config, games);
        games.setFiller(filler);
    }
}
