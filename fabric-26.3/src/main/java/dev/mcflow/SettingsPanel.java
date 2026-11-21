package dev.mcflow;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.Rectangle;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * The settings panel, painted with Java2D: an icon rail on the left picks the page, pill tabs under the page's title
 * pick one of its sections. It's immediate mode: every paint lays the controls out from the current {@link Settings}
 * and records where each one is, and mouse input is matched against those spots.
 * Coordinates are physical screen pixels. Minecraft-free, so every loader build shares it as-is.
 */
public final class SettingsPanel {
    private static final Color BG = new Color(20, 20, 26, 242);
    private static final Color RAIL = new Color(12, 12, 16, 250);
    /** The highlight for whatever is picked: the rail's page, the section pill, toggles, sliders. */
    private static final Color CORAL = new Color(242, 107, 107);
    private static final Color CARD = new Color(32, 32, 41);
    private static final Color CARD_HOVER = new Color(42, 42, 54);
    /** The mod's logo, at the top of the rail (null if it couldn't be read: an accent dot instead). */
    private static final BufferedImage LOGO = readLogo();
    private static final Color HOVER = new Color(255, 255, 255, 12);
    private static final Color TRACK = new Color(255, 255, 255, 26);
    private static final Color DIM = new Color(150, 150, 168);
    private static final int[] SWATCHES = {
            0xFFFFFF, 0xFDE68A, 0xFDBA74, 0xFCA5A5, 0xF9A8D4, 0xC4B5FD, 0x8B5CF6, 0x93C5FD, 0x67E8F9, 0x86EFAC, 0x1F1F2E};

    private interface Drag {
        void to(double x, double y);
    }

    /** A clickable spot: what a press does, what dragging does (sliders), what the mouse wheel does (font pickers). */
    private record Hit(Rectangle area, Runnable press, Drag drag, IntConsumer wheel) {}

    private final List<Hit> hits = new ArrayList<>();
    /** Clipboard access, given by the screen (Minecraft's clipboard; Java's own doesn't work in the game). */
    private Consumer<String> copy;
    private java.util.function.Supplier<String> paste;
    /** A short message in the footer ("copied!") and until when to show it. */
    private String status;
    private long statusUntil;
    private Hit dragging;
    private Rectangle hovered;
    private double mouseX = -1, mouseY = -1;
    private float scroll, maxScroll;
    private boolean dirty = true;
    private int lastW, lastH, lastVersion = -1;
    /** The newer release the header's badge was drawn for (null = no badge). */
    private String shownUpdate;
    /** The music/lyrics status the More page was drawn with. */
    private String shownStatus = "";
    /** While dragging the pill: where on it it was grabbed (offset from its centre, px); null otherwise. */
    private double[] pillGrab;
    /** How visible the panel is (it fades away while the mouse is on the pill), and when that was last eased. */
    private float opacity = 1;
    private long opacityNanos;

    /** Panel opacity this frame, 0..1: almost gone while hovering or dragging the pill, so only the pill shows. */
    public float opacity() {
        long now = System.nanoTime();
        float dt = opacityNanos == 0 ? 16 : Math.min(100, (now - opacityNanos) / 1e6f);
        opacityNanos = now;
        float target = pillGrab != null || onPill(mouseX, mouseY) ? 0.06f : 1;
        opacity += (target - opacity) * (1 - (float) Math.exp(-dt / 90));
        return opacity;
    }

    private static boolean onPill(double x, double y) {
        int[] p = LyricsHud.pillArea;
        return p != null && x >= p[0] && x < p[0] + p[2] && y >= p[1] && y < p[1] + p[3];
    }

    // layout of the current paint, in panel pixels
    private int panelX, panelY, panelW, panelH, railW, headerH, footerH, pad;
    /** The content column: its left and right edges. */
    private int left, right;
    private float u; // one "design pixel" at 1080p
    /** What's painted on: the panel, or a throwaway image while a section that isn't picked lays itself out. */
    private Graphics2D g, panelGraphics, scratch;
    private int y;
    /** This paint's sections of the page (one pill each), and whether the current one is hidden (not the picked one). */
    private final List<String> sections = new ArrayList<>();
    private boolean hidden;
    private int hiddenFromY;

    public void clipboard(Consumer<String> copy, java.util.function.Supplier<String> paste) {
        this.copy = copy;
        this.paste = paste;
    }

    private void flash(String message) {
        status = message;
        statusUntil = System.currentTimeMillis() + 2500;
    }

    public int x() {
        return panelX;
    }

    public int y() {
        return panelY;
    }

    public boolean needsRepaint(int w, int h) {
        boolean statusChanged = status != null && System.currentTimeMillis() > statusUntil;
        if (statusChanged) status = null;
        return dirty || statusChanged || w != lastW || h != lastH || Settings.version() != lastVersion || UpdateCheck.newer() != shownUpdate
                || (page == MORE_PAGE && !Lyrics.status().equals(shownStatus));
    }

    public BufferedImage paint(int w, int h) {
        lastW = w;
        lastH = h;
        lastVersion = Settings.version();
        dirty = false;
        u = h / 1080f;
        pad = px(24);
        panelW = Math.min(px(600), w - px(40));
        panelH = h - px(60);
        panelX = w - panelW - px(30);
        panelY = px(30);
        railW = px(84);
        headerH = px(150);
        footerH = px(40);
        left = railW + pad;
        right = panelW - pad;

        BufferedImage img = new BufferedImage(panelW, panelH, BufferedImage.TYPE_INT_ARGB);
        panelGraphics = g = TextArt.smooth(img);
        scratch = TextArt.smooth(new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB));
        hits.clear();
        RoundRectangle2D shape = new RoundRectangle2D.Float(0.5f, 0.5f, panelW - 1, panelH - 1, px(28), px(28));
        g.setColor(BG);
        g.fill(shape);
        rail(shape);

        // header: the page's name and what it's for; the section pills go under it once the content has named them
        Settings s = Settings.get();
        text(PAGES[page], left, px(52), font(26, Font.BOLD), Color.WHITE);
        text(BLURBS[page], left, px(76), font(13, Font.PLAIN), DIM);
        updateBadge();

