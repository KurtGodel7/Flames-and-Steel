package games.strategy.triplea.delegate;

import static games.strategy.triplea.delegate.GameDataTestUtil.addTo;
import static games.strategy.triplea.delegate.GameDataTestUtil.removeFrom;
import static games.strategy.triplea.delegate.MockDelegateBridge.newDelegateBridge;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.engine.framework.GameDataManager;
import games.strategy.triplea.delegate.air.AirBases;
import games.strategy.triplea.delegate.air.AirCombat;
import games.strategy.triplea.delegate.air.AirCombat.Target;
import games.strategy.triplea.delegate.air.AirCombat.TargetAssignment;
import games.strategy.triplea.delegate.air.AirCombat.TargetKind;
import games.strategy.triplea.delegate.air.AirCover;
import games.strategy.triplea.delegate.air.AirHome;
import games.strategy.triplea.delegate.air.AirPriority;
import games.strategy.triplea.delegate.air.AirStage;
import games.strategy.triplea.delegate.air.AirStage.Choice;
import games.strategy.triplea.delegate.air.AirStage.Landing;
import games.strategy.triplea.delegate.air.AirStage.Stage;
import games.strategy.triplea.delegate.air.BasedMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.triplea.io.IoUtils;

/**
 * Request 38 on the installed map with aircraft on the map: the air stages fly from bases, the air
 * battles are as before, and planes go home at the end of the round. Section 11 (one cap per
 * territory, AA once per battle, defenders uncapped only against a factory raid) and section 12
 * (Soviet priority) too.
 */
class Request38Test {
  private GameData data;
  private GamePlayer germans;
  private GamePlayer russians;
  private GamePlayer british;
  private Territory london;
  private Territory hamburg;

  @BeforeEach
  void setUp() {
    data = BasedMap.load();
    germans = BasedMap.p(data, "Germans");
    russians = BasedMap.p(data, "Russians");
    british = BasedMap.p(data, "British");
    final InitializationDelegate start = new InitializationDelegate();
    start.initialize("initDelegate", "Initializing Delegates");
    start.setDelegateBridgeAndPlayer(newDelegateBridge(germans));
    start.start();
    start.end();
    for (final GamePlayer p : List.of(russians, british, BasedMap.p(data, "Americans"))) {
      war(p, germans);
    }
    london = t("London");
    hamburg = t("Hamburg");
    data.getProperties()
        .set(AirPriority.PROPERTY, "Russians|British:AmericansPacific:Americans:Chinese");
  }

  private void war(final GamePlayer a, final GamePlayer b) {
    data.performChange(
        ChangeFactory.relationshipChange(
            a,
            b,
            data.getRelationshipTracker().getRelationshipType(a, b),
            data.getRelationshipTypeList().getRelationshipType("War")));
  }

  private Territory t(final String name) {
    return data.getMap().getTerritoryOrThrow(name);
  }

  private UnitType type(final String name) {
    return data.getUnitTypeList().getUnitTypeOrThrow(name);
  }

  private List<Unit> add(
      final Territory t, final GamePlayer owner, final String type, final int n) {
    final List<Unit> units = type(type).create(n, owner);
    addTo(t, units);
    return units;
  }

  private static TargetAssignment a(
      final Territory base, final UnitType type, final int n, final Target target) {
    return new TargetAssignment(base, type, n, target);
  }

  private static Target land(final Territory t) {
    return new Target(t, TargetKind.LAND);
  }

  private static Target bomb(final Territory t) {
    return new Target(t, TargetKind.STRATEGIC);
  }

  private int over(final Territory t, final GamePlayer owner, final String type) {
    return (int)
        AirBases.airborneOver(owner, t).stream()
            .filter(u -> u.getType().getName().equals(type))
            .count();
  }

  private int count(final Territory t, final GamePlayer owner, final String type) {
    return t.getUnitCollection()
        .countMatches(Matches.unitIsOwnedBy(owner).and(Matches.unitIsOfType(type(type))));
  }

  /** Hamburg with a German army and its factory, no AA. */
  private void hamburgWithAnArmy() {
    removeFrom(hamburg, hamburg.getUnitCollection().getMatches(Matches.unitIsAaForAnything()));
    removeFrom(hamburg, hamburg.getUnitCollection().getMatches(Matches.unitIsAir()));
    add(hamburg, germans, "infantry", 6);
    assertThat(AirCombat.isStrategicTarget(british, hamburg), is(true));
  }

