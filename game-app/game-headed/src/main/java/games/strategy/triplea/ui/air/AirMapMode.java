package games.strategy.triplea.ui.air;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameStep;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.engine.data.GameDataEvent;
import games.strategy.engine.delegate.IDelegate;
import games.strategy.triplea.delegate.AirCombatDelegate;
import games.strategy.triplea.delegate.AirStageDelegate;
import games.strategy.triplea.delegate.air.AirBases;
import games.strategy.triplea.delegate.air.AirCombat;
import games.strategy.triplea.image.UnitImageFactory;
import games.strategy.triplea.image.UnitImageFactory.ImageKey;
import games.strategy.triplea.ui.panels.map.MapPanel;
import games.strategy.triplea.ui.UiContext;
import games.strategy.triplea.ui.panels.map.MapOverlay;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import javax.swing.SwingUtilities;

/**
 * Flames and Steel (request 38): the air war on the map. Aircraft on bases are drawn by the map as
 * usual (on an airfield plate on land, see the units drawer). Aircraft in the air are kept in a
 * hidden territory the map never draws; this overlay draws them faded over the territory they are
 * over, with a "lands at round end" mark, and pointing at them says how much range they have left.
 *
 * <p>The air map mode (button and Ctrl+I, on by itself during the air phase and planes go home)
 * adds a tag over every territory where opposing aircraft meet ("Air battle"), and the nation
 * filter fades the other nations' aircraft. Air panels may show a banner along the top of the view
 * and flash a territory.
 */
public final class AirMapMode implements MapOverlay {
  private static final Color BANNER_LINE = new Color(255, 170, 40);
  private static final Color LANDS_TAG = new Color(200, 225, 255);
  private static final Color BATTLE_TAG = new Color(255, 120, 90);

  private final UiContext uiContext;
  private final @Nullable MapPanel mapPanel;
  private final Consumer<String> status;
  private final AirDraw draw;
  private GameData data;
  private boolean enabled;
  private boolean inAirPhase;
  private boolean stepKnown;
  private @Nullable GamePlayer filter;
  private final List<Consumer<Boolean>> modeListeners = new CopyOnWriteArrayList<>();
  // where airborne groups were drawn last, for pointing at them
  private final List<Drawn> drawn = new CopyOnWriteArrayList<>();
  private volatile @Nullable String banner;
  // request 44: the View menu's switch for the markers of aircraft in the air
  private volatile boolean showAirMarkers = true;
  private static final int MARKER_WIDTH = 46;
  private final Map<String, Point> corners = new java.util.concurrent.ConcurrentHashMap<>();
  private volatile @Nullable Territory flashZone;
  private volatile long flashUntil;

  private record Drawn(Rectangle box, GamePlayer owner, Territory over, List<Unit> units) {}

  public AirMapMode(
      final UiContext uiContext,
      final @Nullable MapPanel mapPanel,
      final GameData data,
      final Consumer<String> status) {
    this.uiContext = uiContext;
    this.mapPanel = mapPanel;
    this.status = status;
    this.draw = new AirDraw(uiContext);
    setGameData(data);
    if (mapPanel != null) {
      mapPanel.addMapSelectionListener(
          new games.strategy.triplea.ui.DefaultMapSelectionListener() {
            @Override
            public void mouseMoved(
                final @Nullable Territory territory,
                final games.strategy.triplea.ui.MouseDetails md) {
              pointedAt(md.getMapPoint());
            }
          });
    }
  }

  /** Whether the game puts aircraft on the map (it has the hidden airborne territory). */
  public static boolean isUsed(final GameData data) {
    try (GameData.Unlocker ignored = data.acquireReadLock()) {
      return AirBases.usesBases(data);
    }
  }

  public void setGameData(final GameData newData) {
    data = newData;
    data.addGameDataEventListener(
        GameDataEvent.GAME_STEP_CHANGED, () -> SwingUtilities.invokeLater(this::stepChanged));
    data.addDataChangeListener(change -> repaint());
    stepKnown = false;
    stepChanged();
  }

  // ---- the view switch

  public boolean isEnabled() {
    return enabled;
  }

