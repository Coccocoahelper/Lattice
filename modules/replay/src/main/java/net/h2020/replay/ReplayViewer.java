package net.h2020.replay;

import net.h2020.api.stats.CombatStats;
import net.h2020.api.H2020;
import net.h2020.api.map.MapService;
import net.h2020.api.map.MapTemplate;
import net.h2020.api.npc.PacketNpc;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Plays a recorded game back: a fresh copy of the same map, one player-shaped puppet per recorded player, moving ten
 * times a second exactly where they moved when it happened.
 * <p>
 * {@code /h2020 replay list}, {@code /h2020 replay start <id> [speed]}, {@code pause}, {@code resume}, {@code seek
 * <seconds>}, {@code stop}. The puppet is a packet NPC (no entity in the world, no AI, no tick cost), so a replay is
 * just movement packets - it looks like the match and costs nothing.
 * <p>
 * The world is deliberately never unloaded: WindSpigot can race a world unload against its own world ticker, and a
 * spectator feature is not worth a crash. Cinema worlds are reused per map name.
 */
public final class ReplayViewer {
    private static final Map<UUID, Session> SESSIONS = new HashMap<UUID, Session>();
    /** One kept-alive game per map name, purely as a world for replaying that map. */
    private static final Map<String, net.h2020.api.game.Game> HOSTS = new HashMap<String, net.h2020.api.game.Game>();
    private static JavaPlugin plugin;
    private static Task task;

    private ReplayViewer() {}

    /** One recorded player in the replay. */
    private static final class Puppet {
        String name, team;
        PacketNpc npc;
        boolean visible;
        int[] worn = new int[]{-1, -1, -1, -1, -1};
        int sneakState = -1;
        boolean invisible;
        String label;
    }

    private static final class Session {
        String gameId;
        String mapName, typeName;
        World world;
        boolean ownsWorld;   // true when the world was spawned for this replay and has to be handed back
        List<long[]> times = new ArrayList<long[]>();
        List<Map<String, double[]>> frames = new ArrayList<Map<String, double[]>>();
        List<Object[]> events = new ArrayList<Object[]>();
        List<Puppet> puppets = new ArrayList<Puppet>();
        int idx;
        double speed = 1.0;
        boolean paused;
        long pausedFor;
        long startedAt;
        long lastT;
        org.bukkit.Location cameFrom;
        double lift;
        List<long[]> blocks = new ArrayList<long[]>();
        // (npc list lives in the parse step, not on the session)
        int blockIdx;
        long appliedT = -1;
        Map<String, int[]> originals = new HashMap<String, int[]>();   // what the world looked like before we touched it
        int blockErrors;
        long ticks;
        int eventIdx;
        long appliedEventT = -1;
        /** A seek happened while paused: apply exactly one frame so the scene matches the new position. */
        boolean forceTick;
        Map<Integer, org.bukkit.entity.Entity> live = new HashMap<Integer, org.bukkit.entity.Entity>();
        int nextLive = 1;
        Map<String, long[]> breaking = new HashMap<String, long[]>();   // "x,y,z" -> {start, end}
        List<org.bukkit.entity.Entity> npcs = new ArrayList<org.bukkit.entity.Entity>();
        long blocksApplied;
        String follow;                       // puppet name the camera tracks (null = free look)
        org.bukkit.inventory.ItemStack[] savedInv;
        org.bukkit.GameMode savedMode;
        boolean savedFlight;
    }

    public static void install(JavaPlugin p) {
        plugin = p;
        task = new Task();
        Bukkit.getScheduler().runTaskTimer(p, task, 20L, 1L);
        Bukkit.getPluginManager().registerEvents(new Controls(), p);
        net.h2020.api.H2020.register(net.h2020.api.game.ReplayService.class, new net.h2020.api.game.ReplayService() {
            @Override public void openBrowser(Player who) { ReplayViewer.openBrowser(who); }
        });
    }

    /** The replay hotbar: every control is a right click on an item, in adventure mode. */
    public static final class Controls implements org.bukkit.event.Listener {
        @org.bukkit.event.EventHandler
        public void onInteract(org.bukkit.event.player.PlayerInteractEvent e) {
            Player p = e.getPlayer();
            Session ses = SESSIONS.get(p.getUniqueId());
            if (ses == null) return;
            org.bukkit.event.block.Action act = e.getAction();
            // only clicks into the air are replay controls. A click on a block or an entity is somebody opening a chest,
            // a shop, an NPC menu or one of our own menus, and blocking those was never the intent.
            boolean right = act == org.bukkit.event.block.Action.RIGHT_CLICK_AIR;
            boolean left = act == org.bukkit.event.block.Action.LEFT_CLICK_AIR;
            if (!right && !left) return;
            if (e.getClickedBlock() != null && ses.world != null && !e.getClickedBlock().getWorld().equals(ses.world)) return;
            org.bukkit.inventory.ItemStack it = p.getItemInHand();
            if (it == null) return;
            org.bukkit.Material mt = it.getType();
            if (mt != org.bukkit.Material.COMPASS && mt != org.bukkit.Material.WATCH
                    && mt != org.bukkit.Material.PAPER && mt != org.bukkit.Material.SLIME_BALL
                    && mt != org.bukkit.Material.BARRIER) return;      // not a replay control: leave it alone
            int slot = p.getInventory().getHeldItemSlot();
            e.setCancelled(true);
            try { handle(p, ses, it, mt, slot, left); } catch (Throwable t) {
                p.sendMessage(net.h2020.api.core.Messages.get("replay.replayviewer.replay-control-failed","p1",t));
                plugin.getLogger().warning("[replay] control " + mt + " failed: " + t);
            }
        }

        private void handle(Player p, Session ses, org.bukkit.inventory.ItemStack it,
                            org.bukkit.Material mt, int slot, boolean left) {
            switch (mt) {
                case COMPASS:
                    if (left) { ses.follow = null; net.h2020.api.core.Messages.send(p, "replay.replayviewer.free-camera"); }
                    else openPlayers(p, ses);
                    return;
                case WATCH: {
                    double[] speeds = {0.25, 0.5, 1, 2, 4, 8};
                    int cur = 0;
                    for (int i = 0; i < speeds.length; i++) if (Math.abs(speeds[i] - ses.speed) < 1e-6) cur = i;
                    ses.speed = speeds[(cur + 1) % speeds.length];
                    giveControls(p, ses);
                    p.sendMessage(net.h2020.api.core.Messages.get("replay.replayviewer.speed-x","p1",ses.speed));
                    return;
                }
                case PAPER: {
                    long now = System.currentTimeMillis();
                    long cur = (long) ((now - ses.startedAt) * ses.speed);
                    seek(ses, Math.max(0, cur + (slot == 3 ? -10_000 : 10_000)));
                    p.sendMessage(net.h2020.api.core.Messages.get("replay.replayviewer.at-s-of-s","p1",((long) ((System.currentTimeMillis() - ses.startedAt) * ses.speed) / 1000),"p2",(ses.times.get(ses.times.size() - 1)[0] / 1000)));
                    return;
                }
                case SLIME_BALL:
                    ses.paused = !ses.paused;
                    if (ses.paused) ses.pausedFor = System.currentTimeMillis();
                    else ses.startedAt += System.currentTimeMillis() - ses.pausedFor;
                    p.sendMessage(ChatColor.GRAY + (ses.paused ? "Paused." : "Resumed."));
                    return;
                case BARRIER:
                    stop(p);
                    net.h2020.api.core.Messages.send(p, "replay.replayviewer.replay-stopped");
                    return;
                default:
                    return;
            }
        }
    }

