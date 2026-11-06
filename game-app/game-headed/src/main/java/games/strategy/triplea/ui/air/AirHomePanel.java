package games.strategy.triplea.ui.air;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.triplea.delegate.air.AirHome;
import games.strategy.triplea.delegate.air.AirHome.Group;
import games.strategy.triplea.delegate.air.AirStage;
import games.strategy.triplea.delegate.air.AirStage.Landing;
import games.strategy.triplea.formatter.PlayerNames;
import games.strategy.triplea.image.UnitImageFactory.ImageKey;
import games.strategy.triplea.ui.ActionPanel;
import games.strategy.triplea.ui.DefaultMapSelectionListener;
import games.strategy.triplea.ui.MouseDetails;
import games.strategy.triplea.ui.PinnedLayout;
import games.strategy.triplea.ui.TripleAFrame;
import games.strategy.triplea.ui.panels.map.MapOverlay;
import games.strategy.triplea.ui.panels.map.MapSelectionListener;
import games.strategy.triplea.ui.panels.map.UnitSelectionListener;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Polygon;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

/**
 * Flames and Steel (request 38 section 4): planes go home at the end of the round. Every aircraft
 * in the air is listed by the territory it is over; click a group (or its territory on the map),
 * set how many, then click a green landing spot. Aircraft on bases may rebase the same way with
 * their full range. Groups with nowhere to land are red. Done lands everything not chosen at the
 * nearest spot, after asking if any would be lost.
 */
public final class AirHomePanel extends ActionPanel {
  private static final long serialVersionUID = 1L;
  private static final Color SPOT_FILL = new Color(80, 220, 110, 90);
  private static final Color LOST = new Color(200, 40, 40);

