package net.h2020.bots;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * The bots plugin: everything under {@code net.h2020.bots} - the virtual clients, the movement and combat stack, the
 * brains, the models and the training harness. It is the heaviest part of the network and the one most worth being able
 * to switch off: the games, the lobby and the maps all run against {@code h2020-api} and never reference this jar.
 */
public final class BotsPlugin extends JavaPlugin {
    private BotsModule module;

    @Override public void onEnable() {
        module = new BotsModule();
        try {
            module.enable(this);
            getLogger().info("Bots online");
        } catch (Throwable t) {
            getLogger().severe("Bots failed to start: " + t);
            t.printStackTrace();
        }
    }

    @Override public void onDisable() {
        try { if (module != null) module.disable(); } catch (Throwable ignored) { }
    }
}
