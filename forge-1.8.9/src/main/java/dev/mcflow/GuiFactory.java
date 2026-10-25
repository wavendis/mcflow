package dev.mcflow;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.fml.client.IModGuiFactory;

import java.util.Set;

/** The "Config" button in the Mods list opens Mc'Flow's settings. */
public class GuiFactory implements IModGuiFactory {
    @Override
    public void initialize(Minecraft mc) {}

    @Override
    public Class<? extends GuiScreen> mainConfigGuiClass() {
        return SettingsScreen.class; // FML calls its (GuiScreen parent) constructor
    }

    @Override
    public Set<RuntimeOptionCategoryElement> runtimeGuiCategories() {
        return null;
    }

    @Override
    @SuppressWarnings("deprecation")
    public RuntimeOptionGuiHandler getHandlerFor(RuntimeOptionCategoryElement element) {
        return null;
    }
}
