package net.h2020.bots;

import net.h2020.api.stats.CombatStats;
import net.h2020.api.bot.AimIntent;
import net.h2020.api.bot.BotProfile;
import net.h2020.api.bot.CheatProfile;
import net.h2020.bots.client.VirtualClient;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/** /bot - spawn and inspect bots (video tooling). */
final class BotCommand implements CommandExecutor, TabCompleter {
    private static final String P = ChatColor.GOLD + "[Bots] " + ChatColor.RESET;
    private static final List<String> SUBS = Arrays.asList("help", "spawn", "remove", "list", "cheat", "look", "tp", "goto",
            "say", "walk", "stop", "jump", "sneak", "aim", "attack", "aimpos", "left", "right", "slot", "click", "close", "open",
            "settings", "skill", "ml", "why", "counters", "pvp", "deaths", "diag", "defspeed", "neo", "navstats", "navtrace", "zest", "resist",
            "navfix", "navdebug", "swstats", "self", "menutest", "pltest", "shoptest");

    private final BotSettings settings;
    private BotSettingsMenu menu;

    private final JavaPlugin plugin;
    private final BotManager bots;
    private final Random rnd = new Random();

    BotCommand(JavaPlugin plugin, BotManager bots, BotSettings settings) {
        this.plugin = plugin;
        this.bots = bots;
        this.settings = settings;
        this.menu = new BotSettingsMenu(settings, bots);
    }

