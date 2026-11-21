package dev.mcflow;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/** Everything the settings panel can change, saved as config/mcflow.json. Shared by the NeoForge and Fabric builds. */
public final class Settings {
    /** WORLD: on a sphere around the player. PINNED: on a fixed flat plane in the world. SCREEN: fixed on screen. */
    public enum Placement { WORLD, PINNED, SCREEN }
    /** DRIFT: words of mixed sizes on loose, tilted rows. POP: one word at a time, each at its own spot. */
    public enum Layout { STAGGERED, CENTERED, SINGLE, DRIFT, POP }
    public enum Pulse { OFF, NOUNS, ALL }
    public enum Reveal { WORDS, LINE }
    public enum FadeOut { TOGETHER, WORDS }
    public enum Particles { OFF, NOTES, SPARKLES, GLOW }
    /** How letters arrive, what they do while shown, and how they leave. */
    public enum Appear { FADE, TYPEWRITER, BOUNCE, POP, GLITCH }
    public enum Idle { NONE, WAVE, FLOAT }
    public enum Exit { FADE, SCATTER, FALL, BURN }

    /** Font family for the sky lyrics; {@link #BUILT_IN_FONT} is the bundled Caveat. */
    public static final String BUILT_IN_FONT = "Caveat";
    /** Font family for the pill and panel; {@link #AUTO_FONT} picks the system's UI font. */
    public static final String AUTO_FONT = "System UI";

    /** Multiplayer: tell nearby players (on servers with Mc'Flow) which line you're on; show theirs above their heads. */
    public boolean shareLyrics = false;
    public boolean showOthers = true;

    /** Which app's music to follow (its Windows media ID, e.g. "Spotify.exe"); empty = whatever Windows says is playing. */
    public String musicSource = "";

    /** The ready-made look last picked (ticked in the panel); it stays ticked after tweaks, as their starting point. */
    public String look = "Handwritten";

    // lyrics
    public String lyricFont = BUILT_IN_FONT;
    public int lyricColor = 0xFFFFFF;
    /** Outline around each letter (width as a share of the font size, 0 = none) and a coloured glow (0 = none). */
    public float outline = 0;
    public int outlineColor = 0x111111;
    public float glow = 0;
    public int glowColor = 0xF472B6;
    public float lyricSize = 1f;
    public float shadow = 0.55f;
    /** Extra space between letters, as a share of the font size. */
    public float letterSpacing = 0f;
    public boolean uppercase = false;
    public Reveal reveal = Reveal.WORDS;
    public int fadeMs = 250;
    public int fadeOutMs = 400;
    /** Fade the whole line out at once, or word by word in reading order. */
    public FadeOut fadeOut = FadeOut.TOGETHER;
    /** Letters rise into place while appearing and drift up while fading out. */
    public boolean floatUp = true;
    public Appear appear = Appear.FADE;
    public Idle idle = Idle.NONE;
    public Exit exit = Exit.FADE;

