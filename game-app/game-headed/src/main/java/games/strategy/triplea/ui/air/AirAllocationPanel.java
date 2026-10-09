package games.strategy.triplea.ui.air;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.triplea.attachments.AirZoneAttachment;
import games.strategy.triplea.delegate.air.AirAllocation;
import games.strategy.triplea.delegate.air.AirAllocation.AirMove;
import games.strategy.triplea.delegate.air.AirAllocation.Stage;
import games.strategy.triplea.image.UnitImageFactory.ImageKey;
import games.strategy.triplea.ui.ActionPanel;
import games.strategy.triplea.ui.DefaultMapSelectionListener;
import games.strategy.triplea.ui.MouseDetails;
import games.strategy.triplea.ui.TripleAFrame;
import games.strategy.triplea.ui.panels.map.MapOverlay;
import games.strategy.triplea.ui.panels.map.MapSelectionListener;
import games.strategy.triplea.ui.panels.map.UnitSelectionListener;
import games.strategy.triplea.ui.research.ResearchView;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Graphics2D;
import java.awt.Point;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import org.triplea.java.collections.IntegerMap;

/**
 * Flames and Steel: air allocation on the map. The player clicks aircraft in an air box, then the
 * box to send them to; legal boxes light up and the rest are greyed. Staged moves are drawn as
 * arrows with faded icons until Done. Allies' moves made so far are shown dashed, and in the
 * fighter step the bombers' revealed moves are drawn too.
 */
public final class AirAllocationPanel extends ActionPanel {
  private static final long serialVersionUID = 1L;
  private static final Color PREVIEW_BLOCKED = new Color(230, 60, 50);

  private final TripleAFrame frame;
  private final AirDraw draw;
  private final ExecutorService storeExecutor = Executors.newSingleThreadExecutor();

  private Stage stage = Stage.BOMBERS;
  private final List<AirMove> staged = new ArrayList<>();
  private Map<GamePlayer, List<AirMove>> allied = Map.of();
  private Map<GamePlayer, List<AirMove>> revealed = Map.of();
  private Consumer<List<AirMove>> store = moves -> {};

  // the current selection: aircraft of one zone, count per type
  private @Nullable Territory selectedZone;
  private final IntegerMap<UnitType> selected = new IntegerMap<>();
  private Map<Territory, Optional<String>> destinations = Map.of();
  private boolean listening;
  // what the mouse points at, for the move preview
  private @Nullable Territory hoverZone;
  private @Nullable Point mousePoint;
  private final JLabel legalLabel = new JLabel();

  private final JPanel selectionPanel = AirSelection.compactPanel();
  private final JPanel movesPanel = AirSelection.compactPanel();

  private final MapOverlay overlay = this::paintOverlay;

  private final UnitSelectionListener unitListener =
      (units, territory, md) ->
          SwingUtilities.invokeLater(() -> unitsClicked(units, territory, md));

  private final MapSelectionListener hoverListener =
      new DefaultMapSelectionListener() {
        @Override
        public void mouseEntered(final @Nullable Territory territory) {
          hovered(territory);
        }

        @Override
        public void mouseMoved(final @Nullable Territory territory, final MouseDetails md) {
          if (listening && selectedZone != null && !selected.isEmpty()) {
            mousePoint = md.getMapPoint();
            getMap().repaint();
          }
        }
      };

  public AirAllocationPanel(final TripleAFrame frame) {
    super(frame);
    this.frame = frame;
    this.draw = new AirDraw(frame.getUiContext());
    selectionPanel.setLayout(new BoxLayout(selectionPanel, BoxLayout.Y_AXIS));
    movesPanel.setLayout(new BoxLayout(movesPanel, BoxLayout.Y_AXIS));
    selectionPanel.setAlignmentX(LEFT_ALIGNMENT);
    movesPanel.setAlignmentX(LEFT_ALIGNMENT);
  }

