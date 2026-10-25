package games.strategy.triplea.delegate.air;

import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.UnitType;
import games.strategy.triplea.delegate.air.AirCombat.Target;
import games.strategy.triplea.delegate.air.AirCombat.TargetAssignment;
import java.util.ArrayList;
import java.util.List;
import lombok.experimental.UtilityClass;

/**
 * Request 28 part B: bombers are assigned one by one. A base's bombers of one type may be split
 * over several targets and "no target" ("Lithuania: 1 dive bomber; Vitebsk: 3; no target: 1"). The
 * counts can never go above the bombers there.
 */
@UtilityClass
public class TargetSplit {

  /** How many of the type in the zone are sent against the target. */
  public static int count(
      final List<TargetAssignment> staged,
      final Territory zone,
      final UnitType type,
      final Target target) {
    return staged.stream()
        .filter(a -> a.zone().equals(zone) && a.unitType().equals(type) && a.target().equals(target))
        .mapToInt(TargetAssignment::count)
        .sum();
  }

  /** How many of the type in the zone have a target. */
  public static int assigned(
      final List<TargetAssignment> staged, final Territory zone, final UnitType type) {
    return staged.stream()
        .filter(a -> a.zone().equals(zone) && a.unitType().equals(type))
        .mapToInt(TargetAssignment::count)
        .sum();
  }

  /** How many of the player's bombers of the type in the zone have no target yet. */
  public static int unassigned(
      final GamePlayer player,
      final List<TargetAssignment> staged,
      final Territory zone,
      final UnitType type) {
    final long there =
        zone.getUnitCollection().stream()
            .filter(u -> u.isOwnedBy(player) && u.getType().equals(type) && !AirBases.isAirborne(u))
            .count();
    return (int) Math.max(0, there - assigned(staged, zone, type));
  }

  /**
   * The assignments with {@code delta} more (or fewer) bombers of the type against the target,
   * kept within the bombers available: never below 0 at the target, never more in all than {@code
   * available}. Assignments that drop to 0 are removed; one target keeps one entry.
   *
   * @param available how many bombers of the type are in the zone
   */
  public static List<TargetAssignment> adjust(
      final List<TargetAssignment> staged,
      final Territory zone,
      final UnitType type,
      final Target target,
      final int delta,
      final int available) {
    final int now = count(staged, zone, type, target);
    final int free = Math.max(0, available - assigned(staged, zone, type));
    final int wanted = Math.max(0, now + Math.min(delta, free));
    final List<TargetAssignment> result = new ArrayList<>();
    boolean placed = false;
    for (final TargetAssignment a : staged) {
      if (a.zone().equals(zone) && a.unitType().equals(type) && a.target().equals(target)) {
        if (!placed && wanted > 0) {
          result.add(new TargetAssignment(zone, type, wanted, target));
        }
        placed = true;
      } else {
        result.add(a);
      }
    }
    if (!placed && wanted > 0) {
      result.add(new TargetAssignment(zone, type, wanted, target));
    }
    return result;
  }
}