    // placement
    public Placement placement = Placement.WORLD;
    public Layout layout = Layout.STAGGERED;
    /**
     * Staggered layout: the gap between the centre of the screen and each row's inner end (the left row's last letter,
     * the right row's first letter), as a share of the screen's width; the same on both sides. 0.45 = opposite edges.
     */
    public float rowSpread = 0.02f;
    /** Staggered layout: put the first row on the right and the second on the left. */
    public boolean firstRowRight = false;
    /** When a new line comes in, the old one moves down out of its way instead of up. */
    public boolean flowDown = false;
    /** On screen: bends each line into an arc around its middle, -1 (ends up) .. 1 (ends down), 0 = straight. */
    public float screenBend = 0;
    /** World mode: degrees left (-) / right (+) of where you look, and degrees above the crosshair. */
    public float worldYaw = 0, worldRaise = 10;
    /** Pinned mode: how far ahead (blocks) the plane is put when a line starts. */
    public float pinDistance = 16;
    /** Pinned mode: draw the letters as real objects in the world (hidden by anything in front, lit like signs). */
    public boolean realWorld = false;
    /** Pinned mode: how much the sign bends around you as you get close (0 = stays flat). */
    public float warp = 0.5f;
    /** Rows and alternating words sit at different depths, so they slide past each other as you move or look around. */
    public float parallax = 0f;
    /** World placements: blocks hide the text behind them, glass/water let it through dimmed and tinted. */
    public boolean blockDetection = true;
    /** PvP mode: lyrics float above the last player you hit, until this many seconds pass without a hit. */
    public boolean pvpMode = false;
    public float pvpSeconds = 8;
    /** PvP mode: instead of a line over their head, the words float around the target, each cluster in its own random spot. */
    public boolean pvpScatter = false;
    /** Size of the scattered words (1 = the line's usual size over a target). */
    public float scatterSize = 1;
    /** A random comic effect (BOOM!, POW!...) pops up around a player each time you hit them. */
    public boolean hitEffects = false;
    /** Hit effects: how long they take to pop in and to fade out (ms), and their size (x the random 0.7..1.5 blocks). */
    public int hitFadeInMs = 140, hitFadeOutMs = 280;
    public float hitSize = 1;
    /** Hit effects: at most this many on screen at once; a new hit replaces the oldest. */
    public int hitMax = 8;
    /** You glow in this colour, flaring on every beat (seen in third person); shared = nearby players see it too. */
    public boolean playerGlow = false, glowShared = true;
    public int playerGlowColor = 0x8B5CF6;
    /** The lyrics trail behind you letter by letter while you walk; shared = nearby players see it too. */
    public boolean lyricsTrail = false, trailShared = true;
    /** Size of the trail's letters (and their spacing); 1 = about a third of a block tall. */
    public float trailSize = 1;
    /** Blur and dim the lyrics and pill while a menu (inventory, chest, pause...) is open. */
    public boolean menuBlur = true;
    /** Screen mode: where the line's centre sits, as a share of the screen's width / height. */
    public float screenX = 0.5f, screenY = 0.25f;

    // text in (parentheses) and "quotes" (backing vocals, quoted lines): each can get its own colour and opacity
    public boolean parenStyle = true;
    public int parenColor = 0xC4B5FD;
    public float parenOpacity = 0.6f;
    public boolean quoteStyle = true;
    public int quoteColor = 0xFDE68A;
    public float quoteOpacity = 0.9f;

    // effects
    public Pulse pulse = Pulse.NOUNS;
    public float pulseStrength = 1f;
    public int pulseColor = 0xC4B5FD;
    /** When the music drops: screen flash, text shake and a bigger pop, scaled by the intensity. */
    public boolean dropEffect = true;
    public float dropIntensity = 1f;
    /** Particles burst around the player on every beat. */
    public Particles beatParticles = Particles.SPARKLES;
    /** The screen edges glow in the accent colour on beats (0 = off). */
    public float edgeGlow = 0.5f;

    // pill
    public boolean showPill = true;
    public boolean pillAtBottom = false;
    /** Where the pill was dragged to (its centre, as a share of the screen's width/height); -1 = top or bottom as above. */
    public float pillX = -1, pillY = -1;
    public String uiFont = AUTO_FONT;
    public int accent = 0x8B5CF6;
    public float pillOpacity = 0.78f;
    public boolean equalizer = true;
    /** Show the track's cover in the pill, and use its main colour for the accent (pill, equalizer, edge glow) and the text glow. */
    public boolean albumArt = true;
    public boolean accentFromArt = true;

    // timing
    public int offsetMs = 0;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Settings current = new Settings();
    private static int version;

    public static Settings get() {
        return current;
    }

    /** Goes up every time a setting changes, so cached images know to redraw. */
    public static int version() {
        return version;
    }

    public static void changed() {
        version++;
    }

    // ---- presets and share codes (looks only: never the timing offset or which app the music comes from) ----

    /** A one-click look: its name and a short blurb for its card, two colours for the card's bar, and what it sets. */
    public record Look(String name, String blurb, int from, int to, Consumer<Settings> apply) {}

