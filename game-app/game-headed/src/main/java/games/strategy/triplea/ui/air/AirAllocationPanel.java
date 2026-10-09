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
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
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

  private final JPanel selectionPanel = new JPanel();
  private final JPanel movesPanel = new JPanel();
  private final JLabel hint = new JLabel();

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
      };

  public AirAllocationPanel(final TripleAFrame frame) {
    super(frame);
    this.frame = frame;
    this.draw = new AirDraw(frame.getUiContext());
    selectionPanel.setLayout(new BoxLayout(selectionPanel, BoxLayout.Y_AXIS));
    movesPanel.setLayout(new BoxLayout(movesPanel, BoxLayout.Y_AXIS));
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
    if (mode != null && mode.isEnabled()) {
      final Optional<AirMapMode.Hit> hit = mode.hit(md.getMapPoint());
      if (hit.isPresent()) {
        units = hit.get().units();
        territory = hit.get().zone();
      } else if (territory != null && !AirZoneAttachment.isAirZone(territory)) {
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
        if (selected.isEmpty()) {
          clearSelection();
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
    if (selected.isEmpty()) {
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
              + " picked: click a green zone to send them, or click again to pick more.");
    } else {
      frame.clearStatusMessage();
    }
    rebuild(player);
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
    if (!listening || selectedZone == null || territory == null) {
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

  private void movesChanged() {
    final List<AirMove> snapshot = List.copyOf(staged);
    storeExecutor.execute(() -> store.accept(snapshot));
    rebuild(getCurrentPlayer());
    getMap().repaint();
  }

  // ---- the side panel

  private void rebuild(final GamePlayer player) {
    removeAll();
    actionLabel.setText(
        player.getName() + (stage == Stage.BOMBERS ? " bomber" : " fighter") + " allocation");
    add(leftAligned(actionLabel));
    hint.setText(
        "<html>"
            + AirSelection.HELP
            + " Then click the zone to send them to."
            + "<br>Enemies see these moves only at the reveal"
            + (stage == Stage.FIGHTERS ? "; the bombers' moves are already revealed." : ".")
            + "</html>");
    hint.setBorder(BorderFactory.createEmptyBorder(4, 5, 4, 5));
    add(leftAligned(hint));

    selectionPanel.removeAll();
    if (selectedZone != null && !selected.isEmpty()) {
      selectionPanel.add(
          leftAligned(new JLabel("Selected in " + AirAllocation.zoneName(selectedZone) + ":")));
      final IntegerMap<UnitType> available = available(player, selectedZone);
      for (final UnitType type : selected.keySet()) {
        final JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        row.add(new JLabel(icon(player, type)));
        final JSpinner spinner =
            new JSpinner(
                new SpinnerNumberModel(
                    selected.getInt(type), 1, Math.max(1, available.getInt(type)), 1));
        spinner.addChangeListener(
            e -> {
              selected.put(type, (Integer) spinner.getValue());
              selectionChanged();
            });
        row.add(spinner);
        row.add(new JLabel(ResearchView.displayName(type.getName())));
        selectionPanel.add(leftAligned(row));
      }
      selectionPanel.add(
          leftAligned(new JLabel("Now click a highlighted box (" + legalCount() + " allowed).")));
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

  private static JPanel leftAligned(final Component c) {
    final JPanel p = new JPanel(new BorderLayout());
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
          AirSelection.paintPicked(g, mode, selectedZone, getCurrentPlayer(), selected);
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
      allied.forEach((owner, moves) -> paintMoves(g, data, owner, moves, true));
      paintMoves(g, data, getCurrentPlayer(), staged, false);
    }
  }

  private void paintMoves(
      final Graphics2D g,
      final GameData data,
      final GamePlayer owner,
      final List<AirMove> moves,
      final boolean ally) {
    final Color color = draw.mapData().getPlayerColor(owner.getName());
    final Map<Territory, Integer> outCount = new HashMap<>();
    for (final AirMove m : moves) {
      final Point arrival =
          draw.ghostStack(
              g,
              data,
              m.to(),
              owner,
              m.unitType(),
              m.count(),
              ally ? color : AirDraw.PENDING_LINE,
              true);
      AirDraw.arrow(
          g,
          draw.center(m.from()),
          arrival,
          ally ? color : AirDraw.PENDING_LINE,
          ally ? 3f : 4.5f,
          ally,
          "",
          20);
      outCount.merge(m.from(), m.count(), Integer::sum);
    }
    if (!ally) {
      // how many leave each box
      outCount.forEach(
          (zone, count) -> {
            final Point c = draw.center(zone);
            AirDraw.tag(g, count + " leaving", c.x, c.y - 30, AirDraw.PENDING_LINE);
          });
    }
  }
}
