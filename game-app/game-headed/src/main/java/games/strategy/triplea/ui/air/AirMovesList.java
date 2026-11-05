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

  /**
   * Request 53: the list in a titled box that never asks for more width than the panel has (long
   * lines are cut, with their tooltips) and never scrolls sideways; past {@code MAX_ROWS} moves it
   * scrolls down inside itself.
   */
  static JComponent boxed(final JComponent list, final String title) {
    final javax.swing.JScrollPane scroll =
        new javax.swing.JScrollPane(list) {
          private static final long serialVersionUID = 1L;

          @Override
          public Dimension getPreferredSize() {
            int h = 0;
            final java.awt.Component[] rows = list.getComponents();
            if (list instanceof JLabel || rows.length == 0) {
              h = list.getPreferredSize().height;
            } else {
              for (int i = 0; i < Math.min(rows.length, MAX_ROWS); i++) {
                h += rows[i].getPreferredSize().height;
              }
            }
            final java.awt.Insets in = getInsets();
            return new Dimension(200, h + in.top + in.bottom + 4);
          }

          @Override
          public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
          }
        };
    scroll.setBorder(BorderFactory.createTitledBorder(title));
    scroll.getVerticalScrollBar().setUnitIncrement(16);
    scroll.setHorizontalScrollBarPolicy(javax.swing.JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
    scroll.setAlignmentX(JComponent.LEFT_ALIGNMENT);
    return scroll;
  }

  private static final int MAX_ROWS = 10;

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
    // request 53: Undo and Show come first on the line, so they are in sight at any panel width
    // (even when something else makes the side panel scroll sideways); the picture follows
    final JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
    top.setOpaque(false);
    top.add(compact(undo));
    top.add(compact(show));
    top.add(unitsBox);
    // a long route is cut short at the panel's edge; the tooltip has all of it
    final JLabel route = new JLabel(e.label());
    route.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 0));
    route.setToolTipText(e.label());
    undo.setToolTipText("Undo move " + e.number() + ": " + e.label());
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

  /** A small button: just its text, as stock's moves list buttons are. */
  private static JButton compact(final JButton button) {
    button.setMargin(new java.awt.Insets(1, 6, 1, 6));
    button.setFocusable(false);
    return button;
  }
}
