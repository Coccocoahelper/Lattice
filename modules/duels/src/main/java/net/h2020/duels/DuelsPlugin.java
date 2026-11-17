package net.h2020.duels;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/** Duels as its own plugin, against the shared API inside Lattice.jar. */
public final class DuelsPlugin extends JavaPlugin {
    private DuelsModule module;

    @Override public void onEnable() {
        module = new DuelsModule();
        try {
            module.enable(this);
            org.bukkit.command.PluginCommand cmd = getCommand("duel");
            if (cmd != null) {
                DuelsCommand ex = new DuelsCommand(module);
                cmd.setExecutor(ex);
                cmd.setTabCompleter(ex);
            }
            getLogger().info("Duels online");
        }
        catch (Throwable t) { getLogger().severe("Duels failed: " + t); t.printStackTrace(); }
    }

    @Override public void onDisable() { try { if (module != null) module.disable(); } catch (Throwable ignored) { } }
}
