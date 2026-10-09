package games.strategy.triplea.ui.air;

import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.triplea.ui.MouseDetails;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
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

  /** Marks the selected stacks in air map mode: an orange frame and "2 picked". */
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
      AirDraw.tag(g, selected.getInt(type) + " picked", s.x + s.width / 2, s.y - 8, Color.ORANGE);
    }
  }
}
