package net.h2020.bots.ai.ml;

import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import net.h2020.bots.ai.perception.PlayerStats;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The server asking a real player, at the moment it makes sense, one multiple-choice question. Three questions, each
 * covering the whole space of one thing - no separate "where are you bridging to" next to "what are you doing", and
 * no option that means the same as another:
 * <pre>
 *   [Server] (i read you as: ...) What are you doing right now?  [1 bridge→mid] ... [13 idle]
 *   [Server] (i read you as: ...) Combat state?                  [1 fighting, healthy] ... [6 healing / eating]
 *   [Server] (i read you as: ...) What next?                     [1 rush their bed] ... [10 nothing / afk]
 * </pre>
 * Every option maps to one activity class and to the hidden facts it settles (destination, gathering, defending,
 * shopping, healing, fighting...), and to one intent. Option coverage is counted, so it is visible when half the
 * answer space is never picked.
 */
public final class ServerQuestions {
    public static final int ACTIVITY = 0, COMBAT = 1, INTENT = 2, RUSH_TEAM = 3, BRIDGE_TEAM = 4;
    private static final int TYPES = 5;
    private static final long PER_TYPE_MS = 45000;
    private static final long PER_PLAYER_MS = 12000;

    // ---------------------------------------------------------------- the answer space
    private static final String[] ACTIVITY_OPTIONS = {
            "bridge→mid (emeralds)", "bridge→diamonds", "bridge→their base", "bridge→home",
            "walling / block-in", "bed defence", "team gen (iron/gold)", "farming diamonds", "farming emeralds",
            "fighting", "shop / upgrades", "travelling / rotating", "idle / looking"
    };
    private static final String[] COMBAT_OPTIONS = {
            "fighting, healthy", "fighting, low", "chasing them", "backing off", "running away", "healing / eating"
    };
    private static final String[] INTENT_OPTIONS = {
            "rush their bed", "defend our bed", "get iron/gold", "diamonds", "emeralds at mid", "shop (blocks/gear)",
            "team upgrade", "deposit at base", "hunt a bedless player", "nothing / afk"
    };
    private static final String[][] OPTIONS = {ACTIVITY_OPTIONS, COMBAT_OPTIONS, INTENT_OPTIONS, {}, {}};
    private static final String[][] SHORT = {
            {"mid", "dias", "enemy", "home", "wall", "beddef", "gen", "farmdias", "farmems", "fight", "shop", "move", "idle"},
            {"fight", "fight-low", "chase", "backoff", "run", "heal"},
            {"rush", "defend", "iron", "dias", "ems", "shop", "upgrade", "deposit", "hunt", "afk"},
            {},
            {},
    };
    private static final String[] PROMPTS = {
            "What are you doing right now?",
            "Combat state?",
            "What next?",
            "Which team?",
            "Bridging to which team?",
    };

    /** Behaviour class per activity option. */
    private static final int[] ACT_CLASS = {
            BehaviourModel.BRIDGING, BehaviourModel.BRIDGING, BehaviourModel.BRIDGING, BehaviourModel.BRIDGING,
            BehaviourModel.PLACING, BehaviourModel.PLACING, BehaviourModel.GATHERING, BehaviourModel.GATHERING,
            BehaviourModel.GATHERING, BehaviourModel.FIGHTING, BehaviourModel.SHOPPING, BehaviourModel.TRAVELLING,
            BehaviourModel.IDLE
    };
    /** Facts each activity option makes true (everything else in {@link #EXCLUSIVE} is cleared first). */
    private static final int[][] ACT_FACTS = {
            {QuestionBook.BRIDGING_TO_MID, QuestionBook.GATHERING_EMERALDS},
            {QuestionBook.GATHERING_DIAMONDS},
            {QuestionBook.BRIDGING_TO_ENEMY},
            {QuestionBook.BRIDGING_TO_BASE},
            {},
            {QuestionBook.DEFENDING},
            {QuestionBook.IN_GENERATOR},
            {QuestionBook.GATHERING_DIAMONDS},
            {QuestionBook.GATHERING_EMERALDS},
            {QuestionBook.FIGHTING},
            {QuestionBook.SHOPPING},
            {},
            {},
    };
    /** Facts that describe "what am I up to" - mutually exclusive, so an answer clears them all first. */
    private static final int[] EXCLUSIVE = {
            QuestionBook.BRIDGING_TO_MID, QuestionBook.BRIDGING_TO_ENEMY, QuestionBook.BRIDGING_TO_BASE,
            QuestionBook.GATHERING_DIAMONDS, QuestionBook.GATHERING_EMERALDS, QuestionBook.IN_GENERATOR,
            QuestionBook.DEFENDING, QuestionBook.SHOPPING, QuestionBook.HEALING, QuestionBook.FIGHTING,
            QuestionBook.RUNNING
    };

