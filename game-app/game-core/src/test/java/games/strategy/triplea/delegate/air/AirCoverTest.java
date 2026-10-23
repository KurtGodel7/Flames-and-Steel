package games.strategy.triplea.delegate.air;

import static games.strategy.triplea.delegate.GameDataTestUtil.addTo;
import static games.strategy.triplea.delegate.GameDataTestUtil.removeFrom;
import static games.strategy.triplea.delegate.MockDelegateBridge.advanceToStep;
import static games.strategy.triplea.delegate.MockDelegateBridge.newDelegateBridge;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameStep;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.engine.data.gameparser.GameParser;
import games.strategy.engine.delegate.IDelegateBridge;
import games.strategy.triplea.attachments.AirZoneAttachment;
import games.strategy.triplea.delegate.AirCombatDelegate;
import games.strategy.triplea.delegate.InitializationDelegate;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.TechTracker;
import games.strategy.triplea.delegate.air.AirCombat.Target;
import games.strategy.triplea.delegate.air.AirCombat.TargetAssignment;
import games.strategy.triplea.delegate.air.AirCombat.TargetKind;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Request 30 on the installed map: caps, ranges, P-51 escorts and the worked check. */
class AirCoverTest {
  private static final Path XML =
      Path.of(
          System.getProperty("user.home"),
          "triplea/downloadedMaps/flames_and_steel/map/games/flames_and_steel.xml");
  private GameData data;
  private GamePlayer germans;
  private GamePlayer russians;
  private GamePlayer british;
  private GamePlayer americans;
  private Territory easternFront;
  private Territory bombGermany;
  private AirCombatDelegate delegate;
  private int die;

  @BeforeEach
  void setUp() {
    Assumptions.assumeTrue(Files.exists(XML), "Flames and Steel map not installed");
    data = GameParser.parse(XML, false).orElseThrow();
    germans = player("Germans");
    russians = player("Russians");
    british = player("British");
    americans = player("Americans");
    easternFront = data.getMap().getTerritoryOrThrow("Air - Eastern Front");
    bombGermany = data.getMap().getTerritoryOrThrow("Air - Bomb Germany");
    final InitializationDelegate start = new InitializationDelegate();
    start.initialize("initDelegate", "Initializing Delegates");
    start.setDelegateBridgeAndPlayer(newDelegateBridge(germans));
    start.start();
    start.end();
    for (final GamePlayer p : List.of(russians, british, americans)) {
      war(p, germans);
    }
    delegate = (AirCombatDelegate) data.getDelegate("airCombat");
    for (final GamePlayer p : List.of(germans, russians)) {
      final String step = (p.equals(germans) ? "german" : "russian") + "AirCover";
      data.getSequence()
          .addStep(new GameStep(step, step, p, delegate, data, new Properties()));
    }
  }

  private GamePlayer player(final String name) {
    return data.getPlayerList().getPlayerId(name);
  }

  private UnitType type(final String name) {
    return data.getUnitTypeList().getUnitTypeOrThrow(name);
  }

  private void war(final GamePlayer a, final GamePlayer b) {
    data.performChange(
        ChangeFactory.relationshipChange(
            a,
            b,
            data.getRelationshipTracker().getRelationshipType(a, b),
            data.getRelationshipTypeList().getRelationshipType("War")));
  }

  /** A Soviet land territory in the Eastern Front next to German land (both sides reach it). */
  private Territory frontLine() {
    for (final Territory t :
        AirZoneAttachment.get(easternFront).orElseThrow().getCoveredTerritories()) {
      if (!t.isWater()
          && t.isOwnedBy(russians)
          && data.getMap().getNeighbors(t).stream().anyMatch(n -> n.isOwnedBy(germans))) {
        return t;
      }
    }
    throw new IllegalStateException("no front line");
  }

  private static TargetAssignment cover(
      final Territory zone, final UnitType type, final int count, final Territory t) {
    return new TargetAssignment(zone, type, count, AirCover.over(t));
  }

  private IDelegateBridge bridge(final GamePlayer player) {
    final IDelegateBridge bridge = newDelegateBridge(player);
    when(bridge.getRandom(anyInt(), any(), any(), anyString()))
        .thenAnswer(i -> Math.min(die, i.<Integer>getArgument(0) - 1));
    return bridge;
  }

  private String run(final String step, final GamePlayer player, final TargetAssignment... a) {
    final IDelegateBridge bridge = bridge(player);
    advanceToStep(bridge, step);
    delegate.setDelegateBridgeAndPlayer(bridge);
    delegate.start();
    final String error = a.length == 0 ? null : delegate.assignTargets(List.of(a));
    delegate.end();
    return error;
  }

  private int count(final Territory t, final GamePlayer owner, final String type) {
    return t.getUnitCollection()
        .countMatches(Matches.unitIsOwnedBy(owner).and(Matches.unitIsOfType(type(type))));
  }

