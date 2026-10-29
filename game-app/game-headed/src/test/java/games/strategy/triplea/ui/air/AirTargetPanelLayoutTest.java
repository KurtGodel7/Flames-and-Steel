package games.strategy.triplea.ui.air;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.UnitType;
import games.strategy.engine.data.gameparser.GameParser;
import games.strategy.triplea.delegate.air.AirCombat;
import games.strategy.triplea.delegate.air.AirCombat.Target;
import games.strategy.triplea.delegate.air.AirCombat.TargetAssignment;
import games.strategy.triplea.settings.ClientSetting;
import games.strategy.triplea.ui.TestUi;
import games.strategy.triplea.ui.panels.map.MapPanel;
import java.awt.Component;
import java.awt.Container;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import javax.swing.AbstractButton;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.triplea.java.collections.IntegerMap;

/**
 * Request 33 on the installed map, Germany round 1: the compact target panel at the height it has
 * in a 1920×1080 window, the pick-from-list window, and the Place Fighters variant. Writes
 * screenshots to TripleA-fork (request33_*.png).
 */
class AirTargetPanelLayoutTest {
  private static final Path XML =
      Path.of(
          System.getProperty("user.home"),
          "triplea/downloadedMaps/flames_and_steel/map/games/flames_and_steel.xml");
  // the right-hand panel of a maximised 1920x1080 window (title bar, menus, tabs and status bar
  // take the rest)
  private static final int WIDTH = 420;
  private static final int HEIGHT = 880;
  private static final Path SHOTS =
      Path.of(System.getProperty("user.home"), "Documents", "TripleA-fork");
  private GameData data;
  private GamePlayer germans;
  private AirTargetPanel panel;

  @BeforeEach
  void setUp() throws Exception {
    Assumptions.assumeTrue(Files.exists(XML), "Flames and Steel map not installed");
    ClientSetting.initialize();
    data = games.strategy.triplea.ui.TestMaps.load();
    germans = data.getPlayerList().getPlayerId("Germans");
    SwingUtilities.invokeAndWait(
        () -> {
          try {
            javax.swing.UIManager.setLookAndFeel(new com.formdev.flatlaf.FlatDarkLaf());
          } catch (final Exception e) {
            throw new IllegalStateException(e);
          }
        });
    map = mock(MapPanel.class);
    when(map.getData()).thenReturn(data);
    final games.strategy.triplea.ui.UiContext ui = TestUi.create(data);
    final AirTargetPanel.Host frame =
        new AirTargetPanel.Host() {
          @Override
          public MapPanel getMapPanel() {
            return map;
          }

          @Override
          public games.strategy.triplea.ui.UiContext getUiContext() {
            return ui;
          }

          @Override
          public AirMapMode getAirMapMode() {
            return null;
          }

          @Override
          public void setStatusMessage(final String message) {}

          @Override
          public void setStatusErrorMessage(final String message) {}

          @Override
          public void setStatusWarningMessage(final String message) {}

          @Override
          public void clearStatusMessage() {}
        };
    prepareBase();
    final AtomicReference<AirTargetPanel> made = new AtomicReference<>();
    SwingUtilities.invokeAndWait(() -> made.set(new AirTargetPanel(frame)));
    panel = made.get();
  }

  // request 38: German bombers on a base next to Soviet land, at war
  private Territory base;
  // request 40: two more German bases with bombers, and the mocked map (to check it never scrolls)
  private final List<Territory> bases = new ArrayList<>();
  private MapPanel map;

  private void prepareBase() {
    final GamePlayer russians = data.getPlayerList().getPlayerId("Russians");
    data.performChange(
        games.strategy.engine.data.changefactory.ChangeFactory.relationshipChange(
            germans,
            russians,
            data.getRelationshipTracker().getRelationshipType(germans, russians),
            data.getRelationshipTypeList().getRelationshipType("War")));
    for (final Territory t : data.getMap().getTerritories()) {
      if (!t.isWater()
          && t.isOwnedBy(germans)
          && data.getMap().getNeighbors(t).stream().anyMatch(n -> n.isOwnedBy(russians))) {
        bases.add(t);
        if (bases.size() == 3) {
          break;
        }
      }
    }
    base = bases.isEmpty() ? null : bases.get(0);
    for (final Territory t : bases.subList(Math.min(1, bases.size()), bases.size())) {
      data.performChange(
          games.strategy.engine.data.changefactory.ChangeFactory.addUnits(
              t, data.getUnitTypeList().getUnitTypeOrThrow("mediumBomber").create(2, germans)));
    }
    Assumptions.assumeTrue(base != null, "no German land next to the Soviets");
    data.performChange(
        games.strategy.engine.data.changefactory.ChangeFactory.addUnits(
            base, data.getUnitTypeList().getUnitTypeOrThrow("mediumBomber").create(12, germans)));
    data.performChange(
        games.strategy.engine.data.changefactory.ChangeFactory.addUnits(
            base, data.getUnitTypeList().getUnitTypeOrThrow("fighter").create(4, germans)));
  }

  /** The German base with the bombers. */
  private Territory busiestFront() {
    return base;
  }

  private void startPanel(final boolean cover) throws Exception {
    final Map<GamePlayer, List<TargetAssignment>> bombers =
        cover
            ? Map.of(germans, List.of(new TargetAssignment(base, medium(), 1, firstTarget())))
            : Map.of();
    SwingUtilities.invokeAndWait(
        () -> {
          panel.display(germans);
          panel.startStage(
              germans,
              cover
                  ? games.strategy.triplea.delegate.air.AirStage.Stage.FIGHTERS
                  : games.strategy.triplea.delegate.air.AirStage.Stage.BOMBERS,
              List.of(),
              bombers,
              staged -> {});
        });
    flush();
  }

  private UnitType medium() {
    return data.getUnitTypeList().getUnitTypeOrThrow("mediumBomber");
  }

