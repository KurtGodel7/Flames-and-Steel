package games.strategy.triplea.delegate.battle.steps.fire;

import games.strategy.engine.data.Unit;
import games.strategy.engine.delegate.IDelegateBridge;
import games.strategy.triplea.delegate.DiceRoll;
import games.strategy.triplea.delegate.battle.AmphibiousLanding;
import games.strategy.triplea.delegate.battle.BattleActions;
import games.strategy.triplea.delegate.battle.BattleState;
import games.strategy.triplea.delegate.battle.MustFightBattle;
import games.strategy.triplea.delegate.battle.steps.BattleStep;
import games.strategy.triplea.delegate.data.CasualtyDetails;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import lombok.Builder;
import org.triplea.java.RemoveOnNextMajorRelease;

/** Build the steps for the fire round (roll dice, select casualties, and mark casualties) */
@Builder
public class FireRoundStepsFactory {

  @Nonnull final BattleState battleState;
  @Nonnull final BattleActions battleActions;
  @Nonnull final Function<BattleState, Collection<FiringGroup>> firingGroupSplitter;
  @Nonnull final BattleState.Side side;

  @RemoveOnNextMajorRelease("This is ReturnFire.ALL or null for everything except old saves")
  final MustFightBattle.ReturnFire returnFire;

  @Nonnull final BiFunction<IDelegateBridge, RollDiceStep, DiceRoll> diceRoller;
  @Nonnull final BiFunction<IDelegateBridge, SelectCasualties, CasualtyDetails> casualtySelector;

  public List<BattleStep> createSteps() {
    return withoutUnitsHoldingFire(firingGroupSplitter.apply(battleState)).stream()
        .sorted(
            Comparator.comparing(FiringGroup::getDisplayName)
                .thenComparing(FiringGroup::isSuicideOnHit))
        .map(
            firingGroup -> {
              final FireRoundState fireRoundState = new FireRoundState();
              return List.of(
                  new RollDiceStep(battleState, side, firingGroup, fireRoundState, diceRoller),
                  new SelectCasualties(
                      battleState, side, firingGroup, fireRoundState, casualtySelector),
                  new MarkCasualties(
                      battleState, battleActions, side, firingGroup, fireRoundState, returnFire));
            })
        .flatMap(Collection::stream)
        .collect(Collectors.toList());
  }

  /**
   * Flames and Steel (request 55): attackers landing from the sea hold fire in round 1, so they are
   * left out of the attacker's firing groups (groups left empty are dropped). The battle-end checks
   * build their own groups, so the units still count as able to hit in later rounds.
   */
  private Collection<FiringGroup> withoutUnitsHoldingFire(final Collection<FiringGroup> groups) {
    if (side != BattleState.Side.OFFENSE) {
      return groups;
    }
    final Collection<Unit> holding = AmphibiousLanding.holdingFire(battleState);
    if (holding.isEmpty()) {
      return groups;
    }
    final List<FiringGroup> result = new ArrayList<>();
    for (final FiringGroup group : groups) {
      if (group.getFiringUnits().stream().noneMatch(holding::contains)) {
        result.add(group);
        continue;
      }
      final List<Unit> firing = new ArrayList<>(group.getFiringUnits());
      firing.removeAll(holding);
      if (!firing.isEmpty()) {
        result.addAll(
            FiringGroup.groupBySuicideOnHit(group.getGroupName(), firing, group.getTargetUnits()));
      }
    }
    return result;
  }
}