        // scrolling content: only the picked section is laid out for real
        g.setClip(railW, headerH, panelW - railW, panelH - headerH - footerH);
        y = headerH - Math.round(scroll);
        sections.clear();
        content(s);
        endSection();
        maxScroll = Math.max(0, y + Math.round(scroll) - (panelH - footerH) + px(20));
        scroll = Math.clamp(scroll, 0, maxScroll);
        g.setClip(null);
        if (section >= sections.size() && section > 0) { // a section that went away (its setting was switched off)
            section = 0;
            dirty = true;
        }
        sectionPills(px(96));

        // dividers under the header and above the footer, so scrolled content has a clean edge
        g.setColor(TextArt.PILL_BORDER);
        g.fillRect(left, headerH - Math.max(1, px(1)), right - left, Math.max(1, px(1)));
        g.fillRect(left, panelH - footerH, right - left, Math.max(1, px(1)));
        if (status != null) text(status, left, panelH - px(15), font(13, Font.BOLD), CORAL);
        else text(maxScroll > 0 ? "Esc to close  ·  scroll for more  ·  K opens this anytime" : "Esc to close  ·  K opens this anytime",
                left, panelH - px(15), font(12, Font.PLAIN), DIM);
        g.setColor(TextArt.PILL_BORDER);
        g.draw(shape);
        scratch.dispose();
        g.dispose();
        return img;
    }

    /** The pages, in rail order, with the line under each page's title; the panel shows one at a time. Remembered between openings. */
    private static final String[] PAGES = {"Looks", "Text", "Motion", "Place", "Beat", "Pill", "More"};
    private static final String[] BLURBS = {
            "Ready-made styles: one click, then tweak anything",
            "Font, colour, outline and glow",
            "How words arrive, move and leave",
            "Where the lyrics show up",
            "Effects that follow the music",
            "The now playing bar",
            "Music, multiplayer and reset"};
    /** The page with the music status, which repaints when that changes. */
    private static final int MORE_PAGE = 6;
    private static int page, section;

    /** The current page's sections, top to bottom. */
    private void content(Settings s) {
        switch (page) {
            case 0 -> looksPage(s);
            case 1 -> textPage(s);
            case 2 -> motionPage(s);
            case 3 -> placePage(s);
            case 4 -> beatPage(s);
            case 5 -> pillPage(s);
            default -> morePage(s);
        }
        y += px(10);
    }

    private void looksPage(Settings s) {
        section("Ready-made");
        looksGrid(s);
        section("Share");
        hint("Your whole look as a code, for friends to paste");
        button("Copy share code", () -> {
            if (copy != null) copy.accept(Settings.exportCode());
            flash("Share code copied - send it to a friend");
        });
        y += px(8);
        button("Paste share code", () -> flash(paste != null && Settings.importCode(paste.get()) ? "Style applied" : "No valid Mc'Flow code on the clipboard"));
    }

    private void textPage(Settings s) {
        section("Text");
        fontPicker("Font", s.lyricFont, f -> s.lyricFont = f);
        colorPicker("Colour", s.lyricColor, c -> s.lyricColor = c);
        slider("Size", s.lyricSize, 0.4f, 2.5f, 0.05f, String.format("%.2fx", s.lyricSize), v -> s.lyricSize = v);
        slider("Shadow", s.shadow, 0, 1, 0.05f, Math.round(s.shadow * 100) + "%", v -> s.shadow = v);
        slider("Letter spacing", s.letterSpacing, -0.05f, 0.4f, 0.01f, String.format("%+.2f", s.letterSpacing), v -> s.letterSpacing = v);
        toggle("Uppercase", s.uppercase, v -> s.uppercase = v);

        section("Outline & glow");
        slider("Outline", s.outline, 0, 0.2f, 0.01f, s.outline == 0 ? "none" : Math.round(s.outline * 100) + "%", v -> s.outline = v);
        if (s.outline > 0) colorPicker("Outline colour", s.outlineColor, c -> s.outlineColor = c);
        slider("Glow", s.glow, 0, 1, 0.05f, s.glow == 0 ? "none" : Math.round(s.glow * 100) + "%", v -> s.glow = v);
        if (s.glow > 0 && !(s.albumArt && s.accentFromArt)) colorPicker("Glow colour", s.glowColor, c -> s.glowColor = c);
        if (s.glow > 0 && s.albumArt && s.accentFromArt) hint("Glow colour follows the album art (Pill page)");

        section("Brackets & quotes");
        toggle("Style text in (parentheses)", s.parenStyle, v -> s.parenStyle = v);
        if (s.parenStyle) {
            colorPicker("Colour", s.parenColor, c -> s.parenColor = c);
            slider("Opacity", s.parenOpacity, 0.1f, 1, 0.05f, Math.round(s.parenOpacity * 100) + "%", v -> s.parenOpacity = v);
        }
        toggle("Style text in \"quotes\"", s.quoteStyle, v -> s.quoteStyle = v);
        if (s.quoteStyle) {
            colorPicker("Colour", s.quoteColor, c -> s.quoteColor = c);
            slider("Opacity", s.quoteOpacity, 0.1f, 1, 0.05f, Math.round(s.quoteOpacity * 100) + "%", v -> s.quoteOpacity = v);
        }
    }

    private void motionPage(Settings s) {
        section("Reveal & fades");
        choice("Reveal", new String[]{"Word by word", "Whole line"}, s.reveal.ordinal(), i -> s.reveal = Settings.Reveal.values()[i]);
        slider("Fade in", s.fadeMs, 0, 1000, 10, s.fadeMs + " ms", v -> s.fadeMs = Math.round(v));
        slider("Fade out", s.fadeOutMs, 0, 2000, 10, s.fadeOutMs + " ms", v -> s.fadeOutMs = Math.round(v));
        choice("Fade out style", new String[]{"Whole line", "Word by word"}, s.fadeOut.ordinal(), i -> s.fadeOut = Settings.FadeOut.values()[i]);
        toggle("Float in and drift away", s.floatUp, v -> s.floatUp = v);

        section("Animation");
        choice("Appear", new String[]{"Fade", "Type", "Bounce", "Pop", "Glitch"}, s.appear.ordinal(), i -> s.appear = Settings.Appear.values()[i]);
        choice("While shown", new String[]{"Still", "Wave", "Float"}, s.idle.ordinal(), i -> s.idle = Settings.Idle.values()[i]);
        choice("Leave", new String[]{"Fade", "Scatter", "Fall", "Burn"}, s.exit.ordinal(), i -> s.exit = Settings.Exit.values()[i]);
    }

    private void placePage(Settings s) {
        section("Where");
        choice("", new String[]{"Around you", "Pinned", "On screen"}, s.placement.ordinal(), i -> s.placement = Settings.Placement.values()[i]);
        hint(switch (s.placement) {
            case WORLD -> "On a sphere around you: stays where it appeared when you turn";
            case PINNED -> "A sign standing in the world: walk up to it, around it";
            case SCREEN -> "Fixed on the screen, like a classic HUD";
        });

        section("Layout");
        choice("", new String[]{"Staggered", "Centered", "One line", "Drift", "Pop"}, s.layout.ordinal(), i -> s.layout = Settings.Layout.values()[i]);
        hint(switch (s.layout) {
            case STAGGERED -> "The line split in two, side by side";
            case CENTERED -> "Two rows stacked in the middle";
            case SINGLE -> "The whole line in one row";
            case DRIFT -> "Words of mixed sizes, scribbled across the sky";
            case POP -> "One word at a time, each in its own spot";
        });
        if (s.layout == Settings.Layout.STAGGERED) {
            choice("First row on", new String[]{"Left", "Right"}, s.firstRowRight ? 1 : 0, i -> s.firstRowRight = i == 1);
            String spread = s.rowSpread >= 0.4f ? "opposite sides" : "gap " + Math.round(s.rowSpread * 100) + "% each side";
            slider("Row spread", s.rowSpread, 0, 0.45f, 0.005f, spread, v -> s.rowSpread = v);
        }
        if (s.layout != Settings.Layout.DRIFT && s.layout != Settings.Layout.POP) {
            choice("Old lines move", new String[]{"Up", "Down"}, s.flowDown ? 1 : 0, i -> s.flowDown = i == 1);
        }

        section("Position");
        boolean world = s.placement != Settings.Placement.SCREEN;
        float side = world ? s.worldYaw / 40f : (s.screenX - 0.5f) / 0.25f; // -1 left, 0 centre, 1 right
        choice("Side", new String[]{"Left", "Center", "Right"}, Math.round(Math.clamp(side, -1, 1)) + 1, i -> {
            if (world) s.worldYaw = (i - 1) * 40;
            else s.screenX = 0.5f + (i - 1) * 0.25f;
        });
        if (world) {
            slider("Angle from view", s.worldYaw, -90, 90, 1, Math.round(s.worldYaw) + "°", v -> s.worldYaw = v);
            slider("Height", s.worldRaise, -40, 60, 1, Math.round(s.worldRaise) + "°", v -> s.worldRaise = v);
        } else {
            slider("Horizontal", s.screenX, 0, 1, 0.01f, Math.round(s.screenX * 100) + "%", v -> s.screenX = v);
            slider("Vertical", s.screenY, 0, 1, 0.01f, Math.round(s.screenY * 100) + "%", v -> s.screenY = v);
            bendSlider(s);
        }

        if (s.placement == Settings.Placement.PINNED) {
            section("Pinned sign");
            slider("Distance", s.pinDistance, 4, 48, 1, Math.round(s.pinDistance) + " blocks", v -> s.pinDistance = v);
            if (WorldText.supported) toggle("Render in the world (3D)", s.realWorld, v -> s.realWorld = v);
            slider("Warp up close", s.warp, 0, 1, 0.05f, s.warp == 0 ? "flat" : Math.round(s.warp * 100) + "%", v -> s.warp = v);
        }

        section("Depth");
        if (world) toggle("Block detection (walls, glass, water)", s.blockDetection, v -> s.blockDetection = v);
        slider("Parallax", s.parallax, 0, 1, 0.05f, s.parallax == 0 ? "off" : Math.round(s.parallax * 100) + "%", v -> s.parallax = v);

        section("PvP");
        toggle("Lyrics follow the last player you hit", s.pvpMode, v -> s.pvpMode = v);
        if (s.pvpMode) {
            slider("Back to normal after", s.pvpSeconds, 2, 30, 1, Math.round(s.pvpSeconds) + " s without a hit", v -> s.pvpSeconds = v);
            hint("Any new hit restarts the timer; hitting someone else switches to them");
            toggle("Scatter the words around them", s.pvpScatter, v -> s.pvpScatter = v);
            if (s.pvpScatter) slider("Word size", s.scatterSize, 0.4f, 3, 0.05f, Math.round(s.scatterSize * 100) + "%", v -> s.scatterSize = v);
            // the arch only applies to a line over their head; on screen the slider is already up in Position
            if (!s.pvpScatter && s.placement != Settings.Placement.SCREEN) bendSlider(s);
        }
        toggle("Comic hit effects (BOOM! POW!)", s.hitEffects, v -> s.hitEffects = v);
        if (s.hitEffects) {
            slider("Effect size", s.hitSize, 0.3f, 3, 0.05f, Math.round(s.hitSize * 100) + "% (still varies)", v -> s.hitSize = v);
            slider("Effects at once", s.hitMax, 1, 20, 1, s.hitMax == 1 ? "1 (newest only)" : "up to " + s.hitMax, v -> s.hitMax = Math.round(v));
            slider("Effect pop in", s.hitFadeInMs, 0, 1000, 10, s.hitFadeInMs + " ms", v -> s.hitFadeInMs = Math.round(v));
            slider("Effect fade out", s.hitFadeOutMs, 0, 2000, 10, s.hitFadeOutMs + " ms", v -> s.hitFadeOutMs = Math.round(v));
        }
    }

    /** Arches the text (on screen, and over a PvP target). */
    private void bendSlider(Settings s) {
        String bend = Math.abs(s.screenBend) < 0.01f ? "straight" : Math.round(Math.abs(s.screenBend) * 100) + "% " + (s.screenBend > 0 ? "arch" : "smile");
        slider("Bend", s.screenBend, -1, 1, 0.05f, bend, v -> s.screenBend = Math.abs(v) < 0.01f ? 0 : v);
    }

    private void beatPage(Settings s) {
        section("Pulse");
        choice("", new String[]{"Off", "Nouns", "All words"}, s.pulse.ordinal(), i -> s.pulse = Settings.Pulse.values()[i]);
        hint("Sung sounds like \"oh\" and \"ah\" always pulse");
        slider("Strength", s.pulseStrength, 0, 3, 0.1f, String.format("%.1fx", s.pulseStrength), v -> s.pulseStrength = v);
        colorPicker("Pulse colour", s.pulseColor, c -> s.pulseColor = c);

        section("Drops");
        toggle("Flash, shake and pop when the music drops", s.dropEffect, v -> s.dropEffect = v);
        if (s.dropEffect) slider("Intensity", s.dropIntensity, 0.2f, 2, 0.1f, String.format("%.1fx", s.dropIntensity), v -> s.dropIntensity = v);

        section("World");
        choice("Particles on beats", new String[]{"Off", "Notes", "Sparkles", "Glow"}, s.beatParticles.ordinal(), i -> s.beatParticles = Settings.Particles.values()[i]);
        slider("Screen edge glow", s.edgeGlow, 0, 1, 0.05f, s.edgeGlow == 0 ? "off" : Math.round(s.edgeGlow * 100) + "%", v -> s.edgeGlow = v);

        section("You");
        toggle("Glow to the beat", s.playerGlow, v -> s.playerGlow = v);
        if (s.playerGlow) {
            colorPicker("Glow colour", s.playerGlowColor, c -> s.playerGlowColor = c);
            toggle("Let other players see it", s.glowShared, v -> s.glowShared = v);
            hint("You see it in third person (F5)");
        }
        toggle("Lyrics trail while you walk", s.lyricsTrail, v -> s.lyricsTrail = v);
        if (s.lyricsTrail) {
            slider("Trail letter size", s.trailSize, 0.4f, 3, 0.05f, Math.round(s.trailSize * 100) + "%", v -> s.trailSize = v);
            toggle("Let other players see it", s.trailShared, v -> s.trailShared = v);
        }
        if ((s.playerGlow && s.glowShared) || (s.lyricsTrail && s.trailShared)) hint("Others need Mc'Flow, and so does the server");
    }

    private void pillPage(Settings s) {
        section("Now playing");
        toggle("Show pill", s.showPill, v -> s.showPill = v);
        boolean moved = s.pillX >= 0;
        choice("Position", new String[]{"Top", "Bottom"}, moved ? -1 : s.pillAtBottom ? 1 : 0, i -> {
            s.pillAtBottom = i == 1;
            s.pillX = s.pillY = -1; // snap back from wherever it was dragged
        });
        hint(moved ? "Placed by hand: pick Top or Bottom to snap it back" : "Or drag the pill anywhere while this menu is open");
        toggle("Equalizer", s.equalizer, v -> s.equalizer = v);

        section("Album art");
        toggle("Show album art", s.albumArt, v -> s.albumArt = v);
        if (s.albumArt) toggle("Colours from album art (accent, glow)", s.accentFromArt, v -> s.accentFromArt = v);

        section("Style");
        fontPicker("Font", s.uiFont, f -> s.uiFont = f);
        if (!(s.albumArt && s.accentFromArt)) colorPicker("Accent", s.accent, c -> s.accent = c);
        slider("Background", s.pillOpacity, 0, 1, 0.05f, Math.round(s.pillOpacity * 100) + "%", v -> s.pillOpacity = v);
    }

    private void morePage(Settings s) {
        section("Music");
        // what's playing and what became of its lyrics: tells "no music detected" apart from "can't reach LRCLIB"
        shownStatus = Lyrics.status();
        for (String part : shownStatus.split(" — ", 2)) hint(fit(part, font(12, Font.PLAIN), right - left));
        // a bare button row (nothing selected); M / , / . do the same in game
        choice("", new String[]{"‹‹  Previous", NowPlaying.playing() ? "Pause" : "Play", "Next  ››"}, -1,
                i -> NowPlaying.control(new String[]{"previous", "play", "next"}[i]));
        sourcePicker(s);
        slider("Timing offset", s.offsetMs, -3000, 3000, 50, (s.offsetMs > 0 ? "+" : "") + s.offsetMs + " ms  ([ / ] keys)", v -> s.offsetMs = Math.round(v));

        section("Multiplayer");
        toggle("Share my lyrics with nearby players", s.shareLyrics, v -> s.shareLyrics = v);
        toggle("Show other players' lyrics", s.showOthers, v -> s.showOthers = v);
        hint("Needs Mc'Flow on the server (or the host in LAN)");

        section("Menus");
        toggle("Blur and dim behind menus", s.menuBlur, v -> s.menuBlur = v);

        section("Reset");
        button("Reset everything to default", Settings::reset);
    }

    /** How each look's name is written on its card: in its own font, upper case if the look is. */
    private static final Map<String, Settings> SAMPLES = new HashMap<>();

    /** The ready-made looks as cards, two per row: name in the look's font and colours, a blurb, and its colour bar. */
    private void looksGrid(Settings s) {
        int gap = px(12), cardW = (right - left - gap) / 2, cardH = px(86), inner = px(14);
        List<Settings.Look> looks = Settings.LOOKS;
        for (int i = 0; i < looks.size(); i++) {
            Settings.Look look = looks.get(i);
            int x = left + (i % 2) * (cardW + gap), top = y + (i / 2) * (cardH + gap);
            Rectangle card = hit(new Rectangle(x, top, cardW, cardH), () -> Settings.applyLook(look), null, null);
            boolean picked = look.name().equals(s.look);
            RoundRectangle2D shape = new RoundRectangle2D.Float(x, top, cardW, cardH, px(16), px(16));
            g.setColor(isHovered(card) ? CARD_HOVER : CARD);
            g.fill(shape);
            if (picked) {
                g.setColor(new Color(235, 235, 245));
                g.setStroke(new BasicStroke(px(2)));
                g.draw(new RoundRectangle2D.Float(x + px(1), top + px(1), cardW - px(2), cardH - px(2), px(15), px(15)));
                g.setStroke(new BasicStroke(1));
                tick(x + cardW - px(18), top + px(18));
            }
            Settings sample = SAMPLES.computeIfAbsent(look.name(), k -> {
                Settings t = new Settings();
                look.apply().accept(t);
                return t;
            });
            Font nameFont = TextArt.font(sample.lyricFont, TextArt.uiFont()).deriveFont(Font.BOLD,
                    px(sample.lyricFont.equals(Settings.BUILT_IN_FONT) ? 25 : 19) * 1f);
            String name = fit(sample.uppercase ? look.name().toUpperCase(java.util.Locale.ROOT) : look.name(), nameFont, cardW - 2 * inner - px(22));
            int nameW = Math.max(1, metrics(nameFont).stringWidth(name));
            g.setPaint(new LinearGradientPaint(x + inner, 0, x + inner + nameW + 1, 0, new float[]{0, 1},
                    new Color[]{new Color(look.from()), new Color(look.to()).brighter()}));
            g.setFont(nameFont);
            g.drawString(name, x + inner, top + px(32));
            text(fit(look.blurb(), font(12, Font.PLAIN), cardW - 2 * inner), x + inner, top + px(54), font(12, Font.PLAIN), DIM);
            int barY = top + cardH - px(18), barH = px(5);
            g.setPaint(new LinearGradientPaint(x + inner, 0, x + cardW - inner, 0, new float[]{0, 1},
                    new Color[]{new Color(look.from()), new Color(look.to())}));
            g.fill(new RoundRectangle2D.Float(x + inner, barY, cardW - 2 * inner, barH, barH, barH));
        }
        y += (looks.size() + 1) / 2 * (cardH + gap);
    }

    /** The coral round tick on the picked look's card. */
    private void tick(int cx, int cy) {
        int r = px(10);
        g.setColor(CORAL);
        g.fill(new Ellipse2D.Float(cx - r, cy - r, 2 * r, 2 * r));
        Path2D check = new Path2D.Float();
        check.moveTo(cx - r * 0.45, cy);
        check.lineTo(cx - r * 0.1, cy + r * 0.35);
        check.lineTo(cx + r * 0.5, cy - r * 0.35);
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(Math.max(1, px(2)), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(check);
        g.setStroke(new BasicStroke(1));
    }

    /** Top right of the header, when GitHub has a newer release: a badge that opens the releases page. */
    private void updateBadge() {
        UpdateCheck.start(); // once per game, in the background; the panel repaints when the answer arrives
        shownUpdate = UpdateCheck.newer();
        if (shownUpdate == null) return;
        Font f = font(12, Font.BOLD);
        String label = "New release  ›";
        int h = px(28), w = metrics(f).stringWidth(label) + px(24), x = right - w, top = px(42) - h / 2;
        Rectangle badge = new Rectangle(x, top, w, h);
        hits.add(new Hit(badge, UpdateCheck::openReleases, null, null)); // in the fixed header, not the scrolling part
        g.setColor(isHovered(badge) ? CORAL.brighter() : CORAL);
        g.fill(new RoundRectangle2D.Float(x, top, w, h, h, h));
        text(label, x + px(12), top + h / 2 + metrics(f).getAscent() / 2 - px(2), f, Color.WHITE);
    }

    /** The icon rail down the left: the logo, then one icon and name per page; the open one sits on a coral tile. */
    private void rail(RoundRectangle2D panel) {
        g.setClip(panel);
        g.setColor(RAIL);
        g.fillRect(0, 0, railW, panelH);
        g.setClip(null);
        g.setColor(TextArt.PILL_BORDER);
        g.fillRect(railW, 0, Math.max(1, px(1)), panelH);
        int logo = px(54);
        if (LOGO != null) {
            int logoH = logo * LOGO.getHeight() / LOGO.getWidth();
            g.drawImage(LOGO, (railW - logo) / 2, px(40) - logoH / 2, logo, logoH, null);
        } else {
            g.setColor(CORAL);
            g.fillOval(railW / 2 - px(8), px(32), px(16), px(16));
        }
        int tile = px(62), step = px(74), top = px(84);
        Font f = font(11, Font.BOLD);
        for (int i = 0; i < PAGES.length; i++) {
            int tx = (railW - tile) / 2, ty = top + i * step, index = i;
            Rectangle area = new Rectangle(tx, ty, tile, tile);
            hits.add(new Hit(area, () -> {
                if (page != index) {
                    page = index;
                    section = 0;
                    scroll = 0;
                }
            }, null, null));
            boolean open = i == page;
            if (open || isHovered(area)) {
                g.setColor(open ? CORAL : HOVER);
                g.fill(new RoundRectangle2D.Float(tx, ty, tile, tile, px(18), px(18)));
            }
            Color ink = open ? Color.WHITE : DIM;
            icon(i, railW / 2, ty + px(24), px(20), ink);
            int tw = metrics(f).stringWidth(PAGES[i]);
            text(PAGES[i], (railW - tw) / 2, ty + px(50), f, ink);
        }
    }

    /** A small line icon for a page, centred on (cx, cy), about {@code size} px across. */
    private void icon(int index, int cx, int cy, int size, Color ink) {
        g.setColor(ink);
        float r = size / 2f;
        g.setStroke(new BasicStroke(Math.max(1, px(2)), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        switch (index) {
            case 0 -> { // looks: a four-pointed sparkle
                Path2D star = new Path2D.Float();
                star.moveTo(cx, cy - r);
                star.quadTo(cx, cy, cx + r, cy);
                star.quadTo(cx, cy, cx, cy + r);
                star.quadTo(cx, cy, cx - r, cy);
                star.quadTo(cx, cy, cx, cy - r);
                g.fill(star);
            }
            case 1 -> { // text: "Aa"
                Font f = font(17, Font.BOLD);
                int tw = metrics(f).stringWidth("Aa");
                text("Aa", cx - tw / 2, cy + metrics(f).getAscent() / 2 - px(2), f, ink);
            }
            case 2 -> { // motion: a wave
                Path2D wave = new Path2D.Float();
                for (int k = 0; k <= 20; k++) {
                    double t = k / 20.0, x = cx - r + t * 2 * r, yy = cy + Math.sin(t * Math.PI * 2) * r * 0.5;
                    if (k == 0) wave.moveTo(x, yy);
                    else wave.lineTo(x, yy);
                }
                g.draw(wave);
            }
            case 3 -> { // place: a map pin
                Path2D pin = new Path2D.Float();
                pin.moveTo(cx, cy + r);
                pin.curveTo(cx - r * 1.2, cy - r * 0.1, cx - r * 0.8, cy - r, cx, cy - r);
                pin.curveTo(cx + r * 0.8, cy - r, cx + r * 1.2, cy - r * 0.1, cx, cy + r);
                g.draw(pin);
                g.fill(new Ellipse2D.Float(cx - r * 0.25f, cy - r * 0.5f, r * 0.5f, r * 0.5f));
            }
            case 4 -> { // beat: equalizer bars
                float bw = r * 0.42f;
                float[] heights = {0.9f, 1.8f, 1.3f};
                for (int b = 0; b < 3; b++) {
                    float bh = r * heights[b], bx = cx - r + b * (bw + r * 0.37f);
                    g.fill(new RoundRectangle2D.Float(bx, cy + r - bh, bw, bh, bw, bw));
                }
            }
            case 5 -> { // pill: a rounded bar with a dot
                g.draw(new RoundRectangle2D.Float(cx - r, cy - r * 0.45f, 2 * r, r * 0.9f, r * 0.9f, r * 0.9f));
                g.fill(new Ellipse2D.Float(cx - r * 0.7f, cy - r * 0.18f, r * 0.36f, r * 0.36f));
            }
            default -> { // more: three dots
                for (int d = -1; d <= 1; d++) g.fill(new Ellipse2D.Float(cx + d * r * 0.75f - r * 0.2f, cy - r * 0.2f, r * 0.4f, r * 0.4f));
            }
        }
        g.setStroke(new BasicStroke(1));
    }

    /** The page's sections as pills under its title; the picked one is coral. Fixed: it stays put while the content scrolls. */
    private void sectionPills(int top) {
        if (sections.size() < 2) return;
        Font f = font(13, Font.BOLD);
        int h = px(32), gap = px(6), padX = px(14), total = -gap;
        for (String name : sections) total += metrics(f).stringWidth(name) + 2 * padX + gap;
        if (total > right - left) padX = Math.max(px(5), padX - (total - (right - left)) / (2 * sections.size()) - 1); // squeeze to fit
        int x = left;
        for (int i = 0; i < sections.size(); i++) {
            String name = sections.get(i);
            int w = metrics(f).stringWidth(name) + 2 * padX, index = i;
            Rectangle pill = new Rectangle(x, top, w, h);
            hits.add(new Hit(pill, () -> {
                if (section != index) {
                    section = index;
                    scroll = 0;
                }
            }, null, null));
            boolean on = i == section;
            g.setColor(on ? CORAL : isHovered(pill) ? new Color(255, 255, 255, 34) : new Color(255, 255, 255, 16));
            g.fill(new RoundRectangle2D.Float(x, top, w, h, h, h));
            text(name, x + padX, top + h / 2 + metrics(f).getAscent() / 2 - px(2), f, on ? Color.WHITE : TextArt.PILL_TEXT);
            x += w + gap;
        }
    }

    // ---- controls ----

    /**
     * Starts a section of the page. Only the picked one is shown: the others still run through their controls (so
     * they're counted as pills) but paint to a throwaway image, take no space and get no clickable spots.
     */
    private void section(String title) {
        endSection();
        sections.add(title);
        hidden = sections.size() - 1 != section;
        if (hidden) {
            hiddenFromY = y;
            g = scratch;
        } else {
            y += px(14);
        }
    }

    private void endSection() {
        if (!hidden) return;
        y = hiddenFromY;
        g = panelGraphics;
        hidden = false;
    }

    private void toggle(String label, boolean value, Consumer<Boolean> set) {
        int h = px(46);
        Rectangle row = rowHit(h, () -> apply(() -> set.accept(!value)), null, null);
        rowBackground(row);
        label(label, h);
        int tw = px(44), th = px(24), tx = right - tw, ty = y + (h - th) / 2;
        g.setColor(value ? CORAL : TRACK);
        g.fill(new RoundRectangle2D.Float(tx, ty, tw, th, th, th));
        int knob = th - px(6);
        g.setColor(Color.WHITE);
        g.fill(new Ellipse2D.Float(value ? tx + tw - knob - px(3) : tx + px(3), ty + px(3), knob, knob));
        y += h;
    }

    private void slider(String label, float value, float min, float max, float step, String shown, Consumer<Float> set) {
        int h = px(64);
        int x0 = left, x1 = right, trackY = y + px(44);
        Drag drag = (mx, my) -> apply(() -> {
            float v = min + (float) Math.clamp((mx - x0) / (x1 - x0), 0, 1) * (max - min);
            set.accept(Math.round(v / step) * step);
        });
        Rectangle row = rowHit(h, null, drag, null);
        rowBackground(row);
        text(label, left, y + px(26), font(15, Font.PLAIN), TextArt.PILL_TEXT);
        Font vf = font(13, Font.PLAIN);
        text(shown, right - metrics(vf).stringWidth(shown), y + px(26), vf, DIM);
        int th = px(6);
        g.setColor(TRACK);
        g.fill(new RoundRectangle2D.Float(x0, trackY - th / 2f, x1 - x0, th, th, th));
        float t = Math.clamp((value - min) / (max - min), 0, 1);
        int kx = Math.round(x0 + t * (x1 - x0));
        g.setColor(CORAL);
        g.fill(new RoundRectangle2D.Float(x0, trackY - th / 2f, kx - x0, th, th, th));
        int knob = px(18);
        g.setColor(Color.WHITE);
        g.fill(new Ellipse2D.Float(kx - knob / 2f, trackY - knob / 2f, knob, knob));
        y += h;
    }

    private void choice(String label, String[] options, int selected, IntConsumer set) {
        if (label.isEmpty()) {
            y += px(6); // a bare row of buttons
        } else {
            text(label, left, y + px(26), font(15, Font.PLAIN), TextArt.PILL_TEXT);
            y += px(38);
        }
        int h = px(36), x0 = left, w = right - left, segW = w / options.length;
        g.setColor(TRACK);
        g.fill(new RoundRectangle2D.Float(x0, y, w, h, h, h));
        Font f = font(13, Font.PLAIN);
        for (int i = 0; i < options.length; i++) {
            int sx = x0 + i * segW, idx = i;
            Rectangle seg = hit(new Rectangle(sx, y, segW, h), () -> apply(() -> set.accept(idx)), null, null);
            if (i == selected) {
                g.setColor(CORAL);
                g.fill(new RoundRectangle2D.Float(sx + px(3), y + px(3), segW - px(6), h - px(6), h - px(6), h - px(6)));
            } else if (isHovered(seg)) {
                g.setColor(HOVER);
                g.fill(new RoundRectangle2D.Float(sx + px(3), y + px(3), segW - px(6), h - px(6), h - px(6), h - px(6)));
            }
            String option = fit(options[i], f, segW - px(8));
            int tw = metrics(f).stringWidth(option);
            text(option, sx + (segW - tw) / 2, y + h / 2 + metrics(f).getAscent() / 2 - px(2), f, i == selected ? Color.WHITE : TextArt.PILL_TEXT);
        }
        y += h + px(12);
    }

    private void colorPicker(String label, int value, IntConsumer set) {
        int h = px(40);
        label(label, h);
        int dot = px(22);
        g.setColor(new Color(value));
        g.fillOval(right - dot, y + (h - dot) / 2, dot, dot);
        g.setColor(TextArt.PILL_BORDER);
        g.drawOval(right - dot, y + (h - dot) / 2, dot, dot);
        y += h;

        int sw = px(24), gap = (right - left - SWATCHES.length * sw) / Math.max(1, SWATCHES.length - 1);
        for (int i = 0; i < SWATCHES.length; i++) {
            int sx = left + i * (sw + gap), c = SWATCHES[i];
            hit(new Rectangle(sx, y, sw, sw), () -> apply(() -> set.accept(c)), null, null);
            g.setColor(new Color(c));
            g.fillOval(sx, y, sw, sw);
            if (c == (value & 0xFFFFFF)) {
                g.setColor(Color.WHITE);
                g.setStroke(new BasicStroke(px(2)));
                g.drawOval(sx - px(3), y - px(3), sw + px(6), sw + px(6));
                g.setStroke(new BasicStroke(1));
            }
        }
        y += sw + px(14);

        // any colour: drag along the rainbow
        int x0 = left, x1 = right, bh = px(12);
        Drag drag = (mx, my) -> apply(() -> set.accept(Color.HSBtoRGB((float) Math.clamp((mx - x0) / (x1 - x0), 0, 1), 0.55f, 1f) & 0xFFFFFF));
        hit(new Rectangle(x0, y - px(6), x1 - x0, bh + px(12)), null, drag, null);
        float[] stops = new float[7];
        Color[] colors = new Color[7];
        for (int i = 0; i < 7; i++) {
            stops[i] = i / 6f;
            colors[i] = new Color(Color.HSBtoRGB(i / 6f, 0.55f, 1f));
        }
        g.setPaint(new LinearGradientPaint(x0, 0, x1, 0, stops, colors));
        g.fill(new RoundRectangle2D.Float(x0, y, x1 - x0, bh, bh, bh));
        y += bh + px(16);
    }

    private void fontPicker(String label, String value, Consumer<String> set) {
        List<String> fonts = TextArt.families();
        // the name is written in the font itself
        picker(label, fonts, fonts, fonts.indexOf(value), set, TextArt.font(value, TextArt.uiFont()),
                "Click or scroll to browse " + fonts.size() + " fonts");
    }

    /** Which app's music to follow: Auto (whatever Windows says is playing) or one of the apps seen playing media. */
    private void sourcePicker(Settings s) {
        List<String> ids = new ArrayList<>(List.of(""));
        for (String app : NowPlaying.apps()) if (!ids.contains(app)) ids.add(app);
        if (!ids.contains(s.musicSource)) ids.add(s.musicSource); // keep a chosen app listed even while it's closed
        List<String> names = ids.stream().map(id -> id.isEmpty() ? "Auto (current app)" : NowPlaying.appName(id)).toList();
        picker("Music from", ids, names, ids.indexOf(s.musicSource), v -> s.musicSource = v, TextArt.uiFont(),
                ids.size() > 2 ? "Click or scroll to pick an app" : "Open another media app to choose between them");
    }

    /** A box with ‹ › arrows that steps through {@code values} (showing {@code shown}), by click or mouse wheel. */
    private void picker(String label, List<String> values, List<String> shown, int index, Consumer<String> set, Font sampleFont, String hint) {
        text(label, left, y + px(26), font(15, Font.PLAIN), TextArt.PILL_TEXT);
        y += px(36);
        int at = Math.max(0, index);
        IntConsumer step = d -> apply(() -> set.accept(values.get(Math.floorMod(at + d, values.size()))));
        int h = px(44), x0 = left, w = right - left, arrow = px(44);
        Rectangle box = new Rectangle(x0, y, w, h);
        g.setColor(isHovered(box) ? CARD_HOVER : CARD);
        g.fill(new RoundRectangle2D.Float(x0, y, w, h, px(14), px(14)));
        hit(new Rectangle(x0, y, arrow, h), () -> step.accept(-1), null, step);
        hit(new Rectangle(x0 + w - arrow, y, arrow, h), () -> step.accept(1), null, step);
        hit(new Rectangle(x0 + arrow, y, w - 2 * arrow, h), () -> step.accept(1), null, step);
        Font arrows = font(20, Font.BOLD);
        text("‹", x0 + px(16), y + px(30), arrows, DIM);
        text("›", x0 + w - px(26), y + px(30), arrows, DIM);
        Font sample = sampleFont.deriveFont(px(19) * 1f);
        String name = fit(shown.get(at), sample, w - 2 * arrow);
        int tw = metrics(sample).stringWidth(name);
        text(name, x0 + (w - tw) / 2, y + h / 2 + metrics(sample).getAscent() / 2 - px(3), sample, Color.WHITE);
        y += h + px(6);
        text(hint, left, y + px(14), font(12, Font.PLAIN), DIM);
        y += px(24);
    }

    private void button(String label, Runnable action) {
        int h = px(44), x0 = left, w = right - left;
        Rectangle r = hit(new Rectangle(x0, y, w, h), () -> apply(action), null, null);
        g.setColor(isHovered(r) ? CARD_HOVER : CARD);
        g.fill(new RoundRectangle2D.Float(x0, y, w, h, h, h));
        Font f = font(14, Font.BOLD);
        int tw = metrics(f).stringWidth(label);
        text(label, x0 + (w - tw) / 2, y + h / 2 + metrics(f).getAscent() / 2 - px(2), f, TextArt.PILL_TEXT);
        y += h;
    }

    /** A small grey note under a control. */
    private void hint(String text) {
        text(text, left, y + px(14), font(12, Font.PLAIN), DIM);
        y += px(24);
    }

    // ---- painting helpers ----

    private void label(String label, int rowH) {
        Font f = font(15, Font.PLAIN);
        text(label, left, y + rowH / 2 + metrics(f).getAscent() / 2 - px(2), f, TextArt.PILL_TEXT);
    }

    private Rectangle rowHit(int h, Runnable press, Drag drag, IntConsumer wheel) {
        return hit(new Rectangle(left - pad / 2, y, right - left + pad, h), press, drag, wheel);
    }

    private void rowBackground(Rectangle row) {
        if (!isHovered(row)) return;
        g.setColor(HOVER);
        g.fill(new RoundRectangle2D.Float(row.x, row.y, row.width, row.height, px(14), px(14)));
    }

    /** Records a clickable spot, only where it's inside the scrolling area (and not in a hidden section). */
    private Rectangle hit(Rectangle area, Runnable press, Drag drag, IntConsumer wheel) {
        if (hidden) return area;
        Rectangle visible = area.intersection(new Rectangle(railW, headerH, panelW - railW, panelH - headerH - footerH));
        if (!visible.isEmpty()) hits.add(new Hit(visible, press, drag, wheel));
        return area;
    }

    private boolean isHovered(Rectangle r) {
        return !hidden && r.contains(mouseX - panelX, mouseY - panelY);
    }

    private void text(String s, int x, int baseline, Font f, Color c) {
        g.setFont(f);
        g.setColor(c);
        g.drawString(s, x, baseline);
    }

    private Font font(float size, int style) {
        return TextArt.uiFont().deriveFont(style, px(size) * 1f);
    }

    private static FontMetrics metrics(Font f) {
        return TextArt.metrics(f);
    }

    private static String fit(String s, Font f, int maxWidth) {
        if (metrics(f).stringWidth(s) <= maxWidth) return s;
        while (s.length() > 1 && metrics(f).stringWidth(s + "…") > maxWidth) s = s.substring(0, s.length() - 1);
        return s + "…";
    }

    private static BufferedImage readLogo() {
        try (InputStream in = SettingsPanel.class.getResourceAsStream("/assets/mcflow/logo.png")) {
            return in == null ? null : ImageIO.read(in);
        } catch (Exception e) {
            return null;
        }
    }

    private int px(float designPx) {
        return Math.round(designPx * u);
    }

    private static void apply(Runnable change) {
        change.run();
        Settings.changed();
    }

    // ---- input (physical screen pixels) ----

    public void hover(double x, double y) {
        mouseX = x;
        mouseY = y;
        Rectangle now = hitAt(x, y) == null ? null : hitAt(x, y).area();
        if (now == null ? hovered != null : !now.equals(hovered)) dirty = true;
        hovered = now;
    }

    /** Returns true when the click landed on the panel (so the game shouldn't get it). */
    public boolean press(double x, double y) {
        int[] p = LyricsHud.pillArea;
        if (onPill(x, y)) { // grab the pill (it wins over the panel: the panel has faded away by now)
            pillGrab = new double[]{x - (p[0] + p[2] / 2.0), y - (p[1] + p[3] / 2.0)};
            return true;
        }
        if (!inside(x, y)) return false;
        Hit h = hitAt(x, y);
        if (h != null) {
            if (h.press() != null) h.press().run();
            if (h.drag() != null) {
                dragging = h;
                h.drag().to(x - panelX, y - panelY);
            }
            dirty = true;
        }
        return true;
    }

    public boolean drag(double x, double y) {
        mouseX = x;
        mouseY = y;
        if (pillGrab != null && lastW > 0 && lastH > 0) {
            // live, without Settings.changed(): that would redraw the pill and re-lay-out the lyrics every frame
            Settings s = Settings.get();
            s.pillX = (float) Math.clamp((x - pillGrab[0]) / lastW, 0, 1);
            s.pillY = (float) Math.clamp((y - pillGrab[1]) / lastH, 0, 1);
            return true;
        }
        if (dragging == null) return false;
        dragging.drag().to(x - panelX, y - panelY);
        dirty = true;
        return true;
    }

    public void release() {
        dragging = null;
        if (pillGrab != null) { // dropped: it stays there
            pillGrab = null;
            Settings.save();
            dirty = true; // the Pill page's position hint changes
        }
    }

    public boolean scroll(double x, double y, double amount) {
        if (!inside(x, y)) return false;
        Hit h = hitAt(x, y);
        if (h != null && h.wheel() != null) h.wheel().accept(amount > 0 ? -1 : 1);
        else scroll = Math.clamp(scroll - (float) amount * px(70), 0, maxScroll);
        dirty = true;
        return true;
    }

    private boolean inside(double x, double y) {
        return x >= panelX && x < panelX + panelW && y >= panelY && y < panelY + panelH;
    }

    private Hit hitAt(double x, double y) {
        for (Hit h : hits) if (h.area().contains(x - panelX, y - panelY)) return h;
        return null;
    }
}
