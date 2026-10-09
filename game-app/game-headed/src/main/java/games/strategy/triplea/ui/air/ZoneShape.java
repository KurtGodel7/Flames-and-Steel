package games.strategy.triplea.ui.air;

import games.strategy.engine.data.Territory;
import games.strategy.triplea.ui.mapdata.MapData;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Flames and Steel: the shape of a group of territories (an air zone's coverage). Filled territory
 * by territory; its border is the edges that only one territory of the group has, so the borders
 * between its own territories disappear. Neighbouring territories on the map share their corner
 * points, which this relies on.
 */
final class ZoneShape {
  private final List<Polygon> polygons;
  private final Path2D outline;

  private ZoneShape(final List<Polygon> polygons, final Path2D outline) {
    this.polygons = polygons;
    this.outline = outline;
  }

  static ZoneShape of(final List<Territory> territories, final MapData mapData) {
    final List<Polygon> polygons = new ArrayList<>();
    for (final Territory t : territories) {
      polygons.addAll(mapData.getPolygons(t));
    }
    // count each edge, in either direction
    final Map<Long, Integer> count = new HashMap<>();
    for (final Polygon p : polygons) {
      for (int i = 0; i < p.npoints; i++) {
        final int j = (i + 1) % p.npoints;
        count.merge(
            edgeKey(p.xpoints[i], p.ypoints[i], p.xpoints[j], p.ypoints[j]), 1, Integer::sum);
      }
    }
    final Path2D outline = new Path2D.Float();
    for (final Polygon p : polygons) {
      boolean drawing = false;
      for (int i = 0; i < p.npoints; i++) {
        final int j = (i + 1) % p.npoints;
        final boolean border =
            count.get(edgeKey(p.xpoints[i], p.ypoints[i], p.xpoints[j], p.ypoints[j])) == 1;
        if (border) {
          if (!drawing) {
            outline.moveTo(p.xpoints[i], p.ypoints[i]);
            drawing = true;
          }
          outline.lineTo(p.xpoints[j], p.ypoints[j]);
        } else {
          drawing = false;
        }
      }
    }
    return new ZoneShape(List.copyOf(polygons), outline);
  }

  private static long edgeKey(final int x1, final int y1, final int x2, final int y2) {
    final long a = ((long) x1 << 16) | (y1 & 0xFFFF);
    final long b = ((long) x2 << 16) | (y2 & 0xFFFF);
    return a < b ? (a << 32) ^ b : (b << 32) ^ a;
  }

  /** Fills every territory (they do not overlap). */
  void fill(final Graphics2D g) {
    for (final Polygon p : polygons) {
      g.fillPolygon(p);
    }
  }

  /** Draws the outer border with the graphics' current colour and stroke. */
  void draw(final Graphics2D g) {
    g.draw(outline);
  }
}
