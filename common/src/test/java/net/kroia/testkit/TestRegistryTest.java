package net.kroia.testkit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestRegistryTest {
    /** Test-topic selection matcher; same cases as tools/autotest/test_run_matrix.py. */
    @Test
    void topicSelection() {
        String key = "stockmarket/Trading/BotStressTestSuite";
        assertTrue(TestRegistry.isExcluded(key, null, "stockmarket/Trading/BotStressTestSuite"));
        assertTrue(TestRegistry.isExcluded(key, null, "x, stockmarket/Trading"));
        assertTrue(TestRegistry.isExcluded(key, null, "stockmarket"));
        assertTrue(TestRegistry.isExcluded(key, null, "stockmarket/*/BotStressTestSuite"));
        assertFalse(TestRegistry.isExcluded(key, null, "stockmarket/Trad"), "prefix only on '/' boundaries");
        assertFalse(TestRegistry.isExcluded(key, null, "stockmarket/Trading/BotStressTestSuite/x"));
        assertFalse(TestRegistry.isExcluded(key, null, "*/*/Other"));
        assertFalse(TestRegistry.isExcluded(key, null, null));
        assertFalse(TestRegistry.isExcluded(key, "", ""));
        assertFalse(TestRegistry.isExcluded(key, "stockmarket/*/BotStressTestSuite", null), "included");
        assertTrue(TestRegistry.isExcluded(key, "modutilities", null), "not included");
        assertTrue(TestRegistry.isExcluded(key, "stockmarket", "stockmarket/Trading"), "exclude applies on top of include");
        // on demand: only when named by category or suite
        String docs = "stockmarket/DocsShots/DocsScreenshotSuite";
        assertTrue(TestRegistry.isExcluded(docs, true, null, null), "full run");
        assertTrue(TestRegistry.isExcluded(docs, true, "stockmarket", null), "mod alone does not name it");
        assertFalse(TestRegistry.isExcluded(docs, true, "x, stockmarket/DocsShots", null));
        assertFalse(TestRegistry.isExcluded(docs, true, "stockmarket/*/DocsScreenshotSuite", null));
        assertTrue(TestRegistry.isExcluded(docs, true, "stockmarket/DocsShots", "stockmarket/DocsShots"), "exclude still wins");
        assertFalse(TestRegistry.isExcluded(docs, false, null, null), "normal suite: full run");
        // offline: in game a suite is skipped only if it is listed AND can run without the game
        assertTrue(TestRegistry.isOffline(key, false, "stockmarket/*/BotStressTestSuite"));
        assertFalse(TestRegistry.isOffline(key, true, "stockmarket"), "needs the game: stays in game");
        assertFalse(TestRegistry.isOffline(key, false, "modutilities"));
        assertFalse(TestRegistry.isOffline(key, false, null));
        assertFalse(TestRegistry.isOffline(key, false, ""));
    }
}
