package games.strategy.triplea.ui;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.gameparser.GameParser;
import games.strategy.triplea.Constants;
import games.strategy.triplea.delegate.battle.casualty.StickyHits;
import games.strategy.triplea.delegate.data.CasualtyDetails;
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
 * Request 23: the sticky-hits casualty dialog on the installed Flames and Steel map, with the Pearl
 * Harbor fleet (2 destroyers, 2 cruisers, 4 battleships) taking 8 hits. Also draws the dialog to
 * build/preview/sticky-order*.png.
 */
class StickyHitOrderPanelTest {
  private static final Path XML =
      Path.of(
          System.getProperty("user.home"),
          "triplea/downloadedMaps/flames_and_steel/map/games/flames_and_steel.xml");
  private static GameData data;
  private static GamePlayer americans;

  @BeforeAll
  static void load() {
    Assumptions.assumeTrue(Files.exists(XML), "Flames and Steel map not installed");
    ClientSetting.initialize();
    data = GameParser.parse(XML, false).orElseThrow();
    americans = data.getPlayerList().getPlayerId("Americans");
  }

  private static List<Unit> units(final String type, final int count, final int hits) {
    return data.getUnitTypeList()
        .getUnitType(type)
        .orElseThrow()
        .create(count, americans, false, hits, 0);
  }

  private static List<Unit> pearlHarbor() {
    final List<Unit> fleet = new ArrayList<>(units("destroyer", 2, 0));
    fleet.addAll(units("cruiser", 2, 0));
    fleet.addAll(units("battleship", 4, 0));
    return fleet;
  }

  private static StickyHitOrderPanel panel(final List<Unit> units, final int hits) {
    return new StickyHitOrderPanel(
        units, Map.of(), hits, "Air strike on Sea Zone 225.", americans, null);
  }

  @Test
  void defaultOrderLeavesAtMostOneShipDamaged() throws Exception {
    final List<Unit> fleet = pearlHarbor();
    final List<Unit> order = new ArrayList<>(fleet.subList(2, 8));
    order.addAll(fleet.subList(0, 2));
    SwingUtilities.invokeAndWait(
        () -> {
          final StickyHitOrderPanel panel = panel(order, 8);
          final List<StickyHits.Allocation> hits = panel.getAllocations();
          // cruiser 2, cruiser 2, battleship 4 of 5
          assertThat(hits.size(), is(3));
          assertThat(hits.stream().filter(a -> !a.killed()).count(), is(1L));
          assertThat(hits.get(2).hitPointsLeftAfter(), is(1));
          assertThat(
              panel.getResultText(),
              is(
                  "<html>2 Cruisers sunk; 1 Battleship damaged, 1 HP left. "
                      + "(8 hits allocated, 0 left)</html>"));
          final CasualtyDetails details = panel.getCasualtyDetails();
          assertThat(details.getKilled(), contains(order.get(0), order.get(1)));
          assertThat(details.size(), is(8));
        });
  }

  @Test
  void clickingUnitsSetsTheOrder() throws Exception {
    final List<Unit> fleet = pearlHarbor();
    SwingUtilities.invokeAndWait(
        () -> {
          final StickyHitOrderPanel panel = panel(fleet, 8);
          final Unit battleship = fleet.get(4);
          final Unit cruiser = fleet.get(2);
          panel.clickUnit(battleship);
          panel.clickUnit(cruiser);
          // battleship sunk (5), cruiser sunk (2), then 1 hit on the first destroyer: sunk
          final List<StickyHits.Allocation> hits = panel.getAllocations();
          assertThat(hits.get(0).unit(), is(battleship));
          assertThat(hits.get(1).unit(), is(cruiser));
          assertThat(hits.stream().allMatch(StickyHits.Allocation::killed), is(true));
          panel.resetOrder();
          assertThat(panel.getOrder(), is(fleet));
        });
  }

