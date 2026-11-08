package net.h2020.bots;

import net.h2020.api.H2020;
import net.h2020.api.Module;
import net.h2020.api.bot.BotService;
import net.h2020.bots.ai.BotAI;
import net.h2020.bots.profile.SkinRepository;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.logging.Level;

/**
 * Bots body module: server-side fake players driven by virtual 1.8.9 clients.
 * Optional config (config.yml, section "bots"): fetchSkins (true), skinSources (list of usernames to borrow skins from).
 */
public final class BotsModule implements Module {
    private BotManager manager;
    private SkinRepository skins;
    private BotSettings settings;

    @Override public String name() { return "bots"; }

    @Override
    public void enable(JavaPlugin plugin) {
        ConfigurationSection cfg = plugin.getConfig().getConfigurationSection("bots");
        boolean fetch = cfg == null || cfg.getBoolean("fetchSkins", true);
        List<String> sources = cfg != null && cfg.isList("skinSources") ? cfg.getStringList("skinSources") : SkinRepository.DEFAULT_SOURCES;

        skins = new SkinRepository(plugin);
        skins.start(sources, fetch);

        // /bot settings: the switches are applied here, before anyone spawns
        settings = new BotSettings(plugin);
        settings.apply();

        manager = new BotManager(plugin, skins);
        manager.start();
        manager.setAverageSkill(settings.averageSkill);
        H2020.register(BotService.class, manager);
        final BotSettingsMenu settingsMenu = new BotSettingsMenu(settings, manager);
        H2020.register(net.h2020.api.bot.BotSettingsService.class, new net.h2020.api.bot.BotSettingsService() {
            @Override public void open(org.bukkit.entity.Player who) { settingsMenu.open(who); }
        });

        PluginCommand cmd = plugin.getCommand("bot");
        if (cmd != null) {
            BotCommand executor = new BotCommand(plugin, manager, settings);
            cmd.setExecutor(executor);
            cmd.setTabCompleter(executor);
        }

        PluginCommand answer = plugin.getCommand("qanswer");
        if (answer != null) answer.setExecutor(new AnswersCommand());

        try {
            BotAI.install(plugin);
        } catch (Throwable t) {
            plugin.getLogger().log(Level.SEVERE, "[bots] BotAI failed to install", t);
        }
    }

    @Override
    public void disable() {
        try {
            BotAI.shutdown();
        } catch (Throwable ignored) {}
        if (manager != null) manager.shutdown();
        net.h2020.bots.client.VirtualClient.shutdownExecutors();
        if (skins != null) skins.stop();
        manager = null;
    }
}
