package games.strategy.triplea.delegate.air;

import static games.strategy.triplea.delegate.GameDataTestUtil.addTo;
import static games.strategy.triplea.delegate.GameDataTestUtil.bomber;
import static games.strategy.triplea.delegate.GameDataTestUtil.british;
import static games.strategy.triplea.delegate.GameDataTestUtil.carrier;
import static games.strategy.triplea.delegate.GameDataTestUtil.factory;
import static games.strategy.triplea.delegate.GameDataTestUtil.fighter;
import static games.strategy.triplea.delegate.GameDataTestUtil.germans;
import static games.strategy.triplea.delegate.GameDataTestUtil.infantry;
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
 * Flames and Steel air allocation with fronts. Sea zones of the test map stand in for the boxes:
 * land fronts A, B (adjacent) and C (next to B only), all covering the United Kingdom (a British
 * landing spot); a carrier-only front covering 6 Sea Zone; and a bombing zone over Western Europe.
 */
class AirAllocationTest {
  private final GameData gameData = TestMapGameData.REVISED.getGameData();
  private final GamePlayer british = british(gameData);
  private final GamePlayer germans = germans(gameData);
  private final Territory frontA = territory("2 Sea Zone", gameData);
  private final Territory frontB = territory("3 Sea Zone", gameData);
  private final Territory frontC = territory("4 Sea Zone", gameData);
  private final Territory ocean = territory("7 Sea Zone", gameData);
  private final Territory oceanSeaZone = territory("6 Sea Zone", gameData);
  private final Territory bombZone = territory("8 Sea Zone", gameData);
  private final Territory unitedKingdom = territory("United Kingdom", gameData);
  private final Territory westernEurope = territory("Western Europe", gameData);
  private UnitType fighter;
  private UnitType bomber;

  @BeforeEach
  void setUp() {
    for (final Territory t : List.of(frontA, frontB, frontC, ocean, oceanSeaZone, bombZone)) {
      removeFrom(t, t.getUnits());
    }
    zone(frontA, "zoneType", "land");
    zone(frontB, "zoneType", "land");
    zone(frontC, "zoneType", "land");
    zone(ocean, "zoneType", "ocean");
    zone(bombZone, "zoneType", "bomb");
    zone(bombZone, "bombedPlayer", germans.getName());
    zone(bombZone, "coveredTerritories", westernEurope.getName());
    for (final Territory front : List.of(frontA, frontB, frontC)) {
      zone(front, "coveredTerritories", unitedKingdom.getName());
    }
    zone(ocean, "coveredTerritories", oceanSeaZone.getName());
    link(frontA, frontB);
    link(frontB, frontC);
    fighter = fighter(gameData);
    bomber = bomber(gameData);
    airUnit(fighter, "fighter", "true");
    airUnit(bomber, "bombingDamage", "5");
    addTo(frontA, fighter.create(3, british));
    addTo(frontA, bomber.create(2, british));
    // an enemy to strike in the fronts
    addTo(unitedKingdom, infantry(gameData).create(1, germans));
  }

  @Test
  void adjacentMoveIsAllowed() {
    assertThat(valid(Stage.BOMBERS, new AirMove(frontA, frontB, bomber, 2)), is(true));
  }

  @Test
  void aircraftMoveOneBoxARound() {
    assertThat(valid(Stage.BOMBERS, new AirMove(frontA, frontC, bomber, 1)), is(false));
    assertThat(
        AirAllocation.destinationProblem(
                british, Stage.BOMBERS, List.of(), frontA, bomber, 1, frontC)
            .orElseThrow(),
        is("Two boxes away: needs two rounds."));
  }

  @Test
  void boxNamingNationsRefusesOthers() {
    zone(frontB, "players", germans.getName());

    assertThat(valid(Stage.BOMBERS, new AirMove(frontA, frontB, bomber, 1)), is(false));
    assertThat(
        AirAllocation.getDestinations(british, frontA, bomber, gameData.getMap()).contains(frontB),
        is(false));
  }

  @Test
  void frontNeedsALandingSpot() {
    resetCovered(frontB, westernEurope);

    assertThat(
        AirAllocation.entryProblem(british, fighter, frontB).orElseThrow(),
        is("No landing spot in this front."));
  }

  @Test
  void bombersMayPassThroughAFrontWithNothingToAttack() {
    removeFrom(
        unitedKingdom, unitedKingdom.getUnitCollection().getMatches(u -> u.isOwnedBy(germans)));

    assertThat(valid(Stage.BOMBERS, new AirMove(frontA, frontB, bomber, 1)), is(true));
    assertThat(valid(Stage.FIGHTERS, new AirMove(frontA, frontB, fighter, 1)), is(true));
  }

  @Test
  void bomberStepMayOverloadCarriersThatFightersThenLeave() {
    link(frontA, ocean);
    set(bomber.getUnitAttachment(), "carrierCost", "1");
    addTo(oceanSeaZone, carrier(gameData).create(1, british));
    final int capacity = carrier(gameData).getUnitAttachment().getCarrierCapacity();
    addTo(ocean, fighter.create(capacity, british));
    addTo(frontA, bomber.create(capacity, british));

    // bombers alone fit: allowed though the fighters there make it too many
    assertThat(valid(Stage.BOMBERS, new AirMove(frontA, ocean, bomber, capacity)), is(true));
    assertThat(
        AirAllocation.carrierOverload(
            british, List.of(new AirMove(frontA, ocean, bomber, capacity)), ocean),
        is(capacity));
    // bombers alone do not fit: refused
    assertThat(valid(Stage.BOMBERS, new AirMove(frontA, ocean, bomber, capacity + 1)), is(false));

    // once the bombers are there, the fighter step must move the extra fighters off
    removeFrom(frontA, frontA.getUnitCollection().getMatches(u -> u.getType().equals(bomber)));
    addTo(ocean, bomber.create(capacity, british));
    assertThat(valid(Stage.FIGHTERS), is(false));
    assertThat(valid(Stage.FIGHTERS, new AirMove(ocean, frontA, fighter, capacity)), is(true));
  }

