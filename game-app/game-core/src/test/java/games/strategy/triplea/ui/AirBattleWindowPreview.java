package games.strategy.triplea.ui;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.triplea.delegate.air.AirBattleReport;
import games.strategy.triplea.delegate.air.AirBattleReport.Row;
import games.strategy.triplea.settings.ClientSetting;
import java.awt.Component;
import java.awt.Container;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;

/**
 * Request 46 tests: draws the air battles window for a viewing nation on the real map, to
 * build/preview/request46_&lt;nation&gt;.png, and returns its sections and every text it shows.
 */
public final class AirBattleWindowPreview {
  /** The window's sections (title to rows) and all its label and tooltip texts. */
  public record Seen(
      Map<String, List<Row>> sections, List<String> texts, Map<String, String> badges) {}

  private AirBattleWindowPreview() {}

  public static Seen draw(
      final GameData data, final AirBattleReport report, final GamePlayer viewer) throws Exception {
    ClientSetting.initialize();
    final UiContext ui = new UiContext(data);
    final Seen[] seen = new Seen[1];
    SwingUtilities.invokeAndWait(
        () -> {
          darkLook();
          final AirBattleSummaryPanel panel = new AirBattleSummaryPanel(data, ui, report, viewer);
          panel.setMaxViewportHeight(3000);
          final List<String> texts = new java.util.ArrayList<>();
          snap(panel, "build/preview/request46_" + viewer.getName() + ".png");
          collectText(panel, texts);
          seen[0] = new Seen(panel.getSections(), texts, panel.getBadges());
        });
    return seen[0];
  }

  private static void darkLook() {
    try {
      final Class<?> laf = Class.forName("com.formdev.flatlaf.FlatDarkLaf");
      javax.swing.UIManager.setLookAndFeel(
          (javax.swing.LookAndFeel) laf.getDeclaredConstructor().newInstance());
    } catch (final Exception e) {
      // the default look is fine for the tests
    }
  }

  private static void collectText(final Component c, final List<String> texts) {
    if (c instanceof JLabel label) {
      if (label.getText() != null && !label.getText().isEmpty()) {
        texts.add(label.getText());
      }
      if (label.getToolTipText() != null) {
        texts.add(label.getToolTipText());
      }
    }
    if (c instanceof javax.swing.text.JTextComponent area && !area.getText().isEmpty()) {
      texts.add(area.getText());
    }
    if (c instanceof Container container) {
      for (final Component child : container.getComponents()) {
        collectText(child, texts);
      }
    }
  }

  private static void snap(final AirBattleSummaryPanel panel, final String file) {
    final JFrame frame = new JFrame();
    frame.setBounds(-5000, -5000, 100, 100);
    frame.add(new JScrollPane(panel));
    frame.pack();
    // wrapped notes know their height once the window has a width
    frame.validate();
    frame.pack();
    final var c = frame.getContentPane();
    final BufferedImage img =
        new BufferedImage(c.getWidth(), c.getHeight(), BufferedImage.TYPE_INT_RGB);
    c.printAll(img.getGraphics());
    new File("build/preview").mkdirs();
    try {
      ImageIO.write(img, "png", new File(file));
    } catch (final IOException e) {
      throw new IllegalStateException(e);
    }
    frame.dispose();
  }
}
