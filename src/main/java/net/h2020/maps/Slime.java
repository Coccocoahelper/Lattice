package net.h2020.maps;

import net.h2020.api.map.SlimeWorlds;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.logging.Logger;

/**
 * Continued Slime World Manager, used for the two things folder copying could not do: a world is materialised from the
 * map's slime file in milliseconds, and every game or replay gets its own copy, so two of them can never share blocks.
 *
 * <p>Spawning is asynchronous. The world manager builds the NMS world on its own thread and registers it on the main
 * one, so {@link #spawn(String, String)} hands out a world that was built earlier and asks for a replacement; when none
 * is ready it answers null and the caller keeps using the old folder copy. Nothing here is required for a game to
 * start, it is only faster and isolated.
 *
 * <p>Reflection is deliberate: the world manager is an optional plugin and must not be a compile dependency.
 */
public final class Slime implements SlimeWorlds.Spawner {
    private final Logger log;
    private final Object plugin;
    private final Object fileLoader;
    private final java.lang.reflect.Method loadWorld, importWorld, worldExists, clone, generateWorld, unloadWorld;

    /** Read-only template per map id. */
    private final Map<String, Object> templates = new ConcurrentHashMap<String, Object>();
    /** Built worlds waiting for a caller, per map id. */
    private final Map<String, Deque<World>> pool = new ConcurrentHashMap<String, Deque<World>>();
    /** Bukkit world name to the slime world behind it, so release() can unload the right one. */
    private final Map<String, Object> live = new ConcurrentHashMap<String, Object>();
    private final AtomicInteger seq = new AtomicInteger();

    private Slime(Logger log, Object plugin, Object fileLoader) throws Exception {
        this.log = log;
        this.plugin = plugin;
        this.fileLoader = fileLoader;
        Class<?> slimePlugin = Class.forName("net.swofty.swm.api.SlimePlugin");
        Class<?> loader = Class.forName("net.swofty.swm.api.loaders.SlimeLoader");
        Class<?> slimeWorld = Class.forName("net.swofty.swm.api.world.SlimeWorld");
        this.loadWorld = slimePlugin.getMethod("loadWorld", loader, String.class, boolean.class,
                Class.forName("net.swofty.swm.api.world.properties.SlimePropertyMap"));
        this.importWorld = slimePlugin.getMethod("importWorld", File.class, String.class, loader);
        this.worldExists = loader.getMethod("worldExists", String.class);
        this.clone = slimeWorld.getMethod("clone", String.class);
        this.generateWorld = slimePlugin.getMethod("generateWorld", slimeWorld);
        this.unloadWorld = slimeWorld.getMethod("unloadWorld", boolean.class, String.class);
    }

    /** Attach to the world manager when it is installed; leave folder worlds untouched when it is not. */
    public static Slime install(Logger log) {
        Plugin p = Bukkit.getPluginManager().getPlugin("SwoftyWorldManager");
        if (p == null) return null;
        try {
            Object loader = p.getClass().getMethod("getLoader", String.class).invoke(p, "file");
            if (loader == null) {
                log.warning("[slime] the file data source is missing, keeping folder worlds");
                return null;
            }
            Slime s = new Slime(log, p, loader);
            SlimeWorlds.spawner(s);
            log.info("[slime] world manager " + p.getDescription().getVersion() + " attached (data source: file)");
            return s;
        } catch (Throwable t) {
            log.warning("[slime] could not attach the world manager: " + root(t));
            return null;
        }
    }

    /** Slime name for a map: folder name, lower case, runs of anything else collapsed to one underscore. */
    public static String id(String mapName) {
        return mapName == null ? null : mapName.toLowerCase().replaceAll("[^a-z0-9]+", "_");
    }

    /** True when a copy of this map is built and waiting, so a world can be handed out without a delay. */
    public boolean ready(String mapName) {
        String id = id(mapName);
        if (id == null) return false;
        Deque<World> d = pool.get(id);
        return d != null && !d.isEmpty();
    }

