package net.kroia.testkit.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/** Client screen access; copied from ModUtilities' ClientCompat with its version toggles. Client side only. */
public final class ClientCompat {
    private ClientCompat() {}

    public static Screen screen() {
        return Minecraft.getInstance().screen; // mc<=26.1
        //return Minecraft.getInstance().gui.screen(); // mc>=26.2
    }

    public static void setScreen(Screen screen) {
        Minecraft.getInstance().setScreen(screen); // mc<=26.1
        //Minecraft.getInstance().gui.setScreen(screen); // mc>=26.2
    }
}