  private Target firstTarget() {
    return games.strategy.triplea.delegate.air.AirStage.candidates(
            germans,
            base,
            medium(),
            games.strategy.triplea.delegate.air.AirStage.Stage.BOMBERS,
            Map.of())
        .get(0);
  }

  private static void flush() throws Exception {
    for (int i = 0; i < 4; i++) {
      SwingUtilities.invokeAndWait(() -> {});
    }
  }

  private static void layoutAll(final Container c) {
    c.doLayout();
    for (final Component child : c.getComponents()) {
      if (child instanceof Container cc) {
        layoutAll(cc);
      }
    }
  }

  private BufferedImage render(final Container c, final int width, final int height) {
    final AtomicReference<BufferedImage> img = new AtomicReference<>();
    try {
      SwingUtilities.invokeAndWait(
          () -> {
            c.setSize(width, height);
            layoutAll(c);
            final BufferedImage image =
                new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            c.printAll(image.getGraphics());
            img.set(image);
          });
    } catch (final Exception e) {
      throw new IllegalStateException(e);
    }
    return img.get();
  }

  private static List<Component> all(final Container c) {
    final List<Component> result = new ArrayList<>();
    for (final Component child : c.getComponents()) {
      result.add(child);
      if (child instanceof Container cc) {
        result.addAll(all(cc));
      }
    }
    return result;
  }

  private List<AbstractButton> buttons(final String text) {
    return all(panel).stream()
        .filter(c -> c instanceof AbstractButton b && text.equals(b.getText()))
        .map(c -> (AbstractButton) c)
        .toList();
  }

  /** Whether the component lies wholly inside the panel's visible height. */
  private boolean inView(final Component c) {
    final Rectangle r = SwingUtilities.convertRectangle(c.getParent(), c.getBounds(), panel);
    return r.y >= 0 && r.y + r.height <= HEIGHT;
  }

  /** The first target in reach of the front's bombers, and one bomber type that can hit it. */
  private TargetAssignment firstMove(final Territory zone) {
    final List<Target> targets = panel.targetsInReach(germans, zone);
    final Target target = targets.get(0);
    for (final UnitType type : panel.unassigned(germans, zone).keySet()) {
      if (games.strategy.triplea.delegate.air.AirStage.candidates(
              germans,
              zone,
              type,
              games.strategy.triplea.delegate.air.AirStage.Stage.BOMBERS,
              Map.of())
          .contains(target)) {
        return new TargetAssignment(zone, type, 1, target);
      }
    }
    throw new IllegalStateException("no bomber can reach " + target);
  }

  @Test
  void aMoveAndItsUndoAreInViewWithManyTargets() throws Exception {
    startPanel(false);
    final Territory zone = busiestFront();
    final TargetAssignment move = firstMove(zone);
    final IntegerMap<UnitType> one = new IntegerMap<>();
    one.put(move.unitType(), 1);
    SwingUtilities.invokeAndWait(
        () -> {
          panel.chooseFront(germans, zone, false);
          panel.assign(move.target(), one);
        });
    flush();
    final BufferedImage shot = render(panel, WIDTH, HEIGHT);
    ImageIO.write(shot, "png", SHOTS.resolve("request42_bomber_stage.png").toFile());

    assertThat(
        "targets in reach: " + panel.targetsInReach(germans, zone).size(),
        panel.targetsInReach(germans, zone).size(),
        greaterThanOrEqualTo(2));
    assertThat(panel.staged().size(), is(1));
    final List<AbstractButton> undo = buttons("Undo");
    assertThat(undo.size(), is(1));
    assertThat("the Undo button is in view", inView(undo.get(0)), is(true));
    // the side panel never lists every target: one Attack button, for the selected target
    assertThat(buttons("Attack").size(), lessThanOrEqualTo(1));
    assertThat(buttons("Pick from list…").size(), is(1));
    assertThat(
        all(panel).stream()
            .filter(c -> c instanceof JLabel l && l.getText() != null)
            .map(c -> ((JLabel) c).getText())
            .anyMatch(t -> t.contains(" -> ")),
        is(true));
  }

  @Test
  void tenMovesFitAndUndoGivesTheAircraftBack() throws Exception {
    startPanel(false);
    final Territory zone = busiestFront();
    final TargetAssignment move = firstMove(zone);
    final int before = panel.unassigned(germans, zone).getInt(move.unitType());
    final IntegerMap<UnitType> one = new IntegerMap<>();
    one.put(move.unitType(), 1);
    SwingUtilities.invokeAndWait(
        () -> {
          panel.chooseFront(germans, zone, false);
          panel.assign(move.target(), one);
        });
    flush();
    assertThat(panel.unassigned(germans, zone).getInt(move.unitType()), is(before - 1));
    SwingUtilities.invokeAndWait(() -> panel.undo(panel.staged().get(0)));
    flush();
    assertThat(panel.staged().isEmpty(), is(true));
    assertThat(panel.unassigned(germans, zone).getInt(move.unitType()), is(before));
    // Ctrl+Z works on the newest move
    SwingUtilities.invokeAndWait(
        () -> {
          panel.chooseFront(germans, zone, false);
          panel.assign(move.target(), one);
          panel.undoLast();
        });
    flush();
    assertThat(panel.staged().isEmpty(), is(true));
  }

