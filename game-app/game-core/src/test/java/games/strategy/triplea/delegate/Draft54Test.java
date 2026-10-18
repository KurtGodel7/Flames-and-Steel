package games.strategy.triplea.delegate;

import static games.strategy.triplea.delegate.MockDelegateBridge.advanceToStep;
import static games.strategy.triplea.delegate.MockDelegateBridge.newDelegateBridge;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.UnitType;
import games.strategy.engine.data.gameparser.GameParser;
import games.strategy.engine.delegate.IDelegateBridge;
import games.strategy.triplea.Properties;
import games.strategy.triplea.attachments.PlayerAttachment;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Flames and Steel requests 9 and 10 on the installed map (draft 54 or later). Skipped when the map
 * is not installed.
 */
class Draft54Test {
  private static final Path XML =
      Path.of(
          System.getProperty("user.home"),
          "triplea/downloadedMaps/flames_and_steel/map/games/flames_and_steel.xml");
  private GameData data;
  private GamePlayer germans;

  @BeforeEach
  void setUp() {
    Assumptions.assumeTrue(Files.exists(XML), "Flames and Steel map not installed");
    data = GameParser.parse(XML, false).orElseThrow();
    Assumptions.assumeTrue(
        Properties.getUnplacedUnitsLive(data.getProperties()), "map lets unplaced units die");
    germans = data.getPlayerList().getPlayerId("Germans");
  }

  private UnitType type(final String name) {
    return data.getUnitTypeList().getUnitType(name).orElseThrow();
  }

  // --- request 9 item 3: aircraft sail with their carriers

  /** Two empty sea zones next to each other in different fronts that Japan may enter. */
  private games.strategy.engine.data.Territory[] seaZonesAcrossFronts(final GamePlayer japanese) {
    for (final var a : data.getMap().getTerritories()) {
      if (!a.isWater() || !a.getUnits().isEmpty()) {
        continue;
      }
      final var frontA =
          games.strategy.triplea.delegate.air.FleetAircraft.frontOf(a, data.getMap());
      for (final var b : data.getMap().getNeighbors(a)) {
        if (!b.isWater() || !b.getUnits().isEmpty()) {
          continue;
        }
        final var frontB =
            games.strategy.triplea.delegate.air.FleetAircraft.frontOf(b, data.getMap());
        if (frontA.isPresent()
            && frontB.isPresent()
            && !frontA.equals(frontB)
            && games.strategy.triplea.delegate.air.AirAllocation.mayEnter(japanese, frontA.get())
            && games.strategy.triplea.delegate.air.AirAllocation.mayEnter(japanese, frontB.get())) {
          return new games.strategy.engine.data.Territory[] {a, b};
        }
      }
    }
    throw new IllegalStateException("no sea zones across two fronts");
  }

