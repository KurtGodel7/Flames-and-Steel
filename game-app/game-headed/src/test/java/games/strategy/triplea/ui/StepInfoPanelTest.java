package games.strategy.triplea.ui;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.triplea.settings.ClientSetting;
import games.strategy.triplea.ui.panels.map.MapPanel;
import java.awt.Component;
import java.awt.Container;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Request 34: the Actions tab during the research step has a button that reopens the research
 * window, and step headings use display names ("America Atlantic · Research").
 */
class StepInfoPanelTest {
  private static final Path XML =
      Path.of(
          System.getProperty("user.home"),
          "triplea/downloadedMaps/flames_and_steel/map/games/flames_and_steel.xml");

  private static void layoutAll(final Container c) {
    c.doLayout();
    for (final Component child : c.getComponents()) {
      if (child instanceof Container cc) {
        layoutAll(cc);
      }
    }
  }

  private static void flush() throws Exception {
    for (int i = 0; i < 3; i++) {
      SwingUtilities.invokeAndWait(() -> {});
    }
  }

  @Test
  void researchStepHasAReopenButtonAndDisplayNames() throws Exception {
    Assumptions.assumeTrue(Files.exists(XML), "Flames and Steel map not installed");
    ClientSetting.initialize();
    final GameData data = games.strategy.triplea.ui.TestMaps.load();
    final GamePlayer atlantic = data.getPlayerList().getPlayerId("Americans");
    SwingUtilities.invokeAndWait(
        () -> {
          try {
            javax.swing.UIManager.setLookAndFeel(new com.formdev.flatlaf.FlatDarkLaf());
          } catch (final Exception e) {
            throw new IllegalStateException(e);
          }
        });
    final MapPanel map = mock(MapPanel.class);
    when(map.getData()).thenReturn(data);
    final StepInfoPanel[] panel = new StepInfoPanel[1];
    final AtomicBoolean reopened = new AtomicBoolean();
    SwingUtilities.invokeAndWait(
        () -> {
          panel[0] = new StepInfoPanel(data, map);
          panel[0].setStep(
              atlantic,
              "Research",
              "Choose in the research window.",
              "Open research window",
              () -> reopened.set(true));
        });
    flush();
    assertThat(
        panel[0].getText(), is("America Atlantic · Research / Choose in the research window."));
    assertThat(panel[0].getButton().getText(), is("Open research window"));
    SwingUtilities.invokeAndWait(() -> panel[0].getButton().doClick());
    assertThat(reopened.get(), is(true));

    final BufferedImage image = new BufferedImage(420, 200, BufferedImage.TYPE_INT_RGB);
    SwingUtilities.invokeAndWait(
        () -> {
          panel[0].setSize(420, 200);
          layoutAll(panel[0]);
          panel[0].printAll(image.getGraphics());
        });
    ImageIO.write(
        image,
        "png",
        Path.of(System.getProperty("user.home"), "Documents", "TripleA-fork")
            .resolve("request34_research_step.png")
            .toFile());

    // other steps without a window keep a plain line, now with the display name too
    SwingUtilities.invokeAndWait(
        () ->
            panel[0].setStep(
                data.getPlayerList().getPlayerId("AmericansPacific"), "Purchase", "Waiting."));
    flush();
    assertThat(panel[0].getText(), is("America Pacific · Purchase / Waiting."));
    assertThat(panel[0].getButton() == null, is(true));
  }
}
