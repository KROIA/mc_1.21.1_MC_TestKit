package net.kroia.testkit.scenario;

import net.kroia.testkit.TestResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Two "instances" as threads sharing one directory, like two servers of a setup. */
class ScenarioTest {
    static final Set<String> BOTH = Set.of("a", "b");

    static Scenario trade(List<String> log, TestResult.Status bStatus) {
        return Scenario.named("trade")
                .step("a", "sell", () -> { log.add("a sell"); return TestResult.pass("sell", null); })
                .step("b", "buy", () -> { log.add("b buy"); return bStatus == TestResult.Status.PASS ? TestResult.pass("buy", null) : TestResult.fail("buy", "no money"); })
                .step("a", "check", () -> { log.add("a check"); return TestResult.pass("check", null); })
                .timeoutTicks(100);
    }

    static CompletableFuture<TestResult> instance(Scenario s, String me, Path dir, AtomicLong ticks) {
        return CompletableFuture.supplyAsync(() -> s.run("t", me, dir, BOTH, ticks::get, action -> action.get()));
    }

    @Test
    void stepsRunInOrderAcrossInstances(@TempDir Path dir) {
        List<String> log = new CopyOnWriteArrayList<>();
        AtomicLong ticks = new AtomicLong();
        var a = instance(trade(log, TestResult.Status.PASS), "a", dir, ticks);
        var b = instance(trade(log, TestResult.Status.PASS), "b", dir, ticks);
        assertEquals(TestResult.Status.PASS, a.join().getStatus());
        assertEquals(TestResult.Status.PASS, b.join().getStatus());
        assertEquals(List.of("a sell", "b buy", "a check"), log);
    }

    @Test
    void failedStepAbortsTheOthers(@TempDir Path dir) {
        List<String> log = new CopyOnWriteArrayList<>();
        AtomicLong ticks = new AtomicLong();
        var a = instance(trade(log, TestResult.Status.FAIL), "a", dir, ticks);
        var b = instance(trade(log, TestResult.Status.FAIL), "b", dir, ticks);
        assertTrue(b.join().getMessage().contains("step 2 'buy' on b failed: no money"), b.join().getMessage());
        assertTrue(a.join().getMessage().contains("aborted: step 2 'buy' on b failed"), a.join().getMessage());
        assertEquals(List.of("a sell", "b buy"), log);
    }

    @Test
    void missingInstanceTimesOutAndNamesTheStep(@TempDir Path dir) {
        AtomicLong ticks = new AtomicLong();
        var a = instance(trade(new CopyOnWriteArrayList<>(), TestResult.Status.PASS), "a", dir, ticks);
        while (!a.isDone()) ticks.addAndGet(10);
        assertTrue(a.join().getMessage().contains("a timed out after 100 ticks waiting for step 2 'buy' on b"), a.join().getMessage());
    }

    @Test
    void skippedWhenTheSetupLacksAnInstance(@TempDir Path dir) {
        TestResult r = trade(new CopyOnWriteArrayList<>(), TestResult.Status.PASS).run("t", "a", dir, Set.of("a"), () -> 0, s -> s.get());
        assertEquals(TestResult.Status.SKIP, r.getStatus());
    }
}
