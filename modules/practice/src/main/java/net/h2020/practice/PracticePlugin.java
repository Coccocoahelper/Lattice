package net.h2020.practice;

import org.bukkit.plugin.java.JavaPlugin;

/** Bed Wars practice as its own plugin, against the API inside Lattice.jar. */
public final class PracticePlugin extends JavaPlugin {
    private PracticeModule module;

    @Override public void onEnable() {
        module = new PracticeModule();
        try { module.enable(this); getLogger().info("Practice online"); }
        catch (Throwable t) { getLogger().severe("Practice failed: " + t); t.printStackTrace(); }
    }

    @Override public void onDisable() { try { if (module != null) module.disable(); } catch (Throwable ignored) { } }
}