  // ---- the stages fly from bases

  @Test
  void bombersTakeOffFromTheirBaseWithTheRangeTheyHaveLeft() {
    hamburgWithAnArmy();
    add(london, british, "mediumBomber", 2);
    final int out = AirBases.distance(british, london, hamburg, 20).orElseThrow();
    final AirStageRunner stages =
        new AirStageRunner(data)
            .bombers(british, a(london, type("mediumBomber"), 2, land(hamburg)))
            .run();
    assertThat(stages.problems(), is(empty()));
    assertThat(count(london, british, "mediumBomber"), is(0));
    assertThat(over(hamburg, british, "mediumBomber"), is(2));
    for (final Unit u : AirBases.airborneOver(british, hamburg)) {
      assertThat(u.getFlightRangeLeft(), is(8 - out));
    }
  }

  /**
   * The request 30 worked check, flown from bases: 4 German fighters and 4 dive bombers from the
   * German side of the front against 4 Soviet fighters over a Soviet front-line territory.
   */
  private Territory workedCheck(final int die) {
    try {
      final var tech = russians.getTechAttachment();
      tech.getPropertyOrThrow("dogfightBonus").resetValue();
      tech.getPropertyOrThrow("dogfightBonus").setValue("-1:fighter");
      tech.getPropertyOrThrow("interceptionBonus").resetValue();
    } catch (final Exception e) {
      throw new IllegalStateException(e);
    }
    data.getTechTracker().clearCache();
    Territory front = null;
    Territory base = null;
    for (final Territory t : data.getMap().getTerritories()) {
      if (t.isWater() || !t.isOwnedBy(russians)) {
        continue;
      }
      final Optional<Territory> german =
          data.getMap().getNeighbors(t).stream()
              .filter(n -> !n.isWater() && n.isOwnedBy(germans))
              .findFirst();
      if (german.isPresent()) {
        front = t;
        base = german.get();
        break;
      }
    }
    removeFrom(front, front.getUnits());
    removeFrom(base, base.getUnitCollection().getMatches(Matches.unitIsAir()));
    add(base, germans, "fighter", 4);
    add(base, germans, "diveBomber", 4);
    add(front, russians, "fighter", 4);
    add(front, russians, "infantry", 10);
    data.getProperties()
        .getEditablePropertiesByName()
        .get(AirCombat.DOGFIGHT_ROUNDS)
        .setValueIfValid(1);
    final AirStageRunner stages =
        new AirStageRunner(data)
            .die(die)
            .bombers(germans, a(base, type("diveBomber"), 4, land(front)))
            .cover(germans, a(base, type("fighter"), 4, AirCover.over(front)))
            .cover(russians, a(front, type("fighter"), 4, AirCover.over(front)))
            .run();
    assertThat(stages.problems(), is(empty()));
    stages.battles();
    return front;
  }

  @Test
  void workedCheckWhenTheRemainderDiceMiss() {
    final Territory front = workedCheck(5);
    assertThat(over(front, russians, "fighter"), is(3));
    assertThat(over(front, germans, "fighter"), is(4));
    assertThat(over(front, germans, "diveBomber"), is(4));
    // request 39: three dive bombers at 3 + 1 = 12: two hits
    assertThat(count(front, russians, "infantry"), is(10 - 2));
  }

  @Test
  void workedCheckWhenTheRemainderDiceHit() {
    final Territory front = workedCheck(0);
    assertThat(over(front, russians, "fighter"), is(3));
    assertThat(over(front, germans, "fighter"), is(3));
    assertThat(over(front, germans, "diveBomber"), is(3));
    // request 39: three dive bombers at 3 + 1 = 12: two hits
    assertThat(count(front, russians, "infantry"), is(10 - 2));
  }

  @Test
  void everyNationIsAskedAtOnceAndAircraftOnTheGroundAreNoTargets() {
    hamburgWithAnArmy();
    add(london, british, "mediumBomber", 1);
    add(hamburg, germans, "fighter", 2); // on the ground: not a target, and not in the battle
    final AirStageRunner stages =
        new AirStageRunner(data)
            .bombers(british, a(london, type("mediumBomber"), 1, land(hamburg)))
            .run();
    assertThat(stages.asked(), hasItem(british));
    assertThat(stages.asked(), hasItem(germans));
    stages.battles();
    assertThat(count(hamburg, germans, "fighter"), is(2));
  }