    /** One model's report must never hide the others: a formatting slip in one is logged, not fatal. */
    private static void safe(CommandSender sender, java.util.List<String> lines) {
        try {
            for (String l : lines) sender.sendMessage(l);
        } catch (Throwable t) {
            sender.sendMessage("§c[ml] report failed: " + t);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) { help(sender, label); return true; }
        String sub = args[0].toLowerCase(Locale.ROOT);
        try {
            switch (sub) {
                case "spawn": spawn(sender, args); break;
                case "remove": remove(sender, args); break;
                case "list": list(sender); break;
                case "cheat": cheat(sender, args); break;
                case "look": look(sender, args); break;
                case "navstats":
                    if (args.length > 1 && args[1].equalsIgnoreCase("reset")) { net.h2020.bots.ai.nav.NavStats.reset(); sender.sendMessage(P + "navstats reset"); }
                    for (String l : net.h2020.bots.ai.nav.NavStats.report()) sender.sendMessage(l);
                    break;
                case "swstats":
                    if (args.length > 1 && args[1].equalsIgnoreCase("reset")) { net.h2020.bots.ai.brain.SwStats.reset(); sender.sendMessage(P + "swstats reset"); }
                    if (args.length > 1 && args[1].equalsIgnoreCase("debug")) {
                        net.h2020.bots.ai.brain.SwStats.debug = args.length > 2 ? args[2].equalsIgnoreCase("on") : !net.h2020.bots.ai.brain.SwStats.debug;
                        sender.sendMessage(P + "swstats debug " + (net.h2020.bots.ai.brain.SwStats.debug ? "on" : "off"));
                    }
                    for (String l : net.h2020.bots.ai.brain.SwStats.report()) sender.sendMessage(l);
                    break;
                case "settings": {
                    if (!(sender instanceof Player)) throw new IllegalArgumentException("Settings can only be opened in game");
                    menu.open((Player) sender);
                    break;
                }
                case "skill": {
                    if (args.length < 2) throw new IllegalArgumentException("Usage: /" + label + " skill <0-1|default>");
                    double v;
                    if (args[1].equalsIgnoreCase("default") || args[1].equalsIgnoreCase("mix")) {
                        v = BotSettings.DEFAULT_SKILL;
                    } else {
                        try { v = Double.parseDouble(args[1]); }
                        catch (NumberFormatException e) { throw new IllegalArgumentException("A number between 0 and 1, or \"default\""); }
                        if (v < 0 || v > 1) throw new IllegalArgumentException("Skill must be between 0 and 1");
                    }
                    settings.averageSkill = v;
                    bots.setAverageSkill(v);
                    settings.save();
                    sender.sendMessage(P + "average skill " + (v < 0 ? "back to the 2020 lobby mix" : String.format("%.2f (%s)", v, settings.skillLabel()))
                            + ChatColor.GRAY + " - new bots only");
                    break;
                }
                case "self": {
                    if (!(sender instanceof Player)) { sender.sendMessage("In game only."); break; }
                    Player me = (Player) sender;
                    String holder = me.getOpenInventory() != null && me.getOpenInventory().getTopInventory() != null
                            && me.getOpenInventory().getTopInventory().getHolder() != null
                            ? me.getOpenInventory().getTopInventory().getHolder().getClass().getSimpleName() : "none";
                    net.h2020.api.game.Game g = net.h2020.api.H2020.find(net.h2020.api.game.GameManager.class) == null
                            ? null : net.h2020.api.H2020.find(net.h2020.api.game.GameManager.class).gameOf(me);
                    sender.sendMessage("§6[you] §f" + me.getName()
                            + " §7isBot=" + net.h2020.bots.ai.BotAI.isBotPlayer(me)
                            + " realChannel=" + (net.h2020.api.core.Players.realChannel(me) != null)
                            + " game=" + (g == null ? "-" : g.id())
                            + " topHolder=" + holder
                            + " hits=" + net.h2020.bots.ai.perception.PlayerStats.recentHits(me)
                            + " placed=" + net.h2020.bots.ai.perception.PlayerStats.recentPlacements(me));
                    break;
                }
                case "menutest": {
                    for (net.h2020.bots.ai.brain.BaseBrain b : net.h2020.bots.ai.BotAI.brains()) {
                        if (b.context() == null || b.context().player == null) continue;
                        try {
                            menu.open(b.context().player);
                            sender.sendMessage("§6[menu] §frendered for " + b.context().player.getName());
                        } catch (Throwable t) {
                            sender.sendMessage("§c[menu] failed: " + t);
                            t.printStackTrace();
                        }
                        break;
                    }
                    break;
                }
                case "pltest": {
                    int n = 0;
                    for (net.h2020.bots.ai.brain.BaseBrain b : net.h2020.bots.ai.BotAI.brains()) {
                        if (b.context() == null || b.context().player == null) continue;
                        org.bukkit.entity.Player me = b.context().player;
                        org.bukkit.Location at = net.h2020.bots.ai.perception.PlayerStats.lastPlace(me);
                        if (at == null) continue;
                        org.bukkit.entity.Player foe = null;
                        double bd = 10;
                        for (org.bukkit.entity.Player o : me.getWorld().getPlayers()) {
                            if (o == me || o.isDead()) continue;
                            double d = o.getLocation().distance(me.getLocation());
                            if (d < bd) { bd = d; foe = o; }
                        }
                        if (foe == null) continue;
                        net.h2020.bots.ai.ml.HumanPlacements.observe(me, foe, at);
                        n++;
                    }
                    sender.sendMessage("§6[pl] §fwatched " + n + " placements");
                    break;
                }
                case "shoptest": {
                    String nm = args.length > 1 ? args[1] : null;
                    for (net.h2020.bots.ai.brain.BaseBrain b : net.h2020.bots.ai.BotAI.brains()) {
                        if (b.context() == null || b.context().player == null) continue;
                        if (nm != null && !b.context().player.getName().equalsIgnoreCase(nm)) continue;
                        net.h2020.bots.ai.ml.ShopWatch.observed(b.context().player);
                        sender.sendMessage("§6[shop] §fwatched " + b.context().player.getName());
                    }
                    break;
                }
                case "why": {
                    String nm = args.length > 1 ? args[1] : null;
                    for (net.h2020.bots.ai.brain.BaseBrain b : net.h2020.bots.ai.BotAI.brains()) {
                        if (!(b instanceof net.h2020.bots.ai.brain.BedwarsBrain)) continue;
                        net.h2020.bots.ai.brain.BedwarsBrain bw = (net.h2020.bots.ai.brain.BedwarsBrain) b;
                        if (bw.context() == null || bw.context().player == null) continue;
                        if (nm != null && !bw.context().player.getName().equalsIgnoreCase(nm)) continue;
                        sender.sendMessage("§6[why] §f" + bw.context().player.getName() + " §7" + bw.whyString());
                    }
                    break;
                }
                case "defspeed": {
                    if (args.length > 1)
                        net.h2020.bots.ai.combat.CombatTuning.fastDefence =
                                args[1].equalsIgnoreCase("on") || args[1].equalsIgnoreCase("true");
                    sender.sendMessage(P + "fast defence " + (net.h2020.bots.ai.combat.CombatTuning.fastDefence ? "ON" : "OFF"));
                    break;
                }
                case "help": {
                    sender.sendMessage("§6Bot commands:");
                    sender.sendMessage("§7 spawn <name> [cheat] | remove <name|all> | list | settings | skill <0..1> | self <x>");
                    sender.sendMessage("§7 movement: goto <x> <y> <z> | walk <f> <s> | stop | jump | sneak | tp <player>");
                    sender.sendMessage("§7 aim and clicks: aim <player> | attack <player> | aimpos <x> <y> <z> | left [cps] | right | slot <n> | click | close | open");
                    sender.sendMessage("§7 combat: pvp [v1|v2|ab|reset|debug] | cheat <profile> | why <name> | counters | deaths [reset]");
                    sender.sendMessage("§7 bridging: diag on|off (diagonal stance) | defspeed on|off (fast bed defence) | neo (neo availability probe)");
                    sender.sendMessage("§7 learning: ml | navstats | navtrace <bot|off> | navfix <mask> | navdebug | swstats | menutest | pltest | shoptest");
                    sender.sendMessage("§7 chat: say <text>");
                    break;
                }
                case "deaths": {
                    for (String l : net.h2020.bots.ai.stats.DeathStats.report()) sender.sendMessage(l);
                    if (args.length > 1 && args[1].equalsIgnoreCase("reset")) net.h2020.bots.ai.stats.DeathStats.reset();
                    break;
                }
                case "diag": {
                    // live switch for the dedicated diagonal stance; saved with the rest of the bot settings
                    if (args.length > 1) {
                        settings.diagonalStance = args[1].equalsIgnoreCase("on") || args[1].equalsIgnoreCase("true");
                        settings.save();
                        settings.apply();
                        if (settings.diagonalStance) net.h2020.bots.ai.motor.BridgeTuning.resetParked();
                    }
                    sender.sendMessage(P + "diagonal stance " + (net.h2020.bots.ai.motor.BridgeTuning.diagonalStance ? "ON" : "OFF"));
                    break;
                }
                case "resist": {
                    boolean on = args.length > 1 ? args[1].equalsIgnoreCase("on") : !net.h2020.api.core.DemoFlags.resistanceOn;
                    net.h2020.api.core.DemoFlags.resistanceOn = on;
                    if (args.length > 2) net.h2020.api.core.DemoFlags.resistanceAmp = Math.max(0, Math.min(4, Integer.parseInt(args[2])));
                    sender.sendMessage("§7resistance " + (on ? "on (amp " + net.h2020.api.core.DemoFlags.resistanceAmp + ")" : "off"));
                    return true;
                }
                case "zest": {
                    String zsub = args.length > 1 ? args[1].toLowerCase(java.util.Locale.ROOT) : "";
                    if (zsub.equals("on") || zsub.equals("off")) {
                        net.h2020.bots.ai.combat.ZestTap.setEnabled(zsub.equals("on"));
                    } else if (args.length > 2) {
                        int v = Math.max(0, Integer.parseInt(args[2]));
                        switch (zsub) {
                            case "burstbefore": net.h2020.bots.ai.combat.ZestTap.burstBefore = v; break;
                            case "burstafter": net.h2020.bots.ai.combat.ZestTap.burstAfter = v; break;
                            case "wtap": net.h2020.bots.ai.combat.ZestTap.wtapTicks = v; break;
                            case "wtaplead": net.h2020.bots.ai.combat.ZestTap.wtapLead = v; break;
                            case "strafe": net.h2020.bots.ai.combat.ZestTap.strafeTicks = v; break;
                            case "every": net.h2020.bots.ai.combat.ZestTap.every = v; break;
                            case "immunity": net.h2020.bots.ai.combat.ZestTap.immunityTicks = v; break;
                            default: sender.sendMessage("§cunknown zest key " + zsub); return true;
                        }
                    }
                    sender.sendMessage("§7" + net.h2020.bots.ai.combat.ZestTap.describe());
                    return true;
                }
                case "counters": {
                    for (String l : net.h2020.api.stats.CombatStats.dump()) sender.sendMessage(l);
                    break;
                }
                case "ml": {
                    String a = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "stats";
                    if (a.equals("placeoutcome")) {
                        // headless attribution check (works from the console: no bot and no fight needed)
                        sender.sendMessage(net.h2020.bots.ai.ml.PlacementPolicy.selftest());
                        return true;
                    }
                    if (a.equals("bots")) {
                        boolean on = args.length > 2 && args[2].equalsIgnoreCase("on");
                        net.h2020.bots.ai.ml.MlSettings.setTrainFromBots(on);
                        sender.sendMessage("§6[ml] §ebot training " + (on ? "§aenabled" : "§cdisabled"));
                        break;
                    }
                    if (a.equals("reset")) {
                        net.h2020.bots.ai.ml.FightModel.reset();
                        net.h2020.bots.ai.ml.PlacementPolicy.reset();
                        net.h2020.bots.ai.ml.AimPolicy.reset();
                        net.h2020.bots.ai.ml.FightFlow.reset();
                        net.h2020.bots.ai.ml.DuelPolicy.reset();
                        net.h2020.bots.ai.ml.NextOpponent.reset();
                        net.h2020.bots.ai.ml.MacroPolicy.reset();
                        net.h2020.bots.ai.ml.BehaviourModel.reset();
                        net.h2020.bots.ai.ml.QuestionBook.reset();
                        net.h2020.bots.ai.ml.ServerQuestions.clear();
                        net.h2020.bots.ai.ml.IntentModel.reset();
                        net.h2020.bots.ai.ml.ClassicalChecks.reset();
                        net.h2020.bots.ai.ml.ShopWatch.reset();
                        net.h2020.bots.ai.ml.BotLabeler.reset();
                        net.h2020.bots.ai.ml.CombatArbiter.reset();
                        net.h2020.bots.ai.ml.HumanPlacements.reset();
                        net.h2020.bots.ai.ml.ShopModel.reset();
                        net.h2020.bots.ai.ml.RoleModel.reset();
                        sender.sendMessage(P + "fight models reset (all four)");
                    }
                    safe(sender, net.h2020.bots.ai.ml.FightModel.report());
                    safe(sender, net.h2020.bots.ai.ml.PlacementPolicy.report());
                    safe(sender, net.h2020.bots.ai.ml.AimPolicy.report());
                    safe(sender, net.h2020.bots.ai.ml.FightFlow.report());
                    safe(sender, net.h2020.bots.ai.ml.DuelPolicy.report());
                    safe(sender, net.h2020.bots.ai.ml.NextOpponent.report());
                    safe(sender, net.h2020.bots.ai.ml.MacroPolicy.report());
                    safe(sender, net.h2020.bots.ai.ml.HumanFights.report());
                    safe(sender, net.h2020.bots.ai.ml.BehaviourModel.report());
                    safe(sender, net.h2020.bots.ai.ml.QuestionBook.report());
                    safe(sender, net.h2020.bots.ai.ml.ServerQuestions.report());
                    safe(sender, net.h2020.bots.ai.ml.IntentModel.report());
                    safe(sender, net.h2020.bots.ai.ml.ClassicalChecks.report());
                    safe(sender, net.h2020.bots.ai.ml.ShopWatch.report());
                    safe(sender, net.h2020.bots.ai.ml.BotLabeler.report());
                    sender.sendMessage(net.h2020.bots.ai.ml.BehaviourModel.provenance());
                    safe(sender, net.h2020.bots.ai.ml.MlSettings.report());
                    sender.sendMessage(net.h2020.bots.ai.motor.Bridger.launchReport());
                    safe(sender, net.h2020.bots.ai.ml.CombatArbiter.report());
                    safe(sender, net.h2020.bots.ai.ml.HumanPlacements.report());
                    safe(sender, net.h2020.bots.ai.ml.ShopModel.report());
                    safe(sender, net.h2020.bots.ai.ml.RoleModel.report());
                    safe(sender, net.h2020.bots.ai.brain.bedwars.ScoutModel.report());
                    break;
                }
                case "pvp": {
                    String a = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "stats";
                    if (a.equals("v1") || a.equals("v2") || a.equals("ab")) { net.h2020.api.stats.CombatStats.setMode(a); net.h2020.api.stats.CombatStats.save(); }
                    else if (a.equals("reset")) net.h2020.api.stats.CombatStats.reset();
                    else if (a.equals("debug")) net.h2020.api.stats.CombatStats.debug = !net.h2020.api.stats.CombatStats.debug;
                    for (String l : net.h2020.api.stats.CombatStats.report()) sender.sendMessage(l);
                    break;
                }
                case "neo": {
                    // probe: for every bot, is a neo geometrically available on any heading, and does it carry the kit
                    int found = 0, kit = 0, bots2 = 0;
                    for (FakeBot b : bots.all()) {
                        net.h2020.api.bot.BotBrain br = b.brain();
                        if (!(br instanceof net.h2020.bots.ai.brain.BaseBrain)) continue;
                        net.h2020.bots.ai.BotContext bc = ((net.h2020.bots.ai.brain.BaseBrain) br).context();
                        if (bc == null) continue;
                        bots2++;
                        if (net.h2020.bots.ai.inv.Inv.has(bc.player, net.h2020.bots.ai.inv.Inv.type(org.bukkit.Material.LADDER))) kit++;
                        if (net.h2020.bots.ai.inv.Inv.has(bc.player, net.h2020.bots.ai.inv.Inv.type(org.bukkit.Material.FIREBALL))) kit++;
                        org.bukkit.Location at = bc.pos();
                        for (int i = 0; i < 8; i++) {
                            double a = i * Math.PI / 4;
                            org.bukkit.Location to = at.clone().add(Math.cos(a) * 4, 0, Math.sin(a) * 4);
                            if (net.h2020.bots.ai.nav.NeoPlanner.candidate(bc, at, to)) { found++; break; }
                        }
                    }
                    sender.sendMessage(P + "neo probe: " + found + "/" + bots2 + " bots have a neo on some heading; " + kit + " item-kit hits");
                    break;
                }
                case "navtrace":
                    net.h2020.bots.ai.nav.NavStats.trace = args.length > 1 && !args[1].equalsIgnoreCase("off") ? args[1] : null;
                    sender.sendMessage(P + "navtrace " + net.h2020.bots.ai.nav.NavStats.trace);
                    break;
                case "navfix":
                    if (args.length > 1) {
                        try { net.h2020.bots.ai.nav.NavStats.fix = Integer.decode(args[1]); }
                        catch (NumberFormatException e) { throw new IllegalArgumentException("navfix <mask>: 1 start, 2 headroom, 4 boxes, 8 stairs, 16 gui, 32 lookahead, 64 water"); }
                    }
                    sender.sendMessage(P + "navfix mask " + net.h2020.bots.ai.nav.NavStats.fix);
                    break;
                case "navdebug":
                    net.h2020.bots.ai.nav.NavStats.debug = args.length > 1 ? args[1].equalsIgnoreCase("on") : !net.h2020.bots.ai.nav.NavStats.debug;
                    sender.sendMessage(P + "navdebug " + (net.h2020.bots.ai.nav.NavStats.debug ? "on" : "off"));
                    break;
                case "tp": tp(sender, args); break;
                case "goto": goTo(sender, args); break;
                case "say": say(sender, args); break;
                case "walk": walk(sender, args); break;
                case "stop": stop(sender, args); break;
                case "jump": toggle(sender, args, "jump"); break;
                case "sneak": toggle(sender, args, "sneak"); break;
                case "aim": aim(sender, args, false); break;
                case "attack": aim(sender, args, true); break;
                case "aimpos": aimPos(sender, args); break;
                case "left": left(sender, args); break;
                case "right": right(sender, args); break;
                case "slot": slot(sender, args); break;
                case "click": {
                    if (args.length < 5) throw new IllegalArgumentException("Usage: /bot click <name> <rawSlot> <button> <mode>");
                    try {
                        need(args[1]).controller().clickWindow(Integer.parseInt(args[2]), Integer.parseInt(args[3]), Integer.parseInt(args[4]));
                    } catch (NumberFormatException e) { throw new IllegalArgumentException("Numbers expected"); }
                    break;
                }
                case "close":
                    if (args.length < 2) throw new IllegalArgumentException("Usage: /bot close <name>");
                    need(args[1]).controller().closeWindow();
                    break;
                case "open": {
                    if (args.length < 5) throw new IllegalArgumentException("Usage: /bot open <name> <x> <y> <z>");
                    FakeBot b = need(args[1]);
                    try {
                        b.controller().openContainer(b.player().getWorld().getBlockAt(Integer.parseInt(args[2]), Integer.parseInt(args[3]), Integer.parseInt(args[4])));
                    } catch (NumberFormatException e) { throw new IllegalArgumentException("Numbers expected"); }
                    break;
                }
                default: help(sender, label);
            }
        } catch (IllegalArgumentException e) {
            sender.sendMessage(P + ChatColor.RED + e.getMessage());
        }
        return true;
    }

