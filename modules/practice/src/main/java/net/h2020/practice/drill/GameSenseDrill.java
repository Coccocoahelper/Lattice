package net.h2020.practice.drill;

import net.h2020.practice.PracticeArena;
import net.h2020.practice.PracticeGame;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Game sense: the decisions around a fight rather than the aim inside one.
 *
 * <p>Each round is a situation that actually decides a Bed Wars game - a bed trade, a 2v1, a height fight, a
 * resource call - with three options and a timer. The answer is graded and explained, because the point is the reason
 * and not the letter: a player who guesses right for the wrong reason will lose the next one.
 *
 * <p>Answers are clickable so it works the same for a player and for anything else that can send a command.
 */
public final class GameSenseDrill implements Drill {

    /** prompt, three options, the correct index, and why. */
    private static final Object[][] SCENARIOS = {
            {"Your bed is gone. You are at 4 hearts, an enemy at full health is bridging to your island 12 blocks away.\n"
                    + "You have 12 iron and a wooden sword.",
                    new String[]{"Meet them on the bridge and knock them off",
                            "Buy iron armour and a stone sword first, then fight at your base",
                            "Pillar up three blocks and wait"},
                    1, "With no bed, a lost fight is the game. Twelve iron is a stone sword and armour - the fight you "
                    + "want is the one where you have gear and they have to cross to you."},

            {"You are at mid with 6 diamonds and an enemy is 20 blocks away, also at mid.\n"
                    + "Your bed is alive but both teammates are down.",
                    new String[]{"Take the diamonds and run home",
                            "Fight them for the rest of mid",
                            "Drop the diamonds and rush their base"},
                    0, "Diamonds already in your inventory are worth more than mid. Dying at mid hands them over and "
                    + "leaves your bed undefended - the trade is never worth it with a bed to protect."},

            {"An enemy is above you on their pillar, 4 blocks up, and you are both full health.",
                    new String[]{"Jump and hit them",
                            "Build up beside them so you fight at the same height",
                            "Break their pillar from below"},
                    1, "Hitting up costs you reach and knockback. One block beside them removes the advantage "
                    + "entirely; breaking from below drops them onto you and usually means a trade."},

            {"You are 3 blocks from their bed, they are respawning with 2 seconds left, and you have 3 blocks.",
                    new String[]{"Keep breaking the bed",
                            "Place two blocks of cover and finish the bed from behind it",
                            "Fall back and come back with more blocks"},
                    1, "The bed is worth more than the fight. Cover costs two blocks and buys the last second; "
                    + "breaking in the open is how a clean break turns into a death."},

            {"First 30 seconds: your team has no one at mid, and the enemy team across from you has already sent two.",
                    new String[]{"Go to mid yourself",
                            "Hold your base and take your generator",
                            "Rush the enemy base immediately"},
                    1, "Their two at mid are two not defending. A rushed attack on an empty base is worth more than a "
                    + "diamond race you are already losing, and your generator pays for it."},

            {"You are being fireballed from 20 blocks while bridging to mid. Two fireballs, both missing so far.",
                    new String[]{"Keep bridging and hope",
                            "Stop, turn, and knock the next one back with your sword",
                            "Jump into the void to reset"},
                    1, "The third one is aimed at your feet. Turning to deflect is the only answer that does not "
                    + "gamble the bridge - and a deflected fireball usually hits them or their bridge."},

            {"Your teammate is fighting 2v1 at your base and you are 15 blocks away with a bow.",
                    new String[]{"Shoot into the fight",
                            "Bridge over and join the melee",
                            "Stay and keep collecting resources"},
                    0, "At 15 blocks with a bow you add damage without giving them a second target to hit, and a bow "
                    + "shot does not knock your teammate off their own bridge."},

            {"You have 24 gold, no bed, and the enemy has full diamond armour.",
                    new String[]{"Buy diamond armour",
                            "Buy a bow and hold your island",
                            "Buy potions and a pearl and take the fight to their bed"},
                    2, "With no bed, time is losing. Armour defends a base you no longer have; speed, jump and a pearl "
                    + "are the only way to reach a geared team and end it."},

            {"An enemy is 8 blocks away, on the same bridge, both of you at full health, no blocks in your hotbar.",
                    new String[]{"Fight them on the bridge",
                            "Turn and run back to your base",
                            "Jump to the side and fight on the island"},
                    0, "You are both committed and they have to come to you. Turning your back on a bridge fight gives "
                    + "them the first hit and the knockback angle."},

            {"Your bed is alive. You die to a 2v1 at mid with 8 diamonds in your inventory.",
                    new String[]{"That trade was fine",
                            "You should have banked the diamonds and gone back for the fight",
                            "You should have gone mid alone earlier"},
                    1, "A death costs the diamonds, the position and the respawn time. Banking first turns the same "
                    + "fight into one you can afford to lose."},
    };

