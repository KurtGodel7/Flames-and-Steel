package games.strategy.triplea.delegate.air;

import static games.strategy.triplea.delegate.GameDataTestUtil.addTo;
import static games.strategy.triplea.delegate.GameDataTestUtil.removeFrom;
import static games.strategy.triplea.delegate.MockDelegateBridge.advanceToStep;
import static games.strategy.triplea.delegate.MockDelegateBridge.newDelegateBridge;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.gameparser.GameParser;
import games.strategy.engine.delegate.IDelegateBridge;
import games.strategy.engine.history.IDelegateHistoryWriter;
import games.strategy.triplea.delegate.AirCombatDelegate;
import games.strategy.triplea.delegate.InitializationDelegate;
import games.strategy.triplea.delegate.TechAdvance;
import games.strategy.triplea.delegate.TechTracker;
import games.strategy.triplea.delegate.data.CasualtyDetails;
import games.strategy.triplea.delegate.data.CasualtyList;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Flames and Steel request 13 on the installed map: in a dogfight every aircraft fires at its
 * dogfight value (plus dogfight bonuses), never at its strike value, on the dice and on the battle
 * board. Skipped when the map is not installed.
 */
class DogfightValuesTest {
  private static final Path XML =
      Path.of(
          System.getProperty("user.home"),
          "triplea/downloadedMaps/flames_and_steel/map/games/flames_and_steel.xml");
  private static final List<String> JAPANESE_TYPES =
      List.of("fighter", "diveBomber", "torpedoBomber", "mediumBomber");
  private GameData data;
  private GamePlayer japanese;
  private GamePlayer chinese;
  private GamePlayer russians;
  private Territory airChina;

  @BeforeEach
  void setUp() {
    Assumptions.assumeTrue(Files.exists(XML), "Flames and Steel map not installed");
    data = GameParser.parse(XML, false).orElseThrow();
    japanese = data.getPlayerList().getPlayerId("Japanese");
    chinese = data.getPlayerList().getPlayerId("Chinese");
    russians = data.getPlayerList().getPlayerId("Russians");
    airChina = data.getMap().getTerritoryOrThrow("Air - China");
    final InitializationDelegate start = new InitializationDelegate();
    start.initialize("initDelegate", "Initializing Delegates");
    start.setDelegateBridgeAndPlayer(newDelegateBridge(japanese));
    start.start();
    start.end();
    removeFrom(airChina, airChina.getUnits());
  }

  /** One fighter, one dive bomber, one torpedo bomber and one medium bomber, in that order. */
  private List<Unit> japaneseAircraft() {
    final List<Unit> units = new ArrayList<>();
    for (final String type : JAPANESE_TYPES) {
      units.addAll(data.getUnitTypeList().getUnitTypeOrThrow(type).create(1, japanese));
    }
    addTo(airChina, units);
    return units;
  }

  private List<Integer> dogfightValues(final List<Unit> units) {
    return units.stream().map(AirCombat::dogfightValue).toList();
  }

  /** The battle board columns in a dogfight on this front, against these enemies. */
  private List<Integer> boardColumns(final List<Unit> units, final List<Unit> enemies) {
    final boolean dogfight = AirCombat.isDogfight(airChina);
    return units.stream().map(u -> AirCombat.displayStrength(u, dogfight, enemies)).toList();
  }

  private void grant(final GamePlayer player, final String techName) {
    final TechAdvance tech = data.getTechnologyFrontier().getAdvanceByName(techName);
    TechTracker.addAdvance(player, newDelegateBridge(player), tech);
    data.getTechTracker().clearCache();
  }

  @Test
  void dogfightRollsAtDogfightValues() {
    final List<Unit> aircraft = japaneseAircraft();

    assertThat(dogfightValues(aircraft), is(List.of(3, 1, 1, 1)));
    assertThat(aircraft.stream().map(AirCombat::dogfightPower).toList(), is(List.of(3, 1, 1, 1)));
  }

  @Test
  void theBattleBoardShowsDogfightValuesEvenOnceTheEnemyIsGone() {
    final List<Unit> aircraft = japaneseAircraft();
    final List<Unit> chineseFighters =
        data.getUnitTypeList().getUnitTypeOrThrow("fighter").create(2, chinese);

    assertThat(boardColumns(aircraft, chineseFighters), is(List.of(3, 1, 1, 1)));
    // the bug: with every enemy shot down the board showed land strike values 3, 6, 3, 3
    assertThat(boardColumns(aircraft, List.of()), is(List.of(3, 1, 1, 1)));
  }

