package net.kroia.testkit.gui;

import net.minecraft.client.gui.screens.Screen;

import java.util.List;

/**
 * Exposes a screen's own element tree to the GUI driver. TestKit knows only vanilla widgets; a mod with its own
 * GUI framework (ModUtilities) implements this in its dev code and registers it with {@link Gui#addLocator}.
 * Input still goes to the screen's real input methods, so the elements see exactly what a player's input gives.
 */
public interface GuiLocator {
    /** @return the root nodes of this screen's element tree, or an empty list if the screen is not of this kind. */
    List<GuiNode> nodes(Screen screen);
}
