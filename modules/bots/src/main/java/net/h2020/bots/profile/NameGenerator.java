package net.h2020.bots.profile;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 2020-era Minecraft username generator. Mixes the styles you would scroll past in a Hypixel lobby tab list:
 * word+number, adjective+noun, xX_name_Xx, itz/iTz, TTV_, YT/PvP suffixes, underscores, leet/misspellings,
 * lowercase mashups and short "OG" names. All results are 3-16 chars of [A-Za-z0-9_] and avoid known
 * famous players.
 */
public final class NameGenerator {
    private static final Pattern VALID = Pattern.compile("^[A-Za-z0-9_]{3,16}$");

    private static final String[] ADJ = {
            "Dark", "Shadow", "Silent", "Swift", "Toxic", "Frozen", "Crazy", "Lazy", "Epic", "Mad", "Wild", "Lucky",
            "Sneaky", "Fuzzy", "Angry", "Happy", "Salty", "Spicy", "Sweaty", "Cool", "Super", "Mega", "Ultra", "Hyper",
            "Red", "Blue", "Green", "Purple", "Black", "White", "Golden", "Night", "Mystic", "Cosmic", "Pixel", "Tiny",
            "Big", "Lil", "Smol", "Baby", "Royal", "Savage", "Insane", "Rapid", "Ghost", "Ice", "Fire", "Storm",
            "Thunder", "Crystal", "Neon", "Lunar", "Solar", "Rusty", "Sleepy", "Hungry", "Grumpy", "Derpy", "Chill",
            "Soft", "Moody", "Feral", "Clumsy", "Brave", "Noble", "Quick", "Fluffy", "Cursed", "Hollow", "Void",
            "Shiny", "Rogue", "Lone", "Frosty", "Misty", "Wicked", "Sour", "Crispy", "Toasty", "Ender", "Nether"
    };
    private static final String[] NOUN = {
            "Wolf", "Fox", "Tiger", "Dragon", "Ninja", "Knight", "Gamer", "Warrior", "Hunter", "Sniper", "Slayer",
            "Reaper", "Panda", "Bunny", "Kitty", "Potato", "Taco", "Cookie", "Pickle", "Waffle", "Noodle", "Banana",
            "Creeper", "Blaze", "Ghast", "Golem", "Penguin", "Shark", "Eagle", "Falcon", "Viper", "Cobra", "Phoenix",
            "Wizard", "Pirate", "Viking", "Legend", "Boss", "King", "Queen", "Lord", "Pro", "Noob", "Player",
            "Builder", "Miner", "Crafter", "Bridger", "Clicker", "Sword", "Arrow", "Diamond", "Emerald", "Pearl",
            "Frost", "Flame", "Spark", "Bolt", "Duck", "Goose", "Frog", "Toad", "Sloth", "Llama", "Moose", "Otter",
            "Raven", "Crow", "Owl", "Bear", "Lion", "Hawk", "Turtle", "Muffin", "Donut", "Nugget", "Burrito", "Melon",
            "Cactus", "Sheep", "Pig", "Cow", "Squid", "Slime", "Wither", "Storm", "Rogue", "Samurai", "Monkey",
            "Gorilla", "Dino", "Rex", "Kid", "Dude", "Bro", "Guy", "Boi", "Gang", "Squad", "Plays", "Craft"
    };
    private static final String[] FIRST = {
            "Jake", "Ethan", "Liam", "Noah", "Mason", "Logan", "Lucas", "Owen", "Ryan", "Tyler", "Dylan", "Kyle",
            "Josh", "Matt", "Alex", "Sam", "Max", "Ben", "Nick", "Leo", "Finn", "Jack", "Luke", "Evan", "Cole", "Jace",
            "Zach", "Aiden", "Caleb", "Emma", "Mia", "Ella", "Lily", "Ava", "Zoe", "Chloe", "Ruby", "Luna", "Maya",
            "Sofia", "Abby", "Kayla", "Jess", "Toby", "Ollie", "Harry", "Charlie", "Alfie", "Kai", "Milo", "Theo",
            "Oscar", "Leon", "Jonas", "Felix", "Lukas", "Tim", "Nico", "Marco", "Diego", "Mateo", "Hugo", "Ivan",
            "Daan", "Sem", "Lars", "Emil", "Noel", "Elias", "Ash", "Jay", "Dex", "Zane", "Reece", "Callum", "Kian"
    };
    private static final String[] SUFFIX = { "YT", "TV", "PvP", "MC", "HD", "xD", "Pro", "Gaming", "Plays", "Playz", "IRL", "Official", "Live", "Bw", "Sw" };
    private static final String[] NUMS = { "69", "420", "123", "1337", "777", "99", "360", "007", "101", "666", "21", "13", "1", "2", "7", "11", "22", "88" };
    private static final String[] OG_ON = { "b", "c", "d", "f", "g", "h", "j", "k", "l", "m", "n", "p", "r", "s", "t", "v", "w", "x", "z", "qu", "th", "sh", "fl", "kr", "zy", "vy" };
    private static final String[] OG_NU = { "a", "e", "i", "o", "u", "y", "ae", "io", "ou" };
    private static final String[] OG_CODA = { "", "", "x", "n", "k", "r", "z", "s", "l", "th", "ph", "q" };

