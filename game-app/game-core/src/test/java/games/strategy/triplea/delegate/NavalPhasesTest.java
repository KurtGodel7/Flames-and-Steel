package games.strategy.triplea.delegate;

import static games.strategy.triplea.delegate.GameDataTestUtil.addTo;
import static games.strategy.triplea.delegate.GameDataTestUtil.battleship;
import static games.strategy.triplea.delegate.GameDataTestUtil.british;
import static games.strategy.triplea.delegate.GameDataTestUtil.destroyer;
import static games.strategy.triplea.delegate.GameDataTestUtil.germans;
import static games.strategy.triplea.delegate.GameDataTestUtil.move;
import static games.strategy.triplea.delegate.GameDataTestUtil.moveDelegate;
import static games.strategy.triplea.delegate.GameDataTestUtil.removeFrom;
import static games.strategy.triplea.delegate.GameDataTestUtil.submarine;
import static games.strategy.triplea.delegate.GameDataTestUtil.territory;
import static games.strategy.triplea.delegate.GameDataTestUtil.transport;
import static games.strategy.triplea.delegate.MockDelegateBridge.advanceToStep;
import static games.strategy.triplea.delegate.MockDelegateBridge.newDelegateBridge;
import static games.strategy.triplea.delegate.MockDelegateBridge.whenGetRandom;
import static games.strategy.triplea.delegate.MockDelegateBridge.withValues;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Route;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.delegate.IDelegateBridge;
import games.strategy.triplea.Constants;
import games.strategy.triplea.delegate.battle.MustFightBattle;
import games.strategy.triplea.delegate.data.CasualtyDetails;
import games.strategy.triplea.settings.AbstractClientSettingTestCase;
import games.strategy.triplea.xml.TestMapGameData;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Flames and Steel naval combat phases on the WW2v3 test map: destroyers fire first (hits to subs
 * first), then subs fire a surprise shot (victims removed at once), then the rest of the fleet
 * fires (destroyer-phase victims still fire). Dice are 0-based: 0 hits, 5 misses.
 */
class NavalPhasesTest extends AbstractClientSettingTestCase {
  private final GameData gameData = TestMapGameData.WW2V3_1941.getGameData();
  private final GamePlayer british = british(gameData);
  private final GamePlayer germans = germans(gameData);
  private final Territory attacked = territory("31 Sea Zone", gameData);
  private final Territory from = territory("32 Sea Zone", gameData);

  @BeforeEach
  void setUp() {
    gameData.getProperties().set(Constants.NAVAL_COMBAT_PHASES, true);
    removeFrom(attacked, attacked.getUnits());
    removeFrom(from, from.getUnits());
  }

  @Test
  void destroyerHitGoesToTheSubAndTheSubStillFiresItsSurpriseShot() {
    addTo(from, destroyer(gameData).create(1, british));
    addTo(attacked, submarine(gameData).create(1, germans));
    addTo(attacked, transport(gameData).create(1, germans));
    final IDelegateBridge bridge = attack();
    // the German player would rather lose the transport, but the hit must go to the sub
    givenCasualtiesChosenFrom(bridge, true);
    // 1. British destroyer hits; 2. the sunk German sub still fires and hits
    whenGetRandom(bridge).thenAnswer(withValues(0)).thenAnswer(withValues(0));

    battle().fight(bridge);

    assertThat(count(attacked, germans, Matches.unitCanEvade()), is(0));
    assertThat(count(attacked, germans, Matches.unitIsSeaTransport()), is(1));
    assertThat(count(attacked, british, u -> true), is(0));
  }

  @Test
  void shipsHitByDestroyersStillFireAfterTheSubPhase() {
    addTo(from, destroyer(gameData).create(2, british));
    addTo(from, battleship(gameData).create(1, british));
    addTo(attacked, submarine(gameData).create(1, germans));
    addTo(attacked, gameData.getUnitTypeList().getUnitTypeOrThrow("cruiser").create(1, germans));
    final IDelegateBridge bridge = attack();
    givenCasualtiesChosenFrom(bridge, false);
    whenGetRandom(bridge)
        // 1. both British destroyers hit: the sub, then the cruiser
        .thenAnswer(withValues(0, 0))
        // 2. the German sub's surprise shot misses
        .thenAnswer(withValues(5))
        // 3. the hit German cruiser still fires, and hits
        .thenAnswer(withValues(0));

    battle().fight(bridge);

    assertThat(count(attacked, germans, u -> true), is(0));
    // the cruiser's shot cost the British one hit point (a destroyer or a battleship hit point)
    final int britishHitPoints =
        attacked.getUnitCollection().getMatches(Matches.unitIsOwnedBy(british)).stream()
            .mapToInt(u -> u.getUnitAttachment().getHitPoints() - u.getHits())
            .sum();
    final int startHitPoints =
        2 * destroyer(gameData).getUnitAttachment().getHitPoints()
            + battleship(gameData).getUnitAttachment().getHitPoints();
    assertThat(britishHitPoints, is(startHitPoints - 1));
  }

  private IDelegateBridge attack() {
    final IDelegateBridge bridge = newDelegateBridge(british);
    advanceToStep(bridge, "CombatMove");
    moveDelegate(gameData).setDelegateBridgeAndPlayer(bridge);
    moveDelegate(gameData).start();
    move(from.getUnits(), new Route(from, attacked));
    moveDelegate(gameData).end();
    return bridge;
  }

  private MustFightBattle battle() {
    return (MustFightBattle)
        AbstractMoveDelegate.getBattleTracker(gameData).getPendingNonBombingBattle(attacked);
  }

  /** The chooser picks the first unit offered (or, if preferring transports, a transport). */
  private static void givenCasualtiesChosenFrom(
      final IDelegateBridge bridge, final boolean preferTransports) {
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
              final Collection<Unit> selectFrom = invocation.getArgument(0);
              final Unit pick =
                  preferTransports
                      ? selectFrom.stream()
                          .filter(Matches.unitIsSeaTransport())
                          .findFirst()
                          .orElse(selectFrom.iterator().next())
                      : selectFrom.iterator().next();
              return new CasualtyDetails(List.of(pick), List.of(), false);
            });
  }

  private static int count(
      final Territory t, final GamePlayer owner, final java.util.function.Predicate<Unit> match) {
    return t.getUnitCollection().countMatches(Matches.unitIsOwnedBy(owner).and(match));
  }
}