  @Test
  void aircraftSailWithCarriersIntoTheNewFrontAndUndoBringsThemBack() {
    final GamePlayer japanese = data.getPlayerList().getPlayerId("Japanese");
    final var zones = seaZonesAcrossFronts(japanese);
    final var from =
        games.strategy.triplea.delegate.air.FleetAircraft.frontOf(zones[0], data.getMap())
            .orElseThrow();
    final var to =
        games.strategy.triplea.delegate.air.FleetAircraft.frontOf(zones[1], data.getMap())
            .orElseThrow();
    final var carrier = type("carrier").create(1, japanese); // room for 2
    data.performChange(
        games.strategy.engine.data.changefactory.ChangeFactory.addUnits(zones[0], carrier));
    final var aircraft = new java.util.ArrayList<games.strategy.engine.data.Unit>();
    aircraft.addAll(type("diveBomber").create(1, japanese));
    aircraft.addAll(type("fighter").create(2, japanese));
    data.performChange(
        games.strategy.engine.data.changefactory.ChangeFactory.removeUnits(
            from, from.getUnitCollection().getMatches(u -> u.isOwnedBy(japanese))));
    data.performChange(
        games.strategy.engine.data.changefactory.ChangeFactory.addUnits(from, aircraft));
    data.performChange(
        games.strategy.engine.data.changefactory.ChangeFactory.removeUnits(
            to, to.getUnitCollection().getMatches(u -> u.isOwnedBy(japanese))));

    final IDelegateBridge bridge = newDelegateBridge(japanese);
    // the player accepts the suggestion
    org.mockito.Mockito.when(
            bridge
                .getRemotePlayer()
                .selectFleetAircraft(
                    org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.anyString()))
        .thenAnswer(i -> i.getArgument(1));
    advanceToStep(bridge, "japaneseNonCombatMove");
    final MoveDelegate move = (MoveDelegate) data.getDelegate("move");
    move.setDelegateBridgeAndPlayer(bridge);
    move.start();
    final var error =
        move.performMove(
            new games.strategy.engine.data.MoveDescription(
                carrier, new games.strategy.engine.data.Route(zones[0], zones[1])));
    assertThat(error.orElse(""), error.isEmpty(), is(true));

    // fighters before bombers: the carrier's 2 spaces take both fighters
    assertThat(
        to.getUnitCollection()
            .countMatches(u -> u.isOwnedBy(japanese) && u.getType().getName().equals("fighter")),
        is(2));
    assertThat(
        from.getUnitCollection()
            .countMatches(u -> u.isOwnedBy(japanese) && u.getType().getName().equals("diveBomber")),
        is(1));

    move.undoMove(0);
    assertThat(from.getUnitCollection().countMatches(u -> u.isOwnedBy(japanese)), is(3));
    assertThat(to.getUnitCollection().countMatches(u -> u.isOwnedBy(japanese)), is(0));
    move.end();
  }

  @Test
  void jetsGoOnlyWithFleetCarriers() {
    final GamePlayer japanese = data.getPlayerList().getPlayerId("Japanese");
    final var available = new org.triplea.java.collections.IntegerMap<UnitType>();
    available.put(type("jet"), 3);
    available.put(type("fighter"), 3);
    // a carrier (2) and a fleet carrier (4): jets only on the fleet carrier
    final var suggested =
        games.strategy.triplea.delegate.air.FleetAircraftTestAccess.suggest(available, 6, 4);
    assertThat(suggested.getInt(type("jet")), is(3));
    assertThat(suggested.getInt(type("fighter")), is(3));
    final var noFleet =
        games.strategy.triplea.delegate.air.FleetAircraftTestAccess.suggest(available, 2, 0);
    assertThat(noFleet.getInt(type("jet")), is(0));
    assertThat(noFleet.getInt(type("fighter")), is(2));
  }

  // --- request 9 item 2: unplaced units carry over

  @Test
  void unplacedUnitsStayAndShowAsCarriedOverNextTurn() {
    germans.getUnitCollection().addAll(type("infantry").create(6, germans));
    germans.getUnitCollection().addAll(type("conscript").create(4, germans));

    final IDelegateBridge bridge = newDelegateBridge(germans);
    advanceToStep(bridge, "germanPlace");
    final PlaceDelegate place = new PlaceDelegate();
    place.initialize("place");
    place.setDelegateBridgeAndPlayer(bridge);
    place.start();
    place.end(); // nothing placed
    assertThat(germans.getUnitCollection().size(), is(10));

    advanceToStep(bridge, "germanPurchase");
    final PurchaseDelegate purchase = new PurchaseDelegate();
    purchase.initialize("purchase", "Purchase");
    purchase.setDelegateBridgeAndPlayer(bridge);
    purchase.start();
    final var carried = PlayerAttachment.get(germans).getCarriedOverUnits();
    assertThat(carried.totalValues(), is(10));
    assertThat(carried.getInt("infantry"), is(6));
    assertThat(carried.getInt("conscript"), is(4));
    purchase.end();
  }
}
