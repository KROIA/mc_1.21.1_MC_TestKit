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

    /**
     * TK-7: two clients and the master, like runOnClient (steps on the calling test thread, own tick clock) next to
     * run(server) (steps handed to the server thread). The client steps must not run on the master's executor thread.
     */
    @Test
    void clientsAndServerShareOneScenario(@TempDir Path dir) {
        Set<String> setup = Set.of("master", "tester1", "tester2");
        List<String> log = new CopyOnWriteArrayList<>();
        java.util.concurrent.ExecutorService serverThread = java.util.concurrent.Executors.newSingleThreadExecutor(r -> new Thread(r, "server"));
        java.util.function.Supplier<Scenario> trade = () -> Scenario.named("screen-trade")
                .step("master", "fund", () -> { log.add("master fund on " + Thread.currentThread().getName()); return TestResult.pass("fund", null); })
                .step("tester1", "sell via screen", () -> { log.add("tester1 sell on " + Thread.currentThread().getName()); return TestResult.pass("sell", null); })
                .step("tester2", "buy via screen", () -> { log.add("tester2 buy on " + Thread.currentThread().getName()); return TestResult.pass("buy", null); })
                .step("master", "check", () -> { log.add("master check on " + Thread.currentThread().getName()); return TestResult.pass("check", null); })
                .timeoutTicks(100);
        AtomicLong serverTicks = new AtomicLong(), client1Ticks = new AtomicLong(), client2Ticks = new AtomicLong();
        var master = CompletableFuture.supplyAsync(() -> trade.get().run("t", "master", dir, setup, serverTicks::get,
                action -> CompletableFuture.supplyAsync(action, serverThread).join()));
        var c1 = CompletableFuture.supplyAsync(() -> { Thread.currentThread().setName("client1-test"); return trade.get().run("t", "tester1", dir, setup, client1Ticks::get, java.util.function.Supplier::get); });
        var c2 = CompletableFuture.supplyAsync(() -> { Thread.currentThread().setName("client2-test"); return trade.get().run("t", "tester2", dir, setup, client2Ticks::get, java.util.function.Supplier::get); });
        try {
            assertEquals(TestResult.Status.PASS, master.join().getStatus());
            assertEquals(TestResult.Status.PASS, c1.join().getStatus());
            assertEquals(TestResult.Status.PASS, c2.join().getStatus());
        } finally {
            serverThread.shutdownNow();
        }
        assertEquals(List.of("master fund on server", "tester1 sell on client1-test", "tester2 buy on client2-test",
                "master check on server"), log);
    }

    @Test
    void skippedWhenTheSetupLacksAnInstance(@TempDir Path dir) {
        TestResult r = trade(new CopyOnWriteArrayList<>(), TestResult.Status.PASS).run("t", "a", dir, Set.of("a"), () -> 0, s -> s.get());
        assertEquals(TestResult.Status.SKIP, r.getStatus());
    }
}
