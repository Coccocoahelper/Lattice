package net.h2020.api.core;

import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One sidebar board loaded from sidebar.yml. Lines may be conditional ({@code when:}) and every line goes through
 * the PlaceholderAPI bridge (when installed) and the usual '&amp;' colour translation.
 */
public final class SidebarTemplate {
    public static final class Line {
        public final String text;               // may be ""
        public final Set<String> when;          // all must be present in the flag set; empty = always

        public Line(String text, Set<String> when) {
            this.text = text == null ? "" : text;
            this.when = when == null || when.isEmpty()
                    ? Collections.<String>emptySet()
                    : Collections.unmodifiableSet(new LinkedHashSet<String>(when));
        }
    }

    private static final Pattern VAR = Pattern.compile("%([^%]+)%");
    /** The literal left in the text for an unknown key, which is also the team splice marker. */
    private static final String TEAMS = "%teams%";

    public final String name;
    public final String title;
    public final List<Line> lines;

    public SidebarTemplate(String name, String title, List<Line> lines) {
        this.name = name;
        this.title = title == null ? "" : title;
        this.lines = lines == null
                ? Collections.<Line>emptyList()
                : Collections.unmodifiableList(new ArrayList<Line>(lines));
    }

    /** Renders the lines this template has for these flags. Player may be null (console probe). */
    public List<String> render(Player p, Map<String, String> vars, Set<String> flags, List<String> teamLines) {
        List<String> out = new ArrayList<String>();
        for (Line line : lines) {
            if (!line.when.isEmpty() && (flags == null || !flags.containsAll(line.when))) continue;
            String text = substitute(line.text, vars);
            if (TEAMS.equals(text)) {
                if (teamLines == null) continue;
                for (String teamLine : teamLines) {
                    out.add(finish(p, substitute(teamLine, vars), out.size()));
                }
                continue;
            }
            out.add(finish(p, text, out.size()));
        }
        return out;
    }

    public String renderTitle(Player p, Map<String, String> vars) {
        return finishPlain(p, substitute(title, vars));
    }

    /** Steps 2-5 for one line, including the unique invisible replacement for blank entries. */
    private static String finish(Player p, String text, int index) {
        String s = finishPlain(p, text);
        if (!ChatColor.stripColor(s).isEmpty()) return s;
        // 1.8 refuses duplicate sidebar entries, so every blank gets its own number of reset codes
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i <= index; i++) sb.append(ChatColor.RESET);
        return sb.toString();
    }

    /** Steps 2, 4 and 5 (no blank handling): used for titles and as the body of {@link #finish}. */
    private static String finishPlain(Player p, String text) {
        String s = Sidebars.apply(p, text);
        if (s == null) s = "";
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    /** Replaces every known %key%; unknown placeholders are deliberately left intact for PlaceholderAPI. */
    private static String substitute(String text, Map<String, String> vars) {
        if (text == null) return "";
        if (vars == null || vars.isEmpty() || text.indexOf('%') < 0) return text;
        Matcher m = VAR.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String value = vars.get(m.group(1));
            if (value == null) value = m.group(0);
            m.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
