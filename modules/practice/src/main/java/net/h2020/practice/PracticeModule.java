package net.h2020.practice;

import net.h2020.api.H2020;
import net.h2020.api.Module;
import net.h2020.api.bot.Bot;
import net.h2020.api.bot.BotProfile;
import net.h2020.api.bot.BotService;
import net.h2020.api.game.Game;
import net.h2020.api.game.GameFamily;
import net.h2020.api.game.GameManager;
import net.h2020.api.game.GameMode;
import net.h2020.api.game.GameModes;
import net.h2020.api.game.GameProvider;
import net.h2020.api.game.GameState;
import net.h2020.api.map.MapTemplate;
import net.h2020.practice.drill.BedDefenceDrill;
import net.h2020.practice.drill.BlockInDrill;
import net.h2020.practice.drill.BedDefenceTemplates;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import net.h2020.practice.drill.BowDrill;
import net.h2020.practice.drill.BridgeDrill;
import net.h2020.practice.drill.Drill;
import net.h2020.practice.drill.FireballDrill;
import net.h2020.practice.drill.GameSenseDrill;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Fireball;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bed Wars practice: one server, five drills, no match to win.
 *
 * <p>Registered as a family and a set of modes, so a drill is created exactly like a game and the rest of the server
 * keeps working around it - the player is in a game, the lobby router leaves them alone, and {@code /hub} gets them out.
 * The arena is generated per session and deleted afterwards: a drill is a shape of blocks, not a map to maintain.
 *
 * <p>Bots can be dropped into any drill with {@code /practice <drill> bots <n>}. They are ordinary bots on the same
 * arena with the same kit, so the same scoring applies and their numbers are comparable with a player's.
 */
public final class PracticeModule implements Module, GameProvider, Listener, CommandExecutor {

    private JavaPlugin plugin;
    private BukkitTask ticker;
    private final Map<World, PracticeGame> games = new ConcurrentHashMap<World, PracticeGame>();
    private final Map<UUID, PracticeGame> byPlayer = new ConcurrentHashMap<UUID, PracticeGame>();

    @Override public String name() { return "practice"; }

