package net.h2020.bedwars;

import org.bukkit.plugin.java.JavaPlugin;

/** Bed Wars as its own plugin: the game, shop, gadgets and listeners, against the API inside Hypixel2020.jar. */
public final class BedwarsPlugin extends JavaPlugin {
    private BedwarsModule module;

    @Override public void onEnable() {
        module = new BedwarsModule();
        try { module.enable(this); getLogger().info("Bed Wars online"); }
        catch (Throwable t) { getLogger().severe("Bed Wars failed: " + t); t.printStackTrace(); }
    }

    @Override public void onDisable() { try { if (module != null) module.disable(); } catch (Throwable ignored) { } }
}
