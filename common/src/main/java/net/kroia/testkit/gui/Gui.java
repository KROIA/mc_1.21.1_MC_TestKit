package net.kroia.testkit.gui;

import net.kroia.testkit.compat.ClientCompat;
import net.kroia.testkit.compat.InputCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.Screen;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * GUI driver for client test suites ({@link net.kroia.testkit.TestSuite.Side#CLIENT}). Call it from the test
 * thread, never from the client thread: every action hops to the client thread and waits for it, and waits are
 * counted in client ticks (never milliseconds), so the game keeps rendering while a test waits.
 * <pre>{@code
 * Gui.open(new MyScreen());
 * Gui.click("Deposit");
 * Gui.type("100");
 * Gui.key(Keys.ENTER);
 * return assertTrue(Gui.layoutProblems().toString(), Gui.layoutProblems().isEmpty());
 * }</pre>
 * Threading: every method that reads or changes screen state runs on the client thread ({@link #onClient}: submit
 * and wait); only the waiting happens on the test thread. Element lists are snapshots taken on the client thread.
 * Screens and element handles a test holds must be touched only inside {@link #onClient}.
 * Coordinates are GUI-scaled. The OS cursor is never moved; hover effects that read the real cursor position
 * (tooltips) are not driven.
 */
public final class Gui {
    private Gui() {}

    /** Client-thread hops and tick waits give up after this; a frozen client fails the test instead of hanging. */
    private static final long HOP_TIMEOUT_SECONDS = 60;
    private static final List<GuiLocator> LOCATORS = new ArrayList<>();
    private static final AtomicLong TICKS = new AtomicLong();

    /** Called from the autotest client's tick (client thread). */
    public static void onClientTick() {
        TICKS.incrementAndGet();
    }

    /** Register from the mod's client dev init. */
    public static void addLocator(GuiLocator locator) {
        LOCATORS.add(locator);
    }

    // ---------------------------------------------------------------- threading and waiting

    /** Runs {@code action} on the client thread and returns its result. */
    public static <T> T onClient(Supplier<T> action) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.isSameThread())
            throw new IllegalStateException("Gui must be called from the test thread, not the client thread");
        CompletableFuture<T> future = mc.submit(action);
        try {
            return future.get(HOP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException r) throw r;
            if (cause instanceof Error err) throw err;
            throw new IllegalStateException(cause);
        } catch (TimeoutException e) {
            throw new IllegalStateException("client thread did not answer within " + HOP_TIMEOUT_SECONDS + " s");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted");
        }
    }

    public static void onClient(Runnable action) {
        onClient(() -> {
            action.run();
            return null;
        });
    }

    public static void waitTicks(int ticks) {
        long target = TICKS.get() + ticks;
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(HOP_TIMEOUT_SECONDS);
        while (TICKS.get() < target) {
            if (System.nanoTime() > end)
                throw new IllegalStateException("the client did not tick " + ticks + " times within " + HOP_TIMEOUT_SECONDS + " s");
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted");
            }
        }
    }

    /**
     * Checks {@code condition} on the client thread once per tick.
     *
     * @return true if it became true within {@code maxTicks}
     */
    public static boolean waitUntil(BooleanSupplier condition, int maxTicks) {
        for (int i = 0; i <= maxTicks; i++) {
            if (onClient(condition::getAsBoolean)) return true;
            waitTicks(1);
        }
        return false;
    }

    // ---------------------------------------------------------------- screens

    public static @Nullable Screen screen() {
        return onClient(ClientCompat::screen);
    }

    /** Opens the screen and waits one tick, so its first layout and tick ran. */
    public static void open(Screen screen) {
        onClient(() -> ClientCompat.setScreen(screen));
        waitTicks(1);
    }

    /** Closes the current screen like Escape does ({@code onClose}). */
    public static void close() {
        onClient(() -> {
            Screen s = ClientCompat.screen();
            if (s != null) s.onClose();
        });
        waitTicks(1);
    }

    /**
     * @return the open screen once it is of this type; throws if it does not open within {@code maxTicks}.
     * Touch the returned screen only inside {@link #onClient}: Minecraft's GUI is not thread-safe.
     */
    public static <T extends Screen> T waitForScreen(Class<T> type, int maxTicks) {
        if (!waitUntil(() -> type.isInstance(ClientCompat.screen()), maxTicks))
            throw new IllegalStateException("screen " + type.getSimpleName() + " did not open within " + maxTicks
                    + " ticks, open: " + describe(screen()));
        return type.cast(screen());
    }

    /** Sends a command to the server as the player, like typing it in chat (no leading '/'). */
    public static void sendCommand(String command) {
        onClient(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) throw new IllegalStateException("no player: not in a world");
            mc.player.connection.sendCommand(command);
        });
    }

    // ---------------------------------------------------------------- finding elements

    /** @return the element trees of the open screen: vanilla widgets plus every locator's nodes. */
    public static List<GuiNode> nodes() {
        return onClient(Gui::collect);
    }

    /**
     * @return the first visible element (depth first) whose name or text equals {@code nameOrText}, else the first
     * hidden one; throws if none.
     */
    public static GuiNode find(String nameOrText) {
        return find(n -> nameOrText.equals(n.name()) || nameOrText.equals(n.text()), "'" + nameOrText + "'");
    }

    /** @return the first visible match (depth first), else the first hidden one; throws if none. */
    public static GuiNode find(Predicate<GuiNode> filter, String description) {
        List<GuiNode> found = findAll(filter);
        if (found.isEmpty()) {
            List<String> all = new ArrayList<>();
            nodes().forEach(root -> root.forEach(n -> all.add(n.toString())));
            throw new IllegalStateException("no element " + description + " on " + describe(screen()) + "; elements: " + all);
        }
        return found.stream().filter(GuiNode::visible).findFirst().orElse(found.get(0));
    }

    /** All matches, hidden ones included (for tests that assert an element is hidden). */
    public static List<GuiNode> findAll(Predicate<GuiNode> filter) {
        List<GuiNode> found = new ArrayList<>();
        nodes().forEach(root -> root.forEach(n -> {
            if (filter.test(n)) found.add(n);
        }));
        return found;
    }

    private static List<GuiNode> collect() {
        Screen screen = ClientCompat.screen();
        if (screen == null) return List.of();
        List<GuiNode> roots = new ArrayList<>(vanilla(screen.children()));
        for (GuiLocator locator : LOCATORS) roots.addAll(locator.nodes(screen));
        return roots;
    }

    private static List<GuiNode> vanilla(List<? extends GuiEventListener> listeners) {
        List<GuiNode> nodes = new ArrayList<>();
        var font = Minecraft.getInstance().font;
        for (GuiEventListener l : listeners) {
            List<GuiNode> children = l instanceof ContainerEventHandler c ? vanilla(c.children()) : List.of();
            String type = l.getClass().getSimpleName();
            if (l instanceof AbstractWidget w) {
                String text = w.getMessage().getString();
                nodes.add(new GuiNode(type, text, null, w.getX(), w.getY(), w.getWidth(), w.getHeight(),
                        text.isEmpty() ? 0 : font.width(text), w.visible, children, w));
            } else {
                ScreenRectangle r = l.getRectangle();
                nodes.add(new GuiNode(type, "", null, r.left(), r.top(), r.width(), r.height(), 0, true, children, l));
            }
        }
        return nodes;
    }

    // ---------------------------------------------------------------- input

    public static void click(String nameOrText) {
        click(find(nameOrText));
    }

    /** Clicks the element's center; throws if it is hidden, because the click would land on whatever is below. */
    public static void click(GuiNode node) {
        requireVisible(node, "click");
        click(node.centerX(), node.centerY(), Keys.MOUSE_LEFT);
    }

    private static void requireVisible(GuiNode node, String action) {
        if (!node.visible())
            throw new IllegalStateException("cannot " + action + " " + node + ": it is hidden (it or a parent is not"
                    + " visible); GUI tests act only on visible elements");
    }

    /** Press and release at a point; then waits one tick. */
    public static void click(double x, double y, int button) {
        onClient(() -> {
            Screen s = requireScreen();
            s.mouseMoved(x, y);
            InputCompat.press(s, x, y, button, false);
            InputCompat.release(s, x, y, button);
        });
        waitTicks(1);
    }

    /** Two clicks within one tick; from 1.21.9 the second one carries the double-click flag. */
    public static void doubleClick(GuiNode node) {
        requireVisible(node, "double-click");
        double x = node.centerX(), y = node.centerY();
        onClient(() -> {
            Screen s = requireScreen();
            s.mouseMoved(x, y);
            InputCompat.press(s, x, y, Keys.MOUSE_LEFT, false);
            InputCompat.release(s, x, y, Keys.MOUSE_LEFT);
            InputCompat.press(s, x, y, Keys.MOUSE_LEFT, true);
            InputCompat.release(s, x, y, Keys.MOUSE_LEFT);
        });
        waitTicks(1);
    }

    /** Press at (fromX, fromY), drag in {@code steps} moves (one per tick), release at (toX, toY). For sliders. */
    public static void drag(double fromX, double fromY, double toX, double toY, int steps) {
        onClient(() -> {
            Screen s = requireScreen();
            s.mouseMoved(fromX, fromY);
            InputCompat.press(s, fromX, fromY, Keys.MOUSE_LEFT, false);
        });
        double dx = (toX - fromX) / steps, dy = (toY - fromY) / steps;
        for (int i = 1; i <= steps; i++) {
            double x = fromX + dx * i, y = fromY + dy * i;
            onClient(() -> {
                Screen s = requireScreen();
                s.mouseMoved(x, y);
                InputCompat.drag(s, x, y, Keys.MOUSE_LEFT, dx, dy);
            });
            waitTicks(1);
        }
        onClient(() -> InputCompat.release(requireScreen(), toX, toY, Keys.MOUSE_LEFT));
        waitTicks(1);
    }

    /** Mouse wheel over the element; positive = up (zoom in on a ChartView). */
    public static void scroll(GuiNode node, double amount) {
        requireVisible(node, "scroll");
        onClient(() -> {
            Screen s = requireScreen();
            s.mouseMoved(node.centerX(), node.centerY());
            s.mouseScrolled(node.centerX(), node.centerY(), 0, amount);
        });
        waitTicks(1);
    }

    public static void key(int key) {
        key(key, Keys.MOD_NONE);
    }

    /** Press and release a key ({@link Keys}), e.g. {@code key(Keys.A, Keys.MOD_CONTROL)}; then waits one tick. */
    public static void key(int key, int modifiers) {
        onClient(() -> {
            Screen s = requireScreen();
            InputCompat.keyPress(s, key, modifiers);
            InputCompat.keyRelease(s, key, modifiers);
        });
        waitTicks(1);
    }

    /** Types the text into the focused element, character by character; then waits one tick. */
    public static void type(String text) {
        onClient(() -> {
            Screen s = requireScreen();
            text.codePoints().forEach(cp -> InputCompat.charTyped(s, cp));
        });
        waitTicks(1);
    }

    /** Focuses a vanilla element directly; other elements are focused by clicking them. */
    public static void focus(GuiNode node) {
        if (!(node.handle() instanceof GuiEventListener l)) {
            click(node);
            return;
        }
        onClient(() -> requireScreen().setFocused(l));
    }

    private static Screen requireScreen() {
        Screen s = ClientCompat.screen();
        if (s == null) throw new IllegalStateException("no screen open");
        return s;
    }

    // ---------------------------------------------------------------- layout

    /** Layout problems of the open screen at its current size. */
    public static List<Layout.Problem> layoutProblems() {
        return onClient(() -> {
            Screen s = requireScreen();
            return Layout.check(collect(), s.width, s.height);
        });
    }

    /**
     * Lays the open screen out for a window of {@code windowWidth} x {@code windowHeight} at {@code guiScale},
     * checks it and restores the real size. The window itself is not resized: screens that read the window size
     * directly instead of their own width/height still see the real window.
     */
    public static List<Layout.Problem> layoutProblems(int windowWidth, int windowHeight, int guiScale) {
        int w = (windowWidth + guiScale - 1) / guiScale, h = (windowHeight + guiScale - 1) / guiScale;
        return onClient(() -> {
            Screen s = requireScreen();
            var window = Minecraft.getInstance().getWindow();
            try {
                InputCompat.resize(s, w, h);
                return Layout.check(collect(), w, h);
            } finally {
                InputCompat.resize(s, window.getGuiScaledWidth(), window.getGuiScaledHeight());
            }
        });
    }

    /** Sets the real GUI scale option (0 = auto) and waits a tick; restore it in teardown. */
    public static void setGuiScale(int scale) {
        onClient(() -> InputCompat.setGuiScale(scale));
        waitTicks(1);
    }

    public static int guiScale() {
        return onClient(() -> Minecraft.getInstance().options.guiScale().get());
    }

    private static String describe(@Nullable Screen screen) {
        return screen == null ? "no screen" : screen.getClass().getSimpleName();
    }
}
