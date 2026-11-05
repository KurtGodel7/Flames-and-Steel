package games.strategy.triplea.delegate.battle;

import static games.strategy.triplea.delegate.battle.BattleState.Side.OFFENSE;
import static games.strategy.triplea.delegate.battle.BattleState.UnitBattleFilter.ALIVE;

import games.strategy.engine.data.Unit;
import games.strategy.engine.data.properties.GameProperties;
import games.strategy.triplea.delegate.Matches;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.experimental.UtilityClass;

/**
 * Flames and Steel (request 55): in a land battle, attacking land units that came ashore from
 * transports this turn hold fire in the first combat round. They are in the battle and can be taken
 * as casualties, but roll no dice and give no support in round 1. From round 2 they fire normally.
 * Shore bombardment, units that attacked over land and the defenders are unaffected.
 *
 * <p>The rule is on when the game has the property {@value #EXEMPT_PROPERTY}; its value lists the
 * unit types that fire in round 1 even when landing, separated by colons (e.g. {@code
 * marine:rikusentai}). Games without the property (all stock maps) are unaffected.
 */
@UtilityClass
public class AmphibiousLanding {
  public static final String EXEMPT_PROPERTY = "FS Amphibious First Round Exempt";

  /** Ends the name of the battle step that lists the landing units. */
  public static final String STEP_SUFFIX = " landing from the sea hold fire";

  public static boolean applies(final GameProperties properties) {
    return properties.get(EXEMPT_PROPERTY) != null;
  }

  /** Unit type names that fire in round 1 even when landing. */
  public static Set<String> exemptTypes(final GameProperties properties) {
    final Serializable value = properties.get(EXEMPT_PROPERTY);
    if (value == null) {
      return Set.of();
    }
    return Arrays.stream(value.toString().split(":"))
        .map(String::trim)
        .filter(name -> !name.isEmpty())
        .collect(Collectors.toSet());
  }

  /**
   * True for a land unit that was unloaded from sea this turn and is not of an exempt type. Says
   * nothing about the round or side; see {@link #holdingFire(BattleState)}.
   */
  public static boolean isLanding(final Unit unit, final Set<String> exemptTypes) {
    return unit.getWasAmphibious()
        && Matches.unitIsLand().test(unit)
        && !exemptTypes.contains(unit.getType().getName());
  }

  /** The attacking units that hold fire in this round of this battle (empty if none). */
  public static List<Unit> holdingFire(final BattleState battleState) {
    if (!battleState.getStatus().isFirstRound()) {
      return List.of();
    }
    return holdingFire(battleState, battleState.filterUnits(ALIVE, OFFENSE));
  }

  /**
   * Those of {@code attackers} that hold fire in round 1 of this battle, whatever the current
   * round.
   */
  public static List<Unit> holdingFire(
      final BattleState battleState, final Collection<Unit> attackers) {
    // look at the units first: most battles have no units that came by sea
    if (attackers.stream().noneMatch(Unit::getWasAmphibious)
        || battleState.getBattleSite().isWater()) {
      return List.of();
    }
    final GameProperties properties = battleState.getGameData().getProperties();
    if (!applies(properties)) {
      return List.of();
    }
    final Set<String> exempt = exemptTypes(properties);
    return attackers.stream().filter(unit -> isLanding(unit, exempt)).collect(Collectors.toList());
  }

  /** "4 units landing from the sea hold fire this round". */
  public static String logLine(final int units) {
    return units + (units == 1 ? " unit" : " units") + " landing from the sea hold fire this round";
  }
}
