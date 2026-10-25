package games.strategy.triplea.ui;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.gameparser.GameParser;
import games.strategy.triplea.delegate.battle.casualty.StickyCasualtyChoice.Group;
import games.strategy.triplea.delegate.battle.casualty.StickyHits;
import games.strategy.triplea.delegate.data.CasualtyList;
import games.strategy.triplea.settings.ClientSetting;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Request 28 part A: the casualty dialog on the installed Flames and Steel map (Sticky Hits on).
 * Also draws it to build/preview/casualties*.png.
 */
class StickyCasualtyPanelTest {
  private static final Path XML =
      Path.of(
          System.getProperty("user.home"),
          "triplea/downloadedMaps/flames_and_steel/map/games/flames_and_steel.xml");
  private static GameData data;
  private static GamePlayer germans;
  private static UiContext ui;

  @BeforeAll
  static void load() {
    Assumptions.assumeTrue(Files.exists(XML), "Flames and Steel map not installed");
    ClientSetting.initialize();
    data = games.strategy.triplea.ui.TestMaps.load();
    germans = data.getPlayerList().getPlayerId("Germans");
    ui = new UiContext(data);
  }

  private static List<Unit> units(final String type, final int count, final int hits) {
    return data.getUnitTypeList()
        .getUnitType(type)
        .orElseThrow()
        .create(count, germans, false, hits, 0);
  }

  private static CasualtySelection dialog(final List<Unit> units, final int hits) {
    final CasualtyList suggested = StickyHits.getDefaultCasualties(units, hits);
    return new CasualtySelection(
        units, Map.of(), hits, "Test battle", germans, suggested, true, ui, null, false);
  }

  @Test
  void oneHitPointBattlesKeepTheStockChooser() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          final List<Unit> units = new ArrayList<>(units("infantry", 3, 0));
          assertThat(Unit.maxHitPoints(units.get(0)), is(1));
          final CasualtySelection selection = dialog(units, 2);
          assertThat(selection.chooser, is(notNullValue()));
          assertThat(selection.stickyPanel, is(nullValue()));
        });
  }

  @Test
  void okIsEnabledOnlyForACompleteChoice() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          final List<Unit> units = new ArrayList<>(units("infantry", 2, 0));
          units.addAll(units("tank", 2, 0));
          final CasualtySelection selection = dialog(units, 3);
          final StickyCasualtyPanel panel = selection.stickyPanel;
          assertThat(panel, is(notNullValue()));
          // filled with the suggestion, as stock does
          assertThat(selection.okButton.isEnabled(), is(true));
          final Group infantry = panel.getChoice().getGroups().get(0);
          panel.removeHit(infantry);
          assertThat(selection.okButton.isEnabled(), is(false));
          assertThat(panel.getCounterText(), is("Hits to assign: 2 of 3"));
          panel.addHit(infantry);
          assertThat(selection.okButton.isEnabled(), is(true));
        });
  }

  @Test
  void drawPreview() throws Exception {
    final List<Unit> units = new ArrayList<>(units("infantry", 3, 0));
    final List<Unit> tanks = units("tank", 3, 0);
    units.addAll(tanks);
    units.addAll(units("fieldGun", 2, 0));
    final List<Unit> withDamaged = new ArrayList<>(units);
    withDamaged.addAll(units("tank", 1, 1));
    SwingUtilities.invokeAndWait(
        () -> {
          try {
            javax.swing.UIManager.setLookAndFeel(new com.formdev.flatlaf.FlatDarkLaf());
            final List<Unit> order = new ArrayList<>(units);
            snap(
                new StickyCasualtyPanel(
                    order,
                    4,
                    StickyHits.getDefaultCasualties(order, 4),
                    "Battle in Lithuania. Germans select 4 casualties.",
                    germans,
                    ui),
                "build/preview/casualties.png");
            final StickyCasualtyPanel damaged =
                new StickyCasualtyPanel(
                    withDamaged,
                    4,
                    StickyHits.getDefaultCasualties(withDamaged, 4),
                    "Battle in Lithuania. Germans select 4 casualties.",
                    germans,
                    ui);
            final Group tankGroup =
                damaged.getChoice().getGroups().stream()
                    .filter(g -> g.getType().getName().equals("tank"))
                    .findFirst()
                    .orElseThrow();
            damaged.getChoice().clear();
            damaged.addHit(tankGroup);
            damaged.addHit(tankGroup);
            snap(damaged, "build/preview/casualties-damaged.png");
          } catch (final Exception e) {
            throw new RuntimeException(e);
          }
        });
  }

  private static void snap(final StickyCasualtyPanel panel, final String file) throws Exception {
    final JFrame frame = new JFrame();
    frame.setBounds(-5000, -5000, 100, 100);
    frame.add(new JScrollPane(panel));
    frame.pack();
    final var c = frame.getContentPane();
    final BufferedImage img =
        new BufferedImage(c.getWidth(), c.getHeight(), BufferedImage.TYPE_INT_RGB);
    c.printAll(img.getGraphics());
    new File("build/preview").mkdirs();
    ImageIO.write(img, "png", new File(file));
    frame.dispose();
  }
}
