import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * MapsJsonToYaml - converts Swofty-Developments/HypixelRecreation's Bed Wars and SkyWars map configs into
 * Lattice map.yml files.
 *
 * <p>Usage:
 * <pre>
 * java MapsJsonToYaml &lt;hypixel-recreate/configuration&gt; &lt;stagingDir&gt; [--suffix " (Polar)"]
 * </pre>
 *
 * <p>It writes &lt;stagingDir&gt;/bedwars/&lt;id&gt;/map.yml and &lt;stagingDir&gt;/skywars/&lt;id&gt;/map.yml. It does not copy
 * worlds; PolarToAnvil does that. Only the keys Lattice understands are produced. Pitch is read and ignored because
 * map.yml stores yaw (and the server derives pitch from look-at during play).
 */
public final class MapsJsonToYaml {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: MapsJsonToYaml <hypixel-recreate/configuration> <stagingDir> [--suffix text]");
            System.exit(2);
        }
        Path config = Path.of(args[0]).toAbsolutePath();
        Path staging = Path.of(args[1]).toAbsolutePath();
        String suffix = "";
        for (int i = 2; i < args.length; i++) {
            if (args[i].equals("--suffix") && i + 1 < args.length) suffix = args[++i];
        }
        if (!Files.isDirectory(config)) {
            System.err.println("not a directory: " + config);
            System.exit(2);
        }

        Path bedwarsJson = config.resolve("bedwars/maps.json");
        if (!Files.isRegularFile(bedwarsJson)) {
            System.err.println("missing " + bedwarsJson);
            System.exit(2);
        }
        Object root = Json.parse(Files.readString(bedwarsJson, StandardCharsets.UTF_8));
        List<Object> maps = Json.arr(Json.obj(root).get("maps"));
        int n = 0;
        for (Object o : maps) {
            Map<String, Object> map = Json.obj(o);
            String id = Json.str(map, "id");
            String name = Json.str(map, "name") + suffix;
            Map<String, Object> cfg = Json.obj(map.get("configuration"));
            String yaml = bedwarsYaml(id, name, cfg);
            Path dir = staging.resolve("bedwars").resolve(id);
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("map.yml"), yaml, StandardCharsets.UTF_8);
            System.out.println("wrote " + dir.resolve("map.yml"));
            n++;
        }

        Path skyDir = config.resolve("skywars");
        if (Files.isDirectory(skyDir)) {
            File[] files = skyDir.toFile().listFiles((d, f) -> f.endsWith("_config.json"));
            if (files != null) {
                Arrays.sort(files);
                for (File f : files) {
                    Map<String, Object> map = Json.obj(Json.parse(Files.readString(f.toPath(), StandardCharsets.UTF_8)));
                    String id = Json.str(map, "id");
                    String name = Json.str(map, "name") + suffix;
                    Map<String, Object> cfg = Json.obj(map.get("configuration"));
                    String yaml = skywarsYaml(id, name, cfg);
                    Path dir = staging.resolve("skywars").resolve(id);
                    Files.createDirectories(dir);
                    Files.writeString(dir.resolve("map.yml"), yaml, StandardCharsets.UTF_8);
                    System.out.println("wrote " + dir.resolve("map.yml"));
                    n++;
                }
            }
        }
        System.out.println("converted " + n + " map config(s) into " + staging);
    }

    // ================================================================================================== Bed Wars

    private static String bedwarsYaml(String id, String name, Map<String, Object> cfg) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Converted from Swofty-Developments/HypixelRecreation configuration/bedwars/maps.json\n");
        sb.append("# map id: ").append(id).append('\n');
        sb.append("name: ").append(quote(name)).append('\n');
        sb.append("family: BEDWARS\n");

        Map<String, Object> teams = Json.obj(cfg.get("teams"));
        sb.append("teams: [").append(teams.size()).append("]\n");

        Map<String, Object> bounds = Json.obj(cfg.get("bounds"));
        int buildLimit = (int) Math.round(Json.num(Json.obj(bounds.get("y")), "max", 255));
        sb.append("build-limit: ").append(buildLimit).append('\n');
        sb.append("bounds:\n");
        sb.append("  min: {x: ").append(fmt(Json.num(Json.obj(bounds.get("x")), "min", 0)))
          .append(", y: ").append(fmt(Json.num(Json.obj(bounds.get("y")), "min", 0)))
          .append(", z: ").append(fmt(Json.num(Json.obj(bounds.get("z")), "min", 0))).append("}\n");
        sb.append("  max: {x: ").append(fmt(Json.num(Json.obj(bounds.get("x")), "max", 0)))
          .append(", y: ").append(fmt(Json.num(Json.obj(bounds.get("y")), "max", 255)))
          .append(", z: ").append(fmt(Json.num(Json.obj(bounds.get("z")), "max", 0))).append("}\n");

        // Stable team order, independent of JSON member order.
        TreeMap<String, Object> sortedTeams = new TreeMap<>(teams);
        Map<String, List<Point>> points = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : sortedTeams.entrySet()) {
            String colour = colour(e.getKey());
            Map<String, Object> team = Json.obj(e.getValue());
            Map<String, Object> shop = Json.obj(team.get("shop"));
            add(points, "team." + colour + ".spawn", point(team.get("spawn")));
            Map<String, Object> bed = Json.obj(team.get("bed"));
            List<Point> bedPts = new ArrayList<>();
            bedPts.add(blockPoint(bed.get("feet")));
            bedPts.add(blockPoint(bed.get("head")));
            points.put("team." + colour + ".bed", bedPts);
            add(points, "team." + colour + ".shop", point(shop.get("item")));
            add(points, "team." + colour + ".upgrades", point(shop.get("team")));
            add(points, "team." + colour + ".generator", point(team.get("generator")));
        }

        Map<String, Object> globals = Json.obj(cfg.get("globalGenerator"));
        addList(points, "gen.diamond", locations(globals.get("DIAMOND")));
        addList(points, "gen.emerald", locations(globals.get("EMERALD")));

        Map<String, Object> locations = Json.obj(cfg.get("locations"));
        add(points, "waiting.lobby", point(locations.get("waiting")));
        add(points, "spectator", point(locations.get("spectator")));
        // Deliberately no "mid": the source config has no mid point. BedwarsGame falls back to the world spawn
        // (see report); PolarToAnvil puts that spawn at the centre of the converted world.
        writePoints(sb, points);
        return sb.toString();
    }

    // ================================================================================================== SkyWars

    private static String skywarsYaml(String id, String name, Map<String, Object> cfg) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Converted from Swofty-Developments/HypixelRecreation configuration/skywars/").append(id)
          .append("_config.json\n");
        sb.append("name: ").append(quote(name)).append('\n');
        sb.append("family: SKYWARS\n");

        List<Object> islands = Json.arr(cfg.get("islands"));
        sb.append("teams: [").append(islands.size()).append("]\n");

        int buildLimit = 255;
        Map<String, Object> bounds = cfg.get("bounds") instanceof Map ? Json.obj(cfg.get("bounds")) : null;
        if (bounds != null && bounds.get("max") instanceof Map) {
            buildLimit = (int) Math.round(Json.num(Json.obj(bounds.get("max")), "y", 255));
        }
        sb.append("build-limit: ").append(buildLimit).append('\n');
        if (bounds != null) {
            Map<String, Object> min = bounds.get("min") instanceof Map ? Json.obj(bounds.get("min")) : null;
            Map<String, Object> max = bounds.get("max") instanceof Map ? Json.obj(bounds.get("max")) : null;
            if (min != null && max != null) {
                sb.append("bounds:\n");
                sb.append("  min: {x: ").append(fmt(Json.num(min, "x", 0)))
                  .append(", y: ").append(fmt(Json.num(min, "y", 0)))
                  .append(", z: ").append(fmt(Json.num(min, "z", 0))).append("}\n");
                sb.append("  max: {x: ").append(fmt(Json.num(max, "x", 0)))
                  .append(", y: ").append(fmt(Json.num(max, "y", 255)))
                  .append(", z: ").append(fmt(Json.num(max, "z", 0))).append("}\n");
            }
        }

        Map<String, List<Point>> points = new LinkedHashMap<>();
        for (int i = 0; i < islands.size(); i++) {
            Map<String, Object> island = Json.obj(islands.get(i));
            add(points, "island." + i + ".cage", point(island.get("cageCenter")));
        }
        Point centre = point(cfg.get("center"));
        add(points, "mid", centre);
        add(points, "spectator", centre);
        // mid.chests is intentionally absent: the source config has no chest coordinates.
        writePoints(sb, points);
        return sb.toString();
    }

    // ================================================================================================== helpers

    private static List<Point> locations(Object value) {
        List<Point> out = new ArrayList<>();
        if (value instanceof Map) {
            Map<String, Object> g = Json.obj(value);
            if (g.get("locations") instanceof List) {
                for (Object o : Json.arr(g.get("locations"))) out.add(point(o));
            }
        }
        return out;
    }

    private static void add(Map<String, List<Point>> points, String key, Point p) {
        if (p == null) return;
        List<Point> list = new ArrayList<>();
        list.add(p);
        points.put(key, list);
    }

    private static void addList(Map<String, List<Point>> points, String key, List<Point> list) {
        if (list == null || list.isEmpty()) return;
        points.put(key, list);
    }

    private static Point point(Object value) {
        if (!(value instanceof Map)) return null;
        Map<String, Object> m = Json.obj(value);
        return new Point(Json.num(m, "x", 0), Json.num(m, "y", 0), Json.num(m, "z", 0),
                Json.num(m, "yaw", 0), Json.num(m, "pitch", 0), true);
    }

    /** Bed feet/head are integer block coordinates, not standing positions: no yaw/pitch. */
    private static Point blockPoint(Object value) {
        if (!(value instanceof Map)) return null;
        Map<String, Object> m = Json.obj(value);
        return new Point(Json.num(m, "x", 0), Json.num(m, "y", 0), Json.num(m, "z", 0), 0, 0, false);
    }

    private static void writePoints(StringBuilder sb, Map<String, List<Point>> points) {
        sb.append("points:\n");
        for (Map.Entry<String, List<Point>> e : points.entrySet()) {
            List<Point> list = e.getValue();
            if (list == null || list.isEmpty()) continue;
            sb.append("  ").append(e.getKey()).append(":\n");
            for (Point p : list) {
                if (p == null) continue;
                sb.append("    - {x: ").append(fmt(p.x)).append(", y: ").append(fmt(p.y))
                  .append(", z: ").append(fmt(p.z));
                if (p.hasRotation) sb.append(", yaw: ").append(fmt(p.yaw));
                sb.append("}\n");
            }
        }
    }

    private static String colour(String team) {
        switch (team.toUpperCase(Locale.ROOT)) {
            case "RED": return "red";
            case "BLUE": return "blue";
            case "GREEN": return "green";
            case "YELLOW": return "yellow";
            case "AQUA": return "aqua";
            case "WHITE": return "white";
            case "PINK": return "pink";
            case "GRAY": return "gray";
            default: return team.toLowerCase(Locale.ROOT);
        }
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String fmt(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) return "0";
        if (d == Math.rint(d) && Math.abs(d) < 1e12) return Long.toString((long) d);
        return java.math.BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
    }

    private static final class Point {
        final double x, y, z, yaw, pitch;
        final boolean hasRotation;

        Point(double x, double y, double z, double yaw, double pitch, boolean hasRotation) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.pitch = pitch;
            this.hasRotation = hasRotation;
        }
    }

    // ================================================================================================== JSON parser

    /**
     * A deliberately small JSON reader so the converter stays a single plain-Java file with no Gradle dependency.
     * It supports objects, arrays, strings (with escapes), numbers, true/false/null; that is all these configs use.
     */
    static final class Json {
        private final String s;
        private int at;

        private Json(String s) {
            this.s = s;
        }

        static Object parse(String text) {
            Json p = new Json(text);
            p.ws();
            Object v = p.value();
            p.ws();
            if (p.at != p.s.length()) throw p.error("trailing characters");
            return v;
        }

        @SuppressWarnings("unchecked")
        static Map<String, Object> obj(Object o) {
            if (!(o instanceof Map)) throw new IllegalArgumentException("expected JSON object, got " + o);
            return (Map<String, Object>) o;
        }

        @SuppressWarnings("unchecked")
        static List<Object> arr(Object o) {
            if (!(o instanceof List)) throw new IllegalArgumentException("expected JSON array, got " + o);
            return (List<Object>) o;
        }

        static String str(Map<String, Object> m, String key) {
            Object v = m.get(key);
            if (v == null) throw new IllegalArgumentException("missing key " + key);
            return String.valueOf(v);
        }

        static double num(Map<String, Object> m, String key, double def) {
            Object v = m.get(key);
            if (v == null) return def;
            if (v instanceof Number) return ((Number) v).doubleValue();
            try {
                return Double.parseDouble(String.valueOf(v));
            } catch (NumberFormatException e) {
                return def;
            }
        }

        private Object value() {
            if (at >= s.length()) throw error("unexpected end");
            char c = s.charAt(at);
            switch (c) {
                case '{': return object();
                case '[': return array();
                case '"': return string();
                case 't': expect("true"); return Boolean.TRUE;
                case 'f': expect("false"); return Boolean.FALSE;
                case 'n': expect("null"); return null;
                default:
                    if (c == '-' || (c >= '0' && c <= '9')) return number();
                    throw error("unexpected character '" + c + "'");
            }
        }

        private Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            at++; // {
            ws();
            if (peek('}')) { at++; return m; }
            while (true) {
                ws();
                String key = string();
                ws();
                if (!peek(':')) throw error("expected ':'");
                at++;
                ws();
                m.put(key, value());
                ws();
                if (peek(',')) { at++; continue; }
                if (peek('}')) { at++; return m; }
                throw error("expected ',' or '}'");
            }
        }

        private List<Object> array() {
            List<Object> list = new ArrayList<>();
            at++; // [
            ws();
            if (peek(']')) { at++; return list; }
            while (true) {
                ws();
                list.add(value());
                ws();
                if (peek(',')) { at++; continue; }
                if (peek(']')) { at++; return list; }
                throw error("expected ',' or ']'");
            }
        }

        private String string() {
            if (!peek('"')) throw error("expected string");
            at++;
            StringBuilder b = new StringBuilder();
            while (at < s.length()) {
                char c = s.charAt(at++);
                if (c == '"') return b.toString();
                if (c != '\\') { b.append(c); continue; }
                if (at >= s.length()) throw error("bad escape");
                char e = s.charAt(at++);
                switch (e) {
                    case '"': b.append('"'); break;
                    case '\\': b.append('\\'); break;
                    case '/': b.append('/'); break;
                    case 'b': b.append('\b'); break;
                    case 'f': b.append('\f'); break;
                    case 'n': b.append('\n'); break;
                    case 'r': b.append('\r'); break;
                    case 't': b.append('\t'); break;
                    case 'u':
                        if (at + 4 > s.length()) throw error("bad unicode escape");
                        b.append((char) Integer.parseInt(s.substring(at, at + 4), 16));
                        at += 4;
                        break;
                    default: throw error("bad escape \\" + e);
                }
            }
            throw error("unterminated string");
        }

        private Double number() {
            int start = at;
            if (peek('-')) at++;
            while (at < s.length() && Character.isDigit(s.charAt(at))) at++;
            if (peek('.')) {
                at++;
                while (at < s.length() && Character.isDigit(s.charAt(at))) at++;
            }
            if (peek('e') || peek('E')) {
                at++;
                if (peek('+') || peek('-')) at++;
                while (at < s.length() && Character.isDigit(s.charAt(at))) at++;
            }
            return Double.valueOf(s.substring(start, at));
        }

        private void expect(String literal) {
            if (!s.startsWith(literal, at)) throw error("expected " + literal);
            at += literal.length();
        }

        private boolean peek(char c) {
            return at < s.length() && s.charAt(at) == c;
        }

        private void ws() {
            while (at < s.length() && Character.isWhitespace(s.charAt(at))) at++;
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException("JSON at " + at + ": " + message);
        }
    }
}
