package net.kroia.testkit.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/** Client screen access; copied from ModUtilities' ClientCompat with its version toggles. Client side only. */
public final class ClientCompat {
    private ClientCompat() {}

    public static Screen screen() {
        //return Minecraft.getInstance().screen; // mc<=26.1
        return Minecraft.getInstance().gui.screen(); // mc>=26.2
    }

    public static void setScreen(Screen screen) {
        //Minecraft.getInstance().setScreen(screen); // mc<=26.1
        Minecraft.getInstance().gui.setScreen(screen); // mc>=26.2
    }

    /** GLFW handle of the game window. */
    public static long windowHandle() {
        //return Minecraft.getInstance().getWindow().getWindow(); // mc<=1.21.8
        return Minecraft.getInstance().getWindow().handle(); // mc>=1.21.9
    }

    public static com.mojang.blaze3d.pipeline.RenderTarget mainRenderTarget() {
        //return Minecraft.getInstance().getMainRenderTarget(); // mc<=26.1
        return Minecraft.getInstance().gameRenderer.mainRenderTarget(); // mc>=26.2
    }

    /** Reads the main render target (the last finished frame) and hands the image to {@code done}, which closes it. */
    public static void captureFrame(java.util.function.Consumer<com.mojang.blaze3d.platform.NativeImage> done) {
        //done.accept(net.minecraft.client.Screenshot.takeScreenshot(mainRenderTarget())); // mc<=1.21.4
        net.minecraft.client.Screenshot.takeScreenshot(mainRenderTarget(), done); // mc>=1.21.5 (asynchronous: the GPU read finishes later)
    }

    /** Copies a captured frame (RGBA) into an opaque Java image; does not close {@code image}. */
    public static java.awt.image.BufferedImage toImage(com.mojang.blaze3d.platform.NativeImage image) {
        int w = image.getWidth(), h = image.getHeight();
        //int[] p = image.getPixelsRGBA(); // mc<=1.21.1 (ABGR ints)
        int[] p = image.getPixelsABGR(); // mc>=1.21.2
        for (int i = 0; i < p.length; i++) {
            int c = p[i];
            p[i] = (c & 0xFF00) | ((c & 0xFF) << 16) | ((c >> 16) & 0xFF); // ABGR -> RGB
        }
        java.awt.image.BufferedImage out = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
        out.setRGB(0, 0, w, h, p, 0, w);
        return out;
    }

    private static java.lang.reflect.Field mouseX, mouseY;

    /**
     * Moves Minecraft's idea of the mouse (MouseHandler xpos/ypos, what screens get as mouseX/mouseY when they render,
     * so hover effects and tooltips) to a GUI-scaled point. The OS cursor stays where it is; moving the real mouse
     * over the window overrides it. Reflection: the fields have these names in every dev runtime (Mojang mappings).
     */
    public static void setMousePos(double guiX, double guiY) {
        var w = Minecraft.getInstance().getWindow();
        try {
            if (mouseX == null) {
                java.lang.reflect.Field x = net.minecraft.client.MouseHandler.class.getDeclaredField("xpos");
                java.lang.reflect.Field y = net.minecraft.client.MouseHandler.class.getDeclaredField("ypos");
                x.setAccessible(true);
                y.setAccessible(true);
                mouseX = x;
                mouseY = y;
            }
            mouseX.setDouble(Minecraft.getInstance().mouseHandler, guiX * w.getScreenWidth() / w.getGuiScaledWidth());
            mouseY.setDouble(Minecraft.getInstance().mouseHandler, guiY * w.getScreenHeight() / w.getGuiScaledHeight());
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new IllegalStateException("cannot set MouseHandler.xpos/ypos (renamed in this Minecraft version?): " + e, e);
        }
    }

    /** Swings the main hand like a successful right-click does. */
    public static void swingMainHand(net.minecraft.client.player.LocalPlayer player) {
        player.swing(net.minecraft.world.InteractionHand.MAIN_HAND); // mc<=26.2
        //player.swing(net.minecraft.world.InteractionHand.MAIN_HAND, player.getMainHandItem().getInteractAnimation(), false); // mc>=26.3
    }

    /** Time of day of the client world, as {@code /time set} sets it. */
    public static long dayTime(net.minecraft.client.multiplayer.ClientLevel level) {
        //return level.getDayTime(); // mc<=1.21.11
        return level.getOverworldClockTime(); // mc>=26.1 (world clocks replaced the day time)
    }

    public static boolean isHudHidden() {
        //return Minecraft.getInstance().options.hideGui; // mc<=26.1
        return Minecraft.getInstance().gui.hud.isHidden(); // mc>=26.2
    }

    /** F1: hides the HUD (hotbar, crosshair, chat); an open screen still renders. */
    public static void setHudHidden(boolean hidden) {
        //Minecraft.getInstance().options.hideGui = hidden; // mc<=26.1
        if (Minecraft.getInstance().gui.hud.isHidden() != hidden) Minecraft.getInstance().gui.hud.toggle(); // mc>=26.2
    }
}
