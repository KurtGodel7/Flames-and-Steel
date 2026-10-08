package games.strategy.triplea.delegate.air;

import static games.strategy.triplea.delegate.GameDataTestUtil.addTo;
import static games.strategy.triplea.delegate.GameDataTestUtil.bomber;
import static games.strategy.triplea.delegate.GameDataTestUtil.british;
import static games.strategy.triplea.delegate.GameDataTestUtil.carrier;
import static games.strategy.triplea.delegate.GameDataTestUtil.fighter;
import static games.strategy.triplea.delegate.GameDataTestUtil.germans;
import static games.strategy.triplea.delegate.GameDataTestUtil.removeFrom;
import static games.strategy.triplea.delegate.GameDataTestUtil.russians;
import static games.strategy.triplea.delegate.GameDataTestUtil.territory;
import static games.strategy.triplea.delegate.MockDelegateBridge.advanceToStep;
import static games.strategy.triplea.delegate.MockDelegateBridge.newDelegateBridge;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import games.strategy.engine.data.DefaultAttachment;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameStep;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.engine.delegate.IDelegateBridge;
import games.strategy.triplea.attachments.AirUnitAttachment;
import games.strategy.triplea.attachments.AirZoneAttachment;
import games.strategy.triplea.delegate.AirCarrierCheckDelegate;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.air.AirAllocation.AirMove;
import games.strategy.triplea.delegate.air.AirAllocation.Stage;
import games.strategy.triplea.delegate.air.AirCombat.Target;
import games.strategy.triplea.delegate.air.AirCombat.TargetKind;
import games.strategy.triplea.xml.TestMapGameData;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Flames and Steel air range and carrier check, on the Revised test map with real distances. */
class AirRangeTest {
  private final GameData gameData = TestMapGameData.REVISED.getGameData();
  private final GamePlayer british = british(gameData);
  private final GamePlayer germans = germans(gameData);
  // zone boxes (unused sea zones far from the action)
  private final Territory reserve = territory("60 Sea Zone", gameData);
  private final Territory boxA = territory("61 Sea Zone", gameData);
  private final Territory boxB = territory("62 Sea Zone", gameData);
  private final Territory boxC = territory("63 Sea Zone", gameData);
  private UnitType fighter;
  private UnitType bomber;

  @BeforeEach
  void setUp() {
    for (final Territory t : List.of(reserve, boxA, boxB, boxC)) {
      removeFrom(t, t.getUnits());
    }
    zone(reserve, "zoneType", "reserve");
    fighter = fighter(gameData);
    bomber = bomber(gameData);
    air(fighter, "fighter", "true");
  }

  @Test
  void landTargetMustBeWithinHalfTheRangeOfFriendlyLand() {
    final Territory karelia = territory("Karelia S.S.R.", gameData);
    zone(boxA, "zoneType", "land");
    final int d = distanceToLand(germans, karelia);

    assertThat(AirRange.canReach(germans, boxA, bomber, 2 * d, karelia), is(true));
    assertThat(AirRange.canReach(germans, boxA, bomber, 2 * d - 2, karelia), is(false));
  }

  @Test
  void escortsLimitAStrategicRaidToTheShortestRange() {
    final Territory germany = territory("Germany", gameData);
    zone(boxA, "zoneType", "bomb");
    zone(boxA, "bombedPlayer", germans.getName());
    final int d = distanceToLand(british, germany);
    movement(bomber, 2 * d);
    movement(fighter, 2 * d - 2);
    air(bomber, "bombingDamage", "5");
    addTo(boxA, bomber.create(2, british));
    final Target raid = new Target(germany, TargetKind.STRATEGIC);

    assertThat(AirCombat.mayAttack(british, boxA, bomber, raid), is(true));

    addTo(boxA, fighter.create(1, british));
    assertThat(AirCombat.mayAttack(british, boxA, bomber, raid), is(false));
  }

  @Test
  void relationshipCanForbidLandingOnAlliedLand() {
    final Territory russianLand =
        gameData.getMap().getTerritoriesOwnedBy(russians(gameData)).stream()
            .filter(t -> !t.isWater())
            .findFirst()
            .orElseThrow();

    assertThat(AirRange.isLandLandingSpot(british, russianLand), is(true));

    final var relationship =
        gameData
            .getRelationshipTracker()
            .getRelationshipType(british, russians(gameData))
            .getRelationshipTypeAttachment();
    set(relationship, "canLandAirUnitsOnOwnedLand", "false");
    assertThat(AirRange.isLandLandingSpot(british, russianLand), is(false));
  }

  @Test
  void openOceanStrikesFlyFromOwnCarriersOnly() {
    final Territory seaZone = territory("7 Sea Zone", gameData); // next to the UK
    removeFrom(seaZone, seaZone.getUnits());
    zone(boxA, "zoneType", "ocean");
    zone(boxA, "coveredTerritories", seaZone.getName());

    // British land nearby does not help over open ocean
    assertThat(AirRange.canReach(british, boxA, fighter, 4, seaZone), is(false));

    addTo(seaZone, carrier(gameData).create(1, british));
    assertThat(AirRange.canReach(british, boxA, fighter, 4, seaZone), is(true));

    // jets need fleet carriers
    air(fighter, "fleetCarrierOnly", "true");
    assertThat(AirRange.canReach(british, boxA, fighter, 4, seaZone), is(false));
  }

