package games.strategy.triplea.ui.mapdata;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.Territory;
import games.strategy.triplea.ResourceLoader;
import java.awt.Color;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Fork request 54: the installed Flames and Steel map (draft 103 or later) draws the Pripet Marshes
 * in marsh green from {@code color.impassable.overrides}; the Pamirs keep {@code color.Impassable}.
 */
class Request54Test {
  private static final Path MAP =
      Path.of(
          System.getProperty("user.home"), "triplea", "downloadedMaps", "flames_and_steel", "map");

  @Test
  void pripetMarshesIsMarshGreenPamirsStayBeige() throws Exception {
    Assumptions.assumeTrue(
        Files.exists(MAP.resolve("map.properties")), "Flames and Steel map not installed");
    Assumptions.assumeTrue(
        Files.readString(MAP.resolve("map.properties"))
            .contains(PlayerColors.PROPERTY_IMPASSABLE_OVERRIDES),
        "installed map predates draft 103");

    final MapData mapData = new MapData(new ResourceLoader(MAP));
    final GameData data = new GameData();

    assertThat(
        mapData.impassableColor(new Territory("Pripet Marshes - Impassable", data)),
        is(new Color(0x66753A)));
    assertThat(
        mapData.impassableColor(new Territory("Pamirs - Impassable", data)),
        is(new Color(0xD6CFBA)));
    assertThat(mapData.impassableColor(), is(new Color(0xD6CFBA)));
  }
}