  // ---- section 11

  @Test
  void fourBombersSplitTwoStrikeTwoBombAreLegalAndAFifthIsRefused() {
    hamburgWithAnArmy();
    add(london, british, "mediumBomber", 5);
    final UnitType medium = type("mediumBomber");
    final Choice four =
        Choice.of(
            List.of(a(london, medium, 2, land(hamburg)), a(london, medium, 2, bomb(hamburg))));
    assertThat(
        AirStage.problem(british, Stage.BOMBERS, four, Map.of(), Map.of()), is(Optional.empty()));
    final Choice fiveStrike =
        Choice.of(
            List.of(a(london, medium, 3, land(hamburg)), a(london, medium, 2, bomb(hamburg))));
    assertThat(
        AirStage.problem(british, Stage.BOMBERS, fiveStrike, Map.of(), Map.of()).orElseThrow(),
        containsString("at most 4 bombers"));
    final Choice fiveBomb =
        Choice.of(
            List.of(a(london, medium, 2, land(hamburg)), a(london, medium, 3, bomb(hamburg))));
    assertThat(
        AirStage.problem(british, Stage.BOMBERS, fiveBomb, Map.of(), Map.of()).isPresent(),
        is(true));
  }

  /** Section 11: two AA guns fire once, total 4, with 1 bomber or with 4. */
  private void aaOnce(final int strike, final int bomb) {
    hamburgWithAnArmy();
    add(hamburg, germans, "aaGun", 2);
    add(london, british, "mediumBomber", strike + bomb);
    final UnitType medium = type("mediumBomber");
    final AirStageRunner stages = new AirStageRunner(data).die(5);
    if (bomb == 0) {
      stages.bombers(british, a(london, medium, strike, land(hamburg)));
    } else {
      stages.bombers(
          british,
          a(london, medium, strike, land(hamburg)),
          a(london, medium, bomb, bomb(hamburg)));
    }
    stages.run();
    assertThat(stages.problems(), is(empty()));
    stages.battles();
    // total 4 of 6: no sure hit, one die for the remainder, rolled once
    verify(stages.lastBridge(), times(1)).getRandom(anyInt(), any(), any(), contains("AA fire"));
  }

  @Test
  void aaFiresOnceWithOneBomber() {
    aaOnce(1, 0);
  }

  @Test
  void aaFiresOnceWithFourBombersOnTwoMissions() {
    aaOnce(2, 2);
  }

  @Test
  void defendersAreCappedAgainstStrikesAndUncappedAgainstAFactoryRaid() {
    hamburgWithAnArmy();
    add(hamburg, germans, "fighter", 6);
    final UnitType fighter = type("fighter");
    final UnitType medium = type("mediumBomber");
    final Choice five = Choice.of(List.of(a(hamburg, fighter, 5, AirCover.over(hamburg))));
    final Choice six = Choice.of(List.of(a(hamburg, fighter, 6, AirCover.over(hamburg))));
    // 4 bombers striking the army: at most 4 defenders
    final Map<GamePlayer, List<TargetAssignment>> strikes =
        Map.of(british, List.of(a(london, medium, 4, land(hamburg))));
    assertThat(
        AirStage.problem(germans, Stage.FIGHTERS, five, Map.of(), strikes).orElseThrow(),
        containsString("at most 4 fighters"));
    // 3 striking and 1 bombing the factory: every defender may go
    final Map<GamePlayer, List<TargetAssignment>> mixed =
        Map.of(
            british,
            List.of(a(london, medium, 3, land(hamburg)), a(london, medium, 1, bomb(hamburg))));
    assertThat(
        AirStage.problem(germans, Stage.FIGHTERS, six, Map.of(), mixed), is(Optional.empty()));
  }

  // ---- section 12

  @Test
  void aSovietBattleIsRefusedToTheBritishAtTheMouse() {
    hamburgWithAnArmy();
    add(london, british, "mediumBomber", 1);
    final Choice choice = Choice.of(List.of(a(london, type("mediumBomber"), 1, land(hamburg))));
    final Map<GamePlayer, List<TargetAssignment>> soviet =
        Map.of(russians, List.of(a(t("Moscow"), type("mediumBomber"), 1, land(hamburg))));
    assertThat(
        AirStage.problem(british, Stage.BOMBERS, choice, soviet, Map.of()).orElseThrow(),
        is(AirPriority.lockedText(hamburg, russians)));
    // never the other way round
    final Map<GamePlayer, List<TargetAssignment>> westernFirst =
        Map.of(british, List.of(a(london, type("mediumBomber"), 1, land(hamburg))));
    assertThat(AirPriority.lockedBy(russians, hamburg, westernFirst), is(Optional.empty()));
  }

