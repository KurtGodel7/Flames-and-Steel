package games.strategy.triplea.ui.air;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Route;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.framework.IGame;
import games.strategy.engine.framework.LocalPlayers;
import games.strategy.triplea.settings.ClientSetting;
import games.strategy.triplea.ui.TripleAFrame;
import games.strategy.triplea.ui.panels.map.MapPanel;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;
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
 * Request 59: the air route label shows the range the air system counts, red when over the limit.
 * Draws a torpedo bomber's route from Dakar on the real map and saves build/preview/r59-*.png.
 */
class RouteLabelPreviewTest {
  private static final Path XML =
      Path.of(
          System.getProperty("user.home"),
          "triplea/downloadedMaps/flames_and_steel/map/games/flames_and_steel.xml");
  private static GameData data;
  private static TripleAFrame frame;

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

  private static Object field(final MapPanel map, final String name) throws Exception {
    final Field f = MapPanel.class.getDeclaredField(name);
    f.setAccessible(true);
    return f.get(map);
  }

  private void show(
      final List<Territory> path, final int rangeLeft, final boolean overLimit, final String name)
      throws Exception {
    final MapPanel map = frame.getMapPanel();
    final GamePlayer british = data.getPlayerList().getPlayerId("British");
    final List<Unit> bomber =
        data.getUnitTypeList().getUnitTypeOrThrow("torpedoBomber").create(1, british);
    SwingUtilities.invokeAndWait(
        () -> {
          map.centerOnTerritoryIgnoringMapLock(t("Gambia"));
          map.setMouseShadowUnits(bomber, rangeLeft, overLimit);
          final Territory end = path.get(path.size() - 1);
          final Point c = frame.getUiContext().getMapData().getCenter(end);
          map.setRoute(
              new Route(path), frame.getUiContext().getMapData().getCenter(t("Dakar")), c, null);
        });
    Thread.sleep(600);
    assertThat(field(map, "movementLeftForCurrentUnits"), is(String.valueOf(rangeLeft)));
    assertThat(field(map, "routeOverLimit"), is(overLimit));
    SwingUtilities.invokeAndWait(
        () -> {
          final BufferedImage img =
              new BufferedImage(map.getWidth(), map.getHeight(), BufferedImage.TYPE_INT_RGB);
          map.paint(img.getGraphics());
          try {
            new File("build/preview").mkdirs();
            ImageIO.write(img, "png", new File("build/preview/r59-" + name + ".png"));
          } catch (final Exception e) {
            throw new RuntimeException(e);
          }
        });
  }

  @Test
  void torpedoBomberWithOneLeftIsRedTwoStepsOut() throws Exception {
    show(
        List.of(t("Dakar"), t("Gambia"), t("Sea Zone 43 Gulf of Guinea - West")),
        1,
        true,
        "dakar-2-of-1-red");
  }

  @Test
  void torpedoBomberWithOneLeftIsGreyToGambia() throws Exception {
    show(List.of(t("Dakar"), t("Gambia")), 1, false, "dakar-1-of-1-grey");
  }

  @Test
  void stockShadowStillUsesMovementLeftAndIsNeverRed() throws Exception {
    final MapPanel map = frame.getMapPanel();
    final GamePlayer british = data.getPlayerList().getPlayerId("British");
    final List<Unit> tank = data.getUnitTypeList().getUnitTypeOrThrow("tank").create(1, british);
    SwingUtilities.invokeAndWait(
        () -> {
          map.setMouseShadowUnits(tank, 0, true);
          map.setMouseShadowUnits(tank);
        });
    assertThat(field(map, "routeOverLimit"), is(false));
    assertThat(
        field(map, "movementLeftForCurrentUnits"), is(tank.get(0).getMovementLeft().toString()));
  }
}
