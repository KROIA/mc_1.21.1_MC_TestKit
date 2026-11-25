package net.kroia.testkit.gui;

import java.util.ArrayList;
import java.util.List;

/**
 * Layout rules for a screen's element tree. Pure logic on {@link GuiNode}s, so it also runs offline.
 * Only visible elements with a size count. Rules:
 * <ul>
 *   <li>{@code OUTSIDE}: a top-level element is not fully inside the screen;</li>
 *   <li>{@code OVERLAP}: two sibling elements overlap (a child inside its parent is fine; an
 *       {@link GuiNode#overlay() overlay} may cover its siblings);</li>
 *   <li>{@code TEXT}: an element's text is wider than the element ({@code textWidth} 0 = not checked).</li>
 * </ul>
 * Children are not checked against the screen: scrolled list content is legitimately outside.
 */
public final class Layout {
    private Layout() {}

    public enum Kind { OUTSIDE, OVERLAP, TEXT }

    /** @param other the second element of an {@code OVERLAP}, else null */
    public record Problem(Kind kind, GuiNode node, GuiNode other, String message) {
        @Override
        public String toString() {
            return message;
        }
    }

    public static List<Problem> check(List<GuiNode> roots, int screenWidth, int screenHeight) {
        List<Problem> problems = new ArrayList<>();
        for (GuiNode n : roots) {
            if (counts(n) && (n.x() < 0 || n.y() < 0 || n.x() + n.width() > screenWidth || n.y() + n.height() > screenHeight))
                problems.add(new Problem(Kind.OUTSIDE, n, null, n + " is outside the screen " + screenWidth + "x" + screenHeight));
        }
        checkSiblings(roots, problems);
        return problems;
    }

    private static void checkSiblings(List<GuiNode> siblings, List<Problem> problems) {
        for (int i = 0; i < siblings.size(); i++) {
            GuiNode a = siblings.get(i);
            if (!counts(a)) continue;
            if (a.textWidth() > a.width())
                problems.add(new Problem(Kind.TEXT, a, null, a + ": text is " + a.textWidth() + " px wide, element " + a.width()));
            for (int j = i + 1; j < siblings.size(); j++) {
                GuiNode b = siblings.get(j);
                if (counts(b) && !a.overlay() && !b.overlay() && a.x() < b.x() + b.width() && b.x() < a.x() + a.width() && a.y() < b.y() + b.height() && b.y() < a.y() + a.height())
                    problems.add(new Problem(Kind.OVERLAP, a, b, a + " overlaps " + b));
            }
            checkSiblings(a.children(), problems);
        }
    }

    private static boolean counts(GuiNode n) {
        return n.visible() && n.width() > 0 && n.height() > 0;
    }
}