    /** Lower-case names of well known players / staff that must never be generated. */
    private static final Set<String> FAMOUS = new HashSet<String>(Arrays.asList(
            "technoblade", "dream", "georgenotfound", "sapnap", "tommyinnit", "tubbo", "wilbursoot", "ph1lza",
            "fundy", "badboyhalo", "skeppy", "captainsparklez", "notch", "jeb_", "dinnerbone", "grumm", "hypixel",
            "rezzus", "purpled", "wallibear", "gamerboy80", "krinios", "manhal_iq_", "fruitberries", "minikloon",
            "plancke", "rhune", "sammygreen", "dewier", "stimpy", "tapl", "quackity", "karljacobs", "punz", "antfrost",
            "ranboo", "eret", "nihachu", "illumina", "jojosolos", "huahwi", "preston", "prestonplayz", "unspeakable",
            "ssundee", "popularmmos", "dantdm", "stampylongnose", "vikkstar123", "lachlan", "bajancanadian",
            "jeromeasf", "ihascupquake", "sky", "skydoesminecraft", "mumbojumbo", "grian", "xisuma", "iskall85",
            "zyph", "calvinhuang", "bluefireeagle", "hannahxxrose", "xxhannahxx", "lunar", "badlion", "watchdog",
            "steve", "alex", "herobrine", "admin", "owner", "moderator", "helper", "simon", "codename_b", "agentk"
    ));

    private final Random rnd;

    public NameGenerator(Random rnd) { this.rnd = rnd; }

    public String next() {
        for (int attempt = 0; attempt < 50; attempt++) {
            String s = style();
            if (s != null && VALID.matcher(s).matches() && !FAMOUS.contains(s.toLowerCase(Locale.ROOT))) return s;
        }
        return "Player" + (100 + rnd.nextInt(9900));
    }

    private String style() {
        int r = rnd.nextInt(100);
        if (r < 16) return casing(pick(ADJ) + pick(NOUN) + (rnd.nextBoolean() ? number() : ""));
        if (r < 28) return casing(pick(NOUN) + number());
        if (r < 38) return pick(FIRST) + (rnd.nextInt(3) == 0 ? pick(NOUN) : number());
        if (r < 45) return xx();
        if (r < 52) return itz();
        if (r < 56) return ttv();
        if (r < 64) return underscored();
        if (r < 71) return leet(pick(rnd.nextBoolean() ? NOUN : FIRST) + (rnd.nextBoolean() ? pick(NOUN) : number()));
        if (r < 78) return (pick(ADJ) + pick(NOUN)).toLowerCase(Locale.ROOT) + (rnd.nextInt(3) == 0 ? number() : "");
        if (r < 86) return og();
        if (r < 90) return (rnd.nextBoolean() ? "Mr" : (rnd.nextBoolean() ? "The" : "Not")) + pick(rnd.nextBoolean() ? NOUN : FIRST) + (rnd.nextInt(3) == 0 ? number() : "");
        if (r < 96) return pick(rnd.nextBoolean() ? FIRST : NOUN) + pick(SUFFIX);
        return doubled(pick(FIRST) + pick(NOUN));
    }

    private String pick(String[] a) { return a[rnd.nextInt(a.length)]; }

    private String number() {
        int r = rnd.nextInt(10);
        if (r < 3) return String.valueOf(2002 + rnd.nextInt(10));          // birth year
        if (r < 5) return String.format("%02d", rnd.nextInt(100));          // 07, 42
        if (r < 7) return pick(NUMS);
        if (r < 9) return String.valueOf(rnd.nextInt(1000));
        return String.valueOf(1000 + rnd.nextInt(9000));
    }

