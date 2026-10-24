package games.strategy.triplea.delegate.air;

import static games.strategy.triplea.delegate.GameDataTestUtil.addTo;
import static games.strategy.triplea.delegate.GameDataTestUtil.removeFrom;
import static games.strategy.triplea.delegate.MockDelegateBridge.advanceToStep;
import static games.strategy.triplea.delegate.MockDelegateBridge.newDelegateBridge;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.engine.data.gameparser.GameParser;
import games.strategy.engine.delegate.IDelegateBridge;
import games.strategy.engine.history.IDelegateHistoryWriter;
import games.strategy.triplea.delegate.AirCombatDelegate;
import games.strategy.triplea.delegate.AirStageRunner;
import games.strategy.triplea.delegate.InitializationDelegate;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.air.AirAllocation.AirMove;
import games.strategy.triplea.delegate.air.AirAllocation.Stage;
import games.strategy.triplea.delegate.air.AirCombat.Target;
import games.strategy.triplea.delegate.air.AirCombat.TargetAssignment;
import games.strategy.triplea.delegate.air.AirCombat.TargetKind;
import games.strategy.triplea.delegate.data.CasualtyDetails;
import games.strategy.triplea.delegate.data.CasualtyList;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Flames and Steel request 26 on the installed map: German flying wings in Western Europe may raid
 * US factories east of the Mississippi from Air - Bomb USA. Skipped when the map is not installed.
 */
class LongRangeRaidTest {
  private static final Path XML =
      Path.of(
          System.getProperty("user.home"),
          "triplea/downloadedMaps/flames_and_steel/map/games/flames_and_steel.xml");
  private GameData data;
  private GamePlayer germans;
  private GamePlayer americans;
  private Territory westernEurope;
  private Territory easternFront;
  private Territory bombUsa;
  private final List<String> history = new ArrayList<>();

  @BeforeEach
  void setUp() {
    Assumptions.assumeTrue(Files.exists(XML), "Flames and Steel map not installed");
    data = GameParser.parse(XML, false).orElseThrow();
    germans = data.getPlayerList().getPlayerId("Germans");
    americans = data.getPlayerList().getPlayerId("Americans");
    westernEurope = data.getMap().getTerritoryOrThrow("Air - Western Europe");
    easternFront = data.getMap().getTerritoryOrThrow("Air - Eastern Front");
    bombUsa = data.getMap().getTerritoryOrThrow("Air - Bomb America Atlantic");
    final InitializationDelegate start = new InitializationDelegate();
    start.initialize("initDelegate", "Initializing Delegates");
    start.setDelegateBridgeAndPlayer(newDelegateBridge(germans));
    start.start();
    start.end();
    // the raid needs Germany and the US at war
    data.performChange(
        ChangeFactory.relationshipChange(
            germans,
            americans,
            data.getRelationshipTracker().getRelationshipType(germans, americans),
            data.getRelationshipTypeList().getRelationshipType("War")));
    removeFrom(bombUsa, bombUsa.getUnits());
  }

  private UnitType type(final String name) {
    return data.getUnitTypeList().getUnitTypeOrThrow(name);
  }

  private List<Unit> add(final Territory where, final String type, final GamePlayer owner) {
    final List<Unit> units = type(type).create(1, owner);
    addTo(where, units);
    return units;
  }

  private List<String> targetNames(final String type) {
    return AirCombat.getTargets(germans, bombUsa, type(type)).stream()
        .map(t -> t.territory().getName())
        .toList();
  }

  @Test
  void flyingWingInWesternEuropeMayGoToBombUsa() {
    add(westernEurope, "flyingWing", germans);
    assertThat(
        AirAllocation.getDestinations(germans, westernEurope, type("flyingWing"), data.getMap()),
        hasItem(bombUsa));
    assertThat(
        AirAllocation.validate(
                germans,
                Stage.BOMBERS,
                List.of(new AirMove(westernEurope, bombUsa, type("flyingWing"), 1)),
                data.getMap())
            .isEmpty(),
        is(true));
  }

  @Test
  void strategicBomberInWesternEuropeMayNot() {
    add(westernEurope, "strategicBomber", germans);
    assertThat(
        AirAllocation.getDestinations(
            germans, westernEurope, type("strategicBomber"), data.getMap()),
        not(hasItem(bombUsa)));
    assertThat(
        AirAllocation.validate(
                germans,
                Stage.BOMBERS,
                List.of(new AirMove(westernEurope, bombUsa, type("strategicBomber"), 1)),
                data.getMap())
            .isPresent(),
        is(true));
  }

  @Test
  void flyingWingElsewhereMayNot() {
    add(easternFront, "flyingWing", germans);
    assertThat(
        AirAllocation.getDestinations(germans, easternFront, type("flyingWing"), data.getMap()),
        not(hasItem(bombUsa)));
  }

