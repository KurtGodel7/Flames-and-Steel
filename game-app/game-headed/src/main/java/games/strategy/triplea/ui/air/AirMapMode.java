package games.strategy.triplea.ui.air;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GameDataEvent;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameStep;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.engine.data.events.GameDataChangeListener;
import games.strategy.engine.delegate.IDelegate;
import games.strategy.triplea.attachments.AirZoneAttachment;
import games.strategy.triplea.attachments.AirZoneAttachment.ZoneType;
import games.strategy.triplea.delegate.AirAllocationDelegate;
import games.strategy.triplea.delegate.AirCombatDelegate;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.air.AirAllocation;
import games.strategy.triplea.delegate.air.AirCombat;
import games.strategy.triplea.delegate.air.AirCombat.Target;
import games.strategy.triplea.delegate.air.AirCombat.TargetKind;
import games.strategy.triplea.image.UnitImageFactory;
import games.strategy.triplea.image.UnitImageFactory.ImageKey;
import games.strategy.triplea.ui.DefaultMapSelectionListener;
import games.strategy.triplea.ui.UiContext;
import games.strategy.triplea.ui.mapdata.MapData;
import games.strategy.triplea.ui.panels.map.MapOverlay;
import games.strategy.triplea.ui.panels.map.MapPanel;
import games.strategy.triplea.ui.research.ResearchView;
import games.strategy.triplea.util.UnitCategory;
import games.strategy.triplea.util.UnitSeparator;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import javax.swing.SwingUtilities;
import org.triplea.java.collections.IntegerMap;

/**
 * Flames and Steel air map mode: the air war on the real map. Each theatre air zone's coverage is
 * tinted in its own colour with a bold border (which also fades the ground and naval units under
 * it), each zone's aircraft are drawn as stacks inside its area, and lines join neighbouring zones.
 * Bomb zones are a separate dashed layer over the bombed nation's land. The game switches to this
 * view when the air phase starts and back when it ends; a toggle overrides it until the next
 * switch. Hovering any territory, in either view, reports which aircraft could strike it.
 */
public final class AirMapMode implements MapOverlay {
  static final Color[] PALETTE = {
    new Color(0xE6, 0x9F, 0x00),
    new Color(0x56, 0xB4, 0xE9),
    new Color(0x00, 0x9E, 0x73),
    new Color(0xF0, 0xE4, 0x42),
    new Color(0x00, 0x72, 0xB2),
    new Color(0xD5, 0x5E, 0x00),
    new Color(0xCC, 0x79, 0xA7),
    new Color(0x9B, 0x6B, 0xD6),
    new Color(0x8C, 0xC6, 0x3E),
    new Color(0xE0, 0x4E, 0x6B),
    new Color(0x4D, 0xD0, 0xC0),
    new Color(0xB0, 0x8A, 0x5A)
  };
  private static final int TINT_ALPHA = 150;
  private static final Color PANEL_BACK = new Color(20, 24, 30, 215);
  private static final Color DOGFIGHT = new Color(255, 70, 50);
  private static final int ICONS_PER_ROW = 5;
  private static final Color CARRIER_ONLY = new Color(0xFF, 0xD0, 0x00);
  private static final Color UNASSIGNED = new Color(0xFF, 0x8C, 0x00);
  private static final Color ASSIGNED = new Color(0x8C, 0xE0, 0x8C);
  private static final int FLAG_WIDTH = 18;

  /** A drawn aircraft stack (or a zone's name label) that can be clicked. */
  public record Hit(Territory zone, List<Unit> units, boolean moved, @Nullable String greyed) {}

  /** One stack as drawn: an owner's aircraft of one type (moved ones merged in). */
  private record Item(
      GamePlayer owner, ImageKey key, UnitType type, int count, List<Unit> units, boolean moved) {}

  private record Group(GamePlayer owner, List<UnitCategory> categories) {}

  private final UiContext uiContext;
  private final @Nullable MapPanel mapPanel;
  private final int fixedWidth;
  private GameData data;
  private boolean enabled;
  private boolean inAirPhase;
  private boolean stepKnown;
  private volatile boolean busy;
  private final List<Consumer<Boolean>> modeListeners = new CopyOnWriteArrayList<>();
  private final Consumer<String> status;

  // fixed geometry of the theatre zones
  private Map<Territory, Territory> zoneOf = Map.of();
  private Map<Territory, ZoneShape> zoneArea = Map.of();
  private Map<Territory, Point> anchor = Map.of();
  private Map<Territory, Color> zoneColor = Map.of();
  private List<Territory[]> links = List.of();

  // follows the game data
  private volatile boolean dirty = true;
  private Map<Territory, ZoneShape> bombArea = Map.of();
  private Map<Territory, Point> bombAnchor = Map.of();
  private Map<Territory, List<Group>> stacks = Map.of();
  private final GameDataChangeListener changeListener = change -> dirty = true;

  // recorded while painting
  private final List<Rectangle> hitRects = new ArrayList<>();
  private final List<Hit> hits = new ArrayList<>();
  private final Map<Territory, Point> pendingSpot = new HashMap<>();
  private final Map<Territory, Rectangle> panelRect = new HashMap<>();
  // request 9 item 4: box placement, the boxes' title bars (drag handles), the mouse and Alt
  private final BoxPlacer placer = new BoxPlacer();
  private final Map<Territory, Rectangle> panelTitle = new HashMap<>();
  private volatile @Nullable Point mouseMapPoint;
  private volatile boolean altHeld;
  private @Nullable Territory dragZone;
  private Point dragOffset = new Point();
  private boolean dragMoved;
  private final Map<String, Rectangle> stackRect = new HashMap<>();

  private @Nullable Territory hovered;

  // set by the air panels
  private volatile Map<String, Integer> leaving = Map.of();
  private volatile Map<Territory, Map<GamePlayer, IntegerMap<UnitType>>> arriving = Map.of();
  private volatile Set<Territory> changedZones = Set.of();
  private final List<Rectangle> boxHitRects = new ArrayList<>();
  private final List<Hit> boxHits = new ArrayList<>();
  private final Map<String, Rectangle> boxStackRect = new HashMap<>();
  // the nation picking aircraft now (allocation or targets), which of its aircraft it may pick,
  // and why the others are greyed
  private @Nullable GamePlayer actingPlayer;
  private BiPredicate<Territory, Unit> selectable = (zone, unit) -> false;
  private BiFunction<Territory, Unit, String> greyReason = (zone, unit) -> "";
  private final Map<ImageKey, Image> greyImages = new HashMap<>();
  // the nation filter (null: all nations) and the target step's "unassigned" marker
  private volatile @Nullable GamePlayer filter;
  private volatile boolean markUnassigned;

  public AirMapMode(
      final UiContext uiContext,
      final MapPanel mapPanel,
      final GameData data,
      final Consumer<String> status) {
    this.uiContext = uiContext;
    placer.setTiles(name -> uiContext.getResourceLoader().loadBufferedImage(name));
    this.mapPanel = mapPanel;
    this.fixedWidth = 0;
    this.status = status;
    setGameData(data);
    mapPanel.addMapSelectionListener(
        new DefaultMapSelectionListener() {
          @Override
          public void mouseEntered(final @Nullable Territory territory) {
            hovered(territory);
          }

          @Override
          public void mouseMoved(
              final @Nullable Territory territory,
              final games.strategy.triplea.ui.MouseDetails md) {
            mouseOver(md.getMapPoint(), md.isAltDown());
            hit(md.getMapPoint())
                .map(Hit::greyed)
                .ifPresent(
                    reason -> {
                      if (!reason.isEmpty()) {
                        status.accept(reason);
                      }
                    });
          }
        });
    listenForBoxMoves();
  }

