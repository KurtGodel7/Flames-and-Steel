package games.strategy.triplea.delegate.battle.steps.change;

import static games.strategy.triplea.delegate.battle.BattleState.Side.DEFENSE;
import static games.strategy.triplea.delegate.battle.BattleState.Side.OFFENSE;
import static games.strategy.triplea.delegate.battle.BattleState.UnitBattleFilter.ALIVE;

import com.google.common.collect.Iterables;
import games.strategy.engine.data.Unit;
import games.strategy.engine.delegate.IDelegateBridge;
import games.strategy.triplea.Properties;
import games.strategy.triplea.attachments.UnitAttachment;
import games.strategy.triplea.delegate.ExecutionStack;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.battle.BattleActions;
import games.strategy.triplea.delegate.battle.BattleState;
import games.strategy.triplea.delegate.battle.IBattle;
import games.strategy.triplea.delegate.battle.steps.BattleStep;
import games.strategy.triplea.delegate.battle.steps.RetreatChecks;
import games.strategy.triplea.delegate.battle.steps.fire.FiringGroup;
import games.strategy.triplea.delegate.battle.steps.fire.general.FiringGroupSplitterGeneral;
import games.strategy.triplea.delegate.power.calculator.CombatValueBuilder;
import games.strategy.triplea.delegate.power.calculator.PowerStrengthAndRolls;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import lombok.AllArgsConstructor;
import org.triplea.java.collections.CollectionUtils;

@AllArgsConstructor
public class CheckGeneralBattleEnd implements BattleStep {
  private static final long serialVersionUID = 5172121497955756220L;

  private final BattleState battleState;

  private final BattleActions battleActions;

  protected BattleActions getBattleActions() {
    return battleActions;
  }

  protected BattleState getBattleState() {
    return battleState;
  }

  @Override
  public List<StepDetails> getAllStepDetails() {
    return List.of();
  }

  @Override
  public Order getOrder() {
    return Order.GENERAL_BATTLE_END_CHECK;
  }

  @Override
  public void execute(final ExecutionStack stack, final IDelegateBridge bridge) {
    // A prior step (e.g. EvaderRetreat after subs submerge) may have already ended the battle.
    // Re-ending it would double-fire defenderWins/attackerWins and corrupt BattleTracker state
    // (see #14119: duplicate entries in defendingAirThatCanNotLand cause an AddUnits crash).
    if (battleState.getStatus().isOver()) {
      return;
    }
    final Optional<String> oneSided;
    if (hasSideLost(OFFENSE)) {
      battleActions.endBattle(IBattle.WhoWon.DEFENDER, bridge);
    } else if (hasSideLost(DEFENSE)) {
      battleActions.endBattle(IBattle.WhoWon.ATTACKER, bridge);
    } else if (oneSidedRule() && (oneSided = oneSidedReason()).isPresent()) {
      endOneSided(oneSided.get(), bridge);
    } else if (isStalemate() && !canAttackerRetreatInStalemate()) {
      battleActions.endBattle(IBattle.WhoWon.DRAW, bridge);
    }
  }

  // ---- Flames and Steel (request 12 section 2): a battle ends when one side can't hit the other

  /**
   * Game property for the rule; it defaults to on in games with "Sticky Hits" (Flames and Steel).
   */
  public static final String ONE_SIDED_END = "FS Battle Ends When One Side Cannot Hit";

  private boolean oneSidedRule() {
    final var properties = battleState.getGameData().getProperties();
    return properties.get(ONE_SIDED_END, Properties.getStickyHits(properties));
  }

  /**
   * Whether any unit of the side may target any enemy unit still in the battle (its strength does
   * not matter: a unit with no attack value still counts, so a defenceless garrison can be taken).
   */
  private boolean canHit(final BattleState.Side side) {
    return !Iterables.isEmpty(getAllFiringGroups(side));
  }

