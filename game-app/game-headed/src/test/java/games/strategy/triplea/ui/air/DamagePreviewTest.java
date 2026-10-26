package games.strategy.triplea.ui.air;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.gameparser.GameParser;
import games.strategy.triplea.image.HitPointBar;
import games.strategy.triplea.image.UnitImageFactory.ImageKey;
import games.strategy.triplea.settings.ClientSetting;
import games.strategy.triplea.ui.TestUi;
import java.awt.Color;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/** Temporary: draws units at map scale with their hit-point bars (and a 4x zoom of the strip). */
class DamagePreviewTest {
  private record Sample(String type, String owner, int hits) {}

  @Test
  void render() throws Exception {
    ClientSetting.initialize();
    final GameData data =
        games.strategy.triplea.ui.TestMaps.load();
    final var ui = TestUi.create(data);
    final var f = ui.getUnitImageFactory();
    final List<Sample> samples =
        List.of(
            new Sample("infantry", "Germans", 0),
            new Sample("tank", "Germans", 0),
            new Sample("tank", "Germans", 1),
            new Sample("trench", "Russians", 1),
            new Sample("heavyTank", "Russians", 2),
            new Sample("battleship", "British", 0),
            new Sample("battleship", "British", 3),
            new Sample("kingTiger", "Germans", 2),
            new Sample("yamatoBattleship", "Japanese", 0),
            new Sample("yamatoBattleship", "Japanese", 3),
            new Sample("fighter", "Americans", 1));
    final int width = 12 + samples.size() * 46;
    final BufferedImage img = new BufferedImage(width, 70, BufferedImage.TYPE_INT_RGB);
    final var g = img.createGraphics();
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    g.setColor(new Color(0x5B, 0x50, 0x35));
    g.fillRect(0, 0, width, 70);
    int x = 8;
    for (final Sample s : samples) {
      final var type = data.getUnitTypeList().getUnitType(s.type()).orElseThrow();
      final var p = data.getPlayerList().getPlayerId(s.owner());
      final Unit u = type.create(1, p, false, s.hits(), 0).get(0);
      final Image unitImage = f.getImage(ImageKey.of(u));
      g.drawImage(unitImage, x, 10, null);
      HitPointBar.paint(
          g,
          x,
          10 + unitImage.getHeight(null) + 2,
          unitImage.getWidth(null),
          Unit.maxHitPoints(u),
          u.getHits(),
          f.getScaleFactor());
      x += 46;
    }
    new File("build/preview").mkdirs();
    ImageIO.write(img, "png", new File("build/preview/hitbars.png"));

    // an overflowing row as on the map (request 9 item 5): the black overflow line under the row
    // must stay clear of the hit-point bars
    final BufferedImage row = new BufferedImage(300, 80, BufferedImage.TYPE_INT_RGB);
    final var rg = row.createGraphics();
    rg.setColor(new Color(0x5B, 0x50, 0x35));
    rg.fillRect(0, 0, 300, 80);
    final var germans = data.getPlayerList().getPlayerId("Germans");
    final List<Unit> units = new java.util.ArrayList<>();
    units.addAll(data.getUnitTypeList().getUnitType("infantry").orElseThrow().create(3, germans));
    units.addAll(data.getUnitTypeList().getUnitType("tank").orElseThrow().create(2, germans));
    units.addAll(data.getUnitTypeList().getUnitType("heavyTank").orElseThrow().create(1, germans));
    units.addAll(data.getUnitTypeList().getUnitType("kingTiger").orElseThrow().create(1, germans));
    units.addAll(data.getUnitTypeList().getUnitType("fieldGun").orElseThrow().create(1, germans));
    int rx = 10;
    for (final var category :
        games.strategy.triplea.util.UnitSeparator.categorize(units).stream()
            .sorted(java.util.Comparator.comparing(c -> c.getType().getName()))
            .toList()) {
      new games.strategy.triplea.ui.screen.UnitsDrawer(
              category, null, new java.awt.Point(rx, 10), true, ui)
          .draw(new java.awt.Rectangle(0, 0, 300, 80), data, rg, ui.getMapData());
      rx += f.getUnitImageWidth();
    }
    final BufferedImage rowZoom = new BufferedImage(1200, 320, BufferedImage.TYPE_INT_RGB);
    rowZoom.createGraphics().drawImage(row, 0, 0, 1200, 320, null);
    ImageIO.write(rowZoom, "png", new File("build/preview/overflow-row.png"));
    final BufferedImage zoom = new BufferedImage(width * 4, 70 * 4, BufferedImage.TYPE_INT_RGB);
    final var zg = zoom.createGraphics();
    zg.drawImage(img, 0, 0, width * 4, 70 * 4, null);
    ImageIO.write(zoom, "png", new File("build/preview/hitbars-zoom.png"));
  }
}
