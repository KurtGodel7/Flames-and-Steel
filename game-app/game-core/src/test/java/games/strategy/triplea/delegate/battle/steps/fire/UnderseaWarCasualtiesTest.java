package games.strategy.triplea.delegate.battle.steps.fire;

import static games.strategy.triplea.delegate.MockDelegateBridge.newDelegateBridge;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.engine.data.gameparser.GameParser;
import games.strategy.engine.delegate.IDelegateBridge;
import games.strategy.triplea.delegate.DiceRoll;
import games.strategy.triplea.delegate.TechTracker;
import games.strategy.triplea.delegate.battle.BattleState;
import games.strategy.triplea.delegate.battle.FakeBattleState;
import games.strategy.triplea.delegate.battle.UnderseaWar;
import games.strategy.triplea.delegate.data.CasualtyDetails;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Flames and Steel Undersea War on the installed map (draft 46 or later): hits from subs with the
 * tech go to surface warships first and carry over to enemy subs. Skipped without the map.
 */
class UnderseaWarCasualtiesTest {
  private static final Path XML =
      Path.of(
          System.getProperty("user.home"),
          "triplea/downloadedMaps/flames_and_steel/map/games/flames_and_steel.xml");
  private GameData data;
  private GamePlayer germans;
  private GamePlayer british;
  private final IDelegateBridge bridge = mock(IDelegateBridge.class);
  private final SelectMainBattleCasualties.Select select =
      mock(SelectMainBattleCasualties.Select.class);

  @BeforeEach
  void setUp() {
    Assumptions.assumeTrue(Files.exists(XML), "Flames and Steel map not installed");
    data = GameParser.parse(XML, false).orElseThrow();
    Assumptions.assumeTrue(
        data.getTechnologyFrontier().getAdvanceByName(UnderseaWar.TECH_NAME) != null,
        "map has no Undersea War");
    germans = data.getPlayerList().getPlayerId("Germans");
    british = data.getPlayerList().getPlayerId("British");
    TechTracker.addAdvance(
        germans,
        newDelegateBridge(germans),
        data.getTechnologyFrontier().getAdvanceByName(UnderseaWar.TECH_NAME));
    data.getTechTracker().clearCache();
  }

  private UnitType type(final String name) {
    return data.getUnitTypeList().getUnitType(name).orElseThrow();
  }

  private CasualtyDetails fire(final int hits, final List<Unit> targets) {
    final List<Unit> subs = type("submarine").create(2, germans);
    final BattleState state =
        FakeBattleState.builder()
            .battleRound(1)
            .maxBattleRounds(-1)
            .battleId(UUID.randomUUID())
            .battleSite(data.getMap().getTerritoryOrThrow("Sea Zone 86 Baltic Sea - South"))
            .territoryEffects(List.of())
            .attacker(germans)
            .defender(british)
            .attackingUnits(subs)
            .attackingWaitingToDie(List.of())
            .defendingUnits(targets)
            .defendingWaitingToDie(List.of())
            .killed(List.of())
            .retreatUnits(List.of())
            .gameData(data)
            .attackerRetreatTerritories(List.of())
            .dependentUnits(List.of())
            .bombardingUnits(List.of())
            .build();
    final FiringGroup group = FiringGroup.groupBySuicideOnHit("subs", subs, targets).get(0);
    final DiceRoll dice = mock(DiceRoll.class);
    when(dice.getHits()).thenReturn(hits);
    final FireRoundState round = new FireRoundState();
    round.setDice(dice);
    final SelectCasualties step =
        new SelectCasualties(
            state, BattleState.Side.OFFENSE, group, round, (b, s) -> new CasualtyDetails());
    return new SelectMainBattleCasualties(select).apply(bridge, step);
  }

  @Test
  void fewHitsGoOnlyOnSurfaceShips() {
    final Unit cruiser = type("cruiser").create(1, british).get(0); // 2 hit points
    final Unit sub = type("submarine").create(1, british).get(0);
    when(select.apply(any(), any(), anyCollection(), anyInt()))
        .thenReturn(new CasualtyDetails(List.of(), List.of(cruiser), false));

    fire(1, List.of(cruiser, sub));

    verify(select).apply(any(), any(), eq(List.of(cruiser)), eq(1));
  }

  @Test
  void hitsThatExactlySinkTheSurfaceShipsLeaveTheSubs() {
    final Unit cruiser = type("cruiser").create(1, british).get(0);
    final Unit sub = type("submarine").create(1, british).get(0);

    final CasualtyDetails details = fire(2, List.of(cruiser, sub));

    assertThat(details.getKilled(), is(List.of(cruiser)));
    verify(select, never()).apply(any(), any(), anyCollection(), anyInt());
  }

  @Test
  void extraHitsCarryOverToTheSubs() {
    final Unit cruiser = type("cruiser").create(1, british).get(0);
    final List<Unit> subs = type("submarine").create(2, british);
    final List<Unit> targets = new ArrayList<>(subs);
    targets.add(cruiser);
    when(select.apply(any(), any(), anyCollection(), anyInt()))
        .thenReturn(new CasualtyDetails(List.of(subs.get(1)), List.of(), false));

    final CasualtyDetails details = fire(3, targets);

    // the cruiser takes 2 hits and sinks; the owner picks which sub takes the third
    verify(select).apply(any(), any(), eq(subs), eq(1));
    assertThat(details.getKilled(), containsInAnyOrder(cruiser, subs.get(1)));
    assertThat(details.getDamaged(), is(empty()));
  }

  @Test
  void enoughHitsSinkEverything() {
    final Unit cruiser = type("cruiser").create(1, british).get(0);
    final List<Unit> subs = type("submarine").create(2, british);
    final List<Unit> targets = new ArrayList<>(subs);
    targets.add(cruiser);

    final CasualtyDetails details = fire(4, targets);

    assertThat(details.getKilled(), containsInAnyOrder(cruiser, subs.get(0), subs.get(1)));
    verify(select, never()).apply(any(), any(), anyCollection(), anyInt());
  }
}