    public static boolean running(UUID viewer) { return SESSIONS.containsKey(viewer); }

    // ------------------------------------------------------------------------------------------------ commands

    public static void command(CommandSender s, String[] a) {
        String sub = a.length > 1 ? a[1].toLowerCase() : "list";
        if (sub.equals("list")) { list(s); return; }
        if (sub.equals("verify")) {          // console-friendly: parse + build the world, no viewer needed
            if (a.length < 3) { net.h2020.api.core.Messages.send(s, "replay.replayviewer.h2020-replay-verify-gameid"); return; }
            verify(s, a[2]);
            if (a.length > 3) {                       // optional: drive a real session with that player as the viewer
                Player v = Bukkit.getPlayerExact(a[3]);
                if (v == null) { s.sendMessage(net.h2020.api.core.Messages.get("replay.replayviewer.no-player","p1",a[3])); return; }
                start(v, a[2], 4.0);
                Session ses = SESSIONS.get(v.getUniqueId());
                if (ses != null && a.length > 4 && a[4].equalsIgnoreCase("menu")) {
                    openPlayers(v, ses);          // exercise the player picker without a human hand
                    s.sendMessage(net.h2020.api.core.Messages.get("replay.replayviewer.player-menu-opened-for","p1",v.getName()));
                }
                s.sendMessage(net.h2020.api.core.Messages.get("replay.replayviewer.session-started-for","p1",v.getName()));
            }
            return;
        }
        if (!(s instanceof Player)) {
            // the console can drive a session for a named player (testing, or starting one for somebody else)
            boolean targeted = a.length > 4 && Bukkit.getPlayerExact(a[4]) != null;
            boolean boardOnly = a.length > 1 && a[1].equalsIgnoreCase("board");
            if (!targeted && !boardOnly) { net.h2020.api.core.Messages.send(s, "replay.replayviewer.replays-are-watched-in"); return; }
        }
        // from here on the "player" is whoever the command is about: the sender, or the named target for the console
        Player p = s instanceof Player ? (Player) s : (a.length > 4 ? Bukkit.getPlayerExact(a[4]) : null);
        if (p == null && sub.equals("board")) p = a.length > 2 ? Bukkit.getPlayerExact(a[2]) : null;
        if (p == null) { s.sendMessage(net.h2020.api.core.Messages.get("replay.replayviewer.name-a-player-h2020","p1",sub)); return; }
        if (sub.equals("stop")) { stop(p); return; }
        if (sub.equals("pause") || sub.equals("resume")) {
            Session ses = SESSIONS.get(p.getUniqueId());
            if (ses == null) { net.h2020.api.core.Messages.send(p, "replay.replayviewer.you-are-not-watching"); return; }
            if (sub.equals("pause") && !ses.paused) { ses.paused = true; ses.pausedFor = System.currentTimeMillis(); }
            if (sub.equals("resume") && ses.paused) {
                ses.paused = false;
                ses.startedAt += System.currentTimeMillis() - ses.pausedFor;
            }
            p.sendMessage(ChatColor.GRAY + "Replay " + (ses.paused ? "paused" : "resumed") + ".");
            return;
        }
        if (sub.equals("seek")) {
            Session ses = SESSIONS.get(p.getUniqueId());
            if (ses == null) { net.h2020.api.core.Messages.send(p, "replay.replayviewer.you-are-not-watching"); return; }
            double secs = a.length > 2 ? parse(a[2], 0) : 0;
            seek(ses, (long) (secs * 1000));
            p.sendMessage(net.h2020.api.core.Messages.get("replay.replayviewer.replay-at-s","p1",(int) (secs)));
            return;
        }
        if (sub.equals("board")) {
            Player who = a.length > 2 ? Bukkit.getPlayerExact(a[2]) : p;
            if (who == null) { net.h2020.api.core.Messages.send(s, "replay.replayviewer.no-such-player"); return; }
            org.bukkit.scoreboard.Objective o =
                    who.getScoreboard().getObjective(org.bukkit.scoreboard.DisplaySlot.SIDEBAR);
            if (o == null) { s.sendMessage(net.h2020.api.core.Messages.get("replay.replayviewer.no-sidebar-for","p1",who.getName())); return; }
            s.sendMessage(net.h2020.api.core.Messages.get("replay.replayviewer.sidebar-of","p1",who.getName(),"p2",o.getDisplayName()));
            for (String e : o.getScoreboard().getEntries()) {
                org.bukkit.scoreboard.Score s2 = o.getScore(e);
                if (s2 != null) s.sendMessage(ChatColor.DARK_GRAY + "  " + e + ChatColor.GRAY + " -> " + s2.getScore());
            }
            return;
        }
        if (sub.equals("start")) {
            if (a.length < 3) { net.h2020.api.core.Messages.send(p, "replay.replayviewer.h2020-replay-start-gameid"); return; }
            double speed = a.length > 3 ? Math.max(0.25, Math.min(8, parse(a[3], 1))) : 1.0;
            Player who = a.length > 4 ? Bukkit.getPlayerExact(a[4]) : p;
            if (who == null) { p.sendMessage(net.h2020.api.core.Messages.get("replay.replayviewer.no-such-player","p1",a[4])); return; }
            start(who, a[2], speed);
            return;
        }
        net.h2020.api.core.Messages.send(p, "replay.replayviewer.h2020-replay-list-start");
    }

