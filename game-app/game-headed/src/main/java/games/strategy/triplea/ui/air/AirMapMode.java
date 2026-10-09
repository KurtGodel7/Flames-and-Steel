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
import games.strategy.triplea.attachments.ApcAttachment;
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
import java.awt.Polygon;
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
  private static final Color[] PALETTE = {
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
  private final Map<String, Rectangle> stackRect = new HashMap<>();

  private @Nullable Territory hovered;

  /** An arrow button beside a stack: pick one more ({@code delta} 1) or one fewer (-1). */
  public record ArrowHit(Territory zone, UnitType type, int delta) {}

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
  private final List<Rectangle> arrowRects = new ArrayList<>();
  private final List<ArrowHit> arrowHits = new ArrayList<>();
  private final List<Rectangle> boxArrowRects = new ArrayList<>();
  private final List<ArrowHit> boxArrowHits = new ArrayList<>();
  private final Map<ImageKey, Image> greyImages = new HashMap<>();

  public AirMapMode(
      final UiContext uiContext,
      final MapPanel mapPanel,
      final GameData data,
      final Consumer<String> status) {
    this.uiContext = uiContext;
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
  }

  /** Without a map panel: for drawing tests only (switched on, no hover). */
  AirMapMode(final UiContext uiContext, final GameData data, final int mapWidth) {
    this.uiContext = uiContext;
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

  private static boolean isAirStep(final @Nullable GameStep step) {
    if (step == null) {
      return false;
    }
    final IDelegate delegate = step.getDelegate();
    return delegate instanceof AirAllocationDelegate || delegate instanceof AirCombatDelegate;
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
   * The nation picking aircraft now. Its aircraft it may pick get arrow buttons everywhere (boxes
   * and air map); its other aircraft are greyed, and pointing at them gives the reason. Pass null
   * when no air step is running.
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

  /** The arrow button at a map point (in a box, or on the air map when it is on). */
  public Optional<ArrowHit> arrowHit(final Point mapPoint) {
    synchronized (hits) {
      for (int i = 0; i < boxArrowRects.size(); i++) {
        if (boxArrowRects.get(i).contains(mapPoint)) {
          return Optional.of(boxArrowHits.get(i));
        }
      }
      if (!enabled) {
        return Optional.empty();
      }
      for (int i = 0; i < arrowRects.size(); i++) {
        if (arrowRects.get(i).contains(mapPoint)) {
          return Optional.of(arrowHits.get(i));
        }
      }
    }
    return Optional.empty();
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
      arrowRects.clear();
      arrowHits.clear();
      boxArrowRects.clear();
      boxArrowHits.clear();
      boxHitRects.clear();
      boxHits.clear();
    }
    boxStackRect.clear();
    pendingSpot.clear();
    panelRect.clear();
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
      for (final Territory zone : zoneArea.keySet()) {
        paintPanel(g, paintData, mapData, zone, anchor.get(zone), false);
      }
      bombAnchor.forEach((zone, at) -> paintPanel(g, paintData, mapData, zone, at, true));
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
    final int cell = w + 16 + (actingPlayer != null ? 18 : 0);
    final int cols = Math.max(1, (box.x + box.width - 4 - x0) / cell);
    int x = x0;
    int y = spots.get(0).y;
    int col = 0;
    final Set<UnitType> arrowsDrawn = new HashSet<>();
    for (final List<Item> group : items(paintData, zone)) {
      for (final Item item : group) {
        if (col == cols) {
          col = 0;
          x = x0;
          y += h + 6;
        }
        if (y + h > box.y + box.height) {
          return;
        }
        g.drawImage(imageOf(zone, item), x, y, null);
        AirDraw.countLabel(g, String.valueOf(item.count()), x + w + 14, y + h);
        final Rectangle rect = new Rectangle(x, y, w + 16, h);
        synchronized (hits) {
          boxHitRects.add(rect);
          boxHits.add(new Hit(zone, item.units(), item.moved(), greyed(zone, item)));
        }
        boxStackRect.putIfAbsent(
            stackKey(zone, item.owner(), item.type()), new Rectangle(x, y, w, h));
        if (isSelectable(zone, item) && arrowsDrawn.add(item.type())) {
          paintArrows(g, zone, item.type(), x + w + 17, y, h, true);
        }
        col++;
        x += cell;
      }
    }
  }

  /** One zone's aircraft: a dark panel with the zone's name and each nation's stacks. */
  private void paintPanel(
      final Graphics2D g,
      final GameData paintData,
      final MapData mapData,
      final Territory zone,
      final Point at,
      final boolean bomb) {
    final UnitImageFactory images = uiContext.getUnitImageFactory();
    final int w = images.getUnitImageWidth();
    final int h = images.getUnitImageHeight();
    final String title = (bomb ? "STRATEGIC: " : "") + AirAllocation.zoneName(zone);
    g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
    final FontMetrics fm = g.getFontMetrics();
    final int titleHeight = fm.getHeight() + 2;
    final List<List<Item>> groups = items(paintData, zone);
    int rows = 0;
    int maxPerRow = 0;
    for (final List<Item> group : groups) {
      final int n = group.size();
      rows += (n + ICONS_PER_ROW - 1) / ICONS_PER_ROW;
      maxPerRow = Math.max(maxPerRow, Math.min(n, ICONS_PER_ROW));
    }
    final int cell = w + 16 + (actingPlayer != null ? 18 : 0);
    final int width = Math.max(fm.stringWidth(title) + 12, 14 + maxPerRow * cell);
    final int height = titleHeight + rows * (h + 4) + 6;
    final Rectangle r = new Rectangle(at.x - width / 2, at.y - height / 2, width, height);
    panelRect.put(zone, r);
    g.setColor(PANEL_BACK);
    g.fillRoundRect(r.x, r.y, r.width, r.height, 10, 10);
    final boolean contested = isContested(zone, stacks.getOrDefault(zone, List.of()));
    if (bomb) {
      g.setColor(bombColor(paintData, mapData, zone));
      g.setStroke(dashed(3f));
    } else {
      g.setColor(contested ? DOGFIGHT : zoneColor.get(zone));
      g.setStroke(new BasicStroke(contested ? 4f : 2.5f));
    }
    g.drawRoundRect(r.x, r.y, r.width, r.height, 10, 10);
    g.setColor(Color.WHITE);
    g.drawString(title, r.x + 6, r.y + fm.getAscent() + 2);
    record(new Rectangle(r.x, r.y, r.width, titleHeight), new Hit(zone, List.of(), false, null));
    int y = r.y + titleHeight;
    final Set<UnitType> arrowsDrawn = new HashSet<>();
    for (final List<Item> group : groups) {
      int col = 0;
      for (final Item item : group) {
        if (col == ICONS_PER_ROW) {
          col = 0;
          y += h + 4;
        }
        final int x = r.x + 6 + col * cell;
        g.setColor(mapData.getPlayerColor(item.owner().getName()));
        g.fillRect(x, y + 2, 4, h - 4);
        g.drawImage(imageOf(zone, item), x + 5, y, null);
        AirDraw.countLabel(g, String.valueOf(item.count()), x + w + 14, y + h);
        record(
            new Rectangle(x, y, cell, h),
            new Hit(zone, item.units(), item.moved(), greyed(zone, item)));
        stackRect.putIfAbsent(
            stackKey(zone, item.owner(), item.type()), new Rectangle(x + 5, y, w, h));
        if (isSelectable(zone, item) && arrowsDrawn.add(item.type())) {
          paintArrows(g, zone, item.type(), x + w + 17, y, h, false);
        }
        col++;
      }
      y += h + 4;
    }
    pendingSpot.put(zone, new Point(r.x + 6, r.y + r.height + 6));
    if (contested) {
      AirDraw.tag(g, "Dogfight", r.x + r.width / 2, r.y - 10, DOGFIGHT);
    }
    final ZoneType type = AirZoneAttachment.get(zone).orElseThrow().getZoneType();
    if (type == ZoneType.OCEAN || type == ZoneType.COASTAL) {
      int ly = r.y + r.height + 12;
      for (final String line : AirZoneOverlay.carrierLines(paintData, zone)) {
        AirDraw.tag(g, line, r.x + r.width / 2, ly, Color.WHITE);
        ly += 18;
      }
    }
  }

  /** Up and down buttons beside a stack, like the stock unit chooser's arrows. */
  private void paintArrows(
      final Graphics2D g,
      final Territory zone,
      final UnitType type,
      final int x,
      final int y,
      final int h,
      final boolean inBox) {
    final int half = h / 2 - 1;
    paintArrow(g, new Rectangle(x, y, 15, half), true);
    paintArrow(g, new Rectangle(x, y + half + 2, 15, half), false);
    synchronized (hits) {
      final List<Rectangle> rects = inBox ? boxArrowRects : arrowRects;
      final List<ArrowHit> arrows = inBox ? boxArrowHits : arrowHits;
      rects.add(new Rectangle(x, y, 15, half));
      arrows.add(new ArrowHit(zone, type, 1));
      rects.add(new Rectangle(x, y + half + 2, 15, half));
      arrows.add(new ArrowHit(zone, type, -1));
    }
  }

  private static void paintArrow(final Graphics2D g, final Rectangle b, final boolean up) {
    g.setColor(new Color(235, 235, 235));
    g.fillRoundRect(b.x, b.y, b.width, b.height, 4, 4);
    g.setColor(Color.DARK_GRAY);
    g.setStroke(new BasicStroke(1f));
    g.drawRoundRect(b.x, b.y, b.width, b.height, 4, 4);
    final Polygon tri = new Polygon();
    final int cx = b.x + b.width / 2;
    if (up) {
      tri.addPoint(cx, b.y + 2);
      tri.addPoint(b.x + 3, b.y + b.height - 2);
      tri.addPoint(b.x + b.width - 3, b.y + b.height - 2);
    } else {
      tri.addPoint(cx, b.y + b.height - 2);
      tri.addPoint(b.x + 3, b.y + 2);
      tri.addPoint(b.x + b.width - 3, b.y + 2);
    }
    g.setColor(Color.BLACK);
    g.fillPolygon(tri);
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

  private static Color bombColor(
      final GameData paintData, final MapData mapData, final Territory zone) {
    final AirZoneAttachment a = AirZoneAttachment.get(zone).orElseThrow();
    final GamePlayer bombed = a.getBombedPlayer();
    for (final GamePlayer p : a.getPlayers()) {
      if (bombed == null || paintData.getRelationshipTracker().isAtWar(p, bombed)) {
        return mapData.getPlayerColor(p.getName());
      }
    }
    return Color.RED;
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

  /** The covered territory whose centre is closest to all the others (across the date line). */
  private static Point medoid(
      final List<Territory> covered, final MapData mapData, final int width) {
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
        // the strategic targets: the bombed nation's factory and APC territories
        final List<Territory> land = new ArrayList<>();
        for (final Territory t : d.getMap().getTerritoriesOwnedBy(a.getBombedPlayer())) {
          if (!t.isWater()
              && t.anyUnitsMatch(
                  Matches.unitCanProduceUnits().or(u -> ApcAttachment.isApc(u.getType())))) {
            land.add(t);
          }
        }
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
    if (t.isWater()
        || !t.anyUnitsMatch(
            Matches.unitCanProduceUnits().or(u -> ApcAttachment.isApc(u.getType())))) {
      return result;
    }
    for (final Territory zone : AirZoneAttachment.getAirZones(t.getData().getMap())) {
      final AirZoneAttachment a = AirZoneAttachment.get(zone).orElseThrow();
      if (a.getZoneType() == ZoneType.BOMB && t.getOwner().equals(a.getBombedPlayer())) {
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
