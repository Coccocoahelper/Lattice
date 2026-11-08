package net.h2020.maps;

import net.h2020.api.H2020;
import net.h2020.api.Module;
import net.h2020.api.map.MapService;
import org.bukkit.Bukkit;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Maps module: template storage (plugins/Hypixel2020/maps), procedural Hypixel-style Bed Wars / SkyWars maps,
 * hub worlds, and game instance worlds. Generation runs synchronously inside enable() so that MapService is
 * complete (isReady() == true) by the time it is registered and any game is created.
 */
public final class MapsModule implements Module {
    private MapServiceImpl service;
    private Worlds worldListener;

    @Override public String name() { return "maps"; }

    @Override
    public void enable(JavaPlugin plugin) {
        worldListener = new Worlds();
        Bukkit.getPluginManager().registerEvents(worldListener, plugin);
        service = new MapServiceImpl(plugin);
        service.start();
        service.editor = new MapEditService(plugin, service);
        service.editor.register();
        H2020.register(MapService.class, service);
    }

    @Override
    public void disable() {
        if (service != null && service.editor != null) service.editor.unregister();
        if (service != null) service.shutdown();
        if (worldListener != null) HandlerList.unregisterAll(worldListener);
        service = null;
        worldListener = null;
    }
}
