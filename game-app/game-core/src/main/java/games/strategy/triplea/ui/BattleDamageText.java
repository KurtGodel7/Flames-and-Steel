package games.strategy.triplea.ui;

import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.formatter.MyFormatter;
import games.strategy.triplea.formatter.UnitNames;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.triplea.java.collections.IntegerMap;

/**
 * Flames and Steel (request 11 item 1): plain words for battle damage, so players can judge whether
 * to retreat. "British battleship took 2 hits: 3 of 5 left", "Italian cruiser sunk", and each
 * side's state: "British: 2 battleships 3/5, 5/5; 8 HP left; about 1.3 hits a round".
 */
public final class BattleDamageText {
  /** The line that ends the retreat summary. */
  public static final String HEALING = "Damage heals at the end of the game round.";

  private BattleDamageText() {}

  /** "German" for the Germans, "British" for the British. */
  static String adjective(final GamePlayer player) {
    final String name = player.getName();
    return name.endsWith("ns") ? name.substring(0, name.length() - 1) : name;
  }

  static int hitPointsLeft(final Unit unit) {
    return Math.max(0, Unit.maxHitPoints(unit) - unit.getHits());
  }

  /** What each hit of a fire round did, one line per unit hit. */
  public static List<String> hitLines(final Collection<Unit> killed, final List<Unit> damaged) {
    final List<String> lines = new ArrayList<>();
    final IntegerMap<Unit> hits = new IntegerMap<>();
    damaged.forEach(u -> hits.add(u, 1));
    for (final Unit unit : hits.keySet()) {
      if (killed.contains(unit)) {
        continue;
      }
      final int n = hits.getInt(unit);
      lines.add(
          adjective(unit.getOwner())
              + " "
              + UnitNames.of(unit.getType())
              + " took "
              + n
              + MyFormatter.pluralize(" hit", n)
              + ": "
              + hitPointsLeft(unit)
              + " of "
              + Unit.maxHitPoints(unit)
              + " left");
    }
    for (final Unit unit : killed) {
      lines.add(
          adjective(unit.getOwner())
              + " "
              + UnitNames.of(unit.getType())
              + (Matches.unitIsSea().test(unit) ? " sunk" : " destroyed"));
    }
    return lines;
  }

  /**
   * "British: 2 battleships 3/5, 5/5, 3 destroyers; 13 HP left; about 1.3 hits a round".
   *
   * @param totalPower the side's total attack power (strength times rolls) against the enemy now
   */
  public static String sideSummary(
      final GamePlayer player,
      final Collection<Unit> units,
      final int totalPower,
      final int diceSides) {
    final Map<UnitType, List<Unit>> byType = new LinkedHashMap<>();
    for (final Unit u : units) {
      byType.computeIfAbsent(u.getType(), k -> new ArrayList<>()).add(u);
    }
    final List<String> parts = new ArrayList<>();
    int hpLeft = 0;
    for (final Map.Entry<UnitType, List<Unit>> e : byType.entrySet()) {
      final List<Unit> group = e.getValue();
      final String name = MyFormatter.pluralize(UnitNames.of(e.getKey()), group.size());
      final StringBuilder part = new StringBuilder(group.size() + " " + name);
      if (Unit.maxHitPoints(group.get(0)) > 1) {
        part.append(' ')
            .append(
                String.join(
                    ", ",
                    group.stream()
                        .sorted(java.util.Comparator.comparingInt(BattleDamageText::hitPointsLeft))
                        .map(u -> hitPointsLeft(u) + "/" + Unit.maxHitPoints(u))
                        .toList()));
      }
      parts.add(part.toString());
      for (final Unit u : group) {
        hpLeft += hitPointsLeft(u);
      }
    }
    final double expected = diceSides <= 0 ? 0 : totalPower / (double) diceSides;
    return player.getName()
        + ": "
        + (parts.isEmpty() ? "no units" : String.join(", ", parts))
        + "; "
        + hpLeft
        + " HP left; about "
        + String.format(Locale.ROOT, "%.1f", expected)
        + " hits a round";
  }
}