    @Override
    public boolean has(String mapName) {
        String id = id(mapName);
        if (id == null) return false;
        try {
            return Boolean.TRUE.equals(worldExists.invoke(fileLoader, id));
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public World spawn(String mapName, String purpose) {
        String id = id(mapName);
        if (id == null) return null;
        Deque<World> ready = pool.get(id);
        World w = ready == null ? null : ready.pollFirst();
        if (w == null) {
            prepare(mapName);
            return null;
        }
        Worlds.applyRules(w, true);
        refill(mapName);
        log.info("[slime] " + purpose + " got " + w.getName() + " (" + mapName + ")");
        return w;
    }

    /**
     * A private copy under an exact Bukkit world name, for the lobbies: other code looks them up as
     * {@code Bukkit.getWorld("h2020_lobby")}, so the clone has to carry that name. Returns null when the world manager
     * has no template for the id yet.
     */
    public World spawnNamed(final String templateId, final String worldName) {
        return spawnNamed(templateId, worldName, 0);
    }

    private World spawnNamed(final String templateId, final String worldName, final int attempt) {
        Object template = templates.get(templateId);
        if (template == null) {
            // the template is fetched asynchronously, so come back for it rather than giving up on the first tick
            prepare(templateId);
            if (attempt < 40) Bukkit.getScheduler().runTaskLater(plugin(), new Runnable() {
                @Override public void run() { spawnNamed(templateId, worldName, attempt + 1); }
            }, 5L);
            return null;
        }
        if (Bukkit.getWorld(worldName) != null) return null;
        try {
            final Object copy = clone.invoke(template, worldName);
            CompletableFuture<?> f = (CompletableFuture<?>) generateWorld.invoke(plugin, copy);
            final String id = templateId;
            final String name = worldName;
            f.whenComplete((v, err) -> {
                if (err != null) { log.warning("[slime] could not build " + name + ": " + root((Throwable) err)); return; }
                World w = Bukkit.getWorld(name);
                if (w == null) return;
                w.setAutoSave(false);
                live.put(name.toLowerCase(), copy);
            });
            return null;   // same as spawn(): the caller builds the world the old way this time
        } catch (Throwable t) {
            log.warning("[slime] could not clone " + templateId + " as " + worldName + ": " + root(t));
            return null;
        }
    }

    /** Template has been loaded and a world under this name exists right now. */
    public boolean loaded(String worldName) {
        return Bukkit.getWorld(worldName) != null && live.containsKey(worldName.toLowerCase());
    }

    @Override
    public void release(World world) {
        if (world == null) return;
        Object slime = live.remove(world.getName().toLowerCase());
        if (slime == null) return;
        try {
            unloadWorld.invoke(slime, false, null);
            log.info("[slime] released " + world.getName());
        } catch (Throwable t) {
            log.warning("[slime] could not release " + world.getName() + ": " + root(t));
        }
    }

    /** Load the template for a map (once), then make sure one world is waiting. Safe to call repeatedly. */
    public void prepare(String mapName) {
        final String id = id(mapName);
        if (id == null) return;
        if (templates.containsKey(id)) { refill(mapName); return; }
        Bukkit.getScheduler().runTaskAsynchronously(plugin( ), new Runnable() {
            @Override public void run() {
                try {
                    Object props = Class.forName("net.swofty.swm.api.world.properties.SlimePropertyMap")
                            .getConstructor().newInstance();
                    Object slime = loadWorld.invoke(plugin, fileLoader, id, true, props);
                    templates.put(id, slime);
                    log.info("[slime] template " + id + " loaded");
                } catch (Throwable t) {
                    log.fine("[slime] no template for " + id + " (" + root(t) + ")");
                    return;
                }
                Bukkit.getScheduler().runTask(plugin(), new Runnable() {
                    @Override public void run() { refill(id); }
                });
            }
        });
    }

    private void refill(String mapName) {
        String id = id(mapName);
        Object template = id == null ? null : templates.get(id);
        if (template == null) { if (id != null) prepare(id); return; }
        Deque<World> ready = pool.get(id);
        if (ready != null && !ready.isEmpty()) return;
        try {
            final String name = "instances/swm_" + id + "_" + seq.incrementAndGet();
            final Object copy = clone.invoke(template, name);
            @SuppressWarnings("unchecked")
            CompletableFuture<Void> future = (CompletableFuture<Void>) generateWorld.invoke(plugin, copy);
            future.whenComplete(new BiConsumer<Void, Throwable>() {
                @Override public void accept(Void v, Throwable err) {
                    if (err != null) { log.warning("[slime] could not build " + name + ": " + root(err)); return; }
                    World w = Bukkit.getWorld(name);
                    if (w == null) return;
                    w.setAutoSave(false);
                    live.put(name.toLowerCase(), copy);
                    pool.computeIfAbsent(id, k -> new ArrayDeque<World>()).addLast(w);
                }
            });
        } catch (Throwable t) {
            log.warning("[slime] refill for " + mapName + " failed: " + root(t));
        }
    }

    /** Import a Bukkit world folder into the slime data source. The manager needs level.dat, region/ and data/. */
    public boolean importFolder(File worldDir, String slimeName) {
        try {
            File data = new File(worldDir, "data");
            if (!data.isDirectory()) data.mkdirs();
            importWorld.invoke(plugin, worldDir, slimeName, fileLoader);
            log.info("[slime] imported " + slimeName + " from " + worldDir);
            return true;
        } catch (Throwable t) {
            log.warning("[slime] import of " + slimeName + " failed: " + root(t));
            // the root cause is what actually explains the failure; the reflective frames are noise
            Throwable c = t;
            while (c.getCause() != null && c.getCause() != c) c = c.getCause();
            for (StackTraceElement el : c.getStackTrace())
                if (el.getClassName().startsWith("net.swofty") || el.getClassName().startsWith("net.h2020"))
                    log.warning("[slime]   at " + el);
            return false;
        }
    }

    private Plugin plugin() { return (Plugin) plugin; }

    private static String root(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) c = c.getCause();
        return c.getClass().getSimpleName() + (c.getMessage() == null ? "" : ": " + c.getMessage());
    }
}
