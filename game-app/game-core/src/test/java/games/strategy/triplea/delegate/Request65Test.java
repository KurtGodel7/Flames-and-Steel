package games.strategy.triplea.delegate;

import static games.strategy.triplea.delegate.MockDelegateBridge.newDelegateBridge;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.MoveDescription;
import games.strategy.engine.data.Route;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.engine.delegate.IDelegateBridge;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Request 65: Vichy France, a neutral-like player that only collects income and pays half of it to
 * Germany (Indochina: to Japan). Runs on the installed map with Vichy added in memory.
 */
class Request65Test {
  private GameData data;
  private GamePlayer vichy;

  @BeforeEach
  void setUp() {
    data = VichyMap.load();
    vichy = player("Vichy");
    final InitializationDelegate start = new InitializationDelegate();
    start.initialize("initDelegate", "Initializing Delegates");
    start.setDelegateBridgeAndPlayer(newDelegateBridge(player("Germans")));
    start.start();
    start.end();
  }

  private GamePlayer player(final String name) {
    return data.getPlayerList().getPlayerId(name);
  }

  private Territory territory(final String name) {
    return data.getMap().getTerritoryOrThrow(name);
  }

  private UnitType type(final String name) {
    return data.getUnitTypeList().getUnitType(name).orElseThrow();
  }

  private int pus(final String nation) {
    return player(nation).getResources().getQuantity("PUs");
  }

  private void goTo(final String step, final int round) {
    final var sequence = data.getSequence();
    for (int i = 0; i < 10_000; i++) {
      if (sequence.getRound() == round && sequence.getStep().getName().equals(step)) {
        return;
      }
      sequence.next();
    }
    throw new IllegalStateException("no step " + step + " in round " + round);
  }

  private IDelegateBridge bridge(final GamePlayer p) {
    final var bridge = newDelegateBridge(p);
    org.mockito.Mockito.when(
            bridge.getRandom(
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString()))
        .thenAnswer(i -> new int[i.<Integer>getArgument(1)]);
    return bridge;
  }

  /** Runs Vichy's end turn of round 1; returns what Germany and Japan gained. */
  private int[] vichyTurn() {
    goTo("vichyEndTurn", 1);
    final int germans = pus("Germans");
    final int japanese = pus("Japanese");
    final var endTurn = (EndTurnDelegate) data.getDelegate("endTurn");
    endTurn.setDelegateBridgeAndPlayer(bridge(vichy));
    endTurn.start();
    endTurn.end();
    return new int[] {pus("Germans") - germans, pus("Japanese") - japanese};
  }

  private void give(final String territory, final String nation) {
    data.performChange(ChangeFactory.changeOwner(territory(territory), player(nation)));
  }

  @Test
  void vichyStartsWith28PusOfLand() {
    final int income =
        AbstractEndTurnDelegate.getProduction(data.getMap().getTerritoriesOwnedBy(vichy), data);
    assertThat(income, is(28));
    assertThat(NeutralLikePlayers.isListed(vichy), is(true));
    assertThat(TurnlessPlayers.isTurnless(vichy), is(true));
  }

  @Test
  void roundOneGermanyGets10JapanGets3VichyKeepsNothing() {
    final int[] gained = vichyTurn();
    assertThat(gained[0], is(10));
    assertThat(gained[1], is(3));
    assertThat(pus("Vichy"), is(0));
    assertThat(
        AbstractEndTurnDelegate.findEstimatedIncome(vichy, data)
            .getInt(data.getResourceList().getResourceOrThrow("PUs")),
        is(0));
  }

  @Test
  void britainTakesDamascusGermanyGets9() {
    give("Damascus", "British");
    assertThat(vichyTurn()[0], is(9));
  }

  @Test
  void japanTakesTonkinJapanGets2() {
    give("Tonkin", "Japanese");
    assertThat(vichyTurn()[1], is(2));
  }

  @Test
  void parisFallingChangesNothing() {
    give("Paris", "Russians");
    final int[] gained = vichyTurn();
    assertThat(gained[0], is(10));
    assertThat(gained[1], is(3));
  }

  @Test
  void vichyLandIsNeutralLand() {
    assertThat(Matches.territoryIsNeutralButNotWater().test(territory("Morocco")), is(true));
    assertThat(
        Matches.territoryIsBlitzable(player("British")).test(territory("Lebanon")), is(false));
    assertThat(
        Matches.airCanFlyOver(player("British"), false).test(territory("Damascus")), is(false));
    for (final String nation : VichyMap.NATIONS) {
      assertThat(nation, data.getRelationshipTracker().isAtWar(player(nation), vichy), is(true));
    }
    // war with Vichy is not war: the US stays at peace in round 1
    assertThat(Peacetime.isAtPeace(player("Americans")), is(true));
  }

  private Optional<String> move(
      final String nation, final String step, final List<Unit> units, final Route route) {
    goTo(step, 1);
    final var move = (MoveDelegate) data.getDelegate("move");
    move.setDelegateBridgeAndPlayer(bridge(player(nation)));
    move.start();
    final var result = move.performMove(new MoveDescription(units, route));
    move.end();
    return result;
  }

  @Test
  void britainMayAttackDamascusLikeANeutral() {
    final var infantry = type("infantry").create(2, player("British"));
    data.performChange(ChangeFactory.addUnits(territory("Palestine"), infantry));
    final var result =
        move(
            "British",
            "britishCombatMove",
            infantry,
            new Route(territory("Palestine"), territory("Damascus")));
    assertThat(result.orElse(""), result.isEmpty(), is(true));
  }

  @Test
  void tanksCannotBlitzThroughEmptyVichyLand() {
    data.performChange(
        ChangeFactory.removeUnits(
            territory("Lebanon"), territory("Lebanon").getUnitCollection().getUnits()));
    final var tanks = type("tank").create(1, player("British"));
    data.performChange(ChangeFactory.addUnits(territory("Palestine"), tanks));
    final var result =
        move(
            "British",
            "britishCombatMove",
            tanks,
            new Route(territory("Palestine"), territory("Lebanon"), territory("Aleppo")));
    assertThat(
        result.orElse(""), is("Must stop land units when passing through neutral territories"));
  }

  @Test
  void conquerorsKeepVichyLand() {
    final var tracker = data.getBattleDelegate().getBattleTracker();
    final var morocco = territory("Morocco");
    tracker.takeOver(morocco, player("Germans"), bridge(player("Germans")), null, null);
    assertThat(morocco.getOwner().getName(), is("Germans"));
    tracker.takeOver(morocco, player("British"), bridge(player("British")), null, null);
    assertThat(morocco.getOwner().getName(), is("British"));
  }
}