    /** The ready-made looks, in the order the panel shows them. */
    public static final List<Look> LOOKS = List.of(
            new Look("Handwritten", "Soft script, the classic", 0xFFFFFF, 0xC4B5FD, s -> {}),
            new Look("Dreamy", "Words drifting across the sky", 0xE0E7FF, 0x818CF8, s -> {
                s.layout = Layout.DRIFT;
                s.lyricColor = 0xF5F3FF;
                s.glow = 0.35f;
                s.glowColor = 0xA5B4FC;
                s.shadow = 0.3f;
                s.fadeMs = 450;
                s.fadeOutMs = 600;
                s.idle = Idle.FLOAT;
                s.pulse = Pulse.OFF;
                s.worldRaise = 18;
            }),
            new Look("Kinetic", "One word at a time, popping up", 0xFFFFFF, 0x6B7280, s -> {
                s.layout = Layout.POP;
                s.lyricFont = font("Bahnschrift", "Segoe UI");
                s.uppercase = true;
                s.letterSpacing = 0.14f;
                s.lyricSize = 0.5f;
                s.shadow = 0.4f;
                s.appear = Appear.POP;
                s.fadeMs = 120;
                s.fadeOutMs = 700;
                s.floatUp = false;
                s.pulse = Pulse.OFF;
                s.beatParticles = Particles.OFF;
                s.worldRaise = 4;
            }),
            new Look("Neon", "Pink glow that pulses", 0xF9A8D4, 0xDB2777, s -> {
                s.lyricFont = AUTO_FONT;
                s.glow = 1;
                s.glowColor = 0xF472B6;
                s.shadow = 0;
                s.pulse = Pulse.ALL;
                s.edgeGlow = 0.8f;
                s.beatParticles = Particles.GLOW;
                s.idle = Idle.WAVE;
            }),
            new Look("Retro arcade", "Pixel-era yellow, typed in", 0xFDE68A, 0xF59E0B, s -> {
                s.lyricFont = font("Consolas", "Courier New");
                s.uppercase = true;
                s.lyricColor = 0xFDE68A;
                s.outline = 0.08f;
                s.letterSpacing = 0.06f;
                s.appear = Appear.TYPEWRITER;
                s.exit = Exit.SCATTER;
                s.beatParticles = Particles.NOTES;
                s.lyricSize = 0.85f;
            }),
            new Look("Minimal", "Small, calm, centred", 0xF3F4F6, 0x9CA3AF, s -> {
                s.lyricFont = AUTO_FONT;
                s.shadow = 0.3f;
                s.lyricSize = 0.8f;
                s.layout = Layout.CENTERED;
                s.pulse = Pulse.OFF;
                s.dropEffect = false;
                s.edgeGlow = 0;
                s.beatParticles = Particles.OFF;
                s.floatUp = false;
            }),
            new Look("Anime subtitles", "Outlined line at the bottom", 0xFFFFFF, 0x6B7280, s -> {
                s.lyricFont = AUTO_FONT;
                s.placement = Placement.SCREEN;
                s.screenY = 0.86f;
                s.layout = Layout.SINGLE;
                s.lyricSize = 0.6f;
                s.outline = 0.12f;
                s.shadow = 0;
                s.reveal = Reveal.LINE;
                s.pulse = Pulse.OFF;
                s.edgeGlow = 0;
                s.beatParticles = Particles.OFF;
                s.floatUp = false;
            }),
            new Look("Sunset", "Warm peach with a rose glow", 0xFDBA74, 0xFB7185, s -> {
                s.lyricColor = 0xFFE4C4;
                s.glow = 0.8f;
                s.glowColor = 0xFB7185;
                s.pulseColor = 0xFDBA74;
                s.accent = 0xFB923C;
                s.accentFromArt = false;
                s.idle = Idle.FLOAT;
            }),
            new Look("Ocean", "Cool blues that roll like waves", 0x7DD3FC, 0x2563EB, s -> {
                s.lyricColor = 0xE0F2FE;
                s.glow = 0.7f;
                s.glowColor = 0x38BDF8;
                s.pulseColor = 0x7DD3FC;
                s.idle = Idle.WAVE;
                s.exit = Exit.FALL;
                s.accent = 0x3B82F6;
                s.accentFromArt = false;
            }),
            new Look("Toxic", "Glitchy green terminal", 0xA3E635, 0x15803D, s -> {
                s.lyricFont = font("Consolas", "Courier New");
                s.uppercase = true;
                s.lyricColor = 0xD9F99D;
                s.glow = 0.9f;
                s.glowColor = 0x22C55E;
                s.pulseColor = 0xBEF264;
                s.appear = Appear.GLITCH;
                s.beatParticles = Particles.GLOW;
                s.accent = 0x22C55E;
                s.accentFromArt = false;
            }),
            new Look("Ember", "Burns away when it's done", 0xFBBF24, 0xDC2626, s -> {
                s.lyricFont = AUTO_FONT;
                s.uppercase = true;
                s.lyricColor = 0xFFEDD5;
                s.outline = 0.05f;
                s.outlineColor = 0x431407;
                s.glow = 0.9f;
                s.glowColor = 0xEA580C;
                s.pulse = Pulse.ALL;
                s.pulseColor = 0xFDBA74;
                s.exit = Exit.BURN;
                s.accent = 0xEA580C;
                s.accentFromArt = false;
            }),
            new Look("Glacier", "Icy outline, slow and clear", 0xE0F2FE, 0x0EA5E9, s -> {
                s.lyricColor = 0xF0F9FF;
                s.outline = 0.06f;
                s.outlineColor = 0x0369A1;
                s.glow = 0.4f;
                s.glowColor = 0x7DD3FC;
                s.fadeMs = 500;
                s.idle = Idle.FLOAT;
                s.pulseColor = 0xBAE6FD;
            }),
            new Look("Royal", "Serif gold with a warm glow", 0xFDE68A, 0xB45309, s -> {
                s.lyricFont = font("Georgia", "Times New Roman");
                s.lyricColor = 0xFDE68A;
                s.outline = 0.04f;
                s.outlineColor = 0x78350F;
                s.glow = 0.5f;
                s.glowColor = 0xF59E0B;
                s.letterSpacing = 0.04f;
                s.pulseColor = 0xFFFFFF;
                s.accent = 0xD97706;
                s.accentFromArt = false;
            }),
            new Look("Bubblegum", "Bouncy pink, notes on the beat", 0xF9A8D4, 0xC084FC, s -> {
                s.lyricColor = 0xFBCFE8;
                s.glow = 0.6f;
                s.glowColor = 0xF472B6;
                s.appear = Appear.BOUNCE;
                s.idle = Idle.WAVE;
                s.pulse = Pulse.ALL;
                s.pulseColor = 0xFFFFFF;
                s.beatParticles = Particles.NOTES;
            }),
            new Look("Ghost", "Faint, slow, word by word", 0xF3F4F6, 0x4B5563, s -> {
                s.lyricColor = 0xD1D5DB;
                s.shadow = 0;
                s.glow = 0.25f;
                s.glowColor = 0xFFFFFF;
                s.fadeMs = 700;
                s.fadeOutMs = 1200;
                s.fadeOut = FadeOut.WORDS;
                s.idle = Idle.FLOAT;
                s.pulse = Pulse.OFF;
                s.beatParticles = Particles.OFF;
                s.edgeGlow = 0;
                s.dropEffect = false;
            }),
            new Look("Comic", "Bold, outlined and loud", 0xFDE047, 0xF97316, s -> {
                s.lyricFont = font("Impact", "Arial Black");
                s.uppercase = true;
                s.lyricColor = 0xFDE047;
                s.outline = 0.1f;
                s.outlineColor = 0x111111;
                s.shadow = 0.2f;
                s.letterSpacing = 0.03f;
                s.appear = Appear.POP;
                s.exit = Exit.SCATTER;
                s.pulseColor = 0xFFFFFF;
            }),
            new Look("Vapor", "Wide pastel letters, cyan glow", 0xE879F9, 0x22D3EE, s -> {
                s.lyricFont = AUTO_FONT;
                s.uppercase = true;
                s.letterSpacing = 0.22f;
                s.lyricSize = 0.75f;
                s.lyricColor = 0xF5D0FE;
                s.glow = 0.8f;
                s.glowColor = 0x22D3EE;
                s.idle = Idle.WAVE;
                s.accent = 0xD946EF;
                s.accentFromArt = false;
            }),
            new Look("Typewriter", "Typed out letter by letter", 0xFFFFFF, 0xA8A29E, s -> {
                s.lyricFont = font("Courier New", "Consolas");
                s.lyricColor = 0xF5F5F4;
                s.layout = Layout.CENTERED;
                s.appear = Appear.TYPEWRITER;
                s.shadow = 0.4f;
                s.floatUp = false;
                s.pulse = Pulse.OFF;
            }));

