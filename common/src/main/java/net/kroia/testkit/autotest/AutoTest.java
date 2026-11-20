package net.kroia.testkit.autotest;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.architectury.event.events.common.LifecycleEvent;
import dev.architectury.event.events.common.PlayerEvent;
import dev.architectury.event.events.common.TickEvent;
import dev.architectury.platform.Platform;
import dev.architectury.utils.Env;
import net.kroia.testkit.TestKitMod;
import net.kroia.testkit.TestRegistry;
import net.kroia.testkit.TestResult;
import net.kroia.testkit.TestRunner;
import net.kroia.testkit.TestSuite;
import net.kroia.testkit.compat.PlayerCompat;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Unattended test runs for tools/autotest/run_matrix.py. Loaded by TestKit's init (TestKit is a dev-only mod).
 * Inert unless the JVM is started with {@code -Dmodutilities.autotest=<instance label>}.
 * <p>
 * Server side (dedicated or integrated): once every readiness condition holds, runs every registered
 * suite allowed on this server type, writes a JSON report and (dedicated, {@code stop=true}) stops the
 * server. Client side: see {@link AutoTestClient}.
 * <p>
 * System properties ({@code modutilities.autotest.} prefix):
 * <ul>
 *   <li>{@code results} report path (default {@code autotest-results.json}); the client writes
 *       {@code <results>.client.json} next to it</li>
 *   <li>{@code waitPlayer} wait for a player to join before running (default true)</li>
 *   <li>{@code waitSlaves}, {@code waitLink}, {@code trustedSlave}: read by the mods' own ready conditions, not here</li>
 *   <li>{@code after} wait until this file exists (sequencing between instances); on a client the GUI tests wait
 *       for it too, e.g. for the master's {@code <results>.server.done}: client-to-master requests time out while
 *       the master runs its server suites on its server thread</li>
 *   <li>{@code masterDown} the slave-masterdown cell: the master was killed before the tests, see {@link #isMasterDown()}</li>
 *   <li>{@code stop} dedicated server: stop after the report is written (default true)</li>
 *   <li>{@code timeout} watchdog in seconds; on expiry a timeout report is written and the JVM halts (default 900)</li>
 *   <li>{@code mods} comma separated mod ids to run (default: all mods with registered suites)</li>
 *   <li>{@code connect} / {@code world} client only, see {@link AutoTestClient}</li>
 *   <li>{@code waitGui} dedicated server: wait until this file exists (the client's GUI report, see below)</li>
 *   <li>{@code gui} client only: false skips the GUI suites (an empty GUI report is still written), for the 2nd..nth
 *       client of a several-clients setup (default true)</li>
 *   <li>{@code play} manual play (run_matrix.py --play): only the multi-server link and the client's
 *       connect/world are set up; no tests, no report, no auto-stop, no watchdog</li>
 * </ul>
 * Server tick sampling: the time between consecutive server ticks (50 ms = on time) is recorded from the tick on which
 * only the {@code after} file is still missing (or the settle phase, without {@code after}) until the suites start;
 * the report header gets {@code ticks}, {@code maxTickMs} and {@code avgTickMs}, tests read {@link #tickStats()}.
 * <p>
 * GUI tests ({@link TestSuite.Side#CLIENT}) run on the client after it joined, before the server's suites;
 * the client writes them to {@code <results>.gui.json} (same format as the server report). An integrated server
 * waits for them by itself; a dedicated server waits for the file named by {@code waitGui}. After them the client runs
 * its {@link TestSuite.Side#CLIENT_SCENARIO} suites (its steps of the setup's scenarios) and writes
 * {@code <results>.client-scenario.json}; it stays until they are done, even when its server stopped.
 * <p>
 * Mod-specific setup stays in the mods (TestKit depends on none of them): they register
 * {@link #addReadyCondition ready conditions} (e.g. ModUtilities waits for the master/slave link),
 * the {@link #setRoleProvider role} (master/slave/standalone) and {@link #onClientJoin client-join checks}.
 */
public final class AutoTest {
    private AutoTest() {}

    static final String P = "modutilities.autotest";
    static final String LABEL = System.getProperty(P);
    static final Path RESULTS = Paths.get(System.getProperty(P + ".results", "autotest-results.json")).toAbsolutePath();
    private static final boolean WAIT_PLAYER = Boolean.parseBoolean(System.getProperty(P + ".waitPlayer", "true"));
    static final @Nullable String AFTER = System.getProperty(P + ".after");
    private static final String WAITING_AFTER = "file " + AFTER;
    private static final boolean MASTER_DOWN = Boolean.getBoolean(P + ".masterDown");
    private static final boolean STOP = Boolean.parseBoolean(System.getProperty(P + ".stop", "true"));
    private static final long TIMEOUT_SECONDS = Long.getLong(P + ".timeout", 900L);
    private static final @Nullable String MODS = System.getProperty(P + ".mods");
    static final boolean PLAY = Boolean.getBoolean(P + ".play");
    private static final @Nullable String WAIT_GUI = System.getProperty(P + ".waitGui");
    static final Path GUI_RESULTS = RESULTS.resolveSibling(RESULTS.getFileName() + ".gui.json");
    static final Path SCENARIO_RESULTS = RESULTS.resolveSibling(RESULTS.getFileName() + ".scenario.json");
    /** The client's scenario steps (TK-7); its own file, since singleplayer shares RESULTS with the integrated server. */
    static final Path CLIENT_SCENARIO_RESULTS = RESULTS.resolveSibling(RESULTS.getFileName() + ".client-scenario.json");
    /** Written after the server suites (always, before any scenario): run_matrix may start the next server now. */
    static final Path SERVER_DONE = RESULTS.resolveSibling(RESULTS.getFileName() + ".server.done");
    /** Set by the client when its GUI suites are done (or there are none); read by an integrated server. */
    static volatile boolean guiDone;
    /** Ticks every readiness condition must hold before the run starts (lets joins and syncs settle). */
    private static final int SETTLE_TICKS = 40;

    static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final long START_MILLIS = System.currentTimeMillis();

    private static volatile boolean finished;
    private static int readyTicks;
    private static String waitingFor = "server start";
    private static final List<Function<MinecraftServer, @Nullable String>> READY_CONDITIONS = new ArrayList<>();
    static final List<Runnable> CLIENT_JOIN_HOOKS = new ArrayList<>();
    private static Supplier<String> role = () -> "standalone";
    private static boolean started;
    private static long lastTickNanos, maxTickNanos, sumTickNanos;
    private static int sampledTicks;

    /** Server tick intervals sampled before the suites started (see the class doc); zero ticks if none yet. */
    public record TickStats(int ticks, double maxTickMs, double avgTickMs) {}

    public static TickStats tickStats() {
        return new TickStats(sampledTicks, maxTickNanos / 1e6, sampledTicks == 0 ? 0 : sumTickNanos / 1e6 / sampledTicks);
    }

    /** @return true in the slave-masterdown cell (run_matrix.py): the master was killed before this slave's tests. */
    public static boolean isMasterDown() {
        return MASTER_DOWN;
    }

    public static boolean isEnabled() {
        return LABEL != null;
    }

    /** @return this instance's label ({@code -Dmodutilities.autotest=<label>}, e.g. master, slave, client), null when off. */
    public static @Nullable String label() {
        return LABEL;
    }

    /** @return true in manual play mode (run_matrix.py --play): no tests run. */
    public static boolean isPlay() {
        return PLAY;
    }

    /**
     * Adds a condition the run waits for, checked every server tick after "a player" and before the {@code after}
     * file. Register from the mod's dev init.
     *
     * @param condition returns what is still missing (shown in the log and the timeout report), or null when ready
     */
    public static void addReadyCondition(Function<MinecraftServer, @Nullable String> condition) {
        READY_CONDITIONS.add(condition);
    }

    /**
     * Sets this server's role for the report header and the suites' {@link net.kroia.testkit.TestCategory#canRunOn}
     * check: {@code "master"}, {@code "slave"} or {@code "standalone"} (the default).
     */
    public static void setRoleProvider(Supplier<String> provider) {
        role = provider;
    }

    /** Runs on the client thread once, when the autotest client first has a level (singleplayer or joined server). */
    public static void onClientJoin(Runnable hook) {
        CLIENT_JOIN_HOOKS.add(hook);
    }

    private static boolean isSlave() {
        return "slave".equals(role.get());
    }

    /** @return true once this JVM's server wrote its report (the client polls this in singleplayer). */
    public static boolean isFinished() {
        return finished;
    }

    public static void init() {
        if (!isEnabled()) return;
        TestKitMod.LOGGER.info("[AutoTest] enabled, instance '{}', {}", LABEL, PLAY ? "play mode (no tests)" : "report " + RESULTS);

        LifecycleEvent.SERVER_STARTED.register(server -> server.setDifficulty(Difficulty.PEACEFUL, true));
        // Joins happen before waitPlayer + settle let the suites start, so tests always see a creative op.
        PlayerEvent.PLAYER_JOIN.register(player -> {
            player.setGameMode(GameType.CREATIVE);
            TestKitMod.LOGGER.info("[AutoTest] {} set to creative", player.getScoreboardName());
            // Explicit level 4: the default op level is 2 on an integrated server, which would even lower
            // a singleplayer owner's level 4 (cheats on) and leave a cheats-off world without commands.
            PlayerCompat.opOwner(player);
            TestKitMod.LOGGER.info("[AutoTest] {} opped", player.getScoreboardName());
        });
        TickEvent.SERVER_POST.register(AutoTest::serverTick);
        // The dev launcher (Architectury TransformerRuntime) leaves two non-daemon thread pools behind,
        // so a dedicated dev server's JVM never exits on its own after the server stopped.
        // Exit from a separate thread: vanilla's shutdown hook waits for the server thread to finish.
        LifecycleEvent.SERVER_STOPPED.register(server -> {
            if (server.isDedicatedServer()) new Thread(() -> System.exit(0), "TestKit-AutoTest-Exit").start();
        });
        if (Platform.getEnvironment() == Env.CLIENT) {
            AutoTestClient.init();
        }
        if (PLAY) return;

        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(TIMEOUT_SECONDS * 1000L);
            } catch (InterruptedException e) {
                return;
            }
            TestKitMod.LOGGER.error("[AutoTest] watchdog: no result after {} s (waiting for: {}), halting", TIMEOUT_SECONDS, waitingFor);
            if (!finished) {
                JsonObject report = header("timeout");
                report.addProperty("waitingFor", waitingFor);
                write(RESULTS, report);
            }
            if (Platform.getEnvironment() == Env.CLIENT) AutoTestClient.onWatchdog();
            Runtime.getRuntime().halt(3);
        }, "TestKit-AutoTest-Watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    private static void serverTick(MinecraftServer server) {
        if (finished || started) return;
        if (PLAY) return;

        String waiting = waitingFor(server);
        sampleTick(waiting == null || WAITING_AFTER.equals(waiting));
        if (waiting != null) {
            if (!waiting.equals(waitingFor)) TestKitMod.LOGGER.info("[AutoTest] waiting for {}", waiting);
            waitingFor = waiting;
            readyTicks = 0;
            return;
        }
        waitingFor = "settle";
        if (++readyTicks < SETTLE_TICKS) return;
        started = true;
        // Run as a server task, not inside this tick event: tests may register event listeners, and
        // Architectury throws ConcurrentModificationException when that happens while it iterates
        // the SERVER_POST listeners. execute() from another thread queues the task on the server thread.
        CompletableFuture.runAsync(() -> server.execute(() -> run(server)));
    }

    private static void sampleTick(boolean sampling) {
        long now = System.nanoTime();
        if (sampling && lastTickNanos != 0) {
            long dt = now - lastTickNanos;
            sampledTicks++;
            sumTickNanos += dt;
            maxTickNanos = Math.max(maxTickNanos, dt);
        }
        lastTickNanos = sampling ? now : 0;
    }

    private static @Nullable String waitingFor(MinecraftServer server) {
        if (WAIT_PLAYER && server.getPlayerList().getPlayers().isEmpty()) return "a player";
        for (Function<MinecraftServer, @Nullable String> condition : READY_CONDITIONS) {
            String missing = condition.apply(server);
            if (missing != null) return missing;
        }
        if (!server.isDedicatedServer() && !guiDone && hasSuites(TestSuite.Side.CLIENT)) return "the client GUI tests";
        if (WAIT_GUI != null && !Files.exists(Paths.get(WAIT_GUI))) return "file " + WAIT_GUI + " (client GUI tests)";
        if (AFTER != null && !Files.exists(Paths.get(AFTER))) return WAITING_AFTER; // last: starts the tick sampling
        return null;
    }

    private static void run(MinecraftServer server) {
        boolean isSlave = isSlave();
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        ServerPlayer player = players.isEmpty() ? null : players.get(0);
        JsonObject report = header("completed");
        runSuites(report, TestSuite.Side.SERVER, isSlave, server, player);
        write(SERVER_DONE, report);
        if (!hasSuites(TestSuite.Side.SCENARIO)) {
            finish(server, report);
            return;
        }
        // Scenario steps wait for other servers: off the server thread, so this server keeps ticking meanwhile.
        Thread thread = new Thread(() -> {
            JsonObject scenarios = header("completed");
            runSuites(scenarios, TestSuite.Side.SCENARIO, isSlave, server, player);
            write(SCENARIO_RESULTS, scenarios);
            TestKitMod.LOGGER.info("[AutoTest] scenario report written to {}", SCENARIO_RESULTS);
            server.execute(() -> finish(server, report));
        }, "TestKit-Scenario-Tests");
        thread.setDaemon(true);
        thread.start();
    }

    /** Writes the main report last: run_matrix takes it as "this instance is done". */
    private static void finish(MinecraftServer server, JsonObject report) {
        write(RESULTS, report);
        finished = true;
        TestKitMod.LOGGER.info("[AutoTest] report written to {}", RESULTS);
        if (server.isDedicatedServer() && STOP) {
            server.halt(false);
        }
    }

    /** @return true if a mod selected by {@code mods} registered suites of this side in this JVM. */
    static boolean hasSuites(TestSuite.Side side) {
        Set<String> filter = modFilter();
        return TestRegistry.getTestSuites().stream()
                .anyMatch(s -> s.side() == side && (filter == null || filter.contains(s.getCategory().getModId())));
    }

    private static @Nullable Set<String> modFilter() {
        return MODS == null ? null : new LinkedHashSet<>(Arrays.asList(MODS.split(",")));
    }

    /**
     * Runs the suites of one side (server: on the server thread; GUI: on the client's test thread; scenario: on the
     * server's test thread) of every selected mod and adds {@code mods} and the totals to {@code report}.
     */
    static void runSuites(JsonObject report, TestSuite.Side side, boolean isSlave, @Nullable MinecraftServer server, @Nullable ServerPlayer player) {
        try {
            Set<String> filter = modFilter();
            Set<String> modIds = new LinkedHashSet<>();
            for (TestSuite suite : TestRegistry.getTestSuites()) {
                String modId = suite.getCategory().getModId();
                if (suite.side() == side && (filter == null || filter.contains(modId))) modIds.add(modId);
            }
            int[] total = new int[5]; // passed, failed, errors, skipped, excluded suites
            JsonObject mods = new JsonObject();
            for (String modId : modIds) {
                TestKitMod.LOGGER.info("[AutoTest] running {} {} tests (slave={})", modId, side, isSlave);
                TestRunner runner = new TestRunner(modId, modId, isSlave, server, side);
                runner.runAll(player);
                mods.add(modId, modReport(modId, side, isSlave, runner.getResults(), total));
            }
            report.add("mods", mods);
            report.addProperty("passed", total[0]);
            report.addProperty("failed", total[1]);
            report.addProperty("errors", total[2]);
            report.addProperty("skipped", total[3]);
            report.addProperty("excludedSuites", total[4]); // suites, not tests: see each mod's excludedSuites
        } catch (Throwable t) {
            TestKitMod.LOGGER.error("[AutoTest] run aborted", t);
            report.addProperty("status", "error");
            report.addProperty("error", t.toString());
        }
    }

    private static JsonObject modReport(String modId, TestSuite.Side side, boolean isSlave, Map<String, List<TestResult>> results, int[] total) {
        JsonObject mod = new JsonObject();
        int[] counts = new int[4]; // by TestResult.Status ordinal: PASS, FAIL, ERROR, SKIP
        JsonObject suites = new JsonObject();
        for (Map.Entry<String, List<TestResult>> e : results.entrySet()) {
            JsonArray tests = new JsonArray();
            for (TestResult r : e.getValue()) {
                JsonObject t = new JsonObject();
                t.addProperty("name", r.getTestName());
                t.addProperty("status", r.getStatus().name());
                if (r.getMessage() != null) t.addProperty("message", r.getMessage());
                if (r.getExpected() != null) t.addProperty("expected", r.getExpected());
                if (r.getActual() != null) t.addProperty("actual", r.getActual());
                tests.add(t);
                counts[r.getStatus().ordinal()]++;
            }
            suites.add(e.getKey(), tests);
        }
        // topics: every registered suite (run_matrix caches them in <out>/topics.json for the GUI's topic menu)
        JsonArray skipped = new JsonArray(), excluded = new JsonArray(), offline = new JsonArray(), topics = new JsonArray();
        for (TestSuite suite : TestRegistry.getTestSuites(modId)) {
            if (suite.side() != side) continue;
            String key = suite.getCategory().getName() + "/" + suite.getClass().getSimpleName();
            JsonObject topic = new JsonObject();
            topic.addProperty("key", key);
            topic.addProperty("needsGame", suite.needsGame());
            topics.add(topic);
            if (TestRegistry.isExcluded(suite)) excluded.add(key);
            else if (TestRegistry.isOffline(suite)) offline.add(key);
            else if (!side.onClient() && !suite.getCategory().canRunOn(isSlave)) skipped.add(key);
        }
        mod.addProperty("passed", counts[0]);
        mod.addProperty("failed", counts[1]);
        mod.addProperty("errors", counts[2]);
        mod.addProperty("skipped", counts[3]);
        mod.add("skippedSuites", skipped);
        mod.add("excludedSuites", excluded);
        mod.add("offlineSuites", offline); // left to the JUnit run (modutilities.tests.offline)
        mod.add("topics", topics);
        mod.add("suites", suites);
        for (int i = 0; i < counts.length; i++) total[i] += counts[i];
        total[4] += excluded.size();
        return mod;
    }

    static JsonObject header(String status) {
        JsonObject o = new JsonObject();
        o.addProperty("instance", LABEL);
        o.addProperty("status", status);
        o.addProperty("role", role.get());
        o.addProperty("durationMs", System.currentTimeMillis() - START_MILLIS);
        TickStats t = tickStats();
        o.addProperty("ticks", t.ticks());
        o.addProperty("maxTickMs", t.maxTickMs());
        o.addProperty("avgTickMs", t.avgTickMs());
        return o;
    }

    /** Writes via a temp file + rename, so other instances polling for the file never see it half written. */
    static void write(Path file, JsonObject json) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(json));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            TestKitMod.LOGGER.error("[AutoTest] could not write {}", file, e);
        }
    }
}
