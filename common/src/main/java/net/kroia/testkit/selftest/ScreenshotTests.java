package net.kroia.testkit.selftest;

import net.kroia.testkit.TestCategory;
import net.kroia.testkit.TestKitMod;
import net.kroia.testkit.TestResult;
import net.kroia.testkit.TestSuite;
import net.kroia.testkit.compat.ClientCompat;
import net.kroia.testkit.gui.Gui;
import net.kroia.testkit.gui.Screenshots;
import net.minecraft.client.Minecraft;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * TestKit's own check of {@link Screenshots}, run on the game client like the mods' GUI suites. Client only.
 * TK-16: a screenshot taken while the game window is minimized still has the scene size, and afterwards the window is
 * minimized again and comes back at its old size.
 */
public class ScreenshotTests extends TestSuite {
    private static final TestCategory CATEGORY = new TestCategory(TestKitMod.MOD_ID, "selftest",
            "TestKit's own checks of its test helpers", TestCategory.ServerType.BOTH, true);
    private static final int WINDOW_TICKS = 40;

    @Override
    public TestCategory getCategory() {
        return CATEGORY;
    }

    @Override
    public Side side() {
        return Side.CLIENT;
    }

    @Override
    public void registerTests() {
        addTest("screenshot_from_minimized_window", this::fromMinimizedWindow);
    }

    private TestResult fromMinimizedWindow() {
        int[] before = Gui.onClient(ScreenshotTests::screenSize);
        Path file = null;
        try {
            file = Files.createTempFile("testkit-minimized-", ".png");
            Gui.onClient(ClientCompat::minimizeWindow);
            if (!Gui.waitUntil(ClientCompat::isWindowMinimized, WINDOW_TICKS)) return fail("the window did not minimize");
            Screenshots.take(file.toString());
            BufferedImage image = ImageIO.read(file.toFile());
            Screenshots.Scene scene = Screenshots.Scene.DEFAULT;
            if (image.getWidth() != scene.windowWidth() || image.getHeight() != scene.windowHeight())
                return fail("screenshot is " + image.getWidth() + "x" + image.getHeight() + ", expected "
                        + scene.windowWidth() + "x" + scene.windowHeight());
            if (!Gui.onClient(ClientCompat::isWindowMinimized)) return fail("the window is not minimized again after the shot");
            Gui.onClient(ClientCompat::restoreWindow);
            if (!Gui.waitUntil(() -> java.util.Arrays.equals(screenSize(), before), WINDOW_TICKS)) {
                int[] got = Gui.onClient(ScreenshotTests::screenSize);
                return fail("restored window is " + got[0] + "x" + got[1] + ", expected " + before[0] + "x" + before[1]);
            }
            return pass("scene-size shot from a minimized window, minimized again, restored at " + before[0] + "x" + before[1]);
        } catch (IOException e) {
            return fail("screenshot file: " + e);
        } finally {
            Gui.onClient(() -> {
                if (ClientCompat.isWindowMinimized()) ClientCompat.restoreWindow();
            });
            if (file != null) file.toFile().delete();
        }
    }

    private static int[] screenSize() {
        var w = Minecraft.getInstance().getWindow();
        return new int[]{w.getScreenWidth(), w.getScreenHeight()};
    }
}