    private void help(CommandSender s, String label) {
        s.sendMessage(P + ChatColor.YELLOW + "/" + label + " spawn [name|#count] [cheat]" + ChatColor.GRAY + " - spawn at you");
        s.sendMessage(P + ChatColor.YELLOW + "/" + label + " remove <name|all>" + ChatColor.GRAY + " - disconnect bot(s)");
        s.sendMessage(P + ChatColor.YELLOW + "/" + label + " list" + ChatColor.GRAY + " - online bots");
        s.sendMessage(P + ChatColor.YELLOW + "/" + label + " cheat <name|all> <" + cheatNames() + ">");
        s.sendMessage(P + ChatColor.YELLOW + "/" + label + " look <name>" + ChatColor.GRAY + " - aim/physics debug");
        s.sendMessage(P + ChatColor.YELLOW + "/" + label + " ml [reset]" + ChatColor.GRAY + " - the learned fight model (samples, accuracy, weights)");
        s.sendMessage(P + ChatColor.YELLOW + "/" + label + " settings" + ChatColor.GRAY + " - bot settings menu (average skill, behaviours)");
        s.sendMessage(P + ChatColor.YELLOW + "/" + label + " skill <0-1|default>" + ChatColor.GRAY + " - average skill of newly spawned bots");
        s.sendMessage(P + ChatColor.YELLOW + "/" + label + " navstats [reset] | navdebug [on|off]" + ChatColor.GRAY + " - stuck/path-failure counters per map");
        s.sendMessage(P + ChatColor.YELLOW + "/" + label + " swstats [reset|debug [on|off]]" + ChatColor.GRAY + " - SkyWars plans, fight origins, cage-drop confusion per map");
        s.sendMessage(P + ChatColor.YELLOW + "/" + label + " tp <name|all> [player]" + ChatColor.GRAY + " - bring bot(s)");
        s.sendMessage(P + ChatColor.YELLOW + "/" + label + " goto <name>" + ChatColor.GRAY + " - teleport to bot");
        s.sendMessage(P + ChatColor.YELLOW + "/" + label + " say <name> <message>");
        s.sendMessage(P + ChatColor.YELLOW + "/" + label + " walk <name> <fwd> <strafe> [sprint] | stop | jump | sneak <name>");
        s.sendMessage(P + ChatColor.YELLOW + "/" + label + " aim|attack <name> <player>");
        s.sendMessage(P + ChatColor.YELLOW + "/" + label + " aimpos <name> <x> <y> <z> | left <name> [hold|cps|click|off] | right <name> [click|hold|off] | slot <name> <0-8>");
    }

