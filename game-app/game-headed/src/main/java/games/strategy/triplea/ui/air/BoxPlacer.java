package games.strategy.triplea.ui.air;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.Territory;
import games.strategy.triplea.attachments.AirZoneAttachment;
import games.strategy.triplea.ui.mapdata.MapData;
import games.strategy.triplea.util.UnitSeparator;
import java.awt.Point;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.prefs.Preferences;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;

/**
 * Flames and Steel (request 9 item 4, request 20): where each air front's box sits on the air map.
 * A box goes to the spot nearest its front's centre, over a sea zone in or next to the front, that
 * covers no land (nor a land border), unit stack, territory name, strip box or other box, so it
 * only ever sits over open sea; with no such spot it goes to its front's own place in the strip
 * under the map. A box the player dragged stays where it was left (remembered between games on this
 * computer).
 */
@Slf4j
final class BoxPlacer {
  private static final int STEP = 20;
  private static final int MAX_RADIUS = 1200;
  private static final String PREFS_NODE = "flames_and_steel/airBoxSpots";

  private final Map<Territory, Point> auto = new HashMap<>();
  private final Map<String, Point> user = new HashMap<>();
  private final Map<Territory, Rectangle> boxes = new HashMap<>();
  // request 20: fronts with no clear sea near them; their boxes dock at the edge of the view
  private final java.util.Set<Territory> docked = new java.util.HashSet<>();
  private List<Rectangle> keepClear = List.of();
  private List<Rectangle> strip = List.of();

  BoxPlacer() {
    try {
      final Preferences prefs = Preferences.userRoot().node(PREFS_NODE);
      for (final String key : prefs.keys()) {
        final String[] xy = prefs.get(key, "").split(",");
        if (xy.length == 2) {
          user.put(key, new Point(Integer.parseInt(xy[0]), Integer.parseInt(xy[1])));
        }
      }
    } catch (final Exception e) {
      log.debug("No saved air box positions", e);
    }
  }

  /** What boxes must not cover: unit stacks and territory names. Call when the map changes. */
  void refresh(final GameData d, final MapData mapData, final int unitWidth, final int unitHeight) {
    final List<Rectangle> clear = new ArrayList<>();
    final List<Rectangle> boxStrip = new ArrayList<>();
    for (final Territory t : d.getMap().getTerritories()) {
      if (AirZoneAttachment.isAirZone(t)) {
        boxStrip.add(mapData.getBoundingRect(t));
        continue;
      }
      if (!t.getUnits().isEmpty()) {
        final int stacks = UnitSeparator.categorize(t.getUnits()).size();
        final List<Point> places = mapData.getPlacementPoints(t);
        for (int i = 0; i < Math.min(stacks, places.size()); i++) {
          final Point p = places.get(i);
          clear.add(new Rectangle(p.x, p.y, unitWidth, unitHeight + 6));
        }
      }
      mapData
          .getNamePlacementPoint(t)
          .or(() -> Optional.ofNullable(mapData.getCenter(t)))
          .ifPresent(p -> clear.add(new Rectangle(p.x - 45, p.y - 14, 90, 20)));
    }
    keepClear = clear;
    strip = boxStrip;
    auto.clear();
    docked.clear();
    boxes.clear();
  }

  /** The centre of the zone's box, given the box size and its front's centre. */
  Point centreFor(
      final Territory zone,
      final Point anchor,
      final int width,
      final int height,
      final MapData mapData,
      final GameData d) {
    final Point user = this.user.get(zone.getName());
    final Point centre =
        user != null
            ? user
            : auto.computeIfAbsent(zone, z -> findSpot(z, anchor, width, height, mapData, d));
    boxes.put(zone, box(centre, width, height));
    return centre;
  }

  private Point findSpot(
      final Territory zone,
      final Point anchor,
      final int width,
      final int height,
      final MapData mapData,
      final GameData d) {
    final Rectangle area =
        new Rectangle(
            anchor.x - MAX_RADIUS - width,
            anchor.y - MAX_RADIUS - height,
            2 * (MAX_RADIUS + width),
            2 * (MAX_RADIUS + height));
    final List<Rectangle> near = keepClear.stream().filter(area::intersects).toList();
    final java.awt.Dimension map = mapData.getMapDimensions();
    landMask(mapData, d);
    final List<Polygon> home = homeSeas(zone, mapData, d);
    // request 20: the nearest spot (searching outward from the front's centre) that covers no land,
    // border, unit stack, territory name, strip box or other box; the box's tags above it and lines
    // below it count as part of it
    for (int radius = 0; radius <= MAX_RADIUS; radius += STEP) {
      final int count = radius == 0 ? 1 : Math.max(8, (int) (2 * Math.PI * radius / STEP));
      for (int i = 0; i < count; i++) {
        final double angle = 2 * Math.PI * i / count;
        final Point c =
            new Point(
                anchor.x + (int) Math.round(radius * Math.cos(angle)),
                anchor.y + (int) Math.round(radius * Math.sin(angle)));
        final Rectangle r = box(c, width, height);
        final Rectangle padded =
            new Rectangle(r.x - 2, r.y - TAG_ROOM, r.width + 4, r.height + TAG_ROOM + 6);
        if (home.stream().noneMatch(p -> p.contains(c))
            || padded.x < 0
            || padded.y < 0
            || padded.x + padded.width > map.width
            || padded.y + padded.height > map.height
            || coversLand(padded)
            || near.stream().anyMatch(padded::intersects)
            || strip.stream().anyMatch(padded::intersects)
            || boxes.entrySet().stream()
                .anyMatch(o -> !o.getKey().equals(zone) && padded.intersects(o.getValue()))) {
          continue;
        }
        return c;
      }
    }
    // nowhere clear near the front: docked at the edge of the view (its strip slot as a spot)
    docked.add(zone);
    final @Nullable Rectangle own = mapData.getBoundingRect(zone);
    return own == null ? anchor : new Point((int) own.getCenterX(), (int) own.getCenterY());
  }

