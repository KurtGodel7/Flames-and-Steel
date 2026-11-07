package games.strategy.triplea.ui.air;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.framework.IGame;
import games.strategy.engine.framework.LocalPlayers;
import games.strategy.triplea.settings.ClientSetting;
import games.strategy.triplea.ui.TripleAFrame;
import games.strategy.triplea.ui.panels.map.MapPanel;
import java.awt.Color;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Request 64: air drawings take the short way across the map seam and sit inside their territory.
 * Saves build/preview/r64-guatemala.png (America Pacific fighters from Southern Mexico).
 */
class AirDrawWrapTest {
  private static final Path XML =
      Path.of(
          System.getProperty("user.home"),
          "triplea/downloadedMaps/flames_and_steel/map/games/flames_and_steel.xml");
  private static final List<String> OUTSIDE_BOX_MIDDLE =
      List.of(
          "Archangel",
          "Athens",
          "Constantinople",
          "Cuba",
          "East Canadian Arctic",
          "Fiji",
          "Florida",
          "Greenland",
          "Guatemala",
          "Hawaii",
          "Iwo Jima",
          "Karafuto",
          "Korat",
          "Lena Delta",
          "Makassar",
          "Marcus Island",
          "Narvik",
          "Newfoundland",
          "Novgorod",
          "Pskov",
          "Saipan",
          "Samoa",
          "Southern Chile",
          "Spanish Morocco",
          "Tashkent",
          "Trucial Coast",
          "Valdai",
          "West Canadian Arctic",
          "Western Kazakhstan",
          "Yap");
  private static GameData data;
  private static TripleAFrame frame;
  private static AirDraw draw;

  @BeforeAll
  static void open() throws Exception {
    Assumptions.assumeTrue(Files.exists(XML), "Flames and Steel map not installed");
    Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "needs a screen");
    ClientSetting.initialize();
    data = games.strategy.triplea.ui.TestMaps.load();
    final IGame game = mock(IGame.class);
    when(game.getData()).thenReturn(data);
    frame = TripleAFrame.create(game, new LocalPlayers(Set.of()), null, () -> {});
    SwingUtilities.invokeAndWait(
        () -> {
          frame.setExtendedState(Frame.NORMAL);
          frame.setBounds(0, 0, 1366, 768);
          frame.setVisible(true);
        });
    draw = new AirDraw(frame.getUiContext());
  }

  @AfterAll
  static void close() throws Exception {
    if (frame != null) {
      SwingUtilities.invokeAndWait(frame::dispose);
    }
  }

  private static Territory t(final String name) {
    return data.getMap().getTerritoryOrThrow(name);
  }

  private static boolean inside(final String name, final Point p) {
    return draw.mapData().getPolygons(t(name)).stream().anyMatch(poly -> poly.contains(p));
  }

  @Test
  void everyLandTerritoryIsDrawnInsideItself() {
    for (final String name : OUTSIDE_BOX_MIDDLE) {
      assertThat(name, inside(name, draw.middle(t(name))), is(true));
    }
    for (final Territory territory : data.getMap().getTerritories()) {
      if (!territory.isWater() && !draw.mapData().getPolygons(territory).isEmpty()) {
        assertThat(
            territory.getName(), inside(territory.getName(), draw.middle(territory)), is(true));
      }
    }
  }

  @Test
  void guatemalaIsNotDrawnOverBritishHonduras() {
    final Point p = draw.middle(t("Guatemala"));
    assertThat(inside("Guatemala", p), is(true));
    assertThat(inside("British Honduras", p), is(false));
  }

  @Test
  void territoriesWhoseBoxMiddleIsInsideAreDrawnWhereTheyWere() {
    for (final String name : List.of("Berlin", "Madrid", "Moscow", "Texas")) {
      final var r = draw.mapData().getBoundingRect(t(name));
      assertThat(
          name, draw.middle(t(name)), is(new Point((int) r.getCenterX(), (int) r.getCenterY())));
    }
  }

  @Test
  void anArrowAcrossTheSeamTakesTheShortWay() {
    final Point from = draw.center(t("Southern Mexico"));
    final Point to = draw.nearest(from, draw.middle(t("Guatemala")));
    assertThat(Math.abs(to.x - from.x), lessThan(500));
    // flights that don't cross the seam are unchanged
    final Point berlin = draw.center(t("Berlin"));
    final Point warsaw = draw.middle(t("Warsaw"));
    assertThat(draw.nearest(berlin, warsaw), is(warsaw));
  }

  @Test
  void pictureOfSouthernMexicoToGuatemala() throws Exception {
    final MapPanel map = frame.getMapPanel();
    final GamePlayer us = data.getPlayerList().getPlayerId("AmericansPacific");
    final List<Unit> fighters = data.getUnitTypeList().getUnitTypeOrThrow("fighter").create(2, us);
    final games.strategy.triplea.ui.panels.map.MapOverlay overlay =
        (g, d) -> {
          final Point from = draw.center(t("Southern Mexico"));
          final Point end = draw.nearest(from, draw.middle(t("Guatemala")));
          AirDraw.arrow(g, from, end, new Color(255, 150, 30), 4f, false, "", 6);
          draw.drawStacks(g, end, AirDraw.stacks(fighters), 0.95f);
          AirDraw.tag(g, "B 2/4", end.x, end.y - 40, new Color(255, 230, 120));
        };
    SwingUtilities.invokeAndWait(
        () -> {
          map.addOverlay(overlay);
          map.centerOnTerritoryIgnoringMapLock(t("Guatemala"));
        });
    Thread.sleep(800);
    SwingUtilities.invokeAndWait(
        () -> {
          final BufferedImage img =
              new BufferedImage(map.getWidth(), map.getHeight(), BufferedImage.TYPE_INT_RGB);
          map.paint(img.getGraphics());
          try {
            new File("build/preview").mkdirs();
            ImageIO.write(img, "png", new File("build/preview/r64-guatemala.png"));
          } catch (final Exception e) {
            throw new RuntimeException(e);
          }
          map.removeOverlay(overlay);
        });
  }
}