  private final TripleAFrame frame;
  private final AirDraw draw;
  private final PinnedLayout layout = new PinnedLayout();
  private final List<Landing> staged = new ArrayList<>();
  private @Nullable Group selected;
  private int count;
  private Map<Territory, Integer> spots = Map.of();
  private boolean listening;
  private boolean finished;
  // request 44: aircraft with landing orders, by landing spot, drawn there until the step ends
  private volatile Map<Territory, List<Unit>> landed = Map.of();
  private volatile boolean ordersShown;
  private boolean overlayAdded;
  private @Nullable games.strategy.engine.data.GameStep homeStep;
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
          route(territory, md.getMapPoint());
        }
      };

  /** Request 42: the stock route arrow and the selected aircraft at the mouse. */
  private void route(final @Nullable Territory territory, final java.awt.Point mouse) {
    final Group g = selected;
    if (!listening || g == null || territory == null) {
      getMap().setRoute(null);
      getMap().setMouseShadowUnits(null);
      return;
    }
    final List<Territory> path;
    try (GameData.Unlocker ignored = getData().acquireReadLock()) {
      path =
          games.strategy.triplea.delegate.air.AirBases.path(
                  getCurrentPlayer(), g.from(), territory, 60)
              .orElse(List.of(g.from(), territory));
    }
    // request 59: the label's second figure is the range the group has left (as the panel says),
    // red when the territory is not a landing spot in reach
    final boolean overLimit =
        path.size() - 1 > g.range()
            || (!territory.equals(g.from()) && !spots.containsKey(territory));
    getMap()
        .setMouseShadowUnits(
            g.units().subList(0, Math.min(count, g.units().size())), g.range(), overLimit);
    getMap()
        .setRoute(new games.strategy.engine.data.Route(path), draw.middle(g.from()), mouse, null);
  }

  public AirHomePanel(final TripleAFrame frame) {
    super(frame);
    this.frame = frame;
    this.draw = new AirDraw(frame.getUiContext());
    // request 40: Escape clears the selection
    getInputMap(WHEN_IN_FOCUSED_WINDOW)
        .put(
            javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0),
            "fsHomeClear");
    getActionMap()
        .put(
            "fsHomeClear",
            new javax.swing.AbstractAction() {
              private static final long serialVersionUID = 1L;

              @Override
              public void actionPerformed(final java.awt.event.ActionEvent e) {
                if (listening && isActive()) {
                  selected = null;
                  spots = Map.of();
                  rebuild(getCurrentPlayer());
                  getMap().repaint();
                }
              }
            });
  }

  @Override
  public String toString() {
    return "AirHomePanel";
  }

  // request 47: the stock unit scroller (its buttons and '.', ',' and 'S') goes through the
  // aircraft in the air still to land; the place last gone to; whether the how-to shows
  private volatile int toLand;
  private final games.strategy.triplea.ui.unit.scroller.UnitScroller.Source scrollerSource =
      new games.strategy.triplea.ui.unit.scroller.UnitScroller.Source() {
        @Override
        public String title() {
          return "Aircraft to land: " + toLand;
        }

        @Override
        public void next(final boolean forward) {
          SwingUtilities.invokeLater(() -> nextToLand(forward));
        }
      };
  private @Nullable Territory lastVisited;
  private boolean helpShown;
  private int helpBaseline;

  private static final String HELP =
      "Click aircraft in the air (or a group in this panel), then a green spot: your land or one of"
          + " your carriers with room, within the range they have left. Aircraft on bases may"
          + " rebase with their full range. Done lands the rest at the nearest spot; any with"
          + " nowhere to land are lost. '.' and ',' (or the unit scroller's arrows) go to the next"
          + " and previous aircraft still to land.";

  /**
   * Request 47: the airborne groups still to land, one per place and type. Request 53: aircraft
   * that can land where they are (own land, or own carriers with room) count as landed.
   */
  private List<Group> stillToLand(final GamePlayer player) {
    final Map<String, Integer> inPlace = inPlace(player);
    final Map<String, Group> result = new LinkedHashMap<>();
    for (final Group g : groups(player, true)) {
      if (toLand(g, inPlace) > 0) {
        result.putIfAbsent(groupKey(g), g);
      }
    }
    return new ArrayList<>(result.values());
  }

  private int countToLand(final GamePlayer player) {
    final Map<String, Integer> inPlace = inPlace(player);
    return stillToLand(player).stream().mapToInt(g -> toLand(g, inPlace)).sum();
  }

  private static String groupKey(final Group g) {
    return g.from().getName() + "|" + g.type().getName();
  }

  /** How many of the group still need a landing: those left less those landing where they are. */
  private int toLand(final Group g, final Map<String, Integer> inPlace) {
    return Math.max(0, left(g) - inPlace.getOrDefault(groupKey(g), 0));
  }

  /**
   * Request 53: by place and type, how many airborne aircraft Done would land right where they are
   * (the zero-step spot is the first a default landing tries).
   */
  private Map<String, Integer> inPlace(final GamePlayer player) {
    final Map<String, Integer> result = new LinkedHashMap<>();
    try (GameData.Unlocker ignored = getData().acquireReadLock()) {
      for (final Landing l : AirHome.defaultLandings(player, staged)) {
        if (AirHome.landsInPlace(l)) {
          result.merge(l.from().getName() + "|" + l.unitType().getName(), l.count(), Integer::sum);
        }
      }
    }
    return result;
  }

  /** Request 47: "Germans: <b>2</b> aircraft must land", or "Germans: all aircraft have landed". */
  AirMapMode.Banner banner(final GamePlayer player) {
    final int n = countToLand(player);
    final String who = PlayerNames.display(player) + ": ";
    return n == 0
        ? new AirMapMode.Banner(who, "all", " aircraft have landing orders", HELP)
        : new AirMapMode.Banner(who, String.valueOf(n), " aircraft must land", HELP);
  }

  private void updateBanner(final @Nullable GamePlayer player) {
    toLand = player == null || finished ? 0 : countToLand(player);
    frame.refreshAircraftScroller();
    if (frame.getAirMapMode() != null) {
      frame.getAirMapMode().setBanner(player == null || finished ? null : banner(player));
    }
  }

  /**
   * Request 47: goes to the next (or previous) place with aircraft in the air still to land:
   * centers the map on it, flashes it and selects one, as a plain click would.
   */
  void nextToLand(final boolean forward) {
    if (!isActive() || !listening || finished) {
      return;
    }
    final GamePlayer player = getCurrentPlayer();
    final List<Territory> places =
        stillToLand(player).stream().map(Group::from).distinct().toList();
    if (places.isEmpty()) {
      frame.setStatusMessage("Every aircraft in the air has a landing: press Done.");
      return;
    }
    final int at = lastVisited == null ? -1 : places.indexOf(lastVisited);
    final int i =
        at < 0
            ? (forward ? 0 : places.size() - 1)
            : Math.floorMod(at + (forward ? 1 : -1), places.size());
    final Territory place = places.get(i);
    lastVisited = place;
    final Group g =
        stillToLand(player).stream().filter(x -> x.from().equals(place)).findFirst().orElseThrow();
    getMap().centerOn(place);
    if (frame.getAirMapMode() != null) {
      frame.getAirMapMode().flash(place);
    }
    select(player, g, 1);
  }

  /** Shows the nation's screen, starting from these landings. */
  public void start(final GamePlayer player, final List<Landing> start) {
    SwingUtilities.invokeLater(
        () -> {
          finished = false;
          staged.clear();
          staged.addAll(start);
          lastVisited = null;
          helpShown = AirHelp.showAsStageOpens(getData(), player, "HOME");
          helpBaseline = staged.size();
          frame.setAircraftScroller(scrollerSource);
          try (GameData.Unlocker ignored = getData().acquireReadLock()) {
            homeStep = getData().getSequence().getStep();
          }
          selected = null;
          spots = Map.of();
          if (!listening) {
            listening = true;
            getMap().addUnitSelectionListener(unitListener);
            getMap().addMapSelectionListener(hoverListener);
            if (!overlayAdded) {
              overlayAdded = true;
              getMap().addOverlay(overlay);
            }
          }
          showLanded();
          rebuild(player);
          // request 40: nothing is selected for the player; request 47: the banner says what waits
          frame.clearStatusMessage();
        });
  }

  /** Blocks until Done; returns the landings chosen (the host lands the rest by default). */
  public List<Landing> waitForLandings() {
    waitForRelease();
    final List<Landing> result = List.copyOf(staged);
    SwingUtilities.invokeLater(
        () -> {
          finished = true;
          if (listening) {
            listening = false;
            getMap().removeUnitSelectionListener(unitListener);
            getMap().removeMapSelectionListener(hoverListener);
            // request 44: the landings stay drawn (and off their bases) until the step ends
            if (!ordersShown && overlayAdded) {
              overlayAdded = false;
              getMap().removeOverlay(overlay);
            }
            getMap().setRoute(null);
            getMap().setMouseShadowUnits(null);
          }
          frame.clearStatusMessage();
          frame.setAircraftScroller(null);
          rebuild(getCurrentPlayer());
          getMap().repaint();
        });
    return result;
  }

  @Override
  public void performDone() {
    final List<String> lines;
    try (GameData.Unlocker ignored = getData().acquireReadLock()) {
      lines = doneWarnings(getCurrentPlayer());
    }
    // request 53: asked only when aircraft will land somewhere other than where they are, or be
    // lost; each line says which, and where they will land
    if (!lines.isEmpty()) {
      final int answer =
          JOptionPane.showConfirmDialog(
              JOptionPane.getFrameForComponent(this),
              "Aircraft without a landing order:\n" + String.join("\n", lines) + "\nEnd anyway?",
              "Planes go home",
              JOptionPane.YES_NO_OPTION);
      if (answer != JOptionPane.YES_OPTION) {
        return;
      }
    }
    release();
  }

  /**
   * Request 53: Done's warning, one line per kind of aircraft that will not land where it is: "2
   * dive bombers over Hanchung → Sian", "1 fighter over Sea Zone 40 is lost (no carrier room)".
   * Aircraft landing where they are, and those with landing orders, are not listed. Call under the
   * read lock.
   */
  List<String> doneWarnings(final GamePlayer player) {
    final List<String> lines = new ArrayList<>();
    for (final Landing l : AirHome.defaultLandings(player, staged)) {
      if (!l.airborne() || AirHome.landsInPlace(l) || l.count() <= 0) {
        continue;
      }
      lines.add(
          l.count()
              + " "
              + plural(l.unitType(), l.count())
              + " over "
              + l.from().getName()
              + " → "
              + l.to().getName());
    }
    final List<Landing> all = AirHome.withDefaults(player, staged);
    final Map<String, Integer> lost = new LinkedHashMap<>();
    for (final Unit u : AirHome.leftInTheAir(player, all)) {
      final Territory over = u.getFlightOver();
      final boolean nowhere =
          over == null
              || AirHome.spots(
                      player,
                      new Group(over, true, u.getType(), u.getFlightRangeLeft(), List.of(u)))
                  .isEmpty();
      final String where = over == null ? "in the air" : "over " + over.getName();
      lost.merge(
          u.getType().getName()
              + "|"
              + where
              + "|"
              + (nowhere ? "nowhere to land in reach" : "no carrier room in reach"),
          1,
          Integer::sum);
    }
    lost.forEach(
        (key, n) -> {
          final String[] p = key.split("\\|", 3);
          lines.add(
              n
                  + " "
                  + plural(getData().getUnitTypeList().getUnitTypeOrThrow(p[0]), n)
                  + " "
                  + p[1]
                  + (n == 1 ? " is" : " are")
                  + " lost ("
                  + p[2]
                  + ")");
        });
    return lines;
  }

  private static String plural(final UnitType type, final int n) {
    return games.strategy.triplea.formatter.MyFormatter.pluralize(
        AirStage.typeName(type).toLowerCase(java.util.Locale.ROOT), n);
  }

  // ---- groups

  /** The nation's groups: those in the air first, then (unless only those) those on bases. */
  private List<Group> groups(final GamePlayer player, final boolean airborneOnly) {
    final List<Group> result = new ArrayList<>();
    try (GameData.Unlocker ignored = getData().acquireReadLock()) {
      result.addAll(AirHome.airborneGroups(player));
      if (!airborneOnly) {
        result.addAll(AirHome.basedGroups(player));
      }
    }
    return result;
  }

  /** How many of the group are still to land (or may still rebase). */
  private int left(final Group g) {
    final int chosen =
        staged.stream()
            .filter(
                l ->
                    l.from().equals(g.from())
                        && l.airborne() == g.airborne()
                        && l.unitType().equals(g.type()))
            .mapToInt(Landing::count)
            .sum();
    return Math.max(0, unitsLike(g) - chosen);
  }

  /** All the nation's aircraft of the group's kind at its place (all ranges together). */
  private int unitsLike(final Group g) {
    return (int)
        groups(getCurrentPlayer(), false).stream()
            .filter(
                x ->
                    x.from().equals(g.from())
                        && x.airborne() == g.airborne()
                        && x.type().equals(g.type()))
            .mapToInt(x -> x.units().size())
            .sum();
  }

  private void choose(final GamePlayer player, final Group g) {
    select(player, g, left(g));
  }

  /**
   * Request 41: clicks as in a stock move. On aircraft (on a base, or the territory aircraft in the
   * air are over): a click adds one, Alt+click ten, Ctrl+ or Shift+click all of them; a click on a
   * green spot lands the selection there. Right-click takes one off (Ctrl: all), Escape cancels.
   */
  private void clicked(
      final List<Unit> units, final @Nullable Territory territory, final MouseDetails md) {
    if (!isActive() || !listening || territory == null || finished) {
      return;
    }
    final GamePlayer player = getCurrentPlayer();
    if (md.isRightButton()) {
      if (selected != null
          && selected.from().equals(territory)
          && count > 1
          && !md.isControlDown()) {
        count -= md.isAltDown() ? Math.min(count - 1, 10) : 1;
      } else {
        selected = null;
        spots = Map.of();
      }
      rebuild(player);
      getMap().repaint();
      return;
    }
    if (selected != null && !selected.from().equals(territory) && spots.containsKey(territory)) {
      land(player, territory);
      return;
    }
    // the clicked stack's type on a base, else the aircraft in the air over the territory
    final Optional<UnitType> clickedType =
        units.stream()
            .filter(
                u ->
                    u.isOwnedBy(player)
                        && games.strategy.triplea.delegate.air.AirBases.isAircraft(u))
            .map(Unit::getType)
            .findFirst();
    Group pick = null;
    for (final Group g : groups(player, false)) {
      if (!g.from().equals(territory) || left(g) <= 0) {
        continue;
      }
      if (clickedType.isPresent()
          ? !g.airborne() && g.type().equals(clickedType.get())
          : g.airborne()) {
        pick = g;
        break;
      }
    }
    if (pick == null) {
      if (selected != null) {
        frame.setStatusErrorMessage(
            territory.getName() + " is no landing spot in reach for these aircraft.");
      }
      return;
    }
    final boolean same = pick.equals(selected);
    final int add = md.isControlDown() || md.isShiftDown() ? left(pick) : md.isAltDown() ? 10 : 1;
    select(player, pick, Math.min(left(pick), (same ? count : 0) + add));
  }

  private void select(final GamePlayer player, final Group g, final int n) {
    selected = g;
    count = n;
    try (GameData.Unlocker ignored = getData().acquireReadLock()) {
      spots = AirHome.spots(player, g);
    }
    frame.setStatusMessage(
        spots.isEmpty()
            ? "Nowhere to land within " + g.range() + " of " + g.from().getName() + "."
            : n
                + " selected: click a green spot to land them ("
                + spots.size()
                + " in reach). Escape cancels.");
    rebuild(player);
    getMap().repaint();
  }

  private void land(final GamePlayer player, final Territory to) {
    final Group g = selected;
    final int n = Math.min(count, left(g));
    if (n <= 0) {
      return;
    }
    final List<Landing> trial = new ArrayList<>(staged);
    trial.add(new Landing(g.from(), g.airborne(), g.type(), n, to));
    final Optional<String> problem;
    try (GameData.Unlocker ignored = getData().acquireReadLock()) {
      problem = AirHome.problem(player, AirHome.merge(trial));
    }
    if (problem.isPresent()) {
      frame.setStatusErrorMessage(problem.get());
      return;
    }
    staged.clear();
    staged.addAll(AirHome.merge(trial));
    showLanded();
    // request 40: after a landing nothing is selected and the map stays where it is
    selected = null;
    spots = Map.of();
    rebuild(player);
    getMap().repaint();
  }

  private void hovered(final @Nullable Territory territory) {
    if (!listening || selected == null || territory == null) {
      return;
    }
    final Integer steps = spots.get(territory);
    if (steps == null) {
      frame.setStatusWarningMessage(territory.getName() + ": not a landing spot in reach.");
    } else {
      frame.setStatusMessage(
          territory.getName()
              + ": "
              + steps
              + (steps == 1 ? " step" : " steps")
              + (territory.isWater() ? " (your carrier)" : "")
              + ", "
              + (selected.range() - steps)
              + " left. Click to land.");
    }
  }

  // ---- the panel

  private void rebuild(final @Nullable GamePlayer player) {
    layout.install(this);
    updateBanner(player);
    if (player == null) {
      refresh.run();
      return;
    }
    actionLabel.setText(PlayerNames.display(player) + ": Planes Go Home");
    actionLabel.setToolTipText(AirSelection.wrap(HELP));
    layout.top().add(leftAligned(actionLabel));
    if (finished) {
      layout.top().add(leftAligned(new JLabel("Landings sent.")));
      refresh.run();
      return;
    }
    final JButton undoAll = new JButton("Undo All");
    undoAll.setEnabled(!staged.isEmpty());
    undoAll.addActionListener(
        e -> {
          staged.clear();
          showLanded();
          rebuild(player);
          getMap().repaint();
        });
    // request 53: Undo last beside Undo All; the row wraps rather than widening the panel
    final JButton undoLast = new JButton("Undo last");
    undoLast.setEnabled(!staged.isEmpty());
    undoLast.setToolTipText("Undoes the newest landing.");
    undoLast.addActionListener(
        e -> {
          if (!staged.isEmpty()) {
            staged.remove(staged.size() - 1);
            showLanded();
            rebuild(player);
            getMap().repaint();
          }
        });
    final JPanel buttons = new JPanel(new org.triplea.swing.WrapLayout(FlowLayout.LEFT, 4, 2));
    buttons.add(undoLast);
    buttons.add(undoAll);
    buttons.add(createDoneButton());
    buttons.setAlignmentX(LEFT_ALIGNMENT);
    layout.top().add(buttons);
    // request 47: what still has to land; request 48: the unit scroller's bar at the bottom of the
    // side panel (and '.' / ',') goes to them
    final int n = toLand;
    final JLabel still =
        new JLabel(
            n == 0
                ? "Every aircraft in the air has a landing."
                : "<html>Aircraft to land: <b>" + n + "</b></html>");
    if (n > 0) {
      still.setToolTipText(
          "Done lands them at the nearest spot. '.' (or the bar at the bottom of this panel) goes"
              + " to the next of them; ',' goes back.");
    }
    layout.top().add(leftAligned(still));
    // request 48: the how-to, the first time, until the first landing is chosen
    if (staged.size() > helpBaseline) {
      helpShown = false;
    }
    if (helpShown) {
      layout.top().add(AirHelp.text(HELP));
    }
    // landings made, newest first, laid out as stock's moves list (request 42)
    final List<AirMovesList.Entry> entries = new ArrayList<>();
    for (int i = staged.size() - 1; i >= 0; i--) {
      final Landing l = staged.get(i);
      entries.add(
          new AirMovesList.Entry(
              i + 1,
              player,
              l.unitType(),
              l.count(),
              l.from().getName()
                  + " -> "
                  + l.to().getName()
                  // request 45: a rebase ordered in an air stage, landed here by default
                  + (!l.airborne()
                      ? " (rebase)"
                      : AirHome.isRebaseLanding(player, l) ? " (rebase order)" : ""),
              () -> {
                selected = null;
                spots = Map.of();
                if (!getMap().isShowing(l.to())) {
                  getMap().centerOn(l.to());
                }
                rebuild(player);
                getMap().repaint();
              },
              () -> {
                staged.remove(l);
                showLanded();
                rebuild(player);
                getMap().repaint();
              }));
    }
    // request 53: no wider than the panel (a long line is cut, with a tooltip), never scrolls
    // sideways
    final javax.swing.JComponent made =
        AirMovesList.boxed(
            entries.isEmpty()
                ? new JLabel("No landings yet.")
                : AirMovesList.build(frame.getUiContext(), entries),
            "Landings made (" + staged.size() + ")");
    layout.top().add(made);
    // the groups
    addGroups(player, true, "In the air: land them");
    addGroups(player, false, "On bases: may rebase");
    layout.body().add(Box.createVerticalGlue());
    refresh.run();
  }

  private void addGroups(final GamePlayer player, final boolean airborne, final String title) {
    final JPanel box = new JPanel();
    box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
    box.setBorder(BorderFactory.createTitledBorder(title));
    box.setAlignmentX(LEFT_ALIGNMENT);
    final Map<String, Group> shown = new LinkedHashMap<>();
    for (final Group g : groups(player, false)) {
      // request 53: never a line for 0 aircraft (all of them have orders)
      if (g.airborne() == airborne && left(g) > 0) {
        shown.putIfAbsent(g.from().getName() + "|" + g.type().getName(), g);
      }
    }
    if (shown.isEmpty()) {
      box.add(
          leftAligned(
              new JLabel(
                  airborne ? "None in the air without orders." : "None on bases to rebase.")));
    }
    final Map<String, Integer> inPlace = airborne ? inPlace(player) : Map.of();
    for (final Group g : shown.values()) {
      final int left = left(g);
      final int here = Math.min(left, inPlace.getOrDefault(groupKey(g), 0));
      final Map<Territory, Integer> reach;
      try (GameData.Unlocker ignored = getData().acquireReadLock()) {
        reach = AirHome.spots(player, g);
      }
      // request 53: two short lines ("7 torpedo bombers — land here" / "over Sea Zone 225 ...
      // (3 left)"), cut at the panel's edge with the whole text in the tooltip; the button stays
      // in sight at the right and the list never scrolls sideways
      final String what =
          left
              + " "
              + plural(g.type(), left)
              + (here <= 0 ? "" : here == left ? " — land here" : " — " + here + " land here");
      final String where =
          (airborne ? "over " : "at ")
              + g.from().getName()
              + (airborne ? " (" + g.range() + " left)" : "");
      final JLabel line1 = new JLabel(what);
      final JLabel line2 = new JLabel(where);
      final JPanel text = new JPanel(new java.awt.GridLayout(2, 1));
      text.setOpaque(false);
      text.add(line1);
      text.add(line2);
      String tip = what + " " + where;
      if (here > 0) {
        tip +=
            ". They can land where they are (your land, or your carriers with room): Done lands"
                + " them here. You may still send them elsewhere.";
      }
      if (airborne && left > 0 && reach.isEmpty()) {
        line1.setForeground(LOST);
        line2.setForeground(LOST);
        tip += ". Nowhere to land in reach: lost at Done.";
      }
      for (final JLabel l : List.of(line1, line2)) {
        l.setToolTipText(tip);
        l.setMinimumSize(new java.awt.Dimension(0, l.getPreferredSize().height));
      }
      final JPanel row =
          new JPanel(new BorderLayout(4, 0)) {
            private static final long serialVersionUID = 1L;

            @Override
            public java.awt.Dimension getPreferredSize() {
              // never asks for more width than a narrow side panel has; the text is cut instead
              final java.awt.Dimension d = super.getPreferredSize();
              return new java.awt.Dimension(Math.min(d.width, 220), d.height);
            }

            @Override
            public java.awt.Dimension getMaximumSize() {
              return new java.awt.Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
          };
      row.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
      row.add(new JLabel(icon(player, g)), BorderLayout.WEST);
      row.add(text, BorderLayout.CENTER);
      if (left > 0 && !reach.isEmpty()) {
        if (g.equals(selected)) {
          row.add(new JLabel("selected: " + count), BorderLayout.EAST);
        } else {
          final JButton pick = smallButton("Select all");
          pick.setToolTipText("Selects all of them, as Shift+click on the map does.");
          pick.addActionListener(e -> choose(player, g));
          final JPanel east = new JPanel(new java.awt.GridBagLayout());
          east.setOpaque(false);
          east.add(pick);
          row.add(east, BorderLayout.EAST);
        }
      }
      row.setAlignmentX(LEFT_ALIGNMENT);
      box.add(row);
    }
    layout.body().add(box);
  }

  private Icon icon(final GamePlayer player, final Group g) {
    return frame
        .getUiContext()
        .getUnitImageFactory()
        .getIcon(ImageKey.builder().player(player).type(g.type()).build());
  }

  private static JButton smallButton(final String text) {
    final JButton b = new JButton(text);
    b.setMargin(new Insets(0, 4, 0, 4));
    return b;
  }

  private static JPanel leftAligned(final Component c) {
    final JPanel p = new JPanel(new BorderLayout());
    p.add(c, BorderLayout.WEST);
    p.setAlignmentX(LEFT_ALIGNMENT);
    return p;
  }

  // ---- the map

  /**
   * Request 44: as in a stock move, aircraft with a landing order are drawn only at their landing
   * spot (not in the air or on their old base); undo puts them back.
   */
  private void showLanded() {
    final Map<Territory, List<Unit>> byPlace = new LinkedHashMap<>();
    final java.util.Set<Unit> moved = new java.util.HashSet<>();
    final GamePlayer player = getCurrentPlayer();
    if (player != null) {
      try (GameData.Unlocker ignored = getData().acquireReadLock()) {
        AirHome.unitsFor(player, staged)
            .forEach(
                (l, units) -> {
                  if (l.airborne() || !l.from().equals(l.to())) {
                    moved.addAll(units);
                    byPlace.computeIfAbsent(l.to(), k -> new ArrayList<>()).addAll(units);
                  }
                });
      }
    }
    landed = byPlace;
    ordersShown = !moved.isEmpty();
    getMap().setUnitsOffMap(moved);
  }

  private void clearLanded() {
    ordersShown = false;
    landed = Map.of();
    if (overlayAdded && !listening) {
      overlayAdded = false;
      getMap().removeOverlay(overlay);
    }
    getMap().setUnitsOffMap(List.of());
  }

  private void paintOverlay(final Graphics2D g, final GameData data) {
    if (!listening && !ordersShown) {
      return;
    }
    try (GameData.Unlocker ignored = data.acquireReadLock()) {
      if (!listening && data.getSequence().getStep() != homeStep) {
        // request 44: the landings are done; the aircraft are drawn where they are now
        SwingUtilities.invokeLater(
            () -> {
              if (!listening) {
                clearLanded();
              }
            });
        return;
      }
      final Group sel = selected;
      if (listening && sel != null) {
        for (final Territory t : spots.keySet()) {
          g.setColor(SPOT_FILL);
          for (final Polygon p : draw.mapData().getPolygons(t)) {
            g.fillPolygon(p);
          }
          draw.outlineBox(g, t, AirDraw.LEGAL_LINE, 3f);
        }
        draw.outlineBox(g, sel.from(), AirDraw.ORIGIN_LINE, 5f);
      }
      for (final Landing l : staged) {
        AirDraw.arrow(
            g, draw.middle(l.from()), draw.middle(l.to()), AirDraw.LEGAL_LINE, 3f, false, "", 6);
      }
      // the landed aircraft, stacked at their spot as the map stacks units
      for (final Map.Entry<Territory, List<Unit>> e : landed.entrySet()) {
        draw.drawStacks(g, draw.middle(e.getKey()), AirDraw.stacks(e.getValue()), 0.95f);
      }
    }
  }
}