  @Test
  void coastalZoneNeedsLandInRangeOrCarriers() {
    final int range = AirRange.range(fighter, british);
    final Territory near = territory("7 Sea Zone", gameData);
    final Territory far =
        gameData.getMap().getTerritories().stream()
            .filter(t -> t.isWater() && !AirZoneAttachment.isAirZone(t))
            .filter(t -> !AirRange.landWithinReach(british, List.of(t), range))
            .findFirst()
            .orElseThrow();
    removeFrom(far, far.getUnits());
    zone(boxA, "zoneType", "coastal");
    zone(boxA, "coveredTerritories", near.getName());
    zone(boxB, "zoneType", "coastal");
    zone(boxB, "coveredTerritories", far.getName());
    addTo(reserve, fighter.create(2, british));

    assertThat(valid(new AirMove(reserve, boxA, fighter, 2)), is(true));
    assertThat(valid(new AirMove(reserve, boxB, fighter, 2)), is(false));

    addTo(far, carrier(gameData).create(1, british));
    assertThat(valid(new AirMove(reserve, boxB, fighter, 2)), is(true));
  }

  @Test
  void carrierCheckAircraftFollowTheirCarrierOrAreLost() {
    final Territory sea1 = territory("7 Sea Zone", gameData);
    final Territory sea2 = territory("8 Sea Zone", gameData);
    removeFrom(sea1, sea1.getUnits());
    removeFrom(sea2, sea2.getUnits());
    zone(boxA, "zoneType", "ocean");
    zone(boxA, "coveredTerritories", sea1.getName());
    zone(boxB, "zoneType", "ocean");
    zone(boxB, "coveredTerritories", sea2.getName());
    final Unit carrierUnit = carrier(gameData).create(1, british).get(0);
    final Unit carrierStaying = carrier(gameData).create(1, british).get(0);
    addTo(sea1, List.of(carrierUnit, carrierStaying));
    final int capacity = carrierUnit.getUnitAttachment().getCarrierCapacity();
    addTo(boxA, fighter.create(2 * capacity + 1, british)); // one more than both carry
    final AirCarrierCheckDelegate delegate = carrierCheckDelegate();

    run(delegate, "airCarrierSnapshot");
    gameData.performChange(ChangeFactory.moveUnits(sea1, sea2, List.of(carrierUnit)));
    run(delegate, "airCarrierCheck");

    // the moved carrier takes its share; the one that never fitted is lost
    assertThat(boxA.getUnitCollection().countMatches(Matches.unitIsAir()), is(capacity));
    assertThat(boxB.getUnitCollection().countMatches(Matches.unitIsAir()), is(capacity));
  }

  @Test
  void carrierCheckSunkCarrierTakesNothingWithIt() {
    final Territory sea1 = territory("7 Sea Zone", gameData);
    removeFrom(sea1, sea1.getUnits());
    zone(boxA, "zoneType", "ocean");
    zone(boxA, "coveredTerritories", sea1.getName());
    final Unit carrierUnit = carrier(gameData).create(1, british).get(0);
    addTo(sea1, List.of(carrierUnit));
    addTo(boxA, fighter.create(1, british));
    final AirCarrierCheckDelegate delegate = carrierCheckDelegate();

    run(delegate, "airCarrierSnapshot");
    removeFrom(sea1, List.of(carrierUnit));
    run(delegate, "airCarrierCheck");

    assertThat(boxA.getUnitCollection().countMatches(Matches.unitIsAir()), is(0));
  }

  private AirCarrierCheckDelegate carrierCheckDelegate() {
    final AirCarrierCheckDelegate delegate = new AirCarrierCheckDelegate();
    delegate.initialize("airCarrierCheck", "Carrier check");
    for (final String step : List.of("airCarrierSnapshot", "airCarrierCheck")) {
      gameData
          .getSequence()
          .addStep(new GameStep(step, step, null, delegate, gameData, new Properties()));
    }
    return delegate;
  }

  private void run(final AirCarrierCheckDelegate delegate, final String step) {
    final IDelegateBridge bridge = newDelegateBridge(british);
    advanceToStep(bridge, step);
    delegate.setDelegateBridgeAndPlayer(bridge);
    delegate.start();
    delegate.end();
  }

  private boolean valid(final AirMove... moves) {
    return AirAllocation.validate(british, Stage.FIGHTERS, List.of(moves), gameData.getMap())
        .isEmpty();
  }

  /** Fewest borders from the territory to land where the player's aircraft may land. */
  private int distanceToLand(final GamePlayer player, final Territory target) {
    for (int d = 0; d < 20; d++) {
      if (AirRange.landWithinReach(player, List.of(target), 2 * d)) {
        return d;
      }
    }
    throw new IllegalStateException("no friendly land near " + target);
  }

  private void movement(final UnitType type, final int value) {
    set(type.getUnitAttachment(), "movement", String.valueOf(value));
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

  private void air(final UnitType type, final String property, final String value) {
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