  @Test
  void cannotMoveMoreAircraftThanAreThere() {
    assertThat(valid(Stage.BOMBERS, new AirMove(frontA, frontB, bomber, 3)), is(false));
  }

  @Test
  void fightersMoveOnlyInTheFighterStage() {
    assertThat(valid(Stage.BOMBERS, new AirMove(frontA, frontB, fighter, 1)), is(false));
    assertThat(valid(Stage.FIGHTERS, new AirMove(frontA, frontB, fighter, 1)), is(true));
    assertThat(valid(Stage.FIGHTERS, new AirMove(frontA, frontB, bomber, 1)), is(false));
  }

  @Test
  void carrierOnlyFrontNeedsOwnCarrierCapacity() {
    link(frontA, ocean);

    // no carriers: no landing spot, nothing may go
    assertThat(valid(Stage.FIGHTERS, new AirMove(frontA, ocean, fighter, 1)), is(false));

    addTo(oceanSeaZone, carrier(gameData).create(1, british));
    final int capacity = carrier(gameData).getUnitAttachment().getCarrierCapacity();
    assertThat(valid(Stage.FIGHTERS, new AirMove(frontA, ocean, fighter, capacity)), is(true));
    assertThat(valid(Stage.FIGHTERS, new AirMove(frontA, ocean, fighter, capacity + 1)), is(false));
  }

  @Test
  void enemyCarriersDoNotCount() {
    link(frontA, ocean);
    addTo(oceanSeaZone, carrier(gameData).create(1, germans));

    assertThat(valid(Stage.FIGHTERS, new AirMove(frontA, ocean, fighter, 1)), is(false));
  }

  @Test
  void jetsNeedFleetCarriers() {
    link(frontA, ocean);
    airUnit(fighter, "fleetCarrierOnly", "true");
    addTo(oceanSeaZone, carrier(gameData).create(1, british));

    assertThat(valid(Stage.FIGHTERS, new AirMove(frontA, ocean, fighter, 1)), is(false));

    airUnit(carrier(gameData), "fleetCarrier", "true");
    assertThat(valid(Stage.FIGHTERS, new AirMove(frontA, ocean, fighter, 1)), is(true));
  }

  @Test
  void coastalFrontsHaveNoCarrierCap() {
    zone(frontB, "zoneType", "coastal");

    assertThat(valid(Stage.FIGHTERS, new AirMove(frontA, frontB, fighter, 3)), is(true));
  }

  @Test
  void bombingZoneTakesBombersAttackingAndFightersDefending() {
    link(frontA, bombZone);
    addTo(westernEurope, factory(gameData).create(1, germans));
    addTo(frontA, fighter.create(1, germans));
    addTo(frontA, bomber.create(1, germans));

    assertThat(valid(Stage.BOMBERS, new AirMove(frontA, bombZone, bomber, 1)), is(true));
    airUnit(fighter, "bombingDamage", "0");
    assertThat(
        AirAllocation.entryProblem(british, fighter, bombZone).orElseThrow(),
        is("No strategic bombing value."));
    assertThat(AirAllocation.entryProblem(germans, fighter, bombZone).isEmpty(), is(true));
    assertThat(
        AirAllocation.entryProblem(germans, bomber, bombZone).orElseThrow(),
        is("Only fighters defend against strategic bombing."));
  }

  @Test
  void movesAreCarriedOut() {
    gameData.performChange(
        AirAllocation.toChange(
            british, Stage.BOMBERS, List.of(new AirMove(frontA, frontB, bomber, 1))));

    assertThat(AirAllocation.getAircraft(british, frontA, Stage.BOMBERS).size(), is(1));
    assertThat(AirAllocation.getAircraft(british, frontB, Stage.BOMBERS).size(), is(1));
    assertThat(AirAllocation.getAircraft(british, frontA, Stage.FIGHTERS).size(), is(3));
  }

  @Test
  void movesStayHiddenUntilTheRevealStep() {
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
    runStep(delegate, "germanAirBombers", germans);

    // nothing has moved yet
    assertThat(AirAllocation.getAircraft(british, frontA, Stage.BOMBERS).size(), is(2));

    runStep(delegate, "airRevealBombers", germans);

    assertThat(AirAllocation.getAircraft(british, frontB, Stage.BOMBERS).size(), is(2));
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

  private void link(final Territory a, final Territory b) {
    zone(a, "adjacentZones", b.getName());
    zone(b, "adjacentZones", a.getName());
  }

  private void resetCovered(final Territory zone, final Territory only) {
    try {
      AirZoneAttachment.get(zone)
          .orElseThrow()
          .getPropertyOrThrow("coveredTerritories")
          .resetValue();
    } catch (final Exception e) {
      throw new IllegalStateException(e);
    }
    zone(zone, "coveredTerritories", only.getName());
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
