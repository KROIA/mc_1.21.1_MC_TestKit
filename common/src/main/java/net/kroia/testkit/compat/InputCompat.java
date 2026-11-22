package net.kroia.testkit.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * Sends input to a screen through its real input methods. 1.21.9 turned the arguments into event records;
 * 26.1 dropped the modifiers from typed characters; 1.21.11 dropped the Minecraft argument of resize.
 * Client side only. Coordinates are GUI-scaled, like the ones a screen receives.
 */
public final class InputCompat {
    private InputCompat() {}

    public static boolean press(Screen screen, double x, double y, int button, boolean doubleClick) {
        //return screen.mouseClicked(x, y, button); // mc<=1.21.8 (no double-click flag: two quick clicks)
        return screen.mouseClicked(new net.minecraft.client.input.MouseButtonEvent(x, y, new net.minecraft.client.input.MouseButtonInfo(button, 0)), doubleClick); // mc>=1.21.9
    }

    public static boolean release(Screen screen, double x, double y, int button) {
        //return screen.mouseReleased(x, y, button); // mc<=1.21.8
        return screen.mouseReleased(new net.minecraft.client.input.MouseButtonEvent(x, y, new net.minecraft.client.input.MouseButtonInfo(button, 0))); // mc>=1.21.9
    }

    public static boolean drag(Screen screen, double x, double y, int button, double dx, double dy) {
        //return screen.mouseDragged(x, y, button, dx, dy); // mc<=1.21.8
        return screen.mouseDragged(new net.minecraft.client.input.MouseButtonEvent(x, y, new net.minecraft.client.input.MouseButtonInfo(button, 0)), dx, dy); // mc>=1.21.9
    }

    public static boolean keyPress(Screen screen, int key, int modifiers) {
        //return screen.keyPressed(key, 0, modifiers); // mc<=1.21.8
        return screen.keyPressed(new net.minecraft.client.input.KeyEvent(key, 0, modifiers)); // mc>=1.21.9 (26.3: check what the second argument must carry)
    }

    public static boolean keyRelease(Screen screen, int key, int modifiers) {
        //return screen.keyReleased(key, 0, modifiers); // mc<=1.21.8
        return screen.keyReleased(new net.minecraft.client.input.KeyEvent(key, 0, modifiers)); // mc>=1.21.9
    }

    public static boolean charTyped(Screen screen, int codePoint) {
        boolean handled = false;
        //for (char c : Character.toChars(codePoint)) handled |= screen.charTyped(c, 0); // mc<=1.21.8
        //handled = screen.charTyped(new net.minecraft.client.input.CharacterEvent(codePoint, 0)); // mc>=1.21.9 mc<=1.21.11
        handled = screen.charTyped(new net.minecraft.client.input.CharacterEvent(codePoint)); // mc>=26.1
        return handled;
    }

    public static void resize(Screen screen, int guiWidth, int guiHeight) {
        //screen.resize(Minecraft.getInstance(), guiWidth, guiHeight); // mc<=1.21.10
        screen.resize(guiWidth, guiHeight); // mc>=1.21.11
    }

    /** Sets the GUI scale option (0 = auto) and applies it like the options screen does. */
    public static void setGuiScale(int scale) {
        Minecraft mc = Minecraft.getInstance();
        mc.options.guiScale().set(scale);
        //mc.resizeDisplay(); // mc<=1.21.11
        mc.resizeGui(); // mc>=26.1
    }
}