    /** Combat option -> {activity class, facts true, low hp}. */
    private static final int[] COMBAT_CLASS = {
            BehaviourModel.FIGHTING, BehaviourModel.FIGHTING, BehaviourModel.FIGHTING, BehaviourModel.FIGHTING,
            BehaviourModel.TRAVELLING, BehaviourModel.SHOPPING
    };
    private static final int[][] COMBAT_FACTS = {
            {QuestionBook.FIGHTING},
            {QuestionBook.FIGHTING, QuestionBook.LOW_HP},
            {QuestionBook.FIGHTING},
            {QuestionBook.FIGHTING},
            {QuestionBook.RUNNING, QuestionBook.LOW_HP},
            {QuestionBook.HEALING, QuestionBook.LOW_HP},
    };

    /** Intent option -> intent class, facts true. */
    private static final int[] INTENT_CLASS = {
            IntentModel.RUSH, IntentModel.DEFEND, IntentModel.IRON, IntentModel.DIAMONDS, IntentModel.EMERALDS,
            IntentModel.SHOP, IntentModel.UPGRADE, IntentModel.DEPOSIT, IntentModel.HUNT, IntentModel.IDLE
    };
    private static final int[][] INTENT_FACTS = {
            {},
            {QuestionBook.DEFENDING},
            {QuestionBook.IN_GENERATOR},
            {QuestionBook.GATHERING_DIAMONDS},
            {QuestionBook.GATHERING_EMERALDS},
            {QuestionBook.SHOPPING},
            {QuestionBook.SHOPPING},
            {},
            {},
            {},
    };

    // ---------------------------------------------------------------- state
    private static final class Pending {
        final long id;
        final int type;
        final long at;
        final String prompt;
        final String[] options;
        Pending(long id, int type, String prompt, String[] options) {
            this.id = id; this.type = type; this.at = System.currentTimeMillis();
            this.prompt = prompt; this.options = options;
        }
    }

    private static final Map<UUID, Pending> PENDING = new HashMap<UUID, Pending>();
    private static final Map<UUID, long[]> ASKED_AT = new HashMap<UUID, long[]>();
    private static final Map<UUID, Long> LAST_ANY = new HashMap<UUID, Long>();
    private static final Map<UUID, Long> LAST_ANSWER = new HashMap<UUID, Long>();
    private static final Map<UUID, Integer> LAST_ACTIVITY = new HashMap<UUID, Integer>();
    /** answers per question per option, to see what the questions actually capture. */
    private static final int[][] PICKS = new int[TYPES][16];
    private static long nextId = 1;
    private static long answered;

    private ServerQuestions() {}

    public static void install(JavaPlugin p) {
        Bukkit.getScheduler().runTaskTimer(p, new Runnable() {
            @Override public void run() { tick(); }
        }, 200L, 20L);
    }

    // ---------------------------------------------------------------- timing

