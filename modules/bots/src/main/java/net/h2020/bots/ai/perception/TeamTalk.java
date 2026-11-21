package net.h2020.bots.ai.perception;

import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What teammates say in chat, as the AI hears it.
 *
 * <p>Bots understand the vocabulary a Bed Wars team actually uses, not sentences: the callouts people type in the
 * twenty seconds a game takes to start.
 *
 * <ul>
 *   <li><b>INC</b> - "inc", "incoming", "coming", "rushing" (+ a colour): somebody is on their way in</li>
 *   <li><b>VOID</b> - "void", "v", "die": get rid of yourself before they get the final</li>
 *   <li><b>DROP</b> - "drop", "items", "give": hand over what you are carrying</li>
 *   <li><b>SPLIT</b> - "split": stand beside the generator, not on it</li>
 *   <li><b>RUSH</b> - "rush blue", "we should go red", "blue next": the plan for whose bed is next</li>
 *   <li><b>BED</b> - "i take bed", "cover me", "you break it": who is doing what on the push</li>
 *   <li><b>DEFEND</b> - "def", "defend", "stay home": somebody stays back</li>
 *   <li><b>GG</b>/<b>GL</b> - the social lines, answered rather than acted on</li>
 * </ul>
 *
 * <p>A message can carry more than one thing: "rush blue, i take bed" is a RUSH and a BED assignment, and the reader
 * gets both. Colours come back with the intent, because "rush blue" and "rush red" are different plans.
 *
 * <p>Ring buffer, written from the async chat thread, read on the main thread. Every read is synchronised on the class,
 * and {@link #version()} lets a brain tell whether it already answered a line.
 */
public final class TeamTalk {

    public enum Intent { INC, VOID, DROP, SPLIT, RUSH, BED, DEFEND, GG, GL, NONE }

    /** A colour named in a callout, as the lowercase team name the game uses ("blue", "aqua", ...). */
    public static final String[] COLOURS = {"red", "blue", "green", "yellow", "aqua", "white", "pink", "gray", "grey"};

    public static final class Line {
        public final String sender;
        public final String raw;
        public final long atMs;
        /** Every intent the line carried, in the order they were found. Never empty; NONE when it was just chat. */
        public final List<Intent> intents;
        /** The team colour named in the line, lower case and normalised (grey -> gray); null when none. */
        public final String colour;
        Line(String sender, String raw, List<Intent> intents, String colour) {
            this.sender = sender; this.raw = raw; this.atMs = System.currentTimeMillis();
            this.intents = intents; this.colour = colour;
        }
        public boolean has(Intent i) { return intents.contains(i); }
        public Intent main() { return intents.isEmpty() ? Intent.NONE : intents.get(0); }
    }

    private static final int CAP = 64;
    private static final Line[] RING = new Line[CAP];
    private static int head;
    private static long version;

    /** "rush <colour>", "go <colour>", "<colour> next" - the plan lines, which the single-word scan would miss. */
    private static final Pattern PLAN = Pattern.compile(
            "\\b(?:rush|go|push|hit|take|attack)\\s+(?:the\\s+)?(red|blue|green|yellow|aqua|white|pink|gr[ae]y)\\b"
                    + "|\\b(red|blue|green|yellow|aqua|white|pink|gr[ae]y)\\s+(?:next|first|team)\\b");

    private TeamTalk() {}

    /** Trace every line the brains read, for tests and for watching a real game. Off by default. */
    public static boolean debug() { return Boolean.getBoolean("h2020.teamtalk.debug"); }

    public static synchronized void say(Player p, String message) {
        if (p == null || message == null || message.isEmpty()) return;
        List<Intent> intents = new ArrayList<Intent>(2);
        read(message, intents);
        RING[head] = new Line(p.getName(), message, intents, colourOf(message));
        head = (head + 1) % CAP;
        version++;
    }

    /** Bumped on every message, so a brain can tell whether it has already read the latest line. */
    public static synchronized long version() { return version; }

    /**
     * The last line that means something to this bot, or null when there is none since {@code sinceVersion}.
     *
     * <p>"Means something" includes the planning lines: a bot that ignores "rush blue" is not reading its team's chat,
     * it is reading four keywords.
     */
    public static synchronized Line latest(long sinceVersion, Player self) {
        if (version == sinceVersion) return null;
        for (int i = 1; i <= CAP; i++) {
            Line l = RING[(head - i + CAP) % CAP];
            if (l == null) break;
            if (l.sender.equalsIgnoreCase(self.getName())) continue;
            if (l.main() != Intent.NONE) return l;
        }
        return null;
    }

    /** All recent lines worth reading (debug / tests). */
    public static synchronized List<Line> recent() {
        List<Line> out = new ArrayList<Line>();
        for (int i = CAP; i > 0; i--) {
            Line l = RING[(head - i + CAP) % CAP];
            if (l != null) out.add(l);
        }
        return out;
    }

    /** Every intent in a line, in the order found. */
    public static List<Intent> intents(String message) {
        List<Intent> out = new ArrayList<Intent>(2);
        read(message, out);
        return out;
    }

    /** The first and strongest thing a line asked for - the old single-intent view, kept for callers that want it. */
    public static Intent intent(String message) {
        List<Intent> all = intents(message);
        return all.isEmpty() ? Intent.NONE : all.get(0);
    }

    /** The team colour a line names, normalised, or null. */
    public static String colourOf(String message) {
        if (message == null) return null;
        Matcher m = PLAN.matcher(message.toLowerCase(Locale.ROOT));
        if (m.find()) {
            String c = m.group(1) != null ? m.group(1) : m.group(2);
            return c.equals("grey") ? "gray" : c;
        }
        // a bare colour still counts when the line is short: "blue?" is a question about blue
        String[] words = message.toLowerCase(Locale.ROOT).replaceAll("[^a-z ]", " ").trim().split("\\s+");
        if (words.length <= 3) {
            for (String w : words) {
                for (String c : COLOURS) if (w.equals(c)) return c.equals("grey") ? "gray" : c;
            }
        }
        return null;
    }

    private static void read(String message, List<Intent> out) {
        if (message == null) return;
        String lower = message.toLowerCase(Locale.ROOT);
        String m = lower.replaceAll("[^a-z ]", " ").trim();
        if (m.isEmpty()) return;

        // "who we rushing" / "where we going" ask for the plan; they are not the plan. Without this a bot answered its
        // own team's question by starting a rush at whatever colour the question happened to mention.
        boolean question = lower.contains("?")
                || m.startsWith("who ") || m.startsWith("where ") || m.startsWith("what ") || m.startsWith("whats ")
                || m.startsWith("which ") || m.contains(" who ") || m.contains(" where ") || m.contains(" whats ");
        // the plan lines: "rush blue" is both RUSH and a colour, and must not be read as a bare INC
        if (!question && PLAN.matcher(lower).find()) add(out, Intent.RUSH);
        // A question carries no intent. A bot with no plan answers it from the raw text - see the brain's
        // NONE branch - so nothing is recorded here.
        if (question) return;

        for (String w : m.split("\\s+")) {
            if (w.equals("inc") || w.equals("incoming") || w.equals("incomming") || w.equals("coming")
                    || w.equals("rushing")) { add(out, Intent.INC); continue; }
            if (w.equals("void") || w.equals("v") || w.equals("die") || w.equals("dye")) { add(out, Intent.VOID); continue; }
            if (w.equals("drop") || w.equals("items") || w.equals("give") || w.equals("gift")) { add(out, Intent.DROP); continue; }
            if (w.equals("split") || w.equals("divide")) { add(out, Intent.SPLIT); continue; }
            if (w.equals("def") || w.equals("defend") || w.equals("defending") || w.equals("stay")) { add(out, Intent.DEFEND); continue; }
            if (w.equals("gg") || w.equals("ggs") || w.equals("gf") || w.equals("wp")) { add(out, Intent.GG); continue; }
            if (w.equals("gl") || w.equals("glhf")) { add(out, Intent.GL); continue; }
            // who takes the bed: "i take bed", "cover me", "you break it", "bed"
            if (w.equals("bed") || w.equals("cover") || w.equals("me")) { add(out, Intent.BED); continue; }
        }
        // "i take bed" and "cover me" only mean BED together, and a bare "me" is not a plan
        if (out.contains(Intent.BED) && !m.contains("bed") && !m.contains("cover")) out.remove(Intent.BED);
    }

    private static void add(List<Intent> out, Intent i) {
        if (!out.contains(i)) out.add(i);
    }

    public static void clear() {
        for (int i = 0; i < CAP; i++) RING[i] = null;
        head = 0;
        version = 0;
    }
}