    @Override
    public void enable(JavaPlugin plugin) {
        this.plugin = plugin;
        for (PracticeMode m : PracticeMode.ALL) GameModes.register(m);
        BedDefenceTemplates.load(plugin);
        // the replay viewer has no map to look up for a drill: let it ask for a fresh arena instead
        net.h2020.api.practice.PracticeArenas.install(new net.h2020.api.practice.PracticeArenas.Factory() {
            @Override public World create(String modeId) {
                PracticeMode pm = null;
                for (PracticeMode m : PracticeMode.ALL) if (m.id().equalsIgnoreCase(modeId)) pm = m;
                if (pm == null) return null;
                Drill d = drillFor(pm);
                if (d == null) return null;
                PracticeArena a = PracticeArena.create(d.id());
                d.build(a);
                return a.world();
            }
        });
        Bukkit.getPluginManager().registerEvents(this, plugin);
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            @Override public void run() {
                for (PracticeGame g : new ArrayList<PracticeGame>(games.values())) {
                    try { g.tick(); } catch (Throwable t) { plugin.getLogger().warning("practice tick: " + t); }
                }
            }
        }, 1L, 1L);
        org.bukkit.command.PluginCommand cmd = plugin.getCommand("practice");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(new Tab());
        }
        tryRegister();
    }

    @Override
    public void disable() {
        if (ticker != null) ticker.cancel();
        for (PracticeGame g : new ArrayList<PracticeGame>(games.values())) {
            try { g.shutdown(); } catch (Throwable ignored) { }
        }
        games.clear();
        byPlayer.clear();
    }

    /** The core registers GameManager during its own enable; retry for a moment if this module wins the race. */
    private void tryRegister() {
        GameManager gm = H2020.find(GameManager.class);
        if (gm != null) { gm.registerProvider(this); return; }
        Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
            @Override public void run() { tryRegister(); }
        }, 20L);
    }

    // ---- GameProvider ----

    @Override public GameFamily family() { return GameFamily.PRACTICE; }

    @Override
    public Game create(GameMode type, MapTemplate map, String gameId) {
        PracticeMode pm = asPractice(type);
        Drill drill = drillFor(type);
        if (pm == null || drill == null) return null;
        PracticeArena arena = PracticeArena.create(drill.id());
        PracticeGame g = new PracticeGame(pm, gameId, drill, arena);
        games.put(arena.world(), g);
        return g;
    }

    private static PracticeMode asPractice(GameMode m) {
        for (PracticeMode p : PracticeMode.ALL) if (p.id().equals(m.id())) return p;
        return null;
    }

    private static Drill drillFor(GameMode m) {
        String id = m.id();
        if (id.equals(PracticeMode.FIREBALL.id())) return new FireballDrill();
        if (id.equals(PracticeMode.BOW.id())) return new BowDrill();
        if (id.equals(PracticeMode.SENSE.id())) return new GameSenseDrill();
        if (id.equals(PracticeMode.BRIDGE.id())) return new BridgeDrill(BridgeDrill.Variant.STRAIGHT);
        if (id.equals(PracticeMode.BRIDGE_DIAGONAL.id())) return new BridgeDrill(BridgeDrill.Variant.DIAGONAL);
        if (id.equals(PracticeMode.BED_DEFENCE.id())) return new BedDefenceDrill();
        if (id.equals(PracticeMode.BLOCK_IN.id())) return new BlockInDrill();
        return null;
    }

    // ---- command ----

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Player p = sender instanceof Player ? (Player) sender : null;
        if (p == null && args.length > 0 && args[0].equalsIgnoreCase("debug")) { debug(sender); return true; }
        if (p == null) {
            // console: only the drills that can run without a player, i.e. with bots in them. This is how a drill is
            // exercised headlessly, and how a bot run is measured without a human standing in the arena.
            if (args.length < 3) {
                net.h2020.api.core.Messages.send(sender, "practice.command.console-usage");
                return true;
            }
            PracticeMode m = modeFor(args[0].toLowerCase(java.util.Locale.ROOT));
            if (m == null) { sender.sendMessage(net.h2020.api.core.Messages.get("practice.practicemodule.unknown-drill","p1",args[0])); return true; }
            int n = 0;
            for (int i = 1; i + 1 < args.length; i++)
                if (args[i].equalsIgnoreCase("bots")) {
                    try { n = Math.max(1, Math.min(8, Integer.parseInt(args[i + 1]))); } catch (Exception ignored) { }
                }
            String tech = techniqueArg(args);
            PracticeGame g = start(m);
            if (g == null) { net.h2020.api.core.Messages.send(sender, "practice.command.start-failed"); return true; }
            track(g);
            spawnBots(g, n, tech);
            sender.sendMessage(net.h2020.api.core.Messages.get("practice.practicemodule.started-with-bot-s","p1",m.displayName(),"p2",n,"p3",g.id()));
            return true;
        }

        if (args.length == 0) { menu(p); return true; }

        String sub = args[0].toLowerCase(java.util.Locale.ROOT);
        if (sub.equals("leave") || sub.equals("quit") || sub.equals("lobby")) {
            PracticeGame g = byPlayer.get(p.getUniqueId());
            if (g == null) { net.h2020.api.core.Messages.send(p, "practice.practicemodule.you-are-not-in"); return true; }
            g.removePlayer(p);
            GameManager gm = H2020.find(GameManager.class);
            if (gm != null) gm.sendToLobby(p, null);
            return true;
        }
        // only an editor verb goes to the editor: /practice beddefence on its own starts the drill, the way every
        // other drill does, instead of printing the sub-command list at somebody who asked to play
        if ((sub.equals("beddefence") || sub.equals("defence") || sub.equals("defense"))
                && args.length > 1 && isEditorVerb(args[1])) {
            return bedDefenceCommand(p, sender, args);
        }
        if (sub.equals("stats")) { stats(p); return true; }
        if (sub.equals("debug")) { debug(sender); return true; }
        if (sub.equals("answer")) {
            PracticeGame g = byPlayer.get(p.getUniqueId());
            if (g == null) { net.h2020.api.core.Messages.send(p, "practice.practicemodule.you-are-not-in"); return true; }
            if (!(g.drill() instanceof GameSenseDrill)) { net.h2020.api.core.Messages.send(p, "practice.practicemodule.not-the-game-sense"); return true; }
            int n;
            try { n = Integer.parseInt(args[1]); } catch (Exception e) { net.h2020.api.core.Messages.send(p, "practice.practicemodule.practice-answer-1-3"); return true; }
            ((GameSenseDrill) g.drill()).answer(g, p, n);
            return true;
        }

        PracticeMode mode = modeFor(sub);
        if (mode == null) {
            p.sendMessage(net.h2020.api.core.Messages.get("practice.practicemodule.unknown-drill-try-fireball","p1",args[0]));
            return true;
        }

        int bots = 0;
        for (int i = 1; i + 1 < args.length; i++) {
            if (args[i].equalsIgnoreCase("bots")) {
                try { bots = Math.max(0, Math.min(8, Integer.parseInt(args[i + 1]))); } catch (Exception ignored) { }
            }
        }
        String tech = techniqueArg(args);

        PracticeGame existing = byPlayer.get(p.getUniqueId());
        if (existing != null) existing.removePlayer(p);

        PracticeGame g = start(mode);
        if (g == null) {
            net.h2020.api.core.Messages.send(p, "practice.practicemodule.could-not-start-the");
            return true;
        }
        g.addPlayer(p);
        track(g);
        if (bots > 0) {
            List<Player> before = new ArrayList<Player>(g.players());
            spawnBots(g, bots, tech);
            // a player who starts a bridging drill to watch a bot run it does not stand in the arena: they ride the
            // first bot that is actually bridging, in spectator
            if (g.drill() instanceof BridgeDrill) {
                Player watch = null;
                for (Player q : g.players()) if (!before.contains(q) && !q.equals(p)) { watch = q; break; }
                if (watch != null) g.follow(p, watch);
            }
        }
        return true;
    }

    private PracticeMode modeFor(String sub) {
        switch (sub) {
            case "fireball": case "fireballs": case "deflect": return PracticeMode.FIREBALL;
            case "bow": case "aim": case "archery": return PracticeMode.BOW;
            case "gamesense": case "sense": case "scenarios": return PracticeMode.SENSE;
            case "bridge": case "bridging": return PracticeMode.BRIDGE;
            case "beddefence": case "defence": case "defense": return PracticeMode.BED_DEFENCE;
            case "bridge-diagonal": case "diagonal": case "diag": return PracticeMode.BRIDGE_DIAGONAL;
            case "blockin": case "block-in": case "seal": return PracticeMode.BLOCK_IN;
            default: return null;
        }
    }

    /** Create the game through the manager, so it is registered like any other match (lobby routing depends on it). */
    private PracticeGame start(PracticeMode mode) {
        GameManager gm = H2020.find(GameManager.class);
        if (gm == null) return null;
        Game g = gm.create(mode, PracticeMap.of(mode));
        if (!(g instanceof PracticeGame)) return null;
        return (PracticeGame) g;
    }

    private void track(PracticeGame g) {
        games.put(g.world(), g);
        for (Player p : g.players()) byPlayer.put(p.getUniqueId(), g);
        // players are added after creation, so watch joins through the tick loop as well
        Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            @Override public void run() {
                if (g.state() == GameState.ENDING) return;
                for (Player p : g.players()) byPlayer.put(p.getUniqueId(), g);
            }
        }, 20L, 40L);
    }

    /** The technique token of a command, or "auto" when none was given: bots then use the persona's own method. */
    private static String techniqueArg(String[] args) {
        for (int i = 1; i < args.length; i++) {
            String a = args[i].toLowerCase(java.util.Locale.ROOT);
            if (a.equals("bots") || a.matches("\\d+")) continue;
            if (a.matches("auto|noob|shift|speed|ninja|ninja2|moonwalk|breezily")) return a;
        }
        return "auto";
    }

    /** Bots on the same arena with the same kit: this is the testing ground. */
    private void spawnBots(PracticeGame g, int n, String technique) {
        BotService bs = H2020.find(BotService.class);
        if (bs == null) {
            for (Player p : g.players()) net.h2020.api.core.Messages.send(p, "practice.practicemodule.bots-are-not-available");
            return;
        }
        Location base = g.drill().spawn();
        for (int i = 0; i < n; i++) {
            try {
                BotProfile prof = bs.randomProfile();
                // one lane each: two bots on the same block fight over the same cells and neither bridges
                Location at = base.clone().add(0, 0, (i % 3) - 1);
                Bot bot = bs.spawn(prof, at);
                if (bot == null || bot.player() == null) continue;
                g.addPlayer(bot.player());
                byPlayer.put(bot.player().getUniqueId(), g);
                // Outside the bridging drill a bot has nothing to do but stand where the drill put it: a lobby brain
                // wanders, walks off the rim and spends the session falling, which is what the fireball lane showed
                // the first time these were spawned (health 2 at y=49, thirteen fireballs taken in twenty seconds).
                // the bed-defence drill hands the bot the cells it wants built, so a bot's run is the same structure
                // a person builds and the two times sit in one file
                if (g.drill() instanceof BedDefenceDrill) {
                    BedDefenceDrill bd = (BedDefenceDrill) g.drill();
                    java.util.List<int[]> cells = bd.targetCells();
                    int[][] arr = new int[cells.size()][];
                    for (int k = 0; k < cells.size(); k++) arr[k] = cells.get(k);
                    bot.setBrain(bs.bedDefenceBrain(arr, bd.bedX(), bd.bedY(), bd.bedZ()));
                }
                // a block-in is the defence drill with a much smaller cell set: the holes are what has to be filled,
                // and the brain that fills given cells already exists
                if (g.drill() instanceof BlockInDrill) {
                    BlockInDrill bi = (BlockInDrill) g.drill();
                    java.util.List<int[]> holes = bi.gapCells();
                    int[][] arr = new int[holes.size()][];
                    for (int k = 0; k < holes.size(); k++) arr[k] = holes.get(k);
                    // outside the wall, like the player: the hole is in the front face and a bot standing in front
                    // of it is a bot that can reach it
                    bot.setBrain(bs.bedDefenceBrain(arr, bi.bedX(), bi.bedY(), bi.bedZ(), false));
                }
                if (!(g.drill() instanceof BridgeDrill) && !(g.drill() instanceof BedDefenceDrill)
                        && !(g.drill() instanceof BlockInDrill)) bot.setBrain(null);
                // a bridging drill is only a benchmark if the bot actually bridges: give it the brain that does
                if (g.drill() instanceof BridgeDrill) {
                    BridgeDrill bd = (BridgeDrill) g.drill();
                    int dx = bd.variant() == BridgeDrill.Variant.DIAGONAL ? 1 : 1;
                    int dz = bd.variant() == BridgeDrill.Variant.DIAGONAL ? 1 : 0;
                    bot.setBrain(bs.bridgeBrain(dx, dz, 26, technique));
                }
            } catch (Throwable t) {
                plugin.getLogger().warning("practice bot: " + t);
            }
        }
    }

    private void menu(Player p) {
        for (String line : net.h2020.api.core.Messages.lines("practice.menu.lines")) p.sendMessage(line);
    }

    // ---------------------------------------------------------------- bed defence template editor

    /** An open editing session: a private arena, the bed, and the shape being written. */
    private static final class Edit {
        PracticeArena arena;
        String id;
        int x, y, z;
    }

    private final java.util.Map<java.util.UUID, Edit> edits = new ConcurrentHashMap<java.util.UUID, Edit>();

    /**
     * /practice beddefence edit|save|cancel|list|use|delete
     *
     * <p>Editing happens in a private void arena with a bed on a plinth, exactly like the drill's, and the blocks
     * placed around that bed become the shape. Nothing is read from the player's inventory and nothing is inferred:
     * build it, say save, and that is the template.
     */
    private static boolean isEditorVerb(String a) {
        switch (a.toLowerCase(java.util.Locale.ROOT)) {
            case "edit": case "save": case "cancel": case "list": case "use": case "delete":
                return true;
            default:
                return false;
        }
    }

    private boolean bedDefenceCommand(Player p, CommandSender sender, String[] args) {
        String action = args.length > 1 ? args[1].toLowerCase(java.util.Locale.ROOT) : "list";
        if (action.equals("list")) {
            sender.sendMessage(ChatColor.AQUA + "Defence templates " + ChatColor.DARK_GRAY + "(" + BedDefenceTemplates.count() + ")");
            int i = 1;
            for (String id : BedDefenceTemplates.ids()) {
                sender.sendMessage(ChatColor.GRAY + "  " + (i++) + ". " + ChatColor.WHITE + id
                        + (id.equals(BedDefenceTemplates.activeId()) ? ChatColor.GREEN + "  (active)" : ""));
            }
            sender.sendMessage(ChatColor.DARK_GRAY + "  file: " + BedDefenceTemplates.path());
            return true;
        }
        if (action.equals("use")) {
            if (args.length < 3) { sender.sendMessage(ChatColor.RED + "/practice beddefence use <id>"); return true; }
            if (!BedDefenceTemplates.has(args[2])) { sender.sendMessage(ChatColor.RED + "No template " + args[2]); return true; }
            BedDefenceTemplates.active(args[2]);
            BedDefenceTemplates.save(plugin);
            sender.sendMessage(ChatColor.GREEN + "Defence " + ChatColor.WHITE + args[2] + ChatColor.GREEN + " is now the drill's");
            return true;
        }
        if (action.equals("delete")) {
            if (args.length < 3) { sender.sendMessage(ChatColor.RED + "/practice beddefence delete <id>"); return true; }
            boolean had = BedDefenceTemplates.remove(args[2]);
            if (had) BedDefenceTemplates.save(plugin);
            sender.sendMessage(had ? ChatColor.GREEN + "Removed " + args[2] : ChatColor.RED + "No template " + args[2]);
            return true;
        }
        if (action.equals("cancel")) {
            Edit e = edits.remove(p.getUniqueId());
            if (e == null) { sender.sendMessage(ChatColor.GRAY + "You are not editing a defence."); return true; }
            e.arena.destroy();
            sender.sendMessage(ChatColor.GRAY + "Editing cancelled.");
            return true;
        }
        if (action.equals("save")) {
            Edit e = edits.get(p.getUniqueId());
            if (e == null) { sender.sendMessage(ChatColor.RED + "Nothing to save - /practice beddefence edit <id> first."); return true; }
            java.util.List<Object[]> cells = scan(e);
            if (cells.isEmpty()) {
                sender.sendMessage(ChatColor.RED + "Nothing built around the bed - place the defence first.");
                return true;
            }
            BedDefenceTemplates.put(e.id, cells);
            BedDefenceTemplates.save(plugin);
            edits.remove(p.getUniqueId());
            e.arena.destroy();
            sender.sendMessage(ChatColor.GREEN + "Saved " + ChatColor.WHITE + e.id + ChatColor.GREEN + " with "
                    + ChatColor.WHITE + cells.size() + ChatColor.GREEN + " blocks - it is now a drill level.");
            return true;
        }
        if (action.equals("edit")) {
            String id = args.length > 2 ? args[2].toLowerCase(java.util.Locale.ROOT) : "custom-" + (BedDefenceTemplates.count() + 1);
            Edit old = edits.remove(p.getUniqueId());
            if (old != null) old.arena.destroy();
            PracticeArena arena = PracticeArena.create("defedit");
            arena.platform(0, 64, 0, 7, Material.SANDSTONE);
            arena.platform(0, 63, 0, 7, Material.SANDSTONE);
            org.bukkit.block.Block foot = arena.world().getBlockAt(0, 65, 0);
            org.bukkit.block.Block head = arena.world().getBlockAt(1, 65, 0);
            foot.setType(Material.BED_BLOCK, false); foot.setData((byte) 3, false);
            head.setType(Material.BED_BLOCK, false); head.setData((byte) 11, false);
            arena.world().setSpawnLocation(0, 65, 6);
            Edit e = new Edit();
            e.arena = arena; e.id = id; e.x = 0; e.y = 65; e.z = 0;
            edits.put(p.getUniqueId(), e);
            GameManager gm = H2020.find(GameManager.class);
            PracticeGame g = byPlayer.get(p.getUniqueId());
            if (g != null) g.removePlayer(p);
            p.teleport(arena.at(0.5, 65, 6.5, 180f));
            p.setGameMode(org.bukkit.GameMode.CREATIVE);
            p.getInventory().clear();
            p.getInventory().setItem(0, new ItemStack(Material.WOOL, 64, (short) 14));
            p.getInventory().setItem(1, new ItemStack(Material.ENDER_STONE, 64));
            p.getInventory().setItem(2, new ItemStack(Material.WOOD, 64, (short) 5));
            p.sendMessage(ChatColor.AQUA + "Editing defence " + ChatColor.WHITE + id);
            p.sendMessage(ChatColor.GRAY + "Build it around the bed, then " + ChatColor.WHITE
                    + "/practice beddefence save" + ChatColor.GRAY + ". Anything on the floor blocks is ignored.");
            return true;
        }
        sender.sendMessage(ChatColor.RED + "/practice beddefence edit|save|cancel|list|use|delete");
        return true;
    }

    /** Every block placed around the bed, as offsets from the bed's foot and its material. */
    private static java.util.List<Object[]> scan(Edit e) {
        java.util.List<Object[]> out = new java.util.ArrayList<Object[]>();
        for (int dx = -6; dx <= 7; dx++) {
            for (int dz = -6; dz <= 6; dz++) {
                for (int dy = 0; dy <= 3; dy++) {
                    org.bukkit.block.Block b = e.arena.world().getBlockAt(e.x + dx, e.y + dy, e.z + dz);
                    Material m = b.getType();
                    if (m == Material.AIR) continue;
                    if (m == Material.BED_BLOCK) continue;              // the bed is not part of the defence
                    if (m == Material.SANDSTONE) continue;              // the floor is not either
                    out.add(new Object[]{dx, dy, dz, m});
                }
            }
        }
        return out;
    }

    /** Everything a drill can be checked on from the console: the world, who is in it, and what is ticking in it. */
    private void debug(CommandSender to) {
        if (games.isEmpty()) { net.h2020.api.core.Messages.send(to, "practice.command.no-sessions"); return; }
        for (PracticeGame g : games.values()) {
            to.sendMessage(net.h2020.api.core.Messages.get("practice.practicemodule.state-world-players","p1",g.id(),"p2",g.drill().display(),"p3",g.state(),"p4",g.world().getName(),"p5",g.players().size()));
            if (g.drill() instanceof BedDefenceDrill) to.sendMessage("  drill: "
                    + ((BedDefenceDrill) g.drill()).debugLine());
            java.util.Map<String, Integer> counts = new java.util.TreeMap<String, Integer>();
            for (Entity e : g.world().getEntities()) {
                String n = e.getType().name();
                counts.put(n, counts.containsKey(n) ? counts.get(n) + 1 : 1);
            }
            to.sendMessage(net.h2020.api.core.Messages.get("practice.practicemodule.entities","p1",counts));
            for (Player p : g.players()) for (String line : g.drill().stats(g, p)) to.sendMessage(net.h2020.api.core.Messages.get("practice.command.debug-line", "player", p.getName(), "text", line));
            for (Player p : g.players()) to.sendMessage(net.h2020.api.core.Messages.get("practice.practicemodule.dead-hp-gm-at","p1",p.getName(),"p2",p.isDead(),"p3",Math.round(p.getHealth()),"p4",p.getGameMode(),"p5",p.getLocation().getBlockX(),"p6",p.getLocation().getBlockY(),"p7",p.getLocation().getBlockZ(),"p8",g.drill().hud(g, p)));
        }
    }

    private void stats(Player p) {
        PracticeGame g = byPlayer.get(p.getUniqueId());
        if (g == null) { net.h2020.api.core.Messages.send(p, "practice.practicemodule.you-are-not-in"); return; }
        p.sendMessage(ChatColor.AQUA + g.drill().display());
        for (String line : g.drill().stats(g, p)) p.sendMessage(net.h2020.api.core.Messages.get("practice.command.stat-line", "text", line));
    }

    // ---- listeners ----

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onAttack(org.bukkit.event.entity.EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player)) return;
        Player p = (Player) e.getDamager();
        PracticeGame g = byPlayer.get(p.getUniqueId());
        if (g == null || !g.world().equals(p.getWorld())) return;
        g.handleAttack(p, e.getEntity());
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player)) return;
        Player p = (Player) e.getEntity();
        PracticeGame g = byPlayer.get(p.getUniqueId());
        if (g == null || !g.world().equals(p.getWorld())) return;
        if (g.handleDamage(p, e)) e.setCancelled(true);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        PracticeGame g = byPlayer.get(e.getEntity().getUniqueId());
        if (g == null) return;
        e.setDeathMessage(null);
        e.getDrops().clear();
        g.handleDeath(e.getEntity());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        PracticeGame g = byPlayer.remove(e.getPlayer().getUniqueId());
        if (g != null) g.removePlayer(e.getPlayer());
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        PracticeGame g = byPlayer.get(e.getPlayer().getUniqueId());
        if (g == null || !g.world().equals(e.getBlock().getWorld())) return;
        g.drill().onPlace(g, e.getPlayer(), e.getBlock());
    }

    @EventHandler
    public void onShoot(EntityShootBowEvent e) {
        if (!(e.getEntity() instanceof Player)) return;
        Player p = (Player) e.getEntity();
        PracticeGame g = byPlayer.get(p.getUniqueId());
        if (g == null || !g.world().equals(p.getWorld())) return;
        if (g.drill() instanceof BowDrill) ((BowDrill) g.drill()).onShoot(g, p);
    }

    @EventHandler
    public void onArrow(ProjectileHitEvent e) {
        if (!(e.getEntity() instanceof Arrow)) return;
        Arrow arrow = (Arrow) e.getEntity();
        if (!(arrow.getShooter() instanceof Player)) return;
        Player p = (Player) arrow.getShooter();
        PracticeGame g = byPlayer.get(p.getUniqueId());
        if (g == null || !g.world().equals(arrow.getWorld())) return;
        if (!(g.drill() instanceof BowDrill)) return;
        // the armour stand the arrow passed through is the target; 1.8 reports the block, so find the nearest stand
        Entity hit = nearestTarget(arrow.getLocation());
        if (hit != null) ((BowDrill) g.drill()).onArrowHit(g, p, arrow, hit);
    }

    private Entity nearestTarget(Location at) {
        for (Entity e : at.getWorld().getNearbyEntities(at, 2.0, 2.5, 2.0)) {
            if (e instanceof org.bukkit.entity.ArmorStand) return e;
        }
        return null;
    }

    /** Tab completion: the drill names, then bots/leave/stats. */
    private static final class Tab implements org.bukkit.command.TabCompleter {
        @Override
        public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
            List<String> out = new ArrayList<String>();
            if (args.length == 1) {
                for (String s : new String[]{"fireball", "bow", "gamesense", "bridge", "bridge-diagonal", "leave", "stats"}) {
                    if (s.startsWith(args[0].toLowerCase(java.util.Locale.ROOT))) out.add(s);
                }
            } else if (args.length == 2) {
                out.add("bots");
            } else if (args.length == 3 && args[1].equalsIgnoreCase("bots")) {
                for (String s : new String[]{"0", "1", "2", "3", "4"}) out.add(s);
            }
            return out;
        }
    }

    /** Every game needs a template; a drill generates its own, so this is only the identity the manager needs. */
    private static final class PracticeMap implements MapTemplate {
        private final PracticeMode mode;
        private PracticeMap(PracticeMode mode) { this.mode = mode; }
        static PracticeMap of(PracticeMode mode) { return new PracticeMap(mode); }

        @Override public String name() { return mode.displayName(); }
        @Override public GameFamily family() { return GameFamily.PRACTICE; }
        @Override public List<Integer> supportedTeamCounts() { return java.util.Collections.singletonList(1); }
        @Override public List<org.bukkit.util.Vector> points(String key) { return java.util.Collections.emptyList(); }
        @Override public org.bukkit.util.Vector point(String key) { return null; }
        @Override public float yaw(String key, int index) { return 0f; }
        @Override public Map<String, List<org.bukkit.util.Vector>> allPoints() { return java.util.Collections.emptyMap(); }
        @Override public int buildLimitY() { return 256; }
        @Override public org.bukkit.util.Vector boundsMin() { return new org.bukkit.util.Vector(-64, 0, -64); }
        @Override public org.bukkit.util.Vector boundsMax() { return new org.bukkit.util.Vector(64, 256, 64); }
        @Override public World createInstance(String instanceWorldName) {
            throw new UnsupportedOperationException("a practice drill builds its own arena");
        }
        @Override public void destroyInstance(World world) { }
    }
}
