package games.strategy.triplea.delegate.battle;

import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.properties.GameProperties;
import games.strategy.triplea.Properties;
import games.strategy.triplea.attachments.TechAbilityAttachment;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.TechAdvance;
import games.strategy.triplea.delegate.TechTracker;
import java.util.Collection;
import lombok.experimental.UtilityClass;

/**
 * Flames and Steel (request 51): with "Shore Bombard Per Ground Unit Restricted", a land battle's
 * bombardment rolls at most (land units unloaded from sea into it) x N dice. N is 1, or more with a
 * tech ({@code bombardDicePerUnloadedUnit}). Whole ships fire first, best value first; the last
 * ship used, if cut short, fires the dice it keeps at "FS Bombard Reduced Die Value" (5) or its own
 * value if higher. Ships beyond the cap do not fire.
 */
@UtilityClass
public class BombardCap {
  public static final String REDUCED_DIE_VALUE = "FS Bombard Reduced Die Value";

  public static boolean applies(final GameProperties properties) {
    return Properties.getShoreBombardPerGroundUnitRestricted(properties);
  }

  /** Land units in the battle that were unloaded from sea. */
  public static int unloadedUnits(final Collection<Unit> attackingUnits) {
    return (int)
        attackingUnits.stream()
            .filter(Matches.unitWasAmphibious())
            .filter(Matches.unitIsLand())
            .count();
  }

  /** Dice each unloaded unit allows: 1, or the best {@code bombardDicePerUnloadedUnit} tech. */
  public static int dicePerUnloadedUnit(final GamePlayer player) {
    int perUnit = 1;
    for (final TechAdvance tech :
        TechTracker.getCurrentTechAdvances(player, player.getData().getTechnologyFrontier())) {
      final TechAbilityAttachment ability = TechAbilityAttachment.get(tech);
      if (ability != null) {
        perUnit = Math.max(perUnit, ability.getBombardDicePerUnloadedUnit());
      }
    }
    return perUnit;
  }

  /** The most bombardment dice the attacker may roll in this battle. */
  public static int diceCap(final Collection<Unit> attackingUnits, final GamePlayer attacker) {
    return unloadedUnits(attackingUnits) * dicePerUnloadedUnit(attacker);
  }

  public static int reducedDieValue(final GameProperties properties) {
    return properties.get(REDUCED_DIE_VALUE, 5);
  }

  /**
   * "Bombardment: 2 dice (2 units unloaded)", or with fewer dice than the cap allows "Bombardment:
   * 1 die of up to 3 (3 units unloaded)".
   */
  public static String caption(final int dice, final int units, final int perUnit) {
    final int cap = units * perUnit;
    return "Bombardment: "
        + dice
        + (dice == 1 ? " die" : " dice")
        + (dice < cap ? " of up to " + cap : "")
        + " ("
        + units
        + (units == 1 ? " unit" : " units")
        + " unloaded"
        + (perUnit > 1 ? ", " + perUnit + " dice each" : "")
        + ")";
  }
}