  @Test
  void tenMovesMadeStillFitAt1080() throws Exception {
    final Territory zone = busiestFront();
    final UnitType dive = medium();
    startPanel(false);
    final List<Target> reach =
        panel.targetsInReach(germans, zone).stream()
            .filter(t -> t.kind() == games.strategy.triplea.delegate.air.AirCombat.TargetKind.LAND)
            .limit(10)
            .toList();
    Assumptions.assumeTrue(reach.size() == 10, "fewer than 10 medium bomber targets");
    final IntegerMap<UnitType> one = new IntegerMap<>();
    one.put(dive, 1);
    SwingUtilities.invokeAndWait(
        () -> {
          // request 40: each move clears the selection, so the base is picked again each time
          reach.forEach(
              t -> {
                panel.chooseFront(germans, zone, false);
                panel.assign(t, one);
              });
        });
    flush();
    final BufferedImage shot = render(panel, WIDTH, HEIGHT);
    ImageIO.write(shot, "png", SHOTS.resolve("request41_bomber_stage_10_moves.png").toFile());
    assertThat(panel.staged().size(), is(10));
    for (final AbstractButton undo : buttons("Undo")) {
      assertThat("every Undo is in view", inView(undo), is(true));
    }
    assertThat(inView(buttons("Pick from list…").get(0)), is(true));
  }

  @Test
  void showBringsTheMoveIntoViewAndSelectsNothing() throws Exception {
    startPanel(false);
    final Territory zone = busiestFront();
    final TargetAssignment move = firstMove(zone);
    final IntegerMap<UnitType> one = new IntegerMap<>();
    one.put(move.unitType(), 1);
    SwingUtilities.invokeAndWait(
        () -> {
          panel.chooseFront(germans, zone, false);
          panel.assign(move.target(), one);
          panel.showMove(panel.staged().get(0));
        });
    flush();
    assertThat(panel.selectedZone() == null, is(true));
    assertThat(buttons("Show").size(), is(1));
    assertThat(buttons("Undo").size(), is(1));
    assertThat(buttons("Undo All").size(), is(1));
  }

  @Test
  void assigningInTheListWindowIsTheSameAsOnTheMap() throws Exception {
    startPanel(false);
    final Territory zone = busiestFront();
    final TargetAssignment move = firstMove(zone);
    SwingUtilities.invokeAndWait(
        () -> {
          panel.chooseFront(germans, zone, false);
          panel.openPickDialog(germans);
        });
    flush();
    final AirTargetPanel.PickTargetDialog dialog = panel.pickDialog();
    assertThat(dialog != null, is(true));
    SwingUtilities.invokeAndWait(
        () -> {
          // every target in reach is listed, and only in the window
          assertThat(
              dialog.targetList().getModel().getSize(),
              is(panel.targetsInReach(germans, zone).size()));
          dialog.targetList().setSelectedValue(move.target(), true);
          dialog.counts().put(move.unitType(), 1);
        });
    flush();
    assertThat("selecting in the list selects on the map", panel.selectedTarget(), is(move.target()));
    final BufferedImage shot = render(dialog.getContentPane(), 820, 480);
    ImageIO.write(shot, "png", SHOTS.resolve("request41_pick_window.png").toFile());
    SwingUtilities.invokeAndWait(
        () ->
            all(dialog.getContentPane()).stream()
                .filter(c -> c instanceof AbstractButton b && "Assign".equals(b.getText()))
                .map(c -> (AbstractButton) c)
                .findFirst()
                .orElseThrow()
                .doClick());
    flush();
    assertThat(panel.staged(), hasItem(move));
    SwingUtilities.invokeAndWait(dialog::dispose);
  }

  @Test
  void placeFightersUsesTheSameLayout() throws Exception {
    startPanel(true);
    flush();
    final BufferedImage shot = render(panel, WIDTH, HEIGHT);
    ImageIO.write(shot, "png", SHOTS.resolve("request41_fighter_stage.png").toFile());
    assertThat(
        all(panel).stream()
            .filter(c -> c instanceof JLabel l && l.getText() != null)
            .map(c -> ((JLabel) c).getText())
            .anyMatch(t -> t.contains("Fighters — escort and defend")),
        is(true));
    assertThat(buttons("Pick from list…").size(), is(1));
  }
  // ---- request 40: the player chooses the order

  @Test
  void openingAStageSelectsNothing() throws Exception {
    startPanel(false);
    assertThat(panel.selectedZone() == null, is(true));
    verify(map, never()).centerOn(any());
  }

  @Test
  void afterAMoveNothingIsSelectedAndTheMapHasNotScrolled() throws Exception {
    startPanel(false);
    final TargetAssignment move = firstMove(base);
    final IntegerMap<UnitType> one = new IntegerMap<>();
    one.put(move.unitType(), 1);
    SwingUtilities.invokeAndWait(
        () -> {
          panel.chooseFront(germans, base, false);
          panel.assign(move.target(), one);
        });
    flush();
    assertThat(panel.staged().size(), is(1));
    assertThat(panel.selectedZone() == null, is(true));
    verify(map, never()).centerOn(any());
  }

  @Test
  void aircraftOnThreeBasesMoveInTheOrderThePlayerChooses() throws Exception {
    Assumptions.assumeTrue(bases.size() == 3, "fewer than three German bases at the front");
    startPanel(false);
    final List<Territory> order = List.of(bases.get(2), bases.get(0), bases.get(1));
    for (final Territory b : order) {
      final TargetAssignment move = firstMove(b);
      final IntegerMap<UnitType> one = new IntegerMap<>();
      one.put(move.unitType(), 1);
      SwingUtilities.invokeAndWait(
          () -> {
            panel.chooseFront(germans, b, false);
            panel.assign(move.target(), one);
          });
      flush();
    }
    assertThat(panel.staged().stream().map(TargetAssignment::zone).toList(), is(order));
  }

  // ---- request 41: the stock move controls

  private static games.strategy.triplea.ui.MouseDetails mouse(final int modifiers, final int button) {
    final java.awt.event.MouseEvent e =
        new java.awt.event.MouseEvent(
            new javax.swing.JPanel(),
            java.awt.event.MouseEvent.MOUSE_RELEASED,
            0,
            modifiers,
            0,
            0,
            1,
            false,
            button);
    return new games.strategy.triplea.ui.MouseDetails(e, 0, 0);
  }