  /** Shows the panel and the map tools for the nation's allocation. */
  public void start(
      final GamePlayer player,
      final Stage stage,
      final List<AirMove> start,
      final Map<GamePlayer, List<AirMove>> allied,
      final Map<GamePlayer, List<AirMove>> revealed,
      final Consumer<List<AirMove>> store) {
    SwingUtilities.invokeLater(
        () -> {
          this.stage = stage;
          staged.clear();
          staged.addAll(start);
          this.allied = allied;
          this.revealed = revealed;
          this.store = store;
          clearSelection();
          draw.setAirMapMode(frame.getAirMapMode());
          showDepartures();
          if (frame.getAirMapMode() != null) {
            frame
                .getAirMapMode()
                .setActing(
                    player,
                    (zone, unit) -> unit.isOwnedBy(player) && AirAllocation.isInStage(unit, stage),
                    (zone, unit) ->
                        stage == Stage.BOMBERS
                            ? ResearchView.displayName(unit.getType().getName())
                                + ": fighters move in the next step (fighter allocation)."
                            : ResearchView.displayName(unit.getType().getName())
                                + ": bombers moved in the bomber step.");
          }
          if (!listening) {
            listening = true;
            getMap().addUnitSelectionListener(unitListener);
            getMap().addMapSelectionListener(hoverListener);
            getMap().addOverlay(overlay);
          }
          rebuild(player);
        });
  }

  /** Blocks until Done; returns the staged moves. */
  public List<AirMove> waitForAllocation() {
    waitForRelease();
    SwingUtilities.invokeLater(this::stopListening);
    return List.copyOf(staged);
  }

  private void stopListening() {
    if (listening) {
      listening = false;
      getMap().removeUnitSelectionListener(unitListener);
      getMap().removeMapSelectionListener(hoverListener);
      getMap().removeOverlay(overlay);
      getMap().setUnitHighlight(List.of());
      if (frame.getAirMapMode() != null) {
        frame.getAirMapMode().setBusy(false);
        frame.getAirMapMode().setPending(Map.of(), Map.of());
        frame.getAirMapMode().setActing(null, (zone, unit) -> false, (zone, unit) -> "");
      }
      frame.clearStatusMessage();
    }
  }

  @Override
  public String toString() {
    return "AirAllocationPanel";
  }

  @Override
  public void performDone() {
    final Optional<String> error;
    final GameData data = getData();
    try (GameData.Unlocker ignored = data.acquireReadLock()) {
      error = AirAllocation.validate(getCurrentPlayer(), stage, staged, data.getMap());
    }
    if (error.isPresent()) {
      frame.setStatusErrorMessage(error.get());
      return;
    }
    clearSelection();
    release();
  }

  // ---- selection on the map