  // ---- request 43 section 1: one nation's bombers per factory per round

  @Test
  void twoAlliesBombingOneFactoryTheEarlierNationKeepsIt() {
    hamburgWithAnArmy();
    data.getProperties().set(AirPriority.ONE_NATION_PER_RAID, true);
    final GamePlayer americans = BasedMap.p(data, "Americans");
    final List<GamePlayer> order = AirPriority.turnOrder(data);
    final GamePlayer earlier =
        order.indexOf(british) < order.indexOf(americans) ? british : americans;
    final GamePlayer later = earlier == british ? americans : british;
    add(london, british, "strategicBomber", 1);
    add(london, americans, "strategicBomber", 1);
    final AirStageRunner stages =
        new AirStageRunner(data)
            .bombers(later, a(london, type("strategicBomber"), 1, bomb(hamburg)))
            .bombers(earlier, a(london, type("strategicBomber"), 1, bomb(hamburg)))
            .run();
    // whichever answered first, the later nation was told why and its bomber stayed at home
    assertThat(stages.problems(), hasItem(containsString(AirPriority.raidText(hamburg, earlier))));
    assertThat(over(hamburg, earlier, "strategicBomber"), is(1));
    assertThat(over(hamburg, later, "strategicBomber"), is(0));
    assertThat(count(london, later, "strategicBomber"), is(1));
  }

  @Test
  void alliesMayStillMixTacticalStrikesOnOneTerritory() {
    hamburgWithAnArmy();
    data.getProperties().set(AirPriority.ONE_NATION_PER_RAID, true);
    final GamePlayer americans = BasedMap.p(data, "Americans");
    add(london, british, "mediumBomber", 1);
    add(london, americans, "mediumBomber", 1);
    final AirStageRunner stages =
        new AirStageRunner(data)
            .bombers(british, a(london, type("mediumBomber"), 1, land(hamburg)))
            .bombers(americans, a(london, type("mediumBomber"), 1, land(hamburg)))
            .run();
    assertThat(stages.problems().stream().noneMatch(p -> p.contains("raid this round")), is(true));
    assertThat(over(hamburg, british, "mediumBomber"), is(1));
    assertThat(over(hamburg, americans, "mediumBomber"), is(1));
  }

  /** A Soviet base next to Hamburg. */
  private Territory sovietHanover() {
    final Territory hanover = t("Hanover");
    hanover.setOwner(russians);
    removeFrom(hanover, hanover.getUnits());
    return hanover;
  }

  @Test
  void whenTheSovietsChooseABattleTheBritishThereAreAskedAgain() {
    hamburgWithAnArmy();
    final Territory hanover = sovietHanover();
    add(hanover, russians, "mediumBomber", 1);
    add(london, british, "mediumBomber", 1);
    final AirStageRunner stages =
        new AirStageRunner(data)
            .bombers(british, a(london, type("mediumBomber"), 1, land(hamburg)))
            .bombers(russians, a(hanover, type("mediumBomber"), 1, land(hamburg)))
            .run();
    // whichever answered first, the British ended up told why, and only the Soviets fly there
    assertThat(stages.problems(), hasItem(containsString("Hamburg is a Russian air battle")));
    assertThat(over(hamburg, russians, "mediumBomber"), is(1));
    assertThat(over(hamburg, british, "mediumBomber"), is(0));
    assertThat(count(london, british, "mediumBomber"), is(1));
  }