  /** "Battle ends: the British ships can't hit submarines.", or empty if both sides can hit. */
  private Optional<String> oneSidedReason() {
    final boolean attackerCanHit = canHit(OFFENSE);
    final boolean defenderCanHit = canHit(DEFENSE);
    if (attackerCanHit && defenderCanHit) {
      return Optional.empty();
    }
    final BattleState.Side blind = attackerCanHit ? DEFENSE : OFFENSE;
    final Collection<Unit> own =
        CollectionUtils.getMatches(
            battleState.filterUnits(ALIVE, blind), Matches.unitIsNotInfrastructure());
    final Collection<Unit> enemies =
        CollectionUtils.getMatches(
            battleState.filterUnits(ALIVE, blind.getOpposite()), Matches.unitIsNotInfrastructure());
    final String who =
        battleState.getPlayer(blind).getName().endsWith("ns")
            ? battleState
                .getPlayer(blind)
                .getName()
                .substring(0, battleState.getPlayer(blind).getName().length() - 1)
            : battleState.getPlayer(blind).getName();
    final String kind = own.stream().allMatch(Matches.unitIsSea()) ? "ships" : "units";
    final List<String> targets =
        enemies.stream()
            .map(Unit::getType)
            .distinct()
            .map(
                t ->
                    games.strategy.triplea.formatter.MyFormatter.pluralize(
                        games.strategy.triplea.formatter.UnitNames.of(t), 2))
            .sorted()
            .toList();
    return Optional.of(
        "Battle ends: the "
            + who
            + " "
            + kind
            + " can't hit "
            + (targets.isEmpty() ? "the enemy" : String.join(", ", targets))
            + ".");
  }

  /**
   * Ends the battle as a draw. Units that coexist with enemies (submarines, which can evade) stay;
   * any other attackers left beside surviving enemies retreat to a territory they attacked from.
   */
  private void endOneSided(final String reason, final IDelegateBridge bridge) {
    final var attacker = battleState.getPlayer(OFFENSE);
    final Collection<Unit> leaving =
        CollectionUtils.getMatches(
            battleState.filterUnits(ALIVE, OFFENSE),
            Matches.unitCanEvade().negate().and(Matches.unitIsNotInfrastructure()));
    if (!leaving.isEmpty()) {
      final boolean sea = leaving.stream().anyMatch(Matches.unitIsSea());
      final List<games.strategy.engine.data.Territory> sites =
          battleState.getAttackerRetreatTerritories().stream()
              .filter(t -> !sea || t.isWater())
              .toList();
      if (!sites.isEmpty()) {
        final games.strategy.engine.data.Territory to =
            sites.size() == 1
                ? sites.get(0)
                : battleActions
                    .queryRetreatTerritory(
                        battleState, bridge, attacker, sites, reason + " Retreat to:")
                    .orElse(sites.get(0));
        final Collection<Unit> moving = new java.util.ArrayList<>(leaving);
        moving.addAll(battleState.getDependentUnits(leaving));
        battleState.retreatUnits(OFFENSE, moving);
        bridge.addChange(
            games.strategy.engine.data.changefactory.ChangeFactory.moveUnits(
                battleState.getBattleSite(), to, moving));
        bridge
            .getHistoryWriter()
            .addChildToEvent(
                games.strategy.triplea.formatter.MyFormatter.unitsToText(moving)
                    + " retreated to "
                    + to.getName(),
                moving);
        bridge.getDisplayChannelBroadcaster().notifyRetreat(battleState.getBattleId(), moving);
      }
    }
    battleActions.setDrawMessage(reason);
    battleActions.endBattle(IBattle.WhoWon.DRAW, bridge);
  }

  protected boolean hasSideLost(final BattleState.Side side) {
    return battleState.filterUnits(ALIVE, side).stream()
        .noneMatch(Matches.unitIsNotInfrastructure());
  }

  private Predicate<Unit> inAnyFiringGroup(Iterable<FiringGroup> firingGroups) {
    return u ->
        StreamSupport.stream(firingGroups.spliterator(), false)
            .anyMatch(fg -> fg.getFiringUnits().contains(u));
  }

  protected boolean isStalemate() {
    if (battleState.getStatus().isLastRound()) {
      return true;
    }
    final Iterable<FiringGroup> attackerFiringGroups = getAllFiringGroups(OFFENSE);
    // Filter attackers to only units that are in firing groups, to eliminate units
    // that can technically roll dice in abstract, but not against any current enemies.
    final Collection<Unit> attackers =
        CollectionUtils.getMatches(
            battleState.filterUnits(ALIVE, OFFENSE), inAnyFiringGroup(attackerFiringGroups));
    final Iterable<FiringGroup> defendersFiringGroups = getAllFiringGroups(DEFENSE);
    // Filter defenders to only units that are in firing groups, to eliminate units
    // that can technically roll dice in abstract, but not against any current enemies.
    final Collection<Unit> defenders =
        CollectionUtils.getMatches(
            battleState.filterUnits(ALIVE, DEFENSE), inAnyFiringGroup(defendersFiringGroups));
    return battleState.getStatus().isLastRound()
        || (hasNoStrengthOrRolls(OFFENSE, attackers, defenders)
            && hasNoStrengthOrRolls(DEFENSE, defenders, attackers))
        || (hasNoTargets(attackerFiringGroups) && hasNoTargets(defendersFiringGroups));
  }

