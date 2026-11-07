package games.strategy.triplea.ui;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import com.formdev.flatlaf.FlatDarkLaf;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.triplea.delegate.GenericTechAdvance;
import games.strategy.triplea.delegate.TechAdvance;
import java.awt.GraphicsEnvironment;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.swing.JComboBox;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Request 62: the SPC production window offers "Ships go to:" for an SPC building ships with two
 * sea zones, opens on the stored zone, and hides it for tanks. Saves build/preview/r62-*.png.
 */
class ApcSeaZoneDialogTest {
  private static final Path XML =
      Path.of(
          System.getProperty("user.home"),
          "triplea/downloadedMaps/flames_and_steel/map/games/flames_and_steel.xml");

  @Test
  void hamburgShowsBothSeaZonesAndOpensOnTheStoredOne() throws Exception {
    Assumptions.assumeTrue(Files.exists(XML), "Flames and Steel map not installed");
    Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "needs a screen");
    final GameData data = TestMaps.load();
    final GamePlayer germans = data.getPlayerList().getPlayerId("Germans");
    final Territory hamburg = data.getMap().getTerritoryOrThrow("Hamburg");
    final Territory helgoland = data.getMap().getTerritoryOrThrow("Sea Zone 76 Helgoland Bight");
    final Unit spc = data.getUnitTypeList().getUnitTypeOrThrow("apc").create(1, germans).get(0);
    data.performChange(ChangeFactory.addUnits(hamburg, List.of(spc)));
    for (final String tech : List.of("TypeXXIUboat", "Panther")) {
      final TechAdvance advance = data.getTechnologyFrontier().getAdvanceByName(tech);
      data.performChange(
          advance instanceof GenericTechAdvance g && g.getAdvance() == null
              ? ChangeFactory.genericTechChange(
                  germans.getTechAttachment(), true, advance.getProperty())
              : ChangeFactory.attachmentPropertyChange(
                  germans.getTechAttachment(), "true", advance.getProperty()));
    }
    data.getTechTracker().clearCache();
    final UnitType uboat = data.getUnitTypeList().getUnitTypeOrThrow("typeXXI");
    final var attachment = games.strategy.triplea.attachments.PlayerAttachment.get(germans);
    final String id = spc.getId().toString();
    data.performChange(
        ChangeFactory.attachmentPropertyChange(
            attachment, new java.util.HashMap<>(Map.of(id, uboat.getName())), "apcProduction"));
    data.performChange(
        ChangeFactory.attachmentPropertyChange(
            attachment, new java.util.HashMap<>(Map.of(id, helgoland.getName())), "apcSeaZone"));
    SwingUtilities.invokeAndWait(
        () -> {
          try {
            UIManager.setLookAndFeel(new FlatDarkLaf());
          } catch (final Exception e) {
            throw new RuntimeException(e);
          }
          final JPanel panel = new JPanel(new java.awt.GridBagLayout());
          final Map<Unit, JComboBox<Object>> boxes = new LinkedHashMap<>();
          final Map<Unit, JComboBox<Territory>> zones = new LinkedHashMap<>();
          ApcProductionDialog.build(panel, germans, data, boxes, zones);
          final JComboBox<Territory> zoneBox = zones.get(spc);
          assertThat(zoneBox.getItemCount(), is(2));
          assertThat(zoneBox.getSelectedItem(), is(helgoland));
          assertThat(zoneBox.isShowing() || zoneBox.getParent() != null, is(true));
          snap(panel, "r62-hamburg-uboats");
          assertThat(ApcProductionDialog.choices(boxes, zones).seaZones().get(spc), is(helgoland));
          boxes.get(spc).setSelectedItem(data.getUnitTypeList().getUnitTypeOrThrow("panther"));
          assertThat(zoneBox.getParent() == null, is(true));
          assertThat(ApcProductionDialog.choices(boxes, zones).seaZones().isEmpty(), is(true));
          snap(panel, "r62-hamburg-panthers");
        });
  }

  private static void snap(final JPanel panel, final String name) {
    panel.setSize(panel.getPreferredSize());
    layout(panel);
    final BufferedImage img =
        new BufferedImage(
            Math.max(1, panel.getWidth()),
            Math.max(1, panel.getHeight()),
            BufferedImage.TYPE_INT_RGB);
    panel.printAll(img.getGraphics());
    try {
      new File("build/preview").mkdirs();
      ImageIO.write(img, "png", new File("build/preview/" + name + ".png"));
    } catch (final Exception e) {
      throw new RuntimeException(e);
    }
  }

  private static void layout(final java.awt.Container c) {
    c.doLayout();
    for (final java.awt.Component child : c.getComponents()) {
      if (child instanceof java.awt.Container inner) {
        layout(inner);
      }
    }
  }
}
