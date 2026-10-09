package games.strategy.triplea.ui.air;

import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.triplea.image.UnitImageFactory;
import games.strategy.triplea.image.UnitImageFactory.ImageKey;
import games.strategy.triplea.ui.MouseDetails;
import games.strategy.triplea.ui.UiContext;
import games.strategy.triplea.ui.research.ResearchView;
import games.strategy.ui.ScrollableTextField;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import javax.swing.JLabel;
import javax.swing.JPanel;
import org.triplea.java.collections.IntegerMap;

/**
 * Flames and Steel: picking aircraft the way a TripleA move picks units. A click on a stack adds
 * one aircraft, Ctrl-click the whole stack, Alt-click ten, Shift-click every aircraft in the zone;
 * a right click (handled by the panels) puts one back.
 */
final class AirSelection {
  private static final int ALT_COUNT = 10;
  static final String HELP =
      "Click a stack to pick one aircraft (Ctrl: the whole stack, Alt: ten, Shift: all in the zone);"
          + " right-click puts one back.";

  private AirSelection() {}

  /**
   * Adds aircraft to the selection.
   *
   * @param clicked the units of the clicked stack
   * @param eligible which of them may be picked now
   * @return how many aircraft were added
   */
  static int add(
      final IntegerMap<UnitType> selected,
      final IntegerMap<UnitType> available,
      final List<Unit> clicked,
      final Predicate<Unit> eligible,
      final MouseDetails md) {
    int added = 0;
    if (md.isShiftDown()) {
      for (final UnitType type : available.keySet()) {
        added += available.getInt(type) - selected.getInt(type);
        selected.put(type, available.getInt(type));
      }
      return added;
    }
    final List<Unit> stack = clicked.stream().filter(eligible).toList();
    if (stack.isEmpty()) {
      return 0;
    }
    final UnitType type = stack.get(0).getType();
    final int room = available.getInt(type) - selected.getInt(type);
    final int wanted =
        md.isControlDown()
            ? (int) stack.stream().filter(u -> u.getType().equals(type)).count()
            : md.isAltDown() ? ALT_COUNT : 1;
    added = Math.max(0, Math.min(room, wanted));
    if (added > 0) {
      selected.add(type, added);
    }
    return added;
  }

  /**
   * An arrow button beside a stack on the map: one more or one fewer; with Ctrl or Shift, all or
   * none.
   */
  static void applyArrow(
      final IntegerMap<UnitType> selected,
      final IntegerMap<UnitType> available,
      final AirMapMode.ArrowHit arrow,
      final MouseDetails md) {
    final UnitType type = arrow.type();
    final int max = available.getInt(type);
    final boolean all = md.isControlDown() || md.isShiftDown();
    final int current = selected.getInt(type);
    final int next =
        arrow.delta() > 0
            ? (all ? max : Math.min(max, current + 1))
            : (all ? 0 : Math.max(0, current - 1));
    if (next > 0) {
      selected.put(type, next);
    } else {
      selected.removeKey(type);
    }
  }

  /**
   * A panel that is never stretched taller than it needs in the Actions tab's vertical layout, so
   * no gaps open between its rows.
   */
  static JPanel compactPanel() {
    return new JPanel() {
      private static final long serialVersionUID = 1L;

      @Override
      public java.awt.Dimension getMaximumSize() {
        return new java.awt.Dimension(Integer.MAX_VALUE, getPreferredSize().height);
      }
    };
  }

  /** HTML text wrapped to the width of the Actions tab. */
  static String wrap(final String text) {
    return "<html><div style='width:190px'>" + text + "</div></html>";
  }

  /** The selected aircraft themselves, for highlighting their stacks in the boxes. */
  static List<Collection<Unit>> highlight(
      final IntegerMap<UnitType> selected, final List<Unit> zoneAircraft) {
    final List<Collection<Unit>> result = new ArrayList<>();
    for (final UnitType type : selected.keySet()) {
      result.add(
          zoneAircraft.stream()
              .filter(u -> u.getType().equals(type))
              .limit(selected.getInt(type))
              .toList());
    }
    return result;
  }

