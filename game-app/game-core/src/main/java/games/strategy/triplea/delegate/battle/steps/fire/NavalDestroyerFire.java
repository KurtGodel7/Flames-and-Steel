package games.strategy.triplea.delegate.battle.steps.fire;

import static games.strategy.triplea.delegate.battle.BattleState.Side.OFFENSE;
import static games.strategy.triplea.delegate.battle.BattleState.UnitBattleFilter.ALIVE;

import games.strategy.engine.delegate.IDelegateBridge;
import games.strategy.triplea.delegate.ExecutionStack;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.battle.BattleActions;
import games.strategy.triplea.delegate.battle.BattleState;
import games.strategy.triplea.delegate.battle.MustFightBattle.ReturnFire;
import games.strategy.triplea.delegate.battle.steps.BattleStep;
import games.strategy.triplea.delegate.battle.steps.fire.general.FiringGroupSplitterGeneral;
import java.io.Serial;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Flames and Steel naval phase 1: one side's destroyers fire. Hits go to enemy subs while any
 * remain. Units hit here stay in the battle and still fire later in the round.
 */
public class NavalDestroyerFire implements BattleStep {
  @Serial private static final long serialVersionUID = 3071665813275412891L;

  private final BattleState battleState;
  private final BattleActions battleActions;
  private final BattleState.Side side;
  private final boolean applies;

  public NavalDestroyerFire(
      final BattleState battleState,
      final BattleActions battleActions,
      final BattleState.Side side) {
    this.battleState = battleState;
    this.battleActions = battleActions;
    this.side = side;
    this.applies =
        NavalPhases.apply(battleState)
            && battleState.filterUnits(ALIVE, side).stream().anyMatch(Matches.unitIsDestroyer())
            && !battleState.filterUnits(ALIVE, side.getOpposite()).isEmpty();
  }

  @Override
  public List<StepDetails> getAllStepDetails() {
    return applies
        ? getSteps().stream()
            .flatMap(step -> step.getAllStepDetails().stream())
            .collect(Collectors.toList())
        : List.of();
  }

  @Override
  public Order getOrder() {
    return side == OFFENSE ? Order.NAVAL_DESTROYERS_OFFENSIVE : Order.NAVAL_DESTROYERS_DEFENSIVE;
  }

  @Override
  public void execute(final ExecutionStack stack, final IDelegateBridge bridge) {
    if (!applies) {
      return;
    }
    final List<BattleStep> steps = getSteps();
    // steps go in reverse order on the stack
    Collections.reverse(steps);
    steps.forEach(stack::push);
  }

  private List<BattleStep> getSteps() {
    return FireRoundStepsFactory.builder()
        .battleState(battleState)
        .battleActions(battleActions)
        .firingGroupSplitter(
            FiringGroupSplitterGeneral.of(
                side, FiringGroupSplitterGeneral.Type.DESTROYERS, "destroyers"))
        .side(side)
        .returnFire(ReturnFire.ALL)
        .diceRoller(new MainDiceRoller())
        .casualtySelector(new SelectDestroyerCasualties())
        .build()
        .createSteps();
  }
}
