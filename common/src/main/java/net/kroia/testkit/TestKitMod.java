package net.kroia.testkit;

import net.kroia.testkit.autotest.AutoTest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class TestKitMod {
    public static final String MOD_ID = "testkit";
    public static final Logger LOGGER = LoggerFactory.getLogger("TestKit");

    private TestKitMod() {}

    public static void init() {
        LOGGER.info("TestKit loaded (dev-only)");
        AutoTest.init();
        // TestKit's own GUI suite, on the client only (a dedicated server never loads its client classes)
        if (dev.architectury.platform.Platform.getEnvironment() == dev.architectury.utils.Env.CLIENT)
            TestRegistry.register(new net.kroia.testkit.selftest.ScreenshotTests());
    }
}
