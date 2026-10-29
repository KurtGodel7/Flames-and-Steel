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
    // the buttons keep their room at the right; the unit picture takes what is left
    final JPanel top = new JPanel(new BorderLayout());
    top.setOpaque(false);
    top.add(unitsBox, BorderLayout.CENTER);
    top.add(buttons, BorderLayout.EAST);
    final JLabel route = new JLabel(e.label());
    route.setToolTipText(e.label());
    route.setMinimumSize(new Dimension(0, route.getPreferredSize().height));
    final JPanel text = new JPanel(new BorderLayout());
    text.setOpaque(false);
    text.add(route, BorderLayout.CENTER);
    final JPanel containerBox = new JPanel(new BorderLayout());
    containerBox.setOpaque(false);
    containerBox.add(top, BorderLayout.NORTH);
    containerBox.add(text, BorderLayout.SOUTH);
    containerBox.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
    containerBox.setAlignmentX(JComponent.LEFT_ALIGNMENT);
    containerBox.setMaximumSize(
        new Dimension(Integer.MAX_VALUE, containerBox.getPreferredSize().height));
    return containerBox;
  }

  /** A small button: just its text, as stock's moves list buttons are. */
  private static JButton compact(final JButton button) {
    button.setMargin(new java.awt.Insets(1, 6, 1, 6));
    button.setFocusable(false);
    return button;
  }
}