  private boolean hasNoStrengthOrRolls(
      final BattleState.Side side,
      final Collection<Unit> myUnits,
      final Collection<Unit> enemyUnits) {
    return !PowerStrengthAndRolls.buildWithPreSortedUnits(
            myUnits,
            CombatValueBuilder.mainCombatValue()
                .enemyUnits(enemyUnits)
                .friendlyUnits(myUnits)
                .side(side)
                .gameSequence(battleState.getGameData().getSequence())
                .supportAttachments(battleState.getGameData().getUnitTypeList().getSupportRules())
                .lhtrHeavyBombers(
                    Properties.getLhtrHeavyBombers(battleState.getGameData().getProperties()))
                .gameDiceSides(battleState.getGameData().getDiceSides())
                .territoryEffects(battleState.getTerritoryEffects())
                .build())
        .hasStrengthOrRolls();
  }

  private Iterable<FiringGroup> getAllFiringGroups(final BattleState.Side side) {
    // Flames and Steel naval phases: destroyers fire in their own phase, not the general one
    final Iterable<FiringGroup> destroyers =
        games.strategy.triplea.delegate.battle.steps.fire.NavalPhases.apply(battleState)
            ? getFiringGroup(side, FiringGroupSplitterGeneral.Type.DESTROYERS)
            : List.of();
    return Iterables.concat(
        getFiringGroup(side, FiringGroupSplitterGeneral.Type.NORMAL),
        getFiringGroup(side, FiringGroupSplitterGeneral.Type.FIRST_STRIKE),
        destroyers);
  }

  private List<FiringGroup> getFiringGroup(
      final BattleState.Side side, final FiringGroupSplitterGeneral.Type type) {
    return FiringGroupSplitterGeneral.of(side, type, "stalemate").apply(battleState);
  }

  private boolean hasNoTargets(Iterable<FiringGroup> firingGroups) {
    return Iterables.isEmpty(firingGroups);
  }

  protected boolean canAttackerRetreatInStalemate() {
    // First check if any units have an explicit "can retreat on stalemate"
    // property. If none do (all are null), then we will use a fallback algorithm.
    // If any unit has "can retreat on stalemate" set, then we will return true
    // only if all units either have the property set to null or true, if any
    // are set to false then we will return false.

    // Otherwise, if we do not have an explicit property, then we fallback
    // to enforcing the V3 transport vs transport rule that allows retreat in
    // that situation. Ideally all maps would explicitly use the "can retreat
    // on stalemate property", but not all do so we need to account for the
    // V3 transport vs transport rule as a fallback algorithm without it.

    // First, collect all of the non-null 'can retreat on stalemate' option values.
    final Set<Boolean> canRetreatOptions =
        battleState.filterUnits(ALIVE, OFFENSE).stream()
            .map(Unit::getUnitAttachment)
            .map(UnitAttachment::getCanRetreatOnStalemate)
            .flatMap(Optional::stream)
            .collect(Collectors.toSet());

    final boolean propertyIsSetAtLeastOnce = !canRetreatOptions.isEmpty();

    // next, check if all of the non-null properties are set to true.
    final boolean allowRetreatFromProperty = canRetreatOptions.stream().allMatch(b -> b);

    return (propertyIsSetAtLeastOnce && allowRetreatFromProperty)
        || (!propertyIsSetAtLeastOnce && transportsVsTransports());
  }

  private boolean transportsVsTransports() {
    // Check if both sides have only V3 (non-combat) transports remaining.
    // See: https://github.com/triplea-game/triplea/issues/2367
    // Rule: "In a sea battle, if both sides have only transports remaining, the
    // attacker’s transports can remain in the contested sea zone or retreat.
    return RetreatChecks.onlyDefenselessTransportsLeft(
            battleState.filterUnits(ALIVE, OFFENSE), battleState.getGameData())
        && RetreatChecks.onlyDefenselessTransportsLeft(
            battleState.filterUnits(ALIVE, DEFENSE), battleState.getGameData());
  }
}
