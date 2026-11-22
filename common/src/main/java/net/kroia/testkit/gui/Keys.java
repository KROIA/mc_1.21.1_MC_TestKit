package net.kroia.testkit.gui;

/**
 * Named key, mouse button and modifier constants for GUI tests. Never use raw numbers or GLFW constants:
 * 26.3 (SDL3) changed every value. The keys alias Minecraft's own constants, so they follow the version.
 */
public final class Keys {
    private Keys() {}

    public static final int MOUSE_LEFT   = com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT;
    public static final int MOUSE_RIGHT  = com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_RIGHT;
    public static final int MOUSE_MIDDLE = com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_MIDDLE;

    public static final int ESCAPE    = com.mojang.blaze3d.platform.InputConstants.KEY_ESCAPE;
    public static final int ENTER     = com.mojang.blaze3d.platform.InputConstants.KEY_RETURN;
    public static final int TAB       = com.mojang.blaze3d.platform.InputConstants.KEY_TAB;
    public static final int BACKSPACE = com.mojang.blaze3d.platform.InputConstants.KEY_BACKSPACE;
    public static final int DELETE    = com.mojang.blaze3d.platform.InputConstants.KEY_DELETE;
    public static final int LEFT      = com.mojang.blaze3d.platform.InputConstants.KEY_LEFT;
    public static final int RIGHT     = com.mojang.blaze3d.platform.InputConstants.KEY_RIGHT;
    public static final int UP        = com.mojang.blaze3d.platform.InputConstants.KEY_UP;
    public static final int DOWN      = com.mojang.blaze3d.platform.InputConstants.KEY_DOWN;
    public static final int HOME      = com.mojang.blaze3d.platform.InputConstants.KEY_HOME;
    public static final int END       = com.mojang.blaze3d.platform.InputConstants.KEY_END;
    public static final int PAGE_UP   = com.mojang.blaze3d.platform.InputConstants.KEY_PAGEUP;
    public static final int PAGE_DOWN = com.mojang.blaze3d.platform.InputConstants.KEY_PAGEDOWN;
    public static final int A = com.mojang.blaze3d.platform.InputConstants.KEY_A;
    public static final int C = com.mojang.blaze3d.platform.InputConstants.KEY_C;
    public static final int V = com.mojang.blaze3d.platform.InputConstants.KEY_V;
    public static final int X = com.mojang.blaze3d.platform.InputConstants.KEY_X;

    public static final int MOD_NONE = 0;
    //public static final int MOD_SHIFT   = 1; // mc<=26.2
    //public static final int MOD_CONTROL = 2; // mc<=26.2
    //public static final int MOD_ALT     = 4; // mc<=26.2
    public static final int MOD_SHIFT   = com.mojang.blaze3d.platform.InputConstants.MOD_SHIFT; // mc>=26.3
    public static final int MOD_CONTROL = com.mojang.blaze3d.platform.InputConstants.MOD_CONTROL; // mc>=26.3
    public static final int MOD_ALT     = com.mojang.blaze3d.platform.InputConstants.MOD_ALT; // mc>=26.3
}
