package games.strategy.triplea.ui.air;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.gameparser.GameParser;
import games.strategy.triplea.attachments.AirZoneAttachment;
import games.strategy.triplea.settings.ClientSetting;
import games.strategy.triplea.ui.TestUi;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Flames and Steel request 22 on the installed map's opening position: every air front's and
 * bombing zone's box is docked, or lies wholly in its own front's sea zones and covers no land,
 * photo card, title plaque, unit stack, drawn name or other box. Skipped when the map is not
 * installed.
 */
class BoxPlacementTest {
  private static final Path XML =
      Path.of(
          System.getProperty("user.home"),
          "triplea/downloadedMaps/flames_and_steel/map/games/flames_and_steel.xml");

  @Test
  void everyBoxIsInItsOwnFrontsOpenSeaOrDocked() {
    Assumptions.assumeTrue(Files.exists(XML), "Flames and Steel map not installed");
    ClientSetting.initialize();
    final GameData data = GameParser.parse(XML, false).orElseThrow();
    final var ui = TestUi.create(data);
    final var mapData = ui.getMapData();
    final var size = mapData.getMapDimensions();
    final AirMapMode mode = new AirMapMode(ui, data, size.width);
    final BufferedImage img =
        new BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_RGB);
    final var g = img.createGraphics();
    mode.paint(g, data);
    g.dispose();
    final BoxPlacer placer = mode.placer();

    final List<String> placedOnMap = new ArrayList<>();
    final List<Rectangle> drawn = new ArrayList<>();
    for (final Territory zone : AirZoneAttachment.getAirZones(data.getMap())) {
      if (AirZoneAttachment.get(zone).orElseThrow().getZoneType()
          == AirZoneAttachment.ZoneType.RESERVE) {
        continue;
      }
      if (placer.isDocked(zone)) {
        continue;
      }
      final Rectangle r = mode.boxOnMap(zone).orElse(null);
      if (r == null) {
        continue; // not drawn (no aircraft and nothing to show)
      }
      final String name = zone.getName();
      assertThat(
          name + " lies in its own sea",
          BoxPlacer.inside(r, BoxPlacer.homeSeas(zone, mapData)),
          is(true));
      assertThat(name + " covers land, a card or the plaque", placer.coversLand(r), is(false));
      assertThat(name + " covers a stack or a name", placer.touchesStackOrName(r), is(false));
      for (final Rectangle other : drawn) {
        assertThat(name + " overlaps another box", r.intersects(other), is(false));
      }
      drawn.add(r);
      placedOnMap.add(name);
    }
    System.out.println("On the map: " + placedOnMap);
    assertThat(placedOnMap.size() > 5, is(true));
  }
}