  @Test
  void draggingARowMovesIt() throws Exception {
    final List<Unit> fleet = pearlHarbor();
    SwingUtilities.invokeAndWait(
        () -> {
          final StickyHitOrderPanel panel = panel(fleet, 3);
          // rows: destroyers x2 (sunk), cruiser (1 of 2), cruiser (not hit), battleships x4
          final int battleshipRow = panel.getRows().size() - 1;
          panel.moveRow(battleshipRow, 0);
          assertThat(panel.getOrder().get(0).getType().getName(), is("battleship"));
          assertThat(panel.getAllocations().size(), is(1));
          assertThat(panel.getAllocations().get(0).hitsTaken(), is(3));
        });
  }

  @Test
  void shipDamagedEarlierIsLockedFirst() throws Exception {
    final List<Unit> fleet = pearlHarbor();
    final Unit damaged = units("battleship", 1, 3).get(0);
    fleet.add(damaged);
    SwingUtilities.invokeAndWait(
        () -> {
          final StickyHitOrderPanel panel = panel(fleet, 3);
          assertThat(panel.getOrder().get(0), is(damaged));
          // it can't be clicked or dragged out of first place, nor anything dragged above it
          panel.clickUnit(fleet.get(2));
          panel.moveRow(panel.getRows().size() - 1, 0);
          assertThat(panel.getOrder().get(0), is(damaged));
          assertThat(panel.getAllocations().get(0).unit(), is(damaged));
          assertThat(panel.getAllocations().get(0).killed(), is(true));
        });
  }

  @Test
  void casualtyDialogShowsTheOrderWhenStickyHitsApply() throws Exception {
    final List<Unit> fleet = pearlHarbor();
    SwingUtilities.invokeAndWait(
        () -> {
          final boolean before = data.getProperties().get(Constants.STICKY_HITS, false);
          try {
            data.getProperties().set(Constants.STICKY_HITS, true);
            final CasualtySelection sticky =
                new CasualtySelection(
                    fleet, Map.of(), 8, "", americans, new CasualtyList(), true, null, null, false);
            assertThat(sticky.orderPanel, is(notNullValue()));
            assertThat(sticky.chooser, is(nullValue()));
            // only one-hit-point units: the stock chooser
            final CasualtySelection plain =
                new CasualtySelection(
                    fleet.subList(0, 2),
                    Map.of(),
                    1,
                    "",
                    americans,
                    new CasualtyList(),
                    true,
                    new UiContext(data),
                    null,
                    false);
            assertThat(plain.orderPanel, is(nullValue()));
          } finally {
            data.getProperties().set(Constants.STICKY_HITS, before);
          }
        });
  }

  @Test
  void drawPreview() throws Exception {
    final UiContext ui = new UiContext(data);
    final List<Unit> fleet = pearlHarbor();
    final List<Unit> order = new ArrayList<>(fleet.subList(2, 8));
    order.addAll(fleet.subList(0, 2));
    final List<Unit> withDamaged = new ArrayList<>(order);
    withDamaged.add(units("battleship", 1, 3).get(0));
    SwingUtilities.invokeAndWait(
        () -> {
          try {
            javax.swing.UIManager.setLookAndFeel(new com.formdev.flatlaf.FlatDarkLaf());
            snap(
                new StickyHitOrderPanel(
                    order,
                    Map.of(),
                    8,
                    "Air strike on Sea Zone 225. Americans select 8 casualties.",
                    americans,
                    ui),
                "build/preview/sticky-order.png");
            final StickyHitOrderPanel clicked =
                new StickyHitOrderPanel(
                    withDamaged,
                    Map.of(),
                    8,
                    "Air strike on Sea Zone 225. Americans select 8 casualties.",
                    americans,
                    ui);
            clicked.clickUnit(order.get(2));
            snap(clicked, "build/preview/sticky-order-damaged.png");
          } catch (final Exception e) {
            throw new RuntimeException(e);
          }
        });
  }

  private static void snap(final StickyHitOrderPanel panel, final String file) throws Exception {
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
