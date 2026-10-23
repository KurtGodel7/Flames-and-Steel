package games.strategy.triplea.delegate;

import static games.strategy.triplea.delegate.MockDelegateBridge.newDelegateBridge;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.MoveDescription;
import games.strategy.engine.data.Route;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.engine.data.gameparser.GameParser;
import games.strategy.triplea.delegate.air.AirAllocation;
import games.strategy.triplea.util.TransportUtils;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Request 29 part A on the installed map: a nation's transports carry only its own units. */
class OwnTransportsTest {
  private static final Path XML =
      Path.of(
          System.getProperty("user.home"),
          "triplea/downloadedMaps/flames_and_steel/map/games/flames_and_steel.xml");
  private GameData data;

  @BeforeEach
  void setUp() {
    Assumptions.assumeTrue(Files.exists(XML), "Flames and Steel map not installed");
    data = GameParser.parse(XML, false).orElseThrow();
    final InitializationDelegate start = new InitializationDelegate();
    start.initialize("initDelegate", "Initializing Delegates");
    start.setDelegateBridgeAndPlayer(newDelegateBridge(player("Germans")));
    start.start();
    start.end();
  }

  private GamePlayer player(final String name) {
    return data.getPlayerList().getPlayerId(name);
  }

  private UnitType type(final String name) {
    return data.getUnitTypeList().getUnitType(name).orElseThrow();
  }

  /** A coastal land territory of the player next to a sea zone with no units at all. */
  private Route coast(final GamePlayer owner) {
    for (final Territory land : data.getMap().getTerritoriesOwnedBy(owner)) {
      if (land.isWater()) {
        continue;
      }
      for (final Territory sea : data.getMap().getNeighbors(land)) {
        if (sea.isWater() && sea.getUnitCollection().isEmpty()) {
          return new Route(land, sea);
        }
      }
    }
    throw new IllegalStateException("no empty coast for " + owner.getName());
  }

  private void goTo(final String step) {
    final var sequence = data.getSequence();
    for (int i = 0; i < 10_000; i++) {
      if (sequence.getRound() == 1 && sequence.getStep().getName().equals(step)) {
        return;
      }
      sequence.next();
    }
    throw new IllegalStateException("no step " + step);
  }

  /** Puts one infantry of {@code rider} on the coast and tries to load it onto the transport. */
  private Optional<String> load(
      final GamePlayer rider,
      final GamePlayer transportOwner,
      final String step,
      final boolean forceMapping) {
    final Route route = coast(rider);
    final List<Unit> infantry = type("infantry").create(1, rider);
    final List<Unit> transport = type("transport").create(1, transportOwner);
    data.performChange(ChangeFactory.addUnits(route.getStart(), infantry));
    data.performChange(ChangeFactory.addUnits(route.getEnd(), transport));
    goTo(step);
    final var move = (MoveDelegate) data.getDelegate("move");
    move.setDelegateBridgeAndPlayer(newDelegateBridge(rider));
    move.start();
    final Map<Unit, Unit> mapping =
        forceMapping
            ? Map.of(infantry.get(0), transport.get(0))
            : TransportUtils.mapTransports(route, infantry, transport);
    final Optional<String> result =
        move.performMove(new MoveDescription(infantry, route, mapping));
    move.end();
    return result;
  }

  @Test
  void theRuleIsOnInFlamesAndSteel() {
    assertThat(OwnTransports.isActive(data), is(true));
    data.getProperties().set(OwnTransports.PROPERTY, false);
    assertThat(OwnTransports.isActive(data), is(false));
  }

  @Test
  void britishInfantryCannotBoardAnAmericanTransport() {
    final var british = player("British");
    final var americans = player("Americans");
    assertThat(
        load(british, americans, "britishCombatMove", false).orElse(""),
        containsString("Only American units can use American transports."));
  }

  @Test
  void britishInfantryCannotBoardAnAmericanTransportInNonCombatMove() {
    assertThat(
        load(player("British"), player("Americans"), "britishNonCombatMove", false).orElse(""),
        containsString("Only American units can use American transports."));
  }

  @Test
  void evenAForcedAlliedLoadIsRefused() {
    assertThat(
        load(player("British"), player("Americans"), "britishNonCombatMove", true).orElse(""),
        containsString("Only American units can use American transports."));
  }

  @Test
  void japaneseAndGermanUnitsCannotUseEachOthersTransports() {
    assertThat(
        load(player("Japanese"), player("Germans"), "japaneseNonCombatMove", false).orElse(""),
        containsString("Only German units can use German transports."));
  }

  @Test
  void germanUnitsCannotUseJapaneseTransports() {
    assertThat(
        load(player("Germans"), player("Japanese"), "germanNonCombatMove", false).orElse(""),
        containsString("Only Japanese units can use Japanese transports."));
  }

  @Test
  void ownUnitsLoadNormally() {
    final var british = player("British");
    final Optional<String> result = load(british, british, "britishNonCombatMove", false);
    assertThat(result.orElse(""), result.isEmpty(), is(true));
  }

  @Test
  void anAlliedCarrierGivesNoRoomInAnAirFront() {
    final var british = player("British");
    final var americans = player("Americans");
    final Territory front =
        games.strategy.triplea.attachments.AirZoneAttachment.getAirZones(data.getMap()).stream()
            .filter(
                z ->
                    games.strategy.triplea.attachments.AirZoneAttachment.get(z)
                        .orElseThrow()
                        .getCoveredTerritories()
                        .stream()
                        .anyMatch(Territory::isWater))
            .findFirst()
            .orElseThrow();
    final Territory sea =
        games.strategy.triplea.attachments.AirZoneAttachment.get(front)
            .orElseThrow()
            .getCoveredTerritories()
            .stream()
            .filter(Territory::isWater)
            .findFirst()
            .orElseThrow();
    final int before =
        AirAllocation.carrierLoad(british, front, List.of(type("fighter"))).capacity();
    data.performChange(ChangeFactory.addUnits(sea, type("carrier").create(1, americans)));
    assertThat(
        AirAllocation.carrierLoad(british, front, List.of(type("fighter"))).capacity(),
        is(before));
  }
}