  /** The player's toggle; lasts until the air phase starts or ends. */
  public void toggle() {
    setEnabled(!enabled);
  }

  public void addModeListener(final Consumer<Boolean> listener) {
    modeListeners.add(listener);
    listener.accept(enabled);
  }

  private void setEnabled(final boolean value) {
    enabled = value;
    modeListeners.forEach(l -> l.accept(value));
    repaint();
  }

  private void stepChanged() {
    final boolean air;
    try (GameData.Unlocker ignored = data.acquireReadLock()) {
      air = isAirStep(data.getSequence().getStep());
    }
    if (air != inAirPhase || !stepKnown) {
      stepKnown = true;
      inAirPhase = air;
      setEnabled(air);
    }
  }

  // the map XML's names for the air delegates
  private static final Set<String> AIR_DELEGATE_NAMES = Set.of("airCombat", "airStage");

  static boolean isAirStep(final @Nullable GameStep step) {
    if (step == null) {
      return false;
    }
    // request 18: the history view's copy of the game data has no delegates, so never fetch one
    // that may be missing; go by the delegate's name there
    final IDelegate delegate = step.getDelegateOptional().orElse(null);
    if (delegate != null) {
      return delegate instanceof AirCombatDelegate || delegate instanceof AirStageDelegate;
    }
    return AIR_DELEGATE_NAMES.contains(step.getDelegateName());
  }

  /** Fades every nation's aircraft but this one's (null shows all). */
  public void setFilter(final @Nullable GamePlayer nation) {
    filter = nation;
    repaint();
  }

  /** Kept for the air panels: a panel with a selection writes its own status. */
  public void setBusy(final boolean busy) {
    // the overlay reports only when the mouse is over aircraft in the air
  }

  /** Outlines the territory for a moment, so the player sees where the group is. */
  public void flash(final Territory zone) {
    flashZone = zone;
    flashUntil = System.currentTimeMillis() + 1600;
    repaint();
    final javax.swing.Timer timer = new javax.swing.Timer(1700, e -> repaint());
    timer.setRepeats(false);
    timer.start();
  }

  /** Shows (or with null hides) a banner along the top of the visible map. */
  public void setBanner(final @Nullable String text) {
    banner = text;
    repaint();
  }

  private void repaint() {
    if (mapPanel != null) {
      mapPanel.repaint();
    }
  }

  // ---- painting

  @Override
  public void paint(final Graphics2D g, final GameData paintData) {
    final List<Drawn> now = new ArrayList<>();
    try (GameData.Unlocker ignored = paintData.acquireReadLock()) {
      final Territory airborne = AirBases.airborne(paintData).orElse(null);
      if (airborne != null) {
        paintAirborne(g, airborne, now);
      }
    }
    drawn.clear();
    drawn.addAll(now);
    final Territory flash = flashZone;
    if (flash != null && System.currentTimeMillis() < flashUntil) {
      draw.outlineBox(g, flash, Color.YELLOW, 5f);
    }
    paintBanner(g);
  }

