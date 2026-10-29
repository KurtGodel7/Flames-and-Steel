package games.strategy.triplea.ui.air;

import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.UnitType;
import games.strategy.triplea.image.UnitImageFactory.ImageKey;
import games.strategy.triplea.ui.MapUnitTooltipManager;
import games.strategy.triplea.ui.UiContext;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Rectangle;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;

/**
 * Request 42: the air stages' moves list, laid out exactly as stock TripleA's moves list
 * (AbstractUndoableMovesPanel): newest first, each move numbered, its units as images with "x2",
 * Show and Undo on that line, and "From -> To" under it.
 */
final class AirMovesList {
  private AirMovesList() {}

  /** One move: who, what type and how many, the route text, and its two buttons. */
  record Entry(
      int number,
      GamePlayer owner,
      UnitType type,
      int count,
      String label,
      Runnable show,
      Runnable undo) {}

  /**
   * The moves, newest first, as stock draws them. Request 44: the list is as wide as its box (it
   * never runs past the side panel's edge), so Show and Undo stay in sight; a long route line is
   * cut short with the whole of it in its tooltip.
   */
  static JComponent build(final UiContext uiContext, final List<Entry> newestFirst) {
    final JPanel items = new WidthTrackingPanel();
    items.setLayout(new BoxLayout(items, BoxLayout.Y_AXIS));
    for (final Entry e : newestFirst) {
      items.add(row(uiContext, e));
    }
    items.setAlignmentX(JComponent.LEFT_ALIGNMENT);
    return items;
  }

  /** In a scroll pane, laid out at the pane's width (as a text area that wraps). */
  private static final class WidthTrackingPanel extends JPanel implements Scrollable {
    private static final long serialVersionUID = 1L;

    @Override
    public Dimension getPreferredScrollableViewportSize() {
      return getPreferredSize();
    }

    @Override
    public int getScrollableUnitIncrement(
        final Rectangle visibleRect, final int orientation, final int direction) {
      return 16;
    }

    @Override
    public int getScrollableBlockIncrement(
        final Rectangle visibleRect, final int orientation, final int direction) {
      return Math.max(16, visibleRect.height - 16);
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
      return true;
    }

    @Override
    public boolean getScrollableTracksViewportHeight() {
      return false;
    }
  }

