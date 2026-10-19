package games.strategy.triplea.delegate;

import static games.strategy.triplea.delegate.MockDelegateBridge.newDelegateBridge;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameStep;
import games.strategy.engine.data.gameparser.GameParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Flames and Steel requests 11 and 12 on the installed map (draft 60 or later): the Dutch, a player
 * that never takes a turn. Skipped when the map is not installed.
 */
class Draft60Test {
  private static final Path XML =
      Path.of(
          System.getProperty("user.home"),
          "triplea/downloadedMaps/flames_and_steel/map/games/flames_and_steel.xml");
  private GameData data;
  private GamePlayer dutch;

  @BeforeEach
  void setUp() {
    Assumptions.assumeTrue(Files.exists(XML), "Flames and Steel map not installed");
    data = GameParser.parse(XML, false).orElseThrow();
    dutch = data.getPlayerList().getPlayerId("Dutch");
    Assumptions.assumeTrue(dutch != null, "map has no Dutch");
    final InitializationDelegate start = new InitializationDelegate();
    start.initialize("initDelegate", "Initializing Delegates");
    start.setDelegateBridgeAndPlayer(
        newDelegateBridge(data.getPlayerList().getPlayerId("Germans")));
    start.start();
    start.end();
  }

  private GamePlayer player(final String name) {
    return data.getPlayerList().getPlayerId(name);
  }

  private games.strategy.engine.data.Territory territory(final String name) {
    return data.getMap().getTerritoryOrThrow(name);
  }

  private games.strategy.engine.data.UnitType type(final String name) {
    return data.getUnitTypeList().getUnitType(name).orElseThrow();
  }

  /** Moves the game sequence forward to the named step in the given round. */
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

