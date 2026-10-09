package games.strategy.triplea.delegate.battle.steps.fire;

import games.strategy.triplea.Properties;
import games.strategy.triplea.delegate.battle.BattleState;
import lombok.experimental.UtilityClass;

/**
 * Flames and Steel naval combat phases (game property "Naval Combat Phases"). Each round of a sea
 * battle: 1. destroyers fire (hits go to subs while any remain; their victims still fire later); 2.
 * subs fire a surprise shot at surface ships (their victims are removed without firing back); 3.
 * all other ships fire (their victims fire back). Destroyers fire only in phase 1.
 */
@UtilityClass
public class NavalPhases {
  public static boolean apply(final BattleState battleState) {
    return battleState.getBattleSite() != null
        && battleState.getBattleSite().isWater()
        && Properties.getNavalCombatPhases(battleState.getGameData().getProperties());
  }
}
