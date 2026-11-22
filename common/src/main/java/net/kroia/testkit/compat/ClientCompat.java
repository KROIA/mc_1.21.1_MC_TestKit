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

    public static com.mojang.blaze3d.pipeline.RenderTarget mainRenderTarget() {
        //return Minecraft.getInstance().getMainRenderTarget(); // mc<=26.1
        return Minecraft.getInstance().gameRenderer.mainRenderTarget(); // mc>=26.2
    }

    /** Reads the main render target (the last finished frame) and hands the image to {@code done}, which closes it. */
    public static void captureFrame(java.util.function.Consumer<com.mojang.blaze3d.platform.NativeImage> done) {
        //done.accept(net.minecraft.client.Screenshot.takeScreenshot(mainRenderTarget())); // mc<=1.21.4
        net.minecraft.client.Screenshot.takeScreenshot(mainRenderTarget(), done); // mc>=1.21.5 (asynchronous: the GPU read finishes later)
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
