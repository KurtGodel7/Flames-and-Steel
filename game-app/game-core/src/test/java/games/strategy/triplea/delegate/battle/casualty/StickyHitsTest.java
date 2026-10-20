package games.strategy.triplea.delegate.battle.casualty;

import static games.strategy.triplea.delegate.GameDataTestUtil.armour;
import static games.strategy.triplea.delegate.GameDataTestUtil.battleship;
import static games.strategy.triplea.delegate.GameDataTestUtil.british;
import static games.strategy.triplea.delegate.GameDataTestUtil.carrier;
import static games.strategy.triplea.delegate.GameDataTestUtil.infantry;
import static games.strategy.triplea.delegate.GameDataTestUtil.territory;
import static games.strategy.triplea.delegate.MockDelegateBridge.newDelegateBridge;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.engine.delegate.IDelegateBridge;
import games.strategy.triplea.Constants;
import games.strategy.triplea.delegate.DiceRoll;
import games.strategy.triplea.delegate.battle.BattleState;
import games.strategy.triplea.delegate.data.CasualtyDetails;
import games.strategy.triplea.delegate.data.CasualtyList;
import games.strategy.triplea.delegate.power.calculator.CombatValueBuilder;
import games.strategy.triplea.xml.TestMapGameData;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Flames and Steel sticky hits. Battleships are given 3 hit points here. */
class StickyHitsTest {
  private final GameData gameData = TestMapGameData.REVISED.getGameData();
  private final GamePlayer british = british(gameData);

  @BeforeEach
  void setUp() {
    gameData.performChange(
        ChangeFactory.attachmentPropertyChange(
            battleship(gameData).getUnitAttachment(), 3, "hitPoints"));
  }

  @Test
  void twoHitsOnOneUnitIsAllowed() {
    final Unit bb1 = newBattleship(0);
    final Unit bb2 = newBattleship(0);

    assertThat(
        StickyHits.validate(List.of(bb1, bb2), List.of(), List.of(bb1, bb1)).isEmpty(), is(true));
  }

  @Test
  void spreadingHitsOverTwoUndamagedUnitsIsRefused() {
    final Unit bb1 = newBattleship(0);
    final Unit bb2 = newBattleship(0);

    assertThat(
        StickyHits.validate(List.of(bb1, bb2), List.of(), List.of(bb1, bb2)).isPresent(), is(true));
  }

  @Test
  void damagedUnitMustBeHitBeforeUndamagedUnit() {
    final Unit damaged = newBattleship(1);
    final Unit fresh = newBattleship(0);

    assertThat(
        StickyHits.validate(List.of(damaged, fresh), List.of(), List.of(fresh)).isPresent(),
        is(true));
    assertThat(
        StickyHits.validate(List.of(damaged, fresh), List.of(), List.of(damaged)).isEmpty(),
        is(true));
  }

  @Test
  void afterDamagedUnitIsDestroyedTheNextUnitMayBeHit() {
    final Unit damaged = newBattleship(2);
    final Unit fresh = newBattleship(0);

    assertThat(
        StickyHits.validate(List.of(damaged, fresh), List.of(damaged), List.of(fresh)).isEmpty(),
        is(true));
  }

  @Test
  void killingSeveralUnitsAndDamagingOneIsAllowed() {
    final Unit bb1 = newBattleship(0);
    final Unit bb2 = newBattleship(0);
    final Unit bb3 = newBattleship(0);

    assertThat(
        StickyHits.validate(List.of(bb1, bb2, bb3), List.of(bb1, bb2), List.of(bb3)).isEmpty(),
        is(true));
  }

  @Test
  void landAndSeaDamageAreSeparate() {
    final Unit damagedShip = newBattleship(1);
    final Unit infantry = infantry(gameData).create(1, british).get(0);

    assertThat(
        StickyHits.validate(List.of(damagedShip, infantry), List.of(infantry), List.of()).isEmpty(),
        is(true));
  }

  @Test
  void defaultHitsDamagedUnitsFirstThenFillsOneUnitAtATime() {
    final Unit infantry = infantry(gameData).create(1, british).get(0);
    final Unit tank = armour(gameData).create(1, british).get(0);
    final Unit damaged = newBattleship(1);
    final Unit fresh = newBattleship(0);

    // order of loss puts the cheap units first, but the damaged battleship takes hits first
    final CasualtyList casualties =
        StickyHits.getDefaultCasualties(List.of(infantry, tank, fresh, damaged), 5);

    assertThat(casualties.getKilled(), contains(damaged, infantry, tank));
    assertThat(casualties.getDamaged(), contains(damaged, fresh));
    assertThat(casualties.size(), is(5));
  }

  @Test
  void defaultLeavesAtMostOneUnitDamaged() {
    final Unit bb1 = newBattleship(0);
    final Unit bb2 = newBattleship(0);

    final CasualtyList casualties = StickyHits.getDefaultCasualties(List.of(bb1, bb2), 2);

    assertThat(casualties.getKilled(), is(empty()));
    assertThat(casualties.getDamaged(), contains(bb1, bb1));
  }

