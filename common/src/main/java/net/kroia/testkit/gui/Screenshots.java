package net.kroia.testkit.gui;

import com.mojang.blaze3d.platform.NativeImage;
import net.kroia.testkit.TestKitMod;
import net.kroia.testkit.compat.ClientCompat;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Scripted screenshots for the documentation, taken from a client test suite (test thread, like {@link Gui}).
 * Each shot is taken in a fixed {@link Scene}: window size, GUI scale, time of day, clear weather, HUD hidden.
 * Everything is restored afterwards: window size, GUI scale, HUD, the time of day (advanced by the ticks the shot
 * took) and the weather state (clear, rain or thunder; the remaining weather duration is not kept, vanilla commands
 * cannot set it back). No gamerules are changed.
 * <pre>{@code
 * Gui.open(new BankScreen());
 * Path file = Screenshots.take("banksystem/bank_screen.png");
 * }</pre>
 * Relative paths resolve against {@code -Dmodutilities.autotest.screenshots=<dir>} (e.g. the mod's documentation
 * folder), else {@code <game dir>/screenshots/testkit}. Time and weather are set with commands, so the player
 * must be allowed to run {@code /time} and {@code /weather} (autotest ops every player).
 */
public final class Screenshots {
    private Screenshots() {}

    public static final String ROOT_PROPERTY = "modutilities.autotest.screenshots";
    /** Ticks a window resize gets to arrive (the OS resizes asynchronously). */
    private static final int RESIZE_TICKS = 40;
    /** Ticks after the scene is set, so several frames with it are rendered before the capture. */
    private static final int SETTLE_TICKS = 5;

    /**
     * @param dayTime  {@code /time set} value (6000 = noon)
     * @param guiScale GUI scale option (0 = auto)
     */
    public record Scene(int windowWidth, int windowHeight, int guiScale, long dayTime, boolean hideHud) {
        /** 1280x720, GUI scale 2, noon, HUD hidden. */
        public static final Scene DEFAULT = new Scene(1280, 720, 2, 6000, true);
    }

    public static Path take(String path) {
        return take(path, Scene.DEFAULT);
    }

    /** Sets the scene, captures the next finished frame as PNG to {@code path}, restores the window. */
    public static Path take(String path, Scene scene) {
        Path file = resolve(path);
        long[] world = Gui.onClient(() -> {
            var level = Minecraft.getInstance().level;
            if (level == null) throw new IllegalStateException("no world: screenshots need a joined world");
            return new long[]{ClientCompat.dayTime(level), level.getGameTime(), level.isThundering() ? 2 : level.isRaining() ? 1 : 0};
        });
        int[] old = Gui.onClient(() -> {
            var w = Minecraft.getInstance().getWindow();
            return new int[]{w.getScreenWidth(), w.getScreenHeight(), Minecraft.getInstance().options.guiScale().get(), ClientCompat.isHudHidden() ? 1 : 0};
        });
        try {
            Gui.sendCommand("time set " + scene.dayTime());
            Gui.sendCommand("weather clear");
            Gui.onClient(() -> {
                Minecraft.getInstance().getWindow().setWindowed(scene.windowWidth(), scene.windowHeight());
                ClientCompat.setHudHidden(scene.hideHud());
            });
            if (!Gui.waitUntil(() -> {
                var w = Minecraft.getInstance().getWindow();
                return w.getWidth() == scene.windowWidth() && w.getHeight() == scene.windowHeight();
            }, RESIZE_TICKS)) {
                int[] got = Gui.onClient(() -> new int[]{Minecraft.getInstance().getWindow().getWidth(), Minecraft.getInstance().getWindow().getHeight()});
                throw new IllegalStateException("window did not become " + scene.windowWidth() + "x" + scene.windowHeight()
                        + " (framebuffer is " + got[0] + "x" + got[1] + "; screen too small or display scaling on?)");
            }
            Gui.setGuiScale(scene.guiScale());
            Gui.waitTicks(SETTLE_TICKS);
            capture(file);
            TestKitMod.LOGGER.info("[TestKit] screenshot written to {}", file);
            return file;
        } finally {
            Gui.onClient(() -> {
                Minecraft.getInstance().getWindow().setWindowed(old[0], old[1]);
                ClientCompat.setHudHidden(old[3] == 1);
            });
            Gui.setGuiScale(old[2]);
            long elapsed = Gui.onClient(() -> Minecraft.getInstance().level.getGameTime()) - world[1];
            Gui.sendCommand("time set " + (world[0] + elapsed));
            Gui.sendCommand(world[2] == 2 ? "weather thunder" : world[2] == 1 ? "weather rain" : "weather clear");
        }
    }

    private static Path resolve(String path) {
        Path p = Paths.get(path);
        if (p.isAbsolute()) return p;
        String root = System.getProperty(ROOT_PROPERTY);
        Path base = root != null ? Paths.get(root) : Minecraft.getInstance().gameDirectory.toPath().resolve("screenshots").resolve("testkit");
        return base.resolve(p).toAbsolutePath().normalize();
    }

    private static void capture(Path file) {
        CompletableFuture<Void> written = new CompletableFuture<>();
        Gui.onClient(() -> ClientCompat.captureFrame(image -> {
            try (NativeImage img = image) {
                Files.createDirectories(file.getParent());
                img.writeToFile(file);
                written.complete(null);
            } catch (IOException | RuntimeException e) {
                written.completeExceptionally(e);
            }
        }));
        try {
            written.get(60, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw new IllegalStateException("could not write screenshot " + file + ": " + e.getCause(), e.getCause());
        } catch (TimeoutException e) {
            throw new IllegalStateException("screenshot " + file + " was not captured within 60 s");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted");
        }
    }
}
