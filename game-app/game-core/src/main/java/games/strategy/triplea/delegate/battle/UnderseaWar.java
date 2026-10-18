package games.strategy.triplea.delegate.battle;

import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.TechTracker;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Flames and Steel "Undersea War" tech (Germany and Japan). A nation that has it lets its
 * submarines (every sea unit type that can evade, so Type XXI U-boats too) hit enemy submarines.
 * Their hits must go to the enemy's surface warships first; hits beyond what those can take carry
 * over to the enemy submarines (see SelectMainBattleCasualties). Submarines of nations without the
 * tech keep the map's rule (they cannot target submarines).
 *
 * <p>The XML gives the tech no effect options, so it is recognised by its name.
 */
public final class UnderseaWar {
  public static final String TECH_NAME = "UnderseaWar";

  private UnderseaWar() {}

  public static boolean isSub(final UnitType type) {
    return type.getUnitAttachment().isSea() && type.getUnitAttachment().getCanEvade();
  }

  public static boolean isSub(final Unit unit) {
    return isSub(unit.getType());
  }

  public static boolean hasTech(final GamePlayer player) {
    if (player == null || player.isNull() || player.getData() == null) {
      return false;
    }
    return TechTracker.getCurrentTechAdvances(player, player.getData().getTechnologyFrontier())
        .stream()
        .anyMatch(t -> t.getName().equals(TECH_NAME));
  }

  /** True for a submarine whose owner has Undersea War. */
  public static boolean mayHitSubs(final Unit unit) {
    return isSub(unit) && hasTech(unit.getOwner());
  }

  /**
   * The targets that must take these units' hits first: when they all have Undersea War, the
   * enemy's surface warships among the targets (hits beyond what those can take carry over to the
   * enemy submarines). Empty when the rule doesn't apply.
   */
  public static List<Unit> surfaceShipsFirst(
      final Collection<Unit> firingUnits, final Collection<Unit> targets) {
    if (firingUnits.isEmpty() || !firingUnits.stream().allMatch(UnderseaWar::mayHitSubs)) {
      return List.of();
    }
    return targets.stream().filter(UnderseaWar::isSurfaceShip).collect(Collectors.toList());
  }

  /** A sea unit that is not a submarine; transports don't count (they are never casualties). */
  private static boolean isSurfaceShip(final Unit unit) {
    return unit.getUnitAttachment().isSea()
        && !isSub(unit)
        && !Matches.unitIsSeaTransportButNotCombatSeaTransport().test(unit);
  }
}
