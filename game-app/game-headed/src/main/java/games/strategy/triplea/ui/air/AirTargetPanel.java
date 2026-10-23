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
import games.strategy.triplea.delegate.air.AirCover;
import games.strategy.triplea.delegate.air.AirRange;
import games.strategy.triplea.delegate.air.LongRangeRaid;
import games.strategy.triplea.delegate.air.TargetSplit;
import games.strategy.triplea.formatter.MyFormatter;
import games.strategy.triplea.image.UnitImageFactory.ImageKey;
import games.strategy.triplea.ui.ActionPanel;
import games.strategy.triplea.ui.DefaultMapSelectionListener;
import games.strategy.triplea.ui.MouseDetails;
import games.strategy.triplea.ui.PinnedLayout;
import games.strategy.triplea.ui.TripleAFrame;
import games.strategy.triplea.ui.panels.map.MapOverlay;
import games.strategy.triplea.ui.panels.map.MapSelectionListener;
import games.strategy.triplea.ui.panels.map.UnitSelectionListener;
import games.strategy.triplea.ui.research.ResearchView;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.FlowLayout;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
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
import javax.swing.BorderFactory;
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
 *
 * <p>Request 30: the fighter cover step uses the same panel. The player selects fighters and clicks
 * a territory in their front within reach; the enemy's bombers are shown on their targets, and
 * each place shows its side's count against the cap ("B 3/4 F 2/4").
 */