  /** James, 25 October: Soviet fighters may not join a battle British bombers were sent to. */
  @Test
  void sovietFightersAreDeniedABattleBritishBombersWereSentTo() {
    hamburgWithAnArmy();
    final Territory hanover = sovietHanover();
    add(hanover, russians, "fighter", 2);
    add(london, british, "mediumBomber", 2);
    final int infantry = count(hamburg, germans, "infantry");
    final AirStageRunner stages =
        new AirStageRunner(data)
            .bombers(british, a(london, type("mediumBomber"), 2, land(hamburg)))
            .cover(russians, a(hanover, type("fighter"), 2, AirCover.over(hamburg)))
            .run();
    // with nowhere else to go the Soviets have nothing to choose; the British keep their battle
    assertThat(stages.asked().contains(russians), is(false));
    assertThat(over(hamburg, russians, "fighter"), is(0));
    assertThat(count(hanover, russians, "fighter"), is(2));
    assertThat(over(hamburg, british, "mediumBomber"), is(2));
    // nor is Hamburg offered to the Soviet fighters at all
    final Map<GamePlayer, List<TargetAssignment>> revealed =
        Map.of(british, List.of(a(london, type("mediumBomber"), 2, land(hamburg))));
    assertThat(
        AirStage.candidates(russians, hanover, type("fighter"), Stage.FIGHTERS, revealed)
            .contains(AirCover.over(hamburg)),
        is(false));
    // a choice sent anyway is refused with the reason
    assertThat(
        AirStage.problem(
                russians,
                Stage.FIGHTERS,
                Choice.of(List.of(a(hanover, type("fighter"), 2, AirCover.over(hamburg)))),
                Map.of(),
                revealed)
            .orElseThrow(),
        containsString("British bombers were sent there in the bomber stage"));
    stages.battles();
    assertThat(count(hamburg, germans, "infantry") < infantry, is(true)); // the British struck
  }

  // ---- planes go home

  /** A clean board: all land British, Germans own only what the test gives them. */
  private void cleanBoard() {
    BasedMap.allLandTo(data, british);
    BasedMap.clear(data, germans, "carrier", "fleetCarrier");
  }

  @Test
  void aLandingSpotCapturedDuringTheRoundMeansTheAircraftIsLost() {
    cleanBoard();
    // draft 102: Kiev is 5 from Hamburg and Cracow 3 (Lwow is now 4, Silesia 2)
    final Territory kiev = t("Kiev");
    final Territory cracow = t("Krakow");
    kiev.setOwner(germans);
    cracow.setOwner(germans);
    add(hamburg, british, "infantry", 1);
    add(kiev, germans, "mediumBomber", 1);
    final AirStageRunner stages =
        new AirStageRunner(data)
            .bombers(germans, a(kiev, type("mediumBomber"), 1, land(hamburg)))
            .run();
    assertThat(stages.problems(), is(empty()));
    assertThat(over(hamburg, germans, "mediumBomber"), is(1));
    // Cracow (3 from Hamburg) falls; Kiev (5) is beyond the 3 left
    cracow.setOwner(british);
    stages.planesHome();
    assertThat(AirBases.airborneOver(germans, hamburg).size(), is(0));
    assertThat(count(kiev, germans, "mediumBomber"), is(0));
    assertThat(count(cracow, germans, "mediumBomber"), is(0));
  }

  @Test
  void anAircraftLandsWhereItCanByDefault() {
    cleanBoard();
    final Territory lwow = t("Lwow");
    final Territory silesia = t("Silesia");
    lwow.setOwner(germans);
    silesia.setOwner(germans);
    add(hamburg, british, "infantry", 1);
    add(lwow, germans, "mediumBomber", 1);
    new AirStageRunner(data)
        .bombers(germans, a(lwow, type("mediumBomber"), 1, land(hamburg)))
        .run()
        .planesHome();
    assertThat(count(silesia, germans, "mediumBomber"), is(1));
    final Unit landed = silesia.getUnitCollection().getMatches(Matches.unitIsAir()).get(0);
    assertThat(landed.getFlightOver() == null, is(true));
  }

  @Test
  void aCarrierMovesAndItsAircraftLandOnItAtRoundEnd() {
    cleanBoard();
    final Territory base = t("Central Provinces");
    final Territory target = t("Sea Zone 112 Arabian Sea - Southeast");
    final Territory gulf = t("Sea Zone 104 Persian Gulf");
    final Territory near = t("Sea Zone 117 Arabian Sea - Northeast");
    base.setOwner(germans);
    final List<Unit> carrier = add(gulf, germans, "fleetCarrier", 1);
    add(base, germans, "earlyJet", 1);
    // the jet is in the air over the sea zone with 1 step of range left
    final Unit jet = base.getUnitCollection().getMatches(Matches.unitIsAir()).get(0);
    BasedMap.fly(data, target, List.of(jet));
    data.performChange(
        ChangeFactory.unitPropertyChange(jet, 1, Unit.PropertyName.FLIGHT_RANGE_LEFT));
    // the carrier sails 2 toward its aircraft in Germany's turn
    data.performChange(ChangeFactory.moveUnits(gulf, near, carrier));
    new AirStageRunner(data).planesHome();
    assertThat(count(near, germans, "earlyJet"), is(1));
  }

