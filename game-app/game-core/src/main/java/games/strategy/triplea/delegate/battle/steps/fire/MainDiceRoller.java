package games.strategy.triplea.delegate.battle.steps.fire;

import static games.strategy.triplea.delegate.battle.BattleState.UnitBattleFilter.ACTIVE;

import games.strategy.engine.data.Unit;
import games.strategy.engine.delegate.IDelegateBridge;
import games.strategy.triplea.Properties;
import games.strategy.triplea.delegate.DiceRoll;
import games.strategy.triplea.delegate.battle.AmphibiousLanding;
import games.strategy.triplea.delegate.battle.BattleState;
import games.strategy.triplea.delegate.dice.RollDiceFactory;
import games.strategy.triplea.delegate.power.calculator.CombatValueBuilder;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.function.BiFunction;

/** Rolls dice for normal (basically, anything that isn't AA) dice requests */
public class MainDiceRoller
    implements BiFunction<IDelegateBridge, RollDiceStep, DiceRoll>, Serializable {
  private static final long serialVersionUID = 11934707918127558L;

  @Override
  public DiceRoll apply(final IDelegateBridge bridge, final RollDiceStep step) {
    return RollDiceFactory.rollBattleDice(
        step.getFiringGroup().getFiringUnits(),
        step.getBattleState().getPlayer(step.getSide()),
        bridge,
        DiceRoll.getAnnotation(
            step.getFiringGroup().getFiringUnits(),
            step.getBattleState().getPlayer(step.getSide()),
            step.getBattleState().getBattleSite(),
            step.getBattleState().getStatus().getRound()),
        CombatValueBuilder.mainCombatValue()
            .enemyUnits(step.getBattleState().filterUnits(ACTIVE, step.getSide().getOpposite()))
            .friendlyUnits(firingSideUnits(step))
            .side(step.getSide())
            .gameSequence(step.getBattleState().getGameData().getSequence())
            .supportAttachments(
                step.getBattleState().getGameData().getUnitTypeList().getSupportRules())
            .lhtrHeavyBombers(
                Properties.getLhtrHeavyBombers(step.getBattleState().getGameData().getProperties()))
            .gameDiceSides(step.getBattleState().getGameData().getDiceSides())
            .territoryEffects(step.getBattleState().getTerritoryEffects())
            .build());
  }

  /**
   * The firing side's units that can fire or give support. Flames and Steel (request 55): units
   * landing from the sea give no support in round 1.
   */
  private static Collection<Unit> firingSideUnits(final RollDiceStep step) {
    final Collection<Unit> units =
        new ArrayList<>(step.getBattleState().filterUnits(ACTIVE, step.getSide()));
    if (step.getSide() == BattleState.Side.OFFENSE) {
      units.removeAll(AmphibiousLanding.holdingFire(step.getBattleState()));
    }
    return units;
  }
}