  private static games.strategy.triplea.ui.MouseDetails click() {
    return mouse(0, java.awt.event.MouseEvent.BUTTON1);
  }

  private static games.strategy.triplea.ui.MouseDetails ctrlClick() {
    return mouse(java.awt.event.InputEvent.CTRL_DOWN_MASK, java.awt.event.MouseEvent.BUTTON1);
  }

  private static games.strategy.triplea.ui.MouseDetails shiftClick() {
    return mouse(java.awt.event.InputEvent.SHIFT_DOWN_MASK, java.awt.event.MouseEvent.BUTTON1);
  }

  /** Two German dive bombers in Cracow, a Soviet army in Bialystok next door. */
  private Territory[] cracowAndBialystok() {
    final Territory cracow = data.getMap().getTerritoryOrThrow("Cracow");
    final Territory bialystok = data.getMap().getTerritoryOrThrow("Bialystok");
    Assumptions.assumeTrue(cracow.isOwnedBy(germans), "Cracow is not German");
    final var dive = data.getUnitTypeList().getUnitTypeOrThrow("diveBomber");
    data.performChange(
        games.strategy.engine.data.changefactory.ChangeFactory.removeUnits(
            cracow, cracow.getUnitCollection().getMatches(u -> u.getType().equals(dive))));
    data.performChange(
        games.strategy.engine.data.changefactory.ChangeFactory.addUnits(
            cracow, dive.create(2, germans)));
    data.performChange(
        games.strategy.engine.data.changefactory.ChangeFactory.addUnits(
            bialystok,
            data.getUnitTypeList()
                .getUnitTypeOrThrow("infantry")
                .create(2, data.getPlayerList().getPlayerId("Russians"))));
    return new Territory[] {cracow, bialystok};
  }

  private List<games.strategy.engine.data.Unit> dives(final Territory t) {
    return t.getUnitCollection()
        .getMatches(u -> u.getType().getName().equals("diveBomber") && u.isOwnedBy(germans));
  }

  @Test
  void request45AClickOnOwnLandWithNothingToAttackIsARebase() throws Exception {
    final Territory[] t = cracowAndBialystok();
    final var player = germans;
    final Territory home =
        data.getMap().getNeighbors(t[0]).stream()
            .filter(
                n ->
                    !n.isWater()
                        && games.strategy.triplea.delegate.air.AirBases.isLandBase(player, n)
                        && games.strategy.triplea.delegate.air.AirCombat.targetsIn(player, n)
                            .isEmpty())
            .findFirst()
            .orElseThrow();
    final var dive = data.getUnitTypeList().getUnitTypeOrThrow("diveBomber");
    final int range = games.strategy.triplea.delegate.air.AirRange.range(dive, player);
    final Territory far =
        data.getMap().getTerritories().stream()
            .filter(
                n ->
                    !n.isWater()
                        && n.isOwnedBy(player)
                        && games.strategy.triplea.delegate.air.AirCombat.targetsIn(player, n)
                            .isEmpty()
                        && games.strategy.triplea.delegate.air.AirBases.distance(
                                player, t[0], n, range)
                            .isEmpty())
            .findFirst()
            .orElseThrow();
    startPanel(false);
    SwingUtilities.invokeAndWait(
        () -> {
          panel.click(dives(t[0]), t[0], click());
          panel.click(List.of(), far, click());
        });
    flush();
    assertThat(panel.staged().size(), is(0));
    SwingUtilities.invokeAndWait(() -> panel.click(List.of(), home, click()));
    flush();
    assertThat(panel.staged().size(), is(1));
    assertThat(
        panel.staged().get(0).target().kind(),
        is(games.strategy.triplea.delegate.air.AirCombat.TargetKind.REBASE));
    assertThat(
        panel.moveText(germans, panel.staged().get(0)),
        is(t[0].getName() + " -> " + home.getName() + " (rebase)"));
  }

  @Test
  void aPlainClickMovesOneDiveBomberAndCtrlClickTheWholeStack() throws Exception {
    final Territory[] t = cracowAndBialystok();
    startPanel(false);
    SwingUtilities.invokeAndWait(
        () -> {
          panel.click(dives(t[0]), t[0], click());
          panel.click(List.of(), t[1], click());
        });
    flush();
    assertThat(panel.staged().size(), is(1));
    assertThat(panel.staged().get(0).count(), is(1));
    assertThat(panel.staged().get(0).target().territory(), is(t[1]));
    // the stock "all of the stack" click: the other one (the only one left) goes too
    SwingUtilities.invokeAndWait(() -> panel.undoLast());
    SwingUtilities.invokeAndWait(
        () -> {
          panel.click(dives(t[0]), t[0], ctrlClick());
          panel.click(List.of(), t[1], click());
        });
    flush();
    assertThat(panel.staged().size(), is(1));
    assertThat(panel.staged().get(0).count(), is(2));
  }

  @Test
  void rightClickTakesOneOffAndEscapeLikeCancelClearsAll() throws Exception {
    final Territory[] t = cracowAndBialystok();
    startPanel(false);
    SwingUtilities.invokeAndWait(
        () -> {
          panel.click(dives(t[0]), t[0], ctrlClick());
          panel.click(dives(t[0]), t[0], mouse(0, java.awt.event.MouseEvent.BUTTON3));
        });
    flush();
    assertThat(panel.selectedCounts().totalValues(), is(1));
    SwingUtilities.invokeAndWait(
        () -> panel.click(List.of(), t[1], mouse(0, java.awt.event.MouseEvent.BUTTON3)));
    flush();
    assertThat(panel.selectedZone() == null, is(true));
  }