  /**
   * Request 9 item 4: drag a box by its title bar (double-click the title to put it back), and hold
   * Alt to hide the boxes.
   */
  private void listenForBoxMoves() {
    final java.awt.event.MouseAdapter drag =
        new java.awt.event.MouseAdapter() {
          @Override
          public void mousePressed(final java.awt.event.MouseEvent e) {
            dragMoved = false;
            if (!enabled || !SwingUtilities.isLeftMouseButton(e)) {
              return;
            }
            final Point p = mapPoint(e);
            titleAt(p)
                .ifPresent(
                    zone -> {
                      final Rectangle r = panelRect.get(zone);
                      dragZone = zone;
                      dragOffset =
                          new Point(p.x - (int) r.getCenterX(), p.y - (int) r.getCenterY());
                    });
          }

          @Override
          public void mouseDragged(final java.awt.event.MouseEvent e) {
            final Territory zone = dragZone;
            if (zone == null) {
              return;
            }
            final Point p = mapPoint(e);
            placer.moveTo(zone, new Point(p.x - dragOffset.x, p.y - dragOffset.y));
            dragMoved = true;
            repaint();
          }

          @Override
          public void mouseReleased(final java.awt.event.MouseEvent e) {
            if (dragZone != null && dragMoved) {
              placer.remember(dragZone);
            }
            dragZone = null;
          }

          @Override
          public void mouseClicked(final java.awt.event.MouseEvent e) {
            if (enabled && e.getClickCount() == 2) {
              titleAt(mapPoint(e))
                  .ifPresent(
                      zone -> {
                        placer.reset(zone);
                        repaint();
                      });
            }
          }
        };
    mapPanel.addMouseListener(drag);
    mapPanel.addMouseMotionListener(drag);
    java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager()
        .addKeyEventDispatcher(
            e -> {
              if (e.getKeyCode() == java.awt.event.KeyEvent.VK_ALT
                  && (e.getID() == java.awt.event.KeyEvent.KEY_PRESSED
                      || e.getID() == java.awt.event.KeyEvent.KEY_RELEASED)) {
                setAltHeld(e.getID() == java.awt.event.KeyEvent.KEY_PRESSED);
              }
              return false;
            });
  }

  private void setAltHeld(final boolean held) {
    if (altHeld != held) {
      altHeld = held;
      if (enabled) {
        repaint();
      }
    }
  }

  /** The mouse moved on the map: boxes fade or show again as it enters or leaves them. */
  private void mouseOver(final Point mapPoint, final boolean alt) {
    final Point before = mouseMapPoint;
    mouseMapPoint = mapPoint;
    setAltHeld(alt);
    if (!enabled) {
      return;
    }
    for (final Rectangle r : List.copyOf(panelRect.values())) {
      if ((before != null && r.contains(before)) != r.contains(mapPoint)) {
        repaint();
        return;
      }
    }
  }

  private Optional<Territory> titleAt(final Point p) {
    return panelTitle.entrySet().stream()
        .filter(e -> e.getValue().contains(p))
        .map(Map.Entry::getKey)
        .findFirst();
  }

  private Point mapPoint(final java.awt.event.MouseEvent e) {
    final double scale = mapPanel.getScale();
    int x = (int) (e.getX() / scale + mapPanel.getXOffset());
    final int y = (int) (e.getY() / scale + mapPanel.getYOffset());
    final int width = mapWidth();
    if (width > 0) {
      x = ((x % width) + width) % width;
    }
    return new Point(x, y);
  }

  /**
   * True once after a box was dragged: the mouse release that ends a drag is not a click on the
   * map, so the air panels ignore it.
   */
  public boolean consumeDragClick() {
    final boolean was = dragMoved;
    dragMoved = false;
    return was;
  }

  /** Without a map panel: for drawing tests only (switched on, no hover). */
  AirMapMode(final UiContext uiContext, final GameData data, final int mapWidth) {
    this.uiContext = uiContext;
    placer.setTiles(name -> uiContext.getResourceLoader().loadBufferedImage(name));
    this.mapPanel = null;
    this.fixedWidth = mapWidth;
    this.status = text -> {};
    this.data = data;
    enabled = true;
    stepKnown = true;
  }

  /** For drawing tests: pretend the mouse is over this territory. */
  void setHovered(final @Nullable Territory territory) {
    hovered = territory;
  }

  private int mapWidth() {
    return mapPanel == null ? fixedWidth : mapPanel.getImageWidth();
  }

  private void repaint() {
    if (mapPanel != null) {
      mapPanel.repaint();
    }
  }

