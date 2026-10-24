package dev.mcflow;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/** Everything the settings panel can change, saved as config/mcflow.json. Shared by the NeoForge and Fabric builds. */
public final class Settings {
    /** WORLD: on a sphere around the player. PINNED: on a fixed flat plane in the world. SCREEN: fixed on screen. */
    public enum Placement { WORLD, PINNED, SCREEN }
    public enum Layout { STAGGERED, CENTERED, SINGLE }
    public enum Pulse { OFF, NOUNS, ALL }
    public enum Reveal { WORDS, LINE }
    public enum FadeOut { TOGETHER, WORDS }

    /** Font family for the sky lyrics; {@link #BUILT_IN_FONT} is the bundled Caveat. */
    public static final String BUILT_IN_FONT = "Caveat";
    /** Font family for the pill and panel; {@link #AUTO_FONT} picks the system's UI font. */
    public static final String AUTO_FONT = "System UI";

    // lyrics
    public String lyricFont = BUILT_IN_FONT;
    public int lyricColor = 0xFFFFFF;
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
    /** World mode: degrees left (-) / right (+) of where you look, and degrees above the crosshair. */
    public float worldYaw = 0, worldRaise = 10;
    /** Pinned mode: how far ahead (blocks) the plane is put when a line starts. */
    public float pinDistance = 16;
    /** Pinned mode: how much the sign bends around you as you get close (0 = stays flat). */
    public float warp = 0.5f;
    /** Rows and alternating words sit at different depths, so they slide past each other as you move or look around. */
    public float parallax = 0f;
    /** World placements: blocks hide the text behind them, glass/water let it through dimmed and tinted. */
    public boolean blockDetection = true;
    /** Blur and dim the lyrics and pill while a menu (inventory, chest, pause...) is open. */
    public boolean menuBlur = true;
    /** Screen mode: where the line's centre sits, as a share of the screen's width / height. */
    public float screenX = 0.5f, screenY = 0.25f;

    // effects
    public Pulse pulse = Pulse.NOUNS;
    public float pulseStrength = 1f;
    public int pulseColor = 0xC4B5FD;

    // pill
    public boolean showPill = true;
    public boolean pillAtBottom = false;
    public String uiFont = AUTO_FONT;
    public int accent = 0x8B5CF6;
    public float pillOpacity = 0.78f;
    public boolean equalizer = true;

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