  @Test
  void moreThanTheCapAllowsOpensTheStockUnitChooserLimitedToWhatFits() throws Exception {
    final Territory[] t = cracowAndBialystok();
    final var medium = data.getUnitTypeList().getUnitTypeOrThrow("mediumBomber");
    data.performChange(
        games.strategy.engine.data.changefactory.ChangeFactory.addUnits(
            t[0], medium.create(6, germans)));
    startPanel(false);
    final java.util.concurrent.atomic.AtomicReference<String> asked =
        new java.util.concurrent.atomic.AtomicReference<>();
    panel.setChooserAnswer(
        (chooser, title) -> {
          asked.set(title);
          return true;
        });
    SwingUtilities.invokeAndWait(
        () -> {
          panel.click(
              t[0].getUnitCollection().getMatches(u -> u.getType().equals(medium)),
              t[0],
              ctrlClick());
          panel.click(List.of(), t[1], click());
        });
    flush();
    assertThat("the chooser was shown", asked.get() != null, is(true));
    assertThat(asked.get(), org.hamcrest.Matchers.containsString("room for 4"));
    assertThat(panel.staged().stream().mapToInt(TargetAssignment::count).sum(), is(4));
  }

  @Test
  void noBaseListOrNextBaseRemainsAndUndoAllStillWorks() throws Exception {
    final Territory[] t = cracowAndBialystok();
    startPanel(false);
    SwingUtilities.invokeAndWait(
        () -> {
          panel.click(dives(t[0]), t[0], click());
          panel.click(List.of(), t[1], click());
          panel.click(dives(t[0]), t[0], click());
          panel.click(List.of(), t[1], click());
        });
    flush();
    assertThat("two moves, one dive bomber each", panel.staged().size(), is(2));
    final BufferedImage shot = render(panel, WIDTH, HEIGHT);
    ImageIO.write(shot, "png", SHOTS.resolve("request42_bomber_stage_two_moves.png").toFile());
    assertThat(
        all(panel).stream().anyMatch(c -> c instanceof javax.swing.JComboBox), is(false));
    assertThat(buttons("Next base").isEmpty(), is(true));
    // request 47: the line is a link that goes to the next aircraft without orders
    assertThat(
        all(panel).stream()
            .filter(c -> c instanceof AbstractButton b && b.getText() != null)
            .map(c -> ((AbstractButton) c).getText())
            .anyMatch(text -> text.contains("Aircraft without orders: ")),
        is(true));
    assertThat(panel.staged().isEmpty(), is(false));
    SwingUtilities.invokeAndWait(() -> buttons("Undo All").get(0).doClick());
    flush();
    assertThat(panel.staged().isEmpty(), is(true));
  }
  // ---- request 42: the stock route arrow; undo selects nothing

  @Test
  void pointingAtATargetDrawsTheStockRouteWithTheOneSelectedBomber() throws Exception {
    final Territory[] t = cracowAndBialystok();
    startPanel(false);
    SwingUtilities.invokeAndWait(
        () -> {
          panel.click(dives(t[0]), t[0], click());
          panel.pointAt(t[1], new java.awt.Point(10, 10));
        });
    flush();
    final org.mockito.ArgumentCaptor<games.strategy.engine.data.Route> route =
        org.mockito.ArgumentCaptor.forClass(games.strategy.engine.data.Route.class);
    verify(map, org.mockito.Mockito.atLeastOnce()).setRoute(route.capture(), any(), any(), any());
    final games.strategy.engine.data.Route last = route.getAllValues().get(route.getAllValues().size() - 1);
    assertThat(last.getStart(), is(t[0]));
    assertThat(last.getEnd(), is(t[1]));
    final org.mockito.ArgumentCaptor<java.util.Collection<games.strategy.engine.data.Unit>> shadow =
        org.mockito.ArgumentCaptor.forClass(java.util.Collection.class);
    verify(map, org.mockito.Mockito.atLeastOnce()).setMouseShadowUnits(shadow.capture());
    final java.util.Collection<games.strategy.engine.data.Unit> shown =
        shadow.getAllValues().get(shadow.getAllValues().size() - 1);
    assertThat("one of the two dive bombers at the mouse", shown.size(), is(1));
  }

  @Test
  void undoRestoresTheAircraftAndSelectsNothing() throws Exception {
    final Territory[] t = cracowAndBialystok();
    startPanel(false);
    SwingUtilities.invokeAndWait(
        () -> {
          panel.click(dives(t[0]), t[0], click());
          panel.click(List.of(), t[1], click());
          panel.click(dives(t[0]), t[0], click());
        });
    flush();
    assertThat(panel.selectedCounts().totalValues(), is(1));
    SwingUtilities.invokeAndWait(() -> buttons("Undo").get(0).doClick());
    flush();
    assertThat(panel.staged().isEmpty(), is(true));
    // the undo didn't add the other bomber to the selection
    assertThat(panel.selectedCounts().totalValues(), is(1));
    assertThat(panel.unassigned(germans, t[0]).totalValues() >= 2, is(true));
  }

  // ---- request 44: a moved aircraft only where it goes, stacked; Undo buttons in view

  /** The last set of units the panel asked the map to leave off their territory. */
  @SuppressWarnings("unchecked")
  private java.util.Collection<games.strategy.engine.data.Unit> offMap() {
    final org.mockito.ArgumentCaptor<java.util.Collection<games.strategy.engine.data.Unit>> sent =
        org.mockito.ArgumentCaptor.forClass(java.util.Collection.class);
    verify(map, org.mockito.Mockito.atLeastOnce()).setUnitsOffMap(sent.capture());
    return sent.getAllValues().get(sent.getAllValues().size() - 1);
  }

  @Test
  void aMovedDiveBomberIsDrawnOnlyWhereItGoesAndUndoPutsItBack() throws Exception {
    final Territory[] t = cracowAndBialystok();
    startPanel(false);
    SwingUtilities.invokeAndWait(
        () -> {
          panel.click(dives(t[0]), t[0], click());
          panel.click(List.of(), t[1], click());
        });
    flush();
    // Cracow shows 1, Bialystok 1
    assertThat(offMap().size(), is(1));
    assertThat(dives(t[0]).containsAll(offMap()), is(true));
    assertThat(panel.ordered().get(t[1]).size(), is(1));
    SwingUtilities.invokeAndWait(() -> buttons("Undo").get(0).doClick());
    flush();
    // Cracow shows 2 again
    assertThat(offMap().isEmpty(), is(true));
    assertThat(panel.ordered().isEmpty(), is(true));
  }