  private void unitsClicked(
      final List<Unit> clickedUnits, final @Nullable Territory clicked, final MouseDetails md) {
    // in air map mode a click on a stack, or anywhere in a zone's area, means that zone
    List<Unit> units = clickedUnits;
    Territory territory = clicked;
    final AirMapMode mode = frame.getAirMapMode();
    if (mode != null) {
      final Optional<AirMapMode.ArrowHit> arrow = mode.arrowHit(md.getMapPoint());
      if (arrow.isPresent() && isActive() && listening) {
        // an arrow beside any of the nation's stacks: pick in that zone
        if (!arrow.get().zone().equals(selectedZone)) {
          selected.clear();
          selectedZone = arrow.get().zone();
        }
        AirSelection.applyArrow(
            selected, available(getCurrentPlayer(), selectedZone), arrow.get(), md);
        selectionChanged();
        return;
      }
      final Optional<AirMapMode.Hit> hit = mode.hit(md.getMapPoint());
      if (hit.isPresent() && hit.get().moved() && !md.isRightButton()) {
        frame.setStatusErrorMessage(
            "Those aircraft have already moved this step; undo the move to change it.");
        return;
      }
      if (hit.isPresent()) {
        units = hit.get().units();
        territory = hit.get().zone();
      } else if (mode.isEnabled() && territory != null && !AirZoneAttachment.isAirZone(territory)) {
        units = List.of();
        territory = mode.zoneOf(territory).orElse(null);
      }
    }
    if (!isActive() || !listening || territory == null) {
      return;
    }
    final GamePlayer player = getCurrentPlayer();
    if (md.isRightButton()) {
      final Optional<UnitType> type = units.stream().map(Unit::getType).findFirst();
      if (territory.equals(selectedZone) && type.isPresent() && selected.getInt(type.get()) > 0) {
        selected.add(type.get(), -1);
        if (selected.getInt(type.get()) <= 0) {
          selected.removeKey(type.get());
        }
      } else {
        clearSelection();
      }
      selectionChanged();
      return;
    }
    if (!AirZoneAttachment.isAirZone(territory)) {
      return;
    }
    if (selectedZone != null && !territory.equals(selectedZone) && !selected.isEmpty()) {
      moveSelectionTo(territory);
      return;
    }
    // pick aircraft in this zone, as in a TripleA move
    final IntegerMap<UnitType> available = available(player, territory);
    if (!territory.equals(selectedZone)) {
      selected.clear();
      selectedZone = territory;
    }
    final int added =
        AirSelection.add(
            selected,
            available,
            units,
            u ->
                u.isOwnedBy(player)
                    && AirAllocation.isInStage(u, stage)
                    && available.getInt(u.getType()) > 0,
            md);
    if (added == 0 && !units.isEmpty()) {
      final Unit unit = units.get(0);
      frame.setStatusErrorMessage(
          !unit.isOwnedBy(player)
              ? "Those are not your aircraft."
              : !AirAllocation.isInStage(unit, stage)
                  ? "Those aircraft move in the "
                      + (stage == Stage.BOMBERS ? "fighter" : "bomber")
                      + " step."
                  : "All of those are already picked or moving; undo a move to change it.");
    }
    if (available.isEmpty()) {
      if (units.isEmpty()) {
        frame.setStatusErrorMessage(
            "You have no "
                + (stage == Stage.BOMBERS ? "bombers" : "fighters")
                + " to move from "
                + AirAllocation.zoneName(territory)
                + ".");
      }
      clearSelection();
    }
    selectionChanged();
  }

  private void moveSelectionTo(final Territory to) {
    final Optional<String> problem = destinations.getOrDefault(to, Optional.of("Not an air zone."));
    if (problem.isPresent()) {
      frame.setStatusErrorMessage(problem.get());
      return;
    }
    for (final UnitType type : selected.keySet()) {
      addMove(new AirMove(selectedZone, to, type, selected.getInt(type)));
    }
    clearSelection();
    selectionChanged();
    movesChanged();
  }

  /** Adds a move, merging it with a staged move between the same boxes. */
  private void addMove(final AirMove move) {
    for (int i = 0; i < staged.size(); i++) {
      final AirMove m = staged.get(i);
      if (m.from().equals(move.from())
          && m.to().equals(move.to())
          && m.unitType().equals(move.unitType())) {
        staged.set(i, new AirMove(m.from(), m.to(), m.unitType(), m.count() + move.count()));
        return;
      }
    }
    staged.add(move);
  }

  /** The player's aircraft of this stage in the zone that are not already moving. */
  private IntegerMap<UnitType> available(final GamePlayer player, final Territory zone) {
    final IntegerMap<UnitType> result = new IntegerMap<>();
    try (GameData.Unlocker ignored = getData().acquireReadLock()) {
      for (final Unit u : AirAllocation.getAircraft(player, zone, stage)) {
        result.add(u.getType(), 1);
      }
    }
    for (final AirMove m : staged) {
      if (m.from().equals(zone)) {
        result.add(m.unitType(), -m.count());
      }
    }
    result.keySet().removeIf(t -> result.getInt(t) <= 0);
    return result;
  }

  private void clearSelection() {
    selectedZone = null;
    selected.clear();
    destinations = Map.of();
  }

