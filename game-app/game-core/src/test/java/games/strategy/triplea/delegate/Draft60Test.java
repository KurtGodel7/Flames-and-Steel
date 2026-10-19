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

  // --- request 12 section 2: battles end when one side can't hit the other

  /** Two empty sea zones next to each other. */
  private games.strategy.engine.data.Territory[] emptySeaPair() {
    for (final var a : data.getMap().getTerritories()) {
      if (!a.isWater() || !a.getUnits().isEmpty() || a.getName().startsWith("Air")) {
        continue;
      }
      for (final var b : data.getMap().getNeighbors(a)) {
        if (b.isWater() && b.getUnits().isEmpty() && !b.getName().startsWith("Air")) {
          return new games.strategy.engine.data.Territory[] {a, b};
        }
      }
    }
    throw new IllegalStateException("no empty sea zones");
  }

  /** The attacker moves its units from a to b in its combat move, then the battle is fought. */
  private void attack(
      final String attacker,
      final List<games.strategy.engine.data.Unit> units,
      final games.strategy.engine.data.Territory a,
      final games.strategy.engine.data.Territory b,
      final String movePrefix) {
    games.strategy.triplea.settings.ClientSetting.setPreferences(
        new org.sonatype.goodies.prefs.memory.MemoryPreferences());
    final GamePlayer p = player(attacker);
    final var bridge = bridge(p);
    org.mockito.Mockito.when(
            bridge
                .getRemotePlayer()
                .selectCasualties(
                    org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.anyInt(),
                    org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.anyBoolean(),
                    org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.anyBoolean()))
        .thenAnswer(
            i ->
                new games.strategy.triplea.delegate.data.CasualtyDetails(
                    i.<games.strategy.triplea.delegate.data.CasualtyList>getArgument(10), true));
    goTo(movePrefix + "CombatMove", 3);
    final var move = (MoveDelegate) data.getDelegate("move");
    move.setDelegateBridgeAndPlayer(bridge);
    move.start();
    final var error =
        move.performMove(
            new games.strategy.engine.data.MoveDescription(
                units, new games.strategy.engine.data.Route(a, b)));
    assertThat(error.orElse(""), error.isEmpty(), is(true));
    move.end();
    goTo(movePrefix + "Battle", 3);
    final var battle =
        (games.strategy.triplea.delegate.battle.BattleDelegate) data.getDelegate("battle");
    battle.setDelegateBridgeAndPlayer(bridge);
    battle.start();
    for (final var e : battle.getBattleListing().getBattlesMap().entrySet()) {
      if (e.getValue().contains(b)) {
        battle.fightBattle(b, false, e.getKey());
      }
    }
    battle.end();
  }

  @Test
  void aSubAttackingBattleshipsFiresOnceThenTheBattleEnds() {
    usEndTurn(2);
    final var pair = emptySeaPair();
    final var sub = type("submarine").create(1, player("Germans"));
    final var battleships = type("battleship").create(2, player("British"));
    data.performChange(
        games.strategy.engine.data.changefactory.ChangeFactory.addUnits(pair[0], sub));
    data.performChange(
        games.strategy.engine.data.changefactory.ChangeFactory.addUnits(pair[1], battleships));
    attack("Germans", sub, pair[0], pair[1], "german");

    // the sub is still there beside the battleships; one round only: one hit on a battleship
    assertThat(pair[1].getUnitCollection().containsAll(sub), is(true));
    assertThat(pair[1].getUnitCollection().containsAll(battleships), is(true));
    assertThat(
        battleships.stream().mapToInt(games.strategy.engine.data.Unit::getHits).sum(), is(1));
    assertThat(
        data.getBattleDelegate()
            .getBattleTracker()
            .getPendingBattleSitesWithoutBombing()
            .contains(pair[1]),
        is(false));
  }

  @Test
  void battleshipsThatCannotHitASubRetreatAndTheSubStays() {
    usEndTurn(2);
    final var pair = emptySeaPair();
    final var battleships = type("battleship").create(2, player("British"));
    final var sub = type("submarine").create(1, player("Italians"));
    data.performChange(
        games.strategy.engine.data.changefactory.ChangeFactory.addUnits(pair[0], battleships));
    data.performChange(
        games.strategy.engine.data.changefactory.ChangeFactory.addUnits(pair[1], sub));
    attack("British", battleships, pair[0], pair[1], "british");

    assertThat(pair[1].getUnitCollection().containsAll(sub), is(true));
    assertThat(pair[0].getUnitCollection().containsAll(battleships), is(true));
  }

  // --- request 12 section 3: default casualties keep the last units able to hit

  // one hit: the default used to sink the destroyer, the only ship able to hit the sub
  private static final int hitsForTest = 1;

  @Test
  void theDefaultKeepsTheDestroyerWhileEnemySubsRemain() {
    final GamePlayer british = player("British");
    final GamePlayer italians = player("Italians");
    final var fleet = new java.util.ArrayList<games.strategy.engine.data.Unit>();
    fleet.addAll(type("battleship").create(1, british));
    fleet.addAll(type("cruiser").create(1, british));
    final var destroyer = type("destroyer").create(1, british);
    fleet.addAll(destroyer);
    final var enemies = new java.util.ArrayList<games.strategy.engine.data.Unit>();
    enemies.addAll(type("submarine").create(1, italians));
    enemies.addAll(type("cruiser").create(1, italians));
    final var bridge = bridge(british);
    org.mockito.Mockito.when(
            bridge
                .getRemotePlayer()
                .selectCasualties(
                    org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.anyInt(),
                    org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.anyBoolean(),
                    org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.anyBoolean()))
        .thenAnswer(
            i ->
                new games.strategy.triplea.delegate.data.CasualtyDetails(
                    i.<games.strategy.triplea.delegate.data.CasualtyList>getArgument(10), true));
    final var dice = org.mockito.Mockito.mock(DiceRoll.class);
    org.mockito.Mockito.when(dice.getHits()).thenReturn(hitsForTest);
    final var details =
        games.strategy.triplea.delegate.battle.casualty.CasualtySelector.selectCasualties(
            british,
            fleet,
            games.strategy.triplea.delegate.power.calculator.CombatValueBuilder.mainCombatValue()
                .enemyUnits(enemies)
                .friendlyUnits(fleet)
                .side(games.strategy.triplea.delegate.battle.BattleState.Side.DEFENSE)
                .gameSequence(data.getSequence())
                .supportAttachments(data.getUnitTypeList().getSupportRules())
                .lhtrHeavyBombers(false)
                .gameDiceSides(data.getDiceSides())
                .territoryEffects(List.of())
                .build(),
            territory("Sea Zone 148 Java Sea"),
            bridge,
            "test",
            dice,
            java.util.UUID.randomUUID(),
            true,
            hitsForTest,
            true);
    assertThat(details.getKilled().containsAll(destroyer), is(false));
    assertThat(details.getKilled(), is(List.of()));
  }

  // --- request 12 section 4: torpedo and medium bombers choose surface ships or subs

  @Test
  void torpedoBombersChooseSurfaceShipsOrSubsAtDifferentValues() {
    final GamePlayer japan = player("Japanese");
    final var torpedo = type("torpedoBomber").create(1, japan).get(0);
    final var medium = type("mediumBomber").create(1, japan).get(0);
    final var surface = games.strategy.triplea.delegate.air.AirCombat.TargetKind.SURFACE;
    final var subs = games.strategy.triplea.delegate.air.AirCombat.TargetKind.SUBS;
    assertThat(
        games.strategy.triplea.delegate.air.AirCombat.strikePower(torpedo, surface),
        is(2 * games.strategy.triplea.delegate.air.AirCombat.strikePower(torpedo, subs)));
    assertThat(
        games.strategy.triplea.delegate.air.AirCombat.strikePower(medium, surface),
        is(games.strategy.triplea.delegate.air.AirCombat.strikePower(medium, subs)));
    assertThat(
        games.strategy.triplea.delegate.air.AirCombat.mayAttack(type("torpedoBomber"), subs),
        is(true));
    assertThat(
        games.strategy.triplea.delegate.air.AirCombat.mayAttack(type("mediumBomber"), subs),
        is(true));
    assertThat(
        games.strategy.triplea.delegate.air.AirCombat.mayAttack(type("diveBomber"), subs),
        is(false));
    // a zone with both kinds gives two separate targets; hits go only to that kind
    usEndTurn(2);
    final var sea = territory("Sea Zone 148 Java Sea");
    data.performChange(
        games.strategy.engine.data.changefactory.ChangeFactory.addUnits(
            sea, type("cruiser").create(1, player("British"))));
    data.performChange(
        games.strategy.engine.data.changefactory.ChangeFactory.addUnits(
            sea, type("submarine").create(2, player("British"))));
    assertThat(
        games.strategy.triplea.delegate.air.AirCombat.getShipTargetUnits(japan, sea, false).size(),
        is(1));
    assertThat(
        games.strategy.triplea.delegate.air.AirCombat.getShipTargetUnits(japan, sea, true).size(),
        is(2));
  }

  // --- request 12 section 5: the British factory area

  private static final String BRITISH_AREA =
      "London:Midlands:Northern England:Northern Ireland:Scotland:Wales:Wessex:Alberta:"
          + "British Columbia:East Canadian Arctic:Manitoba:Maritimes:Montreal:Newfoundland:"
          + "Northern Ontario:Northern Quebec:Northwest Territories:Saskatchewan:Toronto:"
          + "West Canadian Arctic:Yukon:Bihar:Bombay:Calcutta:Central Provinces:Delhi:Hyderabad:"
          + "Madras:Punjab:United Provinces:Adelaide:Brisbane:Central Australia:Darwin:Kimberley:"
          + "Melbourne:Perth:Sydney:Tasmania:Townsville:Cape Town:Natal:Transvaal";

  @Test
  void britainBuildsNewFactoriesOnlyInItsFactoryArea() {
    final GamePlayer british = player("British");
    data.getProperties().set(FactoryArea.PREFIX + "British", BRITISH_AREA);
    data.getProperties()
        .set(
            FactoryArea.TEXT_PREFIX + "British",
            "Britain may build factories only in the Isles, Canada, India, Australia and South"
                + " Africa");
    for (final String name : BRITISH_AREA.split(":")) {
      territory(name); // every listed territory exists on the map
    }
    final var outside =
        data.getMap().getTerritories().stream()
            .filter(t -> !t.isWater() && t.isOwnedBy(british) && !FactoryArea.allows(british, t))
            .findFirst()
            .orElseThrow();
    goTo("britishPlace", 1);
    final var place = (PlaceDelegate) data.getDelegate("place");
    place.setDelegateBridgeAndPlayer(bridge(british));
    place.start();
    final var factory = type("factory").create(1, british);
    british.getUnitCollection().addAll(factory);
    assertThat(
        place.canUnitsBePlaced(outside, factory, british).orElse(""),
        is(
            "Britain may build factories only in the Isles, Canada, India, Australia and South Africa"));
    assertThat(
        place.canUnitsBePlaced(territory("Bombay"), factory, british).orElse(""),
        org.hamcrest.Matchers.not(org.hamcrest.Matchers.startsWith("Britain may build")));
    place.end();
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
