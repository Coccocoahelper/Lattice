package net.h2020.bots.ai.ml;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Which sources the behaviour/intent models may learn from.
 * <p>
 * Our own bots are cheap labels, but they are not people: a hundred bots on guard duty make "defend" and "idle" the
 * overwhelming majority of the samples, and the model then reads every human walk as "idle, wants to defend". Bot
 * labels are therefore opt-in and off by default; players (answers, shop trips, fights) are always learned from.
 */
public final class MlSettings {
    private static boolean trainFromBots;          // default off: bot data poisons the human priors
    private static File file;

    private MlSettings() {}

    public static void install(JavaPlugin p) {
        if (p == null) return;
        file = new File(p.getDataFolder(), "ml-settings.yml");
        if (file.exists()) {
            try {
                org.bukkit.configuration.file.YamlConfiguration y =
                        org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file);
                trainFromBots = y.getBoolean("train-from-bots", false);
            } catch (Throwable ignored) { }
        }
    }

    public static boolean trainFromBots() { return trainFromBots; }

    public static void setTrainFromBots(boolean v) {
        trainFromBots = v;
        save();
    }

    public static List<String> report() {
        List<String> out = new ArrayList<String>();
        out.add("§6[ml] §esources: §fplayers always, bots " + (trainFromBots ? "§aon§7 (bot labels enabled)"
                : "§coff§7 (/bot ml bots on to enable)"));
        return out;
    }

    private static void save() {
        if (file == null) return;
        try {
            org.bukkit.configuration.file.YamlConfiguration y = new org.bukkit.configuration.file.YamlConfiguration();
            y.set("train-from-bots", trainFromBots);
            if (!file.getParentFile().exists()) file.getParentFile().mkdirs();
            y.save(file);
        } catch (Throwable ignored) { }
    }
}
