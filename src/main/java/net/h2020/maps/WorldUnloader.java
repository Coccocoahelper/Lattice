package net.h2020.maps;

import net.minecraft.server.v1_8_R3.WorldServer;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.craftbukkit.v1_8_R3.CraftWorld;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * Unloading a world safely on WindSpigot.
 * <p>
 * WindSpigot ticks every world on its own thread ({@code WorldTickManager} holds a list of {@code WorldTicker}s, one
 * per {@code WorldServer}). {@code Bukkit.unloadWorld} closes the world's chunk provider but does not take the ticker
 * out of that list, so the tick thread can tick a closed world once more and dies on
 * {@code NullPointerException: chunkProvider is null} inside {@code ChunkProviderServer.unloadChunks} - which takes the
 * whole server down, not just the world.
 * <p>
 * This drops the ticker first, then unloads. If the internals ever change, it falls back to a plain unload.
 */
public final class WorldUnloader {
    private WorldUnloader() {}

    public static boolean unload(World world) {
        if (world == null) return false;
        // anything keyed by this world has to go with it, or a later world with the same name inherits its POIs
        try { net.h2020.api.game.PlacedRegistry.unregister(world); } catch (Throwable ignored) { }
        try { net.h2020.api.game.GeneratorRegistry.clear(world); } catch (Throwable ignored) { }
        try {
            dropTicker(((CraftWorld) world).getHandle());
        } catch (Throwable ignored) {
            // not a CraftWorld or the internals moved: the plain unload below is still worth trying
        }
        try {
            boolean ok = Bukkit.unloadWorld(world, false);
            if (!ok) java.util.logging.Logger.getLogger("Hypixel2020").warning("[maps] unload refused for " + world.getName());
            return ok;
        } catch (Throwable t) {
            java.util.logging.Logger.getLogger("Hypixel2020").warning(
                    "[maps] unload failed for " + world.getName() + ": " + t);
            return false;
        }
    }

    /** Remove this world's ticker from WindSpigot's world tick manager. */
    private static void dropTicker(WorldServer handle) throws Exception {
        Class<?> mgrClass = Class.forName("com.windpvp.windspigot.world.WorldTickManager");
        Object mgr = mgrClass.getMethod("getInstance").invoke(null);
        if (mgr == null) return;
        Field listField = mgrClass.getDeclaredField("worldTickers");
        listField.setAccessible(true);
        Object value = listField.get(mgr);
        if (!(value instanceof List)) return;
        List<?> tickers = (List<?>) value;
        List<Object> doomed = new ArrayList<Object>();
        for (Object ticker : new ArrayList<Object>(tickers)) {
            try {
                Field wf = ticker.getClass().getField("worldserver");
                if (wf.get(ticker) == handle) doomed.add(ticker);
            } catch (Throwable ignored) { }
        }
        for (Object t : doomed) {
            try {
                tickers.remove(t);
            } catch (Throwable ignored) { }
        }
    }
}
