package games.strategy.triplea.ui.air;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.engine.data.gameparser.GameParser;
import games.strategy.triplea.delegate.air.AirAllocation.AirMove;
import games.strategy.triplea.delegate.air.AirAllocation.Stage;
import games.strategy.triplea.settings.ClientSetting;
import games.strategy.triplea.ui.TestUi;
import games.strategy.triplea.ui.UiContext;
import java.awt.Component;
import java.awt.Container;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Request 26: in the German bomber allocation grid, a flying wing in Western Europe may go to Bomb
 * USA, and the cell says "Long-range raid"; a strategic bomber there may not. Draws
 * build/preview/r26-grid.png.
 */
class LongRangeRaidGridTest {
  private static final Path XML =
      Path.of(
          System.getProperty("user.home"),
          "triplea/downloadedMaps/flames_and_steel/map/games/flames_and_steel.xml");

  @Test
  void bombUsaIsALongRangeRaidForTheFlyingWingOnly() throws Exception {
    Assumptions.assumeTrue(Files.exists(XML), "Flames and Steel map not installed");
    ClientSetting.initialize();
    final GameData data = GameParser.parse(XML, false).orElseThrow();
    final GamePlayer germans = data.getPlayerList().getPlayerId("Germans");
    final GamePlayer americans = data.getPlayerList().getPlayerId("Americans");
    data.performChange(
        ChangeFactory.relationshipChange(
            germans,
            americans,
            data.getRelationshipTracker().getRelationshipType(germans, americans),
            data.getRelationshipTypeList().getRelationshipType("War")));
    final Territory westernEurope = data.getMap().getTerritoryOrThrow("Air - Western Europe");
    data.performChange(
        ChangeFactory.addUnits(
            westernEurope,
            data.getUnitTypeList().getUnitTypeOrThrow("flyingWing").create(2, germans)));
    data.performChange(
        ChangeFactory.addUnits(
            westernEurope,
            data.getUnitTypeList().getUnitTypeOrThrow("strategicBomber").create(1, germans)));
    final UiContext ui = TestUi.create(data);
    final List<AirMove> staged = new ArrayList<>();
    SwingUtilities.invokeAndWait(
        () -> {
          final JFrame owner = new JFrame();
          owner.setBounds(-5000, -5000, 1600, 1000);
          final AirAllocationGrid grid =
              new AirAllocationGrid(
                  owner,
                  ui,
                  new AirAllocationGrid.Host() {
                    @Override
                    public GameData data() {
                      return data;
                    }

                    @Override
                    public GamePlayer player() {
                      return germans;
                    }

                    @Override
                    public Stage stage() {
                      return Stage.BOMBERS;
                    }

                    @Override
                    public int round() {
                      return 3;
                    }

                    @Override
                    public List<AirMove> staged() {
                      return List.copyOf(staged);
                    }

                    @Override
                    public void setStaged(final List<AirMove> moves) {
                      staged.clear();
                      staged.addAll(moves);
                    }

                    @Override
                    public Map<GamePlayer, List<AirMove>> allied() {
                      return Map.of();
                    }

                    @Override
                    public void done() {}

                    @Override
                    public void hoverZone(final @Nullable Territory zone) {}
                  });
          final JLabel wing =
              label(
                  grid.getContentPane(), "targets|flyingWing@Air - Western Europe|Air - Bomb USA");
          assertThat(wing.getText(), containsString("Long-range raid"));
          assertThat(wing.getToolTipText(), containsString("Detroit"));
          assertThat(wing.getToolTipText(), containsString("New York"));
          assertThat(
              label(
                  grid.getContentPane(),
                  "targets|strategicBomber@Air - Western Europe|Air - Bomb USA"),
              is(nullValue()));
          try {
            grid.validate();
            final var c = grid.getContentPane();
            final BufferedImage img =
                new BufferedImage(c.getWidth(), c.getHeight(), BufferedImage.TYPE_INT_RGB);
            c.printAll(img.getGraphics());
            new File("build/preview").mkdirs();
            ImageIO.write(img, "png", new File("build/preview/r26-grid.png"));
          } catch (final Exception e) {
            throw new RuntimeException(e);
          }
          grid.dispose();
          owner.dispose();
        });
  }

  private static @Nullable JLabel label(final Container root, final String name) {
    for (final Component c : root.getComponents()) {
      if (c instanceof JLabel l && name.equals(l.getName())) {
        return l;
      }
      if (c instanceof Container k) {
        final JLabel found = label(k, name);
        if (found != null) {
          return found;
        }
      }
    }
    return null;
  }
}