  @Test
  void spreadOutPickIsAppliedTheStickyWay() {
    // three 3-hit-point battleships, four hits picked as damage spread over all three
    final Unit bb1 = newBattleship(0);
    final Unit bb2 = newBattleship(0);
    final Unit bb3 = newBattleship(0);

    final StickyHits.Applied applied =
        StickyHits.applyInPickedOrder(
            List.of(bb1, bb2, bb3), List.of(), List.of(bb2, bb3, bb2, bb1), 4);

    // the most-picked unit (bb2) dies first, then the next picked (bb3) takes the last hit
    assertThat(applied.killed(), contains(bb2));
    assertThat(applied.damaged(), contains(bb3));
    assertThat(
        StickyHits.validate(List.of(bb1, bb2, bb3), applied.killed(), applied.damaged()).isEmpty(),
        is(true));
  }

  @Test
  void forcedHitsGoToTheMostDamagedUnitFirst() {
    final Unit slightly = newBattleship(1);
    final Unit badly = newBattleship(2);
    final Unit fresh = newBattleship(0);

    final StickyHits.ForcedHits forced =
        StickyHits.getForcedHits(List.of(fresh, slightly, badly), 2);

    // badly damaged (1 left) dies, then the slightly damaged one takes the last hit
    assertThat(forced.killed(), contains(badly));
    assertThat(forced.damaged(), contains(slightly));
    assertThat(forced.hitsUsed(), is(2));
  }

  @Test
  void hitsOnDamagedUnitsAreAppliedWithoutAsking() {
    gameData.getProperties().set(Constants.STICKY_HITS, true);
    final IDelegateBridge bridge = newDelegateBridge(british);
    final Unit damaged = newBattleship(1);
    final Unit fresh = newBattleship(0);
    final Unit infantry = infantry(gameData).create(1, british).get(0);

    final CasualtyDetails result = selectCasualties(bridge, List.of(damaged, fresh, infantry), 2);

    assertThat(result.getKilled(), contains(damaged));
    assertThat(result.getDamaged(), is(empty()));
    verify(bridge.getRemotePlayer(), never())
        .selectCasualties(
            any(),
            any(),
            anyInt(),
            any(),
            any(),
            any(),
            any(),
            any(),
            anyBoolean(),
            any(),
            any(),
            any(),
            any(),
            anyBoolean());
  }

  @Test
  void playerOrdersAllUnitsAndTheDamagedUnitIsHitFirstWhateverComesBack() {
    gameData.getProperties().set(Constants.STICKY_HITS, true);
    final IDelegateBridge bridge = newDelegateBridge(british);
    final Unit damaged = newBattleship(1);
    final Unit fresh1 = newBattleship(0);
    final Unit fresh2 = newBattleship(0);
    final List<Unit> infantry = infantry(gameData).create(2, british);
    final List<Unit> targets = new ArrayList<>(List.of(fresh1, damaged, fresh2));
    targets.addAll(infantry);
    final AtomicInteger asked = new AtomicInteger();
    final List<Collection<Unit>> offeredUnits = new ArrayList<>();
    final List<Integer> offeredHits = new ArrayList<>();
    final List<CasualtyList> offeredDefaults = new ArrayList<>();
    when(bridge
            .getRemotePlayer()
            .selectCasualties(
                any(),
                any(),
                anyInt(),
                any(),
                any(),
                any(),
                any(),
                any(),
                anyBoolean(),
                any(),
                any(),
                any(),
                any(),
                anyBoolean()))
        .thenAnswer(
            invocation -> {
              offeredUnits.add(invocation.getArgument(0));
              offeredHits.add(invocation.getArgument(2));
              offeredDefaults.add(invocation.getArgument(10));
              asked.getAndIncrement();
              // a spread pick: the damaged battleship finished, then one hit on each fresh one
              return new CasualtyDetails(List.of(damaged), List.of(damaged, fresh1, fresh2), false);
            });

    final CasualtyDetails result = selectCasualties(bridge, targets, 4);

    // the dialog shows every unit and every hit; the damaged battleship heads the default
    assertThat(offeredHits.get(0), is(4));
    assertThat(offeredUnits.get(0).contains(damaged), is(true));
    assertThat(offeredDefaults.get(0).getKilled().get(0), is(damaged));
    assertThat(asked.get(), is(1));
    // not refused: the spread is applied the sticky way, one fresh battleship takes both hits
    assertThat(result.getKilled(), contains(damaged));
    assertThat(result.getDamaged(), contains(fresh1, fresh1));
  }