    /** The first of these fonts that's installed, else the system UI font. */
    private static String font(String... families) {
        for (String f : families) if (TextArt.families().contains(f)) return f;
        return AUTO_FONT;
    }

    /** Switches to a ready-made look, starting from the default look but keeping the user's own non-look settings. */
    public static void applyLook(Look look) {
        Settings next = new Settings();
        next.offsetMs = current.offsetMs;
        next.musicSource = current.musicSource;
        next.look = look.name();
        look.apply().accept(next);
        current = next;
        changed();
        save();
    }

    private static final String CODE_PREFIX = "MCF1:";
    private static final List<String> PERSONAL = List.of("offsetMs", "musicSource");

    /** The current look as a short text code to share: gzipped JSON, base64. */
    public static String exportCode() {
        com.google.gson.JsonObject json = GSON.toJsonTree(current).getAsJsonObject();
        PERSONAL.forEach(json::remove);
        try {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            try (java.util.zip.GZIPOutputStream zip = new java.util.zip.GZIPOutputStream(bytes)) {
                zip.write(json.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            return CODE_PREFIX + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray());
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** Applies a look shared with {@link #exportCode}; returns false (changing nothing) if it isn't a valid code. */
    public static boolean importCode(String code) {
        if (code == null || !code.trim().startsWith(CODE_PREFIX)) return false;
        try {
            byte[] zipped = java.util.Base64.getUrlDecoder().decode(code.trim().substring(CODE_PREFIX.length()));
            String text;
            try (java.util.zip.GZIPInputStream zip = new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(zipped))) {
                text = new String(zip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
            com.google.gson.JsonObject json = new com.google.gson.JsonParser().parse(text).getAsJsonObject(); // old API: 1.8.9 has Gson 2.2.4
            PERSONAL.forEach(json::remove);
            com.google.gson.JsonObject mine = GSON.toJsonTree(current).getAsJsonObject();
            json.entrySet().forEach(e -> mine.add(e.getKey(), e.getValue()));
            Settings next = GSON.fromJson(mine, Settings.class);
            if (next == null) return false;
            current = next;
            changed();
            save();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static void reset() {
        current = new Settings();
        changed();
    }

    private static Path file() {
        return McFlow.configDir().resolve("mcflow.json");
    }

    public static void load() {
        Path file = file();
        // settings saved before the rename to Mc'Flow; they're written to the new file on the next save
        if (!Files.exists(file)) file = McFlow.configDir().resolve("lyricscord.json");
        if (!Files.exists(file)) return;
        try (Reader r = Files.newBufferedReader(file)) {
            Settings loaded = GSON.fromJson(r, Settings.class);
            if (loaded != null) current = loaded; // fields missing from older files keep their defaults
            changed();
        } catch (Exception e) {
            McFlow.LOGGER.warn("Couldn't read {}, using defaults", file, e);
        }
    }

    public static void save() {
        try (Writer w = Files.newBufferedWriter(file())) {
            GSON.toJson(current, w);
        } catch (Exception e) {
            McFlow.LOGGER.warn("Couldn't save {}", file(), e);
        }
    }
}
