package net.h2020.api;

import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Tiny service locator shared by every module. Modules register their services in enable(). */
public final class H2020 {
    private static final Map<Class<?>, Object> SERVICES = new ConcurrentHashMap<>();
    private static JavaPlugin plugin;

    private H2020() {}

    public static void init(JavaPlugin p) { plugin = p; }
    public static JavaPlugin plugin() { return plugin; }

    public static <T> void register(Class<T> type, T impl) { SERVICES.put(type, impl); }

    /** @throws IllegalStateException if no module registered the service */
    public static <T> T get(Class<T> type) {
        Object o = SERVICES.get(type);
        if (o == null) throw new IllegalStateException("Service not registered: " + type.getName());
        return type.cast(o);
    }

    public static <T> T find(Class<T> type) { return type.cast(SERVICES.get(type)); }
}
