package games.strategy.triplea.ui.air;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
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
import java.awt.Point;
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
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
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
  private final MapOverlay overlay = this::paintOverlay;
  private final UnitSelectionListener unitListener =
      (units, territory, md) -> SwingUtilities.invokeLater(() -> clicked(territory, md));
  private final MapSelectionListener hoverListener =
      new DefaultMapSelectionListener() {
        @Override
        public void mouseEntered(final @Nullable Territory territory) {
          hovered(territory);
        }
      };

  public AirHomePanel(final TripleAFrame frame) {
    super(frame);
    this.frame = frame;
    this.draw = new AirDraw(frame.getUiContext());
    // request 40: Escape clears the selection
    getInputMap(WHEN_IN_FOCUSED_WINDOW)
        .put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0), "fsHomeClear");
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

  /** Shows the nation's screen, starting from these landings. */
  public void start(final GamePlayer player, final List<Landing> start) {
    SwingUtilities.invokeLater(
        () -> {
          finished = false;
          staged.clear();
          staged.addAll(start);
          selected = null;
          spots = Map.of();
          if (!listening) {
            listening = true;
            getMap().addUnitSelectionListener(unitListener);
            getMap().addMapSelectionListener(hoverListener);
            getMap().addOverlay(overlay);
          }
          rebuild(player);
          // request 40: nothing is selected for the player
          frame.setStatusMessage(
              "Click aircraft in the air (or a group on the right), then a green spot. Done lands"
                  + " the rest at the nearest spot.");
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
            getMap().removeOverlay(overlay);
          }
          frame.clearStatusMessage();
          rebuild(getCurrentPlayer());
          getMap().repaint();
        });
    return result;
  }

  @Override
  public void performDone() {
    final GamePlayer player = getCurrentPlayer();
    final List<Landing> all;
    final List<Unit> lost;
    final int defaulted;
    try (GameData.Unlocker ignored = getData().acquireReadLock()) {
      all = AirHome.withDefaults(player, staged);
      lost = AirHome.leftInTheAir(player, all);
      defaulted =
          all.stream().filter(Landing::airborne).mapToInt(Landing::count).sum()
              - staged.stream().filter(Landing::airborne).mapToInt(Landing::count).sum();
    }
    final List<String> lines = new ArrayList<>();
    if (defaulted > 0) {
      lines.add(defaulted + " aircraft still in the air will land at the nearest spot.");
    }
    if (!lost.isEmpty()) {
      lines.add(
          lost.size()
              + " aircraft have nowhere to land and will be lost: "
              + games.strategy.triplea.formatter.MyFormatter.unitsToTextNoOwner(lost)
              + ".");
    }
    if (!lines.isEmpty()) {
      final int answer =
          JOptionPane.showConfirmDialog(
              JOptionPane.getFrameForComponent(this),
              String.join("\n", lines) + "\nEnd anyway?",
              "Planes go home",
              JOptionPane.YES_NO_OPTION);
      if (answer != JOptionPane.YES_OPTION) {
        return;
      }
    }
    release();
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
            .filter(x -> x.from().equals(g.from()) && x.airborne() == g.airborne() && x.type().equals(g.type()))
            .mapToInt(x -> x.units().size())
            .sum();
  }

  private void choose(final GamePlayer player, final Group g) {
    selected = g;
    count = left(g);
    try (GameData.Unlocker ignored = getData().acquireReadLock()) {
      spots = AirHome.spots(player, g);
    }
    frame.setStatusMessage(
        spots.isEmpty()
            ? "Nowhere to land within " + g.range() + " of " + g.from().getName() + "."
            : "Click a green spot to land them (" + spots.size() + " in reach).");
    rebuild(player);
    getMap().repaint();
  }

  private void clicked(final @Nullable Territory territory, final MouseDetails md) {
    if (!isActive() || !listening || territory == null || finished) {
      return;
    }
    final GamePlayer player = getCurrentPlayer();
    if (md.isRightButton()) {
      selected = null;
      spots = Map.of();
      rebuild(player);
      getMap().repaint();
      return;
    }
    if (selected != null && spots.containsKey(territory) && count > 0) {
      land(player, territory);
      return;
    }
    // a click on a place with the nation's aircraft (in the air over it first) picks them
    for (final Group g : groups(player, false)) {
      if (g.from().equals(territory) && left(g) > 0) {
        choose(player, g);
        return;
      }
    }
    if (selected != null) {
      frame.setStatusErrorMessage(
          territory.getName() + " is no landing spot in reach for these aircraft.");
    }
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
    if (player == null) {
      refresh.run();
      return;
    }
    actionLabel.setText(PlayerNames.display(player) + ": Planes Go Home");
    actionLabel.setToolTipText(
        "Each aircraft in the air lands within the range it has left, on your land or one of your"
            + " carriers with room. Aircraft on bases may rebase with their full range. Done lands"
            + " the rest at the nearest spot; any with nowhere to land are lost.");
    layout.top().add(leftAligned(actionLabel));
    if (finished) {
      layout.top().add(leftAligned(new JLabel("Landings sent.")));
      refresh.run();
      return;
    }
    final JButton undoAll = new JButton("Undo all");
    undoAll.setEnabled(!staged.isEmpty());
    undoAll.addActionListener(
        e -> {
          staged.clear();
          rebuild(player);
          getMap().repaint();
        });
    final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
    buttons.add(undoAll);
    buttons.add(createDoneButton());
    layout.top().add(leftAligned(buttons));
    // landings made, newest first
    final JPanel made = new JPanel();
    made.setLayout(new BoxLayout(made, BoxLayout.Y_AXIS));
    made.setBorder(BorderFactory.createTitledBorder("Landings made (" + staged.size() + ")"));
    for (int i = staged.size() - 1; i >= 0; i--) {
      final Landing l = staged.get(i);
      final JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
      row.add(
          new JLabel(
              l.to().getName()
                  + " ← "
                  + l.count()
                  + " "
                  + AirStage.typeName(l.unitType()).toLowerCase(java.util.Locale.ROOT)
                  + (l.airborne() ? " from over " : " rebase from ")
                  + l.from().getName()));
      final JButton undo = smallButton("Undo");
      undo.addActionListener(
          e -> {
            staged.remove(l);
            rebuild(player);
            getMap().repaint();
          });
      row.add(undo);
      made.add(leftAligned(row));
    }
    made.setAlignmentX(LEFT_ALIGNMENT);
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
      if (g.airborne() == airborne) {
        shown.putIfAbsent(g.from().getName() + "|" + g.type().getName(), g);
      }
    }
    if (shown.isEmpty()) {
      box.add(leftAligned(new JLabel(airborne ? "None in the air." : "None on bases.")));
    }
    for (final Group g : shown.values()) {
      final int left = left(g);
      final Map<Territory, Integer> reach;
      try (GameData.Unlocker ignored = getData().acquireReadLock()) {
        reach = AirHome.spots(player, g);
      }
      final JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
      final JLabel label =
          new JLabel(
              left
                  + " "
                  + AirStage.typeName(g.type()).toLowerCase(java.util.Locale.ROOT)
                  + (airborne ? " over " : " at ")
                  + g.from().getName()
                  + (airborne ? " (" + g.range() + " left)" : ""),
              icon(player, g),
              JLabel.LEFT);
      if (airborne && left > 0 && reach.isEmpty()) {
        label.setForeground(LOST);
        label.setToolTipText("Nowhere to land in reach: lost at Done.");
      }
      row.add(label);
      if (left > 0 && !reach.isEmpty()) {
        final JButton pick = smallButton(g.equals(selected) ? "Chosen" : "Choose");
        pick.addActionListener(e -> choose(player, g));
        row.add(pick);
        if (g.equals(selected)) {
          final JSpinner spinner = new JSpinner(new SpinnerNumberModel(Math.min(count, left), 1, left, 1));
          spinner.addChangeListener(e -> count = (Integer) spinner.getValue());
          row.add(new JLabel("land:"));
          row.add(spinner);
        }
      }
      box.add(leftAligned(row));
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

  private void paintOverlay(final Graphics2D g, final GameData data) {
    if (!listening) {
      return;
    }
    final GamePlayer player = getCurrentPlayer();
    try (GameData.Unlocker ignored = data.acquireReadLock()) {
      final Group sel = selected;
      if (sel != null) {
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
        final Point to = draw.middle(l.to());
        AirDraw.arrow(g, draw.middle(l.from()), to, AirDraw.LEGAL_LINE, 3f, false, "", 6);
        draw.iconAt(g, new Point(to.x - 16, to.y - 16), player, l.unitType(), l.count());
      }
    }
  }
}