    private static final int ROUND_TICKS = 240;    // 12 s to answer

    private PracticeArena arena;
    private Location spawn;
    private final Map<UUID, int[]> score = new HashMap<UUID, int[]>();      // {right, wrong, streak, best}
    private final Map<UUID, Integer> question = new HashMap<UUID, Integer>();
    private final Map<UUID, Integer> deadline = new HashMap<UUID, Integer>();
    private final Map<UUID, Integer> asked = new HashMap<UUID, Integer>();
    private int served;
    private int clock;

    @Override public String id() { return "gamesense"; }
    @Override public String display() { return "Game Sense"; }

    @Override public String hint() {
        return "a situation every 12 s - click the call you would make";
    }

    @Override
    public void build(PracticeArena a) {
        this.arena = a;
        a.platform(0, 64, 0, 4, Material.SANDSTONE);
        a.rim(0, 64, 0, 4, 2, Material.SMOOTH_BRICK);
        this.spawn = a.at(0.5, 65, 0.5, 0f);
    }

    @Override public Location spawn() { return spawn; }
    @Override public Location spectatorSpawn() { return arena.at(0.5, 70, 6.5, 0f); }

    @Override public void kit(Player p) { p.getInventory().setItem(4, new ItemStack(Material.BOOK)); }

    @Override
    public void start(PracticeGame g) { clock = 0; }

    @Override
    public void tick(PracticeGame g) {
        clock++;
        for (Player p : g.alivePlayers()) {
            Integer due = deadline.get(p.getUniqueId());
            // asked tick is stored as clock + ROUND_TICKS; a missing stamp is the only signal to ask
            if (due == null) { ask(p, g); continue; }
            if (clock >= due) {
                int[] s = row(p);
                int idx = question.get(p.getUniqueId());
                Object[] sc = SCENARIOS[idx];
                s[1]++;
                s[2] = 0;
                p.sendMessage(net.h2020.api.core.Messages.get("practice.gamesensedrill.time-the-call-was","p1",((int) sc[2] + 1),"p2",((String[]) sc[1])[(int) sc[2]]));
                p.sendMessage(net.h2020.api.core.Messages.get("practice.sense.explanation", "why", sc[3]));
                p.playSound(p.getLocation(), Sound.ITEM_BREAK, 0.5f, 0.8f);
                ask(p, g);
            }
        }
    }

    private void ask(Player p, PracticeGame g) {
        served++;
        int idx = (int) (Math.random() * SCENARIOS.length);
        question.put(p.getUniqueId(), idx);
        deadline.put(p.getUniqueId(), clock + ROUND_TICKS);
        asked.put(p.getUniqueId(), clock);
        Object[] sc = SCENARIOS[idx];
        p.sendMessage("");
        p.sendMessage(net.h2020.api.core.Messages.get("practice.sense.scenario"));
        for (String line : ((String) sc[0]).split("\n")) p.sendMessage(ChatColor.GRAY + line);
        String[] opts = (String[]) sc[1];
        for (int i = 0; i < opts.length; i++) sendClickable(p, " [" + (i + 1) + "] ", opts[i], "/practice answer " + (i + 1));
    }

