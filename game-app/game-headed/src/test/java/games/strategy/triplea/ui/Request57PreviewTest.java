package games.strategy.triplea.ui;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.triplea.delegate.GenericTechAdvance;
import games.strategy.triplea.delegate.TechAdvance;
import games.strategy.triplea.settings.ClientSetting;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import org.junit.jupiter.api.Test;
import org.triplea.java.collections.IntegerMap;

/**
 * Request 57: renders build screens from the installed game to build/preview/request57-*.png (the
 * SPC tile before and after Advanced 1, after buying one; the bomber tiles), for checking by eye.
 */
class Request57PreviewTest {
  private GameData data;
  private UiContext ui;

  @Test
  void render() throws Exception {
    ClientSetting.initialize();
    data = TestMaps.load();
    ui = TestUi.create(data);
    SwingUtilities.invokeAndWait(
        () -> {
          try {
            javax.swing.UIManager.setLookAndFeel(new com.formdev.flatlaf.FlatDarkLaf());
          } catch (final Exception ex) {
            throw new RuntimeException(ex);
          }
        });
    final GamePlayer british = data.getPlayerList().getPlayerId("British");
    final GamePlayer germans = data.getPlayerList().getPlayerId("Germans");
    final GamePlayer russians = data.getPlayerList().getPlayerId("Russians");
    show(british, "british-before");
    show(germans, "germans");
    grant(british, "Advanced1");
    show(british, "british-after-advanced1");
    // one SPC bought (not yet placed) counts towards the limit
    british
        .getUnitCollection()
        .addAll(data.getUnitTypeList().getUnitTypeOrThrow("apc").create(1, british));
    show(british, "british-after-buying-one");
    grant(russians, "Advanced1");
    show(russians, "russians-after-advanced1");
    // KV-2 unlocks the heavy tank as an SPC unit: "builds nothing yet" goes away
    grant(russians, "KV2");
    show(russians, "russians-after-kv2");
  }

  private void grant(final GamePlayer player, final String tech) {
    final TechAdvance advance = data.getTechnologyFrontier().getAdvanceByName(tech);
    data.performChange(
        advance instanceof GenericTechAdvance g && g.getAdvance() == null
            ? ChangeFactory.genericTechChange(
                player.getTechAttachment(), true, advance.getProperty())
            : ChangeFactory.attachmentPropertyChange(
                player.getTechAttachment(), "true", advance.getProperty()));
    data.getTechTracker().clearCache();
  }

  private void show(final GamePlayer player, final String name) throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          final JFrame owner = new JFrame();
          owner.setBounds(-5000, -5000, 1600, 1000);
          final Timer snap =
              new Timer(
                  1500,
                  e -> {
                    for (final Window w : Window.getWindows()) {
                      if (w instanceof JDialog d && d.isVisible()) {
                        try {
                          final var c = d.getContentPane();
                          final BufferedImage img =
                              new BufferedImage(
                                  c.getWidth(), c.getHeight(), BufferedImage.TYPE_INT_RGB);
                          c.printAll(img.getGraphics());
                          new File("build/preview").mkdirs();
                          ImageIO.write(
                              img, "png", new File("build/preview/request57-" + name + ".png"));
                        } catch (final Exception ex) {
                          throw new RuntimeException(ex);
                        }
                        d.setVisible(false);
                      }
                    }
                  });
          snap.setRepeats(false);
          snap.start();
          TabbedProductionPanel.getProduction(player, owner, data, false, new IntegerMap<>(), ui);
          owner.dispose();
        });
  }
}