  /**
   * The shapes of the sea zones a front's box may sit on: those the front covers and those next to
   * a territory it covers (request 20: a box stays inside or next to its front). Bombing zones use
   * the seas next to their home region.
   */
  private static List<Polygon> homeSeas(
      final Territory zone, final MapData mapData, final GameData d) {
    final java.util.Set<Territory> seas = new java.util.HashSet<>();
    for (final Territory t : AirZoneAttachment.get(zone).orElseThrow().getCoveredTerritories()) {
      if (t.isWater()) {
        seas.add(t);
      }
      d.getMap().getNeighbors(t).stream()
          .filter(n -> n.isWater() && !AirZoneAttachment.isAirZone(n))
          .forEach(seas::add);
    }
    final List<Polygon> result = new ArrayList<>();
    seas.forEach(s -> result.addAll(mapData.getPolygons(s)));
    return result;
  }

  // room above a box for its "Dogfight" and status tags
  private static final int TAG_ROOM = 40;
  // the land mask: 1 cell per MASK_SCALE map pixels, as a summed-area table
  private static final int MASK_SCALE = 4;
  private int[] landSum;
  private int maskWidth;
  private int maskHeight;

  /** Draws every land territory (with its border) once into a small mask. */
  private void landMask(final MapData mapData, final GameData d) {
    if (landSum != null) {
      return;
    }
    final java.awt.Dimension map = mapData.getMapDimensions();
    maskWidth = map.width / MASK_SCALE + 1;
    maskHeight = map.height / MASK_SCALE + 1;
    final java.awt.image.BufferedImage img =
        new java.awt.image.BufferedImage(
            maskWidth, maskHeight, java.awt.image.BufferedImage.TYPE_BYTE_GRAY);
    final java.awt.Graphics2D g = img.createGraphics();
    g.scale(1.0 / MASK_SCALE, 1.0 / MASK_SCALE);
    g.setColor(java.awt.Color.WHITE);
    g.setStroke(new java.awt.BasicStroke(MASK_SCALE * 2f));
    for (final Territory t : d.getMap().getTerritories()) {
      if (t.isWater() || AirZoneAttachment.isAirZone(t)) {
        continue;
      }
      for (final Polygon p : mapData.getPolygons(t)) {
        g.fill(p);
        g.draw(p);
      }
    }
    g.dispose();
    final java.awt.image.Raster raster = img.getRaster();
    final int[] sum = new int[(maskWidth + 1) * (maskHeight + 1)];
    for (int y = 0; y < maskHeight; y++) {
      int row = 0;
      for (int x = 0; x < maskWidth; x++) {
        row += raster.getSample(x, y, 0) > 0 ? 1 : 0;
        sum[(y + 1) * (maskWidth + 1) + x + 1] = sum[y * (maskWidth + 1) + x + 1] + row;
      }
    }
    landSum = sum;
  }

  /** Whether any land (or a land border) lies inside the rectangle (map pixels). */
  boolean coversLand(final Rectangle r) {
    final int x0 = Math.max(0, Math.floorDiv(r.x, MASK_SCALE));
    final int y0 = Math.max(0, Math.floorDiv(r.y, MASK_SCALE));
    final int x1 = Math.min(maskWidth, Math.floorDiv(r.x + r.width, MASK_SCALE) + 1);
    final int y1 = Math.min(maskHeight, Math.floorDiv(r.y + r.height, MASK_SCALE) + 1);
    if (x0 >= x1 || y0 >= y1) {
      return false;
    }
    final int w = maskWidth + 1;
    return landSum[y1 * w + x1] - landSum[y0 * w + x1] - landSum[y1 * w + x0] + landSum[y0 * w + x0]
        > 0;
  }

  static Rectangle box(final Point centre, final int width, final int height) {
    return new Rectangle(centre.x - width / 2, centre.y - height / 2, width, height);
  }

  /** Whether the front's box docks at the edge of the view (no clear sea near it, not dragged). */
  boolean isDocked(final Territory zone) {
    return !user.containsKey(zone.getName()) && docked.contains(zone);
  }

  /** The player dragged the box here. */
  void moveTo(final Territory zone, final Point centre) {
    user.put(zone.getName(), new Point(centre));
  }

  /** Back to the automatic spot (double-click on the box's title). */
  void reset(final Territory zone) {
    user.remove(zone.getName());
    auto.remove(zone);
    docked.remove(zone);
    save(zone.getName(), null);
  }

  /** Remembers a dragged box's spot for later games. */
  void remember(final Territory zone) {
    save(zone.getName(), user.get(zone.getName()));
  }

  private static void save(final String key, final @Nullable Point p) {
    try {
      final Preferences prefs = Preferences.userRoot().node(PREFS_NODE);
      if (p == null) {
        prefs.remove(key);
      } else {
        prefs.put(key, p.x + "," + p.y);
      }
      prefs.flush();
    } catch (final Exception e) {
      log.debug("Could not save the air box position", e);
    }
  }

  /** Whether any of the territory's shapes reaches into the rectangle. */
  static boolean covers(final Rectangle box, final Territory t, final MapData mapData) {
    for (final Polygon p : mapData.getPolygons(t)) {
      if (p.intersects(box)) {
        return true;
      }
    }
    return false;
  }
}
