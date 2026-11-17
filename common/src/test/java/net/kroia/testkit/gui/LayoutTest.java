package net.kroia.testkit.gui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LayoutTest {
    static GuiNode node(String text, int x, int y, int w, int h, int textWidth, GuiNode... children) {
        return new GuiNode("Button", text, null, x, y, w, h, textWidth, true, List.of(children), null);
    }

    static List<Layout.Kind> kinds(List<GuiNode> roots) {
        return Layout.check(roots, 100, 50).stream().map(Layout.Problem::kind).toList();
    }

    @Test
    void rules() {
        assertEquals(List.of(), kinds(List.of(node("a", 0, 0, 50, 20, 10), node("b", 50, 0, 50, 20, 50))), "touching edges, text fits exactly");
        assertEquals(List.of(Layout.Kind.OUTSIDE), kinds(List.of(node("a", 60, 0, 50, 20, 0))));
        assertEquals(List.of(Layout.Kind.OUTSIDE), kinds(List.of(node("a", 0, -1, 50, 20, 0))));
        assertEquals(List.of(Layout.Kind.TEXT), kinds(List.of(node("a", 0, 0, 50, 20, 51))));
        assertEquals(List.of(Layout.Kind.OVERLAP), kinds(List.of(node("a", 0, 0, 50, 20, 0), node("b", 49, 19, 10, 10, 0))));
        // a child inside its parent is fine; children are not checked against the screen (scrolled content)
        assertEquals(List.of(), kinds(List.of(node("p", 0, 0, 100, 50, 0, node("c", 10, 10, 10, 10, 0), node("d", 10, 200, 10, 10, 0)))));
        // overlapping children are siblings too
        assertEquals(List.of(Layout.Kind.OVERLAP), kinds(List.of(node("p", 0, 0, 100, 50, 0, node("c", 0, 0, 10, 10, 0), node("d", 5, 5, 10, 10, 0)))));
        // invisible and zero-size elements do not count
        GuiNode hidden = new GuiNode("Button", "x", null, 0, 0, 500, 500, 900, false, List.of(), null);
        assertEquals(List.of(), kinds(List.of(hidden, node("a", 0, 0, 0, 20, 99))));
    }
}
