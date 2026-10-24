package dev.mcflow;

import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Shows the {@link SettingsPanel} over the game, which keeps running behind it (with a demo line if nothing is
 * playing) so changes can be seen live. Only this thin wrapper differs between loader builds.
 */
public class SettingsScreen extends Screen {
    private final Screen parent;
    private final SettingsPanel panel = new SettingsPanel();
    private GpuImage image;

    public SettingsScreen(Screen parent) {
        super(Component.literal("Mc'Flow"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        LyricsHud.preview = true;
        panel.clipboard(text -> minecraft.keyboardHandler.setClipboard(text), () -> minecraft.keyboardHandler.getClipboard());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float a) {
        Window window = Minecraft.getInstance().getWindow();
        int scale = window.getGuiScale();
        panel.hover(mouseX * (double) scale, mouseY * (double) scale);
        if (image == null || panel.needsRepaint(window.getWidth(), window.getHeight())) {
            if (image != null) image.close();
            image = new GpuImage(panel.paint(window.getWidth(), window.getHeight()));
        }
        g.pose().pushMatrix();
        g.pose().scale(1f / scale, 1f / scale); // draw in physical pixels, like the HUD
        image.draw(g, panel.x(), panel.y(), 0xFFFFFFFF);
        g.pose().popMatrix();
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float a) {
        // no dimming or blur: the world and lyrics behind are the preview
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double scale = Minecraft.getInstance().getWindow().getGuiScale();
        return panel.press(event.x() * scale, event.y() * scale) || super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        double scale = Minecraft.getInstance().getWindow().getGuiScale();
        return panel.drag(event.x() * scale, event.y() * scale) || super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        panel.release();
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        double scale = Minecraft.getInstance().getWindow().getGuiScale();
        return panel.scroll(x * scale, y * scale, scrollY) || super.mouseScrolled(x, y, scrollX, scrollY);
    }

    @Override
    public boolean isPauseScreen() {
        return false; // the music doesn't stop, so neither should the preview
    }

    @Override
    public void onClose() {
        if (parent != null) Minecraft.getInstance().setScreen(parent);
        else super.onClose();
    }

    @Override
    public void removed() {
        LyricsHud.preview = false;
        Settings.save();
        if (image != null) image.close();
        image = null;
    }
}
