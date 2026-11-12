package net.h2020.core.command;

import net.h2020.api.H2020;
import net.h2020.api.bot.BotService;
import net.h2020.api.core.Rank;
import net.h2020.api.core.SidebarTemplate;
import net.h2020.api.core.Sidebars;
import net.h2020.api.game.Game;
import net.h2020.api.game.GameState;
import net.h2020.api.game.GameType;
import net.h2020.api.map.MapService;
import net.h2020.api.map.MapTemplate;
import net.h2020.core.Core;
import net.h2020.core.CoreConfig;
import net.h2020.core.CoreUtil;
import net.h2020.core.HypixelFormat;
import net.h2020.maps.Worlds;
import net.h2020.core.lobby.npc.NpcManager;
import net.h2020.core.packet.PingSimulator;
import net.minecraft.server.v1_8_R3.PacketPlayInChat;
import net.minecraft.server.v1_8_R3.PacketPlayInKeepAlive;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/** /h2020 — admin and video tools. */
public final class AdminCommand implements TabExecutor {
    private static final Pattern DATE = Pattern.compile("\\d{2}/\\d{2}/\\d{2}");
    private static final String[] TEAMSHIFT_KEYS = {"spawn", "bed", "generator", "shop", "upgrades", "base"};
    private static final String TEAM_COLOURS = "red, blue, green, yellow, aqua, white, pink, gray";
    private static final List<String> SUBS = Arrays.asList("games", "create", "start", "fill", "bots", "watch", "end", "train", "replay", "reload", "date", "edit", "slimeimport", "maptag", "teamshift", "tags", "mapimportdir",            "rank", "level", "build", "setspawn", "npc", "cheaters", "fillspeed", "background", "npcprobe", "ping", "pingselftest", "settingsprobe",
            "sidebar");
    private final Core core;

    public AdminCommand(Core core) { this.core = core; }

