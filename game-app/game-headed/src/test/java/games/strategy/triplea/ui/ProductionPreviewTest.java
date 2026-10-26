package games.strategy.triplea.ui;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.gameparser.GameParser;
import games.strategy.triplea.settings.ClientSetting;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import org.junit.jupiter.api.Test;
import org.triplea.java.collections.IntegerMap;

/** Temporary: renders Germany's purchase window from the installed game XML. */
class ProductionPreviewTest {
  /** The window squeezed to a width, as on a smaller or scaled screen. */
  private static void snapTo(final JDialog d, final String file, final int width) throws Exception {
    d.setSize(width, d.getHeight());
    d.validate();
    final var c = d.getContentPane();
    final BufferedImage img =
        new BufferedImage(c.getWidth(), c.getHeight(), BufferedImage.TYPE_INT_RGB);
    c.printAll(img.getGraphics());
    new File("build/preview").mkdirs();
    ImageIO.write(img, "png", new File(file));
  }

  @Test
  void render() throws Exception {
    ClientSetting.initialize();
    final GameData data =
        games.strategy.triplea.ui.TestMaps.load();
    final UiContext ui = TestUi.create(data);
    final GamePlayer germans = data.getPlayerList().getPlayerId("Germans");
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
                          snapTo(d, "build/preview/production-narrow.png", 800);
                          d.pack();
                          final var c = d.getContentPane();
                          final BufferedImage img =
                              new BufferedImage(
                                  c.getWidth(), c.getHeight(), BufferedImage.TYPE_INT_RGB);
                          c.printAll(img.getGraphics());
                          new File("build/preview").mkdirs();
                          ImageIO.write(img, "png", new File("build/preview/production.png"));
                        } catch (final Exception ex) {
                          throw new RuntimeException(ex);
                        }
                        d.setVisible(false);
                      }
                    }
                  });
          snap.setRepeats(false);
          snap.start();
          // James's screenshot: dark theme, this purchase made
          try {
            javax.swing.UIManager.setLookAndFeel(new com.formdev.flatlaf.FlatDarkLaf());
          } catch (final Exception ex) {
            throw new RuntimeException(ex);
          }
          final IntegerMap<games.strategy.engine.data.ProductionRule> bought = new IntegerMap<>();
          for (final var rule : germans.getProductionFrontier()) {
            switch (rule.getName()) {
              case "buy_infantry" -> bought.put(rule, 5);
              case "buy_mobileInfantry" -> bought.put(rule, 1);
              case "buy_diveBomber" -> bought.put(rule, 3);
              case "buy_sturmtruppen" -> bought.put(rule, 9);
              default -> {}
            }
          }
          TabbedProductionPanel.getProduction(germans, owner, data, false, bought, ui);
          owner.dispose();
        });
  }
}
