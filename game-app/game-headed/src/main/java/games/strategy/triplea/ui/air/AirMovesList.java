package games.strategy.triplea.ui.air;

import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.UnitType;
import games.strategy.triplea.image.UnitImageFactory.ImageKey;
import games.strategy.triplea.ui.MapUnitTooltipManager;
import games.strategy.triplea.ui.UiContext;
import java.awt.Dimension;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
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

  /** The moves, newest first, as stock draws them. */
  static JComponent build(final UiContext uiContext, final List<Entry> newestFirst) {
    final JPanel items = new JPanel();
    items.setLayout(new BoxLayout(items, BoxLayout.Y_AXIS));
    for (final Entry e : newestFirst) {
      items.add(row(uiContext, e));
    }
    items.setAlignmentX(JComponent.LEFT_ALIGNMENT);
    return items;
  }

  private static JComponent row(final UiContext uiContext, final Entry e) {
    final Dimension buttonSize = new Dimension(80, 22);
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
    unitsBox.add(Box.createHorizontalGlue());
    final JButton show = new JButton("Show");
    show.addActionListener(a -> e.show().run());
    size(show, buttonSize);
    final JButton undo = new JButton("Undo");
    undo.addActionListener(
        a -> {
          undo.setEnabled(false);
          e.undo().run();
        });
    size(undo, buttonSize);
    unitsBox.add(show);
    unitsBox.add(undo);
    final Box textBox = new Box(BoxLayout.X_AXIS);
    textBox.add(new JLabel(e.label()));
    textBox.add(Box.createHorizontalGlue());
    final Box containerBox = new Box(BoxLayout.Y_AXIS);
    containerBox.add(unitsBox);
    containerBox.add(textBox);
    containerBox.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
    containerBox.setAlignmentX(JComponent.LEFT_ALIGNMENT);
    return containerBox;
  }

  private static void size(final JButton button, final Dimension d) {
    button.setMinimumSize(d);
    button.setPreferredSize(d);
    button.setMaximumSize(d);
  }
}