    private static String g(String s) { return ChatColor.GREEN + s; }
    private static String prettifyName(String raw) {
        String n = raw.replace('_', ' ').trim();
        if (n.isEmpty()) return raw;
        StringBuilder sb = new StringBuilder();
        for (String w : n.split("\\s+")) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.length() > 1 ? w.substring(1) : "");
        }
        return sb.toString();
    }

    private static String r(String s) { return ChatColor.RED + s; }

    /** Preview output uses '&' so it survives a console log. */
    private static String amp(String s) {
        return s == null ? "" : s.replace(ChatColor.COLOR_CHAR, '&');
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (a.length == 0 || a[0].equalsIgnoreCase("help")) { help(s); return true; }
        String sub = a[0].toLowerCase();
        switch (sub) {
            case "games": {
                if (s instanceof Player) gamesMenu((Player) s);
                else games(s);
                return true;
            }
            case "create": {
                if (a.length < 2) { s.sendMessage(r("/h2020 create <type> [map]")); return true; }
                net.h2020.api.game.GameMode t = net.h2020.api.game.GameModes.byId(a[1]);
                if (t == null) { s.sendMessage(r("Unknown type " + a[1])); return true; }
                Game game;
                if (a.length >= 3) {
                    MapService ms = H2020.find(MapService.class);
                    MapTemplate m = ms == null ? null : ms.byName(a[2]);
                    if (m == null) { s.sendMessage(r("Unknown map " + a[2])); return true; }
                    // a tag switched off in config.yml keeps the map out of the rotation, and out of /create too
                    if (m instanceof net.h2020.maps.StoredMapTemplate && ms instanceof net.h2020.maps.MapServiceImpl
                            && net.h2020.maps.MapServiceImpl.taggedOff((net.h2020.maps.StoredMapTemplate) m,
                                    ((net.h2020.maps.MapServiceImpl) ms).disabledTags())) {
                        s.sendMessage(r(a[2] + " is switched off by its tags ("
                                + String.join(", ", m.tags()) + ") - see maps/disabled-tags"));
                        return true;
                    }
                    // the map has to be built for this mode: putting 4v4v4v4 on an 8-team solos map spreads sixteen
                    // players over eight islands and everything downstream (defence, rushes, bed distances) is wrong
                    if (m.supportedTeamCounts() != null && !m.supportedTeamCounts().contains(t.teams())) {
                        StringBuilder ok = new StringBuilder();
                        if (ms != null) for (MapTemplate x : ms.templatesFor(t)) {
                            if (ok.length() > 0) ok.append(", ");
                            ok.append(x.name());
                        }
                        s.sendMessage(r(a[2] + " is not a " + t.id() + " map (teams " + m.supportedTeamCounts()
                                + ", needs " + t.teams() + "). Valid: " + (ok.length() == 0 ? "none" : ok)));
                        return true;
                    }
                    game = core.games.create(t, m);
                } else game = core.games.createRandom(t);
                s.sendMessage(game == null ? r("Could not create a " + t.id() + " game (provider/maps missing?)")
                        : g("Created " + game.id() + " (" + t.id() + " on " + game.mapName() + ")"));
                return true;
            }
            case "start": {
                if (a.length < 2) { s.sendMessage(r("/h2020 start <gameId>")); return true; }
                Game game = core.games.byId(a[1]);
                if (game == null) { s.sendMessage(r("No game " + a[1])); return true; }
                if (game.state() != GameState.WAITING && game.state() != GameState.STARTING) { s.sendMessage(r(game.id() + " already started.")); return true; }
                if (H2020.find(BotService.class) == null) { s.sendMessage(r("Bots module not loaded.")); return true; }
                int n = core.filler.fillNow(game, Integer.MAX_VALUE);
                s.sendMessage(g("Filling " + game.id() + " with " + n + " bots; it starts when full."));
                return true;
            }
            case "addmode": {
                // /h2020 addmode <id> <family> <teams> <teamSize> [, icon] -- a mode that exists only on this server
                if (a.length < 5) { s.sendMessage(r("/h2020 addmode <id> <family> <teams> <teamSize>")); return true; }
                net.h2020.api.game.GameFamily fam;
                try { fam = net.h2020.api.game.GameFamily.valueOf(a[2].toUpperCase()); }
                catch (IllegalArgumentException ex) { s.sendMessage(r("Unknown family " + a[2])); return true; }
                final String id = a[1].toLowerCase();
                final int teams = parseInt(a[3], 1), size = parseInt(a[4], 1);
                net.h2020.api.game.GameModes.register(new net.h2020.api.game.GameMode() {
                    public String id() { return id; }
                    public net.h2020.api.game.GameFamily family() { return fam; }
                    public String displayName() { return id; }
                    public int teamSize() { return size; }
                    public int teams() { return teams; }
                    public org.bukkit.Material icon() { return fam == net.h2020.api.game.GameFamily.BEDWARS
                            ? org.bukkit.Material.BED : org.bukkit.Material.EYE_OF_ENDER; }
                    public String menuSubtitle() { return teams + "x" + size; }
                    public String description() { return "Registered on this server."; }
                });
                s.sendMessage(g("Registered mode " + id + " (" + teams + "x" + size + ") in " + fam));
                return true;
            }
            case "private": {
                // /h2020 private <mode> [map] [code] [max] -- a game nobody queues into; join with /h2020 joincode
                if (a.length < 2) { s.sendMessage(r("/h2020 private <mode> [map] [code] [max]")); return true; }
                net.h2020.api.game.GameMode mode = net.h2020.api.game.GameModes.byId(a[1]);
                if (mode == null) { s.sendMessage(r("Unknown mode " + a[1])); return true; }
                net.h2020.api.map.MapService ms = H2020.find(net.h2020.api.map.MapService.class);
                net.h2020.api.map.MapTemplate map = a.length >= 3 ? ms.byName(a[2]) : null;
                String code = a.length >= 4 ? a[3] : null;
                int max = a.length >= 5 ? parseInt(a[4], 0) : 0;
                Game game = core.games.createPrivate(mode, map, code, max);
                if (game == null) { s.sendMessage(r("Could not create a private game (no map fits).")); return true; }
                s.sendMessage(g("Private game " + game.id() + " on " + game.mapName() + ", code " + game.privateCode()
                        + (max > 0 ? ", cap " + max : "") + ". Join with /h2020 joincode " + game.privateCode()));
                return true;
            }
            case "joincode": {
                if (a.length < 2) { s.sendMessage(r("/h2020 joincode <code>")); return true; }
                Game game = core.games.byCode(a[1]);
                if (game == null) { s.sendMessage(r("No open private game with code " + a[1])); return true; }
                Player who = s instanceof Player ? (Player) s : null;
                if (who == null) { s.sendMessage(r("A player must run this.")); return true; }
                game.addPlayer(who);
                s.sendMessage(g("Joined " + game.id() + " (" + game.mapName() + ")."));
                return true;
            }
            case "islandcolors": {
                if (a.length < 2) { s.sendMessage(r("/h2020 islandcolors <map>")); return true; }
                org.bukkit.World w = net.h2020.api.map.SlimeWorlds.spawn(a[1], "probe");
                if (w == null) { s.sendMessage(r("No slime copy of " + a[1] + " - run /h2020 convert " + a[1])); return true; }
                try {
                    Object snap = net.h2020.maps.MapImporter.scan(w);
                    for (String line : net.h2020.maps.MapImporter.diagnose(snap).split("\n"))
                        if (!line.trim().isEmpty()) s.sendMessage(g(line));
                } finally {
                    net.h2020.api.map.SlimeWorlds.release(w);
                }
                return true;
            }
            case "npcprobe": {
                // /h2020 npcprobe <map> -- check shop (left) and upgrades (right) from map.yml only, no world needed
                if (a.length < 2) { s.sendMessage(r("/h2020 npcprobe <map>")); return true; }
                MapService ms = H2020.find(MapService.class);
                MapTemplate m = ms == null ? null : ms.byName(a[1]);
                if (m == null) { s.sendMessage(r("Unknown map " + a[1])); return true; }
                int ok = 0, teams = 0;
                List<String> wrong = new ArrayList<>();
                for (net.h2020.api.game.TeamColor c : net.h2020.api.game.TeamColor.values()) {
                    String color = c.name().toLowerCase(java.util.Locale.ROOT);
                    org.bukkit.util.Vector spawn = m.point(net.h2020.api.map.MapKeys.teamSpawn(c));
                    org.bukkit.util.Vector gen = m.point(net.h2020.api.map.MapKeys.teamGenerator(c));
                    org.bukkit.util.Vector shop = m.point(net.h2020.api.map.MapKeys.teamShop(c));
                    org.bukkit.util.Vector upgrades = m.point(net.h2020.api.map.MapKeys.teamUpgrades(c));
                    if (spawn == null || gen == null || shop == null || upgrades == null) continue;
                    double fx = gen.getX() - spawn.getX(), fz = gen.getZ() - spawn.getZ();
                    double len = Math.hypot(fx, fz);
                    if (len < 1e-6) len = 1; // degenerate outward: both offsets are 0 and the team reports WRONG
                    fx /= len; fz /= len;
                    double lx = fz, lz = -fx;
                    double shopLat = (shop.getX() - spawn.getX()) * lx + (shop.getZ() - spawn.getZ()) * lz;
                    double upLat = (upgrades.getX() - spawn.getX()) * lx + (upgrades.getZ() - spawn.getZ()) * lz;
                    boolean good = shopLat > 0 && upLat < 0;
                    teams++;
                    if (good) ok++; else wrong.add(color);
                    s.sendMessage(g(String.format(java.util.Locale.ROOT, "%s shop %.2f upgrades %.2f %s",
                            color, shopLat, upLat, good ? "ok" : "WRONG")));
                }
                s.sendMessage(g("npcprobe " + a[1] + ": " + ok + "/" + teams + " teams ok"
                        + (wrong.isEmpty() ? "" : " - failing: " + String.join(", ", wrong))));
                return true;
            }
            case "ping": {
                // /h2020 ping [status|enable|disable|off] | /h2020 ping <player> [ms] [jitter]
                if (a.length < 2 || a[1].equalsIgnoreCase("status")) {
                    s.sendMessage(core.ping.enabled()
                            ? g("simulated ping is enabled (config core.simulated-ping)")
                            : r("simulated ping is disabled (config core.simulated-ping)"));
                    return true;
                }
                if (a[1].equalsIgnoreCase("enable") || a[1].equalsIgnoreCase("disable")) {
                    boolean on = a[1].equalsIgnoreCase("enable");
                    core.ping.setEnabled(on);
                    if (on) s.sendMessage(g("simulated ping is enabled (config core.simulated-ping)"));
                    else s.sendMessage(g("simulated ping is disabled (config core.simulated-ping); handlers removed from all online players"));
                    return true;
                }
                if (a[1].equalsIgnoreCase("off")) {
                    if (!(s instanceof Player)) { s.sendMessage(r("A player must run /h2020 ping off.")); return true; }
                    Player p = (Player) s;
                    core.ping.clear(p.getUniqueId());
                    try {
                        net.h2020.core.profile.ProfileData data = core.profiles.data(p);
                        data.simulatedPing = 0;
                        data.simulatedJitter = 0;
                    } catch (Throwable ignored) {}
                    core.ping.remove(p);
                    s.sendMessage(g("Simulated ping cleared for " + p.getName() + " (handler removed)."));
                    return true;
                }
                if (!core.ping.enabled()) {
                    s.sendMessage(r("simulated ping is disabled - run /h2020 ping enable first"));
                    return true;
                }
                Player t = Bukkit.getPlayerExact(a[1]);
                if (t == null) { s.sendMessage(r("Unknown player " + a[1])); return true; }
                if (a.length >= 3) {
                    int ms = parseInt(a[2], -1);
                    if (ms < 0) { s.sendMessage(r("/h2020 ping <player> [ms] [jitter] (ms 0-1000, jitter 0-500)")); return true; }
                    int jitter = core.ping.jitter(t.getUniqueId());
                    if (a.length >= 4) {
                        jitter = parseInt(a[3], -1);
                        if (jitter < 0) { s.sendMessage(r("/h2020 ping <player> [ms] [jitter] (ms 0-1000, jitter 0-500)")); return true; }
                    }
                    core.ping.set(t, ms, jitter);
                }
                s.sendMessage(g("ping " + t.getName() + ": " + core.ping.delay(t.getUniqueId()) + " ms +/- "
                        + core.ping.jitter(t.getUniqueId()) + " ms, handler "
                        + (core.ping.installed(t) ? "installed" : "not installed")));
                return true;
            }
            case "pingselftest": {
                // /h2020 pingselftest -- headless proof of the half-delay maths and the per-packet variability
                try {
                    final long[] seen = new long[1];
                    EmbeddedChannel ch = new EmbeddedChannel();
                    ch.pipeline().addLast("packet_handler", new ChannelInboundHandlerAdapter() {
                        @Override public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                            seen[0] = System.currentTimeMillis();
                            ctx.fireChannelRead(msg);
                        }
                    });
                    ch.pipeline().addBefore("packet_handler", PingSimulator.HANDLER, new PingSimulator.Handler(200));

                    long keepalive = measurePing(ch, new PacketPlayInKeepAlive(), seen);
                    long fixed = measurePing(ch, new PacketPlayInChat("pingselftest"), seen);

                    final long[] jitterSeen = new long[1];
                    EmbeddedChannel jitterCh = new EmbeddedChannel();
                    jitterCh.pipeline().addLast("packet_handler", new ChannelInboundHandlerAdapter() {
                        @Override public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                            jitterSeen[0] = System.currentTimeMillis();
                            ctx.fireChannelRead(msg);
                        }
                    });
                    jitterCh.pipeline().addBefore("packet_handler", PingSimulator.HANDLER,
                            new PingSimulator.Handler(200, 100));

                    long min = Long.MAX_VALUE, max = Long.MIN_VALUE;
                    boolean measured = true;
                    for (int i = 0; i < 20; i++) {
                        long d = measurePing(jitterCh, new PacketPlayInChat("pingselftest" + i), jitterSeen);
                        if (d < 0) { measured = false; break; }
                        min = Math.min(min, d);
                        max = Math.max(max, d);
                    }

                    boolean ok = keepalive >= 0 && keepalive < 20 && fixed >= 80 && fixed <= 160
                            && measured && min >= 40 && max - min >= 20;
                    String jitterText = measured ? min + "-" + max + "ms" : "?";
                    String line = "pingselftest: keepalive " + keepalive + "ms, fixed " + fixed + "ms, jitter "
                            + jitterText + ", " + (ok ? "ok"
                            : "FAILED (want keepalive <20ms, fixed 80-160ms, jitter min >=40ms and spread >=20ms)");
                    s.sendMessage(ok ? g(line) : r(line));
                } catch (Throwable t) {
                    s.sendMessage(r("pingselftest failed: " + t));
                }
                return true;
            }
            case "settingsprobe": {
                // /h2020 settingsprobe <player> -- open the settings menu and report what Bukkit actually built
                if (a.length < 2) { s.sendMessage(r("/h2020 settingsprobe <player>")); return true; }
                Player target = Bukkit.getPlayerExact(a[1]);
                if (target == null) { s.sendMessage(r("No player " + a[1])); return true; }
                try {
                    new net.h2020.core.lobby.SettingsMenu(core).open(target);
                    org.bukkit.inventory.Inventory top = target.getOpenInventory().getTopInventory();
                    int items = 0;
                    for (int i = 0; i < top.getSize(); i++) if (top.getItem(i) != null) items++;
                    org.bukkit.inventory.ItemStack icon = top.getItem(4);
                    String slot4 = icon == null || icon.getItemMeta() == null ? "null"
                            : String.valueOf(icon.getItemMeta().getDisplayName());
                    s.sendMessage(g("settingsprobe " + target.getName() + ": title \"" + top.getTitle()
                            + "\", size " + top.getSize() + ", " + items + " items, slot4 \"" + slot4 + "\""));
                } catch (Throwable t) {
                    s.sendMessage(r("settingsprobe failed: " + t));
                } finally {
                    target.closeInventory();
                }
                return true;
            }
            case "edit": {
                // /lattice edit <map> [leave|selftest] - in-game map editor (ArenaSetup style)
                net.h2020.api.map.MapService ms = H2020.find(net.h2020.api.map.MapService.class);
                if (!(ms instanceof net.h2020.maps.MapServiceImpl)) { s.sendMessage(r("No map service")); return true; }
                net.h2020.maps.MapEditService ed = ((net.h2020.maps.MapServiceImpl) ms).editor();
                if (ed == null) { s.sendMessage(r("The editor is not running")); return true; }
                if (a.length < 2) {
                    s.sendMessage(r("/h2020 edit <map> [leave|selftest]"));
                    StringBuilder names = new StringBuilder();
                    for (net.h2020.api.map.MapTemplate t : ms.templates(null)) {
                        if (names.length() > 0) names.append(", ");
                        names.append(t.name());
                    }
                    s.sendMessage(g("Maps: " + names));
                    return true;
                }
                if (a.length >= 4 && a[2].equalsIgnoreCase("opentest")) {
                    // headless proof that the open/close path works: send a bot in, then take it out again
                    Player target = Bukkit.getPlayer(a[3]);
                    if (target == null) { s.sendMessage(r("No player " + a[3])); return true; }
                    String err = ed.open(target, a[1]);
                    if (err != null) { s.sendMessage(r(err)); return true; }
                    String world = target.getWorld() == null ? "?" : target.getWorld().getName();
                    String mode = String.valueOf(target.getGameMode());
                    int kit = 0;
                    for (org.bukkit.inventory.ItemStack it : target.getInventory().getContents()) if (it != null) kit++;
                    boolean flying = target.isFlying();
                    boolean left = ed.leave(target);
                    s.sendMessage(g("opentest " + a[1] + ": world " + world + ", " + target.getName() + " was in "
                            + mode + " with " + kit + " setup item(s), flying=" + flying + ", left=" + left));
                    return true;
                }
                if (a.length >= 4 && a[2].equalsIgnoreCase("holotest")) {
                    // headless proof: enter the editor, count the ArmorStands it put in the world, leave again
                    Player who = Bukkit.getPlayerExact(a[3]);
                    if (who == null) { s.sendMessage(r("No player " + a[3])); return true; }
                    String err = ed.open(who, a[1]);
                    if (err != null) { s.sendMessage(r(err)); return true; }
                    org.bukkit.World w = who.getWorld();
                    int stands = 0;
                    StringBuilder names = new StringBuilder();
                    if (w != null) for (org.bukkit.entity.Entity en : w.getEntities()) {
                        if (!(en instanceof org.bukkit.entity.ArmorStand)) continue;
                        org.bukkit.entity.ArmorStand as = (org.bukkit.entity.ArmorStand) en;
                        if (!as.isVisible() && as.getCustomName() != null && as.isCustomNameVisible()) {
                            stands++;
                            if (names.length() < 160) names.append(as.getCustomName().replace('\u00a7', '&')).append(" | ");
                        }
                    }
                    ed.leave(who);
                    s.sendMessage(g("holotest " + a[1] + ": " + stands + " hologram(s): " + names));
                    return true;
                }
                if (a.length >= 6 && a[2].equalsIgnoreCase("picktest")) {
                    // what the wand would write for a click on that block, without touching the map
                    org.bukkit.World w = Bukkit.getWorlds().get(0);
                    org.bukkit.block.Block b = w.getBlockAt((int) parseDouble(a[3], 0), (int) parseDouble(a[4], 64),
                            (int) parseDouble(a[5], 0));
                    org.bukkit.Location loc = net.h2020.maps.MapEditService.picker(b);
                    s.sendMessage(g("picktest: block " + b.getX() + "," + b.getY() + "," + b.getZ() + " -> point "
                            + loc.getX() + "," + loc.getY() + "," + loc.getZ()));
                    return true;
                }
                if (a.length >= 3 && a[2].equalsIgnoreCase("selftest")) {
                    s.sendMessage(g(ed.selftest(a[1])));
                    return true;
                }
                if (!(s instanceof Player)) { s.sendMessage(r("Only a player can use the map editor - run it in game.")); return true; }
                Player who = (Player) s;
                if (a.length >= 3 && a[2].equalsIgnoreCase("leave")) {
                    who.performCommand("lattice edit leave");
                    return true;
                }
                String err = ed.open(who, a[1]);
                if (err != null) s.sendMessage(r(err));
                return true;
            }
            case "setpoi": {
                // /h2020 setpoi <map> <key> [head] -- write one point into that map's map.yml from where you stand
                if (a.length < 3) { s.sendMessage(r("/h2020 setpoi <map> <key> [head]")); return true; }
                Player who = s instanceof Player ? (Player) s : null;
                org.bukkit.Location at = who != null ? who.getLocation() : null;
                if (at == null) {
                    if (a.length < 6) { s.sendMessage(r("From console: /h2020 setpoi <map> <key> <x> <y> <z>")); return true; }
                    org.bukkit.World w = Bukkit.getWorlds().get(0);
                    at = new org.bukkit.Location(w, parseDouble(a[3], 0), parseDouble(a[4], 64), parseDouble(a[5], 0));
                }
                net.h2020.api.map.MapService ms = H2020.find(net.h2020.api.map.MapService.class);
                java.io.File dir = ms == null ? null : ms.folderOf(a[1]);
                if (dir == null) { s.sendMessage(r("Unknown map " + a[1])); return true; }
                boolean head = a.length >= 4 && a[3].equalsIgnoreCase("head");
                boolean ok = net.h2020.maps.MapEditor.setPoint(dir, a[2], at, head);
                if (ok) ms.reload();
                s.sendMessage(ok ? g("Set " + a[2] + " on " + a[1] + " at " + at.getBlockX() + ","
                        + at.getBlockY() + "," + at.getBlockZ() + " (maps reloaded)")
                        : r("Could not write map.yml for " + a[1] + " at " + dir.getAbsolutePath()
                        + " (dir=" + dir.isDirectory() + ", map.yml=" + new java.io.File(dir, "map.yml").isFile() + ")"));
                return true;
            }
            case "teamshift": {
                // /lattice teamshift <map> swap|rotate|offset ... - move existing team point sets between colours
                if (a.length < 3) {
                    s.sendMessage(r("/lattice teamshift <map> swap <colourA> <colourB> | rotate <1-7> | offset <colour> <dx> <dy> <dz>"));
                    return true;
                }
                net.h2020.api.map.MapService ms = H2020.find(net.h2020.api.map.MapService.class);
                if (!(ms instanceof net.h2020.maps.MapServiceImpl)) { s.sendMessage(r("No map service")); return true; }
                net.h2020.maps.MapEditService ed = ((net.h2020.maps.MapServiceImpl) ms).editor();
                if (ed != null && ed.inSession(a[1])) {
                    s.sendMessage(r("The editor for " + a[1] + " is open - close it before shifting team points."));
                    return true;
                }
                net.h2020.api.map.MapTemplate t = ms.byName(a[1]);
                if (t == null) { s.sendMessage(r("No map " + a[1])); return true; }
                java.io.File dir = ms.folderOf(a[1]);
                if (dir == null || !dir.isDirectory()) { s.sendMessage(r("No map folder for " + a[1])); return true; }
                String mode = a[2].toLowerCase(java.util.Locale.ROOT);

                if (mode.equals("swap")) {
                    if (a.length < 5) {
                        s.sendMessage(r("/lattice teamshift <map> swap <colourA> <colourB>"));
                        return true;
                    }
                    net.h2020.api.game.TeamColor ca = teamColor(a[3]);
                    net.h2020.api.game.TeamColor cb = teamColor(a[4]);
                    if (ca == null || cb == null) {
                        s.sendMessage(r("Unknown colour. Colours: " + TEAM_COLOURS));
                        return true;
                    }
                    if (ca == cb) { s.sendMessage(r("Pick two different colours.")); return true; }
                    List<net.h2020.api.game.TeamColor> present = existingTeams(t);
                    if (!present.contains(ca) || !present.contains(cb)) {
                        s.sendMessage(r("Both colours must exist on " + t.name() + " - "
                                + ca.name().toLowerCase(java.util.Locale.ROOT) + " and "
                                + cb.name().toLowerCase(java.util.Locale.ROOT) + " are not both set."));
                        return true;
                    }
                    java.util.Map<String, List<org.bukkit.Location>> aa =
                            new java.util.LinkedHashMap<String, List<org.bukkit.Location>>();
                    java.util.Map<String, List<org.bukkit.Location>> bb =
                            new java.util.LinkedHashMap<String, List<org.bukkit.Location>>();
                    for (String k : TEAMSHIFT_KEYS) {
                        aa.put(k, teamPoints(t, ca, k));
                        bb.put(k, teamPoints(t, cb, k));
                    }
                    int moved = 0;
                    boolean ok = true;
                    for (String k : TEAMSHIFT_KEYS) {
                        if (!writeTeamPoints(dir, ca, k, bb.get(k))
                                || !writeTeamPoints(dir, cb, k, aa.get(k))) { ok = false; break; }
                        if (!aa.get(k).isEmpty()) moved++;
                        if (!bb.get(k).isEmpty()) moved++;
                    }
                    if (!ok) {
                        s.sendMessage(r("Could not write the swapped team points into " + a[1] + "'s map.yml."));
                        return true;
                    }
                    ms.reload();
                    s.sendMessage(g("teamshift " + t.name() + ": swapped "
                            + ca.name().toLowerCase(java.util.Locale.ROOT) + " and "
                            + cb.name().toLowerCase(java.util.Locale.ROOT) + ", " + moved + " key(s) moved."));
                    return true;
                }

                if (mode.equals("rotate")) {
                    if (a.length < 4) {
                        s.sendMessage(r("/lattice teamshift <map> rotate <1-7>"));
                        return true;
                    }
                    int n = parseInt(a[3], -1);
                    if (n < 1 || n > 7) {
                        s.sendMessage(r("rotate needs a number from 1 to 7."));
                        return true;
                    }
                    List<net.h2020.api.game.TeamColor> teams = existingTeams(t);
                    if (teams.size() < 2) {
                        s.sendMessage(r("No team points to rotate on " + t.name() + "."));
                        return true;
                    }
                    java.util.Map<String, List<org.bukkit.Location>> old =
                            new java.util.LinkedHashMap<String, List<org.bukkit.Location>>();
                    for (net.h2020.api.game.TeamColor c : teams)
                        for (String k : TEAMSHIFT_KEYS) old.put(teamKey(c, k), teamPoints(t, c, k));
                    int moved = 0;
                    boolean ok = true;
                    for (int i = 0; i < teams.size() && ok; i++) {
                        net.h2020.api.game.TeamColor target = teams.get(i);
                        net.h2020.api.game.TeamColor source = teams.get((i + n) % teams.size());
                        for (String k : TEAMSHIFT_KEYS) {
                            List<org.bukkit.Location> pts = old.get(teamKey(source, k));
                            if (!writeTeamPoints(dir, target, k, pts)) { ok = false; break; }
                            if (!pts.isEmpty()) moved++;
                        }
                    }
                    if (!ok) {
                        s.sendMessage(r("Could not write the rotated team points into " + a[1] + "'s map.yml."));
                        return true;
                    }
                    ms.reload();
                    s.sendMessage(g("teamshift " + t.name() + ": rotated " + n + " position(s) through "
                            + teams.size() + " team(s), " + moved + " key(s) moved."));
                    return true;
                }

                if (mode.equals("offset")) {
                    if (a.length < 7) {
                        s.sendMessage(r("/lattice teamshift <map> offset <colour> <dx> <dy> <dz>"));
                        return true;
                    }
                    net.h2020.api.game.TeamColor c = teamColor(a[3]);
                    if (c == null) { s.sendMessage(r("Unknown colour. Colours: " + TEAM_COLOURS)); return true; }
                    double dx = parseDouble(a[4], Double.NaN);
                    double dy = parseDouble(a[5], Double.NaN);
                    double dz = parseDouble(a[6], Double.NaN);
                    if (Double.isNaN(dx) || Double.isNaN(dy) || Double.isNaN(dz)
                            || Double.isInfinite(dx) || Double.isInfinite(dy) || Double.isInfinite(dz)) {
                        s.sendMessage(r("Offsets must be numbers (dy may be negative)."));
                        return true;
                    }
                    int moved = 0;
                    boolean ok = true;
                    for (String k : TEAMSHIFT_KEYS) {
                        List<org.bukkit.Location> pts = teamPoints(t, c, k);
                        if (pts.isEmpty()) continue;
                        List<org.bukkit.Location> shifted = new ArrayList<org.bukkit.Location>();
                        for (org.bukkit.Location l : pts)
                            shifted.add(new org.bukkit.Location(l.getWorld(), l.getX() + dx, l.getY() + dy,
                                    l.getZ() + dz, l.getYaw(), l.getPitch()));
                        if (!writeTeamPoints(dir, c, k, shifted)) { ok = false; break; }
                        moved++;
                    }
                    if (!ok) {
                        s.sendMessage(r("Could not write the offset team points into " + a[1] + "'s map.yml."));
                        return true;
                    }
                    if (moved == 0) {
                        s.sendMessage(r("No team." + c.name().toLowerCase(java.util.Locale.ROOT)
                                + ".* points on " + t.name() + "."));
                        return true;
                    }
                    ms.reload();
                    s.sendMessage(g("teamshift " + t.name() + ": offset "
                            + c.name().toLowerCase(java.util.Locale.ROOT) + " by " + dx + ", " + dy + ", " + dz
                            + ", " + moved + " key(s) moved."));
                    return true;
                }

                s.sendMessage(r("/lattice teamshift <map> swap <colourA> <colourB> | rotate <1-7> | offset <colour> <dx> <dy> <dz>"));
                return true;
            }
            case "testdeposit": {
                if (a.length < 2) { s.sendMessage(r("/h2020 testdeposit <player> [team|ender]")); return true; }
                Player tp = Bukkit.getPlayerExact(a[1]);
                if (tp == null) { s.sendMessage(r("No player " + a[1])); return true; }
                net.h2020.api.game.BedwarsTools bt = H2020.find(net.h2020.api.game.BedwarsTools.class);
                if (bt == null) { s.sendMessage(r("The Bed Wars plugin is not installed.")); return true; }
                bt.testDeposit(s, tp, a.length >= 3 && a[2].equalsIgnoreCase("ender"));
                return true;
            }
            case "fastboard": {
                if (a.length < 2) { s.sendMessage(r("/h2020 fastboard <player>")); return true; }
                Player fp = Bukkit.getPlayerExact(a[1]);
                if (fp == null) { s.sendMessage(r("No player " + a[1])); return true; }
                net.h2020.core.scoreboard.FastBoardProbe.run(core.plugin, fp);
                s.sendMessage(g("FastBoard probe started for " + fp.getName()));
                return true;
            }
            case "playagainprobe": {
                // send the end-of-game prompt to one player and report whether the component went out
                if (a.length < 2) { s.sendMessage(r("/h2020 playagainprobe <player>")); return true; }
                Player pp = Bukkit.getPlayerExact(a[1]);
                if (pp == null) { s.sendMessage(r("No player " + a[1])); return true; }
                net.h2020.api.core.PlayAgain.sendPrompt(pp, a.length < 3 || !a[2].equalsIgnoreCase("loss"), a.length < 4 || !a[3].equalsIgnoreCase("skywars"));
                net.h2020.api.core.PlayAgain.giveItem(pp);
                s.sendMessage(g("Sent the play-again prompt to " + pp.getName()));
                s.sendMessage(net.h2020.api.core.PlayAgain.legacyPrompt(a.length < 3 || !a[2].equalsIgnoreCase("loss"),
                        a.length < 4 || !a[3].equalsIgnoreCase("skywars")).replace('\u00a7', '&'));
                return true;
            }
            case "slimeprobe": {
                if (a.length < 2) { s.sendMessage(r("/h2020 slimeprobe <map>")); return true; }
                org.bukkit.World w = net.h2020.api.map.SlimeWorlds.spawn(a[1], "probe");
                if (w == null) { s.sendMessage(r("No slime world ready for " + a[1])); return true; }
                // count solid blocks in a slab at the map's own build height, which proves the copy carries the map
                int solid = 0, air = 0;
                net.h2020.api.map.MapTemplate mt = H2020.find(net.h2020.api.map.MapService.class) == null ? null
                        : H2020.find(net.h2020.api.map.MapService.class).byName(a[1]);
                org.bukkit.util.Vector mid = mt == null ? null : mt.point("mid");
                org.bukkit.Location sp = mid == null ? w.getSpawnLocation()
                        : new org.bukkit.Location(w, mid.getX(), mid.getY(), mid.getZ());
                for (int x = -24; x <= 24; x += 3)
                    for (int z = -24; z <= 24; z += 3)
                        for (int y = -8; y <= 8; y += 4) {
                            org.bukkit.block.Block b = w.getBlockAt(sp.getBlockX() + x, Math.max(1, sp.getBlockY() + y), sp.getBlockZ() + z);
                            if (b.getType() == org.bukkit.Material.AIR) air++; else solid++;
                        }
                s.sendMessage(g("world=" + w.getName() + " at=" + sp.getBlockX() + "," + sp.getBlockY() + "," + sp.getBlockZ()
                        + " solid=" + solid + " air=" + air + " chunks=" + w.getLoadedChunks().length
                        + " autoSave=" + w.isAutoSave()));
                net.h2020.api.map.SlimeWorlds.release(w);
                return true;
            }
            case "blockat": {
                // /h2020 blockat <world> <x> <y> <z> ...: read a few blocks back out, for checking a generator
                if (a.length < 5) { s.sendMessage(r("/h2020 blockat <world> <x> <y> <z> [y2 y3 ...]")); return true; }
                org.bukkit.World w = Bukkit.getWorld(a[1]);
                if (w == null) { s.sendMessage(r("No world " + a[1])); return true; }
                int x = Integer.parseInt(a[2]), z = Integer.parseInt(a[4]);
                StringBuilder out = new StringBuilder("world=" + w.getName());
                for (int i = 3; i < a.length; i++) {
                    int y = Integer.parseInt(a[i]);
                    out.append(" y").append(y).append('=').append(w.getBlockAt(x, y, z).getType());
                }
                s.sendMessage(g(out.toString()));
                return true;
            }
            case "mapimport": {
                // /lattice mapimport <world|map> <name> [bedwars|skywars]: derive map.yml from a loaded world. The
                // world is read on the main thread (a fraction of a second), the derivation runs off it because the
                // heuristics take a while and freezing the server for a minute is not acceptable.
                if (a.length < 3) { s.sendMessage(r("/h2020 mapimport <world> <name> [bedwars|skywars]")); return true; }
                final org.bukkit.World w = Bukkit.getWorld(a[1]);
                final boolean temporary = w == null;
                final org.bukkit.World world = w != null ? w : net.h2020.api.map.SlimeWorlds.spawn(a[1], "import");
                if (world == null) { s.sendMessage(r("No world " + a[1] + " is loaded, and there is no slime copy of it.")); return true; }
                net.h2020.api.game.GameFamily fam = net.h2020.api.game.GameFamily.BEDWARS;
                if (a.length > 3) {
                    try { fam = net.h2020.api.game.GameFamily.valueOf(a[3].toUpperCase()); }
                    catch (IllegalArgumentException e) { s.sendMessage(r("Unknown family " + a[3])); return true; }
                }
                final net.h2020.api.game.GameFamily family = fam;
                final String mapName = a[2];
                final CommandSender sender = s;
                final java.io.File mapsRoot = new java.io.File(core.plugin.getDataFolder(), "maps");
                try {
                    Object snapshot = net.h2020.maps.MapImporter.scan(world);
                    Bukkit.getScheduler().runTaskAsynchronously(core.plugin, new Runnable() {
                        @Override public void run() {
                            try {
                                final net.h2020.maps.MapImporter.Result res =
                                        net.h2020.maps.MapImporter.derive(snapshot, mapName, family, mapsRoot);
                                Bukkit.getScheduler().runTask(core.plugin, new Runnable() {
                                    @Override public void run() {
                                        net.h2020.maps.MapImporter.buildLobby(world, res, world);
                                        net.h2020.api.map.MapService ms = H2020.find(net.h2020.api.map.MapService.class);
                                        if (ms != null) ms.reload();
                                        sender.sendMessage(g("Imported " + res.name + " (" + family + ") into "
                                                + res.folder.getName() + " - " + String.join("; ", res.lines)));
                                        if (temporary) net.h2020.api.map.SlimeWorlds.release(world);
                                    }
                                });
                            } catch (Throwable t) {
                                core.plugin.getLogger().log(java.util.logging.Level.WARNING, "[mapimport] failed", t);
                                Bukkit.getScheduler().runTask(core.plugin, new Runnable() {
                                    @Override public void run() {
                                        sender.sendMessage(r("Import failed: " + t.getMessage()));
                                        if (temporary) net.h2020.api.map.SlimeWorlds.release(world);
                                    }
                                });
                            }
                        }
                    });
                    s.sendMessage(g("Reading " + world.getName() + " and deriving " + mapName + "..."));
                } catch (Throwable t) {
                    s.sendMessage(r("Import failed: " + t.getMessage()));
                    if (temporary) net.h2020.api.map.SlimeWorlds.release(world);
                }
                return true;
            }
            case "mapimportdir": {
                // /lattice mapimportdir <folder> [family] [tags]: import every world folder inside <folder>, one map per
                // second, so a bulk import never trips the tick watchdog. Used for a map pack.
                if (a.length < 2) { s.sendMessage(r("/h2020 mapimportdir <folder> [bedwars|skywars] [tag,tag]")); return true; }
                final java.io.File root = new java.io.File(a[1].startsWith("/") ? a[1] : new java.io.File(".").getAbsolutePath() + "/" + a[1]);
                if (!root.isDirectory()) { s.sendMessage(r("No folder " + root)); return true; }
                final java.util.List<java.io.File> worlds = new java.util.ArrayList<java.io.File>();
                java.io.File[] kids = root.listFiles();
                if (kids != null) for (java.io.File f : kids) if (f.isDirectory() && new java.io.File(f, "level.dat").isFile()) worlds.add(f);
                if (worlds.isEmpty()) { s.sendMessage(r("No world folders (level.dat) in " + root)); return true; }
                java.util.Collections.sort(worlds);
                final net.h2020.api.game.GameFamily fam = a.length > 2
                        ? net.h2020.api.game.GameFamily.valueOf(a[2].toUpperCase()) : net.h2020.api.game.GameFamily.BEDWARS;
                final java.util.List<String> tags = new java.util.ArrayList<String>();
                if (a.length > 3) for (String t : a[3].split(",")) if (!t.trim().isEmpty()) tags.add(t.trim().toLowerCase(java.util.Locale.ROOT));
                final java.io.File mapsRoot = new java.io.File(core.plugin.getDataFolder(), "maps");
                final CommandSender sender = s;
                final int[] at = {0};
                s.sendMessage(g("Importing " + worlds.size() + " world(s) from " + root.getName() + " as " + fam + "..."));
                new org.bukkit.scheduler.BukkitRunnable() {
                    @Override public void run() {
                        if (at[0] >= worlds.size()) {
                            net.h2020.api.map.MapService ms = H2020.find(net.h2020.api.map.MapService.class);
                            if (ms != null) ms.reload();
                            sender.sendMessage(g("Bulk import done: " + worlds.size() + " map(s). Run /lattice convert all to build slime copies."));
                            cancel();
                            return;
                        }
                        java.io.File src = worlds.get(at[0]++);
                        String safe = src.getName().replaceAll("[^A-Za-z0-9_-]", "_");
                        String worldName = "import/" + safe;
                        try {
                            java.io.File dst = new java.io.File(Worlds.container(), worldName);
                            if (dst.exists()) Worlds.delete(dst);
                            Worlds.copyWorld(src, dst);
                            org.bukkit.World w = Worlds.loadVoid(worldName, org.bukkit.block.Biome.PLAINS, 0.5, 96, 0.5);
                            if (w == null) throw new IllegalStateException("Bukkit refused to load it");
                            Object snapshot = net.h2020.maps.MapImporter.scan(w);
                            net.h2020.maps.MapImporter.Result res =
                                    net.h2020.maps.MapImporter.derive(snapshot, prettifyName(src.getName()), fam, mapsRoot, tags);
                            Bukkit.unloadWorld(w, false);
                            // The map keeps its blocks in the template folder until /lattice convert builds the
                            // slime copy and prunes it, exactly as an older install's map/ world used to work.
                            java.io.File keptWorld = new java.io.File(res.folder, "world");
                            Worlds.delete(keptWorld);
                            Worlds.copyWorld(dst, keptWorld);
                            Worlds.delete(dst);
                            core.plugin.getLogger().info("[mapimportdir] " + res.name + " -> " + res.folder.getName()
                                    + " (" + res.lines + ")");
                            if (at[0] % 10 == 0 || at[0] == worlds.size())
                                sender.sendMessage(g("Imported " + at[0] + "/" + worlds.size() + ": " + res.name));
                        } catch (Throwable t) {
                            core.plugin.getLogger().log(java.util.logging.Level.WARNING, "[mapimportdir] failed on " + src.getName(), t);
                            sender.sendMessage(r("Failed " + src.getName() + ": " + t.getMessage()));
                        }
                    }
                }.runTaskTimer(core.plugin, 20L, 20L);
                return true;
            }
            case "maptag": {
                // /lattice maptag <map> <tag,tag|->: rewrite the tags line of a map.yml and reload
                if (a.length < 3) { s.sendMessage(r("/h2020 maptag <map> <tag,tag|->")); return true; }
                net.h2020.api.map.MapService ms = H2020.find(net.h2020.api.map.MapService.class);
                net.h2020.api.map.MapTemplate t = ms == null ? null : ms.byName(a[1]);
                if (t == null) { s.sendMessage(r("No map " + a[1])); return true; }
                java.util.List<String> tags = new java.util.ArrayList<String>();
                if (!a[2].equals("-")) for (String x : a[2].split(",")) if (!x.trim().isEmpty()) tags.add(x.trim().toLowerCase(java.util.Locale.ROOT));
                try {
                    net.h2020.maps.MapFiles.writeTags(((net.h2020.maps.StoredMapTemplate) t).folder(), tags);
                } catch (Exception e) {
                    s.sendMessage(r("Could not write tags: " + e.getMessage()));
                    return true;
                }
                if (ms != null) ms.reload();
                s.sendMessage(g("Tags for " + t.name() + ": " + (tags.isEmpty() ? "none" : String.join(", ", tags))));
                return true;
            }
            case "tags": {
                net.h2020.api.map.MapService ms = H2020.find(net.h2020.api.map.MapService.class);
                if (!(ms instanceof net.h2020.maps.MapServiceImpl)) { s.sendMessage(r("No map service")); return true; }
                java.util.Map<String, int[]> sum = ((net.h2020.maps.MapServiceImpl) ms).tagSummary();
                if (sum.isEmpty()) { s.sendMessage(g("No tags on any map.")); return true; }
                for (java.util.Map.Entry<String, int[]> e : sum.entrySet())
                    s.sendMessage(r(e.getKey()) + g(" - " + e.getValue()[0] + " map(s), "
                            + (e.getValue()[1] == 1 ? "DISABLED" : "enabled")));
                return true;
            }
            case "slimeimport": {
                // /lattice slimeimport <folder> <id>: import an arbitrary staged world folder into the slime store
                // under an explicit id. /lattice convert only knows about map templates and the three fixed lobby ids,
                // which is no help when a new lobby world has to be brought in beside the old one.
                if (a.length < 3) { s.sendMessage(r("/h2020 slimeimport <folder> <slimeId>")); return true; }
                net.h2020.api.map.MapService ms0 = H2020.find(net.h2020.api.map.MapService.class);
                if (!(ms0 instanceof net.h2020.maps.MapServiceImpl)) { s.sendMessage(r("No map service")); return true; }
                net.h2020.maps.MapServiceImpl svc = (net.h2020.maps.MapServiceImpl) ms0;
                if (!svc.hasSlime()) { s.sendMessage(r("SwoftyWorldManager is not installed.")); return true; }
                java.io.File src = new java.io.File(a[1].startsWith("/") ? a[1]
                        : new java.io.File(".").getAbsolutePath() + "/" + a[1]);
                if (!src.isDirectory()) { s.sendMessage(r("No folder " + src)); return true; }
                boolean okImp = svc.importSlime(src.getAbsolutePath(), a[2]);
                s.sendMessage(okImp ? g("Imported " + src.getName() + " as '" + a[2] + "'")
                        : r("Import of " + src.getName() + " failed (see the log)"));
                return true;
            }
            case "convert": {
                net.h2020.api.map.MapService ms = H2020.find(net.h2020.api.map.MapService.class);
                if (ms == null) { s.sendMessage(r("No map service.")); return true; }
                String what = a.length >= 2 ? a[1] : "all";
                int n = ms.convertToSlime(what);
                if (n == -1) s.sendMessage(r("SwoftyWorldManager is not installed, worlds stay as folders."));
                else if (n == -2) s.sendMessage(r("No map called " + what));
                else s.sendMessage(g("Converted " + n + " map(s) to slime: " + String.join(", ", ms.slimeMaps())));
                return true;
            }
            case "arena": {
                String asub = a.length > 1 ? a[1].toLowerCase() : "join";
                if (asub.equals("bot")) {
                    if (a.length < 3) { s.sendMessage(r("/h2020 arena bot <name>")); return true; }
                    Player b = Bukkit.getPlayerExact(a[2]);
                    if (b == null) { s.sendMessage(r("No player " + a[2])); return true; }
                    if (net.h2020.core.arena.PvpArena.spawn() == null) { s.sendMessage(r("Arena world failed to load.")); return true; }
                    b.teleport(net.h2020.core.arena.PvpArena.spawn());
                    b.setHealth(20.0);
                    b.setFoodLevel(20);
                    net.h2020.api.core.DemoFlags.sparMode = true;
                    net.h2020.api.core.DemoFlags.zestEnabled = true;
                    s.sendMessage(g("Sent " + b.getName() + " to the arena."));
                    return true;
                }
                if (asub.equals("fill")) {
                    int n = a.length > 2 ? parseInt(a[2], 3) : 3;
                    int moved = net.h2020.core.arena.PvpArena.populate(n);
                    s.sendMessage(g("Moved " + moved + " bots into the arena."));
                    return true;
                }
                if (asub.equals("probe")) {
                    if (a.length < 3) { s.sendMessage(r("/h2020 arena probe <name>")); return true; }
                    Player pl = Bukkit.getPlayerExact(a[2]);
                    if (pl == null) { s.sendMessage(r("No player " + a[2])); return true; }
                    pl.addPotionEffect(new org.bukkit.potion.PotionEffect(
                            org.bukkit.potion.PotionEffectType.DAMAGE_RESISTANCE, 200, 20, true, false), true);
                    int amp = -1; int count = 0;
                    for (org.bukkit.potion.PotionEffect e2 : pl.getActivePotionEffects()) { count++; if (e2.getType().getName().equals("DAMAGE_RESISTANCE")) amp = e2.getAmplifier(); }
                    s.sendMessage(g("probe " + pl.getName() + ": effects=" + count + " resistanceAmp=" + amp
                            + " types=" + pl.getActivePotionEffects().stream().map(e2 -> e2.getType().getName()).collect(java.util.stream.Collectors.toList())));
                    return true;
                }
                if (asub.equals("status")) {
                    org.bukkit.World w = Bukkit.getWorld(net.h2020.core.arena.PvpArena.WORLD);
                    if (w == null) { s.sendMessage(r("Arena world is not loaded.")); return true; }
                    java.util.List<Player> in = new java.util.ArrayList<Player>(w.getPlayers());
                    s.sendMessage(g("  arena players: " + in.size() + " (buff tick ran " + net.h2020.core.arena.PvpArena.buffRuns + "x)"));
                    for (Player pl : in) {
                        int amp = -1, sat = -1;
                        for (org.bukkit.potion.PotionEffect e : pl.getActivePotionEffects()) {
                            if (e.getType().getName().equals("DAMAGE_RESISTANCE")) amp = e.getAmplifier();
                            if (e.getType().getName().equals("SATURATION")) sat = e.getAmplifier();
                        }
                        int canSee = 0, total = 0;
                        for (Player o : in) { if (o == pl) continue; total++; if (pl.canSee(o)) canSee++; }
                        s.sendMessage(g("    " + pl.getName() + " hp=" + Math.round(pl.getHealth()) + " gm=" + pl.getGameMode()
                                + " inv=" + pl.hasPotionEffect(org.bukkit.potion.PotionEffectType.INVISIBILITY)
                                + " resist=" + amp + " sat=" + sat + " sees=" + canSee + "/" + total
                                + " at " + Math.round(pl.getLocation().getX()) + "," + Math.round(pl.getLocation().getY())
                                + "," + Math.round(pl.getLocation().getZ())));
                    }
                    return true;
                }
                if (asub.equals("die")) {
                    // prove the no-death rule: kill a player outright and check they come back on the pad
                    if (a.length < 3) { s.sendMessage(r("/h2020 arena die <name>")); return true; }
                    Player victim = Bukkit.getPlayerExact(a[2]);
                    if (victim == null) { s.sendMessage(r("No player " + a[2])); return true; }
                    boolean inArena = net.h2020.core.arena.PvpArena.isArena(victim.getWorld());
                    victim.setHealth(0.0);
                    s.sendMessage(g("Killed " + victim.getName() + " in arena=" + inArena + "; they should be back on the pad"));
                    return true;
                }
                if (asub.equals("reset")) {
                    org.bukkit.World w = org.bukkit.Bukkit.getWorld(net.h2020.core.arena.PvpArena.WORLD);
                    if (w != null) for (Player pl : w.getPlayers()) pl.teleport(net.h2020.core.arena.PvpArena.spawn());
                    s.sendMessage(g("Arena reset."));
                    return true;
                }
                if (!(s instanceof Player)) { s.sendMessage(r("A player must run this, or use /h2020 arena bot <name>.")); return true; }
                net.h2020.core.arena.PvpArena.join((Player) s);
                s.sendMessage(g("Arena: " + net.h2020.core.arena.PvpArena.WORLD + " at 0,65,0"));
                return true;
            }
            case "spar": {
                // demo: no retreats, everyone topped up, so a combo can be filmed
                boolean on = a.length < 2 || !a[1].equalsIgnoreCase("off");
                net.h2020.api.core.DemoFlags.sparMode = on;
                net.h2020.api.core.DemoFlags.resistanceOn = on;
                if (on) {
                    net.h2020.api.core.DemoFlags.resistanceAmp = Math.max(2, net.h2020.api.core.DemoFlags.resistanceAmp);
                    Game sg = s instanceof Player ? core.games.gameOf((Player) s) : null;
                    if (sg == null && !core.games.games().isEmpty()) sg = core.games.games().iterator().next();
                    if (sg != null) {
                        org.bukkit.Location mid = sg.world().getSpawnLocation();
                        int i = 0;
                        for (Player bp2 : sg.players()) {
                            if (!net.h2020.core.CoreUtil.isBot(bp2)) continue;
                            double ang = (i % 2 == 0 ? 0 : Math.PI) + i * 0.4;
                            org.bukkit.Location at = mid.clone().add(Math.cos(ang) * 3, 0, Math.sin(ang) * 3);
                            int land = at.getWorld().getHighestBlockYAt(at);
                            at.setY(Math.max(at.getY(), land + 1));
                            bp2.teleport(at);
                            i++;
                        }
                    }
                }
                s.sendMessage(g("spar " + (on ? "on (no retreats, resistance " + net.h2020.api.core.DemoFlags.resistanceAmp + ")" : "off")));
                return true;
            }
            case "gens": {
                net.h2020.api.game.BedwarsTools bt = H2020.find(net.h2020.api.game.BedwarsTools.class);
                if (bt == null) { s.sendMessage(r("The Bed Wars plugin is not installed.")); return true; }
                bt.generators(s, s instanceof Player ? (Player) s : null);
                return true;
            }
            case "teamchest": {
                net.h2020.api.game.BedwarsTools bt = H2020.find(net.h2020.api.game.BedwarsTools.class);
                if (bt == null) { s.sendMessage(r("The Bed Wars plugin is not installed.")); return true; }
                bt.teamChest(s, s instanceof Player ? (Player) s : null);
                return true;
            }
            case "modes": {
                // what the lobby menus are generated from: family, order, id and size
                for (net.h2020.api.game.GameFamily f : net.h2020.api.game.GameFamily.values()) {
                    StringBuilder line = new StringBuilder();
                    for (net.h2020.api.game.GameMode m : net.h2020.api.game.GameModes.of(f)) {
                        line.append("  ").append(m.id()).append(" (").append(m.teams()).append("x").append(m.teamSize()).append(")");
                    }
                    s.sendMessage(g(f + ":" + (line.length() == 0 ? "  none" : line.toString())));
                }
                return true;
            }
            case "fill": {
                if (a.length < 2) { s.sendMessage(r("/h2020 fill <type> [count]")); return true; }
                net.h2020.api.game.GameMode t = net.h2020.api.game.GameModes.byId(a[1]);
                if (t == null) { s.sendMessage(r("Unknown type " + a[1])); return true; }
                Game game = core.games.findJoinable(t);
                if (game == null) game = core.games.createRandom(t);
                if (game == null) { s.sendMessage(r("Could not create a " + t.id() + " game.")); return true; }
                int count = a.length >= 3 ? parseInt(a[2], Integer.MAX_VALUE) : Integer.MAX_VALUE;
                int n = core.filler.fillNow(game, count);
                s.sendMessage(g("Added " + n + " bots to " + game.id() + " (" + game.mapName() + ")."));
                return true;
            }
            case "join": {
                // put any online player (human or bot) into a game of that type, e.g. for recording or testing
                if (a.length < 3) { s.sendMessage(r("/h2020 join <player> <type>")); return true; }
                Player target = Bukkit.getPlayerExact(a[1]);
                net.h2020.api.game.GameMode t = net.h2020.api.game.GameModes.byId(a[2]);
                if (target == null || t == null) { s.sendMessage(r("Unknown player or type.")); return true; }
                Game game = core.games.queue(target, t);
                s.sendMessage(game == null ? r("Could not queue " + target.getName() + ".")
                        : g("Queued " + target.getName() + " into " + game.id() + " (" + game.mapName() + ")."));
                return true;
            }
            case "bots": {
                if (a.length < 2) { s.sendMessage(r("/h2020 bots <n> [main|bedwars|skywars]")); return true; }
                int n = Math.max(0, parseInt(a[1], -1));
                World w;
                if (a.length >= 3) w = core.lobby.lobbyWorld(family(a[2]));
                else if (s instanceof Player && core.lobby.isLobby(((Player) s).getWorld())) w = ((Player) s).getWorld();
                else w = core.lobby.lobbyWorld(null);
                int diff = core.lobbyBots.setCount(w, n);
                s.sendMessage(g("Lobby " + w.getName() + ": " + (diff >= 0 ? "+" : "") + diff + " bots (target " + n + ")."));
                return true;
            }
            case "watch": {
                if (!(s instanceof Player)) return true;
                if (a.length < 2) { s.sendMessage(r("/h2020 watch <gameId>")); return true; }
                Game game = core.games.byId(a[1]);
                if (game == null) { s.sendMessage(r("No game " + a[1])); return true; }
                Player p = (Player) s;
                Game cur = core.games.gameOf(p);
                if (cur != null) core.games.leaveGame(p, cur);
                core.lobby.leaving(p);
                core.scoreboards.clearSidebar(p);
                p.getInventory().clear();
                p.setGameMode(GameMode.SPECTATOR);
                Location to = game.spectatorSpawn();
                p.teleport(to != null ? to : game.world().getSpawnLocation());
                p.sendMessage(g("You are now spectating " + game.id() + " (" + game.mapName() + "). Use /lobby to leave."));
                return true;
            }
            case "end": {
                if (a.length < 2) { s.sendMessage(r("/h2020 end <gameId>")); return true; }
                Game g = core.games.byId(a[1]);
                if (g == null) { s.sendMessage(r("No game " + a[1])); return true; }
                g.shutdown();
                s.sendMessage(g("Ended " + a[1]));
                return true;
            }
            case "replay": {
                net.h2020.api.core.AdminHooks.Hook rh = net.h2020.api.core.AdminHooks.get("replay");
                if (rh == null) { s.sendMessage(r("The bots plugin is not installed.")); return true; }
                rh.run(s, a);
                return true;
            }
            case "train": {
                net.h2020.api.core.AdminHooks.Hook th = net.h2020.api.core.AdminHooks.get("train");
                if (th == null) { s.sendMessage(r("The bots plugin is not installed.")); return true; }
                th.run(s, a);
                return true;
            }
            case "placecheck": {
                if (a.length < 4) { s.sendMessage(r("/h2020 placecheck <x> <y> <z>")); return true; }
                net.h2020.api.game.BedwarsTools bt = H2020.find(net.h2020.api.game.BedwarsTools.class);
                if (bt == null) { s.sendMessage(r("The Bed Wars plugin is not installed.")); return true; }
                org.bukkit.World w = s instanceof Player ? ((Player) s).getWorld() : Bukkit.getWorlds().get(0);
                bt.placeCheck(s, w.getBlockAt(parseInt(a[1], 0), parseInt(a[2], 64), parseInt(a[3], 0)));
                return true;
            }
            case "tower": {
                // build a pop-up tower in place, for testing the blueprint without a player hand
                if (a.length < 4) { s.sendMessage(r("/h2020 tower <x> <y> <z>")); return true; }
                org.bukkit.World w = s instanceof Player ? ((Player) s).getWorld() : Bukkit.getWorlds().get(0);
                org.bukkit.Location at = new org.bukkit.Location(w, parseInt(a[1], 0), parseInt(a[2], 64), parseInt(a[3], 0));
                net.h2020.api.game.BedwarsTools bt = H2020.find(net.h2020.api.game.BedwarsTools.class);
                if (bt == null) { s.sendMessage(r("The Bed Wars plugin is not installed.")); return true; }
                bt.tower(s, at);
                s.sendMessage(g("Pop-up tower under construction at " + at.getBlockX() + "," + at.getBlockY() + "," + at.getBlockZ()));
                return true;
            }
            case "reload": {
                core.plugin.reloadConfig();
                core.config.load(core.plugin);
                core.config.applyNetwork(core.plugin);
                net.h2020.api.game.BedwarsTools btr = H2020.find(net.h2020.api.game.BedwarsTools.class);
                if (btr != null) btr.reload();
                net.h2020.core.CoreModule.applyDate(core.config.date);
                if (core.npcs != null) core.npcs.rebuild();
                for (Player p : Bukkit.getOnlinePlayers()) if (!CoreUtil.isBot(p)) core.scoreboards.setTabHeaderFooter(p, core.config.tabHeader, core.config.tabFooter);
                s.sendMessage(g("Hypixel2020 core config reloaded."));
                return true;
            }
            case "date": {
                if (a.length < 2) { s.sendMessage(g("Scoreboard date: " + HypixelFormat.date())); return true; }
                if (a[1].equalsIgnoreCase("2020")) a[1] = "07/14/20";   // back to the 2020 look
                if (a[1].equalsIgnoreCase("today")) a[1] = "today";     // follows the real date
                else if (!DATE.matcher(a[1]).matches()) { s.sendMessage(r("Format: MM/dd/yy (e.g. 07/14/20), 'today' or '2020'")); return true; }
                core.config.saveDate(a[1]);
                net.h2020.core.CoreModule.applyDate(a[1]);
                s.sendMessage(g("Scoreboard date set to " + a[1] + (a[1].equals("today") ? " (" + HypixelFormat.date() + ")" : "") + ". New games use it; the lobby updates now."));
                return true;
            }
            case "rank": {
                if (a.length < 3) { s.sendMessage(r("/h2020 rank <player> <rank> [plusColor]")); return true; }
                Player t = Bukkit.getPlayerExact(a[1]);
                Rank rank = CoreConfig.parseEnum(Rank.class, a[2]);
                if (t == null || rank == null) { s.sendMessage(r("Unknown player or rank. Ranks: " + Arrays.toString(Rank.values()))); return true; }
                ChatColor plus = a.length >= 4 ? CoreConfig.parseEnum(ChatColor.class, a[3]) : null;
                core.profiles.setRank(t, rank, plus);
                s.sendMessage(g("Set " + t.getName() + " to ") + core.profiles.displayName(t));
                return true;
            }
            case "level": {
                if (a.length < 3) { s.sendMessage(r("/h2020 level <player> <level>")); return true; }
                Player t = Bukkit.getPlayerExact(a[1]);
                int lvl = parseInt(a[2], -1);
                if (t == null || lvl < 1) { s.sendMessage(r("Unknown player or bad level.")); return true; }
                core.profiles.setNetworkLevel(t, lvl);
                s.sendMessage(g("Set " + t.getName() + "'s network level to " + lvl));
                return true;
            }
            case "build": {
                if (!(s instanceof Player)) return true;
                Player p = (Player) s;
                boolean on = core.lobby.toggleBuild(p);
                if (on) { p.setGameMode(GameMode.CREATIVE); p.getInventory().clear(); }
                else core.games.sendToLobby(p, core.lobby.familyOf(p.getWorld()));
                s.sendMessage(on ? g("Lobby build mode enabled.") : r("Lobby build mode disabled."));
                return true;
            }
            case "setspawn": {
                if (!(s instanceof Player)) return true;
                Player p = (Player) s;
                if (!core.lobby.isLobby(p.getWorld())) { s.sendMessage(r("Stand in a lobby world.")); return true; }
                core.lobby.setSpawn(p.getLocation());
                if (core.npcs != null) core.npcs.rebuild();
                s.sendMessage(g("Lobby spawn for " + p.getWorld().getName() + " set."));
                return true;
            }
            case "npc": {
                if (!(s instanceof Player)) return true;
                if (a.length < 2) { s.sendMessage(r("/h2020 npc <id>  ids: " + NpcManager.ids())); return true; }
                boolean ok = core.npcs != null && core.npcs.move(a[1], ((Player) s).getLocation());
                s.sendMessage(ok ? g("NPC " + a[1] + " moved here.") : r("Unknown NPC. ids: " + NpcManager.ids()));
                return true;
            }
            case "cheaters": {
                if (a.length < 2) { s.sendMessage(g("Cheater %: " + core.config.cheaterPercent)); return true; }
                core.config.cheaterPercent = Math.max(0, Math.min(100, parseDouble(a[1], 0)));
                core.plugin.getConfig().set("core.bot-fill.cheater-percent", core.config.cheaterPercent);
                core.plugin.saveConfig();
                s.sendMessage(g("Queue-filler cheater percentage set to " + core.config.cheaterPercent + "%"));
                return true;
            }
            case "fillspeed": {
                if (a.length < 2) { s.sendMessage(g("Fill speed: " + core.config.fillSpeed)); return true; }
                core.config.fillSpeed = Math.max(0.05, parseDouble(a[1], 1));
                core.plugin.getConfig().set("core.bot-fill.speed", core.config.fillSpeed);
                core.plugin.saveConfig();
                s.sendMessage(g("Bot fill speed set to x" + core.config.fillSpeed));
                return true;
            }
            case "background": {
                if (a.length < 2) { s.sendMessage(g("Background games: " + core.config.backgroundGames)); return true; }
                core.config.backgroundGames = Math.max(0, parseInt(a[1], 0));
                core.plugin.getConfig().set("core.background-games", core.config.backgroundGames);
                core.plugin.saveConfig();
                s.sendMessage(g("Background bot-only games set to " + core.config.backgroundGames));
                return true;
            }
            case "sidebar": {
                String action = a.length > 1 ? a[1].toLowerCase() : "list";
                if (action.equals("list")) {
                    boolean papi = net.h2020.api.H2020.find(net.h2020.api.core.ScoreboardService.class) != null
                            && Sidebars.placeholderApi();
                    s.sendMessage(g("boards: " + String.join(", ", Sidebars.names())
                            + " (PlaceholderAPI: " + (papi ? "yes" : "no") + ")"));
                    return true;
                }
                if (action.equals("reload")) {
                    try {
                        Sidebars.reload();
                        s.sendMessage(g("sidebar.yml reloaded: " + Sidebars.names().size() + " board(s)"));
                    } catch (Throwable t) {
                        s.sendMessage(r("sidebar.yml reload failed: " + t.getMessage()));
                    }
                    return true;
                }
                if (action.equals("preview")) {
                    if (a.length < 3) { s.sendMessage(r("/lattice sidebar preview <board> [player]")); return true; }
                    SidebarTemplate template = Sidebars.get(a[2].toLowerCase());
                    if (template == null) {
                        s.sendMessage(r("No enabled board '" + a[2] + "'. Boards: " + String.join(", ", Sidebars.names())));
                        return true;
                    }
                    Player target = null;
                    if (a.length >= 4) {
                        target = Bukkit.getPlayerExact(a[3]);
                        if (target == null) { s.sendMessage(r("Unknown player " + a[3])); return true; }
                    } else if (s instanceof Player) {
                        target = (Player) s;
                    }
                    java.util.Map<String, String> vars = new java.util.HashMap<String, String>();
                    // a preview shows every configured line, including conditionals, so use the union of their flags
                    java.util.Set<String> flags = new java.util.HashSet<String>();
                    for (SidebarTemplate.Line line : template.lines) flags.addAll(line.when);
                    // lobby boards can be previewed with the real profile values, which is how the substitution is checked
                    if (target != null && core.lobby != null) {
                        net.h2020.api.game.GameFamily fam = null;
                        if (template.name.startsWith("bedwars")) fam = net.h2020.api.game.GameFamily.BEDWARS;
                        else if (template.name.startsWith("skywars")) fam = net.h2020.api.game.GameFamily.SKYWARS;
                        if (template.name.equals("main") || template.name.endsWith("-lobby")) {
                            vars.putAll(core.lobby.sidebarVars(target, fam));
                        }
                    }
                    s.sendMessage(g("Sidebar " + template.name + " title: " + amp(template.renderTitle(target, vars))));
                    java.util.List<String> lines = template.render(target, vars, flags, null);
                    for (int i = 0; i < lines.size(); i++) {
                        s.sendMessage(ChatColor.GRAY + String.valueOf(i + 1) + ": " + ChatColor.RESET + amp(lines.get(i)));
                    }
                    return true;
                }
                s.sendMessage(r("/lattice sidebar [list] | reload | preview <board> [player]"));
                return true;
            }
            default:
                help(s);
                return true;
        }
    }

    /** The games list as a clickable menu: click a game to spectate it. */
    private void gamesMenu(Player p) {
        java.util.List<Game> list = new java.util.ArrayList<Game>(core.games.games());
        net.h2020.api.gui.Menu m = new net.h2020.api.gui.Menu(ChatColor.DARK_GRAY + "Current games", 6);
        int slot = 0;
        for (final Game g : list) {
            if (slot >= 45) break;
            int players = g.players() == null ? 0 : g.players().size();
            org.bukkit.inventory.ItemStack icon = new net.h2020.api.gui.ItemBuilder(org.bukkit.Material.MAP)
                    .name(ChatColor.GREEN + g.id())
                    .lore(ChatColor.GRAY + "Map: " + ChatColor.WHITE + g.mapName(),
                            ChatColor.GRAY + "Mode: " + ChatColor.WHITE + g.type().id(),
                            ChatColor.GRAY + "State: " + ChatColor.WHITE + g.state().name(),
                            ChatColor.GRAY + "Players: " + ChatColor.WHITE + players + "/" + g.type().maxPlayers(),
                            "", ChatColor.YELLOW + "Click to spectate")
                    .build();
            m.set(slot++, icon, new net.h2020.api.gui.Menu.Click() {
                @Override public void click(Player who, org.bukkit.event.inventory.ClickType type) {
                    who.closeInventory();
                    new net.h2020.core.command.SpectateCommand(core).spectate(who, g);
                }
            });
        }
        if (slot == 0) m.set(22, new net.h2020.api.gui.ItemBuilder(org.bukkit.Material.BARRIER)
                .name(ChatColor.RED + "No games running").build());
        m.open(p);
    }

    private void games(CommandSender s) {
        if (core.games.games().isEmpty()) { s.sendMessage(ChatColor.GRAY + "No running games."); return; }
        s.sendMessage(ChatColor.GOLD + "Running games:");
        for (Game game : core.games.games()) {
            int bots = 0;
            for (Player p : game.players()) if (CoreUtil.isBot(p)) bots++;
            int start = game.secondsUntilStart();
            s.sendMessage(ChatColor.YELLOW + game.id() + ChatColor.GRAY + " " + game.type().id() + " " + ChatColor.WHITE + game.mapName()
                    + ChatColor.GRAY + " [" + game.state() + (start >= 0 ? " " + start + "s" : "") + "] "
                    + ChatColor.GREEN + game.players().size() + "/" + game.type().maxPlayers()
                    + ChatColor.GRAY + " (" + bots + " bots, " + game.alivePlayers().size() + " alive)");
        }
    }

    private static void help(CommandSender s) {
        s.sendMessage(ChatColor.GOLD + "Hypixel2020 admin:");
        s.sendMessage(ChatColor.GRAY + " games");
        s.sendMessage(ChatColor.YELLOW + "/h2020 games" + ChatColor.GRAY + " - list running games");
        s.sendMessage(ChatColor.YELLOW + "/h2020 create <type> [map]" + ChatColor.GRAY + " - create a game");
        s.sendMessage(ChatColor.YELLOW + "/h2020 start <gameId>" + ChatColor.GRAY + " - fill with bots so it starts");
        s.sendMessage(ChatColor.YELLOW + "/h2020 fill <type> [n]" + ChatColor.GRAY + " - add bots to the fullest waiting game");
        s.sendMessage(ChatColor.YELLOW + "/h2020 join <player> <type>" + ChatColor.GRAY + " - queue a player or bot into a game");
        s.sendMessage(ChatColor.YELLOW + "/h2020 bots <n> [lobby]" + ChatColor.GRAY + " - idle bots in a lobby");
        s.sendMessage(ChatColor.YELLOW + "/h2020 watch <gameId>" + ChatColor.GRAY + " - spectate a game");
        s.sendMessage(ChatColor.YELLOW + "/h2020 end <gameId>" + ChatColor.GRAY + " - end a game now");
        s.sendMessage(ChatColor.YELLOW + "/h2020 create bedwars_duel [map]" + ChatColor.GRAY + " - 1v1 on a solo map (RED vs AQUA)");
        s.sendMessage(ChatColor.GRAY + " training (self-play arena)");
        s.sendMessage(ChatColor.YELLOW + "/h2020 train start [total] [train] [eval]" + ChatColor.GRAY + " - run generations");
        s.sendMessage(ChatColor.YELLOW + "/h2020 train stop | report | promote | discard" + ChatColor.GRAY + " - control the run");
        s.sendMessage(ChatColor.GRAY + " replays");
        s.sendMessage(ChatColor.YELLOW + "/h2020 replay list" + ChatColor.GRAY + " - recorded games");
        s.sendMessage(ChatColor.YELLOW + "/h2020 replay start <gameId> [speed]" + ChatColor.GRAY + " - watch one (also in the game menu)");
        s.sendMessage(ChatColor.YELLOW + "/h2020 replay pause | resume | seek <s> | stop" + ChatColor.GRAY + " - playback");
        s.sendMessage(ChatColor.GRAY + " lobby and world");
        s.sendMessage(ChatColor.YELLOW + "/h2020 date [MM/dd/yy|today|2020]" + ChatColor.GRAY + " - scoreboard date");
        s.sendMessage(ChatColor.YELLOW + "/h2020 rank <player> <rank> [color] | level <player> <n>");
        s.sendMessage(ChatColor.YELLOW + "/h2020 cheaters <%> | fillspeed <x> | background <n>");
        s.sendMessage(ChatColor.YELLOW + "/h2020 build | setspawn | npc <id> | reload");
        s.sendMessage(ChatColor.GRAY + " bots have their own help: " + ChatColor.YELLOW + "/bot help");
    }

    /** Write one packet into an embedded pipeline and wait (running its loop) until the recorder sees it. */
    private static long measurePing(EmbeddedChannel ch, Object packet, long[] seen) {
        seen[0] = 0;
        long start = System.currentTimeMillis();
        ch.writeInbound(packet);
        long deadline = start + 1000;
        while (seen[0] == 0 && System.currentTimeMillis() < deadline) {
            try { ch.runPendingTasks(); } catch (Throwable ignored) {}
            if (seen[0] == 0) {
                try { Thread.sleep(1L); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
        }
        return seen[0] == 0 ? -1 : seen[0] - start;
    }

    private static Integer parseInt(String s, int def) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return def; }
    }

    private static double parseDouble(String s, double def) {
        try { return Double.parseDouble(s); } catch (NumberFormatException e) { return def; }
    }

    private static net.h2020.api.game.GameFamily family(String s) {
        String a = s.toLowerCase();
        if (a.startsWith("b")) return net.h2020.api.game.GameFamily.BEDWARS;
        if (a.startsWith("s")) return net.h2020.api.game.GameFamily.SKYWARS;
        return null;
    }

    private static net.h2020.api.game.TeamColor teamColor(String raw) {
        if (raw == null) return null;
        for (net.h2020.api.game.TeamColor c : net.h2020.api.game.TeamColor.values())
            if (c.name().equalsIgnoreCase(raw)) return c;
        return null;
    }

    private static String teamKey(net.h2020.api.game.TeamColor c, String suffix) {
        return "team." + c.name().toLowerCase(java.util.Locale.ROOT) + "." + suffix;
    }

    /** Every colour that actually has at least one team point on this map, in the canonical team order. */
    private static List<net.h2020.api.game.TeamColor> existingTeams(MapTemplate t) {
        List<net.h2020.api.game.TeamColor> out = new ArrayList<net.h2020.api.game.TeamColor>();
        if (t == null) return out;
        for (net.h2020.api.game.TeamColor c : net.h2020.api.game.TeamColor.values()) {
            for (String k : TEAMSHIFT_KEYS) {
                if (!t.points(teamKey(c, k)).isEmpty()) { out.add(c); break; }
            }
        }
        return out;
    }

    /** One team key as Locations (yaw preserved), ready for MapEditor's point writer. */
    private static List<org.bukkit.Location> teamPoints(MapTemplate t, net.h2020.api.game.TeamColor c, String suffix) {
        List<org.bukkit.Location> out = new ArrayList<org.bukkit.Location>();
        if (t == null) return out;
        String key = teamKey(c, suffix);
        List<org.bukkit.util.Vector> pts = t.points(key);
        if (pts == null) return out;
        World w = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
        for (int i = 0; i < pts.size(); i++) {
            org.bukkit.util.Vector v = pts.get(i);
            out.add(new org.bukkit.Location(w, v.getX(), v.getY(), v.getZ(), t.yaw(key, i), 0f));
        }
        return out;
    }

    /**
     * Replace one team point set through MapEditor: clear the old entries, then append the new ones. Appending is the
     * only writer that copes with a source set having a different number of points (a bed is two, a spawn one).
     */
    private static boolean writeTeamPoints(java.io.File dir, net.h2020.api.game.TeamColor c, String suffix,
                                           List<org.bukkit.Location> pts) {
        String key = teamKey(c, suffix);
        while (net.h2020.maps.MapEditor.removePoint(dir, key, 0)) { }
        for (org.bukkit.Location l : pts) {
            if (!net.h2020.maps.MapEditor.writePoint(dir, key, l, false, -2)) return false;
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) {
            for (String x : SUBS) if (x.startsWith(a[0].toLowerCase())) out.add(x);
        } else if (a.length == 2) {
            String sub = a[0].toLowerCase();
            if (sub.equals("setpoi")) {
                if (a.length == 2) {
                    net.h2020.api.map.MapService ms = H2020.find(net.h2020.api.map.MapService.class);
                    if (ms != null) for (net.h2020.api.map.MapTemplate t : ms.templates(net.h2020.api.game.GameFamily.values()[0])) out.add(t.name());
                    if (ms != null) for (net.h2020.api.game.GameFamily f : net.h2020.api.game.GameFamily.values())
                        for (net.h2020.api.map.MapTemplate t : ms.templates(f)) if (!out.contains(t.name())) out.add(t.name());
                } else if (a.length == 3) {
                    java.util.Collections.addAll(out, net.h2020.maps.MapEditor.keys());
                } else out.add("head");
                return out;
            }
            if (sub.equals("teamshift")) {
                net.h2020.api.map.MapService ms = H2020.find(net.h2020.api.map.MapService.class);
                if (ms != null) for (net.h2020.api.game.GameFamily f : net.h2020.api.game.GameFamily.values())
                    for (net.h2020.api.map.MapTemplate t : ms.templates(f))
                        if (!out.contains(t.name())) out.add(t.name());
                return out;
            }
            if (sub.equals("create") || sub.equals("fill")) {
                for (net.h2020.api.game.GameMode m : net.h2020.api.game.GameModes.all()) {
                    if (m.id().startsWith(a[1].toLowerCase())) out.add(m.id());
                }
            } else if (sub.equals("start") || sub.equals("watch")) {
                for (Game game : core.games.games()) if (game.id().toLowerCase().startsWith(a[1].toLowerCase())) out.add(game.id());
            } else if (sub.equals("npc")) {
                for (String id : NpcManager.ids()) if (id.startsWith(a[1].toLowerCase())) out.add(id);
            } else if (sub.equals("replay")) {
                for (String c : new String[]{"list", "start", "pause", "resume", "seek", "stop", "verify"})
                    if (c.startsWith(a[1].toLowerCase())) out.add(c);
            } else if (sub.equals("train")) {
                for (String c : new String[]{"start", "stop", "report", "promote", "discard", "hindsight", "human"})
                    if (c.startsWith(a[1].toLowerCase())) out.add(c);
            } else if (sub.equals("rank") || sub.equals("level")) {
                for (Player p : Bukkit.getOnlinePlayers()) if (p.getName().toLowerCase().startsWith(a[1].toLowerCase())) out.add(p.getName());
            } else if (sub.equals("ping")) {
                for (String c : new String[]{"off", "status", "enable", "disable"})
                    if (c.startsWith(a[1].toLowerCase())) out.add(c);
                for (Player p : Bukkit.getOnlinePlayers()) if (p.getName().toLowerCase().startsWith(a[1].toLowerCase())) out.add(p.getName());
            }
        } else if (a.length == 3 && a[0].equalsIgnoreCase("rank")) {
            for (Rank rk : Rank.values()) if (rk.name().toLowerCase().startsWith(a[2].toLowerCase())) out.add(rk.name());
        } else if (a.length == 3 && a[0].equalsIgnoreCase("ping")) {
            for (String ms : new String[]{"0", "50", "100", "150", "200", "300", "500"}) if (ms.startsWith(a[2])) out.add(ms);
        } else if (a.length >= 3 && a[0].equalsIgnoreCase("teamshift")) {
            String mode = a[2].toLowerCase();
            if (a.length == 3) {
                for (String c : new String[]{"swap", "rotate", "offset"}) if (c.startsWith(mode)) out.add(c);
            } else if (a.length == 4) {
                if (mode.equals("rotate")) {
                    for (int i = 1; i <= 7; i++) out.add(String.valueOf(i));
                } else {
                    for (net.h2020.api.game.TeamColor c : net.h2020.api.game.TeamColor.values())
                        out.add(c.name().toLowerCase(java.util.Locale.ROOT));
                }
            } else if (a.length == 5 && mode.equals("swap")) {
                for (net.h2020.api.game.TeamColor c : net.h2020.api.game.TeamColor.values())
                    out.add(c.name().toLowerCase(java.util.Locale.ROOT));
            }
        }
        return out;
    }
}
