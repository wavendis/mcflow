package dev.mcflow;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Everything the settings panel can change, saved as config/mcflow.json. Shared by the NeoForge and Fabric builds. */
public final class Settings {
    /** WORLD: on a sphere around the player. PINNED: on a fixed flat plane in the world. SCREEN: fixed on screen. */
    public enum Placement { WORLD, PINNED, SCREEN }
    public enum Layout { STAGGERED, CENTERED, SINGLE }
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

    /** One-click looks, in the order the panel shows them. */
    public static final List<String> PRESETS = List.of("Neon", "Handwritten", "Retro arcade", "Minimal", "Anime subtitles");

    public static void applyPreset(String name) {
        Settings s = current;
        // start each preset from the default look, keeping the user's own non-look settings
        Settings next = new Settings();
        next.offsetMs = s.offsetMs;
        next.musicSource = s.musicSource;
        switch (name) {
            case "Neon" -> {
                next.lyricFont = AUTO_FONT;
                next.glow = 1;
                next.glowColor = 0xF472B6;
                next.shadow = 0;
                next.pulse = Pulse.ALL;
                next.edgeGlow = 0.8f;
                next.beatParticles = Particles.GLOW;
                next.idle = Idle.WAVE;
            }
            case "Retro arcade" -> {
                next.lyricFont = TextArt.families().contains("Consolas") ? "Consolas" : "Courier New";
                next.uppercase = true;
                next.lyricColor = 0xFDE68A;
                next.outline = 0.08f;
                next.letterSpacing = 0.06f;
                next.appear = Appear.TYPEWRITER;
                next.exit = Exit.SCATTER;
                next.beatParticles = Particles.NOTES;
                next.lyricSize = 0.85f;
            }
            case "Minimal" -> {
                next.lyricFont = AUTO_FONT;
                next.shadow = 0.3f;
                next.lyricSize = 0.8f;
                next.layout = Layout.CENTERED;
                next.pulse = Pulse.OFF;
                next.dropEffect = false;
                next.edgeGlow = 0;
                next.beatParticles = Particles.OFF;
                next.floatUp = false;
            }
            case "Anime subtitles" -> {
                next.lyricFont = AUTO_FONT;
                next.placement = Placement.SCREEN;
                next.screenY = 0.86f;
                next.layout = Layout.SINGLE;
                next.lyricSize = 0.6f;
                next.outline = 0.12f;
                next.shadow = 0;
                next.reveal = Reveal.LINE;
                next.pulse = Pulse.OFF;
                next.edgeGlow = 0;
                next.beatParticles = Particles.OFF;
                next.floatUp = false;
            }
            default -> {} // "Handwritten" is the default look
        }
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
            com.google.gson.JsonObject json = com.google.gson.JsonParser.parseString(text).getAsJsonObject();
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
