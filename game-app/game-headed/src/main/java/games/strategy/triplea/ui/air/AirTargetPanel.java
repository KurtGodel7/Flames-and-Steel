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
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
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
  private final MapOverlay overlay = this::paintOverlay;
  private final UnitSelectionListener unitListener =
      (units, territory, md) -> SwingUtilities.invokeLater(() -> clicked(units, territory, md));
  private final MapSelectionListener hoverListener =
      new DefaultMapSelectionListener() {
        @Override
        public void mouseEntered(final @Nullable Territory territory) {
          hovered(territory);
        }
      };

  public AirTargetPanel(final TripleAFrame frame) {
    super(frame);
    this.frame = frame;
    this.draw = new AirDraw(frame.getUiContext());
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
      final List<Unit> units, final @Nullable Territory territory, final MouseDetails md) {
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
      selectBombers(player, territory, units, md.isShiftDown());
      return;
    }
    if (selectedZone == null || selected.isEmpty()) {
      return;
    }
    final List<Target> here =
        targets.entrySet().stream()
            .filter(e -> e.getValue() && e.getKey().territory().equals(territory))
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
      final GamePlayer player, final Territory zone, final List<Unit> units, final boolean all) {
    final IntegerMap<UnitType> available = available(player, zone);
    final List<UnitType> types = new ArrayList<>();
    if (all) {
      types.addAll(available.keySet());
    } else {
      units.stream()
          .filter(u -> u.isOwnedBy(player) && AirCombat.isBomber(u))
          .map(Unit::getType)
          .distinct()
          .forEach(types::add);
    }
    if (types.isEmpty()) {
      if (zone.equals(selectedZone)) {
        clearSelection();
        selectionChanged();
      } else if (!units.isEmpty()) {
        frame.setStatusErrorMessage(
            units.get(0).isOwnedBy(player)
                ? "Fighters do not strike; select bombers."
                : "Those are not your aircraft.");
      }
      return;
    }
    if (!zone.equals(selectedZone)) {
      selected.clear();
      selectedZone = zone;
    }
    for (final UnitType type : types) {
      if (available.getInt(type) > 0) {
        selected.put(type, available.getInt(type));
      }
    }
    if (selected.isEmpty()) {
      frame.setStatusErrorMessage(
          "Those bombers already have targets; undo a strike to change it.");
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
        for (final UnitType type : selected.keySet()) {
          highlight.add(
              AirCombat.getBombers(player, selectedZone).stream()
                  .filter(u -> u.getType().equals(type))
                  .toList());
        }
      }
      getMap().setUnitHighlight(highlight);
    } else {
      getMap().setUnitHighlight(List.of());
    }
    targets = result;
    frame.clearStatusMessage();
    rebuild(player);
    getMap().repaint();
  }

  private void hovered(final @Nullable Territory territory) {
    if (!listening || selectedZone == null || territory == null) {
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
      if (!AirCombat.mayAttack(type, target.kind())) {
        return ResearchView.displayName(type.getName())
            + " cannot attack "
            + kindName(target.kind()).toLowerCase()
            + ". Narrow the selection to the other types.";
      }
      if (!AirCombat.mayAttack(player, selectedZone, type, target)) {
        return territory.getName()
            + " is out of range for "
            + ResearchView.displayName(type.getName())
            + " (reach "
            + reach(player, type)
            + "). Narrow the selection to the longer-ranged types.";
      }
    }
    return "";
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

  private void strikesChanged() {
    final List<TargetAssignment> snapshot = List.copyOf(staged);
    storeExecutor.execute(() -> store.accept(snapshot));
    rebuild(getCurrentPlayer());
    getMap().repaint();
  }

  // ---- the side panel

  private void rebuild(final GamePlayer player) {
    removeAll();
    actionLabel.setText(player.getName() + " bomber targets");
    add(leftAligned(actionLabel));
    final JLabel hint =
        new JLabel(
            "<html>Click bombers in an air box; the targets they can all reach light up on the"
                + " map and are listed here. Click a target to attack it. Shift-click takes every"
                + " bomber in the box; right-click drops one or clears.</html>");
    hint.setBorder(BorderFactory.createEmptyBorder(4, 5, 4, 5));
    add(leftAligned(hint));

    if (selectedZone != null && !selected.isEmpty()) {
      add(leftAligned(new JLabel("Selected in " + AirAllocation.zoneName(selectedZone) + ":")));
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
        row.add(
            new JLabel(
                ResearchView.displayName(type.getName()) + " (reach " + reach(player, type) + ")"));
        if (selected.size() > 1) {
          final JButton only = smallButton("Only these");
          only.setToolTipText("Drop the other types to see this type's own reach");
          only.addActionListener(
              e -> {
                final int count = selected.getInt(type);
                selected.clear();
                selected.put(type, count);
                selectionChanged();
              });
          row.add(only);
        }
        add(leftAligned(row));
      }
      final List<Target> legal =
          targets.entrySet().stream().filter(Map.Entry::getValue).map(Map.Entry::getKey).toList();
      add(
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
          add(leftAligned(row));
        }
      }
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
