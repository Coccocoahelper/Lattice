package net.h2020.bots.ai.perception;

import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * What teammates say in chat, as the AI hears it. Bots only understand a small, real vocabulary - the callouts a
 * Bed Wars team actually uses:
 * <ul>
 * <li>"inc" / "incoming" / "coming" / "rushing" (+ a colour) - somebody is on their way to the base;</li>
 * <li>"void" / "v" / "die" - get rid of yourself (respawn at base instead of feeding a kill, or deny the final);</li>
 * <li>"drop" / "items" / "give" - hand over what you are carrying;</li>
 * <li>"split" - stand next to the generator rather than on it (the resource multiplier).</li>
 * </ul>
 * Everything else is just chat. Ring buffer, written from the async chat thread, read on the main thread.
 */
public final class TeamTalk {
    public enum Intent { INC, VOID, DROP, SPLIT, NONE }

    public static final class Line {
        public final String sender;
        public final String raw;
        public final long atMs;
        Line(String sender, String raw) { this.sender = sender; this.raw = raw; this.atMs = System.currentTimeMillis(); }
    }

    private static final int CAP = 64;
    private static final Line[] RING = new Line[CAP];
    private static int head;
    private static long version;

    private TeamTalk() {}

    public static synchronized void say(Player p, String message) {
        if (p == null || message == null || message.isEmpty()) return;
        RING[head] = new Line(p.getName(), message);
        head = (head + 1) % CAP;
        version++;
    }

    /** Bumped on every message, so a brain can tell whether it has already read the latest line. */
    public static synchronized long version() { return version; }

    /** The last line that means something to this bot, or null when there is none since `sinceVersion`. */
    public static synchronized Line latest(long sinceVersion, Player self) {
        if (version == sinceVersion) return null;
        for (int i = 1; i <= CAP; i++) {
            Line l = RING[(head - i + CAP) % CAP];
            if (l == null) break;
            if (l.sender.equalsIgnoreCase(self.getName())) continue;
            if (intent(l.raw) != Intent.NONE) return l;
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

    /** The callout in a chat line, if any. */
    public static Intent intent(String message) {
        if (message == null) return Intent.NONE;
        String m = message.toLowerCase().trim();
        // strip the punctuation people type around a callout
        m = m.replaceAll("[^a-z ]", " ").trim();
        if (m.isEmpty()) return Intent.NONE;
        for (String w : m.split("\\s+")) {
            if (w.equals("inc") || w.equals("incoming") || w.equals("incomming") || w.equals("coming")
                    || w.startsWith("rushing") || w.equals("rush")) return Intent.INC;
            if (w.equals("void") || w.equals("v") || w.equals("die") || w.equals("dye")) return Intent.VOID;
            if (w.equals("drop") || w.equals("items") || w.equals("give") || w.equals("gift")) return Intent.DROP;
            if (w.equals("split") || w.equals("divide")) return Intent.SPLIT;
        }
        return Intent.NONE;
    }

    public static void clear() {
        for (int i = 0; i < CAP; i++) RING[i] = null;
        head = 0;
        version = 0;
    }
}
