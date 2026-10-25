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
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * The settings panel, painted with Java2D in the pill's style. It's immediate mode: every paint lays the controls out
 * from the current {@link Settings} and records where each one is, and mouse input is matched against those spots.
 * Coordinates are physical screen pixels. Minecraft-free, so both loader builds share it as-is.
 */
public final class SettingsPanel {
    private static final Color BG = new Color(16, 16, 26, 232);
    /** The mod's logo, next to the title (null if it couldn't be read: the title gets an accent dot instead). */
    private static final BufferedImage LOGO = readLogo();
    private static final Color HOVER = new Color(255, 255, 255, 14);
    private static final Color TRACK = new Color(255, 255, 255, 34);
    private static final Color DIM = new Color(150, 150, 172);
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

    // layout of the current paint, in panel pixels
    private int panelX, panelY, panelW, panelH, headerH, footerH, pad;
    private float u; // one "design pixel" at 1080p
    private Graphics2D g;
    private int y;

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
        return dirty || statusChanged || w != lastW || h != lastH || Settings.version() != lastVersion || UpdateCheck.newer() != shownUpdate;
    }

    public BufferedImage paint(int w, int h) {
        lastW = w;
        lastH = h;
        lastVersion = Settings.version();
        dirty = false;
        u = h / 1080f;
        pad = px(28);
        panelW = Math.min(px(470), w - px(40));
        panelH = h - px(60);
        panelX = w - panelW - px(30);
        panelY = px(30);
        headerH = px(160);
        footerH = px(44);

        BufferedImage img = new BufferedImage(panelW, panelH, BufferedImage.TYPE_INT_ARGB);
        g = TextArt.smooth(img);
        hits.clear();
        RoundRectangle2D shape = new RoundRectangle2D.Float(0.5f, 0.5f, panelW - 1, panelH - 1, px(30), px(30));
        g.setColor(BG);
        g.fill(shape);
        g.setColor(TextArt.PILL_BORDER);
        g.draw(shape);

        // header
        Settings s = Settings.get();
        int logoW = px(50);
        if (LOGO != null) {
            int logoH = logoW * LOGO.getHeight() / LOGO.getWidth();
            g.drawImage(LOGO, pad, px(44) - logoH / 2, logoW, logoH, null); // centred on the title's letters
        } else {
            g.setColor(new Color(s.accent));
            g.fillOval(pad, px(38), px(14), px(14));
        }
        text("Mc'Flow", pad + (LOGO != null ? logoW + px(10) : px(26)), px(54), font(26, Font.BOLD), TextArt.PILL_TEXT);
        updateBadge(s);
        text("Settings apply live and save automatically", pad, px(80), font(14, Font.PLAIN), DIM);
        tabs(px(100));

        // scrolling content
        g.setClip(0, headerH, panelW, panelH - headerH - footerH);
        y = headerH - Math.round(scroll);
        content(s);
        maxScroll = Math.max(0, y + Math.round(scroll) - (panelH - footerH) + px(20));
        scroll = Math.clamp(scroll, 0, maxScroll);
        g.setClip(null);

        // dividers under the header and above the footer, so scrolled content has a clean edge
        g.setColor(TextArt.PILL_BORDER);
        g.fillRect(pad, headerH - Math.max(1, px(1)), panelW - 2 * pad, Math.max(1, px(1)));
        // footer
        g.fillRect(pad, panelH - footerH, panelW - 2 * pad, Math.max(1, px(1)));
        if (status != null) text(status, pad, panelH - px(17), font(13, Font.BOLD), new Color(Settings.get().accent).brighter());
        else text(maxScroll > 0 ? "Esc to close  ·  scroll for more  ·  K opens this anytime" : "Esc to close  ·  K opens this anytime",
                pad, panelH - px(17), font(13, Font.PLAIN), DIM);
        g.dispose();
        return img;
    }

    /** The tabs, in order; the panel shows one at a time. Remembered between openings. */
    private static final String[] TABS = {"Look", "Motion", "Place", "Beat", "Pill", "More"};
    private static int tab;

    /** The current tab's controls, top to bottom. */
    private void content(Settings s) {
        switch (tab) {
            case 0 -> lookTab(s);
            case 1 -> motionTab(s);
            case 2 -> placeTab(s);
            case 3 -> beatTab(s);
            case 4 -> pillTab(s);
            default -> moreTab(s);
        }
        y += px(10);
    }

    private void lookTab(Settings s) {
        section("Presets");
        List<String> presets = Settings.PRESETS;
        choice("", presets.subList(0, 3).toArray(String[]::new), -1, i -> Settings.applyPreset(presets.get(i)));
        choice("", presets.subList(3, presets.size()).toArray(String[]::new), -1, i -> Settings.applyPreset(presets.get(3 + i)));
        button("Copy share code", () -> {
            if (copy != null) copy.accept(Settings.exportCode());
            flash("Share code copied - send it to a friend");
        });
        y += px(8);
        button("Paste share code", () -> flash(paste != null && Settings.importCode(paste.get()) ? "Style applied" : "No valid Mc'Flow code on the clipboard"));

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
        if (s.glow > 0 && s.albumArt && s.accentFromArt) hint("Glow colour follows the album art (Pill tab)");

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

    private void motionTab(Settings s) {
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

    private void placeTab(Settings s) {
        section("Where");
        choice("", new String[]{"Around you", "Pinned", "On screen"}, s.placement.ordinal(), i -> s.placement = Settings.Placement.values()[i]);
        hint(switch (s.placement) {
            case WORLD -> "On a sphere around you: stays where it appeared when you turn";
            case PINNED -> "A sign standing in the world: walk up to it, around it";
            case SCREEN -> "Fixed on the screen, like a classic HUD";
        });

        section("Layout");
        choice("", new String[]{"Staggered", "Centered", "One line"}, s.layout.ordinal(), i -> s.layout = Settings.Layout.values()[i]);
        if (s.layout == Settings.Layout.STAGGERED) {
            choice("First row on", new String[]{"Left", "Right"}, s.firstRowRight ? 1 : 0, i -> s.firstRowRight = i == 1);
            String spread = s.rowSpread >= 0.4f ? "opposite sides" : "gap " + Math.round(s.rowSpread * 100) + "% each side";
            slider("Row spread", s.rowSpread, 0, 0.45f, 0.005f, spread, v -> s.rowSpread = v);
        }
        choice("Old lines move", new String[]{"Up", "Down"}, s.flowDown ? 1 : 0, i -> s.flowDown = i == 1);

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

    private void beatTab(Settings s) {
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
    }

    private void pillTab(Settings s) {
        section("Now playing pill");
        toggle("Show pill", s.showPill, v -> s.showPill = v);
        choice("Position", new String[]{"Top", "Bottom"}, s.pillAtBottom ? 1 : 0, i -> s.pillAtBottom = i == 1);
        toggle("Equalizer", s.equalizer, v -> s.equalizer = v);

        section("Album art");
        toggle("Show album art", s.albumArt, v -> s.albumArt = v);
        if (s.albumArt) toggle("Colours from album art (accent, glow)", s.accentFromArt, v -> s.accentFromArt = v);

        section("Style");
        fontPicker("Font", s.uiFont, f -> s.uiFont = f);
        if (!(s.albumArt && s.accentFromArt)) colorPicker("Accent", s.accent, c -> s.accent = c);
        slider("Background", s.pillOpacity, 0, 1, 0.05f, Math.round(s.pillOpacity * 100) + "%", v -> s.pillOpacity = v);
    }

    private void moreTab(Settings s) {
        section("Music");
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

    /** Top right of the header, when GitHub has a newer release: a badge that opens the releases page. */
    private void updateBadge(Settings s) {
        UpdateCheck.start(); // once per game, in the background; the panel repaints when the answer arrives
        shownUpdate = UpdateCheck.newer();
        if (shownUpdate == null) return;
        Font f = font(13, Font.BOLD);
        String label = "New release available  ›";
        int h = px(30), w = metrics(f).stringWidth(label) + px(26), x = panelW - pad - w, top = px(44) - h / 2;
        Rectangle badge = new Rectangle(x, top, w, h);
        hits.add(new Hit(badge, UpdateCheck::openReleases, null, null)); // in the fixed header, not the scrolling part
        Color accent = new Color(s.accent);
        g.setColor(isHovered(badge) ? accent.brighter() : accent);
        g.fill(new RoundRectangle2D.Float(x, top, w, h, h, h));
        text(label, x + px(13), top + h / 2 + metrics(f).getAscent() / 2 - px(2), f, Color.WHITE);
    }

    /** A small grey note under a control. */
    private void hint(String text) {
        text(text, pad, y + px(14), font(12, Font.PLAIN), DIM);
        y += px(24);
    }

    /** The tab row under the title; it stays put while the content scrolls. */
    private void tabs(int top) {
        int h = px(38), x0 = pad, w = panelW - 2 * pad, segW = w / TABS.length;
        g.setColor(TRACK);
        g.fill(new RoundRectangle2D.Float(x0, top, w, h, h, h));
        Font f = font(14, Font.BOLD);
        for (int i = 0; i < TABS.length; i++) {
            int sx = x0 + i * segW, index = i;
            Rectangle seg = new Rectangle(sx, top, segW, h);
            hits.add(new Hit(seg, () -> {
                if (tab != index) {
                    tab = index;
                    scroll = 0;
                }
            }, null, null));
            if (i == tab || isHovered(seg)) {
                g.setColor(i == tab ? new Color(Settings.get().accent) : HOVER);
                g.fill(new RoundRectangle2D.Float(sx + px(3), top + px(3), segW - px(6), h - px(6), h - px(6), h - px(6)));
            }
            int tw = metrics(f).stringWidth(TABS[i]);
            text(TABS[i], sx + (segW - tw) / 2, top + h / 2 + metrics(f).getAscent() / 2 - px(2), f, i == tab ? Color.WHITE : TextArt.PILL_TEXT);
        }
    }

    // ---- controls ----

    private void section(String title) {
        y += px(22);
        text(title.toUpperCase(), pad, y + px(14), font(13, Font.BOLD), new Color(Settings.get().accent).brighter());
        y += px(28);
    }

    private void toggle(String label, boolean value, Consumer<Boolean> set) {
        int h = px(46);
        Rectangle row = rowHit(h, () -> apply(() -> set.accept(!value)), null, null);
        rowBackground(row);
        label(label, h);
        int tw = px(44), th = px(24), tx = panelW - pad - tw, ty = y + (h - th) / 2;
        g.setColor(value ? new Color(Settings.get().accent) : TRACK);
        g.fill(new RoundRectangle2D.Float(tx, ty, tw, th, th, th));
        int knob = th - px(6);
        g.setColor(Color.WHITE);
        g.fill(new Ellipse2D.Float(value ? tx + tw - knob - px(3) : tx + px(3), ty + px(3), knob, knob));
        y += h;
    }

    private void slider(String label, float value, float min, float max, float step, String shown, Consumer<Float> set) {
        int h = px(64);
        int x0 = pad, x1 = panelW - pad, trackY = y + px(44);
        Drag drag = (mx, my) -> apply(() -> {
            float v = min + (float) Math.clamp((mx - x0) / (x1 - x0), 0, 1) * (max - min);
            set.accept(Math.round(v / step) * step);
        });
        Rectangle row = rowHit(h, null, drag, null);
        rowBackground(row);
        text(label, pad, y + px(26), font(16, Font.PLAIN), TextArt.PILL_TEXT);
        Font vf = font(14, Font.PLAIN);
        text(shown, panelW - pad - metrics(vf).stringWidth(shown), y + px(26), vf, DIM);
        int th = px(6);
        g.setColor(TRACK);
        g.fill(new RoundRectangle2D.Float(x0, trackY - th / 2f, x1 - x0, th, th, th));
        float t = Math.clamp((value - min) / (max - min), 0, 1);
        int kx = Math.round(x0 + t * (x1 - x0));
        g.setColor(new Color(Settings.get().accent));
        g.fill(new RoundRectangle2D.Float(x0, trackY - th / 2f, kx - x0, th, th, th));
        int knob = px(18);
        g.setColor(Color.WHITE);
        g.fill(new Ellipse2D.Float(kx - knob / 2f, trackY - knob / 2f, knob, knob));
        y += h;
    }

    private void choice(String label, String[] options, int selected, IntConsumer set) {
        if (label.isEmpty()) {
            y += px(6); // a bare row of buttons (presets)
        } else {
            text(label, pad, y + px(26), font(16, Font.PLAIN), TextArt.PILL_TEXT);
            y += px(38);
        }
        int h = px(36), x0 = pad, w = panelW - 2 * pad, segW = w / options.length;
        g.setColor(TRACK);
        g.fill(new RoundRectangle2D.Float(x0, y, w, h, h, h));
        Font f = font(14, Font.PLAIN);
        for (int i = 0; i < options.length; i++) {
            int sx = x0 + i * segW, idx = i;
            Rectangle seg = hit(new Rectangle(sx, y, segW, h), () -> apply(() -> set.accept(idx)), null, null);
            if (i == selected) {
                g.setColor(new Color(Settings.get().accent));
                g.fill(new RoundRectangle2D.Float(sx + px(3), y + px(3), segW - px(6), h - px(6), h - px(6), h - px(6)));
            } else if (isHovered(seg)) {
                g.setColor(HOVER);
                g.fill(new RoundRectangle2D.Float(sx + px(3), y + px(3), segW - px(6), h - px(6), h - px(6), h - px(6)));
            }
            int tw = metrics(f).stringWidth(options[i]);
            text(options[i], sx + (segW - tw) / 2, y + h / 2 + metrics(f).getAscent() / 2 - px(2), f, i == selected ? Color.WHITE : TextArt.PILL_TEXT);
        }
        y += h + px(12);
    }

    private void colorPicker(String label, int value, IntConsumer set) {
        int h = px(40);
        label(label, h);
        int dot = px(22);
        g.setColor(new Color(value));
        g.fillOval(panelW - pad - dot, y + (h - dot) / 2, dot, dot);
        g.setColor(TextArt.PILL_BORDER);
        g.drawOval(panelW - pad - dot, y + (h - dot) / 2, dot, dot);
        y += h;

        int sw = px(26), gap = (panelW - 2 * pad - SWATCHES.length * sw) / Math.max(1, SWATCHES.length - 1);
        for (int i = 0; i < SWATCHES.length; i++) {
            int sx = pad + i * (sw + gap), c = SWATCHES[i];
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
        int x0 = pad, x1 = panelW - pad, bh = px(12);
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
        text(label, pad, y + px(26), font(16, Font.PLAIN), TextArt.PILL_TEXT);
        y += px(36);
        int at = Math.max(0, index);
        IntConsumer step = d -> apply(() -> set.accept(values.get(Math.floorMod(at + d, values.size()))));
        int h = px(44), x0 = pad, w = panelW - 2 * pad, arrow = px(44);
        Rectangle box = new Rectangle(x0, y, w, h);
        g.setColor(isHovered(box) ? new Color(255, 255, 255, 46) : TRACK);
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
        text(hint, pad, y + px(14), font(12, Font.PLAIN), DIM);
        y += px(24);
    }

    private void button(String label, Runnable action) {
        int h = px(44), x0 = pad, w = panelW - 2 * pad;
        Rectangle r = hit(new Rectangle(x0, y, w, h), () -> apply(action), null, null);
        g.setColor(isHovered(r) ? new Color(255, 255, 255, 40) : TRACK);
        g.fill(new RoundRectangle2D.Float(x0, y, w, h, h, h));
        Font f = font(15, Font.BOLD);
        int tw = metrics(f).stringWidth(label);
        text(label, x0 + (w - tw) / 2, y + h / 2 + metrics(f).getAscent() / 2 - px(2), f, TextArt.PILL_TEXT);
        y += h;
    }

    // ---- painting helpers ----

    private void label(String label, int rowH) {
        Font f = font(16, Font.PLAIN);
        text(label, pad, y + rowH / 2 + metrics(f).getAscent() / 2 - px(2), f, TextArt.PILL_TEXT);
    }

    private Rectangle rowHit(int h, Runnable press, Drag drag, IntConsumer wheel) {
        return hit(new Rectangle(pad / 2, y, panelW - pad, h), press, drag, wheel);
    }

    private void rowBackground(Rectangle row) {
        if (!isHovered(row)) return;
        g.setColor(HOVER);
        g.fill(new RoundRectangle2D.Float(row.x, row.y, row.width, row.height, px(14), px(14)));
    }

    /** Records a clickable spot, only where it's inside the scrolling area. */
    private Rectangle hit(Rectangle area, Runnable press, Drag drag, IntConsumer wheel) {
        Rectangle visible = area.intersection(new Rectangle(0, headerH, panelW, panelH - headerH - footerH));
        if (!visible.isEmpty()) hits.add(new Hit(visible, press, drag, wheel));
        return area;
    }

    private boolean isHovered(Rectangle r) {
        return r.contains(mouseX - panelX, mouseY - panelY);
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
        if (dragging == null) return false;
        dragging.drag().to(x - panelX, y - panelY);
        dirty = true;
        return true;
    }

    public void release() {
        dragging = null;
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