  @Test
  void onlyFactoriesEastOfTheMississippiAreTargets() {
    final List<String> targets = targetNames("flyingWing");
    assertThat(targets, hasItem("Detroit"));
    assertThat(targets, hasItem("New York"));
    assertThat(targets, hasItem("Chicago"));
    assertThat(targets, not(hasItem("San Francisco")));
    // and the target step refuses San Francisco
    final Territory sanFrancisco = data.getMap().getTerritoryOrThrow("San Francisco");
    add(bombUsa, "flyingWing", germans);
    assertThat(
        AirCombat.mayAttack(
            germans, bombUsa, type("flyingWing"), new Target(sanFrancisco, TargetKind.STRATEGIC)),
        is(false));
  }

  @Test
  void raidIsInterceptedThenBombsAndTheWingFliesHome() {
    final Territory detroit = data.getMap().getTerritoryOrThrow("Detroit");
    removeFrom(detroit, detroit.getUnitCollection().getMatches(Matches.unitIsAaForAnything()));
    final List<Unit> wing = add(bombUsa, "flyingWing", germans);
    add(bombUsa, "fighter", americans);
    final AirCombatDelegate delegate = (AirCombatDelegate) data.getDelegate("airCombat");

    // request 35: San Francisco is refused in the bomber stage; Detroit is accepted, and the US
    // fighter defends Detroit in the fighter stage
    final Target sanFrancisco =
        new Target(data.getMap().getTerritoryOrThrow("San Francisco"), TargetKind.STRATEGIC);
    assertThat(
        AirStage.problem(
                germans,
                AirAllocation.Stage.BOMBERS,
                new AirStage.Choice(
                    List.of(),
                    List.of(new TargetAssignment(bombUsa, type("flyingWing"), 1, sanFrancisco))),
                Map.of(),
                Map.of())
            .isPresent(),
        is(true));
    assertThat(
        new AirStageRunner(data)
            .bombers(
                germans,
                new TargetAssignment(
                    bombUsa, type("flyingWing"), 1, new Target(detroit, TargetKind.STRATEGIC)))
            .cover(
                americans,
                new TargetAssignment(bombUsa, type("fighter"), 1, AirCover.over(detroit)))
            .run()
            .problems(),
        is(empty()));

    final int before = americans.getResources().getQuantity("PUs");
    final IDelegateBridge strikes = bridge(germans);
    advanceToStep(strikes, "airStrikes");
    delegate.setDelegateBridgeAndPlayer(strikes);
    delegate.start();
    delegate.end();

    // request 27: the targets came first; then US fighters in Bomb USA intercept (3, missed)
    // and the wing fires back (air defense 4, missed)
    assertThat(history, hasItem("Air battle over Detroit (Bomb America Atlantic)"));
    assertThat(history, hasItem("Americans fire with total value 3: 0 hits"));
    assertThat(history, hasItem("Germans fire with total value 4: 0 hits"));
    // bombing damage 6 off the US cash, within Detroit's cap
    assertThat(americans.getResources().getQuantity("PUs"), is(before - 6));
    assertThat(
        history.stream().anyMatch(h -> h.startsWith("German bombed Detroit: Americans −6 PUs")),
        is(true));
    assertThat(history, hasItem(containsString("Strategic bombing of Detroit (long-range raid)")));
    assertThat(westernEurope.getUnitCollection().containsAll(wing), is(true));
    assertThat(bombUsa.getUnitCollection().containsAll(wing), is(false));
  }

  /** Remainder dice always miss; history lines are kept; players take offered casualties. */
  private IDelegateBridge bridge(final GamePlayer player) {
    final IDelegateBridge bridge = newDelegateBridge(player);
    final IDelegateHistoryWriter writer = mock(IDelegateHistoryWriter.class);
    org.mockito.Mockito.doAnswer(
            i -> {
              history.add(i.getArgument(0));
              return null;
            })
        .when(writer)
        .addChildToEvent(anyString());
    org.mockito.Mockito.doAnswer(
            i -> {
              history.add(i.getArgument(0));
              return null;
            })
        .when(writer)
        .addChildToEvent(anyString(), any());
    org.mockito.Mockito.doAnswer(
            i -> {
              history.add(i.getArgument(0));
              return null;
            })
        .when(writer)
        .startEvent(anyString());
    org.mockito.Mockito.doAnswer(
            i -> {
              history.add(i.getArgument(0));
              return null;
            })
        .when(writer)
        .startEvent(anyString(), any());
    when(bridge.getHistoryWriter()).thenReturn(writer);
    when(bridge.getRandom(anyInt(), any(), any(), anyString())).thenReturn(5);
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
            invocation -> new CasualtyDetails(invocation.<CasualtyList>getArgument(10), true));
    return bridge;
  }
}
