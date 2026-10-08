package games.strategy.triplea.delegate.air;

import static games.strategy.triplea.delegate.GameDataTestUtil.addTo;
import static games.strategy.triplea.delegate.GameDataTestUtil.bomber;
import static games.strategy.triplea.delegate.GameDataTestUtil.british;
import static games.strategy.triplea.delegate.GameDataTestUtil.carrier;
import static games.strategy.triplea.delegate.GameDataTestUtil.fighter;
import static games.strategy.triplea.delegate.GameDataTestUtil.germans;
import static games.strategy.triplea.delegate.GameDataTestUtil.removeFrom;
import static games.strategy.triplea.delegate.GameDataTestUtil.territory;
import static games.strategy.triplea.delegate.MockDelegateBridge.advanceToStep;
import static games.strategy.triplea.delegate.MockDelegateBridge.newDelegateBridge;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import games.strategy.engine.data.DefaultAttachment;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameStep;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.UnitType;
import games.strategy.engine.delegate.IDelegateBridge;
import games.strategy.triplea.attachments.AirUnitAttachment;
import games.strategy.triplea.attachments.AirZoneAttachment;
import games.strategy.triplea.delegate.AirAllocationDelegate;
import games.strategy.triplea.delegate.air.AirAllocation.AirMove;
import games.strategy.triplea.delegate.air.AirAllocation.Stage;
import games.strategy.triplea.xml.TestMapGameData;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Flames and Steel air allocation. Sea zones of the test map stand in for zone boxes: a reserve,
 * two adjacent land fronts A and B, a land front C not next to A, and an open-ocean zone covering 6
 * Sea Zone.
 */
class AirAllocationTest {
  private final GameData gameData = TestMapGameData.REVISED.getGameData();
  private final GamePlayer british = british(gameData);
  private final Territory reserve = territory("1 Sea Zone", gameData);
  private final Territory frontA = territory("2 Sea Zone", gameData);
  private final Territory frontB = territory("3 Sea Zone", gameData);
  private final Territory frontC = territory("4 Sea Zone", gameData);
  private final Territory ocean = territory("7 Sea Zone", gameData);
  private final Territory oceanSeaZone = territory("6 Sea Zone", gameData);
  private UnitType fighter;
  private UnitType bomber;

  @BeforeEach
  void setUp() {
    for (final Territory t : List.of(reserve, frontA, frontB, frontC, ocean, oceanSeaZone)) {
      removeFrom(t, t.getUnits());
    }
    zone(reserve, "zoneType", "reserve");
    zone(frontA, "zoneType", "land");
    zone(frontB, "zoneType", "land");
    zone(frontC, "zoneType", "land");
    zone(ocean, "zoneType", "ocean");
    zone(frontA, "adjacentZones", frontB.getName());
    zone(frontB, "adjacentZones", frontA.getName());
    zone(ocean, "coveredTerritories", oceanSeaZone.getName());
    fighter = fighter(gameData);
    bomber = bomber(gameData);
    airUnit(fighter, "fighter", "true");
    addTo(frontA, fighter.create(3, british));
    addTo(frontA, bomber.create(2, british));
    addTo(reserve, bomber.create(1, british));
  }

  @Test
  void adjacentMoveIsAllowed() {
    assertThat(valid(Stage.BOMBERS, new AirMove(frontA, frontB, bomber, 2)), is(true));
  }

  @Test
  void nonAdjacentMoveMustGoThroughTheReserve() {
    assertThat(valid(Stage.BOMBERS, new AirMove(frontA, frontC, bomber, 1)), is(false));
    assertThat(valid(Stage.BOMBERS, new AirMove(frontA, reserve, bomber, 1)), is(true));
    assertThat(valid(Stage.BOMBERS, new AirMove(reserve, frontC, bomber, 1)), is(true));
  }

  @Test
  void zoneNamingNationsRefusesOthers() {
    zone(frontB, "players", germans(gameData).getName());

    assertThat(valid(Stage.BOMBERS, new AirMove(frontA, frontB, bomber, 1)), is(false));
    assertThat(
        AirAllocation.getDestinations(british, frontA, gameData.getMap()).contains(frontB),
        is(false));
  }

  @Test
  void cannotMoveMoreAircraftThanAreThere() {
    assertThat(
        valid(
            Stage.BOMBERS,
            new AirMove(frontA, frontB, bomber, 1),
            new AirMove(frontA, reserve, bomber, 2)),
        is(false));
  }

  @Test
  void fightersMoveOnlyInTheFighterStage() {
    assertThat(valid(Stage.BOMBERS, new AirMove(frontA, frontB, fighter, 1)), is(false));
    assertThat(valid(Stage.FIGHTERS, new AirMove(frontA, frontB, fighter, 1)), is(true));
    assertThat(valid(Stage.FIGHTERS, new AirMove(frontA, frontB, bomber, 1)), is(false));
  }

