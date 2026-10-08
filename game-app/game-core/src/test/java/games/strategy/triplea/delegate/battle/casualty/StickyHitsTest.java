package games.strategy.triplea.delegate.battle.casualty;

import static games.strategy.triplea.delegate.GameDataTestUtil.armour;
import static games.strategy.triplea.delegate.GameDataTestUtil.battleship;
import static games.strategy.triplea.delegate.GameDataTestUtil.british;
import static games.strategy.triplea.delegate.GameDataTestUtil.infantry;
import static games.strategy.triplea.delegate.GameDataTestUtil.territory;
import static games.strategy.triplea.delegate.MockDelegateBridge.newDelegateBridge;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.startsWith;
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
  void playerChoosesOnlyTheLeftoverHitsAndMayNotSpreadThem() {
    gameData.getProperties().set(Constants.STICKY_HITS, true);
    final IDelegateBridge bridge = newDelegateBridge(british);
    final Unit damaged = newBattleship(1);
    final Unit fresh1 = newBattleship(0);
    final Unit fresh2 = newBattleship(0);
    final List<Unit> infantry = infantry(gameData).create(2, british);
    final List<Unit> targets = new ArrayList<>(List.of(damaged, fresh1, fresh2));
    targets.addAll(infantry);
    final AtomicInteger asked = new AtomicInteger();
    final List<Collection<Unit>> offeredUnits = new ArrayList<>();
    final List<Integer> offeredHits = new ArrayList<>();
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
              // first answer spreads the hits over two battleships; the second takes the default
              return asked.getAndIncrement() == 0
                  ? new CasualtyDetails(List.of(), List.of(fresh1, fresh2), false)
                  : new CasualtyDetails(invocation.<CasualtyList>getArgument(10), false);
            });

    final CasualtyDetails result = selectCasualties(bridge, targets, 4);

    // 2 of the 4 hits finish the damaged battleship automatically; 2 are left to choose
    assertThat(offeredHits.get(0), is(2));
    assertThat(offeredUnits.get(0).contains(damaged), is(false));
    assertThat(asked.get(), is(2));
    verify(bridge.getRemotePlayer()).reportError(startsWith("Sticky hits"));
    assertThat(result.getKilled(), hasItem(damaged));
    assertThat(result.getKilled().size() + result.getDamaged().size(), is(3));
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
