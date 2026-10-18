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
