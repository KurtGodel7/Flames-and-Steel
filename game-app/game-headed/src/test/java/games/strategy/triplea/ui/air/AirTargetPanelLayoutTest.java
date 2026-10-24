package games.strategy.triplea.ui.air;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.UnitType;
import games.strategy.engine.data.gameparser.GameParser;
import games.strategy.triplea.attachments.AirZoneAttachment;
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
    data = GameParser.parse(XML, false).orElseThrow();
    germans = data.getPlayerList().getPlayerId("Germans");
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
    final AtomicReference<AirTargetPanel> made = new AtomicReference<>();
    SwingUtilities.invokeAndWait(() -> made.set(new AirTargetPanel(frame)));
    panel = made.get();
  }

  /** The German front with the most targets in reach of its bombers. */
  private Territory busiestFront() {
    Territory best = null;
    int most = -1;
    for (final Territory zone : AirZoneAttachment.getAirZones(data.getMap())) {
      if (AirCombat.getBombers(germans, zone).isEmpty()) {
        continue;
      }
      final int n = AirCombat.getTargets(germans, zone).size();
      if (n > most) {
        most = n;
        best = zone;
      }
    }
    Assumptions.assumeTrue(best != null, "Germany has no bombers in a front");
    return best;
  }

  private void startPanel(final boolean cover) throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          panel.display(germans);
          panel.start(germans, List.of(), staged -> {}, cover, Map.of(), Map.of());
        });
    flush();
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
      if (AirCombat.mayAttack(germans, zone, type, target)) {
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
    ImageIO.write(shot, "png", SHOTS.resolve("request33_bomber_panel.png").toFile());

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
            .anyMatch(t -> t.contains(" ← 1 ")),
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
    final UnitType dive = data.getUnitTypeList().getUnitTypeOrThrow("diveBomber");
    data.performChange(
        games.strategy.engine.data.changefactory.ChangeFactory.addUnits(
            zone, dive.create(12, germans)));
    startPanel(false);
    final List<Target> reach =
        panel.targetsInReach(germans, zone).stream()
            .filter(t -> AirCombat.mayAttack(germans, zone, dive, t))
            .limit(10)
            .toList();
    Assumptions.assumeTrue(reach.size() == 10, "fewer than 10 dive bomber targets");
    final IntegerMap<UnitType> one = new IntegerMap<>();
    one.put(dive, 1);
    SwingUtilities.invokeAndWait(
        () -> {
          panel.chooseFront(germans, zone, false);
          reach.forEach(t -> panel.assign(t, one));
        });
    flush();
    final BufferedImage shot = render(panel, WIDTH, HEIGHT);
    ImageIO.write(shot, "png", SHOTS.resolve("request33_bomber_panel_10_moves.png").toFile());
    assertThat(panel.staged().size(), is(10));
    for (final AbstractButton undo : buttons("Undo")) {
      assertThat("every Undo is in view", inView(undo), is(true));
    }
    assertThat(inView(buttons("Pick from list…").get(0)), is(true));
  }

  @Test
  void aMovesLineSelectsItsTarget() throws Exception {
    startPanel(false);
    final Territory zone = busiestFront();
    final TargetAssignment move = firstMove(zone);
    final IntegerMap<UnitType> one = new IntegerMap<>();
    one.put(move.unitType(), 1);
    SwingUtilities.invokeAndWait(
        () -> {
          panel.chooseFront(germans, zone, false);
          panel.assign(move.target(), one);
          panel.selectTarget(null);
          panel.selectMove(germans, panel.staged().get(0));
        });
    flush();
    assertThat(panel.selectedTarget(), is(move.target()));
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
    ImageIO.write(shot, "png", SHOTS.resolve("request33_pick_window.png").toFile());
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
  void theBomberStageTargetsAnAdjacentFrontAndRepositions() throws Exception {
    data.performChange(
        games.strategy.engine.data.changefactory.ChangeFactory.relationshipChange(
            germans,
            data.getPlayerList().getPlayerId("Russians"),
            data.getRelationshipTracker()
                .getRelationshipType(germans, data.getPlayerList().getPlayerId("Russians")),
            data.getRelationshipTypeList().getRelationshipType("War")));
    // a German bomber with a target in an adjacent box
    Territory origin = null;
    UnitType type = null;
    Target target = null;
    Territory into = null;
    search:
    for (final Territory box : AirZoneAttachment.getAirZones(data.getMap())) {
      for (final var u : AirCombat.getBombers(germans, box)) {
        for (final var e :
            games.strategy.triplea.delegate.air.AirStage.candidates(
                    germans,
                    box,
                    u.getType(),
                    games.strategy.triplea.delegate.air.AirAllocation.Stage.BOMBERS,
                    Map.of())
                .entrySet()) {
          if (!e.getValue().equals(box)) {
            origin = box;
            type = u.getType();
            target = e.getKey();
            into = e.getValue();
            break search;
          }
        }
      }
    }
    Assumptions.assumeTrue(origin != null, "no adjacent target");
    // a second bomber of the type, so one can attack and one can move
    data.performChange(
        games.strategy.engine.data.changefactory.ChangeFactory.addUnits(
            origin, type.create(1, germans)));
    final Territory from = origin;
    final Target far = target;
    final IntegerMap<UnitType> one = new IntegerMap<>();
    one.put(type, 1);
    SwingUtilities.invokeAndWait(
        () -> {
          panel.display(germans);
          panel.startStage(
              germans,
              games.strategy.triplea.delegate.air.AirAllocation.Stage.BOMBERS,
              List.of(),
              Map.of());
        });
    flush();
    SwingUtilities.invokeAndWait(
        () -> {
          panel.chooseFront(germans, from, false);
          panel.assign(far, one);
        });
    flush();
    final var choice =
        games.strategy.triplea.delegate.air.AirStage.toChoice(
            germans,
            games.strategy.triplea.delegate.air.AirAllocation.Stage.BOMBERS,
            panel.staged(),
            Map.of());
    assertThat(choice.moves().size(), is(1));
    assertThat(choice.moves().get(0).to(), is(into));
    assertThat(choice.targets().get(0).zone(), is(into));
    assertThat(
        all(panel).stream()
            .filter(c -> c instanceof JLabel l && l.getText() != null)
            .map(c -> ((JLabel) c).getText())
            .anyMatch(t -> t.contains("(moves to ")),
        is(true));
    // a move with no target: to any box it may move to
    final Territory box =
        games.strategy.triplea.delegate.air.AirStage.neighbours(germans, from, type).get(0);
    SwingUtilities.invokeAndWait(
        () -> {
          panel.chooseFront(germans, from, false);
          panel.assign(games.strategy.triplea.delegate.air.AirStage.moveTo(box), one);
        });
    flush();
    final BufferedImage shot = render(panel, WIDTH, HEIGHT);
    ImageIO.write(shot, "png", SHOTS.resolve("request35_bomber_stage.png").toFile());
    final var both =
        games.strategy.triplea.delegate.air.AirStage.toChoice(
            germans,
            games.strategy.triplea.delegate.air.AirAllocation.Stage.BOMBERS,
            panel.staged(),
            Map.of());
    assertThat(panel.staged().size(), is(2));
    assertThat(
        panel.staged().stream().anyMatch(games.strategy.triplea.delegate.air.AirStage::isMove),
        is(true));
    assertThat(both.moves().stream().anyMatch(m -> m.to().equals(box)), is(true));
  }

  @Test
  void placeFightersUsesTheSameLayout() throws Exception {
    startPanel(true);
    flush();
    final BufferedImage shot = render(panel, WIDTH, HEIGHT);
    ImageIO.write(shot, "png", SHOTS.resolve("request33_fighter_panel.png").toFile());
    assertThat(
        all(panel).stream()
            .filter(c -> c instanceof JLabel l && l.getText() != null)
            .map(c -> ((JLabel) c).getText())
            .anyMatch(t -> t.contains("Place Fighters")),
        is(true));
    assertThat(buttons("Pick from list…").size(), is(1));
  }
}