  /**
   * The request's worked check: 4 German fighters and 4 dive bombers against 4 Soviet fighters over
   * one territory, one dogfight round. Returns the bridge of the strike step.
   */
  private IDelegateBridge workedCheck(final Territory t) {
    removeFrom(easternFront, easternFront.getUnits());
    removeFrom(t, t.getUnits());
    addTo(easternFront, type("fighter").create(4, germans));
    addTo(easternFront, type("diveBomber").create(4, germans));
    addTo(easternFront, type("fighter").create(4, russians));
    addTo(t, type("infantry").create(10, russians));
    // an editable property in the XML: set it the way the game options do
    data.getProperties()
        .getEditablePropertiesByName()
        .get(AirCombat.DOGFIGHT_ROUNDS)
        .setValueIfValid(1);
    assertThat(
        run(
            "germanAirTargets",
            germans,
            new TargetAssignment(
                easternFront, type("diveBomber"), 4, new Target(t, TargetKind.LAND))),
        is((String) null));
    assertThat(
        run("germanAirCover", germans, cover(easternFront, type("fighter"), 4, t)),
        is((String) null));
    assertThat(
        run("russianAirCover", russians, cover(easternFront, type("fighter"), 4, t)),
        is((String) null));
    final IDelegateBridge strikes = bridge(germans);
    advanceToStep(strikes, "airStrikes");
    delegate.setDelegateBridgeAndPlayer(strikes);
    delegate.start();
    delegate.end();
    return strikes;
  }

  @Test
  void workedCheckWhenTheRemainderDiceMiss() {
    die = 5;
    final Territory t = frontLine();
    final IDelegateBridge strikes = workedCheck(t);

    // dogfight: Germans 4 x 3 = 12, two sure hits, one 2-hit-point Soviet fighter destroyed;
    // Soviets 4 x 2 = 8, one sure hit: one German fighter damaged (destroyed only if the 2-in-6
    // remainder die hits)
    assertThat(count(easternFront, russians, "fighter"), is(3));
    assertThat(count(easternFront, germans, "fighter"), is(4));
    // interception 3 x 3 = 9: one hit, one dive bomber damaged and aborted; the bombers' return
    // fire 4 x 1 misses
    assertThat(count(easternFront, germans, "diveBomber"), is(4));
    // three dive bombers strike: 3 x 3, doubled against land, = 18: three infantry
    assertThat(count(t, russians, "infantry"), is(10 - 3));
    // request 30: nobody was asked to choose casualties
    verify(strikes.getRemotePlayer(), never())
        .selectCasualties(
            any(), any(), anyInt(), any(), any(), any(), any(), any(), anyBoolean(), any(), any(),
            any(), any(), anyBoolean());
  }

  @Test
  void workedCheckWhenTheRemainderDiceHit() {
    die = 0;
    final Territory t = frontLine();
    workedCheck(t);

    // each side loses one fighter
    assertThat(count(easternFront, russians, "fighter"), is(3));
    assertThat(count(easternFront, germans, "fighter"), is(3));
    // interception 9: two hits, both on the same dive bomber (sticky): exactly one stopped
    assertThat(count(easternFront, germans, "diveBomber"), is(3));
    assertThat(count(t, russians, "infantry"), is(10 - 3));
  }

  @Test
  void aFifthBomberOrFighterOfOneSideIsRefused() {
    final Territory t = frontLine();
    removeFrom(easternFront, easternFront.getUnits());
    addTo(easternFront, type("diveBomber").create(5, germans));
    addTo(easternFront, type("fighter").create(5, germans));
    addTo(t, type("infantry").create(1, russians));
    final Target land = new Target(t, TargetKind.LAND);

    assertThat(
        run(
            "germanAirTargets",
            germans,
            new TargetAssignment(easternFront, type("diveBomber"), 5, land)),
        is(not((String) null)));
    assertThat(
        run(
            "germanAirTargets",
            germans,
            new TargetAssignment(easternFront, type("diveBomber"), 4, land)),
        is((String) null));
    assertThat(
        run("germanAirCover", germans, cover(easternFront, type("fighter"), 5, t)),
        is(not((String) null)));
    assertThat(
        run("germanAirCover", germans, cover(easternFront, type("fighter"), 4, t)),
        is((String) null));
  }

  @Test
  void britishAndAmericanAircraftShareOneCap() {
    final Territory t = frontLine();
    final Map<GamePlayer, List<TargetAssignment>> us =
        Map.of(americans, List.of(cover(easternFront, type("fighter"), 2, t)));
    assertThat(
        AirCover.capProblem(
                british, List.of(cover(easternFront, type("fighter"), 3, t)), us, Map.of())
            .isPresent(),
        is(true));
    assertThat(
        AirCover.capProblem(
                british, List.of(cover(easternFront, type("fighter"), 2, t)), us, Map.of())
            .isPresent(),
        is(false));
    // the enemy's aircraft never count against a side's cap
    assertThat(
        AirCover.capProblem(
                germans,
                List.of(cover(easternFront, type("fighter"), 4, t)),
                Map.of(russians, List.of(cover(easternFront, type("fighter"), 4, t))),
                Map.of())
            .isPresent(),
        is(false));
  }