  public void setGameData(final GameData newData) {
    if (data != null) {
      data.removeDataChangeListener(changeListener);
    }
    data = newData;
    data.addDataChangeListener(changeListener);
    // request 22: the box map (land, photo cards, plaque) is built in the background
    placer.prepare(uiContext.getMapData(), newData);
    data.addGameDataEventListener(
        GameDataEvent.GAME_STEP_CHANGED, () -> SwingUtilities.invokeLater(this::stepChanged));
    zoneArea = Map.of();
    dirty = true;
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

  // the map XML's names for the air allocation and air combat delegates
  private static final Set<String> AIR_DELEGATE_NAMES =
      java.util.Set.of("airAllocation", "airCombat");

  static boolean isAirStep(final @Nullable GameStep step) {
    if (step == null) {
      return false;
    }
    // request 18: the history view's copy of the game data has no delegates, so never fetch one
    // that may be missing; go by the delegate's name there
    final IDelegate delegate = step.getDelegateOptional().orElse(null);
    if (delegate != null) {
      return delegate instanceof AirAllocationDelegate || delegate instanceof AirCombatDelegate;
    }
    return AIR_DELEGATE_NAMES.contains(step.getDelegateName());
  }

  /** An air panel with a selection writes its own status; hover reports wait meanwhile. */
  public void setBusy(final boolean busy) {
    this.busy = busy;
  }

  // ---- queries for the air panels

  /** The theatre air zone a territory belongs to. */
  public Optional<Territory> zoneOf(final Territory territory) {
    return Optional.ofNullable(zoneOf.get(territory));
  }

  /** The stack (or zone label) drawn at a map point, when this view is on. */
  public Optional<Hit> hit(final Point mapPoint) {
    synchronized (hits) {
      for (int i = boxHitRects.size() - 1; i >= 0; i--) {
        if (boxHitRects.get(i).contains(mapPoint)) {
          return Optional.of(boxHits.get(i));
        }
      }
      if (!enabled) {
        return Optional.empty();
      }
      for (int i = hitRects.size() - 1; i >= 0; i--) {
        if (hitRects.get(i).contains(mapPoint)) {
          return Optional.of(hits.get(i));
        }
      }
    }
    return Optional.empty();
  }

  /**
   * Moves staged this step, shown as if made, as a TripleA move would be: the aircraft are taken
   * off their zone and drawn in their new zone (in the boxes and on the air map). The game itself
   * changes only at the reveal.
   *
   * @param leaving aircraft leaving each zone, by owner and type
   * @param arriving aircraft arriving in each zone, by owner and type
   */
  public void setPending(
      final Map<Territory, Map<GamePlayer, IntegerMap<UnitType>>> leaving,
      final Map<Territory, Map<GamePlayer, IntegerMap<UnitType>>> arriving) {
    final Map<String, Integer> result = new HashMap<>();
    leaving.forEach(
        (zone, byOwner) ->
            byOwner.forEach(
                (owner, types) ->
                    types
                        .keySet()
                        .forEach(
                            type ->
                                result.merge(
                                    stackKey(zone, owner, type),
                                    types.getInt(type),
                                    Integer::sum))));
    this.leaving = result;
    this.arriving = Map.copyOf(arriving);
    final Set<Territory> changed = new HashSet<>(leaving.keySet());
    changed.addAll(arriving.keySet());
    this.changedZones = Set.copyOf(changed);
    repaint();
  }

  /**
   * The nation picking aircraft now. Its aircraft it cannot pick in this step are greyed (in the
   * boxes and on the air map), and pointing at them gives the reason. Pass null when no air step is
   * running.
   */
  public void setActing(
      final @Nullable GamePlayer player,
      final BiPredicate<Territory, Unit> selectable,
      final BiFunction<Territory, Unit, String> greyReason) {
    actingPlayer = player;
    this.selectable = selectable;
    this.greyReason = greyReason;
    repaint();
  }

  /** Shows only this nation's aircraft (others faded), or all when null. */
  public void setFilter(final @Nullable GamePlayer nation) {
    filter = nation;
    repaint();
  }

  public @Nullable GamePlayer getFilter() {
    return filter;
  }

  /** In the target step, the acting nation's bombers without a target carry a marker. */
  public void setMarkUnassigned(final boolean mark) {
    markUnassigned = mark;
    repaint();
  }

  /** Where a nation's stack of one aircraft type is drawn in a zone's panel, while painting. */
  public Optional<Rectangle> stackRect(
      final Territory zone, final GamePlayer owner, final UnitType type) {
    final String key = stackKey(zone, owner, type);
    return Optional.ofNullable(enabled ? stackRect.get(key) : boxStackRect.get(key));
  }

  private static String stackKey(
      final Territory zone, final GamePlayer owner, final UnitType type) {
    return zone.getName() + "|" + owner.getName() + "|" + type.getName();
  }

  /** Where arrows to and from a zone attach: its stack on the map. */
  public Optional<Point> anchorOf(final Territory zone) {
    if (!enabled) {
      return Optional.empty();
    }
    final Rectangle r = panelRect.get(zone);
    if (r != null) {
      return Optional.of(new Point((int) r.getCenterX(), (int) r.getCenterY()));
    }
    return Optional.ofNullable(anchor.getOrDefault(zone, bombAnchor.get(zone)));
  }

  /**
   * Request 20: where to scroll to show a front's aircraft: its box, or for a docked box (which
   * stays in view anyway) the front itself.
   */
  public Optional<Point> scrollTarget(final Territory zone) {
    if (placer.isDocked(zone)) {
      return Optional.ofNullable(anchor.getOrDefault(zone, bombAnchor.get(zone)));
    }
    return anchorOf(zone);
  }

  /** The next free spot under a zone's stack for a pending (staged) icon, while painting. */
  public Optional<Point> takePendingSpot(final Territory zone, final int iconWidth) {
    final Point spot = pendingSpot.get(zone);
    if (!enabled || spot == null) {
      return Optional.empty();
    }
    pendingSpot.put(zone, new Point(spot.x + iconWidth + 18, spot.y));
    return Optional.of(new Point(spot));
  }

  /** Fills a zone's area on the map (theatre or bomb zone); false if it has none. */
  public boolean fillZone(final Graphics2D g, final Territory zone, final Color color) {
    final ZoneShape area = areaOf(zone);
    if (!enabled || area == null) {
      return false;
    }
    g.setColor(color);
    area.fill(g);
    return true;
  }

  /** Outlines a zone's area and its stack; false if it has none. */
  public boolean outlineZone(
      final Graphics2D g, final Territory zone, final Color color, final float width) {
    final ZoneShape area = areaOf(zone);
    if (!enabled || area == null) {
      return false;
    }
    g.setColor(color);
    g.setStroke(new BasicStroke(width));
    area.draw(g);
    final Rectangle r = panelRect.get(zone);
    if (r != null) {
      g.drawRoundRect(r.x - 3, r.y - 3, r.width + 6, r.height + 6, 10, 10);
    }
    return true;
  }

  private @Nullable ZoneShape areaOf(final Territory zone) {
    final ZoneShape area = zoneArea.get(zone);
    return area != null ? area : bombArea.get(zone);
  }

  // ---- painting

  @Override
  public void paint(final Graphics2D g, final GameData paintData) {
    synchronized (hits) {
      hitRects.clear();
      hits.clear();
      boxHitRects.clear();
      boxHits.clear();
    }
    boxStackRect.clear();
    pendingSpot.clear();
    dockX = 0;
    dockRowBottom = 0;
    dockRowHeight = 0;
    dockDepth = 0;
    panelRect.clear();
    panelTitle.clear();
    stackRect.clear();
    final MapData mapData = uiContext.getMapData();
    try (GameData.Unlocker ignored = paintData.acquireReadLock()) {
      if (zoneArea.isEmpty()) {
        buildGeometry(paintData, mapData);
      }
      if (dirty) {
        dirty = false;
        refresh(paintData, mapData);
      }
      final Set<Territory> boxes = new LinkedHashSet<>(changedZones);
      if (actingPlayer != null) {
        stacks.forEach(
            (zone, groups) -> {
              if (groups.stream().anyMatch(gr -> gr.owner().equals(actingPlayer))) {
                boxes.add(zone);
              }
            });
      }
      for (final Territory zone : boxes) {
        paintBox(g, paintData, mapData, zone);
      }
      if (!enabled) {
        paintHoverLink(g, mapData);
        paintBanner(g);
        return;
      }
      // tint (also fades the ground and naval units) and bold borders
      zoneArea.forEach(
          (zone, area) -> {
            final Color c = zoneColor.get(zone);
            g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), TINT_ALPHA));
            area.fill(g);
          });
      zoneArea.forEach(
          (zone, area) -> {
            g.setColor(zoneColor.get(zone).darker());
            g.setStroke(new BasicStroke(4f));
            area.draw(g);
            if (AirZoneAttachment.get(zone).orElseThrow().getZoneType() == ZoneType.OCEAN) {
              // carrier-only fronts are edged in yellow, as on their box
              g.setColor(CARRIER_ONLY);
              g.setStroke(new BasicStroke(2.5f));
              area.draw(g);
            }
          });
      // bomb zones: dashed, in the bombing side's colour
      bombArea.forEach(
          (zone, area) -> {
            g.setColor(Color.BLACK);
            g.setStroke(dashed(7f));
            area.draw(g);
            g.setColor(bombColor(paintData, mapData, zone));
            g.setStroke(dashed(4f));
            area.draw(g);
          });
      paintLinks(g, paintData);
      paintHoverLink(g, mapData);
      // request 22: the acting nation's boxes first (and first on the dock), then the others
      paintDockBar(g);
      for (final boolean acting : new boolean[] {true, false}) {
        for (final Territory zone : zoneArea.keySet()) {
          if (isActingZone(zone) == acting) {
            paintPanel(g, paintData, mapData, zone, anchor.get(zone), false);
          }
        }
        bombAnchor.forEach(
            (zone, at) -> {
              if (isActingZone(zone) == acting) {
                paintPanel(g, paintData, mapData, zone, at, true);
              }
            });
      }
      lastDockDepth = dockDepth;
      paintBanner(g);
    }
  }

  private void paintLinks(final Graphics2D g, final GameData paintData) {
    final int width = mapWidth();
    for (final Territory[] link : links) {
      final Point a = anchor.get(link[0]);
      final Point b0 = anchor.get(link[1]);
      // across the date line if that is shorter
      Point b = b0;
      if (Math.abs(b0.x - a.x) > width / 2) {
        b = new Point(b0.x + (b0.x > a.x ? -width : width), b0.y);
      }
      g.setColor(new Color(0, 0, 0, 120));
      g.setStroke(new BasicStroke(4.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
      g.drawLine(a.x, a.y, b.x, b.y);
      g.setColor(new Color(255, 255, 255, 190));
      g.setStroke(
          new BasicStroke(
              2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10f, new float[] {9f, 6f}, 0f));
      g.drawLine(a.x, a.y, b.x, b.y);
    }
  }

  /** The hovered territory's air zone (and Bomb zones over it) outlined, in either view. */
  private void paintHoverLink(final Graphics2D g, final MapData mapData) {
    final Territory t = hovered;
    if (t == null || AirZoneAttachment.isAirZone(t)) {
      return;
    }
    final Territory zone = zoneOf.get(t);
    if (zone != null) {
      if (enabled) {
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(6f));
        zoneArea.get(zone).draw(g);
      } else {
        AirZoneOverlay.outline(g, mapData, zone, Color.WHITE, 4f);
      }
    }
    for (final Territory bomb : bombZonesOver(t)) {
      if (enabled && bombArea.containsKey(bomb)) {
        g.setColor(Color.WHITE);
        g.setStroke(dashed(5f));
        bombArea.get(bomb).draw(g);
      } else {
        AirZoneOverlay.outline(g, mapData, bomb, Color.WHITE, 4f);
      }
    }
  }

  /** A zone's stacks as drawn: departures taken off, arrivals added, each owner in turn order. */
  private List<List<Item>> items(final GameData paintData, final Territory zone) {
    final Map<GamePlayer, List<Item>> byOwner = new LinkedHashMap<>();
    final Map<String, Integer> leftToRemove = new HashMap<>();
    for (final Group group : stacks.getOrDefault(zone, List.of())) {
      for (final UnitCategory category : group.categories()) {
        final String key = stackKey(zone, group.owner(), category.getType());
        final int remove =
            Math.min(
                category.getUnits().size(),
                leftToRemove.computeIfAbsent(key, k -> leaving.getOrDefault(k, 0)));
        leftToRemove.put(key, leftToRemove.get(key) - remove);
        final int count = category.getUnits().size() - remove;
        if (count > 0) {
          byOwner
              .computeIfAbsent(group.owner(), k -> new ArrayList<>())
              .add(
                  new Item(
                      group.owner(),
                      ImageKey.of(category),
                      category.getType(),
                      count,
                      List.copyOf(category.getUnits()),
                      false));
        }
      }
    }
    arriving
        .getOrDefault(zone, Map.of())
        .forEach(
            (owner, types) -> {
              final List<Item> list = byOwner.computeIfAbsent(owner, k -> new ArrayList<>());
              for (final UnitType type : types.keySet()) {
                final int n = types.getInt(type);
                boolean merged = false;
                for (int i = 0; i < list.size() && !merged; i++) {
                  final Item item = list.get(i);
                  if (item.type().equals(type) && !item.key().isDamaged()) {
                    list.set(
                        i,
                        new Item(owner, item.key(), type, item.count() + n, item.units(), false));
                    merged = true;
                  }
                }
                if (!merged) {
                  list.add(
                      new Item(
                          owner,
                          ImageKey.builder().player(owner).type(type).build(),
                          type,
                          n,
                          List.of(),
                          true));
                }
              }
            });
    final List<List<Item>> result = new ArrayList<>();
    for (final GamePlayer p : paintData.getPlayerList().getPlayers()) {
      final List<Item> list = byOwner.get(p);
      if (list != null && !list.isEmpty()) {
        result.add(list);
      }
    }
    return result;
  }

  private boolean isSelectable(final Territory zone, final Item item) {
    return actingPlayer != null
        && item.owner().equals(actingPlayer)
        && !item.units().isEmpty()
        && selectable.test(zone, item.units().get(0));
  }

  /** Why the acting nation's stack cannot be picked now, or null if it is not greyed. */
  private @Nullable String greyed(final Territory zone, final Item item) {
    if (actingPlayer == null
        || !item.owner().equals(actingPlayer)
        || item.units().isEmpty()
        || isSelectable(zone, item)) {
      return null;
    }
    return greyReason.apply(zone, item.units().get(0));
  }

  /** Faded by the nation filter. */
  private boolean faded(final Item item) {
    final GamePlayer only = filter;
    return only != null && !item.owner().equals(only);
  }

  // how far along the dock the next docked box goes, this paint
  private int dockX;

  /**
   * Where a docked box goes this paint: left to right along the bottom of the visible map (the
   * whole map when there is no map panel, as in previews).
   */
  private @Nullable Rectangle dockSpot(final int width, final int height, final int slot) {
    final Rectangle view = view();
    // request 22: a full row continues on a row above it
    if (dockX > 0 && 10 + dockX + slot > view.width - 10) {
      dockRowBottom += dockRowHeight + 10;
      dockX = 0;
      dockRowHeight = 0;
    }
    final Rectangle r =
        new Rectangle(
            view.x + 10 + dockX + (slot - width) / 2,
            view.y + view.height - 10 - dockRowBottom - height,
            width,
            height);
    dockX += slot + 12;
    dockRowHeight = Math.max(dockRowHeight, height);
    dockDepth = Math.max(dockDepth, view.y + view.height - r.y);
    return r;
  }

  // the docked rows this paint: the bottom of the current row above the view's bottom, its height,
  // and how far up the dock reaches (kept for drawing the dock's bar under the next paint's boxes)
  private int dockRowBottom;
  private int dockRowHeight;
  private int dockDepth;
  private int lastDockDepth;

  // request 22: a box briefly outlined after "Choose targets", until this time
  private volatile @Nullable Territory flashZone;
  private volatile long flashUntil;

  /** Outlines the zone's box for a moment, so the player sees which box the group is in. */
  public void flash(final Territory zone) {
    flashZone = zone;
    flashUntil = System.currentTimeMillis() + 1600;
    repaint();
    final javax.swing.Timer timer = new javax.swing.Timer(1700, e -> repaint());
    timer.setRepeats(false);
    timer.start();
  }

  // request 22: the target step's banner over the map, or null
  private volatile @Nullable String banner;

  /** Shows (or with null hides) a banner along the top of the visible map. */
  public void setBanner(final @Nullable String text) {
    banner = text;
    repaint();
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
    g.setColor(UNASSIGNED);
    g.setStroke(new BasicStroke(3f));
    g.drawRoundRect(x, y, w, h, 12, 12);
    g.setColor(Color.WHITE);
    g.drawString(text, x + 15, y + 7 + fm.getAscent());
  }

  /** For tests: the box placer. */
  BoxPlacer placer() {
    return placer;
  }

  /** Where the zone's box was drawn last (empty for a docked box, which stays in view). */
  public Optional<Rectangle> boxOnMap(final Territory zone) {
    if (placer.isDocked(zone)) {
      return Optional.empty();
    }
    return Optional.ofNullable(panelRect.get(zone));
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

  /** Request 22: the dock is a bar along the bottom of the view, with its own label. */
  private void paintDockBar(final Graphics2D g) {
    if (lastDockDepth <= 0) {
      return;
    }
    final Rectangle view = view();
    final int top = view.y + view.height - lastDockDepth - 26;
    g.setColor(new Color(20, 24, 30, 215));
    g.fillRect(view.x, top, view.width, view.y + view.height - top);
    g.setColor(new Color(255, 255, 255, 120));
    g.drawLine(view.x, top, view.x + view.width, top);
    g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
    g.setColor(new Color(220, 220, 220));
    g.drawString(
        "Air fronts with no room on the map (drag a box onto the map to place it yourself)",
        view.x + 12,
        top + 16);
  }

  /** Whether the nation acting now has aircraft in the zone (true when no nation is acting). */
  private boolean isActingZone(final Territory zone) {
    final GamePlayer player = actingPlayer;
    return player == null
        || stacks.getOrDefault(zone, List.of()).stream().anyMatch(gr -> gr.owner().equals(player));
  }

  /**
   * The target step's label over a box with the acting nation's strike aircraft, and its hover
   * text: "Choose targets: 2 groups waiting", "All assigned" or "Nothing in reach"; null when the
   * nation has no strike aircraft there.
   */
  private @Nullable String[] targetStatus(final Territory zone, final List<List<Item>> groups) {
    final GamePlayer player = actingPlayer;
    if (player == null) {
      return null;
    }
    final Set<UnitType> waiting = new LinkedHashSet<>();
    final Set<UnitType> outOfReach = new LinkedHashSet<>();
    for (final List<Item> group : groups) {
      for (final Item item : group) {
        if (!item.owner().equals(player) || item.units().isEmpty()) {
          continue;
        }
        if (isSelectable(zone, item)) {
          waiting.add(item.type());
        } else if (AirCombat.isBomber(item.units().get(0))) {
          outOfReach.add(item.type());
        }
      }
    }
    final String prefix = zone.getName() + "|" + player.getName() + "|";
    final boolean assigned =
        leaving.entrySet().stream()
            .anyMatch(e -> e.getKey().startsWith(prefix) && e.getValue() > 0);
    final String legend =
        " Orange outline: strike aircraft still without a target. Grey: aircraft that cannot"
            + " strike from here (fighters never strike; others have nothing in reach). Point at an"
            + " icon for the reason.";
    if (!waiting.isEmpty()) {
      return new String[] {
        "Choose targets: "
            + waiting.size()
            + (waiting.size() == 1 ? " group" : " groups")
            + " waiting",
        "These aircraft have targets in reach but none chosen yet: click them, or use the Actions"
            + " panel."
            + legend
      };
    }
    if (assigned) {
      return new String[] {
        "All assigned", "Every strike aircraft here that can reach a target has one." + legend
      };
    }
    if (!outOfReach.isEmpty()) {
      return new String[] {
        "Nothing in reach",
        "No target is within reach of these strike aircraft from "
            + AirAllocation.zoneName(zone)
            + "."
            + legend
      };
    }
    return null;
  }

  /** The target step's marker on bombers that have no target yet: an orange outline. */
  private boolean paintUnassigned(
      final Graphics2D g,
      final Territory zone,
      final Item item,
      final int x,
      final int y,
      final int w) {
    if (!markUnassigned || !isSelectable(zone, item)) {
      return false;
    }
    final int h = uiContext.getUnitImageFactory().getUnitImageHeight();
    g.setColor(UNASSIGNED);
    g.setStroke(new BasicStroke(2.5f));
    g.drawRoundRect(x - 2, y - 1, w + 4, h + 2, 8, 8);
    return true;
  }

  private void paintFlag(final Graphics2D g, final GamePlayer owner, final int x, final int y) {
    final Image flag = uiContext.getFlagImageFactory().getSmallFlag(owner);
    if (flag != null) {
      g.drawImage(flag, x, y, 14, 10, null);
    }
  }

  /** A unit image in faded grey, for aircraft that cannot be picked in this step. */
  private Image greyImage(final ImageKey key) {
    return greyImages.computeIfAbsent(
        key,
        k -> {
          final Image base = uiContext.getUnitImageFactory().getImage(k);
          final int w = Math.max(1, base.getWidth(null));
          final int h = Math.max(1, base.getHeight(null));
          final BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
          final Graphics2D ig = img.createGraphics();
          ig.drawImage(base, 0, 0, null);
          ig.dispose();
          for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
              final int argb = img.getRGB(x, y);
              final int a = (argb >>> 24) & 0xFF;
              final int lum =
                  (int)
                      (0.3 * ((argb >> 16) & 0xFF)
                          + 0.59 * ((argb >> 8) & 0xFF)
                          + 0.11 * (argb & 0xFF));
              final int light = 110 + lum / 3;
              img.setRGB(x, y, ((a * 55 / 100) << 24) | (light << 16) | (light << 8) | light);
            }
          }
          return img;
        });
  }

  private Image imageOf(final Territory zone, final Item item) {
    return greyed(zone, item) != null
        ? greyImage(item.key())
        : uiContext.getUnitImageFactory().getImage(item.key());
  }

  private static final Map<ZoneType, Color> BOX_FILL =
      Map.of(
          ZoneType.OCEAN, new Color(0x1F, 0x3D, 0x62),
          ZoneType.COASTAL, new Color(0x2C, 0x54, 0x70),
          ZoneType.LAND, new Color(0x5B, 0x50, 0x35),
          ZoneType.BOMB, new Color(0x5E, 0x2B, 0x29),
          ZoneType.RESERVE, new Color(0x45, 0x45, 0x45));

  /**
   * Redraws a box's stacks when staged moves change them, the way the map draws units: icons with
   * counts in the box's unit spots.
   */
  private void paintBox(
      final Graphics2D g, final GameData paintData, final MapData mapData, final Territory zone) {
    final Rectangle box = mapData.getBoundingRect(zone);
    final List<Point> spots = mapData.getPlacementPoints(zone);
    if (spots.isEmpty()) {
      return;
    }
    final int top = spots.stream().mapToInt(p -> p.y).min().orElse(box.y) - 2;
    final ZoneType type = AirZoneAttachment.get(zone).orElseThrow().getZoneType();
    g.setColor(BOX_FILL.getOrDefault(type, Color.DARK_GRAY));
    g.fillRect(box.x + 1, top, box.width - 2, box.y + box.height - top - 1);
    final UnitImageFactory images = uiContext.getUnitImageFactory();
    final int w = images.getUnitImageWidth();
    final int h = images.getUnitImageHeight();
    final int x0 = spots.get(0).x;
    final int cell = w + 16;
    final int cols = Math.max(1, (box.x + box.width - 4 - x0) / cell);
    int x = x0;
    int y = spots.get(0).y;
    int col = 0;
    for (final List<Item> group : items(paintData, zone)) {
      for (final Item item : group) {
        if (col == cols) {
          col = 0;
          x = x0;
          y += h + 4 + AirDraw.hitBarRoom(images.getScaleFactor());
        }
        if (y + h > box.y + box.height) {
          return;
        }
        final java.awt.Composite oldComposite = g.getComposite();
        if (faded(item)) {
          g.setComposite(
              java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 0.25f));
        }
        g.drawImage(imageOf(zone, item), x, y, null);
        g.setComposite(oldComposite);
        paintUnassigned(g, zone, item, x, y, w);
        AirDraw.countLabel(g, String.valueOf(item.count()), x + w + 14, y + h);
        AirDraw.hitBar(g, item.units(), x, y + h, w, images.getScaleFactor());
        final Rectangle rect = new Rectangle(x, y, w + 16, h);
        synchronized (hits) {
          boxHitRects.add(rect);
          boxHits.add(new Hit(zone, item.units(), item.moved(), greyed(zone, item)));
        }
        boxStackRect.putIfAbsent(
            stackKey(zone, item.owner(), item.type()), new Rectangle(x, y, w, h));
        col++;
        x += cell;
      }
    }
  }

  /** One zone's aircraft: a dark panel with the zone's name and each nation's stacks. */
  private static final String DOGFIGHT_LABEL = "Air battle";

  // height of one carrier line at the bottom of an ocean front's box
  private static final int CARRIER_LINE = 18;

  /** A flag (item null) or an aircraft stack in a box, by its x offset and its row. */
  private record Placed(@Nullable Item item, GamePlayer owner, int dx, int row) {}

  /** A box's size and what goes where in it. */
  private record Layout(int width, int height, List<Placed> placed) {}

  /**
   * Lays out a box's stacks. With rowPerNation each nation starts a new row (the usual shape);
   * otherwise nations follow one another along rows of up to perRow stacks, which makes a flatter
   * box. A nation's flag starts each of its rows.
   */
  private static Layout layout(
      final List<List<Item>> groups,
      final boolean rowPerNation,
      final int perRow,
      final int cell,
      final int rowHeight,
      final int titleWidth,
      final int titleHeight,
      final int below) {
    final int limit = 6 + FLAG_WIDTH + perRow * cell;
    final List<Placed> placed = new ArrayList<>();
    int x = 6;
    int row = 0;
    int right = 0;
    boolean first = true;
    for (final List<Item> group : groups) {
      final GamePlayer owner = group.get(0).owner();
      if (!first && (rowPerNation || x + FLAG_WIDTH + cell > limit)) {
        row++;
        x = 6;
      }
      placed.add(new Placed(null, owner, x - 2, row));
      x += FLAG_WIDTH;
      for (final Item item : group) {
        if (x + cell > limit) {
          row++;
          x = 6;
          placed.add(new Placed(null, owner, x - 2, row));
          x += FLAG_WIDTH;
        }
        placed.add(new Placed(item, owner, x, row));
        x += cell;
        right = Math.max(right, x);
      }
      first = false;
    }
    final int rows = groups.isEmpty() ? 0 : row + 1;
    return new Layout(
        Math.max(titleWidth + 12, right + 8),
        titleHeight + rows * rowHeight + 6 + below,
        List.copyOf(placed));
  }

  private void paintPanel(
      final Graphics2D g,
      final GameData paintData,
      final MapData mapData,
      final Territory zone,
      final Point at,
      final boolean bomb) {
    // request 22: while a nation picks targets, bombing zones it has nothing in are hidden
    if (markUnassigned && bomb && !isActingZone(zone)) {
      return;
    }
    final UnitImageFactory images = uiContext.getUnitImageFactory();
    final int w = images.getUnitImageWidth();
    final int h = images.getUnitImageHeight();
    final String title = (bomb ? "STRATEGIC: " : "") + AirAllocation.zoneName(zone);
    g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
    final FontMetrics fm = g.getFontMetrics();
    final int titleHeight = fm.getHeight() + 2;
    final List<List<Item>> groups = items(paintData, zone);
    final int cell = w + 16;
    final int rowHeight = h + 4 + AirDraw.hitBarRoom(images.getScaleFactor());
    final boolean contested = isContested(zone, stacks.getOrDefault(zone, List.of()));
    // request 22: nothing hangs outside the box: "Dogfight" sits in the title bar and an ocean
    // front's carrier lines at the bottom of the box
    final int titleWidth =
        fm.stringWidth(title) + (contested ? fm.stringWidth(DOGFIGHT_LABEL) + 16 : 0);
    final List<String> carrierLines =
        AirZoneAttachment.get(zone).orElseThrow().getZoneType() == ZoneType.OCEAN
            ? AirZoneOverlay.carrierLines(paintData, zone)
            : List.of();
    final int below = carrierLines.size() * CARRIER_LINE;
    // request 22: the usual shape (a row per nation) first, then flatter ones that share rows,
    // so a box can fit a narrow sea such as the Mediterranean
    final List<Layout> layouts = new ArrayList<>();
    layouts.add(
        layout(groups, true, ICONS_PER_ROW, cell, rowHeight, titleWidth, titleHeight, below));
    for (final int perRow : new int[] {ICONS_PER_ROW, 7, 10}) {
      layouts.add(layout(groups, false, perRow, cell, rowHeight, titleWidth, titleHeight, below));
    }
    // request 9 item 4: placed clear of what the player acts on, or where the player dragged it
    final BoxPlacer.Placement placement =
        placer.place(
            zone,
            at,
            layouts.stream().map(l -> new java.awt.Dimension(l.width(), l.height())).toList(),
            mapData,
            paintData);
    // request 24: while a nation picks targets, the dock holds only boxes it has aircraft in
    if (markUnassigned && placer.isDocked(zone) && !isActingZone(zone)) {
      return;
    }
    final Layout chosen = layouts.get(placement.shape());
    final int width = chosen.width();
    final int height = chosen.height();
    // request 20: a front with no clear sea near it docks at the bottom of the visible map
    final @Nullable String[] status = markUnassigned ? targetStatus(zone, groups) : null;
    final int slot = status == null ? width : Math.max(width, fm.stringWidth(status[0]) + 14);
    final @Nullable Rectangle docked = placer.isDocked(zone) ? dockSpot(width, height, slot) : null;
    final Rectangle r = docked != null ? docked : BoxPlacer.box(placement.centre(), width, height);
    final @Nullable Point mouse = mouseMapPoint;
    final boolean mouseInside = mouse != null && r.contains(mouse);
    if (altHeld && !mouseInside) {
      // Alt hides the boxes; the one under the mouse stays, so Alt-click still picks 10 aircraft
      return;
    }
    final @Nullable Territory under = hovered;
    final boolean fadedBox =
        !mouseInside
            && under != null
            && !AirZoneAttachment.isAirZone(under)
            && BoxPlacer.covers(r, under, mapData);
    panelRect.put(zone, r);
    panelTitle.put(zone, new Rectangle(r.x, r.y, r.width, titleHeight));
    final java.awt.Composite panelComposite = g.getComposite();
    final int hitsBefore;
    synchronized (hits) {
      hitsBefore = hits.size();
    }
    if (fadedBox) {
      // the mouse is on a territory under the box: fade it so the territory shows
      g.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 0.3f));
    } else if (!isActingZone(zone)) {
      // request 22: other nations' fronts are faded while a nation acts
      g.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 0.45f));
    }
    g.setColor(PANEL_BACK);
    g.fillRoundRect(r.x, r.y, r.width, r.height, 10, 10);
    if (bomb) {
      g.setColor(bombColor(paintData, mapData, zone));
      g.setStroke(dashed(3f));
    } else {
      g.setColor(contested ? DOGFIGHT : zoneColor.get(zone));
      g.setStroke(new BasicStroke(contested ? 4f : 2.5f));
    }
    g.drawRoundRect(r.x, r.y, r.width, r.height, 10, 10);
    if (zone.equals(flashZone) && System.currentTimeMillis() < flashUntil) {
      g.setColor(Color.YELLOW);
      g.setStroke(new BasicStroke(5f));
      g.drawRoundRect(r.x - 4, r.y - 4, r.width + 8, r.height + 8, 14, 14);
    }
    g.setColor(Color.WHITE);
    g.drawString(title, r.x + 6, r.y + fm.getAscent() + 2);
    if (contested) {
      g.setColor(DOGFIGHT);
      g.drawString(
          DOGFIGHT_LABEL,
          r.x + r.width - fm.stringWidth(DOGFIGHT_LABEL) - 8,
          r.y + fm.getAscent() + 2);
    }
    record(new Rectangle(r.x, r.y, r.width, titleHeight), new Hit(zone, List.of(), false, null));
    boolean unassigned = false;
    for (final Placed placed : chosen.placed()) {
      final int y = r.y + titleHeight + placed.row() * rowHeight;
      if (placed.item() == null) {
        paintFlag(g, placed.owner(), r.x + placed.dx(), y + h / 2 - 5);
        continue;
      }
      final Item item = placed.item();
      {
        final int x = r.x + placed.dx();
        final java.awt.Composite oldComposite = g.getComposite();
        if (faded(item)) {
          g.setComposite(
              java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 0.25f));
        }
        g.setColor(mapData.getPlayerColor(item.owner().getName()));
        g.fillRect(x, y + 2, 4, h - 4);
        g.drawImage(imageOf(zone, item), x + 5, y, null);
        g.setComposite(oldComposite);
        unassigned |= paintUnassigned(g, zone, item, x + 5, y, w);
        AirDraw.countLabel(g, String.valueOf(item.count()), x + w + 14, y + h);
        AirDraw.hitBar(g, item.units(), x + 5, y + h, w, images.getScaleFactor());
        record(
            new Rectangle(x, y, cell, h),
            new Hit(zone, item.units(), item.moved(), greyed(zone, item)));
        stackRect.putIfAbsent(
            stackKey(zone, item.owner(), item.type()), new Rectangle(x + 5, y, w, h));
      }
    }
    pendingSpot.put(zone, new Point(r.x + 6, r.y + r.height + 6));
    // request 20: in the target step, the box says what its strike aircraft are waiting for
    // (request 22: on the box's top edge, so it hangs no further over the map than that)
    // (status worked out above, so a docked box can leave room for its tag)
    if (status != null) {
      final int tagY = r.y;
      final Color color =
          unassigned ? UNASSIGNED : status[0].startsWith("All") ? ASSIGNED : Color.LIGHT_GRAY;
      AirDraw.tag(g, status[0], r.x + r.width / 2, tagY, color);
      final int tagWidth = g.getFontMetrics().stringWidth(status[0]) + 10;
      record(
          new Rectangle(r.x + r.width / 2 - tagWidth / 2, tagY - 9, tagWidth, 18),
          new Hit(zone, List.of(), false, status[1]));
    }
    // an ocean front's carrier room, at the bottom of the box
    int ly = r.y + r.height - below - 4 + CARRIER_LINE / 2 + 2;
    for (final String line : carrierLines) {
      AirDraw.tag(g, line, r.x + r.width / 2, ly, Color.WHITE);
      ly += CARRIER_LINE;
    }
    g.setComposite(panelComposite);
    if (fadedBox) {
      // a faded box lets clicks through to the territory under it
      synchronized (hits) {
        while (hits.size() > hitsBefore) {
          hits.remove(hits.size() - 1);
          hitRects.remove(hitRects.size() - 1);
        }
      }
    }
  }

  private void record(final Rectangle rect, final Hit hit) {
    synchronized (hits) {
      hitRects.add(rect);
      hits.add(hit);
    }
  }

  private static boolean isContested(final Territory zone, final List<Group> groups) {
    for (final Group a : groups) {
      for (final Group b : groups) {
        if (zone.getData().getRelationshipTracker().isAtWar(a.owner(), b.owner())) {
          return true;
        }
      }
    }
    return false;
  }

  /** A bombing zone's colour: the attacked nation's. */
  private static Color bombColor(
      final GameData paintData, final MapData mapData, final Territory zone) {
    final GamePlayer bombed = AirZoneAttachment.get(zone).orElseThrow().getBombedPlayer();
    return bombed == null ? Color.RED : mapData.getPlayerColor(bombed.getName());
  }

  private static BasicStroke dashed(final float width) {
    return new BasicStroke(
        width, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f, new float[] {14f, 9f}, 0f);
  }

  // ---- geometry and stacks

  private void buildGeometry(final GameData d, final MapData mapData) {
    final int width = mapWidth();
    final Map<Territory, Territory> owner = new HashMap<>();
    final Map<Territory, ZoneShape> areas = new LinkedHashMap<>();
    final Map<Territory, Point> anchors = new HashMap<>();
    final List<Territory> theatre = new ArrayList<>();
    for (final Territory zone : AirZoneAttachment.getAirZones(d.getMap())) {
      final AirZoneAttachment a = AirZoneAttachment.get(zone).orElseThrow();
      if (a.getZoneType() == ZoneType.BOMB || a.getZoneType() == ZoneType.RESERVE) {
        continue;
      }
      theatre.add(zone);
      final List<Territory> covered = new ArrayList<>(a.getCoveredTerritories());
      for (final Territory t : covered) {
        owner.put(t, zone);
      }
      areas.put(zone, ZoneShape.of(covered, mapData));
      anchors.put(zone, medoid(covered, mapData, width));
    }
    final Map<Territory, Color> colors = frontColors(d, theatre, owner);
    final List<Territory[]> pairs = new ArrayList<>();
    for (final Territory zone : theatre) {
      for (final Territory other : AirZoneAttachment.get(zone).orElseThrow().getAdjacentZones()) {
        if (areas.containsKey(other) && theatre.indexOf(other) > theatre.indexOf(zone)) {
          pairs.add(new Territory[] {zone, other});
        }
      }
    }
    zoneOf = owner;
    zoneArea = areas;
    anchor = anchors;
    zoneColor = colors;
    links = pairs;
  }

  /** Each front's colour as the air map shows it (the allocation grid's mini map uses it too). */
  static Map<Territory, Color> frontColors(final GameData d) {
    final Map<Territory, Territory> owner = new HashMap<>();
    final List<Territory> theatre = new ArrayList<>();
    for (final Territory zone : AirZoneAttachment.getAirZones(d.getMap())) {
      final AirZoneAttachment a = AirZoneAttachment.get(zone).orElseThrow();
      if (a.getZoneType() == ZoneType.BOMB || a.getZoneType() == ZoneType.RESERVE) {
        continue;
      }
      theatre.add(zone);
      for (final Territory t : a.getCoveredTerritories()) {
        owner.put(t, zone);
      }
    }
    return frontColors(d, theatre, owner);
  }

  private static Map<Territory, Color> frontColors(
      final GameData d, final List<Territory> theatre, final Map<Territory, Territory> owner) {
    // neighbouring zones get different colours
    final Map<Territory, Set<Territory>> touching = new HashMap<>();
    for (final Map.Entry<Territory, Territory> e : owner.entrySet()) {
      for (final Territory n : d.getMap().getNeighbors(e.getKey())) {
        final Territory other = owner.get(n);
        if (other != null && !other.equals(e.getValue())) {
          touching.computeIfAbsent(e.getValue(), k -> new HashSet<>()).add(other);
        }
      }
    }
    final Map<Territory, Color> colors = new HashMap<>();
    for (final Territory zone : theatre) {
      final Set<Color> used = new HashSet<>();
      for (final Territory n : touching.getOrDefault(zone, Set.of())) {
        if (colors.containsKey(n)) {
          used.add(colors.get(n));
        }
      }
      Color pick = PALETTE[theatre.indexOf(zone) % PALETTE.length];
      for (final Color c : PALETTE) {
        if (!used.contains(c)) {
          pick = c;
          break;
        }
      }
      colors.put(zone, pick);
    }
    return colors;
  }

  /** The covered territory whose centre is closest to all the others (across the date line). */
  static Point medoid(final List<Territory> covered, final MapData mapData, final int width) {
    Point best = null;
    long bestScore = Long.MAX_VALUE;
    for (final Territory a : covered) {
      final Point pa = mapData.getCenter(a);
      long score = 0;
      for (final Territory b : covered) {
        final Point pb = mapData.getCenter(b);
        final int dx = Math.abs(pa.x - pb.x);
        final long wx = Math.min(dx, width - dx);
        final long dy = pa.y - pb.y;
        score += (long) Math.sqrt((double) (wx * wx + dy * dy));
      }
      if (score < bestScore) {
        bestScore = score;
        best = pa;
      }
    }
    return best == null ? new Point() : new Point(best);
  }

  private void refresh(final GameData d, final MapData mapData) {
    final Map<Territory, ZoneShape> bombs = new LinkedHashMap<>();
    final Map<Territory, Point> bombAnchors = new LinkedHashMap<>();
    final Map<Territory, List<Group>> groups = new HashMap<>();
    for (final Territory zone : AirZoneAttachment.getAirZones(d.getMap())) {
      final AirZoneAttachment a = AirZoneAttachment.get(zone).orElseThrow();
      if (a.getZoneType() == ZoneType.BOMB && a.getBombedPlayer() != null) {
        // the home region the zone covers
        final List<Territory> land =
            a.getCoveredTerritories().stream().filter(t -> !t.isWater()).toList();
        if (!land.isEmpty()) {
          bombs.put(zone, ZoneShape.of(land, mapData));
          bombAnchors.put(zone, bombPanelSpot(d, mapData, a.getBombedPlayer(), land));
        }
      }
      final List<Unit> aircraft = zone.getUnitCollection().getMatches(Matches.unitIsAir());
      final Map<GamePlayer, List<UnitCategory>> byOwner = new LinkedHashMap<>();
      for (final UnitCategory c : UnitSeparator.getSortedUnitCategories(aircraft, d, mapData)) {
        byOwner.computeIfAbsent(c.getOwner(), k -> new ArrayList<>()).add(c);
      }
      final List<Group> list = new ArrayList<>();
      for (final GamePlayer p : d.getPlayerList().getPlayers()) {
        if (byOwner.containsKey(p)) {
          list.add(new Group(p, byOwner.get(p)));
        }
      }
      groups.put(zone, list);
    }
    bombArea = bombs;
    bombAnchor = bombAnchors;
    stacks = groups;
    placer.refresh(
        d,
        mapData,
        uiContext.getUnitImageFactory().getUnitImageWidth(),
        uiContext.getUnitImageFactory().getUnitImageHeight());
  }

  /**
   * Bomb zone stacks sit on the bombed nation's capital, pushed down so they cannot be mistaken for
   * the theatre zone's stack.
   */
  private static Point bombPanelSpot(
      final GameData d,
      final MapData mapData,
      final GamePlayer bombed,
      final List<Territory> land) {
    final List<Territory> capitals =
        games.strategy.triplea.attachments.TerritoryAttachment.getAllCurrentlyOwnedCapitals(
            bombed, d.getMap());
    final Territory at = capitals.isEmpty() ? land.get(0) : capitals.get(0);
    final Point c = mapData.getCenter(at);
    return new Point(c.x, c.y + 70);
  }

  // ---- two-way linking

  private void hovered(final @Nullable Territory territory) {
    hovered = territory;
    repaint();
    if (busy || territory == null || AirZoneAttachment.isAirZone(territory)) {
      return;
    }
    final String text;
    try (GameData.Unlocker ignored = data.acquireReadLock()) {
      text = describe(territory);
    }
    if (!text.isEmpty()) {
      status.accept(text);
    }
  }

  /** Bomb zones whose targets include this territory (the bombed nation's factory or APC). */
  private List<Territory> bombZonesOver(final Territory t) {
    final List<Territory> result = new ArrayList<>();
    for (final Territory zone : AirZoneAttachment.getAirZones(t.getData().getMap())) {
      final AirZoneAttachment a = AirZoneAttachment.get(zone).orElseThrow();
      if (a.getZoneType() == ZoneType.BOMB && a.getCoveredTerritories().contains(t)) {
        result.add(zone);
      }
    }
    return result;
  }

  /**
   * "Kiev: tactical Germany vs. USSR, could strike it: Germans 3 Dive Bomber; strategic Bomb USSR:
   * Germans 2 Strategic Bomber". Aircraft are where they were last revealed.
   */
  private String describe(final Territory t) {
    final Territory zone = zoneOf.get(t);
    if (zone == null) {
      return "";
    }
    final StringBuilder sb =
        new StringBuilder(t.getName())
            .append(": tactical ")
            .append(AirAllocation.zoneName(zone))
            .append(", could strike it: ")
            .append(
                strikers(zone, new Target(t, t.isWater() ? TargetKind.SURFACE : TargetKind.LAND)));
    for (final Territory bomb : bombZonesOver(t)) {
      sb.append("  |  strategic ")
          .append(AirAllocation.zoneName(bomb))
          .append(": ")
          .append(strikers(bomb, new Target(t, TargetKind.STRATEGIC)));
    }
    return sb.toString();
  }

  private static String strikers(final Territory zone, final Target target) {
    final Map<GamePlayer, Map<UnitType, Integer>> counts = new LinkedHashMap<>();
    for (final Unit u : zone.getUnitCollection().getMatches(AirCombat::isBomber)) {
      counts
          .computeIfAbsent(u.getOwner(), k -> new LinkedHashMap<>())
          .merge(u.getType(), 1, Integer::sum);
    }
    final List<String> parts = new ArrayList<>();
    counts.forEach(
        (player, types) -> {
          final List<String> mine = new ArrayList<>();
          types.forEach(
              (type, n) -> {
                if (AirCombat.mayAttack(player, zone, type, target)) {
                  mine.add(n + " " + ResearchView.displayName(type.getName()));
                }
              });
          if (!mine.isEmpty()) {
            parts.add(player.getName() + " " + String.join(", ", mine));
          }
        });
    return parts.isEmpty() ? "no bombers in range" : String.join("; ", parts);
  }
}