  private games.strategy.engine.delegate.IDelegateBridge bridge(final GamePlayer p) {
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

  /** The US end turn of the given round: income in start, the "after" triggers in end. */
  private int[] usEndTurn(final int round) {
    final GamePlayer us = player("Americans");
    goTo("americanEndTurn", round);
    final var pus = data.getResourceList().getResourceOrThrow("PUs");
    final var mp = data.getResourceList().getResourceOrThrow("Manpower");
    final int pusBefore = us.getResources().getQuantity(pus);
    final int mpBefore = us.getResources().getQuantity(mp);
    final var endTurn = (EndTurnDelegate) data.getDelegate("endTurn");
    endTurn.setDelegateBridgeAndPlayer(bridge(us));
    endTurn.start();
    final int[] gained = {
      us.getResources().getQuantity(pus) - pusBefore, us.getResources().getQuantity(mp) - mpBefore
    };
    endTurn.end();
    return gained;
  }

  private boolean atWar(final String a, final String b) {
    return data.getRelationshipTracker().isAtWar(player(a), player(b));
  }

  private boolean allied(final String a, final String b) {
    return data.getRelationshipTracker().isAllied(player(a), player(b));
  }

  @Test
  void nobodyMayAttackTheDutchInRoundsOneAndTwo() {
    for (final String nation :
        List.of("Germans", "Russians", "Japanese", "British", "Italians", "Chinese", "Americans")) {
      assertThat(nation, atWar(nation, "Dutch"), is(false));
    }
    // a Japanese landing on Batavia in round 1 is refused
    final GamePlayer japan = player("Japanese");
    final var sea = territory("Sea Zone 148 Java Sea");
    final var transport = type("transport").create(1, japan);
    final var infantry = type("infantry").create(1, japan);
    data.performChange(
        games.strategy.engine.data.changefactory.ChangeFactory.addUnits(sea, transport));
    data.performChange(
        games.strategy.engine.data.changefactory.ChangeFactory.addUnits(sea, infantry));
    data.performChange(TransportTracker.loadTransportChange(transport.get(0), infantry.get(0)));
    goTo("japaneseCombatMove", 1);
    final var move = (MoveDelegate) data.getDelegate("move");
    move.setDelegateBridgeAndPlayer(bridge(japan));
    move.start();
    final var error =
        move.performMove(
            new games.strategy.engine.data.MoveDescription(
                infantry, new games.strategy.engine.data.Route(sea, territory("Batavia"))));
    assertThat(error.isPresent(), is(true));
    move.end();
  }

  @Test
  void usGetsPeacetimeIncomeInRoundTwoThenWarStartsAndFullIncomeFromRoundThree() {
    final int[] round2 = usEndTurn(2);
    assertThat(round2[0], is(36));
    assertThat(round2[1], is(4));
    // the war trigger fired after the US end turn of round 2
    assertThat(atWar("Japanese", "Americans"), is(true));
    assertThat(atWar("Japanese", "British"), is(true));
    assertThat(atWar("Japanese", "Dutch"), is(true));
    assertThat(atWar("Americans", "Germans"), is(true));
    assertThat(allied("British", "Dutch"), is(true));
    assertThat(allied("Americans", "Dutch"), is(true));
    final int[] round3 = usEndTurn(3);
    assertThat("full income from round 3: " + round3[0], round3[0] > 36, is(true));
  }

  @Test
  void japanCanStrikeHawaiiInTheRoundThreeAirPhase() {
    usEndTurn(2); // the war starts at the very start of round 3
    final GamePlayer japan = player("Japanese");
    final var hawaii = territory("Air - Hawaiian Waters");
    // Japanese carriers in Hawaiian waters, dive bombers in the front
    final var seaZone =
        games.strategy.triplea.attachments.AirZoneAttachment.get(hawaii)
            .orElseThrow()
            .getCoveredTerritories()
            .stream()
            .filter(t -> t.getName().contains("Hawaiian Islands"))
            .findFirst()
            .orElseThrow();
    data.performChange(
        games.strategy.engine.data.changefactory.ChangeFactory.addUnits(
            seaZone, type("carrier").create(2, japan)));
    data.performChange(
        games.strategy.engine.data.changefactory.ChangeFactory.addUnits(
            hawaii, type("diveBomber").create(2, japan)));
    goTo("japaneseAirTargets", 3);
    final var targets =
        games.strategy.triplea.delegate.air.AirCombat.getTargets(japan, hawaii, type("diveBomber"));
    assertThat("targets " + targets, targets.isEmpty(), is(false));
    assertThat(
        targets.stream()
            .anyMatch(t -> t.territory().getName().equals("Oahu") || t.territory().isWater()),
        is(true));
    assertThat(
        games.strategy.triplea.delegate.air.AirAllocation.entryProblem(
                japan, type("diveBomber"), hawaii)
            .isEmpty(),
        is(true));
  }

  /** An infantry on a loaded transport in the Java Sea lands on Batavia in the given step. */
  private java.util.Optional<String> landOnBatavia(final String nation, final String step) {
    final GamePlayer p = player(nation);
    final var sea = territory("Sea Zone 148 Java Sea");
    data.performChange(
        games.strategy.engine.data.changefactory.ChangeFactory.removeUnits(
            sea, sea.getUnitCollection().getMatches(u -> !u.isOwnedBy(p))));
    final var transport = type("transport").create(1, p);
    final var infantry = type("infantry").create(1, p);
    data.performChange(
        games.strategy.engine.data.changefactory.ChangeFactory.addUnits(sea, transport));
    data.performChange(
        games.strategy.engine.data.changefactory.ChangeFactory.addUnits(sea, infantry));
    data.performChange(TransportTracker.loadTransportChange(transport.get(0), infantry.get(0)));
    goTo(step, 3);
    final var move = (MoveDelegate) data.getDelegate("move");
    move.setDelegateBridgeAndPlayer(bridge(p));
    move.start();
    final var result =
        move.performMove(
            new games.strategy.engine.data.MoveDescription(
                infantry, new games.strategy.engine.data.Route(sea, territory("Batavia"))));
    move.end();
    return result;
  }

  @Test
  void japanMayInvadeTheDutchInRoundThree() {
    usEndTurn(2);
    final var result = landOnBatavia("Japanese", "japaneseCombatMove");
    assertThat(result.orElse(""), result.isEmpty(), is(true));
  }

  @Test
  void aBritishUnitMayEnterDutchLandInRoundThree() {
    usEndTurn(2);
    final var result = landOnBatavia("British", "britishNonCombatMove");
    assertThat(result.orElse(""), result.isEmpty(), is(true));
    assertThat(territory("Batavia").getOwner().getName(), is("Dutch"));
  }

  @Test
  void liberatedBataviaGoesBackToTheDutch() {
    usEndTurn(2);
    final var batavia = territory("Batavia");
    final var tracker = data.getBattleDelegate().getBattleTracker();
    tracker.takeOver(batavia, player("Japanese"), bridge(player("Japanese")), null, null);
    assertThat(batavia.getOwner().getName(), is("Japanese"));
    tracker.takeOver(batavia, player("British"), bridge(player("British")), null, null);
    assertThat(batavia.getOwner().getName(), is("Dutch"));
  }

  // --- request 11 item 1: battle damage in words

  @Test
  void battleDamageIsDescribedForEachHitAndEachSide() {
    final GamePlayer british = player("British");
    final var battleships = type("battleship").create(2, british);
    battleships.get(0).setHits(2);
    final var cruiser = type("cruiser").create(1, player("Italians"));
    final var lines =
        games.strategy.triplea.ui.BattleDamageText.hitLines(
            cruiser, List.of(battleships.get(0), battleships.get(0)));
    assertThat(
        lines, is(List.of("British battleship took 2 hits: 3 of 5 left", "Italian cruiser sunk")));
    final String summary =
        games.strategy.triplea.ui.BattleDamageText.sideSummary(british, battleships, 8, 6);
    assertThat(summary, is("British: 2 battleships 3/5, 5/5; 8 HP left; about 1.3 hits a round"));
  }

  // --- request 11 item 3: SPC warnings

  @Test
  void anSpcWithoutAdvancedResearchWarnsThatItBuildsNothing() {
    final GamePlayer germans = player("Germans");
    final var techs = games.strategy.triplea.delegate.apc.Apcs.unlockingTechs(data);
    assertThat("techs " + techs, techs.isEmpty(), is(false));
    final var warning =
        games.strategy.triplea.delegate.apc.Apcs.buildsNothingWarning(germans).orElseThrow();
    assertThat(warning, warning.startsWith("Builds nothing yet: needs one of "), is(true));
    assertThat(warning, warning.contains(techs.get(0)), is(true));
    final var essen =
        data.getMap().getTerritories().stream()
            .filter(t -> t.isOwnedBy(germans) && !t.isWater())
            .findFirst()
            .orElseThrow();
    data.performChange(
        games.strategy.engine.data.changefactory.ChangeFactory.addUnits(
            essen, type("apc").create(1, germans)));
    assertThat(
        games.strategy.triplea.delegate.apc.Apcs.idleLines(germans, data.getMap()),
        is(List.of(essen.getName() + " SPC: idle, no Advanced tech yet")));
  }

  @Test
  void mapLoadsAndStartsWithTheDutch() {
    final InitializationDelegate init = new InitializationDelegate();
    init.initialize("initDelegate", "Initializing Delegates");
    init.setDelegateBridgeAndPlayer(newDelegateBridge(data.getPlayerList().getPlayerId("Germans")));
    init.start();
    init.end();
    int dutchSteps = 0;
    for (final GameStep step : data.getSequence()) {
      if (dutch.equals(step.getPlayerId())) {
        dutchSteps++;
      }
    }
    assertThat(dutchSteps, is(0));
    assertThat(
        data.getMap().getTerritories().stream().filter(t -> t.isOwnedBy(dutch)).count(), is(15L));
  }
}