  /**
   * The stock unit chooser rows for a zone: each aircraft type available there with the arrow
   * counter (up one, up to all, down one, down to none). Changing a counter changes the selection
   * and calls {@code changed}.
   */
  static JPanel chooser(
      final UiContext uiContext,
      final GamePlayer player,
      final IntegerMap<UnitType> available,
      final IntegerMap<UnitType> selected,
      final Runnable changed) {
    final JPanel panel = new JPanel(new GridBagLayout());
    final GridBagConstraints c = new GridBagConstraints();
    c.anchor = GridBagConstraints.WEST;
    c.insets = new Insets(1, 4, 1, 4);
    c.gridy = 0;
    for (final UnitType type : available.keySet()) {
      // icon on the left; name above, arrow counter below (fits the narrow Actions tab)
      c.gridx = 0;
      c.gridheight = 2;
      panel.add(
          new JLabel(
              uiContext
                  .getUnitImageFactory()
                  .getIcon(ImageKey.builder().player(player).type(type).build())),
          c);
      c.gridheight = 1;
      c.gridx = 1;
      panel.add(
          new JLabel(
              ResearchView.displayName(type.getName()) + " (" + available.getInt(type) + " here)"),
          c);
      c.gridy++;
      final ScrollableTextField field = new ScrollableTextField(0, available.getInt(type));
      field.setValue(Math.min(selected.getInt(type), available.getInt(type)));
      field.addChangeListener(
          f -> {
            if (f.getValue() > 0) {
              selected.put(type, f.getValue());
            } else {
              selected.removeKey(type);
            }
            changed.run();
          });
      c.gridx = 1;
      panel.add(field, c);
      c.gridy++;
    }
    return panel;
  }

  /** The picked aircraft drawn at the mouse, like the units following the cursor in a move. */
  static void paintShadow(
      final Graphics2D g,
      final UiContext uiContext,
      final GamePlayer player,
      final IntegerMap<UnitType> selected,
      final Point at) {
    final UnitImageFactory images = uiContext.getUnitImageFactory();
    int x = at.x + 12;
    final int y = at.y + 12;
    final Composite old = g.getComposite();
    for (final UnitType type : selected.keySet()) {
      g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.8f));
      g.drawImage(
          images.getImage(ImageKey.builder().player(player).type(type).build()), x, y, null);
      g.setComposite(old);
      AirDraw.countLabel(
          g,
          String.valueOf(selected.getInt(type)),
          x + images.getUnitImageWidth() + 12,
          y + images.getUnitImageHeight());
      x += images.getUnitImageWidth() + 16;
    }
  }

  /** Marks the selected stacks in air map mode: an orange frame and the number picked. */
  static void paintPicked(
      final Graphics2D g,
      final AirMapMode mode,
      final Territory zone,
      final GamePlayer owner,
      final IntegerMap<UnitType> selected) {
    for (final UnitType type : selected.keySet()) {
      final Optional<Rectangle> r = mode.stackRect(zone, owner, type);
      if (r.isEmpty()) {
        continue;
      }
      final Rectangle s = r.get();
      g.setColor(AirDraw.ORIGIN_LINE);
      g.setStroke(new BasicStroke(3f));
      g.drawRoundRect(s.x - 2, s.y - 2, s.width + 4, s.height + 4, 6, 6);
      // the number picked, as an orange badge on the stack's corner
      final String n = String.valueOf(selected.getInt(type));
      g.setFont(g.getFont().deriveFont(java.awt.Font.BOLD, 12f));
      final int bw = Math.max(16, g.getFontMetrics().stringWidth(n) + 8);
      g.setColor(Color.ORANGE);
      g.fillOval(s.x - 6, s.y - 6, bw, 16);
      g.setColor(Color.BLACK);
      g.drawOval(s.x - 6, s.y - 6, bw, 16);
      g.drawString(n, s.x - 6 + (bw - g.getFontMetrics().stringWidth(n)) / 2, s.y + 6);
    }
  }
}
