package games.strategy.triplea.odds.calculator;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.triplea.settings.ClientSetting;
import games.strategy.triplea.ui.TestMaps;
import games.strategy.triplea.ui.TestUi;
import games.strategy.triplea.ui.UiContext;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

/**
 * Request 58: the calculator opened on a G2-style Leningrad attack (5 German infantry from Estonia,
 * 4 landing from the Gulf of Finland) prefills "from sea" from the units' moves, and the units it
 * hands the battle carry that split. Renders build/preview/request58-leningrad.png.
 */
class Request58PreviewTest {
  @Test
  void leningradSplitIsPrefilled() throws Exception {
    ClientSetting.initialize();
    final GameData data = TestMaps.load();
    final UiContext ui = TestUi.create(data);
    final GamePlayer germans = data.getPlayerList().getPlayerId("Germans");
    final Territory leningrad = data.getMap().getTerritoryOrThrow("Leningrad");
    final List<Unit> overland =
        data.getUnitTypeList().getUnitTypeOrThrow("infantry").create(5, germans);
    final List<Unit> landing =
        data.getUnitTypeList().getUnitTypeOrThrow("infantry").create(4, germans);
    landing.forEach(u -> u.setWasAmphibious(true));
    leningrad.getUnitCollection().addAll(overland);
    leningrad.getUnitCollection().addAll(landing);
    // Germany's turn, as when the calculator is opened during its combat move
    data.getHistory()
        .getHistoryWriter()
        .startNextStep("germanCombatMove", "move", germans, "Combat Move");

    final AtomicReference<int[]> split = new AtomicReference<>();
    final AtomicReference<Long> sentLanding = new AtomicReference<>();
    SwingUtilities.invokeAndWait(
        () -> {
          try {
            javax.swing.UIManager.setLookAndFeel(new com.formdev.flatlaf.FlatDarkLaf());
          } catch (final Exception ex) {
            throw new RuntimeException(ex);
          }
          final BattleCalculatorPanel panel = new BattleCalculatorPanel(data, ui, leningrad);
          final JFrame frame = new JFrame();
          frame.setBounds(-5000, -5000, 1400, 900);
          frame.setContentPane(panel);
          frame.pack();
          frame.validate();
          try {
            final var c = frame.getContentPane();
            final BufferedImage img =
                new BufferedImage(c.getWidth(), c.getHeight(), BufferedImage.TYPE_INT_RGB);
            c.printAll(img.getGraphics());
            new File("build/preview").mkdirs();
            ImageIO.write(img, "png", new File("build/preview/request58-leningrad.png"));
          } catch (final Exception ex) {
            throw new RuntimeException(ex);
          }
          final PlayerUnitsPanel attackers = panel.getAttackingUnitsPanelForTest();
          split.set(attackers.landingAndOverland());
          sentLanding.set(
              attackers.getUnits().stream()
                  .filter(u -> u.getType().getName().equals("infantry"))
                  .filter(Unit::getWasAmphibious)
                  .count());
          frame.dispose();
        });
    // German infantry: 4 landing, 5 over land (other German land units in Leningrad: none)
    assertThat(split.get()[0], is(4));
    assertThat(sentLanding.get(), is(4L));
  }
}
