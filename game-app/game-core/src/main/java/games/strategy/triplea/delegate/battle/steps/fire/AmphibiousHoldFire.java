package games.strategy.triplea.delegate.battle.steps.fire;

import static games.strategy.triplea.delegate.battle.BattleState.Side.DEFENSE;
import static games.strategy.triplea.delegate.battle.BattleState.Side.OFFENSE;
import static games.strategy.triplea.delegate.battle.BattleState.UnitBattleFilter.ALIVE;

import games.strategy.engine.data.Unit;
import games.strategy.engine.delegate.IDelegateBridge;
import games.strategy.triplea.delegate.ExecutionStack;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.battle.AmphibiousLanding;
import games.strategy.triplea.delegate.battle.BattleActions;
import games.strategy.triplea.delegate.battle.BattleState;
import games.strategy.triplea.delegate.battle.steps.BattleStep;
import java.util.List;
import lombok.AllArgsConstructor;

/**
 * Flames and Steel (request 55): in round 1, lists the attacking units landing from the sea that
 * hold fire ("Italians landing from the sea hold fire") and writes "4 units landing from the sea
 * hold fire this round" to the battle's history. The units themselves are left out of the
 * attacker's fire by {@link AmphibiousLanding#holdingFire(BattleState)}.
 */
@AllArgsConstructor
public class AmphibiousHoldFire implements BattleStep {
  private static final long serialVersionUID = -2215704830955482631L;

  protected final BattleState battleState;

  protected final BattleActions battleActions;

  @Override
  public List<StepDetails> getAllStepDetails() {
    return AmphibiousLanding.holdingFire(battleState).isEmpty()
        ? List.of()
        : List.of(new StepDetails(stepName(), this));
  }

  private String stepName() {
    return battleState.getPlayer(OFFENSE).getName() + AmphibiousLanding.STEP_SUFFIX;
  }

  @Override
  public Order getOrder() {
    return Order.AMPHIBIOUS_HOLD_FIRE;
  }

  @Override
  public void execute(final ExecutionStack stack, final IDelegateBridge bridge) {
    final List<Unit> holding = AmphibiousLanding.holdingFire(battleState);
    // nothing to say when the bombardment already destroyed the defenders
    if (holding.isEmpty()
        || battleState.filterUnits(ALIVE, DEFENSE).stream()
            .noneMatch(Matches.unitIsNotInfrastructure())) {
      return;
    }
    bridge.getHistoryWriter().addChildToEvent(AmphibiousLanding.logLine(holding.size()), holding);
  }
}
