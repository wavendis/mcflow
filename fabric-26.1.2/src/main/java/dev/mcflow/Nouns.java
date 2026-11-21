package dev.mcflow;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Rough noun finder for lyric lines, no language model needed: the word after "the / my / your / a ..." (skipping
 * one adjective), plus a list of nouns that come up a lot in songs.
 * ponytail: rule-based, so it misses some nouns and catches the odd verb; swap in a real part-of-speech tagger
 * (e.g. OpenNLP) if this needs to be accurate.
 */
public final class Nouns {
    private static final Set<String> DETERMINERS = Set.of(
            "a", "an", "the", "my", "your", "his", "her", "our", "their", "this", "that", "these", "those",
            "every", "each", "some", "no", "any", "another");

    /** Common adjectives that sit between a determiner and its noun ("the little heart"). */
    private static final Set<String> ADJECTIVES = Set.of(
            "little", "big", "small", "good", "bad", "new", "old", "young", "only", "last", "first", "whole", "other",
            "same", "wild", "long", "cold", "dark", "bright", "sweet", "broken", "beautiful", "perfect", "golden",
            "yellow", "red", "blue", "black", "white", "green", "pink", "purple", "silver", "empty", "lonely", "real",
            "true", "best", "late", "high", "low", "deep", "free", "lost", "secret", "crazy", "pretty", "favorite",
            "favourite", "own", "next", "final", "hot", "slow", "fast", "summer", "winter");

    /** Nouns that are rarely anything else in lyrics. */
    private static final Set<String> COMMON = Set.of(
            "heart", "hearts", "night", "nights", "day", "days", "eyes", "eye", "sky", "skies", "fire", "rain", "world",
            "life", "time", "heaven", "soul", "mind", "body", "hand", "hands", "face", "lips", "sun", "moon", "stars",
            "star", "sea", "ocean", "river", "road", "home", "city", "street", "streets", "door", "window", "baby",
            "girl", "boy", "man", "woman", "friends", "friend", "money", "car", "phone", "music", "song", "songs",
            "party", "bed", "room", "floor", "ground", "air", "water", "wind", "storm", "diamond", "diamonds", "gold",
            "shadow", "shadows", "light", "lights", "darkness", "tears", "smile", "skin", "bones", "blood", "head",
            "morning", "summer", "winter", "rose", "roses", "flowers", "angel", "angels", "devil", "ghost", "dreams",
            "memories", "story", "words", "name", "voice", "radio", "dancefloor", "clouds", "ice", "snow", "beach",
            "wave", "waves", "highway", "town", "places", "place", "space", "moment", "moments", "kiss", "honey",
            "sugar", "wine", "champagne", "lover", "lovers", "boys", "girls", "people", "everybody", "nobody",
            "somebody", "everything", "nothing", "something", "forever", "tonight", "today", "tomorrow", "yesterday");

    /** Sung vocal sounds: ah, aah, oh, ooh, uh, woah/whoa, mm, hmm, eh, ay... (any number of repeated letters). */
    private static final Pattern VOCAL = Pattern.compile("a+h+|o+h*|u+h+|wh?o+a+h*|h?m{2,}|hm+|e+h+|a+y+");

    private Nouns() {}

    /** For each token of a line, whether it's a vocal sound like "oh" or "ah" (these always pulse to the beat). */
    public static boolean[] vocal(List<String> tokens) {
        boolean[] out = new boolean[tokens.size()];
        for (int i = 0; i < out.length; i++) out[i] = VOCAL.matcher(bare(tokens.get(i))).matches();
        return out;
    }

    /** For each token of a line (words split on spaces, punctuation still attached), whether it reads as a noun. */
    public static boolean[] find(List<String> tokens) {
        boolean[] noun = new boolean[tokens.size()];
        for (int i = 0; i < tokens.size(); i++) {
            String w = bare(tokens.get(i));
            if (COMMON.contains(w)) noun[i] = true;
            if (!DETERMINERS.contains(w) || Lyrics.endsClause(tokens.get(i)) || i + 1 >= tokens.size()) continue;
            int target = i + 1;
            // "the little heart": hop one adjective, unless the phrase stops right after it ("the only")
            if (ADJECTIVES.contains(bare(tokens.get(target))) && target + 1 < tokens.size() && !Lyrics.endsClause(tokens.get(target))) {
                target++;
            }
            String t = bare(tokens.get(target));
            if (!t.isEmpty() && !DETERMINERS.contains(t)) noun[target] = true;
        }
        return noun;
    }

    private static String bare(String word) {
        return word.toLowerCase(Locale.ROOT).replace('’', '\'').replaceAll("[^\\p{L}']", "");
    }

    public static void main(String[] args) {
        assert check("Yellow diamonds in the light").equals("diamonds light");
        assert check("As your shadow crosses mine").equals("shadow");
        assert check("I love you").isEmpty();
        assert check("Hold my little heart, baby").equals("heart, baby");
        assert check("You're the only one").equals("one") : "hops the adjective";
        assert check("Take me to the edge,").equals("edge,") : "a determiner before the end of a phrase still marks it";
        boolean[] v = vocal(List.of("Oh,", "ooooh", "Ahh", "whoa", "Woah!", "mmm", "uh", "on", "a", "oak", "home", "hey"));
        assert java.util.Arrays.equals(v, new boolean[]{true, true, true, true, true, true, true, false, false, false, false, false}) : java.util.Arrays.toString(v);
        System.out.println("ok");
    }

    private static String check(String line) {
        List<String> tokens = List.of(line.split(" "));
        boolean[] n = find(tokens);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < n.length; i++) if (n[i]) out.append(out.isEmpty() ? "" : " ").append(tokens.get(i));
        return out.toString();
    }
}