  /** Request 23: 2 cruisers (2 HP) and 4 battleships (5 HP) take 8 hits. */
  @Test
  void eightHitsOnCruisersAndBattleshipsNeverLeaveTwoShipsDamaged() {
    battleshipHitPoints(5);
    final List<Unit> fleet = new ArrayList<>(cruisers(2));
    for (int i = 0; i < 4; i++) {
      fleet.add(newBattleship(0));
    }

    // every order the dialog can give
    for (final List<Unit> order : permutations(fleet)) {
      final List<StickyHits.Allocation> allocations = StickyHits.allocate(order, 8);
      assertThat(allocations.stream().filter(a -> !a.killed()).count() <= 1, is(true));
      assertThat(allocations.stream().mapToInt(StickyHits.Allocation::hitsTaken).sum(), is(8));
      final CasualtyList list = StickyHits.toCasualtyList(allocations);
      assertThat(
          StickyHits.validate(fleet, list.getKilled(), survivorsHit(list)).isEmpty(), is(true));
    }
    // and a spread pick like the one the old dialog offered (one hit on every ship, two more on two
    // battleships) is applied the sticky way: one ship at a time, the most-picked first
    final List<Unit> spread = new ArrayList<>(fleet.subList(0, 2));
    spread.addAll(fleet.subList(2, 6));
    spread.addAll(fleet.subList(2, 4));
    final StickyHits.Applied applied = StickyHits.applyInPickedOrder(fleet, List.of(), spread, 8);
    assertThat(applied.damaged().stream().distinct().count() <= 1, is(true));
    assertThat(StickyHits.validate(fleet, applied.killed(), applied.damaged()).isEmpty(), is(true));
  }

  @Test
  void requestExampleOrderGivesTwoCruisersSunkAndOneBattleshipDamaged() {
    battleshipHitPoints(5);
    final List<Unit> order = new ArrayList<>(cruisers(2));
    final Unit bb = newBattleship(0);
    order.add(bb);
    order.add(newBattleship(0));

    final List<StickyHits.Allocation> allocations = StickyHits.allocate(order, 8);

    assertThat(allocations.size(), is(3));
    assertThat(allocations.get(0).killed(), is(true));
    assertThat(allocations.get(1).killed(), is(true));
    assertThat(allocations.get(2).unit(), is(bb));
    assertThat(allocations.get(2).hitsTaken(), is(4));
    assertThat(allocations.get(2).hitPointsLeftAfter(), is(1));
  }

  @Test
  void unitDamagedEarlierInTheBattleAlwaysTakesTheNextHitFirst() {
    battleshipHitPoints(5);
    final Unit damagedEarlier = newBattleship(2);
    final List<Unit> fleet = new ArrayList<>(cruisers(2));
    fleet.add(newBattleship(0));
    fleet.add(damagedEarlier);

    // the default, any order the player builds, and any pick that comes back
    assertThat(StickyHits.getDefaultCasualties(fleet, 1).getDamaged(), contains(damagedEarlier));
    for (final List<Unit> order : permutations(fleet)) {
      assertThat(
          StickyHits.allocate(StickyHits.damagedFirst(order), 1).get(0).unit(), is(damagedEarlier));
    }
    final StickyHits.Applied applied =
        StickyHits.applyInPickedOrder(fleet, List.of(fleet.get(0)), List.of(fleet.get(0)), 2);
    assertThat(applied.damaged(), contains(damagedEarlier, damagedEarlier));
    assertThat(applied.killed(), is(empty()));
  }

  private void battleshipHitPoints(final int hitPoints) {
    gameData.performChange(
        ChangeFactory.attachmentPropertyChange(
            battleship(gameData).getUnitAttachment(), hitPoints, "hitPoints"));
  }

  /** Two-hit-point ships standing in for cruisers. */
  private List<Unit> cruisers(final int count) {
    gameData.performChange(
        ChangeFactory.attachmentPropertyChange(
            carrier(gameData).getUnitAttachment(), 2, "hitPoints"));
    return carrier(gameData).create(count, british);
  }

  private static List<Unit> survivorsHit(final CasualtyList list) {
    final List<Unit> damaged = new ArrayList<>(list.getDamaged());
    damaged.removeAll(list.getKilled());
    return damaged;
  }

  private static List<List<Unit>> permutations(final List<Unit> units) {
    if (units.isEmpty()) {
      return List.of(List.of());
    }
    final List<List<Unit>> result = new ArrayList<>();
    for (final Unit first : units) {
      final List<Unit> rest = new ArrayList<>(units);
      rest.remove(first);
      for (final List<Unit> tail : permutations(rest)) {
        final List<Unit> order = new ArrayList<>();
        order.add(first);
        order.addAll(tail);
        result.add(order);
      }
    }
    return result;
  }

  private CasualtyDetails selectCasualties(
      final IDelegateBridge bridge, final List<Unit> targets, final int hits) {
    final int[] dice = new int[hits];
    return CasualtySelector.selectCasualties(
        british,
        targets,
        CombatValueBuilder.mainCombatValue()
            .enemyUnits(List.of())
            .friendlyUnits(targets)
            .side(BattleState.Side.DEFENSE)
            .gameSequence(gameData.getSequence())
            .supportAttachments(gameData.getUnitTypeList().getSupportRules())
            .lhtrHeavyBombers(false)
            .gameDiceSides(gameData.getDiceSides())
            .territoryEffects(List.of())
            .build(),
        territory("1 Sea Zone", gameData),
        bridge,
        "",
        new DiceRoll(dice, hits, 1, false, null),
        UUID.randomUUID(),
        false,
        0,
        true);
  }

  private Unit newBattleship(final int hitsTaken) {
    return battleship(gameData).create(1, british, false, hitsTaken, 0).get(0);
  }
}