  @Test
  void aRealDogfightFiresWithTheSumOfDogfightValues() {
    japaneseAircraft();
    addTo(airChina, data.getUnitTypeList().getUnitTypeOrThrow("fighter").create(1, chinese));
    final AirCombatDelegate delegate = (AirCombatDelegate) data.getDelegate("airCombat");
    // request 30: both nations put their fighters over one territory in reach of both
    final games.strategy.engine.data.UnitType fighter =
        data.getUnitTypeList().getUnitTypeOrThrow("fighter");
    final Territory over =
        AirCover.places(japanese, airChina, fighter, java.util.Map.of()).stream()
            .filter(t -> AirCover.places(chinese, airChina, fighter, java.util.Map.of()).contains(t))
            .findFirst()
            .orElseThrow();
    for (final GamePlayer p : List.of(japanese, chinese)) {
      final String step = p.getName() + "AirCover";
      data.getSequence()
          .addStep(
              new games.strategy.engine.data.GameStep(
                  step, step, p, delegate, data, new java.util.Properties()));
      final IDelegateBridge coverBridge = bridge(p);
      advanceToStep(coverBridge, step);
      delegate.setDelegateBridgeAndPlayer(coverBridge);
      delegate.start();
      assertThat(
          delegate.assignTargets(
              List.of(
                  new AirCombat.TargetAssignment(airChina, fighter, 1, AirCover.over(over)))),
          is((String) null));
      delegate.end();
    }
    final IDelegateBridge bridge = bridge(japanese);
    final IDelegateHistoryWriter history = mock(IDelegateHistoryWriter.class);
    when(bridge.getHistoryWriter()).thenReturn(history);
    // request 27: the air battle is fought in the strike step
    advanceToStep(bridge, "airStrikes");
    delegate.setDelegateBridgeAndPlayer(bridge);
    delegate.start();
    delegate.end();

    final ArgumentCaptor<String> lines = ArgumentCaptor.forClass(String.class);
    verify(history, atLeastOnce()).addChildToEvent(lines.capture());
    // request 27: only fighters dogfight: the Japanese fighter's 3, not 3 + 1 + 1 + 1
    assertThat(lines.getAllValues(), hasItem("Japanese fire with total value 3: 0 hits"));
    assertThat(lines.getAllValues(), hasItem("Chinese fire with total value 3: 0 hits"));
  }

  @Test
  void radiosRaisesFightersOnly() {
    // request 35: Modern Fighters and Radios became Radios
    grant(japanese, "Radios");
    final List<Unit> aircraft = japaneseAircraft();

    assertThat(dogfightValues(aircraft), is(List.of(4, 1, 1, 1)));
    assertThat(boardColumns(aircraft, List.of()), is(List.of(4, 1, 1, 1)));
  }

  @Test
  void sovietFightersFireAtOneUntilPilotTrainingAndRadios() {
    // request 35: the Soviet penalty is -2; Pilot Training and Radios give +1 each
    final Unit fighter =
        data.getUnitTypeList().getUnitTypeOrThrow("fighter").create(1, russians).get(0);

    assertThat(AirCombat.dogfightValue(fighter), is(1));
    grant(russians, "PilotTraining");
    assertThat(AirCombat.dogfightValue(fighter), is(2));
    grant(russians, "Radios");
    assertThat(AirCombat.dogfightValue(fighter), is(3));
  }

  @Test
  void tacticalStrikesStillDouble() {
    final List<Unit> aircraft = japaneseAircraft();
    final Unit dive = aircraft.get(1);
    final Unit torpedo = aircraft.get(2);
    final Unit medium = aircraft.get(3);
    final int diveAttack = dive.getUnitAttachment().getAttack(japanese);
    final int torpedoAttack = torpedo.getUnitAttachment().getAttack(japanese);
    final int mediumAttack = medium.getUnitAttachment().getAttack(japanese);

    assertThat(AirCombat.strikePower(dive, AirCombat.TargetKind.LAND), is(2 * diveAttack));
    assertThat(AirCombat.strikePower(torpedo, AirCombat.TargetKind.SURFACE), is(2 * torpedoAttack));
    assertThat(AirCombat.strikePower(torpedo, AirCombat.TargetKind.SUBS), is(torpedoAttack));
    assertThat(AirCombat.strikePower(medium, AirCombat.TargetKind.SUBS), is(mediumAttack));
  }

  /** Remainder dice always miss; players take the offered casualties. */
  private static IDelegateBridge bridge(final GamePlayer player) {
    final IDelegateBridge bridge = newDelegateBridge(player);
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
