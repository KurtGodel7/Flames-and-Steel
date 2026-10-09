package games.strategy.triplea.ui.air;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.triplea.attachments.AirZoneAttachment;
import games.strategy.triplea.attachments.AirZoneAttachment.ZoneType;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.air.AirAllocation;
import games.strategy.triplea.delegate.air.AirCombat;
import games.strategy.triplea.delegate.air.AirCombat.Target;
import games.strategy.triplea.delegate.air.AirCombat.TargetAssignment;
import games.strategy.triplea.delegate.air.AirCombat.TargetKind;
import games.strategy.triplea.delegate.air.AirRange;
import games.strategy.triplea.formatter.MyFormatter;
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
import java.awt.Insets;
import java.awt.Point;
import java.awt.Polygon;
import java.util.ArrayList;
import java.util.Collection;
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
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import org.triplea.java.collections.IntegerMap;

/**
 * Flames and Steel: bombers choose their targets on the map. The player selects bombers in an air
 * box; every target they can all reach lights up (the rest of the zone is dimmed), hovering a
 * target describes what is there, and clicking it sends the bombers. Strikes are drawn as arrows
 * from the box to the target until Done.
 */
public final class AirTargetPanel extends ActionPanel {
  private static final long serialVersionUID = 1L;
  private static final Color TARGET_FILL = new Color(80, 220, 110, 90);
  private static final Color DIM_FILL = new Color(0, 0, 0, 110);
  private static final Color STRIKE_LINE = new Color(255, 80, 60);
  private static final Color PREVIEW_BLOCKED = new Color(230, 60, 50);

  private final TripleAFrame frame;
  private final AirDraw draw;
  private final ExecutorService storeExecutor = Executors.newSingleThreadExecutor();

  private final List<TargetAssignment> staged = new ArrayList<>();
  private Consumer<List<TargetAssignment>> store = moves -> {};
  private @Nullable Territory selectedZone;
  private final IntegerMap<UnitType> selected = new IntegerMap<>();

  /** Targets in the selected zone, and whether every selected type can attack each. */
  private Map<Target, Boolean> targets = Map.of();

  private boolean listening;
  // what the mouse points at, for the strike preview
  private @Nullable Territory hoverPlace;
  private @Nullable Point mousePoint;
  private final JPanel targetsPanel = AirSelection.compactPanel();
  private final MapOverlay overlay = this::paintOverlay;
  private final UnitSelectionListener unitListener =
      (units, territory, md) -> SwingUtilities.invokeLater(() -> clicked(units, territory, md));
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

  public AirTargetPanel(final TripleAFrame frame) {
    super(frame);
    this.frame = frame;
    this.draw = new AirDraw(frame.getUiContext());
    targetsPanel.setLayout(new BoxLayout(targetsPanel, BoxLayout.Y_AXIS));
    targetsPanel.setAlignmentX(LEFT_ALIGNMENT);
  }

  @Override
  public String toString() {
    return "AirTargetPanel";
  }

