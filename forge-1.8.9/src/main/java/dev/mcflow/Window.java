package dev.mcflow;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;

/** 1.8.9 compatibility: the modern {@code Window} API (size in physical pixels, GUI scale) the shared code uses. */
final class Window {
    private static final Window INSTANCE = new Window();

    private Window() {}

    static Window get() {
        return INSTANCE;
    }

    int getWidth() {
        return Minecraft.getMinecraft().displayWidth;
    }

    int getHeight() {
        return Minecraft.getMinecraft().displayHeight;
    }

    int getGuiScale() {
        return new ScaledResolution(Minecraft.getMinecraft()).getScaleFactor();
    }
}