  private static JComponent row(final UiContext uiContext, final Entry e) {
    final Box unitsBox = new Box(BoxLayout.X_AXIS);
    unitsBox.add(new JLabel(e.number() + ") "));
    final JLabel units =
        new JLabel(
            "x" + e.count() + " ",
            uiContext
                .getUnitImageFactory()
                .getIcon(ImageKey.builder().player(e.owner()).type(e.type()).build()),
            SwingConstants.LEFT);
    MapUnitTooltipManager.setUnitTooltip(units, e.type(), e.owner(), e.count(), uiContext);
    unitsBox.add(units);
    final JButton show = new JButton("Show");
    show.addActionListener(a -> e.show().run());
    final JButton undo = new JButton("Undo");
    undo.addActionListener(
        a -> {
          undo.setEnabled(false);
          e.undo().run();
        });
    final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
    buttons.setOpaque(false);
    buttons.add(compact(show));
    buttons.add(compact(undo));
    // the buttons sit at the right of the unit picture; request 47: under it when the panel is too
    // narrow for both, never past its edge
    final JPanel top = new JPanel(new BesideOrBelow());
    top.setOpaque(false);
    top.add(unitsBox);
    top.add(buttons);
    final JLabel route = new JLabel(e.label());
    route.setToolTipText(e.label());
    route.setMinimumSize(new Dimension(0, route.getPreferredSize().height));
    final JPanel text = new JPanel(new BorderLayout());
    text.setOpaque(false);
    text.add(route, BorderLayout.CENTER);
    // request 47: as tall as its lines now (Show and Undo may have gone under the picture)
    final JPanel containerBox =
        new JPanel(new BorderLayout()) {
          private static final long serialVersionUID = 1L;

          @Override
          public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
          }
        };
    containerBox.setOpaque(false);
    containerBox.add(top, BorderLayout.NORTH);
    containerBox.add(text, BorderLayout.SOUTH);
    containerBox.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
    containerBox.setAlignmentX(JComponent.LEFT_ALIGNMENT);
    return containerBox;
  }

  /**
   * Request 47: two components, the first at the left and the second at the right of one line;
   * when the width is too small for both, the second goes on a line of its own under the first,
   * at the right.
   */
  static final class BesideOrBelow implements java.awt.LayoutManager {
    private static final String BESIDE = "fsBesideOrBelow";

    /** Lays out again everything up to the panel holding the moves box (a scroll pane stops it). */
    private static void relayout(final JComponent row) {
      final java.awt.Container box =
          javax.swing.SwingUtilities.getAncestorOfClass(javax.swing.JScrollPane.class, row);
      final java.awt.Container outer = box == null ? null : box.getParent();
      row.revalidate();
      if (outer instanceof JComponent c) {
        c.revalidate();
        c.repaint();
      }
    }

    @Override
    public void addLayoutComponent(final String name, final java.awt.Component comp) {}

    @Override
    public void removeLayoutComponent(final java.awt.Component comp) {}

    private static boolean fits(final java.awt.Container parent, final int width) {
      final java.awt.Component first = parent.getComponent(0);
      final java.awt.Component second = parent.getComponent(1);
      return width <= 0
          || first.getPreferredSize().width + second.getPreferredSize().width <= width;
    }

    @Override
    public Dimension preferredLayoutSize(final java.awt.Container parent) {
      final java.awt.Insets in = parent.getInsets();
      final Dimension a = parent.getComponent(0).getPreferredSize();
      final Dimension b = parent.getComponent(1).getPreferredSize();
      final int width = parent.getWidth() - in.left - in.right;
      return fits(parent, width)
          ? new Dimension(
              a.width + b.width + in.left + in.right,
              Math.max(a.height, b.height) + in.top + in.bottom)
          : new Dimension(
              Math.max(a.width, b.width) + in.left + in.right,
              a.height + b.height + in.top + in.bottom);
    }

    @Override
    public Dimension minimumLayoutSize(final java.awt.Container parent) {
      final Dimension b = parent.getComponent(1).getPreferredSize();
      final java.awt.Insets in = parent.getInsets();
      return new Dimension(b.width + in.left + in.right, b.height + in.top + in.bottom);
    }

    @Override
    public void layoutContainer(final java.awt.Container parent) {
      final java.awt.Insets in = parent.getInsets();
      final int width = parent.getWidth() - in.left - in.right;
      final java.awt.Component first = parent.getComponent(0);
      final java.awt.Component second = parent.getComponent(1);
      final Dimension a = first.getPreferredSize();
      final Dimension b = second.getPreferredSize();
      final boolean beside = fits(parent, width);
      if (parent instanceof JComponent row) {
        // the row's height changed: the list and the box around it are laid out again
        final Object was = row.getClientProperty(BESIDE);
        if (!Boolean.valueOf(beside).equals(was == null ? Boolean.TRUE : was)) {
          javax.swing.SwingUtilities.invokeLater(() -> relayout(row));
        }
        row.putClientProperty(BESIDE, beside);
      }
      if (beside) {
        final int h = Math.max(a.height, b.height);
        first.setBounds(in.left, in.top + (h - a.height) / 2, width - b.width, a.height);
        second.setBounds(in.left + width - b.width, in.top + (h - b.height) / 2, b.width, b.height);
      } else {
        first.setBounds(in.left, in.top, width, a.height);
        second.setBounds(
            in.left + Math.max(0, width - b.width), in.top + a.height, Math.min(width, b.width), b.height);
      }
    }
  }

  /** A small button: just its text, as stock's moves list buttons are. */
  private static JButton compact(final JButton button) {
    button.setMargin(new java.awt.Insets(1, 6, 1, 6));
    button.setFocusable(false);
    return button;
  }
}