    private static void tick() {
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p == null || !p.isOnline() || p.isDead()) continue;
            if (net.h2020.bots.ai.BotAI.isBotPlayer(p)) continue;                 // people only
            if (!inGame(p)) continue;                                             // never in a lobby / spectator seat
            Pending q = PENDING.get(p.getUniqueId());
            if (q != null && now - q.at < 30000) continue;                        // still waiting for the answer
            Long last = LAST_ANY.get(p.getUniqueId());
            if (last != null && now - last < PER_PLAYER_MS) continue;             // one thing at a time
            int type = pickType(p, now);
            if (type >= 0) ask(p, type);
        }
    }

    /** Only players actually playing a running game are asked anything. */
    private static boolean inGame(Player p) {
        net.h2020.api.game.GameManager gm = net.h2020.api.H2020.find(net.h2020.api.game.GameManager.class);
        if (gm == null) return false;
        net.h2020.api.game.Game g = gm.gameOf(p);
        if (g == null || g.state() != net.h2020.api.game.GameState.PLAYING) return false;
        if (g.isSpectator(p) || p.getGameMode() == org.bukkit.GameMode.SPECTATOR) return false;
        if (p.getHealth() <= 0) return false;
        if (g instanceof net.h2020.api.game.BedwarsView && ((net.h2020.api.game.BedwarsView) g).isRespawning(p)) return false;
        return g.alivePlayers().contains(p);
    }

    private static int pickType(Player p, long now) {
        long[] at = ASKED_AT.get(p.getUniqueId());
        if (at == null) { at = new long[TYPES]; ASKED_AT.put(p.getUniqueId(), at); }
        boolean dueCombat = now - at[COMBAT] > PER_TYPE_MS && PlayerStats.recentHits(p) > 0;
        int act = BehaviourModel.activity(p);
        boolean betweenJobs = now - at[INTENT] > PER_TYPE_MS
                && (act == BehaviourModel.IDLE || act == BehaviourModel.TRAVELLING || PlayerStats.recentPickup(p));
        boolean changed = LAST_ACTIVITY.containsKey(p.getUniqueId()) && LAST_ACTIVITY.get(p.getUniqueId()) != act;
        boolean stale = now - at[ACTIVITY] > PER_TYPE_MS * 2;
        // the activity question is only worth asking when the classical checks cannot tell what they are doing
        boolean dueActivity = now - at[ACTIVITY] > PER_TYPE_MS && (changed || stale)
                && !ClassicalChecks.explainsActivity(p);
        if (dueCombat) return COMBAT;
        if (betweenJobs) return INTENT;
        if (dueActivity) return ACTIVITY;
        return -1;
    }

    // ---------------------------------------------------------------- asking

    private static void ask(Player p, int type) {
        ask(p, type, PROMPTS[type], OPTIONS[type]);
    }

    private static void ask(Player p, int type, String prompt, String[] options) {
        long id = nextId++;
        PENDING.put(p.getUniqueId(), new Pending(id, type, prompt, options));
        long[] at = ASKED_AT.get(p.getUniqueId());
        if (at == null) at = new long[TYPES];
        at[type] = System.currentTimeMillis();
        ASKED_AT.put(p.getUniqueId(), at);
        LAST_ANY.put(p.getUniqueId(), System.currentTimeMillis());
        LAST_ACTIVITY.put(p.getUniqueId(), BehaviourModel.activity(p));

        ComponentBuilder b = new ComponentBuilder("[Server] ").color(net.md_5.bungee.api.ChatColor.GRAY)
                .append("(i read you as: " + read(p) + ") ").color(net.md_5.bungee.api.ChatColor.DARK_GRAY).italic(true)
                .append(prompt).color(net.md_5.bungee.api.ChatColor.WHITE).italic(false).append(" ");
        for (int i = 0; i < options.length; i++) {
            b.append("[" + (i + 1) + " " + options[i] + "]")
                    .color(net.md_5.bungee.api.ChatColor.AQUA)
                    .event(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/qanswer " + id + " " + (i + 1)))
                    .event(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                            new ComponentBuilder("Click to answer").color(net.md_5.bungee.api.ChatColor.GRAY).create()))
                    .append(" ").reset();
        }
        p.spigot().sendMessage(b.create());
    }

    /** What the server currently thinks this player is doing, and how old the information is. */
    private static String read(Player p) {
        String act = BehaviourModel.name(BehaviourModel.activity(p));
        String intent = IntentModel.name(IntentModel.predict(p));
        int conf = (int) Math.round(BehaviourModel.confidence(p) * 100);
        long now = System.currentTimeMillis();
        Long answeredAt = LAST_ANSWER.get(p.getUniqueId());
        long answerAgo = answeredAt == null ? -1 : (now - answeredAt) / 1000;
        String age = answerAgo < 0 ? "seen just now" : "you told me " + answerAgo + "s ago";
        boolean unsure = conf < 45 || answerAgo > 90;
        if (unsure) return "not sure - maybe " + act + ", maybe " + intent + " (" + age + ", say if wrong)";
        return act + ", wants to " + intent + " (" + conf + "% sure, " + age + ")";
    }

    // ---------------------------------------------------------------- answering

    public static boolean answer(Player p, long id, int option) {
        Pending q = PENDING.get(p.getUniqueId());
        if (q == null || q.id != id) {
            net.h2020.api.core.Messages.send(p, "bots.serverquestions.server-that-question-is");
            return false;
        }
        if (option < 1 || option > q.options.length) return false;
        PENDING.remove(p.getUniqueId());
        String picked = q.options[option - 1];
        LAST_ANSWER.put(p.getUniqueId(), System.currentTimeMillis());
        String before = read(p);
        apply(p, q.type, option - 1, picked);
        answered++;
        p.sendMessage(net.h2020.api.core.Messages.get("bots.serverquestions.server-recorded-i-had","p1",picked,"p2",before));
        if (q.type == INTENT && INTENT_CLASS[Math.min(option - 1, INTENT_CLASS.length - 1)] == IntentModel.RUSH) {
            askTeam(p, RUSH_TEAM);
        }
        // "bridge→their base" is the same follow-up: which base
        if (q.type == ACTIVITY && option - 1 == 2) {
            askTeam(p, BRIDGE_TEAM);
        }
        return true;
    }

    /** A bare number typed in chat answers the open question. */
    public static boolean answerTyped(Player p, String message) {
        Pending q = PENDING.get(p.getUniqueId());
        if (q == null) return false;
        String m = message.trim();
        if (m.isEmpty() || m.length() > 2 || !Character.isDigit(m.charAt(0))) return false;
        try { return answer(p, q.id, Integer.parseInt(m)); } catch (NumberFormatException e) { return false; }
    }

    /** Follow-up after "rush their bed" / "bridge→their base": which team? Options are the alive enemy colours. */
    private static void askTeam(Player p, int type) {
        net.h2020.api.game.GameManager gm = net.h2020.api.H2020.find(net.h2020.api.game.GameManager.class);
        net.h2020.api.game.Game g = gm == null ? null : gm.gameOf(p);
        if (g == null) return;
        net.h2020.api.game.GameTeam mine = g.teamOf(p);
        java.util.List<String> names = new java.util.ArrayList<String>();
        for (net.h2020.api.game.GameTeam t : g.teams()) {
            if (mine != null && t.color() == mine.color()) continue;
            if (!t.alive() || t.members().isEmpty()) continue;
            names.add(t.color().name());
        }
        if (names.isEmpty()) return;
        ask(p, type, PROMPTS[type], names.toArray(new String[0]));
    }

    private static void apply(Player p, int type, int index, String picked) {
        switch (type) {
            case ACTIVITY: {
                int i = Math.min(index, ACT_CLASS.length - 1);
                BehaviourModel.learnFromAnswer(p, ACT_CLASS[i]);
                for (int f : EXCLUSIVE) QuestionBook.setBelief(p, f, false);
                for (int f : ACT_FACTS[i]) QuestionBook.setBelief(p, f, true);
                break;
            }
            case COMBAT: {
                int i = Math.min(index, COMBAT_CLASS.length - 1);
                BehaviourModel.learnFromAnswer(p, COMBAT_CLASS[i]);
                for (int f : EXCLUSIVE) QuestionBook.setBelief(p, f, false);
                for (int f : COMBAT_FACTS[i]) QuestionBook.setBelief(p, f, true);
                break;
            }
            case INTENT: {
                int i = Math.min(index, INTENT_CLASS.length - 1);
                IntentModel.learnFromAnswer(p, INTENT_CLASS[i]);
                for (int f : EXCLUSIVE) QuestionBook.setBelief(p, f, false);
                for (int f : INTENT_FACTS[i]) QuestionBook.setBelief(p, f, true);
                QuestionBook.setBelief(p, QuestionBook.HEALING, INTENT_CLASS[i] == IntentModel.HEAL);
                break;
            }
            case RUSH_TEAM: {
                IntentModel.setRushTeam(p, picked);
                break;
            }
            case BRIDGE_TEAM: {
                IntentModel.setBridgeTeam(p, picked);
                break;
            }
            default: break;
        }
        if (type >= 0 && type < TYPES && index >= 0 && index < PICKS[type].length) PICKS[type][index]++;
    }

    public static boolean hasPending(Player p) {
        Pending q = PENDING.get(p.getUniqueId());
        return q != null && System.currentTimeMillis() - q.at < 30000;
    }

    // ---------------------------------------------------------------- report

    public static java.util.List<String> report() {
        java.util.List<String> out = new java.util.ArrayList<String>();
        out.add(String.format("§6[ml] §eserver questions: §f%d§7 answered", answered));
        String[] names = {"activity", "combat", "intent"};
        for (int t = 0; t < 3; t++) {
            int used = 0;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < OPTIONS[t].length; i++) {
                if (PICKS[t][i] > 0) used++;
                sb.append(String.format(" %s=%d", SHORT[t][Math.min(i, SHORT[t].length - 1)], PICKS[t][i]));
            }
            out.add(String.format("§7%s options: §f%d/%d§7 used;%s", names[t], used, OPTIONS[t].length, sb));
        }
        return out;
    }

    public static void clear() {
        PENDING.clear(); ASKED_AT.clear(); LAST_ANY.clear(); LAST_ACTIVITY.clear(); LAST_ANSWER.clear();
        for (int[] row : PICKS) java.util.Arrays.fill(row, 0);
    }
}
