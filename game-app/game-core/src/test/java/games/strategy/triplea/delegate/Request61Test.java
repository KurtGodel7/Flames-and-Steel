package games.strategy.triplea.delegate;

import static games.strategy.triplea.delegate.MockDelegateBridge.newDelegateBridge;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.changefactory.ChangeFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Request 61: SPCs only in each nation's "FS SPC Area"; factories unaffected. */
class Request61Test {
  private static final Path XML =
      Path.of(
          System.getProperty("user.home"),
          "triplea/downloadedMaps/flames_and_steel/map/games/flames_and_steel.xml");
  private GameData data;

  @BeforeEach
  void setUp() {
    Assumptions.assumeTrue(Files.exists(XML), "Flames and Steel map not installed");
    data = games.strategy.triplea.delegate.air.BasedMap.load();
    Assumptions.assumeTrue(
        SpcArea.of(data.getPlayerList().getPlayerId("Germans")).isPresent(),
        "map without SPC areas");
    final InitializationDelegate start = new InitializationDelegate();
    start.initialize("initDelegate", "Initializing Delegates");
    start.setDelegateBridgeAndPlayer(newDelegateBridge(player("Germans")));
    start.start();
    start.end();
  }

  private GamePlayer player(final String name) {
    return data.getPlayerList().getPlayerId(name);
  }

  private Territory territory(final String name) {
    return data.getMap().getTerritoryOrThrow(name);
  }

  private void goTo(final String step) {
    final var sequence = data.getSequence();
    for (int i = 0; i < 10_000; i++) {
      if (sequence.getStep().getName().equals(step)) {
        return;
      }
      sequence.next();
    }
    throw new IllegalStateException("no step " + step);
  }

  private PlaceDelegate placeFor(final GamePlayer player, final String step) {
    goTo(step);
    final var place = (PlaceDelegate) data.getDelegate("place");
    place.setDelegateBridgeAndPlayer(newDelegateBridge(player));
    place.start();
    return place;
  }

  private List<Unit> hold(final GamePlayer player, final String type) {
    final List<Unit> units = data.getUnitTypeList().getUnitTypeOrThrow(type).create(1, player);
    player.getUnitCollection().addAll(units);
    return units;
  }

  private void own(final GamePlayer player, final String... names) {
    for (final String name : names) {
      data.performChange(ChangeFactory.changeOwner(territory(name), player));
    }
  }

  private void check(
      final String nation, final String step, final List<String> yes, final List<String> no) {
    final GamePlayer player = player(nation);
    own(player, yes.toArray(String[]::new));
    own(player, no.toArray(String[]::new));
    final PlaceDelegate place = placeFor(player, step);
    final List<Unit> spc = hold(player, "apc");
    for (final String name : yes) {
      assertThat(name, SpcArea.allows(player, territory(name)), is(true));
      assertThat(
          name,
          place
              .canUnitsBePlaced(territory(name), spc, player)
              .orElse("")
              .startsWith(SpcArea.reason(player)),
          is(false));
    }
    for (final String name : no) {
      final Territory t = territory(name);
      assertThat(
          name, place.canUnitsBePlaced(t, spc, player).orElse(""), is(SpcArea.reason(player)));
      assertThat(
          name,
          place.getPlaceableUnits(player.getUnits(), t).getUnits().stream()
              .anyMatch(games.strategy.triplea.delegate.apc.Apcs::isApc),
          is(false));
      assertThat(name, place.placeUnits(spc, t).isPresent(), is(true));
    }
    place.end();
  }

  @Test
  void germany() {
    check(
        "Germans",
        "germanPlace",
        List.of("Berlin", "West Prussia"),
        List.of("Vitebsk", "Wartheland", "Paris"));
    assertThat(
        SpcArea.reason(player("Germans")),
        startsWith("Germans may place SPCs only in majority-German land"));
  }

  @Test
  void ussr() {
    check(
        "Russians",
        "russianPlace",
        List.of("Moscow", "Minsk"),
        List.of("Latvia", "Lwow", "Bessarabia", "East Prussia"));
  }

  @Test
  void japan() {
    check("Japanese", "japanesePlace", List.of("Tokyo"), List.of("Hsinking", "Okinawa"));
  }

  @Test
  void britain() {
    check("British", "britishPlace", List.of("London", "Toronto"), List.of("Delhi", "Sydney"));
  }

  @Test
  void britishFactoriesInDelhiAreStillAllowed() {
    final GamePlayer british = player("British");
    final PlaceDelegate place = placeFor(british, "britishPlace");
    final List<Unit> factory = hold(british, "factory");
    assertThat(
        place.canUnitsBePlaced(territory("Delhi"), factory, british).orElse(""),
        org.hamcrest.Matchers.not(startsWith("British")));
    assertThat(FactoryArea.allows(british, territory("Delhi")), is(true));
  }

  @Test
  void americaAtlantic() {
    check(
        "Americans",
        "americanPlace",
        List.of("New York"),
        List.of("Los Angeles", "Puerto Rico", "Panama"));
  }

  @Test
  void americaPacific() {
    check(
        "AmericansPacific",
        "americanPacificPlace",
        List.of("San Francisco"),
        List.of("New York", "Alaska", "Hawaii"));
  }

  @Test
  void germanyMayStillBuildFactoriesInMadridAndVitebsk() {
    final GamePlayer germans = player("Germans");
    own(germans, "Madrid", "Vitebsk");
    final PlaceDelegate place = placeFor(germans, "germanPlace");
    final List<Unit> factory = hold(germans, "factory");
    for (final String name : List.of("Madrid", "Vitebsk")) {
      assertThat(FactoryArea.allows(germans, territory(name)), is(true));
      assertThat(
          name,
          place.getPlaceableUnits(germans.getUnits(), territory(name)).getUnits().stream()
              .anyMatch(u -> u.getType().getName().equals("factory")),
          is(true));
    }
    assertThat(factory.isEmpty(), is(false));
  }
}
