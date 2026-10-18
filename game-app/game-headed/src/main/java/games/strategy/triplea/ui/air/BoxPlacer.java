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
 * Flames and Steel (request 9 item 4): where each air front's box sits on the air map. A box is
 * placed near its front's centre but clear of unit stacks, territory names, the air box strip and
 * other boxes, preferring sea; a box the player dragged stays where it was left (remembered between
 * games on this computer).
 */
@Slf4j
final class BoxPlacer {
  private static final int STEP = 20;
  private static final int MAX_RADIUS = 600;
  private static final String PREFS_NODE = "flames_and_steel/airBoxSpots";

  private final Map<Territory, Point> auto = new HashMap<>();
  private final Map<String, Point> user = new HashMap<>();
  private final Map<Territory, Rectangle> boxes = new HashMap<>();
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
    final int mapHeight = mapData.getMapDimensions().height;
    Point best = anchor;
    double bestCost = Double.MAX_VALUE;
    for (int radius = 0; radius <= MAX_RADIUS; radius += STEP) {
      final int count = radius == 0 ? 1 : Math.max(8, (int) (2 * Math.PI * radius / STEP));
      for (int i = 0; i < count; i++) {
        final double angle = 2 * Math.PI * i / count;
        final Point c =
            new Point(
                anchor.x + (int) Math.round(radius * Math.cos(angle)),
                anchor.y + (int) Math.round(radius * Math.sin(angle)));
        final Rectangle r = box(c, width, height);
        if (r.y < 0 || r.y + r.height > mapHeight) {
          continue;
        }
        double cost = radius * 2.0;
        for (final Rectangle k : near) {
          cost += overlap(r, k);
        }
        for (final Rectangle s : strip) {
          cost += 50.0 * overlap(r, s);
        }
        for (final Map.Entry<Territory, Rectangle> other : boxes.entrySet()) {
          if (!other.getKey().equals(zone)) {
            cost += 8.0 * overlap(r, other.getValue());
          }
        }
        if (onLand(c, mapData, d)) {
          cost += width * height / 4.0;
        }
        if (cost < bestCost) {
          bestCost = cost;
          best = c;
        }
      }
    }
    return best;
  }

  private static boolean onLand(final Point p, final MapData mapData, final GameData d) {
    final @Nullable String name = mapData.getTerritoryAt(p.x, p.y);
    if (name == null) {
      return false;
    }
    final Territory t = d.getMap().getTerritoryOrNull(name);
    return t != null && !t.isWater();
  }

  private static double overlap(final Rectangle a, final Rectangle b) {
    final Rectangle i = a.intersection(b);
    return i.isEmpty() ? 0 : (double) i.width * i.height;
  }

  static Rectangle box(final Point centre, final int width, final int height) {
    return new Rectangle(centre.x - width / 2, centre.y - height / 2, width, height);
  }

  /** The player dragged the box here. */
  void moveTo(final Territory zone, final Point centre) {
    user.put(zone.getName(), new Point(centre));
  }

  /** Back to the automatic spot (double-click on the box's title). */
  void reset(final Territory zone) {
    user.remove(zone.getName());
    auto.remove(zone);
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