    private String casing(String s) {
        int r = rnd.nextInt(10);
        if (r < 2) return s.toLowerCase(Locale.ROOT);
        if (r == 2 && s.length() > 1) return Character.toLowerCase(s.charAt(0)) + s.substring(1);
        return s;
    }

    private String xx() {
        String core = rnd.nextBoolean() ? pick(ADJ) + pick(NOUN) : pick(rnd.nextBoolean() ? NOUN : FIRST);
        switch (rnd.nextInt(5)) {
            case 0: return "xX_" + core + "_Xx";
            case 1: return "xX" + core + "Xx";
            case 2: return "Xx_" + core + "_xX";
            case 3: return "x" + core + "x";
            default: return "xX" + core + "_" + number().substring(0, 1) + "Xx";
        }
    }

    private String itz() {
        String p;
        switch (rnd.nextInt(5)) {
            case 0: p = "itz"; break;
            case 1: p = "Itz"; break;
            case 2: p = "iTz"; break;
            case 3: p = "ItsJust"; break;
            default: p = "its"; break;
        }
        String core = pick(rnd.nextBoolean() ? FIRST : NOUN);
        if (rnd.nextInt(3) == 0) return p + "_" + core.toLowerCase(Locale.ROOT);
        return p + core + (rnd.nextInt(4) == 0 ? number() : "");
    }

    private String ttv() {
        String core = pick(rnd.nextBoolean() ? FIRST : NOUN);
        switch (rnd.nextInt(4)) {
            case 0: return "TTV_" + core;
            case 1: return "ttv" + core;
            case 2: return "TTV" + core + number().substring(0, 1);
            default: return "ttv_" + core.toLowerCase(Locale.ROOT);
        }
    }

    private String underscored() {
        switch (rnd.nextInt(5)) {
            case 0: return pick(ADJ) + "_" + pick(NOUN);
            case 1: return "_" + pick(NOUN) + "_";
            case 2: return pick(FIRST).toLowerCase(Locale.ROOT) + "_" + (rnd.nextBoolean() ? "mc" : pick(NOUN).toLowerCase(Locale.ROOT));
            case 3: return pick(NOUN) + "_" + number();
            default: return "_" + pick(ADJ) + pick(NOUN);
        }
    }

    private String leet(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (rnd.nextInt(3) == 0) {
                switch (Character.toLowerCase(ch)) {
                    case 'o': ch = '0'; break;
                    case 'i': ch = '1'; break;
                    case 'e': ch = '3'; break;
                    case 'a': ch = rnd.nextBoolean() ? '4' : ch; break;
                    case 's': ch = rnd.nextBoolean() ? '5' : 'z'; break;
                    case 'c': ch = 'k'; break;
                    default: break;
                }
            }
            b.append(ch);
        }
        return b.toString();
    }

    private String doubled(String s) {
        // misspelling by doubled letter or dropped vowel: "Kiingg", "Drgon"
        StringBuilder b = new StringBuilder(s);
        int i = 1 + rnd.nextInt(Math.max(1, b.length() - 1));
        if (rnd.nextBoolean()) b.insert(i, b.charAt(i - 1));
        else if ("aeiou".indexOf(Character.toLowerCase(b.charAt(i))) >= 0 && b.length() > 4) b.deleteCharAt(i);
        if (rnd.nextBoolean()) b.append(b.charAt(b.length() - 1));
        return b.toString();
    }

    private String og() {
        StringBuilder b = new StringBuilder();
        int syll = rnd.nextInt(3) == 0 ? 1 : 2;
        for (int i = 0; i < syll; i++) b.append(pick(OG_ON)).append(pick(OG_NU));
        b.append(pick(OG_CODA));
        String s = b.toString();
        if (s.length() < 3) s = s + pick(OG_CODA) + "x";
        s = Character.toUpperCase(s.charAt(0)) + s.substring(1);
        int r = rnd.nextInt(6);
        if (r == 0) s = s.toLowerCase(Locale.ROOT);
        else if (r == 1) s = s + "_";
        else if (r == 2) s = s + rnd.nextInt(10);
        if (s.length() > 6) s = s.substring(0, 6);
        return s;
    }
}