  @Test
  void aircraftThatStayedHomeRebaseWithTheirFullRange() {
    cleanBoard();
    final Territory lwow = t("Lwow");
    lwow.setOwner(germans);
    final Territory three =
        AirBases.distances(germans, lwow, 3).entrySet().stream()
            .filter(e -> e.getValue() == 3 && !e.getKey().isWater())
            .map(Map.Entry::getKey)
            .findFirst()
            .orElseThrow();
    final Territory four =
        AirBases.distances(germans, lwow, 4).entrySet().stream()
            .filter(e -> e.getValue() == 4 && !e.getKey().isWater())
            .map(Map.Entry::getKey)
            .findFirst()
            .orElseThrow();
    three.setOwner(germans);
    four.setOwner(germans);
    add(lwow, germans, "fighter", 1);
    final UnitType fighter = type("fighter");
    assertThat(
        AirHome.problem(germans, List.of(new Landing(lwow, false, fighter, 1, three))),
        is(Optional.empty()));
    assertThat(
        AirHome.problem(germans, List.of(new Landing(lwow, false, fighter, 1, four))).isPresent(),
        is(true));
    new AirStageRunner(data)
        .home(germans, new Landing(lwow, false, fighter, 1, three))
        .planesHome();
    assertThat(count(three, germans, "fighter"), is(1));
  }

  @Test
  void airborneAircraftSurviveASaveAndLoadMidRound() throws Exception {
    hamburgWithAnArmy();
    add(london, british, "mediumBomber", 2);
    new AirStageRunner(data)
        .bombers(british, a(london, type("mediumBomber"), 2, land(hamburg)))
        .run();
    final byte[] bytes = IoUtils.writeToMemory(os -> GameDataManager.saveGame(os, data));
    final GameData loaded = IoUtils.readFromMemory(bytes, GameDataManager::loadGame).orElseThrow();
    final GamePlayer b = loaded.getPlayerList().getPlayerId("British");
    final Territory h = loaded.getMap().getTerritoryOrThrow("Hamburg");
    final List<Unit> up = AirBases.airborneOver(b, h);
    assertThat(up.size(), is(2));
    assertThat(up.get(0).getFlightRangeLeft() > 0, is(true));
  }

  // ---- stage 4: the stock rules

  @Test
  void aircraftOnATerritoryTakeNoPartInItsGroundBattle() {
    cleanBoard();
    final Territory lwow = t("Lwow");
    lwow.setOwner(germans);
    removeFrom(lwow, lwow.getUnits());
    final List<Unit> infantry = add(lwow, germans, "infantry", 2);
    add(lwow, germans, "fighter", 3);
    final var battle =
        new games.strategy.triplea.delegate.battle.MustFightBattle(
            lwow, british, data, new games.strategy.triplea.delegate.battle.BattleTracker());
    assertThat(battle.getDefendingUnits().size(), is(infantry.size()));
    assertThat(battle.getDefendingUnits().stream().anyMatch(Matches.unitIsAir()), is(false));
  }

  @Test
  void aCapturedBaseGivesItsAircraftAFreeMoveOfOneSpace() {
    cleanBoard();
    final Territory lwow = t("Lwow");
    final Territory next =
        data.getMap().getNeighbors(lwow).stream()
            .filter(n -> !n.isWater())
            .findFirst()
            .orElseThrow();
    next.setOwner(germans);
    // draft 102 has German aircraft on nearby bases; with all land British they would move here
    // too, so only these two are on the map
    BasedMap.clear(data, germans);
    add(lwow, germans, "fighter", 2);
    // Lwow stays British (captured): the fighters must move; nobody answers, so the default
    games.strategy.triplea.delegate.air.AirBaseLoss.freeMoves(
        new AirStageRunner(data).bridge(), false, "End of combat");
    assertThat(count(lwow, germans, "fighter"), is(0));
    assertThat(count(next, germans, "fighter"), is(2));
  }