  @Test
  void openOceanNeedsOwnCarrierCapacity() {
    zone(ocean, "adjacentZones", frontA.getName());
    zone(frontA, "adjacentZones", ocean.getName());

    // no carriers: nothing may go
    assertThat(valid(Stage.FIGHTERS, new AirMove(frontA, ocean, fighter, 1)), is(false));

    addTo(oceanSeaZone, carrier(gameData).create(1, british));
    final int capacity = carrier(gameData).getUnitAttachment().getCarrierCapacity();
    assertThat(valid(Stage.FIGHTERS, new AirMove(frontA, ocean, fighter, capacity)), is(true));
    assertThat(valid(Stage.FIGHTERS, new AirMove(frontA, ocean, fighter, capacity + 1)), is(false));
    // bombers that cannot land on carriers may not go
    assertThat(valid(Stage.BOMBERS, new AirMove(frontA, ocean, bomber, 1)), is(false));
  }

  @Test
  void enemyCarriersDoNotCount() {
    zone(ocean, "adjacentZones", frontA.getName());
    zone(frontA, "adjacentZones", ocean.getName());
    addTo(oceanSeaZone, carrier(gameData).create(1, germans(gameData)));

    assertThat(valid(Stage.FIGHTERS, new AirMove(frontA, ocean, fighter, 1)), is(false));
  }

  @Test
  void jetsNeedFleetCarriers() {
    zone(ocean, "adjacentZones", frontA.getName());
    zone(frontA, "adjacentZones", ocean.getName());
    airUnit(fighter, "fleetCarrierOnly", "true");
    addTo(oceanSeaZone, carrier(gameData).create(1, british));

    assertThat(valid(Stage.FIGHTERS, new AirMove(frontA, ocean, fighter, 1)), is(false));

    airUnit(carrier(gameData), "fleetCarrier", "true");
    assertThat(valid(Stage.FIGHTERS, new AirMove(frontA, ocean, fighter, 1)), is(true));
  }

  @Test
  void movesAreCarriedOut() {
    gameData.performChange(
        AirAllocation.toChange(
            british,
            Stage.BOMBERS,
            List.of(
                new AirMove(frontA, frontB, bomber, 1), new AirMove(frontA, reserve, bomber, 1))));

    assertThat(AirAllocation.getAircraft(british, frontA, Stage.BOMBERS).size(), is(0));
    assertThat(AirAllocation.getAircraft(british, frontB, Stage.BOMBERS).size(), is(1));
    assertThat(AirAllocation.getAircraft(british, reserve, Stage.BOMBERS).size(), is(2));
    assertThat(AirAllocation.getAircraft(british, frontA, Stage.FIGHTERS).size(), is(3));
  }

  @Test
  void movesStayHiddenUntilTheRevealStep() {
    final GamePlayer germans = germans(gameData);
    addTo(frontA, bomber.create(1, germans));
    final AirAllocationDelegate delegate = new AirAllocationDelegate();
    delegate.initialize("airAllocation", "Air allocation");
    for (final String step : List.of("britishAirBombers", "germanAirBombers", "airRevealBombers")) {
      gameData
          .getSequence()
          .addStep(
              new GameStep(
                  step,
                  step,
                  step.startsWith("british") ? british : germans,
                  delegate,
                  gameData,
                  new Properties()));
    }

    runStep(delegate, "britishAirBombers", british, new AirMove(frontA, frontB, bomber, 2));
    runStep(delegate, "germanAirBombers", germans, new AirMove(frontA, reserve, bomber, 1));

    // nothing has moved yet
    assertThat(AirAllocation.getAircraft(british, frontA, Stage.BOMBERS).size(), is(2));
    assertThat(AirAllocation.getAircraft(germans, frontA, Stage.BOMBERS).size(), is(1));

    runStep(delegate, "airRevealBombers", germans);

    assertThat(AirAllocation.getAircraft(british, frontB, Stage.BOMBERS).size(), is(2));
    assertThat(AirAllocation.getAircraft(germans, reserve, Stage.BOMBERS).size(), is(1));
  }

  private void runStep(
      final AirAllocationDelegate delegate,
      final String step,
      final GamePlayer player,
      final AirMove... moves) {
    final IDelegateBridge bridge = newDelegateBridge(player);
    advanceToStep(bridge, step);
    delegate.setDelegateBridgeAndPlayer(bridge);
    delegate.start();
    if (moves.length > 0) {
      assertThat(delegate.allocate(List.of(moves)), is(nullValue()));
    }
    delegate.end();
  }

  private boolean valid(final Stage stage, final AirMove... moves) {
    return AirAllocation.validate(british, stage, List.of(moves), gameData.getMap()).isEmpty();
  }

  private void zone(final Territory territory, final String property, final String value) {
    set(
        AirZoneAttachment.get(territory)
            .orElseGet(
                () -> {
                  final AirZoneAttachment a =
                      new AirZoneAttachment(AirZoneAttachment.ATTACHMENT_NAME, territory, gameData);
                  territory.addAttachment(AirZoneAttachment.ATTACHMENT_NAME, a);
                  return a;
                }),
        property,
        value);
  }

  private void airUnit(final UnitType type, final String property, final String value) {
    set(
        AirUnitAttachment.get(type)
            .orElseGet(
                () -> {
                  final AirUnitAttachment a =
                      new AirUnitAttachment(AirUnitAttachment.ATTACHMENT_NAME, type, gameData);
                  type.addAttachment(AirUnitAttachment.ATTACHMENT_NAME, a);
                  return a;
                }),
        property,
        value);
  }

  private static void set(
      final DefaultAttachment attachment, final String property, final String value) {
    try {
      attachment.getPropertyOrThrow(property).setValue(value);
    } catch (final Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
