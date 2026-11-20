package net.kroia.testkit.scenario;

import net.kroia.testkit.TestResult;
import net.kroia.testkit.autotest.AutoTest;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * A test across several servers, written once and run by every server of the setup (a
 * {@link net.kroia.testkit.TestSuite.Side#SCENARIO} suite). Each step names the instance that runs it (run_matrix's
 * instance label: master, slave, ...); the others wait until it is done. A failed step or a timeout aborts the
 * scenario on every instance, and the report names the instance and step.
 * <pre>{@code
 * addTest("trade", () -> Scenario.named("trade")
 *         .step("slaveA", "seller lists 10 diamonds", () -> ...)
 *         .step("slaveB", "buyer buys them", () -> ...)
 *         .step("master", "balances moved", () -> assertEquals(90, ...))
 *         .run("trade", getServer()));
 * }</pre>
 * Steps run on the server thread and return a {@link TestResult} like a normal test (anything but PASS fails the
 * scenario). The steps are synchronised through a directory all instances share, independent of the mods' own
 * master/slave link, so a broken link shows as a failed step instead of a hang. run_matrix sets
 * {@code -Dmodutilities.autotest.scenarioDir=<dir>} (fresh per setup) and
 * {@code -Dmodutilities.autotest.instances=<label,label,...>} (every server in the setup).
 * Without them, or when a step names an instance the setup does not have, the scenario is skipped.
 * <p>
 * Game clients take part too (TK-7): a step may name a client label (tester1, tester2, ...); each client runs the same
 * scenario from a {@link net.kroia.testkit.TestSuite.Side#CLIENT_SCENARIO} suite with {@link #runOnClient}, after its
 * GUI suites. Its steps run on the client's test thread (not the server thread).
 */
public final class Scenario {
    public static final String DIR_PROPERTY = "modutilities.autotest.scenarioDir";
    public static final String INSTANCES_PROPERTY = "modutilities.autotest.instances";
    /** Default wait for another instance's step: 5 minutes of server ticks (it may still run its server suites). */
    public static final int DEFAULT_TIMEOUT_TICKS = 6000;
    private static final long POLL_MS = 50;

    private record Step(String instance, String name, Supplier<TestResult> action) {}

    private final String name;
    private final List<Step> steps = new ArrayList<>();
    private int timeoutTicks = DEFAULT_TIMEOUT_TICKS;

    private Scenario(String name) {
        this.name = name;
    }

    /** @param name unique per scenario: names the shared directory of its steps */
    public static Scenario named(String name) {
        return new Scenario(name);
    }

    public Scenario step(String instance, String stepName, Supplier<TestResult> action) {
        steps.add(new Step(instance, stepName, action));
        return this;
    }

    /** How many of this server's ticks to wait for another instance's step. */
    public Scenario timeoutTicks(int ticks) {
        timeoutTicks = ticks;
        return this;
    }

    /** Runs this instance's steps and waits for the others' (call it from a scenario suite's test thread). */
    public TestResult run(String testName, MinecraftServer server) {
        String dir = System.getProperty(DIR_PROPERTY), instances = System.getProperty(INSTANCES_PROPERTY);
        if (dir == null || instances == null)
            return TestResult.skip(testName, "needs " + DIR_PROPERTY + " and " + INSTANCES_PROPERTY + " (run_matrix sets them)");
        return run(testName, AutoTest.label(), Paths.get(dir), Set.of(instances.split(",")), server::getTickCount,
                action -> onServer(server, action));
    }

    /**
     * The game client's entry point (a {@link net.kroia.testkit.TestSuite.Side#CLIENT_SCENARIO} suite): runs the steps
     * whose instance is this client's label (e.g. tester1) on the calling test thread, so they drive the GUI with
     * {@link net.kroia.testkit.gui.Gui}, and counts waits in client ticks.
     */
    public TestResult runOnClient(String testName) {
        String dir = System.getProperty(DIR_PROPERTY), instances = System.getProperty(INSTANCES_PROPERTY);
        if (dir == null || instances == null)
            return TestResult.skip(testName, "needs " + DIR_PROPERTY + " and " + INSTANCES_PROPERTY + " (run_matrix sets them)");
        return run(testName, AutoTest.label(), Paths.get(dir), Set.of(instances.split(",")),
                net.kroia.testkit.gui.Gui::ticks, Supplier::get);
    }

    TestResult run(String testName, String me, Path dir, Set<String> instances, LongSupplier ticks,
                   Function<Supplier<TestResult>, TestResult> executor) {
        for (Step step : steps)
            if (!instances.contains(step.instance()))
                return TestResult.skip(testName, "needs instance '" + step.instance() + "', the setup has " + instances);
        Path base = dir.resolve(name), abort = base.resolve("abort");
        for (int i = 0; i < steps.size(); i++) {
            Step step = steps.get(i);
            String what = "step " + (i + 1) + " '" + step.name() + "' on " + step.instance();
            Path done = base.resolve((i + 1) + ".done");
            if (step.instance().equals(me)) {
                TestResult r;
                try {
                    r = executor.apply(step.action());
                } catch (Throwable t) {
                    r = TestResult.error(testName, t.toString());
                }
                if (r == null || r.getStatus() != TestResult.Status.PASS) {
                    String msg = what + " failed: " + (r == null ? "returned null" : r.getMessage());
                    mark(abort, msg);
                    return TestResult.fail(testName, msg);
                }
                mark(done, "");
                continue;
            }
            long start = ticks.getAsLong();
            while (!Files.exists(done)) {
                if (Files.exists(abort)) return TestResult.fail(testName, "aborted: " + read(abort));
                if (ticks.getAsLong() - start > timeoutTicks) {
                    String msg = me + " timed out after " + timeoutTicks + " ticks waiting for " + what;
                    mark(abort, msg);
                    return TestResult.fail(testName, msg);
                }
                try {
                    Thread.sleep(POLL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return TestResult.error(testName, "interrupted");
                }
            }
        }
        return TestResult.pass(testName, null);
    }

    /** Runs {@code task} on the server thread and waits for it (at most 60 s). Call it from the test thread only. */
    public static <T> T onServer(MinecraftServer server, Supplier<T> task) {
        if (server.isSameThread()) throw new IllegalStateException("Scenario.onServer on the server thread would deadlock");
        try {
            return server.submit(task).get(60, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw e.getCause() instanceof RuntimeException re ? re : new IllegalStateException(e.getCause());
        } catch (TimeoutException e) {
            throw new IllegalStateException("server task did not finish within 60 s");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted");
        }
    }

    /** Writes the marker atomically, so a waiting instance never reads half a file. */
    private static void mark(Path file, String text) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");
            Files.writeString(tmp, text);
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            return "(unreadable: " + e + ")";
        }
    }
}
