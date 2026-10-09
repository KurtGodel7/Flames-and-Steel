package games.strategy.triplea.delegate.battle.steps.fire;

import static games.strategy.triplea.delegate.battle.BattleState.UnitBattleFilter.ALIVE;

import games.strategy.engine.data.Unit;
import games.strategy.engine.delegate.IDelegateBridge;
import games.strategy.triplea.Properties;
import games.strategy.triplea.delegate.DiceRoll;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.battle.casualty.CasualtySelector;
import games.strategy.triplea.delegate.data.CasualtyDetails;
import games.strategy.triplea.delegate.power.calculator.CombatValueBuilder;
import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.BiFunction;
import org.triplea.java.collections.CollectionUtils;

/**
 * Flames and Steel naval phase 1: destroyer hits go to enemy subs while any remain; only hits left
 * after every sub is sunk go to surface ships.
 */
public class SelectDestroyerCasualties
    implements BiFunction<IDelegateBridge, SelectCasualties, CasualtyDetails>, Serializable {
  @Serial private static final long serialVersionUID = 1489274302910476163L;

  @Override
  public CasualtyDetails apply(final IDelegateBridge bridge, final SelectCasualties step) {
    final Collection<Unit> targets = step.getFiringGroup().getTargetUnits();
    final List<Unit> subs = CollectionUtils.getMatches(targets, Matches.unitCanEvade());
    final int hits = step.getFireRoundState().getDice().getHits();
    if (subs.isEmpty() || subs.size() == targets.size()) {
      return new SelectMainBattleCasualties().apply(bridge, step);
    }
    final int subHitPoints = hitPointsLeft(subs);
    if (hits <= subHitPoints) {
      return select(bridge, step, subs, hits);
    }
    final List<Unit> surface = new ArrayList<>(targets);
    surface.removeAll(subs);
    final CasualtyDetails rest = select(bridge, step, surface, hits - subHitPoints);
    final List<Unit> killed = new ArrayList<>(subs);
    killed.addAll(rest.getKilled());
    return new CasualtyDetails(killed, rest.getDamaged(), rest.getAutoCalculated());
  }

  private static int hitPointsLeft(final Collection<Unit> units) {
    return units.stream().mapToInt(u -> u.getUnitAttachment().getHitPoints() - u.getHits()).sum();
  }

  private static CasualtyDetails select(
      final IDelegateBridge bridge,
      final SelectCasualties step,
      final Collection<Unit> targets,
      final int hits) {
    final DiceRoll dice = step.getFireRoundState().getDice();
    final var battleState = step.getBattleState();
    final var data = battleState.getGameData();
    return CasualtySelector.selectCasualties(
        battleState.getPlayer(step.getSide().getOpposite()),
        targets,
        CombatValueBuilder.mainCombatValue()
            .enemyUnits(battleState.filterUnits(ALIVE, step.getSide()))
            .friendlyUnits(battleState.filterUnits(ALIVE, step.getSide().getOpposite()))
            .side(step.getSide().getOpposite())
            .gameSequence(data.getSequence())
            .supportAttachments(data.getUnitTypeList().getSupportRules())
            .lhtrHeavyBombers(Properties.getLhtrHeavyBombers(data.getProperties()))
            .gameDiceSides(data.getDiceSides())
            .territoryEffects(battleState.getTerritoryEffects())
            .build(),
        battleState.getBattleSite(),
        bridge,
        "Hits from " + step.getFiringGroup().getDisplayName() + ", ",
        new DiceRoll(dice.getRolls(), hits, dice.getExpectedHits(), dice.getPlayerName()),
        battleState.getBattleId(),
        battleState.getStatus().isHeadless(),
        hits,
        true);
  }
}
