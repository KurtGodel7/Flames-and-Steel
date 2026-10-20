package games.strategy.triplea.ui;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.gameparser.GameParser;
import games.strategy.engine.framework.IGame;
import games.strategy.engine.framework.LocalPlayers;
import games.strategy.triplea.delegate.air.AirAllocation.Stage;
import games.strategy.triplea.settings.ClientSetting;
import games.strategy.triplea.ui.air.AirAllocationPanel;
import games.strategy.triplea.ui.air.AirTargetPanel;
import java.awt.Component;
import java.awt.Container;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Request 24: in a 1366 x 768 game window, the Done button of every air step stays visible and
 * clickable for the nations with the largest starting air forces (Germany, Japan). Opens the real
 * game window on the installed Flames and Steel map; saves each step's window to
 * build/preview/r24-*.png.
 */
class ActionPanelHeightTest {
  private static final Path XML =
      Path.of(
          System.getProperty("user.home"),
          "triplea/downloadedMaps/flames_and_steel/map/games/flames_and_steel.xml");
  private static GameData data;
  private static TripleAFrame frame;
  private static Timer answerDialogs;

  @BeforeAll
  static void openWindow() throws Exception {
    Assumptions.assumeTrue(Files.exists(XML), "Flames and Steel map not installed");
    Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "needs a screen");
    ClientSetting.initialize();
    data = GameParser.parse(XML, false).orElseThrow();
    final IGame game = mock(IGame.class);
    when(game.getData()).thenReturn(data);
    frame = TripleAFrame.create(game, new LocalPlayers(Set.of()), null, () -> {});
    SwingUtilities.invokeAndWait(
        () -> {
          frame.setExtendedState(Frame.NORMAL);
          frame.setBounds(0, 0, 1366, 768);
          frame.setVisible(true);
          // the step's own questions (e.g. "bombers left: end anyway?") are answered yes
          answerDialogs =
              new Timer(
                  300,
                  e -> {
                    for (final Window w : Window.getWindows()) {
                      if (w instanceof JDialog d && d.isVisible()) {
                        optionPane(d).ifPresent(p -> p.setValue(JOptionPane.YES_OPTION));
                      }
                    }
                  });
          answerDialogs.start();
        });
  }

  @AfterAll
  static void close() throws Exception {
    if (frame != null) {
      SwingUtilities.invokeAndWait(
          () -> {
            answerDialogs.stop();
            for (final Window w : Window.getWindows()) {
              w.dispose();
            }
          });
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"Germans", "Japanese"})
  void doneIsVisibleAndClickableInEveryAirStep(final String nation) throws Exception {
    final GamePlayer player = data.getPlayerList().getPlayerId(nation);
    for (final Stage stage : Stage.values()) {
      runStep(
          nation + "-" + stage.name().toLowerCase(java.util.Locale.ROOT),
          AirAllocationPanel.class,
          () -> frame.getAirAllocation(player, stage, List.of(), Map.of(), Map.of(), moves -> {}));
    }
    runStep(
        nation + "-targets",
        AirTargetPanel.class,
        () -> frame.getAirTargets(player, List.of(), strikes -> {}));
  }

  /** Starts the step, checks its Done button is wholly on screen, saves a picture, clicks Done. */
  private static void runStep(
      final String name, final Class<? extends ActionPanel> panelType, final Supplier<?> step)
      throws Exception {
    final CompletableFuture<Object> result = CompletableFuture.supplyAsync(step::get);
    Thread.sleep(2500);
    final AtomicReference<JButton> done = new AtomicReference<>();
    SwingUtilities.invokeAndWait(
        () -> {
          final List<JButton> buttons = new ArrayList<>();
          for (final Component panel : find(frame, panelType::isInstance)) {
            if (panel.isShowing()) {
              find((Container) panel, c -> c instanceof JButton b && b.getText().startsWith("Done"))
                  .forEach(c -> buttons.add((JButton) c));
            }
          }
          assertThat(name + ": one Done button in the Actions tab", buttons.size(), is(1));
          final JButton button = buttons.get(0);
          done.set(button);
          assertThat(name + ": Done is showing", button.isShowing(), is(true));
          assertThat(name + ": Done is enabled", button.isEnabled(), is(true));
          assertThat(
              name + ": Done is not cut off",
              button.getVisibleRect().getSize(),
              is(button.getSize()));
          final Point at = SwingUtilities.convertPoint(button, 0, 0, frame.getContentPane());
          final Rectangle inWindow = new Rectangle(at, button.getSize());
          assertThat(
              name + ": Done lies inside the window " + inWindow,
              new Rectangle(frame.getContentPane().getSize()).contains(inWindow),
              is(true));
          snap(name);
        });
    assertThat(done.get(), is(notNullValue()));
    SwingUtilities.invokeAndWait(() -> done.get().doClick());
    // the step ends once Done is clicked (questions are answered yes)
    result.get(20, TimeUnit.SECONDS);
    Thread.sleep(500);
  }

  private static void snap(final String name) {
    final Container c = frame.getContentPane();
    final BufferedImage img =
        new BufferedImage(c.getWidth(), c.getHeight(), BufferedImage.TYPE_INT_RGB);
    c.printAll(img.getGraphics());
    try {
      new File("build/preview").mkdirs();
      ImageIO.write(img, "png", new File("build/preview/r24-" + name + ".png"));
    } catch (final Exception e) {
      throw new RuntimeException(e);
    }
  }

  private static java.util.Optional<JOptionPane> optionPane(final Container c) {
    return find(c, JOptionPane.class::isInstance).stream().map(JOptionPane.class::cast).findFirst();
  }

  private static List<Component> find(
      final Container root, final java.util.function.Predicate<Component> match) {
    final List<Component> found = new ArrayList<>();
    for (final Component c : root.getComponents()) {
      if (match.test(c)) {
        found.add(c);
      }
      if (c instanceof Container inner) {
        found.addAll(find(inner, match));
      }
    }
    return found;
  }
}
