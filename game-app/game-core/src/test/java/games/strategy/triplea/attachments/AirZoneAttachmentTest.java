package games.strategy.triplea.attachments;

import static games.strategy.triplea.delegate.GameDataTestUtil.addTo;
import static games.strategy.triplea.delegate.GameDataTestUtil.british;
import static games.strategy.triplea.delegate.GameDataTestUtil.carrier;
import static games.strategy.triplea.delegate.GameDataTestUtil.fighter;
import static games.strategy.triplea.delegate.GameDataTestUtil.germans;
import static games.strategy.triplea.delegate.GameDataTestUtil.removeFrom;
import static games.strategy.triplea.delegate.GameDataTestUtil.territory;
import static games.strategy.triplea.delegate.MockDelegateBridge.newDelegateBridge;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertThrows;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.engine.data.gameparser.GameParseException;
import games.strategy.triplea.delegate.AirReserve;
import games.strategy.triplea.delegate.AirThatCantLandUtilAccess;
import games.strategy.triplea.delegate.battle.BattleDelegate;
import games.strategy.triplea.delegate.battle.BattleTracker;
import games.strategy.triplea.xml.TestMapGameData;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Flames and Steel air zones. Two sea zones of the test map stand in for zone boxes. */
class AirZoneAttachmentTest {
  private final GameData gameData = TestMapGameData.REVISED.getGameData();
  private final Territory reserve = territory("1 Sea Zone", gameData);
  private final Territory front = territory("2 Sea Zone", gameData);

  @BeforeEach
  void setUp() {
    removeFrom(reserve, reserve.getUnits());
    removeFrom(front, front.getUnits());
    makeZone(reserve, "reserve");
    makeZone(front, "land");
  }

  @Test
  void findsZonesAndReserve() {
    assertThat(AirZoneAttachment.isAirZone(front), is(true));
    assertThat(AirZoneAttachment.getReserve(gameData.getMap()).orElseThrow(), is(reserve));
    assertThat(AirZoneAttachment.isAirZone(territory("Germany", gameData)), is(false));
  }

  @Test
  void adjacencyMustGoBothWays() throws Exception {
    set(front, "adjacentZones", reserve.getName());

    assertThrows(
        GameParseException.class,
        () -> AirZoneAttachment.get(front).orElseThrow().validate(gameData));

    set(reserve, "adjacentZones", front.getName());
    AirZoneAttachment.get(front).orElseThrow().validate(gameData);
  }

  @Test
  void enemiesSharingAnAirZoneDoNotStartABattle() {
    addTo(front, fighter(gameData).create(2, germans(gameData)));
    addTo(front, fighter(gameData).create(2, british(gameData)));
    final BattleTracker tracker = new BattleTracker();

    BattleDelegate.doInitialize(tracker, newDelegateBridge(germans(gameData)));

    assertThat(tracker.getPendingBattleSitesWithoutBombing(), not(hasItem(front)));
  }

  @Test
  void aircraftInAirZonesNeverNeedToLand() {
    addTo(front, fighter(gameData).create(2, germans(gameData)));

    assertThat(
        AirThatCantLandUtilAccess.territoriesWhereAirCantLand(
            newDelegateBridge(germans(gameData)), germans(gameData)),
        not(hasItem(front)));
  }

  @Test
  void aircraftOnTheMapGoToTheReserve() {
    final Territory germany = territory("Germany", gameData);
    final Territory seaZone = territory("5 Sea Zone", gameData);
    final List<Unit> placed = fighter(gameData).create(2, germans(gameData));
    addTo(germany, placed);
    final Unit carrierUnit = carrier(gameData).create(1, germans(gameData)).get(0);
    final Unit onCarrier = fighter(gameData).create(1, germans(gameData)).get(0);
    addTo(seaZone, List.of(carrierUnit, onCarrier));
    gameData.performChange(
        ChangeFactory.unitPropertyChange(onCarrier, carrierUnit, Unit.PropertyName.TRANSPORTED_BY));

    AirReserve.sendAircraftToReserve(newDelegateBridge(germans(gameData)), germans(gameData));

    assertThat(reserve.getUnits().containsAll(placed), is(true));
    assertThat(reserve.getUnits().contains(onCarrier), is(true));
    assertThat(onCarrier.getTransportedBy() == null, is(true));
    assertThat(seaZone.getUnits().contains(carrierUnit), is(true));
    assertThat(germany.getUnits().stream().anyMatch(placed::contains), is(false));
  }

  private void makeZone(final Territory territory, final String type) {
    final AirZoneAttachment attachment =
        new AirZoneAttachment(AirZoneAttachment.ATTACHMENT_NAME, territory, gameData);
    territory.addAttachment(AirZoneAttachment.ATTACHMENT_NAME, attachment);
    set(territory, "zoneType", type);
  }

  private static void set(final Territory territory, final String property, final String value) {
    try {
      AirZoneAttachment.get(territory).orElseThrow().getPropertyOrThrow(property).setValue(value);
    } catch (final Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
