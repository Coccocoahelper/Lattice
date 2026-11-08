package net.h2020.skywars;

import org.bukkit.plugin.java.JavaPlugin;

/** SkyWars as its own plugin: the game, its kits, loot and listeners, against the shared API in Hypixel2020.jar. */
public final class SkywarsPlugin extends JavaPlugin {
    private SkywarsModule module;

    @Override public void onEnable() {
        module = new SkywarsModule();
        try { module.enable(this); getLogger().info("SkyWars online"); }
        catch (Throwable t) { getLogger().severe("SkyWars failed: " + t); t.printStackTrace(); }
    }

    @Override public void onDisable() { try { if (module != null) module.disable(); } catch (Throwable ignored) { } }
}