  private void selectionChanged() {
    selectionChanged(true);
  }

  /** Recomputes where the picked aircraft may go; the arrow counters skip rebuilding the panel. */
  private void selectionChanged(final boolean rebuildPanel) {
    final GamePlayer player = getCurrentPlayer();
    final Map<Territory, Optional<String>> result = new LinkedHashMap<>();
    if (selectedZone != null && !selected.isEmpty()) {
      try (GameData.Unlocker ignored = getData().acquireReadLock()) {
        for (final Territory zone : AirZoneAttachment.getAirZones(getData().getMap())) {
          result.put(zone, problem(player, zone));
        }
      }
      // highlight the picked aircraft in the box
      try (GameData.Unlocker ignored = getData().acquireReadLock()) {
        getMap()
            .setUnitHighlight(
                AirSelection.highlight(
                    selected, AirAllocation.getAircraft(player, selectedZone, stage)));
      }
    } else {
      getMap().setUnitHighlight(List.of());
    }
    destinations = result;
    if (frame.getAirMapMode() != null) {
      frame.getAirMapMode().setBusy(selectedZone != null);
    }
    if (selectedZone != null && !selected.isEmpty()) {
      frame.setStatusMessage(
          selectionText()
              + " picked: point at a zone to see the move, click a green zone to make it.");
    } else if (selectedZone != null) {
      frame.setStatusMessage(
          "Pick aircraft in "
              + AirAllocation.zoneName(selectedZone)
              + " with the arrows in the panel, or by clicking their stacks.");
    } else {
      frame.clearStatusMessage();
      mousePoint = null;
    }
    legalLabel.setText(
        selected.isEmpty()
            ? " "
            : AirSelection.wrap(
                legalCount() + " zones allowed (green); point at one to preview the move."));
    if (rebuildPanel) {
      rebuild(player);
    }
    getMap().repaint();
  }

  /** Why the selection may not go to the zone, staging the selected types one after another. */
  private Optional<String> problem(final GamePlayer player, final Territory zone) {
    final List<AirMove> moves = new ArrayList<>(staged);
    for (final UnitType type : selected.keySet()) {
      final Optional<String> problem =
          AirAllocation.destinationProblem(
              player, stage, moves, selectedZone, type, selected.getInt(type), zone);
      if (problem.isPresent()) {
        return problem;
      }
      moves.add(new AirMove(selectedZone, zone, type, selected.getInt(type)));
    }
    return Optional.empty();
  }

  private void hovered(final @Nullable Territory hoveredTerritory) {
    Territory territory = hoveredTerritory;
    final AirMapMode mode = frame.getAirMapMode();
    if (mode != null
        && mode.isEnabled()
        && territory != null
        && !AirZoneAttachment.isAirZone(territory)) {
      territory = mode.zoneOf(territory).orElse(territory);
    }
    hoverZone = territory != null && AirZoneAttachment.isAirZone(territory) ? territory : null;
    if (!listening || selectedZone == null || selected.isEmpty() || territory == null) {
      return;
    }
    if (!AirZoneAttachment.isAirZone(territory)) {
      frame.clearStatusMessage();
      return;
    }
    final Optional<String> problem = destinations.getOrDefault(territory, Optional.empty());
    if (territory.equals(selectedZone)) {
      frame.clearStatusMessage();
    } else if (problem.isPresent()) {
      frame.setStatusWarningMessage(problem.get());
    } else {
      frame.setStatusMessage(
          "Click to send " + selectionText() + " to " + AirAllocation.zoneName(territory));
    }
  }

  private String selectionText() {
    final List<String> parts = new ArrayList<>();
    for (final UnitType type : selected.keySet()) {
      parts.add(selected.getInt(type) + " " + ResearchView.displayName(type.getName()));
    }
    return String.join(", ", parts);
  }

