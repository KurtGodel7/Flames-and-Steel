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
  // request 44: aircraft with orders, by target, drawn there until the stage ends
  private volatile Map<Territory, List<Unit>> ordered = Map.of();
  private volatile boolean ordersShown;
  private boolean overlayAdded;
  private @Nullable games.strategy.engine.data.GameStep stageStep;
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
  // request 41: shows the stock unit chooser; true when the player pressed OK (tests answer it)
  private java.util.function.BiPredicate<games.strategy.triplea.ui.UnitChooser, String> askChooser =
      (chooser, title) ->
          JOptionPane.showOptionDialog(
                  JOptionPane.getFrameForComponent(this),
                  chooser,
                  title,
                  JOptionPane.OK_CANCEL_OPTION,
                  JOptionPane.PLAIN_MESSAGE,
                  null,
                  null,
                  null)
              == JOptionPane.OK_OPTION;
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
            updateRoute(territory);
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

    /** Request 47: the stage's aircraft without orders in the unit scroller (null: none). */
    default void setAircraftScroller(
        final @Nullable games.strategy.triplea.ui.unit.scroller.UnitScroller.Source source) {}

    /** Request 47: the count changed. */
    default void refreshAircraftScroller() {}
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

          @Override
          public void setAircraftScroller(
              final @Nullable games.strategy.triplea.ui.unit.scroller.UnitScroller.Source source) {
            frame.setAircraftScroller(source);
          }

          @Override
          public void refreshAircraftScroller() {
            frame.refreshAircraftScroller();
          }
        });
  }

  // request 47: the stock unit scroller (its buttons and the '.', ',' and 'S' keys) goes through
  // the aircraft without orders
  private volatile int withoutOrders;
  private final games.strategy.triplea.ui.unit.scroller.UnitScroller.Source scrollerSource =
      new games.strategy.triplea.ui.unit.scroller.UnitScroller.Source() {
        @Override
        public String title() {
          return "Aircraft without orders: " + withoutOrders;
        }

        @Override
        public void next(final boolean forward) {
          SwingUtilities.invokeLater(() -> nextWithoutOrders(forward));
        }
      };
  // request 47: the base last gone to with "next", and whether the how-to shows in the panel
  private @Nullable Territory lastVisited;
  private boolean helpShown;
  // request 48: the moves the stage opened with; the how-to goes once the player makes one more
  private int helpBaseline;

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
    // request 40: Escape clears the selection, as in a stock move
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
          try (GameData.Unlocker ignored = getData().acquireReadLock()) {
            stageStep = getData().getSequence().getStep();
          }
          staged.clear();
          staged.addAll(start);
          this.store = store;
          reachable.clear();
          try (GameData.Unlocker ignored = getData().acquireReadLock()) {
            reach = new Reach(player);
          }
          clearSelection();
          showAssigned();
          lastVisited = null;
          helpShown = AirHelp.showAsStageOpens(getData(), player, stage.name());
          helpBaseline = staged.size();
          frame.setAircraftScroller(scrollerSource);
          if (!listening) {
            listening = true;
            getMap().addUnitSelectionListener(unitListener);
            getMap().addMapSelectionListener(hoverListener);
            if (!overlayAdded) {
              overlayAdded = true;
              getMap().addOverlay(overlay);
            }
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
            if (ordersShown) {
              showAssigned();
            }
            getMap().repaint();
            return;
          }
          final List<String> why = new ArrayList<>();
          try (GameData.Unlocker ignored = getData().acquireReadLock()) {
            for (final TargetAssignment a : List.copyOf(staged)) {
              final Optional<GamePlayer> locker =
                  AirPriority.lockedBy(player, a.target().territory(), allied);
              // request 43 section 1: an earlier ally's raid on the same factory
              final Optional<GamePlayer> raider = AirPriority.raidTakenBy(player, a.target(), allied);
              if (locker.isPresent() || raider.isPresent()) {
                staged.remove(a);
                final String text =
                    locker.isPresent()
                        ? AirPriority.lockedText(a.target().territory(), locker.get())
                        : AirPriority.raidText(a.target().territory(), raider.get());
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
            showAssigned();
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
    if (target.kind() == TargetKind.REBASE) {
      return mayRebase(player, zone, type, target.territory());
    }
    return targetsFor(player, zone, type).contains(target)
        && AirPriority.lockedBy(player, target.territory(), allied).isEmpty()
        && AirPriority.raidTakenBy(player, target, allied).isEmpty();
  }

  /** Request 45: whether aircraft of the type on the base may rebase to the territory. */
  private boolean mayRebase(
      final GamePlayer player, final Territory zone, final UnitType type, final Territory to) {
    try (GameData.Unlocker ignored = getData().acquireReadLock()) {
      return AirStage.rebaseProblem(player, zone, type, to, stage, revealed).isEmpty();
    }
  }

  /**
   * Request 45: whether a click on the territory would be a rebase: it holds nothing to attack, and
   * in the fighter stage no bombers are going there.
   */
  private boolean isRebasePlace(final GamePlayer player, final Territory territory) {
    try (GameData.Unlocker ignored = getData().acquireReadLock()) {
      return !AirBases.isAirborneTerritory(territory)
          && AirCombat.targetsIn(player, territory).isEmpty()
          && !(coverMode && AirStage.bomberPlaces(revealed).contains(territory));
    }
  }

  /** Request 45: why the selection can't rebase to the territory, or empty if some of it can. */
  private Optional<String> rebaseWhyNot(final GamePlayer player, final Territory territory) {
    Optional<String> first = Optional.empty();
    try (GameData.Unlocker ignored = getData().acquireReadLock()) {
      for (final UnitType type : selected.keySet()) {
        final Optional<String> why =
            AirStage.rebaseProblem(player, selectedZone, type, territory, stage, revealed);
        if (why.isEmpty()) {
          return Optional.empty();
        }
        if (first.isEmpty()) {
          first = why;
        }
      }
    }
    return first;
  }

  /**
   * Request 45: the selection flies to the territory with no attack, as a stock non-combat move
   * made during combat move; the types that can't go there stay selected with the reason.
   */
  private void rebaseTo(final GamePlayer player, final Territory territory) {
    final IntegerMap<UnitType> going = new IntegerMap<>();
    for (final UnitType type : selected.keySet()) {
      if (selected.getInt(type) > 0 && mayRebase(player, selectedZone, type, territory)) {
        going.put(type, selected.getInt(type));
      }
    }
    if (going.isEmpty()) {
      frame.setStatusErrorMessage(rebaseWhyNot(player, territory).orElse(""));
      return;
    }
    assign(AirStage.rebaseTo(territory), going);
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
      // request 44: the orders stay drawn (and off their bases) until the stage ends
      if (!ordersShown && overlayAdded) {
        overlayAdded = false;
        getMap().removeOverlay(overlay);
      }
      getMap().setUnitHighlight(List.of());
      getMap().setRoute(null);
      getMap().setMouseShadowUnits(null);
      if (frame.getAirMapMode() != null) {
        frame.getAirMapMode().setBusy(false);
        frame.getAirMapMode().setBanner(null);
      }
      frame.clearStatusMessage();
      frame.setAircraftScroller(null);
    }
  }

  /**
   * Request 47: goes to the next (or previous) base with aircraft without orders, in the order of
   * the bases: centers the map on it, flashes it and selects one aircraft there, as a plain click
   * would. Repeated, it goes round all of them.
   */
  void nextWithoutOrders(final boolean forward) {
    if (!isActive() || !listening || finished) {
      return;
    }
    final GamePlayer player = getCurrentPlayer();
    final List<Group> waiting = waitingGroups(player);
    if (waiting.isEmpty()) {
      frame.setStatusMessage("Every aircraft that can fly has orders: press Done.");
      return;
    }
    final List<Territory> order;
    try (GameData.Unlocker ignored = getData().acquireReadLock()) {
      order = new ArrayList<>(AirStage.bases(player, stage));
    }
    waiting.stream().map(Group::zone).filter(z -> !order.contains(z)).forEach(order::add);
    final int n = order.size();
    final int from = lastVisited == null ? -1 : order.indexOf(lastVisited);
    Territory zone = null;
    for (int step = 1; step <= n && zone == null; step++) {
      final int i =
          from < 0
              ? (forward ? step - 1 : n - step)
              : Math.floorMod(from + (forward ? step : -step), n);
      final Territory t = order.get(i);
      if (waiting.stream().anyMatch(g -> g.zone().equals(t))) {
        zone = t;
      }
    }
    if (zone == null) {
      return;
    }
    lastVisited = zone;
    final Territory place = zone;
    final Group group = waiting.stream().filter(g -> g.zone().equals(place)).findFirst().orElseThrow();
    getMap().centerOn(zone);
    if (frame.getAirMapMode() != null) {
      frame.getAirMapMode().flash(zone);
    } else {
      getMap()
          .highlightTerritory(
              zone, MapPanel.AnimationDuration.STANDARD, MapPanel.HighlightDelay.SHORT_DELAY);
    }
    // one aircraft selected, as a plain click on the stack selects one
    clearSelection();
    selectedZone = zone;
    selected.put(group.type(), 1);
    selectionChanged();
    frame.setStatusMessage(
        "1 "
            + AirCombat.unitName(group.type())
            + " at "
            + zone.getName()
            + " selected ("
            + withoutOrders
            + " without orders): click where it goes. '.' goes on, ',' goes back.");
  }

  /**
   * Request 30: one move per place with aircraft, whose place shows its side's counts against the
   * caps. Request 48: a rebase is no attack and counts against no cap, so it gets no badge.
   */
  List<TargetAssignment> badgedMoves() {
    final java.util.Set<String> badged = new java.util.HashSet<>();
    final List<TargetAssignment> withBadges = new ArrayList<>(staged);
    allied.values().forEach(withBadges::addAll);
    if (coverMode) {
      revealed.values().forEach(withBadges::addAll);
    }
    final List<TargetAssignment> result = new ArrayList<>();
    for (final TargetAssignment a : withBadges) {
      if (!AirStage.isRebase(a) && badged.add(a.target().territory().getName())) {
        result.add(a);
      }
    }
    return result;
  }

  /** For tests: the base selected and how many aircraft are selected there. */
  @Nullable
  Territory selectedBase() {
    return selectedZone;
  }

  int selectedCount() {
    return selected.totalValues();
  }

  /** For tests: the choices so far, as if made on the map. */
  void setStagedForTests(final List<TargetAssignment> moves) {
    staged.clear();
    staged.addAll(moves);
    strikesChanged();
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
      // request 49: as stock reports an illegal move: red text in the status bar, no window
      frame.setStatusErrorMessage(problem.get());
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

  /**
   * Request 41: clicks exactly as in a stock TripleA move. On the base (or with nothing selected): a
   * click on a stack adds one aircraft, Alt+click ten, Ctrl+click the whole stack, Shift+click every
   * aircraft of the stage there; a click on the base's empty ground opens the unit chooser. A click
   * on another territory sends the selection there. Right-click takes one off (Ctrl: the stack), or
   * elsewhere cancels; Escape cancels.
   */
  private void clicked(
      final List<Unit> clickedUnits, final @Nullable Territory clicked, final MouseDetails md) {
    click(clickedUnits, clicked, md);
  }

  /** For tests: a click on the map. */
  void click(final List<Unit> units, final @Nullable Territory territory, final MouseDetails md) {
    if (!isActive() || !listening || territory == null) {
      return;
    }
    final GamePlayer player = getCurrentPlayer();
    if (md.isRightButton()) {
      deselect(player, units, territory, md);
      return;
    }
    if (selectedZone == null || territory.equals(selectedZone)) {
      if (selectedZone == null) {
        selectedPoint = md.getMapPoint();
        selectedPointZone = territory;
      }
      selectUnits(player, territory, units, md);
      return;
    }
    sendTo(player, territory);
  }

  private void deselect(
      final GamePlayer player,
      final List<Unit> units,
      final Territory territory,
      final MouseDetails md) {
    final Optional<UnitType> type =
        units.stream().filter(u -> u.isOwnedBy(player) && isMine(u)).map(Unit::getType).findFirst();
    if (!territory.equals(selectedZone) || type.isEmpty() || selected.getInt(type.get()) <= 0) {
      clearSelection();
      selectionChanged();
      return;
    }
    final int remove =
        md.isControlDown() ? selected.getInt(type.get()) : md.isAltDown() ? MULTI_SELECT : 1;
    selected.put(type.get(), Math.max(0, selected.getInt(type.get()) - remove));
    if (selected.getInt(type.get()) <= 0) {
      selected.removeKey(type.get());
    }
    if (selected.isEmpty()) {
      clearSelection();
    }
    selectionChanged();
  }

  /** As a stock move's "Alt+click": how many one click adds. */
  private static final int MULTI_SELECT = 10;

  private void selectUnits(
      final GamePlayer player,
      final Territory zone,
      final List<Unit> units,
      final MouseDetails md) {
    final IntegerMap<UnitType> available = available(player, zone);
    if (available.isEmpty()) {
      if (units.stream().anyMatch(u -> u.isOwnedBy(player) && AirBases.isAircraft(u))) {
        frame.setStatusErrorMessage(
            units.stream().anyMatch(u -> u.isOwnedBy(player) && isMine(u))
                ? "All of those already have orders; undo one to change it."
                : coverMode
                    ? "Fighters fly in this stage; bombers had theirs."
                    : "Bombers fly in this stage; fighters come next.");
      }
      return;
    }
    final List<Unit> mine =
        units.stream().filter(u -> u.isOwnedBy(player) && isMine(u)).toList();
    if (!zone.equals(selectedZone)) {
      selected.clear();
    }
    if (units.isEmpty() && selected.isEmpty() && !md.isShiftDown()) {
      // as in a stock move: a click beside the stacks asks which units, with the unit chooser
      final List<Unit> candidates = unassignedUnits(player, zone, available);
      final games.strategy.triplea.ui.UnitChooser chooser =
          new games.strategy.triplea.ui.UnitChooser(
              candidates,
              List.of(),
              null,
              games.strategy.triplea.util.UnitSeparator.SeparatorCategories.builder().build(),
              false,
              frame.getUiContext());
      if (!askChooser.test(chooser, "Select aircraft to send from " + zone.getName())) {
        return;
      }
      chooser.getSelected(false).forEach(u -> selected.add(u.getType(), 1));
    } else if (md.isShiftDown()) {
      for (final UnitType type : available.keySet()) {
        selected.put(type, available.getInt(type));
      }
    } else if (!mine.isEmpty()) {
      final UnitType type = mine.get(0).getType();
      final int add = md.isControlDown() ? available.getInt(type) : md.isAltDown() ? MULTI_SELECT : 1;
      selected.put(type, Math.min(available.getInt(type), selected.getInt(type) + add));
    } else if (!units.isEmpty()) {
      frame.setStatusErrorMessage(
          units.stream().anyMatch(u -> !u.isOwnedBy(player))
              ? "Those are not your aircraft."
              : coverMode
                  ? "Fighters fly in this stage; pick fighters."
                  : "Bombers fly in this stage; pick bombers.");
      return;
    }
    selected.keySet().removeIf(t -> selected.getInt(t) <= 0);
    selectedZone = selected.isEmpty() ? null : zone;
    selectionChanged();
  }

  /** The player's aircraft of this stage on the base that have no order yet, undamaged first. */
  private List<Unit> unassignedUnits(
      final GamePlayer player, final Territory zone, final IntegerMap<UnitType> available) {
    final IntegerMap<UnitType> left = new IntegerMap<>(available);
    final List<Unit> result = new ArrayList<>();
    for (final Unit u : myAircraft(player, zone)) {
      if (left.getInt(u.getType()) > 0) {
        left.add(u.getType(), -1);
        result.add(u);
      }
    }
    return result;
  }

  /**
   * The selection is sent to the territory, if it is a target (bombers) or a place (fighters) for
   * them. As in a stock move, when not all selected aircraft can go (the type can't, or the side's
   * cap leaves less room), the unit chooser asks which, limited to what fits.
   */
  private void sendTo(final GamePlayer player, final Territory territory) {
    final List<Target> here =
        targets.entrySet().stream()
            .filter(e -> e.getValue() && e.getKey().territory().equals(territory))
            .map(Map.Entry::getKey)
            .toList();
    if (here.isEmpty()) {
      // request 45: nothing to attack there, so it is a rebase
      if (isRebasePlace(player, territory)) {
        rebaseTo(player, territory);
      } else {
        frame.setStatusErrorMessage(whyNotTarget(player, territory));
      }
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
    final Territory zone = selectedZone;
    final Target chosen = target;
    final IntegerMap<UnitType> going = new IntegerMap<>();
    int wanted = 0;
    for (final UnitType type : selected.keySet()) {
      wanted += selected.getInt(type);
      if (mayGo(player, zone, type, chosen)) {
        going.put(type, selected.getInt(type));
      }
    }
    final int room = room(player, zone, territory);
    if (going.totalValues() == 0) {
      frame.setStatusErrorMessage(whyNotTarget(player, territory));
      return;
    }
    if (going.totalValues() < wanted || going.totalValues() > room) {
      final int max = Math.min(room, going.totalValues());
      final List<Unit> candidates = new ArrayList<>();
      final IntegerMap<UnitType> left = new IntegerMap<>(going);
      for (final Unit u : myAircraft(player, zone)) {
        if (left.getInt(u.getType()) > 0) {
          left.add(u.getType(), -1);
          candidates.add(u);
        }
      }
      final games.strategy.triplea.ui.UnitChooser chooser =
          new games.strategy.triplea.ui.UnitChooser(
              candidates,
              candidates.subList(0, Math.min(max, candidates.size())),
              null,
              games.strategy.triplea.util.UnitSeparator.SeparatorCategories.builder().build(),
              false,
              frame.getUiContext());
      chooser.setMax(max);
      final String title =
          (max < going.totalValues()
                  ? territory.getName() + ": room for " + max + " more (" + badge(player, zone, territory) + ")"
                  : "Not all of these can go to " + territory.getName())
              + ". Which go?";
      if (!askChooser.test(chooser, title)) {
        return;
      }
      going.clear();
      chooser.getSelected(false).forEach(u -> going.add(u.getType(), 1));
      if (going.isEmpty()) {
        return;
      }
    }
    assign(chosen, going);
  }

  /** For tests: the mouse moves over the territory at this map point. */
  void pointAt(final Territory territory, final Point point) {
    hovered(territory);
    if (listening && selectedZone != null && !selected.isEmpty()) {
      mousePoint = point;
      updateRoute(territory);
    }
  }

  /** For tests: answers the unit chooser instead of the player. */
  void setChooserAnswer(
      final java.util.function.BiPredicate<games.strategy.triplea.ui.UnitChooser, String> answer) {
    askChooser = answer;
  }

  /** For tests: the aircraft selected, by type. */
  IntegerMap<UnitType> selectedCounts() {
    return new IntegerMap<>(selected);
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
    // request 45: rebases count against no cap
    int room =
        target.kind() == TargetKind.REBASE
            ? Integer.MAX_VALUE
            : room(player, zone, target.territory());
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
      // request 42: every move is its own line, as in stock's moves list
      staged.add(new TargetAssignment(zone, type, count, target));
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
   * Request 22: the banner over the map while groups wait: "Italians: choose targets for 2 aircraft
   * groups. ..."; gone when none wait or the step ends.
   */
  private void updateBanner(final GamePlayer player, final int waiting) {
    withoutOrders = finished ? 0 : aircraftWithoutOrders(player);
    frame.refreshAircraftScroller();
    final AirMapMode mode = frame.getAirMapMode();
    if (mode == null) {
      return;
    }
    mode.setBanner(finished ? null : banner(player));
  }

  /**
   * Request 47: "Germans: <b>1</b> aircraft without orders", or "Germans: all aircraft have
   * orders"; the how-to is its tooltip.
   */
  AirMapMode.Banner banner(final GamePlayer player) {
    final int idle = aircraftWithoutOrders(player);
    final String who = PlayerNames.display(player) + ": ";
    return idle == 0
        ? new AirMapMode.Banner(who, "all", " aircraft have orders", help())
        : new AirMapMode.Banner(who, String.valueOf(idle), " aircraft without orders", help());
  }

  /** Request 47: how the stage works, for the banner's tooltip and (at first) the side panel. */
  private String help() {
    return (coverMode
            ? "Bombers are shown on their targets. Select fighters on the map as in a normal move"
                + " (click for one, Ctrl+click the stack, Shift+click all there), then click a"
                + " green territory: over your bombers' target to escort, over the enemy's to"
                + " defend."
            : "Select bombers on the map as in a normal move (click for one, Ctrl+click the"
                + " stack, Shift+click all there), then click a green target.")
        + " A place with nothing to attack is a rebase. Aircraft without orders stay home."
        + " '.' and ',' (or the unit scroller's arrows) go to the next and previous aircraft"
        + " without orders. Ctrl+Z undoes the last move.";
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
    routeTo = null;
    selectedPoint = null;
    selectedPointZone = null;
    getMap().setRoute(null);
    getMap().setMouseShadowUnits(null);
  }

  // request 42: where the stock route arrow starts (the click that selected) and where it points;
  // request 53: and the territory that click was in. A selection made any other way (the unit
  // scroller, the list window, a Show) starts the arrow at the aircraft's own territory, never at
  // an earlier click somewhere else.
  private @Nullable Point selectedPoint;
  private @Nullable Territory selectedPointZone;
  private @Nullable Territory routeTo;

  /**
   * Request 42: the stock route arrow from the base to the territory under the mouse, with stock's
   * figure at its end ("steps flown / movement left", for aircraft their range) and the selected
   * aircraft at the cursor, exactly as in a stock move.
   */
  private void updateRoute(final @Nullable Territory territory) {
    if (selectedZone == null || selected.isEmpty() || territory == null) {
      getMap().setRoute(null);
      return;
    }
    final GamePlayer player = getCurrentPlayer();
    final List<Territory> path;
    try (GameData.Unlocker ignored = getData().acquireReadLock()) {
      path =
          AirBases.path(player, selectedZone, territory, 60)
              .orElse(List.of(selectedZone, territory));
      if (!territory.equals(routeTo)) {
        getMap().setMouseShadowUnits(selectedUnits(player));
      }
    }
    routeTo = territory;
    getMap()
        .setRoute(
            new games.strategy.engine.data.Route(path),
            routeStart(),
            mousePoint,
            null);
  }

  /**
   * Request 53: where the route arrow starts: the click that selected the aircraft if it was in
   * their territory, else the territory's centre.
   */
  Point routeStart() {
    return selectedPoint != null && selectedZone != null && selectedZone.equals(selectedPointZone)
        ? selectedPoint
        : draw.center(selectedZone);
  }

  /** The selected aircraft as units (for the stock mouse shadow), undamaged first. */
  private List<Unit> selectedUnits(final GamePlayer player) {
    final IntegerMap<UnitType> left = new IntegerMap<>(selected);
    final List<Unit> result = new ArrayList<>();
    for (final Unit u : myAircraft(player, selectedZone)) {
      if (left.getInt(u.getType()) > 0) {
        left.add(u.getType(), -1);
        result.add(u);
      }
    }
    return result;
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
      }
      // request 42: as in a stock move the selection follows the mouse; the map's stack highlight
      // would light the whole stack even with one of two selected
      getMap().setUnitHighlight(highlight);
      getMap().setMouseShadowUnits(selected.isEmpty() ? null : selectedUnits(player));
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
              + " by clicking their stacks.");
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
              parts.add(describe(player, target) + diceText(player, target));
            }
          });
      if (parts.isEmpty() && isRebasePlace(player, territory)) {
        // request 45: a move with no attack is a rebase
        final Optional<String> why = rebaseWhyNot(player, territory);
        if (why.isPresent()) {
          frame.setStatusWarningMessage(why.get());
        } else {
          frame.setStatusMessage(
              territory.getName()
                  + ": rebase, no attack; they land here at Planes Go Home  ·  "
                  + landingText(player, territory)
                  + "  (click to send them)");
        }
      } else if (parts.isEmpty()) {
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

  /** Request 39: the selected bombers' strike as dice, " · strike 3 + 1, 2 rounds". */
  private String diceText(final GamePlayer player, final Target target) {
    if (target.kind() == TargetKind.COVER || target.kind() == TargetKind.STRATEGIC) {
      return "";
    }
    final List<String> parts = new ArrayList<>();
    for (final UnitType type : selected.keySet()) {
      myAircraft(player, selectedZone).stream()
          .filter(u -> u.getType().equals(type))
          .findFirst()
          .ifPresent(
              u ->
                  parts.add(
                      (selected.size() > 1 ? ResearchView.displayName(type.getName()) + " " : "")
                          + AirCombat.strikeText(u, target.kind())));
    }
    if (parts.isEmpty()) {
      return "";
    }
    return " · strike "
        + String.join(", ", parts)
        + (AirCombat.strikeRounds(target.kind()) > 1 ? ", 2 rounds" : "");
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
      case REBASE -> t.getName() + ": rebase, no attack";
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
      case REBASE -> "Rebase";
    };
  }

  /**
   * Request 44: as in a stock move, aircraft with orders (own and the allies' so far) are drawn
   * only over their target, not on their base; undo puts them back. The aircraft are the ones the
   * game will send (the same choice as at take-off).
   */
  private void showAssigned() {
    final Map<Territory, List<Unit>> byTarget = new LinkedHashMap<>();
    final java.util.Set<Unit> flying = new java.util.HashSet<>();
    final GamePlayer player = getCurrentPlayer();
    try (GameData.Unlocker ignored = getData().acquireReadLock()) {
      final Map<GamePlayer, List<TargetAssignment>> all = new LinkedHashMap<>();
      if (player != null) {
        all.put(player, List.copyOf(staged));
      }
      allied.forEach((p, list) -> all.putIfAbsent(p, list));
      all.forEach(
          (p, list) -> {
            for (final AirBases.Flight f : AirStage.flights(p, stage, list)) {
              if (flying.add(f.plane())) {
                byTarget.computeIfAbsent(f.over(), k -> new ArrayList<>()).add(f.plane());
              }
            }
          });
    }
    ordered = byTarget;
    ordersShown = true;
    if (!overlayAdded) {
      overlayAdded = true;
      getMap().addOverlay(overlay);
    }
    getMap().setUnitsOffMap(flying);
    getMap().repaint();
  }

  /** Request 44: once the stage is over the aircraft are where the game put them. */
  private void clearOrders() {
    ordersShown = false;
    ordered = Map.of();
    if (overlayAdded && !listening) {
      overlayAdded = false;
      getMap().removeOverlay(overlay);
    }
    getMap().setUnitsOffMap(List.of());
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
    return coverMode ? "Fighters — escort and defend" : "Bombers — choose targets";
  }

  private void rebuild(final GamePlayer player) {
    layout.install(this);
    // 1. header: "Germans: Assign Bomber Targets", Undo all, Done
    actionLabel.setText(PlayerNames.display(player) + ": " + stepTitle());
    actionLabel.setToolTipText(
        AirSelection.wrap(
            "As in a normal move: click a stack for one aircraft, Ctrl+click for the stack,"
                + " Shift+click for all of them there, Alt+click for ten; right-click takes one"
                + " off, Escape cancels. Then click "
                + (coverMode
                    ? "a green territory: over your bombers' target to escort, over the enemy's to"
                        + " defend."
                    : "a green target.")
                + " Aircraft without orders stay home. Ctrl+Z undoes the last move."));
    layout.top().add(leftAligned(actionLabel));
    if (finished) {
      layout.top().add(leftAligned(new JLabel(staged.isEmpty() ? "No moves made." : "Moves made:")));
      layout.body().add(movesBox(player, false));
      layout.body().add(Box.createVerticalGlue());
      refresh.run();
      return;
    }
    final JButton undoAll = new JButton("Undo All");
    undoAll.setEnabled(!staged.isEmpty());
    undoAll.addActionListener(
        e -> {
          staged.clear();
          clearSelection();
          selectionChanged();
          strikesChanged();
        });
    // request 53: Undo last beside Undo All; the row wraps rather than widening the panel
    final JButton undoLastButton = new JButton("Undo last");
    undoLastButton.setEnabled(!staged.isEmpty());
    undoLastButton.setToolTipText("Undoes the newest move (Ctrl+Z).");
    undoLastButton.addActionListener(e -> undoLast());
    final JPanel buttons = new JPanel(new org.triplea.swing.WrapLayout(FlowLayout.LEFT, 4, 2));
    buttons.add(undoLastButton);
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
    buttons.setAlignmentX(LEFT_ALIGNMENT);
    layout.top().add(buttons);
    // 2. status line, in plain words; request 48: the one control that goes to them is the unit
    // scroller's bar at the bottom of the side panel (and '.' / ',')
    final int idle = aircraftWithoutOrders(player);
    final JLabel status =
        new JLabel(
            idle == 0
                ? "Every aircraft that can fly has orders: press Done."
                : "<html>Aircraft without orders: <b>" + idle + "</b></html>");
    if (idle > 0) {
      status.setToolTipText(
          "They stay home if you press Done. '.' (or the bar at the bottom of this panel) goes to"
              + " the next of them; ',' goes back.");
    }
    status.setBorder(BorderFactory.createEmptyBorder(2, 5, 2, 0));
    layout.top().add(leftAligned(status));
    // request 48: the how-to, the first time the stage opens, until the first move
    if (staged.size() > helpBaseline) {
      helpShown = false;
    }
    if (helpShown) {
      layout.top().add(AirHelp.text(help()));
    }
    // 3. moves made, always in view, newest first
    layout.top().add(movesBox(player, true));
    // 4. the list, in its own window
    final JButton pick = smallButton("Pick from list…");
    pick.setToolTipText(
        selectedZone == null
            ? "Select aircraft on the map first; this lists every target in reach of them."
            : "Opens a separate window listing every target in reach of the selected aircraft. The"
                + " map stays usable.");
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
    final List<AirMovesList.Entry> newestFirst = new ArrayList<>();
    for (int i = staged.size() - 1; i >= 0; i--) {
      final TargetAssignment a = staged.get(i);
      newestFirst.add(
          new AirMovesList.Entry(
              i + 1,
              player,
              a.unitType(),
              a.count(),
              moveText(player, a),
              () -> showMove(a),
              withUndo ? () -> undo(a) : () -> {}));
    }
    final JComponent items;
    if (newestFirst.isEmpty()) {
      items = leftAligned(new JLabel("No moves yet."));
    } else {
      items = AirMovesList.build(frame.getUiContext(), newestFirst);
    }
    final JComponent list = items;
    // request 47: as tall as its first MOVE_ROWS moves are now (a move is taller when Show and
    // Undo sit under it); more scroll inside it
    final JScrollPane scroll =
        new JScrollPane(items) {
          private static final long serialVersionUID = 1L;

          @Override
          public Dimension getPreferredSize() {
            int h = 0;
            final java.awt.Component[] rows = list.getComponents();
            if (list instanceof JLabel || rows.length == 0) {
              h = list.getPreferredSize().height;
            } else {
              for (int i = 0; i < Math.min(rows.length, MOVE_ROWS); i++) {
                h += rows[i].getPreferredSize().height;
              }
            }
            final java.awt.Insets in = getInsets();
            return new Dimension(200, h + in.top + in.bottom + 4);
          }

          @Override
          public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
          }
        };
    scroll.setBorder(
        BorderFactory.createTitledBorder(
            "Moves made" + (newestFirst.isEmpty() ? "" : " (" + newestFirst.size() + ")")));
    scroll.getVerticalScrollBar().setUnitIncrement(16);
    scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
    // request 44: no wider than the side panel gives it (the list follows its width)
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
          case REBASE -> " (rebase)";
          default -> "";
        };
    final String role;
    if (!coverMode || AirStage.isRebase(a)) {
      role = "";
    } else if (AirCover.sideCount(player, a.target().territory(), false, revealed) > 0) {
      role = " (escort)";
    } else if (enemyBombers(player, a.target().territory()) > 0) {
      role = " (defend)";
    } else {
      role = "";
    }
    // as stock's moves list: "From -> To"
    return a.zone().getName() + " -> " + a.target().territory().getName() + kind + role;
  }

  /**
   * Request 42, the stock "Show" button: cancels any selection, brings the move's target into view
   * (if it isn't) and marks it for a moment; nothing gets selected.
   */
  void showMove(final TargetAssignment a) {
    clearSelection();
    selectionChanged();
    if (!getMap().isShowing(a.target().territory())) {
      getMap().centerOn(a.target().territory());
    }
    if (frame.getAirMapMode() != null) {
      frame.getAirMapMode().flash(a.target().territory());
    }
  }

  /** Request 33: takes one move back; its aircraft are unassigned again. */
  void undo(final TargetAssignment a) {
    if (!staged.remove(a)) {
      return;
    }
    // request 42: an undo never selects anything
    selectionChanged();
    strikesChanged();
  }

  /** Request 33: Ctrl+Z, the newest move back. */
  void undoLast() {
    if (!staged.isEmpty() && !finished) {
      undo(staged.get(staged.size() - 1));
    }
  }

  /** Request 41: how many of the stage's aircraft could fly somewhere and have no order yet. */
  private int aircraftWithoutOrders(final GamePlayer player) {
    int n = 0;
    for (final Group g : waitingGroups(player)) {
      n += available(player, g.zone()).getInt(g.type());
    }
    return n;
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

  /** For tests (request 44): the aircraft with orders, by the territory they are drawn over. */
  Map<Territory, List<Unit>> ordered() {
    return ordered;
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
                // request 42: the window's base, with nothing selected for the player
                if (!zone.equals(selectedZone)) {
                  selected.clear();
                  selectedZone = zone;
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
        final boolean rebase = target.kind() == TargetKind.REBASE;
        if (rebase
            ? !mayRebase(player, zone, type, place)
            : !targetsFor(player, zone, type).contains(target)) {
          skipped += a.count();
          continue;
        }
        final int count =
            Math.min(
                a.count(),
                Math.min(
                    available(player, zone).getInt(type),
                    rebase ? Integer.MAX_VALUE : room(player, zone, place)));
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
    if (!listening && !ordersShown) {
      return;
    }
    final GamePlayer player = getCurrentPlayer();
    try (GameData.Unlocker ignored = data.acquireReadLock()) {
      if (!listening && data.getSequence().getStep() != stageStep) {
        // request 44: the stage is over (take-off); the aircraft are drawn where they are now
        SwingUtilities.invokeLater(
            () -> {
              if (!listening) {
                clearOrders();
              }
            });
        return;
      }
      if (listening && selectedZone != null) {
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
      if (listening && selectedTarget != null) {
        draw.outlineBox(g, selectedTarget.territory(), Color.YELLOW, 4f);
      }
      if (listening && selectedZone != null && !selected.isEmpty() && mousePoint != null) {
        paintPreview(g, player);
      }
      // in the fighter stage the bombers in the air are drawn over their targets by the air map;
      // the fighters' orders go under them
      final int h = frame.getUiContext().getUnitImageFactory().getUnitImageHeight();
      final int below = coverMode ? h / 2 + 22 : 0;
      // request 38 section 12: the allies' choices so far, dashed
      for (final List<TargetAssignment> list : allied.values()) {
        for (final TargetAssignment a : list) {
          final Point end = draw.middle(a.target().territory());
          AirDraw.arrow(
              g, draw.center(a.zone()), new Point(end.x, end.y + below), ALLY_LINE, 2.5f, true, "", 6);
        }
      }
      // own choices: arrow from the base to the target
      for (final TargetAssignment a : staged) {
        final Point end = draw.middle(a.target().territory());
        AirDraw.arrow(
            g, draw.center(a.zone()), new Point(end.x, end.y + below), STRIKE_LINE, 4f, false, "", 6);
      }
      // request 44: the aircraft with orders, stacked over their target as the map stacks units
      for (final Map.Entry<Territory, List<Unit>> e : ordered.entrySet()) {
        final Point end = draw.middle(e.getKey());
        draw.drawStacks(g, new Point(end.x, end.y + below), AirDraw.stacks(e.getValue()), 0.95f);
      }
      // request 30: each place with aircraft shows its side's counts against the caps
      if (player != null) {
        for (final TargetAssignment a : badgedMoves()) {
          final Point end = draw.middle(a.target().territory());
          AirDraw.tag(
              g,
              badge(player, a.zone(), a.target().territory()),
              end.x,
              coverMode ? end.y + below + h / 2 + 14 : end.y - h / 2 - 14,
              new Color(255, 230, 120));
        }
      }
    }
  }

  /**
   * What clicking would do: over a target in reach a green arrow and what is there, over anything
   * else in the zone a red arrow and why not; elsewhere the bombers follow the mouse.
   */
  /**
   * Request 42: beside the stock route figure, what the landing needs ("lands within 6"), or in red
   * why the territory can't be reached, at the mouse.
   */
  private void paintPreview(final Graphics2D g, final GamePlayer player) {
    final Territory place = hoverPlace;
    if (place == null || place.equals(selectedZone)) {
      return;
    }
    final boolean inReach =
        targets.entrySet().stream().anyMatch(e -> e.getValue() && e.getKey().territory().equals(place));
    if (inReach) {
      final String need = landingNeed(player, place);
      if (!need.isEmpty()) {
        AirDraw.tag(g, need, mousePoint.x, mousePoint.y + 52, new Color(200, 225, 255));
      }
      return;
    }
    final String why = whyNotTarget(player, place);
    if (!why.isEmpty()) {
      AirDraw.tag(g, why, mousePoint.x, mousePoint.y + 52, new Color(255, 140, 120));
    }
  }

  /** "lands within 6": the most steps the selected aircraft may have to a landing spot from there. */
  private String landingNeed(final GamePlayer player, final Territory place) {
    int least = Integer.MAX_VALUE;
    for (final Unit u : selectedUnits(player)) {
      final int left = AirBases.rangeLeftAt(u, selectedZone, place).orElse(-1);
      least = Math.min(least, left);
    }
    return least == Integer.MAX_VALUE || least < 0 ? "" : "lands within " + least;
  }

  private void fill(final Graphics2D g, final Territory t, final Color color) {
    g.setColor(color);
    for (final Polygon p : draw.mapData().getPolygons(t)) {
      g.fillPolygon(p);
    }
  }
}