    private static String cheatNames() {
        StringBuilder b = new StringBuilder();
        for (CheatProfile c : CheatProfile.values()) {
            if (b.length() > 0) b.append('|');
            b.append(c.name().toLowerCase(Locale.ROOT));
        }
        return b.toString();
    }

    private static CheatProfile parseCheat(String s) {
        try {
            return CheatProfile.valueOf(s.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown cheat profile '" + s + "' (" + cheatNames() + ")");
        }
    }

    private Location origin(CommandSender s) {
        if (s instanceof Player) return ((Player) s).getLocation();
        return Bukkit.getWorlds().get(0).getSpawnLocation();
    }

    private FakeBot need(String name) {
        FakeBot b = bots.byName(name);
        if (b == null) throw new IllegalArgumentException("No bot named " + name);
        return b;
    }

    private List<FakeBot> targets(String nameOrAll) {
        if (nameOrAll.equalsIgnoreCase("all")) return bots.all();
        return Collections.singletonList(need(nameOrAll));
    }

    private void spawn(final CommandSender s, String[] args) {
        String nameArg = args.length > 1 ? args[1] : "random";
        final CheatProfile cheat = args.length > 2 ? parseCheat(args[2]) : CheatProfile.NONE;
        final Location base = origin(s);
        if (nameArg.startsWith("#")) {
            final int count;
            try { count = Math.max(1, Math.min(200, Integer.parseInt(nameArg.substring(1)))); }
            catch (NumberFormatException e) { throw new IllegalArgumentException("Bad count " + nameArg); }
            s.sendMessage(P + ChatColor.GREEN + "Spawning " + count + " bots...");
            new BukkitRunnable() {
                int left = count;
                @Override public void run() {
                    if (left-- <= 0 || !plugin.isEnabled()) { cancel(); return; }
                    Location at = base.clone().add(rnd.nextGaussian() * 2.0D, 0.0D, rnd.nextGaussian() * 2.0D);
                    at.setY(base.getY());
                    at.setYaw((float) (rnd.nextDouble() * 360.0D - 180.0D));
                    at.setPitch(0.0F);
                    try { bots.spawn(bots.randomProfile(cheat), at); }
                    catch (RuntimeException e) { s.sendMessage(P + ChatColor.RED + "Spawn failed: " + e.getMessage()); }
                }
            }.runTaskTimer(plugin, 1L, 3L);
            return;
        }
        BotProfile profile;
        if (nameArg.equalsIgnoreCase("random") || nameArg.equals("-")) profile = bots.randomProfile(cheat);
        else {
            if (!nameArg.matches("^[A-Za-z0-9_]{3,16}$")) throw new IllegalArgumentException("Invalid username " + nameArg);
            if (!bots.nameFree(nameArg)) throw new IllegalArgumentException(nameArg + " is already online");
            profile = bots.profileNamed(nameArg, cheat);
        }
        bots.spawn(profile, base);
        s.sendMessage(P + ChatColor.GREEN + "Spawned " + ChatColor.WHITE + profile.name + ChatColor.GRAY + " (" + profile.rank
                + ", lvl " + profile.networkLevel + ", " + profile.pingMs + "ms, skill " + String.format("%.2f", profile.skill.overall)
                + ", cps " + String.format("%.1f", profile.skill.cps) + ", " + profile.skill.bridgeStyle
                + (cheat != CheatProfile.NONE ? ", " + ChatColor.RED + cheat : "") + ChatColor.GRAY + ")");
    }

    private void remove(CommandSender s, String[] args) {
        if (args.length < 2) throw new IllegalArgumentException("Usage: /bot remove <name|all>");
        List<FakeBot> list = targets(args[1]);
        for (FakeBot b : list) b.remove();
        s.sendMessage(P + ChatColor.GREEN + "Removed " + list.size() + " bot(s).");
    }

    private void list(CommandSender s) {
        List<FakeBot> all = bots.all();
        s.sendMessage(P + ChatColor.YELLOW + all.size() + " bot(s) online" + ChatColor.GRAY + " (skins in pool: " + bots.skins().size() + ")");
        StringBuilder line = new StringBuilder();
        for (FakeBot b : all) {
            BotProfile p = b.profile();
            if (line.length() > 0) line.append(ChatColor.GRAY).append(", ");
            line.append(p.cheat != CheatProfile.NONE ? ChatColor.RED : ChatColor.WHITE).append(p.name)
                    .append(ChatColor.DARK_GRAY).append('[').append(b.brain() == null ? "idle" : b.brain().toString())
                    .append(']');
            if (line.length() > 200) { s.sendMessage(line.toString()); line.setLength(0); }
        }
        if (line.length() > 0) s.sendMessage(line.toString());
    }

    private void cheat(CommandSender s, String[] args) {
        if (args.length < 3) throw new IllegalArgumentException("Usage: /bot cheat <name|all> <profile>");
        CheatProfile c = parseCheat(args[2]);
        List<FakeBot> list = targets(args[1]);
        for (FakeBot b : list) b.setCheat(c);
        s.sendMessage(P + ChatColor.GREEN + "Cheat profile " + ChatColor.RED + c + ChatColor.GREEN + " set on " + list.size() + " bot(s).");
    }

    private void look(CommandSender s, String[] args) {
        if (args.length < 2) throw new IllegalArgumentException("Usage: /bot look <name>");
        FakeBot b = need(args[1]);
        BotProfile p = b.profile();
        s.sendMessage(P + ChatColor.YELLOW + p.name + ChatColor.GRAY + " rank=" + p.rank + " skill=" + String.format("%.2f", p.skill.overall)
                + " aim=" + String.format("%.2f", p.skill.aim) + " react=" + p.skill.reactionMs + "ms sens=" + String.format("%.2f", p.skill.sensitivity)
                + " brain=" + (b.brain() == null ? "idle" : b.brain().toString()));
        for (String line : b.client.debug().split("\n")) s.sendMessage(ChatColor.GRAY + line);
    }

    private void tp(CommandSender s, String[] args) {
        if (args.length < 2) throw new IllegalArgumentException("Usage: /bot tp <name|all> [player]");
        Location to;
        if (args.length > 2) {
            Player t = Bukkit.getPlayerExact(args[2]);
            if (t == null) throw new IllegalArgumentException("Player " + args[2] + " not online");
            to = t.getLocation();
        } else {
            to = origin(s);
        }
        List<FakeBot> list = targets(args[1]);
        for (FakeBot b : list) b.player().teleport(to);
        s.sendMessage(P + ChatColor.GREEN + "Teleported " + list.size() + " bot(s).");
    }

    private void goTo(CommandSender s, String[] args) {
        if (!(s instanceof Player)) throw new IllegalArgumentException("Players only");
        if (args.length < 2) throw new IllegalArgumentException("Usage: /bot goto <name>");
        ((Player) s).teleport(need(args[1]).player().getLocation());
    }

    private void say(CommandSender s, String[] args) {
        if (args.length < 3) throw new IllegalArgumentException("Usage: /bot say <name> <message>");
        FakeBot b = need(args[1]);
        String msg = join(args, 2);
        if (msg.startsWith("/")) b.controller().command(msg);
        else b.controller().chat(msg);
    }

    private void walk(CommandSender s, String[] args) {
        if (args.length < 4) throw new IllegalArgumentException("Usage: /bot walk <name> <forward -1..1> <strafe -1..1> [sprint]");
        float f, st;
        try { f = Float.parseFloat(args[2]); st = Float.parseFloat(args[3]); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("Numbers expected"); }
        for (FakeBot b : targets(args[1])) {
            b.setBrain(null);
            b.controller().setMovement(f, st);
            b.controller().setSprinting(args.length > 4 && Boolean.parseBoolean(args[4]));
        }
    }

    private void stop(CommandSender s, String[] args) {
        if (args.length < 2) throw new IllegalArgumentException("Usage: /bot stop <name|all>");
        for (FakeBot b : targets(args[1])) {
            b.setBrain(null);
            VirtualClient c = b.client;
            c.setMovement(0, 0);
            c.setJumping(false);
            c.setSneaking(false);
            c.setSprinting(false);
            c.setLeftHeld(false, 0);
            c.setRightHeld(false);
            c.clearAim();
        }
    }

    private void toggle(CommandSender s, String[] args, String what) {
        if (args.length < 2) throw new IllegalArgumentException("Usage: /bot " + what + " <name>");
        FakeBot b = need(args[1]);
        if (what.equals("jump")) {
            boolean on = !jumpState(b);
            b.client.setJumping(on);
            jumpFlags.put(b, on);
        } else {
            boolean on = !sneakState(b);
            b.client.setSneaking(on);
            sneakFlags.put(b, on);
        }
    }

    private final java.util.WeakHashMap<FakeBot, Boolean> jumpFlags = new java.util.WeakHashMap<FakeBot, Boolean>();
    private final java.util.WeakHashMap<FakeBot, Boolean> sneakFlags = new java.util.WeakHashMap<FakeBot, Boolean>();
    private boolean jumpState(FakeBot b) { Boolean v = jumpFlags.get(b); return v != null && v; }
    private boolean sneakState(FakeBot b) { Boolean v = sneakFlags.get(b); return v != null && v; }

    private void aim(CommandSender s, String[] args, boolean attack) {
        if (args.length < 3) throw new IllegalArgumentException("Usage: /bot " + (attack ? "attack" : "aim") + " <name> <player>");
        FakeBot b = need(args[1]);
        Player t = Bukkit.getPlayerExact(args[2]);
        if (t == null) throw new IllegalArgumentException("Player " + args[2] + " not online");
        b.setBrain(null);
        b.controller().aimAt(t, attack ? AimIntent.COMBAT : AimIntent.PRECISE);
        if (attack) b.controller().setLeftHeld(true, b.profile().skill.cps);
    }

    private void aimPos(CommandSender s, String[] args) {
        if (args.length < 5) throw new IllegalArgumentException("Usage: /bot aimpos <name> <x> <y> <z>");
        FakeBot b = need(args[1]);
        double x, y, z;
        try { x = Double.parseDouble(args[2]); y = Double.parseDouble(args[3]); z = Double.parseDouble(args[4]); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("Numbers expected"); }
        b.setBrain(null);
        b.controller().aimAt(new Location(b.player().getWorld(), x, y, z), AimIntent.PRECISE);
    }

    private void left(CommandSender s, String[] args) {
        if (args.length < 2) throw new IllegalArgumentException("Usage: /bot left <name> [hold|cps|click|off]");
        FakeBot b = need(args[1]);
        String mode = args.length > 2 ? args[2] : "hold";
        if (mode.equalsIgnoreCase("off")) b.controller().setLeftHeld(false, 0);
        else if (mode.equalsIgnoreCase("click")) b.controller().leftClick();
        else if (mode.equalsIgnoreCase("hold")) b.controller().setLeftHeld(true, 0);
        else {
            try { b.controller().setLeftHeld(true, Double.parseDouble(mode)); }
            catch (NumberFormatException e) { throw new IllegalArgumentException("cps number, off, click or hold"); }
        }
    }

    private void right(CommandSender s, String[] args) {
        if (args.length < 2) throw new IllegalArgumentException("Usage: /bot right <name> [click|hold|off]");
        FakeBot b = need(args[1]);
        String mode = args.length > 2 ? args[2] : "click";
        if (mode.equalsIgnoreCase("hold")) b.controller().setRightHeld(true);
        else if (mode.equalsIgnoreCase("off")) b.controller().setRightHeld(false);
        else b.controller().rightClick();
    }

    private void slot(CommandSender s, String[] args) {
        if (args.length < 3) throw new IllegalArgumentException("Usage: /bot slot <name> <0-8>");
        try { need(args[1]).controller().selectHotbar(Integer.parseInt(args[2])); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("Slot 0-8"); }
    }

    private static String join(String[] a, int from) {
        StringBuilder b = new StringBuilder();
        for (int i = from; i < a.length; i++) {
            if (i > from) b.append(' ');
            b.append(a[i]);
        }
        return b.toString();
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<String>();
        if (args.length == 1) {
            for (String s : SUBS) if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(s);
        } else if (args.length == 2 && args[0].equalsIgnoreCase("diag")) {
            for (String s : new String[]{"on", "off"}) if (s.startsWith(args[1].toLowerCase(Locale.ROOT))) out.add(s);
        } else if (args.length == 2 && !args[0].equalsIgnoreCase("spawn")) {
            if ("all".startsWith(args[1].toLowerCase(Locale.ROOT))) out.add("all");
            for (FakeBot b : bots.all()) {
                String n = b.profile().name;
                if (n.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))) out.add(n);
            }
        } else if (args.length == 3 && (args[0].equalsIgnoreCase("cheat") || args[0].equalsIgnoreCase("spawn"))) {
            for (CheatProfile c : CheatProfile.values()) {
                String n = c.name().toLowerCase(Locale.ROOT);
                if (n.startsWith(args[2].toLowerCase(Locale.ROOT))) out.add(n);
            }
        } else if (args.length == 3) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(args[2].toLowerCase(Locale.ROOT))) out.add(p.getName());
            }
        }
        return out;
    }
}