  /**
   * Shows the staged moves (own and allies') as made, as a TripleA move would: the aircraft leave
   * their zone and appear in the new one, in the boxes and on the air map.
   */
  private void showDepartures() {
    final AirMapMode mode = frame.getAirMapMode();
    if (mode == null) {
      return;
    }
    final Map<Territory, Map<GamePlayer, IntegerMap<UnitType>>> leaving = new HashMap<>();
    final Map<Territory, Map<GamePlayer, IntegerMap<UnitType>>> arriving = new HashMap<>();
    final Map<GamePlayer, List<AirMove>> all = new LinkedHashMap<>(allied);
    all.put(getCurrentPlayer(), staged);
    all.forEach(
        (owner, moves) ->
            moves.forEach(
                m -> {
                  leaving
                      .computeIfAbsent(m.from(), k -> new HashMap<>())
                      .computeIfAbsent(owner, k -> new IntegerMap<>())
                      .add(m.unitType(), m.count());
                  arriving
                      .computeIfAbsent(m.to(), k -> new HashMap<>())
                      .computeIfAbsent(owner, k -> new IntegerMap<>())
                      .add(m.unitType(), m.count());
                }));
    mode.setPending(leaving, arriving);
  }

  private void movesChanged() {
    showDepartures();
    final List<AirMove> snapshot = List.copyOf(staged);
    storeExecutor.execute(() -> store.accept(snapshot));
    rebuild(getCurrentPlayer());
    getMap().repaint();
  }

  // ---- the side panel

  private void rebuild(final GamePlayer player) {
    removeAll();
    actionLabel.setText(
        AirSelection.wrap(
            player.getName()
                + (stage == Stage.BOMBERS
                    ? ": bomber allocation (fighters move in the next step)"
                    : ": fighter allocation (bombers have moved)")));
    // the help is a tooltip on the heading; the panel itself lists the moves, as a move panel does
    actionLabel.setToolTipText(
        AirSelection.wrap(
            AirSelection.HELP
                + " Or use the arrows here, or beside the stack on the air map."
                + " Then click the zone to send them to: they move there at once, as in any"
                + " move, and Undo puts them back.<br>Enemies see these moves only at the"
                + " reveal"
                + (stage == Stage.FIGHTERS ? "; the bombers' moves are already revealed." : ".")));
    add(leftAligned(actionLabel));

    selectionPanel.removeAll();
    if (selectedZone != null) {
      selectionPanel.add(
          leftAligned(new JLabel("Aircraft in " + AirAllocation.zoneName(selectedZone) + ":")));
      selectionPanel.add(
          leftAligned(
              AirSelection.chooser(
                  frame.getUiContext(),
                  player,
                  available(player, selectedZone),
                  selected,
                  () -> selectionChanged(false))));
      selectionPanel.add(leftAligned(legalLabel));
    }
    add(selectionPanel);

    movesPanel.removeAll();
    movesPanel.add(Box.createVerticalStrut(6));
    movesPanel.add(leftAligned(new JLabel(staged.isEmpty() ? "No moves yet." : "Moves:")));
    for (final AirMove move : List.copyOf(staged)) {
      final JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
      row.add(new JLabel(icon(player, move.unitType())));
      row.add(
          new JLabel(
              move.count()
                  + " "
                  + ResearchView.displayName(move.unitType().getName())
                  + ": "
                  + AirAllocation.zoneName(move.from())
                  + " → "
                  + AirAllocation.zoneName(move.to())));
      final JButton undo = new JButton("Undo");
      undo.setMargin(new java.awt.Insets(0, 4, 0, 4));
      undo.addActionListener(
          e -> {
            staged.remove(move);
            clearSelection();
            selectionChanged();
            movesChanged();
          });
      row.add(undo);
      movesPanel.add(leftAligned(row));
    }
    if (!allied.isEmpty()) {
      movesPanel.add(Box.createVerticalStrut(6));
      movesPanel.add(leftAligned(new JLabel("Allies' moves so far are drawn dashed.")));
    }
    add(movesPanel);

    final JButton undoAll = new JButton("Undo all");
    undoAll.setEnabled(!staged.isEmpty());
    undoAll.addActionListener(
        e -> {
          staged.clear();
          clearSelection();
          selectionChanged();
          movesChanged();
        });
    add(Box.createVerticalStrut(6));
    final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
    buttons.add(undoAll);
    buttons.add(createDoneButton());
    add(leftAligned(buttons));
    add(Box.createVerticalGlue());
    refresh.run();
  }

