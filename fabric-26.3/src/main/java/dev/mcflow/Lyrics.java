package dev.mcflow;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Synced lyrics from LRCLIB (https://lrclib.net), free and keyless. */
public final class Lyrics {
    public record Word(String text, long startMs) {}

    public record Line(long startMs, long endMs, List<Word> words) {
        public String text() {
            return String.join(" ", words.stream().map(Word::text).toList());
        }
    }

    private static final Pattern LRC_LINE = Pattern.compile("^\\[(\\d+):(\\d+(?:\\.\\d+)?)]\\s*(.*)$");
    /** Bracketed junk and "feat." credits that YouTube titles add but LRCLIB entries don't have. */
    private static final Pattern TITLE_NOISE = Pattern.compile("\\s*([(\\[][^)\\]]*[)\\]]|\\b(ft|feat|featuring)\\.?\\s.*$|\\|.*$)", Pattern.CASE_INSENSITIVE);
    /** "- Remastered 2011", "- Live", "- Radio Edit"...: a version of the song after the dash, not "Artist - Song". */
    private static final Pattern VERSION_TAG = Pattern.compile(
            "\\b(remaster(ed)?|live|version|edit|mix|remix|mono|stereo|deluxe|acoustic|demo|instrumental|bonus|single|sped up|slowed)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CHANNEL_NOISE = Pattern.compile("(VEVO|\\s*-\\s*Topic|\\s*Official)$", Pattern.CASE_INSENSITIVE);
    /** Accept a result this close to the playing track's length without trying more queries. */
    private static final long GOOD_MATCH_MS = 10_000;

    /** Results per track (empty list = no lyrics), so flipping between media sessions doesn't refetch. */
    private static final Map<NowPlaying.Track, List<Line>> CACHE = new ConcurrentHashMap<>();
    /** Tracks being looked up right now, and why the last lookup failed (null after a success). */
    private static final Set<NowPlaying.Track> LOADING = ConcurrentHashMap.newKeySet();
    private static volatile String problem;

    private Lyrics() {}

    /** Lyrics for the current track, or empty while loading / when none were found. */
    public static List<Line> current() {
        NowPlaying.Track t = NowPlaying.track();
        return t == null ? List.of() : CACHE.getOrDefault(t, List.of());
    }

    static void fetch(NowPlaying.Track track) {
        if (CACHE.containsKey(track) || !LOADING.add(track)) return;
        CompletableFuture.supplyAsync(() -> lookup(track)).thenAccept(lrc -> {
            List<Line> lines = lrc == null ? List.of() : parse(lrc, track.durationMs());
            CACHE.put(track, lines);
            LOADING.remove(track);
            problem = null;
            McFlow.LOGGER.info("Loaded {} lyric lines for {} - {}", lines.size(), track.artist(), track.title());
        }).exceptionally(e -> {
            LOADING.remove(track);
            Throwable cause = e.getCause() != null && e.getCause().getCause() != null ? e.getCause().getCause() : e;
            problem = "Couldn't reach LRCLIB: " + cause.getClass().getSimpleName() + (cause.getMessage() == null ? "" : " (" + cause.getMessage() + ")");
            McFlow.LOGGER.warn("Lyrics lookup failed for {} - {}", track.artist(), track.title(), e);
            return null; // not cached, so it retries next time the song comes up
        });
    }

    /**
     * What's happening with the lyrics for the current track, in words, for the settings screen: so a player (or the
     * one helping them) can tell "no music detected" from "no lyrics exist" from "can't reach the lyrics site".
     */
    public static String status() {
        NowPlaying.Track t = NowPlaying.track();
        if (t == null) {
            if (NowPlaying.problem() != null) return "Song detection failed — " + NowPlaying.problem();
            return NowPlaying.apps().isEmpty() ? "No music detected: no app is reporting to Windows' media controls"
                    : "No music detected from the chosen app";
        }
        String song = t.title() + (t.artist().isEmpty() ? "" : " · " + t.artist());
        List<Line> lines = CACHE.get(t);
        if (lines != null) return song + " — " + (lines.isEmpty() ? "no synced lyrics on LRCLIB" : lines.size() + " lyric lines");
        if (LOADING.contains(t)) return song + " — looking up lyrics…";
        return song + " — " + (problem != null ? problem : "lyrics not loaded");
    }

    /** Song guesses from media metadata, most specific first: {artist, title}. */
    static List<String[]> guesses(String rawArtist, String rawTitle) {
        String title = TITLE_NOISE.matcher(rawTitle).replaceAll("").trim();
        String artist = CHANNEL_NOISE.matcher(rawArtist.trim()).replaceAll("").trim();
        List<String[]> out = new ArrayList<>();
        int dash = title.indexOf(" - ");
        if (dash > 0 && VERSION_TAG.matcher(title.substring(dash + 3)).find()) {
            // Spotify-style "Song - Remastered 2011": the part after the dash is a version, not the song
            title = title.substring(0, dash).trim();
        } else if (dash > 0) { // browser videos: "Artist - Song", channel name is often not the artist
            String a = title.substring(0, dash).trim(), t = title.substring(dash + 3).trim();
            out.add(new String[]{a, t});
            String first = a.split("\\s*(,|&|\\bx\\b|\\band\\b)\\s*")[0];
            if (!first.equals(a)) out.add(new String[]{first, t});
            title = t;
        }
        out.add(new String[]{artist, title});
        out.add(new String[]{"", title});
        return out;
    }

    /** Tries each guess until a synced result has about the right length; otherwise the closest one found. */
    private static String lookup(NowPlaying.Track t) {
        JsonObject best = null;
        long bestDiff = Long.MAX_VALUE;
        for (String[] g : guesses(t.artist(), t.title())) {
            String query = g[0].isEmpty() ? "q=" + enc(g[1])
                    : "track_name=" + enc(g[1]) + "&artist_name=" + enc(g[0]);
            for (JsonElement e : search(query)) {
                JsonObject r = e.getAsJsonObject();
                if (r.get("syncedLyrics") == null || r.get("syncedLyrics").isJsonNull()) continue;
                JsonElement dur = r.get("duration"); // some entries have none: rank them last
                long diff = dur == null || dur.isJsonNull() ? Long.MAX_VALUE - 1
                        : Math.abs((long) (dur.getAsDouble() * 1000) - t.durationMs());
                // a version with real per-word timing wins over one that's only a little closer in length
                if (diff <= GOOD_MATCH_MS && WORD_TAG.matcher(r.get("syncedLyrics").getAsString()).find()) diff = 0;
                if (diff < bestDiff) {
                    best = r;
                    bestDiff = diff;
                }
            }
            if (bestDiff <= GOOD_MATCH_MS) break;
        }
        return best == null ? null : best.get("syncedLyrics").getAsString();
    }

    // plain HttpURLConnection and the old Gson parser API: the same code runs on Minecraft 1.8.9 (Java 8, Gson 2.2.4)
    private static JsonArray search(String query) {
        try {
            HttpURLConnection c = Net.open("https://lrclib.net/api/search?" + query);
            if (c.getResponseCode() != 200) throw new IOException("LRCLIB answered " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                JsonElement body = new JsonParser().parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                return body.isJsonArray() ? body.getAsJsonArray() : new JsonArray();
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    static List<Line> parse(String lrc, long durationMs) {
        List<Long> starts = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        for (String raw : lrc.split("\\R")) {
            Matcher m = LRC_LINE.matcher(raw.trim());
            if (!m.matches()) continue;
            starts.add((long) (Integer.parseInt(m.group(1)) * 60_000 + Double.parseDouble(m.group(2)) * 1000));
            texts.add(m.group(3).trim());
        }
        List<Line> out = new ArrayList<>();
        for (int i = 0; i < texts.size(); i++) {
            if (WORD_TAG.matcher(texts.get(i)).replaceAll("").isBlank()) continue; // blank LRC lines just mark instrumental gaps
            long start = starts.get(i);
            long next = i + 1 < starts.size() ? starts.get(i + 1) : Math.max(durationMs, start + 5000);
            long end = Math.min(next, start + 8000);
            out.add(new Line(start, end, spreadWords(texts.get(i), start, end)));
        }
        return out;
    }

    /** Enhanced LRC word timestamps inside a line: "<00:12.40>word". */
    private static final Pattern WORD_TAG = Pattern.compile("<(\\d+):(\\d+(?:\\.\\d+)?)>");

    /**
     * Gives each (grouped) word its start time. Lines with enhanced-LRC word tags use those exact times; otherwise
     * words are spread over the first 70% of the line by syllable count, which follows singing better than letters.
     * ponytail: syllables are counted as vowel groups, so the estimate drifts on long sustained notes.
     */
    private static List<Word> spreadWords(String text, long start, long end) {
        List<String> pieces = new ArrayList<>();
        List<Long> times = new ArrayList<>();
        Matcher m = WORD_TAG.matcher(text);
        if (m.find()) {
            List<String> tokens = new ArrayList<>();
            List<Long> tokenTimes = new ArrayList<>();
            long t = start;
            int last = 0;
            m.reset();
            while (m.find()) {
                addTokens(text.substring(last, m.start()), t, tokens, tokenTimes);
                t = (long) (Integer.parseInt(m.group(1)) * 60_000 + Double.parseDouble(m.group(2)) * 1000);
                last = m.end();
            }
            addTokens(text.substring(last), t, tokens, tokenTimes);
            // split "bam-bam-bam" up front so each piece gets its share of the time until the next word
            for (int i = 0; i < tokens.size(); i++) {
                List<String> split = splitRepeats(new String[]{tokens.get(i)});
                long from = tokenTimes.get(i), to = i + 1 < tokens.size() ? tokenTimes.get(i + 1) : Math.max(from, end);
                for (int j = 0; j < split.size(); j++) {
                    pieces.add(split.get(j));
                    times.add(from + (to - from) * j / split.size());
                }
            }
        } else {
            pieces.addAll(List.of(text.split("\\s+")));
        }

        List<String> groups = groupShortWords(pieces.toArray(String[]::new));
        List<Word> words = new ArrayList<>();
        if (!times.isEmpty()) {
            int piece = 0; // a group starts when its first word is sung
            for (String group : groups) {
                words.add(new Word(group, times.get(Math.min(piece, times.size() - 1))));
                piece += group.split(" ").length;
            }
            return words;
        }
        double span = (end - start) * 0.7, total = groups.stream().mapToDouble(Lyrics::weight).sum();
        double t = start;
        for (String group : groups) {
            words.add(new Word(group, (long) t));
            t += span * weight(group) / Math.max(1e-9, total);
        }
        return words;
    }

    private static void addTokens(String segment, long time, List<String> tokens, List<Long> times) {
        for (String token : segment.trim().split("\\s+")) {
            if (token.isEmpty()) continue;
            tokens.add(token);
            times.add(time);
        }
    }

    /** Roughly how long a word group takes to sing: its syllables (vowel groups), plus a little per word. */
    static double weight(String group) {
        double w = 0;
        for (String word : group.split(" ")) {
            Matcher vowels = Pattern.compile("[aeiouyàáâäæãåèéêëìíîïòóôöõøùúûü]+").matcher(word.toLowerCase(Locale.ROOT));
            int syllables = 0;
            while (vowels.find()) syllables++;
            w += Math.max(1, syllables) + 0.3;
        }
        return w;
    }

    /** Little words that are sung quickly into the next one, so the estimated timing has fewer chances to drift. */
    private static final Set<String> SHORT_WORDS = Set.of(
            "a", "an", "the", "and", "or", "but", "so", "if", "as", "of", "to", "in", "on", "at", "by", "for", "with", "from",
            "i", "i'm", "i'll", "i'd", "i've", "me", "my", "we", "we're", "we'll", "us", "our", "you", "you're", "your",
            "he", "she", "it", "it's", "they", "they're", "his", "her", "its", "their",
            "is", "am", "are", "was", "be", "can", "can't", "don't", "won't", "do", "did", "got", "just", "not", "no", "oh");
    private static final int MAX_GROUP = 2;

    /**
     * "in the light" -> ["in", "the light"], "I can't go on and on" -> ["I can't", "go on", "and on"]: short words
     * carry over to the word after them, at most {@link #MAX_GROUP} words per group. A run of short words is paired
     * up from its end, so the last short word always joins the word it leads into.
     * Repeated words ("bam-bam-bam", "la la la") are split up and each shown on its own instead.
     */
    static List<String> groupShortWords(String[] tokens) {
        List<String> words = splitRepeats(tokens);
        List<String> out = new ArrayList<>();
        List<String> run = new ArrayList<>();
        for (int i = 0; i < words.size(); i++) {
            String w = words.get(i);
            boolean repeat = (i > 0 && sameWord(w, words.get(i - 1))) || (i + 1 < words.size() && sameWord(w, words.get(i + 1)));
            if (repeat) {
                flushRun(run, out);
                out.add(w);
                continue;
            }
            run.add(w);
            // punctuation ends a clause: never glue "it," onto the next sentence's first word
            if (!isShort(w) || endsClause(w)) flushRun(run, out);
        }
        flushRun(run, out); // line ends on short words
        return out;
    }

    /** Splits a run of short words (plus the word ending it) into groups, counting from the end. */
    private static void flushRun(List<String> run, List<String> out) {
        List<String> groups = new ArrayList<>();
        for (int end = run.size(); end > 0; end -= MAX_GROUP) {
            groups.addFirst(String.join(" ", run.subList(Math.max(0, end - MAX_GROUP), end)));
        }
        out.addAll(groups);
        run.clear();
    }

    /** "bam-bam-bam," -> ["bam", "bam", "bam,"]; real hyphenated words like "x-ray" stay whole. */
    static List<String> splitRepeats(String[] tokens) {
        List<String> out = new ArrayList<>();
        for (String t : tokens) {
            String[] pieces = t.split("-+");
            boolean allSame = pieces.length > 1;
            for (String piece : pieces) allSame &= !bare(piece).isEmpty() && sameWord(piece, pieces[0]);
            if (allSame) out.addAll(List.of(pieces));
            else out.add(t);
        }
        return out;
    }

    private static boolean sameWord(String a, String b) {
        return !bare(a).isEmpty() && bare(a).equals(bare(b));
    }

    private static String bare(String word) {
        return word.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}']", "");
    }

    static boolean endsClause(String word) {
        return word.matches(".*[,.?!;:…)\"”]$");
    }

    private static final Pattern SENTENCE_END = Pattern.compile("[.?!…]$"), CLAUSE_END = Pattern.compile("[,;:]$");

    /**
     * Where to break a line into the sky's two rows; returns the first index of row two. Each break costs how uneven
     * the two rows' word counts are, minus a bonus at a sentence end (so one sentence's end rarely shares a row with
     * the next one's start), a smaller one at a comma, and one just before a (bracket) or "quote" (it goes whole onto
     * row two, the right side); breaking inside brackets or quotes costs so much it only happens with no other choice.
     */
    public static int rowSplit(List<Word> words) {
        int n = words.size();
        if (n < 2) return n;
        int[] tokens = new int[n + 1]; // words before index i
        boolean[] openAfter = new boolean[n]; // a bracket or quote is still open after word i
        int[] state = new int[2];
        for (int i = 0; i < n; i++) {
            String text = words.get(i).text();
            tokens[i + 1] = tokens[i] + text.split(" ").length;
            for (char c : text.toCharArray()) spanStep(c, state);
            openAfter[i] = state[0] > 0 || state[1] > 0;
        }
        int best = (n + 1) / 2;
        double bestCost = Double.MAX_VALUE;
        for (int i = 1; i < n; i++) {
            String end = words.get(i - 1).text().replaceAll("[)\"”]+$", ""), next = words.get(i).text();
            double cost = Math.abs(tokens[n] - 2 * tokens[i]);
            if (SENTENCE_END.matcher(end).find()) cost -= 3;
            else if (CLAUSE_END.matcher(end).find()) cost -= 1.5;
            if (openAfter[i - 1]) cost += 100;
            else if (next.startsWith("(") || next.startsWith("\"") || next.startsWith("“")) cost -= 2;
            if (cost < bestCost) {
                bestCost = cost;
                best = i;
            }
        }
        return best;
    }

    private static boolean isShort(String word) {
        String w = word.toLowerCase(Locale.ROOT).replace('’', '\'').replaceAll("[^\\p{L}']", "");
        return w.length() <= 2 || SHORT_WORDS.contains(w);
    }

    /** What a letter is inside of, for {@link #spanStep}. */
    public static final int PLAIN = 0, IN_PARENS = 1, IN_QUOTES = 2;

    /**
     * Feeds the next character of a line (in reading order) and says whether it's inside (parentheses) or "quotes",
     * counting the brackets and quote marks themselves as inside. {@code state} carries [paren depth, in quotes] from
     * one character to the next; start each line with a fresh {@code new int[2]}. Parentheses win if both apply.
     */
    public static int spanStep(char c, int[] state) {
        boolean opensQuote = c == '“' || c == '„' || (c == '"' && state[1] == 0);
        boolean closesQuote = c == '”' || (c == '"' && state[1] == 1);
        if (c == '(') state[0]++;
        if (opensQuote) state[1] = 1;
        int span = state[0] > 0 ? IN_PARENS : state[1] > 0 ? IN_QUOTES : PLAIN;
        if (c == ')') state[0] = Math.max(0, state[0] - 1);
        if (closesQuote) state[1] = 0;
        return span;
    }

    /** Index of the last line that started at or before {@code posMs}, or -1. */
    public static int lastStarted(List<Line> lines, long posMs) {
        for (int i = lines.size() - 1; i >= 0; i--) {
            if (lines.get(i).startMs() <= posMs) return i;
        }
        return -1;
    }

    private static String spans(String line) {
        int[] state = new int[2];
        StringBuilder out = new StringBuilder();
        for (char c : line.toCharArray()) out.append(spanStep(c, state));
        return out.toString();
    }

    private static String row2(List<Word> words) {
        return String.join(" ", words.subList(rowSplit(words), words.size()).stream().map(Word::text).toList());
    }

    public static void main(String[] args) {
        List<Line> l = parse("[00:08.00] Yellow diamonds in the light\n[00:11.86] Now we're standing side by side\n[00:15.50]\n[00:30.00] Last", 40_000);
        assert l.size() == 3 : l;
        assert l.get(0).startMs() == 8000 && l.get(0).endMs() == 11860;
        assert l.get(0).words().get(0).startMs() == 8000;
        assert l.get(0).words().stream().map(Word::text).toList().equals(List.of("Yellow", "diamonds", "in", "the light")) : l.get(0);
        assert l.get(1).words().stream().map(Word::text).toList().equals(List.of("Now", "we're standing", "side", "by side")) : l.get(1);
        assert groupShortWords("I can't go on and on".split(" ")).equals(List.of("I can't", "go on", "and on"));
        assert groupShortWords("Can’t stop, won't stop".split(" ")).equals(List.of("Can’t stop,", "won't stop"));
        assert groupShortWords("Love me".split(" ")).equals(List.of("Love", "me"));
        assert groupShortWords("Hold on to it, we can".split(" ")).equals(List.of("Hold", "on", "to it,", "we can")) : "comma ends the group";
        assert groupShortWords("Is it? I know".split(" ")).equals(List.of("Is it?", "I know"));
        assert groupShortWords("Bam-bam-bam, here we go".split(" ")).equals(List.of("Bam", "bam", "bam,", "here", "we go")) : groupShortWords("Bam-bam-bam, here we go".split(" "));
        assert groupShortWords("and la-la-la-la".split(" ")).equals(List.of("and", "la", "la", "la", "la"));
        assert groupShortWords("in the end of the night".split(" ")).stream().allMatch(g -> g.split(" ").length <= 2) : "never more than 2 words";
        assert groupShortWords("my x-ray eyes".split(" ")).equals(List.of("my x-ray", "eyes")) : "real hyphenated words stay whole";
        List<Word> two = spreadWords("I can't stop. Oh no, don't let go", 0, 4000);
        assert two.get(rowSplit(two) - 1).text().endsWith(".") : "rows split at the sentence end, not at the middle";
        List<Word> plain = spreadWords("Yellow diamonds in the light", 0, 4000);
        assert rowSplit(plain) == 2;
        assert row2(spreadWords("I've been waiting for you all night (oh yeah)", 0, 4000)).endsWith(" (oh yeah)") : "brackets stay on one side";
        assert !row2(spreadWords("She said \"never let me go\" and I believed her", 0, 4000)).matches("[^\"]*\"[^\"]*") : "quotes stay on one side";
        assert row2(spreadWords("Baby (I just want you here tonight)", 0, 4000)).startsWith("(") : "brackets go whole to row two when it's close";
        assert row2(spreadWords("Running. Through the fire and the rain tonight we go", 0, 4000)).split(" ").length < 8 : "a sentence end doesn't win at any imbalance";
        assert row2(spreadWords("one two three four five six seven eight", 0, 4000)).split(" ").length == 4 : "even halves";
        assert l.get(1).endMs() == 15500 : "blank line ends the previous one";
        assert lastStarted(l, 9000) == 0 && lastStarted(l, 7000) == -1 && lastStarted(l, 20000) == 1 && lastStarted(l, 31000) == 2;
        assert Arrays.equals(guesses("JustinBieberVEVO", "Ariana Grande, Justin Bieber - Stuck with U (Lyric Video)").get(0), new String[]{"Ariana Grande, Justin Bieber", "Stuck with U"});
        assert Arrays.equals(guesses("JustinBieberVEVO", "Ariana Grande, Justin Bieber - Stuck with U (Lyric Video)").get(1), new String[]{"Ariana Grande", "Stuck with U"});
        assert Arrays.equals(guesses("Lauv", "Lauv - I Like Me Better [Official Audio]").get(0), new String[]{"Lauv", "I Like Me Better"});
        assert Arrays.equals(guesses("Kraak & Smaak", "Don't Want This to Be Over (feat. Satchmode)").get(0), new String[]{"Kraak & Smaak", "Don't Want This to Be Over"});
        assert Arrays.equals(guesses("RihannaVEVO", "We Found Love ft. Calvin Harris").get(0), new String[]{"Rihanna", "We Found Love"});
        assert Arrays.equals(guesses("Måneskin - Topic", "SUPERMODEL | Live").get(0), new String[]{"Måneskin", "SUPERMODEL"});
        assert Arrays.equals(guesses("Queen", "Bohemian Rhapsody - Remastered 2011").get(0), new String[]{"Queen", "Bohemian Rhapsody"});
        assert Arrays.equals(guesses("Lost Panda", "Katy Perry - California Gurls (Lyrics) Feat. Snoop Dogg").get(0), new String[]{"Katy Perry", "California Gurls"});
        assert spans("I said (oh yeah) \"go\" now").equals("0000000111111111022220000") : spans("I said (oh yeah) \"go\" now");
        assert spans("“hi” (a “b”)").equals("222201111111") : spans("“hi” (a “b”)");
        // enhanced LRC: exact word times, grouping still applies ("the light" starts when "the" is sung)
        List<Line> timed = parse("[00:01.00] <00:01.00>Yellow <00:01.80>diamonds <00:02.50>in <00:02.70>the <00:03.00>light", 10_000);
        assert timed.getFirst().words().stream().map(w -> w.text() + "@" + w.startMs()).toList()
                .equals(List.of("Yellow@1000", "diamonds@1800", "in@2500", "the light@2700")) : timed.getFirst().words();
        // syllables: "strength" (1) gets less time than "beautiful" (3) despite having more letters
        assert weight("strength") < weight("beautiful");
        System.out.println("ok");
    }
}
