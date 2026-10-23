package games.strategy.triplea.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Rectangle;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JViewport;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;

/**
 * Flames and Steel (request 24): a step panel's layout with its heading and main buttons (Done,
 * Undo all, Cancel) in a bar pinned to the top, and everything else in a scrolling area below, so
 * the buttons stay on screen however long the list gets. The mouse wheel scrolls the list.
 *
 * <p>The panel fills {@link #top()} and {@link #body()} after each {@link #install}; the same
 * scroll pane is reused, so the list keeps its scroll position while the panel is rebuilt.
 */
public final class PinnedLayout {
  private final JPanel top = new JPanel();
  private final Body body = new Body();
  private final JScrollPane scroll = new JScrollPane(body);

  public PinnedLayout() {
    top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
    top.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));
    body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
    scroll.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, java.awt.Color.GRAY));
    scroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
    scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
    scroll.getVerticalScrollBar().setUnitIncrement(16);
    scroll.getHorizontalScrollBar().setUnitIncrement(16);
  }

  /** Empties both parts and puts them into the panel (which loses everything else it held). */
  public void install(final Container panel) {
    top.removeAll();
    body.removeAll();
    panel.removeAll();
    panel.setLayout(new BorderLayout());
    panel.add(top, BorderLayout.NORTH);
    panel.add(scroll, BorderLayout.CENTER);
  }

  /**
   * Request 34: as {@link #install}, but {@code fill} (a list with its own scroll bar, such as the
   * moves made) takes all the height below the pinned bar instead of the scrolling body.
   */
  public void installFill(final Container panel, final Component fill) {
    top.removeAll();
    body.removeAll();
    panel.removeAll();
    panel.setLayout(new BorderLayout());
    panel.add(top, BorderLayout.NORTH);
    panel.add(fill, BorderLayout.CENTER);
  }

  /** The pinned bar: heading and the step's main buttons. Add left-aligned components. */
  public JPanel top() {
    return top;
  }

  /** The scrolling part. Add left-aligned components; it ends with glue added by the caller. */
  public JPanel body() {
    return body;
  }

  public JScrollPane scrollPane() {
    return scroll;
  }

  /** Adds a component to the body, left-aligned. */
  public void add(final Component c) {
    body.add(c);
  }

  /**
   * A scroll pane inside the scrolling list (such as the list of moves to undo) that has nothing to
   * scroll itself passes the mouse wheel on to the list around it, which would otherwise not move.
   */
  public static void passWheelToOuter(final JScrollPane inner) {
    inner.addMouseWheelListener(
        e -> {
          final Container outer =
              SwingUtilities.getAncestorOfClass(JScrollPane.class, inner.getParent());
          if (outer != null && !inner.getVerticalScrollBar().isVisible()) {
            outer.dispatchEvent(SwingUtilities.convertMouseEvent(inner, e, outer));
          }
        });
  }

  /** The scrolling content: as wide as the view when it fits, else scrolls sideways too. */
  private static final class Body extends JPanel implements Scrollable {
    private static final long serialVersionUID = 1L;

    @Override
    public Dimension getPreferredScrollableViewportSize() {
      return getPreferredSize();
    }

    @Override
    public int getScrollableUnitIncrement(
        final Rectangle visible, final int orientation, final int direction) {
      return 16;
    }

    @Override
    public int getScrollableBlockIncrement(
        final Rectangle visible, final int orientation, final int direction) {
      return orientation == SwingConstants.VERTICAL ? visible.height - 16 : visible.width - 16;
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
      return getParent() instanceof JViewport v && v.getWidth() > getPreferredSize().width;
    }

    @Override
    public boolean getScrollableTracksViewportHeight() {
      return getParent() instanceof JViewport v && v.getHeight() > getPreferredSize().height;
    }
  }
}