  @Test
  void twoDiveBombersSentInTwoMovesAreOneStackOfTwo() throws Exception {
    final Territory[] t = cracowAndBialystok();
    startPanel(false);
    SwingUtilities.invokeAndWait(
        () -> {
          panel.click(dives(t[0]), t[0], click());
          panel.click(List.of(), t[1], click());
          panel.click(dives(t[0]), t[0], click());
          panel.click(List.of(), t[1], click());
        });
    flush();
    assertThat(panel.staged().size(), is(2));
    // Cracow shows none, Bialystok one stack "2"
    assertThat(offMap().size(), is(2));
    final List<AirDraw.Stack> stacks = AirDraw.stacks(panel.ordered().get(t[1]));
    assertThat(stacks.size(), is(1));
    assertThat(stacks.get(0).units().size(), is(2));
  }

  @Test
  void everyUndoButtonIsInsideThePanelAtItsNormalWidth() throws Exception {
    final Territory[] t = cracowAndBialystok();
    startPanel(false);
    SwingUtilities.invokeAndWait(
        () -> {
          panel.click(dives(t[0]), t[0], click());
          panel.click(List.of(), t[1], click());
          panel.click(dives(t[0]), t[0], click());
          panel.click(List.of(), t[1], click());
        });
    flush();
    for (final int width : List.of(WIDTH, 300, 260)) {
      final BufferedImage shot = render(panel, width, HEIGHT);
      if (width == WIDTH) {
        ImageIO.write(shot, "png", SHOTS.resolve("request44_bomber_stage_two_moves.png").toFile());
      }
      for (final String text : List.of("Show", "Undo")) {
        final List<AbstractButton> found = buttons(text);
        assertThat(text + " buttons", found.size(), is(2));
        for (final AbstractButton b : found) {
          final Rectangle r = SwingUtilities.convertRectangle(b.getParent(), b.getBounds(), panel);
          assertThat(
              text + " at width " + width + ": " + r, r.x >= 0 && r.x + r.width <= width, is(true));
        }
      }
    }
  }

  /**
   * Request 47: in the game's own surroundings (a 1440×900 window, the side panel at its default
   * width in the split pane, under the tabs), no part of any Show or Undo button is hidden.
   */
  @Test
  void undoIsWhollyVisibleInTheGameWindowAtTheDefaultWidth() throws Exception {
    final Territory[] t = cracowAndBialystok();
    startPanel(false);
    SwingUtilities.invokeAndWait(
        () -> {
          panel.click(dives(t[0]), t[0], click());
          panel.click(List.of(), t[1], click());
          panel.click(dives(t[0]), t[0], click());
          panel.click(List.of(), t[1], click());
        });
    flush();
    final javax.swing.JFrame window = new javax.swing.JFrame();
    SwingUtilities.invokeAndWait(
        () -> {
          final javax.swing.JPanel side = new javax.swing.JPanel(new java.awt.BorderLayout());
          final javax.swing.JPanel smallMap = new javax.swing.JPanel();
          smallMap.setPreferredSize(new java.awt.Dimension(400, 185));
          side.add(smallMap, java.awt.BorderLayout.NORTH);
          final javax.swing.JTabbedPane tabs = new javax.swing.JTabbedPane();
          tabs.addTab("Actions", panel);
          for (final String tab : List.of("Players", "Resources", "Research", "Territory")) {
            tabs.addTab(tab, new javax.swing.JPanel());
          }
          side.add(tabs, java.awt.BorderLayout.CENTER);
          side.setPreferredSize(new java.awt.Dimension(400, 800));
          final javax.swing.JPanel mapArea = new javax.swing.JPanel();
          mapArea.setPreferredSize(new java.awt.Dimension(3000, 800));
          final javax.swing.JSplitPane split =
              new javax.swing.JSplitPane(javax.swing.JSplitPane.HORIZONTAL_SPLIT, mapArea, side);
          split.setDividerSize(8);
          split.setResizeWeight(1.0);
          window.add(split);
          window.pack();
          window.setBounds(-5000, -5000, 1440, 900);
          window.validate();
          split.resetToPreferredSizes();
          window.validate();
          layoutAll(window.getContentPane());
        });
    flush();
    final List<String> hidden = new ArrayList<>();
    // the default width, then narrowed by the player (or left narrow by the window opening small)
    for (final int sideWidth : List.of(0, 300, 240, 200, 170)) {
      SwingUtilities.invokeAndWait(
          () -> {
            final javax.swing.JSplitPane split =
                (javax.swing.JSplitPane) window.getContentPane().getComponent(0);
            if (sideWidth > 0) {
              split.setDividerLocation(split.getWidth() - split.getDividerSize() - sideWidth);
            }
            window.validate();
            layoutAll(window.getContentPane());
          });
      // a move whose buttons went under it is laid out again (as the game does at once)
      flush();
      SwingUtilities.invokeAndWait(
          () -> {
            window.validate();
            layoutAll(window.getContentPane());
            for (final String text : List.of("Show", "Undo")) {
              for (final AbstractButton b : buttons(text)) {
                final Rectangle visible = b.getVisibleRect();
                if (visible.width < b.getWidth() || visible.height < b.getHeight()) {
                  hidden.add(
                      text
                          + " shows "
                          + visible.width
                          + " of "
                          + b.getWidth()
                          + " px; panel "
                          + panel.getWidth());
                }
              }
            }
          });
    }
    window.dispose();
    assertThat(hidden.toString(), hidden.isEmpty(), is(true));
  }

