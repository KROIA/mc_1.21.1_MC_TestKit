package net.kroia.testkit;

import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

public abstract class TestSuite {

    private final Map<String, Supplier<TestResult>> tests = new LinkedHashMap<>();
    private @Nullable MinecraftServer server;

    public abstract TestCategory getCategory();

    public abstract void registerTests();

    public void setup() {
    }

    public void teardown() {
    }

    /**
     * False: this suite also runs offline in {@code gradlew :common:test} ({@link OfflineTests}), without a Minecraft
     * server ({@link #getServer()} is null) and without a mod loader. Override only after it passed there.
     */
    public boolean needsGame() {
        return true;
    }

    /**
     * True: the suite runs only when a test selection names it, by category or suite ({@code --only <modid>/<Category>}
     * or deeper; {@code --only <modid>} is not enough). Normal runs report it as excluded. For long or
     * window-changing suites such as the docs shots.
     */
    public boolean onDemand() {
        return false;
    }

    /** Where and on which thread a suite runs. */
    public enum Side {
        /** On the server thread, with the other server suites (the default). */
        SERVER,
        /**
         * GUI suite. It runs only on the game client, on its own test thread, after the client joined and
         * before the server's suites start; {@link #getServer()} is null. Drive the GUI with
         * {@link net.kroia.testkit.gui.Gui}. Register such suites only on the client
         * ({@code Platform.getEnvironment() == Env.CLIENT}), so a dedicated server never loads their client classes.
         */
        CLIENT,
        /**
         * Suite that waits for ticks: world tests ({@link net.kroia.testkit.world.TestArea}) and scenarios across
         * servers ({@link net.kroia.testkit.scenario.Scenario}). It runs on every server of the setup, on its own test
         * thread, after the server suites, so the server keeps ticking while a test waits. Touch the server only
         * through those helpers or {@link net.kroia.testkit.scenario.Scenario#onServer}.
         */
        SCENARIO,
        /**
         * The game client's part of a {@link net.kroia.testkit.scenario.Scenario} (several real clients, TK-7). Runs on
         * the client's test thread after its GUI suites; call {@link net.kroia.testkit.scenario.Scenario#runOnClient}.
         * {@link #getServer()} is null. Register such suites only on the client, like {@link #CLIENT}. Build the
         * scenario in shared code so servers and clients get the same step list, and keep the client steps' bodies in a
         * client-only class: a dedicated server then never loads GUI classes.
         */
        CLIENT_SCENARIO;

        /** @return true for the sides that run on the game client (no server category check). */
        public boolean onClient() {
            return this == CLIENT || this == CLIENT_SCENARIO;
        }
    }

    public Side side() {
        return Side.SERVER;
    }

    protected void addTest(String name, Supplier<TestResult> test) {
        tests.put(name, test);
    }

    public Map<String, Supplier<TestResult>> getTests() {
        return tests;
    }

    public int getTestCount() {
        return tests.size();
    }

    public void setServer(@Nullable MinecraftServer server) {
        this.server = server;
    }

    protected @Nullable MinecraftServer getServer() {
        return server;
    }

    // ========================================================================
    // Assertion helpers
    // ========================================================================

    protected TestResult pass(String message) {
        return TestResult.pass("", message);
    }

    protected TestResult fail(String message) {
        return TestResult.fail("", message);
    }

    /** Test did not run because a precondition is missing (e.g. an optional mod); counted as skipped, not failed. */
    protected TestResult skip(String reason) {
        return TestResult.skip("", reason);
    }

    /**
     * Waits for {@code future} while the server keeps running its queued tasks (on the server thread, via
     * {@code managedBlock}), so work the future needs on the server thread, or callbacks it schedules there,
     * cannot deadlock against the waiting test. Off the server thread (or without a server) it simply blocks.
     * The server does not tick meanwhile (see {@link #pumpUntil}).
     *
     * @return the result
     * @throws IllegalStateException on timeout; a failed future rethrows its cause wrapped
     */
    protected <T> T await(CompletableFuture<T> future, long timeoutMillis) {
        pumpUntil(future::isDone, timeoutMillis);
        if (!future.isDone())
            throw new IllegalStateException("timed out after " + timeoutMillis + " ms");
        return future.join();
    }

