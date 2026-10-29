package games.strategy.triplea.delegate.air;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Request 46: the round's air battles as pictures, sent to every screen at the end of the air
 * phase. Units and nations are named by their XML names, so the report reads the same on every
 * computer; each screen draws it for its own viewing nation (see AirBattleSummaryPanel).
 */
public record AirBattleReport(List<Row> rows, Map<String, Integer> bombingTotals)
    implements Serializable {
  @Serial private static final long serialVersionUID = 1L;

  public AirBattleReport {
    rows = List.copyOf(rows);
    bombingTotals = new LinkedHashMap<>(bombingTotals);
  }

  /**
   * One territory's or sea zone's battle.
   *
   * @param place the territory or sea zone name
   * @param attackers the attacking side's units (the bombers' side, or the aircraft from outside)
   * @param defenders the other side's units: aircraft, units struck, AA guns that fired, a factory or
   *     SPC bombed
   * @param notes what pictures can't say, in plain words with XML nation names
   * @param lossValue PU value each nation lost here (units destroyed plus PUs bombed away)
   * @param hurt the nations that lost or had damaged any unit, or lost PUs
   */
  public record Row(
      String place,
      List<Stack> attackers,
      List<Stack> defenders,
      List<String> notes,
      Map<String, Integer> lossValue,
      List<String> hurt)
      implements Serializable {
    @Serial private static final long serialVersionUID = 1L;

    public Row {
      attackers = List.copyOf(attackers);
      defenders = List.copyOf(defenders);
      notes = List.copyOf(notes);
      lossValue = new LinkedHashMap<>(lossValue);
      hurt = List.copyOf(hurt);
    }

    /** All the PU value lost here by the given nations. */
    public int lossOf(final java.util.function.Predicate<String> nations) {
      return lossValue.entrySet().stream()
          .filter(e -> nations.test(e.getKey()))
          .mapToInt(Map.Entry::getValue)
          .sum();
    }

    /** All hits taken here by units of the given nations (destroyed units count their hits). */
    public int hitsOf(final java.util.function.Predicate<String> nations) {
      final List<Stack> all = new ArrayList<>(attackers);
      all.addAll(defenders);
      return all.stream()
          .filter(s -> nations.test(s.owner()))
          .mapToInt(s -> s.count() * (s.destroyed() ? Math.max(1, s.hits()) : s.hits()))
          .sum();
    }
  }

  /**
   * Identical units drawn as one picture with a count.
   *
   * @param hits hits the units have taken (shown on their hit-point bar)
   * @param destroyed drawn greyed with a red X
   * @param aborted bombers that made no attack: a small "aborted" tag
   * @param bombed PUs this factory or SPC cost its owner ("−6 PU"), or 0
   */
  public record Stack(
      String owner,
      String unitType,
      int count,
      int hits,
      boolean destroyed,
      boolean aborted,
      int bombed)
      implements Serializable {
    @Serial private static final long serialVersionUID = 1L;
  }
}