public final class AirTargetPanel extends ActionPanel {
  private static final long serialVersionUID = 1L;
  private static final Color TARGET_FILL = new Color(80, 220, 110, 90);
  private static final Color DIM_FILL = new Color(0, 0, 0, 110);
  private static final Color STRIKE_LINE = new Color(255, 80, 60);
  private static final Color PREVIEW_BLOCKED = new Color(230, 60, 50);
  private static final Color ENEMY_LINE = new Color(170, 90, 255);

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
  // Flames and Steel (request 16): after Done the strikes are the game's; the panel only shows them
  private boolean finished;
  // what the mouse points at, for the strike preview
  private @Nullable Territory hoverPlace;
  private @Nullable Point mousePoint;
  private final JPanel targetsPanel = AirSelection.compactPanel();
  // request 24: the pinned buttons and the scrolling list; fronts the player opened
  private final PinnedLayout layout = new PinnedLayout();
  private final java.util.Set<Territory> expanded = new java.util.HashSet<>();
  private @Nullable String lastShownGroup;
  private final MapOverlay overlay = this::paintOverlay;
  // request 30: the fighter cover step uses this panel too
  private boolean coverMode;
  // request 30: every nation's bomber targets (cover step) and the allies' choices so far
  private Map<GamePlayer, List<TargetAssignment>> revealed = Map.of();
  private Map<GamePlayer, List<TargetAssignment>> allied = Map.of();
  // request 30: each nation's choices the last time, for "Repeat last round"
  private final Map<String, List<TargetAssignment>> lastRound = new HashMap<>();
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
    start(player, start, store, false, Map.of(), Map.of());
  }

  /**
   * Shows the panel for the nation's bomber targets or (request 30) its fighter cover.
   *
   * @param cover whether this is the fighter cover step
   * @param revealed every nation's bomber targets (cover step only)
   * @param allied the allies' choices so far this step (they share the caps)
   */
  public void start(
      final GamePlayer player,
      final List<TargetAssignment> start,
      final Consumer<List<TargetAssignment>> store,
      final boolean cover,
      final Map<GamePlayer, List<TargetAssignment>> revealed,
      final Map<GamePlayer, List<TargetAssignment>> allied) {
    SwingUtilities.invokeLater(
        () -> {
          coverMode = cover;
          this.revealed = new HashMap<>(revealed);
          this.allied = new HashMap<>(allied);
          finished = false;
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
                            && isMine(unit)
                            && !targetsFor(player, zone, unit.getType()).isEmpty(),
                    (zone, unit) -> greyReason(player, zone, unit));
            frame.getAirMapMode().setCoverMode(coverMode);
            frame.getAirMapMode().setMarkUnassigned(true);
          }
          if (!listening) {
            listening = true;
            getMap().addUnitSelectionListener(unitListener);
            getMap().addMapSelectionListener(hoverListener);
            getMap().addOverlay(overlay);
          }
          rebuild(player);
          // request 22: begin with the first group waiting, after the map has drawn its boxes
          SwingUtilities.invokeLater(() -> selectNextWaiting(player));
        });
  }

  /** Why an aircraft cannot be picked in this step (shown when pointing at a grey icon). */
  private String greyReason(final GamePlayer player, final Territory zone, final Unit unit) {
    final String name = ResearchView.displayName(unit.getType().getName());
    if (AirZoneAttachment.get(zone).orElseThrow().getZoneType() == ZoneType.RESERVE) {
      return "Aircraft in the reserve do not fight.";
    }
    if (coverMode) {
      return AirCombat.isBomber(unit)
          ? name + " (grey): bombers chose their targets in the last step."
          : name
              + " (grey): nowhere to cover from "
              + AirAllocation.zoneName(zone)
              + " ("
              + AirRange.reachText(player, zone, unit.getType()).toLowerCase(java.util.Locale.ROOT)
              + ").";
    }
    return !AirCombat.isBomber(unit)
        ? name
            + " (grey): fighters don't make strikes; they are placed over territories in the next"
            + " step, to escort or defend."
        : AirRange.reachText(player, zone, unit.getType())
            + " (grey): no targets in reach from "
            + AirAllocation.zoneName(zone)
            + ".";
  }

  /** The aircraft this step is about: bombers (targets) or fighter-types (cover). */
  private boolean isMine(final Unit unit) {
    return coverMode ? AirCombat.isFighterType(unit) : AirCombat.isBomber(unit);
  }

  /** The player's aircraft of this step in the zone (none in the reserve). */
  private List<Unit> myAircraft(final GamePlayer player, final Territory zone) {
    if (AirZoneAttachment.get(zone).orElseThrow().getZoneType() == ZoneType.RESERVE) {
      return List.of();
    }
    return zone.getUnitCollection().getMatches(u -> u.isOwnedBy(player) && isMine(u));
  }

  /** What this nation's aircraft of the type in the zone may be sent to. */
  private List<Target> targetsFor(final GamePlayer player, final Territory zone, final UnitType type) {
    if (coverMode) {
      return AirCover.places(player, zone, type, revealed).stream().map(AirCover::over).toList();
    }
    return AirCombat.getTargets(player, zone, type);
  }

  /** Every target in the zone for the step's aircraft there. */
  private List<Target> allTargets(final GamePlayer player, final Territory zone) {
    if (!coverMode) {
      return AirCombat.getTargets(player, zone);
    }
    final java.util.Set<Target> result = new java.util.LinkedHashSet<>();
    myAircraft(player, zone).stream()
        .map(Unit::getType)
        .distinct()
        .forEach(type -> result.addAll(targetsFor(player, zone, type)));
    return new ArrayList<>(result);
  }

  private boolean mayGo(
      final GamePlayer player, final Territory zone, final UnitType type, final Target target) {
    return coverMode
        ? targetsFor(player, zone, type).contains(target)
        : AirCombat.mayAttack(player, zone, type, target);
  }

  /** The nation's own choices and its allies', for the caps. */
  private Map<GamePlayer, List<TargetAssignment>> sideChoices(final GamePlayer player) {
    final Map<GamePlayer, List<TargetAssignment>> all = new HashMap<>(allied);
    all.put(player, List.copyOf(staged));
    return all;
  }

  /** How many more of this step's aircraft the nation's side may send over the territory. */
  private int room(final GamePlayer player, final Territory zone, final Territory t) {
    final int cap = AirCover.cap(player, zone, t, coverMode, revealed);
    if (cap == AirCover.NO_CAP) {
      return Integer.MAX_VALUE;
    }
    return Math.max(0, cap - AirCover.sideCount(player, zone, t, coverMode, sideChoices(player)));
  }

  /**
   * Request 30: a place's counts against the caps for the nation's side, e.g. "B 3/4 F 2/4"; in the
   * cover step bombers are the side's revealed bombers, and the enemy's follow ("enemy B 2").
   */
  private String badge(final GamePlayer player, final Territory zone, final Territory t) {
    final Map<GamePlayer, List<TargetAssignment>> bombers =
        coverMode ? revealed : sideChoices(player);
    final int b = AirCover.sideCount(player, zone, t, false, bombers);
    final int bombCap = AirCover.bomberCap(getData(), t);
    final StringBuilder sb = new StringBuilder("B " + b + "/" + capText(bombCap));
    if (coverMode) {
      final int f = AirCover.sideCount(player, zone, t, true, sideChoices(player));
      sb.append("  F ")
          .append(f)
          .append("/")
          .append(capText(AirCover.fighterCap(player, zone, t, revealed)));
      final int enemy = enemyBombers(player, zone, t);
      if (enemy > 0) {
        sb.append("  enemy B ").append(enemy);
      }
    }
    return sb.toString();
  }

  private static String capText(final int cap) {
    return cap == AirCover.NO_CAP ? "∞" : String.valueOf(cap);
  }

  /** How many enemy bombers are sent over the territory from the box (cover step). */
  private int enemyBombers(final GamePlayer player, final Territory zone, final Territory t) {
    int n = 0;
    for (final Map.Entry<GamePlayer, List<TargetAssignment>> e : revealed.entrySet()) {
      if (!AirCombat.atWar(player, e.getKey())) {
        continue;
      }
      for (final TargetAssignment a : e.getValue()) {
        if (a.zone().equals(zone) && a.target().territory().equals(t)) {
          n += a.count();
        }
      }
    }
    return n;
  }

  /** Blocks until Done; returns the strikes. */
  public List<TargetAssignment> waitForTargets() {
    waitForRelease();
    final List<TargetAssignment> result = List.copyOf(staged);
    final GamePlayer done = getCurrentPlayer();
    if (done != null) {
      lastRound.put(lastKey(done), result);
    }
    SwingUtilities.invokeLater(
        () -> {
          finished = true;
          stopListening();
          rebuild(getCurrentPlayer());
        });
    return result;
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
        frame.getAirMapMode().setMarkUnassigned(false);
        frame.getAirMapMode().setCoverMode(false);
        frame.getAirMapMode().setBanner(null);
        frame.getAirMapMode().setPending(Map.of(), Map.of());
        frame.getAirMapMode().setActing(null, (zone, unit) -> false, (zone, unit) -> "");
      }
      frame.clearStatusMessage();
    }
  }

  private String lastKey(final GamePlayer player) {
    return player.getName() + (coverMode ? "|cover" : "|targets");
  }

  @Override
  public void performDone() {
    final List<String> left = bombersLeft();
    if (!left.isEmpty()) {
      final int answer =
          JOptionPane.showConfirmDialog(
              JOptionPane.getFrameForComponent(this),
              String.join("\n", left)
                  + (coverMode
                      ? "\nThey will take no part this round. End anyway?"
                      : "\nThey will not attack this round. End anyway?"),
              coverMode ? "Fighter cover" : "Bomber targets",
              JOptionPane.YES_NO_OPTION);
      if (answer != JOptionPane.YES_OPTION) {
        return;
      }
    }
    clearSelection();
    release();
  }

  /**
   * One line per aircraft type and zone whose unassigned aircraft still have at least one legal
   * target (the same targets the grid counts and the picker offers), e.g. "2 Torpedo Bomber in
   * Mediterranean could still strike Sea Zone 97". Aircraft with nothing they may hit are left out.
   */
  private List<String> bombersLeft() {
    final GamePlayer player = getCurrentPlayer();
    final List<String> result = new ArrayList<>();
    try (GameData.Unlocker ignored = getData().acquireReadLock()) {
      for (final Territory zone : AirZoneAttachment.getAirZones(getData().getMap())) {
        final IntegerMap<UnitType> left = available(player, zone);
        for (final UnitType type : left.keySet()) {
          final List<Target> targets = targetsFor(player, zone, type);
          if (left.getInt(type) > 0 && !targets.isEmpty()) {
            result.add(
                left.getInt(type)
                    + " "
                    + ResearchView.displayName(type.getName())
                    + " in "
                    + AirAllocation.zoneName(zone)
                    + (coverMode ? " could still cover " : " could still strike ")
                    + describeTargets(targets));
          }
        }
      }
    }
    return result;
  }

  /** "Sea Zone 97", or "3 targets (Malta, Sicily, Sea Zone 97)", at most 3 named. */
  private static String describeTargets(final List<Target> targets) {
    final List<String> names = targets.stream().map(Target::describe).distinct().limit(3).toList();
    if (targets.size() == 1) {
      return names.get(0);
    }
    return targets.size()
        + " targets ("
        + String.join(", ", names)
        + (targets.size() > names.size() ? ", ..." : "")
        + ")";
  }

  // ---- selecting bombers and targets

  private void clicked(
      final List<Unit> clickedUnits, final @Nullable Territory clicked, final MouseDetails md) {
    // in air map mode a click on a stack means that zone's aircraft
    List<Unit> units = clickedUnits;
    Territory territory = clicked;
    final AirMapMode mode = frame.getAirMapMode();
    if (mode != null && mode.consumeDragClick()) {
      return; // the release that ended dragging a box
    }
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
              "Attack what in " + territory.getName() + "?",
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
    // request 22: a plain click on a group with nothing picked picks the whole group, exactly as
    // its "Choose targets" button does (Ctrl, Alt and Shift keep their usual meanings)
    if (!md.isShiftDown() && !md.isControlDown() && !md.isAltDown()) {
      final Optional<UnitType> type =
          units.stream()
              .filter(u -> u.isOwnedBy(player) && isMine(u))
              .map(Unit::getType)
              .filter(t -> available.getInt(t) > 0)
              .findFirst();
      if (type.isPresent() && (!zone.equals(selectedZone) || selected.getInt(type.get()) == 0)) {
        chooseGroup(player, zone, type.get(), false);
        return;
      }
    }
    if (!zone.equals(selectedZone)) {
      selected.clear();
      selectedZone = zone;
    }
    final int added =
        AirSelection.add(
            selected,
            available,
            units,
            u -> u.isOwnedBy(player) && isMine(u) && available.getInt(u.getType()) > 0,
            md);
    if (added == 0 && !units.isEmpty()) {
      final Unit unit = units.get(0);
      frame.setStatusErrorMessage(
          !unit.isOwnedBy(player)
              ? "Those are not your aircraft."
              : !isMine(unit)
                  ? (coverMode
                      ? "Bombers have their targets; pick fighters."
                      : "Fighters do not strike; pick bombers.")
                  : "All of those are already picked or placed; undo one to change it.");
    }
    if (available.isEmpty()) {
      if (units.isEmpty()) {
        frame.setStatusErrorMessage(
            (coverMode ? "You have no fighters left to place in " : "You have no bombers without"
                + " a target in ")
                + AirAllocation.zoneName(zone)
                + ".");
      }
      clearSelection();
    }
    selectionChanged();
  }

  private void assign(final Target target) {
    // request 30: never more than the side's cap over one place
    int room = room(getCurrentPlayer(), selectedZone, target.territory());
    boolean trimmed = false;
    for (final UnitType type : selected.keySet()) {
      final int wanted = selected.getInt(type);
      final int count = Math.min(wanted, room);
      trimmed |= count < wanted;
      if (count <= 0) {
        continue; // request 28: a group kept in view after all its bombers have targets
      }
      room -= count;
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
    final Territory capped = target.territory();
    final Territory cappedZone = selectedZone;
    clearSelection();
    selectionChanged();
    strikesChanged();
    // request 22: straight on to the next group still waiting
    selectNextWaiting(getCurrentPlayer());
    if (trimmed) {
      frame.setStatusErrorMessage(
          capped.getName()
              + ": your side's cap is reached ("
              + badge(getCurrentPlayer(), cappedZone, capped)
              + "); the rest stay unplaced.");
    }
  }

  /** A front's aircraft of one type that still have targets in reach and no target yet. */
  private record Group(Territory zone, UnitType type) {}

  /** The groups still waiting, front by front. */
  private List<Group> waitingGroups(final GamePlayer player) {
    final List<Group> result = new ArrayList<>();
    try (GameData.Unlocker ignored = getData().acquireReadLock()) {
      for (final Territory zone : AirZoneAttachment.getAirZones(getData().getMap())) {
        final IntegerMap<UnitType> free = available(player, zone);
        for (final UnitType type : free.keySet()) {
          if (free.getInt(type) > 0 && !targetsFor(player, zone, type).isEmpty()) {
            result.add(new Group(zone, type));
          }
        }
      }
    }
    return result;
  }

  /** Picks the first group still waiting, scrolls to it and lights up its targets. */
  private void selectNextWaiting(final GamePlayer player) {
    if (finished || player == null) {
      return;
    }
    final List<Group> waiting = waitingGroups(player);
    if (!waiting.isEmpty()) {
      chooseGroup(player, waiting.get(0).zone(), waiting.get(0).type(), true);
    }
    updateBanner(player, waiting.size());
  }

  /**
   * Request 22: the banner over the map while groups wait: "Italians: choose targets for 2 aircraft
   * groups. ..."; gone when none wait or the step ends.
   */
  private void updateBanner(final GamePlayer player, final int waiting) {
    final AirMapMode mode = frame.getAirMapMode();
    if (mode == null) {
      return;
    }
    mode.setBanner(
        finished || waiting == 0
            ? null
            : player.getName()
                + (coverMode ? ": place fighters for " : ": choose targets for ")
                + waiting
                + (waiting == 1 ? " aircraft group" : " aircraft groups")
                + (coverMode
                    ? ". Enemy bombers are shown on their targets. Click Place on the right, or"
                        + " click a group's box."
                    : ". Click Choose targets on the right, or click a group's box."));
  }

  /** The player's bombers in the zone that do not have a target yet. */
  private IntegerMap<UnitType> available(final GamePlayer player, final Territory zone) {
    final IntegerMap<UnitType> result = new IntegerMap<>();
    try (GameData.Unlocker ignored = getData().acquireReadLock()) {
      for (final Unit u : myAircraft(player, zone)) {
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
        for (final Target target : allTargets(player, selectedZone)) {
          result.put(
              target,
              selected.keySet().stream()
                  .allMatch(type -> mayGo(player, selectedZone, type, target)));
        }
        highlight.addAll(AirSelection.highlight(selected, myAircraft(player, selectedZone)));
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
              + (coverMode
                  ? " picked: click a green territory to place them over it (escort or defend)."
                  : " picked: point at a target to see the strike, click a green one to make it."));
    } else if (selectedZone != null) {
      frame.setStatusMessage(
          (coverMode ? "Pick fighters in " : "Pick bombers in ")
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
        frame.setStatusMessage(
            String.join("  |  ", parts) + (coverMode ? "  (click to place)" : "  (click to attack)"));
      }
    }
  }

  /** What the bombers would meet at a target. Call with the read lock held. */
  private String describe(final GamePlayer player, final Target target) {
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
              + aaText
              + "; "
              + badge(player, selectedZone, t);
      case COVER -> coverText(player, t, aaText);
    };
  }

  /**
   * Request 30, the cover step: "Hunan: enemy bombers 3 Dive Bomber (Germans) · B 0/4 F 2/4 ·
   * AA: ...".
   */
  private String coverText(final GamePlayer player, final Territory t, final String aaText) {
    final List<String> enemy = new ArrayList<>();
    for (final Map.Entry<GamePlayer, List<TargetAssignment>> e : revealed.entrySet()) {
      for (final TargetAssignment a : e.getValue()) {
        if (a.zone().equals(selectedZone) && a.target().territory().equals(t)) {
          enemy.add(
              (AirCombat.atWar(player, e.getKey()) ? "enemy " : "own side ")
                  + a.count()
                  + " "
                  + ResearchView.displayName(a.unitType().getName())
                  + " ("
                  + e.getKey().getName()
                  + ", "
                  + kindName(a.target().kind()).toLowerCase(java.util.Locale.ROOT)
                  + ")");
        }
      }
    }
    return t.getName()
        + ": "
        + (enemy.isEmpty() ? "no bombers" : String.join(", ", enemy))
        + " · "
        + badge(player, selectedZone, t)
        + (t.isWater() ? "" : " · " + aaText);
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
      if (coverMode) {
        if (!mayGo(player, selectedZone, type, target)) {
          return AirZoneAttachment.get(selectedZone).orElseThrow().getZoneType() == ZoneType.BOMB
              ? name + ": in a bombing zone escorts go only over a factory their side bombs."
              : "Out of range: "
                  + AirRange.outOfRangeText(player, selectedZone, type, territory)
                  + " (half its range of "
                  + rangeInForce(player, type)
                  + ", rounded down).";
        }
        continue;
      }
      final String narrow = selected.size() > 1 ? " Pick only the other types to attack it." : "";
      if (!AirCombat.mayAttack(type, target.kind())) {
        return name + " cannot attack " + kindName(target.kind()).toLowerCase() + "." + narrow;
      }
      // request 26: a long-range raid bombs only the listed territories
      if (LongRangeRaid.isRaidZone(player, type, selectedZone)
          && !LongRangeRaid.isTarget(getData(), territory)) {
        return LongRangeRaid.refusal(getData(), territory) + narrow;
      }
      if (!AirCombat.mayAttack(player, selectedZone, type, target)) {
        // a target must be within half the bomber's range (rounded down) of a place it can land
        return "Out of range: "
            + AirRange.outOfRangeText(player, selectedZone, type, territory)
            + " (half its range of "
            + rangeInForce(player, type)
            + ", rounded down)."
            + narrow;
      }
    }
    return "";
  }

  /** The range a bomber of this type flies at: its own (with the owner's techs). */
  private int rangeInForce(final GamePlayer player, final UnitType type) {
    return AirRange.range(type, player);
  }

  private static String kindName(final TargetKind kind) {
    return switch (kind) {
      case LAND -> "Land units";
      case SURFACE -> "Surface ships";
      case SUBS -> "Submarines";
      case STRATEGIC -> "Strategic bombing";
      case COVER -> "Fighter cover";
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
    if (finished) {
      return;
    }
    showAssigned();
    updateBanner(getCurrentPlayer(), waitingGroups(getCurrentPlayer()).size());
    final List<TargetAssignment> snapshot = List.copyOf(staged);
    storeExecutor.execute(() -> store.accept(snapshot));
    rebuild(getCurrentPlayer());
    getMap().repaint();
  }

  // ---- the side panel

  private void rebuild(final GamePlayer player) {
    // request 24: heading and buttons pinned at the top, the rest scrolls
    layout.install(this);
    actionLabel.setText(player.getName() + (coverMode ? " fighter cover" : " bomber targets"));
    // the help is a tooltip on the heading; the panel itself lists the strikes
    actionLabel.setToolTipText(
        AirSelection.wrap(
            "Click a group's icon in its box (or its Choose targets button here) to pick the whole"
                + " group; then Ctrl, Alt and Shift clicks and right-clicks adjust it as usual."
                + " Or use the counters here. The targets"
                + " all picked bombers can reach light up on the map and are listed here;"
                + " click one to attack it."));
    layout.top().add(leftAligned(actionLabel));
    if (finished) {
      // read only: the strikes are chosen and resolve once every nation has chosen
      actionLabel.setText(
          player.getName() + (coverMode ? " fighter cover: done" : " bomber targets: done"));
      layout.add(
          leftAligned(
              new JLabel(
                  staged.isEmpty()
                      ? (coverMode ? "No fighters placed." : "No strikes.")
                      : (coverMode ? "Fighters placed:" : "Strikes:"))));
      for (final TargetAssignment a : List.copyOf(staged)) {
        layout.add(leftAligned(new JLabel(describe(a), icon(player, a.unitType()), JLabel.LEFT)));
      }
      layout.add(Box.createVerticalGlue());
      refresh.run();
      return;
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
    final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
    buttons.add(undoAll);
    // request 30: the same choices as last time, where they are still possible
    final List<TargetAssignment> last = lastRound.getOrDefault(lastKey(player), List.of());
    if (!last.isEmpty()) {
      final JButton repeat = new JButton("Repeat last round");
      repeat.setToolTipText(
          "Makes last time's choices again where the aircraft and places are still there.");
      repeat.addActionListener(e -> repeatLastRound(player, last));
      buttons.add(repeat);
    }
    buttons.add(createDoneButton());
    layout.top().add(leftAligned(buttons));
    final int waiting = waitingGroups(player).size();
    final JLabel progress =
        new JLabel(
            waiting == 0
                ? (coverMode
                    ? "Every fighter group is placed or has nowhere in reach: press Done."
                    : "Every group has a target or nothing in reach: press Done.")
                : waiting
                    + (waiting == 1 ? " group still waiting" : " groups still waiting")
                    + "; "
                    + staged.size()
                    + (coverMode
                        ? (staged.size() == 1 ? " placement" : " placements")
                        : (staged.size() == 1 ? " strike" : " strikes"))
                    + " made.");
    progress.setBorder(BorderFactory.createEmptyBorder(2, 5, 0, 0));
    layout.top().add(leftAligned(progress));

    addOverview(player);

    layout.add(Box.createVerticalStrut(6));
    layout.add(
        leftAligned(
            new JLabel(
                staged.isEmpty()
                    ? (coverMode ? "No fighters placed yet." : "No strikes yet.")
                    : (coverMode ? "Fighters placed:" : "Strikes:"))));
    for (final TargetAssignment a : List.copyOf(staged)) {
      final JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
      row.add(new JLabel(icon(player, a.unitType())));
      row.add(new JLabel(describe(a)));
      // request 28: one bomber more or fewer against this target
      row.add(counter(player, a.zone(), a.unitType(), a.target(), false));
      final JButton undo = smallButton("Undo");
      undo.addActionListener(
          e -> {
            staged.remove(a);
            clearSelection();
            selectionChanged();
            strikesChanged();
          });
      row.add(undo);
      layout.add(leftAligned(row));
    }
    layout.add(Box.createVerticalGlue());
    // a newly chosen group is first in the list: show it
    final String shown = selectedZone == null ? null : selectedZone.getName() + selected.keySet();
    if (shown != null && !shown.equals(lastShownGroup)) {
      SwingUtilities.invokeLater(
          () -> layout.scrollPane().getViewport().setViewPosition(new Point(0, 0)));
    }
    lastShownGroup = shown;
    refresh.run();
  }

  /**
   * Request 20: the starting point of the step. Every front holding the nation's aircraft, with one
   * line per type: how many targets are in reach and a button that picks the group, scrolls the map
   * to its box and lights up its targets; or why it has nothing to do. Request 24: the front being
   * chosen comes first, with its bombers and targets right under it; then fronts with groups
   * waiting; fronts with nothing to do are one line each, opened by a click.
   */
  private void addOverview(final GamePlayer player) {
    final List<Territory> chosen = new ArrayList<>();
    final List<Territory> waiting = new ArrayList<>();
    final List<Territory> idle = new ArrayList<>();
    final Map<Territory, IntegerMap<UnitType>> allByZone = new HashMap<>();
    try (GameData.Unlocker ignored = getData().acquireReadLock()) {
      for (final Territory zone : AirZoneAttachment.getAirZones(getData().getMap())) {
        if (AirZoneAttachment.get(zone).orElseThrow().getZoneType() == ZoneType.RESERVE) {
          continue;
        }
        final IntegerMap<UnitType> all = new IntegerMap<>();
        zone.getUnitCollection()
            .getMatches(u -> u.isOwnedBy(player))
            .forEach(u -> all.add(u.getType(), 1));
        if (all.isEmpty()) {
          continue;
        }
        allByZone.put(zone, all);
        final IntegerMap<UnitType> left = available(player, zone);
        final boolean hasWork =
            all.keySet().stream()
                .anyMatch(
                    t ->
                        left.getInt(t) > 0
                            && isMineType(zone, t)
                            && !targetsFor(player, zone, t).isEmpty());
        if (zone.equals(selectedZone)) {
          chosen.add(zone);
        } else if (hasWork) {
          waiting.add(zone);
        } else {
          idle.add(zone);
        }
      }
      if (allByZone.isEmpty()) {
        layout.add(leftAligned(new JLabel("You have no aircraft in any front.")));
      }
      for (final Territory zone : chosen) {
        addZoneSection(player, zone, allByZone.get(zone));
        addSelection(player);
      }
      for (final Territory zone : waiting) {
        addZoneSection(player, zone, allByZone.get(zone));
      }
      for (final Territory zone : idle) {
        if (expanded.contains(zone)) {
          addZoneSection(player, zone, allByZone.get(zone));
        } else {
          addCollapsed(player, zone, allByZone.get(zone));
        }
      }
    }
    layout.add(Box.createVerticalStrut(6));
  }

  private static boolean isBomberType(final Territory zone, final UnitType type) {
    return zone.getUnitCollection()
        .anyMatch(u -> u.getType().equals(type) && AirCombat.isBomber(u));
  }

  /** Whether the type is one this step places (bombers, or fighter-types for cover). */
  private boolean isMineType(final Territory zone, final UnitType type) {
    return coverMode ? !isBomberType(zone, type) : isBomberType(zone, type);
  }

  /** The picked bombers' counters and their targets, under the front being chosen. */
  private void addSelection(final GamePlayer player) {
    if (selectedZone == null) {
      return;
    }
    final JPanel picked = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
    picked.add(Box.createHorizontalStrut(10));
    picked.add(
        new JLabel(
            (coverMode ? "Fighters picked in " : "Bombers picked in ")
                + AirAllocation.zoneName(selectedZone)
                + ":"));
    layout.add(leftAligned(picked));
    layout.add(
        leftAligned(
            AirSelection.chooser(
                frame.getUiContext(),
                player,
                available(player, selectedZone),
                selected,
                () -> selectionChanged(false))));
    fillTargets(player);
    layout.add(targetsPanel);
  }

  /**
   * A front with nothing to do, on one line: "▸ Philippine Sea: 4 fighters (dogfight only), 8
   * torpedo bombers (nothing in reach)". A click opens it.
   */
  private void addCollapsed(
      final GamePlayer player, final Territory zone, final IntegerMap<UnitType> all) {
    final IntegerMap<UnitType> left = available(player, zone);
    final List<String> parts = new ArrayList<>();
    for (final UnitType type : all.keySet()) {
      final int count = all.getInt(type);
      final String why;
      if (!isMineType(zone, type)) {
        why = coverMode ? "bombers: targets chosen" : "placed in the next step";
      } else if (targetsFor(player, zone, type).isEmpty()) {
        why = "nothing in reach";
      } else {
        why = left.getInt(type) <= 0 ? (coverMode ? "placed" : "target chosen") : "waiting";
      }
      parts.add(count + " " + typeName(type, count) + " (" + why + ")");
    }
    final JLabel line =
        new JLabel(
            "<html><div style='width:320px'>▸ <b>"
                + AirAllocation.zoneName(zone)
                + ":</b> "
                + String.join(", ", parts)
                + "</div></html>");
    line.setToolTipText("Click to show the details");
    line.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    line.setBorder(BorderFactory.createEmptyBorder(1, 4, 1, 0));
    line.addMouseListener(
        new MouseAdapter() {
          @Override
          public void mouseClicked(final MouseEvent e) {
            expanded.add(zone);
            rebuild(player);
          }
        });
    layout.add(leftAligned(line));
  }

  /** A front's heading and one line per aircraft type (the request 20 overview). */
  private void addZoneSection(
      final GamePlayer player, final Territory zone, final IntegerMap<UnitType> all) {
    final IntegerMap<UnitType> left = available(player, zone);
    final List<String> parts = new ArrayList<>();
    all.keySet().forEach(t -> parts.add(all.getInt(t) + " " + typeName(t, all.getInt(t))));
    final JPanel header = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
    final boolean opened = expanded.contains(zone);
    final JLabel title =
        new JLabel(
            "<html>"
                + (opened ? "▾ " : "")
                + "<b>"
                + AirAllocation.zoneName(zone)
                + ":</b> "
                + String.join(", ", parts)
                + "</html>");
    if (opened) {
      title.setToolTipText("Click to fold it back to one line");
      title.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
      title.addMouseListener(
          new MouseAdapter() {
            @Override
            public void mouseClicked(final MouseEvent e) {
              expanded.remove(zone);
              rebuild(player);
            }
          });
    }
    header.add(title);
    layout.add(leftAligned(header));
    for (final UnitType type : all.keySet()) {
      final int count = all.getInt(type);
      final String name =
          AirAllocation.typeName(type) + (count == 1 ? "" : "s") + " (" + count + "): ";
      final JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
      row.add(Box.createHorizontalStrut(10));
      if (!isMineType(zone, type)) {
        row.add(
            new JLabel(
                name
                    + (coverMode
                        ? "bombers: their targets are chosen."
                        : "fighters are placed in the next step, to escort or defend.")));
      } else {
        final int targets = targetsFor(player, zone, type).size();
        final int free = left.getInt(type);
        if (targets == 0) {
          row.add(
              new JLabel(
                  name
                      + "nothing in reach ("
                      + AirRange.reachText(player, zone, type).toLowerCase(java.util.Locale.ROOT)
                      + ")."));
        } else if (free <= 0) {
          row.add(new JLabel(name + "all assigned."));
        } else {
          row.add(
              new JLabel(
                  name
                      + targets
                      + (coverMode
                          ? (targets == 1 ? " place" : " places")
                          : (targets == 1 ? " target" : " targets"))
                      + " in reach"
                      + (free < count ? "; " + free + " without a target" : "")
                      + "."));
          final JButton choose = smallButton(coverMode ? "Place" : "Choose targets");
          choose.setToolTipText(
              "Picks the whole group, shows its box and targets, and flashes the box. Same as"
                  + " clicking the group's icon in its box.");
          choose.addActionListener(e -> chooseGroup(player, zone, type, true));
          // request 22: the group being chosen now has its button lit
          if (zone.equals(selectedZone) && selected.getInt(type) > 0) {
            choose.setText(coverMode ? "Placing: click a territory" : "Choosing: click a target");
            choose.setBackground(new Color(0xFF, 0x8C, 0x00));
            choose.setForeground(Color.BLACK);
            choose.setOpaque(true);
            choose.setFont(choose.getFont().deriveFont(java.awt.Font.BOLD));
          }
          row.add(choose);
        }
      }
      layout.add(leftAligned(row));
    }
  }

  /** "dive bomber", "dive bombers". */
  private static String typeName(final UnitType type, final int count) {
    final String name = AirAllocation.typeName(type).toLowerCase(java.util.Locale.ROOT);
    return count == 1 ? name : name + "s";
  }

  /**
   * Picks every aircraft of the type without a target in the front, scrolls the map to the front's
   * box and lights up the targets in reach.
   */
  private void chooseGroup(
      final GamePlayer player, final Territory zone, final UnitType type, final boolean scroll) {
    final int free = available(player, zone).getInt(type);
    if (free <= 0) {
      return;
    }
    selectedZone = zone;
    selected.clear();
    selected.put(type, free);
    final AirMapMode mode = frame.getAirMapMode();
    if (mode != null) {
      // request 22: bring the box and the targets in reach into view together, and flash the box
      Rectangle view = mode.boxOnMap(zone).orElse(null);
      try (GameData.Unlocker ignored = getData().acquireReadLock()) {
        for (final Target target : targetsFor(player, zone, type)) {
          final Rectangle r = frame.getUiContext().getMapData().getBoundingRect(target.territory());
          if (r != null) {
            view = view == null ? new Rectangle(r) : view.union(r);
          }
        }
      }
      if (!scroll) {
        // clicked on the map: the box is in view already
      } else if (view != null) {
        getMap().centerOnPoint(new Point((int) view.getCenterX(), (int) view.getCenterY()));
      } else {
        mode.scrollTarget(zone).ifPresent(p -> getMap().centerOnPoint(p));
      }
      mode.flash(zone);
    }
    selectionChanged();
  }

  /** "1 Torpedo Bomber from Mediterranean → Tobruk". */
  private static String describe(final TargetAssignment a) {
    return a.count()
        + " "
        + ResearchView.displayName(a.unitType().getName())
        + " from "
        + AirAllocation.zoneName(a.zone())
        + " → "
        + a.target().describe();
  }

  /**
   * Request 28: "[−] 2 [+]", how many bombers of the type in the zone go against the target. + takes
   * one that has no target yet (never more than there are), − gives one back.
   *
   * @param keepGroup whether the group stays picked after a change (the targets list), or the
   *     panel goes on as after an Undo (the strikes list)
   */
  private JPanel counter(
      final GamePlayer player,
      final Territory zone,
      final UnitType type,
      final Target target,
      final boolean keepGroup) {
    final JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
    final int now = TargetSplit.count(staged, zone, type, target);
    final int free = available(player, zone).getInt(type);
    final JButton minus = smallButton("−");
    minus.setEnabled(now > 0);
    minus.setToolTipText("One " + typeName(type, 1) + " fewer against " + target.describe());
    minus.addActionListener(e -> adjust(player, zone, type, target, -1, keepGroup));
    final JLabel count = new JLabel(String.valueOf(now));
    count.setToolTipText(now + " " + typeName(type, now) + " sent; " + free + " without a target");
    final JButton plus = smallButton("+");
    final boolean roomLeft = room(player, zone, target.territory()) > 0;
    plus.setEnabled(free > 0 && roomLeft);
    plus.setToolTipText(
        !roomLeft
            ? "Your side's cap is reached here: " + badge(player, zone, target.territory())
            : free > 0
                ? "One more " + typeName(type, 1) + " against " + target.describe()
                : "Every "
                    + typeName(type, 1)
                    + " in "
                    + AirAllocation.zoneName(zone)
                    + (coverMode ? " is placed" : " has a target"));
    plus.addActionListener(e -> adjust(player, zone, type, target, 1, keepGroup));
    panel.add(minus);
    panel.add(count);
    panel.add(plus);
    return panel;
  }

  private void adjust(
      final GamePlayer player,
      final Territory zone,
      final UnitType type,
      final Target target,
      final int delta,
      final boolean keepGroup) {
    if (delta > 0 && room(player, zone, target.territory()) <= 0) {
      frame.setStatusErrorMessage(
          target.territory().getName()
              + ": your side's cap is reached ("
              + badge(player, zone, target.territory())
              + ").");
      return;
    }
    final List<TargetAssignment> changed;
    try (GameData.Unlocker ignored = getData().acquireReadLock()) {
      final int there =
          (int)
              myAircraft(player, zone).stream().filter(u -> u.getType().equals(type)).count();
      changed = TargetSplit.adjust(staged, zone, type, target, delta, there);
    }
    staged.clear();
    staged.addAll(changed);
    if (!keepGroup || !zone.equals(selectedZone)) {
      clearSelection();
    } else {
      // the picked count can't be more than the bombers still without a target
      final IntegerMap<UnitType> free = available(player, zone);
      for (final UnitType picked : List.copyOf(selected.keySet())) {
        if (free.getInt(picked) <= 0) {
          selected.removeKey(picked);
        } else if (selected.getInt(picked) > free.getInt(picked)) {
          selected.put(picked, free.getInt(picked));
        }
      }
      if (selected.isEmpty()) {
        // the group is done; keep the zone so the counters stay in view
        selected.put(type, 0);
      }
    }
    selectionChanged();
    strikesChanged();
  }

  /** The reach of each picked type, "only" buttons for mixed picks, and the targets in reach. */
  private void fillTargets(final GamePlayer player) {
    targetsPanel.removeAll();
    if (selectedZone == null || selected.isEmpty()) {
      targetsPanel.revalidate();
      return;
    }
    // request 17: how far each picked type strikes, and from where
    for (final UnitType type : selected.keySet()) {
      targetsPanel.add(
          leftAligned(
              new JLabel(
                  LongRangeRaid.isRaidZone(player, type, selectedZone)
                      // request 26: no range limit, only the listed factories
                      ? AirSelection.wrap(
                          LongRangeRaid.LABEL
                              + ": "
                              + ResearchView.displayName(type.getName())
                              + "s may bomb factories in "
                              + String.join(", ", LongRangeRaid.targets(getData()))
                              + ".")
                      : AirRange.reachText(player, selectedZone, type))));
    }
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
    final List<Target> allLegal =
        targets.entrySet().stream().filter(Map.Entry::getValue).map(Map.Entry::getKey).toList();
    // request 30: in the cover step the list holds only places where something happens; any
    // other green territory can still be clicked on the map
    final List<Target> legal =
        coverMode
            ? allLegal.stream().filter(t -> isBusy(player, selectedZone, t.territory())).toList()
            : allLegal;
    // request 28: how many of each type still have no target
    final List<String> unassigned = new ArrayList<>();
    for (final UnitType type : selected.keySet()) {
      final int free = available(player, selectedZone).getInt(type);
      unassigned.add(free + " " + typeName(type, free));
    }
    targetsPanel.add(leftAligned(new JLabel("Unassigned: " + String.join(", ", unassigned))));
    targetsPanel.add(
        leftAligned(
            new JLabel(
                coverMode
                    ? AirSelection.wrap(
                        allLegal.isEmpty()
                            ? "Nowhere in reach of all of them."
                            : legal.size()
                                + " of the "
                                + allLegal.size()
                                + " places in reach have bombers over them (listed here); any green"
                                + " territory on the map can be clicked. Place sends all picked;"
                                + " − and + one at a time. Each side may have "
                                + getData().getProperties().get(AirCover.CAP_FIGHTERS, 4)
                                + " fighters over a land territory, more over a bombed factory or"
                                + " a sea zone.")
                    : legal.isEmpty()
                        ? "No target in reach of all of them."
                        : "Targets in reach ("
                            + legal.size()
                            + "). Attack sends all picked; − and + send one at a time."
                            + " Targets are final for the round.")));
    try (GameData.Unlocker ignored = getData().acquireReadLock()) {
      for (final Target target : legal) {
        final JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        final JButton attack = smallButton(coverMode ? "Place" : "Attack");
        attack.addActionListener(e -> assign(target));
        row.add(attack);
        final JButton show = smallButton("Show");
        show.addActionListener(e -> getMap().centerOn(target.territory()));
        row.add(show);
        final JLabel text = new JLabel(targetLabel(player, target));
        text.setToolTipText(describe(player, target));
        row.add(text);
        targetsPanel.add(leftAligned(row));
        // request 28: a count per picked type against this target
        for (final UnitType type : selected.keySet()) {
          final JPanel counterRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
          counterRow.add(Box.createHorizontalStrut(24));
          counterRow.add(new JLabel(ResearchView.displayName(type.getName()) + ":"));
          counterRow.add(counter(player, selectedZone, type, target, true));
          targetsPanel.add(leftAligned(counterRow));
        }
      }
    }
    targetsPanel.revalidate();
    targetsPanel.repaint();
  }

  /**
   * Request 12 section 4: a sea target says how many ships of that kind there are and the value the
   * picked aircraft fire at against them, e.g. "Sea Zone 97: Surface ships (3): fires at 6".
   */
  private String targetLabel(final GamePlayer player, final Target target) {
    if (target.kind() == TargetKind.COVER) {
      return target.territory().getName() + " · " + badge(player, selectedZone, target.territory());
    }
    if (target.kind() != TargetKind.SURFACE && target.kind() != TargetKind.SUBS) {
      // request 27: targets are final before the dogfight, so the AA waiting there is shown now
      final List<Unit> aa =
          target
              .territory()
              .getUnitCollection()
              .getMatches(
                  Matches.unitIsAaForAnything().and(u -> AirCombat.atWar(player, u.getOwner())));
      return target.describe()
          + (aa.isEmpty()
              ? " · no AA"
              : " · AA: "
                  + aa.size()
                  + (aa.size() == 1 ? " gun" : " guns")
                  + ", fire at "
                  + aa.stream().mapToInt(AirCombat::aaPower).sum())
          + " · "
          + badge(player, selectedZone, target.territory());
    }
    final boolean subs = target.kind() == TargetKind.SUBS;
    final int count = AirCombat.getShipTargetUnits(player, target.territory(), subs).size();
    final List<String> values = new ArrayList<>();
    for (final UnitType type : selected.keySet()) {
      AirCombat.getBombers(player, selectedZone).stream()
          .filter(u -> u.getType().equals(type))
          .findFirst()
          .ifPresent(
              u -> {
                final int value =
                    Math.min(getData().getDiceSides(), AirCombat.strikePower(u, target.kind()));
                values.add(
                    selected.size() == 1
                        ? String.valueOf(value)
                        : ResearchView.displayName(type.getName()) + " " + value);
              });
    }
    return target.territory().getName()
        + ": "
        + (subs ? "Submarines" : "Surface ships")
        + " ("
        + count
        + ")"
        + (values.isEmpty() ? "" : ": fires at " + String.join(", ", values));
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

  /**
   * Request 30: whether something happens over the territory: bombers of any nation are sent there
   * from the box, or the nation's side has placed fighters there.
   */
  private boolean isBusy(final GamePlayer player, final Territory zone, final Territory t) {
    final List<TargetAssignment> all = new ArrayList<>(staged);
    allied.values().forEach(all::addAll);
    revealed.values().forEach(all::addAll);
    return all.stream().anyMatch(a -> a.zone().equals(zone) && a.target().territory().equals(t));
  }

  /**
   * Request 30: last time's choices, as far as they still fit: the aircraft must still be in the
   * box, the place still in reach, and the caps allow it.
   */
  private void repeatLastRound(final GamePlayer player, final List<TargetAssignment> last) {
    staged.clear();
    int skipped = 0;
    try (GameData.Unlocker ignored = getData().acquireReadLock()) {
      for (final TargetAssignment a : last) {
        final Territory zone = getData().getMap().getTerritoryOrNull(a.zone().getName());
        final Territory place =
            getData().getMap().getTerritoryOrNull(a.target().territory().getName());
        final UnitType type =
            getData().getUnitTypeList().getUnitType(a.unitType().getName()).orElse(null);
        if (zone == null || place == null || type == null) {
          skipped += a.count();
          continue;
        }
        final Target target = new Target(place, a.target().kind());
        if (!targetsFor(player, zone, type).contains(target)) {
          skipped += a.count();
          continue;
        }
        final int count =
            Math.min(
                a.count(),
                Math.min(available(player, zone).getInt(type), room(player, zone, place)));
        if (count > 0) {
          staged.add(new TargetAssignment(zone, type, count, target));
        }
        skipped += a.count() - Math.max(0, count);
      }
    }
    clearSelection();
    selectionChanged();
    strikesChanged();
    if (skipped > 0) {
      frame.setStatusWarningMessage(
          skipped + " of last time's aircraft could not be placed the same way this round.");
    }
  }

  // ---- the map overlay

  private void paintOverlay(final Graphics2D g, final GameData data) {
    if (!listening) {
      return;
    }
    final GamePlayer player = getCurrentPlayer();
    try (GameData.Unlocker ignored = data.acquireReadLock()) {
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
      // request 30: in the cover step every nation's bombers are shown on their targets
      final Map<Territory, Integer> perTarget = new HashMap<>();
      if (coverMode) {
        for (final Map.Entry<GamePlayer, List<TargetAssignment>> e : revealed.entrySet()) {
          for (final TargetAssignment a : e.getValue()) {
            final Point end = draw.middle(a.target().territory());
            final int n = perTarget.merge(a.target().territory(), 1, Integer::sum) - 1;
            final Point icon = new Point(end.x - 16 + n * 34, end.y + 18);
            AirDraw.arrow(
                g,
                draw.center(a.zone()),
                end,
                AirCombat.atWar(player, e.getKey()) ? ENEMY_LINE : STRIKE_LINE,
                2.5f,
                false,
                "",
                6);
            draw.iconAt(g, icon, e.getKey(), a.unitType(), a.count());
          }
        }
      }
      // request 30: each place with aircraft shows its side's counts against the caps
      final java.util.Set<String> badged = new java.util.HashSet<>();
      final List<TargetAssignment> withBadges = new ArrayList<>(staged);
      allied.values().forEach(withBadges::addAll);
      if (coverMode) {
        revealed.values().forEach(withBadges::addAll);
      }
      for (final TargetAssignment a : withBadges) {
        if (badged.add(a.zone().getName() + "|" + a.target().territory().getName())) {
          final Point end = draw.middle(a.target().territory());
          AirDraw.tag(
              g,
              badge(player, a.zone(), a.target().territory()),
              end.x,
              end.y - 24,
              new Color(255, 230, 120));
        }
      }
      // strikes: arrow from the box to the target, with the bombers and count at the target
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
}