  /**
   * Every nation's aircraft in the air over the territory they are over. During the air phase (and
   * in the air map) they are drawn in full, stacked as the map stacks units, with "lands at round
   * end" under them; in a territory where enemies meet the air map adds "Air battle". Request 44:
   * the rest of the round each nation shows only a small faded marker in a corner of the
   * territory (the View menu hides them), so they don't get in the way of the turn. Aircraft an
   * air screen draws elsewhere (they have landing orders) are left out.
   */
  private void paintAirborne(final Graphics2D g, final Territory airborne, final List<Drawn> out) {
    final Set<Unit> elsewhere = mapPanel == null ? Set.of() : mapPanel.getUnitsOffMap();
    final Map<Territory, List<Unit>> byPlace = new LinkedHashMap<>();
    for (final Unit u : airborne.getUnits()) {
      if (u.getFlightOver() == null || elsewhere.contains(u)) {
        continue;
      }
      byPlace.computeIfAbsent(u.getFlightOver(), k -> new ArrayList<>()).add(u);
    }
    final boolean compact = isCompact();
    if (compact && !showAirMarkers) {
      return;
    }
    final int h = uiContext.getUnitImageFactory().getUnitImageHeight();
    for (final Map.Entry<Territory, List<Unit>> e : byPlace.entrySet()) {
      final Territory over = e.getKey();
      if (compact) {
        paintMarkers(g, over, e.getValue(), out);
        continue;
      }
      final Point middle = draw.middle(over);
      final List<AirDraw.Stack> stacks = AirDraw.stacks(e.getValue());
      final List<GamePlayer> owners = new ArrayList<>();
      stacks.forEach(
          stack -> {
            if (!owners.contains(stack.owner())) {
              owners.add(stack.owner());
            }
          });
      final Point centre = new Point(middle.x, middle.y - h / 2 - 12);
      final List<Rectangle> boxes = draw.drawStacks(g, centre, stacks, 0.6f);
      for (int i = 0; i < stacks.size(); i++) {
        final AirDraw.Stack stack = stacks.get(i);
        if (filter != null && !filter.equals(stack.owner())) {
          // the nation filter fades the others
          g.setColor(new Color(0, 0, 0, 110));
          g.fill(boxes.get(i));
        }
        out.add(new Drawn(boxes.get(i), stack.owner(), over, stack.units()));
      }
      AirDraw.tag(g, "lands at round end", middle.x, centre.y + h / 2 + 16, LANDS_TAG);
      if (enabled && hasBattle(owners)) {
        AirDraw.tag(g, "Air battle", middle.x, centre.y - h / 2 - 14, BATTLE_TAG);
      }
    }
  }

  /** Request 44: outside the air phase, with the air map off, aircraft in the air are markers. */
  private boolean isCompact() {
    return !inAirPhase && !enabled;
  }

  public boolean isShowingAirMarkers() {
    return showAirMarkers;
  }

  /** Request 44: the View menu's "Show Aircraft in the Air". */
  public void setShowingAirMarkers(final boolean show) {
    showAirMarkers = show;
    repaint();
  }

