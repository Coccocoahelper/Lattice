package net.h2020.api.core;

import org.bukkit.command.CommandSender;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lets a subcommand be owned by a plugin that core must not depend on: the bots plugin registers its train and replay
 * commands here, and core's /h2020 dispatcher calls whatever is registered. Absent plugin, absent command.
 */
public final class AdminHooks {
    public interface Hook { boolean run(CommandSender sender, String[] args); }

    private static final Map<String, Hook> HOOKS = new ConcurrentHashMap<>();

    private AdminHooks() {}

    public static void register(String sub, Hook hook) {
        if (sub != null && hook != null) HOOKS.put(sub.toLowerCase(), hook);
    }

    public static Hook get(String sub) { return sub == null ? null : HOOKS.get(sub.toLowerCase()); }

    public static boolean has(String sub) { return get(sub) != null; }
}
