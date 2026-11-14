package games.strategy.triplea.delegate;

import static games.strategy.triplea.delegate.MockDelegateBridge.advanceToStep;
import static games.strategy.triplea.delegate.MockDelegateBridge.newDelegateBridge;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.UnitType;
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
    data = games.strategy.triplea.delegate.air.BasedMap.load();
    Assumptions.assumeTrue(
        Properties.getUnplacedUnitsLive(data.getProperties()), "map lets unplaced units die");
    germans = data.getPlayerList().getPlayerId("Germans");
  }

  private UnitType type(final String name) {
    return data.getUnitTypeList().getUnitType(name).orElseThrow();
  }

  // --- request 10: Chinese free conscripts

  private PlaceDelegate startPlace(final GamePlayer player) {
    final IDelegateBridge bridge = newDelegateBridge(player);
    advanceToStep(bridge, player.getName().equals("Chinese") ? "chinesePlace" : "germanPlace");
    // the game's own place delegate, which undo looks up through the current step
    final PlaceDelegate place = (PlaceDelegate) data.getDelegate("place");
    place.setDelegateBridgeAndPlayer(bridge);
    place.start();
    return place;
  }

  private java.util.List<games.strategy.engine.data.Unit> hold(
      final GamePlayer player, final String unitType, final int count) {
    final var units = type(unitType).create(count, player);
    player.getUnitCollection().addAll(units);
    return units;
  }

  /** A land territory the player owns with no factory. */
  private games.strategy.engine.data.Territory ownedWithoutFactory(final GamePlayer player) {
    return data.getMap().getTerritories().stream()
        .filter(t -> !t.isWater() && t.isOwnedBy(player))
        .filter(t -> !t.anyUnitsMatch(Matches.unitCanProduceUnits()))
        .findFirst()
        .orElseThrow();
  }

  @Test
  void chinaPlacesTwoFreeConscriptsWithoutAFactoryButNothingElse() {
    final GamePlayer chinese = data.getPlayerList().getPlayerId("Chinese");
    assertThat(FreeConscripts.allowance(chinese), is(2));
    final var place = startPlace(chinese);
    final var t = ownedWithoutFactory(chinese);
    final var conscripts = hold(chinese, "conscript", 3);
    final var infantry = hold(chinese, "infantry", 1);

    assertThat(place.getPlaceableUnits(conscripts, t).getMaxUnits(), is(2));
    assertThat(place.canUnitsBePlaced(t, infantry, chinese).isPresent(), is(true));
    assertThat(place.placeUnits(conscripts.subList(0, 2), t).isEmpty(), is(true));
    // the allowance is used up
    assertThat(place.placeUnits(conscripts.subList(2, 3), t).isPresent(), is(true));
    place.end();
  }

  @Test
  void freeConscriptsMayBePlacedBeforeTheFactoryUnits() {
    // request 11 item 2: China still holds infantry when it places conscripts without a factory
    final GamePlayer chinese = data.getPlayerList().getPlayerId("Chinese");
    final var place = startPlace(chinese);
    final var t = ownedWithoutFactory(chinese);
    final var conscripts = hold(chinese, "conscript", 2);
    hold(chinese, "infantry", 5);
    final var offered = place.getPlaceableUnits(chinese.getUnits(), t);
    assertThat(offered.isError(), is(false));
    assertThat(offered.getUnits().stream().allMatch(FreeConscripts::isConscript), is(true));
    assertThat(offered.getMaxUnits(), is(2));
    assertThat(place.placeUnits(conscripts, t).orElse(""), is(""));
    place.end();
  }

  @Test
  void inAFactoryTerritoryTheOrderOfPlacingNeverLosesCapacity() {
    final GamePlayer chinese = data.getPlayerList().getPlayerId("Chinese");
    final var place = startPlace(chinese);
    final var chungking = data.getMap().getTerritoryOrThrow("Chungking");
    final var infantry = hold(chinese, "infantry", 20);
    final int capacity = place.getPlaceableUnits(infantry, chungking).getMaxUnits();
    final var conscripts = hold(chinese, "conscript", 2);
    // conscripts first, then the factory's full capacity in infantry
    assertThat(place.placeUnits(conscripts, chungking).orElse(""), is(""));
    assertThat(place.getPlaceableUnits(infantry, chungking).getMaxUnits(), is(capacity));
    assertThat(place.placeUnits(infantry.subList(0, capacity), chungking).orElse(""), is(""));
    // undo the conscripts: they can go back in, still free
    assertThat(place.undoMove(0), is((String) null));
    assertThat(place.getPlaceableUnits(conscripts, chungking).getMaxUnits(), is(2));
    place.end();
  }

  @Test
  void inAFactoryTerritoryTheFirstTwoConscriptsAreFree() {
    final GamePlayer chinese = data.getPlayerList().getPlayerId("Chinese");
    final var place = startPlace(chinese);
    final var chungking = data.getMap().getTerritoryOrThrow("Chungking");
    final var infantry = hold(chinese, "infantry", 20);
    final int capacity = place.getPlaceableUnits(infantry, chungking).getMaxUnits();
    assertThat(capacity > 0 && capacity < 20, is(true));
    final var conscripts = hold(chinese, "conscript", 5);

    // capacity in infantry, plus 2 free conscripts
    final var mixed = new java.util.ArrayList<>(infantry.subList(0, capacity));
    mixed.addAll(conscripts.subList(0, 2));
    assertThat(place.placeUnits(mixed, chungking).orElse(""), is(""));
    // the factory is full and the free conscripts are used
    assertThat(place.placeUnits(conscripts.subList(2, 3), chungking).isPresent(), is(true));
    place.end();
  }

  @Test
  void territoriesConqueredThisTurnAndOtherNationsGetNoFreeConscripts() throws Exception {
    final GamePlayer chinese = data.getPlayerList().getPlayerId("Chinese");
    final var place = startPlace(chinese);
    final var t = ownedWithoutFactory(chinese);
    final var tracker = data.getBattleDelegate().getBattleTracker();
    final var add =
        tracker
            .getClass()
            .getDeclaredMethod("addToConquered", games.strategy.engine.data.Territory.class);
    add.setAccessible(true);
    add.invoke(tracker, t);
    assertThat(FreeConscripts.qualifies(t, chinese), is(false));
    assertThat(
        place.canUnitsBePlaced(t, hold(chinese, "conscript", 1), chinese).isPresent(), is(true));
    place.end();

    assertThat(FreeConscripts.allowance(germans), is(0));
    final var germanPlace = startPlace(germans);
    final var g = ownedWithoutFactory(germans);
    assertThat(
        germanPlace.canUnitsBePlaced(g, hold(germans, "conscript", 1), germans).isPresent(),
        is(true));
    germanPlace.end();
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