  private long legalCount() {
    return destinations.values().stream().filter(Optional::isEmpty).count();
  }

  private javax.swing.Icon icon(final GamePlayer player, final UnitType type) {
    return frame
        .getUiContext()
        .getUnitImageFactory()
        .getIcon(ImageKey.builder().player(player).type(type).build());
  }

  /** A left-aligned row that keeps its own height (no gaps stretched between rows). */
  private static JPanel leftAligned(final Component c) {
    final JPanel p = AirSelection.compactPanel();
    p.setLayout(new BorderLayout());
    p.add(c, BorderLayout.WEST);
    p.setAlignmentX(LEFT_ALIGNMENT);
    return p;
  }

  // ---- the map overlay

  private void paintOverlay(final Graphics2D g, final GameData data) {
    if (!listening) {
      return;
    }
    draw.resetSlots();
    try (GameData.Unlocker ignored = data.acquireReadLock()) {
      if (selectedZone != null) {
        destinations.forEach(
            (zone, problem) -> {
              if (zone.equals(selectedZone)) {
                return;
              }
              if (problem.isEmpty()) {
                draw.fillBox(g, zone, AirDraw.LEGAL_FILL);
                draw.outlineBox(g, zone, AirDraw.LEGAL_LINE, 5f);
              } else {
                draw.fillBox(g, zone, AirDraw.ILLEGAL_FILL);
              }
            });
        draw.outlineBox(g, selectedZone, AirDraw.ORIGIN_LINE, 5f);
        final AirMapMode mode = frame.getAirMapMode();
        if (mode != null) {
          AirSelection.paintPicked(
              g, frame.getUiContext(), mode, selectedZone, getCurrentPlayer(), selected);
        }
      }
      // revealed bomber moves (fighter step): thin dashed grey arrows, arrivals labelled
      revealed.forEach(
          (owner, moves) -> {
            for (final AirMove m : moves) {
              AirDraw.arrow(
                  g,
                  draw.center(m.from()),
                  draw.center(m.to()),
                  new Color(200, 200, 200),
                  2.5f,
                  true,
                  "");
              final Point c = draw.center(m.to());
              AirDraw.tag(
                  g,
                  "new: " + m.count() + " " + ResearchView.displayName(m.unitType().getName()),
                  c.x,
                  c.y + 12 * (1 + moves.indexOf(m)),
                  draw.mapData().getPlayerColor(owner.getName()));
            }
          });
      if (selectedZone != null && !selected.isEmpty() && mousePoint != null) {
        paintPreview(g, data);
      }
    }
  }

  /**
   * What clicking would do, as in a TripleA move: over an allowed zone a green arrow and the
   * aircraft arriving there; over a forbidden one a red arrow and the reason; elsewhere the
   * aircraft follow the mouse.
   */
  private void paintPreview(final Graphics2D g, final GameData data) {
    final Point from = draw.center(selectedZone);
    if (mousePoint.distance(from) < 20) {
      return;
    }
    final Territory to = hoverZone;
    final Color color;
    if (to == null || to.equals(selectedZone) || !destinations.containsKey(to)) {
      color = Color.WHITE;
    } else {
      color = destinations.get(to).isEmpty() ? AirDraw.LEGAL_LINE : PREVIEW_BLOCKED;
    }
    AirDraw.routeLine(g, from, mousePoint, color);
    AirSelection.paintShadow(g, frame.getUiContext(), getCurrentPlayer(), selected, mousePoint);
  }
}
