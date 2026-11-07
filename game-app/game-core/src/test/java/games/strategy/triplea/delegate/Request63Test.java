package games.strategy.triplea.delegate;

import static games.strategy.triplea.delegate.MockDelegateBridge.newDelegateBridge;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.triplea.delegate.data.PlaceableUnits;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Request 63: an SPC may be placed in a territory of the nation's SPC area whether or not a factory
 * is there, including a factory built during the game.
 */
class Request63Test {
  private static final Path XML =
      Path.of(
          System.getProperty("user.home"),
          "triplea/downloadedMaps/flames_and_steel/map/games/flames_and_steel.xml");
  private GameData data;

  @BeforeEach
  void setUp() {
    Assumptions.assumeTrue(Files.exists(XML), "Flames and Steel map not installed");
    data = games.strategy.triplea.delegate.air.BasedMap.load();
    final InitializationDelegate start = new InitializationDelegate();
    start.initialize("initDelegate", "Initializing Delegates");
    start.setDelegateBridgeAndPlayer(newDelegateBridge(player("Germans")));
    start.start();
    start.end();
  }

  GamePlayer player(final String name) {
    return data.getPlayerList().getPlayerId(name);
  }

  Territory territory(final String name) {
    return data.getMap().getTerritoryOrThrow(name);
  }

  UnitType type(final String name) {
    return data.getUnitTypeList().getUnitTypeOrThrow(name);
  }

  void grant(final GamePlayer player, final String tech) {
    final TechAdvance advance = data.getTechnologyFrontier().getAdvanceByName(tech);
    data.performChange(
        advance instanceof GenericTechAdvance g && g.getAdvance() == null
            ? ChangeFactory.genericTechChange(
                player.getTechAttachment(), true, advance.getProperty())
            : ChangeFactory.attachmentPropertyChange(
                player.getTechAttachment(), "true", advance.getProperty()));
    data.getTechTracker().clearCache();
  }

  void goTo(final String step) {
    final var sequence = data.getSequence();
    for (int i = 0; i < 10_000; i++) {
      if (sequence.getStep().getName().equals(step)) {
        return;
      }
      sequence.next();
    }
    throw new IllegalStateException("no step " + step);
  }

  /** The player at Place with two SPCs to place and Advanced 1 and 2. */
  PlaceDelegate placeWithSpcs(final GamePlayer player, final String step) {
    grant(player, "Advanced1");
    grant(player, "Advanced2");
    player.getUnitCollection().addAll(type("apc").create(2, player));
    goTo(step);
    final var place = (PlaceDelegate) data.getDelegate("place");
    place.setDelegateBridgeAndPlayer(newDelegateBridge(player));
    place.start();
    return place;
  }

  List<Unit> spcs(final GamePlayer player) {
    return player.getUnitCollection().getMatches(u -> u.getType().getName().equals("apc"));
  }

  void assertSpcPlaceable(final PlaceDelegate place, final GamePlayer player, final String at) {
    final Territory t = territory(at);
    final List<Unit> spcs = spcs(player);
    final PlaceableUnits placeable = place.getPlaceableUnits(spcs, t);
    assertThat(
        at + ": " + placeable.getErrorMessage(),
        placeable.getUnits().stream().anyMatch(u -> u.getType().getName().equals("apc")),
        is(true));
    assertThat(at, place.placeUnits(List.of(spcs.get(0)), t).orElse(""), is(""));
    assertThat(t.getUnitCollection().countMatches(u -> u.getType().getName().equals("apc")), is(1));
  }

  @Test
  void germansPlaceAnSpcInDanzigWithAFactoryBuiltDuringTheGame() {
    final GamePlayer germans = player("Germans");
    final Territory danzig = territory("Danzig");
    final Unit factory = type("factory").create(1, germans).get(0);
    data.performChange(ChangeFactory.addUnits(danzig, List.of(factory)));
    final PlaceDelegate place = placeWithSpcs(germans, "germanPlace");
    assertSpcPlaceable(place, germans, "Danzig");
  }

  @Test
  void germansPlaceAnSpcInBerlin() {
    final GamePlayer germans = player("Germans");
    assertSpcPlaceable(placeWithSpcs(germans, "germanPlace"), germans, "Berlin");
  }

  @Test
  void germansPlaceAnSpcInBerlinAfterItsFactoryIsFull() {
    final GamePlayer germans = player("Germans");
    final PlaceDelegate place = placeWithSpcs(germans, "germanPlace");
    final Territory berlin = territory("Berlin");
    final int room =
        place.getPlaceableUnits(type("infantry").create(1, germans), berlin).getMaxUnits();
    final List<Unit> infantry = type("infantry").create(Math.max(1, room), germans);
    germans.getUnitCollection().addAll(infantry);
    assertThat(place.placeUnits(infantry, berlin).orElse(""), is(""));
    assertSpcPlaceable(place, germans, "Berlin");
  }

  @Test
  void americaPacificPlacesAnSpcInSanFrancisco() {
    final GamePlayer us = player("AmericansPacific");
    assertSpcPlaceable(placeWithSpcs(us, "americanPacificPlace"), us, "San Francisco");
  }

  @Test
  void mixedPurchaseLikeThePlacementScreen() {
    final GamePlayer germans = player("Germans");
    final Territory danzig = territory("Danzig");
    data.performChange(ChangeFactory.addUnits(danzig, type("factory").create(1, germans)));
    final PlaceDelegate place = placeWithSpcs(germans, "germanPlace");
    germans.getUnitCollection().addAll(type("infantry").create(10, germans));
    germans.getUnitCollection().addAll(type("tank").create(3, germans));
    for (final String at : List.of("Danzig", "Berlin", "West Prussia")) {
      final PlaceableUnits p =
          place.getPlaceableUnits(germans.getUnitCollection().getUnits(), territory(at));
      System.out.println(
          at
              + ": max "
              + p.getMaxUnits()
              + " error "
              + p.getErrorMessage()
              + " units "
              + games.strategy.triplea.formatter.MyFormatter.unitsToText(p.getUnits()));
    }
    final Territory berlin = territory("Berlin");
    final List<Unit> inf =
        germans.getUnitCollection().getMatches(u -> u.getType().getName().equals("infantry"));
    final int room = place.getPlaceableUnits(inf, berlin).getMaxUnits();
    place.placeUnits(inf.subList(0, Math.min(room, inf.size())), berlin);
    final PlaceableUnits p =
        place.getPlaceableUnits(germans.getUnitCollection().getUnits(), berlin);
    System.out.println(
        "Berlin after "
            + room
            + " infantry: max "
            + p.getMaxUnits()
            + " units "
            + games.strategy.triplea.formatter.MyFormatter.unitsToText(p.getUnits()));
  }
}
