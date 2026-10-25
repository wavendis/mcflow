package dev.mcflow;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import java.io.IOException;

/**
 * Shows the {@link SettingsPanel} over the game, which keeps running behind it (with a demo line if nothing is
 * playing) so changes can be seen live. 1.8.9 port of the screen; the panel itself is shared.
 */
public class SettingsScreen extends GuiScreen {
    private final GuiScreen parent;
    private final SettingsPanel panel = new SettingsPanel();
    private final GuiGraphicsExtractor g = new GuiGraphicsExtractor();
    private GpuImage image;

    public SettingsScreen(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        LyricsHud.preview = true;
        panel.clipboard(GuiScreen::setClipboardString, GuiScreen::getClipboardString);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        // no dimming or blur: the world and lyrics behind are the preview
        Window window = Window.get();
        int scale = window.getGuiScale();
        panel.hover(mouseX * (double) scale, mouseY * (double) scale);
        if (image == null || panel.needsRepaint(window.getWidth(), window.getHeight())) {
            if (image != null) image.close();
            image = new GpuImage(panel.paint(window.getWidth(), window.getHeight()));
        }
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GlStateManager.disableAlpha();
        GlStateManager.pushMatrix();
        GlStateManager.scale(1f / scale, 1f / scale, 1); // draw in physical pixels, like the HUD
        image.draw(g, panel.x(), panel.y(), Math.round(panel.opacity() * 255) << 24 | 0xFFFFFF); // fades while on the pill
        GlStateManager.popMatrix();
        GlStateManager.enableAlpha();
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) throws IOException {
        int scale = Window.get().getGuiScale();
        if (!panel.press(mouseX * (double) scale, mouseY * (double) scale)) super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int button, long sinceClick) {
        int scale = Window.get().getGuiScale();
        panel.drag(mouseX * (double) scale, mouseY * (double) scale);
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        panel.release();
        super.mouseReleased(mouseX, mouseY, state);
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0) {
            // LWJGL 2 gives physical pixels from the bottom left
            double x = Mouse.getEventX(), y = Minecraft.getMinecraft().displayHeight - Mouse.getEventY() - 1;
            panel.scroll(x, y, Math.signum(wheel));
        }
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false; // the music doesn't stop, so neither should the preview
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (keyCode == 1) { // Esc: back to wherever we came from
            Minecraft.getMinecraft().displayGuiScreen(parent);
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public void onGuiClosed() {
        LyricsHud.preview = false;
        Settings.save();
        if (image != null) image.close();
        image = null;
    }
}
