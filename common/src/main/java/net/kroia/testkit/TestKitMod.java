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
    }
}
