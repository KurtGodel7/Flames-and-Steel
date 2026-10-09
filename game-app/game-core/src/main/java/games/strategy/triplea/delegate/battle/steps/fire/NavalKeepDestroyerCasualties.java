package games.strategy.triplea.delegate.battle.steps.fire;

import games.strategy.engine.delegate.IDelegateBridge;
import games.strategy.triplea.delegate.ExecutionStack;
import games.strategy.triplea.delegate.battle.BattleActions;
import games.strategy.triplea.delegate.battle.BattleState;
import games.strategy.triplea.delegate.battle.steps.BattleStep;
import java.io.Serial;
import java.util.List;

/**
 * Flames and Steel naval phases: after the destroyers fire, remembers their victims so that the
 * subs' surprise-shot clean-up does not remove them; they fire back later in the round.
 */
public class NavalKeepDestroyerCasualties implements BattleStep {
  @Serial private static final long serialVersionUID = -5402381969224410531L;

  private final BattleState battleState;
  private final BattleActions battleActions;

  public NavalKeepDestroyerCasualties(
      final BattleState battleState, final BattleActions battleActions) {
    this.battleState = battleState;
    this.battleActions = battleActions;
  }

  @Override
  public List<StepDetails> getAllStepDetails() {
    return List.of();
  }

  @Override
  public Order getOrder() {
    return Order.NAVAL_KEEP_DESTROYER_CASUALTIES;
  }

  @Override
  public void execute(final ExecutionStack stack, final IDelegateBridge bridge) {
    if (NavalPhases.apply(battleState)) {
      battleActions.keepCurrentCasualtiesUntilRoundEnd();
    }
  }
}
