package net.h2020.core;

import net.h2020.api.H2020;
import net.h2020.api.Module;
import net.h2020.api.core.ProfileService;
import net.h2020.api.core.ScoreboardService;
import net.h2020.api.game.GameManager;
import net.h2020.core.command.AdminCommand;
import net.h2020.core.command.LobbyCommand;
import net.h2020.core.command.PlayCommand;
import net.h2020.api.gui.MenuListener;
import net.h2020.core.tab.TabFilter;
import net.h2020.core.lobby.LobbyBots;
import net.h2020.core.lobby.LobbyManager;
import net.h2020.core.lobby.SettingsMenu;
import net.h2020.core.lobby.npc.NpcManager;
import net.h2020.core.packet.PacketInjector;
import net.h2020.core.packet.PingSimulator;
import net.h2020.core.profile.ChatListener;
import net.h2020.core.profile.ProfileServiceImpl;
import net.h2020.core.profile.StatsListener;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Core: Netty packet injection, profiles/ranks/chat, scoreboards/nametags/tab, matchmaking + bot queue filler,
 * the main/Bed Wars/SkyWars lobbies (items, menus, NPCs, protection, idle bots) and the /play, /lobby, /h2020 commands.
 */
public final class CoreModule implements Module {
    private TabFilter tab;
    private Core core;
    private PacketInjector injector;
    private final java.util.List<Listener> listeners = new java.util.ArrayList<>();

    @Override public String name() { return "core"; }

    /** Set the fake scoreboard date everywhere. */
    public static void applyDate(String date) { HypixelFormat.setDate(date); }

    @Override
    public void enable(JavaPlugin plugin) {
        net.h2020.api.game.GameModes.install();
        // the two data files a server edits: identity and menu presentation
        for (String res : new String[]{"network.yml", "menus.yml"}) {
            try { plugin.saveResource(res, false); }
            catch (IllegalArgumentException alreadyThere) { /* first run only */ }
        }
        CoreConfig config = new CoreConfig();
        config.load(plugin);
        config.applyNetwork(plugin);
        applyDate(config.date);

        core = new Core(plugin, config);
        net.h2020.core.arena.PvpArena.install(plugin);
        if (plugin.getCommand("playagain") != null) plugin.getCommand("playagain").setExecutor(new net.h2020.core.command.PlayAgainCommand());
        core.scoreboards.setDefaultTab(config.tabHeader, config.tabFooter);
        core.lobby = new LobbyManager(core);
        core.games.setLobbyHooks(core.lobby);
        core.npcs = new NpcManager(core);
        core.lobbyBots = new LobbyBots(core);
        core.profiles.setChangeListener(new ProfileServiceImpl.ChangeListener() {
            @Override public void profileChanged(Player p) { core.lobby.profileChanged(p); }
        });

        H2020.register(ProfileService.class, core.profiles);
        H2020.register(ScoreboardService.class, core.scoreboards);
        H2020.register(GameManager.class, core.games);
        net.h2020.api.core.Sidebars.init(plugin);

        injector = new PacketInjector(plugin.getLogger());
        core.ping = new PingSimulator(core, plugin.getLogger(),
                plugin.getConfig().getBoolean("core.simulated-ping", false));
        // order matters within a priority: profiles/scoreboards first so the lobby can use them on join
        register(plugin, injector);
        register(plugin, core.ping);
        register(plugin, core.profiles);
        register(plugin, core.scoreboards);
        register(plugin, new ChatListener(core));
        register(plugin, new StatsListener(core.profiles));
        register(plugin, core.games);
        register(plugin, new MenuListener());
        tab = new TabFilter(plugin);
        tab.start();
        register(plugin, tab);
        register(plugin, core.lobby);
        register(plugin, core.npcs);

        register(plugin, new net.h2020.core.command.HelpMenu());
        command(plugin, "play", new PlayCommand(core));
        command(plugin, "lobby", new LobbyCommand(core));
        command(plugin, "settings", new org.bukkit.command.CommandExecutor() {
            @Override public boolean onCommand(org.bukkit.command.CommandSender sender, org.bukkit.command.Command cmd,
                                                String label, String[] args) {
                if (!(sender instanceof Player)) {
                    sender.sendMessage(org.bukkit.ChatColor.RED + "Players only.");
                    return true;
                }
                new SettingsMenu(core).open((Player) sender);
                return true;
            }
        });
        net.h2020.core.command.SpectateCommand spec = new net.h2020.core.command.SpectateCommand(core);
        command(plugin, "spectate", spec);
        org.bukkit.command.PluginCommand sp = plugin.getCommand("spectate");
        if (sp != null) sp.setTabCompleter(spec);
        AdminCommand admin = new AdminCommand(core);
        PluginCommand h = plugin.getCommand("h2020");
        if (h != null) { h.setExecutor(admin); h.setTabCompleter(admin); }
        PluginCommand play = plugin.getCommand("play");
        if (play != null) play.setTabCompleter(new PlayCommand(core));

        injector.injectAll();
        core.filler.start();
        // everything below needs the other modules (maps, bots) which enable after core
        Bukkit.getScheduler().runTask(plugin, new Runnable() {
            @Override public void run() {
                core.lobby.applyWorldSettings();
                core.lobby.start();
                core.npcs.start();
                core.lobbyBots.start();
            }
        });
        Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            @Override public void run() { core.profiles.saveAll(); }
        }, 20L * 300, 20L * 300);
    }

    private void register(JavaPlugin plugin, Listener l) {
        PluginManager pm = Bukkit.getPluginManager();
        pm.registerEvents(l, plugin);
        listeners.add(l);
    }

    private static void command(JavaPlugin plugin, String name, org.bukkit.command.CommandExecutor ex) {
        PluginCommand c = plugin.getCommand(name);
        if (c != null) c.setExecutor(ex);
    }

    @Override
    public void disable() {
        if (core == null) return;
        try { core.games.shutdownAll(); } catch (Throwable ignored) {}
        try { core.npcs.stop(); } catch (Throwable ignored) {}
        core.profiles.saveAll();
        if (injector != null) injector.uninjectAll();
        core.scoreboards.shutdown();
        for (Listener l : listeners) HandlerList.unregisterAll(l);
        listeners.clear();
        core = null;
    }
}