  @Test
  void aCapturedIslandBaseLosesItsAircraft() {
    cleanBoard();
    final Territory lwow = t("Lwow");
    add(lwow, germans, "fighter", 1);
    games.strategy.triplea.delegate.air.AirBaseLoss.freeMoves(
        new AirStageRunner(data).bridge(), false, "End of combat");
    assertThat(count(lwow, germans, "fighter"), is(0));
    assertThat(AirBases.basedAircraft(germans).isEmpty(), is(true));
  }

  @Test
  void aSunkCarriersAircraftMoveOneSpaceAndOneThatCantLandIsLost() {
    cleanBoard();
    final Territory gulf = t("Sea Zone 104 Persian Gulf");
    final Territory next =
        data.getMap().getNeighbors(gulf).stream()
            .filter(Territory::isWater)
            .findFirst()
            .orElseThrow();
    removeFrom(next, next.getUnits());
    add(next, germans, "carrier", 1); // room for 2
    add(gulf, germans, "fighter", 3); // their carrier was sunk
    games.strategy.triplea.delegate.air.AirBaseLoss.freeMoves(
        new AirStageRunner(data).bridge(), true, "Carrier lost in " + gulf.getName());
    assertThat(count(gulf, germans, "fighter"), is(0));
    assertThat(count(next, germans, "fighter"), is(2));
    // the third had no room within one space: lost
    int near = count(gulf, germans, "fighter");
    for (final Territory n : data.getMap().getNeighbors(gulf)) {
      near += count(n, germans, "fighter");
    }
    assertThat(near, is(2));
  }

  /** The Germans' non-combat move with a move delegate ready. */
  private MoveDelegate germanMove() {
    final var sequence = data.getSequence();
    for (int i = 0;
        i < 10_000 && !sequence.getStep().getName().equals("germanNonCombatMove");
        i++) {
      sequence.next();
    }
    final MoveDelegate move = (MoveDelegate) data.getDelegate("move");
    move.setDelegateBridgeAndPlayer(new AirStageRunner(data).bridge());
    move.start();
    return move;
  }

  @Test
  void aircraftCannotMakeOrdinaryMovesButSailWithTheirCarriers() {
    final Territory a = t("Sea Zone 76 Helgoland Bight");
    final Territory b =
        data.getMap().getNeighbors(a).stream().filter(Territory::isWater).findFirst().orElseThrow();
    removeFrom(a, a.getUnits());
    removeFrom(b, b.getUnits());
    final List<Unit> carrier = add(a, germans, "carrier", 1);
    final List<Unit> fighters = add(a, germans, "fighter", 2);
    final MoveDelegate move = germanMove();
    final Optional<String> refused =
        move.performMove(
            new games.strategy.engine.data.MoveDescription(
                fighters, new games.strategy.engine.data.Route(a, b)));
    assertThat(refused.isPresent(), is(true));
    final Optional<String> sailed =
        move.performMove(
            new games.strategy.engine.data.MoveDescription(
                carrier, new games.strategy.engine.data.Route(a, b)));
    assertThat(sailed, is(Optional.empty()));
    assertThat(count(b, germans, "fighter"), is(2));
    assertThat(count(a, germans, "fighter"), is(0));
    move.end();
  }

  @Test
  void newAircraftGoOnlyOnTheNationsOwnCarriersWithRoom() {
    final Territory zone = t("Sea Zone 76 Helgoland Bight");
    removeFrom(zone, zone.getUnits());
    final List<Unit> two = type("fighter").create(2, germans);
    assertThat(AirBases.newAircraftProblem(germans, zone, two).isPresent(), is(true));
    add(zone, BasedMap.p(data, "Italians"), "carrier", 1); // an ally's carrier is no base
    assertThat(AirBases.newAircraftProblem(germans, zone, two).isPresent(), is(true));
    add(zone, germans, "carrier", 1);
    assertThat(AirBases.newAircraftProblem(germans, zone, two), is(Optional.empty()));
    assertThat(
        AirBases.newAircraftProblem(germans, zone, type("fighter").create(3, germans)).isPresent(),
        is(true));
    // a new carrier placed with them counts too
    final List<Unit> withCarrier = new java.util.ArrayList<>(type("fighter").create(4, germans));
    withCarrier.addAll(type("carrier").create(1, germans));
    assertThat(AirBases.newAircraftProblem(germans, zone, withCarrier), is(Optional.empty()));
  }
}