    /**
     * The clickable line. Sent as its own message because a click event needs the whole component, and mixed into the
     * chat rather than into an inventory so the answer can be given at any moment.
     */
    private void sendClickable(Player p, String prefix, String option, String command) {
        try {
            net.minecraft.server.v1_8_R3.ChatComponentText text =
                    new net.minecraft.server.v1_8_R3.ChatComponentText(
                            ChatColor.WHITE + prefix + ChatColor.YELLOW + option);
            text.getChatModifier().setChatClickable(new net.minecraft.server.v1_8_R3.ChatClickable(
                    net.minecraft.server.v1_8_R3.ChatClickable.EnumClickAction.RUN_COMMAND, command));
            text.getChatModifier().setChatHoverable(new net.minecraft.server.v1_8_R3.ChatHoverable(
                    net.minecraft.server.v1_8_R3.ChatHoverable.EnumHoverAction.SHOW_TEXT,
                    new net.minecraft.server.v1_8_R3.ChatComponentText(ChatColor.GRAY + "Answer: " + option)));
            ((org.bukkit.craftbukkit.v1_8_R3.entity.CraftPlayer) p).getHandle().playerConnection.sendPacket(
                    new net.minecraft.server.v1_8_R3.PacketPlayOutChat(text));
        } catch (Throwable t) {
            p.sendMessage(ChatColor.WHITE + prefix + ChatColor.YELLOW + option);
        }
    }

    /** /practice answer &lt;n&gt; - graded here, because the drill is the only thing that knows the right call. */
    public void answer(PracticeGame g, Player p, int choice) {
        if (!g.players().contains(p)) return;
        // one answer per question: a second click in the same round is ignored rather than scoring twice
        Integer askedAt = asked.get(p.getUniqueId());
        Integer due = deadline.get(p.getUniqueId());
        if (askedAt == null || due == null || clock < askedAt || clock >= due) return;
        Integer idx = question.get(p.getUniqueId());
        if (idx == null) return;
        int[] s = row(p);
        Object[] sc = SCENARIOS[idx];
        int correct = (int) sc[2];
        if (choice == correct + 1) {
            s[0]++;
            s[2]++;
            if (s[2] > s[3]) s[3] = s[2];
            p.sendMessage(net.h2020.api.core.Messages.get("practice.gamesensedrill.right","p1",sc[3]));
            p.playSound(p.getLocation(), Sound.LEVEL_UP, 0.6f, 1.6f);
        } else {
            s[1]++;
            s[2] = 0;
            p.sendMessage(net.h2020.api.core.Messages.get("practice.gamesensedrill.not-this-one-the","p1",(correct + 1),"p2",((String[]) sc[1])[correct]));
            p.sendMessage(net.h2020.api.core.Messages.get("practice.sense.explanation", "why", sc[3]));
            p.playSound(p.getLocation(), Sound.ITEM_BREAK, 0.5f, 0.8f);
        }
        ask(p, g);
    }

    @Override public void onJoin(PracticeGame g, Player p) { score.put(p.getUniqueId(), new int[4]); }

    @Override
    public String hud(PracticeGame g, Player p) {
        int[] s = row(p);
        int total = s[0] + s[1];
        return ChatColor.GREEN + "Right " + ChatColor.WHITE + s[0] + ChatColor.DARK_GRAY + "/"
                + ChatColor.WHITE + total + (total > 0
                ? ChatColor.DARK_GRAY + " (" + Math.round(100.0 * s[0] / total) + "%)" : "")
                + ChatColor.DARK_GRAY + " | " + ChatColor.YELLOW + "Streak " + ChatColor.WHITE + s[2]
                + ChatColor.DARK_GRAY + " (best " + s[3] + ")";
    }

    @Override
    public List<String> stats(PracticeGame g, Player p) {
        int[] s = row(p);
        List<String> out = new ArrayList<String>();
        int total = s[0] + s[1];
        out.add("right " + s[0] + " of " + total + (total > 0
                ? " (" + Math.round(100.0 * s[0] / total) + "%)" : ""));
        out.add("best streak " + s[3]);
        out.add("questions served " + served);
        return out;
    }

    private int[] row(Player p) { return score.computeIfAbsent(p.getUniqueId(), k -> new int[4]); }
}