  /**
   * Request 44: one small faded marker per nation (its most common aircraft, small, and the
   * total count) in the spot of the territory furthest from where its units are drawn.
   */
  private void paintMarkers(
      final Graphics2D g, final Territory over, final List<Unit> units, final List<Drawn> out) {
    final Map<GamePlayer, List<Unit>> byNation = new LinkedHashMap<>();
    units.forEach(u -> byNation.computeIfAbsent(u.getOwner(), k -> new ArrayList<>()).add(u));
    final UnitImageFactory images = uiContext.getUnitImageFactory();
    final int iconW = Math.max(12, images.getUnitImageWidth() * 3 / 5);
    final int iconH = Math.max(12, images.getUnitImageHeight() * 3 / 5);
    final Point corner = markerCorner(over, MARKER_WIDTH, (iconH + 4) * byNation.size());
    int y = corner.y;
    final Composite old = g.getComposite();
    for (final Map.Entry<GamePlayer, List<Unit>> e : byNation.entrySet()) {
      final GamePlayer nation = e.getKey();
      final boolean faded = filter != null && !filter.equals(nation);
      g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, faded ? 0.3f : 0.75f));
      final Rectangle box = new Rectangle(corner.x, y, MARKER_WIDTH, iconH + 2);
      g.setColor(new Color(20, 20, 20, 200));
      g.fillRoundRect(box.x, box.y, box.width, box.height, 10, 10);
      g.setColor(uiContext.getMapData().getPlayerColor(nation.getName()));
      g.setStroke(new BasicStroke(2f));
      g.drawRoundRect(box.x, box.y, box.width, box.height, 10, 10);
      g.drawImage(
          images.getImage(ImageKey.builder().player(nation).type(mostCommon(e.getValue())).build()),
          box.x + 3,
          box.y + 1,
          iconW,
          iconH,
          null);
      g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
      g.setColor(Color.WHITE);
      final FontMetrics fm = g.getFontMetrics();
      g.drawString(
          String.valueOf(e.getValue().size()),
          box.x + iconW + 6,
          box.y + (box.height + fm.getAscent()) / 2 - 2);
      g.setComposite(old);
      out.add(new Drawn(box, nation, over, e.getValue()));
      y += iconH + 4;
    }
  }

  private static UnitType mostCommon(final List<Unit> units) {
    final Map<UnitType, Integer> counts = new LinkedHashMap<>();
    units.forEach(u -> counts.merge(u.getType(), 1, Integer::sum));
    return counts.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();
  }

  /**
   * Request 44: where a territory's markers go: a spot inside it that covers neither its name nor
   * the units drawn there, as far as possible from its unit placement points and name.
   */
  private Point markerCorner(final Territory t, final int width, final int height) {
    final int stacks =
        games.strategy.triplea.util.UnitSeparator.getSortedUnitCategories(t, uiContext.getMapData())
            .size();
    return corners.computeIfAbsent(
        t.getName() + "|" + height + "|" + stacks, k -> findCorner(t, width, height, stacks));
  }

  private static boolean mostlyInside(final List<java.awt.Polygon> polygons, final Rectangle r) {
    for (final java.awt.Polygon p : polygons) {
      if (!p.contains(r.getCenterX(), r.getCenterY())) {
        continue;
      }
      int corners = 0;
      for (final Point c :
          List.of(
              new Point(r.x, r.y),
              new Point(r.x + r.width, r.y),
              new Point(r.x, r.y + r.height),
              new Point(r.x + r.width, r.y + r.height))) {
        if (p.contains(c)) {
          corners++;
        }
      }
      if (corners >= 3) {
        return true;
      }
    }
    return false;
  }

  /** Where the map writes the territory's name, with its PU and MP lines under it. */
  private Rectangle nameBox(final Territory t) {
    final var mapData = uiContext.getMapData();
    final java.awt.image.BufferedImage scratch =
        new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB);
    final Graphics2D g = scratch.createGraphics();
    g.setFont(games.strategy.triplea.image.MapImage.getPropertyMapFont());
    final FontMetrics fm = g.getFontMetrics();
    g.dispose();
    final int nameWidth = fm.stringWidth(t.getName());
    final int x;
    final int y;
    final var place = mapData.getNamePlacementPoint(t);
    if (place.isPresent()) {
      x = place.get().x;
      y = place.get().y;
    } else {
      final Rectangle best =
          games.strategy.triplea.ui.screen.drawable.TerritoryNameDrawable.getBestTerritoryNameRect(
              mapData, t, fm);
      x = best.x + best.width / 2 - nameWidth / 2;
      y = best.y + best.height / 2 + fm.getAscent() / 2;
    }
    // the name, then the PU value and the MP line under it
    return new Rectangle(x - 4, y - fm.getAscent() - 4, nameWidth + 8, fm.getHeight() * 3 + 8);
  }

  private Point findCorner(final Territory t, final int width, final int height, final int stacks) {
    final var mapData = uiContext.getMapData();
    final List<java.awt.Polygon> polygons = mapData.getPolygons(t);
    final Rectangle bounds = mapData.getBoundingRect(t);
    final UnitImageFactory images = uiContext.getUnitImageFactory();
    final int unitW = images.getUnitImageWidth();
    final int unitH = images.getUnitImageHeight();
    // never on the name (with its PU and MP lines) or the unit stacks drawn now
    final List<Rectangle> covered = new ArrayList<>();
    final Rectangle nameBox = nameBox(t);
    covered.add(nameBox);
    final List<Point> avoid = new ArrayList<>();
    avoid.add(new Point((int) nameBox.getCenterX(), (int) nameBox.getCenterY()));
    int n = 0;
    for (final Point p : mapData.getPlacementPoints(t)) {
      if (n++ < stacks) {
        covered.add(new Rectangle(p.x - 2, p.y - 2, unitW + 4, unitH + 12));
      }
      avoid.add(new Point(p.x + unitW / 2, p.y + unitH / 2));
    }
    // the spot that covers least (the name counts three times), then the furthest from all
    Point best = null;
    double bestCover = Double.MAX_VALUE;
    double bestScore = -1;
    final int step = 4;
    for (int x = bounds.x - width / 2; x + width / 2 <= bounds.x + bounds.width; x += step) {
      for (int y = bounds.y - height / 2; y + height / 2 <= bounds.y + bounds.height; y += step) {
        final Rectangle r = new Rectangle(x, y, width, height);
        // its middle and at least three corners in the territory (it may reach over the border
        // a little)
        if (!mostlyInside(polygons, r)) {
          continue;
        }
        double cover = 0;
        for (int i = 0; i < covered.size(); i++) {
          final Rectangle overlap = r.intersection(covered.get(i));
          if (!overlap.isEmpty()) {
            cover += (i == 0 ? 3.0 : 1.0) * overlap.width * overlap.height;
          }
        }
        double score = Double.MAX_VALUE;
        for (final Point a : avoid) {
          score = Math.min(score, a.distance(r.getCenterX(), r.getCenterY()));
        }
        if (cover < bestCover || (cover == bestCover && score > bestScore)) {
          bestCover = cover;
          bestScore = score;
          best = new Point(x, y);
        }
      }
    }
    if (best != null) {
      return best;
    }
    final Point middle = draw.middle(t);
    return new Point(middle.x - width / 2, bounds.y + 2);
  }

  private static boolean hasBattle(final List<GamePlayer> owners) {
    for (final GamePlayer a : owners) {
      for (final GamePlayer b : owners) {
        if (AirCombat.atWar(a, b)) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * Pointing at aircraft in the air says what they are and how far they can still fly (request
   * 44: a marker lists all of its nation's aircraft there).
   */
  private void pointedAt(final Point mapPoint) {
    for (final Drawn d : drawn) {
      if (d.box().contains(mapPoint)) {
        status.accept(
            games.strategy.triplea.formatter.PlayerNames.display(d.owner())
                + " aircraft in the air over "
                + d.over().getName()
                + ": "
                + listAircraft(d.units())
                + ". They land at round end; in the air they can't be attacked and are no base.");
        return;
      }
    }
  }

  /** "2 dive bombers (3 steps of range left), 1 fighter (2 steps of range left)". */
  static String listAircraft(final List<Unit> units) {
    final Map<String, List<Unit>> byType = new LinkedHashMap<>();
    units.forEach(
        u ->
            byType
                .computeIfAbsent(
                    u.getType().getName() + "|" + u.getFlightRangeLeft(), k -> new ArrayList<>())
                .add(u));
    final List<String> parts = new ArrayList<>();
    for (final List<Unit> same : byType.values()) {
      final int left = same.get(0).getFlightRangeLeft();
      parts.add(
          same.size()
              + " "
              + typeName(same.get(0).getType(), same.size())
              + " ("
              + left
              + (left == 1 ? " step" : " steps")
              + " of range left)");
    }
    return String.join(", ", parts);
  }

  private static String typeName(final UnitType type, final int count) {
    final String name = AirCombat.unitName(type);
    return count == 1 ? name : name + "s";
  }

  private void paintBanner(final Graphics2D g) {
    final String text = banner;
    if (text == null) {
      return;
    }
    final Rectangle view = view();
    g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 15));
    final FontMetrics fm = g.getFontMetrics();
    final int w = Math.min(view.width - 20, fm.stringWidth(text) + 30);
    final int h = fm.getHeight() + 14;
    final int x = view.x + (view.width - w) / 2;
    final int y = view.y + 12;
    g.setColor(new Color(0x1C, 0x1C, 0x1C, 230));
    g.fillRoundRect(x, y, w, h, 12, 12);
    g.setColor(BANNER_LINE);
    g.setStroke(new BasicStroke(3f));
    g.drawRoundRect(x, y, w, h, 12, 12);
    g.setColor(Color.WHITE);
    g.drawString(text, x + 15, y + 7 + fm.getAscent());
  }

  /** The visible part of the map, in map pixels (the whole map without a map panel). */
  private Rectangle view() {
    if (mapPanel != null && mapPanel.getWidth() > 0) {
      final double scale = mapPanel.getScale();
      return new Rectangle(
          mapPanel.getXOffset(),
          mapPanel.getYOffset(),
          (int) (mapPanel.getWidth() / scale),
          (int) (mapPanel.getHeight() / scale));
    }
    final java.awt.Dimension size = uiContext.getMapData().getMapDimensions();
    return new Rectangle(0, 0, size.width, size.height);
  }

  /** For tests. */
  List<Rectangle> airborneBoxes() {
    return drawn.stream().map(Drawn::box).toList();
  }
}
