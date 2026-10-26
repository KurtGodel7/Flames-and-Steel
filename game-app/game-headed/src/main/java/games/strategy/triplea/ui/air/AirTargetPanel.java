package games.strategy.triplea.ui.air;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.air.AirBases;
import games.strategy.triplea.delegate.air.AirBases.Flight;
import games.strategy.triplea.delegate.air.AirBases.Reach;
import games.strategy.triplea.delegate.air.AirCombat;
import games.strategy.triplea.delegate.air.AirCombat.Target;
import games.strategy.triplea.delegate.air.AirCombat.TargetAssignment;
import games.strategy.triplea.delegate.air.AirCombat.TargetKind;
import games.strategy.triplea.delegate.air.AirCover;
import games.strategy.triplea.delegate.air.AirPriority;
import games.strategy.triplea.delegate.air.AirRange;
import games.strategy.triplea.delegate.air.AirStage;
import games.strategy.triplea.delegate.air.AirStage.Choice;
import games.strategy.triplea.delegate.air.AirStage.Stage;
import games.strategy.triplea.delegate.air.TargetSplit;
import games.strategy.triplea.formatter.MyFormatter;
import games.strategy.triplea.formatter.PlayerNames;
import games.strategy.triplea.image.UnitImageFactory.ImageKey;
import games.strategy.triplea.ui.ActionPanel;
import games.strategy.triplea.ui.DefaultMapSelectionListener;
import games.strategy.triplea.ui.MouseDetails;
import games.strategy.triplea.ui.PinnedLayout;
import games.strategy.triplea.ui.TripleAFrame;
import games.strategy.triplea.ui.panels.map.MapOverlay;
import games.strategy.triplea.ui.panels.map.MapPanel;
import games.strategy.triplea.ui.panels.map.MapSelectionListener;
import games.strategy.triplea.ui.panels.map.UnitSelectionListener;
import games.strategy.triplea.ui.research.ResearchView;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.FlowLayout;
import java.awt.Dimension;
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
import javax.swing.JScrollPane;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import org.triplea.java.collections.IntegerMap;

/**
 * Flames and Steel (requests 33-35 and 38): one nation's screen in a simultaneous air stage. The
 * player picks aircraft on a base (click their stack on the map, or the base list), sets how many go,
 * and clicks a target (bomber stage) or a territory where bombers are going (fighter stage, to
 * escort or defend). Everything in reach lights up; pointing at it says what is there and where the
 * aircraft would land ("lands at Smolensk, 2 left"), pointing elsewhere says why not. Moves made are
 * listed with Undo; Pick from list opens the same choice in a window. The allies' choices so far
 * are drawn too; a territory a nation with priority has chosen is refused at the mouse.
 */
public final class AirTargetPanel extends ActionPanel {
  private static final long serialVersionUID = 1L;
  private static final Color TARGET_FILL = new Color(80, 220, 110, 90);
  private static final Color DIM_FILL = new Color(0, 0, 0, 110);
  private static final Color STRIKE_LINE = new Color(255, 80, 60);
  private static final Color PREVIEW_BLOCKED = new Color(230, 60, 50);
  private static final Color ENEMY_LINE = new Color(170, 90, 255);
  private static final Color ALLY_LINE = new Color(90, 170, 255);

  private final Host frame;
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
  // request 33: the target shown in the panel (chosen on the map, in a moves line or in the list)
  private @Nullable Target selectedTarget;
  private @Nullable PickTargetDialog pickDialog;
  // request 30: the fighter cover step uses this panel too
  private boolean coverMode;
  // request 35: the simultaneous air stage this screen belongs to (null: the per-nation steps).
  // Choices are kept by the box the aircraft start in; a target in an adjacent box moves them.
  private Stage stage = Stage.BOMBERS;
  // request 38: what each type on each base could fly to, worked out once per screen
  private final Map<String, List<Target>> reachable = new HashMap<>();
  private @Nullable Reach reach;
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

  /**
   * Request 33: what the panel needs from the game window, so a test can show the panel without
   * one.
   */
  interface Host {
    MapPanel getMapPanel();

    games.strategy.triplea.ui.UiContext getUiContext();

    @Nullable
    AirMapMode getAirMapMode();

    void setStatusMessage(String message);

    void setStatusErrorMessage(String message);

    void setStatusWarningMessage(String message);

    void clearStatusMessage();
  }

  public AirTargetPanel(final TripleAFrame frame) {
    this(
        new Host() {
          @Override
          public MapPanel getMapPanel() {
            return frame.getMapPanel();
          }

          @Override
          public games.strategy.triplea.ui.UiContext getUiContext() {
            return frame.getUiContext();
          }

          @Override
          public @Nullable AirMapMode getAirMapMode() {
            return frame.getAirMapMode();
          }

          @Override
          public void setStatusMessage(final String message) {
            frame.setStatusMessage(message);
          }

          @Override
          public void setStatusErrorMessage(final String message) {
            frame.setStatusErrorMessage(message);
          }

          @Override
          public void setStatusWarningMessage(final String message) {
            frame.setStatusWarningMessage(message);
          }

          @Override
          public void clearStatusMessage() {
            frame.clearStatusMessage();
          }
        });
  }

