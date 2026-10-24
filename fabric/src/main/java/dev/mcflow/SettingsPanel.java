package dev.mcflow;

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
    private Hit dragging;
    private Rectangle hovered;
    private double mouseX = -1, mouseY = -1;
    private float scroll, maxScroll;
    private boolean dirty = true;
    private int lastW, lastH, lastVersion = -1;

    // layout of the current paint, in panel pixels
    private int panelX, panelY, panelW, panelH, headerH, footerH, pad;
    private float u; // one "design pixel" at 1080p
    private Graphics2D g;
    private int y;

    public int x() {
        return panelX;
    }

    public int y() {
        return panelY;
    }

    public boolean needsRepaint(int w, int h) {
        return dirty || w != lastW || h != lastH || Settings.version() != lastVersion;
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
        headerH = px(112);
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
        g.setColor(new Color(s.accent));
        g.fillOval(pad, px(38), px(14), px(14));
        text("Mc'Flow", pad + px(26), px(52), font(26, Font.BOLD), TextArt.PILL_TEXT);
        text("Settings apply live and save automatically", pad, px(80), font(14, Font.PLAIN), DIM);

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
        text("Esc to close  ·  scroll for more  ·  K opens this anytime", pad, panelH - px(17), font(13, Font.PLAIN), DIM);
        g.dispose();
        return img;
    }

    /** Everything on the panel, top to bottom. */
    private void content(Settings s) {
        section("Lyrics");
        fontPicker("Font", s.lyricFont, f -> s.lyricFont = f);
        colorPicker("Colour", s.lyricColor, c -> s.lyricColor = c);
        slider("Size", s.lyricSize, 0.4f, 2.5f, 0.05f, String.format("%.2fx", s.lyricSize), v -> s.lyricSize = v);
        slider("Shadow", s.shadow, 0, 1, 0.05f, Math.round(s.shadow * 100) + "%", v -> s.shadow = v);
        slider("Letter spacing", s.letterSpacing, -0.05f, 0.4f, 0.01f, String.format("%+.2f", s.letterSpacing), v -> s.letterSpacing = v);
        toggle("Uppercase", s.uppercase, v -> s.uppercase = v);
        choice("Reveal", new String[]{"Word by word", "Whole line"}, s.reveal.ordinal(), i -> s.reveal = Settings.Reveal.values()[i]);
        slider("Fade in", s.fadeMs, 0, 1000, 10, s.fadeMs + " ms", v -> s.fadeMs = Math.round(v));
        slider("Fade out", s.fadeOutMs, 0, 2000, 10, s.fadeOutMs + " ms", v -> s.fadeOutMs = Math.round(v));
        choice("Fade out style", new String[]{"Whole line", "Word by word"}, s.fadeOut.ordinal(), i -> s.fadeOut = Settings.FadeOut.values()[i]);
        toggle("Float in and drift away", s.floatUp, v -> s.floatUp = v);

        section("Placement");
        choice("Where", new String[]{"Around you", "Pinned", "On screen"}, s.placement.ordinal(), i -> s.placement = Settings.Placement.values()[i]);
        choice("Layout", new String[]{"Staggered", "Centered", "One line"}, s.layout.ordinal(), i -> s.layout = Settings.Layout.values()[i]);
        if (s.layout == Settings.Layout.STAGGERED) {
            choice("First row on", new String[]{"Left", "Right"}, s.firstRowRight ? 1 : 0, i -> s.firstRowRight = i == 1);
            String spread = s.rowSpread >= 0.4f ? "opposite sides" : "gap " + Math.round(s.rowSpread * 100) + "% each side";
            slider("Row spread", s.rowSpread, 0, 0.45f, 0.005f, spread, v -> s.rowSpread = v);
        }
        boolean world = s.placement != Settings.Placement.SCREEN;
        float side = world ? s.worldYaw / 40f : (s.screenX - 0.5f) / 0.25f; // -1 left, 0 centre, 1 right
        choice("Side", new String[]{"Left", "Center", "Right"}, Math.round(Math.clamp(side, -1, 1)) + 1, i -> {
            if (world) s.worldYaw = (i - 1) * 40;
            else s.screenX = 0.5f + (i - 1) * 0.25f;
        });
        if (world) {
            slider("Angle from view", s.worldYaw, -90, 90, 1, Math.round(s.worldYaw) + "°", v -> s.worldYaw = v);
            slider("Height", s.worldRaise, -40, 60, 1, Math.round(s.worldRaise) + "°", v -> s.worldRaise = v);
            if (s.placement == Settings.Placement.PINNED) {
                slider("Distance", s.pinDistance, 4, 48, 1, Math.round(s.pinDistance) + " blocks", v -> s.pinDistance = v);
                slider("Warp up close", s.warp, 0, 1, 0.05f, s.warp == 0 ? "flat" : Math.round(s.warp * 100) + "%", v -> s.warp = v);
            }
        } else {
            slider("Horizontal", s.screenX, 0, 1, 0.01f, Math.round(s.screenX * 100) + "%", v -> s.screenX = v);
            slider("Vertical", s.screenY, 0, 1, 0.01f, Math.round(s.screenY * 100) + "%", v -> s.screenY = v);
        }

        if (world) toggle("Block detection (walls, glass, water)", s.blockDetection, v -> s.blockDetection = v);
        slider("Parallax", s.parallax, 0, 1, 0.05f, s.parallax == 0 ? "off" : Math.round(s.parallax * 100) + "%", v -> s.parallax = v);

        section("Menus");
        toggle("Blur and dim behind menus", s.menuBlur, v -> s.menuBlur = v);

        section("Beat effects");
        choice("Pulse", new String[]{"Off", "Nouns", "All words"}, s.pulse.ordinal(), i -> s.pulse = Settings.Pulse.values()[i]);
        slider("Strength", s.pulseStrength, 0, 3, 0.1f, String.format("%.1fx", s.pulseStrength), v -> s.pulseStrength = v);
        colorPicker("Pulse colour", s.pulseColor, c -> s.pulseColor = c);

        section("Now playing pill");
        toggle("Show pill", s.showPill, v -> s.showPill = v);
        choice("Position", new String[]{"Top", "Bottom"}, s.pillAtBottom ? 1 : 0, i -> s.pillAtBottom = i == 1);
        fontPicker("Font", s.uiFont, f -> s.uiFont = f);
        colorPicker("Accent", s.accent, c -> s.accent = c);
        slider("Background", s.pillOpacity, 0, 1, 0.05f, Math.round(s.pillOpacity * 100) + "%", v -> s.pillOpacity = v);
        toggle("Equalizer", s.equalizer, v -> s.equalizer = v);

        section("Timing");
        slider("Offset", s.offsetMs, -3000, 3000, 50, (s.offsetMs > 0 ? "+" : "") + s.offsetMs + " ms  ([ / ] keys)", v -> s.offsetMs = Math.round(v));
        y += px(10);
        button("Reset everything to default", Settings::reset);
        y += px(10);
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
        text(label, pad, y + px(26), font(16, Font.PLAIN), TextArt.PILL_TEXT);
        y += px(38);
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
        text(label, pad, y + px(26), font(16, Font.PLAIN), TextArt.PILL_TEXT);
        y += px(36);
        List<String> fonts = TextArt.families();
        int index = Math.max(0, fonts.indexOf(value));
        IntConsumer step = d -> apply(() -> set.accept(fonts.get(Math.floorMod(index + d, fonts.size()))));
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
        // the name is written in the font itself
        Font sample = TextArt.font(value, TextArt.uiFont()).deriveFont(px(19) * 1f);
        String name = fit(value, sample, w - 2 * arrow);
        int tw = metrics(sample).stringWidth(name);
        text(name, x0 + (w - tw) / 2, y + h / 2 + metrics(sample).getAscent() / 2 - px(3), sample, Color.WHITE);
        y += h + px(6);
        text("Click or scroll to browse " + fonts.size() + " fonts", pad, y + px(14), font(12, Font.PLAIN), DIM);
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