  /** Request 44 screenshots: the map around the places, drawn as the game draws it. */
  private BufferedImage mapAround(
      final games.strategy.triplea.ui.UiContext ui,
      final java.util.Set<games.strategy.engine.data.Unit> offMap,
      final List<games.strategy.triplea.ui.panels.map.MapOverlay> overlays,
      final Territory... places) {
    final var mapData = ui.getMapData();
    Rectangle region = null;
    for (final Territory t : places) {
      final Rectangle r = mapData.getBoundingRect(t);
      region = region == null ? new Rectangle(r) : region.union(r);
    }
    region.grow(60, 60);
    final games.strategy.triplea.ui.screen.TileManager tiles =
        new games.strategy.triplea.ui.screen.TileManager(ui);
    tiles.setUnitsOffMap(offMap);
    tiles.createTiles(new Rectangle(mapData.getMapDimensions()));
    tiles.resetTiles(data, mapData);
    // twice the size, to be read easily
    final BufferedImage image =
        new BufferedImage(region.width * 2, region.height * 2, BufferedImage.TYPE_INT_RGB);
    final java.awt.Graphics2D g = image.createGraphics();
    g.setRenderingHint(
        java.awt.RenderingHints.KEY_INTERPOLATION,
        java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
    g.scale(2, 2);
    g.translate(-region.x, -region.y);
    for (final var tile : tiles.getTiles(new java.awt.geom.Rectangle2D.Double(
        region.x, region.y, region.width, region.height))) {
      tile.drawImage(data, mapData);
      if (tile.getImage() != null) {
        g.drawImage(tile.getImage(), tile.getBounds().x, tile.getBounds().y, null);
      }
    }
    g.setRenderingHint(
        java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
    for (final var overlay : overlays) {
      overlay.paint(g, data);
    }
    g.dispose();
    return image;
  }

  @Test
  void request44ScreenshotsOfTheMap() throws Exception {
    final Territory[] t = cracowAndBialystok();
    startPanel(false);
    SwingUtilities.invokeAndWait(
        () -> {
          panel.click(dives(t[0]), t[0], click());
          panel.click(List.of(), t[1], click());
        });
    flush();
    final org.mockito.ArgumentCaptor<games.strategy.triplea.ui.panels.map.MapOverlay> overlay =
        org.mockito.ArgumentCaptor.forClass(games.strategy.triplea.ui.panels.map.MapOverlay.class);
    verify(map, org.mockito.Mockito.atLeastOnce()).addOverlay(overlay.capture());
    final games.strategy.triplea.ui.UiContext ui = TestUi.create(data);
    // the bomber stage: one of Cracow's two dive bombers sent to Bialystok
    ImageIO.write(
        mapAround(
            ui, java.util.Set.copyOf(offMap()), List.of(overlay.getValue()), t[0], t[1]),
        "png",
        SHOTS.resolve("request44_bomber_stage_map.png").toFile());
    SwingUtilities.invokeAndWait(
        () -> {
          panel.click(dives(t[0]).stream().filter(u -> !offMap().contains(u)).toList(), t[0], click());
          panel.click(List.of(), t[1], click());
        });
    flush();
    ImageIO.write(
        mapAround(
            ui, java.util.Set.copyOf(offMap()), List.of(overlay.getValue()), t[0], t[1]),
        "png",
        SHOTS.resolve("request44_bomber_stage_map_both.png").toFile());
    // later in the round: both in the air over Bialystok, a German fighter too, outside the air
    // phase: one small marker
    final Territory airborne = AirBases_airborne();
    final List<games.strategy.engine.data.Unit> flying = new ArrayList<>(dives(t[0]));
    flying.addAll(data.getUnitTypeList().getUnitTypeOrThrow("fighter").create(1, germans));
    data.performChange(
        games.strategy.engine.data.changefactory.ChangeFactory.removeUnits(t[0], dives(t[0])));
    data.performChange(
        games.strategy.engine.data.changefactory.ChangeFactory.addUnits(airborne, flying));
    for (final games.strategy.engine.data.Unit u : flying) {
      data.performChange(
          games.strategy.engine.data.changefactory.ChangeFactory.unitPropertyChange(
              u, t[1], games.strategy.engine.data.Unit.PropertyName.FLIGHT_OVER));
      data.performChange(
          games.strategy.engine.data.changefactory.ChangeFactory.unitPropertyChange(
              u, 3, games.strategy.engine.data.Unit.PropertyName.FLIGHT_RANGE_LEFT));
    }
    final AirMapMode airMap = new AirMapMode(ui, null, data, text -> {});
    ImageIO.write(
        mapAround(ui, java.util.Set.of(), List.of(airMap), t[0], t[1]),
        "png",
        SHOTS.resolve("request44_turn_air_marker.png").toFile());
    assertThat(airMap.airborneBoxes().size(), is(1));
    assertThat(
        AirMapMode.listAircraft(flying),
        org.hamcrest.Matchers.containsString("2 dive bombers (3 steps of range left), 1 fighter"));
    airMap.setShowingAirMarkers(false);
    ImageIO.write(
        mapAround(ui, java.util.Set.of(), List.of(airMap), t[0], t[1]),
        "png",
        SHOTS.resolve("request44_turn_air_hidden.png").toFile());
    assertThat(airMap.airborneBoxes().isEmpty(), is(true));
    airMap.toggle();
    ImageIO.write(
        mapAround(ui, java.util.Set.of(), List.of(airMap), t[0], t[1]),
        "png",
        SHOTS.resolve("request44_turn_air_map_mode.png").toFile());
  }

  /**
   * Request 47: orders for every German bomber that can fly, except {@code keep} of the type on
   * each base given (bases in the order given).
   */
  private List<TargetAssignment> ordersLeaving(final Map<Territory, Integer> keep) {
    final var stage = games.strategy.triplea.delegate.air.AirStage.Stage.BOMBERS;
    final List<TargetAssignment> orders = new ArrayList<>();
    final Map<Territory, Integer> left = new java.util.HashMap<>(keep);
    for (final Territory zone : games.strategy.triplea.delegate.air.AirStage.bases(germans, stage)) {
      final IntegerMap<UnitType> free = panel.unassigned(germans, zone);
      for (final UnitType type : free.keySet()) {
        final List<Target> targets =
            games.strategy.triplea.delegate.air.AirStage.candidates(
                germans, zone, type, stage, Map.of());
        if (targets.isEmpty()) {
          continue;
        }
        final int stay = Math.min(free.getInt(type), left.getOrDefault(zone, 0));
        left.put(zone, left.getOrDefault(zone, 0) - stay);
        if (free.getInt(type) - stay > 0) {
          orders.add(new TargetAssignment(zone, type, free.getInt(type) - stay, targets.get(0)));
        }
      }
    }
    return orders;
  }

  @Test
  void request47TheBannerSaysHowManyAircraftHaveNoOrders() throws Exception {
    startPanel(false);
    SwingUtilities.invokeAndWait(
        () -> panel.setStagedForTests(ordersLeaving(Map.of(bases.get(1), 1))));
    flush();
    final AirMapMode.Banner banner = panel.banner(germans);
    assertThat(banner.text(), is("Germans: 1 aircraft without orders"));
    assertThat(banner.bold(), is("1"));
    // the how-to is in its tooltip, not in the banner
    assertThat(banner.help(), org.hamcrest.Matchers.containsString("Select bombers on the map"));
    SwingUtilities.invokeAndWait(
        () -> {
          panel.setStagedForTests(List.of());
          panel.setStagedForTests(ordersLeaving(Map.of()));
        });
    flush();
    assertThat(panel.banner(germans).text(), is("Germans: all aircraft have orders"));
  }

  @Test
  void request47NextGoesRoundThePlacesWithAircraftWithoutOrdersAndSelectsOne() throws Exception {
    startPanel(false);
    // 3 without orders in 2 places: 2 on the first base, 1 on another
    final Territory first = bases.get(0);
    final Territory second = bases.get(1);
    SwingUtilities.invokeAndWait(
        () -> panel.setStagedForTests(ordersLeaving(Map.of(first, 2, second, 1))));
    flush();
    final List<Territory> visited = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      SwingUtilities.invokeAndWait(
          () -> {
            panel.nextWithoutOrders(true);
            visited.add(panel.selectedBase());
            assertThat(panel.selectedCount(), is(1));
          });
    }
    final List<Territory> order =
        games.strategy.triplea.delegate.air.AirStage.bases(
                germans, games.strategy.triplea.delegate.air.AirStage.Stage.BOMBERS)
            .stream()
            .filter(z -> z.equals(first) || z.equals(second))
            .toList();
    assertThat(visited, is(List.of(order.get(0), order.get(1), order.get(0))));
    final org.mockito.InOrder centered = org.mockito.Mockito.inOrder(map);
    centered.verify(map).centerOn(order.get(0));
    centered.verify(map).centerOn(order.get(1));
    centered.verify(map).centerOn(order.get(0));
    // back the other way
    SwingUtilities.invokeAndWait(() -> panel.nextWithoutOrders(false));
    assertThat(panel.selectedBase(), is(order.get(1)));
  }

  @Test
  void request47ScreenshotOfOneAircraftWithoutOrdersAfterNext() throws Exception {
    final Territory[] t = cracowAndBialystok();
    startPanel(false);
    // every bomber has orders but one of Cracow's dive bombers
    SwingUtilities.invokeAndWait(
        () -> panel.setStagedForTests(ordersLeaving(Map.of(t[0], 1))));
    flush();
    SwingUtilities.invokeAndWait(() -> panel.nextWithoutOrders(true));
    flush();
    assertThat(panel.selectedBase(), is(t[0]));
    final BufferedImage side = render(panel, WIDTH, HEIGHT);
    final org.mockito.ArgumentCaptor<games.strategy.triplea.ui.panels.map.MapOverlay> overlay =
        org.mockito.ArgumentCaptor.forClass(games.strategy.triplea.ui.panels.map.MapOverlay.class);
    verify(map, org.mockito.Mockito.atLeastOnce()).addOverlay(overlay.capture());
    final games.strategy.triplea.ui.UiContext ui = TestUi.create(data);
    final BufferedImage around =
        mapAround(ui, java.util.Set.copyOf(offMap()), List.of(overlay.getValue()), t[0], t[1]);
    // the banner as the map draws it
    final AirMapMode airMap = new AirMapMode(ui, null, data, text -> {});
    airMap.setBanner(panel.banner(germans));
    final java.awt.Dimension mapSize = ui.getMapData().getMapDimensions();
    final BufferedImage banner = new BufferedImage(520, 52, BufferedImage.TYPE_INT_ARGB);
    final java.awt.Graphics2D bg = banner.createGraphics();
    bg.translate(-(mapSize.width / 2 - 260), 0);
    airMap.paint(bg, data);
    bg.dispose();
    final int width = around.getWidth() + side.getWidth();
    final int height = Math.max(around.getHeight(), side.getHeight());
    final BufferedImage shot = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    final java.awt.Graphics2D g = shot.createGraphics();
    g.drawImage(around, 0, 0, null);
    g.drawImage(banner, (around.getWidth() - banner.getWidth()) / 2, 0, null);
    g.drawImage(side, around.getWidth(), 0, null);
    g.dispose();
    ImageIO.write(shot, "png", SHOTS.resolve("request47_bomber_stage_next.png").toFile());
  }

  private Territory AirBases_airborne() {
    return games.strategy.triplea.delegate.air.AirBases.airborne(data).orElseThrow();
  }
}
