package net.kroia.testkit.gui;

import net.kroia.testkit.TestKitMod;
import net.kroia.testkit.compat.ClientCompat;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import javax.imageio.ImageIO;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Scripted screenshots and GIF recordings for the documentation, taken from a client test suite (test thread, like
 * {@link Gui}). Each is taken in a fixed {@link Scene}: window size, GUI scale, time of day, clear weather, HUD hidden.
 * Everything is restored afterwards: window size, GUI scale, HUD, the time of day (advanced by the ticks the shot
 * took) and the weather state (clear, rain or thunder; the remaining weather duration is not kept, vanilla commands
 * cannot set it back). No gamerules are changed.
 * <pre>{@code
 * Gui.open(new BankScreen());
 * Path file = Screenshots.take("banksystem/bank_screen.png");
 * Path chat = Screenshots.take("shared/alert-chat.png", scene, new Rectangle(0, 700, 900, 300)); // window pixels
 * Path gif = Screenshots.record("banksystem/deposit.gif", scene, () -> {
 *     Gui.click("Send items to bank"); // the drawn pointer glides there first
 *     Gui.waitTicks(20);
 * });
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
    /** A recording captures a frame every this many client ticks: 10 frames per second. */
    private static final int FRAME_TICKS = 2;
    /** Default width a recording is scaled down to (the docs pages show GIFs about 600 px wide). */
    public static final int GIF_WIDTH = 800;

    private static volatile @Nullable Recording recording;

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

    public static Path take(String path, Scene scene) {
        return take(path, scene, null);
    }

    /**
     * Sets the scene, captures the next finished frame as PNG to {@code path}, restores the window.
     *
     * @param crop part of the window to keep, in window pixels (GUI coordinates times the GUI scale); clipped to the
     *             picture. Null: the whole window.
     */
    public static Path take(String path, Scene scene, @Nullable Rectangle crop) {
        Path file = resolve(path);
        inScene(scene, () -> {
            Gui.waitTicks(SETTLE_TICKS);
            BufferedImage image = capture();
            if (crop != null) {
                Rectangle r = crop.intersection(new Rectangle(image.getWidth(), image.getHeight()));
                if (r.isEmpty()) throw new IllegalArgumentException("crop " + crop + " is outside the "
                        + image.getWidth() + "x" + image.getHeight() + " picture");
                image = image.getSubimage(r.x, r.y, r.width, r.height);
            }
            try {
                Files.createDirectories(file.getParent());
                ImageIO.write(image, "png", file.toFile());
            } catch (IOException e) {
                throw new IllegalStateException("could not write screenshot " + file + ": " + e, e);
            }
            return null;
        });
        TestKitMod.LOGGER.info("[TestKit] screenshot written to {}", file);
        return file;
    }

    public static Path record(String path, Scene scene, Runnable script) {
        return record(path, scene, GIF_WIDTH, script);
    }

    /**
     * Sets the scene and records an animated GIF to {@code path} while {@code script} runs on this thread: a frame
     * every {@link #FRAME_TICKS} client ticks (10 per second, played back at that rate), scaled down to at most
     * {@code maxWidth}. While a screen is open, a mouse pointer is drawn where the {@link Gui} driver points; its moves
     * glide over a few frames. Keep it short (5-10 s): every frame costs a GPU read. Restores the scene afterwards.
     */
    public static Path record(String path, Scene scene, int maxWidth, Runnable script) {
        Path file = resolve(path);
        if (recording != null) throw new IllegalStateException("a recording is already running");
        int frames = inScene(scene, () -> {
            Gui.waitTicks(SETTLE_TICKS);
            Recording r;
            try {
                r = new Recording(new GifWriter(file, maxWidth, FRAME_TICKS * 50));
            } catch (IOException e) {
                throw new IllegalStateException("cannot write " + file + ": " + e, e);
            }
            recording = r;
            try {
                script.run();
                Gui.waitTicks(FRAME_TICKS); // the last state gets a frame
            } finally {
                recording = null;
                r.finish();
            }
            return r.gif.frames();
        });
        long size = file.toFile().length();
        TestKitMod.LOGGER.info("[TestKit] recording written to {} ({} frames, {} KB)", file, frames, size / 1024);
        return file;
    }

    /** @return true while {@link #record} captures frames. */
    public static boolean isRecording() {
        return recording != null;
    }

    /** Client thread, every client tick (from {@link Gui#onClientTick}). */
    static void onClientTick() {
        Recording r = recording;
        if (r != null && r.ticks++ % FRAME_TICKS == 0) r.capture();
    }

    /** Frames go from the client thread to one writer thread, so encoding never stalls the game. */
    private static final class Recording {
        final GifWriter gif;
        final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "TestKit-GIF-Writer");
            t.setDaemon(true);
            return t;
        });
        int ticks;
        volatile @Nullable Throwable error;

        Recording(GifWriter gif) {
            this.gif = gif;
        }

        /** Client thread. */
        void capture() {
            double[] p = Gui.pointerFraction();
            ClientCompat.captureFrame(image -> {
                try {
                    writer.execute(() -> {
                        try (image) {
                            gif.add(ClientCompat.toImage(image), p == null ? Double.NaN : p[0], p == null ? Double.NaN : p[1]);
                        } catch (IOException | RuntimeException e) {
                            if (error == null) error = e;
                        }
                    });
                } catch (RejectedExecutionException e) {
                    image.close(); // captured after the recording finished
                }
            });
        }

        /** Test thread: lets queued frames finish, closes the file; throws if a frame failed. */
        void finish() {
            Gui.waitTicks(2); // from 1.21.5 the GPU read of the last frame completes a frame later
            writer.shutdown();
            try {
                if (!writer.awaitTermination(120, TimeUnit.SECONDS)) error = new TimeoutException("GIF frames not written within 120 s");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            try {
                gif.close();
            } catch (IOException e) {
                if (error == null) error = e;
            }
            if (error != null) throw new IllegalStateException("recording failed: " + error, error);
        }
    }

    /** Sets the scene, runs {@code action} (test thread) and restores everything, also when it throws. */
    private static <T> T inScene(Scene scene, Supplier<T> action) {
        long[] world = Gui.onClient(() -> {
            var level = Minecraft.getInstance().level;
            if (level == null) throw new IllegalStateException("no world: screenshots need a joined world");
            return new long[]{ClientCompat.dayTime(level), level.getGameTime(), level.isThundering() ? 2 : level.isRaining() ? 1 : 0};
        });
        // A minimized window reports 0x0 and ignores resizes: restore it first, minimize it again afterwards.
        boolean minimized = Gui.onClient(() -> {
            long h = ClientCompat.windowHandle();
            if (GLFW.glfwGetWindowAttrib(h, GLFW.GLFW_ICONIFIED) == 0) return false;
            GLFW.glfwRestoreWindow(h);
            return true;
        });
        if (minimized) Gui.waitUntil(() -> Minecraft.getInstance().getWindow().getScreenWidth() > 0, RESIZE_TICKS);
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
            return action.get();
        } finally {
            Gui.onClient(() -> {
                if (old[0] > 0 && old[1] > 0) Minecraft.getInstance().getWindow().setWindowed(old[0], old[1]);
                if (minimized) GLFW.glfwIconifyWindow(ClientCompat.windowHandle());
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

    /** The last finished frame. */
    private static BufferedImage capture() {
        CompletableFuture<BufferedImage> done = new CompletableFuture<>();
        Gui.onClient(() -> ClientCompat.captureFrame(image -> {
            try (image) {
                done.complete(ClientCompat.toImage(image));
            } catch (RuntimeException e) {
                done.completeExceptionally(e);
            }
        }));
        try {
            return done.get(60, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw new IllegalStateException("could not capture the screenshot: " + e.getCause(), e.getCause());
        } catch (TimeoutException e) {
            throw new IllegalStateException("the screenshot was not captured within 60 s");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted");
        }
    }
}
