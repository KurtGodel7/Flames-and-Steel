package games.strategy.triplea.delegate.battle.steps.fire;

import static games.strategy.triplea.delegate.battle.BattleState.Side.DEFENSE;
import static games.strategy.triplea.delegate.battle.BattleState.Side.OFFENSE;
import static games.strategy.triplea.delegate.battle.BattleState.UnitBattleFilter.ALIVE;

import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Unit;
import games.strategy.engine.delegate.IDelegateBridge;
import games.strategy.triplea.Properties;
import games.strategy.triplea.attachments.TechAbilityAttachment;
import games.strategy.triplea.delegate.DiceRoll;
import games.strategy.triplea.delegate.ExecutionStack;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.TechAdvance;
import games.strategy.triplea.delegate.TechTracker;
import games.strategy.triplea.delegate.air.AirCombat;
import games.strategy.triplea.delegate.battle.BattleActions;
import games.strategy.triplea.delegate.battle.BattleDelegate;
import games.strategy.triplea.delegate.battle.BattleState;
import games.strategy.triplea.delegate.battle.casualty.CasualtySelector;
import games.strategy.triplea.delegate.battle.steps.BattleStep;
import games.strategy.triplea.delegate.data.CasualtyDetails;
import games.strategy.triplea.delegate.power.calculator.CombatValueBuilder;
import java.io.Serial;
import java.util.ArrayList;
import java.util.List;

/**
 * Flames and Steel Night Combat: before the first round of a sea battle, ships of a nation with a
 * night combat tech (Japan: destroyers on 1, cruisers on 2) fire at enemy surface ships (never
 * transports). Ships they sink are removed at once, without a casualty shot. Low luck.
 */
public class NavalNightCombat implements BattleStep {
  @Serial private static final long serialVersionUID = 6634502176012850941L;

  private final BattleState battleState;
  private final BattleActions battleActions;

  public NavalNightCombat(final BattleState battleState, final BattleActions battleActions) {
    this.battleState = battleState;
    this.battleActions = battleActions;
  }

  @Override
  public List<StepDetails> getAllStepDetails() {
    return applies() ? List.of(new StepDetails("Night combat", this)) : List.of();
  }

  @Override
  public Order getOrder() {
    return Order.NAVAL_NIGHT_COMBAT;
  }

  private boolean applies() {
    return battleState.getBattleSite() != null
        && battleState.getBattleSite().isWater()
        && battleState.getStatus().isFirstRound()
        && (power(OFFENSE) > 0 || power(DEFENSE) > 0);
  }

  @Override
  public void execute(final ExecutionStack stack, final IDelegateBridge bridge) {
    if (!applies()) {
      return;
    }
    for (final BattleState.Side side : List.of(OFFENSE, DEFENSE)) {
      final int power = power(side);
      if (power > 0) {
        fire(bridge, side, power);
      }
    }
  }

  private int power(final BattleState.Side side) {
    return battleState.filterUnits(ALIVE, side).stream().mapToInt(NavalNightCombat::value).sum();
  }

  /** The unit's night combat value from its owner's techs (the best if several). */
  private static int value(final Unit unit) {
    int best = 0;
    for (final TechAdvance tech :
        TechTracker.getCurrentTechAdvances(
            unit.getOwner(), unit.getData().getTechnologyFrontier())) {
      final TechAbilityAttachment ability = TechAbilityAttachment.get(tech);
      if (ability != null) {
        best = Math.max(best, ability.getNightCombat().getInt(unit.getType()));
      }
    }
    return best;
  }

  private void fire(final IDelegateBridge bridge, final BattleState.Side side, final int power) {
    final BattleState.Side targetSide = side.getOpposite();
    final List<Unit> targets =
        new ArrayList<>(
            battleState.filterUnits(ALIVE, targetSide).stream()
                .filter(Matches.unitIsSea())
                .filter(Matches.unitCanEvade().negate())
                .filter(Matches.unitIsSeaTransportButNotCombatSeaTransport().negate())
                .toList());
    if (targets.isEmpty()) {
      return;
    }
    final GamePlayer firer = battleState.getPlayer(side);
    final String text = firer.getName() + " night combat";
    bridge.getHistoryWriter().startEvent(text);
    final AirCombat.Roll roll = AirCombat.lowLuck(bridge, firer, power, text);
    bridge
        .getHistoryWriter()
        .addChildToEvent(text + ": total value " + power + ", " + roll.hits() + " hit(s)");
    if (roll.hits() <= 0) {
      return;
    }
    final var data = battleState.getGameData();
    final CasualtyDetails details =
        CasualtySelector.selectCasualties(
            battleState.getPlayer(targetSide),
            targets,
            CombatValueBuilder.mainCombatValue()
                .enemyUnits(battleState.filterUnits(ALIVE, side))
                .friendlyUnits(battleState.filterUnits(ALIVE, targetSide))
                .side(targetSide)
                .gameSequence(data.getSequence())
                .supportAttachments(data.getUnitTypeList().getSupportRules())
                .lhtrHeavyBombers(Properties.getLhtrHeavyBombers(data.getProperties()))
                .gameDiceSides(data.getDiceSides())
                .territoryEffects(battleState.getTerritoryEffects())
                .build(),
            battleState.getBattleSite(),
            bridge,
            "Hits from " + text + ", ",
            new DiceRoll(roll.dice(), roll.hits(), 0, false, firer.getName()),
            battleState.getBattleId(),
            battleState.getStatus().isHeadless(),
            roll.hits(),
            true);
    final List<Unit> damaged = new ArrayList<>(details.getDamaged());
    damaged.removeAll(details.getKilled());
    BattleDelegate.markDamaged(damaged, bridge, battleState.getBattleSite());
    // sunk at once: no casualty shot
    battleActions.removeUnits(details.getKilled(), bridge, battleState.getBattleSite(), targetSide);
  }
}