    /** Read a recording end to end and build its cinema world, reporting what it contains. */
    private static void verify(CommandSender s, String gameId) {
        File f = new File(Recorder.dir(), gameId + ".jsonl");
        if (!f.isFile()) { s.sendMessage(net.h2020.api.core.Messages.get("replay.replayviewer.no-recording-called","p1",gameId)); return; }
        String map = null;
        int frames = 0, rows = 0;
        long last = 0;
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader(f));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("{\"h\":")) { map = field(line, "map"); continue; }
                if (line.startsWith("{\"end\":")) continue;
                frames++;
                last = (long) num(line, "t", last);
                if (rows == 0) rows = line.split(java.util.regex.Pattern.quote("],[")).length;
            }
        } catch (Throwable e) {
            s.sendMessage(net.h2020.api.core.Messages.get("replay.replayviewer.unreadable","p1",e));
            return;
        } finally {
            try { if (r != null) r.close(); } catch (Throwable ignored) { }
        }
        int blockEvents = 0;
        BufferedReader r2 = null;
        try {
            r2 = new BufferedReader(new FileReader(f));
            String l2;
            while ((l2 = r2.readLine()) != null) if (l2.contains("\"b\":[")) blockEvents++;
        } catch (Throwable ignored) {
        } finally {
            try { if (r2 != null) r2.close(); } catch (Throwable ignored) { }
        }
        s.sendMessage(net.h2020.api.core.Messages.get("replay.replayviewer.replay-map-frames-players","p1",gameId,"p2",map,"p3",frames,"p4",rows,"p5",blockEvents,"p6",(last / 1000)));
    }

    private static void list(CommandSender s) {
        File dir = Recorder.dir();
        File[] files = dir == null ? null : dir.listFiles();
        if (files == null || files.length == 0) { net.h2020.api.core.Messages.send(s, "replay.replayviewer.no-recordings-yet"); return; }
        List<File> list = new ArrayList<File>();
        for (File f : files) if (f.getName().endsWith(".jsonl")) list.add(f);
        list.sort(new java.util.Comparator<File>() {
            @Override public int compare(File x, File y) { return Long.compare(y.lastModified(), x.lastModified()); }
        });
        s.sendMessage(net.h2020.api.core.Messages.get("replay.replayviewer.recorded-games","p1",list.size()));
        int shown = 0;
        for (File f : list) {
            if (shown++ >= 12) break;
            String mode = "?";
            try {
                BufferedReader r = new BufferedReader(new FileReader(f));
                try { mode = modeName(field(r.readLine(), "type")); } finally { r.close(); }
            } catch (Throwable ignored) { }
            s.sendMessage(net.h2020.api.core.Messages.get("replay.replayviewer.kb","p1",f.getName().replace(".jsonl", ""),"p2",mode,"p3",(f.length() / 1024),"p4",new java.text.SimpleDateFormat("dd MMM HH:mm").format(new java.util.Date(f.lastModified()))));
        }
        net.h2020.api.core.Messages.send(s, "replay.replayviewer.watch-one-with-h2020");
    }

    // ------------------------------------------------------------------------------------------------ playback

    private static void start(Player p, String gameId, double speed) {
        File f = new File(Recorder.dir(), gameId + ".jsonl");
        if (!f.isFile()) { p.sendMessage(net.h2020.api.core.Messages.get("replay.replayviewer.no-recording-called","p1",gameId)); return; }
        stop(p);
        String mapName = null;
        List<long[]> times = new ArrayList<long[]>();
        List<Map<String, double[]>> frames = new ArrayList<Map<String, double[]>>();
        List<Object[]> events = new ArrayList<Object[]>();
        List<Object[]> npcList = new ArrayList<Object[]>();
        List<long[]> blocks = new ArrayList<long[]>();
        Map<String, String> names = new LinkedHashMap<String, String>();
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader(f));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("{\"h\":")) {
                    mapName = field(line, "map");
                    String npcPart = between(line, "\"npcs\":[", "]");
                    if (npcPart != null && npcPart.contains("[")) {
                        for (String row : npcPart.split(java.util.regex.Pattern.quote("],["))) {
                            String[] c = row.replace("[", "").replace("]", "").split(",");
                            if (c.length < 4) continue;
                            Object[] ev = new Object[]{0L, "npc", c[0].replace("\"", ""),
                                    parse(c[1], 0), parse(c[2], 0), parse(c[3], 0),
                                    c.length > 5 ? c[5].replace("\"", "") : ""};
                            npcList.add(ev);
                        }
                    }
                    continue;
                }
                if (line.startsWith("{\"end\":")) continue;
                if (line.contains("\"e\":[")) {
                    long et = (long) num(line, "t", 0);
                    String body = between(line, "\"e\":[", "]");
                    if (body == null) continue;
                    String[] c = body.split(",");
                    if (c.length < 1) continue;
                    Object[] ev = new Object[]{et, c[0].replace("\"", ""), 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, ""};
                    for (int k = 1; k < c.length && k <= 7; k++) {
                        String v = c[k].replace("\"", "");
                        ev[k + 1] = parse(v, 0);
                    }
                    if (c.length > 8) ev[8] = c[8].replace("\"", "");
                    events.add(ev);
                    continue;
                }
                if (line.contains("\"b\":[")) {
                    long bt = (long) num(line, "t", 0);
                    int bi = line.indexOf("\"b\":[") + 5;
                    int be = line.indexOf(']', bi);
                    if (be < 0) continue;
                    String[] bc = line.substring(bi, be).split(",");
                    if (bc.length < 6) continue;
                    long[] ev = new long[8];
                    ev[0] = bt;
                    for (int k = 0; k < 6; k++) ev[1 + k] = (long) parse(bc[k], 0);
                    blocks.add(ev);                 // {t,x,y,z,newId,data,oldId,0}
                    continue;
                }
                long t = (long) num(line, "t", 0);
                String body = line.substring(line.indexOf("\"p\":[") + 5);
                if (body.endsWith("]}")) body = body.substring(0, body.length() - 1);
                String[] rows = body.contains("],[") ? body.split(java.util.regex.Pattern.quote("],[")) : new String[]{body};
                Map<String, double[]> fr = new java.util.HashMap<String, double[]>();
                for (int i = 0; i < rows.length; i++) {
                    String row = rows[i].replace("[", "").replace("]", "");
                    String[] c = row.split(",");
                    if (c.length < 10) continue;
                    String nm = c[0].replace("\"", "");
                    if (!names.containsKey(nm)) names.put(nm, c[1].replace("\"", ""));
                    double[] v = new double[13];
                    v[0] = parse(c[2], 0); v[1] = parse(c[3], 0); v[2] = parse(c[4], 0);
                    v[3] = parse(c[5], 0); v[4] = parse(c[6], 0); v[5] = parse(c[7], 20);
                    v[6] = parse(c[8], 0); v[7] = parse(c[9], 0);
                    // held, helmet, chest, legs, boots (recordings from before equipment existed have none)
                    for (int k = 0; k < 5; k++) v[8 + k] = c.length > 10 + k ? parse(c[10 + k], -1) : -1;
                    v[12] = c.length > 14 ? parse(c[14], 0) : 0;      // swing | hurt | eat
                    fr.put(nm, v);
                }
                times.add(new long[]{t});
                frames.add(fr);
            }
        } catch (Throwable e) {
            p.sendMessage(net.h2020.api.core.Messages.get("replay.replayviewer.could-not-read-that","p1",e));
            return;
        } finally {
            try { if (r != null) r.close(); } catch (Throwable ignored) { }
        }
        if (frames.isEmpty()) { net.h2020.api.core.Messages.send(p, "replay.replayviewer.that-recording-is-empty"); return; }

        // The replay needs the map's world, and only a game-owned world is safe: an instance world created outside a
        // game is ticked by WindSpigot with a chunk provider that was never attached, which crashes the server. So the
        // replay borrows a live game on that map, or creates a host game (never filled, never started) and keeps it.
        World world = hostWorld(mapName);
        if (world == null) {
            p.sendMessage(net.h2020.api.core.Messages.get("replay.replayviewer.no-map-called-to","p1",mapName));
            return;
        }
        double lift = 0;

        Session ses = new Session();
        ses.gameId = gameId;
        ses.world = world;
        ses.ownsWorld = net.h2020.api.map.SlimeWorlds.available()
                && world.getName().startsWith("instances/swm_");
        ses.times = times;
        ses.frames = frames;
        ses.blocks = blocks;
        ses.events = events;
        ses.npcs.addAll(spawnNpcs(world, npcList));
        if (!npcList.isEmpty()) plugin.getLogger().info("[replay] " + gameId + ": " + ses.npcs.size()
                + " static NPCs spawned of " + npcList.size() + " recorded");
        events.sort(new java.util.Comparator<Object[]>() {
            @Override public int compare(Object[] a, Object[] b) { return Long.compare((Long) a[0], (Long) b[0]); }
        });
        blocks.sort(new java.util.Comparator<long[]>() {
            @Override public int compare(long[] a, long[] b) { return Long.compare(a[0], b[0]); }
        });
        ses.speed = speed;
        for (Map.Entry<String, String> e2 : names.entrySet()) {
            String nm = e2.getKey();
            double[] first = null;
            for (Map<String, double[]> fr : frames) { first = fr.get(nm); if (first != null) break; }
            double[] v = first == null ? new double[]{0, 80, 0, 0, 0, 20, 0, 0} : first;
            v = new double[]{v[0], v[1] + lift, v[2], v[3], v[4], v[5], v[6], v[7]};
            Puppet pu = new Puppet();
            pu.name = nm;
            pu.team = e2.getValue();
            try {
                pu.npc = new PacketNpc(nm, new Location(world, v[0], v[1], v[2]), null, null);
            } catch (Throwable t) {
                plugin.getLogger().warning("[replay] puppet " + nm + " failed: " + t);
                continue;
            }
            ses.puppets.add(pu);
        }
        ses.lift = lift;
        ses.startedAt = System.currentTimeMillis();
        // what the replay sidebar shows: which game, which map, which mode
        try {
            java.io.BufferedReader hr = new java.io.BufferedReader(new java.io.FileReader(f));
            try {
                String head = hr.readLine();
                ses.mapName = field(head, "map");
                ses.typeName = modeName(field(head, "type"));
            } finally { hr.close(); }
        } catch (Throwable ignored) { }
        SESSIONS.put(p.getUniqueId(), ses);
        net.h2020.api.core.ReplayState.watching(p.getUniqueId(), true);

        // stand behind the first puppet's start, looking at where the action is
        double[] first = null;
        for (Map<String, double[]> fr : frames) { if (!fr.isEmpty()) { first = fr.values().iterator().next(); break; } }
        double sx = first == null ? 0.5 : first[0], sz = first == null ? 0.5 : first[2], sy = first == null ? 90 : first[1];
        p.teleport(new Location(world, sx, sy + 12, sz + 7, 0f, 55f));
        ses.cameFrom = p.getLocation().clone();
        ses.savedInv = p.getInventory().getContents();
        ses.savedMode = p.getGameMode();
        ses.savedFlight = p.getAllowFlight();
        p.setGameMode(org.bukkit.GameMode.ADVENTURE);      // watch, walk around, break nothing
        p.setAllowFlight(true);
        p.setFlying(true);
        giveControls(p, ses);
        for (Puppet pu : ses.puppets) { pu.npc.show(p); pu.visible = true; }
        replayBoard(p, ses);

        p.sendMessage(net.h2020.api.core.Messages.get("replay.replayviewer.replaying-on-at-x","p1",gameId,"p2",mapName,"p3",speed,"p4",ses.puppets.size(),"p5",(times.get(times.size() - 1)[0] / 1000)));
    }

    private static int indexOfPuppet(double[][] frame, String[] order, String name) {
        for (int i = 0; i < order.length; i++) if (order[i].equals(name)) return i;
        return 0;
    }


    /** A game-owned world for this map: a live game if one is running, otherwise a kept-alive host game. */
    private static World hostWorld(String mapName) {
        if (mapName == null) return null;
        // a slime copy is a private world: nobody's game is running in it, so the replay cannot overlap live blocks
        World own = net.h2020.api.map.SlimeWorlds.spawn(mapName, "replay");
        if (own != null) return own;
        net.h2020.api.game.GameManager gm = H2020.find(net.h2020.api.game.GameManager.class);
        if (gm == null) return null;
        for (net.h2020.api.game.Game g : gm.games()) {
            if (g.mapName() != null && g.mapName().equalsIgnoreCase(mapName) && g.world() != null) return g.world();
        }
        net.h2020.api.game.Game host = HOSTS.get(mapName);
        if (host != null && host.world() != null && gm.games().contains(host)) return host.world();
        try {
            net.h2020.api.map.MapService ms = H2020.find(net.h2020.api.map.MapService.class);
            net.h2020.api.map.MapTemplate t = ms == null ? null : ms.byName(mapName);
            if (t == null) return null;
            net.h2020.api.game.GameType type = net.h2020.api.game.GameType.BEDWARS_4V4V4V4;
            net.h2020.api.game.Game g = gm.create(type, t);
            if (g == null) return null;
            HOSTS.put(mapName, g);
            publishHost(g, true);
            plugin.getLogger().info("[replay] host game " + g.id() + " on " + mapName + " for playback");
            return g.world();
        } catch (Throwable e) {
            plugin.getLogger().warning("[replay] no host world for " + mapName + ": " + e);
            return null;
        }
    }

    /** The queue filler must never fill a replay host with bots. */
    public static boolean isHost(net.h2020.api.game.Game g) {
        if (g == null) return false;
        for (net.h2020.api.game.Game h : HOSTS.values()) if (h == g) return net.h2020.api.core.ReplayState.isHost(g.id());
        return false;
    }

    /** Tell the rest of the network that this game world is a replay (or no longer one). */
    private static void publishHost(net.h2020.api.game.Game g, boolean host) {
        if (g != null) net.h2020.api.core.ReplayState.setHost(g.id(), host);
    }

    /**
     * The replay's own sidebar: clock, length, speed and state. Deliberately separate from the game and lobby boards so
     * a watcher can read the playback at a glance instead of guessing from the action bar.
     */
    private static void replayBoard(Player p, Session ses) {
        net.h2020.api.stats.CombatStats.inc("replay.board");
        try {
            net.h2020.api.core.ScoreboardService sb =
                    net.h2020.api.H2020.find(net.h2020.api.core.ScoreboardService.class);
            if (sb == null) return;
            long duration = ses.times.isEmpty() ? 0 : ses.times.get(ses.times.size() - 1)[0];
            long now = ses.paused ? (long) ((ses.pausedFor - ses.startedAt) * ses.speed)
                    : (long) ((System.currentTimeMillis() - ses.startedAt) * ses.speed);
            now = Math.max(0, Math.min(duration, now));
            java.util.List<String> lines = new java.util.ArrayList<String>();
            lines.add("§7Game §f" + ses.gameId);
            lines.add("§7Map §f" + (ses.mapName == null ? "?" : ses.mapName));
            lines.add("§7Mode §f" + (ses.typeName == null ? "?" : ses.typeName));
            lines.add("");
            lines.add("§7Time §f" + clock(now) + " §7/ " + clock(duration));
            lines.add("§7Speed §f" + ses.speed + "x");
            lines.add("§7State " + (ses.paused ? "§cPaused" : "§aPlaying"));
            if (ses.follow != null) lines.add("§7Following §f" + ses.follow);
            lines.add("");
            lines.add("§8space §7pause   §8paper §7±10s");
            lines.add("§8slime §7players   §8barrier §7stop");
            sb.setSidebar(p, "§b§lREPLAY", lines);
        } catch (Throwable ignored) { }
    }

    /** Is this player watching a replay right now? Game listeners use it to stop policing their inventory. */
    public static boolean isWatching(Player p) {
        return p != null && SESSIONS.containsKey(p.getUniqueId());
    }

    private static void stop(Player p) {
        Session ses = SESSIONS.remove(p.getUniqueId());
        net.h2020.api.core.ReplayState.watching(p.getUniqueId(), false);
        if (ses == null) return;
        for (Puppet pu : ses.puppets) { try { pu.npc.hide(p); } catch (Throwable ignored) { } }
        try { revertBlocks(ses); } catch (Throwable ignored) { }        // leave the map exactly as we found it
        for (org.bukkit.entity.Entity e : ses.npcs) { try { e.remove(); } catch (Throwable ignored) { } }
        ses.npcs.clear();
        try { giveInventoryBack(p, ses); } catch (Throwable ignored) { }
        try {
            net.h2020.api.core.ScoreboardService sb = net.h2020.api.H2020.find(net.h2020.api.core.ScoreboardService.class);
            if (sb != null) sb.clearSidebar(p);
        } catch (Throwable ignored) { }
        try {
            // a finished replay returns the player to the lobby: the world it was started from can be a game instance
            // that has since been torn down, and the lobby is where a watcher wants to be afterwards anyway
            net.h2020.api.game.GameManager gm = net.h2020.api.H2020.find(net.h2020.api.game.GameManager.class);
            if (gm != null) gm.sendToLobby(p, null);
            else if (ses.cameFrom != null && ses.cameFrom.getWorld() != null) p.teleport(ses.cameFrom);
        } catch (Throwable ignored) { }
        if (ses.ownsWorld) {
            ses.ownsWorld = false;
            try { net.h2020.api.map.SlimeWorlds.release(ses.world); } catch (Throwable ignored) { }
        }
    }

    /** One packet: the replay clock, in the action bar like a video player. */
    private static void actionBar(Player p, String text) {
        try {
            net.minecraft.server.v1_8_R3.IChatBaseComponent c =
                    net.minecraft.server.v1_8_R3.IChatBaseComponent.ChatSerializer.a("{\"text\":\"" + text + "\"}");
            net.h2020.api.packet.Packets.send(p, new net.minecraft.server.v1_8_R3.PacketPlayOutChat(c, (byte) 2));
        } catch (Throwable ignored) { }
    }

    private static String clock(long ms) {
        long s = Math.max(0, ms / 1000);
        return String.format(java.util.Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    /** The replay hotbar: speed, jump back/forward, pause, players, stop. */
    private static void giveControls(Player p, Session ses) {
        p.getInventory().clear();
        p.getInventory().setArmorContents(null);
        p.getInventory().setItem(0, new net.h2020.api.gui.ItemBuilder(org.bukkit.Material.COMPASS)
                .name(ChatColor.AQUA + "Replay players")
                .lore(ChatColor.GRAY + "Right click: pick a player to follow",
                        ChatColor.GRAY + "Left click: free camera")
                .build());
        p.getInventory().setItem(1, new net.h2020.api.gui.ItemBuilder(org.bukkit.Material.WATCH)
                .name(ChatColor.YELLOW + "Speed: " + speedName(ses))
                .lore(ChatColor.GRAY + "Right click to cycle 0.25x - 8x")
                .build());
        p.getInventory().setItem(3, new net.h2020.api.gui.ItemBuilder(org.bukkit.Material.PAPER)
                .name(ChatColor.GOLD + "Back 10s")
                .lore(ChatColor.GRAY + "Right click: jump back ten seconds")
                .build());
        p.getInventory().setItem(5, new net.h2020.api.gui.ItemBuilder(org.bukkit.Material.PAPER)
                .name(ChatColor.GOLD + "Forward 10s")
                .lore(ChatColor.GRAY + "Right click: jump forward ten seconds")
                .build());
        p.getInventory().setItem(4, new net.h2020.api.gui.ItemBuilder(org.bukkit.Material.SLIME_BALL)
                .name(ChatColor.GREEN + "Pause / resume")
                .build());
        p.getInventory().setItem(8, new net.h2020.api.gui.ItemBuilder(org.bukkit.Material.BARRIER)
                .name(ChatColor.RED + "Stop replay")
                .build());
        p.updateInventory();
    }

    private static String speedName(Session ses) { return ses.speed + "x"; }

    private static void giveInventoryBack(Player p, Session ses) {
        p.getInventory().clear();
        if (ses.savedInv != null) p.getInventory().setContents(ses.savedInv);
        p.setGameMode(ses.savedMode == null ? org.bukkit.GameMode.SURVIVAL : ses.savedMode);
        p.setAllowFlight(ses.savedFlight);
        if (!ses.savedFlight) p.setFlying(false);
        p.updateInventory();
    }

    /** The player picker: one head per recorded player, click to follow them. */
    private static void openPlayers(Player p, Session ses) {
        net.h2020.api.gui.Menu menu = new net.h2020.api.gui.Menu(ChatColor.DARK_GRAY + "Replay players", 6);
        int slot = 0;
        for (final Puppet pu : ses.puppets) {
            if (slot >= 53) break;
            menu.set(slot++, head(pu.name, teamColor(pu.team) + pu.name,
                    ChatColor.GRAY + "Team " + pu.team, "", ChatColor.YELLOW + "Click to follow"),
                    new net.h2020.api.gui.Menu.Click() {
                @Override public void click(Player who, org.bukkit.event.inventory.ClickType type) {
                    Session s2 = SESSIONS.get(who.getUniqueId());
                    if (s2 != null) s2.follow = pu.name;
                    who.closeInventory();
                    who.sendMessage(net.h2020.api.core.Messages.get("replay.replayviewer.following","p1",pu.name));
                }
            });
        }
        menu.set(53, new net.h2020.api.gui.ItemBuilder(org.bukkit.Material.ARROW)
                .name(ChatColor.GRAY + "Free camera")
                .build(), new net.h2020.api.gui.Menu.Click() {
            @Override public void click(Player who, org.bukkit.event.inventory.ClickType type) {
                Session s2 = SESSIONS.get(who.getUniqueId());
                if (s2 != null) s2.follow = null;
                who.closeInventory();
            }
        });
        menu.open(p);
    }

    /** A player head with a name and lore, built by hand so a missing profile cannot break the whole menu. */
    private static org.bukkit.inventory.ItemStack head(String owner, String name, String... lore) {
        org.bukkit.inventory.ItemStack it = new org.bukkit.inventory.ItemStack(org.bukkit.Material.SKULL_ITEM, 1, (short) 3);
        org.bukkit.inventory.meta.ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            if (lore.length > 0) meta.setLore(java.util.Arrays.asList(lore));
            if (meta instanceof org.bukkit.inventory.meta.SkullMeta) {
                try { ((org.bukkit.inventory.meta.SkullMeta) meta).setOwner(owner); } catch (Throwable ignored) { }
            }
            it.setItemMeta(meta);
        }
        return it;
    }

    /** The browser: every recorded game, newest first, click to watch. */
    public static void openBrowser(Player p) {
        File dir = Recorder.dir();
        File[] files = dir == null ? null : dir.listFiles();
        java.util.List<File> list = new ArrayList<File>();
        if (files != null) for (File f : files) if (f.getName().endsWith(".jsonl")) list.add(f);
        list.sort(new java.util.Comparator<File>() {
            @Override public int compare(File a, File b) { return Long.compare(b.lastModified(), a.lastModified()); }
        });
        net.h2020.api.gui.Menu menu = new net.h2020.api.gui.Menu(ChatColor.DARK_GRAY + "Replays", 6);
        int slot = 0;
        for (final File f : list) {
            if (slot >= 45) break;
            final String id = f.getName().replace(".jsonl", "");
            String line1 = null;
            try {
                BufferedReader r = new BufferedReader(new FileReader(f));
                try { line1 = r.readLine(); } finally { r.close(); }
            } catch (Throwable ignored) { }
            String map = line1 == null ? "?" : field(line1, "map");
            String mode = modeName(line1 == null ? null : field(line1, "type"));
            menu.set(slot++, new net.h2020.api.gui.ItemBuilder(org.bukkit.Material.MAP)
                    .name(ChatColor.GREEN + id)
                    .lore(ChatColor.GRAY + "Mode: " + ChatColor.WHITE + mode,
                            ChatColor.GRAY + "Map: " + ChatColor.WHITE + map,
                            ChatColor.GRAY + "Size: " + ChatColor.WHITE + (f.length() / 1024) + " KB",
                            ChatColor.GRAY + "Recorded: " + ChatColor.WHITE
                                    + new java.text.SimpleDateFormat("dd MMM HH:mm").format(new java.util.Date(f.lastModified())),
                            "", ChatColor.YELLOW + "Click to watch")
                    .build(), new net.h2020.api.gui.Menu.Click() {
                @Override public void click(Player who, org.bukkit.event.inventory.ClickType type) {
                    who.closeInventory();
                    start(who, id, 1.0);
                }
            });
        }
        if (slot == 0) {
            menu.set(22, new net.h2020.api.gui.ItemBuilder(org.bukkit.Material.BARRIER)
                    .name(ChatColor.RED + "No recordings yet")
                    .lore(ChatColor.GRAY + "Games are recorded automatically").build());
        }
        menu.open(p);
    }

    private static ChatColor teamColor(String team) {
        try { return ChatColor.valueOf(team); } catch (Throwable t) { return ChatColor.WHITE; }
    }

    /**
     * Put the world back the way the recording left it. Everything we changed is remembered with its original block,
     * so a replay leaves nothing behind: the host world stays a clean copy of the map.
     */
    private static void revertBlocks(Session ses) {
        World w = ses.world;
        for (Map.Entry<String, int[]> e : ses.originals.entrySet()) {
            String[] p = e.getKey().split(",");
            try {
                int[] o = e.getValue();
                org.bukkit.block.Block b = w.getBlockAt(Integer.parseInt(p[0]), Integer.parseInt(p[1]),
                        Integer.parseInt(p[2]));
                b.setTypeIdAndData(o[0], (byte) o[1], false);
            } catch (Throwable ignored) { }
        }
        ses.originals.clear();
        ses.blockIdx = 0;
        ses.appliedT = -1;
        clearLive(ses);
    }

    /** Apply (or undo, when the clock went backwards) every block event up to this time. */
    private static void applyBlocks(Session ses, long t, Player p) {
        if (ses.blocks.isEmpty()) return;
        if (t < ses.appliedT) { revertBlocks(ses); }
        World w = ses.world;
        while (ses.blockIdx < ses.blocks.size() && ses.blocks.get(ses.blockIdx)[0] <= t) {
            long[] ev = ses.blocks.get(ses.blockIdx++);
            int x = (int) ev[1], y = (int) ev[2], z = (int) ev[3];
            int id = (int) ev[4], data = (int) ev[5], old = (int) ev[6];
            String key = x + "," + y + "," + z;
            try {
                org.bukkit.block.Block b = w.getBlockAt(x, y, z);
                if (!ses.originals.containsKey(key)) ses.originals.put(key, new int[]{b.getTypeId(), b.getData()});
                if (id == 0) b.setTypeIdAndData(old == 0 ? 0 : old, (byte) data, false);
                else b.setTypeIdAndData(id, (byte) data, false);
                p.playSound(new Location(w, x + 0.5, y + 0.5, z + 0.5),
                        blockSound(id == 0 ? old : id, id != 0), 0.7f, 1f);
                ses.blocksApplied++;
            } catch (Throwable err) {
                ses.blockErrors++;
            }
        }
        ses.appliedT = t;
    }

    /** Sound for a block, so a replay is not silent. */
    private static org.bukkit.Sound blockSound(int id, boolean place) {
        if (id == 35 || id == 159) return place ? org.bukkit.Sound.DIG_WOOL : org.bukkit.Sound.DIG_WOOL;
        if (id == 20 || id == 95 || id == 102) return org.bukkit.Sound.GLASS;
        if (id == 24 || id == 98) return org.bukkit.Sound.STEP_STONE;
        if (id == 5 || id == 17 || id == 25) return org.bukkit.Sound.STEP_WOOD;
        return org.bukkit.Sound.STEP_STONE;
    }

    /**
     * Projectiles, dropped items, break progress, explosions and potion effects, replayed against the same clock as
     * the blocks: real entities in the replay world, so the client draws them exactly as it did live.
     */
    private static void applyEvents(Session ses, long t, Player p) {
        if (t < ses.appliedEventT) clearLive(ses);
        while (ses.eventIdx < ses.events.size() && (Long) ses.events.get(ses.eventIdx)[0] <= t) {
            Object[] ev = ses.events.get(ses.eventIdx++);
            String kind = (String) ev[1];
            double x = (Double) ev[2], y = (Double) ev[3], z = (Double) ev[4];
            double a = (Double) ev[5], b = (Double) ev[6], c = (Double) ev[7];
            Location at = new Location(ses.world, x, y, z);
            try {
                if (kind.equals("p")) {                                   // a projectile leaves a hand
                    org.bukkit.entity.EntityType type = org.bukkit.entity.EntityType.valueOf((String) ev[8]);
                    org.bukkit.entity.Entity e = ses.world.spawnEntity(at, type);
                    e.setVelocity(new org.bukkit.util.Vector(a, b, c));
                    ses.live.put(ses.nextLive++, e);
                    p.playSound(at, type == org.bukkit.entity.EntityType.ARROW ? org.bukkit.Sound.SHOOT_ARROW
                                    : type == org.bukkit.entity.EntityType.ENDER_PEARL ? org.bukkit.Sound.ENDERMAN_TELEPORT
                                    : org.bukkit.Sound.SHOOT_ARROW, 1f, 1f);
                } else if (kind.equals("ph")) {                            // it landed
                    removeNearest(ses, at, 4.0, true);
                    ses.world.playEffect(at, org.bukkit.Effect.CRIT, 0);
                } else if (kind.equals("i")) {                             // a dropped item
                    org.bukkit.entity.Item item = ses.world.dropItem(at, new org.bukkit.inventory.ItemStack((int) a, 1, (short) b));
                    item.setVelocity(new org.bukkit.util.Vector(0, 0.1, 0));
                    ses.live.put(ses.nextLive++, item);
                } else if (kind.equals("ir")) {                            // picked up
                    removeNearest(ses, at, 2.5, false);
                } else if (kind.equals("br")) {                            // started breaking a block
                    long ticks = (long) Math.max(4, a);
                    ses.breaking.put((int) x + "," + (int) y + "," + (int) z,
                            new long[]{t, t + ticks * 50});
                } else if (kind.equals("x")) {                             // explosion
                    ses.world.playEffect(at, org.bukkit.Effect.EXPLOSION_LARGE, 0);
                    p.playSound(at, org.bukkit.Sound.EXPLODE, 1.2f, 1f);
                }
            } catch (Throwable ignored) { }
        }
        // crack stages while a block is being broken
        for (java.util.Iterator<Map.Entry<String, long[]>> it = ses.breaking.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, long[]> e = it.next();
            String[] xyz = e.getKey().split(",");
            long[] span = e.getValue();
            if (t > span[1]) {
                try {
                    net.h2020.api.packet.Packets.send(p, new net.minecraft.server.v1_8_R3.PacketPlayOutBlockBreakAnimation(
                            e.getKey().hashCode() & 0x7FFF,
                            new net.minecraft.server.v1_8_R3.BlockPosition(Integer.parseInt(xyz[0]),
                                    Integer.parseInt(xyz[1]), Integer.parseInt(xyz[2])), -1));
                } catch (Throwable ignored) { }
                it.remove();
                continue;
            }
            int stage = (int) Math.min(9, Math.max(0, (t - span[0]) * 10 / Math.max(1, span[1] - span[0])));
            try {
                net.h2020.api.packet.Packets.send(p, new net.minecraft.server.v1_8_R3.PacketPlayOutBlockBreakAnimation(
                        e.getKey().hashCode() & 0x7FFF,
                        new net.minecraft.server.v1_8_R3.BlockPosition(Integer.parseInt(xyz[0]),
                                Integer.parseInt(xyz[1]), Integer.parseInt(xyz[2])), stage));
            } catch (Throwable ignored) { }
        }
        ses.appliedEventT = t;
    }

    /** Remove the nearest tracked entity (projectile or item) to a point. */
    private static void removeNearest(Session ses, Location at, double radius, boolean projectile) {
        Integer bestKey = null;
        double best = radius * radius;
        for (Map.Entry<Integer, org.bukkit.entity.Entity> e : ses.live.entrySet()) {
            org.bukkit.entity.Entity en = e.getValue();
            if (!en.isValid()) continue;
            boolean isItem = en instanceof org.bukkit.entity.Item;
            if (projectile == isItem) continue;
            double d = en.getLocation().distanceSquared(at);
            if (d < best) { best = d; bestKey = e.getKey(); }
        }
        if (bestKey == null) return;
        org.bukkit.entity.Entity en = ses.live.remove(bestKey);
        try { if (en != null) en.remove(); } catch (Throwable ignored) { }
    }

    private static void clearLive(Session ses) {
        for (org.bukkit.entity.Entity e : ses.live.values()) { try { e.remove(); } catch (Throwable ignored) { } }
        ses.live.clear();
        ses.eventIdx = 0;
        ses.appliedEventT = -1;
        ses.breaking.clear();
    }

    /**
     * Move the clock to {@code t} milliseconds. While paused nothing is applied by the playback loop, so a seek would
     * move the index but leave the world showing the old frame - the helper arms {@code forceTick} so exactly one frame
     * is drawn at the new position. The event cursor is moved with it: events are drained in order, so a backward seek
     * that left it alone would skip everything between the two positions.
     */
    private static void seek(Session ses, long t) {
        long duration = ses.times.get(ses.times.size() - 1)[0];
        long target = Math.max(0, Math.min(duration, t));
        ses.startedAt = System.currentTimeMillis() - (long) (target / ses.speed);
        ses.idx = indexAt(ses, target);
        // put the event cursor back to the first event at or after the new position (events drained up to t are gone)
        int lo = 0, hi = ses.events.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (((Long) ses.events.get(mid)[0]) < target) lo = mid + 1;
            else hi = mid;
        }
        ses.eventIdx = lo;
        ses.appliedEventT = -1;
        // anything alive (arrows, dropped items) and any crack overlay belongs to the old position
        clearLive(ses);
        ses.breaking.clear();
        if (ses.paused) ses.forceTick = true;
    }

    private static int indexAt(Session ses, long t) {
        for (int i = 0; i < ses.times.size(); i++) if (ses.times.get(i)[0] >= t) return i;
        return ses.times.size() - 1;
    }

    /** The playback clock: one frame per recorded frame, paced against the wall clock. */
    private static final class Task implements Runnable {
        @Override public void run() {
            if (SESSIONS.isEmpty()) return;
            long now = System.currentTimeMillis();
            for (Map.Entry<UUID, Session> e : new ArrayList<Map.Entry<UUID, Session>>(SESSIONS.entrySet())) {
                Player p = Bukkit.getPlayer(e.getKey());
                Session ses = e.getValue();
                if (p == null || !p.isOnline()) { SESSIONS.remove(e.getKey()); continue; }
                if (ses.paused && !ses.forceTick) continue;
                long t = (long) ((now - ses.startedAt) * ses.speed);
                if (t > ses.times.get(ses.times.size() - 1)[0]) {
                    plugin.getLogger().info("[replay] " + ses.gameId + " finished: " + ses.blocksApplied
                            + " blocks, " + ses.blockErrors + " errors");
                    p.sendMessage(ChatColor.GRAY + "Replay finished (" + ses.blocksApplied + " blocks placed"
                            + (ses.blockErrors > 0 ? ", " + ses.blockErrors + " failed" : "") + ").");
                    stop(p);
                    continue;
                }
                if (ses.follow != null) {
                    for (Puppet pu : ses.puppets) {
                        if (!pu.name.equals(ses.follow)) continue;
                        double[] cur = null;
                        for (int back = ses.idx; back >= 0; back--) {
                            cur = ses.frames.get(back).get(pu.name);
                            if (cur != null) break;
                        }
                        if (cur != null) {
                            Location at = new Location(p.getWorld(), cur[0], cur[1] + ses.lift + 3.2, cur[2],
                                    (float) cur[3], 35f);
                            p.teleport(at);
                            // keep the camera in the air without throwing "cannot make player fly" every tick
                            if (!p.getAllowFlight()) p.setAllowFlight(true);
                            if (!p.isFlying()) p.setFlying(true);
                        }
                        break;
                    }
                }
                applyBlocks(ses, t, p);              // the islands build themselves as the replay runs
                applyEvents(ses, t, p);              // arrows, dropped items, crack stages, explosions
                if (ses.ticks++ % 5 == 0) {
                    long total = ses.times.get(ses.times.size() - 1)[0];
                    actionBar(p, ChatColor.GRAY + clock(Math.min(t, total)) + ChatColor.DARK_GRAY + " / "
                            + ChatColor.GRAY + clock(total) + ChatColor.DARK_GRAY + "  "
                            + ChatColor.YELLOW + ses.speed + "x"
                            + (ses.paused ? ChatColor.RED + "  paused" : "")
                            + (ses.follow != null ? ChatColor.AQUA + "  " + ses.follow : ""));
                }
                int idx = indexAt(ses, t);
                if (idx == ses.idx) continue;
                ses.idx = idx;
                Map<String, double[]> frame = ses.frames.get(idx);
                for (Puppet pu : ses.puppets) {
                    double[] v = frame.get(pu.name);
                    if (v == null) continue;
                    boolean dead = v[5] <= 0;
                    if (dead && pu.visible) { pu.npc.destroy(p); pu.visible = false; continue; }
                    if (!dead && !pu.visible) {
                        pu.npc.show(p);
                        pu.visible = true;
                        pu.worn = new int[]{-1, -1, -1, -1, -1};
                        pu.sneakState = -1;
                        pu.invisible = false;
                        pu.label = null;
                    }
                    if (dead) continue;
                    pu.npc.moveTo(p, v[0], v[1] + ses.lift, v[2], (float) v[3], (float) v[4]);
                    // equipment: held item and the four armour slots, sent only when they change.
                    // Minecraft slots are 0 held, 1 boots, 2 leggings, 3 chestplate, 4 helmet.
                    int[] eq = {(int) v[7], (int) v[11], (int) v[10], (int) v[9], (int) v[8]};
                    for (int slot = 0; slot < 5; slot++) {
                        int packed = eq[slot];
                        if (packed < 0) continue;                    // unchanged since the previous frame
                        if (packed == pu.worn[slot]) continue;
                        pu.worn[slot] = packed;
                        pu.npc.setEquipment(p, slot, packed);
                    }
                    // animations: crouching, arm swing, hurt flash, eating - the things that make a puppet look alive
                    int flags = (int) v[12];
                    boolean invis = (flags & 8) != 0;
                    if (invis != pu.invisible) { pu.invisible = invis; pu.npc.setInvisible(p, invis); }
                    int sneak = (int) v[6];
                    if (sneak != pu.sneakState) {
                        pu.sneakState = sneak;
                        pu.npc.setSneaking(p, sneak != 0);
                    }
                    if ((flags & 2) != 0) {                               // hurt
                        pu.npc.animate(p, 1);
                        p.playSound(new Location(p.getWorld(), v[0], v[1], v[2]), org.bukkit.Sound.HURT_FLESH, 1f, 1f);
                        p.getWorld().playEffect(new Location(p.getWorld(), v[0], v[1] + 1, v[2]),
                                org.bukkit.Effect.CRIT, 0);
                    }
                    if ((flags & 4) != 0) pu.npc.animate(p, 3);          // eating
                    if ((flags & 1) != 0) pu.npc.animate(p, 0);          // arm swing
                    int hpNow = (int) Math.max(0, Math.min(20, v[5]));
                    StringBuilder bar = new StringBuilder(ChatColor.DARK_GRAY + "[");
                    for (int h = 0; h < 10; h++) {
                        boolean full = h < Math.round(hpNow / 2f);
                        bar.append(full ? (hpNow > 10 ? ChatColor.GREEN : ChatColor.GOLD) : ChatColor.DARK_GRAY)
                                .append('|');
                    }
                    bar.append(ChatColor.DARK_GRAY + "]");
                    String label = pu.name + ChatColor.GRAY + " " + pu.team + " " + bar + ChatColor.RED + " " + hpNow;
                    if (!label.equals(pu.label)) { pu.label = label; pu.npc.setLabel(p, label); }
                }
                ses.forceTick = false;      // the one frame a seek asked for has been drawn
            if (ses.ticks % 5 == 0) replayBoard(p, ses);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ parsing

    /** Text between two markers (the recording format is ours, so this is enough). */
    private static String between(String line, String open, String close) {
        int i = line.indexOf(open);
        if (i < 0) return null;
        int j = i + open.length();
        int end = line.lastIndexOf(close);
        if (end <= j) return null;
        return line.substring(j, end);
    }

    /** Static entities that were in the game world (shops, generators, decorations) come back as real entities. */
    private static List<org.bukkit.entity.Entity> spawnNpcs(World w, List<Object[]> list) {
        List<org.bukkit.entity.Entity> out = new ArrayList<org.bukkit.entity.Entity>();
        for (Object[] e : list) {
            try {
                org.bukkit.entity.EntityType type = org.bukkit.entity.EntityType.valueOf((String) e[2]);
                Location at = new Location(w, (Double) e[3], (Double) e[4], (Double) e[5]);
                org.bukkit.entity.Entity spawned = w.spawnEntity(at, type);
                String label = e.length > 6 && e[6] != null ? (String) e[6] : "";
                if (spawned instanceof org.bukkit.entity.ArmorStand) ((org.bukkit.entity.ArmorStand) spawned).setVisible(false);
                if (spawned instanceof org.bukkit.entity.Creature) {
                    ((org.bukkit.entity.Creature) spawned).setTarget(null);   // a shop keeper stands still
                }
                if (spawned instanceof org.bukkit.entity.LivingEntity) {
                    org.bukkit.entity.LivingEntity le = (org.bukkit.entity.LivingEntity) spawned;
                    if (!label.isEmpty()) {
                        le.setCustomName(label);
                        le.setCustomNameVisible(true);
                    }
                }
                out.add(spawned);
            } catch (Throwable ignored) { }
        }
        return out;
    }

    /** Human name for a game type id, the way the lobby menu shows it. */
    private static String modeName(String id) {
        if (id == null) return "?";
        net.h2020.api.game.GameMode mode = net.h2020.api.game.GameModes.byId(id);
        if (mode != null) return net.h2020.api.game.GameModes.display(mode).name;
        switch (id) {
            case "bedwars_eight_one": return "Bed Wars Solo";
            case "bedwars_eight_two": return "Bed Wars Doubles";
            case "bedwars_four_three": return "Bed Wars 3v3v3v3";
            case "bedwars_four_four": return "Bed Wars 4v4v4v4";
            case "bedwars_two_two": return "Bed Wars Doubles (4 teams)";
            case "skywars_solo": return "SkyWars Solo";
            case "skywars_team": return "SkyWars Team";
            default: return id;
        }
    }

    private static String field(String line, String key) {
        String k = "\"" + key + "\":\"";
        int i = line.indexOf(k);
        if (i < 0) return null;
        int j = i + k.length();
        int end = line.indexOf('"', j);
        return end < 0 ? null : line.substring(j, end);
    }

    private static double num(String line, String key, double def) {
        String k = "\"" + key + "\":";
        int i = line.indexOf(k);
        if (i < 0) return def;
        int j = i + k.length();
        int end = j;
        while (end < line.length() && "-0123456789.".indexOf(line.charAt(end)) >= 0) end++;
        try { return Double.parseDouble(line.substring(j, end)); } catch (Throwable t) { return def; }
    }

    private static double parse(String v, double def) {
        try { return Double.parseDouble(v.trim()); } catch (Throwable t) { return def; }
    }
}
