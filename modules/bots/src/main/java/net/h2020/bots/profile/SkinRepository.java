package net.h2020.bots.profile;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;

/**
 * Pool of signed Mojang skin texture properties for bots. Textures are fetched asynchronously for a configurable list
 * of "skin source" usernames (api.mojang.com name -> uuid, then sessionserver ?unsigned=false), cached on disk in
 * plugins/Hypixel2020/skins.json and reused across restarts. If nothing is available bots get the default skin.
 */
public final class SkinRepository {
    public static final class Skin {
        public final String source, value, signature;
        Skin(String source, String value, String signature) { this.source = source; this.value = value; this.signature = signature; }
    }

    /** Ordinary short names whose accounts exist with custom skins; override with bots.skinSources in config.yml. */
    public static final List<String> DEFAULT_SOURCES = Collections.unmodifiableList(java.util.Arrays.asList(
            "Bob", "Max", "Sam", "Jake", "Luke", "Ryan", "Ben", "Tom", "Leo", "Kai", "Finn", "Zack", "Nick", "Liam",
            "Noah", "Ella", "Mia", "Lily", "Emma", "Zoe", "Ruby", "Ivy", "Nova", "Luna", "Blaze", "Frost", "Shadow",
            "Ghost", "Storm", "Viper", "Phoenix", "Wolf", "Tiger", "Panda", "Kitty", "Bunny", "Dragon", "Ninja",
            "Pirate", "Pickle", "Taco", "Waffle", "Cookie", "Potato", "Muffin", "Toast", "Sushi", "Mango", "Peach",
            "Cherry", "Apple", "Lemon", "Ocean", "River", "Forest", "Desert", "Comet", "Rocket", "Pixel", "Turtle"));

    private static final long REFRESH_MS = 14L * 24 * 3600 * 1000;

    private final JavaPlugin plugin;
    private final File file;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final List<Skin> pool = new CopyOnWriteArrayList<Skin>();
    private final Map<String, Long> fetchedAt = new ConcurrentHashMap<String, Long>();
    private final Map<String, Skin> bySource = new ConcurrentHashMap<String, Skin>();
    private volatile boolean stopped;
    private BukkitTask task;

    public SkinRepository(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "skins.json");
    }

    public int size() { return pool.size(); }

    /** Random skin from the pool or null. */
    public Skin random(Random rnd) {
        int n = pool.size();
        if (n == 0) return null;
        try { return pool.get(rnd.nextInt(n)); } catch (IndexOutOfBoundsException e) { return null; }
    }

    /** Loads the disk cache (main thread, small) and starts fetching missing/stale sources asynchronously. */
    public void start(List<String> sources, boolean fetch) {
        load();
        if (!fetch || sources.isEmpty()) return;
        final List<String> todo = new ArrayList<String>();
        long now = System.currentTimeMillis();
        for (String s : sources) {
            String key = s.toLowerCase(Locale.ROOT);
            Long at = fetchedAt.get(key);
            if (at == null || now - at > REFRESH_MS) todo.add(s);
        }
        if (todo.isEmpty()) return;
        task = plugin.getServer().getScheduler().runTaskAsynchronously(plugin, new Runnable() {
            @Override public void run() { fetchAll(todo); }
        });
    }

    public void stop() {
        stopped = true;
        if (task != null) task.cancel();
    }

    private void fetchAll(List<String> names) {
        int ok = 0;
        for (String name : names) {
            if (stopped) break;
            try {
                if (fetch(name)) ok++;
            } catch (Throwable t) {
                plugin.getLogger().log(Level.FINE, "Skin fetch failed for " + name, t);
            }
            fetchedAt.put(name.toLowerCase(Locale.ROOT), System.currentTimeMillis());
            if (ok % 10 == 0) save();
            try { Thread.sleep(1100L); } catch (InterruptedException e) { return; }
        }
        save();
        plugin.getLogger().info("[bots] Skin pool ready: " + pool.size() + " skins (" + ok + " fetched this run)");
    }

    private boolean fetch(String name) throws Exception {
        JsonObject prof = getJson("https://api.mojang.com/users/profiles/minecraft/" + name);
        if (prof == null || !prof.has("id")) return false;
        String id = prof.get("id").getAsString();
        JsonObject session = getJson("https://sessionserver.mojang.com/session/minecraft/profile/" + id + "?unsigned=false");
        if (session == null || !session.has("properties")) return false;
        JsonArray props = session.getAsJsonArray("properties");
        for (JsonElement el : props) {
            JsonObject p = el.getAsJsonObject();
            if (!"textures".equals(p.get("name").getAsString())) continue;
            if (!p.has("signature")) return false;
            String value = p.get("value").getAsString();
            String sig = p.get("signature").getAsString();
            if (!hasCustomSkin(value)) return false;
            add(new Skin(name, value, sig));
            return true;
        }
        return false;
    }

    private static boolean hasCustomSkin(String base64) {
        try {
            String json = new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8);
            return json.contains("\"SKIN\"");
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private JsonObject getJson(String url) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(5000);
        con.setReadTimeout(5000);
        con.setRequestProperty("User-Agent", "Hypixel2020-bots");
        int code = con.getResponseCode();
        if (code != 200) { con.disconnect(); return null; }
        InputStream in = con.getInputStream();
        try {
            Reader r = new InputStreamReader(in, StandardCharsets.UTF_8);
            JsonElement el = new JsonParser().parse(r);
            return el.isJsonObject() ? el.getAsJsonObject() : null;
        } finally {
            in.close();
            con.disconnect();
        }
    }

    private void add(Skin s) {
        Skin old = bySource.put(s.source.toLowerCase(Locale.ROOT), s);
        if (old != null) pool.remove(old);
        for (Skin o : pool) if (o.value.equals(s.value)) return; // duplicate texture
        pool.add(s);
    }

    private void load() {
        if (!file.isFile()) return;
        try {
            Reader r = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8);
            try {
                JsonElement root = new JsonParser().parse(r);
                if (!root.isJsonObject()) return;
                JsonObject skins = root.getAsJsonObject().getAsJsonObject("skins");
                if (skins == null) return;
                for (Map.Entry<String, JsonElement> e : skins.entrySet()) {
                    JsonObject o = e.getValue().getAsJsonObject();
                    long at = o.has("fetched") ? o.get("fetched").getAsLong() : 0L;
                    fetchedAt.put(e.getKey().toLowerCase(Locale.ROOT), at);
                    if (o.has("value") && o.has("signature")) {
                        add(new Skin(e.getKey(), o.get("value").getAsString(), o.get("signature").getAsString()));
                    }
                }
            } finally {
                r.close();
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "[bots] Could not read skins.json", e);
        }
    }

    private synchronized void save() {
        try {
            JsonObject skins = new JsonObject();
            for (Map.Entry<String, Long> e : fetchedAt.entrySet()) {
                JsonObject o = new JsonObject();
                o.addProperty("fetched", e.getValue());
                Skin s = bySource.get(e.getKey());
                if (s != null) {
                    o.addProperty("value", s.value);
                    o.addProperty("signature", s.signature);
                }
                skins.add(e.getKey(), o);
            }
            JsonObject root = new JsonObject();
            root.add("skins", skins);
            File dir = file.getParentFile();
            if (dir != null && !dir.isDirectory()) dir.mkdirs();
            File tmp = new File(file.getPath() + ".tmp");
            Writer w = Files.newBufferedWriter(tmp.toPath(), StandardCharsets.UTF_8);
            try { gson.toJson(root, w); } finally { w.close(); }
            Files.move(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "[bots] Could not write skins.json", e);
        }
    }
}