  /** A German factory in the Bomb Germany zone. */
  private Territory germanFactory() {
    return AirZoneAttachment.get(bombGermany).orElseThrow().getCoveredTerritories().stream()
        .filter(t -> t.isOwnedBy(germans) && t.anyUnitsMatch(Matches.unitCanProduceUnits()))
        .findFirst()
        .orElseThrow();
  }

  @Test
  void defendersOfABombedFactoryAreUncappedAndItsBombersCapped() {
    final Territory factory = germanFactory();
    final Map<GamePlayer, List<TargetAssignment>> raid =
        Map.of(
            british,
            List.of(
                new TargetAssignment(
                    bombGermany,
                    type("strategicBomber"),
                    5,
                    new Target(factory, TargetKind.STRATEGIC))));
    assertThat(AirCover.fighterCap(germans, bombGermany, factory, raid), is(AirCover.NO_CAP));
    assertThat(
        AirCover.capProblem(
                germans, List.of(cover(bombGermany, type("fighter"), 12, factory)), Map.of(), raid)
            .isPresent(),
        is(false));
    assertThat(AirCover.capProblem(british, raid.get(british), Map.of(), raid).isPresent(), is(true));
    // with the property set, defenders are capped too
    data.getProperties().set(AirCover.CAP_DEFEND_FACTORIES, true);
    assertThat(AirCover.fighterCap(germans, bombGermany, factory, raid), is(4));
  }

  @Test
  void seaZonesAreUncapped() {
    final Territory sea =
        data.getMap().getTerritories().stream().filter(Territory::isWater).findFirst().orElseThrow();
    assertThat(AirCover.bomberCap(data, sea), is(AirCover.NO_CAP));
    assertThat(AirCover.fighterCap(germans, easternFront, sea, Map.of()), is(AirCover.NO_CAP));
  }

  @Test
  void aFighterCannotCoverBeyondHalfItsRange() {
    final UnitType fighter = type("fighter");
    final List<Territory> places = AirCover.places(germans, easternFront, fighter, Map.of());
    final Territory far =
        AirZoneAttachment.get(easternFront).orElseThrow().getCoveredTerritories().stream()
            .filter(t -> !t.isWater())
            .filter(
                t ->
                    !AirRange.canReach(
                        germans, easternFront, fighter, AirRange.range(fighter, germans), t))
            .findFirst()
            .orElseThrow();
    assertThat(places, not(hasItem(far)));
    assertThat(places, hasItem(frontLine()));
    removeFrom(easternFront, easternFront.getUnits());
    addTo(easternFront, fighter.create(1, germans));
    assertThat(
        run("germanAirCover", germans, cover(easternFront, fighter, 1, far)),
        is(not((String) null)));
  }

  @Test
  void mustangEscortsFourPerFactoryAndJetsNever() {
    final Territory factory = germanFactory();
    final UnitType advanced = type("advancedFighter");
    // without the tech American fighters may not enter a bombing zone
    assertThat(AirAllocation.entryProblem(americans, advanced, bombGermany).isPresent(), is(true));
    TechTracker.addAdvance(
        americans,
        newDelegateBridge(americans),
        data.getTechnologyFrontier().getAdvanceByName("P51Mustang"));
    data.getTechTracker().clearCache();
    assertThat(AirAllocation.entryProblem(americans, advanced, bombGermany).isPresent(), is(false));
    assertThat(
        AirAllocation.entryProblem(americans, type("jet"), bombGermany).isPresent(), is(true));

    final Map<GamePlayer, List<TargetAssignment>> raid =
        Map.of(
            americans,
            List.of(
                new TargetAssignment(
                    bombGermany,
                    type("strategicBomber"),
                    4,
                    new Target(factory, TargetKind.STRATEGIC))));
    // an escort covers only a factory its side bombs (and in range); jets never
    assertThat(AirCover.places(americans, bombGermany, type("jet"), raid).isEmpty(), is(true));
    assertThat(AirCover.places(americans, bombGermany, advanced, raid), is(List.of(factory)));
    assertThat(
        AirCover.capProblem(
                americans, List.of(cover(bombGermany, advanced, 4, factory)), Map.of(), raid)
            .isPresent(),
        is(false));
    assertThat(
        AirCover.capProblem(
                americans, List.of(cover(bombGermany, advanced, 5, factory)), Map.of(), raid)
            .isPresent(),
        is(true));
  }
}
