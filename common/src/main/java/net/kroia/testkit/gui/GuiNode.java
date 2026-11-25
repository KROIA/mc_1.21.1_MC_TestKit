package net.kroia.testkit.gui;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Consumer;

/**
 * One GUI element as the tests see it: type, bounds (GUI-scaled), text and optional name. Built from vanilla
 * widgets by {@link Gui} and from other element trees (e.g. ModUtilities GuiElements) by a {@link GuiLocator}.
 *
 * <p>Bounds are whole GUI pixels. A locator that scales float positions rounds each edge, not the size:
 * {@code x = round(left * scale)}, {@code width = round(right * scale) - x}. Elements that touch before scaling
 * then still touch after it instead of overlapping by a pixel.
 *
 * @param type      simple class name, e.g. {@code Button}
 * @param text      the text the element draws ("" if none)
 * @param name      a stable test name, if the element has one
 * @param textWidth width of {@code text} in GUI pixels with the game font. 0 = no {@code TEXT} layout check: no
 *                  text, or text that legitimately clips or scrolls (TextBox content). A vanilla EditBox reports
 *                  0, because its message is the narration title and is never drawn.
 * @param visible   false if the element or one of its parents is hidden; {@link Gui#click} refuses hidden nodes
 * @param overlay   true for an element drawn on top of its siblings on purpose (an open dropdown, a popup):
 *                  the {@code OVERLAP} layout check skips it
 * @param handle    the element object itself, for checks on its state (an EditBox's value, a slider's value).
 *                  The node is a snapshot taken on the client thread; read the live handle only inside
 *                  {@link Gui#onClient}, e.g. {@code Gui.onClient(() -> ((EditBox) node.handle()).getValue())}.
 */
public record GuiNode(String type, String text, @Nullable String name, int x, int y, int width, int height,
                      int textWidth, boolean visible, boolean overlay, List<GuiNode> children, @Nullable Object handle) {

    /** A node that is not an overlay (the signature from before {@code overlay} existed). */
    public GuiNode(String type, String text, @Nullable String name, int x, int y, int width, int height,
                   int textWidth, boolean visible, List<GuiNode> children, @Nullable Object handle) {
        this(type, text, name, x, y, width, height, textWidth, visible, false, children, handle);
    }

    public int centerX() {
        return x + width / 2;
    }

    public int centerY() {
        return y + height / 2;
    }

    public boolean contains(double px, double py) {
        return px >= x && py >= y && px < x + width && py < y + height;
    }

    /** Visits this node and all its descendants, depth first. */
    public void forEach(Consumer<GuiNode> action) {
        action.accept(this);
        for (GuiNode child : children) child.forEach(action);
    }

    @Override
    public String toString() {
        return type + (name != null ? "#" + name : "") + (text.isEmpty() ? "" : " '" + text + "'")
                + " [" + x + "," + y + " " + width + "x" + height + "]";
    }
}