  /** Shows the panel and the map tools for the nation's targets. */
  public void start(
      final GamePlayer player,
      final List<TargetAssignment> start,
      final Consumer<List<TargetAssignment>> store) {
    SwingUtilities.invokeLater(
        () -> {
          staged.clear();
          staged.addAll(start);
          this.store = store;
          clearSelection();
          draw.setAirMapMode(frame.getAirMapMode());
          showAssigned();
          if (frame.getAirMapMode() != null) {
            frame
                .getAirMapMode()
                .setActing(
                    player,
                    (zone, unit) ->
                        unit.isOwnedBy(player)
                            && AirCombat.isBomber(unit)
                            && !AirCombat.getBombers(player, zone).isEmpty(),
                    (zone, unit) ->
                        AirZoneAttachment.get(zone).orElseThrow().getZoneType() == ZoneType.RESERVE
                            ? "Aircraft in the reserve do not fight."
                            : ResearchView.displayName(unit.getType().getName())
                                + ": fighters do not strike targets.");
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

  /** Blocks until Done; returns the strikes. */
  public List<TargetAssignment> waitForTargets() {
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
  public void performDone() {
    final boolean anyLeft = zonesWithBombersLeft() > 0;
    if (anyLeft && staged.isEmpty()) {
      final int answer =
          JOptionPane.showConfirmDialog(
              JOptionPane.getFrameForComponent(this),
              "Your bombers have not been given targets. End without attacking?",
              "Bomber targets",
              JOptionPane.YES_NO_OPTION);
      if (answer != JOptionPane.YES_OPTION) {
        return;
      }
    }
    clearSelection();
    release();
  }

  private long zonesWithBombersLeft() {
    final GamePlayer player = getCurrentPlayer();
    try (GameData.Unlocker ignored = getData().acquireReadLock()) {
      return AirZoneAttachment.getAirZones(getData().getMap()).stream()
          .filter(
              z -> !available(player, z).isEmpty() && !AirCombat.getTargets(player, z).isEmpty())
          .count();
    }
  }

  // ---- selecting bombers and targets

  private void clicked(
      final List<Unit> clickedUnits, final @Nullable Territory clicked, final MouseDetails md) {
    // in air map mode a click on a stack means that zone's aircraft
    List<Unit> units = clickedUnits;
    Territory territory = clicked;
    final AirMapMode mode = frame.getAirMapMode();
    if (mode != null) {
      final Optional<AirMapMode.Hit> hit = mode.hit(md.getMapPoint());
      if (hit.isPresent()) {
        units = hit.get().units();
        territory = hit.get().zone();
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
    if (AirZoneAttachment.isAirZone(territory)) {
      selectBombers(player, territory, units, md);
      return;
    }
    if (selectedZone == null || selected.isEmpty()) {
      return;
    }
    final Territory place = territory;
    final List<Target> here =
        targets.entrySet().stream()
            .filter(e -> e.getValue() && e.getKey().territory().equals(place))
            .map(Map.Entry::getKey)
            .toList();
    if (here.isEmpty()) {
      frame.setStatusErrorMessage(whyNotTarget(player, territory));
      return;
    }
    Target target = here.get(0);
    if (here.size() > 1) {
      final String[] options = here.stream().map(t -> kindName(t.kind())).toArray(String[]::new);
      final int choice =
          JOptionPane.showOptionDialog(
              JOptionPane.getFrameForComponent(this),
              "Attack which ships in " + territory.getName() + "?",
              "Bomber target",
              JOptionPane.DEFAULT_OPTION,
              JOptionPane.QUESTION_MESSAGE,
              null,
              options,
              options[0]);
      if (choice < 0) {
        return;
      }
      target = here.get(choice);
    }
    assign(target);
  }

  private void selectBombers(
      final GamePlayer player,
      final Territory zone,
      final List<Unit> units,
      final MouseDetails md) {
    final IntegerMap<UnitType> available = available(player, zone);
    if (!zone.equals(selectedZone)) {
      selected.clear();
      selectedZone = zone;
    }
    final int added =
        AirSelection.add(
            selected,
            available,
            units,
            u -> u.isOwnedBy(player) && AirCombat.isBomber(u) && available.getInt(u.getType()) > 0,
            md);
    if (added == 0 && !units.isEmpty()) {
      final Unit unit = units.get(0);
      frame.setStatusErrorMessage(
          !unit.isOwnedBy(player)
              ? "Those are not your aircraft."
              : !AirCombat.isBomber(unit)
                  ? "Fighters do not strike; pick bombers."
                  : "All of those are already picked or have targets; undo a strike to change it.");
    }
    if (available.isEmpty()) {
      if (units.isEmpty()) {
        frame.setStatusErrorMessage(
            "You have no bombers without a target in " + AirAllocation.zoneName(zone) + ".");
      }
      clearSelection();
    }
    selectionChanged();
  }

  private void assign(final Target target) {
    for (final UnitType type : selected.keySet()) {
      final int count = selected.getInt(type);
      boolean merged = false;
      for (int i = 0; i < staged.size(); i++) {
        final TargetAssignment a = staged.get(i);
        if (a.zone().equals(selectedZone)
            && a.unitType().equals(type)
            && a.target().equals(target)) {
          staged.set(i, new TargetAssignment(a.zone(), type, a.count() + count, target));
          merged = true;
        }
      }
      if (!merged) {
        staged.add(new TargetAssignment(selectedZone, type, count, target));
      }
    }
    clearSelection();
    selectionChanged();
    strikesChanged();
  }

  /** The player's bombers in the zone that do not have a target yet. */
  private IntegerMap<UnitType> available(final GamePlayer player, final Territory zone) {
    final IntegerMap<UnitType> result = new IntegerMap<>();
    try (GameData.Unlocker ignored = getData().acquireReadLock()) {
      for (final Unit u : AirCombat.getBombers(player, zone)) {
        result.add(u.getType(), 1);
      }
    }
    for (final TargetAssignment a : staged) {
      if (a.zone().equals(zone)) {
        result.add(a.unitType(), -a.count());
      }
    }
    result.keySet().removeIf(t -> result.getInt(t) <= 0);
    return result;
  }

  private void clearSelection() {
    selectedZone = null;
    selected.clear();
    targets = Map.of();
  }

  private void selectionChanged() {
    selectionChanged(true);
  }

  /** Recomputes the reachable targets; the arrow counters skip rebuilding the counters. */
  private void selectionChanged(final boolean rebuildPanel) {
    final GamePlayer player = getCurrentPlayer();
    final Map<Target, Boolean> result = new LinkedHashMap<>();
    if (selectedZone != null && !selected.isEmpty()) {
      final List<Collection<Unit>> highlight = new ArrayList<>();
      try (GameData.Unlocker ignored = getData().acquireReadLock()) {
        for (final Target target : AirCombat.getTargets(player, selectedZone)) {
          result.put(
              target,
              selected.keySet().stream()
                  .allMatch(type -> AirCombat.mayAttack(player, selectedZone, type, target)));
        }
        highlight.addAll(
            AirSelection.highlight(selected, AirCombat.getBombers(player, selectedZone)));
      }
      getMap().setUnitHighlight(highlight);
    } else {
      getMap().setUnitHighlight(List.of());
    }
    targets = result;
    if (frame.getAirMapMode() != null) {
      frame.getAirMapMode().setBusy(selectedZone != null);
    }
    if (selectedZone != null && !selected.isEmpty()) {
      frame.setStatusMessage(
          selectionText()
              + " picked: point at a target to see the strike, click a green one to make it.");
    } else if (selectedZone != null) {
      frame.setStatusMessage(
          "Pick bombers in "
              + AirAllocation.zoneName(selectedZone)
              + " with the arrows in the panel, or by clicking their stacks.");
    } else {
      frame.clearStatusMessage();
      mousePoint = null;
    }
    showAssigned();
    if (rebuildPanel) {
      rebuild(player);
    } else {
      fillTargets(player);
      refresh.run();
    }
    getMap().repaint();
  }

  private String selectionText() {
    final List<String> parts = new ArrayList<>();
    for (final UnitType type : selected.keySet()) {
      parts.add(selected.getInt(type) + " " + ResearchView.displayName(type.getName()));
    }
    return String.join(", ", parts);
  }

  private void hovered(final @Nullable Territory territory) {
    hoverPlace = territory != null && !AirZoneAttachment.isAirZone(territory) ? territory : null;
    if (!listening || selectedZone == null || selected.isEmpty() || territory == null) {
      return;
    }
    if (AirZoneAttachment.isAirZone(territory)) {
      frame.clearStatusMessage();
      return;
    }
    final GamePlayer player = getCurrentPlayer();
    final List<String> parts = new ArrayList<>();
    try (GameData.Unlocker ignored = getData().acquireReadLock()) {
      targets.forEach(
          (target, legal) -> {
            if (legal && target.territory().equals(territory)) {
              parts.add(describe(player, target));
            }
          });
      if (parts.isEmpty()) {
        frame.setStatusWarningMessage(whyNotTarget(player, territory));
      } else {
        frame.setStatusMessage(String.join("  |  ", parts) + "  (click to attack)");
      }
    }
  }

  /** What the bombers would meet at a target. Call with the read lock held. */
  private static String describe(final GamePlayer player, final Target target) {
    final Territory t = target.territory();
    final List<Unit> aa =
        t.getUnitCollection()
            .getMatches(
                Matches.unitIsAaForAnything().and(u -> AirCombat.atWar(player, u.getOwner())));
    final String aaText =
        aa.isEmpty() ? "no AA" : "AA: " + MyFormatter.unitsToText(aa) + " fire first";
    return switch (target.kind()) {
      case LAND ->
          t.getName()
              + ": "
              + MyFormatter.unitsToText(AirCombat.getLandTargetUnits(player, t))
              + "; "
              + aaText;
      case SURFACE ->
          t.getName()
              + ", surface ships that can be hit: "
              + MyFormatter.unitsToText(AirCombat.getShipTargetUnits(player, t, false));
      case SUBS ->
          t.getName()
              + ", submarines: "
              + MyFormatter.unitsToText(AirCombat.getShipTargetUnits(player, t, true));
      case STRATEGIC ->
          t.getName()
              + ", strategic bombing: raid limit "
              + AirCombat.bombingCap(t)
              + " PUs this round (twice the territory value); "
              + aaText;
    };
  }

  private String whyNotTarget(final GamePlayer player, final Territory territory) {
    if (selectedZone == null) {
      return "";
    }
    final List<Target> there =
        targets.keySet().stream().filter(t -> t.territory().equals(territory)).toList();
    if (there.isEmpty()) {
      final boolean covered =
          AirZoneAttachment.get(selectedZone)
              .orElseThrow()
              .getCoveredTerritories()
              .contains(territory);
      return covered
          ? territory.getName() + ": nothing these bombers can attack."
          : territory.getName()
              + " is not a target from "
              + AirAllocation.zoneName(selectedZone)
              + ".";
    }
    final Target target = there.get(0);
    for (final UnitType type : selected.keySet()) {
      final String name = ResearchView.displayName(type.getName());
      final String narrow = selected.size() > 1 ? " Pick only the other types to attack it." : "";
      if (!AirCombat.mayAttack(type, target.kind())) {
        return name + " cannot attack " + kindName(target.kind()).toLowerCase() + "." + narrow;
      }
      if (!AirCombat.mayAttack(player, selectedZone, type, target)) {
        // a target must be within half the bomber's range of a place it can land
        final int reach = reach(player, type);
        final java.util.OptionalInt distance =
            AirRange.landingDistance(player, selectedZone, type, territory, reach + 6);
        return "Out of range: "
            + territory.getName()
            + (distance.isPresent()
                ? " is " + distance.getAsInt() + " steps from the nearest "
                : " is more than " + (reach + 6) + " steps from any ")
            + player.getName()
            + " landing spot; a "
            + name
            + " strikes up to "
            + reach
            + (reach == 1 ? " step" : " steps")
            + " from one (half its range of "
            + rangeInForce(player, type)
            + ", rounded down)."
            + narrow;
      }
    }
    return "";
  }

  /** The range a bomber of this type flies at from the selected zone (escorts limit Bomb zones). */
  private int rangeInForce(final GamePlayer player, final UnitType type) {
    if (selectedZone != null
        && AirZoneAttachment.get(selectedZone).orElseThrow().getZoneType() == ZoneType.BOMB) {
      return bombZoneRange(player, selectedZone);
    }
    return AirRange.range(type, player);
  }

  private int reach(final GamePlayer player, final UnitType type) {
    if (selectedZone != null
        && AirZoneAttachment.get(selectedZone).orElseThrow().getZoneType() == ZoneType.BOMB) {
      return bombZoneRange(player, selectedZone) / 2;
    }
    return AirRange.range(type, player) / 2;
  }

  private static int bombZoneRange(final GamePlayer player, final Territory zone) {
    return AirRange.shortestRange(
        zone.getUnitCollection()
            .getMatches(
                u -> Matches.unitIsAir().test(u) && !AirCombat.atWar(player, u.getOwner())));
  }

  private static String kindName(final TargetKind kind) {
    return switch (kind) {
      case LAND -> "Land units";
      case SURFACE -> "Surface ships";
      case SUBS -> "Submarines";
      case STRATEGIC -> "Strategic bombing";
    };
  }

  /** Takes bombers that already have a target off their zone's stacks in air map mode. */
  private void showAssigned() {
    final AirMapMode mode = frame.getAirMapMode();
    if (mode == null) {
      return;
    }
    final Map<Territory, Map<GamePlayer, IntegerMap<UnitType>>> leaving = new HashMap<>();
    for (final TargetAssignment a : staged) {
      leaving
          .computeIfAbsent(a.zone(), k -> new HashMap<>())
          .computeIfAbsent(getCurrentPlayer(), k -> new IntegerMap<>())
          .add(a.unitType(), a.count());
    }
    mode.setPending(leaving, Map.of());
  }

  private void strikesChanged() {
    showAssigned();
    final List<TargetAssignment> snapshot = List.copyOf(staged);
    storeExecutor.execute(() -> store.accept(snapshot));
    rebuild(getCurrentPlayer());
    getMap().repaint();
  }

  // ---- the side panel

  private void rebuild(final GamePlayer player) {
    removeAll();
    actionLabel.setText(player.getName() + " bomber targets");
    // the help is a tooltip on the heading; the panel itself lists the strikes
    actionLabel.setToolTipText(
        AirSelection.wrap(
            AirSelection.HELP
                + " Or use the counters here. The targets"
                + " all picked bombers can reach light up on the map and are listed here;"
                + " click one to attack it."));
    add(leftAligned(actionLabel));

    if (selectedZone != null) {
      add(leftAligned(new JLabel("Bombers in " + AirAllocation.zoneName(selectedZone) + ":")));
      add(
          leftAligned(
              AirSelection.chooser(
                  frame.getUiContext(),
                  player,
                  available(player, selectedZone),
                  selected,
                  () -> selectionChanged(false))));
      fillTargets(player);
      add(targetsPanel);
    }

    add(Box.createVerticalStrut(6));
    add(leftAligned(new JLabel(staged.isEmpty() ? "No strikes yet." : "Strikes:")));
    for (final TargetAssignment a : List.copyOf(staged)) {
      final JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
      row.add(new JLabel(icon(player, a.unitType())));
      row.add(
          new JLabel(
              a.count()
                  + " "
                  + ResearchView.displayName(a.unitType().getName())
                  + " from "
                  + AirAllocation.zoneName(a.zone())
                  + " → "
                  + a.target().describe()));
      final JButton undo = smallButton("Undo");
      undo.addActionListener(
          e -> {
            staged.remove(a);
            clearSelection();
            selectionChanged();
            strikesChanged();
          });
      row.add(undo);
      add(leftAligned(row));
    }
    final JButton undoAll = new JButton("Undo all");
    undoAll.setEnabled(!staged.isEmpty());
    undoAll.addActionListener(
        e -> {
          staged.clear();
          clearSelection();
          selectionChanged();
          strikesChanged();
        });
    add(Box.createVerticalStrut(6));
    final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
    buttons.add(undoAll);
    buttons.add(createDoneButton());
    add(leftAligned(buttons));
    add(Box.createVerticalGlue());
    refresh.run();
  }

  /** The reach of each picked type, "only" buttons for mixed picks, and the targets in reach. */
  private void fillTargets(final GamePlayer player) {
    targetsPanel.removeAll();
    if (selectedZone == null || selected.isEmpty()) {
      targetsPanel.revalidate();
      return;
    }
    final List<String> reaches = new ArrayList<>();
    for (final UnitType type : selected.keySet()) {
      reaches.add(ResearchView.displayName(type.getName()) + " " + reach(player, type));
    }
    targetsPanel.add(leftAligned(new JLabel("Reach: " + String.join(", ", reaches))));
    if (selected.size() > 1) {
      final JPanel only = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
      only.add(new JLabel("See one type's own reach:"));
      for (final UnitType type : selected.keySet()) {
        final JButton button = smallButton("Only " + ResearchView.displayName(type.getName()));
        button.addActionListener(
            e -> {
              final int count = selected.getInt(type);
              selected.clear();
              selected.put(type, count);
              selectionChanged();
            });
        only.add(button);
      }
      targetsPanel.add(leftAligned(only));
    }
    final List<Target> legal =
        targets.entrySet().stream().filter(Map.Entry::getValue).map(Map.Entry::getKey).toList();
    targetsPanel.add(
        leftAligned(
            new JLabel(
                legal.isEmpty()
                    ? "No target in reach of all of them."
                    : "Targets in reach (" + legal.size() + "):")));
    try (GameData.Unlocker ignored = getData().acquireReadLock()) {
      for (final Target target : legal) {
        final JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        final JButton attack = smallButton("Attack");
        attack.addActionListener(e -> assign(target));
        row.add(attack);
        final JButton show = smallButton("Show");
        show.addActionListener(e -> getMap().centerOn(target.territory()));
        row.add(show);
        final JLabel text = new JLabel(target.describe());
        text.setToolTipText(describe(player, target));
        row.add(text);
        targetsPanel.add(leftAligned(row));
      }
    }
    targetsPanel.revalidate();
    targetsPanel.repaint();
  }

  private static JButton smallButton(final String text) {
    final JButton b = new JButton(text);
    b.setMargin(new Insets(0, 4, 0, 4));
    return b;
  }

  private Icon icon(final GamePlayer player, final UnitType type) {
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
    final GamePlayer player = getCurrentPlayer();
    try (GameData.Unlocker ignored = data.acquireReadLock()) {
      paintRangeTags(g, data, player);
      if (selectedZone != null) {
        final AirZoneAttachment zone = AirZoneAttachment.get(selectedZone).orElseThrow();
        final Map<Territory, Boolean> legalPlaces = new HashMap<>();
        targets.forEach((t, legal) -> legalPlaces.merge(t.territory(), legal, Boolean::logicalOr));
        for (final Territory t : zone.getCoveredTerritories()) {
          if (!legalPlaces.getOrDefault(t, false)) {
            fill(g, t, DIM_FILL);
          }
        }
        legalPlaces.forEach(
            (t, legal) -> {
              if (legal) {
                fill(g, t, TARGET_FILL);
                draw.outlineBox(g, t, AirDraw.LEGAL_LINE, 3f);
              }
            });
        draw.outlineBox(g, selectedZone, AirDraw.ORIGIN_LINE, 5f);
      }
      if (selectedZone != null && !selected.isEmpty() && mousePoint != null) {
        paintPreview(g, player);
      }
      // strikes: arrow from the box to the target, with the bombers and count at the target
      final Map<Territory, Integer> perTarget = new HashMap<>();
      for (final TargetAssignment a : staged) {
        final Point end = draw.middle(a.target().territory());
        final int n = perTarget.merge(a.target().territory(), 1, Integer::sum) - 1;
        final Point icon = new Point(end.x - 16 + n * 34, end.y - 16);
        AirDraw.arrow(g, draw.center(a.zone()), end, STRIKE_LINE, 4f, false, "", 6);
        draw.iconAt(g, icon, player, a.unitType(), a.count());
      }
    }
  }

  /**
   * What clicking would do: over a target in reach a green arrow and what is there, over anything
   * else in the zone a red arrow and why not; elsewhere the bombers follow the mouse.
   */
  private void paintPreview(final Graphics2D g, final GamePlayer player) {
    final Point from = draw.center(selectedZone);
    if (mousePoint.distance(from) < 20) {
      AirSelection.paintShadow(g, frame.getUiContext(), player, selected, mousePoint);
      return;
    }
    final Territory place = hoverPlace;
    Color color = Color.WHITE;
    String why = "";
    if (place != null) {
      final boolean inReach =
          targets.entrySet().stream()
              .anyMatch(e -> e.getValue() && e.getKey().territory().equals(place));
      if (inReach) {
        color = AirDraw.LEGAL_LINE;
      } else {
        why = whyNotTarget(player, place);
        color = why.isEmpty() ? Color.WHITE : PREVIEW_BLOCKED;
      }
    }
    AirDraw.routeLine(g, from, mousePoint, color);
    AirSelection.paintShadow(g, frame.getUiContext(), player, selected, mousePoint);
    if (!why.isEmpty()) {
      // the reason next to the mouse, where the player is looking
      AirDraw.tag(g, why, mousePoint.x, mousePoint.y + 52, new Color(255, 140, 120));
    }
  }

  private void fill(final Graphics2D g, final Territory t, final Color color) {
    g.setColor(color);
    for (final Polygon p : draw.mapData().getPolygons(t)) {
      g.fillPolygon(p);
    }
  }

  /** On each Bomb box the player has aircraft in: the range the whole side flies at. */
  private void paintRangeTags(final Graphics2D g, final GameData data, final GamePlayer player) {
    for (final Territory zone : AirZoneAttachment.getAirZones(data.getMap())) {
      if (AirZoneAttachment.get(zone).orElseThrow().getZoneType() != ZoneType.BOMB
          || AirCombat.getBombers(player, zone).isEmpty()) {
        continue;
      }
      final int range = bombZoneRange(player, zone);
      final int bomberRange =
          AirCombat.getBombers(player, zone).stream()
              .mapToInt(u -> AirRange.range(u.getType(), player))
              .min()
              .orElse(range);
      final Point c = draw.center(zone);
      AirDraw.tag(
          g,
          "Range "
              + range
              + " (reach "
              + range / 2
              + ")"
              + (range < bomberRange ? ": limited by escorts" : ""),
          c.x,
          c.y + 150,
          Color.WHITE);
    }
  }
}