    /**
     * Runs queued server tasks until {@code done} is true or the timeout passed. From MC 1.21.9 on it also
     * handles queued incoming packets, so client requests are answered while the test waits.
     * The server does not tick meanwhile: work done in tick events (e.g. order matching) waits until the test
     * returns. To wait for such work in a scenario, use {@link net.kroia.testkit.scenario.Scenario#stepUntil}.
     *
     * @return {@code done} at the end
     */
    protected boolean pumpUntil(BooleanSupplier done, long timeoutMillis) {
        long end = System.nanoTime() + timeoutMillis * 1_000_000L;
        MinecraftServer s = server;
        if (s != null && s.isSameThread()) {
            s.managedBlock(() -> done.getAsBoolean() || System.nanoTime() > end); // mc<=1.21.8
            //s.managedBlock(() -> { s.packetProcessor().processQueuedPackets(); return done.getAsBoolean() || System.nanoTime() > end; }); // mc>=1.21.9 (incoming packets wait in packetProcessor, which managedBlock does not drain)
        } else {
            while (!done.getAsBoolean() && System.nanoTime() < end) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return done.getAsBoolean();
    }

    /** Runs queued server tasks for {@code millis} (lets async callbacks that hop to the server thread arrive); no server tick meanwhile. */
    protected void pump(long millis) {
        pumpUntil(() -> false, millis);
    }

    protected TestResult assertEquals(Object expected, Object actual) {
        if (expected == null && actual == null) {
            return pass("Both values are null");
        }
        if (expected != null && expected.equals(actual)) {
            return pass("Values match: " + expected);
        }
        return TestResult.fail("",
                "assertEquals failed",
                String.valueOf(expected),
                String.valueOf(actual));
    }

    protected TestResult assertEquals(String message, Object expected, Object actual) {
        if (expected == null && actual == null) {
            return pass(message);
        }
        if (expected != null && expected.equals(actual)) {
            return pass(message);
        }
        return TestResult.fail("",
                message,
                String.valueOf(expected),
                String.valueOf(actual));
    }

    protected TestResult assertTrue(boolean condition) {
        if (condition) {
            return pass("Condition is true");
        }
        return TestResult.fail("", "assertTrue failed", "true", "false");
    }

    protected TestResult assertTrue(String message, boolean condition) {
        if (condition) {
            return pass(message);
        }
        return TestResult.fail("", message, "true", "false");
    }

    protected TestResult assertFalse(boolean condition) {
        if (!condition) {
            return pass("Condition is false");
        }
        return TestResult.fail("", "assertFalse failed", "false", "true");
    }

    protected TestResult assertFalse(String message, boolean condition) {
        if (!condition) {
            return pass(message);
        }
        return TestResult.fail("", message, "false", "true");
    }

    protected TestResult assertNotNull(Object object) {
        if (object != null) {
            return pass("Object is not null");
        }
        return TestResult.fail("", "assertNotNull failed", "non-null", "null");
    }

    protected TestResult assertNotNull(String message, Object object) {
        if (object != null) {
            return pass(message);
        }
        return TestResult.fail("", message, "non-null", "null");
    }

    protected TestResult assertNull(Object object) {
        if (object == null) {
            return pass("Object is null");
        }
        return TestResult.fail("", "assertNull failed", "null", String.valueOf(object));
    }

    protected TestResult assertNull(String message, Object object) {
        if (object == null) {
            return pass(message);
        }
        return TestResult.fail("", message, "null", String.valueOf(object));
    }

    protected TestResult assertThrows(Class<? extends Throwable> expectedType, Runnable runnable) {
        try {
            runnable.run();
            return TestResult.fail("",
                    "assertThrows failed: no exception thrown",
                    expectedType.getSimpleName(),
                    "no exception");
        } catch (Throwable t) {
            if (expectedType.isInstance(t)) {
                return pass("Threw expected " + expectedType.getSimpleName());
            }
            return TestResult.fail("",
                    "assertThrows failed: wrong exception type",
                    expectedType.getSimpleName(),
                    t.getClass().getSimpleName());
        }
    }

    protected TestResult assertThrows(String message, Class<? extends Throwable> expectedType, Runnable runnable) {
        try {
            runnable.run();
            return TestResult.fail("",
                    message,
                    expectedType.getSimpleName(),
                    "no exception");
        } catch (Throwable t) {
            if (expectedType.isInstance(t)) {
                return pass(message);
            }
            return TestResult.fail("",
                    message,
                    expectedType.getSimpleName(),
                    t.getClass().getSimpleName());
        }
    }
}