  AirTargetPanel(final Host frame) {
    super(frame.getMapPanel().getData(), frame.getMapPanel());
    this.frame = frame;
    this.draw = new AirDraw(frame.getUiContext());
    targetsPanel.setLayout(new BoxLayout(targetsPanel, BoxLayout.Y_AXIS));
    targetsPanel.setAlignmentX(LEFT_ALIGNMENT);
    // request 33: Ctrl+Z takes the newest move back
    getInputMap(WHEN_IN_FOCUSED_WINDOW)
        .put(
            javax.swing.KeyStroke.getKeyStroke(
                java.awt.event.KeyEvent.VK_Z, java.awt.event.InputEvent.CTRL_DOWN_MASK),
            "fsUndoLastAirMove");
    // request 40: Escape clears the selection; N picks the next base still waiting (only when pressed)
    getInputMap(WHEN_IN_FOCUSED_WINDOW)
        .put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0), "fsAirClear");
    getActionMap()
        .put(
            "fsAirClear",
            new javax.swing.AbstractAction() {
              private static final long serialVersionUID = 1L;

              @Override
              public void actionPerformed(final java.awt.event.ActionEvent e) {
                if (listening && isActive()) {
                  clearSelection();
                  selectionChanged();
                }
              }
            });
    getInputMap(WHEN_IN_FOCUSED_WINDOW)
        .put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_N, 0), "fsAirNextBase");
    getActionMap()
        .put(
            "fsAirNextBase",
            new javax.swing.AbstractAction() {
              private static final long serialVersionUID = 1L;

              @Override
              public void actionPerformed(final java.awt.event.ActionEvent e) {
                if (listening && isActive()) {
                  nextBase(getCurrentPlayer());
                }
              }
            });
    getActionMap()
        .put(
            "fsUndoLastAirMove",
            new javax.swing.AbstractAction() {
              private static final long serialVersionUID = 1L;

              @Override
              public void actionPerformed(final java.awt.event.ActionEvent e) {
                if (listening && isActive()) {
                  undoLast();
                }
              }
            });
  }

  @Override
  public String toString() {
    return "AirTargetPanel";
  }

  /**
   * The nation's screen in a simultaneous air stage: bombers (targets) or fighters (escort or
   * defend).
   *
   * @param start the choices to begin with (none, or the ones refused or handed back)
   * @param bombers every nation's bomber targets (fighter stage)
   * @param store gets the choices after every change (sent to the host for the allies)
   */
  public void startStage(
      final GamePlayer player,
      final Stage stage,
      final List<TargetAssignment> start,
      final Map<GamePlayer, List<TargetAssignment>> bombers,
      final Consumer<List<TargetAssignment>> store) {
    SwingUtilities.invokeLater(
        () -> {
          this.stage = stage;
          coverMode = stage == Stage.FIGHTERS;
          this.revealed = new HashMap<>(bombers);
          finished = false;
          staged.clear();
          staged.addAll(start);
          this.store = store;
          reachable.clear();
          try (GameData.Unlocker ignored = getData().acquireReadLock()) {
            reach = new Reach(player);
          }
          clearSelection();
          showAssigned();
          if (!listening) {
            listening = true;
            getMap().addUnitSelectionListener(unitListener);
            getMap().addMapSelectionListener(hoverListener);
            getMap().addOverlay(overlay);
          }
          rebuild(player);
          strikesChanged();
          // request 40: nothing is selected for the player; the banner says what is waiting
          updateBanner(player, waitingGroups(player).size());
        });
  }

  /**
   * Request 38 section 12: the allies' choices so far. Own choices over a territory a nation with
   * priority has now chosen go back to their base, with the reason.
   */
  public void setAllied(final GamePlayer player, final Map<GamePlayer, List<TargetAssignment>> choices) {
    SwingUtilities.invokeLater(
        () -> {
          allied = new HashMap<>(choices);
          if (finished || !listening || !player.equals(getCurrentPlayer())) {
            getMap().repaint();
            return;
          }
          final List<String> why = new ArrayList<>();
          try (GameData.Unlocker ignored = getData().acquireReadLock()) {
            for (final TargetAssignment a : List.copyOf(staged)) {
              final Optional<GamePlayer> locker =
                  AirPriority.lockedBy(player, a.target().territory(), allied);
              if (locker.isPresent()) {
                staged.remove(a);
                final String text = AirPriority.lockedText(a.target().territory(), locker.get());
                if (!why.contains(text)) {
                  why.add(text);
                }
              }
            }
          }
          if (!why.isEmpty()) {
            frame.setStatusErrorMessage(
                String.join(" ", why) + " Your aircraft for it are back on their base.");
            selectionChanged();
            strikesChanged();
          } else {
            rebuild(player);
            getMap().repaint();
          }
        });
  }

  /** The aircraft this stage is about: bombers (targets) or fighter-types (escort or defend). */
  private boolean isMine(final Unit unit) {
    return AirStage.isOfStage(unit.getType(), stage);
  }

  /** The player's aircraft of this stage on the base. */
  private List<Unit> myAircraft(final GamePlayer player, final Territory zone) {
    return AirStage.aircraftAt(player, zone, stage);
  }

  /** What this nation's aircraft of the type on the base could fly to (worked out once). */
  private List<Target> targetsFor(final GamePlayer player, final Territory zone, final UnitType type) {
    return reachable.computeIfAbsent(
        zone.getName() + "|" + type.getName(),
        k -> {
          try (GameData.Unlocker ignored = getData().acquireReadLock()) {
            return AirStage.candidates(
                player, zone, type, stage, revealed, reach == null ? new Reach(player) : reach);
          }
        });
  }

  /** Every target in reach for the stage's aircraft on the base. */
  private List<Target> allTargets(final GamePlayer player, final Territory zone) {
    final java.util.Set<Target> result = new java.util.LinkedHashSet<>();
    myAircraft(player, zone).stream()
        .map(Unit::getType)
        .distinct()
        .forEach(type -> result.addAll(targetsFor(player, zone, type)));
    return new ArrayList<>(result);
  }

  private boolean mayGo(
      final GamePlayer player, final Territory zone, final UnitType type, final Target target) {
    return targetsFor(player, zone, type).contains(target)
        && AirPriority.lockedBy(player, target.territory(), allied).isEmpty();
  }

  /** The nation's own choices and its allies', for the caps. */
  private Map<GamePlayer, List<TargetAssignment>> sideChoices(final GamePlayer player) {
    final Map<GamePlayer, List<TargetAssignment>> all = new HashMap<>(allied);
    all.put(player, List.copyOf(staged));
    return all;
  }

  /** How many more of this stage's aircraft the nation's side may send over the territory. */
  private int room(final GamePlayer player, final Territory origin, final Territory t) {
    final int cap = AirCover.cap(player, t, coverMode, revealed);
    if (cap == AirCover.NO_CAP) {
      return Integer.MAX_VALUE;
    }
    return Math.max(0, cap - AirCover.sideCount(player, t, coverMode, sideChoices(player)));
  }

  /**
   * Request 30: a place's counts against the caps for the nation's side, e.g. "B 3/4 F 2/4"; in the
   * fighter stage bombers are the side's revealed bombers, and the enemy's follow ("enemy B 2").
   */
  private String badge(final GamePlayer player, final Territory origin, final Territory t) {
    final Map<GamePlayer, List<TargetAssignment>> bombers =
        coverMode ? revealed : sideChoices(player);
    final int b = AirCover.sideCount(player, t, false, bombers);
    final int bombCap = AirCover.bomberCap(getData(), t);
    final StringBuilder sb = new StringBuilder("B " + b + "/" + capText(bombCap));
    if (coverMode) {
      final int f = AirCover.sideCount(player, t, true, sideChoices(player));
      sb.append("  F ")
          .append(f)
          .append("/")
          .append(capText(AirCover.fighterCap(player, t, revealed)));
      final int enemy = enemyBombers(player, t);
      if (enemy > 0) {
        sb.append("  enemy B ").append(enemy);
      }
    }
    return sb.toString();
  }

  private static String capText(final int cap) {
    return cap == AirCover.NO_CAP ? "∞" : String.valueOf(cap);
  }

  /** How many enemy bombers are going to the territory (fighter stage). */
  private int enemyBombers(final GamePlayer player, final Territory t) {
    int n = 0;
    for (final Map.Entry<GamePlayer, List<TargetAssignment>> e : revealed.entrySet()) {
      if (!AirCombat.atWar(player, e.getKey())) {
        continue;
      }
      for (final TargetAssignment a : e.getValue()) {
        if (a.target().territory().equals(t)) {
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
    if (pickDialog != null) {
      pickDialog.dispose();
      pickDialog = null;
    }
    if (listening) {
      listening = false;
      getMap().removeUnitSelectionListener(unitListener);
      getMap().removeMapSelectionListener(hoverListener);
      getMap().removeOverlay(overlay);
      getMap().setUnitHighlight(List.of());
      if (frame.getAirMapMode() != null) {
        frame.getAirMapMode().setBusy(false);
        frame.getAirMapMode().setBanner(null);
      }
      frame.clearStatusMessage();
    }
  }

  private String lastKey(final GamePlayer player) {
    return player.getName() + (coverMode ? "|cover" : "|targets");
  }

  @Override
  public void performDone() {
    final GamePlayer player = getCurrentPlayer();
    final Optional<String> problem;
    try (GameData.Unlocker ignored = getData().acquireReadLock()) {
      problem = AirStage.problem(player, stage, Choice.of(staged), allied, revealed);
    }
    if (problem.isPresent()) {
      frame.setStatusErrorMessage(problem.get());
      JOptionPane.showMessageDialog(
          JOptionPane.getFrameForComponent(this),
          problem.get(),
          stepTitle(),
          JOptionPane.WARNING_MESSAGE);
      return;
    }
    clearSelection();
    release();
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
    final List<Unit> units = clickedUnits;
    final Territory territory = clicked;
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
    // request 38: a click on the nation's aircraft of this stage picks them (as in stock moves);
    // a click on a territory without them is a target
    if (units.stream().anyMatch(u -> u.isOwnedBy(player) && isMine(u))) {
      selectBombers(player, territory, units, md);
      return;
    }
    if (selectedZone == null) {
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
              "Bomber mission",
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
    // request 33: with nothing set to send the click only selects the target
    if (sendable(player, selectedZone, target) == 0) {
      selectTarget(target);
      return;
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
                  : "All of those already fly somewhere; undo one to change it.");
    }
    if (available.isEmpty()) {
      if (units.isEmpty()) {
        frame.setStatusErrorMessage(
            (coverMode ? "No fighters left to send from " : "No bombers left to send from ")
                + zone.getName()
                + ".");
      }
      clearSelection();
    }
    selectionChanged();
  }

  private void assign(final Target target) {
    assign(target, selected);
  }

  /**
   * Sends these counts (the ones under Unassigned, or the list window's) against the target, as far
   * as the aircraft are unassigned, can reach it and the side's cap allows; the target stays
   * selected and the counts go back to "all".
   */
  void assign(final Target target, final IntegerMap<UnitType> counts) {
    final GamePlayer player = getCurrentPlayer();
    final Territory zone = selectedZone;
    if (zone == null) {
      return;
    }
    final List<TargetAssignment> before = List.copyOf(staged);
    // request 30: never more than the side's cap over one place
    int room = room(player, zone, target.territory());
    boolean trimmed = false;
    final IntegerMap<UnitType> free = available(player, zone);
    for (final UnitType type : List.copyOf(counts.keySet())) {
      final int wanted = Math.min(counts.getInt(type), free.getInt(type));
      if (wanted <= 0 || !mayGo(player, zone, type, target)) {
        continue;
      }
      final int count = Math.min(wanted, room);
      trimmed |= count < wanted;
      if (count <= 0) {
        continue;
      }
      room -= count;
      boolean merged = false;
      for (int i = 0; i < staged.size(); i++) {
        final TargetAssignment a = staged.get(i);
        if (a.zone().equals(zone) && a.unitType().equals(type) && a.target().equals(target)) {
          staged.set(i, new TargetAssignment(a.zone(), type, a.count() + count, target));
          merged = true;
        }
      }
      if (!merged) {
        staged.add(new TargetAssignment(zone, type, count, target));
      }
    }
    // request 38: every flying aircraft must be sure of a landing spot; if not, the move is undone
    final Optional<String> landing;
    try (GameData.Unlocker ignored = getData().acquireReadLock()) {
      final List<Flight> flights = new ArrayList<>(AirStage.airborneFlights(player));
      flights.addAll(AirStage.flights(player, stage, staged));
      landing = AirBases.landingProblem(player, flights);
    }
    if (landing.isPresent()) {
      staged.clear();
      staged.addAll(before);
      frame.setStatusErrorMessage(landing.get());
      selectionChanged();
      return;
    }
    // request 40: after a move nothing is selected and the map stays where it is
    clearSelection();
    selectionChanged();
    strikesChanged();
    if (pickDialog != null && pickDialog.isDisplayable()) {
      pickDialog.refreshList();
    }
    if (trimmed) {
      frame.setStatusErrorMessage(
          target.territory().getName()
              + ": your side's cap is reached ("
              + badge(player, zone, target.territory())
              + "); the rest stay unassigned.");
    }
  }

  /** A base's aircraft of one type that still have somewhere to go and no choice yet. */
  private record Group(Territory zone, UnitType type) {}

  /** The groups still waiting, base by base. */
  private List<Group> waitingGroups(final GamePlayer player) {
    final List<Group> result = new ArrayList<>();
    try (GameData.Unlocker ignored = getData().acquireReadLock()) {
      for (final Territory zone : AirStage.bases(player, stage)) {
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

  /**
   * Request 40, only when the player asks (Next base, or N): selects the next base after the one
   * shown that still has aircraft without an order, and brings it into view.
   */
  void nextBase(final GamePlayer player) {
    if (finished || player == null) {
      return;
    }
    final List<Territory> bases = new ArrayList<>();
    for (final Group g : waitingGroups(player)) {
      if (!bases.contains(g.zone())) {
        bases.add(g.zone());
      }
    }
    if (bases.isEmpty()) {
      frame.setStatusMessage("Every aircraft that can fly has an order. Press Done when ready.");
      return;
    }
    final int now = selectedZone == null ? -1 : bases.indexOf(selectedZone);
    chooseFront(player, bases.get((now + 1) % bases.size()), true);
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
                    ? ". Bombers are shown on their targets. Click your fighters on the map,"
                        + " choose a base on the right, or press N for the next base."
                    : ". Click your bombers on the map, choose a base on the right, or press N"
                        + " for the next base."));
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
    selectedTarget = null;
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
    if (selectedZone != null) {
      final List<Collection<Unit>> highlight = new ArrayList<>();
      try (GameData.Unlocker ignored = getData().acquireReadLock()) {
        // request 33: a target is green when any type being sent can reach it (with nothing set
        // to send, any of the front's types: the click then only selects it)
        final List<UnitType> sending =
            selected.keySet().stream().filter(t -> selected.getInt(t) > 0).toList();
        final List<UnitType> judge =
            sending.isEmpty()
                ? myAircraft(player, selectedZone).stream().map(Unit::getType).distinct().toList()
                : sending;
        for (final Target target : allTargets(player, selectedZone)) {
          result.put(
              target, judge.stream().anyMatch(type -> mayGo(player, selectedZone, type, target)));
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
                  ? " picked: click a green territory to escort or defend there."
                  : " picked: point at a target to see where they would land, click a green one."));
    } else if (selectedZone != null) {
      frame.setStatusMessage(
          (coverMode ? "Pick fighters at " : "Pick bombers at ")
              + selectedZone.getName()
              + " with the arrows in the panel, or by clicking their stacks.");
    } else {
      frame.clearStatusMessage();
      mousePoint = null;
    }
    showAssigned();
    if (rebuildPanel) {
      rebuild(player);
    } else {
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
    hoverPlace = territory;
    if (!listening || selectedZone == null || selected.isEmpty() || territory == null) {
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
            String.join("  |  ", parts)
                + "  ·  "
                + landingText(player, territory)
                + (coverMode ? "  (click to send them)" : "  (click to attack)"));
      }
    }
  }

  /**
   * Request 38: where the picked aircraft would land from the territory: "lands at Smolensk, 2
   * left", "carrier from Sea Zone 104 can reach Sea Zone 117". Call with the read lock held.
   */
  private String landingText(final GamePlayer player, final Territory t) {
    final List<String> parts = new ArrayList<>();
    for (final UnitType type : selected.keySet()) {
      if (selected.getInt(type) <= 0) {
        continue;
      }
      final Optional<Unit> plane =
          myAircraft(player, selectedZone).stream().filter(u -> u.getType().equals(type)).findFirst();
      if (plane.isEmpty()) {
        continue;
      }
      final int left = AirBases.rangeLeftAt(plane.get(), selectedZone, t).orElse(-1);
      if (left >= 0) {
        parts.add(
            (selected.size() > 1 ? ResearchView.displayName(type.getName()) + ": " : "")
                + AirBases.landingNote(new Flight(plane.get(), t, left)));
      }
    }
    return String.join("; ", parts);
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
        if (a.target().territory().equals(t)) {
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
    final Optional<GamePlayer> locker = AirPriority.lockedBy(player, territory, allied);
    if (locker.isPresent()) {
      return AirPriority.lockedText(territory, locker.get());
    }
    final List<Target> kinds =
        coverMode ? List.of(AirCover.over(territory)) : AirCombat.targetsIn(player, territory);
    if (kinds.isEmpty()) {
      return territory.getName() + ": nothing these aircraft can attack.";
    }
    for (final UnitType type : selected.keySet()) {
      final String narrow = selected.size() > 1 ? " Pick only the other types to send them." : "";
      for (final Target target : kinds) {
        final Optional<String> why =
            AirStage.whyNot(player, selectedZone, type, target, stage, revealed);
        if (why.isPresent()) {
          return why.get() + narrow;
        }
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
      case COVER -> "Escort or defend";
    };
  }

  /** The choices are drawn by this panel's overlay; the bases keep their stacks until take-off. */
  private void showAssigned() {
    // nothing to do: stock stacks stay where they are until the reveal
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

  // ---- the side panel (request 33: compact; the map is the main way to choose)

  /** Moves made shown before the list scrolls inside its own box. */
  static final int MOVE_ROWS = 10;

  private static final int ROW_HEIGHT = 26;

  private String stepTitle() {
    return coverMode ? "Fighters: Escort and Defend" : "Bombers: Choose Targets";
  }

  private void rebuild(final GamePlayer player) {
    layout.install(this);
    // 1. header: "Germans: Assign Bomber Targets", Undo all, Done
    actionLabel.setText(PlayerNames.display(player) + ": " + stepTitle());
    actionLabel.setToolTipText(
        AirSelection.wrap(
            coverMode
                ? "Pick fighters on a base (click their stack, or the base list), set how many to"
                    + " send, then click a green territory: over your bombers' target to escort,"
                    + " over the enemy's to defend. Aircraft left on their base stay. Ctrl+Z undoes"
                    + " the last move."
                : "Pick bombers on a base (click their stack, or the base list), set how many to"
                    + " send, then click a green target. Aircraft left on their base stay. Ctrl+Z"
                    + " undoes the last move."));
    layout.top().add(leftAligned(actionLabel));
    if (finished) {
      layout.top().add(leftAligned(new JLabel(staged.isEmpty() ? "No moves made." : "Moves made:")));
      layout.body().add(movesBox(player, false));
      layout.body().add(Box.createVerticalGlue());
      refresh.run();
      return;
    }
    final JButton undoAll = new JButton("Undo all");
    undoAll.setEnabled(!staged.isEmpty());
    undoAll.addActionListener(
        e -> {
          staged.clear();
          resetSend(selectedZone);
          selectionChanged();
          strikesChanged();
        });
    final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
    buttons.add(undoAll);
    buttons.add(createDoneButton());
    // request 30: the same choices as last time, where they are still possible
    final List<TargetAssignment> last = lastRound.getOrDefault(lastKey(player), List.of());
    if (!last.isEmpty()) {
      final JButton repeat = smallButton("Repeat last round");
      repeat.setToolTipText(
          "Makes last time's choices again where the aircraft and places are still there.");
      repeat.addActionListener(e -> repeatLastRound(player, last));
      buttons.add(repeat);
    }
    layout.top().add(leftAligned(buttons));
    // 2. status line: "2 groups waiting · 1 strike made"
    final int waiting = waitingGroups(player).size();
    final JLabel status =
        new JLabel(
            (waiting == 0
                    ? "Nothing waiting: press Done"
                    : waiting + (waiting == 1 ? " group waiting" : " groups waiting"))
                + " · "
                + staged.size()
                + (staged.size() == 1 ? " choice made" : " choices made"));
    status.setBorder(BorderFactory.createEmptyBorder(2, 5, 2, 0));
    layout.top().add(leftAligned(status));
    // 3. moves made, always in view, newest first
    layout.top().add(movesBox(player, true));
    // 4. the front and its unassigned aircraft
    addUnassigned(player);
    // 5. the selected target
    addSelectedTarget(player);
    // 6. the list, in its own window
    final JButton pick = smallButton("Pick from list…");
    pick.setToolTipText(
        "Opens a separate window listing every target in reach of this base's aircraft. The map"
            + " stays usable.");
    pick.setEnabled(selectedZone != null);
    pick.addActionListener(e -> openPickDialog(player));
    layout.body().add(Box.createVerticalStrut(4));
    layout.body().add(leftAligned(pick));
    layout.body().add(Box.createVerticalGlue());
    refresh.run();
  }

  /**
   * Request 33: the moves made, newest first, one line each with its Undo; clicking a line selects
   * its target. More than {@link #MOVE_ROWS} scroll inside the box.
   */
  private JComponent movesBox(final GamePlayer player, final boolean withUndo) {
    final JPanel rows = AirSelection.compactPanel();
    rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
    final List<TargetAssignment> newestFirst = new ArrayList<>(staged);
    java.util.Collections.reverse(newestFirst);
    if (newestFirst.isEmpty()) {
      rows.add(
          leftAligned(
              new JLabel(
                  coverMode
                      ? "No fighters sent yet: click a green territory on the map."
                      : "No bombers sent yet: click a green target on the map.")));
    }
    for (final TargetAssignment a : newestFirst) {
      final JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
      final JLabel text = new JLabel(moveText(player, a));
      text.setToolTipText("Click to show " + a.target().territory().getName() + " on the map");
      text.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
      text.addMouseListener(
          new MouseAdapter() {
            @Override
            public void mouseClicked(final MouseEvent e) {
              selectMove(player, a);
            }
          });
      row.add(text);
      if (withUndo) {
        final JButton undo = smallButton("Undo");
        undo.setToolTipText("Takes these aircraft back (Ctrl+Z undoes the newest move)");
        undo.addActionListener(e -> undo(a));
        row.add(undo);
      }
      rows.add(leftAligned(row));
    }
    final JScrollPane scroll = new JScrollPane(rows);
    scroll.setBorder(
        BorderFactory.createTitledBorder(
            "Moves made" + (newestFirst.isEmpty() ? "" : " (" + newestFirst.size() + ")")));
    scroll.getVerticalScrollBar().setUnitIncrement(16);
    scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
    // the box is as tall as its first MOVE_ROWS lines; more scroll inside it
    int height = 0;
    final Component[] lines = rows.getComponents();
    for (int i = 0; i < Math.min(lines.length, MOVE_ROWS); i++) {
      height += lines[i].getPreferredSize().height;
    }
    height = Math.max(height, ROW_HEIGHT) + 30;
    scroll.setPreferredSize(new Dimension(300, height));
    scroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
    scroll.setAlignmentX(LEFT_ALIGNMENT);
    return scroll;
  }

  /**
   * "Bialystok ← 4 dive bombers", "Sea Zone 97 (surface ships) ← 2 torpedo bombers", or in the
   * cover step "Bialystok ← 2 fighters (escort)"; the front is named when it is not the one shown.
   */
  String moveText(final GamePlayer player, final TargetAssignment a) {
    final String kind =
        switch (a.target().kind()) {
          case SURFACE -> " (surface ships)";
          case SUBS -> " (submarines)";
          case STRATEGIC -> " (strategic bombing)";
          default -> "";
        };
    final String role;
    if (!coverMode) {
      role = "";
    } else if (AirCover.sideCount(player, a.target().territory(), false, revealed) > 0) {
      role = " (escort)";
    } else if (enemyBombers(player, a.target().territory()) > 0) {
      role = " (defend)";
    } else {
      role = "";
    }
    return a.target().territory().getName()
        + kind
        + " ← "
        + a.count()
        + " "
        + typeName(a.unitType(), a.count())
        + role
        + " from "
        + a.zone().getName();
  }

  /** Request 33: a moves-made line was clicked: its front and target are shown. */
  void selectMove(final GamePlayer player, final TargetAssignment a) {
    if (!a.zone().equals(selectedZone)) {
      chooseFront(player, a.zone(), false);
    }
    selectTarget(a.target());
    getMap().centerOn(a.target().territory());
  }

  /** Request 33: takes one move back; its aircraft are unassigned again. */
  void undo(final TargetAssignment a) {
    if (!staged.remove(a)) {
      return;
    }
    if (a.zone().equals(selectedZone)) {
      resetSend(selectedZone);
    }
    selectionChanged();
    strikesChanged();
  }

  /** Request 33: Ctrl+Z, the newest move back. */
  void undoLast() {
    if (!staged.isEmpty() && !finished) {
      undo(staged.get(staged.size() - 1));
    }
  }

  /** The bases with aircraft of this stage that have somewhere to go. */
  private List<Territory> frontsWithWork(final GamePlayer player) {
    final List<Territory> result = new ArrayList<>();
    try (GameData.Unlocker ignored = getData().acquireReadLock()) {
      for (final Territory zone : AirStage.bases(player, stage)) {
        final boolean any =
            myAircraft(player, zone).stream()
                .map(Unit::getType)
                .distinct()
                .anyMatch(t -> !targetsFor(player, zone, t).isEmpty());
        if (any) {
          result.add(zone);
        }
      }
    }
    return result;
  }

  /**
   * Request 33, section 4: the front shown and its unassigned aircraft, one compact row per type
   * with how many go with the next map click.
   */
  private void addUnassigned(final GamePlayer player) {
    final List<Territory> fronts = frontsWithWork(player);
    if (selectedZone != null && !fronts.contains(selectedZone)) {
      fronts.add(0, selectedZone);
    }
    final JPanel frontRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
    frontRow.add(new JLabel("Base:"));
    final javax.swing.JComboBox<Territory> chooser =
        new javax.swing.JComboBox<>(fronts.toArray(new Territory[0]));
    chooser.setRenderer(
        new javax.swing.DefaultListCellRenderer() {
          @Override
          public Component getListCellRendererComponent(
              final javax.swing.JList<?> list,
              final Object value,
              final int index,
              final boolean isSelected,
              final boolean cellHasFocus) {
            final String text =
                value instanceof Territory zone ? frontSummary(player, zone) : "(choose a base)";
            return super.getListCellRendererComponent(list, text, index, isSelected, cellHasFocus);
          }
        });
    chooser.setSelectedItem(selectedZone);
    if (selectedZone == null) {
      chooser.setSelectedIndex(-1);
    }
    chooser.addActionListener(
        e -> {
          if (chooser.getSelectedItem() instanceof Territory zone && !zone.equals(selectedZone)) {
            chooseFront(player, zone, true);
          }
        });
    frontRow.add(chooser);
    // request 40: the next base only when asked
    final JButton next = smallButton("Next base");
    next.setToolTipText(
        "Selects the next base with aircraft that have no order yet (or press N). Nothing is ever"
            + " selected for you.");
    next.addActionListener(e -> nextBase(player));
    frontRow.add(next);
    layout.body().add(leftAligned(frontRow));
    if (selectedZone == null) {
      layout.body().add(leftAligned(new JLabel("Choose a base, or click your aircraft on the map.")));
      return;
    }
    final Territory zone = selectedZone;
    final IntegerMap<UnitType> free = available(player, zone);
    if (free.isEmpty()) {
      layout
          .body()
          .add(
              leftAligned(
                  new JLabel(
                      coverMode
                          ? "Every fighter here has somewhere to go."
                          : "Every bomber here has a target.")));
      return;
    }
    final JPanel rows = AirSelection.compactPanel();
    rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
    rows.setBorder(BorderFactory.createTitledBorder("Unassigned"));
    final List<UnitType> types = new ArrayList<>(free.keySet());
    types.sort(java.util.Comparator.comparing(UnitType::getName));
    for (final UnitType type : types) {
      final int count = free.getInt(type);
      final JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
      final JLabel name =
          new JLabel(AirStage.typeName(type).toLowerCase(java.util.Locale.ROOT), icon(player, type), JLabel.LEFT);
      row.add(name);
      row.add(new JLabel(count + " unassigned"));
      final boolean reach = !targetsFor(player, zone, type).isEmpty();
      final String tip =
          "Range "
              + AirRange.range(type, player)
              + ": out to the target and on to a landing spot. The next map click (or "
              + (coverMode ? "Send" : "Attack")
              + ") sends this many; − and + at a target change one at a time.";
      name.setToolTipText(tip);
      if (reach) {
        row.add(new JLabel("send:"));
        final int value = Math.max(0, Math.min(count, selected.getInt(type)));
        final javax.swing.JSpinner spinner =
            new javax.swing.JSpinner(new javax.swing.SpinnerNumberModel(value, 0, count, 1));
        spinner.setToolTipText(tip);
        spinner.addChangeListener(
            e -> {
              selected.put(type, (Integer) spinner.getValue());
              selectionChanged(false);
            });
        row.add(spinner);
      } else {
        final JLabel none = new JLabel("nothing in reach");
        none.setToolTipText(tip);
        row.add(none);
      }
      row.setToolTipText(tip);
      rows.add(leftAligned(row));
      final long damaged =
          myAircraft(player, zone).stream()
              .filter(u -> u.getType().equals(type) && u.getHits() > 0)
              .count();
      if (damaged > 0) {
        final JLabel hurt =
            new JLabel(
                "   of which damaged: "
                    + damaged
                    + " (undamaged "
                    + typeName(type, 2)
                    + " are sent first)");
        rows.add(leftAligned(hurt));
      }
    }
    rows.setAlignmentX(LEFT_ALIGNMENT);
    layout.body().add(rows);
  }

  /** "Eastern Front: 2 groups waiting" for the front chooser. */
  private String frontSummary(final GamePlayer player, final Territory zone) {
    final IntegerMap<UnitType> free = available(player, zone);
    final long waiting =
        free.keySet().stream().filter(t -> !targetsFor(player, zone, t).isEmpty()).count();
    return zone.getName()
        + (waiting == 0 ? ": done" : ": " + waiting + (waiting == 1 ? " group" : " groups") + " waiting");
  }

  /**
   * Request 33, section 5: the selected target only: what is there, the cap, what is assigned per
   * type with − and +, and the Attack (or Place) button.
   */
  private void addSelectedTarget(final GamePlayer player) {
    final JPanel block = AirSelection.compactPanel();
    block.setLayout(new BoxLayout(block, BoxLayout.Y_AXIS));
    block.setAlignmentX(LEFT_ALIGNMENT);
    if (selectedZone == null || selectedTarget == null) {
      block.setBorder(BorderFactory.createTitledBorder("Target"));
      block.add(
          leftAligned(
              new JLabel(
                  coverMode
                      ? "Click a territory on the map to see it here."
                      : "Click a target on the map to see it here.")));
      layout.body().add(block);
      return;
    }
    final Target target = selectedTarget;
    final Territory zone = selectedZone;
    block.setBorder(BorderFactory.createTitledBorder(target.territory().getName()));
    try (GameData.Unlocker ignored = getData().acquireReadLock()) {
      block.add(leftAligned(new JLabel(AirSelection.wrap(describe(player, target)))));
      // other targets in the same territory (land units and strategic bombing, ships and subs)
      final JPanel kinds = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
      for (final Target other : allTargets(player, zone)) {
        if (other.territory().equals(target.territory()) && !other.equals(target)) {
          final JButton show = smallButton("Show " + kindName(other.kind()).toLowerCase(java.util.Locale.ROOT));
          show.addActionListener(e -> selectTarget(other));
          kinds.add(show);
        }
      }
      if (kinds.getComponentCount() > 0) {
        block.add(leftAligned(kinds));
      }
      final List<UnitType> types =
          myAircraft(player, zone).stream()
              .map(Unit::getType)
              .distinct()
              .filter(t -> mayGo(player, zone, t, target))
              .sorted(java.util.Comparator.comparing(UnitType::getName))
              .toList();
      if (types.isEmpty()) {
        block.add(leftAligned(new JLabel("None of this base's aircraft can go there.")));
      }
      for (final UnitType type : types) {
        final JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        row.add(new JLabel(AirStage.typeName(type) + ":", icon(player, type), JLabel.LEFT));
        row.add(counter(player, zone, type, target, true));
        block.add(leftAligned(row));
      }
    }
    final JButton attack = smallButton(coverMode ? "Send here" : "Attack");
    attack.setToolTipText(
        (coverMode ? "Sends" : "Sends")
            + " the counts chosen under Unassigned against "
            + target.describe()
            + ", as a click on the map does. − and + change one at a time.");
    attack.setEnabled(sendable(player, zone, target) > 0);
    attack.addActionListener(e -> assign(target, selected));
    block.add(leftAligned(attack));
    layout.body().add(block);
  }

  /** How many of the counts chosen to send can go against the target. */
  private int sendable(final GamePlayer player, final Territory zone, final Target target) {
    return selected.keySet().stream()
        .filter(t -> mayGo(player, zone, t, target))
        .mapToInt(t -> Math.min(selected.getInt(t), available(player, zone).getInt(t)))
        .sum();
  }

  /** Request 33: a target chosen on the map, in a moves line or in the list window. */
  void selectTarget(final @Nullable Target target) {
    selectedTarget = target;
    rebuild(getCurrentPlayer());
    if (pickDialog != null && pickDialog.isDisplayable()) {
      pickDialog.showTarget(target);
    }
    getMap().repaint();
  }

  /** Every unassigned aircraft with a target in reach goes with the next click (the default). */
  private void resetSend(final @Nullable Territory zone) {
    selected.clear();
    if (zone == null) {
      return;
    }
    final GamePlayer player = getCurrentPlayer();
    final IntegerMap<UnitType> free = available(player, zone);
    for (final UnitType type : free.keySet()) {
      if (!targetsFor(player, zone, type).isEmpty()) {
        selected.put(type, free.getInt(type));
      }
    }
  }

  // ---- the pick-from-list window (request 33)

  // the window's place and size, kept for the rest of the game
  private static @Nullable Rectangle pickBounds;

  void openPickDialog(final GamePlayer player) {
    if (selectedZone == null) {
      return;
    }
    if (pickDialog == null || !pickDialog.isDisplayable()) {
      pickDialog = new PickTargetDialog(player);
    }
    pickDialog.zone = selectedZone;
    pickDialog.refreshList();
    pickDialog.setVisible(true);
    pickDialog.toFront();
  }

  /** For tests: the moves made, oldest first. */
  List<TargetAssignment> staged() {
    return List.copyOf(staged);
  }

  /** For tests: the target shown in the panel. */
  @Nullable
  Target selectedTarget() {
    return selectedTarget;
  }

  /** For tests: the front shown. */
  @Nullable
  Territory selectedZone() {
    return selectedZone;
  }

  /** For tests: the player's unassigned aircraft of this step in the front. */
  IntegerMap<UnitType> unassigned(final GamePlayer player, final Territory zone) {
    return available(player, zone);
  }

  @Nullable
  PickTargetDialog pickDialog() {
    return pickDialog;
  }

  /** The targets in reach of any of this front's aircraft of this step, by name. */
  List<Target> targetsInReach(final GamePlayer player, final Territory zone) {
    final List<UnitType> types = myAircraft(player, zone).stream().map(Unit::getType).distinct().toList();
    return allTargets(player, zone).stream()
        .filter(t -> types.stream().anyMatch(type -> mayGo(player, zone, type, t)))
        .sorted(
            java.util.Comparator.comparing((Target t) -> t.territory().getName())
                .thenComparing(Target::kind))
        .toList();
  }

  /**
   * "Bialystok · no AA · B 2/4 · yours: 2"; in the cover step "Bialystok · enemy B 3 · B 1/4 F
   * 2/4 · yours: 2".
   */
  String pickLabel(final GamePlayer player, final Territory zone, final Target target) {
    final Territory t = target.territory();
    final int mine =
        staged.stream()
            .filter(a -> a.zone().equals(zone) && a.target().equals(target))
            .mapToInt(TargetAssignment::count)
            .sum();
    final String what =
        switch (target.kind()) {
          case SURFACE -> " (surface ships)";
          case SUBS -> " (submarines)";
          case STRATEGIC -> " (strategic bombing)";
          default -> "";
        };
    final String aa;
    if (t.isWater()) {
      aa = "";
    } else {
      final List<Unit> guns =
          t.getUnitCollection()
              .getMatches(
                  Matches.unitIsAaForAnything().and(u -> AirCombat.atWar(player, u.getOwner())));
      aa = guns.isEmpty() ? " · no AA" : " · AA " + guns.size();
    }
    return t.getName() + what + aa + " · " + badge(player, zone, t) + (mine > 0 ? " · yours: " + mine : "");
  }

  /**
   * Request 33: picking from a list, in its own window so it is never mixed into the side panel.
   * Left: every target in reach, with a filter. Right: the chosen target, a count per type and
   * Assign, which makes the move exactly as a map click does. Undo stays in the side panel.
   */
  final class PickTargetDialog extends javax.swing.JDialog {
    private static final long serialVersionUID = 1L;
    private final GamePlayer player;
    private final javax.swing.JTextField filter = new javax.swing.JTextField(14);
    private final javax.swing.DefaultListModel<Target> model = new javax.swing.DefaultListModel<>();
    private final javax.swing.JList<Target> list = new javax.swing.JList<>(model);
    private final JPanel right = new JPanel();
    private final IntegerMap<UnitType> counts = new IntegerMap<>();
    private boolean updating;
    // request 40: the base the window was opened for (the panel's selection clears after a move)
    private @Nullable Territory zone;

    PickTargetDialog(final GamePlayer player) {
      super(
          JOptionPane.getFrameForComponent(AirTargetPanel.this),
          PlayerNames.display(player) + ": " + stepTitle() + " (pick from list)",
          ModalityType.MODELESS);
      this.player = player;
      setDefaultCloseOperation(DISPOSE_ON_CLOSE);
      list.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
      list.setCellRenderer(
          new javax.swing.DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(
                final javax.swing.JList<?> l,
                final Object value,
                final int index,
                final boolean isSelected,
                final boolean cellHasFocus) {
              final String text =
                  value instanceof Target t && zone != null
                      ? pickLabel(player, zone, t)
                      : String.valueOf(value);
              return super.getListCellRendererComponent(l, text, index, isSelected, cellHasFocus);
            }
          });
      list.addListSelectionListener(
          e -> {
            if (!e.getValueIsAdjusting() && !updating) {
              counts.clear();
              final Target t = list.getSelectedValue();
              if (t != null) {
                if (!zone.equals(selectedZone)) {
                  selectedZone = zone;
                  resetSend(zone);
                }
                selectTarget(t);
                getMap().centerOn(t.territory());
              }
              fillRight();
            }
          });
      filter.getDocument()
          .addDocumentListener(
              new javax.swing.event.DocumentListener() {
                @Override
                public void insertUpdate(final javax.swing.event.DocumentEvent e) {
                  refreshList();
                }

                @Override
                public void removeUpdate(final javax.swing.event.DocumentEvent e) {
                  refreshList();
                }

                @Override
                public void changedUpdate(final javax.swing.event.DocumentEvent e) {
                  refreshList();
                }
              });
      final JPanel left = new JPanel(new BorderLayout(0, 4));
      final JPanel filterRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
      filterRow.add(new JLabel("Filter:"));
      filterRow.add(filter);
      left.add(filterRow, BorderLayout.NORTH);
      left.add(new JScrollPane(list), BorderLayout.CENTER);
      right.setLayout(new BoxLayout(right, BoxLayout.Y_AXIS));
      final JButton close = new JButton("Close");
      close.addActionListener(e -> dispose());
      final JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT));
      bottom.add(close);
      final javax.swing.JSplitPane split =
          new javax.swing.JSplitPane(
              javax.swing.JSplitPane.HORIZONTAL_SPLIT, left, new JScrollPane(right));
      split.setResizeWeight(0.5);
      split.setDividerLocation(400);
      right.setMinimumSize(new Dimension(320, 0));
      getContentPane().setLayout(new BorderLayout());
      getContentPane().add(split, BorderLayout.CENTER);
      getContentPane().add(bottom, BorderLayout.SOUTH);
      if (pickBounds != null) {
        setBounds(pickBounds);
      } else {
        setSize(820, 480);
        setLocationRelativeTo(AirTargetPanel.this);
      }
      addComponentListener(
          new java.awt.event.ComponentAdapter() {
            @Override
            public void componentMoved(final java.awt.event.ComponentEvent e) {
              pickBounds = getBounds();
            }

            @Override
            public void componentResized(final java.awt.event.ComponentEvent e) {
              pickBounds = getBounds();
            }
          });
      fillRight();
    }

    /** The targets in reach that match the filter, keeping the selection. */
    void refreshList() {
      final Target keep = list.getSelectedValue();
      updating = true;
      model.clear();
      if (zone != null) {
        final String text = filter.getText().trim().toLowerCase(java.util.Locale.ROOT);
        try (GameData.Unlocker ignored = getData().acquireReadLock()) {
          for (final Target t : targetsInReach(player, zone)) {
            if (text.isEmpty()
                || t.territory().getName().toLowerCase(java.util.Locale.ROOT).contains(text)) {
              model.addElement(t);
            }
          }
        }
      }
      if (keep != null && model.contains(keep)) {
        list.setSelectedValue(keep, true);
      }
      updating = false;
      list.repaint();
    }

    /** The side panel or the map chose a target: show it here too. */
    void showTarget(final @Nullable Target target) {
      if (target == null || target.equals(list.getSelectedValue())) {
        return;
      }
      updating = true;
      if (model.contains(target)) {
        list.setSelectedValue(target, true);
      } else {
        list.clearSelection();
      }
      updating = false;
      counts.clear();
      fillRight();
    }

    /** The chosen target: one row per type that can go, a count each, and Assign. */
    void fillRight() {
      right.removeAll();
      final Target target = list.getSelectedValue();
      if (target == null || zone == null) {
        right.add(leftAligned(new JLabel("Pick a target on the left.")));
      } else {
        right.add(leftAligned(new JLabel(AirSelection.wrap(pickLabel(player, zone, target)))));
        final IntegerMap<UnitType> free = available(player, zone);
        final int room = room(player, zone, target.territory());
        for (final UnitType type : free.keySet()) {
          if (!mayGo(player, zone, type, target)) {
            continue;
          }
          final int max = Math.min(free.getInt(type), room);
          final JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
          row.add(new JLabel(AirStage.typeName(type), icon(player, type), JLabel.LEFT));
          row.add(new JLabel(free.getInt(type) + " unassigned"));
          final javax.swing.JSpinner spinner =
              new javax.swing.JSpinner(
                  new javax.swing.SpinnerNumberModel(
                      Math.min(counts.getInt(type), Math.max(0, max)), 0, Math.max(0, max), 1));
          spinner.setToolTipText(
              room == Integer.MAX_VALUE
                  ? "At most the unassigned aircraft"
                  : "At most the unassigned aircraft and the cap (" + room + " more allowed)");
          spinner.addChangeListener(e -> counts.put(type, (Integer) spinner.getValue()));
          row.add(spinner);
          right.add(leftAligned(row));
        }
        final JButton assign = new JButton("Assign");
        assign.setToolTipText("Makes the move as a click on the map would. Undo it in the side panel.");
        assign.addActionListener(
            e -> {
              // the window's own base, whatever the panel shows
              selectedZone = zone;
              assign(target, counts);
              counts.clear();
              refreshList();
              fillRight();
            });
        right.add(leftAligned(assign));
      }
      right.add(Box.createVerticalGlue());
      right.revalidate();
      right.repaint();
    }

    javax.swing.JList<Target> targetList() {
      return list;
    }

    IntegerMap<UnitType> counts() {
      return counts;
    }
  }

  /** "dive bomber", "dive bombers". */
  private static String typeName(final UnitType type, final int count) {
    final String name = AirStage.typeName(type).toLowerCase(java.util.Locale.ROOT);
    return count == 1 ? name : name + "s";
  }

  private void chooseGroup(
      final GamePlayer player, final Territory zone, final UnitType type, final boolean scroll) {
    chooseFront(player, zone, scroll);
  }

  /**
   * Request 33: shows a front: all its unassigned aircraft go with the next click, its box and the
   * targets in reach come into view, and the box flashes.
   */
  void chooseFront(final GamePlayer player, final Territory zone, final boolean scroll) {
    if (!zone.equals(selectedZone)) {
      selectedTarget = null;
    }
    selectedZone = zone;
    resetSend(zone);
    if (scroll) {
      getMap().centerOn(zone);
    }
    if (frame.getAirMapMode() != null) {
      frame.getAirMapMode().flash(zone);
    }
    if (pickDialog != null && pickDialog.isDisplayable()) {
      pickDialog.refreshList();
    }
    selectionChanged();
  }

  /** "1 Torpedo Bomber from Mediterranean → Tobruk". */
  private static String describe(final TargetAssignment a) {
    return a.count()
        + " "
        + ResearchView.displayName(a.unitType().getName())
        + " from "
        + a.zone().getName()
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
                    + " at "
                    + zone.getName()
                    + (coverMode ? " is sent" : " has a target"));
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
      myAircraft(player, selectedZone).stream()
          .filter(u -> u.getType().equals(type))
          .findFirst()
          .ifPresent(
              u -> {
                // request 39: as dice, "3 + 1", per round (two rounds at sea)
                final String value =
                    AirCombat.strikeText(u, target.kind())
                        + (AirCombat.strikeRounds(target.kind()) > 1 ? " ×2 rounds" : "");
                values.add(
                    selected.size() == 1
                        ? value
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
    return all.stream().anyMatch(a -> a.target().territory().equals(t));
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
        final Map<Territory, Boolean> legalPlaces = new HashMap<>();
        targets.forEach((t, legal) -> legalPlaces.merge(t.territory(), legal, Boolean::logicalOr));
        legalPlaces.forEach(
            (t, legal) -> {
              if (legal) {
                fill(g, t, TARGET_FILL);
                draw.outlineBox(g, t, AirDraw.LEGAL_LINE, 3f);
              }
            });
        draw.outlineBox(g, selectedZone, AirDraw.ORIGIN_LINE, 5f);
      }
      // request 33: the target shown in the panel
      if (selectedTarget != null) {
        draw.outlineBox(g, selectedTarget.territory(), Color.YELLOW, 4f);
      }
      if (selectedZone != null && !selected.isEmpty() && mousePoint != null) {
        paintPreview(g, player);
      }
      final Map<Territory, Integer> perTarget = new HashMap<>();
      // the fighter stage shows every nation's bombers on their targets
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
      // request 38 section 12: the allies' choices so far, dashed
      for (final Map.Entry<GamePlayer, List<TargetAssignment>> e : allied.entrySet()) {
        for (final TargetAssignment a : e.getValue()) {
          final Point end = draw.middle(a.target().territory());
          final int n = perTarget.merge(a.target().territory(), 1, Integer::sum) - 1;
          final Point icon = new Point(end.x - 16 + n * 34, end.y - 16);
          AirDraw.arrow(g, draw.center(a.zone()), end, ALLY_LINE, 2.5f, true, "", 6);
          draw.iconAt(g, icon, e.getKey(), a.unitType(), a.count());
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
        if (badged.add(a.target().territory().getName())) {
          final Point end = draw.middle(a.target().territory());
          AirDraw.tag(
              g,
              badge(player, a.zone(), a.target().territory()),
              end.x,
              end.y - 24,
              new Color(255, 230, 120));
        }
      }
      // own choices: arrow from the base to the target, with the aircraft and count there
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
